#!/usr/bin/env bash
# 后台启动服务端并保持 stdin 打开。
#
#   bash tools/server-start.sh [额外的 gradlew 参数…]
#
# 为什么需要它：MinecraftServer 的 console handler 从 stdin 读命令，读到 EOF 会立即停服
# （表现为 "Done (...)" 同一秒就 "Stopping server"）。nohup 把 stdin 设成 /dev/null，
# 重定向空文件也一样 —— 必须是「永不 EOF 的流」，也就是一个不会退出的 tail 管道。
#
# setsid 让整个管道脱离当前会话，bash 工具超时也不会带走服务端。
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p build/consistency run/server
LOG="${SERVER_LOG:-build/consistency/server.log}"
PIDFILE="build/consistency/server.pid"

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "服务端已在跑（pid $(cat "$PIDFILE")）。先 bash tools/server-stop.sh" >&2
  exit 1
fi

nohup bash -c "tail -f /dev/null | ./gradlew.bat runServer $* --console=plain > '$LOG' 2>&1" &
echo $! > "$PIDFILE"
echo "已启动 pid=$(cat "$PIDFILE")，日志 $LOG"
