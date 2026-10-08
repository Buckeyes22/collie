#!/usr/bin/env python3
"""Scan locally reachable Git blobs for secret/private-config indicators.

Output is deliberately restricted to rule names, paths, commit IDs, and counts. It
never prints matched bytes, lines, or values. A human must classify the candidates.
This is a focused safety net, not a replacement for a maintained secret scanner.
"""

from __future__ import annotations

import collections
import argparse
import re
import subprocess
import sys
from pathlib import Path


RULES: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    ("private-key-block", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("aws-access-key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("github-token", re.compile(rb"\b(?:github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9]{30,})\b")),
    ("slack-token", re.compile(rb"\bxox[a-z]-[A-Za-z0-9-]{20,}\b")),
    ("google-api-key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("bearer-value", re.compile(rb"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{20,}")),
    ("credential-assignment", re.compile(
        rb"(?i)\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|private[_-]?key)"
        rb"\b\s*[:=]\s*[\"']?[A-Za-z0-9_./+=:-]{16,}"
    )),
    ("jwt-shaped", re.compile(rb"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")),
    ("tailscale-host", re.compile(rb"(?i)\b[a-z0-9][a-z0-9.-]{0,100}\.ts\.net\b")),
    ("private-ipv4", re.compile(
        rb"(?<![0-9])(?:10\.(?:[0-9]{1,3}\.){2}[0-9]{1,3}|"
        rb"172\.(?:1[6-9]|2[0-9]|3[01])\.(?:[0-9]{1,3}\.)[0-9]{1,3}|"
        rb"192\.168\.(?:[0-9]{1,3}\.)[0-9]{1,3})(?![0-9])"
    )),
    ("workstation-path", re.compile(rb"(?:/home/|/Users/)[A-Za-z0-9._-]{1,64}/")),
)


def git(*args: str, input_bytes: bytes | None = None) -> bytes:
    return subprocess.check_output(["git", *args], input=input_bytes, stderr=subprocess.DEVNULL)


def scan_worktree() -> int:
    """Scan tracked and untracked, non-ignored worktree files without printing content."""
    paths = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode("utf-8", "replace").split("\0")
    findings: dict[tuple[str, str], int] = collections.defaultdict(int)
    scanned = 0
    for name in paths:
        if not name:
            continue
        path = Path(name)
        if path.is_symlink():
            continue
        try:
            data = path.read_bytes()
        except (OSError, IsADirectoryError):
            continue
        scanned += 1
        for rule, pattern in RULES:
            if pattern.search(data):
                findings[(rule, name)] += 1
    print(f"scope=worktree tracked_and_untracked_files={scanned}")
    for (rule, path), count in sorted(findings.items()):
        print(f"candidate rule={rule} path={path} occurrences={count} source=WORKTREE")
    print(f"candidate_paths={len(findings)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worktree", action="store_true", help="scan current tracked and untracked files")
    if parser.parse_args().worktree:
        return scan_worktree()

    shallow = git("rev-parse", "--is-shallow-repository").decode().strip() == "true"
    commits = git("rev-list", "--all").decode().splitlines()
    object_rows = git("rev-list", "--objects", "--all").decode("utf-8", "replace").splitlines()
    object_paths: dict[str, str] = {}
    for row in object_rows:
        parts = row.split(" ", 1)
        if len(parts) == 2:
            object_paths.setdefault(parts[0], parts[1])

    object_ids = list(object_paths)
    metadata = git("cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)", input_bytes=("\n".join(object_ids) + "\n").encode())
    blobs: list[tuple[str, int]] = []
    for row in metadata.decode().splitlines():
        oid, kind, size = row.split()
        if kind == "blob":
            blobs.append((oid, int(size)))

    findings: dict[tuple[str, str], set[str]] = collections.defaultdict(set)
    scanned = 0
    content = git("cat-file", "--batch", input_bytes=("\n".join(oid for oid, _ in blobs) + "\n").encode())
    offset = 0
    for oid, expected_size in blobs:
        header_end = content.find(b"\n", offset)
        if header_end < 0:
            raise RuntimeError("git cat-file batch output ended unexpectedly")
        header = content[offset:header_end].split()
        if len(header) != 3 or int(header[2]) != expected_size:
            raise RuntimeError("git cat-file batch metadata did not match")
        start = header_end + 1
        data = content[start:start + expected_size]
        offset = start + expected_size + 1
        scanned += 1
        for name, pattern in RULES:
            if pattern.search(data):
                findings[(oid, name)].add(object_paths.get(oid, "(path unavailable)"))

    # Resolve paths and introducing commits only for candidate blobs. --find-object
    # output gives path changes without dumping blob content.
    print(f"scope=reachable-local-refs commits={len(commits)} blobs={scanned} shallow={str(shallow).lower()}")
    if shallow:
        print("coverage=history stops at configured shallow boundary; objects outside reachable graph were not scanned")
    if not findings:
        print("candidates=0")
        return 0

    grouped: dict[tuple[str, str], dict[str, set[str]]] = collections.defaultdict(lambda: {"commits": set(), "oids": set()})
    for (oid, rule), paths in sorted(findings.items(), key=lambda item: (item[0][1], sorted(item[1]))):
        history = git("log", "--all", f"--find-object={oid}", "--format=%H", "--name-only", "--no-renames").decode("utf-8", "replace").splitlines()
        commit_paths: list[tuple[str, str]] = []
        current_commit = ""
        for line in history:
            if re.fullmatch(r"[0-9a-f]{40,64}", line):
                current_commit = line
            elif line:
                commit_paths.append((current_commit, line))
        matches = [(commit, path) for commit, path in commit_paths if path in paths]
        if not matches:
            matches = [("(reachable-object)", path) for path in paths]
        for commit, path in matches:
            grouped[(rule, path)]["commits"].add(commit)
            grouped[(rule, path)]["oids"].add(oid)
    for (rule, path), details in sorted(grouped.items()):
        commits_for_path = sorted(details["commits"])
        print(
            f"candidate rule={rule} path={path} blobs={len(details['oids'])} "
            f"commit_count={len(commits_for_path)} commits={','.join(commits_for_path[:3])}"
        )
    print(f"candidate_paths={len(grouped)} candidate_blobs={len(findings)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"audit failed: git command exited {error.returncode}", file=sys.stderr)
        raise SystemExit(error.returncode)
