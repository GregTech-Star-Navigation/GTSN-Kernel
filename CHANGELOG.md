# Changelog

## 0.6.2 — 2026-11-14

### 修复

- **P2b 非阻塞快路径：漏斗从「100% 不可并行」变成「可并行」。**

  P2a 时漏斗 3757 次失败，因为它每 tick 都查邻居。但那些邻居大多**就在同一个 region 里**
  —— 只是守卫把**所有** `getChunk` 都拦掉了。

  改成 region 线程先走非阻塞快路径：

  ```java
  ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
  if (holder != null && holder.getTickingChunk() != null) return chunk;  // 已加载
  return null;                                                          // 未加载才记账
  ```

  `getVisibleChunkIfPresent` 只查一个 `ConcurrentHashMap`，不碰 `mainThreadProcessor`，
  所以在工作线程上是安全的。

  289 个漏斗实测：

  | 指标 | P2a | P2b |
  | --- | --- | --- |
  | 漏斗成功 | 289 | **71382** |
  | 漏斗失败 | 3757 | **1** |
  | 非阻塞读成功 | 0 | **142767** |
  | 违规 | 7514 | **0** |

### 新增

- `ChunkMapAccessor`：把 protected 的 `getVisibleChunkIfPresent` 暴露给 region 线程。
- 一致性测试加第五场景（`-PsafeTypes=minecraft:hopper`），判据变成五条路径哈希全一致。

### 说明

P2a 的性能结论（p99 涨 818 倍）**没有被 P2b 改变** —— P2b 解决的是「能不能并行」
（正确性），不是「并行值不值」。细粒度并行的调度开销仍然是负收益，要解决得重做调度层。

## 0.6.1 — 2026-11-14

### 实测：P2a 并行是负收益

289 个正在烧的熔炉，`block_entities` 阶段：

| 模式 | avg | p50 | p99 | max |
| --- | --- | --- | --- | --- |
| 串行 | 0.375 | 0.368 | 0.553 | 1.061 |
| 并行 | 109.245 | 0.450 | 449.972 | 3370.579 |

**p50 差不多，p99 涨 818 倍。** 每 tick 唤醒 lane 线程 + `allOf().join()` 等最慢的
那个，而 289 个熔炉总工作量只有 0.375ms，抵不掉调度开销。

正确性没问题（四条路径哈希一致、零违规），是**性能不可用**。

### 修复

- **并行路径的 profiler 打点**：`LevelBlockEntityParallelMixin` 用 `ci.cancel()` 之后，
  `LevelMixin` 的 RETURN 注入不会执行，`block_entities` 在并行模式下永远是 0 样本。
  在并行路径里手动 `StageProfiler.begin/end`。
- **统计从 `ConcurrentHashMap` + `AtomicLong` 换 `LongAdder`**：第一版每个 ticker 每 tick
  一次 `computeIfAbsent` + `incrementAndGet`，把 0.343ms 顶到 110.681ms（慢 322 倍）——
  测量工具自己成了瓶颈。

### 新增

- `tools/parallel-benchmark.sh`：装满矿石与煤炭、让熔炉真的在烧，对比串行/并行的
  `block_entities` 分布。

## 0.6.0 — 2026-11-14

### 文档

- 新增 `docs/requirements/REQ-001-region-owned-chunks.md`：region 拥有独立区块存储的
  完整需求边界、五期分解、逐期可执行验收、风险表。
- 完成 P0 量化（从 `forge-1.20.1-47.4.0-sources.jar` 统计）：

  **决定性事实**：`ServerChunkCache.getChunk` 在非主线程会 `supplyAsync(..., mainThreadProcessor).join()`
  转交主线程（死锁），而 `getChunkNow` 在非主线程直接返回 `null`（安全降级）。

  **跨区块访问密度**：方块实体 40 处、实体 9 处 —— 量级是几十处，不是几百处。

  **关键分化**：`AbstractFurnaceBlockEntity` 零跨区块访问（可直接并行），
  `HopperBlockEntity` 4 处全查邻居（必须走队列）。

  因此 P2 拆成 2a（只并行零依赖的那类）与 2b（碰邻居的走队列）。可行性判断：
  值得做，但 P1 的所有权断言仍是必须的前置。

## 0.5.0 — 2026-11-14

### 新增

- `RegionThreadPool`：把 region 稳定绑定到固定 lane（`hash(regionId) % parallelism`），
  保证同一 region 的两次 tick 落在同一线程 —— 这是「region 内串行」的前提。
- `ParallelBlockEntityTick`：`tickBlockEntities` 的 tick 循环按 region 分组并行分组逻辑。
- `-PparallelBlocks[=N]` / `-PregionShift=S`：实验开关。
- `tools/consistency-check.sh` 加第三个场景（并行路径），判据变成三条路径哈希全部一致。

### 负结果：方块实体并行会死锁（这条路走不通）

