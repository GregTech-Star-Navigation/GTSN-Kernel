package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * 方块 / 流体调度 tick（{@code LevelTicks.tick}）。
 *
 * <p>方块与流体各有一个 {@code LevelTicks} 实例，同一 tick 内会调用两次，耗时累加到
 * 同一阶段。要分开统计需在 {@code ServerLevel.tick} 的调用点按 ordinal 注入 —— 留到有
 * 明确需求时再做。</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$levelTicksStart(long gameTime, int maxAllowed,
                                            BiConsumer<BlockPos, ?> ticker, CallbackInfo ci) {
        StageProfiler.begin(TickStage.LEVEL_TICKS);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$levelTicksEnd(long gameTime, int maxAllowed,
                                          BiConsumer<BlockPos, ?> ticker, CallbackInfo ci) {
        StageProfiler.end(TickStage.LEVEL_TICKS);
    }
}
