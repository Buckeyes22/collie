// HERDR'S CONFORMANCE FIXTURE — what lets the reference adapter be proved with no Herdr on the box.
//
// NOT a production module and not imported by one. `bridge/index.ts` builds `HerdrMux` over a real
// `HerdrClient`; this file builds the same adapter over a fake of the same shape (`HerdrRpc`), so the
// conformance engine (../conformance.ts) drives the WHOLE translation layer — the wire joins, the key
// spelling, the event split, the refusal mapping — without a socket.
//
// WHY A HAND-WRITTEN FAKE AND NOT `bridge/crew/fake-herdr.ts`. That one is a real unix-socket daemon,
// because its subject is the crew TRANSPORT: two Collie processes and a live connection between them.
// Conformance's subject is the adapter, and the five things it has to simulate — a reconnect, a
// multiplexer restart, an out-of-band rename, changed pane content, a pane dying — are state
// transitions, not bytes. Doing them in memory keeps the pure layer deterministic and keeps it
// runnable where a unix socket is not.
//
// WHAT IT MODELS, AND WHY EACH DETAIL IS THERE. Every answer below is Herdr's documented behaviour
// (HERDR_API.md), because a fake that is kinder than the real server proves nothing:
//   • an unknown id answers `pane_not_found` / `tab_not_found` / `workspace_not_found` — the codes the
//     adapter turns into the contract's `gone`;
//   • Herdr 0.9.3 clamps `lines` at 1000 and sets `truncated` when its line limiter omits rows;
//     `visible` vs `recent` return different depths. `readableLines` remains useful for the actual
//     scroll ring and must not be treated as evidence about agent-owned alt-screen history;
//   • `format:"ansi"` carries SGR and `format:"text"` carries none, so the contract's `styling`
//     request is a real branch rather than a field nobody reads;
//   • ids are the session's, so they survive a restart — that is the Herdr promise identity rule 2
//     rests on, and the fake would be lying if it minted fresh ones.

import type { AgentStatus } from "../../types.ts";
import type { JsonValue } from "../../json.ts";
import type { MuxConformanceFixture, MuxConformanceWorld, MuxWrite } from "../conformance.ts";
import { HerdrMux } from "./adapter.ts";
import type {
  CreatedShell,
  EventStream,
  HerdrRpc,
  HerdrSplitPaneParams,
  PaneRead,
  SubscribeOptions,
  WireAgentInfo,
  WirePane,
  WirePaneProcessInfo,
  WireSnapshot,
  WireTab,
  WireWorkspace,
  WireWorktree,
  WireWorktreeListing,
} from "./client.ts";

const IDLE: AgentStatus = "idle";

// SGR only — a colour on and a reset off. `pane.read format:"ansi"` carries exactly this much and no
// cursor sequence, which is the whole reason Collie can render the grid without an emulator.
const GREEN = "\u001b[32m";
const RESET = "\u001b[0m";

/** One live subscriber of the fake's event stream. */
interface Subscriber {
  readonly opts: SubscribeOptions;
  down: boolean;
}

/** A pane's rendered text, split the way Herdr's two read sources split it. */
interface FakeScreen {
  /** Lines that have scrolled off the viewport — what only a `recent` read reaches. */
  history: string[];
  /** Lines on screen now — what a `visible` read returns. */
  viewport: string[];
}

/** The error a Herdr server answers with for an id it does not know. `client.ts` folds it into this. */
function notFound(method: string, code: string, id: string): Error {
  return new Error(`herdr ${method}: ${code}: ${id} not found`);
}

/**
 * A Herdr control socket, in memory, behaving as the documented one does.
 *
 * Implements {@link HerdrRpc} — the narrow shape `HerdrMux` depends on — so the adapter under test is
 * the real one, unmodified.
 */
export class FakeHerdr implements HerdrRpc {
  layoutVersion = "0.9.3";
  layoutProtocol = 22;
  runtimeIdentity = "fake-herdr-runtime-1";
  splitAfterEffectFailure = false;
  lastSplitParameters: HerdrSplitPaneParams | null = null;
  private workspaces: WireWorkspace[] = [];
  private tabs: WireTab[] = [];
  private panes: WirePane[] = [];
  private readonly zoomedTabs = new Set<string>();
  private readonly screens = new Map<string, FakeScreen>();
  private readonly subscribers = new Set<Subscriber>();
  private readonly recorded: MuxWrite[] = [];
  /** Worktrees per repo root — the bookkeeping herdr keeps and a plain `git worktree add` does not. */
  private readonly worktreesByRepo = new Map<string, WireWorktree[]>();
  /** Never decreases, and never reused — so a fresh pane can never land on a dead pane's id. */
  private minted = 0;
  /** False while the adapter's connection is down: every RPC rejects, as a closed socket does. */
  private connected = true;
  readonly processInfoTargets: string[] = [];
  readonly agentInfoRows: WireAgentInfo[] = [];

  constructor() {
    this.seed();
  }

  // ── What the fixture drives ────────────────────────────────────────────────

  writes(): readonly MuxWrite[] {
    return this.recorded;
  }

