package com.gtsn.kernel.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把 {@code ChunkMap.getVisibleChunkIfPresent(long)} 暴露出来（三期 P2b）。
 *
 * <p>它是 protected，但它是<b>唯一一个「非阻塞」的区块查询</b>：只查一个
 * {@code ConcurrentHashMap}，不转交主线程、不阻塞、不需要锁。</p>
 *
 * <p>region 线程读自己 region 里的邻居区块就走这个方法 —— 走原版 {@code getChunk} 会
 * {@code supplyAsync(..., mainThreadProcessor).join()} 死锁。</p>
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder gtsnkernel$getVisibleChunkIfPresent(long chunkPos);
}
