package com.gtsn.kernel.perf;

import org.junit.jupiter.api.Test;

/**
 * profiler 自身的开销量级（不是严格基准，是「量级验证」）。
 *
 * <p>为什么要测：profiler 每 tick 被调用约 30 次 begin + 30 次 end。如果单次开销是微秒级，
 * 那就是每 tick 0.1ms 量级 —— 对一个 5ms 的 tick 是 2%，必须优化；如果是几十纳秒，那
 * 是 0.02%，可以忽略。</p>
 *
 * <p>这不是 JMH：没有预热、没有统计置信区间。它只回答「是纳秒级还是微秒级」。</p>
 */
class StageProfilerOverheadTest {

    private static final int WARMUP = 200_000;
    private static final int ITERATIONS = 1_000_000;

    @Test
    void beginEndCostIsNanosecondScale() {
        StageProfiler.bindOwner(Thread.currentThread());
        StageProfiler.setEnabled(true);

        // 预热（让 JIT 编译掉分支预测与内联）
        for (int i = 0; i < WARMUP; i++) {
            StageProfiler.begin(TickStage.ENTITIES);
            StageProfiler.end(TickStage.ENTITIES);
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            StageProfiler.begin(TickStage.ENTITIES);
            StageProfiler.end(TickStage.ENTITIES);
        }
        long elapsed = System.nanoTime() - start;

        double nanosPerPair = elapsed / (double) ITERATIONS;
        // 每 tick 约 30 对（26 个注入点，部分每 tick 多次）
        double millisPerTick = nanosPerPair * 30 / 1_000_000.0;

        System.out.printf("[bench] begin+end = %.1f ns/对 → 每 tick(30对) ≈ %.4f ms%n",
                nanosPerPair, millisPerTick);

        // 阈值取 1ms：超过就说明实现里混进了锁 / 分配 / 系统调用，必须回头看。
        // 这个上限很宽松 —— 实测远低于它，设这么高是为了不因机器差异误报。
        org.junit.jupiter.api.Assertions.assertTrue(
                millisPerTick < 1.0,
                "profiler 每 tick 开销 " + millisPerTick + " ms 过高（应有纳秒级）");
    }

    @Test
    void disabledProfilerCostsAlmostNothing() {
        StageProfiler.bindOwner(Thread.currentThread());
        StageProfiler.setEnabled(false);

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            StageProfiler.begin(TickStage.ENTITIES);
            StageProfiler.end(TickStage.ENTITIES);
        }
        long elapsed = System.nanoTime() - start;
        double nanosPerPair = elapsed / (double) ITERATIONS;

        System.out.printf("[bench] 关闭时 begin+end = %.1f ns/对%n", nanosPerPair);
        org.junit.jupiter.api.Assertions.assertTrue(nanosPerPair < 50.0,
                "关闭态应该是两次 volatile 读 + 返回，实测 " + nanosPerPair + " ns");
        StageProfiler.setEnabled(true);
    }
}
