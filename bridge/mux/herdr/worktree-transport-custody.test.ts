import { describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { startWorktreeTestPeer } from "./worktree-test-peer.ts";
import { dialHerdr } from "../../dial.ts";
import { HerdrClient } from "./client.ts";
import { HerdrMux } from "./adapter.ts";
import { withAgentHints } from "../../beacon/hint.ts";
import { withAgentBeacons } from "../../beacon/decorate.ts";
import { fakeBeaconReader } from "../../beacon/fake.ts";
import type { MuxAdapter } from "../types.ts";
import { declareCapabilities, MUX_CAPABILITIES } from "../capabilities.ts";
import { typeIntoFreshShell } from "../../server.ts";

const deferred = () => { let resolve!: () => void; const promise = new Promise<void>((done) => { resolve = done; }); return { promise, resolve }; };
const bounded = <T>(promise: Promise<T>) => Promise.race([promise, new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error("owned fixture continuation did not enter")), 4_000); promise.finally(() => clearTimeout(timer)).catch(() => {}); })]);

/** Metadata-only fixture; every protected method, readiness and socket remains real Herdr. */
class IdentitylessHerdrFixture extends HerdrMux {
  override get capabilities() {
    const c = super.capabilities;
    return declareCapabilities({ ...c, supports: MUX_CAPABILITIES.filter((key) => c.supports[key] && key !== "agentDetection" && key !== "agentSessionRef") });
  }
}

async function fixture(beacons: boolean, mode: "auto" | "net" = "auto") {
  const dir = mkdtempSync(join(tmpdir(), "collie-worktree-transport-"));
  const path = process.platform === "win32" ? `\\\\.\\pipe\\collie-worktree-test-${randomUUID()}` : join(dir, "herdr.sock");
  let peer = await startWorktreeTestPeer(path, dir);
  const peers = [peer];
  const captures = () => peers.flatMap((owned) => owned.captures());
  let nth: number | null = null; let dials = 0;
  let entered = deferred(); let release = deferred();
  const client = new HerdrClient(path, 1_000, mode, async (endpoint, handlers, dialMode) => {
    const socket = await dialHerdr(endpoint, handlers, dialMode);
    if (nth !== null && ++dials === nth) { entered.resolve(); await release.promise; }
    return socket;
  });
  const raw = beacons ? new IdentitylessHerdrFixture(client, () => []) : new HerdrMux(client, () => []);
  let mux: MuxAdapter = raw;
  if (beacons) {
    // Normal Herdr refuses redundant beacons; the fixture projects only identity metadata away,
    // without changing readiness, mutation capability, original callbacks or transport methods.
    mux = withAgentBeacons(raw, fakeBeaconReader([]), { hooksInstalled: () => true, matcher: { namespace: "herdr", scope: async () => null, matches: () => false, notesWithoutHooks: { agentDetection: "fixture", agentSessionRef: "fixture" } } });
  }
  mux = withAgentHints(mux, { hooksInstalled: () => false });
  expect(await mux.reachable()).toBe(true);
  await mux.snapshot();
  const identity = await mux.layoutRuntimeIdentity!(); expect(identity).not.toBeNull();
  let paired = true; let writable = true; let expired = false;
  const original = (runtime: string) => runtime === identity && paired && writable && !expired;
  return {
    mux, client, original, captures,
    arm: (number: number) => { nth = number; dials = 0; entered = deferred(); release = deferred(); },
    entered: () => bounded(entered.promise), release: () => release.resolve(),
    retire: async (cause: string) => { if (cause === "pairing") paired = false; else if (cause === "write-factor") writable = false; else if (cause === "expiry") expired = true; else if (cause === "runtime") { const oldPid = peer.pid; await peer.retireListener(); peer = await startWorktreeTestPeer(path, dir); peers.push(peer); expect(peer.pid).not.toBe(oldPid); } },
    dropReply: () => peer.dropReply(),
    mutationBytes: () => captures().filter((c) => c.method !== "ping" && c.method !== "session.snapshot" && c.method !== "pane.read").reduce((n, c) => n + c.bytes.byteLength, 0),
    methods: () => captures().flatMap((c) => c.method && c.method !== "ping" && c.method !== "session.snapshot" && c.method !== "pane.read" ? [c.method] : []),
    close: async () => { release.resolve(); await Promise.all(peers.map((owned) => owned.close())); rmSync(dir, { recursive: true, force: true }); },
  };
}

