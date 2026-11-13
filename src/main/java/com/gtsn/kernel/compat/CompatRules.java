package com.gtsn.kernel.compat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则表（四期）—— 从 {@code config/gtsnkernel-compat.json} 加载，运行时可查。
 *
 * <p>一期只放容器与查询；JSON 解析、探针产出、热重载在四期补。</p>
 *
 * <p>未在表里的 mod 走 {@link #DEFAULT}（不额外合并、不独占线程）—— 遇到越界时由
 * 运行时守卫自动合并 region 并记录，攒够日志再补规则。</p>
 */
public final class CompatRules {

    /** 未声明 mod 的默认规则：不额外合并，允许被自动降级。 */
    public static final CompatRule DEFAULT = new CompatRule("__default__", 0, false, List.of(), "");

    private static final Map<String, CompatRule> RULES = new ConcurrentHashMap<>();

    private CompatRules() {
    }

    /** 查某个 mod 的规则（未声明返回 {@link #DEFAULT}）。 */
    public static CompatRule of(String modId) {
        if (modId == null) {
            return DEFAULT;
        }
        return RULES.getOrDefault(modId, DEFAULT);
    }

    /** 注册/覆盖一条规则（探针产出或手动加载时调用）。 */
    public static void put(CompatRule rule) {
        RULES.put(rule.modId(), rule);
    }

    /** 当前所有显式声明的规则。 */
    public static List<CompatRule> all() {
        return List.copyOf(RULES.values());
    }

    /** 清空（重载前调用）。 */
    public static void clear() {
        RULES.clear();
    }

    /**
     * 计算某组 mod 需要的区块亲和半径（取最大值）。
     *
     * <p>三期切分 region 时用：同一 region 内的区块必须满足所有在场 mod 的亲和要求。</p>
     */
    public static int affinityRadiusFor(Iterable<String> modIds) {
        int radius = 0;
        for (String modId : modIds) {
            radius = Math.max(radius, of(modId).affinityRadius());
        }
        return radius;
    }
}
