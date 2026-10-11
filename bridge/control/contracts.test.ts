import { describe, expect, test } from "bun:test";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { controlScope, controlGate, payloadHash, supportsNew } from "./contracts.ts";
import { OperationStore } from "./operations.ts";
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
    expect(controlGate("/api/control/arbitrary-rpc", "POST")).toBeNull();
    expect(payloadHash({ a: 1, b: 2 })).toBe(payloadHash({ b: 2, a: 1 }));
    const absent: (Record<string, boolean> | undefined)[] = [undefined, {}, { movePane: false }];
    for (const map of absent) expect(supportsNew(map, "movePane")).toBe(false);
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
