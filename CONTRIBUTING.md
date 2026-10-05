<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Contributing to Zairenkai

Thanks for your interest. Please read [`COPYRIGHT`](COPYRIGHT) first: components
carry different licenses (kernel is GPL-2.0; app/engine/module/tools are the
Zairenkai Proprietary License). By submitting a contribution you agree to the
terms in Section 6 of `LICENSES/LicenseRef-Zairenkai-Proprietary.txt`;
contributions under `kernel/` are additionally GPL-2.0-only.

## Ground rules

- Every source file keeps its `SPDX-License-Identifier` header.
- Don't commit secrets. The owner signing key, issued tokens (`*.zkl`,
  `*.inc`), and keystores are git-ignored — keep it that way.
- Kernel code follows the Linux kernel coding style; run `checkpatch` where you
  can. Userspace C targets C11, `-Wall -Wextra`.
- The app is Kotlin + Compose; keep `./gradlew :app:lintDebug` clean.

## Building / testing

| Part | Command |
|------|---------|
| Crypto vectors | `python3 tests/crypto/gen_vectors.py /tmp/v.txt && gcc -fsanitize=address,undefined -I kernel/crypto tests/crypto/crypto_test.c kernel/crypto/zk_*.c -o t && ./t /tmp/v.txt` |
| Kernel module (host) | `make -C kernel KDIR=/lib/modules/$(uname -r)/build modules` |
| GKI LKM | `kernel/gki/build_lkm.sh <kmi> --kernel-dir <gki-out>` |
| Engine | `userspace/build.sh host` |
| App | `./gradlew :app:assembleDebug` |

CI runs the crypto vectors, a host module compile, the NDK engine build and the
app build on every PR (`.github/workflows/`).

## ZKFC API changes

Bumping the ioctl ABI means bumping `ZKFC_API_*` in
`kernel/include/uapi/linux/zkfc.h` and the `BUILD_BUG_ON` struct-size checks in
`kernel/core/zkfc_main.c`. A major bump makes older tokens report
`ZKFC_LIC_API_OUTDATED` — document it.
