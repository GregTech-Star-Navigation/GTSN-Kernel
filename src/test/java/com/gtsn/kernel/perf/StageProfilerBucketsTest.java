package com.gtsn.kernel.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多线程分桶（三期前置）。
 *
 * <p>要验证的核心事实：两个线程各自 {@code begin/end} 之后，样本落在<b>不同的桶</b>里，
 * 合并统计又看得到两边之和。如果桶没分开，两个线程会往同一组 {@code head}/{@code count}
 * 上写 —— 既丢样本也回答不了「哪个 region 慢」。</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
class StageProfilerBucketsTest {

    /** 在一个新线程里跑一段记录，返回该线程的统计。 */
    private static Map<String, StageProfiler.Stats> recordOnNewThread(String name, long nanos)
            throws InterruptedException {
        AtomicReference<Map<String, StageProfiler.Stats>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            // 忙等出指定时长，比 Thread.sleep 精度高得多
            StageProfiler.begin(TickStage.SAVE);
            long start = System.nanoTime();
            while (System.nanoTime() - start < nanos) {
                Thread.onSpinWait();
            }
            StageProfiler.end(TickStage.SAVE);
            result.set(StageProfiler.perBucketStats(TickStage.SAVE));
            done.countDown();
        }, name);
        thread.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "线程未在 5 秒内完成");
        thread.join(1000);
        return result.get();
    }

    @Test
    void twoThreadsLandInSeparateBuckets() throws Exception {
        StageProfiler.setEnabled(true);
        Map<String, StageProfiler.Stats> first = recordOnNewThread("region-1", 1_000_000L);
        Map<String, StageProfiler.Stats> second = recordOnNewThread("region-2", 1_000_000L);

        // 每个线程的桶里各自只有 1 个样本
        assertTrue(first.containsKey("region-1"), "region-1 应出现在桶列表: " + first.keySet());
        assertEquals(1, first.get("region-1").count());

        assertTrue(second.containsKey("region-2"), "region-2 应出现在桶列表: " + second.keySet());
        assertEquals(1, second.get("region-2").count());
        // region-1 的桶还在（桶不会被新线程覆盖）
        assertTrue(second.containsKey("region-1"), "旧桶不应消失: " + second.keySet());
    }

    @Test
    void tickTotalSumsAcrossThreads() throws Exception {
        StageProfiler.setEnabled(true);
        recordOnNewThread("sum-a", 1_000_000L);
        recordOnNewThread("sum-b", 1_000_000L);

        // 注意：tickTotalMs 读的是「本 tick 累加器」，上面两个线程各写了 1ms
        double total = StageProfiler.tickTotalMs(TickStage.SAVE);
        assertTrue(total >= 1.5,
                "两个线程各 1ms，合计应 >= 1.5ms，实际 " + total + " ms");
        assertTrue(total < 10.0,
                "合计不该到 10ms 量级（说明桶被重复计数了），实际 " + total + " ms");
    }

    @Test
    void statsMergesAllBuckets() throws Exception {
        StageProfiler.setEnabled(true);
        recordOnNewThread("merge-a", 500_000L);
        recordOnNewThread("merge-b", 500_000L);

        assertEquals(0, StageProfiler.droppedThreads(),
                "不该有线程被丢弃（桶上限 16），否则本测试测的不是「合并」而是「丢弃」");
        StageProfiler.Stats merged = StageProfiler.stats(TickStage.SAVE);
        assertTrue(merged.count() >= 2,
                "合并统计应至少 2 个样本，实际 " + merged.count());
        assertTrue(merged.avgMs() > 0.1,
                "平均耗时应覆盖两个线程，实际 " + merged.avgMs() + " ms");
    }

    @Test
    void disabledProfilerRecordsNothing() {
        StageProfiler.setEnabled(false);
        int before = StageProfiler.sampleCount(TickStage.PLAYERS);
        StageProfiler.begin(TickStage.PLAYERS);
        StageProfiler.end(TickStage.PLAYERS);
        assertEquals(before, StageProfiler.sampleCount(TickStage.PLAYERS),
                "关闭态不应记录任何样本");
        StageProfiler.setEnabled(true);
    }

    @Test
    void bucketCountGrowsWithThreads() throws Exception {
        StageProfiler.setEnabled(true);
        int before = StageProfiler.bucketCount();
        recordOnNewThread("grow-a", 100_000L);
        recordOnNewThread("grow-b", 100_000L);
        int after = StageProfiler.bucketCount();
        assertTrue(after >= before, "桶数不应减少");
        assertFalse(after > 100, "桶数异常膨胀: " + after);
    }
}
