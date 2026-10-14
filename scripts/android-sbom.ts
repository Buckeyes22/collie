import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";

/** The locked releaseRuntimeClasspath modules, sorted by `group:name`. */
export function releaseModules(lockfile: string) {
  const modules = new Map<string, { group: string; name: string; version: string }>();
  for (const line of lockfile.split(/\r?\n/)) {
    if (!line.trim() || line.startsWith("#")) continue;
    const [coordinate, ...rest] = line.split("=");
    if (rest.length !== 1) throw new Error("Malformed Gradle lock entry");
    if (!rest[0]!.split(",").includes("releaseRuntimeClasspath")) continue;
    if (coordinate === "empty") throw new Error("Release runtime classpath is empty");
    const parts = coordinate!.split(":");
    if (parts.length !== 3 || parts.some((p) => !p || /\s/.test(p))) throw new Error("Invalid Maven coordinate");
    const group = parts[0]!;
    const name = parts[1]!;
    const dependencyVersion = parts[2]!;
    const key = `${group}:${name}`;
    if (modules.has(key)) throw new Error(`Duplicate release module: ${key}`);
    modules.set(key, { group, name, version: dependencyVersion });
  }
  if (modules.size === 0) throw new Error("No locked release runtime dependencies");
  return [...modules.values()].toSorted((a, b) => `${a.group}:${a.name}`.localeCompare(`${b.group}:${b.name}`));
}

export interface NativeReleaseComponent {
  type: "library";
  "bom-ref": string;
  group: string;
  name: string;
  version: string;
  purl: string;
  licenses: { license: { id: "MIT"; url: string; text: { contentType: "text/plain"; encoding: "base64"; content: string } } }[];
  externalReferences: { type: "vcs" | "distribution"; url: string }[];
  properties: { name: string; value: string }[];
}

interface NativeManifest {
  repository: string;
  commit: string;
  tag: string;
  archiveSha256: string;
  files: { file: string; sha256: string }[];
  localPatches: { file: string; currentSha256: string }[];
}

const NATIVE_SOURCES = [
  { name: "woff2", version: "1.0.2", commit: "1bccf208bca986e53a647dfe4811322adb06ecf8" },
  { name: "brotli", version: "1.2.0", commit: "028fb5a23661f123017c060daa546b55cf4bde29" },
];
const SHA256 = /^[a-f0-9]{64}$/;
const MAX_NATIVE_MANIFEST_BYTES = 64 * 1024;
const MAX_NATIVE_FILE_BYTES = 8 * 1024 * 1024;
const MAX_NATIVE_TOTAL_BYTES = 32 * 1024 * 1024;
const MAX_NATIVE_FILES = 128;

function nativePathValid(file: string): boolean {
  return file.length <= 256 && file.split("/").length <= 8 && /^(?:[A-Za-z0-9_.-]+\/)*[A-Za-z0-9_.-]+$/.test(file) &&
    file.split("/").every((part) => part !== "." && part !== "..");
}

function boundedNativeBytes(file: string, limit: number): Buffer {
  const info = lstatSync(file);
  if (!info.isFile() || info.size > limit) throw new Error(`Invalid native inventory file: ${file}`);
  const bytes = readFileSync(file);
  if (bytes.length !== info.size || bytes.length > limit) throw new Error(`Changed native inventory file: ${file}`);
  return bytes;
}

