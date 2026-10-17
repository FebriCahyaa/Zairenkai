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
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export CARGO_TARGET_DIR="$TARGET"
test -d "$ANDROID_NDK_HOME/toolchains/llvm" || {
    echo "Android NDK not found at $ANDROID_NDK_HOME; install NDK 30.0.16248370 or set ANDROID_NDK_HOME" >&2
    exit 1
}

rustup target add aarch64-linux-android x86_64-linux-android
test -f "$CRATE" || {
    echo "Cargo manifest not found: $CRATE" >&2
    exit 1
}

cd "$ROOT/native/bootpatch"
cargo ndk -t arm64-v8a -t x86_64 --platform 31 build --release --bin zkbootpatch

for pair in "aarch64-linux-android:arm64-v8a" "x86_64-linux-android:x86_64"; do
    triple="${pair%%:*}"
    abi="${pair##*:}"
    source="$TARGET/$triple/release/zkbootpatch"
    dest="$JNI/$abi/libzkbootpatch.so"
    test -x "$source" || { echo "missing target executable: $source" >&2; exit 1; }
    mkdir -p "$(dirname "$dest")"
    install -m 0755 "$source" "$dest"
    if command -v file >/dev/null 2>&1; then file "$dest"; fi
done

echo "Standalone GPL boot patcher prepared in app/src/main/jniLibs (no PID-1 wrapper)."
