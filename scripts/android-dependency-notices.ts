// Writes res/raw/dependency_notices.txt from the locked release classpath. `--check` diffs instead.
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { releaseModules } from "./android-sbom";

export interface LicenseMap { rules: { prefix: string; license: string; source: string; extra?: string }[] }

export function dependencyNotices(lockfile: string, map: LicenseMap, apache: string): string {
  const rows: string[] = [];
  const extras = new Set<string>();
  for (const m of releaseModules(lockfile)) {
    const key = `${m.group}:${m.name}`;
    const rule = map.rules
      .filter((r) => key.startsWith(r.prefix))
      .toSorted((a, b) => b.prefix.length - a.prefix.length)[0];
    if (!rule) throw new Error(`${key} has no license mapping`);
    rows.push(`${key}:${m.version} — ${rule.license} — ${rule.source}`);
    if (rule.extra) extras.add(rule.extra);
  }
  return [
    "RUNTIME DEPENDENCIES",
    "Every library in the release runtime classpath, from android/app/gradle.lockfile.",
    "",
    ...rows,
    "",
    ...extras,
    "",
    "APACHE LICENSE 2.0",
    apache.trimEnd(),
    "",
  ].join("\n");
}

if (import.meta.main) {
  const root = resolve(import.meta.dir, "..");
  const out = resolve(root, "android/app/src/main/res/raw/dependency_notices.txt");
  const map: LicenseMap = JSON.parse(readFileSync(resolve(root, "android/dependency-licenses.json"), "utf8"));
  const text = dependencyNotices(
    readFileSync(resolve(root, "android/app/gradle.lockfile"), "utf8"),
    map,
    readFileSync(resolve(root, "android/licenses/Apache-2.0.txt"), "utf8"),
  );
  if (process.argv.includes("--check")) {
    if (readFileSync(out, "utf8") !== text) {
      console.error("dependency_notices.txt is stale: run bun scripts/android-dependency-notices.ts");
      process.exit(1);
    }
  } else writeFileSync(out, text);
}
