import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import type { MuxPortableLayoutNode, MuxSpace } from "../types.ts";
import { HerdrMux, REPO_LOOKUP_RETRY_MS, workspaceRepoOf } from "./adapter.ts";
import { maskForwardedAnswer } from "../../crew/mask.ts";
import type { HerdrWorktreeRemove, WireWorktreeRemoved, HerdrLayoutNode, HerdrRpc, WireSnapshot, WireWorkspace, WireWorktreeListing } from "./client.ts";
import type { JsonValue } from "../../json.ts";
import { FakeHerdr } from "./fixture.ts";
import { buildSubscriptions } from "./events.ts";

// WHICH REPO A WORKSPACE SITS IN, across the two Herdr shapes (ADR 0032).
//
// herdr 0.8.2 put it on the workspace record (`worktree.repo_root`). herdr 0.9.3 (protocol 22) keeps
// the field in its schema and no longer sends it: the 0.9.3 capture below is the real answer, and it
// is what left every space without `repoRoot` on the dev lane, so "New agent on a branch" never
// showed. The repo now comes from `worktree.list` by workspace, looked up once and cached.

interface Capture {
  version: string;
  protocol: number;
  workspaces: WireWorkspace[];
  worktreeList: Record<string, { result: WireWorktreeListing }>;
  notGit: { error: { code: string; message: string } };
}

// SAFETY: a file this repo checks in, captured from herdr 0.9.3 and scrubbed (see its `_comment`).
const CAPTURE = JSON.parse(
  readFileSync(join(import.meta.dir, "captures", "workspaces-0.9.3.json"), "utf8"),
) as Capture;

const NOT_GIT = `herdr worktree.list: ${CAPTURE.notGit.error.code}: ${CAPTURE.notGit.error.message}`;

/** What the fake answers one workspace's `worktree.list` with. */
type Answer = { readonly listing: WireWorktreeListing } | { readonly error: string };

/** The real adapter over a fake that answers from a fixed set of workspace records. */
class CapturedHerdr extends FakeHerdr {
  lookups: string[] = [];
  /** Per workspace: a listing, or the error Herdr answers. Absent is not_git_worktree. */
  answers = new Map<string, Answer>();

  constructor(public records: WireWorkspace[]) {
    super();
  }

  override async sessionSnapshot(): Promise<WireSnapshot> {
    return { version: CAPTURE.version, protocol: CAPTURE.protocol, workspaces: this.records, tabs: [], panes: [] };
  }

  override async workspaceWorktrees(workspaceId: string): Promise<WireWorktreeListing> {
    this.lookups.push(workspaceId);
    const answer = this.answers.get(workspaceId) ?? { error: NOT_GIT };
    if ("error" in answer) throw new Error(answer.error);
    return answer.listing;
  }
}

function captured(): CapturedHerdr {
  const fake = new CapturedHerdr(CAPTURE.workspaces);
  for (const [id, reply] of Object.entries(CAPTURE.worktreeList)) fake.answers.set(id, { listing: reply.result });
  return fake;
}

class PortableReplyHerdr extends FakeHerdr {
  applyReply: "matching" | "wrong-tab" | "wrong-tree" = "matching";
  ratioReply: "matching" | "unchanged" | "wrong-tab" = "matching";

  private layout(root: HerdrLayoutNode, workspaceId: string, tabId: string, mode: "matching" | "wrong-tab" | "wrong-tree") {
    let next = 0;
    const ids = (node: HerdrLayoutNode): HerdrLayoutNode => {
      if (node.type === "split") return { ...node, first: ids(node.first), second: ids(node.second) };
      const id = `new:p${++next}`;
      return { ...node, pane_id: id, label: mode === "wrong-tree" && next === 1 ? "unexpected" : node.label };
    };
    return { workspace_id: workspaceId, tab_id: mode === "wrong-tab" ? "other:t1" : tabId,
      focused_pane_id: "new:p1", zoomed: false, root: ids(root) };
  }

