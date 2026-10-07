#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Thermal policy must be expressed in normalized runtime headroom, never a
# universal Celsius threshold in the production (non-test) policy path.
python3 - <<'PY'
from pathlib import Path
import re, tomllib

policy = Path("rust/zk-core/src/policy.rs").read_text()
assert "ThermalSignal" in policy
assert "headroom_permille" in policy
assert "hottest_mdeg" not in policy
assert "mdeg" not in policy.lower()

thermal = Path("rust/zperfd/src/thermal.rs").read_text()
prod = thermal.split("#[cfg(test)]", 1)[0]
assert "headroom_permille" in prod
assert "telemetry_complete" in prod
assert "critical_reached" in prod
assert "kernel_guard_limit" in prod
assert not re.search(r"(?<![A-Za-z0-9_])(?:[3-9][0-9]_000|[3-9][0-9]000)(?![A-Za-z0-9_])", prod)

registry = tomllib.loads(Path("database/registry/thermal.toml").read_text())
authority = tomllib.loads(Path("database/registry/authority.toml").read_text())
assert registry["authority"]["source"] == "runtime-thermal-trip-topology"
assert registry["authority"]["thresholds"] == "runtime-derived"
assert registry["authority"]["static_database_role"] == "evidence-and-provider-hints-only"
assert registry["authority"]["missing_telemetry"] == "limited-performance-envelope"
assert "read.properties" in authority["principals"]["runtime"]["capabilities"]
assert "read.properties" not in authority["principals"]["android"]["capabilities"]
print("control-plane source invariants: PASS")
PY

# Kernel guard is allowed to validate only structural integer ranges; it may not
# carry the old device-agnostic 35/95 C or fixed hysteresis policy.
! grep -q '95000\|35000\|2000.*hysteresis\|2.*Celsius' kernel/thermal/zkfc_thermal.c

grep -q 'ZKFC_THERMAL_GUARD_MIN_MDEG' kernel/include/uapi/linux/zkfc.h
grep -q 'ZKFC_THERMAL_GUARD_MAX_MDEG' kernel/include/uapi/linux/zkfc.h
grep -q 'runtime-derived' kernel/thermal/zkfc_thermal.c

grep -q 'scaling_available_governors' rust/zperfd/src/engine.rs
grep -q 'available_governors' rust/zperfd/src/engine.rs
grep -q 'effective_congestion_allowlist' rust/zperfd/src/tweak.rs
grep -q 'tcp_allowed_congestion_control' rust/zperfd/src/inventory.rs
grep -q 'property is outside the Zairenkai property allowlist' rust/zperfd/src/properties.rs
grep -q 'require_root_peer' rust/zperfd/src/api.rs
grep -q 'kind_evidence' rust/zperfd/src/inventory.rs
grep -q 'health_evidence' rust/zperfd/src/inventory.rs

test -x userspace/build.sh
test -x kernel/gki/build_lkm.sh
test -x kernel/nongki/build.sh
test -x kernel/setup.sh
test -x module/service.sh

diff -qr database module/zperf/database
printf '%s\n' 'control-plane checks passed'
