package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 方块实体 tick（{@code Level.tickBlockEntities}）—— 漏斗 / 熔炉 / 箱子 / 刷怪笼。 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void gtsnkernel$blockEntitiesStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.BLOCK_ENTITIES);
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void gtsnkernel$blockEntitiesEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.BLOCK_ENTITIES);
    }
}
