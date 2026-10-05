<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai Rust bindings (`zkfc-sys`)

Rust FFI to `/dev/zkfc`, built on the [`libc`](https://github.com/rust-lang/libc)
crate. It mirrors the C `libzkfc` and uses the same stable UAPI
(`kernel/include/uapi/linux/zkfc.h`), so the ioctl numbers are computed the
same way (asm-generic encoding for arm64 / x86_64 / riscv64).

```sh
cargo build --release                 # host
cargo test                            # ioctl-number + struct-size checks
cargo run --bin zkfc-info             # prints ZKFC version as JSON (needs root + module)
```

Cross-compile for Android (arm64):
```sh
rustup target add aarch64-linux-android
cargo build --release --target aarch64-linux-android
```

`zkfc-sys` is the foundation for optional Rust components; the C `zkfctl`
remains the engine the app ships with.