  async applyLayout(params: Parameters<NonNullable<HerdrRpc["applyLayout"]>>[0]): Promise<JsonValue> {
    const snapshot = await this.sessionSnapshot();
    const workspace = snapshot.workspaces[0]!;
    const tab = snapshot.tabs.find((item) => item.workspace_id === workspace.workspace_id)!;
    // SAFETY: the fixture's response is a wire-shaped JSON value built from the closed layout DTO.
    return JSON.parse(JSON.stringify({ type: "layout_apply", layout: this.layout(params.root, workspace.workspace_id, tab.tab_id, this.applyReply) })) as JsonValue;
  }

  async setSplitRatio(params: Parameters<NonNullable<HerdrRpc["setSplitRatio"]>>[0]): Promise<JsonValue> {
    const snapshot = await this.sessionSnapshot();
    const workspace = snapshot.workspaces[0]!;
    const tab = snapshot.tabs.find((item) => item.workspace_id === workspace.workspace_id)!;
    const ratio = this.ratioReply === "unchanged" ? 0.5 : params.ratio;
    const root: HerdrLayoutNode = { type: "split", direction: "right", ratio,
      first: { type: "pane", pane_id: "new:p1", label: "left", cwd: null, command: null, env: {} },
      second: { type: "pane", pane_id: "new:p2", label: "right", cwd: null, command: null, env: {} } };
    // SAFETY: the fixture's response is a wire-shaped JSON value built from the closed layout DTO.
    return JSON.parse(JSON.stringify({ type: "layout_split_ratio_set", layout: this.layout(root, workspace.workspace_id,
      this.ratioReply === "wrong-tab" ? "other:t1" : tab.tab_id, "matching") })) as JsonValue;
  }
}

function spaceOf(spaces: readonly MuxSpace[], id: string): MuxSpace {
  const space = spaces.find((candidate) => candidate.spaceId === id);
  if (space === undefined) throw new Error(`no space ${id}`);
  return space;
}

const plainWorkspace = (id: string, label: string): WireWorkspace => ({
  workspace_id: id,
  number: 9,
  label,
  focused: false,
  pane_count: 1,
  tab_count: 1,
  active_tab_id: `${id}:t1`,
  agent_status: "idle",
});

