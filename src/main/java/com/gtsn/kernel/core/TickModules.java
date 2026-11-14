package com.gtsn.kernel.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 世界 tick 的可拆卸模块清单（三期模块化）。
 *
 * <p><b>目标</b>：把 {@code ServerLevel.tick} 里那 15 个阶段变成「能单独关掉」的模块。
 * 关掉一个模块之后世界应该照常运行，只是少了那部分行为 —— 这是判断「这个阶段到底依赖谁」
 * 的唯一硬证据。</p>
 *
 * <p><b>为什么不重写 {@code ServerLevel.tick}</b>：那等于把 108 行原版逻辑复制一份，
 * 每个 MC 版本都要重做，且和所有 mixin 改这个方法的 mod 冲突。这里只改<b>每个阶段的调用点</b>
 * —— 骨架、顺序、局部变量全部保持原样。</p>
 *
 * <p><b>开关</b>：{@code -Dgtsnkernel.modules.<name>=false}。默认全开，也就是原版行为。</p>
 */
public enum TickModules {

    /** 世界边界收缩。关掉之后边界不推进（区域不再缩小）。 */
    WORLD_BORDER("worldBorder", "世界边界"),
    /** 天气周期推进（晴/雨/雷的切换与计时）。 */
    WEATHER("weather", "天气周期"),
    /** 天空亮度缓存更新。 */
    SKY_BRIGHTNESS("skyBrightness", "天空亮度"),
    /** 世界时间推进（gameTime / dayTime / 计划任务）。 */
    TIME("time", "世界时间"),
    /** 方块调度 tick（红石、作物、流体传播的排队执行）。 */
    BLOCK_TICKS("blockTicks", "方块调度 tick"),
    /** 流体调度 tick。 */
    FLUID_TICKS("fluidTicks", "流体调度 tick"),
    /** 袭击（Raid）推进。 */
    RAIDS("raids", "袭击"),
    /** 区块系统的每 tick 维护（加载/卸载/实体追踪）。 */
    CHUNK_SOURCE("chunkSource", "区块系统"),
    /** 方块事件（活塞、音符盒这类延迟事件）。 */
    BLOCK_EVENTS("blockEvents", "方块事件"),
    /** 末影龙战斗逻辑。 */
    DRAGON_FIGHT("dragonFight", "末影龙战"),
    /** 方块实体 tick。 */
    BLOCK_ENTITIES("blockEntities", "方块实体"),
    /** 实体管理（实体分区迁移、持久化）。 */
    ENTITY_MANAGEMENT("entityManagement", "实体管理");

    private static final Map<String, Boolean> OVERRIDES = loadOverrides();

    private final String key;
    private final String label;

    TickModules(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * 该模块是否启用。
     *
     * <p>每次调用都查一个 {@code HashMap} —— 这在 tick 路径上是热点，但一次 get 是几纳秒，
     * 相对省下的一整个阶段可以忽略。真要抠可以事后加一个 volatile 快照数组。</p>
     */
    public boolean enabled() {
        Boolean override = OVERRIDES.get(key);
        return override == null || override;
    }

    private static Map<String, Boolean> loadOverrides() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (TickModules module : values()) {
            String raw = System.getProperty("gtsnkernel.modules." + module.key);
            if (raw != null) {
                result.put(module.key, Boolean.parseBoolean(raw));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 被显式关掉的模块（报告用）。 */
    public static Map<String, Boolean> overrides() {
        return OVERRIDES;
    }

    /** 按 key 找模块（报告用）。 */
    public static TickModules byKey(String key) {
        for (TickModules module : values()) {
            if (module.key.equals(key)) {
                return module;
            }
        }
        return null;
    }
}
