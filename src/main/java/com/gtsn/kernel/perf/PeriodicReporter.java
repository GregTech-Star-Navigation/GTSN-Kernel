package com.gtsn.kernel.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 定期把阶段摘要写进日志。
 *
 * <p>用途：无人值守的服务器没法手敲 {@code /gtsnperf}，开这个开关就能在日志里持续看到
 * 「谁在吃时间」。也方便跑压测时对照时间轴。</p>
 *
 * <p>开启方式：启动参数 {@code -Dgtsnkernel.perf.logEvery=<tick 数>}（0 或未设 = 关闭）。
 * 例如 {@code -Dgtsnkernel.perf.logEvery=1200} 每 60 秒打一次。</p>
 */
public final class PeriodicReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("GTSN-Kernel/Perf");
    private static final int INTERVAL = Integer.getInteger("gtsnkernel.perf.logEvery", 0);

    private static long counter;

    private PeriodicReporter() {
    }

    public static boolean enabled() {
        return INTERVAL > 0;
    }

    /** 每 tick 调一次（在 SERVER_TICK 的 end 之后）。 */
    public static void tick() {
        if (INTERVAL <= 0) {
            return;
        }
        if (++counter < INTERVAL) {
            return;
        }
        counter = 0L;
        report();
    }

    /** 立即输出一次摘要（也供命令复用）。 */
    public static void report() {
        int ticks = StageProfiler.sampleCount(TickStage.SERVER_TICK);
        if (ticks == 0) {
            return;
        }
        StringBuilder line = new StringBuilder(160);
        line.append("[GTSN-Kernel] 阶段耗时 · 最近 ").append(ticks).append(" tick · ms:");
        for (TickStage stage : TickStage.ALL) {
            StageProfiler.Stats stats = StageProfiler.stats(stage);
            if (stats.count() == 0) {
                continue;
            }
            line.append(' ').append(stage.key()).append('=');
            line.append(String.format(Locale.ROOT, "%.2f", stats.avgMs()));
        }
        LOGGER.info(line.toString());
    }
}
