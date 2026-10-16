#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Tests that release artifact hashes are covered by signed metadata."""
from __future__ import annotations
import base64
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).parent
spec = importlib.util.spec_from_file_location("verify_release_manifest", ROOT / "verify-release-manifest.py")
assert spec and spec.loader
release_verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_verifier)

class VerifyReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.key = Ed25519PrivateKey.generate()
        self.pub = self.root / "public.pem"
        self.pub.write_bytes(self.key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        self.artifact = self.root / "Zairenkai-module.zip"
        self.artifact.write_bytes(b"module payload")
        data = {
            "schema_version": 1,
            "product": "Zairenkai",
            "version": "v1.2.3",
            "hash_algorithm": "SHA-256",
            "artifacts": [{
                "path": self.artifact.name,
                "size_bytes": self.artifact.stat().st_size,
                "sha256": hashlib.sha256(self.artifact.read_bytes()).hexdigest(),
            }],
        }
        self.manifest = self.root / "Zairenkai-release-manifest.json"
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode()
        self.manifest.write_bytes(payload)
        der = self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        sig = base64.b64encode(self.key.sign(payload)).decode("ascii")
        envelope = "\n".join([
            "ZKFC-METADATA-SIGNATURE-V1",
            f"file={self.manifest.name}",
            f"key_id={hashlib.sha256(der).hexdigest()}",
            f"sha256={hashlib.sha256(payload).hexdigest()}",
            f"signature={sig}",
        ]) + "\n"
        self.signature = self.root / (self.manifest.name + ".sig")
        self.signature.write_text(envelope, encoding="ascii")

    def tearDown(self): self.tmp.cleanup()

    def test_accepts_signed_manifest_and_matching_artifacts(self):
        self.assertEqual(1, release_verifier.verify_manifest(self.manifest, self.signature, self.pub))

    def test_rejects_changed_artifact_even_when_manifest_signature_is_valid(self):
        self.artifact.write_bytes(b"modified payload")
        with self.assertRaisesRegex(ValueError, "size does not match|SHA-256 does not match"):
            release_verifier.verify_manifest(self.manifest, self.signature, self.pub)

    def test_rejects_path_traversal(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["artifacts"][0]["path"] = "../outside.zip"
        payload = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
        self.manifest.write_bytes(payload)
        der = self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        envelope = "\n".join([
            "ZKFC-METADATA-SIGNATURE-V1",
            f"file={self.manifest.name}",
            f"key_id={hashlib.sha256(der).hexdigest()}",
            f"sha256={hashlib.sha256(payload).hexdigest()}",
            f"signature={base64.b64encode(self.key.sign(payload)).decode('ascii')}",
        ]) + "\n"
        self.signature.write_text(envelope, encoding="ascii")
        with self.assertRaisesRegex(ValueError, "escapes"):
            release_verifier.verify_manifest(self.manifest, self.signature, self.pub)

if __name__ == "__main__": unittest.main(verbosity=2)
