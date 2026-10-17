#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Fail closed unless the configured License Server and its D1 database are live."""
from __future__ import annotations
import json
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit


def fetch_json(url: str, timeout: float = 12.0) -> dict:
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Zairenkai-CI/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}")
            if response.headers.get_content_type() != "application/json":
                raise RuntimeError("response content type is not application/json")
            value = json.load(response)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} for {urlsplit(url).path}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"network error for {urlsplit(url).path}: {exc.reason}") from exc
    if not isinstance(value, dict):
        raise RuntimeError(f"invalid JSON object at {urlsplit(url).path}")
    return value


def validate_origin(origin: str) -> str:
    parsed = urlsplit(origin)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("server URL must be an HTTPS origin")
    if parsed.path not in ("", "/") or parsed.port not in (None, 443):
        raise ValueError("server URL must not include a path or non-443 port")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", parsed.hostname):
        raise ValueError("server hostname contains invalid characters")
    return f"https://{parsed.netloc}"


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: smoke-license-server.py https://license-server.example", file=sys.stderr)
        return 2
    try:
        origin = validate_origin(sys.argv[1].strip())
        health = fetch_json(origin + "/v1/health")
        if health.get("ok") is not True or health.get("service") != "zairenkai-license-server" or health.get("apiMajor") != 1:
            raise RuntimeError("/v1/health returned an unexpected service or API contract")
        ready = fetch_json(origin + "/v1/ready")
        if (
            ready.get("ok") is not True
            or ready.get("service") != "zairenkai-license-server"
            or ready.get("database") != "connected"
        ):
            raise RuntimeError("/v1/ready did not confirm the expected Worker and connected D1 database")
        print(f"PASS: License Server health and D1 readiness verified at {origin}")
        return 0
    except Exception as exc:
        print(f"::error::License Server preflight failed: {exc}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
