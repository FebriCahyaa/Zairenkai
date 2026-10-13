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
# Lite mode must reach the engine on every tweak write: ViewModel -> repository
# -> ZperfClient -> `zperfd --lite`.
grep -q 'c.repository.setTweak(id, value, lite)' app/src/main/java/com/zairenkai/app/ui/TweaksViewModel.kt
grep -q 'zperf.setTweak(id, value, lite)' app/src/main/java/com/zairenkai/app/data/ZkfcRepository.kt
grep -q 'if (lite) "--lite " else ""' app/src/main/java/com/zairenkai/app/data/ZperfClient.kt
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

# APK size: the full material-icons-extended set must not come back (it made the
# unminified debug APK ~64 MB); needed icons are vendored in ui/icons. The
# sideloadable preview stays R8-optimized, i.e. not debuggable.
! grep -q 'material.icons.extended' app/build.gradle.kts
! grep -q 'material-icons-extended' gradle/libs.versions.toml
! grep -R -q 'androidx.compose.material.icons.rounded.\(Shield\|Bolt\|ChevronLeft\)' app/src/main
grep -q 'isDebuggable = false' app/build.gradle.kts
grep -q 'useLegacyPackaging.set(true)' app/build.gradle.kts

# Custom profiles: zperfd is the only writer and they stay off Auto/per-app.
grep -q 'mod custom;' rust/zperfd/src/main.rs
grep -q 'custom::inject(&mut profile, state)' rust/zperfd/src/main.rs
grep -q 'uses the prefix reserved for user-defined modes' rust/zperfd/src/config.rs
grep -q 'profile.base_of(&effective) != "powersave"' rust/zperfd/src/main.rs
grep -q 'custom put' app/src/main/java/com/zairenkai/app/data/ZperfClient.kt
! grep -q 'custom.toml' app/src/main/java/com/zairenkai/app/data/ZperfClient.kt

# Setup wizard: the system check is read-only and probes each layer; setup
# applies nothing (profiles are applied through zperfd from the Profiles tab),
# and Next is held until the legal terms are accepted.
grep -q 'fun setupChecks' app/src/main/java/com/zairenkai/app/ui/onboarding/SetupChecks.kt
! grep -q 'zperf.apply\|RootShell.run' app/src/main/java/com/zairenkai/app/ui/onboarding/OnboardingViewModel.kt
grep -q 'c.zperf.apply(mode)' app/src/main/java/com/zairenkai/app/ui/ProfilesViewModel.kt
grep -q 'SetupStep.LEGAL -> legalChecked' app/src/main/java/com/zairenkai/app/ui/onboarding/OnboardingViewModel.kt

# Monitor redesign: battery and frame telemetry are read-only zperfd
# commands, the FPS recorder only streams them, and the Monitor no longer
# measures its own UI frames.
grep -q 'mod observe;' rust/zperfd/src/main.rs
grep -A3 'fn cmd_battery' rust/zperfd/src/main.rs | grep -q 'Operation::ReadInventory'
grep -A3 'fn cmd_frames' rust/zperfd/src/main.rs | grep -q 'Operation::ReadPerformance'
! grep -q 'fs::write\|write_node\|apply_' rust/zperfd/src/observe.rs
grep -q 'frames --follow --watch' app/src/main/java/com/zairenkai/app/data/ZperfClient.kt
! grep -R -q 'RootShell.run\|tweak set\|apply(' app/src/main/java/com/zairenkai/app/data/fps app/src/main/java/com/zairenkai/app/data/monitor
test ! -e app/src/main/java/com/zairenkai/app/ui/components/FrameMonitor.kt

# Languages: every shipped locale has resources, the list matches
# locales_config.xml, and the setup animations are reproducible from the generator.
for q in values-en values-en-rGB values-ja values-es values-zh-rCN; do test -d "app/src/main/res/$q"; done
grep -q 'android:localeConfig="@xml/locales_config"' app/src/main/AndroidManifest.xml
tmp_lottie="$(mktemp -d)"; python3 tools/lottie/gen_setup_animations.py --out "$tmp_lottie" >/dev/null
for f in "$tmp_lottie"/setup_*.json; do diff -q "$f" "app/src/main/res/raw/$(basename "$f")" >/dev/null; done
# Every setup_*.json in res/raw comes from the generator (no stale files).
test "$(ls app/src/main/res/raw/setup_*.json | wc -l)" -eq "$(ls "$tmp_lottie"/setup_*.json | wc -l)"
# The mascot: one Lottie per state from the owner's poses (tools/lottie/gen_mascot.py).
for s in welcome idle inspecting ready warning celebrating; do
  test -f "art/mascot/$s.png"
  grep -q '"refId"' "app/src/main/res/raw/mascot_$s.json"
done
# Icons are the app's own: no Material icon library or vendored copies.
! grep -R -q 'androidx.compose.material.icons' app/src/main/java
! grep -q 'material-icons' app/build.gradle.kts gradle/libs.versions.toml
# Open-source licences: metadata and the bundled licence texts exist.
test -s tools/licenses/libraries.json
for t in Apache-2.0 MIT BSD-3-Clause OFL-1.1; do test -s "tools/licenses/texts/$t.txt"; done
# The launcher icon and splash mark are reproducible from their generator.
tmp_icon="$(mktemp -d)"; python3 tools/icon/gen_app_icon.py --out "$tmp_icon" >/dev/null
for f in ic_launcher_background ic_launcher_foreground ic_launcher_monochrome ic_zk_logo; do
  diff -q "$tmp_icon/$f.xml" "app/src/main/res/drawable/$f.xml" >/dev/null
done
# Typography is Google Sans Flex (OFL), bundled with its licence.
test -f app/src/main/res/font/google_sans_flex.ttf
grep -q "Google Sans Flex" LICENSES/OFL-1.1.txt
