package com.gtsn.kernel.perf;

import java.util.Arrays;

/**
 * 阶段耗时采集器（无锁、无每 tick 分配）。
 *
 * <p>设计约束（这是 profiler 自己必须遵守的纪律，否则测出来的数据不可信）：</p>
 * <ul>
 *   <li>每 tick 调用 {@code begin/end} 约 30 次，总开销必须远小于 1ms；</li>
 *   <li>环形缓冲预分配，{@code begin/end} 路径上<b>零对象分配</b>；</li>
 *   <li>只记录服务端主线程的数据 —— 其他线程调用直接忽略（多 region 之后会有多线程，
 *       届时按 region 分桶）。</li>
 * </ul>
 *
 * <p>嵌套语义：不同阶段各自有独立的开始时间槽位，所以 {@code SERVER_TICK} 与
 * {@code LEVEL_TICK} 可以同时计时。同一阶段重复 {@code begin} 会覆盖（原版 tick 不会
 * 出现同阶段自嵌套）。</p>
 */
public final class StageProfiler {

    /** 保留最近多少 tick 的样本（60 秒 @ 20 TPS）。 */
    public static final int CAPACITY = 1200;

    private static final int STAGE_COUNT = TickStage.ALL.length;

    /** 每个阶段一个环形缓冲，单位纳秒。 */
    private static final long[][] SAMPLES = new long[STAGE_COUNT][CAPACITY];
    /** 每个阶段的写入位置。 */
    private static final int[] HEAD = new int[STAGE_COUNT];
    /** 每个阶段已写入的样本数（上限 CAPACITY）。 */
    private static final int[] COUNT = new int[STAGE_COUNT];

    /** 每线程每阶段的开始时间。 */
    private static final ThreadLocal<long[]> STARTS =
            ThreadLocal.withInitial(() -> new long[STAGE_COUNT]);

    /** 主线程引用：只采集这个线程的数据。 */
    private static volatile Thread owner;

    /** 是否启用（关闭时 begin/end 立即返回）。 */
    private static volatile boolean enabled = true;

    private StageProfiler() {
    }

    /** 由服务端启动时登记主线程；其他线程的 begin/end 会被忽略。 */
    public static void bindOwner(Thread thread) {
        owner = thread;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void begin(TickStage stage) {
        if (!enabled) {
            return;
        }
        if (owner != null && Thread.currentThread() != owner) {
            return;
        }
        STARTS.get()[stage.ordinal()] = System.nanoTime();
    }

    public static void end(TickStage stage) {
        if (!enabled) {
            return;
        }
        if (owner != null && Thread.currentThread() != owner) {
            return;
        }
        int index = stage.ordinal();
        long[] starts = STARTS.get();
        long start = starts[index];
        if (start == 0L) {
            return;
        }
        starts[index] = 0L;
        long elapsed = System.nanoTime() - start;
        if (elapsed < 0L) {
            return;
        }
        int head = HEAD[index];
        SAMPLES[index][head] = elapsed;
        HEAD[index] = (head + 1) % CAPACITY;
        if (COUNT[index] < CAPACITY) {
            COUNT[index]++;
        }
    }

    /** 清空所有样本。 */
    public static void reset() {
        for (int i = 0; i < STAGE_COUNT; i++) {
            HEAD[i] = 0;
            COUNT[i] = 0;
        }
    }

    /** 某阶段已采集的样本数。 */
    public static int sampleCount(TickStage stage) {
        return COUNT[stage.ordinal()];
    }

    /**
     * 某阶段的统计快照。
     *
     * @param count 样本数
     * @param avgMs 平均耗时（毫秒）
     * @param p50Ms 中位数
     * @param p99Ms 99 分位
     * @param maxMs 最大值
     */
    public record Stats(int count, double avgMs, double p50Ms, double p99Ms, double maxMs) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);
    }

    /** 计算某阶段的统计（会分配一个数组用于排序；只应在命令触发时调用）。 */
    public static Stats stats(TickStage stage) {
        int index = stage.ordinal();
        int count = COUNT[index];
        if (count == 0) {
            return Stats.EMPTY;
        }
        long[] snapshot = new long[count];
        int head = HEAD[index];
        // 环形缓冲：有效区间是 [head, head+count) 取模。
        for (int i = 0; i < count; i++) {
            snapshot[i] = SAMPLES[index][(head + i) % CAPACITY];
        }
        Arrays.sort(snapshot);
        long sum = 0L;
        for (long value : snapshot) {
            sum += value;
        }
        double avg = sum / (double) count / 1_000_000.0;
        double p50 = snapshot[count / 2] / 1_000_000.0;
        double p99 = snapshot[Math.min(count - 1, (int) Math.ceil(count * 0.99) - 1)] / 1_000_000.0;
        double max = snapshot[count - 1] / 1_000_000.0;
        return new Stats(count, avg, p50, p99, max);
    }

    /** 取某阶段最近的样本（用于 CSV 导出，按时间顺序）。 */
    public static long[] recentSamples(TickStage stage) {
        int index = stage.ordinal();
        int count = COUNT[index];
        long[] result = new long[count];
        int head = HEAD[index];
        for (int i = 0; i < count; i++) {
            result[i] = SAMPLES[index][(head + i) % CAPACITY];
        }
        return result;
    }
}
