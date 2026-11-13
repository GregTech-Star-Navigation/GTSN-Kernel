package com.gtsn.kernel.core;

/**
 * region 的坐标标识（region 网格坐标，不是区块坐标）。
 *
 * <p>用 record 而不是 int：三期要按玩家位置动态切分，region id 会随玩家移动变化，
 * 用二维坐标可以避免「id 复用」导致的陈旧引用问题。</p>
 *
 * @param x region 网格 X
 * @param z region 网格 Z
 */
public record RegionId(int x, int z) implements Comparable<RegionId> {

    /** 便于日志与 CSV 的短标识。 */
    public String shortName() {
        return x + "," + z;
    }

    @Override
    public int compareTo(RegionId other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }

    @Override
    public String toString() {
        return "R(" + x + "," + z + ")";
    }
}
