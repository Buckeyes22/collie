import { afterEach, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { toPipeName } from "../../dial.ts";
import type { JsonObject, JsonValue } from "../../json.ts";
import { HerdrClient, MAX_PANE_REPLY_CHARS, WORKTREE_TIMEOUT_MS } from "./client.ts";

// The per-call budget (ADR 0089). Every Herdr call gets the client's own budget, 5 s in production,
// except the two worktree calls, which run `git worktree add` before they answer and get
// WORKTREE_TIMEOUT_MS. Proved against a real Unix socket (a named pipe on Windows) that answers late: a call on the client's
// budget gives up, a worktree call on the same client waits and gets the reply.

const REPLY_DELAY_MS = 150;
const CLIENT_BUDGET_MS = 40;

let dir: string | null = null;
let server: net.Server | null = null;
let counter = 0;
/** The last request line the stand-in read, parsed. */
let lastRequest: { method: string; params: JsonObject } | null = null;

afterEach(() => {
  server?.close();
  server = null;
  lastRequest = null;
  if (dir !== null) rmSync(dir, { recursive: true, force: true });
  dir = null;
});

/**
 * A one-shot Herdr stand-in: reads one request line, answers `result` after {@link REPLY_DELAY_MS}.
 *
 * It listens through node:net, which opens an AF_UNIX socket on POSIX and a named pipe on Windows,
 * so the same test runs on both. Windows has no socket file: the client dials the pipe named for
 * the socket path (`toPipeName`), so the stand-in listens on that name and the path is a plain tag.
 */
async function lateHerdr(result: JsonValue, rawReply?: (id: string) => string | Buffer): Promise<string> {
  let path: string;
  if (process.platform === "win32") {
    path = `collie-herdr-client-${process.pid}-${++counter}`;
  } else {
    dir = mkdtempSync(join(tmpdir(), "collie-herdr-client-"));
    path = join(dir, "herdr.sock");
  }
  server = net.createServer((conn) => {
    let buf = "";
    conn.on("error", () => {
      // The client gave up and closed first; nothing to answer.
    });
    conn.on("data", (chunk) => {
      buf += chunk.toString();
      const nl = buf.indexOf("\n");
      if (nl < 0) return;
      // SAFETY: the client under test writes exactly one JSON request line per connection.
      const req = JSON.parse(buf.slice(0, nl)) as { id: string; method: string; params: JsonObject };
      lastRequest = { method: req.method, params: req.params };
      buf = "";
      setTimeout(() => {
        if (conn.destroyed) return;
        const reply = rawReply ? rawReply(req.id) : `${JSON.stringify({ id: req.id, result })}\n`;
        const bytes = Buffer.isBuffer(reply) ? reply : Buffer.from(reply);
        if (!rawReply || bytes.length <= 4096) {
          conn.end(bytes);
          return;
        }
        let offset = 0;
        const writeNext = () => {
          if (conn.destroyed) return;
          const end = Math.min(offset + 1024, bytes.length);
          conn.write(bytes.subarray(offset, end));
          offset = end;
          if (offset < bytes.length) setTimeout(writeNext, 1);
          else conn.end();
        };
        writeNext();
      }, REPLY_DELAY_MS);
    });
  });
  const listening = server;
  await new Promise<void>((resolve, reject) => {
    listening.once("error", reject);
    listening.listen(process.platform === "win32" ? toPipeName(path) : path, resolve);
  });
  return path;
}

const PANE = { pane_id: "w9:p1", workspace_id: "w9", tab_id: "w9:t1", cwd: "/repo/.worktrees/x" };

describe("HerdrClient per-call timeout", () => {
  test("a worktree call gets a budget far above the default", () => {
    expect(WORKTREE_TIMEOUT_MS).toBe(60_000);
  });

  test("an ordinary call gives up at the client's own budget", async () => {
    const client = new HerdrClient(await lateHerdr({ worktrees: [] }), CLIENT_BUDGET_MS);
    await expect(client.listWorktrees("/repo")).rejects.toThrow(
      `herdr worktree.list: timed out after ${CLIENT_BUDGET_MS}ms`,
    );
  });

  test("worktree.create on the same client waits past that budget and gets the reply", async () => {
    const client = new HerdrClient(
      await lateHerdr({ workspace: { label: "x" }, root_pane: PANE }),
      CLIENT_BUDGET_MS,
    );
    const created = await client.createWorktree({ cwd: "/repo", branch: "worktree/x" });
    expect(created.paneId).toBe("w9:p1");
    expect(created.cwd).toBe("/repo/.worktrees/x");
  });

  test("worktree.open on the same client waits past that budget too", async () => {
    const client = new HerdrClient(
      await lateHerdr({ workspace: { label: "x" }, root_pane: PANE, already_open: true }),
      CLIENT_BUDGET_MS,
    );
    const opened = await client.openWorktree({ cwd: "/repo", path: "/repo/.worktrees/x" });
    expect(opened.alreadyOpen).toBe(true);
  });

  test("worktree.create sends `base` when given, between branch and focus", async () => {
    const client = new HerdrClient(await lateHerdr({ workspace: { label: "x" }, root_pane: PANE }), 1_000);
    await client.createWorktree({ cwd: "/repo", branch: "worktree/x", base: "main" });
    expect(lastRequest?.method).toBe("worktree.create");
    expect(lastRequest?.params).toEqual({ cwd: "/repo", branch: "worktree/x", base: "main", focus: false });
  });

  test("worktree.create without a base is the call it always was: no `base` key at all", async () => {
    const client = new HerdrClient(await lateHerdr({ workspace: { label: "x" }, root_pane: PANE }), 1_000);
    await client.createWorktree({ cwd: "/repo", branch: "worktree/x" });
    expect(lastRequest?.params).toEqual({ cwd: "/repo", branch: "worktree/x", focus: false });
    expect(Object.keys(lastRequest?.params ?? {})).not.toContain("base");
  });

  test("worktree.remove sends only the discovered workspace identity and explicit force choice", async () => {
    const client = new HerdrClient(await lateHerdr({
      type: "worktree_removed", workspace_id: "w9", path: "/repo/.worktrees/topic", forced: true,
    }), 1_000);
    const removed = await client.removeWorktree({ workspace_id: "w9", force: true });
    expect(removed).toEqual({ type: "worktree_removed", workspace_id: "w9", path: "/repo/.worktrees/topic", forced: true });
    expect(lastRequest).toEqual({ method: "worktree.remove", params: { workspace_id: "w9", force: true } });
    expect(lastRequest?.params).not.toHaveProperty("trust_repository");
  });
});


describe("HerdrClient exact ping mapping", () => {
  test("one-shot ping returns validated vendor identity without listing or mutating panes", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "pong", version: "0.9.3", protocol: 22 }), 1000);
    expect(await client.handshake()).toEqual({ version: "0.9.3", protocol: 22, paneOriginalMetadataV1: false, agentViewReadV1: false });
    expect(lastRequest).toEqual({ method: "ping", params: {} });
  });
  test("advertises only positive additive metadata capabilities from ping", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "pong", version: "0.9.3", protocol: 22, capabilities: { pane_original_metadata_v1: true, agent_view_read_v1: true } }), 1000);
    expect(await client.handshake()).toEqual({ version: "0.9.3", protocol: 22, paneOriginalMetadataV1: true, agentViewReadV1: true });
  });
  test("ignores unnegotiated top-level flags and refuses malformed nested flags", async () => {
    const top = new HerdrClient(await lateHerdr({ type: "pong", version: "0.9.3", protocol: 22, pane_original_metadata_v1: true, agent_view_read_v1: true }), 1000);
    expect(await top.handshake()).toMatchObject({ paneOriginalMetadataV1: false, agentViewReadV1: false });
    const malformed = new HerdrClient(await lateHerdr({ type: "pong", version: "0.9.3", protocol: 22, capabilities: { pane_original_metadata_v1: "true" } }), 1000);
    await expect(malformed.handshake()).rejects.toThrow("malformed metadata capability");
  });
  test("accepts the producer's omitted optional label and cleared-source fields", async () => {
    const set = new HerdrClient(await lateHerdr({ type: "agent_view", active: true, source: "collie.native.owner" }), 1000);
    expect(await set.setAgentView({ source: "collie.native.owner" })).toEqual({ type: "agent_view", active: true, source: "collie.native.owner" });
    const clear = new HerdrClient(await lateHerdr({ type: "agent_view", active: false }), 1000);
    expect(await clear.clearAgentView({ source: "collie.native.owner" })).toEqual({ type: "agent_view", active: false });
  });
  for (const result of [{ type: "ok", version: "0.9.3", protocol: 22 }, { type: "pong", version: "0.9.3", protocol: -1 }, { type: "pong", version: "0.9.3", protocol: 22.5 }]) {
    test(`health refuses malformed pong ${JSON.stringify(result)}`, async () => {
      const client = new HerdrClient(await lateHerdr(result), 1000);
      expect(await client.ping()).toBe(false);
      expect(lastRequest?.method).toBe("ping");
    });
  }
});

