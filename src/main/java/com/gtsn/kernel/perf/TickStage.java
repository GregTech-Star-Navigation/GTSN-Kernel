package com.gtsn.kernel.perf;

/**
 * 原版服务端 tick 的可观测阶段。
 *
 * <p>每个阶段对应 {@code MinecraftServer.tickServer} / {@code tickChildren} /
 * {@code ServerLevel.tick} 里的一段执行。{@link #depth()} 决定命令输出时的缩进层级，
 * 让「谁包含谁」一眼可见。</p>
 *
 * <p><b>为什么这些阶段是拆分的边界</b>：它们全都是原版里的<b>方法调用</b>，可以用
 * {@code @Inject(at = INVOKE)} 或注入到被调方法自身的 HEAD/RETURN 打点，不需要
 * {@code @Overwrite}。内联循环体（如 {@code ServerLevel.tick} 里对实体的遍历）没有
 * 方法边界，只能整段计入 {@link #ENTITIES}。</p>
 */
public enum TickStage {

    /** 整个 {@code MinecraftServer.tickServer}。 */
    SERVER_TICK("server_tick", "服务器总", 0),
    /** {@code tickChildren} —— tickServer 的主体。 */
    SERVER_CHILDREN("server_children", "tickChildren", 1),
    /** 数据包 tick 函数（{@code ServerFunctionManager.tick}）。 */
    FUNCTIONS("functions", "函数", 2),
    /** 每个世界一次 {@code ServerLevel.tick}（多个世界会累加）。 */
    LEVEL_TICK("level_tick", "世界 tick", 2),
    /** 区块系统：加载调度 + 区块 tick（{@code ServerChunkCache.tick}）。 */
    CHUNK_CACHE("chunk_cache", "区块系统", 3),
    /** 区块管理：加载/卸载、实体追踪（{@code ChunkMap.tick}）。 */
    CHUNK_MAP("chunk_map", "区块管理", 4),
    /** 方块与流体调度 tick（{@code LevelTicks.tick}，两个实例累加）。 */
    LEVEL_TICKS("level_ticks", "方块/流体 tick", 3),
    /** 袭击（{@code Raids.tick}）。 */
    RAIDS("raids", "袭击", 3),
    /** 实体 tick（{@code EntityTickList.forEach}）—— 通常是最重的一段。 */
    ENTITIES("entities", "实体", 3),
    /** 方块实体 tick（{@code Level.tickBlockEntities}）。 */
    BLOCK_ENTITIES("block_entities", "方块实体", 3),
    /** 实体分区管理（{@code PersistentEntitySectionManager.tick}）。 */
    ENTITY_SECTIONS("entity_sections", "实体分区", 3),
    /** 网络连接收发（{@code ServerConnectionListener.tick}）。 */
    CONNECTIONS("connections", "网络连接", 2),
    /** 玩家列表（{@code PlayerList.tick}）。 */
    PLAYERS("players", "玩家", 2),
    /** 自动保存（{@code saveEverything}）。 */
    SAVE("save", "自动保存", 1);

    private final String key;
    private final String label;
    private final int depth;

    TickStage(String key, String label, int depth) {
        this.key = key;
        this.label = label;
        this.depth = depth;
    }

    /** CSV 列名 / 配置键（稳定，不随显示文案变化）。 */
    public String key() {
        return key;
    }

    /** 命令输出用的显示名。 */
    public String label() {
        return label;
    }

    /** 缩进层级（0 = 最外层）。 */
    public int depth() {
        return depth;
    }

    /** 按 ordinal 索引的缓存，避免每次 values() 分配。 */
    public static final TickStage[] ALL = values();
}
