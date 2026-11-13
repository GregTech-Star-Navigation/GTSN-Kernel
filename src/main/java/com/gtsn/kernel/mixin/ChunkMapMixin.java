package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** 区块管理：加载 / 卸载 / 实体追踪（{@code ChunkMap.tick}）。 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$chunkMapTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.begin(TickStage.CHUNK_MAP);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$chunkMapTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.end(TickStage.CHUNK_MAP);
    }
}