  /** Deterministic close-group fixture: a root checkout and one linked checkout remain on disk. */
  async addWorktreeGroup(sourceWorkspaceId: string, repoRoot: string): Promise<string> {
    this.workspace("fixture.worktree_group", sourceWorkspaceId);
    const linked = await this.createWorkspace({ cwd: `${repoRoot}/.worktrees/b13-fixture`, label: "b13-fixture" });
    const existing = this.worktreesByRepo.get(repoRoot) ?? [
      { path: repoRoot, is_linked_worktree: false, is_prunable: false, is_bare: false, is_detached: false, open_workspace_id: sourceWorkspaceId },
    ];
    this.worktreesByRepo.set(repoRoot, [...existing, {
      path: `${repoRoot}/.worktrees/b13-fixture-${linked.workspaceId}`,
      branch: `b13-fixture-${linked.workspaceId}`,
      is_linked_worktree: true,
      is_prunable: false,
      is_bare: false,
      is_detached: false,
      open_workspace_id: linked.workspaceId,
    }]);
    return linked.workspaceId;
  }

  /**
   * The connection drops and comes back.
   *
   * Invisible to a caller by construction, and that is the Herdr truth rather than a shortcut: RPC is
   * one-shot, so every call already opens its own connection (HERDR_API.md § Transport). What the
   * conformance check is really asking is whether the adapter mints ids per-connection — it must not,
   * and this proves it does not.
   */
  async reconnect(): Promise<void> {
    this.connected = false;
    await Promise.resolve();
    this.connected = true;
  }

  /**
   * The multiplexer process restarts with its session intact.
   *
   * Every record is REBUILT as a fresh object carrying the same ids and the same values, which is
   * what makes the identity check meaningful: an adapter caching object identity, or deriving an id
   * from anything ephemeral, fails here and nowhere else.
   */
  async restartMux(): Promise<void> {
    this.workspaces = this.workspaces.map((workspace) => ({ ...workspace }));
    this.tabs = this.tabs.map((tab) => ({ ...tab }));
    this.panes = this.panes.map((pane) => ({ ...pane }));
    for (const subscriber of this.subscribers) this.fireDown(subscriber, "connection closed");
    await Promise.resolve();
  }

  /** The operator renames a pane in Herdr's own TUI. Collie learns it on the next poll. */
  async renameOutOfBand(paneId: string, label: string): Promise<void> {
    const pane = this.panes.find((candidate) => candidate.pane_id === paneId);
    if (pane !== undefined) pane.label = label;
    await Promise.resolve();
  }

  /**
   * The PROGRAM in a pane prints an OSC title. Herdr keeps it in its OWN field, so this fixture is
   * the one that shows what a two-slot multiplexer gets for free: the operator's `label` is not
   * touched, and the adapter needs no memory to keep the two apart.
   */
  async setProgramTitle(paneId: string, title: string): Promise<void> {
    const pane = this.panes.find((candidate) => candidate.pane_id === paneId);
    if (pane !== undefined) pane.terminal_title = title;
    await Promise.resolve();
  }

  /** The pane paints another line. What a keystroke landing would have done. */
  async changePane(paneId: string): Promise<void> {
    const pane = this.panes.find((candidate) => candidate.pane_id === paneId);
    const screen = this.screens.get(paneId);
    if (pane !== undefined && screen !== undefined) {
      screen.viewport.push(`● changed at ${String(pane.revision + 1)}`);
      pane.revision += 1;
    }
    await Promise.resolve();
  }

  /** Replace retained history for bounded pane-read fixtures. */
  setScrollback(paneId: string, rows: readonly string[]): void {
    const screen = this.screens.get(paneId);
    if (screen !== undefined) screen.history.splice(0, screen.history.length, ...rows);
  }

  /** The pane's process ends; Herdr drops it from `pane.list` and answers `pane_not_found` after. */
  async endPane(paneId: string): Promise<void> {
    this.panes = this.panes.filter((pane) => pane.pane_id !== paneId);
    this.screens.delete(paneId);
    await Promise.resolve();
  }

  /** The operator renames a tab in Herdr's own TUI, with the event stream saying nothing. */
  async pokeTopologyOutOfBand(): Promise<void> {
    const tab = this.tabs[0];
    if (tab !== undefined) tab.label = `out-of-band-${String(this.tabs.length)}`;
    await Promise.resolve();
  }

  /** Announce a herd-structure change on the event stream. */
  pokeTopology(): void {
    this.emit("pane_created", { pane_id: "" });
  }

  /** Announce one pane's change on the event stream. */
  pokePane(paneId: string): void {
    this.emit("pane_agent_status_changed", { pane_id: paneId });
  }

  /** Bring every stream down and forget them. Idempotent. */
  shutdown(): void {
    for (const subscriber of this.subscribers) this.fireDown(subscriber, "closed");
    this.subscribers.clear();
  }

  // ── The RPCs (HerdrRpc) ────────────────────────────────────────────────────

  async ping(): Promise<boolean> {
    return this.connected;
  }

  async sessionSnapshot(): Promise<WireSnapshot> {
    this.assertConnected("session.snapshot");
    return {
      version: "0.7.5",
      protocol: 16,
      workspaces: this.workspaces,
      tabs: this.tabs,
      panes: this.panes,
    };
  }

  async listWorkspaces(): Promise<WireWorkspace[]> {
    this.assertConnected("workspace.list");
    return this.workspaces;
  }

