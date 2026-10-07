#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
import sys
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[1]

def load(path):
    with path.open('rb') as fh:
        return tomllib.load(fh)

def main():
    manifest = load(ROOT / 'core/manifest.toml')
    assert manifest['id'] == 'zairenkai.core'
    assert manifest['core_api'] == 1
    assert set(manifest['supported_kernel_models']) >= {'gki', 'non-gki'}
    assert 'arm64' in manifest['supported_architectures']
    assert '6.18' in manifest['supported_kernel_generations']
    authority = load(ROOT / 'database/schema/authority-v1.toml')
    assert authority['principles']['default'] == 'deny'
    assert authority['principles']['unknown_capability'] == 'deny'
    permissions = authority['permissions']
    assert len(permissions) == len(set(permissions))
    required_permissions = {
        'read.storage', 'read.network', 'read.memory', 'read.security',
        'tune.storage', 'tune.network', 'tune.zram',
        'manage.device_registry', 'manage.data_sources', 'manage.evidence', 'manage.recovery',
    }
    assert required_permissions <= set(permissions)
    for vendor in ('qualcomm', 'mediatek', 'exynos', 'tensor'):
        family = load(ROOT / f'database/soc/{vendor}/family.toml')
        assert family['vendor'] == vendor
        assert family['schema_version'] == 1
    device_files = sorted((ROOT / 'database/devices').glob('*/*.toml'))
    assert len(device_files) >= 14
    for path in device_files:
        profile = load(path)
        assert profile['schema_version'] in (1, 3)
        assert profile['runtime_discovery_required'] is True
        assert profile.get('measurement', {}).get('interpretation') == 'measured-only'
    print(f'PASS core registry: {len(device_files)} device profiles')

if __name__ == '__main__':
    try:
        main()
    except (AssertionError, KeyError, tomllib.TOMLDecodeError) as exc:
        print(f'FAIL: {exc}', file=sys.stderr)
        sys.exit(1)
