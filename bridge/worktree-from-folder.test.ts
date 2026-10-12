import { HerdrMux } from "./mux/herdr/adapter.ts";
import { FakeHerdr } from "./mux/herdr/fixture.ts";
import { withAgentHints } from "./beacon/hint.ts";
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdir, mkdtemp, readdir, realpath, rename, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { AuditLog } from "./audit.ts";
import type { JsonObject, JsonValue } from "./json.ts";
import { harnessLaunch } from "./harness-launch.ts";
import { createWorktreeAt, planWorktree, openWorktree } from "./server.ts";
import type { StateEngine } from "./state-engine.ts";
import type { WorktreeCreateResponse, WorktreePlanResponse } from "./types.ts";
import {
  muxAck,
  muxOk,
  type MuxAck,
  type MuxAdapter,
  type MuxCreatedPane,
  type MuxGrid,
  type MuxOutcome,
  type MuxWorktreeCreateRequest,
} from "./mux/types.ts";
import { CHOICES_FILE, WorktreeChoiceStore, coerceChoice, memoryWorktreeChoices } from "./worktree-choices.ts";
import { memoryWorktreeReceipts } from "./worktree-receipts.ts";
import { OperationStore } from "./control/operations.ts";

// The New sheet's branch from a FOLDER (M48, ADR 0093), against a real git repo in a temporary home:
//
//   1. The plan finds the repo from any folder in it, a linked worktree included, and answers the
//      default and current branch, Herdr's default folder and the folder rule's verdict on a parent.
//   2. The create runs the folder rule again at use time and sends `path` only for "Other folder".
//   3. A refusal reaches no multiplexer; a create that worked is remembered per repo, never before.
//   4. A known request id replays; a harness id types the bridge's own binary.

const ID = "4f1c2b3a-9d8e-4c7b-a6f5-0e1d2c3b4a59";

let root: string;
let home: string;
let repo: string;

async function git(cwd: string, ...args: string[]): Promise<void> {
  const proc = Bun.spawn(["git", ...args], {
    cwd,
    stdout: "ignore",
    stderr: "pipe",
    env: { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1", HOME: home },
  });
  if ((await proc.exited) !== 0) throw new Error(`git ${args.join(" ")}: ${await new Response(proc.stderr).text()}`);
}

beforeEach(async () => {
  root = await realpath(await mkdtemp(join(tmpdir(), "collie-wt-from-folder-")));
  home = join(root, "home", "op");
  repo = join(home, "src", "app");
  await mkdir(join(repo, "sub"), { recursive: true });
  await mkdir(join(home, "trees"), { recursive: true });
  await git(repo, "init", "-q", "-b", "main");
  await git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "one");
  await git(repo, "branch", "fix-tabs-base");
  await git(repo, "worktree", "add", "-q", "-b", "side", join(home, "trees", "side"));
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
});

const deps = () => ({ home, readHerdrConfig: () => Promise.resolve(null) });

async function plan(query: Record<string, string>, choices = memoryWorktreeChoices()) {
  const url = new URL("http://x/api/worktree/plan");
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, v);
  const res = await planWorktree(new Request(url), choices, deps());
  // SAFETY: planWorktree wrote this body and typed it as WorktreePlanResponse itself.
  return (await res.json()) as WorktreePlanResponse;
}

function engine(): StateEngine {
  const stub: Partial<StateEngine> = {
    pokeNow: () => {},
    current: () => ({ agents: [], shellPanes: [], workspaces: [], tabs: [], bridge: "connected" }),
  };
  // SAFETY: the create reaches `current()` (a replay's label) and `pokeNow()` and nothing else.
  return stub as StateEngine;
}

class FakeMux {
  readonly creates: MuxWorktreeCreateRequest[] = [];
  readonly texts: Array<[string, string]> = [];
  gate: Promise<void> | null = null;
  async createWorktree(request: MuxWorktreeCreateRequest, finalAdmission?: (identity: string) => boolean, beforeDispatch?: () => Promise<boolean>): Promise<MuxOutcome<MuxCreatedPane>> {
    if (beforeDispatch && !await beforeDispatch()) return { ok: false, reason: "refused", detail: "target_changed" };
    if (finalAdmission && !finalAdmission("fixture-runtime")) return { ok: false, reason: "refused", detail: "authority_changed" };
    this.creates.push(request);
    if (this.gate !== null) await this.gate;
    await Promise.resolve();
    return muxOk({ paneId: "w7:p1", spaceId: "w7", spaceLabel: "app-fix", tabId: "w7:t1", cwd: request.path ?? "/x" });
  }
  readGrid(paneId: string): Promise<MuxOutcome<MuxGrid>> {
    return Promise.resolve(muxOk({ paneId, text: "$ ", truncated: false, revision: 1 }));
  }
  typeText(paneId: string, text: string): Promise<MuxAck> {
    this.texts.push([paneId, text]);
    return Promise.resolve(muxAck());
  }
  sendKeys(): Promise<MuxAck> {
    return Promise.resolve(muxAck());
  }
  closePane(): Promise<MuxAck> {
    return Promise.resolve(muxAck());
  }
  refresh(): Promise<void> {
    return Promise.resolve();
  }
}

