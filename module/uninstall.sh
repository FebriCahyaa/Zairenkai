#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Copyright (C) 2026 FebriCahyaa
# Stop the resident engine before removing root-side state.
ZPERF=/data/adb/zperf
PIDFILE="$ZPERF/zperfd.pid"

if [ -f "$PIDFILE" ]; then
  pid=$(cat "$PIDFILE" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) ;;
    *)
      kill -TERM "$pid" 2>/dev/null || true
      i=0
      while [ "$i" -lt 20 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 0.1
        i=$((i + 1))
      done
      if kill -0 "$pid" 2>/dev/null; then
        kill -KILL "$pid" 2>/dev/null || true
      fi
      ;;
  esac
fi

rm -f /data/powercfg.sh /data/powercfg.json
rm -rf /data/adb/zperf /data/adb/zkfc
