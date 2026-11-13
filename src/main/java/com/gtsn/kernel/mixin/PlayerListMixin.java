package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家列表（{@code PlayerList.tick}）。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$playersStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.PLAYERS);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$playersEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.PLAYERS);
    }
}
