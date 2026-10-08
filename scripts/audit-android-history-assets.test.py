#!/usr/bin/env python3
"""End-to-end coverage for the local Android-history asset scanner."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCANNER = Path(__file__).with_name("audit-android-history-assets.py").resolve()


class AndroidHistoryAssetScannerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="collie-history-audit-test-")
        self.root = Path(self.temp.name)
        self.empty_hooks = self.root / "empty-hooks"
        self.empty_hooks.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            {
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": str(self.root / "empty.gitconfig"),
                "GIT_TERMINAL_PROMPT": "0",
                "GIT_ALLOW_PROTOCOL": "file",
                "GIT_PROTOCOL_FROM_USER": "0",
            },
        )
        self.repo = self.root / "fixture-repo"
        self.repo.mkdir()
        self.git("init", "--quiet", "--initial-branch=main")
        self.git("config", "user.name", "Isolated Fixture")
        self.git("config", "user.email", "fixture.invalid@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        self.git("config", "tag.gpgSign", "false")
        self.git("config", "core.hooksPath", str(self.empty_hooks))

        # Deliberately fake values shaped like secret indicators; never use real credentials.
        self.removed_token = "ghp_" + "A" * 36
        self.branch_token = "fixture-branch-" + "B" * 32
        self.worktree_token = "fixture-worktree-" + "C" * 32
        self.removed_line = f"Authorization: Bearer {self.removed_token} [synthetic fixture line]"
        self.branch_line = f"api_key={self.branch_token} [synthetic fixture line]"
        self.worktree_line = f"client_secret={self.worktree_token} [synthetic fixture line]"

        removed = self.repo / "android" / "history" / "removed-candidate.txt"
        removed.parent.mkdir(parents=True)
        removed.write_text(self.removed_line + "\n", encoding="utf-8")
        self.git("add", "android/history/removed-candidate.txt")
        self.git("commit", "--quiet", "-m", "add synthetic historical fixture")
        removed.unlink()
        self.git("add", "-u")
        self.git("commit", "--quiet", "-m", "remove synthetic historical fixture")

        self.git("switch", "--quiet", "--create", "audit-only")
        branch_path = self.repo / "tools" / "branch-only-candidate.cfg"
        branch_path.parent.mkdir(parents=True)
        branch_path.write_text(self.branch_line + "\n", encoding="utf-8")
        self.git("add", "tools/branch-only-candidate.cfg")
        self.git("commit", "--quiet", "-m", "add candidate on local-only branch")
        self.git("switch", "--quiet", "main")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str, cwd: Path | None = None) -> str:
        result = subprocess.run(
            ["git", *args],
            cwd=cwd or self.repo,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            self.fail(f"isolated Git fixture command failed with status {result.returncode}")
        return result.stdout

    def scan(self, repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(SCANNER), *args],
            cwd=repo,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            self.fail(f"scanner exited with status {result.returncode}")
        self.assert_redacted(result.stdout + result.stderr)
        return result

    def assert_redacted(self, output: str) -> None:
        fixture_content = (
            self.removed_token,
            self.branch_token,
            self.worktree_token,
            self.removed_line,
            self.branch_line,
            self.worktree_line,
        )
        if any(value in output for value in fixture_content):
            self.fail("scanner output leaked synthetic fixture bytes or a matched line")

    def assert_output_contains(self, output: str, expected: str, description: str) -> None:
        if expected not in output:
            self.fail(f"scanner output omitted expected {description}")

    def test_history_scans_removed_candidates_and_all_local_branches_redacted(self) -> None:
        result = self.scan(self.repo)

        self.assert_output_contains(result.stdout, "scope=reachable-local-refs", "history scope")
        self.assert_output_contains(
            result.stdout,
            "candidate rule=bearer-value path=android/history/removed-candidate.txt",
            "removed historical candidate",
        )
        self.assert_output_contains(
            result.stdout,
            "candidate rule=credential-assignment path=tools/branch-only-candidate.cfg",
            "candidate reachable only through the local audit-only branch",
        )

    def test_worktree_mode_scans_untracked_candidate_without_printing_content(self) -> None:
        worktree_file = self.repo / "scratch" / "untracked-candidate.cfg"
        worktree_file.parent.mkdir()
        worktree_file.write_text(self.worktree_line + "\n", encoding="utf-8")

        result = self.scan(self.repo, "--worktree")

        self.assert_output_contains(result.stdout, "scope=worktree", "worktree scope")
        self.assert_output_contains(
            result.stdout,
            "candidate rule=credential-assignment path=scratch/untracked-candidate.cfg",
            "untracked worktree candidate",
        )

    def test_shallow_clone_reports_boundary_without_claiming_missing_history(self) -> None:
        shallow = self.root / "shallow-clone"
        self.git(
            "clone",
            "--quiet",
            "--depth=1",
            "--branch=main",
            f"file://{self.repo}",
            str(shallow),
            cwd=self.root,
        )

        result = self.scan(shallow)

        self.assert_output_contains(result.stdout, "shallow=true", "shallow repository marker")
        self.assert_output_contains(
            result.stdout,
            "history stops at configured shallow boundary",
            "shallow history coverage limitation",
        )
        self.assert_output_contains(result.stdout, "candidates=0", "empty shallow candidate result")
        if "android/history/removed-candidate.txt" in result.stdout:
            self.fail("shallow scan reported an object outside its reachable history boundary")


if __name__ == "__main__":
    unittest.main()
