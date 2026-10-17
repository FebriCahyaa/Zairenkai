#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Sync an existing pinned metadata signing key into another Actions environment.

This does not generate or rotate a key. It validates the local encrypted private
key against the public key already pinned in the APK, then uploads only that
same key/passphrase to the requested GitHub environment.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
except ImportError as exc:
    raise SystemExit("Install dependency first: python3 -m pip install cryptography") from exc

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_KEY = Path.home() / ".config/zairenkai/release-metadata-ed25519.pem"
DEFAULT_PUBLIC = ROOT / "app/src/main/assets/security/update-metadata-public-key.pem"


def run_gh(args: list[str], *, input_data: bytes | None = None) -> str:
    result = subprocess.run(args, input=input_data, capture_output=True, check=False)
    if result.returncode:
        details = result.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(f"GitHub CLI command failed ({args[1:3]}): {details}")
    return result.stdout.decode("utf-8", "replace")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub repository, e.g. FebriCahyaa/Zairenkai")
    parser.add_argument("--environment", default="canary-signing", help="target Actions environment")
    parser.add_argument("--key-file", type=Path, default=DEFAULT_KEY, help="existing encrypted Ed25519 private key backup")
    parser.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC, help="public key pinned in app assets")
    args = parser.parse_args()

    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}", args.repo):
        parser.error("--repo must be owner/repository form")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", args.environment):
        parser.error("--environment contains unsupported characters")
    if not shutil.which("gh"):
        print("error: GitHub CLI (gh) is required", file=sys.stderr)
        return 2
    if not args.key_file.is_file() or not args.public_key.is_file():
        print("error: existing metadata key backup or pinned public key is missing; no secrets were changed", file=sys.stderr)
        return 2

    try:
        run_gh(["gh", "auth", "status"])
        run_gh(["gh", "api", f"repos/{args.repo}/environments/{args.environment}"])
        private_pem = args.key_file.read_bytes()
        passphrase = getpass.getpass("Existing metadata-key passphrase (not shown): ").encode("utf-8")
        private_key = serialization.load_pem_private_key(private_pem, password=passphrase)
        pinned = serialization.load_pem_public_key(args.public_key.read_bytes())
        if not isinstance(private_key, Ed25519PrivateKey) or not isinstance(pinned, Ed25519PublicKey):
            raise ValueError("both keys must be Ed25519")
        private_public_der = private_key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        pinned_public_der = pinned.public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        if private_public_der != pinned_public_der:
            raise ValueError("private key does not match the public key pinned in the APK; refusing to upload")

        secrets = {
            "ZKFC_METADATA_SIGNING_KEY_B64": base64.b64encode(private_pem),
            "ZKFC_METADATA_SIGNING_PASSPHRASE": passphrase,
        }
        for name, value in secrets.items():
            run_gh(["gh", "secret", "set", name, "--env", args.environment, "--repo", args.repo], input_data=value)
            print(f"Configured Actions environment secret: {name} ({args.environment})")
        print("PASS: the same already-pinned metadata key is now available to this environment. No key was generated or rotated.")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
