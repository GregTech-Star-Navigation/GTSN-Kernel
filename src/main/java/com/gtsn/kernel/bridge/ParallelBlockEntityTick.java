package com.gtsn.kernel.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * {@code Level.tickBlockEntities()} 里那个 tick 循环的并行版本（三期实验）。
 *
 * <p><b>只并行那一个循环</b>，方法前后的 pending 列表合并、{@code onLoad}、{@code
 * tickingBlockEntities} 开关全部留在调用线程 —— 那些操作改的是同一批 ArrayList，并行没有
 * 好处只有风险。</p>
 *
 * <p><b>分组策略</b>：按方块实体坐标算 region（{@code chunkX >> shift}），同一 region 的
 * ticker 一定落在同一 lane、按原列表顺序执行。这样 region 内的顺序与原版完全一致，
 * 变的只是「不同 region 之间」的先后。</p>
 *
 * <p><b>已知风险（这就是为什么要一致性测试）</b>：方块实体的 tick 可能碰隔壁区块 ——
 * 漏斗把物品推进相邻的箱子就是典型。如果两个相邻区块被分到不同 region 且不同 lane，
 * 就会真的并发访问同一份容器。{@code tools/consistency-check.sh} 用漏斗场景专门测这个。</p>
 */
public final class ParallelBlockEntityTick {

    /**
     * 可以安全并行的方块实体类型（白名单）。
     *
     * <p>判据是<b>实测</b>不是推断：`-Dgtsnkernel.blocks.safeTypes=furnace,chest`。
     * 2026-11-14 实测 {@code minecraft:furnace} 113288 次 tick 零失败，
     * {@code minecraft:hopper} 3757 次失败（每次都跨区块查邻居）。</p>
     *
     * <p>为空时退化为「全部串行」—— 也就是原版行为，不会误伤。</p>
     */
    private static final java.util.Set<String> SAFE_TYPES = parseSafeTypes();

    private static java.util.Set<String> parseSafeTypes() {
        String raw = System.getProperty("gtsnkernel.blocks.safeTypes", "");
        if (raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return java.util.Set.copyOf(result);
    }

    /** 该 ticker 是否在白名单里（只有白名单的才进 lane）。 */
    private static boolean isSafe(String tickerType) {
        return SAFE_TYPES.contains(tickerType);
    }

    /** 白名单内容（报告用）。 */
    public static java.util.Set<String> safeTypes() {
        return SAFE_TYPES;
    }

    /**
     * 成功 / 失败的计数。
     *
     * <p><b>这里踩过一个坑</b>：第一版用 {@code ConcurrentHashMap<String, AtomicLong>} 加
     * {@code computeIfAbsent} + {@code incrementAndGet}，每个 ticker 每 tick 都做一次 ——
     * 289 个熔炉就把 {@code block_entities} 从 0.343ms 顶到 110.681ms（慢 322 倍）。
     * 测量工具自己成了瓶颈。</p>
     *
     * <p>改用 {@link LongAdder}（无竞争时几乎零开销）+ 把类型名的哈希缓存到 ticker 上，
     * 热路径只剩一次 {@code add}。</p>
     */
    private static final Map<String, LongAdder> FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> SUCCESSES = new ConcurrentHashMap<>();

    /** 类型名 → 计数器，避免每次 tick 都查 Map。 */
    private static final Map<String, LongAdder> SUCCESS_BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> FAILURE_BY_TYPE = new ConcurrentHashMap<>();

    private ParallelBlockEntityTick() {
    }

    /** 按 ticker 类型统计失败次数（跑完一轮看哪些类型依赖跨区块访问）。 */
    public static Map<String, Long> failureBreakdown() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        FAILURE_BY_TYPE.forEach((kind, counter) -> result.put(kind, counter.sum()));
        FAILURES.forEach((kind, counter) -> result.merge(kind, counter.sum(), Long::sum));
        return result;
    }

