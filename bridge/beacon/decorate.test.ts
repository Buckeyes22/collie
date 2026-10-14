import { describe, expect, test } from "bun:test";

import { withAgentBeacons, type AgentBeaconDeps, type BeaconMatcher } from "./decorate.ts";
import { withAgentHints } from "./hint.ts";
import { fakeBeaconReader, FAKE_BEACON_NOW, type FakeBeacon } from "./fake.ts";
import { BEACON_SCHEMA_VERSION, type BeaconMarker, type BeaconStatus } from "./types.ts";
import { declareCapabilities, type MuxCapability, type MuxCapabilityDeclaration } from "../mux/capabilities.ts";
import { herdrHostControl } from "../host-control/herdr.ts";
import {
  muxAck,
  muxGone,
  muxOk,
  type MuxAdapter,
  type MuxGridRequest,
  type MuxLayoutMutation,
  type MuxLayoutRead,
  type MuxPortableLayout,
  type MuxLayoutCreated,
  type MuxOutcome,
  type MuxPane,
  type MuxSession,
  type MuxSnapshot,
  type MuxSpaceRequest,
  type MuxStarterOutcome,
  type MuxStarters,
  type MuxSubscription,
  type MuxTabRequest,
  type MuxWatchOptions,
  type MuxWorktree,
  type MuxWorktreeCreateRequest,
  type MuxWorktreeOpenRequest,
  type MuxWorktreeScope,
} from "../mux/types.ts";
import type { ForegroundProcess } from "../journal/types.ts";

// THE DECORATOR'S OWN TESTS — the join, the two-way capability lift, and the pass-through.
//
// The pass-through is pinned method by method rather than by reading the source: a decorator that
// reimplements one verb is a fork of the adapter under it, and the failure would be invisible until
// the two drift.

const NAMESPACE = "fixture-mux";
const SCOPE = "/fixture/socket";

/** One pane, in the shape a blind adapter reports: a shell of unknown status, no session. */
function shellPane(paneId: string): MuxPane {
  return {
    paneId,
    spaceId: "space",
    spaceLabel: "space",
    spaceNumber: 1,
    tabId: "tab",
    cwd: "/tmp",
    focused: false,
    alive: true,
    agent: "shell",
    status: "unknown",
  };
}

/** One call the decorated adapter made, as the adapter underneath saw it. */
interface RecordedCall {
  readonly method: string;
  readonly args: readonly unknown[];
}

/** A blind adapter that records what reached it and answers something distinguishable. */
class StubAdapter implements MuxAdapter {
  readonly mux = "stub";
  /** The adapter's mark, when a case gives it one. Absent by default, like an adapter with none. */
  logo?: string;
  hostControl?: MuxAdapter["hostControl"];
  /** The unwrapped pane read, when a case gives it one. Absent by default, like a multiplexer that
   *  cannot unwrap at all (tmux) — the decorators must carry it without inventing it. */
  readLogicalText?: (paneId: string, lines: number) => Promise<MuxOutcome<string>>;
  processInfo?: (paneId: string) => Promise<MuxOutcome<import("../mux/types.ts").MuxPaneProcessInfo>>;
  readLayout?: MuxAdapter["readLayout"];
  applyLayout?: MuxAdapter["applyLayout"];
  layoutRuntimeIdentity?: MuxAdapter["layoutRuntimeIdentity"];
  removeWorktree?: MuxAdapter["removeWorktree"];

  exportPortableLayout?: MuxAdapter["exportPortableLayout"];
  applyPortableLayout?: MuxAdapter["applyPortableLayout"];
  setSplitRatio?: MuxAdapter["setSplitRatio"];
  renameSpace?: MuxAdapter["renameSpace"];
  closeSpace?: MuxAdapter["closeSpace"];
  moveSpace?: MuxAdapter["moveSpace"];
  moveSpaceBlock?: MuxAdapter["moveSpaceBlock"];
  focusSpace?: MuxAdapter["focusSpace"];
  moveTab?: MuxAdapter["moveTab"];
  focusTab?: MuxAdapter["focusTab"];
  agentIdentity?: (paneId: string) => Promise<MuxOutcome<import("../mux/types.ts").MuxAgentIdentity>>;
  reportPaneMetadata?: MuxAdapter["reportPaneMetadata"];
  reportWorkspaceMetadata?: MuxAdapter["reportWorkspaceMetadata"];
  /** The starter pair of a durable program (ADR 0102). Absent by default, like every other multiplexer. */
  listStarters?: () => Promise<MuxStarters>;
  startStarter?: (starterId: string, requestId: string) => Promise<MuxStarterOutcome>;
  /** The two ask methods (ADR 0100), when a case gives them. Absent by default, like every adapter
   *  whose panes have a screen. */
  openAsks?: MuxAdapter["openAsks"];
  answerAsk?: MuxAdapter["answerAsk"];
  /** The two control methods (ADR 0101), when a case gives them. Absent by default, like every
   *  adapter whose panes have a screen. */
  paneTurn?: MuxAdapter["paneTurn"];
  controlTurn?: MuxAdapter["controlTurn"];
  /** The foreground argv, when a case gives it one. Absent by default, like a multiplexer
   *  without the call — the decorators must carry it without inventing it. */
  foregroundProcesses?: (paneId: string) => Promise<MuxOutcome<readonly ForegroundProcess[]>>;
  capabilities = declareCapabilities({
    supports: ["paneGrid", "typeText", "sendKeys"],
    notes: { agentDetection: "the adapter's own note", closePane: "untouched" },
    topologyLatency: { kind: "push" },
  });
  readonly calls: RecordedCall[] = [];
  panes: MuxPane[] = [shellPane("%1"), shellPane("%2")];
  readonly subscription: MuxSubscription = { close: () => undefined };