  async listPanes(): Promise<WirePane[]> {
    this.assertConnected("pane.list");
    return this.panes;
  }

  async getPane(paneId: string): Promise<WirePane> {
    this.assertConnected("pane.get");
    return { ...this.pane("pane.get", paneId) };
  }

  async getAgent(target: string): Promise<WireAgentInfo> {
    this.assertConnected("agent.get");
    const matching = this.agentInfoRows.filter((row) => row.pane_id === target);
    if (matching.length > 0) return { ...matching[0]! };
    const pane = this.pane("agent.get", target);
    return {
      pane_id: pane.pane_id,
      agent: pane.agent,
      agent_status: pane.agent_status,
      agent_session: pane.agent_session && pane.agent_session.source && pane.agent_session.agent &&
        (pane.agent_session.kind === "id" || pane.agent_session.kind === "path") && pane.agent_session.value
        ? {
            source: pane.agent_session.source,
            agent: pane.agent_session.agent,
            kind: pane.agent_session.kind,
            value: pane.agent_session.value,
          }
        : null,
      interactive_ready: true,
      launch_pending: false,
      state_change_seq: "0",
      completion_seq: null,
    };
  }

  async processInfo(paneId: string): Promise<WirePaneProcessInfo> {
    const pane = this.pane("pane.process_info", paneId);
    this.processInfoTargets.push(paneId);
    return {
      pane_id: paneId,
      shell_pid: 100,
      foreground_process_group_id: 100,
      foreground_processes: [{ pid: 100, name: "zsh", argv: ["zsh"], cwd: pane.foreground_cwd ?? pane.cwd }],
      tty: "/dev/pts/1",
    };
  }

  changeTerminalOccupant(paneId: string, terminalId: string): void {
    this.pane("pane.list", paneId).terminal_id = terminalId;
  }

  changePaneMetadata(paneId: string, fields: Partial<WirePane>): void {
    Object.assign(this.pane("pane.list", paneId), fields);
  }

  async listTabs(): Promise<WireTab[]> {
    this.assertConnected("tab.list");
    return this.tabs;
  }

  async readPane(paneId: string, source: string, lines: number, format = "text"): Promise<PaneRead> {
    const pane = this.pane("pane.read", paneId);
    const screen = this.screens.get(paneId) ?? { history: [], viewport: [] };
    const all = source === "visible" ? screen.viewport : [...screen.history, ...screen.viewport];
    // Herdr 0.9.3 clamps a pane read to 1000 rows and reports when the clamp omits rows.
    const limit = Math.min(lines, 1_000);
    const omitted = all.length > limit;
    const shown = all.slice(Math.max(0, all.length - limit));
    // SGR only, and only in `ansi` — colour and weight, never a cursor move (ADR 0008).
    const paint = (line: string) => (format === "ansi" ? `${GREEN}${line}${RESET}` : line);
    return {
      pane_id: paneId,
      text: shown.map(paint).join("\n"),
      truncated: omitted,
      revision: pane.revision,
    };
  }

  async sendPaneText(paneId: string, text: string): Promise<void> {
    this.pane("pane.send_text", paneId);
    this.recorded.push({ paneId, kind: "text", payload: [text] });
  }

  async sendPaneKeys(paneId: string, keys: string[]): Promise<void> {
    this.pane("pane.send_keys", paneId);
    this.recorded.push({ paneId, kind: "keys", payload: [...keys] });
  }

  async renamePane(paneId: string, label: string | null): Promise<void> {
    const pane = this.pane("pane.rename", paneId);
    if (label === null) delete pane.label;
    else pane.label = label;
  }

  async closePane(paneId: string): Promise<void> {
    this.pane("pane.close", paneId);
    await this.endPane(paneId);
  }

  /**
   * Focus one pane — and exactly one, across the whole herd.
   *
   * The real server moves the tab and the workspace with the pane (client.ts § focusPane), and the
   * fixture's tabs and workspaces carry their own `focused` flags, so both are moved here too. Anything
   * less would let an adapter pass conformance while leaving two panes claiming the operator's screen.
   */
  async focusPane(paneId: string): Promise<void> {
    const target = this.pane("pane.focus", paneId);
    for (const pane of this.panes) pane.focused = pane.pane_id === target.pane_id;
    for (const tab of this.tabs) tab.focused = tab.tab_id === target.tab_id;
    for (const workspace of this.workspaces) workspace.focused = workspace.workspace_id === target.workspace_id;
  }

  async handshake(): Promise<{ version: string; protocol: number }> { return { version: this.layoutVersion, protocol: this.layoutProtocol }; }
  layoutRuntimeIdentity(): string | null { return this.runtimeIdentity; }
  async layoutHandshakeSample(): Promise<{ handshake: { version: string; protocol: number }; identity: string } | null> {
    const first = this.runtimeIdentity;
    const handshake = await this.handshake();
    const second = this.runtimeIdentity;
    return first && first === second ? { handshake, identity: first } : null;
  }
  retireLayoutRuntime(): void { /* no cached identity in this stateless fixture */ }

  async readPaneLayout(paneId: string): Promise<JsonValue> {
    const selected = this.pane("pane.layout", paneId);
    return { type: "pane_layout", layout: this.layoutSnapshot(selected) };
  }

