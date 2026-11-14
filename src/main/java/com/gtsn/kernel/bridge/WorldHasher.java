package com.gtsn.kernel.bridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 世界状态哈希 —— 一致性测试的比对依据（二期 2b）。
 *
 * <p><b>为什么不用「所有方块状态」</b>：遍历全部加载区块的每个方块，一个 10 视距的世界
 * 有几十万方块，每 tick 算一次会直接把 tick 时间顶满，测出来的不是调度器开销而是哈希开销。</p>
 *
 * <p><b>改用什么</b>：只哈希「会被 tick 影响的东西」—— 实体（位置 + 类型）和方块实体
 * （位置 + 类型）。这两类正是 {@code tickBlockEntities} 与 {@code EntityTickList} 的作用对象，
 * 调度器路径如果出错，最先在这里表现出来。</p>
 *
 * <p><b>确定性</b>：用 FNV-1a，输入顺序固定（实体按 id 排序、方块实体按坐标排序），
 * 不依赖 HashMap 迭代顺序。</p>
 */
public final class WorldHasher {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private WorldHasher() {
    }

    /**
     * 计算一个世界的状态哈希。
     *
     * @param level       目标世界
     * @param includeEntities 是否包含实体（实体多时关掉，只看方块实体）
     */
    public static long hash(ServerLevel level, boolean includeEntities) {
        long hash = FNV_OFFSET;

        // 方块实体：以原点为中心扫固定半径（ChunkMap.getChunks() 是 protected，
        // 没有公开的"列出已加载区块"API）。半径可配，默认 8 区块 = 289 个。
        int radius = Integer.getInteger("gtsnkernel.hash.radius", 8);
        java.util.List<BlockEntity> blockEntities = new java.util.ArrayList<>();
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    blockEntities.addAll(chunk.getBlockEntities().values());
                }
            }
        }
        blockEntities.sort((a, b) -> {
            int byX = Integer.compare(a.getBlockPos().getX(), b.getBlockPos().getX());
            if (byX != 0) {
                return byX;
            }
            int byY = Integer.compare(a.getBlockPos().getY(), b.getBlockPos().getY());
            return byY != 0 ? byY : Integer.compare(a.getBlockPos().getZ(), b.getBlockPos().getZ());
        });
        for (BlockEntity blockEntity : blockEntities) {
            hash = mix(hash, blockEntity.getBlockPos().asLong());
            hash = mix(hash, typeName(blockEntity.getType()));
        }

        if (includeEntities) {
            java.util.List<Entity> entities = new java.util.ArrayList<>();
            level.getAllEntities().forEach(entities::add);
            entities.sort(java.util.Comparator.comparingInt(Entity::getId));
            for (Entity entity : entities) {
                hash = mix(hash, entity.getId());
                hash = mix(hash, typeName(entity.getType()));
                hash = mix(hash, Double.doubleToLongBits(entity.getX()));
                hash = mix(hash, Double.doubleToLongBits(entity.getY()));
                hash = mix(hash, Double.doubleToLongBits(entity.getZ()));
            }
        }

        return hash;
    }

    /** 只数方块实体个数（诊断用：hash 不一致时先看两边对象数是否相同）。 */
    public static int countBlockEntities(ServerLevel level) {
        int radius = Integer.getInteger("gtsnkernel.hash.radius", 8);
        int count = 0;
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    count += chunk.getBlockEntities().size();
                }
            }
        }
        return count;
    }

    /**
     * 类型 → 稳定标识。
     *
     * <p><b>不能用 {@code type.hashCode()}</b>：{@code BlockEntityType} / {@code EntityType}
     * 没重写 hashCode，走的是 Object 的身份哈希（内存地址），每次 JVM 启动都不一样 ——
     * 会让同一世界的两次运行算出不同结果，把「一致性测试」变成永远失败。</p>
     */
    private static long typeName(Object type) {
        if (type instanceof net.minecraft.world.level.block.entity.BlockEntityType<?> blockType) {
            return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockType)).hashCode();
        }
        if (type instanceof net.minecraft.world.entity.EntityType<?> entityType) {
            return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entityType)).hashCode();
        }
        return String.valueOf(type).hashCode();
    }

    private static long mix(long hash, long value) {
        long result = hash ^ value;
        return result * FNV_PRIME;
    }
}
