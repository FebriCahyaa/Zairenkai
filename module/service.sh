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

# Apply a saved boot profile, if the app wrote one.
if [ -x /data/adb/zkfc/zkfctl ] && [ -f /data/adb/zkfc/boot_profile ]; then
  while read -r id val; do
    [ -n "$id" ] && /data/adb/zkfc/zkfctl tweak set "$id" "$val" >/dev/null 2>&1
  done < /data/adb/zkfc/boot_profile
fi
