#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT/tools/atlas_validate.py"
python3 "$ROOT/tools/profile_validate.py" "$ROOT/database"

grep -q 'runtime_discovery_is_authority = true' "$ROOT/database/registry/coverage.toml"
grep -q 'unknown_device_mutation = "conservative"' "$ROOT/database/registry/coverage.toml"
grep -q "mutation_policy: 'not-exposed-in-v1'" "$ROOT/api/zairenkai-local-v1.yaml"
printf '%s\n' 'PASS atlas invariants'
