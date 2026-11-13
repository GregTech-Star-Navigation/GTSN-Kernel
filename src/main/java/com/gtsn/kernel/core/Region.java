package com.gtsn.kernel.core;

import java.util.List;

/**
 * 一个 region —— 一组相邻区块及其上的实体 / 方块实体，由**一个**线程独占 tick。
 *
 * <p><b>二期只会有 1 个 region</b>（并行度 = 1，行为与原版单线程逐 tick 一致）；三期开始
 * 按玩家位置与区块邻接切分。这个接口先定下来，是为了让二期的调度器、守卫层、转发层都
 * 针对抽象编程，三期切分时不需要改调用方。</p>
 *
 * <p>不变式（写进实现类断言）：</p>
 * <ul>
 *   <li>任何 chunk / entity 在任意时刻只属于一个 region；</li>
 *   <li>只有该 region 的线程能写它的数据；</li>
 *   <li>跨 region 的同步调用必须先经过 {@link AccessProxy}，触发依赖检测。</li>
 * </ul>
 */
public interface Region {

    /** region 的稳定标识（重启后按规则重新分配）。 */
    RegionId id();

    /** 当前拥有这个 region 的线程；{@code null} 表示尚未启动。 */
    Thread owner();

    /** 这个 region 覆盖的区块数（用于监控并行度是否退化）。 */
    int chunkCount();

    /** 这个 region 是否处于运行状态。 */
    boolean running();

    /**
     * 一个 region 的划分依据，用于日志与探针输出。
     *
     * @param reason  人类可读的原因（如 {@code "player:Steve"}、{@code "affinity:create"}）
     * @param merged  是否由自动合并产生
     */
    record Provenance(String reason, boolean merged) {
    }

    /** 这个 region 的划分依据列表（合并后可能有多个）。 */
    List<Provenance> provenance();
}
