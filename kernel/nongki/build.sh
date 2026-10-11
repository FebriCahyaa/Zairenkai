#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# Build ZKFC for a NON-GKI / vendor kernel (MSM, MediaTek, Exynos, Kirin ...).
# Non-GKI kernels pin exact symbol versions, so ZKFC is built in the SAME tree
# as the kernel (built-in =y, or module =m against that tree's Module.symvers).
#
# Copyright (C) 2026 FebriCahyaa
#
# Usage:
#   nongki/build.sh --kernel-dir DIR --arch arm64 [--cross PREFIX]
#                   [--defconfig NAME] [--module] [--tag TAG] [--jobs N]
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ZKFC_SRC="$(dirname "$HERE")"
ARCH="arm64"; CROSS=""; KDIR=""; DEFCONFIG=""; MODULE=0; TAG="nongki"; JOBS="$(nproc)"
die(){ printf '\033[1;31m[zkfc]\033[0m %s\n' "$*" >&2; exit 1; }
info(){ printf '\033[1;36m[zkfc]\033[0m %s\n' "$*"; }
while [ $# -gt 0 ]; do case "$1" in
	--kernel-dir) KDIR="$(cd "$2"&&pwd)"; shift 2;;
	--arch) ARCH="$2"; shift 2;;
	--cross) CROSS="$2"; shift 2;;
	--defconfig) DEFCONFIG="$2"; shift 2;;
	--module) MODULE=1; shift;;
	--tag) TAG="$2"; shift 2;;
	--jobs) JOBS="$2"; shift 2;;
	*) die "unknown option $1";; esac; done
[ -n "$KDIR" ] || die "pass --kernel-dir (the non-GKI kernel source root)"
case "$ARCH" in arm64) KA=arm64; CROSS="${CROSS:-aarch64-linux-gnu-}";;
	x86_64) KA=x86; CROSS="${CROSS:-x86_64-linux-gnu-}";;
	riscv64) KA=riscv; CROSS="${CROSS:-riscv64-linux-gnu-}";;
	*) die "unknown arch $ARCH";; esac

if [ "$MODULE" = 1 ]; then
	[ -f "$KDIR/Module.symvers" ] || die "non-GKI module build needs a built tree ($KDIR/Module.symvers)"
	info "building module against $KDIR"
	make -C "$KDIR" M="$ZKFC_SRC" ARCH="$KA" CROSS_COMPILE="$CROSS" \
		CONFIG_ZKFC=m CONFIG_ZKFC_HOOK_HYBRID=y ZKFC_TAG="$TAG" -j"$JOBS" modules
	info "-> $ZKFC_SRC/zkfc.ko"
else
	info "integrating ZKFC built-in into $KDIR"
	( cd "$KDIR" && bash "$ZKFC_SRC/setup.sh" )
	[ -n "$DEFCONFIG" ] && info "now add kernel/nongki/zkfc_nongki.fragment to $DEFCONFIG and rebuild the kernel"
	info "run your normal kernel build; ZKFC is compiled into the image"
fi
