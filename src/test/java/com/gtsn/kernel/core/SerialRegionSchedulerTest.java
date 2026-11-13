package com.gtsn.kernel.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialRegionSchedulerTest {

    private final RegionId a = new RegionId(0, 0);
    private final RegionId b = new RegionId(1, 0);
    private final RegionId c = new RegionId(2, 0);

    private SerialRegionScheduler scheduler() {
        return new SerialRegionScheduler(RegionMap.grid(16));
    }

    @Test
    void activatedRegionsAreListedInDeterministicOrder() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(c, "test");
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        List<Region> regions = scheduler.regions();
        assertEquals(3, regions.size());
        // 按 RegionId 排序：0,0 → 1,0 → 2,0
        assertEquals(a, regions.get(0).id());
        assertEquals(b, regions.get(1).id());
        assertEquals(c, regions.get(2).id());
    }

    @Test
    void oneWaySyncAccessDoesNotMerge() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        boolean merged = scheduler.recordSyncAccess(a, b, "hopper-pull");

        assertFalse(merged, "单向访问不该合并");
        assertEquals(0, scheduler.mergeCount());
        assertEquals(2, scheduler.regions().size());
    }

    /**
     * 核心场景：A 同步调 B，B 又同步调 A —— 不合并就是死锁。
     */
    @Test
    void reciprocalSyncAccessTriggersMerge() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        assertFalse(scheduler.recordSyncAccess(a, b, "a→b"));
        assertTrue(scheduler.recordSyncAccess(b, a, "b→a"), "反向边出现时必须合并");

        assertEquals(1, scheduler.mergeCount());
        assertEquals(1, scheduler.regions().size(), "合并后只剩一个代表");
        assertEquals(scheduler.find(a), scheduler.find(b), "两者必须同属一个集合");
    }

    @Test
    void mergeKeepsLowerIdAsRepresentative() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        RegionId representative = scheduler.merge(b, a, "test");

        assertEquals(a, representative, "保留 compareTo 较小的，保证可复现");
        assertTrue(scheduler.isRepresentative(a));
        assertFalse(scheduler.isRepresentative(b));
    }

    @Test
    void mergeIsIdempotent() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        scheduler.merge(a, b, "first");
        RegionId again = scheduler.merge(a, b, "second");

        assertEquals(a, again);
        assertEquals(1, scheduler.mergeCount(), "重复合并不应计数");
    }

    @Test
    void mergedRegionsTickOnlyOnce() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");
        scheduler.merge(a, b, "test");

        List<RegionId> ticked = new ArrayList<>();
        scheduler.tickAll(region -> ticked.add(region.id()));

        assertEquals(List.of(a), ticked, "合并后的两个 region 只 tick 代表一次");
    }

    @Test
    void tickAllRunsInRegionIdOrder() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(c, "test");
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");

        List<RegionId> ticked = new ArrayList<>();
        scheduler.tickAll(region -> ticked.add(region.id()));

        assertEquals(List.of(a, b, c), ticked);
    }

    @Test
    void accessWithinSameRegionIsNotRecorded() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");
        scheduler.merge(a, b, "test");

        boolean merged = scheduler.recordSyncAccess(a, b, "same-region");

        assertFalse(merged, "已同属一个 region，不算跨 region 访问");
        assertEquals(0, scheduler.observedEdgeCount(), "同 region 访问不该留边");
    }

    /**
     * 三角环 A→B→C→A 同样会死锁，必须检测出来 —— 只查直接反向边是不够的。
     */
    @Test
    void indirectCycleIsDetected() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");
        scheduler.activate(c, "test");

        assertFalse(scheduler.recordSyncAccess(a, b, "a→b"));
        assertFalse(scheduler.recordSyncAccess(b, c, "b→c"));
        // 此时 a 能到达 c（a→b→c），再加 c→a 就闭合三角环
        assertTrue(scheduler.recordSyncAccess(c, a, "c→a"), "间接环也必须合并");

        assertEquals(1, scheduler.mergeCount());
        assertEquals(scheduler.find(a), scheduler.find(c), "成环的两个端点合并");
    }

    /**
     * 单向长链不该误判成环。
     */
    @Test
    void longChainWithoutCycleDoesNotMerge() {
        SerialRegionScheduler scheduler = scheduler();
        RegionId d = new RegionId(3, 0);
        for (RegionId id : List.of(a, b, c, d)) {
            scheduler.activate(id, "test");
        }

        scheduler.recordSyncAccess(a, b, "a→b");
        scheduler.recordSyncAccess(b, c, "b→c");
        scheduler.recordSyncAccess(c, d, "c→d");

        assertEquals(0, scheduler.mergeCount(), "纯单向链不该合并");
        assertEquals(4, scheduler.regions().size());
    }

    @Test
    void shutdownStopsTicking() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.shutdown();

        List<RegionId> ticked = new ArrayList<>();
        scheduler.tickAll(region -> ticked.add(region.id()));

        assertTrue(ticked.isEmpty(), "停机后不再 tick");
    }

    @Test
    void parallelismIsOneInSerialMode() {
        SerialRegionScheduler scheduler = scheduler();
        scheduler.activate(a, "test");
        scheduler.activate(b, "test");
        assertEquals(1, scheduler.parallelism());
    }
}
