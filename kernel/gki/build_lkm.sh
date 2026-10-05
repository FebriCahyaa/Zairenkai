#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# Build ZKFC as a GKI vendor module (loadable .ko) against one or more KMI
# branches, so a single module loads on every device shipping that branch.
#
# Copyright (C) 2026 FebriCahyaa
#
# Usage:
#   gki/build_lkm.sh <kmi-branch|all> [--arch arm64|x86_64|riscv64]
#                                     [--kernel-dir DIR] [--out DIR] [--jobs N]
#
# Two ways to supply the GKI kernel:
#   1. --kernel-dir DIR : an already-built GKI tree that contains
#                         Module.symvers and the KMI headers (fastest).
#   2. nothing          : the script fetches the AOSP common kernel for the
#                         branch with `repo` and builds it once (needs network
#                         and the prebuilt Clang toolchain; see kmi_matrix.txt).
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ZKFC_SRC="$(dirname "$HERE")"
MATRIX="$HERE/kmi_matrix.txt"
ARCH="arm64"
KERNEL_DIR=""
# Keep output OUTSIDE the module tree: `make clean` removes every *.ko under
# the module source dir recursively, which would wipe a copy placed in it.
OUT="$(dirname "$ZKFC_SRC")/out/lkm"
JOBS="$(nproc)"

info() { printf '\033[1;36m[zkfc-lkm]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[zkfc-lkm]\033[0m %s\n' "$*" >&2; exit 1; }

cross_for() {
	case "$1" in
	arm64)   echo "aarch64-linux-gnu-" ;;
	x86_64)  echo "x86_64-linux-gnu-" ;;
	riscv64) echo "riscv64-linux-gnu-" ;;
	*) die "unknown arch $1" ;;
	esac
}
karch_for() {
	case "$1" in
	arm64) echo arm64 ;; x86_64) echo x86 ;; riscv64) echo riscv ;;
	esac
}

matrix_row() {
	grep -E "^$1[[:space:]]" "$MATRIX" || die "unknown KMI branch '$1' (see kmi_matrix.txt)"
}

build_one() {
	local kmi="$1" row kver api aosp clang kdir karch cross tag
	row="$(matrix_row "$kmi")"
	read -r _ kver api aosp clang _ <<<"$row"
	karch="$(karch_for "$ARCH")"
	cross="$(cross_for "$ARCH")"
	tag="zkfc-$kmi"

	if [ -n "$KERNEL_DIR" ]; then
		kdir="$KERNEL_DIR"
	else
		kdir="$ZKFC_SRC/out/gki/$kmi/common"
		if [ ! -f "$kdir/Module.symvers" ]; then
			command -v repo >/dev/null || die "install 'repo' or pass --kernel-dir"
			info "fetching $aosp (this is large; one time per branch)"
			mkdir -p "$ZKFC_SRC/out/gki/$kmi"
			( cd "$ZKFC_SRC/out/gki/$kmi"
			  repo init -u https://android.googlesource.com/kernel/manifest -b "$aosp" --depth=1
			  repo sync -c -j"$JOBS" --no-tags --optimized-fetch )
			info "building GKI $aosp once to get Module.symvers"
			( cd "$ZKFC_SRC/out/gki/$kmi"
			  if [ -f build/build.sh ]; then
				LTO=thin BUILD_CONFIG=common/build.config.gki.${ARCH} build/build.sh
			  else
				tools/bazel build //common:kernel_${ARCH}_dist || \
				  tools/bazel run //common:kernel_${ARCH}_dist
			  fi )
		fi
	fi

	[ -f "$kdir/Module.symvers" ] || die "no Module.symvers in $kdir"
	mkdir -p "$OUT/$kmi-$ARCH"
	info "building $tag ($ARCH, kernel $kver, api $api)"
	# Note: do NOT pass KBUILD_EXTRA_SYMBOLS=$kdir/Module.symvers — kbuild
	# already consumes the kernel's own symbols; passing it again makes
	# modpost report every kernel export "exported twice".
	make -C "$kdir" M="$ZKFC_SRC" \
		ARCH="$karch" CROSS_COMPILE="$cross" \
		CONFIG_ZKFC=m CONFIG_ZKFC_HOOK_HYBRID=y \
		ZKFC_TAG="gki-$kmi" \
		-j"$JOBS" modules

	cp "$ZKFC_SRC/zkfc.ko" "$OUT/$kmi-$ARCH/zkfc.ko"
	# Strip debug info for a small shippable module.
	"${cross}strip" --strip-debug "$OUT/$kmi-$ARCH/zkfc.ko" 2>/dev/null || true
	make -C "$kdir" M="$ZKFC_SRC" ARCH="$karch" clean >/dev/null 2>&1 || true
	info "-> $OUT/$kmi-$ARCH/zkfc.ko"
}

main() {
	local target="${1:-}"; shift || true
	[ -n "$target" ] || die "usage: build_lkm.sh <kmi-branch|all> [options]"
	while [ $# -gt 0 ]; do
		case "$1" in
		--arch) ARCH="$2"; shift 2 ;;
		--kernel-dir) KERNEL_DIR="$(cd "$2" && pwd)"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--jobs) JOBS="$2"; shift 2 ;;
		*) die "unknown option $1" ;;
		esac
	done

	if [ "$target" = "all" ]; then
		while read -r kmi _; do
			[[ "$kmi" =~ ^#|^$ ]] && continue
			build_one "$kmi"
		done < "$MATRIX"
	else
		build_one "$target"
	fi
	info "done. modules in $OUT"
}

main "$@"