  private note(method: string, ...args: readonly unknown[]): void {
    this.calls.push({ method, args });
  }

  reachable(): Promise<boolean> {
    this.note("reachable");
    return Promise.resolve(true);
  }

  refresh(): Promise<void> {
    this.note("refresh");
    return Promise.resolve();
  }

  snapshot(): Promise<MuxSnapshot> {
    this.note("snapshot");
    return Promise.resolve({ panes: this.panes, spaces: [], tabs: [] });
  }

  readGrid(paneId: string, request: MuxGridRequest) {
    this.note("readGrid", paneId, request);
    return Promise.resolve(muxOk({ paneId, text: "grid", truncated: false, revision: 7 }));
  }

  typeText(paneId: string, text: string) {
    this.note("typeText", paneId, text);
    return Promise.resolve(muxAck());
  }

  sendKeys(paneId: string, keys: readonly string[]) {
    this.note("sendKeys", paneId, keys);
    return Promise.resolve(muxAck());
  }

  renamePane(paneId: string, label: string | null) {
    this.note("renamePane", paneId, label);
    return Promise.resolve(muxAck());
  }

  closePane(paneId: string) {
    this.note("closePane", paneId);
    return Promise.resolve(muxGone("gone"));
  }

  setFocus(paneId: string) {
    this.note("setFocus", paneId);
    return Promise.resolve(muxAck());
  }

  createTab(request: MuxTabRequest) {
    this.note("createTab", request);
    return Promise.resolve(muxOk({ paneId: "%9", spaceId: "space", spaceLabel: "space", tabId: "tab", cwd: "/tmp" }));
  }

  renameTab(tabId: string, label: string) {
    this.note("renameTab", tabId, label);
    return Promise.resolve(muxAck());
  }

  closeTab(tabId: string) {
    this.note("closeTab", tabId);
    return Promise.resolve(muxAck());
  }

  createSpace(request: MuxSpaceRequest) {
    this.note("createSpace", request);
    return Promise.resolve(muxOk({ paneId: "%9", spaceId: "space", spaceLabel: "space", tabId: "tab", cwd: "/tmp" }));
  }

  listSessions() {
    this.note("listSessions");
    return Promise.resolve(muxOk<readonly MuxSession[]>([{ name: "default", endpoint: "/tmp/stub.sock" }]));
  }

  listWorktrees(scope: MuxWorktreeScope) {
    this.note("listWorktrees", scope);
    return Promise.resolve(muxOk<readonly MuxWorktree[]>([]));
  }

  createWorktree(request: MuxWorktreeCreateRequest) {
    this.note("createWorktree", request);
    return Promise.resolve(muxOk({ paneId: "%9", spaceId: "space", spaceLabel: "space", tabId: "tab", cwd: "/tmp" }));
  }

  openWorktree(request: MuxWorktreeOpenRequest) {
    this.note("openWorktree", request);
    return Promise.resolve(muxOk({ pane: { paneId: "%9", spaceId: "space", spaceLabel: "space", tabId: "tab", cwd: "/tmp" }, alreadyOpen: false }));
  }


  watch(options: MuxWatchOptions): MuxSubscription {
    this.note("watch", options);
    return this.subscription;
  }
}

/** The matcher a fixture multiplexer would contribute: pane id equality inside one scope. */
function stubMatcher(scope: string | null = SCOPE): BeaconMatcher {
  return {
    namespace: NAMESPACE,
    scope: () => Promise.resolve(scope),
    matches: (pane, entry, resolved) => entry.scope === resolved && entry.pane === pane.paneId,
    notesWithoutHooks: {
      agentDetection: "install the hooks and a pane names itself",
      agentSessionRef: "install the hooks and the agent supplies its session",
    },
  };
}

function beacon(markers: readonly BeaconMarker[], status: BeaconStatus, pid: number, alive = true): FakeBeacon {
  return {
    record: {
      schemaVersion: BEACON_SCHEMA_VERSION,
      harness: "claude",
      session: { kind: "id", value: `session-${String(pid)}` },
      status,
      pid,
      pidStartTime: 11,
      markers,
      heartbeatMs: FAKE_BEACON_NOW,
    },
    alive,
  };
}

function marker(pane: string, scope = SCOPE): BeaconMarker {
  return { namespace: NAMESPACE, scope, pane };
}

function deps(hooksInstalled: boolean, matcher: BeaconMatcher = stubMatcher()): AgentBeaconDeps {
  return { matcher, hooksInstalled: () => hooksInstalled };
}

