import { expect, test } from "bun:test";
import net from "node:net";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AuditLog } from "./audit.ts";
import { createBunWriteStream } from "./dial.ts";
import { HerdrClient } from "./mux/herdr/client.ts";
import { HerdrMux } from "./mux/herdr/adapter.ts";
import { withAgentHints } from "./beacon/hint.ts";
import { createWorktree, createWorktreeAt } from "./server.ts";
import { memoryWorktreeReceipts } from "./worktree-receipts.ts";
import { OperationStore } from "./control/operations.ts";
import { serveControlRoute } from "./control/routes.ts";
import { PlanStore } from "./control/plans.ts";
import { serveWorktreeRemoveRoute } from "./control/worktree-remove.ts";
import type { StateEngine } from "./state-engine.ts";
import type { JsonObject } from "./json.ts";

const owner = "a".repeat(64);
const id = "4f1c2b3a-9d8e-4c7b-a6f5-0e1d2c3b4a59";
type Route = "legacy" | "createAt" | "remove";
type Outcome = "zero" | "prefix" | "current";

async function git(cwd: string, ...args: string[]) {
  const child = Bun.spawn(["git", "-C", cwd, ...args], { stdout: "ignore", stderr: "pipe", env: { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1", GIT_TERMINAL_PROMPT: "0" } });
  if (await child.exited !== 0) throw new Error(await new Response(child.stderr).text());
}

async function fixture(outcome: Outcome) {
  const root = await mkdtemp(join(tmpdir(), "collie-worktree-prefix-"));
  const repo = join(root, "repo"); const checkout = join(root, "linked"); await mkdir(repo);
  await git(repo, "init", "-q", "-b", "main");
  await git(repo, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "base");
  await git(repo, "worktree", "add", "-q", "-b", "topic", checkout);
  // Actual endpoint inode pins the production client's original runtime. The controlled raw
  // acceptance below drives the same Bun writer factory, not a replacement client or adapter.
  const endpoint = join(root, "herdr.sock"); const server = net.createServer();
  await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(endpoint, resolve); });
  let signal!: () => void; const entered = new Promise<void>((resolve) => { signal = resolve; });
  let drain: (() => void) | undefined; let allowed = true; let now = Date.now();
  const bytes: Uint8Array[] = []; let dispatches = 0; let closed = 0; let completeMutations = 0;
  const worktrees = [{ path: checkout, branch: "topic", is_linked_worktree: true, is_prunable: false, is_bare: false, is_detached: false, open_workspace_id: "w" }];
  const client = new HerdrClient(endpoint, 1_000, "bun", async (_path, handlers) => {
    let calls = 0; let offered: { id: string; method: string } | undefined; let accepted = Buffer.alloc(0); let replied = false; let mutation = false;
    const stream = createBunWriteStream(handlers);
    const raw = {
      write(data: Uint8Array, offset = 0, length = data.byteLength - offset) {
        if (!offered) {
          // SAFETY: exact production HerdrClient fixture-owned request; selection of raw
          // acceptance and wire replies never grants original caller or runtime admission.
          offered = JSON.parse(Buffer.from(data).toString()) as { id: string; method: string };
          mutation = offered.method === "worktree.create" || offered.method === "worktree.remove";
          if (mutation) { dispatches++; drain = () => stream.drain(raw); }
        }
        const count = mutation && calls++ === 0 ? (outcome === "zero" ? 0 : 2) : mutation && calls === 2 ? 0 : length;
        const part = data.slice(offset, offset + count); accepted = Buffer.concat([accepted, part]);
        if (mutation) { bytes.push(part); if (count === 0) signal(); }
        return count;
      },
      flush() {
        if (!offered || replied || !accepted.toString().endsWith("\n")) return;
        replied = true; if (mutation) completeMutations++;
        const result = offered.method === "ping" ? { type: "pong", version: "0.9.3", protocol: 22 }
          : offered.method === "session.snapshot" ? { snapshot: { version: "0.9.3", protocol: 22, workspaces: [{ workspace_id: "w", number: 1, label: "topic", focused: false, pane_count: 0, tab_count: 0, active_tab_id: "t", agent_status: "idle" }], tabs: [], panes: [] } }
          : offered.method === "worktree.list" ? { source: { repo_root: repo, source_checkout_path: checkout }, worktrees }
          : offered.method === "worktree.remove" ? { type: "worktree_removed", workspace_id: "w", path: checkout, forced: false }
          : { workspace: { label: "topic" }, root_pane: { pane_id: "p", workspace_id: "w", tab_id: "t", cwd: checkout } };
        const answer = new TextEncoder().encode(JSON.stringify({ id: offered.id, result }) + "\n");
        queueMicrotask(() => handlers.data?.(stream.handle, answer));
      },
      end() { if (mutation) closed++; },
    };
    stream.attach(raw); return stream.handle;
  });
  const mux = withAgentHints(new HerdrMux(client, () => []), { hooksInstalled: () => false });
  expect(await mux.reachable()).toBe(true);
  const identity = await mux.layoutRuntimeIdentity!(); expect(identity).not.toBeNull();
  const state = { agents: [], shellPanes: [], workspaces: [{ workspaceId: "w", number: 1, label: "root", focused: false, activeTabId: "t", tabCount: 0, paneCount: 0, repoRoot: repo }], tabs: [], bridge: "connected" as const };
  const partial: Partial<StateEngine> = { current: () => state, pokeNow() {} };
  // SAFETY: these handlers reach only current/pokeNow for read metadata; all protected mux
  // methods, handshake, original callbacks, request encoding and writer remain production.
  const engine = partial as StateEngine;
  const operationsPath = join(root, "operations"); const operations = new OperationStore(operationsPath); expect(await operations.prepare()).toBe(true);
  const plans = new PlanStore(() => now); const receipts = memoryWorktreeReceipts();
  const caller = { gate: (level: string) => level !== "write" || allowed ? null : new Response("original write retired", { status: 403 }), pairedIdentity: () => owner, resolve: async () => ({ host: "host", session: "default", mux }), audit() {} };
  const context = { key: new Uint8Array(32).fill(7), deviceIdentity: owner, owningHost: "host", runtime: "default" };
  return { root, repo, checkout, mux, engine, plans, operations, receipts, operationsPath, caller, context,
    finalAdmission: (current: string) => allowed && current === identity,
    entered, restoreWrite: () => { allowed = true; }, retire: (expiry: boolean) => { if (expiry) now += 60_001; else allowed = false; },
    drain: () => { if (!drain) throw new Error("actual protected writer did not enter"); drain(); },
    bytes: () => Buffer.concat(bytes), dispatches: () => dispatches, closed: () => closed, complete: () => completeMutations,
    close: async () => { await new Promise<void>((resolve) => server.close(() => resolve())); await rm(root, { recursive: true, force: true }); },
  };
}

for (const route of ["legacy", "createAt", "remove"] as const) for (const outcome of ["zero", "prefix", "current"] as const) {
  test(`${route} actual accepted-byte retirement retains ${outcome} durable outcome and sends no reconciliation`, async () => run(route, outcome, false));
}
for (const outcome of ["zero", "prefix"] as const) test(`remove actual ${outcome} with expired original plan`, async () => run("remove", outcome, true));

async function run(route: Route, outcome: Outcome, expiry: boolean) {
  const f = await fixture(outcome);
  try {
    let planToken = "";
    if (route === "remove") {
      const url = new URL("https://collie/api/workspace/w/worktree/remove-plan");
      const response = await serveWorktreeRemoveRoute(new Request(url), url, f.caller, f); expect(response?.status).toBe(200);
      // SAFETY: this production route's asserted positive closed plan supplies its token.
      planToken = (await response!.json() as { planToken: string }).planToken;
    }
    const body: JsonObject = route === "remove" ? { requestId: id, planToken, force: false } : route === "legacy" ? { requestId: id, branch: "fresh-topic" } : { requestId: id, branch: "fresh-topic", cwd: f.repo, base: { kind: "default" } };
    const invoke = async (operations = f.operations) => {
      const request = new Request("https://collie/api/worktree", { method: "POST", body: JSON.stringify(body) });
      if (route === "legacy") return createWorktree(f.mux, f.engine, "w", request, new AuditLog(() => {}), owner, "default", async () => [], f.receipts, {}, { receiptContext: f.context, operations, finalAdmission: f.finalAdmission });
      if (route === "createAt") return createWorktreeAt(f.mux, f.engine, request, new AuditLog(() => {}), owner, "default", async () => [], { home: f.root, readHerdrConfig: async () => null, receipts: f.receipts, receiptContext: f.context, operations, finalAdmission: f.finalAdmission });
      const url = new URL("https://collie/api/workspace/w/worktree/remove"); const response = await serveWorktreeRemoveRoute(new Request(url, { method: "POST", body: JSON.stringify(body) }), url, f.caller, { plans: f.plans, operations });
      if (!response) throw new Error("production removal route missing"); return response;
    };
    const pending = invoke();
    await Promise.race([f.entered, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("protected writer never entered")), 4_000))]);
    expect(f.bytes().byteLength).toBe(outcome === "zero" ? 0 : 2);
    if (outcome !== "current") f.retire(expiry); f.drain();
    const response = await pending;
    const expected = outcome === "prefix" ? "unknown" : outcome === "zero" ? "refused" : "succeeded";
    expect(await f.operations.inspect(id, owner, "host", "default")).toMatchObject({ state: expected });
    expect(f.dispatches()).toBe(1); expect(f.complete()).toBe(outcome === "current" ? 1 : 0); expect(f.closed()).toBe(1);
    if (outcome === "prefix") { expect(response.status).toBe(route === "remove" ? 202 : 200); expect(await response.json()).toMatchObject({ ok: false, code: route === "remove" ? "worktree.outcome_unknown" : "launch.outcome_unknown" }); }
    else expect(await response.json()).toMatchObject({ ok: outcome === "current" });
    const accepted = f.bytes(); const restarted = new OperationStore(f.operationsPath); expect(await restarted.prepare()).toBe(true);
    expect(await restarted.inspect(id, owner, "host", "default")).toMatchObject({ state: expected });
    // The ordinary paired READ reconciles the exact old intent while the independent write
    // factor remains closed. A fresh explicit write-factor recovery still cannot resend it.
    const lookupURL = new URL(`https://collie/api/control/operations/${id}`);
    const lookup = await serveControlRoute(new Request(lookupURL), lookupURL, { ...f.caller, device: () => "fixture" }, restarted);
    expect(lookup?.status).toBe(200); expect(await lookup!.json()).toMatchObject({ ok: true, operation: { state: expected } });
    if (route === "remove" && outcome !== "current" && !expiry) expect((await invoke(restarted)).status).toBe(403);
    f.restoreWrite();
    const again = await invoke(restarted);
    if (outcome === "prefix") expect(await again.json()).toMatchObject({ ok: false, code: route === "remove" ? "worktree.outcome_unknown" : "launch.outcome_unknown" });
    expect(f.dispatches()).toBe(1); expect(f.bytes()).toEqual(accepted); f.drain(); expect(f.bytes()).toEqual(accepted);
  } finally { await f.close(); }
}
