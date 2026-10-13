/** Monitoring files are fixed children of the operator's private state root. */
import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import { dirname } from "node:path";
import { HOST } from "./host.ts";
import { secretFileVerdict } from "./owner-only.ts";

export async function assertMonitoringPrivateDirectory(path: string): Promise<void> {
  const stat = await lstat(path);
  if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error("monitoring.private_directory");
  if (HOST.platform === "win32") {
    const verdict = secretFileVerdict(path, { repair: false });
    if (!verdict.ok || verdict.warning !== null) throw new Error("monitoring.private_acl");
  } else if ((stat.mode & 0o077) !== 0 || (process.getuid !== undefined && stat.uid !== process.getuid())) {
    throw new Error("monitoring.private_owner");
  }
}

export async function readMonitoringPrivateFile(path: string, maxBytes: number): Promise<Buffer> {
  await assertMonitoringPrivateDirectory(dirname(path));
  const before = await lstat(path);
  if (before.isSymbolicLink() || !before.isFile() || before.nlink !== 1 || before.size > maxBytes) throw new Error("monitoring.private_file");
  if (HOST.platform === "win32") {
    const verdict = secretFileVerdict(path, { repair: false });
    if (!verdict.ok || verdict.warning !== null) throw new Error("monitoring.private_acl");
  } else if ((before.mode & 0o077) !== 0 || (process.getuid !== undefined && before.uid !== process.getuid())) {
    throw new Error("monitoring.private_owner");
  }
  const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || opened.nlink !== 1 || opened.size !== before.size || opened.dev !== before.dev || opened.ino !== before.ino) throw new Error("monitoring.private_file_changed");
    // A growing file cannot allocate past the original checked size or the declared cap.
    const bytes = Buffer.alloc(Math.min(maxBytes + 1, opened.size + 1));
    let total = 0;
    while (total < bytes.length) {
      const { bytesRead } = await handle.read(bytes, total, bytes.length - total, total);
      if (bytesRead === 0) break;
      total += bytesRead;
    }
    const after = await handle.stat();
    if (total !== opened.size || total > maxBytes || after.size !== opened.size || after.mtimeMs !== opened.mtimeMs || after.ctimeMs !== opened.ctimeMs || after.nlink !== 1) throw new Error("monitoring.private_file_changed");
    return bytes.subarray(0, total);
  } finally { await handle.close(); }
}
