package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 给 {@code Level.getEntities} 单独计时（三期 P3 前置）。
 *
 * <p>原版这里已经走 {@code EntitySectionStorage} 的空间索引（按 16³ section 分桶），
 * 所以「换更快的索引」收益可能有限 —— 先量出它在实体 tick 里到底占多少，
 * 再决定值不值得动。</p>
 */
@Mixin(Level.class)
public abstract class LevelEntityQueryMixin {

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"))
    private void gtsnkernel$queryStart(Entity entity, AABB box, Predicate<? super Entity> predicate,
                                       CallbackInfoReturnable<List<Entity>> cir) {
        StageProfiler.begin(TickStage.ENTITY_QUERY);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"))
    private void gtsnkernel$queryEnd(Entity entity, AABB box, Predicate<? super Entity> predicate,
                                     CallbackInfoReturnable<List<Entity>> cir) {
        StageProfiler.end(TickStage.ENTITY_QUERY);
    }
}
