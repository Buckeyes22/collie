import { describe, expect, test } from "bun:test";
import { cpSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { androidSbom, nativeReleaseComponents } from "./android-sbom.ts";

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


describe("verified native decoder build inventory", () => {
  const source = resolve(import.meta.dir, "../android/app/src/main/cpp/third_party");
  test("records pinned sources, exact MIT license bytes and retained patch hashes", () => {
    const native = nativeReleaseComponents(source);
    expect(native.map((component) => `${component.name}@${component.version}`)).toEqual(["woff2@1.0.2", "brotli@1.2.0"]);
    for (const component of native) {
      const manifest = JSON.parse(readFileSync(resolve(source, component.name, "UPSTREAM.json"), "utf8"));
      expect(component.purl).toBe(`pkg:github/google/${component.name}@${manifest.commit}`);
      expect(component.licenses[0]!.license.id).toBe("MIT");
      expect(Buffer.from(component.licenses[0]!.license.text.content, "base64")).toEqual(readFileSync(resolve(source, component.name, "LICENSE")));
      expect(component.externalReferences[1]!.url).toBe(`https://codeload.github.com/google/${component.name}/tar.gz/${manifest.commit}`);
      const inventory = JSON.parse(component.properties.find((property) => property.name === "collie:native:retainedFiles")!.value);
      expect(inventory.length).toBe(manifest.files.length);
      expect(inventory.filter((entry: { upstreamSha256: string; currentSha256: string }) => entry.upstreamSha256 !== entry.currentSha256).length)
        .toBe(manifest.localPatches.length);
    }
    const bom = androidSbom("a:b:1=releaseRuntimeClasspath", "1.5.2", hash, native);
    expect(bom.components.length).toBe(3);
    expect(bom.compositions[0]!.aggregate).toBe("incomplete");
    expect(bom.metadata.properties.find((property) => property.name === "collie:limitations")!.value).toContain("binary-level retention/link analysis");
  });

  test("the release CLI includes verified native components without changing its arguments", () => {
    const temporary = mkdtempSync(resolve(tmpdir(), "collie-sbom-cli-"));
    try {
      const apk = resolve(temporary, "fixture.apk");
      const output = resolve(temporary, "inventory.json");
      // This tests inventory generation only; these bytes are not an APK acceptance artifact.
      writeFileSync(apk, "inventory CLI input fixture");
      const result = Bun.spawnSync([process.execPath, resolve(import.meta.dir, "android-sbom.ts"), apk, "1.5.2", output]);
      expect(result.exitCode).toBe(0);
      const bom = JSON.parse(readFileSync(output, "utf8"));
      expect(bom.components.filter((component: { group: string }) => component.group === "google").length).toBe(2);
      expect(bom.components.some((component: { name: string }) => component.name === "woff2")).toBe(true);
      expect(bom.components.some((component: { name: string }) => component.name === "brotli")).toBe(true);
    } finally { rmSync(temporary, { recursive: true, force: true }); }
  });

  test("rejects changed source and license bytes, undeclared files and links", () => {
    const temporary = mkdtempSync(resolve(tmpdir(), "collie-native-inventory-"));
    try {
      cpSync(source, temporary, { recursive: true });
      const file = resolve(temporary, "woff2/src/woff2_dec.cc");
      const original = readFileSync(file);
      writeFileSync(file, Buffer.concat([original, Buffer.from("\n// Changed fixture bytes\n")]));
      expect(() => nativeReleaseComponents(temporary)).toThrow("Native source hash mismatch");
      writeFileSync(file, original);
      const license = resolve(temporary, "brotli/LICENSE");
      const licenseBytes = readFileSync(license);
      writeFileSync(license, "Changed license");
      expect(() => nativeReleaseComponents(temporary)).toThrow("Native source hash mismatch");
      writeFileSync(license, licenseBytes);
      const extra = resolve(temporary, "woff2/src/unrecorded.cc");
      writeFileSync(extra, "unlisted");
      expect(() => nativeReleaseComponents(temporary)).toThrow("Unlisted or linked native source");
      rmSync(extra);
      rmSync(file);
      symlinkSync(resolve(source, "woff2/src/woff2_dec.cc"), file);
      expect(() => nativeReleaseComponents(temporary)).toThrow("Unlisted or linked native source");
    } finally { rmSync(temporary, { recursive: true, force: true }); }
  });

  test("rejects unpinned provenance, traversal and malformed manifest inventories", () => {
    const temporary = mkdtempSync(resolve(tmpdir(), "collie-native-manifest-"));
    try {
      cpSync(source, temporary, { recursive: true });
      const file = resolve(temporary, "woff2/UPSTREAM.json");
      const original = readFileSync(file, "utf8");
      const manifest = JSON.parse(original);
      for (const altered of [
        { ...manifest, commit: "a".repeat(40) },
        { ...manifest, repository: "https://attacker.example/woff2" },
        { ...manifest, files: [...manifest.files, { file: "../LICENSE", sha256: hash }] },
        { ...manifest, files: [...manifest.files, manifest.files[0]] },
        { ...manifest, localPatches: [{ file: "not-recorded.cc", currentSha256: hash }] },
        { ...manifest, files: Array(129).fill(manifest.files[0]) },
      ]) {
        writeFileSync(file, JSON.stringify(altered));
        expect(() => nativeReleaseComponents(temporary)).toThrow();
      }
      writeFileSync(file, " ".repeat(64 * 1024 + 1));
      expect(() => nativeReleaseComponents(temporary)).toThrow("Invalid native inventory file");
    } finally { rmSync(temporary, { recursive: true, force: true }); }
  });
});
