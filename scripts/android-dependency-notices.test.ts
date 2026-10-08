import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { dependencyNotices } from "./android-dependency-notices";

const root = resolve(import.meta.dir, "..");
const map = { rules: [
  { prefix: "androidx.", license: "Apache-2.0", source: "https://android.googlesource.com/platform/frameworks/support" },
  { prefix: "com.squareup.okhttp3:", license: "Apache-2.0", source: "https://github.com/square/okhttp",
    extra: "OkHttp bundles the Public Suffix List (MPL-2.0), https://publicsuffix.org/." },
] };
const lock = (...c: string[]) => c.map((x) => `${x}=releaseRuntimeClasspath`).join("\n");

describe("dependencyNotices", () => {
  test("lists each module with its license and source, then the license text once", () => {
    const out = dependencyNotices(lock("androidx.core:core:1.0.0", "com.squareup.okhttp3:okhttp:4.12.0"), map, "APACHE TEXT");
    expect(out).toContain("androidx.core:core:1.0.0 — Apache-2.0 — https://android.googlesource.com/platform/frameworks/support");
    expect(out).toContain("Public Suffix List (MPL-2.0)");
    expect(out.match(/APACHE TEXT/g)?.length).toBe(1);
  });

  test("an unmapped module fails instead of emitting an empty license", () => {
    expect(() => dependencyNotices(lock("org.example:lib:1.0"), map, "x")).toThrow("org.example:lib has no license mapping");
  });

  test("the packaged file matches the current lockfile and map", () => {
    const expected = dependencyNotices(
      readFileSync(resolve(root, "android/app/gradle.lockfile"), "utf8"),
      JSON.parse(readFileSync(resolve(root, "android/dependency-licenses.json"), "utf8")),
      readFileSync(resolve(root, "android/licenses/Apache-2.0.txt"), "utf8"),
    );
    expect(readFileSync(resolve(root, "android/app/src/main/res/raw/dependency_notices.txt"), "utf8")).toBe(expected);
  });
});
