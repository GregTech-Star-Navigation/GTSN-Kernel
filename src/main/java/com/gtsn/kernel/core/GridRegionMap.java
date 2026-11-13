package com.gtsn.kernel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 固定网格切分：每边 {@code regionSize} 个区块一个 region。
 *
 * <p>坐标用 {@link Math#floorDiv} 处理，所以负数区块坐标（世界原点以西/以北）也落在正确的
 * region 里 —— 用 {@code /} 会得到 -1/16 = 0，把西侧和东侧混进同一个 region。</p>
 */
public final class GridRegionMap implements RegionMap {

    private final int regionSize;

    public GridRegionMap(int regionSize) {
        if (regionSize <= 0) {
            throw new IllegalArgumentException("regionSize must be positive: " + regionSize);
        }
        this.regionSize = regionSize;
    }

    public int regionSize() {
        return regionSize;
    }

    @Override
    public RegionId regionOf(int chunkX, int chunkZ) {
        return new RegionId(Math.floorDiv(chunkX, regionSize), Math.floorDiv(chunkZ, regionSize));
    }

    @Override
    public List<RegionId> regions() {
        // 固定网格没有「已加载」的概念，所以这里只返回一个占位实现：
        // 真正参与 tick 的 region 由调度器按已加载区块集合决定（见 SerialRegionScheduler）。
        return List.of();
    }

    @Override
    public int chunkCount(RegionId region) {
        Objects.requireNonNull(region, "region");
        return regionSize * regionSize;
    }

    /** 某个 region 覆盖的区块坐标范围（含端点）。 */
    public record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }
    }

    /** 某个 region 的区块范围，用于日志与探针。 */
    public ChunkRange rangeOf(RegionId region) {
        int minX = region.x() * regionSize;
        int minZ = region.z() * regionSize;
        return new ChunkRange(minX, minZ, minX + regionSize - 1, minZ + regionSize - 1);
    }

    /** 从一组已加载区块反推涉及的 region 列表（排序去重）。 */
    public List<RegionId> regionsOf(Iterable<long[]> chunks) {
        List<RegionId> result = new ArrayList<>();
        for (long[] chunk : chunks) {
            RegionId id = regionOf((int) chunk[0], (int) chunk[1]);
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        result.sort(null);
        return result;
    }
}