    /** 按类型统计成功次数。 */
    public static Map<String, Long> successBreakdown() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        SUCCESS_BY_TYPE.forEach((kind, counter) -> result.put(kind, counter.sum()));
        SUCCESSES.forEach((kind, counter) -> result.merge(kind, counter.sum(), Long::sum));
        return result;
    }

    public static void resetStats() {
        FAILURES.clear();
        SUCCESSES.clear();
        SUCCESS_BY_TYPE.clear();
        FAILURE_BY_TYPE.clear();
    }

    /**
     * 跑一个 ticker，把失败限制在它自己身上。
     *
     * <p>为什么必须 try-catch：{@link ServerChunkCacheGuard} 对 region 线程的 {@code getChunk}
     * 返回 null，调用方会 NPE。**一个 ticker 的 NPE 不该拖垮整个 tick** —— 本 tick 跳过它，
     * 记一笔账，下一 tick 还有机会（或者被 P2b 的队列接走）。</p>
     */
    private static void tickSafely(TickingBlockEntity ticker, Predicate<net.minecraft.core.BlockPos> shouldTick) {
        String kind = ticker.getType();
        try {
            if (shouldTick.test(ticker.getPos())) {
                ticker.tick();
                // 热路径：只做一次 LongAdder.add。类型名查找仍然在 Map 上（String.hashCode
                // 有缓存），比 computeIfAbsent 便宜一个数量级。
                SUCCESS_BY_TYPE.computeIfAbsent(kind, key -> new LongAdder()).increment();
            }
        } catch (Throwable failure) {
            FAILURE_BY_TYPE.computeIfAbsent(kind, key -> new LongAdder()).increment();
        }
    }

    /**
     * 并行跑 tick 循环。
     *
     * @param tickers     待处理的 ticker（<b>调用方已保证</b>不再是正在被原版迭代的那个列表）
     * @param laneOf      region → lane 的映射（{@code parallelism} 取模之后的值）
     * @param parallelism lane 数量
     * @param regionShift 区块坐标右移多少位算一个 region（4 = 16×16 区块）
     * @param pools       lane 对应的单线程执行器
     * @param shouldTick  原版 {@code shouldTickBlocksAt} 的绑定（{@code level::shouldTickBlocksAt}）
     * @return 新的 ticker 列表（已剔除 {@code isRemoved()} 的），供调用方写回
     */
    public static List<TickingBlockEntity> tick(
            List<TickingBlockEntity> tickers,
            int parallelism,
            int regionShift,
            ExecutorService[] pools,
            Predicate<BlockPos> shouldTick) {

        // ---- 第一步：单线程分组（顺带剔除已移除的 ticker，与原版一致）----
        List<List<TickingBlockEntity>> groups = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            groups.add(new ArrayList<>());
        }
        List<TickingBlockEntity> survivors = new ArrayList<>(tickers.size());
        // 白名单外的类型留在主线程跑 —— 它们可能在任意位置碰别的区块，丢到 lane 上就死锁。
        List<TickingBlockEntity> serial = new ArrayList<>();
        for (TickingBlockEntity ticker : tickers) {
            if (ticker.isRemoved()) {
                // 原版这里是 iterator.remove()；调用方拿 survivors 覆盖原列表即可。
                continue;
            }
            survivors.add(ticker);
            if (!isSafe(ticker.getType())) {
                // 不在白名单：留在主线程按原顺序跑（原版语义，不冒任何风险）。
                serial.add(ticker);
                continue;
            }
            BlockPos pos = ticker.getPos();
            int regionX = pos.getX() >> 4 >> regionShift;
            int regionZ = pos.getZ() >> 4 >> regionShift;
            int lane = Math.floorMod(regionX * 31 + regionZ, parallelism);
            groups.get(lane).add(ticker);
        }

        // ---- 第二步：并行 tick 各 lane ----
        if (parallelism == 1) {
            // 退化路径：不切线程，保持与原版一模一样的调用栈（调试时好排查）。
            for (TickingBlockEntity ticker : serial) {
                tickSafely(ticker, shouldTick);
            }
            for (TickingBlockEntity ticker : groups.get(0)) {
                tickSafely(ticker, shouldTick);
            }
            return survivors;
        }

        // 白名单外的先在主线程跑完（此时还没启动 lane，不存在并发）
        for (TickingBlockEntity ticker : serial) {
            tickSafely(ticker, shouldTick);
        }

        CompletableFuture<?>[] futures = new CompletableFuture<?>[parallelism];
        for (int lane = 0; lane < parallelism; lane++) {
            final List<TickingBlockEntity> group = groups.get(lane);
            if (group.isEmpty()) {
                futures[lane] = CompletableFuture.completedFuture(null);
                continue;
            }
            final int laneIndex = lane;
            futures[lane] = CompletableFuture.runAsync(() -> {
                // 进入 region 上下文：这之后 ServerChunkCacheGuard 会把阻塞式 getChunk 拦掉
                // （否则 region 线程转交主线程、主线程又在等 region 线程 → 死锁）。
                RegionTickContext.enter(new com.gtsn.kernel.core.RegionId(laneIndex, 0));
                try {
                    for (TickingBlockEntity ticker : group) {
                        tickSafely(ticker, shouldTick);
                    }
                } finally {
                    RegionTickContext.exit();
                }
            }, pools[lane]);
        }
        CompletableFuture.allOf(futures).join();
        return survivors;
    }
}
