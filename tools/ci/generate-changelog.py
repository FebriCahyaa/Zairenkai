#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate release notes from Git commits since the previous stable tag."""
from __future__ import annotations
import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
SEMVER = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
CONVENTIONAL = re.compile(r"^(feat|fix|perf|docs|refactor|build|ci|test|chore|revert|style|security)(?:\(([^)]+)\))?(!)?:\s*(.+)$", re.I)
GROUPS = {
    "feat": "Features", "fix": "Fixes", "perf": "Performance",
    "docs": "Documentation", "refactor": "Refactoring",
    "build": "Build & Dependencies", "ci": "Build & CI",
    "test": "Tests", "chore": "Maintenance", "revert": "Reverts",
    "style": "Style", "security": "Security",
}
ORDER = ["Security", "Features", "Fixes", "Performance", "Refactoring", "Documentation", "Build & Dependencies", "Build & CI", "Tests", "Style", "Reverts", "Maintenance", "Other Changes"]

def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()

def version_tuple(tag: str) -> tuple[int, int, int] | None:
    match = SEMVER.fullmatch(tag)
    return tuple(int(part) for part in match.groups()) if match else None

def parse_commit(subject: str) -> tuple[str, str]:
    match = CONVENTIONAL.match(subject.strip())
    if not match:
        return "Other Changes", subject.strip()
    kind, scope, breaking, description = match.groups()
    kind = kind.lower()
    group = "Security" if kind == "security" else GROUPS.get(kind, "Other Changes")
    prefix = f"**{scope}:** " if scope else ""
    if breaking:
        prefix = "**BREAKING:** " + prefix
    return group, prefix + description.strip()

def render(channel: str, version: str, head: str, base: str | None, commits: Sequence[tuple[str, str]]) -> str:
    label = f"Zairenkai {version} Changelog" if channel == "release" else "Zairenkai Canary Changelog"
    lines = [f"# {label}", "", f"- Channel: `{channel}`", f"- Commit: [`{head[:12]}`](https://github.com/FebriCahyaa/Zairenkai/commit/{head})"]
    if base:
        lines.append(f"- Commit range: `{base}..{head[:12]}`")
    lines.extend(["", "## Changes"])
    grouped: dict[str, list[tuple[str, str]]] = {}
    for sha, subject in commits:
        group, content = parse_commit(subject)
        grouped.setdefault(group, []).append((sha, content))
    any_items = False
    for group in ORDER:
        items = grouped.get(group, [])
        if not items:
            continue
        any_items = True
        lines.extend(["", f"### {group}"])
        for sha, subject in items:
            lines.append(f"- {subject} (`{sha[:8]}`)")
    if not any_items:
        lines.extend(["", "- No non-merge commit subjects were found in this range."])
    lines.extend(["", "---", "Generated automatically from Git commit history by `tools/ci/generate-changelog.py`.", ""])
    return "\n".join(lines)

def collect(channel: str, tag: str | None) -> tuple[str, str | None, list[tuple[str, str]]]:
    head = git("rev-parse", "HEAD")
    tags = git("tag", "--merged", "HEAD", "--list", "v*", "--sort=-version:refname").splitlines()
    # Restrict baselines to tags reachable from this commit. A newer release tag
    # on another branch must never corrupt a development-branch changelog range.
    stable = [(tag_name, version_tuple(tag_name)) for tag_name in tags]
    stable = [(name, ver) for name, ver in stable if ver is not None]
    current_tag = tag
    current_tag_exists = bool(current_tag and current_tag in tags)
    if channel == "release":
        if not current_tag:
            try:
                current_tag = git("describe", "--tags", "--exact-match", "HEAD")
            except RuntimeError:
                raise ValueError("Release changelog needs --tag when drafting before the tag is created")
            current_tag_exists = current_tag in tags
        current_version = version_tuple(current_tag)
        if current_version is None:
            raise ValueError(f"Release tag must be stable vMAJOR.MINOR.PATCH: {current_tag}")
        older = [(name, ver) for name, ver in stable if ver < current_version and name != current_tag]
        base = max(older, key=lambda item: item[1])[0] if older else None
    else:
        base = stable[0][0] if stable else None
    if base:
        # A manually requested Release is built before its vX.Y.Z tag is created.
        # In that case describe commits through HEAD; use the tag only if it exists.
        target = current_tag if current_tag_exists and current_tag else head
        revision_range = f"{base}..{target}"
        raw = git("log", "--reverse", "--no-merges", "--format=%H%x09%s", revision_range)
    else:
        # A new repository with no stable tag: summarize all reachable history.
        revision_range = head
        raw = git("log", "--reverse", "--no-merges", "--format=%H%x09%s", "HEAD")
    commits: list[tuple[str, str]] = []
    for line in raw.splitlines():
        if "\t" in line:
            sha, subject = line.split("\t", 1)
            commits.append((sha, subject))
    return head, base, commits

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=("canary", "release"))
    parser.add_argument("--tag", default=None, help="release tag (defaults to the exact current tag)")
    parser.add_argument("--version", default=None, help="display version for the heading")
    parser.add_argument("--output", default="Zairenkai-CHANGELOG.md")
    args = parser.parse_args()
    try:
        head, base, commits = collect(args.channel, args.tag)
        version = args.version or (args.tag or "canary")
        output = (ROOT / args.output).resolve()
        if ROOT not in output.parents:
            raise ValueError("Output path must remain inside the checkout")
        output.write_text(render(args.channel, version, head, base, commits), encoding="utf-8")
        print(f"Generated {output.relative_to(ROOT)} from {len(commits)} commit(s); base={base or 'repository history'}")
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
