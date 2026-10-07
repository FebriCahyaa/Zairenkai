#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# Build ZKFC as a GKI vendor module (loadable .ko) against one or more KMI
# branches. Compatibility is scoped to the exact KMI/configuration/toolchain
# used for the target GKI build; one module is not universal across branches.
#
# Copyright (C) 2026 FebriCahyaa
#
# Usage:
#   gki/build_lkm.sh <kmi-branch|all> [--arch arm64|x86_64|riscv64]
#                                     [--kernel-dir DIR] [--llvm-dir DIR]
#                                     [--out DIR] [--jobs N]
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
LLVM_DIR=""
# Keep output OUTSIDE the module tree: `make clean` removes every *.ko under
# the module source dir recursively, which would wipe a copy placed in it.
OUT="$(dirname "$ZKFC_SRC")/out/lkm"
JOBS="$(nproc)"

info() { printf '\033[1;36m[zkfc-lkm]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[zkfc-lkm]\033[0m %s\n' "$*" >&2; exit 1; }

kernel_clang_version() {
	local kdir="$1" file line
	for file in "$kdir/bazel/constants.scl" "$kdir/build.config.constants"; do
		[ -f "$file" ] || continue
		line="$(grep -E '(^|[[:space:]])CLANG_VERSION[[:space:]]*=' "$file" | head -n1 || true)"
		if [[ "$line" =~ r[0-9]+[a-z]* ]]; then
			printf '%s\n' "${BASH_REMATCH[0]}"
			return 0
		fi
	done
	return 1
}

llvm_dir_for() {
	local root="$1" clang="$2" candidate
	if [ -n "$LLVM_DIR" ]; then
		printf '%s\n' "$LLVM_DIR"
		return 0
	fi
	for candidate in \
		"${root}/prebuilts/clang/host/linux-x86/${clang}/bin" \
		"${root}/prebuilts/clang/host/linux-x86/clang-stable/bin"; do
		if [ -x "$candidate/clang" ]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done
	return 1
}

karch_for() {
	case "$1" in
	arm64) echo arm64 ;; x86_64) echo x86 ;; riscv64) echo riscv ;;
	*) die "unknown arch $1" ;;
	esac
}

matrix_row() {
	grep -E "^$1[[:space:]]" "$MATRIX" || die "unknown KMI branch '$1' (see kmi_matrix.txt)"
}

build_one() {
	local kmi="$1" row kver api aosp clang kdir karch llvm_dir repo_root root actual_kver tag
	row="$(matrix_row "$kmi")"
	read -r _ kver api aosp clang _ <<<"$row"
	karch="$(karch_for "$ARCH")"
	tag="zkfc-$kmi"

	if [ -n "$KERNEL_DIR" ]; then
		kdir="$KERNEL_DIR"
		repo_root="$(dirname "$kdir")"
	else
		root="$ZKFC_SRC/out/gki/$kmi"
		kdir="$root/common"
		repo_root="$root"
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
	actual_kver="$(make -s -C "$kdir" kernelversion 2>/dev/null || true)"
	case "$actual_kver" in
		"$kver"*) ;;
		*) die "kernel tree version '$actual_kver' does not match matrix branch $kmi (expected $kver.x)" ;;
	esac
	if [ "$clang" = "auto" ] || [ "$clang" = "AUTO" ]; then
		clang="$(kernel_clang_version "$kdir" || true)"
	fi
	[ -n "$clang" ] || die "kernel tree does not expose CLANG_VERSION; pass --llvm-dir DIR or update matrix fallback"
	llvm_dir="$(llvm_dir_for "$repo_root" "$clang")" || \
		die "matching LLVM toolchain '$clang' not found; pass --llvm-dir DIR or provide AOSP prebuilts/clang/.../$clang/bin"
	[ -x "$llvm_dir/clang" ] || die "clang not executable in $llvm_dir"
	[ -x "$llvm_dir/llvm-strip" ] || die "llvm-strip not executable in $llvm_dir"
	mkdir -p "$OUT/$kmi-$ARCH"
	info "building $tag ($ARCH, kernel $kver, api $api, clang $clang)"
	# GKI modules must use an LLVM toolchain compatible with the GKI branch.
	# LLVM=1 keeps the compiler/binutils family consistent and lets Kbuild
	# select the correct target from ARCH without mixing GNU cross tools.
	PATH="$llvm_dir:$PATH" make -C "$kdir" M="$ZKFC_SRC" \
		ARCH="$karch" LLVM=1 LLVM_IAS=1 \
		CONFIG_ZKFC=m CONFIG_ZKFC_HOOK_HYBRID=y \
		ZKFC_TAG="gki-$kmi" \
		-j"$JOBS" modules

	cp "$ZKFC_SRC/zkfc.ko" "$OUT/$kmi-$ARCH/zkfc.ko"
	# Strip debug info for a small shippable module.
	"$llvm_dir/llvm-strip" --strip-debug "$OUT/$kmi-$ARCH/zkfc.ko"
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
		--llvm-dir) LLVM_DIR="$(cd "$2" && pwd)"; shift 2 ;;
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
