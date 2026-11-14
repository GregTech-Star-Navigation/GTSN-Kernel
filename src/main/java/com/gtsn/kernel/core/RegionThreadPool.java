package com.gtsn.kernel.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * region 线程池：把一个 region 稳定绑定到一条线程上（三期）。
 *
 * <p><b>为什么「绑定」而不是直接丢给 {@code ForkJoinPool}</b>：同一个 region 的两次 tick
 * 必须落在同一线程上，否则 region 内的「串行」保证就没了 —— 原版 tick 的很多不变量
 * （比如方块实体 tick 的先来后到）依赖这一点。做法是 {@code hash(regionId) % parallelism}
 * 映射到固定的单线程执行器。</p>
 *
 * <p><b>并行度 = 1 时退化成一个执行器</b>，行为与 {@link SerialRegionScheduler} 等价，
 * 只是多了一层线程切换 —— 所以生产配置下默认不开（见 {@code gtsnkernel.region.parallelism}）。</p>
 *
 * <p><b>为什么不是「每个 region 一条线程」</b>：region 数量随视图距离动态变化，一个
 * 1000 区块的世界可能切出上百个 region。线程数固定、region 用哈希分摊，才不会线程爆炸。</p>
 */
public final class RegionThreadPool {

    /** 单线程执行器：名字里带序号，profiler 的按线程分桶能直接看出是哪个 lane。 */
    private final ExecutorService[] lanes;

    private final int parallelism;

    private volatile boolean shutdown;

    public RegionThreadPool(int parallelism) {
        this.parallelism = Math.max(1, parallelism);
        this.lanes = new ExecutorService[this.parallelism];
        for (int i = 0; i < this.parallelism; i++) {
            final int lane = i;
            this.lanes[i] = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GTSN-Region-" + lane);
                // 守护线程：即使 shutdown 没走完，也不阻止 JVM 退出
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    public int parallelism() {
        return parallelism;
    }

    /**
     * region 落在哪条 lane 上。
     *
     * <p>用 {@link RegionId#hashCode()} 而不是自增计数：region 是动态出现/消失的，
     * 自增会让同一 region 在不同时刻换线程，破坏「同 region 串行」。</p>
     */
    public int laneOf(RegionId regionId) {
        return Math.floorMod(regionId.hashCode(), parallelism);
    }

    /**
     * 提交任务到 region 对应的 lane。
     *
     * @return 完成信号；调用方需要同步语义时 {@code .join()} 即可
     */
    public CompletableFuture<Void> submit(RegionId regionId, Runnable task) {
        if (shutdown) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("region pool already shut down"));
        }
        int lane = laneOf(regionId);
        return CompletableFuture.runAsync(task, lanes[lane]);
    }

    /**
     * 底层执行器数组（只给需要「按 lane 直接提交」的调用方用）。
     *
     * <p>暴露它不是图方便：方块实体并行那边要按 lane 分组后一次性提交整组任务，
     * 走 {@link #submit(RegionId, Runnable)} 会每个 ticker 一次 future，开销比 tick 本身还大。</p>
     */
    public ExecutorService[] lanes() {
        return lanes;
    }

    /** 停机：不再接受新任务，等待已提交的跑完（最多 5 秒）。 */
    public void shutdown() {
        shutdown = true;
        for (ExecutorService lane : lanes) {
            lane.shutdown();
        }
        try {
            for (ExecutorService lane : lanes) {
                if (!lane.awaitTermination(5, TimeUnit.SECONDS)) {
                    lane.shutdownNow();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            for (ExecutorService lane : lanes) {
                lane.shutdownNow();
            }
        }
    }
}