/** Verifies the retained decoder subsets against their pinned manifests, not entire upstream trees. */
export function nativeReleaseComponents(thirdPartyRoot = resolve(import.meta.dir, "../android/app/src/main/cpp/third_party")): NativeReleaseComponent[] {
  if (!lstatSync(thirdPartyRoot).isDirectory()) throw new Error("Native third-party root must be a real directory");
  let totalBytes = 0;
  return NATIVE_SOURCES.map((source) => {
    const directory = resolve(thirdPartyRoot, source.name);
    if (!lstatSync(directory).isDirectory()) throw new Error(`Native source root must be a real directory: ${source.name}`);
    const rawManifest = boundedNativeBytes(resolve(directory, "UPSTREAM.json"), MAX_NATIVE_MANIFEST_BYTES);
    // Repository-owned provenance is still checked before any manifest path is read.
    const manifest: NativeManifest = JSON.parse(rawManifest.toString("utf8"));
    if (manifest.repository !== `https://github.com/google/${source.name}` || manifest.commit !== source.commit ||
        manifest.tag !== `v${source.version}` || !SHA256.test(manifest.archiveSha256) ||
        !Array.isArray(manifest.files) || manifest.files.length < 1 || manifest.files.length > MAX_NATIVE_FILES ||
        !Array.isArray(manifest.localPatches) || manifest.localPatches.length > manifest.files.length) {
      throw new Error(`Invalid pinned native manifest: ${source.name}`);
    }
    const files = new Map<string, string>();
    for (const entry of manifest.files) {
      if (!nativePathValid(entry.file) || entry.file === "UPSTREAM.json" || !SHA256.test(entry.sha256) || files.has(entry.file)) {
        throw new Error(`Invalid native source inventory: ${source.name}`);
      }
      files.set(entry.file, entry.sha256);
    }
    const patches = new Map<string, string>();
    for (const patch of manifest.localPatches) {
      if (!files.has(patch.file) || patch.file === "LICENSE" || patches.has(patch.file) || !SHA256.test(patch.currentSha256)) {
        throw new Error(`Invalid native patch inventory: ${source.name}`);
      }
      patches.set(patch.file, patch.currentSha256);
    }
    const found = new Set<string>();
    function visit(relativeDirectory: string) {
      for (const entry of readdirSync(resolve(directory, relativeDirectory), { withFileTypes: true })) {
        const relativeFile = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
        if (entry.isDirectory()) {
          if (![...files.keys()].some((file) => file.startsWith(`${relativeFile}/`))) {
            throw new Error(`Unlisted native source directory: ${source.name}/${relativeFile}`);
          }
          visit(relativeFile);
        }
        else if (!entry.isFile() || (relativeFile !== "UPSTREAM.json" && !files.has(relativeFile))) {
          throw new Error(`Unlisted or linked native source: ${source.name}/${relativeFile}`);
        } else if (relativeFile !== "UPSTREAM.json") found.add(relativeFile);
      }
    }
    visit("");
    if (found.size !== files.size) throw new Error(`Incomplete native source inventory: ${source.name}`);
    const inventory = [...files].toSorted(([a], [b]) => a.localeCompare(b)).map(([file, upstreamSha256]) => {
      const bytes = boundedNativeBytes(resolve(directory, file), MAX_NATIVE_FILE_BYTES);
      totalBytes += bytes.length;
      if (totalBytes > MAX_NATIVE_TOTAL_BYTES) throw new Error("Native source inventory exceeds size bound");
      const currentSha256 = createHash("sha256").update(bytes).digest("hex");
      if (currentSha256 !== (patches.get(file) ?? upstreamSha256)) throw new Error(`Native source hash mismatch: ${source.name}/${file}`);
      return { file, upstreamSha256, currentSha256 };
    });
    if (!files.has("LICENSE")) throw new Error(`Missing native license inventory: ${source.name}`);
    const license = boundedNativeBytes(resolve(directory, "LICENSE"), 16 * 1024);
    const purl = `pkg:github/google/${source.name}@${source.commit}`;
    return {
      type: "library", "bom-ref": purl, group: "google", name: source.name, version: source.version, purl,
      licenses: [{ license: { id: "MIT", url: `${manifest.repository}/blob/${source.commit}/LICENSE`,
        text: { contentType: "text/plain", encoding: "base64", content: license.toString("base64") } } }],
      externalReferences: [
        { type: "vcs", url: `${manifest.repository}/tree/${source.commit}` },
        { type: "distribution", url: `https://codeload.github.com/google/${source.name}/tar.gz/${source.commit}` },
      ],
      properties: [
        { name: "collie:native:scope", value: "Vendored decoder source subset; local patches are recorded per file" },
        { name: "collie:native:manifest:sha256", value: createHash("sha256").update(rawManifest).digest("hex") },
        { name: "collie:native:upstreamArchive:recordedSha256", value: manifest.archiveSha256 },
        { name: "collie:native:retainedFiles", value: JSON.stringify(inventory) },
      ],
    };
  });
}

/** A locked build-input inventory, not a claim about what R8 retained in the APK. */
export function androidSbom(lockfile: string, version: string, apkSha256: string, nativeComponents: NativeReleaseComponent[] = []) {
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?$/.test(version)) throw new Error("Invalid Android version");
  if (!/^[a-f0-9]{64}$/.test(apkSha256)) throw new Error("Invalid APK SHA-256");
  const modules = releaseModules(lockfile);
  const rootRef = `collie-android:${version}`;
  return {
    $schema: "http://cyclonedx.org/schema/bom-1.6.schema.json",
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    metadata: {
      component: {
        type: "application",
        "bom-ref": rootRef,
        name: "com.lateapex.collie",
        version,
        hashes: [{ alg: "SHA-256", content: apkSha256 }],
        licenses: [{ license: { id: "MIT" } }],
      },
      properties: [
        { name: "collie:scope", value: nativeComponents.length ? "Gradle locked releaseRuntimeClasspath and verified vendored native decoder build inputs" : "Gradle locked releaseRuntimeClasspath build inputs" },
        { name: "collie:lockfile:sha256", value: createHash("sha256").update(lockfile).digest("hex") },
        { name: "collie:limitations", value: "Includes resolved platform modules and code removed by R8, plus supplied verified native source subsets. Excludes build/test tooling, other bundled assets and binary-level retention/link analysis. Dependency edges and Maven licenses are not inferred from the lockfile; native archive hashes are recorded provenance, not a fresh upstream download verification." },
      ],
    },
    components: [...modules.map((m) => {
      const purl = `pkg:maven/${encodeURIComponent(m.group)}/${encodeURIComponent(m.name)}@${encodeURIComponent(m.version)}`;
      return { type: "library", "bom-ref": purl, group: m.group, name: m.name, version: m.version, purl };
    }), ...nativeComponents],
    // The lockfile gives the resolved inventory but no edges or bundled-asset inventory.
    compositions: [{ aggregate: "incomplete", assemblies: [rootRef] }],
  };
}

if (import.meta.main) {
  try {
    const [apk, version, destination] = process.argv.slice(2);
    if (!apk || !version || !destination || process.argv.length !== 5) {
      throw new Error("Usage: bun scripts/android-sbom.ts APK VERSION OUTPUT");
    }
    const root = resolve(import.meta.dir, "..");
    const lockfile = await readFile(resolve(root, "android/app/gradle.lockfile"), "utf8");
    const digest = createHash("sha256").update(await readFile(apk)).digest("hex");
    const bom = androidSbom(lockfile, version, digest, nativeReleaseComponents());
    await mkdir(dirname(resolve(destination)), { recursive: true });
    await writeFile(destination, `${JSON.stringify(bom, null, 2)}\n`, { flag: "wx" });
    console.log(`Android SBOM: ${bom.components.length} release build-input components`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : "Android SBOM generation failed");
    process.exitCode = 1;
  }
}
