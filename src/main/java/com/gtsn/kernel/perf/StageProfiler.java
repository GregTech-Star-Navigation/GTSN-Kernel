package com.gtsn.kernel.perf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阶段耗时采集器（无锁、无每 tick 分配、按线程分桶）。
 *
 * <p>设计约束（这是 profiler 自己必须遵守的纪律，否则测出来的数据不可信）：</p>
 * <ul>
 *   <li>每 tick 调用 {@code begin/end} 约 30 次，总开销必须远小于 1ms；</li>
 *   <li>环形缓冲预分配，{@code begin/end} 路径上<b>零对象分配</b>；</li>
 *   <li><b>多线程安全</b>：每个线程写自己的桶，桶之间没有任何共享可写状态。</li>
 * </ul>
 *
 * <p><b>为什么按线程分桶</b>：二期 2b 之后 {@code ServerLevel.tick} 是经调度器进入的，
 * 三期换成线程池后同一个阶段会在多个线程上同时跑。原来的「只采集 owner 线程 + 全局数组」
 * 那时候会把所有线程的数据混进同一组 {@code HEAD}/{@code COUNT}，技术上是数据竞争，
 * 业务上也无法回答「哪个 region 慢」。</p>
 *
 * <p>分桶之后：主线程（全局阶段：保存、玩家、网络）一个桶，每个 region 线程一个桶。
 * 报告时既看得到总量（{@link #stats}），也看得到每个桶的明细（{@link #perBucketStats}）。</p>
 *
 * <p>嵌套语义：不同阶段各自有独立的开始时间槽位，所以 {@code SERVER_TICK} 与
 * {@code LEVEL_TICK} 可以同时计时。同一阶段重复 {@code begin} 会覆盖（原版 tick 不会
 * 出现同阶段自嵌套）。</p>
 */
public final class StageProfiler {

    /** 保留最近多少 tick 的样本（60 秒 @ 20 TPS）。 */
    public static final int CAPACITY = 1200;

    private static final int STAGE_COUNT = TickStage.ALL.length;

    /**
     * 最多跟踪多少个线程的桶。
     *
     * <p>每个桶约 {@code 25 阶段 × 1200 样本 × 8 字节 = 240KB}。16 个桶 ≈ 3.8MB —— 对
     * 一个诊断工具可以接受，但不能再多：桶一多内存就不可控了。超出上限的线程不采集
     * （计数记在 {@link #droppedThreads()}，报告里会显示，不会静默丢数据）。</p>
     */
    private static final int MAX_BUCKETS = 16;

    /**
     * 已注册的桶。
     *
     * <p>必须是 {@code AtomicReferenceArray} 而不是普通数组：桶由各自线程写入，报告线程
     * 在另一个线程读，普通数组的元素写没有跨线程可见性保证 —— 实测表现为报告里少看到
     * 一个桶（样本「凭空消失」）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicReferenceArray<Bucket> BUCKETS =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(MAX_BUCKETS);
    private static final AtomicInteger BUCKET_COUNT = new AtomicInteger();
    private static final AtomicInteger DROPPED_THREADS = new AtomicInteger();

    /** 当前线程的桶；线程首次 begin/end 时分配。 */
    private static final ThreadLocal<Bucket> LOCAL = new ThreadLocal<>();

    /** 主线程引用（用于标记「这个桶是服务端主线程」）。 */
    private static volatile Thread owner;

    /** 是否启用（关闭时 begin/end 立即返回）。 */
    private static volatile boolean enabled = true;

    private StageProfiler() {
    }

    /**
     * 一个线程的独立采集桶。
     *
     * <p>所有字段都是每线程私有的，除 {@code threadName} 外没有 volatile / 锁 ——
     * 只有报告线程会读，而读的是「已经写进去的历史样本」，读到一个正在写的槽位顶多
     * 让某一次统计略有偏差，不影响正确性。</p>
     */
    static final class Bucket implements Comparable<Bucket> {
        final String threadName;
        /** 每个阶段一个环形缓冲，单位纳秒。 */
        final long[][] samples = new long[STAGE_COUNT][CAPACITY];
        /** 每个阶段的写入位置。 */
        final int[] head = new int[STAGE_COUNT];
        /** 每个阶段已写入的样本数（上限 CAPACITY）。 */
        final int[] count = new int[STAGE_COUNT];
        /** 本 tick 内每个阶段的累计耗时（纳秒）—— 多世界 / 多 region 会累加。 */
        final long[] tickTotals = new long[STAGE_COUNT];
        /** 每阶段的开始时间（纳秒）。 */
        final long[] starts = new long[STAGE_COUNT];

        Bucket(String threadName) {
            this.threadName = threadName;
        }

        @Override
        public int compareTo(Bucket other) {
            return threadName.compareTo(other.threadName);
        }
    }

    /** 由服务端启动时登记主线程（只用于报告里标注哪个桶是主线程，不再限制采集范围）。 */
    public static void bindOwner(Thread thread) {
        owner = thread;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 因超出 {@link #MAX_BUCKETS} 而未被采集的线程数（非零时报告会提示）。 */
    public static int droppedThreads() {
        return DROPPED_THREADS.get();
    }

    /** 取当前线程的桶；第一次调用时分配并注册。超上限返回 null（不采集）。 */
    private static Bucket bucket() {
        Bucket bucket = LOCAL.get();
        if (bucket != null) {
            return bucket;
        }
        int slot = BUCKET_COUNT.getAndIncrement();
        if (slot >= MAX_BUCKETS) {
            DROPPED_THREADS.incrementAndGet();
            // 记成特殊桶，避免每次都走 getAndIncrement
            LOCAL.set(new Bucket("dropped"));
            return null;
        }
        bucket = new Bucket(Thread.currentThread().getName());
        BUCKETS.set(slot, bucket);
        LOCAL.set(bucket);
        return bucket;
    }

    public static void begin(TickStage stage) {
        if (!enabled) {
            return;
        }
        Bucket bucket = bucket();
        if (bucket == null || bucket.threadName.equals("dropped")) {
            return;
        }
        bucket.starts[stage.ordinal()] = System.nanoTime();
    }

    public static void end(TickStage stage) {
        if (!enabled) {
            return;
        }
        Bucket bucket = bucket();
        if (bucket == null || bucket.threadName.equals("dropped")) {
            return;
        }
        int index = stage.ordinal();
        long start = bucket.starts[index];
        if (start == 0L) {
            return;
        }
        bucket.starts[index] = 0L;
        long elapsed = System.nanoTime() - start;
        if (elapsed < 0L) {
            return;
        }
        bucket.tickTotals[index] += elapsed;
        int head = bucket.head[index];
        bucket.samples[index][head] = elapsed;
        bucket.head[index] = (head + 1) % CAPACITY;
        if (bucket.count[index] < CAPACITY) {
            bucket.count[index]++;
        }
    }

    /**
     * 环形缓冲里第 {@code i} 个样本的数组下标。
     *
     * <p><b>这里曾经错得很隐蔽</b>：{@code head} 在写完一个样本后就递增了，它指向的是
     * 「下一个要写的位置」，不是「第一个有效样本」。从 {@code head} 开始读会读到还没写过的
     * 槽位（值 0）—— 只有缓冲写满（{@code count == CAPACITY}）时两者才恰好重合，所以
     * 长时间采样看不出问题，短窗口采样（reset 后跑几十秒）会把 avg 拉低、p50 变成 0。</p>
     */
    private static int sampleIndex(Bucket bucket, int stageIndex, int i) {
        int count = bucket.count[stageIndex];
        int start = (bucket.head[stageIndex] - count + CAPACITY) % CAPACITY;
        return (start + i) % CAPACITY;
    }

    /** 所有活跃桶（注册顺序，跳过未分配的槽位）。 */
    private static List<Bucket> activeBuckets() {
        List<Bucket> result = new ArrayList<>(MAX_BUCKETS);
        for (int i = 0; i < BUCKETS.length(); i++) {
            Bucket bucket = BUCKETS.get(i);
            if (bucket != null && !bucket.threadName.equals("dropped")) {
                result.add(bucket);
            }
        }
        return result;
    }

    /** 本 tick 内某阶段的累计耗时（毫秒，多线程 / 多世界已累加）。 */
    public static double tickTotalMs(TickStage stage) {
        int index = stage.ordinal();
        long sum = 0L;
        for (Bucket bucket : activeBuckets()) {
            sum += bucket.tickTotals[index];
        }
        return sum / 1_000_000.0;
    }

    /** 每个 tick 结束时调用：清零所有桶的本 tick 累加器。必须在读取 {@link #tickTotalMs} 之后。 */
    public static void endTick() {
        for (Bucket bucket : activeBuckets()) {
            java.util.Arrays.fill(bucket.tickTotals, 0L);
        }
    }

    /** 清空所有样本。 */
    public static void reset() {
        for (Bucket bucket : activeBuckets()) {
            for (int i = 0; i < STAGE_COUNT; i++) {
                bucket.head[i] = 0;
                bucket.count[i] = 0;
                bucket.samples[i] = new long[CAPACITY];
            }
        }
    }

    /** 某阶段已采集的样本数（所有桶合计）。 */
    public static int sampleCount(TickStage stage) {
        int index = stage.ordinal();
        int total = 0;
        for (Bucket bucket : activeBuckets()) {
            total += bucket.count[index];
        }
        return total;
    }

    /**
     * 某阶段的统计快照（所有桶合并）。
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

    /** 计算某阶段的统计（合并所有桶；会分配数组用于排序，只应在命令触发时调用）。 */
    public static Stats stats(TickStage stage) {
        int index = stage.ordinal();
        List<Bucket> buckets = activeBuckets();
        int total = 0;
        long sum = 0L;
        for (Bucket bucket : buckets) {
            total += bucket.count[index];
            for (int i = 0; i < bucket.count[index]; i++) {
                sum += bucket.samples[index][sampleIndex(bucket, index, i)];
            }
        }
        if (total == 0) {
            return Stats.EMPTY;
        }
        long[] snapshot = new long[total];
        int cursor = 0;
        for (Bucket bucket : buckets) {
            int count = bucket.count[index];
            for (int i = 0; i < count; i++) {
                snapshot[cursor++] = bucket.samples[index][sampleIndex(bucket, index, i)];
            }
        }
        Arrays.sort(snapshot);
        double avg = sum / (double) total / 1_000_000.0;
        double p50 = snapshot[total / 2] / 1_000_000.0;
        double p99 = snapshot[Math.min(total - 1, (int) Math.ceil(total * 0.99) - 1)] / 1_000_000.0;
        double max = snapshot[total - 1] / 1_000_000.0;
        return new Stats(total, avg, p50, p99, max);
    }

    /**
     * 每个桶单独的统计（三期多线程之后用它回答「哪个 region 慢」）。
     *
     * <p>按线程名排序，只返回有样本的桶。</p>
     */
    public static Map<String, Stats> perBucketStats(TickStage stage) {
        int index = stage.ordinal();
        List<Bucket> buckets = activeBuckets();
        buckets.sort(null);
        Map<String, Stats> result = new LinkedHashMap<>();
        for (Bucket bucket : buckets) {
            int count = bucket.count[index];
            if (count == 0) {
                continue;
            }
            long[] snapshot = new long[count];
            long sum = 0L;
            for (int i = 0; i < count; i++) {
                long value = bucket.samples[index][sampleIndex(bucket, index, i)];
                snapshot[i] = value;
                sum += value;
            }
            Arrays.sort(snapshot);
            result.put(bucket.threadName, new Stats(
                    count,
                    sum / (double) count / 1_000_000.0,
                    snapshot[count / 2] / 1_000_000.0,
                    snapshot[Math.min(count - 1, (int) Math.ceil(count * 0.99) - 1)] / 1_000_000.0,
                    snapshot[count - 1] / 1_000_000.0));
        }
        return result;
    }

    /** 活跃桶数量（多线程时 > 1）。 */
    public static int bucketCount() {
        return activeBuckets().size();
    }

    /** 是否是主线程的桶（报告里标注用）。 */
    public static boolean isOwnerBucket(String threadName) {
        return owner != null && owner.getName().equals(threadName);
    }

    /** 取某阶段最近的样本（所有桶合并、按写入顺序；用于 CSV 导出）。 */
    public static long[] recentSamples(TickStage stage) {
        int index = stage.ordinal();
        List<Bucket> buckets = activeBuckets();
        int total = 0;
        for (Bucket bucket : buckets) {
            total += bucket.count[index];
        }
        long[] result = new long[total];
        int cursor = 0;
        for (Bucket bucket : buckets) {
            int count = bucket.count[index];
            for (int i = 0; i < count; i++) {
                result[cursor++] = bucket.samples[index][sampleIndex(bucket, index, i)];
            }
        }
        return result;
    }
}
