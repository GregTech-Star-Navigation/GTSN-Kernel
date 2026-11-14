# REQ-001 · region 拥有自己的区块存储

> 状态：**设计中**（P0 调研进行中）
> 创建 2026-11-14

## 要做什么

把世界按 region 切分，**每个 region 拥有自己的区块存储与 tick 线程**，region 之间并行
tick，跨 region 的交互通过「延迟一个 tick 的队列」传递。

这是 Folia 的核心模型。它的原话（README）：

> region 之间是**并行但不是并发**：它们不共享数据，共享数据必然损坏。

一句话概括这条需求：**当前 `ServerChunkCache` 假定全世界只有一个线程在 tick；要让它变成
「每个 region 一个 tick 线程，各自只碰自己的区块」。**

## 不做什么

这一节比上一节重要。没有边界，这件事会膨胀成「重写整个 Minecraft 服务端」。

- **不做完整的 Folia 移植**。不引入 Folia 的 API，不要求插件/mod 声明 `folia-supported`，
  不实现 region 调度器的公开接口。
- **不改 mod 的公开 API**。`GTSNLib` / `GTSNPonder` / GTCEu 的代码一行不改；
  它们如果碰了别的 region 的数据，由本内核在机制层处理（延迟或合并），不是让它们适配。
- **不做跨维度并行**。主世界 / 下界 / 末地之间仍然串行 —— 维度之间的耦合（末影箱、
  传送门、实体转移）太深，收益又小（下界末地通常很空）。
- **不重写区块生成**。地形生成交给 `GTSN-Terrain-Generation`（另一个仓库），
  本需求只处理「已生成的区块如何被 tick」。
- **不保证 100% mod 兼容**。这是明知的取舍，写在第一版就说明白，
  兼容性通过第五期的规则表逐步补。
- **第一期不动 `ServerLevel` 的公开契约**。先做能验证的小切片。

## 已知约束（都是实测过的，不是推测）

1. **`ServerChunkCache.getChunk` 在非主线程会死锁**（2026-11-14 实测，整服卡死）：
   它 `supplyAsync(..., mainThreadProcessor).join()` 把活转交主线程，主线程若正等工作线程
   就互等。这是三期「方块实体并行」失败的根因。
2. **`ChunkMap` 是单个 level 一份**，`ServerLevel` 直接持有，被 tick 路径与查询路径共享。
3. **`ServerLevel` 有大量全局状态**（时间、天气、计分板、玩家列表、raid、bossevent），
   这些 region 之间必须共享，不能各存一份。
4. **Forge 的 `LevelChunk` / `ChunkHolder` 被 mod 直接引用**（GTCEu 就大量直接用）。
   把它们换成 region 私有的副本，等于所有 mod 的引用失效。
5. **区块生成期的线程模型已经存在**（`ChunkTaskPriorityQueueSorter` + `Worker-Main-*`），
   和 tick 期是两套。改造只动 tick 期。

## 方案

### 核心思路：所有权，而不是加锁

不给共享状态加锁（那样并行度会被锁吃掉，而且死锁风险更大），而是**让数据有唯一的主人**：

- 每个区块在任何时刻属于**唯一一个 region**
- 只有该 region 的 tick 线程能读写它
- 别的 region 想读 → 拿到的是**上一个 tick 的快照**（或者排队等下一 tick）
- 别的 region 想写 → 入队，由目标 region 在自己的 tick 里执行

这样没有锁，只有队列。代价是**跨 region 的交互延迟 1 tick** —— 这是必须接受的语义变化。

### 与现有代码的关系

已有的东西直接用：

| 已有 | 用途 |
| --- | --- |
| `RegionMap` / `GridRegionMap` | 区块 → region 的切分（已有，二期做的） |
| `RegionThreadPool` | region → 固定 lane 的绑定（已有，三期实验留的） |
| `SerialRegionScheduler` | union-find 合并 + 跨 region 依赖检测（已有） |
| `TickScheduler` 接口 | 调度抽象（已有，加并行实现） |
| `StageProfiler` 按线程分桶 | 并行之后按 region 看耗时（已就绪） |
| `tools/consistency-check.sh` | 每期验收的比对工具（已就绪） |

要新写的：

| 新东西 | 作用 |
| --- | --- |
| `RegionChunkStore` | 一个 region 的区块集合 + 只允许 owner 线程访问的断言 |
| `CrossRegionQueue` | 跨 region 的延迟任务队列（读快照 / 写入队） |
| `GlobalState` | 明确的「region 之间共享」的状态容器（时间、天气等） |
| `RegionTickContext` | 当前线程正在 tick 哪个 region（`ThreadLocal`），供守卫与队列用 |

### 为什么不是「给 ServerChunkCache 加锁」

算过：`getChunk` 在 tick 路径上每 tick 被调用数百到数千次（每个实体、每个方块实体都可能
调）。加锁之后每次几纳秒的锁竞争，乘以这个数量级就是毫秒级开销 —— 把并行的收益直接吃光。

而且 `getChunk` 会转交主线程，一旦主线程也去抢同一把锁，就是死锁。

