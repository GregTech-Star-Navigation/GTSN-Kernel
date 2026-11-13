package com.gtsn.kernel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRegionMapTest {

    private final GridRegionMap map = new GridRegionMap(16);

    @Test
    void originChunkFallsInRegionZero() {
        assertEquals(new RegionId(0, 0), map.regionOf(0, 0));
        assertEquals(new RegionId(0, 0), map.regionOf(15, 15));
    }

    @Test
    void chunkAtBoundaryStartsNewRegion() {
        assertEquals(new RegionId(1, 0), map.regionOf(16, 0));
        assertEquals(new RegionId(0, 1), map.regionOf(0, 16));
        assertEquals(new RegionId(1, 1), map.regionOf(16, 16));
    }

    /**
     * 负数坐标必须用 floorDiv —— 用 {@code /} 会得到 -1/16 = 0，把世界原点以西的区块
     * 和原点以东的区块混进同一个 region。
     */
    @Test
    void negativeCoordinatesUseFloorDivision() {
        assertEquals(new RegionId(-1, -1), map.regionOf(-1, -1));
        assertEquals(new RegionId(-1, 0), map.regionOf(-16, 0));
        assertEquals(new RegionId(-2, 0), map.regionOf(-17, 0));
        assertEquals(new RegionId(-1, -1), map.regionOf(-16, -16));
        assertEquals(new RegionId(-2, -2), map.regionOf(-17, -17));
    }

    @Test
    void rangeCoversExactlyTheGridCell() {
        GridRegionMap.ChunkRange range = map.rangeOf(new RegionId(2, -1));
        assertEquals(32, range.minX());
        assertEquals(-16, range.minZ());
        assertEquals(47, range.maxX());
        assertEquals(-1, range.maxZ());
        assertTrue(range.contains(32, -16));
        assertTrue(range.contains(47, -1));
        assertTrue(!range.contains(48, -1));
        assertTrue(!range.contains(32, 0));
    }

    @Test
    void rejectsNonPositiveRegionSize() {
        assertThrows(IllegalArgumentException.class, () -> new GridRegionMap(0));
        assertThrows(IllegalArgumentException.class, () -> new GridRegionMap(-4));
    }
}
