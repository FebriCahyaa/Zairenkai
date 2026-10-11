#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# late_start service: load ZKFC, restore trusted state, and start one zperfd.
# Copyright (C) 2026 FebriCahyaa
MODDIR=${0%/*}

until [ -d /dev ]; do sleep 1; done

# Load the kernel module if ZKFC is not already built in.
if [ ! -e /dev/zkfc ] && [ -f "$MODDIR/zkfc/zkfc.ko" ]; then
  insmod "$MODDIR/zkfc/zkfc.ko" 2>/dev/null \
    && log -t zairenkai "zkfc.ko loaded" \
    || log -t zairenkai "insmod zkfc.ko failed (KMI mismatch?)"
fi

# Tighten and restore the root-side license state before the performance engine starts.
STATE=/data/adb/zkfc
mkdir -p "$STATE"
chmod 0700 "$STATE" 2>/dev/null || true
[ -f "$STATE/zkfctl" ] && chmod 0755 "$STATE/zkfctl"
license_restore_failed=0
if [ -x "$STATE/zkfctl" ] && [ -f "$STATE/crl.zkcrl" ]; then
  if ! "$STATE/zkfctl" license crl "$STATE/crl.zkcrl" >/dev/null 2>&1; then
    log -t zairenkai "persistent CRL rejected; entering SAFE MODE"
    license_restore_failed=1
  fi
fi
if [ -x "$STATE/zkfctl" ] && [ -f "$STATE/token.zkl" ]; then
  if ! "$STATE/zkfctl" license install "$STATE/token.zkl" >/dev/null 2>&1; then
    log -t zairenkai "persistent API token rejected; performance stays locked"
  fi
fi
# A staged token is never trusted at boot. It is an interrupted hand-off and
# may contain sensitive or incomplete material, so discard it fail-closed.
rm -f "$STATE/token.pending"

# Boot fail-safe. Two consecutive boots without a healthy app confirmation put
# the device in SAFE MODE. zperfd also observes this marker and restores its
# baseline instead of applying a performance profile.
PENDING="$STATE/boot_pending"
SAFE="$STATE/safe_mode"
THRESHOLD=2
count=0
[ -f "$PENDING" ] && count=$(cat "$PENDING" 2>/dev/null | grep -o '^[0-9]*' | head -1)
[ -n "$count" ] || count=0

if [ "$license_restore_failed" -eq 1 ]; then
  : > "$SAFE"
  log -t zairenkai "SAFE MODE: license state could not be restored"
elif [ "$count" -ge "$THRESHOLD" ]; then
  : > "$SAFE"
  log -t zairenkai "SAFE MODE: $count unconfirmed boots; performance profile disabled"
else
  echo $((count + 1)) > "$PENDING"
  chmod 0600 "$PENDING" 2>/dev/null || true
  rm -f "$SAFE"
fi

# zperfd is the sole owner of performance profile application. Never start a
# second daemon instance when the service is re-triggered by the root manager.
ZPERF=/data/adb/zperf
ZBIN="$ZPERF/zperfd"
PIDFILE="$ZPERF/zperfd.pid"
mkdir -p "$ZPERF"
chmod 0700 "$ZPERF" 2>/dev/null || true
if [ "$license_restore_failed" -eq 0 ] && [ -x "$ZBIN" ]; then
  running=0
  if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && running=1
  fi
  if [ "$running" -eq 0 ]; then
    rm -f "$PIDFILE"
    "$ZBIN" daemon --state "$ZPERF" >/dev/null 2>&1 &
    echo $! > "$PIDFILE"
    chmod 0600 "$PIDFILE" 2>/dev/null || true
    log -t zairenkai "zperfd started"
  fi
fi

# The app calls `zkfctl safe confirm` only after its stability window passes.
