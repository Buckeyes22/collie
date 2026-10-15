import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, realpath, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { MuxLayoutApplyRequest, MuxLayoutCreated, MuxPortableLayout, MuxPortableLayoutNode, MuxSnapshot } from "../mux/types.ts";
import { declareCapabilities, muxOk, muxRefused } from "../mux/types.ts";
import { OperationStore } from "./operations.ts";
import { PlanStore } from "./plans.ts";
import { serveLayoutRecipeRoute, type LayoutRecipeCaller } from "./layout-recipes.ts";
import type { Launcher } from "../types.ts";

const OWNER = "a".repeat(64);
const tree: MuxPortableLayout = {
  workspaceId: "w1", tabId: "t1", focusedPaneId: "p1", zoomed: false,
  root: { type: "split", direction: "right", ratio: 0.5,
    first: { type: "pane", label: "one", cwd: null, paneId: "p1", command: null, env: {} },
    second: { type: "pane", label: "two", cwd: null, paneId: "p2", command: null, env: {} } },
};

describe("portable layout route contracts", () => {
  let stateDir: string | undefined;
  afterEach(async () => { if (stateDir) await rm(stateDir, { recursive: true, force: true }); stateDir = undefined; });

  async function fixture(options: { readonly ratioReply?: "apply" | "unchanged" | "wrong-tab"; readonly applyReply?: "success" | "wrong-tab" | "wrong-count";
    readonly layout?: MuxPortableLayout; readonly denyDeviceRead?: boolean; readonly stops?: number; readonly launchers?: readonly Launcher[] } = {}) {
    stateDir = await realpath(await mkdtemp(join(tmpdir(), "collie-layout-recipes-")));
    const operations = new OperationStore(stateDir);
    expect(await operations.prepare()).toBe(true);
    let ratioRequest: { tabId: string; paneId: string | null; path: readonly boolean[]; ratio: number } | null = null;
    let ratioCalls = 0;
    let applyCalls = 0;
    let now = 0;
    let exportHook: ((count: number) => Promise<void>) | null = null;
    let exportCalls = 0;
    let resolveCalls = 0;
    // The export contract resolves real workspace folders on every supported host.
    const workspaceRoot = stateDir;
    const inOwnedRoot = (node: MuxPortableLayoutNode): MuxPortableLayoutNode => node.type === "split"
      ? { ...node, first: inOwnedRoot(node.first), second: inOwnedRoot(node.second) }
      : { ...node, cwd: node.cwd === "/tmp" ? workspaceRoot : node.cwd };
    let currentLayout = { ...(options.layout ?? tree), root: inOwnedRoot((options.layout ?? tree).root) };
    const updateRatio = (node: MuxPortableLayoutNode, path: readonly boolean[], ratio: number): MuxPortableLayoutNode => {
      if (path.length === 0) return node.type === "split" ? { ...node, ratio } : node;
      if (node.type !== "split") return node;
      return path[0] ? { ...node, second: updateRatio(node.second, path.slice(1), ratio) } : { ...node, first: updateRatio(node.first, path.slice(1), ratio) };
    };
    const stops = Array.from({ length: options.stops ?? 1 }, (_, index) => ({ paneId: `p${index + 1}`, tabId: "t1", spaceId: "w1", spaceLabel: "Workspace", spaceNumber: 1, cwd: workspaceRoot, focused: index === 0, alive: true,
      agent: "shell", status: "idle" as const, terminalIncarnation: "a".repeat(64), paneLabel: `old-${index}`.padEnd(128, "x") }));
    const snapshot = {
      spaces: [{ spaceId: "w1", number: 1, label: "Workspace", focused: true, activeTabId: "t1", tabCount: 1, paneCount: stops.length, folder: workspaceRoot }],
      tabs: [{ tabId: "t1", spaceId: "w1", number: 1, label: "Tab", focused: true, paneCount: stops.length }],
      panes: stops,
    } satisfies MuxSnapshot;
    const mux = {
      capabilities: declareCapabilities({ supports: ["portableLayouts"], topologyLatency: { kind: "push" } }),
      layoutRuntimeIdentity: () => "runtime-one",
      snapshot: async () => snapshot,
      exportPortableLayout: async () => { exportCalls++; await exportHook?.(exportCalls); return muxOk(currentLayout); },
      applyPortableLayout: async (request: MuxLayoutApplyRequest, admit: (id: string, next: typeof snapshot) => boolean | Promise<boolean>) => {
        if (!await admit("runtime-one", snapshot)) return muxRefused("expired or stale");
        applyCalls++;
        const paneIds = request.root.type === "pane" ? ["p1"] : ["p1", "p2"];
        return muxOk({ workspaceId: "w1", tabId: options.applyReply === "wrong-tab" ? "other-tab" : "t1",
          paneIds: options.applyReply === "wrong-count" ? [...paneIds, "p-extra"] : paneIds, focusedPaneId: paneIds[0]! } satisfies MuxLayoutCreated);
      },
      setSplitRatio: async (request: { tabId: string; paneId: string | null; path: readonly boolean[]; ratio: number }, admit: (id: string, next: typeof snapshot) => boolean | Promise<boolean>) => {
        if (!await admit("runtime-one", snapshot)) return { ok: false as const, reason: "refused" as const, detail: "stale" };
        ratioCalls++;
        ratioRequest = request;
        if (options.ratioReply === "unchanged") return muxOk(currentLayout);
        if (options.ratioReply === "wrong-tab") return muxOk({ ...currentLayout, tabId: "other-tab" });
        currentLayout = { ...currentLayout, root: updateRatio(currentLayout.root, request.path, request.ratio) };
        return muxOk(currentLayout);
      },
    };
    // SAFETY: This fixture supplies only the mux methods the portable-layout route invokes.
    const runtime = { host: "host-one", session: null, mux };
    const caller: LayoutRecipeCaller = {
      resolve: async () => { resolveCalls++; return runtime; },
      gate: (level) => options.denyDeviceRead && level === "device-read" ? new Response("device denied", { status: 403 }) : null,
      pairedIdentity: () => OWNER,
      launchers: async () => options.launchers ?? [],
      audit: () => {},
    };
    const plans = new PlanStore(() => now);
    return { caller, operations, plans, getRatio: () => ratioRequest, getCalls: () => ratioCalls,
      getApplyCalls: () => applyCalls, getResolveCalls: () => resolveCalls, getExportCalls: () => exportCalls,
      setNow: (value: number) => { now = value; }, setExportHook: (hook: ((count: number) => Promise<void>) | null) => { exportHook = hook; } };
  }

  async function planRatio(f: Awaited<ReturnType<typeof fixture>>, ratio = 0.625) {
    const url = new URL("https://collie/api/layout/plan?host=host-one");
    const response = await serveLayoutRecipeRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ kind: "split-ratio", tabId: "t1", paneId: null, path: [], ratio }) }), url, f.caller, { plans: f.plans, operations: f.operations });
    // SAFETY: callers assert a successful plan status before using this server-returned opaque token.
    return { response, plan: await response?.json() as { planToken: string; expiresAt: number } };
  }

  async function executeRatio(f: Awaited<ReturnType<typeof fixture>>, planToken: string, requestId = "ratio-once", ratio = 0.625) {
    const url = new URL("https://collie/api/tab/t1/layout/split-ratio?host=host-one");
    return serveLayoutRecipeRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ requestId, planToken, path: [], ratio }) }), url, f.caller, { plans: f.plans, operations: f.operations });
  }

  async function planApply(f: Awaited<ReturnType<typeof fixture>>, root: MuxPortableLayoutNode = tree.root) {
    const url = new URL("https://collie/api/layout/plan?host=host-one");
    const response = await serveLayoutRecipeRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ root, workspaceId: "w1", tabId: "t1", tabLabel: null }) }), url, f.caller, { plans: f.plans, operations: f.operations });
    // SAFETY: callers assert a successful plan status before using this server-returned opaque token.
    return { response, plan: await response?.json() as { planToken: string; expiresAt: number } };
  }

  async function executeApply(f: Awaited<ReturnType<typeof fixture>>, planToken: string, requestId = "apply-once", root: MuxPortableLayoutNode = tree.root) {
    const url = new URL("https://collie/api/layout/apply?host=host-one");
    return serveLayoutRecipeRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ requestId, planToken, root, workspaceId: "w1", tabId: "t1", tabLabel: null }) }), url, f.caller, { plans: f.plans, operations: f.operations });
  }

  test("invalid split paths fail before plan or mutation and preserve boolean path shape", async () => {
    const f = await fixture();
    const malformed = await serveLayoutRecipeRoute(new Request("https://collie/api/layout/plan?host=host-one", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ kind: "split-ratio", tabId: "t1", paneId: null, path: [0], ratio: 0.5 }),
    }), new URL("https://collie/api/layout/plan?host=host-one"), f.caller, { plans: f.plans, operations: f.operations });
    expect(malformed?.status).toBe(400);
    expect(await malformed?.json()).toMatchObject({ code: "layout.invalid_ratio" });

    const { response: planned, plan } = await planRatio(f);
    expect(planned?.status).toBe(200);
    const applied = await executeRatio(f, plan.planToken);
    expect(applied?.status).toBe(200);
    expect(f.getRatio()).toEqual({ tabId: "t1", paneId: null, path: [], ratio: 0.625 });
    const retry = await executeRatio(f, plan.planToken);
    expect(retry?.status).toBe(200);
    expect(f.getCalls()).toBe(1);
    const secondRequest = await executeRatio(f, plan.planToken, "ratio-twice");
    expect(secondRequest?.status).toBe(409);
    expect(f.getCalls()).toBe(1);
  });

  test("device-read is enforced before resolving sensitive local export and plan data", async () => {
    const f = await fixture({ denyDeviceRead: true });
    for (const [url, init] of [
      [new URL("https://collie/api/tab/t1/layout/export?host=host-one"), { method: "GET" }],
      [new URL("https://collie/api/layout/plan?host=host-one"), { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ kind: "split-ratio", tabId: "t1", paneId: null, path: [], ratio: 0.625 }) }],
    ] as const) {
      const response = await serveLayoutRecipeRoute(new Request(url, init), url, f.caller, { plans: f.plans, operations: f.operations });
      expect(response?.status).toBe(403);
    }
    expect(f.getResolveCalls()).toBe(0);
  });

  test("export and plan redact launch secrets and remove live selection identities", async () => {
    const secret = "PortableLayoutSecretValue123456789";
    const command = ["bash", `--token=${secret}`];
    const sensitive: MuxPortableLayout = { ...tree, workspaceId: "w1", tabId: "t1", focusedPaneId: "p1",
      root: { type: "pane", label: "Agent", cwd: "/tmp", paneId: "p1", command, env: { SESSION_TOKEN: secret, SAFE: `password=${secret}` } } };
    const f = await fixture({ layout: sensitive, launchers: [{ command: command.join(" "), label: "Shell" }] });
    const exportUrl = new URL("https://collie/api/tab/t1/layout/export?host=host-one");
    const exported = await serveLayoutRecipeRoute(new Request(exportUrl), exportUrl, f.caller, { plans: f.plans, operations: f.operations });
    // SAFETY: the handler's successful export response is the route's closed `{ layout }` DTO.
    const exportBody = await exported?.json() as { layout: MuxPortableLayout };
    expect(exportBody.layout.workspaceId).toBe("");
    expect(exportBody.layout.tabId).toBe("");
    expect(exportBody.layout.focusedPaneId).toBe("");
    expect(exportBody.layout.root.type).toBe("pane");
    if (exportBody.layout.root.type !== "pane") throw new Error("portable export did not retain its pane root");
    expect(exportBody.layout.root.paneId).toBeNull();
    expect(exportBody.layout.root.env).toEqual({});
    expect(JSON.stringify(exportBody)).not.toContain(secret);

    const planUrl = new URL("https://collie/api/layout/plan?host=host-one");
    const importRoot = { ...sensitive.root, cwd: null, env: { SAFE: `password=${secret}` } };
    const planned = await serveLayoutRecipeRoute(new Request(planUrl, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ root: importRoot, workspaceId: "w1", tabId: "t1", tabLabel: null }) }), planUrl, f.caller, { plans: f.plans, operations: f.operations });
    expect(planned?.status).toBe(200);
    // SAFETY: status 200 above confirms the handler's closed plan response before reading its fields.
    const planBody = await planned?.json() as { workspaceId: string; tabId: string | null; effects: { starts: { command: string }[] } };
    expect(planBody.workspaceId).toBe("");
    expect(planBody.tabId).toBeNull();
    expect(planBody.effects.starts[0]?.command).toContain("--token=");
    expect(planBody.effects.starts[0]?.command).not.toContain(secret);
  });

  test.each(["unchanged", "wrong-tab"] as const)("a post-dispatch %s ratio reply becomes unknown and is never retried", async (ratioReply) => {
    const f = await fixture({ ratioReply });
    const { plan } = await planRatio(f);
    const response = await executeRatio(f, plan.planToken, `ratio-${ratioReply}`);
    expect(response?.status).toBe(202);
    expect(await response?.json()).toMatchObject({ ok: false, code: "layout.outcome_unknown", operation: { state: "unknown" } });
    expect(f.getCalls()).toBe(1);
    const retry = await executeRatio(f, plan.planToken, `ratio-${ratioReply}`);
    expect(retry?.status).toBe(202);
    expect(f.getCalls()).toBe(1);
  });

  test("a plan that expires during the final asynchronous evidence read does not dispatch ratio RPC", async () => {
    const f = await fixture();
    const { plan } = await planRatio(f);
    f.setExportHook(async (count) => { if (count >= 3) f.setNow(plan.expiresAt); });
    const response = await executeRatio(f, plan.planToken, "ratio-expired-final");
    expect(response?.status).toBe(409);
    expect(f.getCalls()).toBe(0);
  });

  test.each(["wrong-tab", "wrong-count"] as const)("a post-dispatch apply reply with %s identities stays unknown", async (applyReply) => {
    const f = await fixture({ applyReply });
    const { plan } = await planApply(f);
    const response = await executeApply(f, plan.planToken, `apply-${applyReply}`);
    expect(response?.status).toBe(202);
    expect(await response?.json()).toMatchObject({ ok: false, code: "layout.outcome_unknown", operation: { state: "unknown" } });
    expect(f.getApplyCalls()).toBe(1);
  });

  test("a plan that expires during the final asynchronous apply evidence read does not dispatch apply RPC", async () => {
    const f = await fixture();
    const { plan } = await planApply(f);
    f.setExportHook(async (count) => { if (count >= 3) f.setNow(plan.expiresAt); });
    const response = await executeApply(f, plan.planToken, "apply-expired-final");
    expect(response?.status).toBe(409);
    expect(f.getApplyCalls()).toBe(0);
  });

  test("oversized exported geometry is rejected before serialization to the client", async () => {
    const command = "x".repeat(2048);
    const pane = (index: number): MuxPortableLayoutNode => ({ type: "pane", label: `pane-${index}`, cwd: null, paneId: `p${index}`, command: [command], env: {} });
    let root: MuxPortableLayoutNode = pane(0);
    for (let index = 1; index < 32; index++) root = { type: "split", direction: "right", ratio: 0.5, first: root, second: pane(index) };
    const f = await fixture({ layout: { ...tree, root } });
    const url = new URL("https://collie/api/tab/t1/layout/export?host=host-one");
    const response = await serveLayoutRecipeRoute(new Request(url), url, f.caller, { plans: f.plans, operations: f.operations });
    expect(response?.status).toBe(413);
    expect(await response?.json()).toMatchObject({ code: "layout.response_too_large" });
  });

  test("oversized aggregate recipe effects are rejected even when the import body fits", async () => {
    const command = "a".repeat(1770);
    const balanced = (first: number, count: number): MuxPortableLayoutNode => {
      if (count === 1) return { type: "pane", label: "L".repeat(128), cwd: null, paneId: null, command: [command], env: {} };
      const half = count / 2;
      return { type: "split", direction: "right", ratio: 0.5, first: balanced(first, half), second: balanced(first + half, half) };
    };
    const root = balanced(0, 32);
    const requestBody = JSON.stringify({ root, workspaceId: "w1", tabId: "t1", tabLabel: null });
    expect(Buffer.byteLength(requestBody)).toBeLessThan(64 * 1024);
    const f = await fixture({ stops: 64, launchers: [{ command, label: "declared" }] });
    const url = new URL("https://collie/api/layout/plan?host=host-one");
    const response = await serveLayoutRecipeRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: requestBody }),
      url, f.caller, { plans: f.plans, operations: f.operations });
    expect(response?.status).toBe(413);
    expect(await response?.json()).toMatchObject({ code: "layout.response_too_large" });
  });
});
