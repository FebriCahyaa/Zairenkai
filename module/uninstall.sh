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
      # PID files can outlive a crash; never signal an unrelated process merely
      # because the numeric PID has been reused by Android after a reboot.
      actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
      actual_cmd="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
      case "$actual_exe $actual_cmd" in
        *"/data/adb/zperf/zperfd"*)
          kill -TERM "$pid" 2>/dev/null || true
          i=0
          while [ "$i" -lt 20 ] && kill -0 "$pid" 2>/dev/null; do
            actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
            case "$actual_exe" in *"/data/adb/zperf/zperfd"*) ;; *) break ;; esac
            sleep 0.1
            i=$((i + 1))
          done
          actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
          case "$actual_exe" in
            *"/data/adb/zperf/zperfd"*) kill -KILL "$pid" 2>/dev/null || true ;;
          esac
          ;;
        *) log -t zairenkai "uninstall: stale PID file does not point to zperfd; not signaling PID $pid" ;;
      esac
      ;;
  esac
fi

# Only remove global compatibility hooks when their ownership marker matches.
# A pre-existing hook from another module must survive Zairenkai uninstall.
if grep -Fq '# Zairenkai <-> Scene/vtools external-scheduler contract.' /data/powercfg.sh 2>/dev/null; then
  rm -f /data/powercfg.sh
fi
if grep -Fq '"integration": "zairenkai.scene-interop"' /data/powercfg.json 2>/dev/null || \
   grep -Fq '"version": "zairenkai-zperfd 1.0"' /data/powercfg.json 2>/dev/null; then
  rm -f /data/powercfg.json
fi
# Remove Zairenkai executables and transient daemon state, but preserve user
# profile overrides/custom profiles and owner-signed license material so a
# reinstall does not silently destroy configuration or require a new grant.
rm -f \
  /data/adb/zperf/zperfd \
  /data/adb/zperf/zperfd.pid \
  /data/adb/zperf/zperfd.log \
  /data/adb/zperf/daemon.lock \
  /data/adb/zperf/boot_pending \
  /data/adb/zperf/safe_mode \
  /data/adb/zperf/transaction.pending \
  /data/adb/zperf/powercfg.sh \
  /data/adb/zperf/powercfg.json \
  /data/adb/zkfc/zkfctl \
  /data/adb/zkfc/token.pending
rmdir /data/adb/zperf /data/adb/zkfc 2>/dev/null || true