  private layoutSnapshot(selected: WirePane): JsonValue {
    const panes = this.panes.filter((pane) => pane.tab_id === selected.tab_id);
    return {
      workspace_id: selected.workspace_id, tab_id: selected.tab_id, zoomed: this.zoomedTabs.has(selected.tab_id),
      area: { x: 0, y: 0, width: 80, height: 24 }, focused_pane_id: panes.find((pane) => pane.focused)?.pane_id ?? selected.pane_id,
      panes: panes.map((pane, index) => ({ pane_id: pane.pane_id, focused: pane.focused, rect: { x: index * 40, y: 0, width: 40, height: 24 } })),
      splits: [],
    };
  }

  private paneInfoWire(pane: WirePane): JsonValue {
    return { pane_id: pane.pane_id, terminal_id: pane.terminal_id, workspace_id: pane.workspace_id, tab_id: pane.tab_id, focused: pane.focused, agent_status: pane.agent_status, revision: pane.revision };
  }

  async readPaneNeighbor(paneId: string, direction: "left" | "right" | "up" | "down"): Promise<JsonValue> {
    const selected = this.pane("pane.neighbor", paneId);
    const peers = this.panes.filter((pane) => pane.tab_id === selected.tab_id);
    const index = peers.findIndex((pane) => pane.pane_id === paneId);
    const next = direction === "left" ? peers[index - 1] : direction === "right" ? peers[index + 1] : undefined;
    return { type: "pane_neighbor", neighbor: { pane_id: paneId, direction, neighbor_pane_id: next?.pane_id ?? null, layout: this.layoutSnapshot(selected) } };
  }

  async readPaneEdges(paneId: string): Promise<JsonValue> {
    const selected = this.pane("pane.edges", paneId);
    const peers = this.panes.filter((pane) => pane.tab_id === selected.tab_id);
    const index = peers.findIndex((pane) => pane.pane_id === paneId);
    return { type: "pane_edges", edges: { pane_id: paneId, left: index === 0, right: index === peers.length - 1, up: true, down: true, layout: this.layoutSnapshot(selected) } };
  }

  async focusPaneDirection(paneId: string, direction: "left" | "right" | "up" | "down"): Promise<JsonValue> {
    const selected = this.pane("pane.focus_direction", paneId);
    const peers = this.panes.filter((pane) => pane.tab_id === selected.tab_id);
    const index = peers.findIndex((pane) => pane.pane_id === paneId);
    const target = direction === "left" ? peers[index - 1] : direction === "right" ? peers[index + 1] : undefined;
    if (target) await this.focusPane(target.pane_id);
    return { type: "pane_focus_direction", focus: { changed: target !== undefined, source_pane_id: paneId, focused_pane_id: target?.pane_id ?? null, layout: this.layoutSnapshot(selected) } };
  }

  async splitPane(params: HerdrSplitPaneParams): Promise<JsonValue> {
    this.lastSplitParameters = { ...params, env: { ...params.env } };
    const source = this.pane("pane.split", params.target_pane_id ?? "");
    const tab = this.tab("pane.split", source.tab_id);
    const created = this.newPane(tab, source.cwd);
    if (this.splitAfterEffectFailure) throw new Error("transport disconnected after split dispatch");
    return { type: "pane_info", pane: {
      pane_id: created.pane_id, terminal_id: created.terminal_id, workspace_id: created.workspace_id, tab_id: created.tab_id,
      focused: created.focused, agent_status: created.agent_status, revision: created.revision,
    } };
  }

  async resizePane(paneId: string, _direction: "left" | "right" | "up" | "down", _amount: number | null): Promise<JsonValue> {
    const pane = this.pane("pane.resize", paneId);
    return { type: "pane_resize", resize: { changed: true, pane_id: paneId, focused_pane_id: pane.pane_id, layout: this.layoutSnapshot(pane) } };
  }

  async zoomPane(paneId: string, mode: "toggle" | "on" | "off"): Promise<JsonValue> {
    const pane = this.pane("pane.zoom", paneId);
    const before = this.zoomedTabs.has(pane.tab_id);
    const zoomed = mode === "toggle" ? !before : mode === "on";
    if (zoomed) this.zoomedTabs.add(pane.tab_id);
    else this.zoomedTabs.delete(pane.tab_id);
    const changed = before !== zoomed;
    const focused = this.panes.find((candidate) => candidate.tab_id === pane.tab_id && candidate.focused)?.pane_id ?? pane.pane_id;
    return { type: "pane_zoom", zoom: { changed, zoomed, zoom_changed: changed, focus_changed: false, pane_id: paneId, focused_pane_id: focused, layout: this.layoutSnapshot(pane) } };
  }

  async swapPanes(params: { pane_id: string; direction: "left" | "right" | "up" | "down" | null; source_pane_id: string | null; target_pane_id: string | null }): Promise<JsonValue> {
    const source = this.pane("pane.swap", params.source_pane_id ?? params.pane_id);
    const targetId = params.target_pane_id;
    const target = targetId === null ? undefined : this.panes.find((pane) => pane.pane_id === targetId);
    const changed = target !== undefined && target.tab_id === source.tab_id;
    return { type: "pane_swap", swap: { changed, source_pane_id: source.pane_id, target_pane_id: target?.pane_id ?? null, focused_pane_id: this.panes.find((pane) => pane.focused)?.pane_id ?? source.pane_id, layout: this.layoutSnapshot(source) } };
  }

