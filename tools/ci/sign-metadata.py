#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Sign one release metadata file with the dedicated Ed25519 metadata key."""
from __future__ import annotations

import argparse
import base64
import hashlib
import os
import re
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
except ImportError as exc:  # pragma: no cover - checked in CI bootstrap
    raise SystemExit("cryptography is required: python3 -m pip install cryptography") from exc

MAGIC = "ZKFC-METADATA-SIGNATURE-V1"
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PUBLIC_KEY = ROOT / "app/src/main/assets/security/update-metadata-public-key.pem"


def public_der(key: Ed25519PublicKey) -> bytes:
    return key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def signature_envelope(payload: bytes, filename: str, private_key: Ed25519PrivateKey) -> bytes:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", filename) or ".." in filename:
        raise ValueError("unsafe metadata filename")
    public_bytes = public_der(private_key.public_key())
    key_id = hashlib.sha256(public_bytes).hexdigest()
    signature = base64.b64encode(private_key.sign(payload)).decode("ascii")
    lines = [MAGIC, f"file={filename}", f"key_id={key_id}", f"sha256={hashlib.sha256(payload).hexdigest()}", f"signature={signature}"]
    return ("\n".join(lines) + "\n").encode("ascii")


def sign(input_path: Path, output_path: Path, key_data: bytes, password: bytes | None, public_key_path: Path) -> None:
    private = serialization.load_pem_private_key(key_data, password=password)
    if not isinstance(private, Ed25519PrivateKey):
        raise ValueError("metadata signing key must be Ed25519")
    configured_data = public_key_path.read_bytes()
    configured = serialization.load_pem_public_key(configured_data)
    if not isinstance(configured, Ed25519PublicKey):
        raise ValueError("pinned metadata public key must be Ed25519")
    if public_der(private.public_key()) != public_der(configured):
        raise ValueError("metadata private key does not match the public key pinned in app assets")
    payload = input_path.read_bytes()
    envelope = signature_envelope(payload, input_path.name, private)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(envelope)
    print(f"Signed {input_path.name} with pinned Ed25519 metadata key (key id {hashlib.sha256(public_der(configured)).hexdigest()[:16]}…).")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC_KEY)
    args = parser.parse_args()
    key_b64 = os.environ.get("ZKFC_METADATA_SIGNING_KEY_B64", "")
    passphrase = os.environ.get("ZKFC_METADATA_SIGNING_PASSPHRASE", "")
    if not key_b64 or not passphrase:
        print("error: metadata signing secrets are missing; refusing to publish unsigned release metadata", file=sys.stderr)
        return 2
    try:
        key_data = base64.b64decode(key_b64, validate=True)
        if not key_data.startswith(b"-----BEGIN ENCRYPTED PRIVATE KEY-----"):
            raise ValueError("expected a base64-encoded encrypted PKCS#8 PEM private key")
        sign(args.input, args.output or Path(str(args.input) + ".sig"), key_data, passphrase.encode(), args.public_key)
    except Exception as exc:
        print(f"error: metadata signing failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
