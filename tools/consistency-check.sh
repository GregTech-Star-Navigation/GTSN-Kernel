#!/usr/bin/env bash
# 二期 2b 一致性测试：同一个世界跑两次，一次原版 tick 路径、一次调度器路径。
#
#   bash tools/consistency-check.sh
#
# 判据：两次的 `hash(blocks)` 必须完全相同（含方块实体个数）。
#
# 为什么只比方块实体、不比实体：实体会移动、会互相推挤，两次运行的 tick 时机有偏差，
# 位置必然不同；而箱子/熔炉这类静态方块实体的状态只取决于「被 tick 了多少次」，稳定可比。
#
# 第一次跑需要先准备基准世界（自动做）：
#   起服 → forceload → 铺 289 个箱子 → 停服 → 备份到 build/world-base
set -euo pipefail
cd "$(dirname "$0")/.."

RCON="node tools/rcon.mjs"
RESULT_DIR="build/consistency"
BASE_WORLD="build/world-base"
mkdir -p "$RESULT_DIR"

# 同一时间只允许一个实例 —— 两个脚本并行会互相 stop 对方启动的服务端。
LOCK="$RESULT_DIR/.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "另一个 consistency-check 正在跑（$LOCK 存在）。先确认它真的活着，否则删掉锁重试。" >&2
  exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

log() { echo "[$(date +%H:%M:%S)] $*"; }

wait_rcon() {
  if ! $RCON --wait 300 "list" > /dev/null 2>&1; then
    echo "FAIL: 服务端没起来，看 $RESULT_DIR/*.log" >&2
    return 1
  fi
}

restore_world() {
  rm -rf run/server/world
  cp -r "$BASE_WORLD" run/server/world
}

read_hash() {
  $RCON "gtsnperf hash blocks" 2>&1 | grep -o 'hash(blocks)=[0-9a-f]*' | head -1
}

# 基准世界不存在就先造一个（只跑一次）
if [ ! -d "$BASE_WORLD" ]; then
  echo "=== 准备基准世界 ==="
  rm -rf run/server/world
  SERVER_LOG="$RESULT_DIR/prepare.log" bash tools/server-start.sh
  wait_rcon
  $RCON "forceload add -4 -4 4 4" > /dev/null 2>&1 || true
  $RCON "fill -8 -60 -8 8 -60 8 minecraft:stone" > /dev/null 2>&1 || true
  # 用漏斗而不是箱子：箱子不注册 ticker，并行路径会拿到空列表，测了等于没测。
  $RCON "fill -8 -59 -8 8 -59 8 minecraft:hopper" > /dev/null 2>&1 || true
  # 再加一层熔炉：熔炉是「零跨区块访问」的代表（实测 113288 次 tick 零失败），
  # 用来验证白名单并行路径真的接管了 ticker —— 只有漏斗的话并行白名单是空的。
  $RCON "fill -8 -58 -8 8 -58 8 minecraft:furnace" > /dev/null 2>&1 || true
  sleep 15
  bash tools/server-stop.sh
  cp -r run/server/world "$BASE_WORLD"
  echo "基准世界已建立（$BASE_WORLD）"
fi

log "=== 第一次：原版 tick 路径 ==="
restore_world
log "世界已恢复"
SERVER_LOG="$RESULT_DIR/vanilla.log" bash tools/server-start.sh
log "服务端已启动，等 RCON"
wait_rcon
log "RCON 就绪"
sleep 15
HASH_VANILLA=$(read_hash || true)
log "vanilla: ${HASH_VANILLA:-<空>}"
bash tools/server-stop.sh

sleep 3

log "=== 第二次：调度器路径（-PregionEnabled）==="
restore_world
log "世界已恢复"
SERVER_LOG="$RESULT_DIR/region.log" bash tools/server-start.sh -PregionEnabled
log "服务端已启动，等 RCON"
wait_rcon
log "RCON 就绪"
sleep 15
HASH_REGION=$(read_hash || true)
log "region : ${HASH_REGION:-<空>}"
bash tools/server-stop.sh

log "=== 第三次：方块实体并行（-PparallelBlocks=2）==="
restore_world
# regionShift=1：2×2 区块算一个 region。测试场地跨度 17 区块，这样才切得出多个 region，
  # 否则「并行」只会有 1 条 lane 有活干。
  SERVER_LOG="$RESULT_DIR/parallel.log" bash tools/server-start.sh -PparallelBlocks=2 -PregionShift=1
log "服务端已启动，等 RCON"
wait_rcon
log "RCON 就绪"
sleep 15
HASH_PARALLEL=$(read_hash || true)
log "parallel: ${HASH_PARALLEL:-<空>}"
bash tools/server-stop.sh

log "=== 第四次：白名单并行（-PsafeTypes=minecraft:furnace）==="
restore_world
SERVER_LOG="$RESULT_DIR/safeparallel.log" bash tools/server-start.sh -PparallelBlocks=2 -PregionShift=1 -PsafeTypes=minecraft:furnace
log "服务端已启动，等 RCON"
wait_rcon
log "RCON 就绪"
sleep 15
HASH_SAFEPARALLEL=$(read_hash || true)
log "safeParallel: ${HASH_SAFEPARALLEL:-<空>}"
bash tools/server-stop.sh

log "=== 第五次：漏斗并行（-PsafeTypes=minecraft:hopper，走非阻塞快路径）==="
restore_world
SERVER_LOG="$RESULT_DIR/hopperparallel.log" bash tools/server-start.sh -PparallelBlocks=2 -PregionShift=1 -PsafeTypes=minecraft:hopper
log "服务端已启动，等 RCON"
wait_rcon
log "RCON 就绪"
sleep 15
HASH_HOPPER=$(read_hash || true)
log "hopperParallel: ${HASH_HOPPER:-<空>}"
bash tools/server-stop.sh

echo ""
echo "=== 结果 ==="
if [ -n "$HASH_VANILLA" ] && [ "$HASH_VANILLA" = "$HASH_REGION" ] && [ "$HASH_VANILLA" = "$HASH_PARALLEL" ] && [ "$HASH_VANILLA" = "$HASH_SAFEPARALLEL" ] && [ "$HASH_VANILLA" = "$HASH_HOPPER" ]; then
  echo "PASS: 五条路径的世界状态一致"
  printf 'PASS\nvanilla=%s\nregion=%s\n' "$HASH_VANILLA" "$HASH_REGION" > "$RESULT_DIR/last-result.txt"
  exit 0
else
  echo "FAIL: 世界状态不一致"
  echo "  vanilla: ${HASH_VANILLA:-<空>}"
  echo "  region : ${HASH_REGION:-<空>}"
  echo "  parallel: ${HASH_PARALLEL:-<空>}"
  echo "  safeParallel: ${HASH_SAFEPARALLEL:-<空>}"
  echo "  hopperParallel: ${HASH_HOPPER:-<空>}"
  printf 'FAIL\nvanilla=%s\nregion=%s\n' "${HASH_VANILLA:-}" "${HASH_REGION:-}" > "$RESULT_DIR/last-result.txt"
  exit 1
fi
