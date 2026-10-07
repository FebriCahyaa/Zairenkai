#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Zairenkai <-> Scene/vtools external-scheduler contract.
#
# Scene (and compatible front-ends) invoke this as `sh /data/powercfg.sh <mode>`
# on every mode / foreground change, with $1 = init|powersave|balance|
# performance|fast|pedestal and, when available, $top_app / $category in the
# environment. We record the requested mode and apply it through zperfd; the
# resident daemon also converges on the mode file on its own.
#
# Copyright (C) 2026 FebriCahyaa
STATE=/data/adb/zperf
BIN="$STATE/zperfd"

mode="$1"
[ -z "$mode" ] && mode="$2"      # some callers pass the mode as $2
[ -z "$mode" ] && mode="balance"

case "$mode" in
  init) exit 0 ;;                 # Scene startup probe; daemon already running
  pedestal) mode="fast" ;;        # we have no dock mode yet; map to fast
esac

mkdir -p "$STATE"
# Never publish desired state before the engine accepts and commits the change.
# zperfd is the single state owner; this contract is intentionally a thin adapter.
[ -x "$BIN" ] || exit 0
"$BIN" set "$mode" >/dev/null 2>&1
exit $?
