#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Crypto regression tests for release metadata signatures."""
from __future__ import annotations
import base64
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SCRIPT = Path(__file__).with_name("sign-metadata.py")
SPEC = importlib.util.spec_from_file_location("sign_metadata", SCRIPT)
assert SPEC and SPEC.loader
signer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signer)

class MetadataSignatureTest(unittest.TestCase):
    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.public = self.key.public_key()
        self.der = self.public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.payload = b'{"versionCode":42,"apiMajor":1}\n'
        self.envelope = signer.signature_envelope(self.payload, "version-release.json", self.key)
        self.fields = dict(line.split("=", 1) for line in self.envelope.decode().splitlines()[1:])

    def test_signs_exact_payload(self):
        self.public.verify(base64.b64decode(self.fields["signature"]), self.payload)
        self.assertEqual(self.fields["sha256"], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(self.fields["key_id"], hashlib.sha256(self.der).hexdigest())

    def test_modified_payload_is_detected_by_digest_and_signature(self):
        changed = self.payload.replace(b"42", b"43")
        self.assertNotEqual(self.fields["sha256"], hashlib.sha256(changed).hexdigest())
        with self.assertRaises(Exception):
            self.public.verify(base64.b64decode(self.fields["signature"]), changed)

    def test_filename_is_bound(self):
        with self.assertRaises(ValueError):
            signer.signature_envelope(self.payload, "../version-release.json", self.key)

    def test_sign_writes_signature_only_when_pinned_key_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            private_path = root / "private.pem"
            pub_path = root / "public.pem"
            payload_path = root / "version-release.json"
            out_path = root / "version-release.json.sig"
            private_pem = self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            private_path.write_bytes(private_pem)
            pub_path.write_bytes(self.public.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
            payload_path.write_bytes(self.payload)
            signer.sign(payload_path, out_path, private_pem, None, pub_path)
            self.assertTrue(out_path.is_file())
            self.assertEqual(signer.signature_envelope(self.payload, payload_path.name, self.key), out_path.read_bytes())

    def test_public_key_pinning_mismatch_fails(self):
        other = Ed25519PrivateKey.generate()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            private_path = root / "private.pem"
            pub_path = root / "public.pem"
            payload_path = root / "version-release.json"
            out_path = root / "version-release.json.sig"
            private_path.write_bytes(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            pub_path.write_bytes(other.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
            payload_path.write_bytes(self.payload)
            with self.assertRaisesRegex(ValueError, "does not match"):
                signer.sign(payload_path, out_path, private_path.read_bytes(), None, pub_path)

if __name__ == "__main__":
    unittest.main(verbosity=2)
