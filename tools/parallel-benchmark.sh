#!/usr/bin/env bash
# P2a 性能收益测量：熔炉真的在烧的时候，并行 vs 串行。
#
#   bash tools/parallel-benchmark.sh [熔炉数量…]
#
# 为什么必须「真的在烧」：空熔炉的 tick 只做几次状态判断，成本接近零 —— 并行它等于
# 并行一堆空转，测不出任何东西。装满矿石 + 煤炭之后每一 tick 都有配方查找、进度推进、
# 燃料消耗、产物写入，这才是真实成本。
#
# 判据：比较两种模式下 `block_entities` 阶段的 avg。并行如果没更快，P2a 就只是机制验证。
set -uo pipefail
cd "$(dirname "$0")/.."

RCON="node tools/rcon.mjs"
RESULT_DIR="build/benchmark"
mkdir -p "$RESULT_DIR"

LOCK="$RESULT_DIR/.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "另一个 benchmark 正在跑（$LOCK）。删掉锁重试。" >&2
  exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

# 方块实体铺在 y=-59。SIZE=8 → 17×17=289 个；SIZE=16 → 33×33=1089 个。
SIZE="${BENCH_SIZE:-8}"
LOAD_Y=-59

wait_rcon() {
  $RCON --wait 300 "list" > /dev/null 2>&1 || { echo "FAIL: 服务端没起来" >&2; return 1; }
}

# 给每个熔炉装 64 个矿石 + 64 个煤炭 —— 够烧 10 分钟
load_furnaces() {
  local commands="$RESULT_DIR/load.cmd"
  : > "$commands"
  for x in $(seq -$SIZE $SIZE); do
    for z in $(seq -$SIZE $SIZE); do
      if [ "$KIND" = "hopper" ]; then
        # 漏斗：装满物品，它会每 tick 尝试往下推
        echo "item replace block $x $LOAD_Y $z container.0 with minecraft:cobblestone 64" >> "$commands"
        echo "item replace block $x $LOAD_Y $z container.1 with minecraft:cobblestone 64" >> "$commands"
      else
        echo "item replace block $x $LOAD_Y $z container.0 with minecraft:iron_ore 64" >> "$commands"
        echo "item replace block $x $LOAD_Y $z container.1 with minecraft:coal 64" >> "$commands"
      fi
    done
  done
  local count
  count=$(wc -l < "$commands")
  $RCON --batch < "$commands" 2>&1 | tail -1
  echo "已装料 $count 条命令"
}

# 读 block_entities 那一行
read_block_entities() {
  $RCON "gtsnperf" 2>&1 | grep -E "方块实体" | head -1 | awk '{print $2, $3, $4, $5}'
}

run_case() {
  local label="$1"; shift
  local log="$RESULT_DIR/$label.log"

  echo "=== $label ==="
  rm -rf run/server/world
  cp -r build/world-base run/server/world
  SERVER_LOG="$log" bash tools/server-start.sh "$@"
  wait_rcon

  # 基准世界是漏斗+熔炉；把漏斗换成熔炉，全部装料
  $RCON "fill -$SIZE $LOAD_Y -$SIZE $SIZE $LOAD_Y $SIZE minecraft:$KIND replace minecraft:hopper" > /dev/null 2>&1
  $RCON "fill -$SIZE $LOAD_Y -$SIZE $SIZE $LOAD_Y $SIZE minecraft:$KIND replace minecraft:furnace" > /dev/null 2>&1
  sleep 2
  load_furnaces

  # 等熔炉烧起来，采样窗口跑满（profiler 保留最近 1200 tick = 60 秒）
  sleep 70

  local stats
  stats=$(read_block_entities)
  echo "$label: block_entities(avg p50 p99 max) = $stats"
  echo "$label $stats" >> "$RESULT_DIR/results.txt"

  bash tools/server-stop.sh
  sleep 3
}

: > "$RESULT_DIR/results.txt"

KIND="${BENCH_KIND:-furnace}"
LANES="${BENCH_LANES:-2}"
echo "测试类型=$KIND 并行度=$LANES 规模=$((2*SIZE+1))x$((2*SIZE+1))"
run_case serial
run_case parallel "-PparallelBlocks=$LANES" -PregionShift=1 "-PsafeTypes=minecraft:$KIND"
run_case spin "-PparallelBlocks=$LANES" -PregionShift=1 "-PsafeTypes=minecraft:$KIND" -Pspin

echo ""
echo "=== 结果 ==="
cat "$RESULT_DIR/results.txt"