实测（289 个漏斗，`-PparallelBlocks=2 -PregionShift=1`）：服务端第一 tick 卡死，
RCON 无响应，只能强杀。

根因是原版线程模型的硬约束，不是实现 bug：

```
主线程                          GTSN-Region-0
  allOf(futures).join()  ←──等待──  ticker.tick() → level.getChunk()
                                     └─ ServerChunkCache.getChunk()
                                        非主线程 → supplyAsync(..., mainThreadProcessor).join()
```

`ServerChunkCache.getChunk` 在非主线程会阻塞并把活转交主线程，而主线程正在等它。

**结论**：三期不是「把循环并行」，而是「让每个 region 拥有自己的区块存储与调度队列」
（Folia 那套，region 之间并行但不并发）。量级是数月。

代码保留并标注为「记录的死路」—— 分组逻辑和 `RegionThreadPool` 在四期仍有价值。
默认关闭，不影响任何正常路径。

### 修正

- 一致性测试的基准世界从箱子改成**漏斗**。箱子不注册 `TickingBlockEntity`，
  并行路径拿到的是空列表 —— 用箱子测并行等于没测（第一次「PASS」是假阳性）。

## 0.4.0 — 2026-11-14

### 新增

- **profiler 按线程分桶**（三期真并行的前置）。每个线程写自己的桶，桶之间没有共享可写
  状态；报告里既看得到总量（`stats`，合并所有桶），也看得到每个线程的明细
  （`perBucketStats`，回答「哪个 region 慢」）。

  `/gtsnperf` 头部现在显示 `采集桶 N 线程`，超出上限（16）的线程会单独计数并提示，
  不会静默丢数据。

  分桶代价：`begin+end` 从 65.5 ns 涨到 **73.9 ns**（多了 `ThreadLocal.get()`），
  每 tick 仍只有 **0.0022 ms**，占 5ms tick 的 0.044%。

### 修复

- **环形缓冲的读取索引错位（一直在错，只是没被发现）。** 写入时 `head` 递增指向
  「下一个要写的位置」，读取却从 `head` 开始 —— 读到的是还没写过的槽位（值 0）。

  只有缓冲写满（`count == CAPACITY == 1200`）时两者才恰好重合，所以跑满 60 秒的实测
  数据都是对的；**短窗口采样全错**。表现：`reset` 之后跑 30 秒，`p50` 显示 0.001 甚至
  比 `avg` 还大，平均值被 0 值拉低。

  修法：`start = (head - count + CAPACITY) % CAPACITY`，统一走 `sampleIndex()` 辅助方法。

  发现过程：写分桶测试时用 `System.nanoTime()` 忙等 1ms，样本却记录为 0，才顺藤摸瓜
  找到写入/读取的索引语义不一致。

- **桶注册的跨线程可见性**：`BUCKETS[slot] = bucket` 是普通数组写，报告线程在另一个
  线程读可能看不到新桶（样本「凭空消失」）。改用 `AtomicReferenceArray`。

## 0.3.0 — 2026-11-14

### 新增

- **二期 2b：`@Redirect` 接管 `ServerLevel.tick`。** `tickChildren` 里对 `ServerLevel.tick`
  的调用转到 `bridge.TickRedirectHandler`，开关 `-PregionEnabled` 控制走调度器还是原版路径。
  这是项目第一个改变原版调用路径的注入（之前全是 HEAD/RETURN 打点）。
- `bridge` 包：放「需要接触 `net.minecraft.*` 但不属于 Mixin 注入」的类。
  Mixin 配置把 `com.gtsn.kernel.mixin` 整包声明为 mixin 包，包内其他类不能被普通代码引用。
- `WorldHasher`：方块实体 + 实体的状态哈希，一致性测试的比对依据。
- `/gtsnperf hash [blocks]`：输出 `hash(blocks)=<hex> dim=<dim> blocks=<n>`。
- `tools/consistency-check.sh` / `server-start.sh` / `server-stop.sh`：一致性测试与可靠启停。

### 验证

同一基准世界跑两次，两条路径结果完全一致：

```
vanilla: hash(blocks)=881bd189827a5eac blocks=293
region : hash(blocks)=881bd189827a5eac blocks=293
```

调度器路径性能无退化（`server_tick` 0.666 ms，TPS 20.000）。

### 修复

- **哈希用了对象身份**：`BlockEntityType.hashCode()` / `EntityType.hashCode()` 没重写，
  走 `Object.hashCode()`（内存地址），每次 JVM 启动都不同 —— 同一世界两次运行结果必然不同，
  一致性测试会永远失败。改用注册名（`BuiltInRegistries.*.getKey(type)`）。
- **`nohup` 启动服务端会立即停服**：`MinecraftServer` 的 console handler 从 stdin 读命令，
  读到 EOF 就停（`Done` 同一秒 `Stopping server`）。重定向空文件也一样，必须是
  `tail -f /dev/null |` 这种永不 EOF 的管道。

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