describe("herdr 0.9.3: the repo comes from worktree.list", () => {
  test("the captured records carry no worktree field, which is the regression", () => {
    expect(CAPTURE.version).toBe("0.9.3");
    for (const workspace of CAPTURE.workspaces) expect(workspace.worktree).toBeUndefined();
  });

  test("every captured workspace gets repoRoot, isWorktree and its folder", async () => {
    const mux = new HerdrMux(captured(), () => []);
    const { spaces } = await mux.snapshot();
    expect(spaces).toHaveLength(4);
    expect(spaceOf(spaces, "w51")).toMatchObject({
      label: "harbor",
      repoRoot: "/home/operator/projects/harbor",
      isWorktree: false,
      folder: "/home/operator/projects/harbor",
    });
    for (const space of spaces) {
      expect(space.repoRoot).toBe(`/home/operator/projects/${space.label}`);
      expect(space.isWorktree).toBe(false);
    }
  });

  test("a workspace outside Git has no repo, and the snapshot still answers", async () => {
    const fake = captured();
    fake.records = [...CAPTURE.workspaces, plainWorkspace("w90", "scratch")];
    const { spaces } = await new HerdrMux(fake, () => []).snapshot();
    const scratch = spaceOf(spaces, "w90");
    expect("repoRoot" in scratch).toBe(false);
    expect("isWorktree" in scratch).toBe(false);
    expect("folder" in scratch).toBe(false);
  });

  test("looked up once per workspace, not once per poll", async () => {
    const fake = captured();
    fake.records = [...CAPTURE.workspaces, plainWorkspace("w90", "scratch")];
    const mux = new HerdrMux(fake, () => []);
    await mux.snapshot();
    await mux.snapshot();
    await Promise.all([mux.snapshot(), mux.snapshot()]);
    expect(fake.lookups.toSorted()).toEqual(["w2H", "w47", "w51", "w54", "w90"]);
  });

  test("overlapping first snapshots ask Herdr once", async () => {
    const fake = captured();
    const mux = new HerdrMux(fake, () => []);
    await Promise.all([mux.snapshot(), mux.snapshot(), mux.snapshot()]);
    expect(fake.lookups).toHaveLength(4);
  });

  test("a new workspace is looked up, a closed one is forgotten", async () => {
    const fake = captured();
    const mux = new HerdrMux(fake, () => []);
    await mux.snapshot();
    fake.records = CAPTURE.workspaces.filter((w) => w.workspace_id !== "w47");
    await mux.snapshot();
    fake.records = [...CAPTURE.workspaces];
    await mux.snapshot();
    // w47 came back as a workspace the cache had dropped, so it is asked again — once.
    expect(fake.lookups.filter((id) => id === "w47")).toHaveLength(2);
    expect(fake.lookups).toHaveLength(5);
  });

  test("a failed lookup leaves the space bare and is retried only after the backoff", async () => {
    const fake = captured();
    fake.answers.set("w51", { error: "herdr worktree.list: timed out after 5000ms" });
    const mux = new HerdrMux(fake, () => []);
    const first = await mux.snapshot();
    expect("repoRoot" in spaceOf(first.spaces, "w51")).toBe(false);
    expect(spaceOf(first.spaces, "w54").repoRoot).toBe("/home/operator/projects/kennel");

    await mux.snapshot();
    expect(fake.lookups.filter((id) => id === "w51")).toHaveLength(1);

    const realNow = Date.now;
    const start = realNow();
    Date.now = () => start + REPO_LOOKUP_RETRY_MS + 1;
    try {
      const harbor = CAPTURE.worktreeList["w51"];
      if (harbor === undefined) throw new Error("capture lost w51");
      fake.answers.set("w51", { listing: harbor.result });
      const later = await mux.snapshot();
      expect(spaceOf(later.spaces, "w51").repoRoot).toBe("/home/operator/projects/harbor");
    } finally {
      Date.now = realNow;
    }
    expect(fake.lookups.filter((id) => id === "w51")).toHaveLength(2);
  });

  test("the event stream coming up clears the cache", async () => {
    const fake = captured();
    const mux = new HerdrMux(fake, () => []);
    await mux.snapshot();
    let ups = 0;
    const watch = mux.watch({
      panes: [],
      onUp: () => void (ups += 1),
      onDown: () => undefined,
      onTopologyChange: () => undefined,
      onPaneChange: () => undefined,
    });
    await Promise.resolve();
    await Promise.resolve();
    expect(ups).toBe(1);
    await mux.snapshot();
    watch.close();
    expect(fake.lookups).toHaveLength(8);
  });

  test("a linked worktree is the entry that names the workspace, not source_checkout_path", () => {
    // Shape from a 0.9.3 probe of a linked worktree: `source` names the MAIN checkout even then.
    const listing: WireWorktreeListing = {
      source: {
        repo_root: "/home/operator/projects/kennel/collie",
        repo_key: "/home/operator/projects/kennel/collie/.git",
        repo_name: "collie",
        source_checkout_path: "/home/operator/projects/kennel/collie",
        source_workspace_id: "w54",
      },
      worktrees: [
        {
          path: "/home/operator/projects/kennel/collie",
          branch: "main",
          is_linked_worktree: false,
          is_prunable: false,
          is_bare: false,
          is_detached: false,
          open_workspace_id: "w54",
        },
        {
          path: "/home/operator/apps/collie-remix",
          branch: "remix-v3",
          is_linked_worktree: true,
          is_prunable: false,
          is_bare: false,
          is_detached: false,
          open_workspace_id: "w60",
        },
      ],
    };
    expect(workspaceRepoOf("w60", listing)).toEqual({
      repoRoot: "/home/operator/projects/kennel/collie",
      isWorktree: true,
      folder: "/home/operator/apps/collie-remix",
    });
    expect(workspaceRepoOf("w54", listing)).toEqual({
      repoRoot: "/home/operator/projects/kennel/collie",
      isWorktree: false,
      folder: "/home/operator/projects/kennel/collie",
    });
    // No entry names it: the repo is known, the checkout is not — no folder, and not a worktree.
    expect(workspaceRepoOf("w77", listing)).toEqual({
      repoRoot: "/home/operator/projects/kennel/collie",
      isWorktree: false,
    });
    expect(workspaceRepoOf("w54", { ...listing, source: { repo_root: "" } })).toBeNull();
  });
});

