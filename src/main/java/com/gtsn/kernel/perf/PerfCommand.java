package com.gtsn.kernel.perf;

import com.gtsn.kernel.core.ThreadGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code /gtsnperf} —— 阶段耗时报表。
 *
 * <pre>
 * /gtsnperf            输出各阶段 avg / p50 / p99 / max
 * /gtsnperf reset      清空样本
 * /gtsnperf on|off     开关采集（关掉时 profiler 开销为零）
 * /gtsnperf csv        开始/停止录制每 tick 明细到 run/gtsnperf-&lt;时间戳&gt;.csv
 * </pre>
 */
public final class PerfCommand {

    private PerfCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gtsnperf")
                .requires(source -> source.hasPermission(2))
                .executes(PerfCommand::report)
                .then(Commands.literal("reset").executes(PerfCommand::reset))
                .then(Commands.literal("on").executes(context -> setEnabled(context, true)))
                .then(Commands.literal("off").executes(context -> setEnabled(context, false)))
                .then(Commands.literal("csv").executes(PerfCommand::toggleCsv)));
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        int tickCount = StageProfiler.sampleCount(TickStage.SERVER_TICK);
        if (tickCount == 0) {
            reply(context, "[GTSN-Kernel] 尚无样本（服务器还没跑够一个 tick？）");
            return 0;
        }
        reply(context, String.format(Locale.ROOT,
                "GTSN-Kernel 阶段耗时 · 最近 %d tick · 单位 ms · 越界 %d 次",
                tickCount, ThreadGuard.violationCount()));
        reply(context, pad("阶段", 22) + "     avg      p50      p99      max");
        for (TickStage stage : TickStage.ALL) {
            StageProfiler.Stats stats = StageProfiler.stats(stage);
            if (stats.count() == 0) {
                continue;
            }
            String name = "  ".repeat(stage.depth()) + stage.label();
            reply(context, String.format(Locale.ROOT, "%s%8.3f %8.3f %8.3f %8.3f",
                    pad(name, 22), stats.avgMs(), stats.p50Ms(), stats.p99Ms(), stats.maxMs()));
        }
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        StageProfiler.reset();
        reply(context, "[GTSN-Kernel] 样本已清空");
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        StageProfiler.setEnabled(enabled);
        reply(context, "[GTSN-Kernel] 采集" + (enabled ? "已开启" : "已关闭（开销为零）"));
        return 1;
    }

    private static int toggleCsv(CommandContext<CommandSourceStack> context) {
        if (CsvRecorder.isRecording()) {
            CsvRecorder.stop();
            reply(context, "[GTSN-Kernel] CSV 录制已停止");
            return 1;
        }
        try {
            String path = CsvRecorder.start(context.getSource().getServer());
            reply(context, "[GTSN-Kernel] CSV 录制已开始 → " + path);
            return 1;
        } catch (IOException failure) {
            reply(context, "[GTSN-Kernel] CSV 打开失败: " + failure.getMessage());
            return 0;
        }
    }

    private static void reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    /**
     * 按显示宽度右补空格（中日韩字符算 2 个宽度）。
     *
     * <p>Minecraft 的等宽字体里 CJK 占两格，直接用 {@code String.length()} 对齐会歪。</p>
     */
    private static String pad(String text, int width) {
        int display = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            display += (ch >= 0x2E80 && ch <= 0x9FFF) || (ch >= 0xFF00 && ch <= 0xFF60) ? 2 : 1;
        }
        return display >= width ? text : text + " ".repeat(width - display);
    }
}