describe("actual HerdrClient dial continuation keeps original worktree admission", () => {
  for (const beacons of [false, true]) for (const operation of ["create", "open", "remove", "text", "Enter"] as const) {
    for (const cause of ["pairing", "write-factor", "runtime", "expiry", "current"]) {
      test(`${operation} after held real dial / ${cause} / beacons=${beacons}`, async () => {
        const f = await fixture(beacons);
        try {
          f.arm(operation === "text" || operation === "Enter" ? 1 : 2);
          const pending = operation === "create" ? f.mux.createWorktree({ repoRoot: "/repo", branch: "topic" }, f.original)
            : operation === "open" ? f.mux.openWorktree({ repoRoot: "/repo", path: "/repo-topic" }, f.original)
            : operation === "remove" ? f.mux.removeWorktree!({ workspaceId: "w", force: false }, f.original)
            : operation === "text" ? f.mux.typeText("p", "codex", f.original) : f.mux.sendKeys("p", ["Enter"], f.original);
          await f.entered(); expect(f.mutationBytes()).toBe(0);
          if (cause !== "current") await f.retire(cause);
          f.release(); const result = await pending;
          expect(result.ok).toBe(cause === "current");
          expect(f.methods()).toHaveLength(cause === "current" ? 1 : 0);
          if (cause !== "current") { expect(f.mutationBytes()).toBe(0); expect(result).toMatchObject({ reason: "refused" }); }
        } finally { await f.close(); }
      });
    }
  }
  test("created checkout and delivered text remain truthful when Enter loses its owner during dial", async () => {
    const f = await fixture(true);
    try {
      const created = await f.mux.createWorktree({ repoRoot: "/repo", branch: "topic" }, f.original); expect(created.ok).toBe(true);
      // read, read, text, then Enter; the injected wait clock keeps readiness semantics real.
      f.arm(4); let now = 0;
      const pending = typeIntoFreshShell(f.mux, "p", "codex", { floorMs: 0, pollMs: 1, now: () => now, sleep: async (ms) => { now += ms; } }, "worktree", { prepare: async () => f.original(f.client.layoutRuntimeIdentity()!), finalAdmission: f.original });
      await f.entered(); expect(f.methods()).toEqual(["worktree.create", "pane.send_text"]);
      await f.retire("pairing"); f.release();
      expect(await pending).toMatchObject({ ok: false, textDelivered: true });
      expect(f.methods()).toEqual(["worktree.create", "pane.send_text"]);
    } finally { await f.close(); }
  });
  for (const current of [false, true]) test(`real Node queue admission after held dial / current=${current}`, async () => {
    const f = await fixture(false, "net");
    try {
      f.arm(2); const pending = f.mux.createWorktree({ repoRoot: "/repo", branch: "topic" }, f.original);
      await f.entered(); expect(f.mutationBytes()).toBe(0);
      if (!current) await f.retire("pairing"); f.release();
      expect((await pending).ok).toBe(current); expect(f.methods()).toHaveLength(current ? 1 : 0);
      if (!current) expect(f.mutationBytes()).toBe(0);
    } finally { await f.close(); }
  });
  test("lost vendor reply is unknown and the real client never automatically sends a second create", async () => {
    const f = await fixture(false);
    try {
      f.dropReply(); const result = await f.mux.createWorktree({ repoRoot: "/repo", branch: "topic" }, f.original);
      expect(result).toMatchObject({ ok: false, reason: "unreachable" }); expect(f.methods()).toEqual(["worktree.create"]);
    } finally { await f.close(); }
  });
});