/** The decorated adapter over one seeded beacon directory. */
function decorate(adapter: MuxAdapter, beacons: readonly FakeBeacon[], hooks = true, matcher = stubMatcher()): MuxAdapter {
  return withAgentBeacons(adapter, fakeBeaconReader(beacons), deps(hooks, matcher));
}

describe("production decorator composition preserves declared read bounds", () => {
  for (const maxRequestedLines of [1_000, 10_000]) {
    test(`hooks and hints keep the owning adapter's ${maxRequestedLines}-line ceiling`, () => {
      const metadata: Omit<MuxCapabilityDeclaration, "supports" | "notes"> = {
        unsupportedKeys: ["F1"],
        spaces: "one",
        topologyLatency: { kind: "bounded", ms: 321 },
        readLimits: { maxRequestedLines },
      };
      const raw = new StubAdapter();
      raw.capabilities = declareCapabilities({ ...metadata, supports: ["paneGrid", "gridScrollback", "typeText", "sendKeys"] });
      let installed = false;
      const beacons = withAgentBeacons(raw, fakeBeaconReader([]), { matcher: stubMatcher(), hooksInstalled: () => installed });
      const composed = withAgentHints(beacons, { hooksInstalled: () => installed });
      for (const state of [false, true, false]) {
        installed = state;
        for (const wrapped of [beacons, composed]) {
          expect(wrapped.capabilities.readLimits.maxRequestedLines).toBe(maxRequestedLines);
          expect(wrapped.capabilities.spaces).toBe(metadata.spaces);
          expect(wrapped.capabilities.topologyLatency).toEqual(metadata.topologyLatency);
          expect(wrapped.capabilities.unsupportedKeys).toEqual(metadata.unsupportedKeys);
          expect(wrapped.capabilities.supports.agentDetection).toBe(state);
          expect(wrapped.capabilities.supports.agentSessionRef).toBe(state);
        }
      }
    });
  }
});

async function paneOf(adapter: MuxAdapter, paneId: string): Promise<MuxPane> {
  const found = (await adapter.snapshot()).panes.find((pane) => pane.paneId === paneId);
  if (found === undefined) throw new Error(`no pane ${paneId} in the snapshot`);
  return found;
}

describe("withAgentBeacons refuses an adapter that already sees", () => {
  test("an adapter declaring agentDetection is refused at construction", () => {
    const adapter = new StubAdapter();
    adapter.capabilities = declareCapabilities({ supports: ["agentDetection"], topologyLatency: { kind: "push" } });
    expect(() => decorate(adapter, [])).toThrow(/already declares agentDetection/u);
  });

  test("an adapter declaring agentSessionRef is refused at construction", () => {
    const adapter = new StubAdapter();
    adapter.capabilities = declareCapabilities({ supports: ["agentSessionRef"], topologyLatency: { kind: "push" } });
    expect(() => decorate(adapter, [])).toThrow(/already declares agentSessionRef/u);
  });
});

