#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Fast unit tests for commit-to-changelog mapping."""
from __future__ import annotations
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("generate-changelog.py")
SPEC = importlib.util.spec_from_file_location("zairenkai_changelog", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class ChangelogTests(unittest.TestCase):
    def test_conventional_commits_map_to_sections(self):
        self.assertEqual(module.parse_commit("feat(updater): add signed canary updates")[0], "Features")
        self.assertEqual(module.parse_commit("fix: reject invalid manifest")[0], "Fixes")
        self.assertEqual(module.parse_commit("security!: rotate owner key")[0], "Security")
        self.assertEqual(module.parse_commit("random maintenance wording")[0], "Other Changes")

    def test_collect_uses_commits_since_latest_stable_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            def git(*args):
                subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.DEVNULL)
            git("init", "-q")
            git("config", "user.name", "CI Test")
            git("config", "user.email", "ci@example.invalid")
            (repo / "tracked.txt").write_text("base\n", encoding="utf-8")
            git("add", "tracked.txt")
            git("commit", "-m", "chore: initial baseline", "-q")
            git("tag", "v1.0.0")
            (repo / "tracked.txt").write_text("feature\n", encoding="utf-8")
            git("commit", "-am", "feat(updater): add signed canary metadata", "-q")
            (repo / "tracked.txt").write_text("fixed\n", encoding="utf-8")
            git("commit", "-am", "fix: reject stale manifests", "-q")
            with patch.object(module, "ROOT", repo):
                head, base, commits = module.collect("canary", None)
            self.assertEqual(base, "v1.0.0")
            self.assertEqual(len(commits), 2)
            changelog = module.render("canary", "1.0.0-canary.3", head, base, commits)
            self.assertIn("signed canary metadata", changelog)
            self.assertIn("reject stale manifests", changelog)
            self.assertNotIn("initial baseline", changelog)

    def test_collect_ignores_stable_tags_not_merged_into_head(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            def git(*args):
                subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.DEVNULL)
            git("init", "-q")
            git("config", "user.name", "CI Test")
            git("config", "user.email", "ci@example.invalid")
            (repo / "tracked.txt").write_text("base\n", encoding="utf-8")
            git("add", "tracked.txt")
            git("commit", "-m", "chore: common root", "-q")
            common = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            git("tag", "v1.0.0")
            git("checkout", "-q", "--orphan", "other-release-line")
            git("rm", "-q", "-rf", ".")
            (repo / "other.txt").write_text("other\n", encoding="utf-8")
            git("add", "other.txt")
            git("commit", "-m", "feat: unrelated future release", "-q")
            git("tag", "v9.0.0")
            git("checkout", "-q", "--orphan", "dev-line")
            git("rm", "-q", "-rf", ".")
            (repo / "dev.txt").write_text("dev\n", encoding="utf-8")
            git("add", "dev.txt")
            git("commit", "-m", "feat: development branch change", "-q")
            with patch.object(module, "ROOT", repo):
                _, base, commits = module.collect("canary", None)
            self.assertIsNone(base)  # Neither tag is an ancestor of this orphan branch.
            self.assertEqual(len(commits), 1)
            self.assertIn("development branch change", commits[0][1])

    def test_render_includes_commit_ids_and_range(self):
        text = module.render("release", "v1.2.3", "abcdef0123456789", "v1.2.2", [
            ("111111112222", "feat(app): add better dashboard"),
            ("333333334444", "fix: prevent stale package update"),
        ])
        self.assertIn("Zairenkai v1.2.3 Changelog", text)
        self.assertIn("v1.2.2..abcdef012345", text)
        self.assertIn("Features", text)
        self.assertIn("better dashboard (`11111111`)", text)
        self.assertIn("prevent stale package update (`33333333`)", text)

if __name__ == "__main__":
    unittest.main(verbosity=2)
