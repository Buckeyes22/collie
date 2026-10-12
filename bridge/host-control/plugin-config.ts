import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir, realpath, rename, unlink } from "node:fs/promises";
import { basename, extname, isAbsolute, relative, resolve, sep } from "node:path";
import { redactPluginConfigDocument, restorePluginConfigDocument } from "./request.ts";

export { PLUGIN_CONFIG_REDACTED } from "./request.ts";
export const redactPluginConfigContent = redactPluginConfigDocument;
export const restorePluginConfigSecrets = restorePluginConfigDocument;

export interface PluginConfigFile { readonly id: string; readonly bytes: number; readonly revision: string }
export interface PluginConfigContent extends PluginConfigFile { readonly content: string }
export type PluginConfigOutcome<T> = { readonly kind: "available"; readonly value: T } | { readonly kind: "refused"; readonly reason: string } | { readonly kind: "unavailable"; readonly reason: string };

const MAX_FILES = 256;
const MAX_FILE_BYTES = 64 * 1024;
const MAX_TOTAL_BYTES = 512 * 1024;
const SAFE_FILE = /^[A-Za-z0-9._/-]{1,512}$/u;
const sha = (value: Uint8Array | string): string => createHash("sha256").update(value).digest("hex");

async function safeRoot(root: string): Promise<string> {
  if (!isAbsolute(root) || root.length > 4096 || root.includes("\0")) throw new Error("invalid owner-derived plugin config directory");
  const resolved = resolve(root);
  const actual = await realpath(resolved);
  if (actual !== resolved) throw new Error("plugin config directory resolves through a symbolic link");
  const stat = await lstat(actual);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error("plugin config root is not a regular directory");
  return actual;
}

async function safeFilePath(root: string, id: string): Promise<string> {
  const target = safeRelative(root, id);
  const parts = id.split("/");
  let parent = root;
  for (const part of parts.slice(0, -1)) {
    parent = resolve(parent, part);
    const stat = await lstat(parent);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error("plugin config path contains a non-directory or symbolic link");
  }
  return target;
}

async function readRegularFile(path: string): Promise<Buffer> {
  const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > MAX_FILE_BYTES) throw new Error("plugin config file is not a bounded regular file");
    return await handle.readFile();
  } finally { await handle.close(); }
}

function safeRelative(root: string, id: string): string {
  if (!SAFE_FILE.test(id) || id.startsWith("/") || id.includes("\\") || id.split("/").some((part) => part === "" || part === "." || part === "..") || extname(id).toLowerCase() !== ".json") throw new Error("only bounded relative JSON config files are supported");
  const target = resolve(root, id);
  const rel = relative(root, target);
  if (!rel || rel === ".." || rel.startsWith(`..${sep}`) || isAbsolute(rel) || basename(target) !== id.split("/").at(-1)) throw new Error("config file escapes plugin config directory");
  return target;
}

async function walk(root: string, dir = root, depth = 0, found: PluginConfigFile[] = []): Promise<PluginConfigFile[]> {
  if (depth > 4 || found.length > MAX_FILES) throw new Error("plugin config inventory exceeds its bounds");
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === "." || entry.name === ".." || entry.isSymbolicLink()) throw new Error("plugin config contains an unsupported symbolic link");
    const path = resolve(dir, entry.name);
    const rel = relative(root, path).split(sep).join("/");
    if (entry.isDirectory()) { await walk(root, path, depth + 1, found); continue; }
    if (!entry.isFile() || extname(entry.name).toLowerCase() !== ".json") continue;
    const stat = await lstat(path);
    if (stat.isSymbolicLink() || stat.size > MAX_FILE_BYTES) throw new Error("plugin config file exceeds its safe read limit");
    found.push({ id: rel, bytes: stat.size, revision: sha(await readRegularFile(path)) });
    if (found.length > MAX_FILES) throw new Error("plugin config inventory exceeds its bounds");
  }
  return found;
}

