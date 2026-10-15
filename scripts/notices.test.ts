import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const read = (path: string) => readFileSync(resolve(root, path), "utf8");
const ANDROID_NOTICES = "android/app/src/main/res/raw/third_party_notices.txt";

describe("notices", () => {
  test("the Goose block carries Block's copyright, not the Apache template", () => {
    const text = read(ANDROID_NOTICES);
    expect(text).toContain("Copyright 2024 Block, Inc.");
    expect(text).not.toContain("[yyyy]");
    expect(text).not.toContain("[name of copyright owner]");
  });

  test("Android sources and fixtures carry no maintainer path, tailnet host or LAN address", () => {
    // The account name is joined at run time so this file does not match the repo-wide guard.
    const user = "chris";
    const result = spawnSync(
      "git",
      ["grep", "-nE", `/home/${user}|${user}@ed8|\\.tail[0-9a-f]{6}\\.ts\\.net|192\\.168\\.[0-9]+\\.[0-9]+`, "--", "android/app/src"],
      { cwd: root, encoding: "utf8" },
    );
    expect(result.error).toBeUndefined();
    expect(result.status).toBe(1);
    expect(result.stdout).toBe("");
    expect(result.stderr).toBe("");
  });
});
