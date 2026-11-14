package com.gtsn.kernel.bridge;

import com.gtsn.kernel.core.RegionId;
import com.gtsn.kernel.core.SerialRegionScheduler;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * {@code tickChildren} 里对 {@code ServerLevel.tick} 的调用重定向到这里（二期 2b）。
 *
 * <p><b>为什么放在 mixin 包</b>：它直接接触 {@code ServerLevel}，按项目约定只有 mixin 包
 * 能 import {@code net.minecraft.*}。</p>
 *
 * <p><b>开关</b>：{@code -Dgtsnkernel.region.enabled=true} 时走调度器路径，否则直接调
 * {@code level.tick(...)}（原版路径）。两条路径的<b>唯一区别</b>是「谁调用这个方法」——
 * 方法体、调用顺序、参数都不变。</p>
 *
 * <p>二期调度器是串行的（{@link SerialRegionScheduler#parallelism()} 恒为 1），所以
 * 开启后行为应当与原版<b>逐 tick 一致</b>。一致性测试就是验证这一点。</p>
 */
public final class TickRedirectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("GTSN-Kernel/Region");
    private static final boolean ENABLED = Boolean.getBoolean("gtsnkernel.region.enabled");

    /** 每个维度一个 region（三期再按区块网格细分）。 */
    private static final Map<String, RegionId> DIMENSION_REGIONS = new HashMap<>();

    private static SerialRegionScheduler scheduler;
    private static long dispatchCount;

    private TickRedirectHandler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** 服务端启动时建立调度器。 */
    public static void bootstrap() {
        if (!ENABLED) {
            LOGGER.info("[GTSN-Kernel] region dispatcher disabled (vanilla tick path)");
            return;
        }
        scheduler = new SerialRegionScheduler(com.gtsn.kernel.core.RegionMap.grid(16));
        LOGGER.info("[GTSN-Kernel] region dispatcher ENABLED (serial, parallelism=1)");
    }

    /**
     * 重定向目标：{@code tickChildren} 原本在这里调 {@code level.tick(hasTimeLeft)}。
     *
     * @param level       被 tick 的世界
     * @param hasTimeLeft 原样透传的 {@link BooleanSupplier}
     */
    public static void dispatch(ServerLevel level, BooleanSupplier hasTimeLeft) {
        if (!ENABLED || scheduler == null) {
            level.tick(hasTimeLeft);
            return;
        }
        dispatchCount++;
        RegionId id = regionOf(level);
        scheduler.activate(id, "dimension:" + level.dimension().location());
        // 串行调度器在当前线程直接执行，等价于原版调用；三期换成线程池后这里不变。
        scheduler.submit(id, () -> level.tick(hasTimeLeft));
    }

    /** 维度 → region（缓存，避免每次分配）。 */
    private static RegionId regionOf(ServerLevel level) {
        String key = level.dimension().location().toString();
        RegionId cached = DIMENSION_REGIONS.get(key);
        if (cached != null) {
            return cached;
        }
        // 用维度路径的稳定 hash 当 region x 坐标；z 恒为 0（二期每个维度一个 region）。
        RegionId created = new RegionId(key.hashCode(), 0);
        DIMENSION_REGIONS.put(key, created);
        return created;
    }

    public static long dispatchCount() {
        return dispatchCount;
    }

    public static SerialRegionScheduler scheduler() {
        return scheduler;
    }

    /** 停机时清理。 */
    public static void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        LOGGER.info("[GTSN-Kernel] region dispatcher stopped ({} dispatches)", dispatchCount);
    }
}