describe("herdr pane process metadata", () => {
  test("maps foreground cwd and restore error without changing pane cwd", async () => {
    const fake = new FakeHerdr();
    const wire = (await fake.listPanes())[0]!;
    wire.foreground_cwd = "/tmp/foreground";
    wire.restore_error = "snapshot restore failed";
    const pane = (await new HerdrMux(fake, () => []).snapshot()).panes.find((candidate) => candidate.paneId === wire.pane_id)!;
    expect(pane.cwd).toBe(wire.cwd);
    expect(pane.foregroundCwd).toBe("/tmp/foreground");
    expect(pane.restoreError).toBe("snapshot restore failed");
  });

  test("asks for the explicit pane and returns OS-unavailable fields as absent", async () => {
    const fake = new FakeHerdr();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const result = await new HerdrMux(fake, () => []).processInfo(paneId);
    expect(fake.processInfoTargets).toEqual([paneId]);
    expect(result).toMatchObject({ ok: true, value: { paneId, processes: [{ pid: 100, name: "zsh" }] } });
  });

  test("discards a process result when the terminal occupant changed, regardless of PID", async () => {
    class ReplacedOccupant extends FakeHerdr {
      override async processInfo(paneId: string) {
        const value = await super.processInfo(paneId);
        this.changeTerminalOccupant(paneId, "different-terminal");
        return value;
      }
    }
    const fake = new ReplacedOccupant();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const result = await new HerdrMux(fake, () => []).processInfo(paneId);
    expect(result).toMatchObject({ ok: false, reason: "gone" });
    expect(fake.processInfoTargets).toEqual([paneId]);
  });
});

