# GTSN-Kernel — 项目规则

Minecraft 1.20.1（Forge 47.4.0）服务端 tick 内核。目标是把原版挤在单线程上的 tick 拆成可观测、可调度的模块。

## 当前阶段

**一期（可观测性）已完成**；**二期 2a（region 切分 + 串行调度 + 依赖检测）与 2b（接管原版 tick）已完成**。

改代码前先看 `docs/设计.md` 的五期划分 —— **不要提前实现后面几期的东西**。`compat` / `gen` 两个包还是骨架接口，没有实现。

## 常用命令

```bash
./gradlew.bat build       # 编译 + 出 jar
./gradlew.bat runServer   # 跑服务端（已默认开启定期日志 + CSV）
./gradlew.bat runClient   # 跑客户端
```

服务端首次启动需要 `run/server/eula.txt`（`eula=true`）。

## 硬约束

1. **JDK**：构建固定 JDK 21（`gradle.properties` 的 `org.gradle.java.home`）。Gradle 8.14 不支持 JDK 25 —— 实测报 `Unsupported class file major version 69`。目标字节码 17。
2. **改变原版行为的注入必须先改设计文档**：
   - 一期的 profiler 注入点只做 HEAD/RETURN 打点，不读参数、不改返回值、不 `cancel()`。
   - 二期 2b 起有 **唯一一个** 改变调用路径的注入：`@Redirect` 接管 `ServerLevel.tick`
     （`MinecraftServerMixin`）。它必须保持「开关关闭时逐字节等价于原版」。
   - 再要加改变行为的注入，先写进 `docs/设计.md` 并补一致性测试。
3. **profiler 零每 tick 分配**：`StageProfiler.begin/end` 路径上不许 new 对象。要加数据就扩预分配数组。
4. **profiler 按线程分桶**：每个线程写自己的桶（上限 16，超出计数不静默丢）。
   `begin/end` 路径上不许加锁、不许分配。环形缓冲的读取必须走 `sampleIndex()`
   —— `head` 是「下一个写入位」，直接从它读会拿到未写槽位（曾经这么错过）。
5. **`TickStage.key()` 是稳定契约**：CSV 列名依赖它，改名等于破坏下游脚本。要改就新增，不要重命名。
6. **不要在任何非主线程调用 `level.getChunk` / `getChunkNow` / 方块实体 tick**：
   `ServerChunkCache.getChunk` 在非主线程会 `supplyAsync(..., mainThreadProcessor).join()`
   把活转交主线程，主线程若正在等这个工作线程 → **死锁**（2026-11-14 实测，整服卡死）。
   三期「方块实体按 region 并行」就是撞在这条上（见 `docs/设计.md`）。
7. **不要用 `RenderSystem.isOnGameThread()`**：1.20.1 里硬编码 `return true`，拿它当线程守卫等于没有守卫。用 `ThreadGuard`。

## 包结构

```
com.gtsn.kernel
├── GtsnKernel        入口
├── perf              一期：阶段 profiler（已实现）
├── core              二期：region 切分 / 串行调度 / 依赖检测（2a）；ThreadGuard
├── bridge            二期 2b：tick 重定向目标 + 世界哈希（接触 MC，但不是 mixin）
├── compat            四期：规则表 / 探针 / 降级（骨架）
├── gen               五期：地形生成接口（骨架）
└── mixin             唯一写 Mixin 注解的地方
```

**`mixin` 包只放 Mixin 类。** 别的类放进去会在运行时抛
`IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly`
—— `mixins.gtsnkernel.json` 把整包声明为 mixin 包，包内非 mixin 类不能被普通代码引用。
需要接触 `net.minecraft.*` 但不是 Mixin 的类放 `bridge`。

**除 `mixin` / `bridge` 包外，其他包不得 import `net.minecraft.*`**（`GtsnKernel` 与
`ThreadGuard` 例外，它们需要 Forge 事件与线程引用）。

## 二期 2b 的验证方式

```bash
bash tools/consistency-check.sh      # 同一世界跑两次（原版 / 调度器），比对方块实体哈希
```

首次运行会自动建立基准世界（`build/world-base`）。判据是两行 `hash(blocks)` 完全相同。

单独起停服务端用 `tools/server-start.sh` / `tools/server-stop.sh`，**不要 nohup 直接起**
（stdin EOF 会让服务端秒停），也**不要强杀**（会搞坏 Gradle transform 缓存）。

## 禁区

- 不要 `@Overwrite` 原版方法 —— 会和所有优化 mod 冲突。
- 不要在 profiler 里做 IO（CSV 的 flush 已按 200 行节流，别再加密）。
- 不要把 `run/` 或 `build/` 提交进仓库（已在 `.gitignore`）。
- 不要在 `TickStage` 里加内联循环体对应的阶段 —— 那些没有方法边界，加不进去。

## 文档

- `docs/设计.md` —— 为什么这么拆、否决了什么
- `docs/接口.md` —— 命令、CSV、JVM 参数、内部 API
- `docs/实测数据.md` —— 压测方法与结论
