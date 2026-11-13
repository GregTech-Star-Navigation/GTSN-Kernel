package com.gtsn.kernel.perf;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 每 tick 明细录制（CSV）。
 *
 * <p>用途：把「哪个阶段在哪个时刻飙了」和当时的游戏行为对上。汇总统计（{@code /gtsnperf}）
 * 看不出尖峰出现在跑图时还是刷怪时，明细可以。</p>
 *
 * <p>写入用 {@link BufferedWriter}，每 tick 一行、几十个数字，开销在微秒级；只有显式开启
 * 才写盘，默认关闭。</p>
 */
public final class CsvRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("GTSN-Kernel/CSV");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static BufferedWriter writer;
    private static Path file;
    private static long rows;

    private CsvRecorder() {
    }

    public static boolean isRecording() {
        return writer != null;
    }

    /** 启动参数 {@code -Dgtsnkernel.perf.csv=true} 时，服务端启动即开始录制。 */
    public static boolean autoStartRequested() {
        return Boolean.getBoolean("gtsnkernel.perf.csv");
    }

    /** 开始录制，返回文件路径。 */
    public static String start(MinecraftServer server) throws IOException {
        if (writer != null) {
            return file.toString();
        }
        Path directory = server.getServerDirectory().toPath().resolve("gtsnperf");
        Files.createDirectories(directory);
        file = directory.resolve("gtsnperf-" + LocalDateTime.now().format(STAMP) + ".csv");
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        rows = 0L;

        StringBuilder header = new StringBuilder("tick");
        for (TickStage stage : TickStage.ALL) {
            header.append(',').append(stage.key());
        }
        writer.write(header.toString());
        writer.newLine();
        LOGGER.info("[GTSN-Kernel] CSV recording started: {}", file);
        return file.toString();
    }

    /** 每 tick 结束时调用一次（在 SERVER_TICK 的 end 之后）。 */
    public static void tick() {
        BufferedWriter out = writer;
        if (out == null) {
            return;
        }
        try {
            StringBuilder row = new StringBuilder(Long.toString(rows++));
            for (TickStage stage : TickStage.ALL) {
                row.append(',').append(formatMs(StageProfiler.tickTotalMs(stage)));
            }
            out.write(row.toString());
            out.newLine();
            // 每 200 tick 刷一次盘：既不会丢太多，也不至于每 tick 都 syscall。
            if ((rows % 200L) == 0L) {
                out.flush();
            }
        } catch (IOException failure) {
            LOGGER.warn("[GTSN-Kernel] CSV write failed, stopping: {}", failure.getMessage());
            stop();
        }
    }

    /**
     * 本 tick 该阶段的总耗时（多世界已累加）。
     *
     * <p>之前取「环形缓冲最后一个样本」，多维度下会只记到最后一个（通常几乎空的）维度 ——
     * 表现为 CSV 里 level_tick 接近 0 而 /gtsnperf 显示 1ms+。</p>
     */
    private static String formatMs(double millis) {
        if (millis <= 0.0) {
            return "0.000";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", millis);
    }

    public static void stop() {
        BufferedWriter out = writer;
        writer = null;
        if (out == null) {
            return;
        }
        try {
            out.flush();
            out.close();
            LOGGER.info("[GTSN-Kernel] CSV recording stopped: {} ({} rows)", file, rows);
        } catch (IOException failure) {
            LOGGER.warn("[GTSN-Kernel] CSV close failed: {}", failure.getMessage());
        }
    }
}
