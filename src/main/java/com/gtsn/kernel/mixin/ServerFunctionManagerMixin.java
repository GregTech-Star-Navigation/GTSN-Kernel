package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 数据包 tick 函数（{@code ServerFunctionManager.tick}）。 */
@Mixin(ServerFunctionManager.class)
public abstract class ServerFunctionManagerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$functionsStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.FUNCTIONS);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$functionsEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.FUNCTIONS);
    }
}