  async movePane(params: { pane_id: string; destination: import("./client.ts").HerdrMoveDestination; focus: false }): Promise<JsonValue> {
    const source = this.pane("pane.move", params.pane_id);
    const previousPaneId = source.pane_id;
    const previousTabId = source.tab_id;
    const previousWorkspaceId = source.workspace_id;
    let destinationTab: WireTab;
    if (params.destination.type === "tab") {
      destinationTab = this.tab("pane.move", params.destination.tab_id);
      if (destinationTab.tab_id === source.tab_id) {
        return { type: "pane_move", move_result: { changed: false, reason: "same_tab", previous_pane_id: previousPaneId, previous_tab_id: previousTabId, previous_workspace_id: previousWorkspaceId, pane: this.paneInfoWire(source), target_layout: this.layoutSnapshot(source), focused_pane_id: this.panes.find((pane) => pane.focused)?.pane_id ?? source.pane_id } };
      }
    } else if (params.destination.type === "new_tab") {
      const workspace = this.workspace("pane.move", params.destination.workspace_id);
      destinationTab = this.newTab(workspace, params.destination.label ?? String(workspace.tab_count + 1));
    } else {
      const workspace = this.newWorkspace(params.destination.label ?? `space-${String(this.workspaces.length + 1)}`);
      destinationTab = this.newTab(workspace, params.destination.tab_label ?? "1");
    }
    this.minted += 1;
    const nextPaneId = `${destinationTab.workspace_id}:p${String(this.minted)}`;
    // pane.move changes the pane identity while carrying the existing terminal process.
    const moved: WirePane = { ...source, pane_id: nextPaneId, workspace_id: destinationTab.workspace_id, tab_id: destinationTab.tab_id, focused: false };
    this.panes = this.panes.filter((pane) => pane.pane_id !== source.pane_id);
    const oldTab = this.tabs.find((tab) => tab.tab_id === source.tab_id);
    if (oldTab) oldTab.pane_count = Math.max(0, oldTab.pane_count - 1);
    const oldWorkspace = this.workspaces.find((workspace) => workspace.workspace_id === source.workspace_id);
    if (oldWorkspace) oldWorkspace.pane_count = Math.max(0, oldWorkspace.pane_count - 1);
    destinationTab.pane_count += 1;
    const newWorkspace = this.workspaces.find((workspace) => workspace.workspace_id === destinationTab.workspace_id);
    if (newWorkspace) newWorkspace.pane_count += 1;
    this.panes.push(moved);
    const screen = this.screens.get(source.pane_id);
    if (screen) { this.screens.delete(source.pane_id); this.screens.set(nextPaneId, screen); }
    const focusedPaneId = this.panes.find((pane) => pane.focused)?.pane_id ?? moved.pane_id;
    return { type: "pane_move", move_result: { changed: true, previous_pane_id: previousPaneId, previous_tab_id: previousTabId, previous_workspace_id: previousWorkspaceId, pane: this.paneInfoWire(moved), target_layout: this.layoutSnapshot(moved), focused_pane_id: focusedPaneId } };
  }

  async createTab(workspaceId: string, opts: { label?: string; cwd?: string } = {}): Promise<CreatedShell> {
    const workspace = this.workspace("tab.create", workspaceId);
    const tab = this.newTab(workspace, opts.label ?? String(workspace.tab_count + 1));
    const pane = this.newPane(tab, opts.cwd ?? "/tmp");
    return { paneId: pane.pane_id, workspaceId: workspace.workspace_id, tabId: tab.tab_id, cwd: pane.cwd };
  }

  async renameTab(tabId: string, label: string): Promise<void> {
    this.tab("tab.rename", tabId).label = label;
  }

  async closeTab(tabId: string): Promise<void> {
    this.tab("tab.close", tabId);
    // A tab close is a bulk pane close — live-verified against the real server.
    for (const pane of this.panes.filter((candidate) => candidate.tab_id === tabId)) {
      this.screens.delete(pane.pane_id);
    }
    this.panes = this.panes.filter((pane) => pane.tab_id !== tabId);
    this.tabs = this.tabs.filter((tab) => tab.tab_id !== tabId);
  }

