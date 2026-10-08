import { describe, expect, test } from "bun:test";
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
});