describe("herdr pane layout capability and neutral adapter", () => {
  const admitLayoutMutation = () => true;
  test("layout invalidation subscription is added only after positive runtime support", () => {
    expect(buildSubscriptions([]).some((entry) => entry.type === "layout.updated")).toBe(false);
    expect(buildSubscriptions([], true).some((entry) => entry.type === "layout.updated")).toBe(true);
  });
  test("layout APIs stay absent until a schema-22 0.9.3 handshake and stay absent on older servers", async () => {
    const old = new FakeHerdr();
    old.layoutVersion = "0.9.2";
    const oldMux = new HerdrMux(old, () => []);
    await oldMux.reachable();
    expect(oldMux.capabilities.supports.paneLayout).toBe(false);
    expect(oldMux.capabilities.supports.paneMove).toBe(false);
    expect(oldMux.capabilities.supports.removeWorktree).toBe(false);
    expect((await oldMux.readLayout("w1:p3")).ok).toBe(false);

    const current = new FakeHerdr();
    const mux = new HerdrMux(current, () => []);
    await mux.reachable();
    expect(mux.capabilities.supports.paneLayout).toBe(true);
    expect(mux.capabilities.supports.paneMove).toBe(true);
    expect(mux.capabilities.supports.removeWorktree).toBe(true);
    const snapshot = await mux.snapshot();
    const source = snapshot.panes[0]!;
    const layout = await mux.readLayout(source.paneId);
    expect(layout).toMatchObject({ ok: true, value: { kind: "layout", layout: { workspaceId: source.spaceId, tabId: source.tabId } } });
    expect(source.terminalIncarnation).toMatch(/^[a-f0-9]{64}$/);
  });

  test("neutral worktree removal requires the current verified Herdr runtime and maps no vendor trust option", async () => {
    class RemovableHerdr extends FakeHerdr {
      seen: HerdrWorktreeRemove | null = null;
      async removeWorktree(request: HerdrWorktreeRemove): Promise<WireWorktreeRemoved> {
        this.seen = request;
        return { type: "worktree_removed", workspace_id: request.workspace_id, path: "/repo/.worktrees/topic", forced: request.force };
      }
    }
    const old = new RemovableHerdr();
    old.layoutVersion = "0.9.2";
    const oldMux = new HerdrMux(old, () => []);
    await oldMux.reachable();
    expect(await oldMux.removeWorktree({ workspaceId: "w1", force: false })).toMatchObject({ ok: false, reason: "unsupported" });
    expect(old.seen).toBeNull();

    const current = new RemovableHerdr();
    const mux = new HerdrMux(current, () => []);
    await mux.reachable();
    expect(await mux.removeWorktree({ workspaceId: "w1", force: true })).toEqual({
      ok: true, value: { workspaceId: "w1", path: "/repo/.worktrees/topic", forced: true },
    });
    expect(current.seen).toEqual({ workspace_id: "w1", force: true });
  });

  test("portable apply and ratio replies prove the exact target and requested effect", async () => {
    const fake = new PortableReplyHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const snapshot = await mux.snapshot();
    const source = snapshot.panes[0]!;
    const root: MuxPortableLayoutNode = { type: "split", direction: "right", ratio: 0.5,
      first: { type: "pane", label: "left", cwd: null, paneId: null, command: null, env: {} },
      second: { type: "pane", label: "right", cwd: null, paneId: null, command: null, env: {} } };
    const request = { root, workspaceId: source.spaceId, tabId: source.tabId, tabLabel: null, focus: false as const };
    expect((await mux.applyPortableLayout(request, admitLayoutMutation)).ok).toBe(true);
    fake.applyReply = "wrong-tree";
    expect(await mux.applyPortableLayout(request, admitLayoutMutation)).toMatchObject({ ok: false, reason: "unreachable" });
    fake.applyReply = "wrong-tab";
    expect(await mux.applyPortableLayout(request, admitLayoutMutation)).toMatchObject({ ok: false, reason: "unreachable" });

    const ratio = { tabId: source.tabId, paneId: null, path: [] as const, ratio: 0.625 };
    expect((await mux.setSplitRatio(ratio, admitLayoutMutation)).ok).toBe(true);
    fake.ratioReply = "unchanged";
    expect(await mux.setSplitRatio(ratio, admitLayoutMutation)).toMatchObject({ ok: false, reason: "unreachable" });
    fake.ratioReply = "wrong-tab";
    expect(await mux.setSplitRatio(ratio, admitLayoutMutation)).toMatchObject({ ok: false, reason: "unreachable" });
  });

  test("runtime replacement retires pane-layout readiness and requires a new positive handshake", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    expect(mux.capabilities.supports.paneLayout).toBe(true);
    fake.runtimeIdentity = "fake-herdr-runtime-2";
    const stale = await mux.readLayout("w1:p3");
    expect(stale.ok).toBe(false);
    expect(mux.capabilities.supports.paneLayout).toBe(false);
    await mux.reachable();
    expect(mux.capabilities.supports.paneLayout).toBe(true);
    expect(await mux.layoutRuntimeIdentity()).toBe("fake-herdr-runtime-2");
  });

  test("missing layout evidence probes ordinary health and never keeps positive layout capabilities", async () => {
    for (const healthy of [false, true]) {
      const fake = new FakeHerdr();
      let probes = 0;
      fake.ping = async () => { probes++; return healthy; };
      const mux = new HerdrMux(fake, () => []);
      expect(await mux.reachable()).toBe(true); // Compatible, stable sample needs no fallback probe.
      expect(probes).toBe(0);
      expect(mux.capabilities.supports.paneLayout).toBe(true);
      fake.layoutHandshakeSample = async () => null;
      expect(await mux.reachable()).toBe(healthy);
      expect(probes).toBe(1);
      for (const capability of ["paneLayout", "paneSplit", "paneResize", "paneZoom", "paneSwap", "paneMove", "paneDirectionalFocus"] as const)
        expect(mux.capabilities.supports[capability]).toBe(false);
    }
  });

  test("directional swap rechecks synchronous admission after neighbor preflight", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const source = (await mux.snapshot()).panes[0]!;
    const readNeighbor = fake.readPaneNeighbor.bind(fake);
    let entered!: () => void;
    let release!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    const blocked = new Promise<void>((resolve) => { release = resolve; });
    fake.readPaneNeighbor = async (paneId, direction) => {
      entered();
      await blocked;
      return readNeighbor(paneId, direction);
    };
    let allowed = true;
    let mutations = 0;
    const swap = fake.swapPanes.bind(fake);
    fake.swapPanes = async (params) => { mutations++; return swap(params); };
    const pending = mux.applyLayout({ kind: "swap", paneId: source.paneId, direction: "right", sourcePaneId: source.paneId, targetPaneId: null }, () => allowed);
    await started;
    allowed = false;
    release();
    const result = await pending;
    expect(result.ok).toBe(false);
    expect(mutations).toBe(0);
  });

  test("a malformed mutation discriminant becomes uncertain transport and retires readiness", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const pane = (await mux.snapshot()).panes[0]!;
    fake.resizePane = async () => ({ type: "pane_zoom", zoom: {} });
    const result = await mux.applyLayout({ kind: "resize", paneId: pane.paneId, direction: "right", amount: 0.1 }, admitLayoutMutation);
    expect(result.ok).toBe(false);
    expect(mux.capabilities.supports.paneLayout).toBe(false);
  });

  test("split is focus-off, swap refuses a cross-tab target, and workspace moves rebind pane identity", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const before = await mux.snapshot();
    const source = before.panes[0]!;
    const operatorFocused = before.panes.find((pane) => pane.focused)?.paneId;
    const split = await mux.applyLayout({ kind: "split", paneId: source.paneId, direction: "right", ratio: 0.4, targetPaneId: source.paneId, workspaceId: source.spaceId, focus: false }, admitLayoutMutation);
    expect(split).toMatchObject({ ok: true, value: { kind: "split" } });
    const after = await mux.snapshot();
    expect(after.panes.find((pane) => pane.focused)?.paneId).toBe(operatorFocused);
    expect(after.panes).toHaveLength(before.panes.length + 1);
    const otherTab = after.panes.find((pane) => pane.tabId !== source.tabId)!;
    expect(await mux.applyLayout({ kind: "swap", paneId: source.paneId, direction: null, sourcePaneId: source.paneId, targetPaneId: otherTab.paneId }, admitLayoutMutation)).toMatchObject({ ok: true, value: { kind: "swap", changed: false } });
    const moved = await mux.applyLayout({ kind: "move", paneId: source.paneId, destination: { type: "new_workspace", label: "moved", tabLabel: "main" }, focus: false }, admitLayoutMutation);
    expect(moved).toMatchObject({ ok: true, value: { kind: "move", changed: true, previousPaneId: source.paneId, previousTabId: source.tabId, previousWorkspaceId: source.spaceId } });
    if (moved.ok && moved.value.kind === "move") {
      expect(moved.value.paneId).not.toBe(source.paneId);
      expect(moved.value.layout.workspaceId).not.toBe(source.spaceId);
    }
  });
});