// THE FIELD THAT WENT MISSING, and the tripwire for the next one.
//
// `logo` was added to MuxAdapter and to all three adapters, and no live instance ever published it:
// index.ts wraps every adapter in a decorator, each decorator rebuilds the adapter as a literal
// naming every field, and an unnamed field simply ceases to exist. Nothing caught it, because every
// test in the tree that read a logo read it off a RAW adapter.
//
// So the data fields are gathered by one helper (bridge/mux/types.ts § muxDataFields) and the
// surface is asserted here, for BOTH decorators — `withAgentHints` included, even though it lives in
// hint.ts, because the failure is the shape they share and a guard in only one of them is half a
// guard.
describe("a decorator preserves the adapter's whole surface", () => {
  /**
   * Every field of the contract, at runtime.
   *
   * `satisfies Record<keyof Required<MuxAdapter>, true>` is the half that has teeth: adding a field
   * to {@link MuxAdapter} stops this FILE compiling until the field is named here, and naming it
   * here then fails the assertions below until both decorators carry it. `Required<…>` so an
   * OPTIONAL field is covered too — which is the only kind that has ever gone missing.
   */
  const MUX_ADAPTER_FIELDS = {
    mux: true,
    capabilities: true,
    logo: true,
    hostControl: true,
    reachable: true,
    snapshot: true,
    refresh: true,
    readGrid: true,
    processInfo: true,
    agentIdentity: true,
    agentList: true,
    agentRead: true,
    agentControl: true,
    agentExplain: true,
    agentAction: true,
    agentWait: true,
    agentObservedMove: true,
    agentObservedTransition: true,
    reportPaneMetadata: true,
    reportPaneMetadataOriginal: true,
    reportWorkspaceMetadata: true,
    readLogicalText: true,
    readLayout: true,
    applyPortableLayout: true,
    exportPortableLayout: true,
    setSplitRatio: true,
    applyLayout: true,
    layoutRuntimeIdentity: true,
    listStarters: true,
    startStarter: true,
    openAsks: true,
    answerAsk: true,
    paneTurn: true,
    controlTurn: true,
    foregroundProcesses: true,
    typeText: true,
    sendKeys: true,
    renamePane: true,
    closePane: true,
    setFocus: true,
    createTab: true,
    renameTab: true,
    closeTab: true,
    renameSpace: true,
    closeSpace: true,
    moveSpace: true,
    moveSpaceBlock: true,
    focusSpace: true,
    moveTab: true,
    focusTab: true,
    createSpace: true,
    listWorktrees: true,
    createWorktree: true,
    openWorktree: true,
    removeWorktree: true,
    listSessions: true,
    watch: true,
  } satisfies Record<keyof Required<MuxAdapter>, true>;

  const CONTRACT = Object.keys(MUX_ADAPTER_FIELDS).toSorted();

  /**
   * Which contract fields this object actually has.
   *
   * `in`, not `Object.keys`: a raw adapter is a class, so its methods live on the prototype and its
   * private state (`#client`, a revision map) is own state that no decorator should be expected to
   * reproduce. Asking the contract's own questions compares the two shapes on the only terms that
   * mean anything.
   */
  function surfaceOf(adapter: MuxAdapter): string[] {
    return CONTRACT.filter((field) => field in adapter);
  }

  /** A raw adapter carrying EVERY field, so a decorator dropping any one of them is visible. */
  function fullAdapter(): MuxAdapter {
    const stub: StubAdapter & Pick<MuxAdapter, "agentList" | "agentRead" | "agentControl" | "agentExplain" | "agentAction" | "agentWait" | "agentObservedMove" | "agentObservedTransition" | "reportPaneMetadataOriginal"> = new StubAdapter();
    stub.logo = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"></svg>`;
    stub.hostControl = herdrHostControl;
    stub.readLogicalText = (paneId, lines) => Promise.resolve(muxOk(`${paneId}:${lines}`));
    stub.processInfo = (paneId) => Promise.resolve(muxOk({ paneId, processes: [], truncated: false }));
    const layout = { workspaceId: "space", tabId: "tab", zoomed: false as const, area: { x: 0, y: 0, width: 80, height: 24 }, focusedPaneId: "%1", panes: [{ paneId: "%1", focused: true, rect: { x: 0, y: 0, width: 80, height: 24 } }], splits: [] };
    stub.readLayout = async () => muxOk({ kind: "layout", layout } satisfies MuxLayoutRead);
    stub.applyLayout = async () => muxOk({ kind: "focus-direction", changed: false, paneId: "%1", layout } satisfies MuxLayoutMutation);
    stub.layoutRuntimeIdentity = () => "fixture-runtime-1";
    stub.removeWorktree = async ({ workspaceId, force }) => muxOk({ workspaceId, path: "/repo/.worktrees/topic", forced: force });

    const portable: MuxPortableLayout = {
      workspaceId: "space", tabId: "tab", focusedPaneId: "%1", zoomed: false,
      root: { type: "pane", label: null, cwd: null, paneId: "%1", command: null, env: {} },
    };
    stub.exportPortableLayout = async () => muxOk(portable);
    stub.applyPortableLayout = async (): Promise<MuxOutcome<MuxLayoutCreated>> => muxOk({ workspaceId: "space", tabId: "tab", paneIds: ["%2"], focusedPaneId: "%2" });
    stub.setSplitRatio = async () => muxOk(portable);
    stub.renameSpace = async () => ({ ok: true, value: undefined });
    stub.closeSpace = async () => ({ ok: true, value: undefined });
    stub.moveSpace = async () => ({ ok: true, value: undefined });
    stub.moveSpaceBlock = async () => ({ ok: true, value: undefined });
    stub.focusSpace = async () => ({ ok: true, value: undefined });
    stub.moveTab = async () => ({ ok: true, value: undefined });
    stub.focusTab = async () => ({ ok: true, value: undefined });
    stub.agentIdentity = (paneId) => Promise.resolve(muxOk({
      paneId, agent: null, status: "unknown", stateAuthority: { observed: false, source: null },
      interactiveReady: null, launchPending: null, stateChangeSeq: null, completionSeq: null,
    }));
    stub.agentList = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentRead = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentControl = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentExplain = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentAction = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentWait = async () => ({ ok: false, reason: "refused", detail: "fixture" });
    stub.agentObservedTransition = async () => ({ ok: false, reason: "gone", detail: "fixture" });
    stub.agentObservedMove = async () => ({ ok: false, reason: "gone", detail: "fixture" });
    stub.reportPaneMetadata = async () => muxOk(undefined);
    stub.reportPaneMetadataOriginal = async () => muxOk(undefined);
    stub.reportWorkspaceMetadata = async () => muxOk(undefined);
    stub.listStarters = () => Promise.resolve({ hostName: "stub", starters: [], reachable: true });
    stub.startStarter = (starterId) => Promise.resolve({ kind: "started", paneId: starterId, replayed: false });
    stub.openAsks = () => [];
    stub.answerAsk = () => Promise.resolve(muxOk({ kind: "taken", replayed: false }));
    stub.paneTurn = () => "turn-1";
    stub.controlTurn = () => Promise.resolve({ kind: "ok", replayed: false });
    stub.foregroundProcesses = () => Promise.resolve(muxOk([]));
    return stub;
  }

  test("the fixture itself is complete — otherwise the assertions below prove nothing", () => {
    // This is the test that fails FIRST when a field is added to the contract: name it above, and
    // this one demands the fixture carry it before anything gets to claim a decorator preserves it.
    expect(surfaceOf(fullAdapter())).toEqual(CONTRACT);
  });

  test("the beacon decorator hands on every field it was given", () => {
    expect(surfaceOf(decorate(fullAdapter(), []))).toEqual(CONTRACT);
  });

  test("the hint decorator hands on every field it was given", () => {
    expect(surfaceOf(withAgentHints(fullAdapter(), { hooksInstalled: () => false }))).toEqual(CONTRACT);
  });

  test("both decorators, stacked exactly as the bridge stacks them, still preserve it", () => {
    // index.ts wraps beacons first and hints outside them — the real chain, not one link of it.
    const raw = fullAdapter();
    const wrapped = withAgentHints(decorate(raw, []), { hooksInstalled: () => false });
    expect(surfaceOf(wrapped)).toEqual(CONTRACT);
    expect(wrapped.logo).toBe(raw.logo);
  });

  test("actual stacked decorators forward original named action and wait callbacks without recapture", async () => {
    const raw = fullAdapter(); const original: import("../control/agent-models.ts").AgentOccupant = { paneId: "w1:p1", terminalId: "terminal", workspaceId: "w1", tabId: "w1:t1", runtimeIdentity: "runtime", agent: "codex", sessionIdentity: null, processIdentity: "a".repeat(64), name: "named" };
    const signal = new AbortController().signal; let admitted = true; let preflights = 0; let actions = 0; let observations = 0;
    const current = (runtime: string) => admitted && runtime === original.runtimeIdentity;
    const firstBytes = async () => { preflights++; return current(original.runtimeIdentity); };
    raw.agentAction = async (_action, expected, finalAdmission, actualSignal, beforeDispatch) => {
      expect(expected).toBe(original); expect(finalAdmission).toBe(current); expect(actualSignal).toBe(signal); expect(beforeDispatch).toBe(firstBytes); expect(preflights).toBe(0);
      if (beforeDispatch && await beforeDispatch() && finalAdmission(expected.runtimeIdentity)) actions++;
      return { ok: false, reason: "refused", detail: "fixture" };
    };
    raw.agentWait = async (_condition, expected, timeout, finalAdmission, actualSignal) => { expect(expected).toBe(original); expect(timeout).toBe(1000); expect(finalAdmission).toBe(current); expect(actualSignal).toBe(signal); observations++; return { ok: false, reason: "refused", detail: "fixture" }; };
    const wrapped = withAgentHints(decorate(raw, []), { hooksInstalled: () => false }); admitted = false;
    await wrapped.agentAction?.({ kind: "rename", name: "new" }, original, current, signal, firstBytes); expect(actions).toBe(0); expect(preflights).toBe(1);
    await wrapped.agentWait?.({ kind: "lifecycle", until: ["idle"] }, original, 1000, current, signal); expect(observations).toBe(1);
    admitted = true; preflights = 0; await wrapped.agentAction?.({ kind: "rename", name: "new" }, original, current, signal, firstBytes); expect(actions).toBe(1);
  });

  test("stacked decorators preserve the captured observation owner and original moved proof without invoking them", async () => {
    const raw = fullAdapter(); const original: import("../control/agent-models.ts").AgentOccupant = { paneId: "w1:p1", terminalId: "terminal", workspaceId: "w1", tabId: "w1:t1", runtimeIdentity: "runtime", agent: "codex", sessionIdentity: null, processIdentity: "a".repeat(64), name: "named" };
    const controller = new AbortController(); let checks = 0; let calls = 0;
    const current = () => false; const owner = Object.freeze({ current: () => { checks++; return true; } });
    const moved = { previousPaneId: original.paneId, previousWorkspaceId: original.workspaceId, previousTabId: original.tabId, info: { ...original, paneId: "w1:p2", status: "idle" as const, focused: false, interactiveReady: false, launchPending: false, stateChangeSeq: "0", completionSeq: "0" } };
    raw.agentWait = async (_condition, expected, timeout, admission, signal, actualOwner) => { expect(expected).toBe(original); expect(timeout).toBe(1000); expect(admission).toBe(current); expect(signal).toBe(controller.signal); expect(actualOwner).toBe(owner); expect(checks).toBe(0); calls++; return { ok: false, reason: "gone", detail: "fixture" }; };
    raw.agentObservedMove = async (expected, proof, actualOwner) => { expect(expected).toBe(original); expect(proof).toBe(moved); expect(actualOwner).toBe(owner); expect(checks).toBe(0); calls++; return { ok: false, reason: "gone", detail: "fixture" }; };
    const wrapped = withAgentHints(decorate(raw, []), { hooksInstalled: () => false });
    await wrapped.agentWait?.({ kind: "event", event: "pane-moved", targetId: original.paneId, workspaceId: null, status: null, agent: null, label: null }, original, 1000, current, controller.signal, owner);
    await wrapped.agentObservedMove?.(original, moved, owner); expect(calls).toBe(2); expect(checks).toBe(0);
    const transition = { event: "pane-closed" as const, previousPaneId: original.paneId, previousWorkspaceId: original.workspaceId, previousTabId: original.tabId, terminalId: original.terminalId, after: null };
    raw.agentObservedTransition = async (expected, proof, actualOwner) => { expect(expected).toBe(original); expect(proof).toBe(transition); expect(actualOwner).toBe(owner); expect(checks).toBe(0); calls++; return { ok: false, reason: "gone", detail: "fixture" }; };
    const transitionWrapped = withAgentHints(decorate(raw, []), { hooksInstalled: () => false });
    await transitionWrapped.agentObservedTransition?.(original, transition, owner); expect(calls).toBe(3); expect(checks).toBe(0);
  });

  test("an adapter with no unwrapped read does not gain one from a decorator", () => {
    // Optional, so `in` is the question that matters: a decorator that defined the key would make
    // every multiplexer without the read look like it had one.
    expect("readLogicalText" in decorate(new StubAdapter(), [])).toBe(false);
    expect("readLogicalText" in withAgentHints(new StubAdapter(), { hooksInstalled: () => false })).toBe(false);
    // The ask methods too (ADR 0100): a screen adapter must never come out looking like it answers by id.
    // And the control methods (ADR 0101): a screen adapter must never come out refusing `/reply`.
    for (const field of ["openAsks", "answerAsk", "paneTurn", "controlTurn"]) {
      expect(field in decorate(new StubAdapter(), [])).toBe(false);
      expect(field in withAgentHints(new StubAdapter(), { hooksInstalled: () => false })).toBe(false);
    }
  });

  test("an adapter with no mark comes out with the KEY absent, through either decorator", () => {
    // Absent, not present-and-undefined: `"logo" in adapter` is the question `muxDataFields` keeps
    // answerable, and a decorator that assigned `logo: undefined` would answer it wrongly.
    expect("logo" in decorate(new StubAdapter(), [])).toBe(false);
    expect("logo" in withAgentHints(new StubAdapter(), { hooksInstalled: () => false })).toBe(false);
  });

  test("an explicitly declared host-control provider survives both decorators", () => {
    const raw = Object.assign(new StubAdapter(), { hostControl: herdrHostControl });
    expect(decorate(raw, []).hostControl).toBe(herdrHostControl);
    expect(withAgentHints(raw, { hooksInstalled: () => false }).hostControl).toBe(herdrHostControl);
  });
});

