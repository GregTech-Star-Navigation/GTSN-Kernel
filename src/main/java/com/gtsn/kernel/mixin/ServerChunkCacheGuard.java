package com.gtsn.kernel.mixin;

import com.gtsn.kernel.bridge.RegionTickContext;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 三期 P2a：拦住 region 线程对 {@code getChunk} 的阻塞访问。
 *
 * <p><b>不加这个会怎样</b>：`ServerChunkCache.getChunk` 在非主线程会
 * `supplyAsync(..., mainThreadProcessor).join()` 把活转交主线程；主线程此刻正
 * `allOf().join()` 等 region 线程 —— 两边互等，整服卡死（2026-11-14 实测过）。</p>
 *
 * <p><b>加了这个之后</b>：region 线程调用直接返回 {@code null} 并记一次违规。
 * 调用方多半会 NPE，但那个 NPE 被 {@code ParallelBlockEntityTick} 里每个 ticker 的
 * try-catch 接住，只让<b>这一个 ticker 本 tick 不 tick</b>，其余照常。
 *
 * <p>这不是最终形态，是<b>量尺</b>：跑一遍就知道哪些方块实体真的依赖跨区块访问。
 * 统计里 {@code getChunk} 违规为 0 的方块实体类型，就是 P2a 能安全并行的集合。</p>
 *
 * <p>主线程完全不受影响 —— {@link RegionTickContext#inRegionThread()} 在那边恒为 false。</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGuard {

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void gtsnkernel$guardGetChunk(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
                                          CallbackInfoReturnable<ChunkAccess> cir) {
        if (!RegionTickContext.inRegionThread()) {
            return;
        }

        // ── P2b：先试「非阻塞快路径」──
        // 原版 getChunk 在非主线程会 supplyAsync(..., mainThreadProcessor).join() 转交主线程，
        // 主线程此刻正在等我们 → 死锁。但「区块已加载」这件事本身是可以在工作线程读的
        // （getVisibleChunkIfPresent 只查一个 ConcurrentHashMap，不阻塞）。
        //
        // 所以：已加载的区块正常返回，未加载的才记账并返回 null。
        // 这一步让 region 线程能读自己 region 里的邻居 —— 漏斗查隔壁箱子就是这条路。
        ChunkHolder holder = ((ChunkMapAccessor) (Object) this.chunkMap)
                .gtsnkernel$getVisibleChunkIfPresent(
                        net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
        if (holder != null) {
            LevelChunk chunk = requiredStatus == ChunkStatus.FULL
                    ? holder.getTickingChunk()
                    : holder.getFullChunk();
            if (chunk != null) {
                RegionTickContext.recordFastPath();
                cir.setReturnValue(chunk);
                return;
            }
        }

        RegionTickContext.recordViolation("getChunk-miss",
                Thread.currentThread().getName() + " @" + chunkX + "," + chunkZ + " status=" + requiredStatus);
        cir.setReturnValue(null);
    }
}
