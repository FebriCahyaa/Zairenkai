#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#
# Build zkfctl for Android (all ABIs) or for the host.
# Copyright (C) 2026 FebriCahyaa
#
# Usage:
#   ANDROID_NDK=/path/to/ndk userspace/build.sh android   # arm64 + x86_64
#   userspace/build.sh host                                # local test binary
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
UAPI="$HERE/../kernel/include/uapi"
SRC=("$HERE"/lib/*.c "$HERE/zkfctl/main.c")
CFLAGS=(-std=c11 -Wall -Wextra -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2
	-I"$UAPI" -I"$HERE/include" -I"$HERE/lib")

build_host() {
	mkdir -p "$OUT/host"
	cc "${CFLAGS[@]}" "${SRC[@]}" -o "$OUT/host/zkfctl"
	echo "-> $OUT/host/zkfctl"
}

build_android() {
	local ndk="${ANDROID_NDK:-${NDK:-}}"
	[ -n "$ndk" ] || { echo "set ANDROID_NDK"; exit 1; }
	local tc; tc="$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin)"
	local api="${API:-31}"
	for abi in "aarch64-linux-android:arm64-v8a" "x86_64-linux-android:x86_64"; do
		local triple="${abi%%:*}" name="${abi##*:}"
		mkdir -p "$OUT/$name"
		"$tc/${triple}${api}-clang" "${CFLAGS[@]}" -fPIE -pie \
			"${SRC[@]}" -o "$OUT/$name/zkfctl"
		"$tc/llvm-strip" "$OUT/$name/zkfctl"
		echo "-> $OUT/$name/zkfctl"
	done
}

case "${1:-host}" in
	host) build_host ;;
	android) build_android ;;
	*) echo "usage: build.sh host|android"; exit 2 ;;
esac
