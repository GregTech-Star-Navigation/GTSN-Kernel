package com.gtsn.kernel.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 忙等 lane 池（三期 P2c）。
 *
 * <p><b>为什么不用 {@code CompletableFuture}</b>：P2a 实测并行把 {@code block_entities}
 * 的 p99 从 0.553 ms 顶到 449.972 ms。根因是每 tick 都要唤醒睡眠的工作线程 ——
 * 线程调度 + 上下文切换是 10–100 μs 量级，而 289 个熔炉的总工作量只有 0.375 ms，
 * 根本不够抵。</p>
 *
 * <p><b>这里换成什么</b>：常驻线程<b>不睡眠</b>，一直自旋轮询自己的槽位；主线程提交后也自旋
 * 等完成计数归零。整条路径上没有 park/unpark、没有 future 分配、没有 `allOf` 的完成树。</p>
 *
 * <p><b>代价是 CPU</b>：N 条 lane 线程在空闲时也在自旋。这就是 Folia 那条「≥16 物理核、
 * 线程总占用 &lt; 80%」的由来 —— 它默认接受几核专门空转。本机 18 核 36 线程，开 2 lane
 * 意味着常驻多占 2 个核。</p>
 *
 * <p><b>为什么主线程也要自旋而不是 yield</b>：{@code Thread.yield()} 在 Windows 上经常
 * 变成一次完整的调度（让出 + 等回来），那正是要避免的开销。自旋几十次之后再退化为 yield，
 * 兼顾「短任务快」与「长任务不烧死 CPU」。</p>
 */
public final class SpinLanePool {

    /** 自旋多少次之后开始穿插 yield（经验值：够覆盖一次短 tick 的等待）。 */
    private static final int SPIN_BEFORE_YIELD = 200;

    private final Thread[] threads;
    private final AtomicReferenceArray<Runnable> slots;

    /** 本 tick 还没跑完的 lane 数。主线程自旋等它归零。 */
    private final AtomicInteger pending = new AtomicInteger();

    private volatile boolean running = true;

    public SpinLanePool(int lanes) {
        int count = Math.max(1, lanes);
        this.slots = new AtomicReferenceArray<>(count);
        this.threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            final int lane = i;
            Thread thread = new Thread(() -> loop(lane), "GTSN-Spin-" + lane);
            thread.setDaemon(true);
            // 自旋线程给最低优先级：空闲自旋不该抢主线程的 CPU
            thread.setPriority(Thread.MIN_PRIORITY);
            this.threads[i] = thread;
            thread.start();
        }
    }

    public int lanes() {
        return slots.length();
    }

    /** lane 线程主循环：一直轮询自己的槽位，直到停机。 */
    private void loop(int lane) {
        while (running) {
            Runnable task = slots.getAndSet(lane, null);
            if (task == null) {
                // 空闲：轻量自旋。onSpinWait 在 x86 上是 PAUSE 指令，
                // 让出流水线但不进内核态。
                Thread.onSpinWait();
                continue;
            }
            try {
                task.run();
            } catch (Throwable failure) {
                // 单个 lane 的异常不能拖垮整个池 —— 记下来，继续跑。
                LaneFailures.record(lane, failure);
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    /**
     * 提交一组任务并<b>阻塞到全部完成</b>。
     *
     * <p>这是 tick 路径上的调用：主线程在这里等，等的是「本 tick 的 lane 都跑完」。
     * 没有 future、没有 allOf —— 只是一个计数归零。</p>
     *
     * @param tasks 每个 lane 一个任务；null 表示该 lane 本 tick 没活
     */
    public void runAll(List<Runnable> tasks) {
        int assigned = 0;
        for (int i = 0; i < tasks.size() && i < slots.length(); i++) {
            Runnable task = tasks.get(i);
            if (task == null) {
                continue;
            }
            slots.set(i, task);
            assigned++;
        }
        if (assigned == 0) {
            return;
        }
        pending.set(assigned);

        // 自旋等归零：前 SPIN_BEFORE_YIELD 次纯自旋（快路径），之后穿插 yield。
        int spins = 0;
        while (pending.get() > 0) {
            if (++spins < SPIN_BEFORE_YIELD) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }

    /** 停机。 */
    public void shutdown() {
        running = false;
        for (Thread thread : threads) {
            try {
                thread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** lane 任务抛出的异常（不该发生，发生了要能看见）。 */
    private static final class LaneFailures {
        private static final AtomicInteger COUNT = new AtomicInteger();
        private static volatile String last;

        static void record(int lane, Throwable failure) {
            COUNT.incrementAndGet();
            last = "lane " + lane + ": " + failure;
        }

        static int count() {
            return COUNT.get();
        }

        static String last() {
            return last;
        }
    }

    /** 出错的 lane 任务数。 */
    public static int failureCount() {
        return LaneFailures.count();
    }

    /** 最后一个 lane 异常的描述。 */
    public static String lastFailure() {
        return LaneFailures.last();
    }
}
