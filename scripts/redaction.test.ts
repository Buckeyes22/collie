import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

const root = join(import.meta.dir, "..");

// Paths outside the check: the Android sources are cleaned in their own change, the history
// review quotes the private values it reviews, and the remediation plan names the patterns.
const excluded = [
  "android/app/src",
  "android/validation/hardening/history-assets-review.md",
  "docs/superpowers/plans/2026-10-08-public-release-remediation.md",
];

describe("operational metadata", () => {
  test("tracked text carries no maintainer path, tailnet host or LAN address", () => {
    // -I skips binaries, so the font subsets that match by accident are not read.
    const result = spawnSync(
      "git",
      [
        "grep",
        "-lIE",
        // "/home/" and the account name are joined at run time so this file does not match itself.
        `/home/${"chris"}|\\.tail[0-9a-f]{6}\\.ts\\.net|192\\.168\\.[0-9]+\\.[0-9]+`,
        "--",
        ".",
        ...excluded.map((path) => `:!${path}`),
      ],
      { cwd: root, encoding: "utf8" },
    );
    // git grep exits 1 when nothing matches, which is the passing case.
    expect(result.status).toBe(1);
    expect(result.stdout).toBe("");
  });
});
