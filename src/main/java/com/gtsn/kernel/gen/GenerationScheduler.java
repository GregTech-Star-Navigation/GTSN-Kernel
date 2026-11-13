package com.gtsn.kernel.gen;

import java.util.concurrent.CompletableFuture;

/**
 * 地形生成调度接口（五期）—— 内核与地形生成 mod 之间的唯一契约。
 *
 * <p><b>边界</b>：地形生成（噪声、生物群系、结构、填充）不归本内核，由
 * {@code GTSN-Terrain-Generation} 负责。内核只负责「在哪个线程跑」和「生成结果怎么安全
 * 注入世界」。</p>
 *
 * <p>三条约束，地形 mod 必须遵守：</p>
 * <ol>
 *   <li><b>生成阶段完全不碰 {@code Level}</b> —— 只能读种子、生物群系参数、自己的配置。
 *       需要世界数据必须走 {@link #inject}；</li>
 *   <li><b>{@code inject} 在目标 region 线程执行</b> —— 不能跨线程写 chunk；</li>
 *   <li><b>同一 {@code ChunkPos} 同时只有一个生成任务</b> —— 内核保证，地形 mod 不用加锁。</li>
 * </ol>
 */
public interface GenerationScheduler {

    /**
     * 提交一个区块生成请求。
     *
     * <p>内核决定在哪个线程跑；同一 {@code ChunkPos} 的请求串行化。</p>
     *
     * @param chunkX   区块 X
     * @param chunkZ   区块 Z
     * @param target   目标生成阶段（原版 {@code ChunkStatus} 的等价物）
     * @param generator 实际生成逻辑（纯计算，不碰 Level）
     * @return 生成结果；失败时异常完成
     */
    <T> CompletableFuture<T> request(int chunkX, int chunkZ, String target, Generator<T> generator);

    /**
     * 把生成结果注入世界（必须在目标 region 线程执行）。
     *
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @param data   生成结果
     * @param onInjected 注入完成回调（同一 region 线程）
     */
    <T> void inject(int chunkX, int chunkZ, T data, Runnable onInjected);

    /** 纯计算生成器：不碰 Level、不读写全局可变状态。 */
    @FunctionalInterface
    interface Generator<T> {
        T generate();
    }
}