  async focusWorkspace(workspaceId: string): Promise<void> {
    const workspace = this.workspace("workspace.focus", workspaceId);
    const tab = this.tabs.find((candidate) => candidate.tab_id === workspace.active_tab_id);
    if (!tab) throw new Error("herdr workspace.focus: active tab missing");
    this.focusTabState(tab);
  }
  async renameWorkspace(workspaceId: string, label: string): Promise<void> { this.workspace("workspace.rename", workspaceId).label = label; }
  async moveWorkspace(workspaceId: string, insertIndex: number): Promise<void> {
    this.workspace("workspace.move", workspaceId);
    if (!Number.isSafeInteger(insertIndex) || insertIndex < 0 || insertIndex > this.workspaces.length) throw new Error("herdr workspace.move: invalid_insert_index");
    const [selected] = this.workspaces.splice(this.workspaces.findIndex((row) => row.workspace_id === workspaceId), 1);
    this.workspaces.splice(insertIndex, 0, selected!);
    this.renumberWorkspaces();
  }
  async moveWorkspaceBlock(workspaceIds: readonly string[], beforeWorkspaceId: string | null): Promise<void> {
    this.assertConnected("workspace.move_block");
    if (workspaceIds.length === 0 || new Set(workspaceIds).size !== workspaceIds.length || (beforeWorkspaceId !== null && workspaceIds.includes(beforeWorkspaceId)) || !workspaceIds.every((id) => this.workspaces.some((row) => row.workspace_id === id)) || (beforeWorkspaceId !== null && !this.workspaces.some((row) => row.workspace_id === beforeWorkspaceId))) throw new Error("herdr workspace.move_block: invalid_workspace_order");
    const selected = workspaceIds.map((id) => this.workspaces.find((row) => row.workspace_id === id)!);
    const selectedIds = new Set(workspaceIds);
    const remainder = this.workspaces.filter((row) => !selectedIds.has(row.workspace_id));
    const index = beforeWorkspaceId === null ? remainder.length : remainder.findIndex((row) => row.workspace_id === beforeWorkspaceId);
    this.workspaces = [...remainder.slice(0, index), ...selected, ...remainder.slice(index)];
    this.renumberWorkspaces();
  }
  async closeWorkspace(workspaceId: string, closeGroup: boolean): Promise<void> {
    this.workspace("workspace.close", workspaceId);
    const group = this.worktreeGroup(workspaceId);
    if (!closeGroup && group.length > 1) throw new Error("herdr workspace.close: workspace_group_close_required");
    const closing = new Set(closeGroup ? group : [workspaceId]);
    for (const pane of this.panes.filter((candidate) => closing.has(candidate.workspace_id))) this.screens.delete(pane.pane_id);
    const tabIds = new Set(this.tabs.filter((tab) => closing.has(tab.workspace_id)).map((tab) => tab.tab_id));
    this.panes = this.panes.filter((pane) => !closing.has(pane.workspace_id));
    this.tabs = this.tabs.filter((tab) => !tabIds.has(tab.tab_id));
    this.workspaces = this.workspaces.filter((workspace) => !closing.has(workspace.workspace_id));
    for (const worktrees of this.worktreesByRepo.values()) for (const worktree of worktrees) if (worktree.open_workspace_id && closing.has(worktree.open_workspace_id)) delete worktree.open_workspace_id;
    this.renumberWorkspaces();
  }
  async focusTab(tabId: string): Promise<void> { this.focusTabState(this.tab("tab.focus", tabId)); }
  async moveTab(tabId: string, insertIndex: number): Promise<void> {
    const tab = this.tab("tab.move", tabId);
    const siblings = this.tabs.filter((row) => row.workspace_id === tab.workspace_id);
    if (!Number.isSafeInteger(insertIndex) || insertIndex < 0 || insertIndex > siblings.length) throw new Error("herdr tab.move: invalid_insert_index");
    const current = siblings.findIndex((row) => row.tab_id === tabId);
    siblings.splice(current, 1);
    siblings.splice(insertIndex, 0, tab);
    let next = 0;
    this.tabs = this.tabs.map((row) => row.workspace_id === tab.workspace_id ? siblings[next++]! : row);
    // Herdr tab numbers are stable metadata; array order is the authoritative display order.
    const workspace = this.workspace("tab.move", tab.workspace_id);
    if (workspace.active_tab_id === tabId) workspace.active_tab_id = tabId;
  }

  private focusTabState(tab: WireTab): void {
    const workspace = this.workspace("tab.focus", tab.workspace_id);
    workspace.active_tab_id = tab.tab_id;
    for (const pane of this.panes) pane.focused = pane.tab_id === tab.tab_id && pane.focused;
    const current = this.panes.find((pane) => pane.tab_id === tab.tab_id);
    if (current) for (const pane of this.panes) pane.focused = pane.pane_id === current.pane_id;
    for (const row of this.tabs) row.focused = row.tab_id === tab.tab_id;
    for (const row of this.workspaces) row.focused = row.workspace_id === workspace.workspace_id;
  }
  private renumberWorkspaces(): void { this.workspaces.forEach((workspace, index) => { workspace.number = index + 1; }); }
  private worktreeGroup(workspaceId: string): string[] {
    for (const rows of this.worktreesByRepo.values()) {
      const members = rows.flatMap((row) => row.open_workspace_id ? [row.open_workspace_id] : []);
      if (members.includes(workspaceId)) return [...new Set(members)];
    }
    return [workspaceId];
  }

  async createWorkspace(opts: { cwd: string; label?: string }): Promise<CreatedShell> {
    const workspace = this.newWorkspace(opts.label ?? `space ${String(this.workspaces.length + 1)}`);
    const tab = this.newTab(workspace, "1");
    const pane = this.newPane(tab, opts.cwd);
    return {
      paneId: pane.pane_id,
      workspaceId: workspace.workspace_id,
      workspaceLabel: workspace.label,
      tabId: tab.tab_id,
      cwd: pane.cwd,
    };
  }

  // ── Worktrees ──────────────────────────────────────────────────────────────
  //
  // Modelled, not stubbed, because conformance EXERCISES every declared capability: the fake keeps a
  // per-repo list, so create → list → open → remove tells the same story the real socket told when
  // it was probed (2026-08-28, herdr 0.8.2). Two behaviours are copied deliberately because the
  // adapter's contract leans on them: opening what is already open answers `already_open` instead of
  // refusing, and removal is addressed by WORKSPACE, so a checkout nothing shows cannot be removed.

