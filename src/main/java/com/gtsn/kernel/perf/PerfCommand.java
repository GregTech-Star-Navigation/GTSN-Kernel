package com.gtsn.kernel.perf;

import com.gtsn.kernel.core.ThreadGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.gtsn.kernel.bridge.ParallelBlockEntityTick;
import com.gtsn.kernel.bridge.RegionTickContext;
import com.gtsn.kernel.bridge.WorldHasher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

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
                .then(Commands.literal("csv").executes(PerfCommand::toggleCsv))
                .then(Commands.literal("regions").executes(PerfCommand::regions))
                .then(Commands.literal("hash")
                        .executes(context -> hash(context, true))
                        .then(Commands.literal("blocks").executes(context -> hash(context, false)))));
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        int tickCount = StageProfiler.sampleCount(TickStage.SERVER_TICK);
        if (tickCount == 0) {
            reply(context, "[GTSN-Kernel] 尚无样本（服务器还没跑够一个 tick？）");
            return 0;
        }
        int buckets = StageProfiler.bucketCount();
        int dropped = StageProfiler.droppedThreads();
        reply(context, String.format(Locale.ROOT,
                "GTSN-Kernel 阶段耗时 · 最近 %d tick · 单位 ms · 越界 %d 次 · 采集桶 %d 线程%s",
                tickCount, ThreadGuard.violationCount(), buckets,
                dropped > 0 ? "（" + dropped + " 个线程超出上限未采集）" : ""));
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

    /**
     * 世界状态哈希（二期 2b 一致性测试用）。
     *
     * <p>输出格式固定为 {@code hash=<hex> entities=<n>}，方便脚本比对两次运行。</p>
     */
    private static int hash(CommandContext<CommandSourceStack> context, boolean includeEntities) {
        ServerLevel level = context.getSource().getLevel();
        long value = WorldHasher.hash(level, includeEntities);
        String kind = includeEntities ? "full" : "blocks";
        reply(context, "[GTSN-Kernel] hash(" + kind + ")=" + Long.toHexString(value)
                + " dim=" + level.dimension().location()
                + " blocks=" + WorldHasher.countBlockEntities(level));
        return 1;
    }

    /**
     * region 线程的违规统计与方块实体并行结果（三期 P2a 的量尺）。
     */
    private static int regions(CommandContext<CommandSourceStack> context) {
        long violations = RegionTickContext.violationCount();
        java.util.Set<String> safe = ParallelBlockEntityTick.safeTypes();
        reply(context, "  并行白名单: " + (safe.isEmpty() ? "（空，全部走主线程）" : safe));
        reply(context, String.format(Locale.ROOT,
                "[GTSN-Kernel] region 线程：非阻塞读成功 %d 次，违规 %d 次%s",
                RegionTickContext.fastPathCount(), violations,
                violations == 0 ? "（没有 region 线程做过阻塞式区块访问）" : ""));
        RegionTickContext.violationBreakdown()
                .forEach((kind, count) -> reply(context, "  " + pad(kind, 18) + count));
        RegionTickContext.samples()
                .forEach((kind, sample) -> reply(context, "  样本 " + kind + ": " + sample));

        Map<String, Long> failures = ParallelBlockEntityTick.failureBreakdown();
        Map<String, Long> successes = ParallelBlockEntityTick.successBreakdown();
        if (failures.isEmpty() && successes.isEmpty()) {
            reply(context, "  方块实体并行统计：尚无数据（未开启 -PparallelBlocks？）");
            return 1;
        }
        reply(context, "  方块实体并行（成功 / 失败）：");
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        kinds.addAll(successes.keySet());
        kinds.addAll(failures.keySet());
        for (String kind : kinds) {
            long ok = successes.getOrDefault(kind, 0L);
            long bad = failures.getOrDefault(kind, 0L);
            reply(context, "    " + pad(kind, 40) + ok + " / " + bad);
        }
        return 1;
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