describe("everything but capabilities and snapshot is a pass-through", () => {
  const request: MuxGridRequest = { scope: "viewport", lines: 20, styling: "strip" };
  const watchOptions: MuxWatchOptions = {
    panes: ["%1"],
    onTopologyChange: () => undefined,
    onPaneChange: () => undefined,
    onUp: () => undefined,
    onDown: () => undefined,
  };

  test("every method reaches the wrapped adapter with the arguments it was given", async () => {
    const adapter = new StubAdapter();
    adapter.removeWorktree = async (removeRequest) => {
      adapter.calls.push({ method: "removeWorktree", args: [removeRequest] });
      return muxOk({ workspaceId: removeRequest.workspaceId, path: "/repo/.worktrees/topic", forced: removeRequest.force });
    };
    const decorated = decorate(adapter, []);
    expect(decorated.mux).toBe(adapter.mux);
    expect(await decorated.reachable()).toBe(true);
    expect(await decorated.readGrid("%1", request)).toEqual(await adapter.readGrid("%1", request));
    await decorated.typeText("%1", "hello");
    await decorated.sendKeys("%1", ["ctrl+c"]);
    await decorated.renamePane("%1", null);
    await decorated.closePane("%1");
    await decorated.setFocus("%1");
    await decorated.createTab({ spaceId: "space" });
    await decorated.renameTab("tab", "label");
    await decorated.closeTab("tab");
    await decorated.createSpace({ cwd: "/tmp" });
    expect(await decorated.removeWorktree?.({ workspaceId: "space", force: false })).toMatchObject({ ok: true });
    expect(decorated.watch(watchOptions)).toBe(adapter.subscription);

    const seen = adapter.calls.filter((call) => call.method !== "snapshot" && call.method !== "readGrid");
    expect(seen).toEqual([
      { method: "reachable", args: [] },
      { method: "typeText", args: ["%1", "hello"] },
      { method: "sendKeys", args: ["%1", ["ctrl+c"]] },
      { method: "renamePane", args: ["%1", null] },
      { method: "closePane", args: ["%1"] },
      { method: "setFocus", args: ["%1"] },
      { method: "createTab", args: [{ spaceId: "space" }] },
      { method: "renameTab", args: ["tab", "label"] },
      { method: "closeTab", args: ["tab"] },
      { method: "createSpace", args: [{ cwd: "/tmp" }] },
      { method: "removeWorktree", args: [{ workspaceId: "space", force: false }] },
      { method: "watch", args: [watchOptions] },
    ]);
  });

  test("the unwrapped read passes through with its arguments, through either decorator", async () => {
    const adapter = new StubAdapter();
    adapter.readLogicalText = (paneId, lines) => Promise.resolve(muxOk(`${paneId}/${lines}`));
    expect(await decorate(adapter, []).readLogicalText?.("%1", 20)).toEqual(
      await adapter.readLogicalText("%1", 20),
    );
    expect(
      await withAgentHints(adapter, { hooksInstalled: () => false }).readLogicalText?.("%1", 20),
    ).toEqual(await adapter.readLogicalText("%1", 20));
  });

  test("a beacon changing synthesises no event — watch is the wrapped adapter's, untouched", () => {
    const adapter = new StubAdapter();
    const decorated = decorate(adapter, [beacon([marker("%1")], "working", 1)]);
    expect(decorated.watch(watchOptions)).toBe(adapter.subscription);
    expect(decorated.capabilities.supports.pushPaneEvents).toBe(adapter.capabilities.supports.pushPaneEvents);
  });
});

