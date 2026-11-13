package com.gtsn.kernel.core;

/**
 * 线程守卫（二期基础设施，一期只做最小实现）。
 *
 * <p>多 region 之后，每个 region 有自己的线程；任何跨 region 的写入都必须在
 * 目标 region 线程上执行。这个类提供两件事：</p>
 * <ul>
 *   <li>{@link #bindServerThread(Thread)} —— 登记服务端主线程；</li>
 *   <li>{@link #checkOnServerThread(String)} —— 断言当前在主线程，越界时按配置
 *       记录或抛错。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code RenderSystem.isOnGameThread()}</b>：1.20.1 里它被硬编码成
 * {@code return true}，拿它当守卫等于没有守卫。</p>
 */
public final class ThreadGuard {

    private static volatile Thread serverThread;
    /** 越界时是否抛错（默认只记录，避免把本来能跑的 mod 弄崩）。 */
    private static volatile boolean failFast = false;
    /** 已记录的越界次数。 */
    private static volatile long violations = 0L;

    private ThreadGuard() {
    }

    public static void bindServerThread(Thread thread) {
        serverThread = thread;
    }

    public static Thread serverThread() {
        return serverThread;
    }

    /** 当前是否在服务端主线程上（未绑定线程时恒为 true）。 */
    public static boolean onServerThread() {
        Thread bound = serverThread;
        return bound == null || Thread.currentThread() == bound;
    }

    /**
     * 断言当前在服务端主线程。
     *
     * @param what 调用点描述，用于日志定位
     * @return 是否在主线程
     */
    public static boolean checkOnServerThread(String what) {
        if (onServerThread()) {
            return true;
        }
        violations++;
        if (failFast) {
            throw new IllegalStateException("cross-thread access: " + what
                    + " (expected server thread, was " + Thread.currentThread().getName() + ")");
        }
        return false;
    }

    public static void setFailFast(boolean value) {
        failFast = value;
    }

    public static long violationCount() {
        return violations;
    }
}