**必须从「谁拥有数据」入手，不是「谁先拿到锁」。**

## P0 结论（2026-11-14，从反编译源码统计）

### 决定性事实：getChunk 与 getChunkNow 行为不同

`ServerChunkCache.java:117-160`：

| 方法 | 非主线程调用时 |
| --- | --- |
| `getChunk(...)` | `supplyAsync(..., mainThreadProcessor).join()` → **死锁** |
| `getChunkNow(...)` | `return null` → 安全降级 |

所以问题不是「能不能并行」，而是「所有 `level.get*` 调用在 region 线程上怎么办」。

### 跨区块访问密度

`net/minecraft/world/level/block/entity/` 与 `net/minecraft/world/entity/` 全目录统计：

| 位置 | getBlockState | getEntities | getBlockEntity | getChunk | 合计 |
| --- | --- | --- | --- | --- | --- |
| 方块实体 | 21 | 11 | 5 | 3 | **40** |
| 实体 | 7 | 1 | 1 | — | **9** |

**量级是几十处，不是几百。** 这比预期的乐观。

### 关键分化：方块实体分两类

- **零跨区块访问**（`AbstractFurnaceBlockEntity` 实测 0 处）—— 纯自身状态，
  **可以直接并行**，不需要队列
- **碰邻居**（`HopperBlockEntity` 4 处：`getEntitiesOfClass` / `getBlockState` /
  `getBlockEntity` / `getEntities`；另有 Beacon / Beehive / Bell / Chest / Conduit /
  Jukebox / CommandBlock 各 1–3 处）—— 必须走跨 region 队列

### P2a 运行时验证（2026-11-14 实测）

静态统计只是推测，所以做了运行时量尺：`ServerChunkCacheGuard` 拦截 region 线程的
`getChunk`（返回 null 并记账），`ParallelBlockEntityTick` 对每个 ticker 单独 try-catch。

289 个方块实体、`-PparallelBlocks=2 -PregionShift=1` 的结果：

| 类型 | 成功 | 失败 | 结论 |
| --- | --- | --- | --- |
| `minecraft:furnace` | **113288** | **0** | 零跨区块，**并行完全安全** |
| `minecraft:hopper` | 289 | **3757** | 100% 依赖跨区块，必须走队列 |

`getChunk` 违规共 7514 次，样本 `GTSN-Region-0 @0,0 status=minecraft:full`。

**这与 P0 的静态统计完全吻合**（熔炉 0 处跨区块调用、漏斗 4 处）。

**所以 P2a 的正式形态是「按类型白名单分流」**：白名单里的类型丢 lane 并行，
其余留在主线程按原版顺序跑。这样既能吃到并行收益，又不会破坏依赖跨区块的那部分。

### P2a 性能实测：尾延迟灾难（2026-11-14）

机制正确（四条路径哈希一致、零违规），但**性能是负收益**。

289 个正在烧的熔炉（64 矿石 + 64 煤炭），`block_entities` 阶段：

| 模式 | avg | p50 | p99 | max |
| --- | --- | --- | --- | --- |
| 串行（原版） | 0.375 | 0.368 | 0.553 | 1.061 |
| 并行（2 lane） | **109.245** | 0.450 | **449.972** | **3370.579** |

**p50 只差 0.08ms，p99 涨了 818 倍，max 涨了 3000 倍。**

原因：
1. 每 tick 都要唤醒 lane 线程 —— Java 线程调度 + 上下文切换是 10–100 μs 量级
2. `CompletableFuture.allOf().join()` 要等**最慢的那个 lane**，负载不均时主线程被拖住
3. 289 个熔炉的总工作量只有 0.375 ms，**根本不够抵掉调度开销**

**测量工具本身也污染过一次**：第一版统计用 `ConcurrentHashMap.computeIfAbsent` +
`AtomicLong.incrementAndGet`，每个 ticker 每 tick 一次 —— 直接把 0.343 ms 顶到 110.681 ms。
改用 `LongAdder` 后 p50 才回到 0.450（avg/p99 仍然是真的调度开销，不是测量污染）。

**结论**：细粒度并行（每 tick 一次屏障）在 Java 线程模型下不划算。要救只有三条路：

- **忙等屏障**（spin barrier）而不是 future/join —— 省掉线程唤醒，但会烧 CPU
- **增大粒度**：每 N tick 并行一次，或者只在积压时并行
- **换模型**：像 Folia 那样让每个 region 常驻一个忙等线程（它的设计前提就是
  「≥16 物理核，线程总占用 < 80% CPU」，也就是默认接受几核专门空转）

三条都属于「重做调度层」，不是 P2a 的调整能覆盖的。

### P2b：非阻塞快路径救回漏斗（2026-11-14）

P2a 时漏斗 3757 次失败，因为它每个 tick 都查邻居。但那些邻居**大部分就在同一个 region 里**
—— 只是守卫把**所有** `getChunk` 都拦掉了。

修法：region 线程上不再一律拦截，而是先走**非阻塞快路径**：