describe("herdr 0.8.2: the repo is on the workspace record", () => {
  // The record shape probed 2026-08-28 on herdr 0.8.2, as 0.9.3's schema still describes it
  // (WorkspaceWorktreeInfo). Read straight off the record; no worktree.list call is made.
  const linked: WireWorkspace = {
    ...plainWorkspace("w3", "feature"),
    worktree: {
      repo_root: "/home/operator/projects/kennel/collie",
      repo_name: "collie",
      repo_key: "/home/operator/projects/kennel/collie/.git",
      checkout_path: "/home/operator/projects/kennel/collie/.worktrees/feature",
      is_linked_worktree: true,
    },
  };
  const main: WireWorkspace = {
    ...plainWorkspace("w1", "collie"),
    worktree: {
      repo_root: "/home/operator/projects/kennel/collie",
      checkout_path: "/home/operator/projects/kennel/collie",
      is_linked_worktree: false,
    },
  };

  test("repoRoot, isWorktree and folder come from the record, with no lookup", async () => {
    const fake = new CapturedHerdr([main, linked]);
    const { spaces } = await new HerdrMux(fake, () => []).snapshot();
    expect(spaceOf(spaces, "w3")).toMatchObject({
      repoRoot: "/home/operator/projects/kennel/collie",
      isWorktree: true,
      folder: "/home/operator/projects/kennel/collie/.worktrees/feature",
    });
    expect(spaceOf(spaces, "w1")).toMatchObject({
      repoRoot: "/home/operator/projects/kennel/collie",
      isWorktree: false,
    });
    expect(fake.lookups).toEqual([]);
  });

  test("a 0.8.2 record without the field is looked up like a 0.9.3 one", async () => {
    const fake = new CapturedHerdr([main, plainWorkspace("w5", "notes")]);
    const { spaces } = await new HerdrMux(fake, () => []).snapshot();
    expect(fake.lookups).toEqual(["w5"]);
    expect("repoRoot" in spaceOf(spaces, "w5")).toBe(false);
  });
});