describe("B31 producer-specific guarded methods", () => {
  test("uses exact method names and bounded owner-scoped payloads", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "ok" }), 1000);
    await client.reportPaneMetadataOriginal({ original_binding: "a".repeat(64), report: { pane_id: "p1", source: "collie.native" } });
    expect(lastRequest).toEqual({ method: "pane.report_metadata.original_v1", params: { original_binding: "a".repeat(64), report: { pane_id: "p1", source: "collie.native" } } });
    server?.close(); server = null;
    const stateClient = new HerdrClient(await lateHerdr({ type: "agent_view_state", source: "owner-a", active: false, view: null }), 1000);
    await stateClient.getAgentView({ source: "owner-a" });
    expect(lastRequest).toEqual({ method: "agent.view.get", params: { source: "owner-a" } });
  });
});

describe("HerdrClient workspace and tab organization wire mapping", () => {
  test("uses the shipped schema-22 method names and exact scoped parameter fields", async () => {
    const client = new HerdrClient(await lateHerdr({}), 1_000);
    const check = async (run: () => Promise<void>, method: string, params: JsonObject) => {
      await run();
      expect(lastRequest).toEqual({ method, params });
    };
    await check(() => client.renameWorkspace("w1", "Review"), "workspace.rename", { workspace_id: "w1", label: "Review" });
    await check(() => client.closeWorkspace("w1", true), "workspace.close", { workspace_id: "w1", close_group: true });
    await check(() => client.moveWorkspace("w1", 3), "workspace.move", { workspace_id: "w1", insert_index: 3 });
    await check(() => client.moveWorkspaceBlock(["w2", "w3"], "w4"), "workspace.move_block", { workspace_ids: ["w2", "w3"], before_workspace_id: "w4" });
    await check(() => client.focusWorkspace("w1"), "workspace.focus", { workspace_id: "w1" });
    await check(() => client.moveTab("t1", 2), "tab.move", { tab_id: "t1", insert_index: 2 });
    await check(() => client.focusTab("t1"), "tab.focus", { tab_id: "t1" });
  });
});