describe("the capability lift is gated on the hooks, not on a beacon", () => {
  test("hooks installed lifts both capabilities and keeps every other answer", () => {
    const adapter = new StubAdapter();
    // No beacon at all: the declaration must not flicker with what happens to be on disk.
    const decorated = decorate(adapter, [], true);
    expect(decorated.capabilities.supports.agentDetection).toBe(true);
    expect(decorated.capabilities.supports.agentSessionRef).toBe(true);
    const inherited: readonly MuxCapability[] = ["paneGrid", "typeText", "sendKeys", "closePane", "createTab"];
    for (const capability of inherited) {
      expect(decorated.capabilities.supports[capability]).toBe(adapter.capabilities.supports[capability]);
    }
  });

  test("hooks absent leaves both absent and replaces the note with how to enable them", () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)], false);
    expect(decorated.capabilities.supports.agentDetection).toBe(false);
    expect(decorated.capabilities.supports.agentSessionRef).toBe(false);
    expect(decorated.capabilities.notes.agentDetection).toBe("install the hooks and a pane names itself");
    expect(decorated.capabilities.notes.agentSessionRef).toBe("install the hooks and the agent supplies its session");
    // A note that has nothing to do with this decorator travels through untouched.
    expect(decorated.capabilities.notes.closePane).toBe("untouched");
  });

  test("hooks absent leaves the panes exactly as the blind adapter reported them", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)], false);
    const pane = await paneOf(decorated, "%1");
    expect(pane.agent).toBe("shell");
    expect(pane.status).toBe("unknown");
    expect(pane.agentSession).toBeUndefined();
  });

  test("the declaration follows the seam, so installing the hooks needs no restart", () => {
    let installed = false;
    const decorated = withAgentBeacons(new StubAdapter(), fakeBeaconReader([]), {
      matcher: stubMatcher(),
      hooksInstalled: () => installed,
    });
    expect(decorated.capabilities.supports.agentDetection).toBe(false);
    installed = true;
    expect(decorated.capabilities.supports.agentDetection).toBe(true);
  });
});

