package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.CsvRecorder;
import com.gtsn.kernel.perf.PeriodicReporter;
import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * {@code MinecraftServer} 的顶层打点：整个 tick、tickChildren、自动保存。
 *
 * <p>三个注入点都是 HEAD/RETURN，不读参数、不改返回值、不取消 —— 对原版行为零影响。</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void gtsnkernel$tickServerStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.begin(TickStage.SERVER_TICK);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void gtsnkernel$tickServerEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.end(TickStage.SERVER_TICK);
        // 每 tick 明细只在显式开启时写盘（内部判断，关闭时开销为一次 null 检查）。
        // 顺序不能改：先读本 tick 累加值，再清零。
        CsvRecorder.tick();
        PeriodicReporter.tick();
        StageProfiler.endTick();
    }

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void gtsnkernel$tickChildrenStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.begin(TickStage.SERVER_CHILDREN);
    }

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void gtsnkernel$tickChildrenEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StageProfiler.end(TickStage.SERVER_CHILDREN);
    }

    @Inject(method = "saveEverything", at = @At("HEAD"))
    private void gtsnkernel$saveStart(boolean suppressLog, boolean flush, boolean forced,
                                      CallbackInfoReturnable<Boolean> cir) {
        StageProfiler.begin(TickStage.SAVE);
    }

    @Inject(method = "saveEverything", at = @At("RETURN"))
    private void gtsnkernel$saveEnd(boolean suppressLog, boolean flush, boolean forced,
                                    CallbackInfoReturnable<Boolean> cir) {
        StageProfiler.end(TickStage.SAVE);
    }
}