function asMux(fake: Partial<MuxAdapter>): MuxAdapter {
  // SAFETY: a create from a folder reaches createWorktree, then readGrid, typeText and sendKeys for
  // a launcher, and refresh: the members FakeMux implements.
  return fake as MuxAdapter;
}

const clock = () => {
  let ms = 0;
  return {
    now: () => ms,
    sleep: (by: number) => {
      ms += by;
      return Promise.resolve();
    },
  };
};

async function create(mux: FakeMux, body: JsonObject, extra: {
  choices?: ReturnType<typeof memoryWorktreeChoices>;
  receipts?: ReturnType<typeof memoryWorktreeReceipts>;
  operations?: OperationStore;
  finalAdmission?: (identity: string) => boolean;
  receiptContext?: { key: Uint8Array; deviceIdentity: string; owningHost?: string; runtime: string } | null;
} = {}) {
  const res = await createWorktreeAt(
    asMux(mux),
    engine(),
    new Request("http://x/api/worktree", { method: "POST", body: JSON.stringify(body) }),
    new AuditLog(() => {}),
    null,
    "default",
    () => Promise.resolve([]),
    {
      ...deps(),
      receipts: extra.receipts ?? memoryWorktreeReceipts(),
      operations: extra.operations,
      finalAdmission: extra.finalAdmission,
      receiptContext: extra.receiptContext,
      choices: extra.choices,
      harnesses: { launch: harnessLaunch },
      wait: clock(),
    },
  );
  // SAFETY: createWorktreeAt wrote this body as a WorktreeCreateResponse.
  return { status: res.status, body: (await res.json()) as WorktreeCreateResponse };
}

describe("GET /api/worktree/plan", () => {
  test("from a folder deep in the repo: its root, default and current branch", async () => {
    expect(await plan({ cwd: join(repo, "sub") })).toEqual({ ok: true, repoRoot: repo, defaultBranch: "main", currentBranch: "main" });
  });

  test("from a linked worktree: the MAIN checkout, and the worktree's own branch", async () => {
    expect(await plan({ cwd: join(home, "trees", "side") })).toMatchObject({ ok: true, repoRoot: repo, currentBranch: "side" });
  });

  test("a folder in no repo is not_a_repo, a name with no leading / or ~ is under home, one with .. is folder_invalid", async () => {
    expect(await plan({ cwd: join(home, "trees") })).toMatchObject({ ok: false, code: "worktree.not_a_repo" });
    expect(await plan({ cwd: "src/app" })).toMatchObject({ ok: true, repoRoot: repo });
    expect(await plan({ cwd: "src/../app" })).toMatchObject({ ok: false, code: "worktree.folder_invalid" });
  });

  test("with a branch: Herdr's default folder; with a parent: the rule's verdict", async () => {
    const answer = await plan({ cwd: repo, branch: "Fix/Tabs", parent: "~/trees" });
    expect(answer).toMatchObject({
      ok: true,
      branchValid: true,
      defaultTarget: { path: join(home, ".herdr", "worktrees", "app", "fix-tabs"), exists: false },
      parentTarget: { ok: true, path: join(home, "trees", "fix-tabs") },
    });
    expect(await plan({ cwd: repo, branch: "x", parent: repo })).toMatchObject({ parentTarget: { ok: false, code: "worktree.folder_in_repo" } });
  });

  test("a branch git refuses is branchValid false, and no folder is worked out", async () => {
    const answer = await plan({ cwd: repo, branch: "a..b", parent: "~/trees" });
    expect(answer).toMatchObject({ ok: true, branchValid: false });
    expect(answer).not.toHaveProperty("parentTarget");
  });

  test("the plan hands back what this repo remembers", async () => {
    const choices = memoryWorktreeChoices();
    await choices.record(repo, { base: "current", folder: "parent", parent: join(home, "trees"), at: 1 });
    expect(await plan({ cwd: join(repo, "sub") }, choices)).toMatchObject({
      remembered: { base: "current", folder: "parent", parent: join(home, "trees") },
    });
  });
});

