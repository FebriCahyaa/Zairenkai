#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Unit tests for the production License Server preflight (no live network)."""
from __future__ import annotations
import importlib.util
import io
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("smoke-license-server.py")
SPEC = importlib.util.spec_from_file_location("license_server_smoke", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class LicenseServerSmokeTests(unittest.TestCase):
    def test_accepts_https_origin_and_trailing_slash(self):
        self.assertEqual(module.validate_origin("https://license.example/"), "https://license.example")
        self.assertEqual(module.validate_origin("https://license.example:443"), "https://license.example:443")

    def test_rejects_non_origin_urls(self):
        for url in (
            "http://license.example",
            "https://license.example/v1",
            "https://license.example?debug=1",
            "https://license.example:8443",
            "https://user:pass@license.example",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                module.validate_origin(url)

    def test_passes_only_when_health_and_d1_are_ready(self):
        responses = {
            "https://license.example/v1/health": {
                "ok": True, "service": "zairenkai-license-server", "apiMajor": 1,
            },
            "https://license.example/v1/ready": {
                "ok": True, "service": "zairenkai-license-server", "database": "connected",
            },
        }
        with patch.object(module, "fetch_json", side_effect=lambda url: responses[url]), \
             patch("sys.argv", [str(SCRIPT), "https://license.example"]), \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(module.main(), 0)
        self.assertIn("PASS:", output.getvalue())

    def test_fails_closed_for_wrong_api_contract_or_d1(self):
        for health, ready in (
            ({"ok": True, "service": "other", "apiMajor": 1}, {"ok": True, "service": "zairenkai-license-server", "database": "connected"}),
            ({"ok": True, "service": "zairenkai-license-server", "apiMajor": 2}, {"ok": True, "service": "zairenkai-license-server", "database": "connected"}),
            ({"ok": True, "service": "zairenkai-license-server", "apiMajor": 1}, {"ok": True, "service": "zairenkai-license-server", "database": "unavailable"}),
        ):
            with self.subTest(health=health, ready=ready), \
                 patch.object(module, "fetch_json", side_effect=[health, ready]), \
                 patch("sys.argv", [str(SCRIPT), "https://license.example"]), \
                 redirect_stderr(io.StringIO()):
                self.assertEqual(module.main(), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
