package com.gtsn.kernel.mixin;

import com.gtsn.kernel.core.TickModules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 {@code ServerLevel.tick} 的各个阶段变成可单独关掉的模块（三期模块化）。
 *
 * <p><b>做法</b>：不重写 {@code tick} 方法，只把每个阶段的<b>调用点</b>换成「先问开关、
 * 再决定调不调」。骨架、顺序、局部变量全部保持原版 —— 关掉所有模块时，跑的还是原版代码路径，
 * 只是某些调用被跳过。</p>
 *
 * <p><b>为什么用 {@code @Redirect} 而不是 {@code @Inject}</b>：{@code @Inject} 只能前后插桩，
 * 没法「取消某一个具体调用」；{@code @Redirect} 正好是这个语义。</p>
 *
 * <p><b>风险</b>：{@code @Redirect} 因为要精确匹配字节码里的调用，比其他注入脆弱 ——
 * 换 MC 版本可能失效。但失败方式是「编译期/启动期报错」，不会静默错。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelModuleMixin {

    // protected 方法可以直接 @Shadow 成 abstract
    @org.spongepowered.asm.mixin.Shadow
    protected abstract void tickTime();


    // ── 世界边界 ───────────────────────────────────────────────────────────
    // 原版：profiler.push("world border"); this.getWorldBorder().tick();
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/border/WorldBorder;tick()V"))
    private void gtsnkernel$worldBorder(WorldBorder border) {
        if (TickModules.WORLD_BORDER.enabled()) {
            border.tick();
        }
    }

    // ── 天气周期 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;advanceWeatherCycle()V"))
    private void gtsnkernel$weather(ServerLevel level) {
        if (TickModules.WEATHER.enabled()) {
            ((ServerLevelInvoker) level).gtsnkernel$advanceWeatherCycle();
        }
    }

    // ── 天空亮度 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;updateSkyBrightness()V"))
    private void gtsnkernel$skyBrightness(ServerLevel level) {
        if (TickModules.SKY_BRIGHTNESS.enabled()) {
            level.updateSkyBrightness();
        }
    }

    // ── 世界时间 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickTime()V"))
    private void gtsnkernel$time(ServerLevel level) {
        if (TickModules.TIME.enabled()) {
            tickTime();
        }
    }

    // ── 袭击 ───────────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/raid/Raids;tick()V"))
    private void gtsnkernel$raids(net.minecraft.world.entity.raid.Raids raids) {
        if (TickModules.RAIDS.enabled()) {
            raids.tick();
        }
    }

    // ── 区块系统 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void gtsnkernel$chunkSource(net.minecraft.server.level.ServerChunkCache cache,
                                        java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks) {
        if (TickModules.CHUNK_SOURCE.enabled()) {
            cache.tick(hasTimeLeft, tickChunks);
        }
    }

    // ── 方块事件 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
    private void gtsnkernel$blockEvents(ServerLevel level) {
        if (TickModules.BLOCK_EVENTS.enabled()) {
            ((ServerLevelInvoker) level).gtsnkernel$runBlockEvents();
        }
    }

    // ── 末影龙战 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/dimension/end/EndDragonFight;tick()V"))
    private void gtsnkernel$dragonFight(net.minecraft.world.level.dimension.end.EndDragonFight fight) {
        if (TickModules.DRAGON_FIGHT.enabled()) {
            fight.tick();
        }
    }

    // ── 方块实体 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"))
    private void gtsnkernel$blockEntities(ServerLevel level) {
        if (TickModules.BLOCK_ENTITIES.enabled()) {
            ((LevelInvoker) (Object) level).gtsnkernel$tickBlockEntities();
        }
    }

    // ── 实体管理 ───────────────────────────────────────────────────────────
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"))
    private void gtsnkernel$entityManagement(
            net.minecraft.world.level.entity.PersistentEntitySectionManager<?> manager) {
        if (TickModules.ENTITY_MANAGEMENT.enabled()) {
            manager.tick();
        }
    }
}