  async listWorktrees(cwd: string): Promise<WireWorktree[]> {
    this.assertConnected("worktree.list");
    const repo = this.worktreesByRepo.get(cwd) ?? [];
    if (repo.some((worktree) => !worktree.is_linked_worktree)) return [...repo];
    return [
      {
        path: cwd,
        branch: "main",
        is_linked_worktree: false,
        is_prunable: false,
        is_bare: false,
        is_detached: false,
      },
      ...repo,
    ];
  }

  /**
   * `worktree.list` by workspace, as herdr 0.9.3 answers it: the repo whose bookkeeping names the
   * workspace, `not_git_worktree` for one outside every modelled repo (the seeded spaces are plain
   * folders), and `workspace_not_found` for an id the session does not have.
   */
  async workspaceWorktrees(workspaceId: string): Promise<WireWorktreeListing> {
    this.assertConnected("worktree.list");
    if (!this.workspaces.some((workspace) => workspace.workspace_id === workspaceId)) {
      throw notFound("worktree.list", "workspace_not_found", workspaceId);
    }
    for (const [repoRoot, repo] of this.worktreesByRepo) {
      if (!repo.some((worktree) => worktree.open_workspace_id === workspaceId)) continue;
      return {
        source: { repo_root: repoRoot, source_checkout_path: repoRoot },
        worktrees: await this.listWorktrees(repoRoot),
      };
    }
    throw new Error("herdr worktree.list: not_git_worktree: Herdr worktree actions require a path inside a Git work tree");
  }

  async createWorktree(opts: { cwd: string; branch: string }): Promise<CreatedShell> {
    this.assertConnected("worktree.create");
    const repo = this.worktreesByRepo.get(opts.cwd) ?? [];
    const path = `${opts.cwd}/.worktrees/${opts.branch.replace(/\//g, "-")}`;
    if (repo.some((w) => w.path === path)) {
      throw new Error(`herdr worktree.create: worktree_create_failed: '${path}' already exists`);
    }
    const shell = await this.createWorkspace({ cwd: path, label: opts.branch });
    repo.push({
      path,
      branch: opts.branch,
      is_linked_worktree: true,
      is_prunable: false,
      is_bare: false,
      is_detached: false,
      open_workspace_id: shell.workspaceId,
    });
    this.worktreesByRepo.set(opts.cwd, repo);
    return shell;
  }

  async openWorktree(opts: {
    cwd: string;
    path: string;
  }): Promise<{ shell: CreatedShell; alreadyOpen: boolean }> {
    this.assertConnected("worktree.open");
    const repo = this.worktreesByRepo.get(opts.cwd) ?? [];
    const found = repo.find((w) => w.path === opts.path);
    if (found === undefined) {
      throw new Error(`herdr worktree.open: worktree_not_found: ${opts.path}`);
    }
    if (found.open_workspace_id != null) {
      const pane = this.panes.find((candidate) => candidate.workspace_id === found.open_workspace_id);
      const workspace = this.workspaces.find((w) => w.workspace_id === found.open_workspace_id);
      if (pane !== undefined && workspace !== undefined) {
        return {
          shell: {
            paneId: pane.pane_id,
            workspaceId: workspace.workspace_id,
            workspaceLabel: workspace.label,
            tabId: pane.tab_id,
            cwd: pane.cwd,
          },
          alreadyOpen: true,
        };
      }
    }
    const shell = await this.createWorkspace({ cwd: found.path, label: found.branch ?? "worktree" });
    found.open_workspace_id = shell.workspaceId;
    return { shell, alreadyOpen: false };
  }


  subscribeEvents(opts: SubscribeOptions): EventStream {
    const subscriber: Subscriber = { opts, down: false };
    this.subscribers.add(subscriber);
    // The real client acks on a round trip, so `up` is never synchronous with the call.
    queueMicrotask(() => {
      if (!subscriber.down) opts.onUp();
    });
    return { close: () => this.fireDown(subscriber, "closed") };
  }

  // ── Internals ──────────────────────────────────────────────────────────────

  private assertConnected(method: string): void {
    if (!this.connected) throw new Error(`herdr ${method}: connection closed before reply`);
  }

  private pane(method: string, paneId: string): WirePane {
    this.assertConnected(method);
    const pane = this.panes.find((candidate) => candidate.pane_id === paneId);
    if (pane === undefined) throw notFound(method, "pane_not_found", paneId);
    return pane;
  }

  private tab(method: string, tabId: string): WireTab {
    this.assertConnected(method);
    const tab = this.tabs.find((candidate) => candidate.tab_id === tabId);
    if (tab === undefined) throw notFound(method, "tab_not_found", tabId);
    return tab;
  }

  private workspace(method: string, workspaceId: string): WireWorkspace {
    this.assertConnected(method);
    const workspace = this.workspaces.find((candidate) => candidate.workspace_id === workspaceId);
    if (workspace === undefined) throw notFound(method, "workspace_not_found", workspaceId);
    return workspace;
  }

