#!/usr/bin/env bash
# 优雅停服：RCON stop → 等 MC 进程退出 → 收掉 tail。
#
#   bash tools/server-stop.sh
#
# 不强杀：强杀会让 Gradle 的 transform 缓存处于半写状态，下次构建报
# "Could not read workspace metadata from .../transforms/*/metadata.bin"，
# 必须删掉 ~/.gradle/caches/<version>/transforms 才能恢复。
set -uo pipefail
cd "$(dirname "$0")/.."

node tools/rcon.mjs "stop" > /dev/null 2>&1 || true

for i in $(seq 1 90); do
  count=$(powershell -NoProfile -Command \
    "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*forgeserveruserdev*' }).Count" \
    2>/dev/null | tr -d '\r\n ')
  if [ "${count:-0}" = "0" ] || [ -z "$count" ]; then
    echo "已停（${i}s）"
    rm -f build/consistency/server.pid
    # tail 是 setsid 起的，按命令行精确匹配收掉
    powershell -NoProfile -Command \
      "Get-CimInstance Win32_Process -Filter \"Name='tail.exe'\" | Where-Object { \$_.CommandLine -like '*dev/null*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" \
      2>/dev/null || true
    exit 0
  fi
  sleep 1
done

echo "WARN: MC 进程 90 秒未退出" >&2
exit 1
