# Changelog

## 0.2.3 — 2026-11-14

### 性能

- **profiler 每 tick 开销从 2.2% 降到 0.04%**（占 5ms tick）。`begin/end` 每次都调
  `ThreadLocal.get()`，52 次/卡顿 × 约 2μs。改成静态数组 + owner 线程比较后
  65.5 ns/对 → 每 tick 0.0020 ms。
- 新增 `StageProfilerOverheadTest` 锁住这个量级（关闭 9.1 ns / 开启 65.5 ns，阈值 1ms）。

代价：不再支持多线程采集 —— 本来也只采集 owner 线程，多 region 之后按 region 分桶时再引入
per-region 数组。

### 新增

- `tools/rcon.mjs` 加 `--batch` 模式：一条连接跑多句命令。摆 300 个僵尸从 30 秒降到 1 秒。

### 实测（第三轮，详见 docs/实测数据.md）

- **GT 机器没法用命令批量摆**：`fill` 几乎全失败，只能逐个 `setblock`，且需要下方有支撑。
  所以「用命令造一个真实 GT 工厂」不可行，GT 数据仍只有空转值。
- **停服要用 RCON `stop`**：强杀会导致 Gradle transform 缓存损坏，下次构建直接失败。

## 0.2.2 — 2026-11-14

### 新增

- **可选 GTCEu 依赖**：`./gradlew runServer -PwithGtceu` 会引入 GTCEu 7.5.4-patch01 +
  LDLib + Registrate + Configuration（组织 GitHub Packages，需要 read:packages 凭据）。
  默认不引入，避免每次构建都拉私有包。

### 实测（第二轮）

**区块生成/加载**：`/forceload` 分批加载到 6144 区块，`server_tick` 只到 1.385ms
（对比实体负载 6.83ms）。启动期有 408ms 尖峰，但那是 spawn chunk 一次性预生成。

**GTCEu**：799 台 GT 机器（lv_macerator + lv_assembler）只占 **0.143ms** 方块实体时间，
而 2177 个原版漏斗/熔炉占 **2.204ms** —— GT 机器轻 15 倍，因为没能量就跳过 tick。

### 结论修正

原「方块实体 32%」是**原版漏斗**的数据。GT 机器空转占比不到 1%。三期优先级需要按机器
类型细分，而不是笼统地「方块实体」。GT 机器**工作中**（有能量、跑配方、multiblock）的成本
仍是未知数，需要真实场景测。

详见 `docs/实测数据.md`。

## 0.2.1 — 2026-11-14

### 修复

- **CSV 的 `level_tick` 少记 275 倍。** `LEVEL_TICK` 在一个 tick 内会被多个维度（主世界 /
  下界 / 末地）各调一次，环形缓冲记录的是「单次样本」，CSV 取最后一个样本，只拿到了最后
  一个维度（通常几乎空）。表现为 CSV 里 `level_tick=0.005` 而 `/gtsnperf` 显示 `1.377`。
  - `StageProfiler` 新增 `TICK_TOTALS` 累加器 + `tickTotalMs()` + `endTick()`
  - CSV 改用 `tickTotalMs`（本 tick 该阶段总耗时，多世界已累加）
  - `MinecraftServerMixin` 在 tick 末尾清零（顺序：先读、再清）
  - 修复后 `level_tick=6.783ms` 与 `server_tick=6.826ms` 一致

  **这个 bug 只在多维度下出现**，单维度世界测不出来。

### 新增

- `tools/rcon.mjs`：最小 RCON 客户端（Node 内置模块，无依赖），用来从外部驱动服务端 ——
  `gradlew runServer` 会吞掉 stdin，`/gtsnperf` 没法通过管道送进去。
- `docs/实测数据.md`：第一次压测的方法与结论。

### 实测结论（影响三期优先级）

用 1088 漏斗 + 1089 熔炉 + 200 僵尸压测：

| 阶段 | 占服务器 tick |
| --- | --- |
| 实体 | **54.3%** |
| 方块实体 | **32.3%** |
| 区块系统 | 12.5%（其中区块管理 3.6%） |

**这推翻了「区块生成是最大瓶颈」的先验假设** —— 在「区块已加载、实体密集」的稳态下，
实体 + 方块实体占了 86.6%。C2ME 的 34× 是**生成期**收益，不是稳态 tick 收益。

