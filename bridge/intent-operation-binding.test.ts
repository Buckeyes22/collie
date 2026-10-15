import { describe, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { OperationStore } from "./control/operations.ts";
import { sameBinding } from "./control/plans.ts";
import { dispatchedOutcome, executeBoundIntent, intentOperationBinding, ownerBoundCallerReady, ownerBoundOperationReady } from "./intent-operation-binding.ts";

const key = new Uint8Array(32).fill(9);
const body = { requestId: "intent_1", branch: "feature/demo" };
const context = { key, deviceIdentity: "a".repeat(64), owningHost: "host-1", runtime: "work" };

describe("durable effect binding", () => {
  test("local and Crew capability declarations both fail closed without key or durable state", () => {
    expect(ownerBoundOperationReady(key, true)).toBe(true);
    expect(ownerBoundOperationReady(null, true)).toBe(false);
    expect(ownerBoundOperationReady(undefined, true)).toBe(false);
    expect(ownerBoundOperationReady(new Uint8Array(16), true)).toBe(false);
    expect(ownerBoundOperationReady(key, false)).toBe(false);
    expect(ownerBoundCallerReady(key, true, "a".repeat(64))).toBe(true);
    expect(ownerBoundCallerReady(key, true, null)).toBe(false);
    expect(ownerBoundCallerReady(null, true, "a".repeat(64))).toBe(false);
  });

  test("binds the stable owner, owning host, runtime, operation, target and exact body", () => {
    const binding = intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.create", "workspace-7", body);
    expect(binding).toMatchObject({
      device: "a".repeat(64),
      host: "host-1",
      session: "work",
      capability: "worktree.create",
      targetIdentity: "workspace-7",
    });
    expect(binding.payloadHash).toMatch(/^[a-f0-9]{64}$/u);
    expect(binding.evidence).toMatch(/^[a-f0-9]{64}$/u);
    expect(sameBinding(binding, intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.create", "workspace-7", body))).toBe(true);
  });

  test("same display labels do not matter; pairing, host, runtime, operation, route target and body changes refuse", () => {
    const binding = intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.create", "workspace-7", body);
    const changed = [
      intentOperationBinding(key, "b".repeat(64), "host-1", "work", "worktree.create", "workspace-7", body),
      intentOperationBinding(key, "a".repeat(64), "host-2", "work", "worktree.create", "workspace-7", body),
      intentOperationBinding(key, "a".repeat(64), "host-1", "other", "worktree.create", "workspace-7", body),
      intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.createAt", "workspace-7", body),
      intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.create", "workspace-8", body),
      intentOperationBinding(key, "a".repeat(64), "host-1", "work", "worktree.create", "workspace-7", { ...body, branch: "feature/other" }),
      intentOperationBinding(new Uint8Array(32).fill(8), "a".repeat(64), "host-1", "work", "worktree.create", "workspace-7", body),
    ];
    for (const candidate of changed) expect(sameBinding(binding, candidate)).toBe(false);
  });

  test("a failed post-dispatch answer remains unknown without proof that the effect did not land", () => {
    expect(dispatchedOutcome(true)).toEqual({ state: "succeeded" });
    expect(dispatchedOutcome(false)).toEqual({ state: "unknown", code: "operation.outcome_unknown" });
  });

  test("a restarted owner with an old successful tombstone and no response receipt never redispatches", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-intent-operation-"));
    try {
      const first = new OperationStore(root);
      expect(await first.prepare()).toBe(true);
      let effects = 0;
      type Answer = { ok: boolean; result?: string; code?: string };
      const args = {
        operations: first,
        requestId: body.requestId,
        context,
        operation: "worktree.create" as const,
        targetIdentity: "workspace:7",
        body,
        dispatch: async (): Promise<Answer> => { effects++; return { ok: true, result: "created" }; },
        knownReceipt: (): Answer | undefined => undefined,
        unknown: (): Answer => ({ ok: false, code: "launch.outcome_unknown" }),
        unavailable: (): Answer => ({ ok: false, code: "launch.receipts_unavailable" }),
        mismatch: (): Answer => ({ ok: false, code: "launch.request_mismatch" }),
      };
      expect(await executeBoundIntent(args)).toEqual({ ok: true, result: "created" });
      const restarted = new OperationStore(root);
      expect(await restarted.prepare()).toBe(true);
      expect(await executeBoundIntent({ ...args, operations: restarted, dispatch: async (): Promise<Answer> => { effects++; return { ok: true }; } }))
        .toEqual({ ok: false, code: "launch.outcome_unknown" });
      expect(effects).toBe(1);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  test("unreadable durable state fails closed before the effect", async () => {
    const parent = await mkdtemp(join(tmpdir(), "collie-intent-operation-bad-"));
    const statePath = join(parent, "state-is-a-file");
    await writeFile(statePath, "blocked");
    try {
      const operations = new OperationStore(statePath);
      expect(await operations.prepare()).toBe(false);
      let effects = 0;
      type Answer = { ok: boolean; code?: string };
      const answer = await executeBoundIntent<Answer>({
        operations,
        requestId: body.requestId,
        context,
        operation: "launch",
        targetIdentity: "launch:new",
        body,
        dispatch: async (): Promise<Answer> => { effects++; return { ok: true }; },
        knownReceipt: () => undefined,
        unknown: (): Answer => ({ ok: false, code: "launch.outcome_unknown" }),
        unavailable: (): Answer => ({ ok: false, code: "launch.receipts_unavailable" }),
        mismatch: (): Answer => ({ ok: false, code: "launch.request_mismatch" }),
      });
      expect(answer).toEqual({ ok: false, code: "launch.receipts_unavailable" });
      expect(effects).toBe(0);
    } finally {
      await rm(parent, { recursive: true, force: true });
    }
  });

  test("a successful effect with failed terminal persistence stays unknown and never claims durable success", async () => {
    const parent = await mkdtemp(join(tmpdir(), "collie-intent-terminal-failure-"));
    const stateDir = join(parent, "state");
    try {
      const operations = new OperationStore(stateDir);
      expect(await operations.prepare()).toBe(true);
      let effects = 0;
      type Answer = { ok: boolean; result?: string; code?: string };
      const answer = await executeBoundIntent<Answer>({
        operations,
        requestId: body.requestId,
        context,
        operation: "launch",
        targetIdentity: "launch:new",
        body,
        dispatch: async (): Promise<Answer> => {
          effects++;
          await rm(stateDir, { recursive: true, force: true });
          await writeFile(stateDir, "storage became unavailable after effect");
          return { ok: true, result: "created" };
        },
        knownReceipt: () => undefined,
        unknown: (): Answer => ({ ok: false, code: "launch.outcome_unknown" }),
        unavailable: (): Answer => ({ ok: false, code: "launch.receipts_unavailable" }),
        mismatch: (): Answer => ({ ok: false, code: "launch.request_mismatch" }),
      });
      expect(answer).toEqual({ ok: false, code: "launch.outcome_unknown" });
      expect(effects).toBe(1);
      expect(operations.isReady()).toBe(false);
    } finally {
      await rm(parent, { recursive: true, force: true });
    }
  });
});
