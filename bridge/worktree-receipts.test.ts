import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtemp, readdir, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import type { JsonObject } from "./json.ts";
import type { WorktreeCreateResponse } from "./types.ts";
import {
  MAX_RECEIPTS,
  RECEIPTS_FILE,
  INTENT_RECEIPT_KEY_FILE,
  WorktreeReceiptStore,
  canonicalJson,
  coerceReceipts,
  isRequestId,
  loadOrCreateIntentReceiptKey,
  keyedIntentFingerprint,
  matchReceiptBinding,
  memoryWorktreeReceipts,
  type WorktreeReceipt,
} from "./worktree-receipts.ts";

// The receipt store behind a worktree create's replay (ADR 0089): one receipt per request id, 0600,
// capped at MAX_RECEIPTS with the oldest dropped, and a create in flight joined rather than repeated.

let stateDir = "";
beforeEach(async () => {
  stateDir = await mkdtemp(join(tmpdir(), "collie-worktree-receipts-"));
});
afterEach(async () => {
  await rm(stateDir, { recursive: true, force: true });
});

/** A distinct, well-formed request id for index `n`. */
function idOf(n: number): string {
  return `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;
}

function receiptOf(n: number): WorktreeReceipt {
  return {
    requestId: idOf(n),
    at: 1_000 + n,
    workspaceId: `w${n}`,
    paneId: `w${n}:p1`,
    path: `/home/op/repo/.worktrees/x${n}`,
    branch: `worktree/x${n}`,
    launcherStarted: n % 2 === 0,
  };
}

const silent = () => {};

describe("isRequestId", () => {
  test("accepts a canonical UUID in either case", () => {
    expect(isRequestId("0b9e6a1c-3f2d-4c5e-8a7b-1d2e3f4a5b6c")).toBe(true);
    expect(isRequestId("0B9E6A1C-3F2D-4C5E-8A7B-1D2E3F4A5B6C")).toBe(true);
  });

  test("refuses anything else", () => {
    for (const bad of ["", "nope", "0b9e6a1c3f2d4c5e8a7b1d2e3f4a5b6c", "../../etc", " 0b9e6a1c-3f2d-4c5e-8a7b-1d2e3f4a5b6c"]) {
      expect(isRequestId(bad)).toBe(false);
    }
    expect(isRequestId(42)).toBe(false);
    expect(isRequestId(null)).toBe(false);
    expect(isRequestId(undefined)).toBe(false);
  });
});

describe("WorktreeReceiptStore — the file", () => {
  test("loading an empty state dir writes nothing", async () => {
    const store = new WorktreeReceiptStore(stateDir, silent);
    await store.load();
    expect(await readdir(stateDir)).toEqual([]);
    expect(store.get(idOf(1))).toBeUndefined();
  });

  test("a recorded receipt is read back, from memory and from the file after a reload", async () => {
    const store = new WorktreeReceiptStore(stateDir, silent);
    await store.load();
    await store.record(receiptOf(1));
    expect(store.get(idOf(1))).toEqual(receiptOf(1));

    const again = new WorktreeReceiptStore(stateDir, silent);
    await again.load();
    expect(again.get(idOf(1))).toEqual(receiptOf(1));
  });

  test("the file is owner-only (0600)", async () => {
    if (process.platform === "win32") return;
    const store = new WorktreeReceiptStore(stateDir, silent);
    await store.record(receiptOf(1));
    const mode = (await stat(join(stateDir, RECEIPTS_FILE))).mode & 0o777;
    expect(mode).toBe(0o600);
  });

  test(`keeps at most ${MAX_RECEIPTS}, dropping the oldest first`, async () => {
    const store = new WorktreeReceiptStore(stateDir, silent);
    for (let n = 1; n <= MAX_RECEIPTS + 5; n++) await store.record(receiptOf(n));
    for (let n = 1; n <= 5; n++) expect(store.get(idOf(n))).toBeUndefined();
    expect(store.get(idOf(6))).toEqual(receiptOf(6));
    expect(store.get(idOf(MAX_RECEIPTS + 5))).toEqual(receiptOf(MAX_RECEIPTS + 5));

    const again = new WorktreeReceiptStore(stateDir, silent);
    await again.load();
    expect(again.get(idOf(5))).toBeUndefined();
    expect(again.get(idOf(6))).toEqual(receiptOf(6));
  });

  test("a broken file loads as no receipts and is not rewritten by loading", async () => {
    const file = join(stateDir, RECEIPTS_FILE);
    await writeFile(file, "{not json");
    const store = new WorktreeReceiptStore(stateDir, silent);
    await store.load();
    expect(store.get(idOf(1))).toBeUndefined();
    expect(await Bun.file(file).text()).toBe("{not json");
  });

  test("a failed durable write keeps the receipt in memory, warns, and reports uncertainty", async () => {
    const warnings: string[] = [];
    // A state dir that is a FILE: mkdir and the temp write both fail.
    const blocked = join(stateDir, "blocked");
    await writeFile(blocked, "");
    const store = new WorktreeReceiptStore(blocked, (line) => warnings.push(line));
    await expect(store.record(receiptOf(1))).rejects.toThrow();
    expect(store.get(idOf(1))).toEqual(receiptOf(1));
    expect(warnings).toHaveLength(1);
  });
});

describe("coerceReceipts", () => {
  test("drops entries that are not receipts and keeps the rest in order", () => {
    const good = receiptOf(3);
    const raw: JsonObject = {
      receipts: [
        {
          requestId: "not-a-uuid",
          at: 1_001,
          workspaceId: "w1",
          paneId: "w1:p1",
          path: "/home/op/repo/.worktrees/x1",
          branch: "worktree/x1",
          launcherStarted: false,
        },
        {
          requestId: idOf(2),
          at: 1_002,
          workspaceId: "w2",
          paneId: "w2:p1",
          path: "/home/op/repo/.worktrees/x2",
          branch: "worktree/x2",
          launcherStarted: "yes",
        },
        {
          requestId: idOf(3),
          at: 1_003,
          workspaceId: "w3",
          paneId: "w3:p1",
          path: "/home/op/repo/.worktrees/x3",
          branch: "worktree/x3",
          launcherStarted: false,
        },
        7,
        null,
      ],
    };
    expect(coerceReceipts(raw)).toEqual([good]);
  });

  test("anything but a receipts list is empty", () => {
    expect(coerceReceipts(null)).toEqual([]);
    expect(coerceReceipts([])).toEqual([]);
    expect(coerceReceipts({ receipts: "x" })).toEqual([]);
  });
});

describe("intent receipt binding", () => {
  const key = new Uint8Array(32).fill(9);
  const binding = {
    deviceIdentity: "device-1",
    operation: "launch",
    runtime: "default",
    intentFingerprint: keyedIntentFingerprint(key, { requestId: idOf(1), body: { z: 2, a: 1 } }),
  };

  test("canonicalizes object key order and uses a keyed fingerprint", () => {
    expect(canonicalJson({ z: 2, a: { y: true, b: null } })).toBe('{"a":{"b":null,"y":true},"z":2}');
    expect(keyedIntentFingerprint(key, { requestId: idOf(1), body: { a: 1, z: 2 } })).toBe(binding.intentFingerprint);
    expect(keyedIntentFingerprint(new Uint8Array(32).fill(8), { requestId: idOf(1), body: { z: 2, a: 1 } }))
      .not.toBe(binding.intentFingerprint);
    expect(() => keyedIntentFingerprint(new Uint8Array(31), null)).toThrow();
  });

  test("replay distinguishes exact bindings, cross-owner/context mismatches, and legacy receipts", () => {
    expect(matchReceiptBinding(binding, { ...binding })).toBe("match");
    expect(matchReceiptBinding(binding, { ...binding, deviceIdentity: "device-2" })).toBe("mismatch");
    expect(matchReceiptBinding(binding, { ...binding, intentFingerprint: "0".repeat(64) })).toBe("mismatch");
    expect(matchReceiptBinding(undefined, binding)).toBe("legacy-unbound");
    expect(matchReceiptBinding(binding, undefined)).toBe("mismatch");
  });

  test("valid binding metadata survives durable receipt coercion; malformed metadata is dropped", () => {
    const receipt = { ...receiptOf(1), binding };
    expect(coerceReceipts({ receipts: [receipt] })).toEqual([receipt]);
    expect(coerceReceipts({ receipts: [{ ...receipt, binding: { ...binding, intentFingerprint: "short" } }] })).toEqual([]);
  });
});

describe("intent receipt fingerprint key", () => {
  test("creates one owner-only stable key on demand", async () => {
    expect(await readdir(stateDir)).toEqual([]);
    const first = await loadOrCreateIntentReceiptKey(stateDir);
    const second = await loadOrCreateIntentReceiptKey(stateDir);
    expect(first).toHaveLength(32);
    expect(second).toEqual(first);
    expect(await readdir(stateDir)).toEqual([INTENT_RECEIPT_KEY_FILE]);
    if (process.platform !== "win32") {
      expect((await stat(join(stateDir, INTENT_RECEIPT_KEY_FILE))).mode & 0o777).toBe(0o600);
    }
  });

  test("a malformed existing key fails closed and remains untouched", async () => {
    const path = join(stateDir, INTENT_RECEIPT_KEY_FILE);
    await writeFile(path, "bad-key", { mode: 0o600 });
    await expect(loadOrCreateIntentReceiptKey(stateDir)).rejects.toThrow();
    expect(await Bun.file(path).text()).toBe("bad-key");
  });

  test("an oversized key file is refused before reading it into memory", async () => {
    const path = join(stateDir, INTENT_RECEIPT_KEY_FILE);
    await writeFile(path, "x".repeat(1_000_000), { mode: 0o600 });
    await expect(loadOrCreateIntentReceiptKey(stateDir)).rejects.toThrow();
    expect((await stat(path)).size).toBe(1_000_000);
  });
});

describe("the in-flight half", () => {
  const answer: WorktreeCreateResponse = {
    ok: true,
    alreadyOpen: false,
    launcherStarted: false,
    pane: { paneId: "w1:p1", workspaceId: "w1", workspaceLabel: "x", tabId: "w1:t1", cwd: "/x" },
  };

  for (const [name, make] of [
    ["file store", () => new WorktreeReceiptStore(stateDir, silent)],
    ["memory store", () => memoryWorktreeReceipts()],
  ] as const) {
    test(`${name}: a tracked create is joinable until it settles, then forgotten`, async () => {
      const store = make();
      let finish: (value: WorktreeCreateResponse) => void = () => {};
      const running = new Promise<WorktreeCreateResponse>((resolve) => {
        finish = resolve;
      });
      store.track(idOf(1), running);
      expect(store.inflight(idOf(1))).toBe(running);
      expect(store.inflight(idOf(2))).toBeUndefined();
      finish(answer);
      await running;
      await Promise.resolve();
      expect(store.inflight(idOf(1))).toBeUndefined();
    });
  }

  test("memory store: record and get", async () => {
    const store = memoryWorktreeReceipts();
    await store.record(receiptOf(1));
    expect(store.get(idOf(1))).toEqual(receiptOf(1));
  });
});