describe("POST /api/worktree", () => {
  test("Herdr's default sends no path, and the repo root, branch and base reach Herdr", async () => {
    const mux = new FakeMux();
    const { body } = await create(mux, { cwd: join(repo, "sub"), branch: "fix-tabs", base: { kind: "default" } });
    expect(body.ok).toBe(true);
    expect(mux.creates).toEqual([{ repoRoot: repo, branch: "fix-tabs", base: "main" }]);
  });

  test("Other folder sends the checked child of the parent", async () => {
    const mux = new FakeMux();
    await create(mux, { cwd: repo, branch: "feat/x", folder: { kind: "parent", parent: "~/trees" } });
    expect(mux.creates[0]?.path).toBe(join(home, "trees", "feat-x"));
  });

  test("the rule runs at use time: a parent that broke since the plan reaches no Herdr", async () => {
    const mux = new FakeMux();
    await symlink(join(home, "trees"), join(home, "via"));
    const cases: Array<[JsonValue, string]> = [
      [{ kind: "parent", parent: join(home, "via") }, "worktree.folder_link"],
      [{ kind: "parent", parent: root }, "worktree.folder_outside_home"],
      [{ kind: "parent", parent: `${home}/trees/../trees` }, "worktree.folder_invalid"],
      [{ kind: "parent", parent: join(repo, ".git") }, "worktree.folder_hidden"],
      [{ kind: "parent", parent: join(repo, "sub") }, "worktree.folder_in_repo"],
      [{ kind: "parent", parent: join(home, "trees") }, "worktree.target_exists"],
      [{ kind: "elsewhere" }, "worktree.folder_invalid"],
    ];
    for (const [folder, code] of cases) {
      const { body } = await create(mux, { cwd: repo, branch: "side", folder });
      expect({ folder, body }).toMatchObject({ folder, body: { ok: false, code } });
    }
    expect(mux.creates).toEqual([]);
  });

  test("a branch that reads as a flag, or one git refuses, reaches no Herdr", async () => {
    const mux = new FakeMux();
    expect((await create(mux, { cwd: repo, branch: "-D" })).body).toMatchObject({ code: "worktree.invalid_branch" });
    expect((await create(mux, { cwd: repo, branch: "a..b" })).body).toMatchObject({ code: "worktree.invalid_branch" });
    expect((await create(mux, { cwd: repo, branch: "ok", base: { kind: "ref", ref: "--orphan" } })).body).toMatchObject({
      code: "worktree.invalid_base",
    });
    expect(mux.creates).toEqual([]);
  });

  test("a harness id types the bridge's own binary in the new folder", async () => {
    const mux = new FakeMux();
    const { body } = await create(mux, { cwd: repo, branch: "fix-tabs", harness: "codex" });
    expect(body).toMatchObject({ ok: true, launcherStarted: true });
    expect(mux.texts).toEqual([["w7:p1", "codex"]]);
    expect((await create(mux, { cwd: repo, branch: "x2", harness: "rm -rf" })).body).toMatchObject({
      code: "launch.unknown_harness",
    });
  });

  test("a create that worked is remembered for the repo; a refusal is not", async () => {
    const choices = memoryWorktreeChoices();
    await create(new FakeMux(), { cwd: repo, branch: "side", folder: { kind: "parent", parent: "~/trees" } }, { choices });
    expect(choices.get(repo)).toBeUndefined();
    await create(new FakeMux(), { cwd: repo, branch: "fix", base: { kind: "ref", ref: "side" }, folder: { kind: "parent", parent: "~/trees" } }, { choices });
    expect(choices.get(repo)).toMatchObject({ base: "current", folder: "parent", parent: join(home, "trees") });
  });

  test("a known request id replays and Herdr is asked once", async () => {
    const mux = new FakeMux();
    const receipts = memoryWorktreeReceipts();
    await create(mux, { cwd: repo, branch: "fix-tabs", requestId: ID }, { receipts });
    const again = await create(mux, { cwd: repo, branch: "fix-tabs", requestId: ID }, { receipts });
    expect(again.body).toMatchObject({ ok: true, replayed: true });
    expect(mux.creates).toHaveLength(1);
  });

  test("the durable operation ledger blocks replay after createAt receipt eviction and restart", async () => {
    const mux = new FakeMux();
    const path = join(root, "operations");
    const context = { key: new Uint8Array(32).fill(8), deviceIdentity: "a".repeat(64), owningHost: "host-a", runtime: "default" };
    const first = new OperationStore(path);
    expect(await first.prepare()).toBe(true);
    const body = { cwd: repo, branch: "durable-create-at", requestId: ID };
    expect((await create(mux, body, { operations: first, receiptContext: context })).body.ok).toBe(true);
    const restarted = new OperationStore(path);
    expect(await restarted.prepare()).toBe(true);
    const retry = await create(mux, body, { operations: restarted, receiptContext: context, receipts: memoryWorktreeReceipts() });
    expect(retry.body).toMatchObject({ ok: false, code: "launch.outcome_unknown" });
    expect(mux.creates).toHaveLength(1);
  });

  test("a concurrent createAt intent joins the durable effect already in flight", async () => {
    const mux = new FakeMux();
    let open!: () => void;
    mux.gate = new Promise<void>((resolve) => { open = resolve; });
    const receipts = memoryWorktreeReceipts();
    const operations = new OperationStore();
    expect(await operations.prepare()).toBe(true);
    const receiptContext = { key: new Uint8Array(32).fill(8), deviceIdentity: "a".repeat(64), owningHost: "host-a", runtime: "default" };
    const body = { cwd: repo, branch: "concurrent-create-at", requestId: ID };
    const first = create(mux, body, { receipts, operations, receiptContext });
    for (let i = 0; mux.creates.length === 0 && i < 100; i++) await Bun.sleep(5);
    expect(mux.creates).toHaveLength(1);
    const second = create(mux, body, { receipts, operations, receiptContext });
    await Bun.sleep(100);
    open();
    const [a, b] = await Promise.all([first, second]);
    expect(a.body.ok).toBe(true);
    expect(b.body).toMatchObject({ ok: true, replayed: true });
    expect(mux.creates).toHaveLength(1);
  });
});

