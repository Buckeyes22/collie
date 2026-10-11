import { describe, expect, test } from "bun:test";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { FakeHerdr } from "../mux/herdr/fixture.ts";
import { servePaneLayoutRoute } from "./layout.ts";
import { OperationStore } from "./operations.ts";
import { PlanStore, type PlanBinding, type ControlPlan } from "./plans.ts";
import type { JsonValue } from "../json.ts";
import type { FolderLists, FolderSurface } from "../folders.ts";

const owner = "c".repeat(64);
function request(pathValue: string, body?: JsonValue): Request {
  return new Request(`https://collie${pathValue}`, body === undefined ? undefined : { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
}
function path(paneId: string, action = ""): string {
  return `/api/pane/${encodeURIComponent(paneId)}/layout${action}`;
}
function caller(mux: HerdrMux) {
  return {
    gate: () => null,
    pairedIdentity: () => owner,
    audit: () => {},
    resolve: async () => ({ host: "host-one", session: "work", mux }),
  };
}

describe("pane layout scoped routes", () => {
  test("read, plan and mutation are closed, owner-bound and focus-off", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const snapshot = await mux.snapshot();
    const source = snapshot.panes[0]!;
    const focusedBefore = snapshot.panes.find((pane) => pane.focused)?.paneId;
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const get = await servePaneLayoutRoute(request(path(source.paneId), undefined), new URL(`https://collie${path(source.paneId)}`), caller(mux), stores);
      expect(get?.status).toBe(200);
      expect(await get!.json()).toMatchObject({ ok: true, result: { kind: "layout", layout: { workspaceId: source.spaceId, tabId: source.tabId } } });

      const action = { kind: "split", direction: "right", ratio: 0.4, workspaceId: source.spaceId };
      const invalidPlan = await servePaneLayoutRoute(request(path(source.paneId, "/plan"), { action: { ...action, ratio: 1.01 } }), new URL(`https://collie${path(source.paneId, "/plan")}`), caller(mux), stores);
      expect(invalidPlan?.status).toBe(400);
      const planResponse = await servePaneLayoutRoute(request(path(source.paneId, "/plan"), { action }), new URL(`https://collie${path(source.paneId, "/plan")}`), caller(mux), stores);
      expect(planResponse?.status).toBe(200);
      // SAFETY: this response is the just-validated plan endpoint's successful JSON shape.
      const plan = await planResponse!.json() as { planToken: string; effects: { focusChanges: boolean } };
      expect(plan.effects.focusChanges).toBe(false);
      const finalBody = { requestId: "layout-intent-1", planToken: plan.planToken, action };
      const finalPath = path(source.paneId, "/split");
      const completed = await servePaneLayoutRoute(request(finalPath, finalBody), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(completed?.status).toBe(200);
      expect(await completed!.json()).toMatchObject({ ok: true, operation: { state: "succeeded" } });
      const after = await mux.snapshot();
      expect(after.panes).toHaveLength(snapshot.panes.length + 1);
      expect(after.panes.find((pane) => pane.focused)?.paneId).toBe(focusedBefore);

      const duplicate = await servePaneLayoutRoute(request(finalPath, finalBody), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(duplicate?.status).toBe(200);
      expect((await mux.snapshot()).panes).toHaveLength(after.panes.length);
      const altered = await servePaneLayoutRoute(request(finalPath, { ...finalBody, action: { ...action, direction: "down" } }), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(altered?.status).toBe(409);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("an effect followed by transport loss is durable unknown and same-ID reconciliation never sends again", async () => {
    const fake = new FakeHerdr();
    fake.splitAfterEffectFailure = true;
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const action = { kind: "split", direction: "down", ratio: null, workspaceId: source.spaceId };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-unknown-"));
    try {
      const firstStores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const planResponse = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), firstStores);
      // SAFETY: this response is the just-validated plan endpoint's successful JSON shape.
      const plan = await planResponse!.json() as { planToken: string };
      const finalPath = path(source.paneId, "/split");
      const body = { requestId: "layout-unknown-1", planToken: plan.planToken, action };
      const uncertain = await servePaneLayoutRoute(request(finalPath, body), new URL(`https://collie${finalPath}`), caller(mux), firstStores);
      expect(uncertain?.status).toBe(202);
      expect(await uncertain!.json()).toMatchObject({ code: "layout.outcome_unknown" });
      const afterEffect = await mux.snapshot();
      expect(afterEffect.panes).toHaveLength(4);

      const restartedStores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const reconciled = await servePaneLayoutRoute(request(finalPath, body), new URL(`https://collie${finalPath}`), caller(mux), restartedStores);
      expect(reconciled?.status).toBe(202);
      expect((await mux.snapshot()).panes).toHaveLength(afterEffect.panes.length);
      expect((await restartedStores.operations.inspect(body.requestId, owner, "host-one", "work"))?.state).toBe("unknown");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("malformed post-effect zoom and move replies stay unknown and expose no move aliases", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-malformed-effect-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const zoomAction = { kind: "zoom", mode: "on" };
      const zoomPlanPath = path(source.paneId, "/plan");
      const zoomPlanResponse = await servePaneLayoutRoute(request(zoomPlanPath, { action: zoomAction }), new URL(`https://collie${zoomPlanPath}`), caller(mux), stores);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const zoomPlan = await zoomPlanResponse!.json() as { planToken: string };
      let zoomCalls = 0;
      fake.zoomPane = async () => {
        zoomCalls++;
        // Missing required zoom_changed/focus_changed makes an HTTP-success-shaped vendor reply
        // ambiguous after the one named effect could have happened.
        return { type: "pane_zoom", zoom: { changed: true, zoomed: true, pane_id: source.paneId,
          focused_pane_id: source.paneId, layout: { workspace_id: source.spaceId, tab_id: source.tabId,
            zoomed: true, area: { x: 0, y: 0, width: 80, height: 24 }, focused_pane_id: source.paneId,
            panes: [{ pane_id: source.paneId, focused: true, rect: { x: 0, y: 0, width: 80, height: 24 } }], splits: [] } } };
      };
      const zoomPath = path(source.paneId, "/zoom");
      const zoomBody = { requestId: "layout-malformed-zoom", planToken: zoomPlan.planToken, action: zoomAction };
      const zoomReply = await servePaneLayoutRoute(request(zoomPath, zoomBody), new URL(`https://collie${zoomPath}`), caller(mux), stores);
      expect(zoomReply?.status).toBe(202);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const zoomPayload = await zoomReply!.json() as { code: string; operation: { state: string; result?: unknown } };
      expect(zoomPayload).toMatchObject({ code: "layout.outcome_unknown", operation: { state: "unknown" } });
      expect(zoomPayload.operation).not.toHaveProperty("result");
      expect(zoomCalls).toBe(1);
      expect((await servePaneLayoutRoute(request(zoomPath, zoomBody), new URL(`https://collie${zoomPath}`), caller(mux), stores))?.status).toBe(202);
      expect(zoomCalls).toBe(1);

      await mux.reachable();
      const moveAction = { kind: "move", destination: { type: "new_workspace", label: "review", tabLabel: "main" } };
      const movePlanPath = path(source.paneId, "/plan");
      const movePlanResponse = await servePaneLayoutRoute(request(movePlanPath, { action: moveAction }), new URL(`https://collie${movePlanPath}`), caller(mux), stores);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const movePlan = await movePlanResponse!.json() as { planToken: string };
      let moveCalls = 0;
      const move = fake.movePane.bind(fake);
      fake.movePane = async (params) => {
        moveCalls++;
        // SAFETY: the fixture constructed this closed successful response immediately above.
        const result = await move(params) as { type: string; move_result: import("../json.ts").JsonObject };
        return { ...result, move_result: { ...result.move_result, previous_tab_id: "forged-old-tab" } };
      };
      const movePath = path(source.paneId, "/move");
      const moveBody = { requestId: "layout-malformed-move", planToken: movePlan.planToken, action: moveAction };
      const moveReply = await servePaneLayoutRoute(request(movePath, moveBody), new URL(`https://collie${movePath}`), caller(mux), stores);
      expect(moveReply?.status).toBe(202);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const movePayload = await moveReply!.json() as { code: string; operation: { state: string; result?: unknown } };
      expect(movePayload).toMatchObject({ code: "layout.outcome_unknown", operation: { state: "unknown" } });
      expect(movePayload.operation).not.toHaveProperty("result");
      expect(JSON.stringify(movePayload)).not.toContain("forged-old-tab");
      expect(moveCalls).toBe(1);
      expect((await servePaneLayoutRoute(request(movePath, moveBody), new URL(`https://collie${movePath}`), caller(mux), stores))?.status).toBe(202);
      expect(moveCalls).toBe(1);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("cross-workspace move saves and replays only bounded pane aliases after repository recreation", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const before = await mux.snapshot();
    const source = before.panes[0]!;
    const action = { kind: "move", destination: { type: "new_workspace", label: "handoff", tabLabel: "main" } };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-move-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const planResponse = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
      // SAFETY: this response is the just-validated plan endpoint's successful JSON shape.
      const plan = await planResponse!.json() as { planToken: string };
      const finalPath = path(source.paneId, "/move");
      const body = { requestId: "layout-move-1", planToken: plan.planToken, action };
      const moved = await servePaneLayoutRoute(request(finalPath, body), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(moved?.status).toBe(200);
      // SAFETY: the immediately preceding 200 response is the route's validated move success shape.
      const reply = await moved!.json() as { move: { paneId: string; previousPaneId: string; previousTabId: string; previousWorkspaceId: string } };
      expect(reply.move).toMatchObject({ previousPaneId: source.paneId, previousTabId: source.tabId, previousWorkspaceId: source.spaceId });
      expect(reply.move.paneId).not.toBe(source.paneId);
      expect((await mux.snapshot()).panes.some((pane) => pane.paneId === reply.move.paneId)).toBe(true);

      const restarted = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const replay = await servePaneLayoutRoute(request(finalPath, body), new URL(`https://collie${finalPath}`), caller(mux), restarted);
      expect(replay?.status).toBe(200);
      expect(await replay!.json()).toMatchObject({ move: reply.move });
      expect(await restarted.operations.inspect(body.requestId, owner, "host-one", "work")).toMatchObject({ state: "succeeded", result: { kind: "pane-move", paneId: reply.move.paneId } });
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("a move plan binds destination geometry and refuses before dispatch when only that geometry changes", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const snapshot = await mux.snapshot();
    const source = snapshot.panes[0]!;
    const target = snapshot.panes.find((pane) => pane.spaceId !== source.spaceId)!;
    const action = { kind: "move", destination: { type: "tab", tabId: target.tabId, split: "right", targetPaneId: target.paneId, ratio: 0.5 } };
    const routePlan = path(source.paneId, "/plan");
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-dest-geometry-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planResponse = await servePaneLayoutRoute(request(routePlan, { action }), new URL(`https://collie${routePlan}`), caller(mux), stores);
      expect(planResponse?.status).toBe(200);
      // SAFETY: status 200 is the route's validated successful plan response.
      const plan = await planResponse!.json() as { planToken: string };
      const read = fake.readPaneLayout.bind(fake);
      fake.readPaneLayout = async (paneId) => {
        const value = await read(paneId);
        if (paneId !== target.paneId) return value;
        // SAFETY: the fake's successful pane.layout method returns this exact wire fixture shape.
        const row = value as { type: string; layout: { area: { width: number } } };
        return { ...row, layout: { ...row.layout, area: { ...row.layout.area, width: row.layout.area.width + 1 } } };
      };
      const finalPath = path(source.paneId, "/move");
      const before = await mux.snapshot();
      const result = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-dest-change", planToken: plan.planToken, action }), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(result?.status).toBe(409);
      expect((await mux.snapshot()).panes.map((pane) => pane.paneId).toSorted()).toEqual(before.panes.map((pane) => pane.paneId).toSorted());
      expect(await stores.operations.inspect("layout-dest-change", owner, "host-one", "work")).toMatchObject({ state: "refused", code: "plan.changed" });
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("a destination geometry change between its read and final coherent verification prevents dispatch", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const snapshot = await mux.snapshot();
    const source = snapshot.panes[0]!;
    const target = snapshot.panes.find((pane) => pane.spaceId !== source.spaceId)!;
    const action = { kind: "move", destination: { type: "tab", tabId: target.tabId, split: "down", targetPaneId: target.paneId, ratio: 0.4 } };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-dest-race-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const plan = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
      expect(plan?.status).toBe(200);
      // SAFETY: status 200 is the route's validated successful plan response.
      const token = (await plan!.json() as { planToken: string }).planToken;
      const originalRead = fake.readPaneLayout.bind(fake);
      let destinationReads = 0;
      fake.readPaneLayout = async (paneId) => {
        const value = await originalRead(paneId);
        if (paneId !== target.paneId || ++destinationReads !== 2) return value;
        // SAFETY: the fake's successful pane.layout method returns this exact wire fixture shape.
        const row = value as { type: string; layout: { area: { width: number } } };
        return { ...row, layout: { ...row.layout, area: { ...row.layout.area, width: row.layout.area.width + 1 } } };
      };
      const before = await mux.snapshot();
      const finalPath = path(source.paneId, "/move");
      const result = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-dest-race", planToken: token, action }), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(result?.status).toBe(409);
      expect((await mux.snapshot()).panes.map((pane) => pane.paneId).toSorted()).toEqual(before.panes.map((pane) => pane.paneId).toSorted());
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("the mux supplying the final witness is the only mux that dispatches", async () => {
    const oldFake = new FakeHerdr();
    const freshFake = new FakeHerdr();
    const oldMux = new HerdrMux(oldFake, () => []);
    const freshMux = new HerdrMux(freshFake, () => []);
    await oldMux.reachable(); await freshMux.reachable();
    const source = (await oldMux.snapshot()).panes[0]!;
    const action = { kind: "split", direction: "right", ratio: null, workspaceId: source.spaceId, targetPaneId: source.paneId };
    let resolves = 0;
    const switchingCaller = {
      gate: () => null,
      pairedIdentity: () => owner,
      audit: () => {},
      resolve: async () => ({ host: "host-one", session: "work", mux: ++resolves < 3 ? oldMux : freshMux }),
    };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-fresh-mux-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const plan = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), switchingCaller, stores);
      expect(plan?.status).toBe(200);
      // SAFETY: status 200 is the route's validated successful plan response.
      const token = (await plan!.json() as { planToken: string }).planToken;
      const finalPath = path(source.paneId, "/split");
      const result = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-fresh-mux", planToken: token, action }), new URL(`https://collie${finalPath}`), switchingCaller, stores);
      expect(result?.status).toBe(200);
      expect((await oldMux.snapshot()).panes).toHaveLength(3);
      expect((await freshMux.snapshot()).panes).toHaveLength(4);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("a write-gate revocation during the final witness blocks the effect", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const action = { kind: "zoom", mode: "on" };
    let denied = false;
    const gatedCaller = { ...caller(mux), gate: () => denied ? new Response("denied", { status: 403 }) : null };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-final-gate-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const planResponse = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), gatedCaller, stores);
      // SAFETY: this response is the just-validated plan endpoint's successful JSON shape.
      const token = (await planResponse!.json() as { planToken: string }).planToken;
      let mutations = 0;
      const zoom = fake.zoomPane.bind(fake);
      fake.zoomPane = async (...args) => { mutations++; return zoom(...args); };
      const read = fake.readPaneLayout.bind(fake);
      fake.readPaneLayout = async (paneId) => { const value = await read(paneId); denied = true; return value; };
      const finalPath = path(source.paneId, "/zoom");
      const result = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-gate-revoked", planToken: token, action }), new URL(`https://collie${finalPath}`), gatedCaller, stores);
      expect(result?.status).toBe(409);
      expect(mutations).toBe(0);
      expect(await stores.operations.inspect("layout-gate-revoked", owner, "host-one", "work")).toMatchObject({ state: "refused", code: "layout.authority_changed" });
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("source geometry changed during the adapter's final read is refused before the vendor call", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const action = { kind: "resize", direction: "right", amount: 0.1 };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-final-source-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const planned = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const token = (await planned!.json() as { planToken: string }).planToken;
      const originalRead = fake.readPaneLayout.bind(fake);
      let reads = 0;
      fake.readPaneLayout = async (paneId) => {
        const result = await originalRead(paneId);
        reads++;
        if (reads !== 3 || paneId !== source.paneId) return result;
        // SAFETY: FakeHerdr returns this declared pane_layout fixture, including its area width.
        const row = result as { type: string; layout: { area: { width: number } } };
        return { ...row, layout: { ...row.layout, area: { ...row.layout.area, width: row.layout.area.width + 1 } } };
      };
      let effects = 0;
      const resize = fake.resizePane.bind(fake);
      fake.resizePane = async (...args) => { effects++; return resize(...args); };
      const finalPath = path(source.paneId, "/resize");
      const response = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-final-source", planToken: token, action }), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(response?.status).toBe(409);
      expect(effects).toBe(0);
      expect(await stores.operations.inspect("layout-final-source", owner, "host-one", "work")).toMatchObject({ state: "refused" });
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("old Herdr runtimes and unpaired callers cannot create plans", async () => {
    const old = new FakeHerdr();
    old.layoutVersion = "0.9.2";
    const mux = new HerdrMux(old, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const url = new URL(`https://collie${path(source.paneId)}`);
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-old-"));
    try {
      const planPath = path(source.paneId, "/plan");
      const response = await servePaneLayoutRoute(request(planPath, { action: { kind: "zoom", mode: "toggle" } }), new URL(`https://collie${planPath}`), caller(mux), { plans: new PlanStore(), operations: new OperationStore(dir) });
      expect(response?.status).toBe(501);
      const denied = await servePaneLayoutRoute(request(planPath, { action: { kind: "zoom", mode: "toggle" } }), new URL(`https://collie${planPath}`), { ...caller(mux), pairedIdentity: () => null }, { plans: new PlanStore(), operations: new OperationStore(dir) });
      expect(denied?.status).toBe(403);
      expect(url.pathname).toBe(path(source.paneId));
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("oversized chunked plan bodies are cancelled before runtime resolution", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    let resolved = 0;
    let cancelled = false;
    const pathValue = path("w1:p1", "/plan");
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(16 * 1024 + 1)); },
      cancel() { cancelled = true; },
    });
    // SAFETY: Bun's RequestInit runtime supports the duplex field for streaming request bodies.
    const req = new Request(`https://collie${pathValue}`, { method: "POST", body: stream, duplex: "half" } as RequestInit);
    const response = await servePaneLayoutRoute(req, new URL(`https://collie${pathValue}`), {
      gate: () => null, pairedIdentity: () => owner, audit: () => {},
      resolve: async () => { resolved += 1; return { host: "host-one", session: "work", mux }; },
    }, { plans: new PlanStore(), operations: new OperationStore() });
    expect(response?.status).toBe(400);
    expect(resolved).toBe(0);
    expect(cancelled).toBe(true);
    expect(fake.writes()).toHaveLength(0);
  });

  test("split plans accept bounded literal environment values without echoing them and reject reserved names", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const pane = (await mux.snapshot()).panes[0]!;
    const route = path(pane.paneId, "/plan");
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-env-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const valid = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", ratio: null, env: { HERDR_ROLE: "worker\nreview" } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(valid?.status).toBe(200);
      const body = await valid!.text();
      expect(body).not.toContain("worker");
      const reserved = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: { HERDR_PANE_ID: "forged" } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(reserved?.status).toBe(400);
      const nonString = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: { COUNT: 3 } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(nonString?.status).toBe(400);
      const oversizedCount = Object.fromEntries(Array.from({ length: 33 }, (_, index) => [`KEY_${index}`, "x"]));
      const tooMany = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: oversizedCount } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(tooMany?.status).toBe(400);
      const oversizedValue = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: { VALUE: "x".repeat(1025) } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(oversizedValue?.status).toBe(400);
      const nul = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: { VALUE: "before\u0000after" } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(nul?.status).toBe(400);
      const unpaired = await servePaneLayoutRoute(request(route, { action: { kind: "split", direction: "right", env: { VALUE: "\uD800" } } }), new URL(`https://collie${route}`), caller(mux), stores);
      expect(unpaired?.status).toBe(400);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("split sends the exact canonical literal environment and server-owned focus parameters once", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const env = { HERDR_ROLE: "worker\nreview", EMPTY: "" };
    const action = { kind: "split", direction: "down", targetPaneId: source.paneId, workspaceId: source.spaceId, ratio: 0.37, env };
    const dir = await mkdtemp(join(tmpdir(), "collie-layout-env-effect-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
      const planPath = path(source.paneId, "/plan");
      const planResponse = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
      expect(planResponse?.status).toBe(200);
      // SAFETY: status 200 is the route's validated successful plan response.
      const token = (await planResponse!.json() as { planToken: string }).planToken;
      const finalPath = path(source.paneId, "/split");
      const result = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-env-effect", planToken: token, action }), new URL(`https://collie${finalPath}`), caller(mux), stores);
      expect(result?.status).toBe(200);
      expect(fake.lastSplitParameters).toMatchObject({ direction: "down", target_pane_id: source.paneId, workspace_id: source.spaceId, ratio: 0.37, env, focus: false, right_click: "herdr" });
      expect(fake.lastSplitParameters).not.toHaveProperty("cwd");
      expect(await result!.text()).not.toContain("worker");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("advertised split cwd is refused if owner folder membership changes at final adapter admission", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-layout-cwd-lease-"));
    const folder = join(root, "repo");
    await mkdir(folder);
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    let lists: FolderLists = { recent: [folder], favourites: [] };
    const folders: FolderSurface = {
      home: root,
      current: () => lists,
      recordRecent: async () => {},
      star: async () => ({ ok: true, lists, changed: false }),
    };
    const apply = mux.applyLayout.bind(mux);
    mux.applyLayout = async (action, finalAdmission) => {
      // All route-side asynchronous source/layout/folder preflight is complete before adapter entry.
      lists = { recent: [], favourites: [] };
      return apply(action, finalAdmission);
    };
    const storesDir = await mkdtemp(join(tmpdir(), "collie-layout-cwd-store-"));
    try {
      const stores = { plans: new PlanStore(), operations: new OperationStore(storesDir) };
      const action = { kind: "split", direction: "right", ratio: null, cwd: { kind: "advertised-folder", path: folder }, env: {} };
      const planPath = path(source.paneId, "/plan");
      const planned = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), { ...caller(mux), resolve: async () => ({ host: "host-one", session: "work", mux, folders }) }, stores);
      expect(planned?.status).toBe(200);
      // SAFETY: the fixture constructed this closed successful response immediately above.
      const token = (await planned!.json() as { planToken: string }).planToken;
      const before = await mux.snapshot();
      const finalPath = path(source.paneId, "/split");
      const applied = await servePaneLayoutRoute(request(finalPath, { requestId: "layout-cwd-retired", planToken: token, action }), new URL(`https://collie${finalPath}`), { ...caller(mux), resolve: async () => ({ host: "host-one", session: "work", mux, folders }) }, stores);
      expect(applied?.status).toBe(409);
      expect(fake.lastSplitParameters).toBeNull();
      expect((await mux.snapshot()).panes.map((pane) => pane.paneId).toSorted()).toEqual(before.panes.map((pane) => pane.paneId).toSorted());
    } finally {
      await rm(storesDir, { recursive: true, force: true });
      await rm(root, { recursive: true, force: true });
    }
  });
});

describe("corrective5 final adapter witness", () => {
  test("late handshake changes geometry, occupant or source cwd before the final source read: zero effects", async () => {
    for (const change of ["geometry", "occupant", "cwd"]) {
      const fake = new FakeHerdr();
      const mux = new HerdrMux(fake, () => []);
      await mux.reachable();
      const source = (await mux.snapshot()).panes[0]!;
      const dir = await mkdtemp(join("/var/tmp", "collie-layout-final-witness-"));
      try {
        const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const action = { kind: "resize", direction: "right", amount: 0.1 };
        const planPath = path(source.paneId, "/plan");
        const reply = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
        // SAFETY: successful closed plan from this fixture's actual route.
        const plan = await reply!.json() as { planToken: string };
        let armed = false; let changed = false; let effects = 0;
        let entered!: () => void; let release!: () => void;
        const blocked = new Promise<void>((resolve) => { release = resolve; });
        const reached = new Promise<void>((resolve) => { entered = resolve; });
        const originalApply = mux.applyLayout.bind(mux);
        mux.applyLayout = (prepared, admission) => { armed = true; return originalApply(prepared, admission); };
        const originalHandshake = fake.handshake.bind(fake);
        fake.handshake = async () => {
          if (armed && !changed) { entered(); await blocked; changed = true; }
          return originalHandshake();
        };
        const originalSnapshot = fake.sessionSnapshot.bind(fake);
        fake.sessionSnapshot = async () => {
          const snapshot = await originalSnapshot();
          return { ...snapshot, panes: snapshot.panes.map((pane) => pane.pane_id === source.paneId && changed ?
            Object.assign({}, pane, { terminal_id: change === "occupant" ? "replacement-terminal" : pane.terminal_id,
              cwd: change === "cwd" ? "/different/current-cwd" : pane.cwd }) : pane) };
        };
        const originalLayout = fake.readPaneLayout.bind(fake);
        fake.readPaneLayout = async (paneId) => {
          const raw = await originalLayout(paneId);
          if (!changed || change !== "geometry" || paneId !== source.paneId) return raw;
          // SAFETY: FakeHerdr's declared pane_layout response has this exact object shape.
          const row = raw as { type: string; layout: import("../json.ts").JsonObject };
          return { ...row, layout: { ...row.layout, area: { x: 0, y: 0, width: 81, height: 24 } } };
        };
        const originalResize = fake.resizePane.bind(fake);
        fake.resizePane = (...args) => { effects++; return originalResize(...args); };
        const applyPath = path(source.paneId, "/resize");
        const body = { requestId: `late-${change}`, planToken: plan.planToken, action };
        const pending = servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        await reached;
        release();
        const result = await pending;
        expect(result?.status).toBe(409);
        expect(effects).toBe(0);
        const sameId = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        expect(sameId?.status).toBe(409);
        expect(effects).toBe(0);
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });

  test("retargeting the still-advertised alias during adapter preflight refuses, unchanged alias succeeds once", async () => {
    const { symlink, unlink } = await import("node:fs/promises");
    for (const choice of ["advertised-folder", "source-pane"]) for (const replace of [false, true]) {
      const dir = await mkdtemp(join("/var/tmp", "collie-layout-alias-"));
      try {
        const first = join(dir, "first"); const second = join(dir, "second"); const alias = join(dir, "advertised");
        await mkdir(first); await mkdir(second); await symlink(first, alias, "dir");
        const fake = new FakeHerdr();
        const originalSnapshot = fake.sessionSnapshot.bind(fake);
        fake.sessionSnapshot = async () => {
          const snapshot = await originalSnapshot();
          return { ...snapshot, panes: snapshot.panes.map((pane) => Object.assign({}, pane, { cwd: alias })) };
        };
        const mux = new HerdrMux(fake, () => []);
        await mux.reachable(); const source = (await mux.snapshot()).panes[0]!;
        const folders: FolderSurface = { home: dir, current: () => ({ recent: [alias], favourites: [] }), star: async () => ({ ok: false, code: "folders.unknown" }), recordRecent: async () => {} };
        const baseCaller = caller(mux);
        const routes = { ...baseCaller, resolve: async () => ({ host: "host-one", session: "work", mux, folders }) };
        const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const cwd = choice === "advertised-folder" ? { kind: choice, path: alias } : { kind: choice };
        const action = { kind: "split", direction: "right", cwd };
        const planPath = path(source.paneId, "/plan");
        const reply = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), routes, stores);
        // SAFETY: successful closed plan from this fixture's actual route.
        const plan = await reply!.json() as { planToken: string };
        const originalApply = mux.applyLayout.bind(mux);
        mux.applyLayout = async (prepared, admission) => {
          if (replace) { await unlink(alias); await symlink(second, alias, "dir"); }
          return originalApply(prepared, admission);
        };
        let effects = 0; const originalSplit = fake.splitPane.bind(fake);
        fake.splitPane = (parameters) => { effects++; return originalSplit(parameters); };
        const applyPath = path(source.paneId, "/split");
        const body = { requestId: `alias-${choice}-${replace ? "changed" : "stable"}`, planToken: plan.planToken, action };
        const result = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), routes, stores);
        expect(result?.status).toBe(replace ? 409 : 200);
        expect(effects).toBe(replace ? 0 : 1);
        if (!replace) expect(fake.lastSplitParameters?.cwd).toBe(first);
        await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), routes, stores);
        expect(effects).toBe(replace ? 0 : 1);
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });
});


describe("corrective5 workspace cwd witness", () => {
  test("selected workspace folder bypasses cached repo metadata: stable succeeds, late replacement refuses", async () => {
    for (const replace of [false, true]) {
      const dir = await mkdtemp(join("/var/tmp", "collie-layout-workspace-cwd-"));
      try {
        const first = join(dir, "first"); const second = join(dir, "second");
        await mkdir(first); await mkdir(second);
        const fake = new FakeHerdr();
        let changed = false;
        fake.workspaceWorktrees = async (workspaceId) => ({ source: { repo_root: first }, worktrees: [{
          path: changed ? second : first, open_workspace_id: workspaceId, is_linked_worktree: false,
          is_prunable: false, is_bare: false, is_detached: false,
        }] });
        const mux = new HerdrMux(fake, () => []); await mux.reachable();
        const source = (await mux.snapshot()).panes[0]!;
        const folders: FolderSurface = { home: dir, current: () => ({ recent: [], favourites: [] }),
          star: async () => ({ ok: false, code: "folders.unknown" }), recordRecent: async () => {} };
        const routes = { ...caller(mux), resolve: async () => ({ host: "host-one", session: "work", mux, folders }) };
        const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const action = { kind: "split", direction: "right", cwd: { kind: "workspace", workspaceId: source.spaceId } };
        const planPath = path(source.paneId, "/plan");
        const reply = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), routes, stores);
        expect(reply?.status).toBe(200);
        // SAFETY: the mounted plan just returned its successful closed response.
        const plan = await reply!.json() as { planToken: string };
        const originalApply = mux.applyLayout.bind(mux);
        mux.applyLayout = (prepared, admission) => { changed = replace; return originalApply(prepared, admission); };
        let effects = 0; const originalSplit = fake.splitPane.bind(fake);
        fake.splitPane = (parameters) => { effects++; return originalSplit(parameters); };
        const applyPath = path(source.paneId, "/split");
        const body = { requestId: `workspace-${replace ? "changed" : "stable"}`, planToken: plan.planToken, action };
        const result = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), routes, stores);
        expect(result?.status).toBe(replace ? 409 : 200);
        expect(effects).toBe(replace ? 0 : 1);
        if (!replace) expect(fake.lastSplitParameters?.cwd).toBe(first);
        await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), routes, stores);
        expect(effects).toBe(replace ? 0 : 1);
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });
});

describe("corrective5 destination-await witness", () => {
  test("source changes during destination geometry read are refused before the move RPC", async () => {
    for (const change of ["geometry", "occupant"]) {
      const fake = new FakeHerdr(); const mux = new HerdrMux(fake, () => []);
      await mux.reachable();
      const snapshot = await mux.snapshot();
      const source = snapshot.panes[0]!;
      const target = snapshot.panes.find((pane) => pane.tabId !== source.tabId)!;
      const dir = await mkdtemp(join("/var/tmp", "collie-layout-destination-await-"));
      try {
        const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const action = { kind: "move", destination: { type: "tab", tabId: target.tabId,
          targetPaneId: target.paneId, split: "right", ratio: 0.5 } };
        const planPath = path(source.paneId, "/plan");
        const reply = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
        expect(reply?.status).toBe(200);
        // SAFETY: this actual route returned the closed successful plan above.
        const plan = await reply!.json() as { planToken: string };
        let armed = false; let changed = false; let effects = 0;
        let entered!: () => void; let release!: () => void;
        const reached = new Promise<void>((resolve) => { entered = resolve; });
        const blocked = new Promise<void>((resolve) => { release = resolve; });
        const originalApply = mux.applyLayout.bind(mux);
        mux.applyLayout = (prepared, admission) => { armed = true; return originalApply(prepared, admission); };
        const originalRead = fake.readPaneLayout.bind(fake);
        fake.readPaneLayout = async (paneId) => {
          if (armed && !changed && paneId === target.paneId) { entered(); await blocked; changed = true; }
          const raw = await originalRead(paneId);
          if (!changed || change !== "geometry" || paneId !== source.paneId) return raw;
          // SAFETY: FakeHerdr's successful pane_layout fixture is a typed layout object.
          const row = raw as { type: string; layout: import("../json.ts").JsonObject };
          return { ...row, layout: { ...row.layout, area: { x: 0, y: 0, width: 81, height: 24 } } };
        };
        const originalSnapshot = fake.sessionSnapshot.bind(fake);
        fake.sessionSnapshot = async () => {
          const current = await originalSnapshot();
          return { ...current, panes: current.panes.map((pane) => changed && change === "occupant" && pane.pane_id === source.paneId ?
            Object.assign({}, pane, { terminal_id: "late-replacement" }) : pane) };
        };
        const originalMove = fake.movePane.bind(fake);
        fake.movePane = (...args) => { effects++; return originalMove(...args); };
        const applyPath = path(source.paneId, "/move");
        const body = { requestId: `destination-${change}`, planToken: plan.planToken, action };
        const pending = servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        await reached; release();
        expect((await pending)?.status).toBe(409);
        expect(effects).toBe(0);
        await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        expect(effects).toBe(0);
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });
});


describe("corrective6 final plan lifetime", () => {
  test("all six named layout families refuse expiry after consumed-plan preflight; unexpired effects happen once", async () => {
    class ShortPlanStore extends PlanStore {
      override issue(binding: PlanBinding): ControlPlan { return super.issue(binding, 10); }
    }
    for (const expire of [false, true]) for (const kind of ["split", "resize", "zoom", "swap", "move", "focus-direction"]) {
      let clock = 1_000;
      const fake = new FakeHerdr(); const mux = new HerdrMux(fake, () => []); await mux.reachable();
      const snapshot = await mux.snapshot(); const source = snapshot.panes[0]!;
      const sibling = snapshot.panes.find((pane) => pane.paneId !== source.paneId && pane.tabId === source.tabId)!;
      const actions = new Map<string, JsonValue>([
        ["split", { kind: "split", direction: "right", ratio: 0.5 }],
        ["resize", { kind: "resize", direction: "right", amount: 0.1 }],
        ["zoom", { kind: "zoom", mode: "on" }],
        ["swap", { kind: "swap", direction: null, sourcePaneId: source.paneId, targetPaneId: sibling.paneId }],
        ["move", { kind: "move", destination: { type: "new_workspace", label: "new", tabLabel: "moved" } }],
        ["focus-direction", { kind: "focus-direction", direction: "right" }],
      ]);
      const action = actions.get(kind)!;
      const dir = await mkdtemp(join("/var/tmp", "collie-layout-final-expiry-"));
      try {
        const stores = { plans: new ShortPlanStore(() => clock), operations: new OperationStore(dir) };
        const planPath = path(source.paneId, "/plan");
        const reply = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
        expect(reply?.status).toBe(200);
        // SAFETY: actual successful closed plan above, issued by the ten-ms declared PlanStore clock.
        const plan = await reply!.json() as { planToken: string; expiresAt: number };
        expect(plan.expiresAt).toBe(1_010);
        let armed = false; let held = false; let effects = 0;
        let entered!: () => void; let release!: () => void;
        const reached = new Promise<void>((resolve) => { entered = resolve; });
        const blocked = new Promise<void>((resolve) => { release = resolve; });
        const originalApply = mux.applyLayout.bind(mux);
        mux.applyLayout = (prepared, admission) => { armed = true; return originalApply(prepared, admission); };
        const originalHandshake = fake.handshake.bind(fake);
        fake.handshake = async () => {
          if (armed && !held) { held = true; entered(); await blocked; }
          return originalHandshake();
        };
        const split = fake.splitPane.bind(fake); fake.splitPane = (...args) => { effects++; return split(...args); };
        const resize = fake.resizePane.bind(fake); fake.resizePane = (...args) => { effects++; return resize(...args); };
        const zoom = fake.zoomPane.bind(fake); fake.zoomPane = (...args) => { effects++; return zoom(...args); };
        const swap = fake.swapPanes.bind(fake); fake.swapPanes = (...args) => { effects++; return swap(...args); };
        const move = fake.movePane.bind(fake); fake.movePane = (...args) => { effects++; return move(...args); };
        const focus = fake.focusPaneDirection.bind(fake); fake.focusPaneDirection = (...args) => { effects++; return focus(...args); };
        const applyPath = path(source.paneId, `/${kind}`);
        const body = { requestId: `ttl-${kind}-${expire ? "expired" : "live"}`, planToken: plan.planToken, action };
        const pending = servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        await Promise.race([reached, pending.then(async (response) => {
          throw new Error(`Expected adapter preflight for ${kind}/${String(expire)}, got ${String(response?.status)}: ${await response?.clone().text()}`);
        })]);
        expect(stores.plans.lookup(plan.planToken)).toBeNull(); // Token consumed/reserved before adapter wait.
        clock = expire ? plan.expiresAt : plan.expiresAt - 1;
        release();
        const result = await pending;
        expect(result?.status).toBe(expire ? 409 : 200);
        expect(effects).toBe(expire ? 0 : 1);
        if (expire) expect(await result!.json()).toMatchObject({ code: "plan.expired" });
        const stored = await stores.operations.inspect(body.requestId, owner, "host-one", "work");
        expect(stored?.state).toBe(expire ? "refused" : "succeeded");
        if (expire) expect(stored?.code).toBe("plan.expired");
        clock = plan.expiresAt + 100;
        const restarted = { plans: new ShortPlanStore(() => clock), operations: new OperationStore(dir) };
        const sameId = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), restarted);
        expect(sameId?.status).toBe(expire ? 409 : 200);
        if (expire) expect(await sameId!.json()).toMatchObject({ code: "plan.expired" });
        expect(effects).toBe(expire ? 0 : 1); // Expiry never denies receipt read or admits another named effect.
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });
});

describe("corrective7 directional focus effect correlation", () => {
  test("wrong canonical focus after an effect stays original-ID unknown across restart; exact neighbor succeeds once", async () => {
    for (const wrong of [false, true]) {
      const fake = new FakeHerdr();
      const mux = new HerdrMux(fake, () => []);
      await mux.reachable();
      const source = (await mux.snapshot()).panes[0]!;
      await fake.splitPane({ direction: "right", ratio: 0.5, target_pane_id: source.paneId,
        workspace_id: source.spaceId, env: {}, focus: false, right_click: "herdr" });
      const neighbor = await mux.readLayout(source.paneId, { kind: "neighbor", direction: "right" });
      expect(neighbor.ok && neighbor.value.kind === "neighbor" && neighbor.value.neighborPaneId).toBeTruthy();
      const expectedNeighbor = neighbor.ok && neighbor.value.kind === "neighbor" ? neighbor.value.neighborPaneId : null;
      const other = (await mux.snapshot()).panes.find((pane) => pane.tabId === source.tabId &&
        pane.paneId !== source.paneId && pane.paneId !== expectedNeighbor)!;
      expect(other).toBeDefined();
      const action = { kind: "focus-direction", direction: "right" };
      const dir = await mkdtemp(join(tmpdir(), "collie-layout-focus-result-"));
      try {
        const stores = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const planPath = path(source.paneId, "/plan");
        const planResponse = await servePaneLayoutRoute(request(planPath, { action }), new URL(`https://collie${planPath}`), caller(mux), stores);
        expect(planResponse?.status).toBe(200);
        // SAFETY: the successful closed plan endpoint immediately above produced this token.
        const plan = await planResponse!.json() as { planToken: string };
        let effects = 0;
        const original = fake.focusPaneDirection.bind(fake);
        fake.focusPaneDirection = async (...args) => {
          effects++;
          const result = await original(...args); // An actual fixture focus effect has happened.
          if (!wrong) return result;
          // Supply another canonical, coherent in-tab focus, not a malformed discriminant/ID.
          await fake.focusPane(other.paneId);
          const read = await fake.readPaneLayout(source.paneId);
          // SAFETY: this FakeHerdr method returns the pane_layout envelope produced immediately
          // above for the existing source; the fixture changes only its coherent focused identity.
          return { type: "pane_focus_direction", focus: { changed: true, source_pane_id: source.paneId,
            focused_pane_id: other.paneId, layout: (read as { layout: JsonValue }).layout } };
        };
        const applyPath = path(source.paneId, "/focus-direction");
        const body = { requestId: `focus-${wrong ? "wrong" : "exact"}`, planToken: plan.planToken, action };
        const response = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), stores);
        expect(response?.status).toBe(wrong ? 202 : 200);
        expect(await response!.json()).toMatchObject({ ok: !wrong, operation: { requestId: body.requestId, state: wrong ? "unknown" : "succeeded" } });
        const recovered = await new OperationStore(dir).inspect(body.requestId, owner, "host-one", "work");
        expect(recovered).toMatchObject({ requestId: body.requestId, state: wrong ? "unknown" : "succeeded" });
        expect(recovered).not.toHaveProperty("result");
        const restarted = { plans: new PlanStore(), operations: new OperationStore(dir) };
        const replay = await servePaneLayoutRoute(request(applyPath, body), new URL(`https://collie${applyPath}`), caller(mux), restarted);
        expect(replay?.status).toBe(wrong ? 202 : 200);
        expect(await replay!.json()).toMatchObject({ operation: { requestId: body.requestId, state: wrong ? "unknown" : "succeeded" } });
        expect(effects).toBe(1);
      } finally { await rm(dir, { recursive: true, force: true }); }
    }
  });
});
