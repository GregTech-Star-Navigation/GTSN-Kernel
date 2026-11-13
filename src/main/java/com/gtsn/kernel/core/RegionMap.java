package com.gtsn.kernel.core;

import java.util.List;

/**
 * 区块 → region 的映射（切分策略）。
 *
 * <p>二期只有一种实现：固定网格（{@link GridRegionMap}）。三期会加入「按玩家位置动态聚合」，
 * 四期加入「按 mod 亲和半径合并」—— 都实现这个接口，调度器不用改。</p>
 */
public interface RegionMap {

    /** 某个区块属于哪个 region。 */
    RegionId regionOf(int chunkX, int chunkZ);

    /** 当前所有 region（按 {@link RegionId#compareTo} 排序，保证 tick 顺序确定）。 */
    List<RegionId> regions();

    /** region 覆盖的区块数（用于监控并行度是否退化）。 */
    int chunkCount(RegionId region);

    /**
     * 固定网格切分：每边 {@code regionSize} 个区块一个 region。
     *
     * <p>为什么用网格而不是「按玩家聚合」：二期要的是<b>确定性</b> —— 同一份输入必须
     * 产生同一份切分，否则一致性测试没法比对。按玩家位置聚合引入浮点/顺序依赖，留到三期。</p>
     */
    static RegionMap grid(int regionSize) {
        return new GridRegionMap(regionSize);
    }
}