describe("Herdr pane agent identity join", () => {
  test("returns exact current owner facts without inventing the authority source", async () => {
    const herdr = new FakeHerdr();
    const pane = (await herdr.listPanes()).find((row) => row.agent != null)!;
    herdr.changePaneMetadata(pane.pane_id, {
      agent: "codex", agent_status: "working",
      agent_session: { source: "herdr:codex", agent: "codex", kind: "id", value: "session-42" },
    });
    herdr.agentInfoRows.push({
      pane_id: pane.pane_id, agent: "codex", agent_status: "working",
      agent_session: { source: "herdr:codex", agent: "codex", kind: "id", value: "session-42" },
      interactive_ready: true, launch_pending: false, state_change_seq: "9", completion_seq: "7",
    });
    const result = await new HerdrMux(herdr, () => []).agentIdentity(pane.pane_id);
    expect(result).toMatchObject({
      ok: true,
      value: {
        paneId: pane.pane_id, agent: "codex", status: "working",
        stateAuthority: { observed: false, source: null }, interactiveReady: true,
        launchPending: false, stateChangeSeq: "9", completionSeq: "7",
        session: { source: "herdr:codex", value: "session-42" },
      },
    });
  });

  test("refuses a keyed agent.get identity that differs from pane.get", async () => {
    class ConflictingAgent extends FakeHerdr {
      override async getAgent(target: string) {
        const agent = await super.getAgent(target);
        return { ...agent, agent: "different-agent" };
      }
    }
    const herdr = new ConflictingAgent();
    const pane = (await herdr.listPanes()).find((row) => row.agent != null)!;
    herdr.agentInfoRows.push({ pane_id: pane.pane_id, agent: pane.agent, agent_status: pane.agent_status });
    const mismatch = await new HerdrMux(herdr, () => []).agentIdentity(pane.pane_id);
    expect(mismatch).toMatchObject({ ok: false, reason: "gone" });
  });

  test("removes control characters and bounds vendor identity text before it reaches a phone", async () => {
    const herdr = new FakeHerdr();
    const pane = (await herdr.listPanes()).find((row) => row.agent != null)!;
    const noisy = `co\ndex${"x".repeat(400)}`;
    herdr.changePaneMetadata(pane.pane_id, { agent: noisy, agent_status: "working" });
    herdr.agentInfoRows.push({
      pane_id: pane.pane_id, agent: noisy, agent_status: "working",
      agent_session: { source: "claude", agent: "claude", kind: "id", value: "0a1b2c3d-session" },
    });
    const result = await new HerdrMux(herdr, () => []).agentIdentity(pane.pane_id);
    expect(result).toMatchObject({ ok: true, value: { agent: `codex${"x".repeat(251)}` } });
  });

  test("redacts credentials across the synthetic clipping boundary before clipping identity fields", async () => {
    const herdr = new FakeHerdr();
    const pane = (await herdr.listPanes()).find((row) => row.agent != null)!;
    const secret = "sk-" + "BoundaryCredential".repeat(4);
    const source = `${"x".repeat(72)} ${secret}`;
    herdr.changePaneMetadata(pane.pane_id, {
      agent: "codex", agent_status: "working",
      agent_session: { source, agent: "codex", kind: "id", value: "session-safe" },
    });
    herdr.agentInfoRows.push({
      pane_id: pane.pane_id, agent: "codex", agent_status: "working",
      agent_session: { source, agent: "codex", kind: "id", value: "session-safe" },
    });
    const result = await new HerdrMux(herdr, () => []).agentIdentity(pane.pane_id);
    expect(result.ok).toBe(true);
    if (result.ok) {
      const clipped = result.value.session?.source ?? "";
      expect(clipped.length).toBeLessThanOrEqual(80);
      expect(clipped).not.toContain("sk-BoundaryCredential");
      expect(clipped).toContain("sk-");
      expect(clipped).toContain("•");
      const masked = maskForwardedAnswer("agent", JSON.stringify({ ok: true, ...result.value }), pane.pane_id);
      expect(masked).not.toBeNull();
      expect(masked).not.toContain("BoundaryCredential");
    }
  });

  test("projects exact full-range uint64 diagnostics without inventing authority", async () => {
    const herdr = new FakeHerdr();
    const pane = (await herdr.listPanes()).find((row) => row.agent != null)!;
    herdr.agentInfoRows.push({
      pane_id: pane.pane_id, agent: pane.agent, agent_status: pane.agent_status,
      agent_session: pane.agent_session?.source && pane.agent_session.agent &&
        (pane.agent_session.kind === "id" || pane.agent_session.kind === "path") && pane.agent_session.value
        ? { source: pane.agent_session.source, agent: pane.agent_session.agent, kind: pane.agent_session.kind, value: pane.agent_session.value }
        : null,
      state_change_seq: "9007199254740993", completion_seq: "18446744073709551615",
    });
    const result = await new HerdrMux(herdr, () => []).agentIdentity(pane.pane_id);
    expect(result).toMatchObject({ ok: true, value: {
      stateChangeSeq: "9007199254740993", completionSeq: "18446744073709551615",
      stateAuthority: { observed: false, source: null },
    } });
  });
});


