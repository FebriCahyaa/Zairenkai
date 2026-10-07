#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
from pathlib import Path
root=Path('.')
identity=root/'app/src/main/java/com/zairenkai/app/core/identity/ZairenkaiIdentity.kt'
cap=root/'app/src/main/java/com/zairenkai/app/core/capability/CapabilityModels.kt'
runtime=root/'app/src/main/java/com/zairenkai/app/core/runtime/RuntimeSnapshotRepository.kt'
authority=root/'app/src/main/java/com/zairenkai/app/core/authority/AuthorityModels.kt'
for p in (identity, cap, runtime, authority):
    assert p.is_file(), p
assert 'const val APP_ID = "com.zairenkai.app"' in identity.read_text()
assert 'zairenkai.core' in identity.read_text()
assert 'UNOBSERVED' in cap.read_text()
assert 'clients.zperf.thermal()' in runtime.read_text()
assert 'package com.zairenkai.app.core.authority' in authority.read_text()
# Legacy authority source must not remain in the data adapter layer.
assert not (root/'app/src/main/java/com/zairenkai/app/data/CoreAuthority.kt').exists()
print('PASS Android architecture identity/layering')
PY
