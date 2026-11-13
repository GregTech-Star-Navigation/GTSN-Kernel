package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 区块系统：{@code ServerChunkCache.tick}。
 *
 * <p>这一段包含三件事（只有「生成」将来会划给地形 mod）：ticket 调度、区块 tick（随机 tick /
 * 刷怪）、{@code ChunkMap.tick}（加载卸载 / 实体追踪）。{@link ChunkMapMixin} 会把
 * {@code ChunkMap.tick} 单独计时，两者相减即可得到「除区块管理外的部分」。</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$chunkCacheTickStart(BooleanSupplier hasTimeLeft, boolean tickChunks,
                                               CallbackInfo ci) {
        StageProfiler.begin(TickStage.CHUNK_CACHE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$chunkCacheTickEnd(BooleanSupplier hasTimeLeft, boolean tickChunks,
                                              CallbackInfo ci) {
        StageProfiler.end(TickStage.CHUNK_CACHE);
    }
}
