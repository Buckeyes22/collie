import { WORKTREE_REMOVE_COMPLETION_HEADROOM_BYTES } from "./operations.ts";
import { describe, expect, test } from "bun:test";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { controlScope, controlGate, payloadHash, supportsNew } from "./contracts.ts";
import { OPERATION_COMPLETION_HEADROOM_BYTES, OperationStore } from "./operations.ts";
import { executePlannedOperation, PlanStore, type PlanBinding } from "./plans.ts";
import { serveControlRoute } from "./routes.ts";

const owner = "a".repeat(64);
const binding: PlanBinding = { device: owner, host: "local", session: "work", capability: "movePane", payloadHash: payloadHash({ to: "tab2", pane: "p1" }), targetIdentity: "occupant1", evidence: "snapshot1" };
describe("scoped control contracts", () => {
  test("metadata bindings reject accidental payload fields before plan issue or dispatch", async () => {
    const extra = { ...binding, payload: "private terminal content" };
    expect(() => new PlanStore().issue(extra)).toThrow();
    let dispatched = 0;
    await expect(new OperationStore().execute("extra", extra, null, async () => { dispatched++; return { state: "succeeded" }; })).rejects.toThrow();
    expect(dispatched).toBe(0);
  });
  test("closed reads and canonical payloads, absence never authorizes a new verb", () => {
    expect(controlGate("/api/refresh", "POST")).toBe("read");
    expect(controlGate("/api/control/operations/intent1", "GET")).toBe("read");
    expect(controlGate("/api/pane/w1:p1/layout", "GET")).toBe("read");
    expect(controlGate("/api/pane/w1:p1/layout/plan", "POST")).toBe("read");
    expect(controlGate("/api/tab/t1/layout/export", "GET")).toBe("device-read");
    expect(controlGate("/api/layout/plan", "POST")).toBe("device-read");
    expect(controlGate("/api/tab/t1/transfer/plan", "POST")).toBe("read");
    expect(controlGate("/api/workspace/w1/worktree/remove-plan", "GET")).toBe("read");
    expect(controlGate("/api/workspace/w1/worktree/remove", "POST")).toBe("write");
    expect(controlGate("/api/pane/w1:p1/layout/move", "POST")).toBe("write");
    expect(controlGate("/api/pane/w1:p1/layout/plan", "GET")).toBeNull();
    expect(controlGate("/api/pane/w1%3Ap1/agent", "GET")).toBe("device-read");
    for (const route of ["state", "session", "release", "authority/clear"]) {
      expect(controlGate(`/api/pane/w1%3Ap1/agent/${route}`, "POST")).toBe("write");
    }
    expect(controlGate("/api/control/arbitrary-rpc", "POST")).toBeNull();
    expect(payloadHash({ a: 1, b: 2 })).toBe(payloadHash({ b: 2, a: 1 }));
    const absent: (Record<string, boolean> | undefined)[] = [undefined, {}, { movePane: false }];
    for (const map of absent) expect(supportsNew(map, "movePane")).toBe(false);
  });
  test("workspace and tab plans are scoped reads while every execution route is a write", () => {
    const routes = [
      ["/api/workspace/w1/rename/plan", "read"], ["/api/workspace/w1/rename", "write"],
      ["/api/workspace/w1/close/plan", "read"], ["/api/workspace/w1/close", "write"],
      ["/api/workspace/w1/move/plan", "read"], ["/api/workspace/w1/move", "write"],
      ["/api/workspace/w1/focus/plan", "read"], ["/api/workspace/w1/focus", "write"],
      ["/api/workspaces/reorder/plan", "read"], ["/api/workspaces/reorder", "write"],
      ["/api/tab/t1/move/plan", "read"], ["/api/tab/t1/move", "write"],
      ["/api/tab/t1/focus/plan", "read"], ["/api/tab/t1/focus", "write"],
      ["/api/tab/t1/rename/plan", "read"], ["/api/tab/t1/rename", "write"],
      ["/api/tab/t1/close/plan", "read"], ["/api/tab/t1/close", "write"],
      ["/api/tab/t1/transfer/plan", "read"],
    ] as const;
    for (const [path, expected] of routes) expect(controlGate(path, "POST")).toBe(expected);
    expect(controlGate("/api/tab/t1/move/plan", "GET")).toBeNull();
    expect(controlGate("/api/workspaces/reorder/extra", "POST")).toBeNull();
  });
  test("malformed and irrelevant selectors fail before runtime resolution", () => {
    for (const query of ["session=one&session=two", "host=", "sessions=all", "session=%00"]) expect(controlScope(new URL(`https://collie/api/refresh?${query}`), "session")).toBeNull();
    expect(controlScope(new URL("https://collie/api/host/status?session=work"), "host")).toBeNull();
  });
  test("plans reject expiry, changed occupant/payload, NaN, infinity and fractional lifetimes", () => {
    let now = 0;
    const plans = new PlanStore(() => now);
    for (const ttl of [NaN, Infinity, 1.5, 0, -1]) expect(() => plans.issue(binding, ttl)).toThrow();
    const plan = plans.issue(binding, 10);
    expect(plans.validate(plan.token, { ...binding, targetIdentity: "replacement" })).toBe("changed");
    expect(plans.validate(plan.token, { ...binding, payloadHash: payloadHash({ changed: true }) })).toBe("changed");
    now = 10;
    expect(plans.validate(plan.token, binding)).toBe("expired");
  });
  test("reserve joins concurrent duplicates, conflicts do not dispatch, disconnect never replays", async () => {
    const operations = new OperationStore();
    let release!: () => void;
    let dispatched = 0;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const first = operations.execute("intent", binding, null, async () => { dispatched++; await pending; throw new Error("secret-disconnect-detail"); });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect((await operations.execute("intent", binding, null, async () => { dispatched++; return { state: "succeeded" }; })).state).toBe("pending");
    await expect(operations.execute("intent", { ...binding, device: "another" }, null, async () => ({ state: "succeeded" }))).rejects.toThrow("operation.request_id_reused");
    release();
    const unknown = await first;
    expect(unknown.state).toBe("unknown");
    expect(JSON.stringify(unknown)).not.toContain("secret");
    expect((await operations.execute("intent", binding, null, async () => { dispatched++; return { state: "succeeded" }; })).state).toBe("unknown");
    expect(dispatched).toBe(1);
  });
  test("expired/changed plans reserve a refusal without performing a write", async () => {
    const plans = new PlanStore();
    const plan = plans.issue(binding);
    let writes = 0;
    const result = await executePlannedOperation(new OperationStore(), plans, "intent", plan,
      async () => ({ ...binding, evidence: "changed" }), async () => { writes++; return { state: "succeeded" }; });
    expect(result).toMatchObject({ state: "refused", code: "plan.changed" });
    expect(writes).toBe(0);
  });
  test("persisted reservations survive restart as unknown; completion is replayable and private", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-"));
    try {
      const operations = new OperationStore(dir);
      await operations.execute("done", binding, null, async () => ({ state: "succeeded" }));
      const restarted = new OperationStore(dir);
      expect((await restarted.inspect("done", owner, "local", "work"))?.state).toBe("succeeded");
      expect(await restarted.inspect("done", "other", "local", "work")).toBeNull();
      expect(await restarted.inspect("done", owner, "local", "other")).toBeNull();
      const file = join(dir, "control-operations.json");
      const rows = JSON.parse(await readFile(file, "utf8"));
      rows[0].state = "pending";
      await writeFile(file, JSON.stringify(rows));
      const crashed = new OperationStore(dir);
      let writes = 0;
      expect((await crashed.execute("done", binding, null, async () => { writes++; return { state: "succeeded" }; })).state).toBe("unknown");
      expect(writes).toBe(0);
      rows[0].state = ["pending"];
      await writeFile(file, JSON.stringify(rows));
      await expect(new OperationStore(dir).inspect("done", owner, "local", "work")).rejects.toThrow("unreadable");
      await writeFile(file, " ".repeat(4 * 1024 * 1024 + 1));
      await expect(new OperationStore(dir).inspect("done", owner, "local", "work")).rejects.toThrow("too large");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  test("reserved per-row headroom covers the largest closed pane-move result and Unicode code", () => {
    const pending = { requestId: "move-result", binding: { ...binding, capability: "paneMove" }, planToken: "p".repeat(256), state: "pending", at: 1, updatedAt: 1, code: null };
    const terminal = {
      ...pending, state: "succeeded", updatedAt: 2, code: "😀".repeat(256),
      result: { kind: "pane-move", paneId: "p".repeat(128), previousPaneId: "q".repeat(128), previousTabId: "t".repeat(128), previousWorkspaceId: "w".repeat(128) },
    };
    const growth = Buffer.byteLength(JSON.stringify(terminal), "utf8") - Buffer.byteLength(JSON.stringify(pending), "utf8");
    expect(growth).toBeGreaterThan(0);
    expect(growth).toBeLessThanOrEqual(OPERATION_COMPLETION_HEADROOM_BYTES);
    const transferTerminal = { ...terminal, result: { ...terminal.result, transfer: { schema: 1, changed: true, targetTabId: "t".repeat(128), targetWorkspaceId: "w".repeat(128), processWitness: "a".repeat(64), createdTabId: "c".repeat(128), createdWorkspaceId: "d".repeat(128), closedTabId: "e".repeat(128), closedWorkspaceId: "f".repeat(128) } } };
    const transferGrowth = Buffer.byteLength(JSON.stringify(transferTerminal), "utf8") - Buffer.byteLength(JSON.stringify(pending), "utf8");
    expect(transferGrowth).toBeLessThanOrEqual(OPERATION_COMPLETION_HEADROOM_BYTES);
  });

  test("pane-move receipts preserve only bounded aliases across restart and reject malformed stored results", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-move-result-"));
    try {
      const moveBinding = { ...binding, capability: "paneMove" };
      const alias = "w1:p" + "7".repeat(123);
      const result = { kind: "pane-move" as const, paneId: alias, previousPaneId: "w1:p1", previousTabId: "w1:t1", previousWorkspaceId: "w1" };
      const operation = await new OperationStore(dir).execute("move-result", moveBinding, "plan-1", async () => ({ state: "succeeded", result, code: "x".repeat(256) }));
      expect(operation).toMatchObject({ state: "succeeded", result });
      expect(await new OperationStore(dir).inspectIntent("move-result", owner, "local", "work", "paneMove", moveBinding.payloadHash, "plan-1")).toMatchObject({ kind: "match", operation: { result } });
      expect((await new OperationStore(dir).inspectIntent("move-result", "b".repeat(64), "local", "work", "paneMove", moveBinding.payloadHash, "plan-1")).kind).toBe("missing");
      const file = join(dir, "control-operations.json");
      const rows = JSON.parse(await readFile(file, "utf8"));
      rows[0].result.vendorPayload = "must reject";
      await writeFile(file, JSON.stringify(rows));
      await expect(new OperationStore(dir).inspect("move-result", owner, "local", "work")).rejects.toThrow("operation registry unreadable");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  test("portable-layout receipts keep only bounded created identities and reject malformed stored rows", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-portable-result-"));
    try {
      const layoutBinding = { ...binding, capability: "portableLayouts" };
      const result = { kind: "portable-layout" as const, workspaceId: "w1", tabId: "t2", paneIds: ["p2", "p3"], focusedPaneId: "p2" };
      const operation = await new OperationStore(dir).execute("portable-result", layoutBinding, "plan-1", async () => ({ state: "succeeded", result }));
      expect(operation).toMatchObject({ state: "succeeded", result });
      expect(await new OperationStore(dir).inspectIntent("portable-result", owner, "local", "work", "portableLayouts", layoutBinding.payloadHash, "plan-1"))
        .toMatchObject({ kind: "match", operation: { result } });
      const file = join(dir, "control-operations.json");
      const rows = JSON.parse(await readFile(file, "utf8"));
      rows[0].result.vendorPayload = "must reject";
      await writeFile(file, JSON.stringify(rows));
      await expect(new OperationStore(dir).inspect("portable-result", owner, "local", "work")).rejects.toThrow("operation registry unreadable");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  test("portable-layout success without valid created pane identities is unknown", async () => {
    const layoutBinding = { ...binding, capability: "portableLayouts" };
    const result = await new OperationStore().execute("portable-missing-identities", layoutBinding, "plan-1", async () => ({
      state: "succeeded", result: { kind: "portable-layout", workspaceId: "w1", tabId: "t1", paneIds: [], focusedPaneId: "p1" },
    }));
    expect(result).toMatchObject({ state: "unknown", code: "operation.invalid_outcome" });
    expect(result).not.toHaveProperty("result");
  });
  test("failed move completion drops aliases, stays unknown, and leaves a readable registry", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-move-persist-failure-"));
    try {
      const moveBinding = { ...binding, capability: "paneMove" };
      let persists = 0;
      const operations = new OperationStore(dir, Date.now, async () => {
        persists++;
        if (persists === 2) throw new Error("injected terminal fsync failure");
      });
      let effects = 0;
      const move = await operations.execute("move-fsync", moveBinding, "plan-1", async () => {
        effects++;
        return { state: "succeeded", result: { kind: "pane-move", paneId: "new", previousPaneId: "old", previousTabId: "tab", previousWorkspaceId: "space" } };
      });
      expect(move).toMatchObject({ state: "unknown", code: "operation.persistence_failed" });
      expect(move).not.toHaveProperty("result");
      await operations.execute("unrelated", binding, null, async () => ({ state: "succeeded" }));
      const restarted = new OperationStore(dir);
      const restored = await restarted.inspectIntent("move-fsync", owner, "local", "work", "paneMove", moveBinding.payloadHash, "plan-1");
      expect(restored).toMatchObject({ kind: "match", operation: { state: "unknown" } });
      expect(restored.kind === "match" ? restored.operation : {}).not.toHaveProperty("result");
      expect((await restarted.execute("move-fsync", moveBinding, "plan-1", async () => { effects++; return { state: "succeeded" }; })).state).toBe("unknown");
      expect(effects).toBe(1);
      const missing = await new OperationStore().execute("missing-move-result", moveBinding, null, async () => ({ state: "succeeded" }));
      expect(missing).toMatchObject({ state: "unknown", code: "operation.invalid_outcome" });
      expect(missing).not.toHaveProperty("result");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  test("persisted timestamps reject malformed chronology and completion survives clock rollback", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-clock-"));
    try {
      let now = 20;
      const operations = new OperationStore(dir, () => now);
      const receipt = await operations.execute("clock", binding, null, async () => { now = 10; return { state: "succeeded" }; });
      expect(receipt.updatedAt).toBe(receipt.at);
      const file = join(dir, "control-operations.json");
      const valid = await readFile(file, "utf8");
      for (const change of [{ at: 1.5, updatedAt: 2 }, { at: 2, updatedAt: 1 }]) {
        const rows = JSON.parse(valid);
        Object.assign(rows[0], change);
        await writeFile(file, JSON.stringify(rows));
        await expect(new OperationStore(dir).inspect("clock", owner, "local", "work")).rejects.toThrow("unreadable");
      }
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  test("reserve byte cap refuses before dispatch and never writes an unreadable registry", async () => {
    // A large valid seed avoids thousands of fsyncs while testing the authoritative reserve boundary.
    const dir = await mkdtemp(join(tmpdir(), "collie-control-cap-"));
    try {
      const large = "😀".repeat(128);
      const largeBinding = { ...binding, device: large, host: large, session: large, capability: large, targetIdentity: large, evidence: large };
      const rows = Array.from({ length: 2048 }, (_, i) => ({ requestId: `i${i}`, binding: largeBinding, planToken: large, state: "succeeded", at: 1, updatedAt: 1, code: null }));
      while (Buffer.byteLength(JSON.stringify(rows)) > 4 * 1024 * 1024 - 2048) rows.pop();
      await writeFile(join(dir, "control-operations.json"), JSON.stringify(rows));
      const operations = new OperationStore(dir);
      let writes = 0;
      let refused = false;
      for (let i = 0; i < 4; i++) {
        try { await operations.execute(`extra${i}`, largeBinding, large, async () => { writes++; return { state: "succeeded" }; }); }
        catch (error) { expect(String(error)).toContain("capacity"); refused = true; break; }
      }
      expect(refused).toBe(true);
      expect(writes).toBeLessThan(4);
      await expect(new OperationStore(dir).inspect("i0", large, large, large)).resolves.not.toBeNull();
    } finally { await rm(dir, { recursive: true, force: true }); }
  }, 30_000);
  test("concurrent pending receipts reserve individual completion headroom", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-control-concurrent-cap-"));
    try {
      const large = "😀".repeat(128);
      const largeBinding = { ...binding, device: large, host: large, session: large, capability: large, targetIdentity: large, evidence: large };
      const rows = Array.from({ length: 2048 }, (_, i) => ({ requestId: `seed${i}`, binding: largeBinding, planToken: large, state: "succeeded", at: 1, updatedAt: 1, code: null }));
      while (Buffer.byteLength(JSON.stringify(rows)) > 4 * 1024 * 1024 - 20_000) rows.pop();
      await writeFile(join(dir, "control-operations.json"), JSON.stringify(rows));
      const operations = new OperationStore(dir);
      let release!: () => void;
      const blocked = new Promise<void>((resolve) => { release = resolve; });
      let dispatched = 0;
      const pending = Array.from({ length: 15 }, (_, i) => operations.execute(`pending${i}`, largeBinding, large, async () => {
        dispatched++; await blocked; return { state: "succeeded" as const, code: large };
      }).then(() => true, (error) => { expect(String(error)).toContain("capacity"); return false; }));
      // Inspect shares the reservation queue, so this waits until all fifteen reservations settle.
      await operations.inspect("seed0", large, large, large);
      release();
      const outcomes = await Promise.all(pending);
      expect(dispatched).toBeGreaterThan(0);
      expect(dispatched).toBeLessThan(15);
      expect(outcomes.filter(Boolean)).toHaveLength(dispatched);
      expect(Buffer.byteLength(await readFile(join(dir, "control-operations.json")))).toBeLessThanOrEqual(4 * 1024 * 1024);
      expect(await new OperationStore(dir).inspect("seed0", large, large, large)).not.toBeNull();
    } finally { await rm(dir, { recursive: true, force: true }); }
  }, 30_000);
  test("mounted operation handler gates before resolution, refuses wrong owner and masks internals", async () => {
    const operations = new OperationStore();
    const pairedIdentity = "a".repeat(64);
    await operations.execute("intent", { ...binding, device: pairedIdentity }, null, async () => ({ state: "succeeded" }));
    const url = new URL("https://collie/api/control/operations/intent?session=work");
    let resolutions = 0;
    const caller = { gate: (): Response | null => null, resolve: async () => { resolutions++; return { host: "local", session: "work" }; }, device: () => "phone", pairedIdentity: () => pairedIdentity };
    const response = await serveControlRoute(new Request(url), url, caller, operations);
    expect(response?.status).toBe(200);
    expect(resolutions).toBe(1);
    const wire = await response!.text();
    for (const secret of ["payloadHash", "planToken", "device", "snapshot1"]) expect(wire).not.toContain(secret);
    const refused = await serveControlRoute(new Request(url), url, { ...caller, gate: () => new Response("denied", { status: 403 }) }, operations);
    expect(refused?.status).toBe(403);
    expect(resolutions).toBe(1);
    const missingIdentity = await serveControlRoute(new Request(url), url, { ...caller, pairedIdentity: () => null }, operations);
    expect(missingIdentity?.status).toBe(403);
    expect(resolutions).toBe(1);
    expect((await serveControlRoute(new Request(url), url, { ...caller, pairedIdentity: () => "b".repeat(64) }, operations))?.status).toBe(404);
    expect((await serveControlRoute(new Request(url), url, { ...caller, pairedIdentity: () => "same-display-label" }, operations))?.status).toBe(403);
    const remote = new Response("peer unsupported", { status: 404 });
    expect(await serveControlRoute(new Request(url), url, { ...caller, resolve: async () => remote }, operations)).toBe(remote);
  });
});


test("worktree completion reserves the serialized worst legal path and escaped branch for every pending row", async () => {
  const result = { kind: "worktree-remove" as const, workspaceId: "w".repeat(128), path: "/" + "界".repeat(4095), branch: "\u0001".repeat(256), forced: true };
  const pending = { requestId: "remove", binding: { ...binding, capability: "worktreeRemove" }, planToken: "p".repeat(256), state: "pending", at: 1, updatedAt: 1, code: null };
  const terminal = { ...pending, state: "succeeded", updatedAt: 2, code: "😀".repeat(256), result };
  expect(Buffer.byteLength(JSON.stringify(terminal)) - Buffer.byteLength(JSON.stringify(pending))).toBeLessThan(WORKTREE_REMOVE_COMPLETION_HEADROOM_BYTES);
  const dir = await mkdtemp(join(tmpdir(), "collie-control-remove-headroom-"));
  try {
    const store = new OperationStore(dir);
    let release!: () => void;
    const hold = new Promise<void>((resolve) => { release = resolve; });
    let entered = 0;
    let allEntered!: () => void;
    const reserved = new Promise<void>((resolve) => { allEntered = resolve; });
    const actions = Array.from({ length: 96 }, (_, index) => store.execute(`remove-${index}`, { ...binding, capability: "worktreeRemove" }, "plan", async () => { if (++entered === 96) allEntered(); await hold; return { state: "succeeded" as const, result }; }));
    await reserved;
    release();
    const receipts = await Promise.all(actions);
    expect(receipts.every((row) => row.state === "succeeded")).toBe(true);
    expect(Buffer.byteLength(await readFile(join(dir, "control-operations.json"), "utf8"))).toBeLessThanOrEqual(4 * 1024 * 1024);
    expect((await new OperationStore(dir).inspect("remove-95", owner, "local", "work"))?.state).toBe("succeeded");
  } finally { await rm(dir, { recursive: true, force: true }); }
}, 30_000);


test("mixed pending removal reservations refuse before effects and keep every completion inside the fixed byte cap", async () => {
  const dir = await mkdtemp(join(tmpdir(), "collie-control-mixed-remove-cap-"));
  try {
    const large = "😀".repeat(128);
    const seedBinding = { ...binding, device: large, host: large, session: large, capability: large, targetIdentity: large, evidence: large };
    const rows = Array.from({ length: 2048 }, (_, i) => ({ requestId: `seed${i}`, binding: seedBinding, planToken: large, state: "succeeded", at: 1, updatedAt: 1, code: null }));
    while (Buffer.byteLength(JSON.stringify(rows)) > 4 * 1024 * 1024 - 70_000) rows.pop();
    await writeFile(join(dir, "control-operations.json"), JSON.stringify(rows));
    const store = new OperationStore(dir);
    let release!: () => void;
    const hold = new Promise<void>((resolve) => { release = resolve; });
    let removalEffects = 0;
    let moveEffects = 0;
    const result = { kind: "worktree-remove" as const, workspaceId: "linked", path: "/" + "界".repeat(4095), branch: "\\\"".repeat(128), forced: true };
    const removals = Array.from({ length: 6 }, (_, index) => store.execute(`remove-${index}`, { ...binding, capability: "worktreeRemove" }, "plan", async () => {
      removalEffects++; await hold; return { state: "succeeded" as const, result };
    }).then(() => true, (error) => { expect(String(error)).toContain("capacity"); return false; }));
    const moves = Array.from({ length: 2 }, (_, index) => store.execute(`move-${index}`, binding, "plan", async () => {
      moveEffects++; await hold; return { state: "refused" as const, code: "source.changed" };
    }).then(() => true, (error) => { expect(String(error)).toContain("capacity"); return false; }));
    await store.inspect("seed0", large, large, large);
    release();
    const admitted = await Promise.all(removals);
    await Promise.all(moves);
    expect(removalEffects).toBeGreaterThan(0);
    expect(removalEffects).toBeLessThan(6);
    expect(admitted.filter(Boolean)).toHaveLength(removalEffects);
    expect(moveEffects).toBeLessThanOrEqual(2);
    const bytes = await readFile(join(dir, "control-operations.json"), "utf8");
    expect(Buffer.byteLength(bytes)).toBeLessThanOrEqual(4 * 1024 * 1024);
    const restored = new OperationStore(dir);
    expect(await restored.inspect("seed0", large, large, large)).not.toBeNull();
    for (let i = 0; i < admitted.length; i++) {
      const row = await restored.inspect(`remove-${i}`, owner, "local", "work");
      expect(row?.state ?? null).toBe(admitted[i] ? "succeeded" : null);
    }
  } finally { await rm(dir, { recursive: true, force: true }); }
}, 30_000);