describe("WorktreeChoiceStore — the file", () => {
  test("loading writes nothing; a record writes one owner-only file a restart reads back", async () => {
    const dir = join(root, "state");
    await mkdir(dir);
    const store = new WorktreeChoiceStore(dir, () => {});
    await store.load();
    expect(await readdir(dir)).toEqual([]);
    await store.record(repo, { base: "default", folder: "parent", parent: "/p", at: 1 });
    await store.record(repo, { base: "current", folder: "default", at: 2 });
    expect(await readdir(dir)).toEqual([CHOICES_FILE]);
    const again = new WorktreeChoiceStore(dir, () => {});
    await again.load();
    // The default kind keeps the last parent, so switching back to Other folder still offers it.
    expect(again.get(repo)).toEqual({ base: "current", folder: "default", parent: "/p", at: 2 });
    // NTFS has no 0600 mode bits; on Windows the state folder's access list keeps the file private.
    if (process.platform !== "win32") expect((await Bun.file(join(dir, CHOICES_FILE)).stat()).mode & 0o777).toBe(0o600);
  });

  test("an entry that is not a choice is dropped", () => {
    expect(coerceChoice({ base: "sideways", folder: "default", at: 1 })).toBeNull();
    expect(coerceChoice({ base: "default", folder: "parent", parent: 3, at: 1 })).toBeNull();
  });
});


describe("create-at final durable-reservation boundary", () => {
  for (const changed of ["child", "parent", "policy"] as const) test(`a ${changed} change during durable reservation cannot dispatch the frozen parent create`, async () => {
    const mux = new FakeMux();
    const context = { key: new Uint8Array(32).fill(8), deviceIdentity: "a".repeat(64), owningHost: "host-a", runtime: "default" };
    let allowed = true;
    let persisted = 0;
    const operations = new OperationStore(join(root, "race-operations"), Date.now, async () => {
      if (++persisted !== 1) return;
      if (changed === "child") await mkdir(join(home, "trees", "final-race"));
      else if (changed === "parent") { await rename(join(home, "trees"), join(home, "old-trees")); await symlink(join(root, "outside"), join(home, "trees")); }
      else allowed = false;
    });
    await mkdir(join(root, "outside"));
    await operations.prepare();
    const body = { cwd: repo, branch: "final-race", requestId: ID, folder: { kind: "parent", parent: "~/trees" } };
    const first = await create(mux, body, { operations, receiptContext: context, finalAdmission: () => allowed });
    expect(first.body.ok).toBe(false);
    expect(mux.creates).toHaveLength(0);
    const again = await create(mux, body, { operations, receiptContext: context, finalAdmission: () => true });
    expect(again.body.ok).toBe(false);
    expect(mux.creates).toHaveLength(0);
  });
});


