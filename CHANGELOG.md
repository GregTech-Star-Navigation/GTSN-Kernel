# Changelog

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
