import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";
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

/** A locked build-input inventory, not a claim about what R8 retained in the APK. */
export function androidSbom(lockfile: string, version: string, apkSha256: string) {
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
        { name: "collie:scope", value: "Gradle locked releaseRuntimeClasspath build inputs" },
        { name: "collie:lockfile:sha256", value: createHash("sha256").update(lockfile).digest("hex") },
        { name: "collie:limitations", value: "Includes resolved platform modules and code removed by R8; excludes build/test tooling and bundled assets. Dependency edges and third-party licenses are not inferred from the lockfile." },
      ],
    },
    components: modules.map((m) => {
      const purl = `pkg:maven/${encodeURIComponent(m.group)}/${encodeURIComponent(m.name)}@${encodeURIComponent(m.version)}`;
      return { type: "library", "bom-ref": purl, group: m.group, name: m.name, version: m.version, purl };
    }),
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
    const bom = androidSbom(lockfile, version, digest);
    await mkdir(dirname(resolve(destination)), { recursive: true });
    await writeFile(destination, `${JSON.stringify(bom, null, 2)}\n`, { flag: "wx" });
    console.log(`Android SBOM: ${bom.components.length} locked release modules`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : "Android SBOM generation failed");
    process.exitCode = 1;
  }
}