test("worktree adapters repeat original authority after awaited preparation immediately before the named vendor method", async () => {
  class CountedHerdr extends FakeHerdr {
    creates = 0;
    removes = 0;
    override async createWorktree(options: Parameters<FakeHerdr["createWorktree"]>[0]) { this.creates++; return super.createWorktree(options); }
    async removeWorktree(request: HerdrWorktreeRemove): Promise<WireWorktreeRemoved> { this.removes++; return { type: "worktree_removed", workspace_id: request.workspace_id, path: "/repo/topic", forced: request.force }; }
  }
  for (const action of ["create", "remove"] as const) for (const replace of [false, true]) {
    const fake = new CountedHerdr();
    const mux = new HerdrMux(fake, () => []);
    await mux.reachable();
    const original = await mux.layoutRuntimeIdentity();
    let allowed = true;
    const admission = (identity: string) => allowed && identity === original;
    const prepare = async () => { await Promise.resolve(); if (replace) fake.runtimeIdentity = "replacement"; else allowed = false; return true; };
    const result = action === "create" ? await mux.createWorktree({ repoRoot: "/repo", branch: "topic" }, admission, prepare) : await mux.removeWorktree({ workspaceId: "w1", force: false }, admission, prepare);
    expect(result.ok).toBe(false);
    expect(fake.creates + fake.removes).toBe(0);
  }
  const positive = new CountedHerdr();
  const mux = new HerdrMux(positive, () => []);
  await mux.reachable();
  const original = await mux.layoutRuntimeIdentity();
  expect((await mux.createWorktree({ repoRoot: "/repo", branch: "fresh" }, (identity) => identity === original, async () => true)).ok).toBe(true);
  expect((await mux.removeWorktree({ workspaceId: "w1", force: false }, (identity) => identity === original, async () => true)).ok).toBe(true);
  expect(positive.creates).toBe(1);
  expect(positive.removes).toBe(1);
});
