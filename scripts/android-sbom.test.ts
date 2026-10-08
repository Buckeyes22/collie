import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { androidSbom } from "./android-sbom.ts";

const hash = "a".repeat(64);
describe("Android release dependency inventory", () => {
  test("includes release transitive inputs and excludes test/compiler-only versions", () => {
    const bom = androidSbom([
      "# Gradle lockfile", "a:lib:1=debugRuntimeClasspath,releaseRuntimeClasspath",
      "a:lib:0=testImplementationDependenciesMetadata", "b:transitive:2=releaseRuntimeClasspath",
      "junit:junit:4.13.2=debugUnitTestRuntimeClasspath", "empty=lintChecks", "",
    ].join("\n"), "1.5.1", hash);
    expect(bom.components.map((c) => c.purl)).toEqual(["pkg:maven/a/lib@1", "pkg:maven/b/transitive@2"]);
    expect(bom.metadata.component.hashes[0]!.content).toBe(hash);
    expect(bom.compositions[0]!.aggregate).toBe("incomplete");
    expect(bom).not.toHaveProperty("dependencies");
  });
  test("encodes package URLs and produces stable ordering", () => {
    const a = "z:thing:1+patch=releaseRuntimeClasspath\na:thing:2=releaseRuntimeClasspath";
    const b = a.split("\n").toReversed().join("\n");
    expect(androidSbom(a, "1.5.2-beta.1", hash).components).toEqual(androidSbom(b, "1.5.2-beta.1", hash).components);
    expect(androidSbom(a, "1.5.1", hash).components[1]!.purl).toEndWith("@1%2Bpatch");
  });
  test("fails closed for missing, malformed or conflicting locks", () => {
    for (const lock of ["", "empty=releaseRuntimeClasspath", "bad=releaseRuntimeClasspath", "a:b:1=releaseRuntimeClasspath\na:b:2=releaseRuntimeClasspath", "a:b:1=releaseRuntimeClasspath=other"]) {
      expect(() => androidSbom(lock, "1.5.1", hash)).toThrow();
    }
    expect(() => androidSbom("a:b:1=releaseRuntimeClasspath", "unsafe/version", hash)).toThrow();
    expect(() => androidSbom("a:b:1=releaseRuntimeClasspath", "1.5.1", "bad")).toThrow();
  });
  test("the actual app lock has network libraries but no Robolectric, JUnit or compiler", () => {
    const lock = readFileSync(new URL("../android/app/gradle.lockfile", import.meta.url), "utf8");
    const bom = androidSbom(lock, "1.5.1", hash);
    expect(bom.components.some((c) => c.group === "com.squareup.okhttp3" && c.name === "okhttp")).toBe(true);
    expect(bom.components.filter((c) => /junit|robolectric|compiler/.test(`${c.group}:${c.name}`))).toEqual([]);
    expect(new Set(bom.components.map((c) => c["bom-ref"])).size).toBe(bom.components.length);
  });
});
