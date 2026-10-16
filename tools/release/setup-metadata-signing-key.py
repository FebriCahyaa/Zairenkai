#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Provision the dedicated Ed25519 signer for official update metadata.

Run locally from a trusted workstation. The encrypted private key is saved with
0600 permissions, and only its encrypted PEM plus its passphrase are sent to a
protected GitHub Actions environment as secrets. The public key is pinned in app
assets and must be committed by the repository owner.
"""
from __future__ import annotations
import argparse
import base64
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
except ImportError as exc:
    raise SystemExit("Install dependency first: python3 -m pip install cryptography") from exc

ROOT = Path(__file__).resolve().parents[2]
PUBLIC_FILE = ROOT / "app/src/main/assets/security/update-metadata-public-key.pem"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub repository, e.g. FebriCahyaa/Zairenkai")
    parser.add_argument("--environment", default="production-signing", help="protected Actions environment")
    parser.add_argument("--rotate", action="store_true", help="reserved; in-place rotation is intentionally not supported by this single-key pin")
    parser.add_argument("--no-upload", action="store_true", help="write the key locally only; manually configure Actions secrets")
    args = parser.parse_args()

    if args.rotate:
        print("error: in-place metadata-key rotation is not supported by the single-key pin. Implement and ship a dual-key migration before rotating; no files or secrets were changed.", file=sys.stderr)
        return 2
    current = PUBLIC_FILE.read_text(encoding="ascii").strip() if PUBLIC_FILE.exists() else ""
    if current.startswith("-----BEGIN PUBLIC KEY-----"):
        print("error: a metadata public key is already provisioned; do not replace it without a staged dual-key migration", file=sys.stderr)
        return 2
    if not reponame_ok(args.repo):
        parser.error("--repo must be in owner/repository form")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", args.environment):
        parser.error("--environment contains unsupported characters")
    if not args.no_upload:
        if not shutil.which("gh"):
            print("error: GitHub CLI (gh) is required; use --no-upload to provision manually", file=sys.stderr)
            return 2
        try:
            subprocess.run(["gh", "auth", "status"], check=True, stdout=subprocess.DEVNULL)
            env_raw = subprocess.run(
                ["gh", "api", f"repos/{args.repo}/environments/{args.environment}"],
                check=True, capture_output=True, text=True,
            ).stdout
            env_info = json.loads(env_raw)
            rules = env_info.get("protection_rules") or []
            has_reviewers = any(
                rule.get("type") == "required_reviewers" and rule.get("reviewers")
                for rule in rules
            )
            policy = env_info.get("deployment_branch_policy") or {}
            if not has_reviewers or not policy.get("custom_branch_policies"):
                print(
                    f"error: environment '{args.environment}' must already exist, require at least one reviewer, "
                    "and use custom deployment branch/tag restrictions (default branch + v* tags). "
                    "Configure it in GitHub Settings → Environments before generating signing keys.",
                    file=sys.stderr,
                )
                return 2
            repo_info = json.loads(subprocess.run(
                ["gh", "api", f"repos/{args.repo}"],
                check=True, capture_output=True, text=True,
            ).stdout)
            default_branch = repo_info.get("default_branch")
            branch_policies = subprocess.run(
                ["gh", "api", f"repos/{args.repo}/environments/{args.environment}/deployment-branch-policies"],
                check=True, capture_output=True, text=True,
            ).stdout
            policies = json.loads(branch_policies).get("branch_policies") or []
            policy_names = {(item.get("type"), item.get("name")) for item in policies}
            if ("branch", default_branch) not in policy_names or ("tag", "v*") not in policy_names:
                print(
                    f"error: environment '{args.environment}' must explicitly allow branch '{default_branch}' "
                    "and tag pattern 'v*'; no signing key was generated.",
                    file=sys.stderr,
                )
                return 2
            print(f"Verified that environment '{args.environment}' requires reviewer approval and allows only the configured release refs.")
        except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
            print(
                f"error: could not verify the protected Actions environment before key generation ({exc}). "
                "Configure it first, or use --no-upload and set secrets manually.",
                file=sys.stderr,
            )
            return 2

    password = getpass.getpass("Metadata signing key passphrase (minimum 16 characters): ")
    if len(password) < 16:
        print("error: passphrase must be at least 16 characters", file=sys.stderr)
        return 2
    if password != getpass.getpass("Repeat passphrase: "):
        print("error: passphrases do not match", file=sys.stderr)
        return 2

    key = Ed25519PrivateKey.generate()
    private_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.BestAvailableEncryption(password.encode("utf-8")),
    )
    public_pem = key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    backup = Path.home() / ".config/zairenkai/release-metadata-ed25519.pem"
    backup.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600) if not backup.exists() else None
    if fd is None:
        print(f"error: refusing to overwrite existing private-key backup: {backup}", file=sys.stderr)
        return 2
    with os.fdopen(fd, "wb") as stream:
        stream.write(private_pem)
    PUBLIC_FILE.parent.mkdir(parents=True, exist_ok=True)
    PUBLIC_FILE.write_bytes(public_pem)
    os.chmod(PUBLIC_FILE, 0o644)

    print(f"Pinned public key written to: {PUBLIC_FILE.relative_to(ROOT)}")
    print(f"Encrypted private-key backup written to: {backup}")
    print("Keep the backup and passphrase separately; never commit the private-key backup.")
    if not args.no_upload:
        secrets = {
            "ZKFC_METADATA_SIGNING_KEY_B64": base64.b64encode(private_pem),
            "ZKFC_METADATA_SIGNING_PASSPHRASE": password.encode("utf-8"),
        }
        for name, value in secrets.items():
            subprocess.run(
                ["gh", "secret", "set", name, "--env", args.environment, "--repo", args.repo],
                input=value, check=True,
            )
            print(f"Configured Actions environment secret: {name}")
        print(f"Environment used: {args.environment}. Ensure required reviewers and allowed branches/tags are configured in GitHub settings.")
    else:
        print("Manually configure the two required environment secrets after creating the protected environment.")
        print("ZKFC_METADATA_SIGNING_KEY_B64 = base64 of the encrypted PEM file")
        print("ZKFC_METADATA_SIGNING_PASSPHRASE = the passphrase entered above")
    print("Next: review and commit only the public key asset; the private key never belongs in Git.")
    return 0


def reponame_ok(value: str) -> bool:
    parts = value.split("/")
    return len(parts) == 2 and all(part and all(c.isalnum() or c in "-_." for c in part) for part in parts)

if __name__ == "__main__":
    raise SystemExit(main())
