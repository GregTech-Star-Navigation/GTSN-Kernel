package com.gtsn.kernel.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * {@code Level.tickBlockEntities()} 里那个 tick 循环的并行版本（三期实验）。
 *
 * <p><b>只并行那一个循环</b>，方法前后的 pending 列表合并、{@code onLoad}、{@code
 * tickingBlockEntities} 开关全部留在调用线程 —— 那些操作改的是同一批 ArrayList，并行没有
 * 好处只有风险。</p>
 *
 * <p><b>分组策略</b>：按方块实体坐标算 region（{@code chunkX >> shift}），同一 region 的
 * ticker 一定落在同一 lane、按原列表顺序执行。这样 region 内的顺序与原版完全一致，
 * 变的只是「不同 region 之间」的先后。</p>
 *
 * <p><b>已知风险（这就是为什么要一致性测试）</b>：方块实体的 tick 可能碰隔壁区块 ——
 * 漏斗把物品推进相邻的箱子就是典型。如果两个相邻区块被分到不同 region 且不同 lane，
 * 就会真的并发访问同一份容器。{@code tools/consistency-check.sh} 用漏斗场景专门测这个。</p>
 */
public final class ParallelBlockEntityTick {

    private ParallelBlockEntityTick() {
    }

    /**
     * 并行跑 tick 循环。
     *
     * @param tickers     待处理的 ticker（<b>调用方已保证</b>不再是正在被原版迭代的那个列表）
     * @param laneOf      region → lane 的映射（{@code parallelism} 取模之后的值）
     * @param parallelism lane 数量
     * @param regionShift 区块坐标右移多少位算一个 region（4 = 16×16 区块）
     * @param pools       lane 对应的单线程执行器
     * @param shouldTick  原版 {@code shouldTickBlocksAt} 的绑定（{@code level::shouldTickBlocksAt}）
     * @return 新的 ticker 列表（已剔除 {@code isRemoved()} 的），供调用方写回
     */
    public static List<TickingBlockEntity> tick(
            List<TickingBlockEntity> tickers,
            int parallelism,
            int regionShift,
            ExecutorService[] pools,
            Predicate<BlockPos> shouldTick) {

        // ---- 第一步：单线程分组（顺带剔除已移除的 ticker，与原版一致）----
        List<List<TickingBlockEntity>> groups = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            groups.add(new ArrayList<>());
        }
        List<TickingBlockEntity> survivors = new ArrayList<>(tickers.size());
        for (TickingBlockEntity ticker : tickers) {
            if (ticker.isRemoved()) {
                // 原版这里是 iterator.remove()；调用方拿 survivors 覆盖原列表即可。
                continue;
            }
            survivors.add(ticker);
            BlockPos pos = ticker.getPos();
            int regionX = pos.getX() >> 4 >> regionShift;
            int regionZ = pos.getZ() >> 4 >> regionShift;
            int lane = Math.floorMod(regionX * 31 + regionZ, parallelism);
            groups.get(lane).add(ticker);
        }

        // ---- 第二步：并行 tick 各 lane ----
        if (parallelism == 1) {
            // 退化路径：不切线程，保持与原版一模一样的调用栈（调试时好排查）。
            for (TickingBlockEntity ticker : groups.get(0)) {
                if (shouldTick.test(ticker.getPos())) {
                    ticker.tick();
                }
            }
            return survivors;
        }

        CompletableFuture<?>[] futures = new CompletableFuture<?>[parallelism];
        for (int lane = 0; lane < parallelism; lane++) {
            final List<TickingBlockEntity> group = groups.get(lane);
            if (group.isEmpty()) {
                futures[lane] = CompletableFuture.completedFuture(null);
                continue;
            }
            futures[lane] = CompletableFuture.runAsync(() -> {
                for (TickingBlockEntity ticker : group) {
                    // shouldTick 依赖 level 的 chunk 状态，必须在工作线程上重新判一次
                    // （原版是在迭代时判的，那时区块状态已经是最新的）。
                    if (shouldTick.test(ticker.getPos())) {
                        ticker.tick();
                    }
                }
            }, pools[lane]);
        }
        CompletableFuture.allOf(futures).join();
        return survivors;
    }
}
