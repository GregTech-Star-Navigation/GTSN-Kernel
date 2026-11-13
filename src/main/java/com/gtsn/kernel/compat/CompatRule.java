package com.gtsn.kernel.compat;

import java.util.List;

/**
 * 一个 mod 的兼容规则（四期）。
 *
 * <p>三个旋钮覆盖绝大多数情况：</p>
 * <ul>
 *   <li>{@link #affinityRadius()} —— 这个 mod 碰的区块必须合并到同一 region；</li>
 *   <li>{@link #exclusiveThread()} —— 这个 mod 的全部调用转发到固定线程（最保守）；</li>
 *   <li>{@link #staticUnsafe()} —— 列出的静态字段由框架加锁包装。</li>
 * </ul>
 *
 * <p>规则由 {@code compat-probe} 模式自动产出草案，人只需要确认或微调。</p>
 *
 * @param modId           mod id
 * @param affinityRadius  区块亲和半径（0 = 不额外合并）
 * @param exclusiveThread 是否独占线程
 * @param staticUnsafe    已知不安全的静态字段（全限定名）
 * @param reason          为什么需要这条规则（探针产出时自动填）
 */
public record CompatRule(
        String modId,
        int affinityRadius,
        boolean exclusiveThread,
        List<String> staticUnsafe,
        String reason) {

    public CompatRule {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        affinityRadius = Math.max(0, affinityRadius);
        staticUnsafe = staticUnsafe == null ? List.of() : List.copyOf(staticUnsafe);
        reason = reason == null ? "" : reason;
    }

    /** 最保守的规则：独占线程。 */
    public static CompatRule exclusive(String modId, String reason) {
        return new CompatRule(modId, 0, true, List.of(), reason);
    }
}
