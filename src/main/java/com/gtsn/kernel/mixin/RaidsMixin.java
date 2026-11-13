package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 袭击波次（{@code Raids.tick}）。 */
@Mixin(Raids.class)
public abstract class RaidsMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$raidsStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.RAIDS);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$raidsEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.RAIDS);
    }
}
