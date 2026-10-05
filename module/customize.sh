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
rm -rf "$MODPATH/bin"
set_perm /data/adb/zkfc/zkfctl 0 0 0755 u:object_r:system_file:s0 2>/dev/null
set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "- Installed zkfctl to /data/adb/zkfc/zkfctl"
ui_print "- Reboot, then open the Zairenkai app"