describe("HerdrClient portable layout wire mapping", () => {
  test("uses the shipped layout method names and preserves binary split paths", async () => {
    const client = new HerdrClient(await lateHerdr({}), 1_000);
    const root = { type: "split" as const, direction: "right" as const, ratio: 0.5,
      first: { type: "pane" as const, label: "left", cwd: ".", pane_id: null, command: null, env: {} },
      second: { type: "pane" as const, label: "right", cwd: ".", pane_id: null, command: null, env: {} } };
    await client.exportLayout({ tab_id: "w1:t1" });
    expect(lastRequest).toEqual({ method: "layout.export", params: { tab_id: "w1:t1" } });
    await client.applyLayout({ root, workspace_id: "w1", tab_id: "w1:t1", focus: false });
    expect(lastRequest).toEqual({ method: "layout.apply", params: { root, workspace_id: "w1", tab_id: "w1:t1", focus: false } });
    await client.setSplitRatio({ tab_id: "w1:t1", path: [false, true, false], ratio: 0.625 });
    expect(lastRequest).toEqual({ method: "layout.set_split_ratio", params: { tab_id: "w1:t1", path: [false, true, false], ratio: 0.625 } });
  });
});

describe("HerdrClient pane.process_info", () => {
  test("sends an explicit pane id and decodes the named success result", async () => {
    const result = {
      type: "pane_process_info",
      process_info: { pane_id: "w9:p1", foreground_processes: [{ pid: 17, name: "node", argv: ["node", "server.js"] }] },
    };
    const client = new HerdrClient(await lateHerdr(result), 1_000);
    await expect(client.processInfo("w9:p1")).resolves.toMatchObject({ ...result.process_info, truncated: false });
    expect(lastRequest).toEqual({ method: "pane.process_info", params: { pane_id: "w9:p1" } });
  });

  test("redacts a credential before process strings leave the Herdr wire parser", async () => {
    const secret = "KnownCredentialBeforeAnyAdapterClipping123";
    const client = new HerdrClient(await lateHerdr({
      type: "pane_process_info",
      process_info: { pane_id: "w9:p1", foreground_processes: [{ pid: 17, name: "node", cmdline: `node --token=${secret}` }] },
    }), 1_000);
    const result = await client.processInfo("w9:p1");
    expect(result.foreground_processes[0]!.cmdline).not.toContain(secret);
    expect(result.foreground_processes[0]!.cmdline).toContain("--token=••");
  });

  test("bounds excessive process and argv arrays and marks the omitted rows", async () => {
    const rows = Array.from({ length: 70 }, (_, pid) => ({ pid, name: "node", argv: Array(70).fill("node") }));
    const client = new HerdrClient(await lateHerdr({ type: "pane_process_info", process_info: { pane_id: "w9:p1", foreground_processes: rows } }), 1_000);
    const result = await client.processInfo("w9:p1");
    expect(result.foreground_processes).toHaveLength(64);
    expect(result.foreground_processes[0]!.argv).toHaveLength(64);
    expect(result.truncated).toBe(true);
  });

  test("rejects malformed process rows at the socket boundary", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "pane_process_info", process_info: { pane_id: "w9:p1", foreground_processes: [{ pid: -1, name: "node" }] } }), 1_000);
    await expect(client.processInfo("w9:p1")).rejects.toThrow("malformed pid");
  });

  test("refuses an oversized process-info socket reply before parsing an unbounded buffer", async () => {
    const client = new HerdrClient(await lateHerdr({
      type: "pane_process_info",
      process_info: { pane_id: "w9:p1", foreground_processes: [{ pid: 1, name: "x".repeat(1_000_001) }] },
    }), 1_000);
    await expect(client.processInfo("w9:p1")).rejects.toThrow("reply exceeded 1000000 characters");
  });
});

