package com.gtsn.kernel.mixin;

import com.gtsn.kernel.bridge.TickRedirectHandler;
import com.gtsn.kernel.perf.CsvRecorder;
import com.gtsn.kernel.perf.PeriodicReporter;
import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    /**
     * 二期 2b：把 {@code tickChildren} 里对 {@code ServerLevel.tick} 的调用交给调度器。
     *
     * <p>开关关闭时 {@link TickRedirectHandler#dispatch} 直接调 {@code level.tick(...)}，
     * 与原版等价；开启时走 {@code SerialRegionScheduler}（串行，并行度=1）。</p>
     *
     * <p>这是本项目<b>第一个改变原版调用路径</b>的注入 —— 之前全是 HEAD/RETURN 打点。</p>
     */
    @Redirect(
            method = "tickChildren",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void gtsnkernel$redirectLevelTick(ServerLevel level, BooleanSupplier hasTimeLeft) {
        TickRedirectHandler.dispatch(level, hasTimeLeft);
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
