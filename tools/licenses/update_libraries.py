#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Regenerate tools/licenses/libraries.json from the libraries' Maven POMs.

    ./gradlew :app:writeOssModules        # lists every packaged library
    python3 tools/licenses/update_libraries.py

For each "group:name:version" in app/build/oss/modules.txt the POM is read
from the Gradle cache when present, else downloaded from Google Maven or
Maven Central (with back-off when rate limited). Fields missing from a POM
(licences, developers, website, source) are taken from its parent POMs.
Licence names are mapped to SPDX ids; the app bundles the full text of each
id from tools/licenses/texts. Entries under "extra" (non-Maven components
such as the bundled typeface) are kept as they are.

The build (OssLicensesTask) fails when a packaged library has no entry here.
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = ROOT / "app/build/oss/modules.txt"
OUT = ROOT / "tools/licenses/libraries.json"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo.maven.apache.org/maven2"]


def local_pom(group: str, name: str, version: str) -> bytes | None:
    d = CACHE / group / name / version
    for p in d.glob("*/*.pom") if d.exists() else []:
        return p.read_bytes()
    return None


def fetch_pom(group: str, name: str, version: str) -> bytes | None:
    data = local_pom(group, name, version)
    if data:
        return data
    path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"
    for repo in REPOS:
        for attempt in range(6):
            try:
                with urllib.request.urlopen(f"{repo}/{path}", timeout=30) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    break
                if e.code in (429, 500, 502, 503):
                    time.sleep(2 ** attempt)
                    continue
                break
            except urllib.error.URLError:
                time.sleep(2 ** attempt)
    return None


def strip_ns(root: ET.Element) -> ET.Element:
    for el in root.iter():
        if "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return root


def text(el: ET.Element | None, tag: str) -> str | None:
    if el is None:
        return None
    c = el.find(tag)
    if c is None or c.text is None:
        return None
    t = " ".join(c.text.split())
    return t if t and "${" not in t else None


def spdx(name: str | None, url: str | None) -> str:
    n = f"{name or ''} {url or ''}".lower()
    words = set(re.findall(r"[a-z0-9.-]+", n))
    if "apache" in n:
        return "Apache-2.0"
    if "bouncy castle" in n:
        return "Bouncy-Castle-License"
    if "mit" in words or "mit-license" in n or "/mit" in n:
        return "MIT"
    if "bsd" in n:
        return "BSD-2-Clause" if ("2-clause" in n or "simplified" in n) else "BSD-3-Clause"
    if "ofl" in n or "open font" in n:
        return "OFL-1.1"
    if "eclipse public license" in n or "epl" in words:
        return "EPL-2.0" if "2.0" in n else "EPL-1.0"
    return name or "Unknown"


POMS: dict[str, ET.Element | None] = {}


def pom(coord: str) -> ET.Element | None:
    if coord not in POMS:
        g, a, v = coord.split(":")
        data = fetch_pom(g, a, v)
        POMS[coord] = strip_ns(ET.fromstring(data)) if data else None
    return POMS[coord]


def parent(el: ET.Element) -> str | None:
    p = el.find("parent")
    if p is None:
        return None
    parts = [text(p, "groupId"), text(p, "artifactId"), text(p, "version")]
    return ":".join(parts) if all(parts) else None


def inherit(coord: str, get):
    c: str | None = coord
    for _ in range(6):
        el = pom(c) if c else None
        if el is None:
            return None
        v = get(el)
        if v:
            return v
        c = parent(el)
    return None


def library(coord: str) -> dict | None:
    el = pom(coord)
    if el is None:
        return None
    name = text(el, "name") or coord.split(":")[1]
    lic = inherit(coord, lambda e: [
        {"spdx": spdx(text(l, "name"), text(l, "url")), "name": text(l, "name") or spdx(None, text(l, "url")), "url": text(l, "url")}
        for l in e.findall("licenses/license")
    ])
    devs = inherit(coord, lambda e: [d for d in (text(x, "name") or text(x, "organization") for x in e.findall("developers/developer")) if d])
    if not devs:
        org = inherit(coord, lambda e: text(e.find("organization"), "name"))
        devs = [org] if org else []
    desc = text(el, "description")
    return {
        "name": name,
        "description": desc if desc and desc.lower() != name.lower() else None,
        "developers": list(dict.fromkeys(devs)),
        "website": text(el, "url") or inherit(coord, lambda e: text(e, "url")),
        "source": inherit(coord, lambda e: text(e.find("scm"), "url")),
        "licenses": lic or [],
    }


def main() -> None:
    if not MODULES.exists():
        sys.exit("Run ./gradlew :app:writeOssModules first.")
    coords = [l.strip() for l in MODULES.read_text().splitlines() if l.strip()]
    old = json.loads(OUT.read_text()) if OUT.exists() else {"libraries": {}, "extra": []}
    libs: dict[str, dict] = {}
    missing = []
    for coord in coords:
        ga = coord.rsplit(":", 1)[0]
        entry = library(coord)
        if entry is None:
            if ga in old["libraries"]:
                entry = old["libraries"][ga]
            else:
                missing.append(coord)
                continue
        libs[ga] = entry
    OUT.write_text(json.dumps({"libraries": dict(sorted(libs.items())), "extra": old.get("extra", [])}, indent=1, ensure_ascii=False) + "\n")
    unknown = sorted({l["spdx"] for e in libs.values() for l in e["licenses"]} - {p.stem for p in (OUT.parent / "texts").glob("*.txt")})
    print(f"{len(libs)} libraries; missing POM: {missing or 'none'}; licence ids without text: {unknown or 'none'}")
    no_lic = [ga for ga, e in libs.items() if not e["licenses"]]
    if no_lic:
        print("No licence in POM:", ", ".join(no_lic))


if __name__ == "__main__":
    main()
