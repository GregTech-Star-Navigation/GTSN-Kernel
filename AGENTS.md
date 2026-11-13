# GTSN-Kernel — 项目规则

Minecraft 1.20.1（Forge 47.4.0）服务端 tick 内核。目标是把原版挤在单线程上的 tick 拆成可观测、可调度的模块。

## 当前阶段

**一期（可观测性）已完成**。二期（并行度=1 的多线程骨架）未开始。

改代码前先看 `docs/设计.md` 的五期划分 —— **不要提前实现后面几期的东西**。`core` / `compat` / `gen` 三个包现在是骨架接口，没有实现。

## 常用命令

```bash
./gradlew.bat build       # 编译 + 出 jar
./gradlew.bat runServer   # 跑服务端（已默认开启定期日志 + CSV）
./gradlew.bat runClient   # 跑客户端
```

服务端首次启动需要 `run/server/eula.txt`（`eula=true`）。

## 硬约束

1. **JDK**：构建固定 JDK 21（`gradle.properties` 的 `org.gradle.java.home`）。Gradle 8.14 不支持 JDK 25 —— 实测报 `Unsupported class file major version 69`。目标字节码 17。
2. **Mixin 只做 HEAD/RETURN 插桩**：一期的所有注入点不得读参数、不得改返回值、不得 `cancel()`。任何会改变原版行为的注入都要先改 `docs/设计.md` 说明理由。
3. **profiler 零每 tick 分配**：`StageProfiler.begin/end` 路径上不许 new 对象。要加数据就扩预分配数组。
4. **只采集主线程**：`StageProfiler` 用 `bindOwner` 过滤；多 region 之后要改成按 region 分桶，不是简单放开。
5. **`TickStage.key()` 是稳定契约**：CSV 列名依赖它，改名等于破坏下游脚本。要改就新增，不要重命名。
6. **不要用 `RenderSystem.isOnGameThread()`**：1.20.1 里硬编码 `return true`，拿它当线程守卫等于没有守卫。用 `ThreadGuard`。

## 包结构

```
com.gtsn.kernel
├── GtsnKernel        入口
├── perf              一期：阶段 profiler（已实现）
├── core              二期：region / 调度器 / 守卫（骨架）
├── compat            四期：规则表 / 探针 / 降级（骨架）
├── gen               五期：地形生成接口（骨架）
└── mixin             唯一接触原版类的地方
```

**除 `mixin` 包外，其他包不得 import `net.minecraft.*`**（`GtsnKernel` 与 `ThreadGuard` 例外，它们需要 Forge 事件与线程引用）。

## 禁区

- 不要 `@Overwrite` 原版方法 —— 会和所有优化 mod 冲突。
- 不要在 profiler 里做 IO（CSV 的 flush 已按 200 行节流，别再加密）。
- 不要把 `run/` 或 `build/` 提交进仓库（已在 `.gitignore`）。
- 不要在 `TickStage` 里加内联循环体对应的阶段 —— 那些没有方法边界，加不进去。

## 文档

- `docs/设计.md` —— 为什么这么拆、否决了什么
- `docs/接口.md` —— 命令、CSV、JVM 参数、内部 API
