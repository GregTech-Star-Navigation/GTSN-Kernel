package com.gtsn.kernel.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把 {@code ServerLevel} 的私有方法暴露给模块 mixin（三期模块化）。
 *
 * <p>只暴露「确实需要按开关跳过」的那两个 private 方法。protected 的走 {@code @Shadow}
 * 就够了 —— {@code @Invoker} 只对 private 必要。</p>
 */
@Mixin(ServerLevel.class)
public interface ServerLevelInvoker {

    @Invoker("advanceWeatherCycle")
    void gtsnkernel$advanceWeatherCycle();

    @Invoker("runBlockEvents")
    void gtsnkernel$runBlockEvents();
}