describe("B15 original authority across actual hinted owner dispatch", () => {
  class CountedOwner extends FakeHerdr {
    creations = 0; opens = 0; textCalls = 0; keyCalls = 0;
    override async createWorktree(options: { cwd: string; branch: string }) { this.creations++; return super.createWorktree(options); }
    override async openWorktree(options: { cwd: string; path: string }) { this.opens++; return super.openWorktree(options); }
    override async sendPaneText(paneId: string, value: string) { this.textCalls++; return super.sendPaneText(paneId, value); }
    override async sendPaneKeys(paneId: string, keys: string[]) { this.keyCalls++; return super.sendPaneKeys(paneId, keys); }
  }
  function deferred() { let release!: () => void; const promise = new Promise<void>((resolve) => { release = resolve; }); return { promise, release }; }
  async function owner() {
    const client = new CountedOwner(); const raw = new HerdrMux(client, () => []);
    await raw.reachable();
    return { client, mux: withAgentHints(raw, { hooksInstalled: () => false }) };
  }
  for (const retirement of ["pairing", "runtime", "between-text-enter", "unchanged"] as const) {
    test(`post-create base custody and text/Enter keep the original ${retirement} witness and same-ID checkout`, async () => {
      const { client, mux } = await owner(); const originalRuntime = client.runtimeIdentity;
      let allowed = true;
      const entered = deferred(); const release = deferred(); const receipts = memoryWorktreeReceipts();
      const branch = "custody-" + retirement;
      let ms = 0;
      const invoke = () => createWorktreeAt(mux, engine(), new Request("http://x/api/worktree", { method: "POST", body: JSON.stringify({ cwd: repo, branch, base: { kind: "default" }, harness: "codex", requestId: ID }) }), new AuditLog(() => {}), "original-device", "default", async () => [], {
        ...deps(), receipts, harnesses: { launch: harnessLaunch },
        bases: { get: () => undefined, record: async () => { entered.release(); await release.promise; } },
        finalAdmission: (identity) => allowed && identity === originalRuntime,
        wait: { now: () => ms, sleep: async (by) => { ms += by; if (retirement === "between-text-enter" && client.textCalls > 0) allowed = false; } },
      });
      const pending = invoke(); await entered.promise;
      expect(client.creations).toBe(1); expect(client.textCalls).toBe(0); expect(client.keyCalls).toBe(0);
      if (retirement === "pairing") allowed = false;
      if (retirement === "runtime") client.runtimeIdentity = "replacement-runtime";
      release.release();
      const result = await (await pending).json();
      expect(result.ok).toBe(true); expect(result.pane).toBeDefined(); // The checkout exists.
      expect(result.launcherStarted).toBe(retirement === "unchanged");
      expect(client.textCalls).toBe(retirement === "unchanged" || retirement === "between-text-enter" ? 1 : 0);
      expect(client.keyCalls).toBe(retirement === "unchanged" ? 1 : 0);
      await invoke(); // Identical receipt recovery never recreates or retypes.
      expect(client.creations).toBe(1);
      expect(client.textCalls).toBe(retirement === "unchanged" || retirement === "between-text-enter" ? 1 : 0);
      expect(client.keyCalls).toBe(retirement === "unchanged" ? 1 : 0);
    });
  }
  for (const changed of ["authority", "runtime", "target", "unchanged"] as const) {
    test(`actual hinted create preparation refuses ${changed} replacement at named vendor boundary`, async () => {
      const { client, mux } = await owner(); const runtime = client.runtimeIdentity;
      const entered = deferred(); const release = deferred(); let allowed = true; let target = true;
      const finalAdmission = (identity: string) => allowed && identity === runtime;
      const beforeDispatch = async () => { entered.release(); await release.promise; return target; };
      const pending = mux.createWorktree({ repoRoot: repo, branch: "decorated-" + changed }, finalAdmission, beforeDispatch);
      await entered.promise;
      if (changed === "authority") allowed = false;
      if (changed === "runtime") client.runtimeIdentity = "new-runtime";
      if (changed === "target") target = false;
      release.release(); const result = await pending;
      expect(result.ok).toBe(changed === "unchanged"); expect(client.creations).toBe(changed === "unchanged" ? 1 : 0);
    });
  }
  test("actual open reads held body under original root and final hinted owner gate", async () => {
    for (const changed of ["authority", "runtime", "target", "unchanged"] as const) {
      const { client, mux } = await owner();
      const created = await client.createWorktree({ cwd: repo, branch: "opened-" + changed });
      const runtime = client.runtimeIdentity; let allowed = true; let currentRoot = repo;
      // Only the authoritative current root is needed by this real route helper.
      const current: Partial<StateEngine> = { pokeNow() {}, current: () => ({ agents: [], shellPanes: [], tabs: [], bridge: "connected", workspaces: [{ workspaceId: "root", number: 1, label: "root", focused: false, activeTabId: "tab", paneCount: 1, tabCount: 1, repoRoot: currentRoot }] }) };
      // SAFETY: openWorktree consumes only current() and pokeNow(); its named owner is real HerdrMux.
      const state = current as StateEngine;
      const entered = deferred(); const release = deferred();
      const req = new Request("http://x/api/workspace/root/worktree/open", { method: "POST", body: JSON.stringify({ path: created.cwd }) });
      const readBody = req.json.bind(req);
      req.json = async () => { entered.release(); await release.promise; return readBody(); };
      const pending = openWorktree(mux, state, "root", req, new AuditLog(() => {}), "original", "default", (identity) => allowed && identity === runtime);
      await entered.promise;
      if (changed === "authority") allowed = false;
      if (changed === "runtime") client.runtimeIdentity = "new-open-runtime";
      if (changed === "target") currentRoot = "/replacement-root";
      release.release(); const result = await (await pending).json();
      expect(result.ok).toBe(changed === "unchanged"); expect(client.opens).toBe(changed === "unchanged" ? 1 : 0);
    }
  });
  for (const held of ["choices", "ready"] as const) {
    test(`actual post-create ${held} await refuses old caller and keeps known checkout`, async () => {
      const { client, mux } = await owner(); const runtime = client.runtimeIdentity; let allowed = true;
      const entered = deferred(); const release = deferred(); const receipts = memoryWorktreeReceipts();
      const choices = memoryWorktreeChoices();
      const actualChoices = { get: choices.get.bind(choices), record: async (...args: Parameters<typeof choices.record>) => { if (held === "choices") { entered.release(); await release.promise; } return choices.record(...args); } };
      const read = mux.readGrid.bind(mux);
      mux.readGrid = async (paneId, request) => { if (held === "ready") { entered.release(); await release.promise; } return read(paneId, request); };
      let ms = 0;
      const pending = createWorktreeAt(mux, engine(), new Request("http://x/api/worktree", { method: "POST", body: JSON.stringify({ cwd: repo, branch: "await-" + held, base: { kind: "default" }, harness: "codex", requestId: ID }) }), new AuditLog(() => {}), "original", "default", async () => [], {
        ...deps(), receipts, choices: actualChoices, harnesses: { launch: harnessLaunch }, finalAdmission: (identity) => allowed && identity === runtime,
        wait: { now: () => ms, sleep: async (by) => { ms += by; } },
      });
      await entered.promise; expect(client.creations).toBe(1); allowed = false; release.release();
      const result = await (await pending).json();
      expect(result.ok).toBe(true); expect(result.pane).toBeDefined(); expect(result.launcherStarted).toBe(false);
      expect(client.creations).toBe(1); expect(client.textCalls).toBe(0); expect(client.keyCalls).toBe(0);
      expect(receipts.get(ID)?.launcherStarted).toBe(false);
    });
  }
  test("actual hinted open preparation rechecks original owner after its final target await", async () => {
    const { client, mux } = await owner(); const created = await client.createWorktree({ cwd: repo, branch: "open-preparation" });
    const runtime = client.runtimeIdentity; let allowed = true; const entered = deferred(); const release = deferred();
    const pending = mux.openWorktree({ repoRoot: repo, path: created.cwd }, (identity) => allowed && identity === runtime, async () => { entered.release(); await release.promise; return true; });
    await entered.promise; allowed = false; release.release();
    expect((await pending).ok).toBe(false); expect(client.opens).toBe(0);
    expect((await mux.openWorktree({ repoRoot: repo, path: created.cwd }, (identity) => identity === runtime, async () => true)).ok).toBe(true);
    expect(client.opens).toBe(1);
  });

});
