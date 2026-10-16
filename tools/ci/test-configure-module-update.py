#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Exercise the root-module update metadata stamper in an isolated fixture."""
from __future__ import annotations
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/ci/configure-module-update.py"
REPO = "https://github.com/FebriCahyaa/Zairenkai"
SERVER = "https://licenses.example.workers.dev"


class ConfigureModuleUpdateTest(unittest.TestCase):
    def fixture(self, temp: Path) -> Path:
        module = temp / "module"
        module.mkdir()
        shutil.copy2(ROOT / "module/module.prop", module / "module.prop")
        return module

    def command(self, module: Path, output: Path, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([
            sys.executable, str(SCRIPT), "--module-dir", str(module),
            "--channel", "canary", "--version-name", "1.2.3-canary.7",
            "--version-code", "123456789", "--server-url", SERVER,
            "--zip-url", f"{REPO}/releases/download/canary/Zairenkai-module.zip",
            "--changelog-url", f"{REPO}/releases/tag/canary",
            "--metadata-output", str(output), *extra,
        ], text=True, capture_output=True)

    def test_stamps_channel_endpoint_and_matching_version_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            temp = Path(raw)
            module = self.fixture(temp)
            output = temp / "module-update-canary.json"
            result = self.command(module, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            props = dict(line.split("=", 1) for line in (module / "module.prop").read_text().splitlines() if "=" in line)
            metadata = json.loads(output.read_text())
            self.assertEqual(props["version"], metadata["version"])
            self.assertEqual(int(props["versionCode"]), metadata["versionCode"])
            self.assertEqual(props["versionCode"], "123456789")
            self.assertEqual(props["updateJson"], SERVER + "/v1/updates/module/canary")
            self.assertEqual(metadata["zipUrl"], f"{REPO}/releases/download/canary/Zairenkai-module.zip")

    def test_refuses_non_official_download_and_bad_server_origin(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            temp = Path(raw)
            module = self.fixture(temp)
            output = temp / "manifest.json"
            result = self.command(module, output, "--zip-url", "https://evil.example/Zairenkai-module.zip")
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            result = self.command(module, output, "--server-url", "http://licenses.example.workers.dev")
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
