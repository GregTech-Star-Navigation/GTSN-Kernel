package com.gtsn.kernel.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 二期调度器：<b>按 region 串行 tick</b>。
 *
 * <p>它做三件事，三件事都能在纯 JVM 上单测（不碰 MC）：</p>
 * <ol>
 *   <li><b>激活</b>：区块加载时把对应 region 纳入 tick 集合；</li>
 *   <li><b>依赖检测</b>：记录跨 region 同步访问的边，发现 A→B 与 B→A 同时存在就合并；</li>
 *   <li><b>串行 tick</b>：按 {@link RegionId#compareTo} 的确定顺序逐个跑。</li>
 * </ol>
 *
 * <p><b>为什么串行也能验证并行逻辑</b>：合并规则、访问检测、region 归属这些都不依赖真正的
 * 并发。串行执行时它们的行为与并行时完全一致 —— 只有「两个 region 同时写同一份数据」这种
 * 竞态才需要真并行才能暴露，而那属于三期。先在串行下把逻辑跑对，三期只需把
 * {@link #tickAll} 的执行方式换掉。</p>
 *
 * <p>确定性保证：{@link #tickAll} 每次按 {@link RegionId} 排序执行，同一份输入产生同一份
 * 执行顺序 —— 这是二期一致性测试能比对的前提。</p>
 */
public final class SerialRegionScheduler implements TickScheduler {

    private final RegionMap map;
    /** 已激活的 region（key 是原始 id，可能是已被合并的非代表）。 */
    private final Map<RegionId, SimpleRegion> regions = new LinkedHashMap<>();
    /** union-find 的父指针；代表指向自己。 */
    private final Map<RegionId, RegionId> parent = new HashMap<>();
    /** 已观测到的跨 region 同步访问边。 */
    private final Map<RegionId, Set<RegionId>> syncEdges = new HashMap<>();
    private long mergeCount;
    private boolean shutdown;

    public SerialRegionScheduler(RegionMap map) {
        this.map = Objects.requireNonNull(map, "map");
    }

    public RegionMap map() {
        return map;
    }

    /**
     * 激活一个 region（区块加载时调用）。
     *
     * @param id     region
     * @param reason 划分依据（如 {@code "chunk-load"}）
     */
    public SimpleRegion activate(RegionId id, String reason) {
        Objects.requireNonNull(id, "id");
        parent.putIfAbsent(id, id);
        return regions.computeIfAbsent(id, key -> new SimpleRegion(key, reason));
    }

    /** 该 region 是否已激活。 */
    public boolean isActive(RegionId id) {
        return regions.containsKey(id);
    }

    @Override
    public List<Region> regions() {
        List<Region> result = new ArrayList<>();
        for (Map.Entry<RegionId, SimpleRegion> entry : regions.entrySet()) {
            if (isRepresentative(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        result.sort((a, b) -> a.id().compareTo(b.id()));
        return result;
    }

    @Override
    public int parallelism() {
        // 二期恒为 1：所有 region 串行跑，同一时刻只有一个在 tick。
        return 1;
    }

    @Override
    public CompletableFuture<Void> submit(RegionId regionId, Runnable task) {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(task, "task");
        // 串行调度器没有自己的线程：任务在当前线程直接执行（等价于原版的同步调用）。
        RegionId representative = find(regionId);
        SimpleRegion region = regions.get(representative);
        if (region != null) {
            ThreadGuard.checkOnServerThread("submit(" + representative.shortName() + ")");
        }
        try {
            task.run();
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(failure);
            return failed;
        }
    }

    /**
     * 记录一次跨 region 同步访问。
     *
     * <p>如果加边之后形成 A→B→A 的环，就合并两个 region —— 合并后它们同属一个 region，
     * 同步调用退化为同一线程内的直接调用，不可能死锁。</p>
     *
     * @return 是否触发了合并
     */
    public boolean recordSyncAccess(RegionId from, RegionId to, String what) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        RegionId source = find(from);
        RegionId target = find(to);
        if (source.equals(target)) {
            // 已同属一个 region：不是跨 region 访问，不记账。
            return false;
        }
        // 加边前先判环：如果 target 已经能到达 source，加上 source→target 就闭合了环。
        // 只查直接反向边是不够的 —— A→B→C→A 的三角环同样会死锁。
        if (canReach(target, source)) {
            merge(source, target, what);
            return true;
        }
        syncEdges.computeIfAbsent(source, key -> new HashSet<>()).add(target);
        return false;
    }

    /**
     * 从 {@code start} 出发能否沿已有的同步边到达 {@code goal}（DFS）。
     *
     * <p>边数很少（region 级别的依赖，通常个位数），用递归 DFS 足够；不做记忆化是因为
     * 每次合并后图会被清空重建。</p>
     */
    private boolean canReach(RegionId start, RegionId goal) {
        return canReach(start, goal, new HashSet<>());
    }

    private boolean canReach(RegionId start, RegionId goal, Set<RegionId> visited) {
        if (start.equals(goal)) {
            return true;
        }
        if (!visited.add(start)) {
            return false;
        }
        for (RegionId next : syncEdges.getOrDefault(start, Set.of())) {
            if (canReach(next, goal, visited)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RegionId merge(RegionId regionA, RegionId regionB, String reason) {
        Objects.requireNonNull(regionA, "regionA");
        Objects.requireNonNull(regionB, "regionB");
        RegionId a = find(regionA);
        RegionId b = find(regionB);
        if (a.equals(b)) {
            return a;
        }
        // 合并方向确定：保留 compareTo 较小的作为代表，保证可复现。
        RegionId keep = a.compareTo(b) <= 0 ? a : b;
        RegionId drop = keep.equals(a) ? b : a;
        parent.put(drop, keep);
        mergeCount++;

        SimpleRegion kept = regions.get(keep);
        SimpleRegion dropped = regions.get(drop);
        if (kept != null) {
            kept.addProvenance("merged:" + drop.shortName() + (reason == null ? "" : "(" + reason + ")"), true);
            if (dropped != null) {
                kept.chunkCount(kept.chunkCount() + dropped.chunkCount());
            }
        }
        // 合并后清掉这两个节点相关的边，避免下一轮重复触发。
        syncEdges.remove(keep);
        syncEdges.remove(drop);
        return keep;
    }

    @Override
    public long mergeCount() {
        return mergeCount;
    }

    /**
     * 串行 tick 所有代表 region。
     *
     * @param tickPerRegion 对单个 region 执行一次 tick；参数是代表 region
     */
    public void tickAll(java.util.function.Consumer<SimpleRegion> tickPerRegion) {
        Objects.requireNonNull(tickPerRegion, "tickPerRegion");
        if (shutdown) {
            return;
        }
        List<SimpleRegion> active = new ArrayList<>();
        for (Map.Entry<RegionId, SimpleRegion> entry : regions.entrySet()) {
            if (isRepresentative(entry.getKey())) {
                active.add(entry.getValue());
            }
        }
        // 确定顺序：同一份输入 → 同一份执行顺序（一致性测试的前提）。
        active.sort((a, b) -> a.id().compareTo(b.id()));
        for (SimpleRegion region : active) {
            region.running(true);
            try {
                tickPerRegion.accept(region);
            } finally {
                region.running(false);
            }
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    /** union-find 查找（带路径压缩）。 */
    public RegionId find(RegionId id) {
        RegionId current = parent.getOrDefault(id, id);
        if (current.equals(id)) {
            return id;
        }
        RegionId root = find(current);
        parent.put(id, root);
        return root;
    }

    /** 该 id 是否是所在集合的代表。 */
    public boolean isRepresentative(RegionId id) {
        return find(id).equals(id);
    }

    /** 测试与监控用：当前已观测的跨 region 边数。 */
    public int observedEdgeCount() {
        int total = 0;
        for (Set<RegionId> targets : syncEdges.values()) {
            total += targets.size();
        }
        return total;
    }
}
