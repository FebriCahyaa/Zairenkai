#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Unit tests for the post-publication updater proxy smoke check."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("smoke-update-server.py")
SPEC = importlib.util.spec_from_file_location("zairenkai_smoke_update_server", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class UpdateServerSmokeTests(unittest.TestCase):
    def setUp(self):
        self.apk_bytes = b"test APK payload"
        self.digest = hashlib.sha256(self.apk_bytes).hexdigest()
        self.manifest = {
            "schemaVersion": 1,
            "versionCode": 110000123,
            "versionName": "1.1.0-canary.123",
            "apkUrl": "https://github.com/FebriCahyaa/Zairenkai/releases/download/canary/Zairenkai-canary.apk",
            "sha256": self.digest,
            "apiMajor": 1,
            "buildId": "abcdef012345-12345-1",
            "commit": "abcdef0123456789",
        }

    def payload(self, **changes):
        value = self.manifest | changes
        return json.dumps(value, sort_keys=True).encode("utf-8")

    def test_accepts_exact_channel_version_apk_hash_and_api_major(self):
        value = module.validate_manifest(
            self.payload(), channel="canary", expected_version="1.1.0-canary.123",
            tag="canary", expected_sha256=self.digest,
        )
        self.assertEqual(value["sha256"], self.digest)

    def test_rejects_stale_hash_wrong_asset_or_version(self):
        mutations = [
            {"sha256": "0" * 64},
            {"apkUrl": "https://github.com/FebriCahyaa/Zairenkai/releases/download/canary/Zairenkai-canary-spoof.apk"},
            {"versionName": "1.0.0"},
            {"apiMajor": 99},
            {"schemaVersion": True},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                module.validate_manifest(
                    self.payload(**mutation), channel="canary", expected_version="1.1.0-canary.123",
                    tag="canary", expected_sha256=self.digest,
                )

    def test_release_manifest_requires_exact_stable_tagged_asset(self):
        stable = self.manifest | {
            "versionCode": 110000000,
            "versionName": "1.1.0",
            "apkUrl": "https://github.com/FebriCahyaa/Zairenkai/releases/download/v1.1.0/Zairenkai-release.apk",
        }
        module.validate_manifest(
            json.dumps(stable).encode(), channel="release", expected_version="1.1.0",
            tag="v1.1.0", expected_sha256=self.digest,
        )
        stable["apkUrl"] = "https://github.com/FebriCahyaa/Zairenkai/releases/latest/download/Zairenkai-release.apk"
        with self.assertRaises(ValueError):
            module.validate_manifest(
                json.dumps(stable).encode(), channel="release", expected_version="1.1.0",
                tag="v1.1.0", expected_sha256=self.digest,
            )

    def test_module_metadata_requires_matching_version_and_official_urls(self):
        module_manifest = {
            "version": "v1.1.0-canary.123",
            "versionCode": 110000123,
            "zipUrl": "https://github.com/FebriCahyaa/Zairenkai/releases/download/canary/Zairenkai-module.zip",
            "changelog": "https://github.com/FebriCahyaa/Zairenkai/releases/tag/canary",
        }
        module.validate_module_manifest(
            json.dumps(module_manifest).encode(), channel="canary",
            expected_version="1.1.0-canary.123", tag="canary",
        )
        for key, value in (
            ("version", "v1.0.0"),
            ("zipUrl", "https://example.invalid/Zairenkai-module.zip"),
            ("changelog", "https://example.invalid/changelog"),
        ):
            bad = module_manifest | {key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.validate_module_manifest(
                    json.dumps(bad).encode(), channel="canary",
                    expected_version="1.1.0-canary.123", tag="canary",
                )

    def test_check_probes_module_endpoint_without_an_apk(self):
        module_manifest = {
            "version": "v1.1.0-canary.123",
            "versionCode": 110000123,
            "zipUrl": "https://github.com/FebriCahyaa/Zairenkai/releases/download/canary/Zairenkai-module.zip",
            "changelog": "https://github.com/FebriCahyaa/Zairenkai/releases/tag/canary",
        }
        with patch.object(module, "fetch_bytes", return_value=("application/json", json.dumps(module_manifest).encode())) as fetch:
            module.check(
                "https://license.example", "canary", None, "1.1.0-canary.123",
                "canary", 1, 0, component="module",
            )
        fetch.assert_called_once_with("https://license.example/v1/updates/module/canary", 1024 * 1024)

    def test_origin_must_be_https_origin(self):
        self.assertEqual(module.validate_origin("https://license.example/"), "https://license.example")
        for invalid in ("http://license.example", "https://license.example/path", "https://license.example:8443"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                module.validate_origin(invalid)

    def test_retries_stale_cached_manifest_then_checks_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "Zairenkai-canary.apk"
            apk.write_bytes(self.apk_bytes)
            stale = self.payload(sha256="0" * 64)
            current = self.payload()
            manifest_calls = 0

            def fetch(url, _limit):
                nonlocal manifest_calls
                if url.endswith(".sig"):
                    return "text/plain", b"signature-envelope"
                manifest_calls += 1
                return "application/json", stale if manifest_calls == 1 else current

            with patch.object(module, "fetch_bytes", side_effect=fetch), \
                 patch.object(module, "verify_signature") as verify, \
                 patch.object(module.time, "sleep"):
                module.check(
                    "https://license.example", "canary", apk, "1.1.0-canary.123", "canary", 2, 0,
                )
            verify.assert_called_once_with(current, b"signature-envelope", "canary")
            self.assertEqual(manifest_calls, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
