#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Verify a detached ZKFC Ed25519 metadata signature against the pinned public key."""
from __future__ import annotations
import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
except ImportError as exc:
    raise SystemExit("cryptography is required: python3 -m pip install cryptography") from exc

MAGIC = "ZKFC-METADATA-SIGNATURE-V1"
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PUBLIC_KEY = ROOT / "app/src/main/assets/security/update-metadata-public-key.pem"


def verify(input_path: Path, signature_path: Path, public_key_path: Path) -> None:
    payload = input_path.read_bytes()
    envelope = signature_path.read_bytes().decode("ascii")
    lines = envelope.splitlines()
    if len(lines) != 5 or lines[0] != MAGIC:
        raise ValueError("invalid metadata signature envelope")
    fields: dict[str, str] = {}
    for line in lines[1:]:
        key, sep, value = line.partition("=")
        if not sep or not key or key in fields:
            raise ValueError("malformed or duplicate signature-envelope field")
        fields[key] = value
    if set(fields) != {"file", "key_id", "sha256", "signature"}:
        raise ValueError("signature envelope has unexpected fields")
    if fields["file"] != input_path.name:
        raise ValueError("signature is bound to a different filename")
    if not re.fullmatch(r"[0-9a-f]{64}", fields["key_id"]):
        raise ValueError("invalid public-key ID")
    if not re.fullmatch(r"[0-9a-f]{64}", fields["sha256"]):
        raise ValueError("invalid payload SHA-256")

    public = serialization.load_pem_public_key(public_key_path.read_bytes())
    if not isinstance(public, Ed25519PublicKey):
        raise ValueError("pinned metadata key must be Ed25519")
    public_der = public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if hashlib.sha256(public_der).hexdigest() != fields["key_id"]:
        raise ValueError("signature key ID does not match the pinned public key")
    if hashlib.sha256(payload).hexdigest() != fields["sha256"]:
        raise ValueError("metadata payload SHA-256 does not match the signature envelope")
    try:
        signature = base64.b64decode(fields["signature"], validate=True)
        public.verify(signature, payload)
    except (ValueError, InvalidSignature) as exc:
        raise ValueError("metadata Ed25519 signature is invalid") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="exact signed metadata file")
    parser.add_argument("--signature", type=Path, help="detached signature file (default: <input>.sig)")
    parser.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC_KEY)
    args = parser.parse_args()
    signature_path = args.signature or Path(str(args.input) + ".sig")
    try:
        verify(args.input, signature_path, args.public_key)
    except Exception as exc:
        print(f"verification failed: {exc}", file=sys.stderr)
        return 2
    print(f"Verified {args.input.name}: pinned Ed25519 signature and exact payload SHA-256 are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