export async function listPluginConfigFiles(ownerDirectory: string): Promise<PluginConfigOutcome<readonly PluginConfigFile[]>> {
  try {
    const root = await safeRoot(ownerDirectory);
    const files = await walk(root);
    const bytes = files.reduce((total, file) => total + file.bytes, 0);
    if (bytes > MAX_TOTAL_BYTES) return { kind: "refused", reason: "Plugin config inventory exceeds its total size limit." };
    return { kind: "available", value: files.toSorted((a, b) => a.id.localeCompare(b.id)) };
  } catch (error) { return { kind: "unavailable", reason: error instanceof Error ? error.message : "Plugin config inventory is unavailable." }; }
}

export async function readPluginConfigFile(ownerDirectory: string, fileId: string): Promise<PluginConfigOutcome<PluginConfigContent>> {
  try {
    const root = await safeRoot(ownerDirectory);
    const path = await safeFilePath(root, fileId);
    const stat = await lstat(path);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_FILE_BYTES) return { kind: "refused", reason: "Plugin config file is not a bounded regular JSON file." };
    const bytes = await readRegularFile(path);
    const content = bytes.toString("utf8");
    JSON.parse(content);
    return { kind: "available", value: { id: fileId, bytes: Buffer.byteLength(content), revision: sha(content), content } };
  } catch (error) { return { kind: "unavailable", reason: error instanceof Error ? error.message : "Plugin config file is unavailable." }; }
}

/** Atomically replace one owner-derived regular JSON file after the plan's exact revision check. */
export async function writePluginConfigFile(ownerDirectory: string, fileId: string, expectedRevision: string, content: string): Promise<PluginConfigOutcome<{ readonly revision: string; readonly backupCreated: boolean }>> {
  if (!/^[a-f0-9]{64}$/u.test(expectedRevision) || content.length > MAX_FILE_BYTES || Buffer.byteLength(content) > MAX_FILE_BYTES) return { kind: "refused", reason: "Plugin config edit exceeds its binding or size limit." };
  try { JSON.parse(content); } catch { return { kind: "refused", reason: "Plugin config edit is not valid JSON." }; }
  let temp: string | null = null;
  try {
    const root = await safeRoot(ownerDirectory);
    const path = await safeFilePath(root, fileId);
    const before = await lstat(path);
    if (!before.isFile() || before.isSymbolicLink() || before.size > MAX_FILE_BYTES) return { kind: "refused", reason: "Plugin config target is not a bounded regular JSON file." };
    const original = await readRegularFile(path);
    if (sha(original) !== expectedRevision) return { kind: "refused", reason: "Plugin config changed after its edit plan was created." };
    // Re-open with no-follow where supported immediately before writing, then re-check the exact bytes.
    const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
    try {
      const current = await handle.readFile();
      if (sha(current) !== expectedRevision) return { kind: "refused", reason: "Plugin config changed during the edit." };
    } finally { await handle.close(); }
    const backup = `${path}.collie-backup-${randomUUID()}`;
    const backupHandle = await open(backup, "wx", before.mode & 0o777);
    try { await backupHandle.writeFile(original); await backupHandle.sync(); } finally { await backupHandle.close(); }
    const backupStat = await lstat(backup);
    if (!backupStat.isFile() || backupStat.isSymbolicLink()) { await unlink(backup).catch(() => {}); return { kind: "refused", reason: "Config backup could not be verified." }; }
    temp = `${path}.collie-tmp-${randomUUID()}`;
    const out = await open(temp, "wx", before.mode & 0o777);
    try { await out.writeFile(content); await out.sync(); } finally { await out.close(); }
    const last = await readRegularFile(path);
    if (sha(last) !== expectedRevision) { await unlink(temp).catch(() => {}); temp = null; return { kind: "refused", reason: "Plugin config changed before the edit was committed." }; }
    await rename(temp, path); temp = null;
    return { kind: "available", value: { revision: sha(content), backupCreated: true } };
  } catch (error) {
    if (temp) await unlink(temp).catch(() => {});
    return { kind: "unavailable", reason: error instanceof Error ? error.message : "Plugin config edit failed." };
  }
}
