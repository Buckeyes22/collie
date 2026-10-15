import { expect, test } from "bun:test";
import { realpath, mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { JsonObject } from "../json.ts";
import { listPluginConfigFiles, readPluginConfigFile, writePluginConfigFile, redactPluginConfigContent, restorePluginConfigSecrets, PLUGIN_CONFIG_REDACTED } from "./plugin-config.ts";

test("plugin config edits are relative, revision-bound, atomic, and leave a private backup", async () => {
  const parent = await realpath(await mkdtemp(join(tmpdir(), "collie-plugin-config-")));
  const root = join(parent, "config");
  try {
    await mkdir(root);
    await writeFile(join(root, "settings.json"), '{"enabled":false}\n', { mode: 0o600 });
    const inventory = await listPluginConfigFiles(root);
    expect(inventory.kind).toBe("available");
    if (inventory.kind !== "available") return;
    const original = await readPluginConfigFile(root, "settings.json");
    expect(original.kind).toBe("available");
    if (original.kind !== "available") return;
    const written = await writePluginConfigFile(root, "settings.json", original.value.revision, '{"enabled":true}\n');
    expect(written).toMatchObject({ kind: "available", value: { backupCreated: true } });
    expect(await readFile(join(root, "settings.json"), "utf8")).toBe('{"enabled":true}\n');
    expect((await listPluginConfigFiles(root)).kind).toBe("available");
    const stale = await writePluginConfigFile(root, "settings.json", original.value.revision, '{"enabled":false}\n');
    expect(stale.kind).toBe("refused");
    const files = await (await import("node:fs/promises")).readdir(root);
    expect(files.filter((name) => name.includes("collie-backup-")).length).toBe(1);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("plugin config discovery and editing refuse symlinks and path traversal", async () => {
  const parent = await realpath(await mkdtemp(join(tmpdir(), "collie-plugin-config-")));
  const root = join(parent, "config");
  try {
    await mkdir(root);
    await writeFile(join(parent, "outside.json"), '{"outside":true}');
    await symlink(join(parent, "outside.json"), join(root, "linked.json"));
    expect((await listPluginConfigFiles(root)).kind).toBe("unavailable");
    expect((await readPluginConfigFile(root, "../outside.json")).kind).toBe("unavailable");
    expect((await writePluginConfigFile(root, "../outside.json", "a".repeat(64), "{}" )).kind).not.toBe("available");
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("plugin config responses redact secret-shaped values and preserve untouched secrets during edits", () => {
  const original = '{"api_key":"real-secret","nested":{"password":"hunter2"},"label":"safe"}';
  const displayed = redactPluginConfigContent(original);
  expect(displayed).not.toContain("real-secret");
  expect(displayed).not.toContain("hunter2");
  expect(displayed).toContain(PLUGIN_CONFIG_REDACTED);
  // SAFETY: `displayed` is produced by the JSON config redactor and always serializes a JSON object for this fixture.
  const edited = JSON.parse(displayed) as JsonObject;
  edited.label = "changed";
  // SAFETY: This restore fixture starts from the exact object shape asserted below.
  const restored = JSON.parse(restorePluginConfigSecrets(JSON.stringify(edited), original)) as { api_key: string; nested: { password: string }; label: string };
  expect(restored).toEqual({ api_key: "real-secret", nested: { password: "hunter2" }, label: "changed" });
});


test("config redaction and restoration preserve nested own reserved keys", () => {
  const original = '{"__proto__":{"api_key":"fixture-secret","label":"own-data"},"nested":{"constructor":{"prototype":{"password":"fixture-password"}}},"label":"before"}';
  const displayed = redactPluginConfigContent(original);
  expect(displayed).toContain('"__proto__"');
  expect(displayed).toContain('"constructor"');
  expect(displayed).not.toContain("fixture-secret");
  expect(displayed).not.toContain("fixture-password");
  expect(restorePluginConfigSecrets(displayed, original)).toBe(original);
});
