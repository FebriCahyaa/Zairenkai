#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# These are deliberately simple source-level guards; they complement, rather
# than replace, the C/Rust/Android test suites.
grep -q 'ZKFC_LIC_API_INCOMPATIBLE' kernel/include/uapi/linux/zkfc.h
grep -q 'ZKFC_IOC_GET_CAPABILITIES' kernel/include/uapi/linux/zkfc.h
grep -q 'struct pid \*target_pid' kernel/perf/zkfc_task_boost.c
grep -q 'find_get_pid' kernel/perf/zkfc_task_boost.c
grep -q 'put_pid' kernel/perf/zkfc_task_boost.c
grep -q 'zkfc_capabilities' kernel/core/zkfc_cap.c
grep -q 'CPU_FREQ' kernel/Kconfig
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
grep -q 'tests/abi/zkfc_uapi_test.c' .github/workflows/native.yml
grep -q 'tools/profile_validate.py database' .github/workflows/native.yml
grep -q 'diff -qr database module/zperf/database' .github/workflows/native.yml
grep -q 'setTweak(id, value, _ui.value.lite)' app/src/main/java/com/zairenkai/app/ui/Vm.kt
grep -q 'MAX_JOURNAL_BYTES' rust/zperfd/src/state.rs
grep -q 'write_commit_marker' rust/zperfd/src/state.rs
grep -q 'O_NOFOLLOW | libc::O_CLOEXEC' rust/zperfd/src/nodes.rs
grep -q 'rust-toolchain.toml' .github/workflows/release.yml || test -f rust-toolchain.toml
! grep -R -q 'dtolnay/rust-toolchain' .github/workflows
! grep -R -q 'expect("validated mode")' rust/zperfd/src
python3 tools/profile_validate.py database >/dev/null
python3 tools/zperf_analyze.py --help >/dev/null
python3 tools/zperf_analyze.py summary tests/analysis/baseline.jsonl >/dev/null

grep -q 'refreshed_nodes != nodes' rust/zperfd/src/main.rs
grep -q 'version=1' rust/zperfd/src/state.rs
grep -q 'android16-6.12.*r536225' kernel/gki/kmi_matrix.txt
grep -q 'common-android17-6.18.*auto' kernel/gki/kmi_matrix.txt
grep -q 'mod family;' rust/zperfd/src/main.rs
grep -q 'FamilyProfile' rust/zperfd/src/family.rs
grep -q 'managed node .*exceeds' rust/zperfd/src/state.rs

grep -q "ZKFC_CAP_TUNE_CPU" kernel/include/uapi/linux/zkfc.h
grep -q "zairenkai.core" core/manifest.toml
grep -q 'default = "deny"' database/schema/authority-v1.toml

grep -q 'zairenkai-core' rust/zperfd/Cargo.toml
test -f rust/zk-core/src/lib.rs
test -f database/registry/core.toml
test -f database/registry/authority.toml
test -f database/kernels/capabilities.toml
test -f docs/architecture/CORE_INVARIANTS.md
grep -q 'default = "deny"' database/schema/authority-v1.toml
grep -q 'ZKFC_CAP_TUNE_CPU' kernel/include/uapi/linux/zkfc.h

test -f database/schema/measurement-v2.toml
test -f tools/measurement_validate.py
grep -q 'ZKFC_IOC_THERMAL_GUARD' kernel/include/uapi/linux/zkfc.h
grep -q 'ZKFC_CAP_TUNE_THERMAL' kernel/include/uapi/linux/zkfc.h
grep -q 'performance_trip_mdeg' rust/zperfd/src/thermal.rs
grep -q 'control_temp_mdeg' rust/zperfd/src/main.rs
grep -q 'runtime-thermal-trip-topology' database/registry/thermal.toml
! grep -q 'performance_escalation_max_mdeg' database/registry/thermal.toml
! grep -q 'hard_block_mdeg' database/registry/thermal.toml
! grep -q 'hysteresis_mdeg' database/registry/thermal.toml
grep -q 'device did not expose a runtime allowlist' rust/zperfd/src/tweak.rs
grep -q 'property is outside the Zairenkai property allowlist' rust/zperfd/src/properties.rs
grep -q 'v1.1.0' module/module.prop
python3 -c 'from pathlib import Path; import tomllib; allowed=set(tomllib.loads(Path("database/schema/authority-v1.toml").read_text())["permissions"]); a=tomllib.loads(Path("database/registry/authority.toml").read_text()); bad=[c for p in a.get("principals",{}).values() for c in p.get("capabilities",[]) if c not in allowed]; assert not bad, bad; print("authority permissions aligned")'
test -f core/subsystems.toml
grep -q 'zairenkai.thermoguard' core/subsystems.toml
grep -q 'subsystem_registry = "subsystems.toml"' core/manifest.toml
python3 - <<'PY'
from pathlib import Path
import tomllib
registry = tomllib.loads(Path("database/registry/authority.toml").read_text())
android = registry["principals"]["android"]["capabilities"]
assert "read.properties" not in android, android
runtime = registry["principals"]["runtime"]["capabilities"]
assert "read.properties" in runtime, runtime
print("property authority boundary aligned")
PY
