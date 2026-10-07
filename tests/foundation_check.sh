#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# These are deliberately simple source-level guards; they complement, rather
# than replace, the C/Rust/Android test suites.
grep -q 'ZKFC_LIC_API_INCOMPATIBLE' kernel/include/uapi/linux/zkfc.h
grep -q 'api_min_supported > expected' userspace/lib/libzkfc.c
grep -q 'process.destroyForcibly()' app/src/main/java/com/zairenkai/app/data/RootShell.kt
grep -q 'OpenOptions::new' rust/zperfd/src/nodes.rs
grep -q 'Build zperfd' .github/workflows/release.yml
grep -q 'zperfd started' module/service.sh

printf '%s\n' 'foundation source checks passed'
test "$(file -b --mime-type module/service.sh)" = "text/x-shellscript" || grep -q '^#!/system/bin/sh' module/service.sh
test "$(file -b --mime-type userspace/zkfctl/main.c)" = "text/x-c" || true
test $(grep -c 'Build zperfd and validate workspace' .github/workflows/release.yml) -eq 1
grep -q 'chmod 0700 /data/adb/zperf' module/customize.sh
grep -q 'version.api_compatible()' rust/zperfd/src/main.rs
grep -q 'transaction.committed' rust/zperfd/src/state.rs
grep -q 'O_NOFOLLOW' userspace/zkfctl/main.c
grep -q 'pending_transaction' app/src/main/java/com/zairenkai/app/data/ZperfModels.kt
grep -q 'daemon.lock' rust/zperfd/src/main.rs
grep -q 'args.lite' rust/zperfd/src/main.rs
grep -q 't->lite' userspace/zkfctl/main.c
grep -q 'setTweak(id, value, _ui.value.lite)' app/src/main/java/com/zairenkai/app/ui/Vm.kt
grep -q 'MAX_JOURNAL_BYTES' rust/zperfd/src/state.rs
grep -q 'write_commit_marker' rust/zperfd/src/state.rs
grep -q 'O_NOFOLLOW | libc::O_CLOEXEC' rust/zperfd/src/nodes.rs
grep -q 'rust-toolchain.toml' .github/workflows/release.yml || test -f rust-toolchain.toml
! grep -R -q 'dtolnay/rust-toolchain' .github/workflows
! grep -R -q 'expect("validated mode")' rust/zperfd/src

grep -q 'refreshed_nodes != nodes' rust/zperfd/src/main.rs
grep -q 'version=1' rust/zperfd/src/state.rs
grep -q 'managed node .*exceeds' rust/zperfd/src/state.rs
