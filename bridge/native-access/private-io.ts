import { closeSync, constants, fsyncSync, fstatSync, lstatSync, mkdirSync, openSync, readSync, renameSync, rmdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { randomUUID } from "node:crypto";
import { HOST } from "../host.ts";
import { secretFileVerdict } from "../owner-only.ts";
import { strictJson } from "./jose.ts";
import type { JsonValue } from "../json.ts";

export interface PrivateJsonIo { read(): JsonValue | null; write(value: JsonValue): void; exclusive<T>(operation: () => T): T }
function validate(path: string, directory: boolean): void {
    const stat = lstatSync(path);
    if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile() || stat.nlink !== 1)) throw new Error("native.private_file");
    if (HOST.platform === "win32") { const verdict = secretFileVerdict(path, { repair: false }); if (!verdict.ok || verdict.warning !== null) throw new Error("native.private_acl"); }
    else if ((stat.mode & 0o077) !== 0 || process.getuid && stat.uid !== process.getuid()) throw new Error("native.private_owner");
  }
/** Operator-declared fixed file only. No caller JSON becomes a filesystem path. */
export function privateJsonIo(file: string, maxBytes = 4 * 1024 * 1024): PrivateJsonIo {
  function parent(): void { const directory = dirname(file); mkdirSync(directory, { recursive: true, mode: 0o700 }); validate(directory, true); }
  return {
    read() {
      let fd: number;
      try { validate(dirname(file), true); validate(file, false); fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW); }
      catch (error) { if (error instanceof Error && "code" in error && error.code === "ENOENT") return null; throw error; }
      try { const stat = fstatSync(fd); if (!stat.isFile() || stat.size > maxBytes || stat.nlink !== 1) throw new Error("native.private_bound"); const bytes = Buffer.alloc(Math.min(maxBytes + 1, stat.size + 1));
        let total = 0;
        while (total < bytes.length) { const count = readSync(fd, bytes, total, bytes.length - total, total); if (count === 0) break; total += count; }
        if (total > maxBytes || total > stat.size) throw new Error("native.private_bound");
        return strictJson(new TextDecoder("utf8", { fatal: true }).decode(bytes.subarray(0, total))); }
      finally { closeSync(fd); }
    },
    write(value) {
      parent(); const bytes = Buffer.from(JSON.stringify(value)); if (bytes.length > maxBytes) throw new Error("native.private_bound");
      const dir = dirname(file);
      const tmp = join(dir, `.native-${randomUUID()}.tmp`);
      const fd = openSync(tmp, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600);
      try { writeFileSync(fd, bytes); fsyncSync(fd); validate(tmp, false); closeSync(fd); renameSync(tmp, file);
      if (HOST.platform !== "win32") { const directory = openSync(dirname(file), constants.O_RDONLY | constants.O_DIRECTORY); try { fsyncSync(directory); } finally { closeSync(directory); } } }
      catch (error) { try { closeSync(fd); } catch { /* Already closed before rename. */ } rmSync(tmp, { force: true }); throw error; }
    },
    exclusive(operation) {
      parent(); const lock = `${file}.lock`; mkdirSync(lock, { mode: 0o700 });
      try { return operation(); } finally { rmdirSync(lock); }
    },
  };
}
