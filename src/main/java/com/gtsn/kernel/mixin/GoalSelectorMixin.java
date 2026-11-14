package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 {@code GoalSelector.tick()} 单独计时（三期 P3 前置）。
 *
 * <p>为什么需要：{@code ENTITIES} 阶段占主线程 54.3%（3.709ms），但里面混着 AI 判定、
 * 物理、移动、伤害一堆东西。要决定「两阶段拆分值不值」，先得知道 AI 判定本身占多少。</p>
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$goalTickStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.ENTITY_AI);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$goalTickEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.ENTITY_AI);
    }
}
