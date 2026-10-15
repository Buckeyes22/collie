import { HOST } from "../../host.ts";
// Test-only owning process. A restart must change the real Windows named-pipe server process,
// not just re-listen in the same process while its OS incarnation remains unchanged.
import net from "node:net";
import { existsSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

interface WireCapture { bytes: string; method?: string }
export interface WorktreeCapture { bytes: Buffer; method?: string }

function capturesAt(file: string): WireCapture[] {
  if (!existsSync(file)) return [];
  // SAFETY: only this owned child writes this fixed fixture log, as the exact DTO below.
  return JSON.parse(readFileSync(file, "utf8")) as WireCapture[];
}

export async function startWorktreeTestPeer(endpoint: string, directory: string) {
  const drop = join(directory, "drop-mutation-reply");
  const child = Bun.spawn([process.execPath, import.meta.path, endpoint, directory], {
    stdin: "ignore", stdout: "pipe", stderr: "pipe",
  });
  const trace = join(directory, `wire-captures-${child.pid}.json`);
  let ready = false;
  const startupDeadline = setTimeout(() => child.kill(), 3_000);
  try {
    const reader = child.stdout.getReader();
    try {
      const first = await reader.read();
      if (first.done || new TextDecoder().decode(first.value) !== "READY\n") throw new Error("owned worktree peer did not start");
      ready = true;
    } finally { reader.releaseLock(); }
  } finally {
    clearTimeout(startupDeadline);
    if (!ready) { child.kill(); await child.exited; }
  }
  return {
    pid: child.pid,
    captures: (): WorktreeCapture[] => capturesAt(trace).map((row) => ({ method: row.method, bytes: Buffer.from(row.bytes, "base64") })),
    dropReply: () => writeFileSync(drop, "drop"),
    retireListener: async () => {
      const request = join(directory, `retire-listener-${child.pid}`);
      const acknowledged = `${request}.ack`;
      writeFileSync(request, "retire");
      const deadline = Date.now() + 3_000;
      while (!existsSync(acknowledged) && Date.now() < deadline) await Bun.sleep(5);
      if (!existsSync(acknowledged)) throw new Error("owned peer listener did not retire");
      if (HOST.platform !== "win32" && existsSync(endpoint)) unlinkSync(endpoint);
    },
    close: async () => { if (child.exitCode === null) child.kill(); await child.exited; },
  };
}

if (import.meta.main) {
  const endpoint = process.argv[2]; const directory = process.argv[3];
  if (!endpoint || !directory) throw new Error("owned worktree peer arguments missing");
  const trace = join(directory, `wire-captures-${process.pid}.json`);
  const drop = join(directory, "drop-mutation-reply");
  const captures = capturesAt(trace);
  const persist = () => { writeFileSync(`${trace}.tmp`, JSON.stringify(captures)); renameSync(`${trace}.tmp`, trace); };
  const server = net.createServer((socket) => {
    socket.on("error", () => {});
    const capture: WireCapture = { bytes: "" }; captures.push(capture);
    let bytes = Buffer.alloc(0);
    socket.on("data", (chunk: Buffer) => {
      bytes = Buffer.concat([bytes, chunk]); capture.bytes = bytes.toString("base64");
      if (capture.method || !bytes.includes(10)) { persist(); return; }
      // SAFETY: only the production HerdrClient sends newline-delimited requests to this owned peer.
      const request = JSON.parse(bytes.toString().split("\n", 1)[0]!) as { id: string; method: string };
      capture.method = request.method; persist();
      if (existsSync(drop) && request.method.startsWith("worktree.")) { socket.destroy(); return; }
      const created = { workspace: { label: "topic" }, root_pane: { pane_id: "p", workspace_id: "w", tab_id: "t", cwd: "/repo-topic" } };
      const result = request.method === "ping" ? { type: "pong", version: "0.9.3", protocol: 22 }
        : request.method === "session.snapshot" ? { snapshot: { version: "0.9.3", protocol: 22, workspaces: [], tabs: [], panes: [] } }
        : request.method === "worktree.create" || request.method === "worktree.open" ? { ...created, already_open: false }
        : request.method === "worktree.remove" ? { type: "worktree_removed", workspace_id: "w", path: "/repo-topic", forced: false }
        : request.method === "pane.read" ? { read: { pane_id: "p", text: "$ ", truncated: false, revision: 1 } }
        : { type: "ok" };
      socket.end(`${JSON.stringify({ id: request.id, result })}\n`);
    });
  });
  await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(endpoint, resolve); });
  // Stop accepting new connections while keeping the already-held old connection alive. The
  // next real child can own this same pipe name with a genuinely different OS process witness.
  const retireRequest = join(directory, `retire-listener-${process.pid}`);
  const timer = setInterval(() => {
    if (!existsSync(retireRequest)) return;
    clearInterval(timer); server.close();
    setImmediate(() => writeFileSync(`${retireRequest}.ack`, "retired"));
  }, 5);
  process.stdout.write("READY\n");
}
