#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Tests for the public Ed25519 metadata signature verifier."""
from __future__ import annotations
import importlib.util
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SCRIPT = Path(__file__).with_name("verify-metadata.py")
SPEC = importlib.util.spec_from_file_location("verify_metadata", SCRIPT)
assert SPEC and SPEC.loader
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

class VerifyMetadataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.payload_path = self.root / "release.json"
        self.signature_path = self.root / "release.json.sig"
        self.public_path = self.root / "public.pem"
        self.key = Ed25519PrivateKey.generate()
        self.payload = b'{"schemaVersion":1,"apiMajor":1}\n'
        self.payload_path.write_bytes(self.payload)
        public = self.key.public_key()
        public_der = public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        import hashlib, base64
        envelope = "\n".join([
            verifier.MAGIC,
            "file=release.json",
            "key_id=" + hashlib.sha256(public_der).hexdigest(),
            "sha256=" + hashlib.sha256(self.payload).hexdigest(),
            "signature=" + base64.b64encode(self.key.sign(self.payload)).decode("ascii"),
        ]) + "\n"
        self.signature_path.write_text(envelope, encoding="ascii")
        self.public_path.write_bytes(public.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))

    def tearDown(self):
        self.tmp.cleanup()

    def test_accepts_valid_signature(self):
        verifier.verify(self.payload_path, self.signature_path, self.public_path)

    def test_rejects_modified_payload(self):
        self.payload_path.write_bytes(self.payload + b"tamper")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verifier.verify(self.payload_path, self.signature_path, self.public_path)

    def test_rejects_wrong_filename(self):
        other = self.root / "different.json"
        other.write_bytes(self.payload)
        with self.assertRaisesRegex(ValueError, "filename"):
            verifier.verify(other, self.signature_path, self.public_path)

    def test_rejects_wrong_pinned_key(self):
        key = Ed25519PrivateKey.generate().public_key()
        self.public_path.write_bytes(key.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        with self.assertRaisesRegex(ValueError, "key ID"):
            verifier.verify(self.payload_path, self.signature_path, self.public_path)

if __name__ == "__main__":
    unittest.main(verbosity=2)
