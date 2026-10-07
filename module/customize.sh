#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Zairenkai module install script (Magisk / KernelSU / APatch).
# Copyright (C) 2026 FebriCahyaa
SKIPUNZIP=0

ui_print "- Zairenkai ZKFC installer"
ARCH64="$(getprop ro.product.cpu.abi)"
API="$(getprop ro.build.version.sdk)"
ui_print "  device abi: $ARCH64, api: $API"

if [ "$API" -lt 31 ]; then
  ui_print "! Zairenkai requires Android 12 (API 31) or newer"
  abort "  aborting"
fi

# Pick the matching prebuilt GKI module for this kernel, if shipped.
KREL="$(uname -r)"
ui_print "- Kernel: $KREL"
KMI="$(echo "$KREL" | grep -oE 'android[0-9]+-[0-9]+\.[0-9]+' | head -1)"
ABIDIR="arm64"
case "$ARCH64" in
  arm64*) ABIDIR="arm64" ;;
  x86_64) ABIDIR="x86_64" ;;
esac

mkdir -p "$MODPATH/zkfc"
if [ -n "$KMI" ] && [ -f "$MODPATH/lkm/$KMI-$ABIDIR/zkfc.ko" ]; then
  cp "$MODPATH/lkm/$KMI-$ABIDIR/zkfc.ko" "$MODPATH/zkfc/zkfc.ko"
  ui_print "- Bundled GKI module selected: $KMI-$ABIDIR"
else
  ui_print "! No bundled module for '$KMI/$ABIDIR'."
  ui_print "  If ZKFC is built into your kernel, this is fine."
  ui_print "  Otherwise build one: kernel/gki/build_lkm.sh $KMI"
fi
# Keep only the selected .ko to save space.
rm -rf "$MODPATH/lkm"

# Install the userspace engine.
mkdir -p /data/adb/zkfc
for abi in arm64-v8a arm64 x86_64; do
  if [ -f "$MODPATH/bin/$abi/zkfctl" ]; then
    cp "$MODPATH/bin/$abi/zkfctl" /data/adb/zkfc/zkfctl
    break
  fi
done

# ---- Install the zperfd performance engine + profile catalog ----
mkdir -p /data/adb/zperf/catalog
for abi in arm64-v8a arm64 x86_64; do
  if [ -f "$MODPATH/bin/$abi/zperfd" ]; then
    cp "$MODPATH/bin/$abi/zperfd" /data/adb/zperf/zperfd
    ui_print "- zperfd engine installed ($abi)"
    break
  fi
done
if [ -d "$MODPATH/zperf/catalog" ]; then
  cp "$MODPATH"/zperf/catalog/*.toml /data/adb/zperf/catalog/ 2>/dev/null
fi
# Scene / vtools external-scheduler contract.
if [ -f "$MODPATH/zperf/powercfg.sh" ]; then
  cp "$MODPATH/zperf/powercfg.sh" /data/powercfg.sh && chmod 0755 /data/powercfg.sh
  [ -f "$MODPATH/zperf/powercfg.json" ] && cp "$MODPATH/zperf/powercfg.json" /data/powercfg.json
  ui_print "- Scene powercfg contract installed (/data/powercfg.sh)"
fi
[ -f /data/adb/zperf/mode ] || echo balance > /data/adb/zperf/mode
chmod 0600 /data/adb/zperf/mode 2>/dev/null || true
chmod 0700 /data/adb/zperf 2>/dev/null || true
chmod 0600 /data/adb/zperf/mode 2>/dev/null || true

rm -rf "$MODPATH/bin"
set_perm /data/adb/zkfc/zkfctl 0 0 0755 u:object_r:system_file:s0 2>/dev/null
[ -f /data/adb/zperf/zperfd ] && set_perm /data/adb/zperf/zperfd 0 0 0755 u:object_r:system_file:s0 2>/dev/null
set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "- Installed zkfctl to /data/adb/zkfc/zkfctl"
ui_print "- Reboot, then open the Zairenkai app"