```java
ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
if (holder != null && holder.getTickingChunk() != null) {
    return chunk;          // 已加载 → 直接给，不转交主线程、不阻塞
}
return null;               // 未加载 → 记账后返回 null
```

`getVisibleChunkIfPresent` 只查一个 `ConcurrentHashMap`，不碰 `mainThreadProcessor`，
所以它在工作线程上是安全的。

289 个漏斗的结果：

| 指标 | P2a（一律拦截） | P2b（快路径） |
| --- | --- | --- |
| 漏斗成功 | 289 | **71382** |
| 漏斗失败 | 3757 | **1** |
| 非阻塞读成功 | 0 | **142767** |
| 违规 | 7514 | **0** |

**漏斗从「100% 不可并行」变成「可并行，1 次边界失败」。**

### 对分期的影响

P2 拆成 2a / 2b：

- **P2a**：只并行「零跨区块访问」的方块实体。不需要队列，只需要所有权断言。
  风险低、收益立即可测（实测 2177 个漏斗/熔炉的负载里，熔炉那部分能直接并行）。
- **P2b**：碰邻居的那类走跨 region 队列。

**可行性判断**：改造面在「几十个类、几十处调用」的量级，不是「重写整个 chunk 系统」。
值得做。但 P1 的所有权断言仍然是必须的 —— 它决定这几十处里有多少是真正绕不过去的。

## 分期

每一期都必须能独立验收，不能一次做完再验证。

### P0 · 量化改造范围（进行中）

回答：要改的类有多少、跨区块访问有多密。

- 调研 A：`ServerChunkCache` / `ChunkMap` / `ChunkHolder` / `ServerLevel` 的单线程假设清单
- 调研 B：原版方块实体 / 实体 / GTCEu 在 tick 期的跨区块访问密度

**产出**：这份文档补上「改造范围」与「可行性判断」两节。
**如果调研显示必须改的类超过某个阈值（比如 20 个核心类），就要重新评估是否值得做。**

### P1 · 单 region 所有权（不动并行）

把主世界整体当成**一个** region，建立 `RegionChunkStore` 与 `RegionTickContext`，
所有区块访问都必须经过所有权断言。

**这一期不提升任何性能**，目的是：
- 把「谁拥有这块数据」变成代码里可检查的事实
- 暴露所有绕过所有权的地方（断言会打日志）

**验收**：`tools/consistency-check.sh` 三条路径哈希一致 + 断言违规数为 0（或已知白名单）。

### P2 · 双 region 并行 tick

把主世界切成两个 region，各一个线程，真正并行。

**验收**：同一个基准世界，双 region 并行的 `hash(blocks)` 与串行一致。

**这一期一定会失败**，因为跨 region 交互还没处理。失败的形态就是 P3 的输入清单。

### P3 · 跨 region 延迟队列

读走快照、写入队。跨 region 的漏斗传输、实体推挤、爆炸影响都走这条路。

**验收**：漏斗链（A 区漏斗 → B 区箱子）在**有限 tick 内收敛**，且不丢物品、不复制物品。

### P4 · 全局状态隔离

时间、天气、计分板、玩家列表、raid 从「每 region 一份」收回到 `GlobalState`。

**验收**：玩家在不同 region 之间移动时，看到的时间/天气一致。

### P5 · mod 兼容规则表

`compat` 包：声明哪些 mod 的哪些行为需要特殊处理（强制同 region、串行化某个调用等）。

**验收**：装上 GTCEu + GTSNLib，跑 `tools/consistency-check.sh` 不崩。

## 验收标准（汇总）

每一期的验收都必须**可执行**，不能是「跑起来看看」：

| 期 | 可执行的验收 |
| --- | --- |
| P1 | `bash tools/consistency-check.sh` 输出 PASS；日志里 ownership violation 数 = 0 |
| P2 | 同上，但第三个场景换成双 region 并行；哈希一致 |
| P3 | 新增漏斗跨区场景：跑 200 tick 后两侧物品总数不变，且传输完成 |
| P4 | 新增多 region 时间一致性检查：所有 region 读到的 `dayTime` 相同 |
| P5 | 装 GTCEu 后 `consistency-check.sh` 通过 |

## 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 改造面比预期大（超过 20 个核心类） | 工期失控 | P0 结束时重新评估，必要时**停止** |
| mod 大量直接引用 `LevelChunk` | 兼容性全崩 | P1 的所有权断言先量化，再决定是否放弃 |
| 跨 region 延迟 1 tick 改变游戏语义 | 玩家可感知（漏斗变慢、红石延迟） | 文档明确写；可配置「强制同 region」的兼容模式 |
| 长期维护成本 | 每次 MC 升级都要重做 | 明确记录：这是 fork 级改造，不跟随上游 |

## 状态

- [x] 需求边界写清
- [x] P0 量化（已完成，结论见上）
- [ ] P0 可行性判断
- [ ] P1 单 region 所有权
- [x] P2a 白名单并行（机制完成，性能负收益）
- [x] P2b 非阻塞快路径（漏斗可并行）
- [ ] P3 跨 region 队列
- [ ] P4 全局状态
- [ ] P5 mod 兼容
