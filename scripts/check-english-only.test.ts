import { describe, expect, test } from "bun:test";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const root = join(import.meta.dir, "..");

const textExtensions = new Set([
  ".css", ".gradle", ".html", ".java", ".js", ".json", ".jsx", ".kts", ".md", ".mjs",
  ".properties", ".sh", ".svg", ".toml", ".ts", ".tsx", ".txt", ".xml", ".yaml", ".yml",
]);

function projectTextFiles(directory: string): string[] {
  const ignoredDirectories = new Set([".git", ".gradle", ".idea", "build", "dist", "node_modules"]);
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return ignoredDirectories.has(entry.name) ? [] : projectTextFiles(path);
    }
    if (!entry.isFile() || statSync(path).size > 2_000_000) return [];
    const extension = entry.name.includes(".") ? `.${entry.name.split(".").at(-1)}` : "";
    return textExtensions.has(extension) ? [path] : [];
  });
}

describe("English-only interface contract", () => {
  test("the web has exactly one English message catalog and no language selector", () => {
    const catalogs = readdirSync(join(root, "web/src/lib/i18n/messages"))
      .filter((name) => name.endsWith(".ts"))
      .toSorted();
    expect(catalogs).toEqual(["en.ts"]);
    expect(existsSync(join(root, "web/src/components/language-control.tsx"))).toBe(false);
    expect(existsSync(join(root, "web/src/components/language-control.test.tsx"))).toBe(false);
  });

  test("Android has no localized resource overlays or locale selector configuration", () => {
    const resources = join(root, "android/app/src/main/res");
    const localeDirectories = readdirSync(resources)
      .filter((name) => /^values-[a-z]{2}(?:-r[A-Z]{2})?$/.test(name))
      .toSorted();
    expect(localeDirectories).toEqual([]);

    const manifest = readFileSync(join(root, "android/app/src/main/AndroidManifest.xml"), "utf8");
    const build = readFileSync(join(root, "android/app/build.gradle.kts"), "utf8");
    const preferences = readFileSync(
      join(root, "android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt"),
      "utf8",
    );
    expect(manifest).not.toContain("localeConfig");
    expect(build).toContain('localeFilters += "en"');
    expect(preferences).not.toContain("enum class Language");
    expect(existsSync(join(root, "android/app/src/main/res/xml/locales_config.xml"))).toBe(false);
  });

  test("translation-generation artifacts are absent", () => {
    expect(existsSync(join(root, "scripts/generate-android-locales.ts"))).toBe(false);
    expect(existsSync(join(root, "scripts/android-locales.machine.json"))).toBe(false);
  });

  test("project text contains no CJK or Hangul language characters", () => {
    const violations = projectTextFiles(root).flatMap((path) => {
      const match = readFileSync(path, "utf8").match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u);
      return match === null ? [] : [`${path.slice(root.length + 1)}: ${match[0]}`];
    });
    expect(violations).toEqual([]);
  });
});
