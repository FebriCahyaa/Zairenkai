#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# late_start service: load the ZKFC module and settle permissions.
# Copyright (C) 2026 FebriCahyaa
MODDIR=${0%/*}

# Wait for /dev to be ready.
until [ -d /dev ]; do sleep 1; done

# Load the kernel module if ZKFC is not already built-in.
if [ ! -e /dev/zkfc ] && [ -f "$MODDIR/zkfc/zkfc.ko" ]; then
  insmod "$MODDIR/zkfc/zkfc.ko" 2>/dev/null \
    && log -t zairenkai "zkfc.ko loaded" \
    || log -t zairenkai "insmod zkfc.ko failed (KMI mismatch?)"
fi

# Ensure the engine is executable.
[ -f /data/adb/zkfc/zkfctl ] && chmod 0755 /data/adb/zkfc/zkfctl

# ---- Boot fail-safe (sudden reboot / kernel panic / bootloop rollback) ----
# boot_pending counts boots that were never confirmed by the app. Two or more
# unconfirmed boots means the last boot(s) crashed or rebooted before the app
# came up, so we enter SAFE MODE and do NOT re-apply the boot profile.
STATE=/data/adb/zkfc
PENDING="$STATE/boot_pending"
SAFE="$STATE/safe_mode"
THRESHOLD=2
mkdir -p "$STATE"

count=0
[ -f "$PENDING" ] && count=$(cat "$PENDING" 2>/dev/null | grep -o '^[0-9]*' || echo 0)
[ -z "$count" ] && count=0

if [ "$count" -ge "$THRESHOLD" ]; then
  : > "$SAFE"
  log -t zairenkai "SAFE MODE: $count unconfirmed boots; boot profile skipped"
else
  echo $((count + 1)) > "$PENDING"
  rm -f "$SAFE"
  # Apply a saved boot profile only when not in safe mode.
  if [ -x "$STATE/zkfctl" ] && [ -f "$STATE/boot_profile" ]; then
    while read -r id val; do
      [ -n "$id" ] && "$STATE/zkfctl" tweak set "$id" "$val" >/dev/null 2>&1
    done < "$STATE/boot_profile"
  fi

  # Start the zperfd performance engine (reacts to foreground app + mode file).
  # Skipped in safe mode so a bad profile can never contribute to a bootloop.
  ZPERF=/data/adb/zperf
  if [ -x "$ZPERF/zperfd" ]; then
    "$ZPERF/zperfd" daemon --state "$ZPERF" >/dev/null 2>&1 &
    log -t zairenkai "zperfd daemon started"
  fi
fi
# The app calls `zkfctl safe confirm` once it is up, which clears boot_pending.
