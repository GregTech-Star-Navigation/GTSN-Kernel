package com.gtsn.kernel.mixin;

import com.gtsn.kernel.bridge.ParallelBlockEntityTick;
import com.gtsn.kernel.bridge.RegionTickContext;
import com.gtsn.kernel.core.RegionId;
import com.gtsn.kernel.core.RegionThreadPool;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 三期实验：把 {@code tickBlockEntities} 的 tick 循环按 region 并行（{@code -PparallelBlocks} 开启）。
 *
 * <p><b>只替换 tick 循环</b>。方法前后的 pending 列表合并、{@code onLoad}、
 * {@code tickingBlockEntities} 开关全部按原样重放 —— 这些都在同一批 ArrayList 上操作，
 * 并行没有好处。</p>
 *
 * <p><b>为什么用 {@code @Inject(cancellable)} 而不是 {@code @Redirect}</b>：要并行就必须
 * 自己掌控那段循环（原版是 {@code Iterator} 逐个处理，没法把「批量」塞进一次调用）。
 * 代价是这段逻辑是「复制原版实现」—— 所以它必须是<b>可关闭的</b>，关闭时完全走原方法。</p>
 *
 * <h2>⚠ 实测结论：这条路会死锁，不要开</h2>
 *
 * <p>2026-11-14 实测（{@code tools/consistency-check.sh} 的 parallel 场景，289 个漏斗）：
 * 服务端启动后第一 tick 就卡死，RCON 完全无响应，只能强杀。</p>
 *
 * <p><b>根因</b>：方块实体的 tick 里有 {@code level.getChunk(...)} 之类的调用，而
 * {@code ServerChunkCache.getChunk} 在<b>非主线程</b>会
 * {@code supplyAsync(..., mainThreadProcessor).join()} —— 把活转交主线程并等它。
 * 此刻主线程正卡在 {@code CompletableFuture.allOf(...).join()} 等 lane 线程。
 * 两边互等，{@code mainThreadProcessor} 的队列永远得不到执行。</p>
 *
 * <p>这不是实现 bug，是<b>原版线程模型的硬约束</b>：整个 {@code ServerChunkCache} 假定
 * 只有一个线程在 tick。要真正并行就必须让每个 region 拥有自己的区块存储与调度队列
 * （Folia 的做法，其 README 原话是「region 之间并行但不并发：它们不共享数据」），
 * 那是重写而非并行化。</p>
 *
 * <p>保留这段代码是因为它<b>记录了一条死路</b>，以及它需要的基础设施
 * （{@link RegionThreadPool}、按 lane 分组）在四期「只并行真正无依赖的阶段」时还能用。</p>
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityParallelMixin {

    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    @Shadow
    @Final
    protected List<TickingBlockEntity> pendingBlockEntityTickers;

    // 类型必须与目标字段**完全一致**：Level 里这两个是 ArrayList，不是 List。
    // 写成 List 会让 @Shadow 定位失败，报 "field xx was not located in the target class"。
    @Shadow
    @Final
    private ArrayList<BlockEntity> freshBlockEntities;

    @Shadow
    @Final
    private ArrayList<BlockEntity> pendingFreshBlockEntities;

    @Shadow
    protected boolean tickingBlockEntities;

    @Shadow
    protected abstract boolean shouldTickBlocksAt(net.minecraft.core.BlockPos pos);

    @Unique
    private static final boolean GTSNKERNEL_PARALLEL_BLOCKS =
            Boolean.getBoolean("gtsnkernel.blocks.parallel");

    @Unique
    private static final int GTSNKERNEL_PARALLELISM =
            Integer.getInteger("gtsnkernel.blocks.parallelism", 2);

    @Unique
    private static final int GTSNKERNEL_REGION_SHIFT =
            Integer.getInteger("gtsnkernel.blocks.regionShift", 4);

    @Unique
    private static volatile RegionThreadPool gtsnkernel$pool;

    @Unique
    private static RegionThreadPool gtsnkernel$pool() {
        RegionThreadPool pool = gtsnkernel$pool;
        if (pool == null) {
            synchronized (LevelBlockEntityParallelMixin.class) {
                pool = gtsnkernel$pool;
                if (pool == null) {
                    pool = new RegionThreadPool(GTSNKERNEL_PARALLELISM);
                    gtsnkernel$pool = pool;
                }
            }
        }
        return pool;
    }

    /**
     * 开关关闭时直接返回，原方法照常执行。
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void gtsnkernel$parallelBlockEntities(CallbackInfo ci) {
        if (!GTSNKERNEL_PARALLEL_BLOCKS) {
            return;
        }
        Level level = (Level) (Object) this;
        var profiler = level.getProfiler();
        profiler.push("blockEntities");
        try {
            // ---- 以下按原版顺序重放，只有最后的 tick 循环换成并行 ----
            if (!this.pendingFreshBlockEntities.isEmpty()) {
                this.freshBlockEntities.addAll(this.pendingFreshBlockEntities);
                this.pendingFreshBlockEntities.clear();
            }
            this.tickingBlockEntities = true;
            if (!this.freshBlockEntities.isEmpty()) {
                this.freshBlockEntities.forEach(BlockEntity::onLoad);
                this.freshBlockEntities.clear();
            }
            if (!this.pendingBlockEntityTickers.isEmpty()) {
                this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
                this.pendingBlockEntityTickers.clear();
            }

            RegionThreadPool pool = gtsnkernel$pool();
            List<TickingBlockEntity> survivors = ParallelBlockEntityTick.tick(
                    this.blockEntityTickers,
                    pool.parallelism(),
                    GTSNKERNEL_REGION_SHIFT,
                    pool.lanes(),
                    this::shouldTickBlocksAt);

            // 写回：原版是 iterator.remove() 就地剔除，这里整体替换。
            // 注意 tickingBlockEntities 仍为 true —— 期间新增的 ticker 会进 pending 列表，
            // 下一 tick 才合并，与原版一致。
            if (survivors.size() != this.blockEntityTickers.size()) {
                this.blockEntityTickers.clear();
                this.blockEntityTickers.addAll(survivors);
            }
        } finally {
            this.tickingBlockEntities = false;
            profiler.pop();
        }
        ci.cancel();
    }
}
