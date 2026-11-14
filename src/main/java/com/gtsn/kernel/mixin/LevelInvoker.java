package com.gtsn.kernel.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@code Level.tickBlockEntities()}（三期模块化）。
 *
 * <p>它定义在 {@code Level} 而非 {@code ServerLevel} 上，所以不能在
 * {@code ServerLevelModuleMixin} 里 {@code @Shadow} —— {@code @Shadow} 只找目标类自己声明的方法，
 * 不找继承来的（实测报 "was not located in the target class ServerLevel"）。</p>
 */
@Mixin(Level.class)
public interface LevelInvoker {

    @Invoker("tickBlockEntities")
    void gtsnkernel$tickBlockEntities();
}
