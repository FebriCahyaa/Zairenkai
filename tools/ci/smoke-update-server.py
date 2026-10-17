#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Verify the live updater proxy serves the exact newly published signed APK manifest."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "FebriCahyaa/Zairenkai"
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$")
HEX_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def validate_origin(origin: str) -> str:
    parsed = urlsplit(origin)
    if (
        parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
        or parsed.query or parsed.fragment or parsed.path not in ("", "/")
        or parsed.port not in (None, 443)
        or not re.fullmatch(r"[A-Za-z0-9.-]+", parsed.hostname)
    ):
        raise ValueError("server URL must be an HTTPS origin without a path")
    return f"https://{parsed.netloc}"


def fetch_bytes(url: str, limit: int) -> tuple[str, bytes]:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json, text/plain", "User-Agent": "Zairenkai-Updater-Smoke/1.0"},
    )
    with urllib.request.urlopen(request, timeout=12) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status} for {urlsplit(url).path}")
        content_type = response.headers.get_content_type()
        data = response.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError(f"payload too large at {urlsplit(url).path}")
    return content_type, data


def validate_manifest(
    payload: bytes, *, channel: str, expected_version: str, tag: str, expected_sha256: str,
) -> dict:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("updater proxy returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("updater manifest must be a JSON object")
    version_code = value.get("versionCode")
    api_major = value.get("apiMajor")
    try:
        api_source = (ROOT / "tools/zkfc-license/zkfc_license.py").read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError("cannot read the local ZKFC API contract") from exc
    api_match = re.search(r"^API_MAJOR\s*=\s*(\d+)\s*$", api_source, re.M)
    if not api_match:
        raise ValueError("API_MAJOR not found in local ZKFC contract")
    expected_api_major = int(api_match.group(1))
    expected_asset = "Zairenkai-canary.apk" if channel == "canary" else "Zairenkai-release.apk"
    expected_tag = "canary" if channel == "canary" else tag
    expected_url = f"https://github.com/{REPOSITORY}/releases/download/{expected_tag}/{expected_asset}"
    digest = value.get("sha256")
    if (
        isinstance(value.get("schemaVersion"), bool)
        or not isinstance(value.get("schemaVersion"), int)
        or value.get("schemaVersion") != 1
        or isinstance(version_code, bool) or not isinstance(version_code, int)
        or not 1 <= version_code <= 2_100_000_000
        or value.get("versionName") != expected_version
        or not isinstance(expected_version, str) or not SEMVER.fullmatch(expected_version)
        or value.get("apiMajor") != expected_api_major
        or isinstance(api_major, bool) or not isinstance(api_major, int)
        or not isinstance(value.get("buildId"), str)
        or not re.fullmatch(r"[A-Za-z0-9._-]{1,160}", value["buildId"])
        or not isinstance(value.get("commit"), str)
        or not re.fullmatch(r"[0-9a-f]{7,64}", value["commit"])
        or not isinstance(digest, str) or not HEX_SHA256.fullmatch(digest)
        or digest != expected_sha256
        or value.get("apkUrl") != expected_url
    ):
        raise ValueError("updater manifest does not match the expected channel, version, APK hash, or API contract")
    return value



def validate_module_manifest(payload: bytes, *, channel: str, expected_version: str, tag: str) -> dict:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("module updater proxy returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("module updater manifest must be a JSON object")
    expected_tag = "canary" if channel == "canary" else tag
    expected_zip = f"https://github.com/{REPOSITORY}/releases/download/{expected_tag}/Zairenkai-module.zip"
    expected_changelog = f"https://github.com/{REPOSITORY}/releases/tag/{expected_tag}"
    version_code = value.get("versionCode")
    if (
        value.get("version") != f"v{expected_version.lstrip('v')}"
        or not SEMVER.fullmatch(expected_version.removeprefix("v"))
        or isinstance(version_code, bool) or not isinstance(version_code, int)
        or not 1 <= version_code <= 2_100_000_000
        or value.get("zipUrl") != expected_zip
        or value.get("changelog") != expected_changelog
    ):
        raise ValueError("module updater metadata does not match the expected channel, version, or official Release URLs")
    return value

def verify_signature(manifest: bytes, signature: bytes, channel: str) -> None:
    manifest_name = f"version-{channel}.json"
    signature_name = f"{manifest_name}.sig"
    with tempfile.TemporaryDirectory(prefix="zkfc-updater-smoke-") as temporary:
        folder = Path(temporary)
        manifest_path = folder / manifest_name
        signature_path = folder / signature_name
        manifest_path.write_bytes(manifest)
        signature_path.write_bytes(signature)
        subprocess.run(
            [
                sys.executable, str(ROOT / "tools/ci/verify-metadata.py"),
                "--input", str(manifest_path), "--signature", str(signature_path),
            ],
            cwd=ROOT,
            check=True,
        )


def check(
    origin: str, channel: str, apk_path: Path | None, expected_version: str, tag: str,
    retries: int, interval: float, component: str = "app",
) -> None:
    origin = validate_origin(origin)
    if channel not in ("canary", "release"):
        raise ValueError("channel must be canary or release")
    if component not in ("app", "module"):
        raise ValueError("component must be app or module")
    if channel == "release" and not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("stable release tag must be vMAJOR.MINOR.PATCH")
    digest = None
    if component == "app":
        if apk_path is None or not apk_path.is_file() or apk_path.stat().st_size <= 0:
            raise ValueError(f"published APK is missing or empty: {apk_path}")
        digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        manifest_url = f"{origin}/v1/updates/{channel}"
        signature_url = manifest_url + ".sig"
    else:
        manifest_url = f"{origin}/v1/updates/module/{channel}"
        signature_url = None
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            manifest_type, manifest = fetch_bytes(manifest_url, 1024 * 1024)
            if manifest_type != "application/json":
                raise ValueError(f"manifest content type is not application/json: {manifest_type}")
            if component == "app":
                assert signature_url is not None
                signature_type, signature = fetch_bytes(signature_url, 16 * 1024)
                if signature_type not in ("text/plain", "application/octet-stream"):
                    raise ValueError(f"signature content type is unexpected: {signature_type}")
                assert digest is not None
                validate_manifest(
                    manifest, channel=channel, expected_version=expected_version,
                    tag=tag, expected_sha256=digest,
                )
                verify_signature(manifest, signature, channel)
                print(
                    f"PASS: {channel} app updater proxy serves version {expected_version}; "
                    f"manifest SHA-256 matches {apk_path.name} ({digest})."
                )
            else:
                validate_module_manifest(
                    manifest, channel=channel, expected_version=expected_version, tag=tag,
                )
                print(
                    f"PASS: {channel} module updater proxy serves version v{expected_version.lstrip('v')} "
                    f"and official module/changelog URLs."
                )
            return
        except Exception as exc:
            last_error = exc
            if attempt < retries:
                print(
                    f"::notice::Updater proxy attempt {attempt}/{retries} not ready: {exc}; retrying in {interval:g}s",
                    file=sys.stderr,
                )
                time.sleep(interval)
    raise RuntimeError(f"live {channel} {component} updater proxy verification failed after {retries} attempts: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin", help="configured HTTPS License Server origin")
    parser.add_argument("channel", choices=("canary", "release"))
    parser.add_argument("--component", choices=("app", "module"), default="app", help="updater manifest to validate")
    parser.add_argument("--apk", type=Path, help="exact standard APK published in the current release (required for --component app)")
    parser.add_argument("--version", required=True, help="expected app versionName or module version without its leading v")
    parser.add_argument("--tag", default="canary", help="release tag for stable builds")
    parser.add_argument("--retries", type=int, default=15)
    parser.add_argument("--interval", type=float, default=5.0)
    args = parser.parse_args()
    if args.retries < 1 or args.retries > 60 or args.interval < 0 or args.interval > 30:
        parser.error("retries must be 1..60 and interval must be 0..30 seconds")
    try:
        check(
            args.origin, args.channel, args.apk, args.version, args.tag,
            args.retries, args.interval, args.component,
        )
        return 0
    except Exception as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
