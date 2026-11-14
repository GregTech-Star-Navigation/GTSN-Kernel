package com.gtsn.kernel;

import com.gtsn.kernel.core.ThreadGuard;
import com.gtsn.kernel.perf.CsvRecorder;
import com.gtsn.kernel.bridge.TickRedirectHandler;
import com.gtsn.kernel.perf.PerfCommand;
import com.gtsn.kernel.perf.StageProfiler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * GTSN Kernel 入口。
 *
 * <p>本 mod 目前只做一件事：给原版服务端 tick 的每个阶段打点计时（一期）。
 * <b>不修改任何原版逻辑</b> —— 所有 Mixin 都是 {@code @Inject} 的 HEAD/RETURN 插桩，
 * 不改参数、不改返回值、不取消调用。</p>
 *
 * <p>包结构按后续各期的目标预先划好：</p>
 * <ul>
 *   <li>{@code perf} —— 阶段 profiler（一期，已实现）</li>
 *   <li>{@code core} —— region / 调度器 / 线程守卫（二期）</li>
 *   <li>{@code compat} —— 规则表 / 探针 / 降级（四期）</li>
 *   <li>{@code gen} —— 地形生成接口（五期）</li>
 *   <li>{@code mixin} —— 唯一接触原版类的地方</li>
 * </ul>
 */
@Mod(GtsnKernel.MOD_ID)
public class GtsnKernel {

    public static final String MOD_ID = "gtsnkernel";
    private static final Logger LOGGER = LogUtils.getLogger();

    public GtsnKernel() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[GTSN-Kernel] loading (perf profiler enabled)");
    }

    /** 服务端起好之后绑定主线程：profiler 只采集这个线程的数据。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        Thread serverThread = event.getServer().getRunningThread();
        StageProfiler.bindOwner(serverThread);
        ThreadGuard.bindServerThread(serverThread);
        TickRedirectHandler.bootstrap();
        LOGGER.info("[GTSN-Kernel] profiler bound to server thread '{}'", serverThread.getName());
        if (CsvRecorder.autoStartRequested()) {
            try {
                LOGGER.info("[GTSN-Kernel] CSV 自动录制 → {}", CsvRecorder.start(event.getServer()));
            } catch (java.io.IOException failure) {
                LOGGER.warn("[GTSN-Kernel] CSV 自动录制启动失败: {}", failure.getMessage());
            }
        }
    }

    /** 停机时让调度器收尾（二期 2b）。 */
    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        TickRedirectHandler.shutdown();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PerfCommand.register(event.getDispatcher());
    }
}
