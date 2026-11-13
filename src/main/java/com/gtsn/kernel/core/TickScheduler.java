package com.gtsn.kernel.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * region 调度器 —— 决定「哪个 region 在哪个线程上跑、什么时候跑」。
 *
 * <p>二期实现为「按 {@link RegionId} 顺序串行 tick」（{@link SerialRegionScheduler}），
 * 行为与原版一致；三期把串行换成并行，逻辑不变。</p>
 *
 * <p>与原版 tick 的关系：调度器<b>不替换</b> {@code ServerLevel.tick}，而是决定它被哪个线程
 * 调用。原版的阶段顺序、内部逻辑完全不变。</p>
 */
public interface TickScheduler {

    /** 当前所有 region 的只读快照（已合并的只保留代表）。 */
    List<Region> regions();

    /** 当前并行度（同时跑 tick 的 region 数）。二期恒为 1。 */
    int parallelism();

    /**
     * 提交一个任务到指定 region 的线程执行。
     *
     * @return 完成信号；调用方可以选择等待（同步）或不等待（异步写）
     */
    CompletableFuture<Void> submit(RegionId regionId, Runnable task);

    /**
     * 请求合并两个 region（跨 region 同步调用检测到依赖环时由 {@link AccessProxy} 触发）。
     *
     * <p>合并是单向的、不可逆的（本次会话内）。合并后并行度下降，但保证不死锁。</p>
     *
     * @return 合并后的代表 region；两者本就同属一个 region 时返回该 region
     */
    RegionId merge(RegionId regionA, RegionId regionB, String reason);

    /** 已发生的合并次数（监控用）。 */
    long mergeCount();

    /** 停机：等待所有 region 完成当前 tick。 */
    void shutdown();
}