describe("the beacon status map", () => {
  test("working reads as working", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)]);
    expect((await paneOf(decorated, "%1")).status).toBe("working");
  });

  test("idle reads as idle", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "idle", 1)]);
    expect((await paneOf(decorated, "%1")).status).toBe("idle");
  });

  test("waiting reads as blocked — the top of triage", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "waiting", 1)]);
    expect((await paneOf(decorated, "%1")).status).toBe("blocked");
  });

  test("a live beacon names the agent and carries its session", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)]);
    const pane = await paneOf(decorated, "%1");
    expect(pane.agent).toBe("claude");
    expect(pane.agentSession).toEqual({ kind: "id", value: "session-1" });
  });

  test("an expired beacon names NO agent — the pane the dead agent left behind is a shell again", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1, false)]);
    const pane = await paneOf(decorated, "%1");
    // Exactly what an ABSENT beacon produces, which is what an expired one is (.adr/0024). A pane
    // stuck at `claude` / `unknown` for the whole TTL is the ghost this rule exists to prevent.
    expect(pane.agent).toBe("shell");
    expect(pane.status).toBe("unknown");
  });

  test("an expired beacon still keys the pane's history, invisibly", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1, false)]);
    const pane = await paneOf(decorated, "%1");
    // The conversation is still on disk and the journal registry is keyed by harness name, so the
    // ref and the harness that wrote it survive — server-side only, and off the wire entirely.
    expect(pane.agentSession).toEqual({ kind: "id", value: "session-1" });
    expect(pane.sessionAgent).toBe("claude");
  });

  test("a live pane carries no session harness — the field is the dead agent's alone", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)]);
    expect((await paneOf(decorated, "%1")).sessionAgent).toBeUndefined();
  });

  test("an agent that dies drops out of the herd on the next sweep", async () => {
    const markers = [marker("%1")];
    const live = decorate(new StubAdapter(), [beacon(markers, "working", 1)]);
    const before = await paneOf(live, "%1");
    expect(before.agent).toBe("claude");
    expect(before.status).toBe("working");
    // The same beacon FILE, unchanged on disk — only the pid is gone. Nothing deletes it: the sweep
    // is a read, and the next snapshot is where the pane goes quiet.
    const after = await paneOf(decorate(new StubAdapter(), [beacon(markers, "working", 1, false)]), "%1");
    expect(after.agent).toBe("shell");
    expect(after.status).toBe("unknown");
    expect(after.agentSession).toEqual(before.agentSession);
  });

  test("an absent beacon reads unknown and never idle", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%2")], "idle", 2)]);
    const pane = await paneOf(decorated, "%1");
    expect(pane.status).toBe("unknown");
    expect(pane.status).not.toBe("idle");
    expect(pane.agent).toBe("shell");
    expect(pane.agentSession).toBeUndefined();
  });

  test("an empty beacon directory leaves every pane unknown", async () => {
    const decorated = decorate(new StubAdapter(), []);
    for (const pane of (await decorated.snapshot()).panes) {
      expect(pane.status).toBe("unknown");
      expect(pane.agent).toBe("shell");
    }
  });
});