  private emit(event: string, data: { pane_id: string }): void {
    for (const subscriber of this.subscribers) {
      if (!subscriber.down) subscriber.opts.onEvent(event, data);
    }
  }

  private fireDown(subscriber: Subscriber, reason: string): void {
    if (subscriber.down) return;
    subscriber.down = true;
    subscriber.opts.onDown(reason);
  }

  private newWorkspace(label: string): WireWorkspace {
    this.minted += 1;
    const workspace: WireWorkspace = {
      workspace_id: `w${String(this.minted)}`,
      number: this.workspaces.length + 1,
      label,
      focused: this.workspaces.length === 0,
      pane_count: 0,
      tab_count: 0,
      active_tab_id: "",
      agent_status: IDLE,
    };
    this.workspaces.push(workspace);
    return workspace;
  }

  private newTab(workspace: WireWorkspace, label: string): WireTab {
    this.minted += 1;
    const tab: WireTab = {
      tab_id: `t${String(this.minted)}`,
      workspace_id: workspace.workspace_id,
      number: workspace.tab_count + 1,
      label,
      focused: workspace.tab_count === 0,
      pane_count: 0,
      agent_status: IDLE,
    };
    workspace.tab_count += 1;
    if (workspace.active_tab_id === "") workspace.active_tab_id = tab.tab_id;
    this.tabs.push(tab);
    return tab;
  }

  private newPane(tab: WireTab, cwd: string, agent?: string): WirePane {
    this.minted += 1;
    const paneId = `${tab.workspace_id}:p${String(this.minted)}`;
    const pane: WirePane = {
      pane_id: paneId,
      terminal_id: `term${String(this.minted)}`,
      workspace_id: tab.workspace_id,
      tab_id: tab.tab_id,
      focused: false,
      cwd,
      agent_status: agent === undefined ? "unknown" : IDLE,
      revision: 1,
      scroll: { offset_from_bottom: 0, max_offset_from_bottom: 40, viewport_rows: 24 },
    };
    if (agent !== undefined) pane.agent = agent;
    this.panes.push(pane);
    tab.pane_count += 1;
    const workspace = this.workspaces.find((candidate) => candidate.workspace_id === tab.workspace_id);
    if (workspace !== undefined) workspace.pane_count += 1;
    this.screens.set(paneId, {
      history: Array.from({ length: 30 }, (_, i) => `scrollback line ${String(i)} of ${paneId}`),
      viewport: [`$ shell in ${cwd}`, `pane ${paneId} on screen`],
    });
    return pane;
  }

  /**
   * The world every conformance world starts in: three live panes across two spaces and two tabs,
   * one of them an agent pane that has named its session.
   *
   * The engine's world contract asks for exactly this, and the reason is that half the suite would
   * otherwise pass vacuously — a single bare shell proves nothing about `agentDetection`, about a
   * space join, or about an id staying unique across two spaces.
   */
  private seed(): void {
    const first = this.newWorkspace("collie");
    const firstTab = this.newTab(first, "agents");
    const agentPane = this.newPane(firstTab, "/home/dev/collie", "claude");
    agentPane.focused = true;
    agentPane.agent_session = { source: "claude", agent: "claude", kind: "id", value: "0a1b2c3d-session" };
    agentPane.terminal_title = "✳ Writing the conformance suite";
    this.newPane(firstTab, "/home/dev/collie");

    const second = this.newWorkspace("scratch");
    const secondTab = this.newTab(second, "shell");
    this.newPane(secondTab, "/tmp");
  }
}

/**
 * Herdr's entry in the fixture registry (../fixtures.ts).
 *
 * The world is the real {@link HerdrMux} over a {@link FakeHerdr} — the same adapter
 * `bridge/index.ts` builds, with only the socket replaced.
 */
export const herdrConformanceFixture: MuxConformanceFixture = {
  mux: "herdr",
  create(): Promise<MuxConformanceWorld> {
    const fake = new FakeHerdr();
    // Two sessions, because the `listSessions` leg of conformance has to see a real answer: the
    // primary's own socket plus one named session, exactly the shape `./sessions.ts` reads off disk.
    const adapter = new HerdrMux(fake, () => [
      { name: "default", endpoint: "/tmp/collie-conformance/herdr.sock" },
      { name: "conformance", endpoint: "/tmp/collie-conformance/sessions/conformance/herdr.sock" },
    ]);
    return Promise.resolve({
      adapter,
      writes: () => fake.writes(),
      reconnect: () => fake.reconnect(),
      restartMux: () => fake.restartMux(),
      renameOutOfBand: (paneId, label) => fake.renameOutOfBand(paneId, label),
      setProgramTitle: (paneId, title) => fake.setProgramTitle(paneId, title),
      // The operator moves focus in their own TUI. Herdr's own `pane.focus` does exactly this, so the
      // perturbation is the same state change arriving without Collie having asked for it.
      focusOutOfBand: (paneId) => fake.focusPane(paneId),
      changePane: (paneId) => fake.changePane(paneId),
      endPane: (paneId) => fake.endPane(paneId),
      pokeTopologyOutOfBand: () => fake.pokeTopologyOutOfBand(),
      pokeTopology: () => fake.pokeTopology(),
      pokePane: (paneId) => fake.pokePane(paneId),
      close: () => {
        fake.shutdown();
        return Promise.resolve();
      },
    });
  },
};
