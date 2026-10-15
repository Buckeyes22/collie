import { describe, expect, test } from "bun:test";
import { execFile as execFileCallback } from "node:child_process";
import { realpath, mkdtemp, mkdir, rename, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { OperationStore } from "./operations.ts";
import { PlanStore } from "./plans.ts";
import { serveWorktreeRemoveRoute, type WorktreeRemoveRuntime } from "./worktree-remove.ts";
import { declareCapabilities, type MuxAdapter } from "../mux/types.ts";
import type { JsonObject } from "../json.ts";

const execFile = promisify(execFileCallback);
const owner = "a".repeat(64);

async function git(cwd: string, ...args: string[]) {
  await execFile("git", ["-C", cwd, ...args], { env: { ...process.env, GIT_CONFIG_NOSYSTEM: "1", GIT_TERMINAL_PROMPT: "0" } });
}

async function fixture(dirty = false, loseReply = false) {
  const root = await realpath(await mkdtemp(join(tmpdir(), "collie-worktree-remove-")));
  const repo = join(root, "repo");
  const checkout = join(root, "linked");
  await mkdir(repo);
  await git(repo, "init", "-b", "main");
  await git(repo, "config", "user.name", "Collie test");
  await git(repo, "config", "user.email", "collie-test@example.invalid");
  await writeFile(join(repo, "tracked.txt"), "base\n");
  await git(repo, "add", "tracked.txt");
  await git(repo, "commit", "-m", "base");
  await git(repo, "worktree", "add", "-b", "topic", checkout);
  if (dirty) await writeFile(join(checkout, "untracked.txt"), "preserve unless force confirmed\n");
  const state = { spaces: [{ spaceId: "linked-space", number: 1, label: "linked", focused: false, activeTabId: "tab", tabCount: 1, paneCount: 0, repoRoot: repo, isWorktree: true, folder: checkout }], panes: [], tabs: [] };
  let removeCalls = 0;
  let vendorRefusal: string | null = null;
  let runtimeIdentity = "fixture-incarnation-a";
  let allowWrite = true;
  let pairedOwner = owner;
  let beforeDispatch: (() => Promise<void>) | null = null;
  let now = Date.now();
  let holdRemoval = false;
  let signalEntered!: () => void;
  let releaseRemoval!: () => void;
  const removeEntered = new Promise<void>((resolve) => { signalEntered = resolve; });
  const removalReleased = new Promise<void>((resolve) => { releaseRemoval = resolve; });
  const muxPartial: Partial<MuxAdapter> = {
    capabilities: declareCapabilities({ supports: ["removeWorktree", "listWorktrees"], topologyLatency: { kind: "bounded", ms: 1_000 } }),
    layoutRuntimeIdentity: () => runtimeIdentity,
    snapshot: async () => state,
    listWorktrees: async () => ({ ok: true, value: [{ path: checkout, branch: "topic", openSpaceId: "linked-space", linked: true, prunable: false }] }),
    removeWorktree: async ({ workspaceId, force }, finalAdmission, prepare) => {
      const original = runtimeIdentity;
      if (beforeDispatch) await beforeDispatch();
      if (prepare && !await prepare()) return { ok: false, reason: "refused", detail: "target_changed" };
      if (runtimeIdentity !== original || finalAdmission && !finalAdmission(runtimeIdentity)) return { ok: false, reason: "refused", detail: "authority_changed" };
      removeCalls++;
      if (vendorRefusal !== null) return { ok: false, reason: "refused", detail: `herdr worktree.remove: ${vendorRefusal}: owner refused` };
      if (holdRemoval) { signalEntered(); await removalReleased; }
      await git(repo, "worktree", "remove", ...(force ? ["--force"] : []), checkout);
      if (loseReply) return { ok: false, reason: "unreachable", detail: "reply disconnected after dispatch" };
      return { ok: true, value: { workspaceId, path: checkout, forced: force } };
    },
    refresh: async () => {},
  };
  // SAFETY: the removal route calls only the fixture's snapshot/list/remove/refresh methods.
  const mux = muxPartial as MuxAdapter;
  const runtime: WorktreeRemoveRuntime = { host: "owner-host", session: "work", mux };
  const operations = new OperationStore(join(root, "state"));
  await operations.prepare();
  const plans = new PlanStore(() => now);
  const audits: string[] = [];
  const caller = {
    gate: (kind: string) => kind === "write" && !allowWrite ? new Response("write refused", { status: 403 }) : null,
    pairedIdentity: () => pairedOwner,
    resolve: async () => runtime,
    audit: (_action: string, _target: string, _host: string, _session: string | null, outcome: string) => audits.push(outcome),
  };
  return {
    root, repo, checkout, state, operations, plans, caller,
    setVendorRefusal: (code: string) => { vendorRefusal = code; },
    setBeforeDispatch: (callback: () => Promise<void>) => { beforeDispatch = callback; },
    expire: () => { now += 60_001; },
    replaceRuntime: () => { runtimeIdentity = "fixture-incarnation-b"; },
    revokeWrite: () => { allowWrite = false; },
    replaceOwner: () => { pairedOwner = "b".repeat(64); },
    removeCalls: () => removeCalls, audits,
    holdRemove: () => { holdRemoval = true; },
    removeEntered,
    releaseRemoval: () => releaseRemoval(),
  };
}

async function route(fx: Awaited<ReturnType<typeof fixture>>, method: string, suffix: string, body?: JsonObject) {
  const url = new URL(`https://collie/api/workspace/linked-space/worktree/${suffix}`);
  url.searchParams.set("session", "work");
  const init: RequestInit = { method };
  if (body !== undefined) {
    init.headers = { "content-type": "application/json" };
    init.body = JSON.stringify(body);
  }
  const req = new Request(url, init);
  const answer = await serveWorktreeRemoveRoute(req, url, fx.caller, { plans: fx.plans, operations: fx.operations });
  if (!answer) throw new Error("route not recognized");
  return answer;
}

describe("owning-runtime linked worktree removal", () => {
  test("clean plan binds the discovered checkout and successful receipt removes only the checkout, preserving its branch", async () => {
    const fx = await fixture();
    try {
      const planned = await route(fx, "GET", "remove-plan");
      // SAFETY: the route's checked 200 plan response has these closed fields.
      const plan = await planned.json() as { ok: boolean; planToken: string; path: string; changedCount: number; vendorConditionalRemoval: boolean };
      expect(planned.status).toBe(200);
      expect(plan).toMatchObject({ ok: true, path: fx.checkout, changedCount: 0, vendorConditionalRemoval: false });
      const removed = await route(fx, "POST", "remove", { requestId: "stable-remove-id", planToken: plan.planToken, force: false });
      expect(removed.status).toBe(200);
      expect(await removed.json()).toMatchObject({ ok: true, type: "worktree_removed", workspaceId: "linked-space", path: fx.checkout, forced: false, operation: { requestId: "stable-remove-id", capability: "worktreeRemove", state: "succeeded" } });
      expect(fx.removeCalls()).toBe(1);
      expect(await Bun.file(join(fx.checkout, "tracked.txt")).exists()).toBe(false);
      const { stdout } = await execFile("git", ["-C", fx.repo, "branch", "--list", "topic"]);
      expect(stdout.trim()).toBe("topic");
      const replay = await route(fx, "POST", "remove", { requestId: "stable-remove-id", planToken: plan.planToken, force: false });
      expect(replay.status).toBe(200);
      expect(fx.removeCalls()).toBe(1);
    } finally { await rm(fx.root, { recursive: true, force: true }); }
  });

  test("dirty and untracked entries require a separate force plan; changes to evidence stale a clean plan", async () => {
    const fx = await fixture(true);
    try {
      const dirty = await route(fx, "GET", "remove-plan");
      expect(dirty.status).toBe(409);
      expect(await dirty.json()).toMatchObject({ code: "worktree.dirty_worktree_requires_force", forceAvailable: true, changedCount: 1 });
      const forced = await route(fx, "GET", "remove-plan?force=true");
      expect(forced.status).toBe(200);
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const forcePlan = await forced.json() as { planToken: string };
      const forcedRemoval = await route(fx, "POST", "remove", { requestId: "force-remove-id", planToken: forcePlan.planToken, force: true });
      expect(forcedRemoval.status).toBe(200);
      expect(fx.removeCalls()).toBe(1);
    } finally { await rm(fx.root, { recursive: true, force: true }); }

    const stale = await fixture();
    try {
      const planned = await route(stale, "GET", "remove-plan");
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const plan = await planned.json() as { planToken: string };
      await writeFile(join(stale.checkout, "raced.txt"), "changed after review\n");
      const refused = await route(stale, "POST", "remove", { requestId: "stale-remove-id", planToken: plan.planToken, force: false });
      expect(refused.status).toBe(409);
      expect(await refused.json()).toMatchObject({ code: "plan.changed" });
      expect(stale.removeCalls()).toBe(0);
    } finally { await rm(stale.root, { recursive: true, force: true }); }
  });

  test("a lost vendor reply is durably unknown and the same request ID never dispatches twice", async () => {
    const fx = await fixture(false, true);
    try {
      const planned = await route(fx, "GET", "remove-plan");
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const plan = await planned.json() as { planToken: string };
      const request = { requestId: "lost-remove-id", planToken: plan.planToken, force: false };
      const lost = await route(fx, "POST", "remove", request);
      expect(lost.status).toBe(202);
      expect(await lost.json()).toMatchObject({ ok: false, code: "worktree.outcome_unknown", operation: { state: "unknown", requestId: request.requestId } });
      const retry = await route(fx, "POST", "remove", request);
      expect(retry.status).toBe(202);
      expect(fx.removeCalls()).toBe(1);
    } finally { await rm(fx.root, { recursive: true, force: true }); }
  });

  test("the main checkout is never a removal target and changed pane identities stale the plan", async () => {
    const main = await fixture();
    try {
      main.state.spaces[0]!.folder = main.repo;
      const refused = await route(main, "GET", "remove-plan");
      expect(refused.status).toBe(409);
      expect(await refused.json()).toMatchObject({ code: "worktree.not_linked_worktree" });
      expect(main.removeCalls()).toBe(0);
    } finally { await rm(main.root, { recursive: true, force: true }); }

    const panes = await fixture();
    try {
      const planned = await route(panes, "GET", "remove-plan");
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const plan = await planned.json() as { planToken: string };
      // SAFETY: the fixture intentionally injects a changed pane identity; the route reads only these three fields.
      panes.state.panes.push({ paneId: "new-pane", spaceId: "linked-space", terminalIncarnation: "new-incarnation" } as never);
      const refused = await route(panes, "POST", "remove", { requestId: "pane-race-id", planToken: plan.planToken, force: false });
      expect(refused.status).toBe(409);
      expect(await refused.json()).toMatchObject({ code: "plan.changed" });
      expect(panes.removeCalls()).toBe(0);
    } finally { await rm(panes.root, { recursive: true, force: true }); }
  });

  test("concurrent removal is refused and a repository-root symlink substitution never dispatches", async () => {
    const busy = await fixture();
    try {
      const planned = await route(busy, "GET", "remove-plan");
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const plan = await planned.json() as { planToken: string };
      busy.holdRemove();
      const inFlight = route(busy, "POST", "remove", { requestId: "busy-remove-id", planToken: plan.planToken, force: false });
      await busy.removeEntered;
      const blocked = await route(busy, "GET", "remove-plan");
      expect(blocked.status).toBe(409);
      expect(await blocked.json()).toMatchObject({ code: "worktree.worktree_operation_in_progress" });
      busy.releaseRemoval();
      expect((await inFlight).status).toBe(200);
      expect(busy.removeCalls()).toBe(1);
    } finally { busy.releaseRemoval(); await rm(busy.root, { recursive: true, force: true }); }

    if (process.platform === "win32") return;
    const substituted = await fixture();
    try {
      const planned = await route(substituted, "GET", "remove-plan");
      // SAFETY: the asserted 200 response is a removal plan with a plan token.
      const plan = await planned.json() as { planToken: string };
      await rename(substituted.checkout, `${substituted.checkout}-saved`);
      await symlink(substituted.repo, substituted.checkout);
      const refused = await route(substituted, "POST", "remove", { requestId: "symlink-remove-id", planToken: plan.planToken, force: false });
      expect(refused.status).toBe(409);
      expect(await refused.json()).toMatchObject({ code: "plan.changed" });
      expect(substituted.removeCalls()).toBe(0);
    } finally { await rm(substituted.root, { recursive: true, force: true }); }
  });
});


describe("worktree removal final original-actor admission", () => {
  for (const change of ["expiry", "runtime", "write", "owner", "checkout"] as const) test(`post-preflight ${change} refuses before effect and same-ID never dispatches`, async () => {
    const fx = await fixture();
    try {
      // SAFETY: the real route returned its closed removal-plan response; only its declared token is used.
      const plan = await (await route(fx, "GET", "remove-plan")).json() as { planToken: string };
      fx.setBeforeDispatch(async () => {
        if (change === "expiry") fx.expire();
        else if (change === "runtime") fx.replaceRuntime();
        else if (change === "write") fx.revokeWrite();
        else if (change === "owner") fx.replaceOwner();
        else await writeFile(join(fx.checkout, "new-dirty.txt"), "changed after adapter preflight");
      });
      const request = { requestId: `race-${change}`, planToken: plan.planToken, force: false };
      const first = await route(fx, "POST", "remove", request);
      expect(first.status).toBeGreaterThanOrEqual(400);
      if (change === "expiry") expect(await first.json()).toMatchObject({ operation: { state: "refused", code: "plan.expired" } });
      expect(fx.removeCalls()).toBe(0);
      expect(await Bun.file(join(fx.checkout, "tracked.txt")).exists()).toBe(true);
      await route(fx, "POST", "remove", request);
      expect(fx.removeCalls()).toBe(0);
    } finally { await rm(fx.root, { recursive: true, force: true }); }
  });
});


test("closed owner refusals retain their exact code and durable no-effect receipt without force or replay inference", async () => {
  for (const code of ["dirty_worktree_requires_force", "worktree_operation_in_progress", "stale_worktree_operation", "repository_not_trusted", "ownership_refused"]) {
    const fx = await fixture();
    try {
      // SAFETY: a checked route plan has the declared bounded token.
      const plan = await (await route(fx, "GET", "remove-plan")).json() as { planToken: string };
      fx.setVendorRefusal(code);
      const body = { requestId: `known-${code}`, planToken: plan.planToken, force: false };
      const response = await route(fx, "POST", "remove", body);
      expect(response.status).toBe(409);
      expect(response.headers.get("x-collie-error-source")).toBe("collie");
      expect(response.headers.get("x-collie-error-code")).toBe(`worktree.${code}`);
      expect(await response.json()).toMatchObject({ ok: false, code: `worktree.${code}`, operation: { state: "refused", code: `worktree.${code}` } });
      expect(await Bun.file(join(fx.checkout, "tracked.txt")).exists()).toBe(true);
      expect((await route(fx, "POST", "remove", body)).status).toBe(409);
      expect(fx.removeCalls()).toBe(1);
    } finally { await rm(fx.root, { recursive: true, force: true }); }
  }
});
