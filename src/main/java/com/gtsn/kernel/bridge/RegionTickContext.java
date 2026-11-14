package com.gtsn.kernel.bridge;

import com.gtsn.kernel.core.RegionId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「当前线程正在 tick 哪个 region」（三期 P2a）。
 *
 * <p>它的用途只有一个：让 {@link ServerChunkCacheGuard} 能区分「主线程的正常访问」和
 * 「region 线程的危险访问」。前者放行，后者拦截并记账。</p>
 *
 * <p><b>为什么用 ThreadLocal 而不是传参</b>：调用链太深（tick → ticker → blockEntity →
 * level.getChunk），中间隔着几百个原版方法，没有地方可以安全地加一个 region 参数。</p>
 */
public final class RegionTickContext {

    /** 当前线程正在 tick 的 region；主线程为 null。 */
    private static final ThreadLocal<RegionId> CURRENT = new ThreadLocal<>();

    /** 被拦截的危险调用次数（按调用点分类）。 */
    private static final Map<String, AtomicLong> VIOLATIONS = new ConcurrentHashMap<>();

    /** 最近一次违规的样本描述（给报告看，不保留全部，避免内存膨胀）。 */
    private static final Map<String, String> SAMPLES = new ConcurrentHashMap<>();

    private RegionTickContext() {
    }

    public static void enter(RegionId region) {
        CURRENT.set(region);
    }

    public static void exit() {
        CURRENT.remove();
    }

    /** 当前线程正在 tick 的 region；不在 region 线程上返回 null。 */
    public static RegionId current() {
        return CURRENT.get();
    }

    /** 当前线程是不是 region 工作线程。 */
    public static boolean inRegionThread() {
        return CURRENT.get() != null;
    }

    /**
     * 记录一次「region 线程做了不该做的区块访问」。
     *
     * @param kind   调用类型（{@code getChunk} / {@code getChunkBlocking} …）
     * @param detail 出事的坐标或线程名
     */
    public static void recordViolation(String kind, String detail) {
        VIOLATIONS.computeIfAbsent(kind, key -> new AtomicLong()).incrementAndGet();
        SAMPLES.putIfAbsent(kind, detail);
    }

    /** 违规总数。 */
    public static long violationCount() {
        long total = 0L;
        for (AtomicLong counter : VIOLATIONS.values()) {
            total += counter.get();
        }
        return total;
    }

    /** 按类型的违规明细（报告用）。 */
    public static Map<String, Long> violationBreakdown() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        VIOLATIONS.forEach((kind, counter) -> result.put(kind, counter.get()));
        return result;
    }

    /** 每类违规的第一个样本。 */
    public static Map<String, String> samples() {
        return Map.copyOf(SAMPLES);
    }

    public static void reset() {
        VIOLATIONS.clear();
        SAMPLES.clear();
    }
}
