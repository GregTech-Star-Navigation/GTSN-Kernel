package com.gtsn.kernel.core;

import java.util.Optional;

/**
 * 跨 region 访问协议 —— 整个多线程方案的核心取舍就落在这个接口上。
 *
 * <p>Folia 的做法是<b>硬失败</b>（跨 region 直接抛异常，逼插件改写）。本方案改成<b>软处理</b>：</p>
 *
 * <table border="1">
 *   <caption>三种访问类型的处理</caption>
 *   <tr><th>类型</th><th>处理</th><th>代价</th></tr>
 *   <tr><td>读</td><td>放行，读主内存 + 内存屏障</td><td>可能读到同 tick 内的旧值</td></tr>
 *   <tr><td>写</td><td>投递到目标 region 队列，下一 tick 生效</td><td>延迟 1 tick</td></tr>
 *   <tr><td>同步</td><td>检测依赖环 → 自动合并 region</td><td>并行度下降，但永不崩</td></tr>
 * </table>
 *
 * <p>目标：适配一个新 mod 只需加一条 JSON 规则，不改 mod 代码。</p>
 */
public interface AccessProxy {

    /**
     * 读跨 region 数据。
     *
     * @param regionId 数据所属 region
     * @param what     访问描述（用于探针记账）
     * @param reader   实际读取动作
     * @return 读到的值；region 不存在时为空
     */
    <T> Optional<T> read(int regionId, String what, Reader<T> reader);

    /**
     * 写跨 region 数据（异步，下一 tick 生效）。
     *
     * @return 是否成功入队
     */
    boolean write(int regionId, String what, Runnable mutation);

    /**
     * 同步调用跨 region（需要返回值且必须一致）。
     *
     * <p>实现必须做依赖环检测：检测到 A→B 与 B→A 同时存在时，调用
     * {@link TickScheduler#merge} 合并两者，而不是等待 —— 等待就是死锁。</p>
     *
     * @param regionId 目标 region
     * @param what     访问描述
     * @param action   实际动作
     * @param timeoutMs 超时（超时视为设计缺陷，记录并降级，不无限等待）
     */
    <T> Optional<T> sync(int regionId, String what, Reader<T> action, long timeoutMs);

    /** 读动作（允许抛受检异常由实现包装）。 */
    @FunctionalInterface
    interface Reader<T> {
        T read();
    }
}