三期优先级因此调整：**先做方块实体按 region 并行**（收益 32%，跨 region 依赖小），再做实体。

## 0.2.0 — 2026-11-14

### 新增（二期 2a：region 切分与依赖检测）

- `RegionId`：region 网格坐标（record，可比较、可复现排序）。
- `RegionMap` / `GridRegionMap`：区块 → region 的固定网格切分。用 `Math.floorDiv` 处理
  负坐标 —— 用 `/` 会把世界原点以西（-1/16 = 0）和以东的区块混进同一个 region。
- `SimpleRegion`：`Region` 的最小实现。
- `SerialRegionScheduler`：按 `RegionId` 排序**串行** tick 所有 region；union-find 做合并；
  **DFS 检测任意环**（只查直接反向边不够，A→B→C→A 同样会死锁）。
- `TickScheduler` 接口调整：`merge` 参数从 `int` 改为 `RegionId`，新增 `mergeCount()`。

### 为什么串行也算二期

合并规则、访问检测、region 归属都不依赖真并发。只有「两个 region 同时写同一份数据」的竞态
需要真并行才能暴露，那属于三期。先在串行下把逻辑跑对，三期只需换掉 `tickAll` 的执行方式。

### 验证

- `./gradlew test`：**17 个单测全绿**（`GridRegionMapTest` 5 个 + `SerialRegionSchedulerTest` 12 个）。
- 覆盖：负坐标切分、边界、单向访问不合并、直接双向合并、**间接三角环合并**、长链不误判、
  合并幂等、合并后只 tick 代表一次、tick 顺序确定、停机。

### 未做（2b）

- `@Redirect` 接管 `tickChildren` 里对 `ServerLevel.tick` 的调用。
- 一致性测试框架（同种子 + 同操作序列，每 tick 世界状态哈希）。

## 0.1.0 — 2026-11-14

### 新增

- **一期：阶段级 tick profiler。** 给原版服务端 tick 的 11 个阶段边界打点（`tickServer`、
  `tickChildren`、`ServerLevel.tick`、`ServerChunkCache`、`ChunkMap`、`LevelTicks`、
  `tickBlockEntities`、`EntityTickList`、`Raids`、`ServerFunctionManager`、`PlayerList`、
  `ServerConnectionListener`、`saveEverything`），回答「那 50ms 花在哪」。
- `StageProfiler`：无锁、每 tick 零分配，环形缓冲 1200 槽（60 秒 @ 20 TPS），输出
  avg / p50 / p99 / max。
- `/gtsnperf` 命令（含 `reset` / `on` / `off` / `csv` 子命令）。
- CSV 明细录制：每 tick 一行，按阶段分列，200 行 flush 一次。
- `-Dgtsnkernel.perf.logEvery=<tick>`：无人值守服务器定期把摘要写日志。
- `-Dgtsnkernel.perf.csv=true`：服务端启动即开始录 CSV。

### 架构骨架（仅接口，无实现）

按五期规划预先划好包与接口，避免后续改调用方：

- `core.Region` / `core.TickScheduler` / `core.AccessProxy` —— 二期（region 调度与跨 region 访问协议）
- `core.ThreadGuard` —— 二期（线程断言；已可用的最小实现）
- `compat.CompatRule` / `compat.CompatRules` —— 四期（mod 兼容规则表）
- `gen.GenerationScheduler` —— 五期（与地形生成 mod 的契约）

### 验证

- `./gradlew build` 通过；Mixin refmap 正常生成。
- `runServer` 实测：11 个注入点全部生效，无类加载错误。空载服
  `server_tick=0.67ms`、`level_tick=0.21ms`、`chunk_cache=0.20ms`。
- CSV 实测生成 3001 行，表头与数据格式正确。

### 已知未验证

- `-Pjdk25`（运行时切 JDK 25 + 强制 ASM 9.10.1）未实测通过。Forge 47.4.0 自带 ASM 9.7.1
  只认到 class 68，且 JDK 25 上 `-Djava.security.manager=allow` 会让 VM 直接退出。
- `entity_sections` 列当前恒为空（`PersistentEntitySectionManager` 的注入点未实现）。
