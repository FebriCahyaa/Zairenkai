#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CRATE="$ROOT/native/bootpatch/Cargo.toml"
TARGET="$ROOT/native/bootpatch/target"
JNI="$ROOT/app/src/main/jniLibs"

command -v cargo >/dev/null || { echo "cargo is required" >&2; exit 1; }
command -v rustup >/dev/null || { echo "rustup is required to add Android targets" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "Install cargo-ndk: cargo install cargo-ndk --locked" >&2; exit 1; }
: "${ANDROID_NDK_HOME:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
export ANDROID_NDK_HOME
export CARGO_TARGET_DIR="$TARGET"
test -d "$ANDROID_NDK_HOME/toolchains/llvm" || {
    echo "Android NDK not found at $ANDROID_NDK_HOME; install NDK 30.0.16248370 or set ANDROID_NDK_HOME" >&2
    exit 1
}

rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -t x86_64 --platform 31 build --manifest-path "$CRATE" --release

for pair in "aarch64-linux-android:arm64-v8a" "x86_64-linux-android:x86_64"; do
    triple="${pair%%:*}"
    abi="${pair##*:}"
    source="$TARGET/$triple/release/zkbootpatch"
    init_source="$TARGET/$triple/release/zkinit"
    dest="$JNI/$abi/libzkbootpatch.so"
    init_dest="$JNI/$abi/libzkinit.so"
    test -x "$source" || { echo "missing target executable: $source" >&2; exit 1; }
    test -x "$init_source" || { echo "missing target init wrapper: $init_source" >&2; exit 1; }
    mkdir -p "$(dirname "$dest")"
    install -m 0755 "$source" "$dest"
    install -m 0755 "$init_source" "$init_dest"
    if command -v file >/dev/null 2>&1; then file "$dest" "$init_dest"; fi
done

echo "Standalone GPL boot patcher and init wrapper prepared in app/src/main/jniLibs."
