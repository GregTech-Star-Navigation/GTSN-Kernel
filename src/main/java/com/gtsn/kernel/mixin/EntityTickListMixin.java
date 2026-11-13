package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 实体 tick（{@code EntityTickList.forEach}）—— 通常是最重的一段（AI、寻路、物理、流体）。
 *
 * <p>原版在这里是内联循环，没有逐实体的方法边界；本阶段只给整段计时。要拆到单实体级别，
 * 需要注入到 {@code Entity.tick} 自身（会与所有优化 mod 抢注入点），留到三期再说。</p>
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin {

    @Inject(method = "forEach", at = @At("HEAD"))
    private void gtsnkernel$entitiesStart(Consumer<Entity> consumer, CallbackInfo ci) {
        StageProfiler.begin(TickStage.ENTITIES);
    }

    @Inject(method = "forEach", at = @At("RETURN"))
    private void gtsnkernel$entitiesEnd(Consumer<Entity> consumer, CallbackInfo ci) {
        StageProfiler.end(TickStage.ENTITIES);
    }
}
