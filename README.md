# GTSN Kernel

Minecraft 1.20.1（Forge 47.4.0）的服务端 tick 内核。目标是**把原版挤在单个线程上的 tick 拆成可观测、可调度的模块**。

当前进度：**一期（可观测性）已完成**。

## 这是什么

原版 1.20.1 的服务端主线程每 tick 要跑完整个世界的一次完整模拟 —— 区块、实体、方块实体、红石、刷怪，全在 50ms 预算内串行完成。这个项目分五期把它拆开：

| 期 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 阶段级耗时 profiler | ✅ 已完成 |
| 二 | 并行度=1 的多线程骨架（行为与原版逐 tick 一致） | 未开始 |
| 三 | region 切分 + 真并行 tick | 未开始 |
| 四 | 兼容层（mod 适配 = 一条 JSON 规则） | 未开始 |
| 五 | 地形生成调度接口 | 未开始 |

一期**不改变任何原版逻辑** —— 所有 Mixin 都是 `@Inject` 的 HEAD/RETURN 插桩，不读参数、不改返回值、不取消调用。

## 快速开始

```bash
# 构建（需要 JDK 21，见「JDK」一节）
./gradlew.bat build

# 跑服务端（每 10 秒把阶段摘要写进日志 + 自动录 CSV）
./gradlew.bat runServer
```

日志里会看到：

```
[GTSN-Kernel] 阶段耗时 · 最近 1200 tick · ms: server_tick=0.67 server_children=0.65
functions=0.01 level_tick=0.21 chunk_cache=0.20 chunk_map=0.08 ...
```

CSV 在 `run/server/gtsnperf/gtsnperf-<时间戳>.csv`。

## 游戏内命令

```
/gtsnperf            输出各阶段 avg / p50 / p99 / max（权限 ≥2）
/gtsnperf reset      清空样本
/gtsnperf on|off     开关采集（关掉时开销为零）
/gtsnperf csv        开始/停止录制明细
```

## JVM 参数

| 参数 | 作用 | 默认 |
| --- | --- | --- |
| `-Dgtsnkernel.perf.logEvery=<tick>` | 每 N tick 把摘要写日志（无人值守用） | `0`（关闭） |
| `-Dgtsnkernel.perf.csv=true` | 服务端启动即开始录 CSV | `false` |

## JDK

- **构建**：JDK 21（`gradle.properties` 里固定）。Gradle 8.14 读不了 JDK 25 的 class 69，实测报 `Unsupported class file major version 69`。
- **目标字节码**：17（MC 1.20.1 要求）。
- **运行时 JDK 25**：`./gradlew runServer -Pjdk25` 会切 toolchain 并把 ASM 强制到 9.10.1。**未验证** —— Forge 47.4.0 自带 ASM 9.7.1（只认到 class 68），且 JDK 25 上 `-Djava.security.manager=allow` 会让 VM 直接退出。详见 `docs/设计.md` 的「JDK 25」一节。

## 文档

- [`docs/设计.md`](docs/设计.md) —— 为什么这么拆、否决了哪些方案、五期边界
- [`docs/接口.md`](docs/接口.md) —— 命令、CSV 格式、JVM 参数、内部 API
- [`AGENTS.md`](AGENTS.md) —— 给 agent 的项目约定

## 许可

LGPL-3.0
