package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** {@code ServerLevel.tick} 总耗时（多个世界会累加到同一阶段）。 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$levelTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.begin(TickStage.LEVEL_TICK);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$levelTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.end(TickStage.LEVEL_TICK);
    }
}