describe("no pane takes an identity it cannot prove", () => {
  test("a beacon from another scope is not this pane's, however well the id matches", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1", "/somebody/else")], "working", 1)]);
    expect((await paneOf(decorated, "%1")).agent).toBe("shell");
  });

  test("a beacon from another namespace is not read at all", async () => {
    const foreign: BeaconMarker = { namespace: "another-mux", scope: SCOPE, pane: "%1" };
    const decorated = decorate(new StubAdapter(), [beacon([foreign], "working", 1)]);
    expect((await paneOf(decorated, "%1")).agent).toBe("shell");
  });

  test("a nested emitter's own namespace still joins on the entry that is ours", async () => {
    const markers: BeaconMarker[] = [{ namespace: "another-mux", scope: "outer", pane: "0" }, marker("%1")];
    const decorated = decorate(new StubAdapter(), [beacon(markers, "working", 1)]);
    expect((await paneOf(decorated, "%1")).agent).toBe("claude");
  });

  test("two beacons claiming one pane are two answers to a one-answer question — neither is taken", async () => {
    const decorated = decorate(new StubAdapter(), [
      beacon([marker("%1")], "working", 1),
      beacon([marker("%1"), marker("%2")], "idle", 2),
    ]);
    expect((await paneOf(decorated, "%1")).agent).toBe("shell");
  });

  test("an unresolvable scope joins nothing rather than joining on the pane id alone", async () => {
    const decorated = decorate(new StubAdapter(), [beacon([marker("%1")], "working", 1)], true, stubMatcher(null));
    expect((await paneOf(decorated, "%1")).agent).toBe("shell");
  });

  test("a harness name that no registry could match is not put on a pane", async () => {
    const shouty = beacon([marker("%1")], "working", 1);
    const decorated = decorate(new StubAdapter(), [
      { ...shouty, record: { ...shouty.record, harness: "a name with spaces and a ../ in it" } },
    ]);
    expect((await paneOf(decorated, "%1")).agent).toBe("shell");
  });
});


test("composed production beacons and hints forward exact original worktree predicates without early invocation", async () => {
  const adapter = new StubAdapter();
  let beforeCalls = 0; let finalCalls = 0; let targetCurrent = true; let ownerCurrent = true;
  const finalAdmission = (identity: string) => { finalCalls++; return identity === "original-runtime" && ownerCurrent; };
  const beforeDispatch = async () => { beforeCalls++; return targetCurrent; };
  let seenFinal: typeof finalAdmission | undefined; let seenBefore: typeof beforeDispatch | undefined;
  adapter.createWorktree = async (request, originalFinal?: typeof finalAdmission, originalBefore?: typeof beforeDispatch) => {
    seenFinal = originalFinal; seenBefore = originalBefore;
    expect(request.branch).toBe("original-branch");
    if (!originalBefore || !await originalBefore() || !originalFinal || !originalFinal("original-runtime")) return { ok: false, reason: "refused", detail: "retired" };
    return muxOk({ paneId: "%original", spaceId: "original", spaceLabel: "original", tabId: "original-tab", cwd: "/original" });
  };
  const wrapped = withAgentHints(decorate(adapter, [], false), { hooksInstalled: () => false });
  expect(beforeCalls).toBe(0); expect(finalCalls).toBe(0);
  expect((await wrapped.createWorktree({ repoRoot: "/original", branch: "original-branch" }, finalAdmission, beforeDispatch)).ok).toBe(true);
  expect(seenFinal).toBe(finalAdmission); expect(seenBefore).toBe(beforeDispatch);
  expect(beforeCalls).toBe(1); expect(finalCalls).toBe(1);
  ownerCurrent = false;
  expect((await wrapped.createWorktree({ repoRoot: "/original", branch: "original-branch" }, finalAdmission, beforeDispatch)).ok).toBe(false);
  targetCurrent = false;
  const priorFinal = finalCalls;
  expect((await wrapped.createWorktree({ repoRoot: "/original", branch: "original-branch" }, finalAdmission, beforeDispatch)).ok).toBe(false);
  expect(finalCalls).toBe(priorFinal); // The final target wait precedes authority dispatch.
});
