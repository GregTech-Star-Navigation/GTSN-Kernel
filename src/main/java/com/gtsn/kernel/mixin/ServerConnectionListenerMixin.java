package com.gtsn.kernel.mixin;

import com.gtsn.kernel.perf.StageProfiler;
import com.gtsn.kernel.perf.TickStage;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 网络连接收发（{@code ServerConnectionListener.tick}）。 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void gtsnkernel$connectionsStart(CallbackInfo ci) {
        StageProfiler.begin(TickStage.CONNECTIONS);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gtsnkernel$connectionsEnd(CallbackInfo ci) {
        StageProfiler.end(TickStage.CONNECTIONS);
    }
}