describe("HerdrClient agent identity diagnostics", () => {
  test("pane.get is exact-id scoped and accepts only its named reply", async () => {
    const pane = {
      pane_id: "w9:p1", terminal_id: "term-7", workspace_id: "w9", tab_id: "w9:t1", focused: false,
      cwd: "/repo", agent: "codex", agent_status: "working", revision: 12,
      agent_session: { source: "herdr:codex", agent: "codex", kind: "id", value: "session-42" },
    };
    const client = new HerdrClient(await lateHerdr({ type: "pane_info", pane }), 1_000);
    expect(await client.getPane("w9:p1")).toMatchObject({ pane_id: "w9:p1", terminal_id: "term-7" });
    expect(lastRequest).toEqual({ method: "pane.get", params: { pane_id: "w9:p1" } });
  });

  test("pane.get refuses absent, empty, mistyped, or malformed occupant witnesses", async () => {
    for (const pane of [
      { pane_id: "w9:p1", terminal_id: "", workspace_id: "w9", tab_id: "w9:t1", agent_status: "working" },
      { pane_id: "w9:p1", terminal_id: null, workspace_id: "w9", tab_id: "w9:t1", agent_status: "working" },
      { pane_id: "w9:p1", terminal_id: "term", workspace_id: "w9", tab_id: "w9:t1", agent_status: "future" },
      { pane_id: "w9:p1", terminal_id: "term", workspace_id: "w9", tab_id: "w9:t1", agent_status: "working", agent_session: { source: "", agent: "codex", kind: "id", value: "s" } },
    ]) {
      const client = new HerdrClient(await lateHerdr({ type: "pane_info", pane }), 1000);
      await expect(client.getPane("w9:p1")).rejects.toThrow("pane identity mismatch");
    }
  });

  test("pane.get refuses an oversized chunked reply before JSON parsing", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "pane_info", pane: { ...PANE, pane_id: "w9:p1", terminal_id: "term", agent_status: "working", padding: "x".repeat(MAX_PANE_REPLY_CHARS + 1) } }), 1000);
    await expect(client.getPane("w9:p1")).rejects.toThrow(`reply exceeded ${MAX_PANE_REPLY_CHARS} characters`);
  });

  test("pane.get bounds unterminated replies and rejects malformed UTF-8", async () => {
    const oversized = new HerdrClient(await lateHerdr({}, () => `{"result":"${"x".repeat(MAX_PANE_REPLY_CHARS * 4 + 1)}`), 1000);
    await expect(oversized.getPane("w9:p1")).rejects.toThrow(`reply exceeded ${MAX_PANE_REPLY_CHARS} characters`);
    const malformedUtf8Path = await lateHerdr({}, (id) => Buffer.concat([
      Buffer.from(`{"id":${JSON.stringify(id)},"result":{"type":"pane_info","pane":{"pane_id":"w9:p1","terminal_id":"term","workspace_id":"w9","tab_id":"w9:t1","agent_status":"working","cwd":"`),
      Buffer.from([0xff]), Buffer.from("\"}}}\n"),
    ]));
    const malformedUtf8 = new HerdrClient(malformedUtf8Path, 1000);
    await expect(malformedUtf8.getPane("w9:p1")).rejects.toThrow("reply was not valid UTF-8");
  });

  test("agent.get targets the exact pane and preserves session, readiness and bounded sequences", async () => {
    const client = new HerdrClient(await lateHerdr({ type: "agent_info", agent: {
      pane_id: "w9:p1", agent: "codex", agent_status: "working",
      agent_session: { source: "herdr:codex", agent: "codex", kind: "id", value: "session-42" },
      interactive_ready: true, launch_pending: false, state_change_seq: 9, completion_seq: 7,
    } }), 1_000);
    expect(await client.getAgent("w9:p1")).toMatchObject({
      pane_id: "w9:p1", agent: "codex", interactive_ready: true, launch_pending: false,
      state_change_seq: "9", completion_seq: "7", agent_session: { source: "herdr:codex", value: "session-42" },
    });
    expect(lastRequest).toEqual({ method: "agent.get", params: { target: "w9:p1" } });
  });

  test("agent.get preserves the full uint64 numeric wire domain as exact decimal strings", async () => {
    for (const [wire, expected] of [
      ["0", "0"], ["9007199254740991", "9007199254740991"], ["9007199254740992", "9007199254740992"],
      ["9007199254740993", "9007199254740993"], ["18446744073709551615", "18446744073709551615"],
    ] as const) {
      const client = new HerdrClient(await lateHerdr({}, (id) =>
        `{"id":${JSON.stringify(id)},"result":{"type":"agent_info","agent":{"pane_id":"w9:p1","agent_status":"working","state_change_seq":${wire},"completion_seq":${wire}}}}\n`), 1000);
      await expect(client.getAgent("w9:p1")).resolves.toMatchObject({ state_change_seq: expected, completion_seq: expected });
    }
    for (const wire of ["18446744073709551616", "-1", "1.5", "1e2", "\"9\""]) {
      const client = new HerdrClient(await lateHerdr({}, (id) =>
        `{"id":${JSON.stringify(id)},"result":{"type":"agent_info","agent":{"pane_id":"w9:p1","agent_status":"working","state_change_seq":${wire}}}}\n`), 1000);
      await expect(client.getAgent("w9:p1")).rejects.toThrow("malformed state_change_seq");
    }
  });

  test("rejects wrong target, wrapper and status", async () => {
    const wrongTarget = new HerdrClient(await lateHerdr({ type: "agent_info", agent: { pane_id: "w9:p2", agent_status: "working" } }), 1_000);
    await expect(wrongTarget.getAgent("w9:p1")).rejects.toThrow("target identity mismatch");
    const wrongWrapper = new HerdrClient(await lateHerdr({ type: "pane_info", agent: { pane_id: "w9:p1", agent_status: "working" } }), 1_000);
    await expect(wrongWrapper.getAgent("w9:p1")).rejects.toThrow("unexpected result type");
    const invalid = new HerdrClient(await lateHerdr({ type: "agent_info", agent: { pane_id: "w9:p1", agent_status: "invented" } }), 1_000);
    await expect(invalid.getAgent("w9:p1")).rejects.toThrow("malformed agent_status");
  });
});
