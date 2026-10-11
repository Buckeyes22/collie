import type { JsonObject, JsonValue } from "../../json.ts";
import { HOST } from "../../host.ts";
import { statSync } from "node:fs";
import { createHash } from "node:crypto";
import { readWindowsPipeWitness } from "./windows-pipe-witness.ts";
import type { AgentStatus } from "../../types.ts";
import { dialHerdr, type DialMode, type SockHandle } from "../../dial.ts";
import { decodeReplyLine, decodeStreamLine, type EventData } from "../../wire.ts";
import { redactText } from "../../redact.ts";
import { randomUUID } from "node:crypto";

// ─────────────────────────────────────────────────────────────────────────────
// The Herdr socket TRANSPORT. THIS IS THE ONLY FILE that knows Herdr's method
// names and wire shapes; `adapter.ts` next to it is the only file that knows
// these typed methods, and everything above that talks the mux port
// (bridge/mux/types.ts). So a Herdr API change is still a one-file fix.
// Protocol facts are documented in HERDR_API.md.
//
// Key fact: RPC is ONE-SHOT — the server closes the connection after a single
// response. So every request opens a fresh connection, reads one line, closes.
// ─────────────────────────────────────────────────────────────────────────────

/** Identity reported by the vendor ping; this alone grants no feature authority. */
export interface HerdrHandshake { readonly version: string; readonly protocol: number }
export type HerdrMoveDestination =
  | { readonly type: "tab"; readonly tab_id: string; readonly split: "right" | "down"; readonly target_pane_id: string; readonly ratio: number | null }
  | { readonly type: "new_tab"; readonly workspace_id: string; readonly label: string | null }
  | { readonly type: "new_workspace"; readonly label: string | null; readonly tab_label: string | null };

/** Raw wire shape of a workspace from `workspace.list`. */
/** The repo a workspace sits in, as Herdr reports it on the workspace record itself. */
export interface WireWorkspaceWorktree {
  repo_root: string;
  repo_name?: string;
  repo_key?: string;
  checkout_path?: string;
  is_linked_worktree?: boolean;
}

export interface WireWorkspace {
  workspace_id: string;
  number: number;
  label: string;
  focused: boolean;
  pane_count: number;
  tab_count: number;
  active_tab_id: string;
  agent_status: AgentStatus;
  /**
   * Present when the workspace sits in a Git work tree — probed 2026-08-28 on herdr 0.8.2.
   *
   * Still in herdr 0.9.3's schema (protocol 22) but no longer sent for a plain workspace: probed
   * 2026-10-07, `workspace.list` and `session.snapshot` omit it on every workspace, Git ones
   * included. The repo is read from {@link HerdrClient.workspaceWorktrees} instead (adapter.ts).
   */
  worktree?: WireWorkspaceWorktree | null;
}

/**
 * The repo a `worktree.list` answer is about — its `source` object (herdr 0.9.3, protocol 22).
 *
 * `source_checkout_path` is NOT the asked workspace's own checkout: probed 2026-10-07, asked from
 * inside a linked worktree it still names the repo's main checkout. Which checkout the workspace
 * sits in is the `worktrees` entry whose `open_workspace_id` is that workspace.
 */
export interface WireWorktreeSource {
  repo_root: string;
  repo_key?: string;
  repo_name?: string;
  source_checkout_path?: string;
  source_workspace_id?: string | null;
}

/** A whole `worktree.list` answer: the repo, and every checkout of it. */
export interface WireWorktreeListing {
  source: WireWorktreeSource;
  worktrees: WireWorktree[];
}

/**
 * Raw wire shape of a worktree from `worktree.list`.
 *
 * `open_workspace_id` is absent (not null) when nothing shows the checkout — probed 2026-08-28
 * against herdr 0.8.2, where closing the workspace dropped the key entirely.
 */
export interface WireWorktree {
  path: string;
  branch?: string | null;
  label?: string;
  is_linked_worktree: boolean;
  is_prunable: boolean;
  is_bare: boolean;
  is_detached: boolean;
  open_workspace_id?: string | null;
}

/** Raw wire shape of a tab from `tab.list`. */
export interface WireTab {
  tab_id: string;
  workspace_id: string;
  number: number;
  label: string;
  focused: boolean;
  pane_count: number;
  agent_status: AgentStatus;
}

/** Raw wire shape of a pane from `pane.list` (and, identically, inside `session.snapshot`). */
export interface WirePane {
  pane_id: string;
  terminal_id: string;
  workspace_id: string;
  tab_id: string;
  focused: boolean;
  cwd: string;
  foreground_cwd?: string;
  restore_error?: string | null;
  agent?: string | null;
  agent_status: AgentStatus;
  /** User-set pane label (herdr `pane.rename`). Present only once set — the key disappears when
   *  cleared with `label: null`, so absent/null both read as "no label". */
  label?: string | null;
  /**
   * The pane's OSC title, as the process running in it set it, and Herdr's own attempt at stripping
   * a leading status glyph off it. Both optional: older servers omit them.
   *
   * Carried on the PANE — not only on `session.snapshot`'s `agents[]` — which is why reading it
   * costs nothing architecturally: agents stay derived from `panes`, one code path (see
   * {@link WireSnapshot}).
   *
   * `terminal_title_stripped` is NOT a drop-in for display. Herdr strips the settled `✳` but leaves
   * Claude's rotating spinner frames in place (live-observed in one snapshot: `✳ Read Notes From
   * Underground` stripped, `◐ Custom UI for Collie…` not). Those frames advance on every poll, so a
   * label bound straight to it churns. `meaningfulTerminalTitle` does the strip Collie can rely on.
   */
  terminal_title?: string | null;
  terminal_title_stripped?: string | null;
  revision: number;
  /**
   * The agent's OWN session identity, as the agent reported it to Herdr (herdr ≥ 0.7.2). For Claude
   * this is `{kind:"id", value:"<uuid>"}` — the uuid naming its on-disk session log, which is how
   * Collie serves real conversation history for a pane whose terminal keeps no scrollback (see
   * transcript.ts). Optional + defensively typed: older servers omit it, and `kind` may be something
   * other than "id" for other agents.
   */
  agent_session?: {
    source?: string;
    agent?: string;
    kind?: string;
    value?: string;
  } | null;
  /**
   * Scroll geometry (herdr ≥ 0.7.2); optional so older servers that omit it still typecheck.
   *
   * `max_offset_from_bottom` is how far the pane can scroll UP — the depth of its scrollback ring —
   * so `max_offset_from_bottom + viewport_rows` is the line count a `pane.read source:"recent"` can
   * return. Live-verified on a sandbox pane (2026-07-26): 95+31 → 127 lines read, 498+31 → 530 (the
   * +1 is the trailing newline). Exact once scrollback exists; an OVER-estimate on a near-empty
   * screen, where trailing blank rows are trimmed from the read (0+31 → 4 lines read).
   *
   * This is the reported pane scroll-ring depth, not proof that an agent's alternate-screen output is
   * recoverable. Herdr 0.9.3 also reports `PaneRead.truncated` when its bounded line helper omits
   * rows; retain both facts without treating `truncated: false` as exhaustive history.
   */
  scroll?: {
    offset_from_bottom: number;
    max_offset_from_bottom: number;
    viewport_rows: number;
  } | null;
}

/** Read-only `agent.get` payload from the pinned Herdr protocol. */
export interface WireAgentInfo {
  pane_id: string;
  agent?: string | null;
  agent_status: AgentStatus;
  agent_session?: {
    source: string;
    agent: string;
    kind: "id" | "path";
    value: string;
  } | null;
  interactive_ready?: boolean;
  launch_pending?: boolean;
  state_change_seq?: string;
  completion_seq?: string | null;
}

function parseWireAgentInfo(value: JsonValue, exactUint64Marker: string): WireAgentInfo {
  const row = wireObject(value, "agent row");
  const paneId = wireText(row, "pane_id");
  const rawAgent = row.agent;
  if (rawAgent !== undefined && rawAgent !== null && typeof rawAgent !== "string") {
    throw new Error("herdr agent.get: malformed agent");
  }
  const rawStatus = row.agent_status;
  const status = parseAgentStatus(rawStatus);
  if (status === null) throw new Error("herdr agent.get: malformed agent_status");
  const sequence = (field: string): string | null => {
    const raw = row[field];
    if (raw === undefined || raw === null) return null;
    if (typeof raw === "string" && raw.startsWith(`\u0000collie.uint64.${exactUint64Marker}.`)) {
      const exact = raw.slice(`\u0000collie.uint64.${exactUint64Marker}.`.length);
      if (!/^(0|[1-9][0-9]{0,19})$/.test(exact) || BigInt(exact) > 18_446_744_073_709_551_615n) {
        throw new Error(`herdr agent.get: malformed ${field}`);
      }
      return exact;
    }
    if (typeof raw === "string") throw new Error(`herdr agent.get: malformed ${field}`);
    if (typeof raw !== "number" || !Number.isSafeInteger(raw) || raw < 0) {
      throw new Error(`herdr agent.get: malformed ${field}`);
    }
    return String(raw);
  };
  const optionalBoolean = (field: string): boolean | undefined => {
    const raw = row[field];
    if (raw === undefined) return undefined;
    if (typeof raw !== "boolean") throw new Error(`herdr agent.get: malformed ${field}`);
    return raw;
  };
  const rawSession = row.agent_session;
  let session: WireAgentInfo["agent_session"];
  if (rawSession === undefined || rawSession === null) {
    session = rawSession;
  } else {
    const ref = wireObject(rawSession, "agent_session");
    const kind = wireText(ref, "kind");
    if (kind !== "id" && kind !== "path") throw new Error("herdr agent.get: malformed agent_session.kind");
    session = {
      source: wireText(ref, "source"),
      agent: wireText(ref, "agent"),
      kind,
      value: wireText(ref, "value"),
    };
  }
  const info: WireAgentInfo = {
    pane_id: paneId,
    agent: rawAgent,
    agent_status: status,
    agent_session: session,
    state_change_seq: sequence("state_change_seq") ?? undefined,
    completion_seq: sequence("completion_seq"),
  };
  const interactiveReady = optionalBoolean("interactive_ready");
  const launchPending = optionalBoolean("launch_pending");
  if (interactiveReady !== undefined) info.interactive_ready = interactiveReady;
  if (launchPending !== undefined) info.launch_pending = launchPending;
  return info;
}

function parseAgentStatus(value: JsonValue | undefined): AgentStatus | null {
  switch (value) {
    case "idle": case "working": case "blocked": case "done": case "unknown": return value;
    default: return null;
  }
}

function validWireSession(value: WirePane["agent_session"]): boolean {
  return value === undefined || value === null || (
    typeof value === "object" && typeof value.source === "string" && value.source.trim() !== "" &&
    typeof value.agent === "string" && value.agent.trim() !== "" &&
    (value.kind === "id" || value.kind === "path") &&
    typeof value.value === "string" && value.value.trim() !== ""
  );
}

/**
 * Raw shape of `session.snapshot` — the whole herd in one reply, superseding the three parallel
 * list calls. `agents`/`layouts`/`focused_*` are carried too but intentionally unused: agents stay
 * derived from `panes` so there's one code path. Older servers predate the method (see StateEngine).
 */
export interface WireSnapshot {
  version: string;
  protocol: number;
  workspaces: WireWorkspace[];
  tabs: WireTab[];
  panes: WirePane[];
}

/** The freshly-created shell pane returned by tab.create / workspace.create (`root_pane`). */
export interface CreatedShell {
  paneId: string;
  workspaceId: string;
  workspaceLabel?: string;
  tabId: string;
  cwd: string;
}

export interface PaneRead {
  pane_id: string;
  text: string;
  truncated: boolean;
  revision: number;
}

/** `pane.process_info` result from the pinned protocol schema (protocol 22/schema 1). */
export interface WirePaneProcessInfo {
  pane_id: string;
  shell_pid?: number | null;
  foreground_process_group_id?: number | null;
  foreground_processes: Array<{
    pid: number;
    name: string;
    argv?: string[] | null;
    argv0?: string | null;
    cmdline?: string | null;
    cwd?: string | null;
  }>;
  tty?: string | null;
  /** Parser metadata, not a Herdr field. True when the wire list exceeded Collie's output bound. */
  truncated?: boolean;
}

const MAX_PROCESS_INFO_REPLY_CHARS = 1_000_000;
const MAX_PROCESSES = 64;
const MAX_PROCESS_ARGS = 64;

function wireObject(value: JsonValue | undefined, field: string): JsonObject {
  if (value === null || value === undefined || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`herdr pane.process_info: malformed ${field}`);
  }
  return value;
}

function wireText(row: JsonObject, field: string): string;
function wireText(row: JsonObject, field: string, optional: true): string | undefined;
function wireText(row: JsonObject, field: string, optional = false): string | undefined {
  const value = row[field];
  if (value === undefined || value === null) {
    if (optional) return undefined;
    throw new Error(`herdr pane.process_info: missing ${field}`);
  }
  if (typeof value !== "string") throw new Error(`herdr pane.process_info: malformed ${field}`);
  return value;
}

function wirePid(row: JsonObject, field: string): number | undefined {
  const value = row[field];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error(`herdr pane.process_info: malformed ${field}`);
  }
  return value;
}

/** Parse the pinned vendor result once; omitted rows/args are signalled to the adapter as truncated. */
function parsePaneProcessInfo(value: JsonValue): WirePaneProcessInfo {
  const result = wireObject(value, "result");
  const paneId = wireText(result, "pane_id");
  const rows = result.foreground_processes;
  if (!Array.isArray(rows)) throw new Error("herdr pane.process_info: malformed foreground_processes");
  let truncated = rows.length > MAX_PROCESSES;
  const foregroundProcesses: WirePaneProcessInfo["foreground_processes"] = [];
  for (const entry of rows.slice(0, MAX_PROCESSES)) {
    const process = wireObject(entry, "foreground process");
    const pid = wirePid(process, "pid");
    const name = wireText(process, "name");
    if (pid === undefined) throw new Error("herdr pane.process_info: missing pid");
    const argvValue = process.argv;
    let argv: string[] | undefined;
    if (argvValue !== undefined && argvValue !== null) {
      if (!Array.isArray(argvValue) || !argvValue.every((arg) => typeof arg === "string")) {
        throw new Error("herdr pane.process_info: malformed argv");
      }
      if (argvValue.length > MAX_PROCESS_ARGS) truncated = true;
      argv = argvValue.slice(0, MAX_PROCESS_ARGS).map(redactText);
    }
    const parsed: WirePaneProcessInfo["foreground_processes"][number] = { pid, name: redactText(name) };
    if (argv !== undefined) parsed.argv = argv;
    const argv0 = wireText(process, "argv0", true);
    if (argv0 !== undefined) parsed.argv0 = redactText(argv0);
    const cmdline = wireText(process, "cmdline", true);
    if (cmdline !== undefined) parsed.cmdline = redactText(cmdline);
    const cwd = wireText(process, "cwd", true);
    if (cwd !== undefined) parsed.cwd = redactText(cwd);
    foregroundProcesses.push(parsed);
  }
  const parsed: WirePaneProcessInfo = { pane_id: paneId, foreground_processes: foregroundProcesses, truncated };
  const shellPid = wirePid(result, "shell_pid");
  if (shellPid !== undefined) parsed.shell_pid = shellPid;
  const processGroupId = wirePid(result, "foreground_process_group_id");
  if (processGroupId !== undefined) parsed.foreground_process_group_id = processGroupId;
  const tty = wireText(result, "tty", true);
  if (tty !== undefined) parsed.tty = redactText(tty);
  return parsed;
}

// Wire names are snake_case: `recent-unwrapped` is REJECTED by the server (`unknown variant`,
// live-probed 2026-08-03, herdr 0.7.5). Nothing called it before that probe, so the kebab spelling
// this type carried since day one was never caught. `detection` also exists (listed by the server's
// own error message); semantics unverified, so it stays out of the union until something needs it.
type ReadSource = "visible" | "recent" | "recent_unwrapped";
type ReadFormat = "text" | "ansi";

/**
 * A pane's `agent_session`, accepted only when it is a usable session ref.
 *
 * Parsed HERE because this file is the wire boundary — everything downstream branches on the domain
 * value instead of re-narrowing the raw record. `agent` is carried through so the caller can check
 * the ref still belongs to the agent currently in the pane; it is `undefined` both when the server
 * omits it and when it is empty, which is the "stay permissive with older servers" case.
 */
export type PaneAgentSession = { kind: "id" | "path"; value: string; agent?: string };

/** {@link PaneAgentSession} off a raw pane record, or null when the record can't produce one. */
export function paneAgentSession(raw: WirePane["agent_session"]): PaneAgentSession | null {
  if (raw === null || raw === undefined) return null;
  if (raw.kind !== "id" && raw.kind !== "path") return null;
  if (typeof raw.value !== "string" || raw.value === "") return null;
  const agent = typeof raw.agent === "string" && raw.agent !== "" ? raw.agent : undefined;
  return { kind: raw.kind, value: raw.value, agent };
}

/** Wire params of `tab.create`. `focus` is always false — never yank the desktop TUI's focus. */
type TabCreateParams = { workspace_id: string; focus: false; label?: string; cwd?: string };

/** Wire params of `workspace.create`. */
type WorkspaceCreateParams = { cwd: string; focus: false; label?: string };

/** What {@link HerdrClient.subscribeEvents} is asked for. */
export type SubscribeOptions = {
  subscriptions: Array<{ type: string; pane_id?: string }>;
  onUp: () => void;
  onEvent: (event: string, data: EventData) => void;
  onDown: (reason: string) => void;
};

/** The handle {@link HerdrClient.subscribeEvents} hands back. `close()` is idempotent. */
export type EventStream = { close(): void };

/**
 * The RPCs the adapter next door actually calls — the shape it depends on, rather than this class.
 *
 * Written as a `Pick` of {@link HerdrClient} so it cannot drift from the real transport: a method
 * renamed here stops compiling, and nothing can be added to the port's surface without being added
 * to the client too. The real client is what the bridge constructs (`herdrMuxFactory`); a fake of
 * this shape is what the conformance fixture drives, which is how the reference adapter is proved
 * with no Herdr on the box (M10/03). The same narrowing `server.ts` already does for the reply path.
 */
export type HerdrRpc = Pick<
  HerdrClient,
  | "ping"
  | "sessionSnapshot"
  | "listWorkspaces"
  | "listPanes"
  | "getAgent"
  | "getPane"
  | "listTabs"
  | "readPane"
  | "processInfo"
  | "sendPaneText"
  | "sendPaneKeys"
  | "renamePane"
  | "closePane"
  | "focusPane"
  | "createTab"
  | "renameTab"
  | "closeTab"
  | "focusWorkspace"
  | "renameWorkspace"
  | "moveWorkspace"
  | "moveWorkspaceBlock"
  | "closeWorkspace"
  | "focusTab"
  | "moveTab"
  | "createWorkspace"
  | "listWorktrees"
  | "workspaceWorktrees"
  | "createWorktree"
  | "openWorktree"
  | "subscribeEvents"
> & Partial<Pick<HerdrClient,
  | "handshake"
  | "layoutHandshakeSample"
  | "layoutRuntimeIdentity"
  | "retireLayoutRuntime"
  | "readPaneLayout"
  | "readPaneNeighbor"
  | "readPaneEdges"
  | "focusPaneDirection"
  | "splitPane"
  | "resizePane"
  | "zoomPane"
  | "swapPanes"
  | "movePane"
>>;

let idCounter = 0;

/** Per-request wall-clock budget. Exported so callers can pass it explicitly alongside a dial mode. */
export const DEFAULT_TIMEOUT_MS = 5000;

/**
 * The budget of a worktree create or open, per call (ADR 0089). Herdr runs `git worktree add` and
 * opens a workspace before it answers, and on a large repo that takes far longer than the 5 s every
 * other call gets. Cut at 5 s, the create still finishes on the host, and the phone is told it failed.
 */
export const WORKTREE_TIMEOUT_MS = 60_000;

/** pane.get is read twice for every identity join, so each untrusted reply has a hard wire bound. */
export const MAX_PANE_REPLY_CHARS = 64 * 1024;
const UINT64_FIELDS = new Set(["state_change_seq", "completion_seq"]);

/** Quote only JSON numeric tokens belonging to uint64 fields before JSON.parse can round them. */
function preserveUint64Tokens(line: string, marker: string): string {
  const edits: Array<{ start: number; end: number; replacement: string }> = [];
  for (let i = 0; i < line.length;) {
    if (line[i] !== '"') { i++; continue; }
    const start = i++;
    let escaped = false;
    while (i < line.length) {
      const ch = line[i++]!;
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') break;
    }
    if (line[i - 1] !== '"') break;
    let key: unknown;
    try { key = JSON.parse(line.slice(start, i)); } catch { continue; }
    if (typeof key !== "string" || !UINT64_FIELDS.has(key)) continue;
    let cursor = i;
    while (/\s/.test(line[cursor] ?? "")) cursor++;
    if (line[cursor] !== ":") continue;
    cursor++;
    while (/\s/.test(line[cursor] ?? "")) cursor++;
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(line.slice(cursor));
    if (match) edits.push({ start: cursor, end: cursor + match[0].length, replacement: JSON.stringify(`\u0000collie.uint64.${marker}.${match[0]}`) });
  }
  if (edits.length === 0) return line;
  let output = "";
  let from = 0;
  for (const edit of edits) {
    output += line.slice(from, edit.start) + edit.replacement;
    from = edit.end;
  }
  return output + line.slice(from);
}


/** `worktree.create`'s parameters as Collie sends them. `base` and `path` go on the wire only when set. */
export interface HerdrWorktreeCreate {
  cwd: string;
  branch: string;
  base?: string;
  path?: string;
}

/** Closed parameters for the one-shot, focus-off pane.split mapping. */
export interface HerdrSplitPaneParams {
  readonly direction: "right" | "down";
  readonly ratio: number | null;
  readonly target_pane_id: string;
  readonly workspace_id: string;
  cwd?: string;
  readonly env: JsonObject;
  readonly focus: false;
  readonly right_click: "herdr";
}

export class HerdrClient {
  constructor(
    private readonly socketPath: string,
    private readonly timeoutMs = DEFAULT_TIMEOUT_MS,
    /** Which dialer to use; see {@link DialMode}. `auto` picks by platform. */
    private readonly dialMode: DialMode = "auto",
  ) {}

  /** Stable local endpoint plus process-incarnation witness for the currently handshaken runtime. */
  layoutRuntimeIdentity(): string | null {
    if (!this.verifiedHandshake || !this.verifiedEndpointWitness) return null;
    const witness = this.observeEndpointWitness();
    if (!witness || witness !== this.verifiedEndpointWitness) { this.retireHandshake(); return null; }
    return this.runtimeIdentity(witness);
  }

  /** A compatible ping bracketed by the OS witness of the actual local endpoint. */
  async layoutHandshakeSample(): Promise<{ handshake: HerdrHandshake; identity: string } | null> {
    const before = this.observeEndpointWitness();
    if (!before) return null;
    try {
      const handshake = await this.handshake();
      const after = this.observeEndpointWitness();
      if (!after || before !== after) { this.retireHandshake(); return null; }
      this.verifiedEndpointWitness = after;
      return { handshake, identity: this.runtimeIdentity(after) };
    } catch {
      this.retireHandshake();
      return null;
    }
  }

  /** Event-stream loss or replacement immediately retires cached layout readiness. */
  retireLayoutRuntime(): void { this.retireHandshake(); }

  private runtimeIdentity(witness: string): string {
    return createHash("sha256").update(`herdr-layout-runtime\0${witness}\0${this.handshakeGeneration}\0${this.verifiedHandshake}`).digest("hex");
  }
  private observeEndpointWitness(): string | null {
    try {
      const witness = HOST.platform === "win32"
        ? readWindowsPipeWitness(this.socketPath)
        : this.unixEndpointWitness();
      if (!witness) { this.retireHandshake(); return null; }
      if (this.lastEndpointWitness !== null && this.lastEndpointWitness !== witness) {
        this.retireHandshake();
        return null;
      }
      if (this.lastEndpointWitness === null) this.handshakeGeneration += 1;
      this.lastEndpointWitness = witness;
      return witness;
    } catch { this.retireHandshake(); return null; }
  }
  private unixEndpointWitness(): string | null {
    const stat = statSync(this.socketPath, { bigint: true });
    if (!stat.isSocket()) return null;
    return createHash("sha256").update(`${stat.dev}:${stat.ino}:${stat.ctimeNs}`).digest("hex");
  }
  private retireHandshake(): void {
    this.verifiedHandshake = null;
    this.verifiedEndpointWitness = null;
    this.lastEndpointWitness = null;
    this.handshakeGeneration += 1;
  }
  private verifiedHandshake: string | null = null;
  private verifiedEndpointWitness: string | null = null;
  private lastEndpointWitness: string | null = null;
  private handshakeGeneration = 0;

  /**
   * One request, one reply, one connection. Rejects on error reply, timeout, or early close.
   *
   * `timeoutMs` overrides the client's budget for this one call; absent, the constructor's applies.
   */
  private request<T>(method: string, params: JsonObject = {}, timeoutMs = this.timeoutMs, maxReplyChars?: number, exactUint64Marker?: string): Promise<T> {
    const id = `b${++idCounter}`;
    return new Promise<T>((resolve, reject) => {
      let buf = "";
      let settled = false;
      // The live socket, once the dial opens one. Hoisted so EVERY terminal path (timeout
      // included) can close it — otherwise a timeout leaves the FD dangling.
      let socket: SockHandle | null = null;
      // Aborts a dial that is still connecting — a timeout that fires mid-connect has no socket
      // to end() yet, and without this the pending OS handle lives until the connect settles.
      let cancelDial: (() => void) | null = null;
      // Stream-decode so a multi-byte UTF-8 codepoint split across chunk boundaries isn't
      // corrupted into replacement characters.
      const decoder = new TextDecoder("utf-8", { fatal: true });
      let replyBytes = 0;
      // Settle BEFORE closing: socket.end() synchronously fires `close`, which re-enters finish —
      // but `settled` is already set there, so that reject is a no-op and we keep the real outcome.
      const finish = (fn: () => void) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        fn();
        if (socket) {
          try {
            socket.end();
          } catch {
            /* ignore */
          }
          socket = null;
        } else if (cancelDial) {
          // Timed out (or failed) while still connecting — abort the in-flight dial.
          try {
            cancelDial();
          } catch {
            /* ignore */
          }
        }
        cancelDial = null;
      };
      const timer = setTimeout(
        () => finish(() => reject(new Error(`herdr ${method}: timed out after ${timeoutMs}ms`))),
        timeoutMs,
      );

      const dialed = dialHerdr(this.socketPath, {
        onDial(cancel) {
          cancelDial = cancel;
        },
        open(s) {
          socket = s;
        },
        data(s, chunk) {
          socket = s;
          replyBytes += chunk.byteLength;
          if (maxReplyChars !== undefined && replyBytes > maxReplyChars * 4) {
            finish(() => reject(new Error(`herdr ${method}: reply exceeded ${maxReplyChars * 4} bytes`)));
            return;
          }
          try {
            buf += decoder.decode(chunk, { stream: true });
          } catch {
            finish(() => reject(new Error(`herdr ${method}: reply was not valid UTF-8`)));
            return;
          }
          if (maxReplyChars !== undefined && buf.length > maxReplyChars) {
            finish(() => reject(new Error(`herdr ${method}: reply exceeded ${maxReplyChars} characters`)));
            return;
          }
          const nl = buf.indexOf("\n");
          if (nl < 0) return;
          const line = buf.slice(0, nl);
          finish(() => {
            try {
              decoder.decode(); // Reject an incomplete UTF-8 codepoint at the reply boundary.
              const decoded = decodeReplyLine<T>(exactUint64Marker ? preserveUint64Tokens(line, exactUint64Marker) : line, method);
              resolve(decoded);
            } catch (error) {
              reject(error instanceof Error ? error : new Error(`herdr ${method}: malformed reply`));
            }
          });
        },
        error(_s, err) {
          finish(() => reject(err));
        },
        close() {
          finish(() => reject(new Error(`herdr ${method}: connection closed before reply`)));
        },
      }, this.dialMode);

      // One catch for BOTH the dial failing and anything the post-connect block throws — the
      // `.then(…).catch(…)` this replaces funnelled them to the same `finish(reject)`.
      void (async () => {
        try {
          const s = await dialed;
          // Already settled (e.g. timed out) before the connection opened — close it so the FD
          // doesn't leak, and don't bother writing.
          if (settled) {
            try {
              s.end();
            } catch {
              /* ignore */
            }
            return;
          }
          socket = s;
          // Write only once the connection is established — matches the verified probe pattern.
          // A long request (a big paste, a wide pane's text) can exceed what the socket accepts in
          // one go; the dialer owns that continuation, so there is nothing to retry here (dial.ts,
          // write-drain.ts). A connection that dies mid-write lands in close/error above and
          // rejects like any other transport failure.
          s.write(JSON.stringify({ id, method, params }) + "\n");
          s.flush();
        } catch (err) {
          finish(() => reject(err));
        }
      })();
    });
  }

  async listWorkspaces(): Promise<WireWorkspace[]> {
    const r = await this.request<{ workspaces: WireWorkspace[] }>("workspace.list");
    return r.workspaces;
  }

  async listPanes(): Promise<WirePane[]> {
    const r = await this.request<{ panes: WirePane[] }>("pane.list");
    return r.panes;
  }

  async getPane(paneId: string): Promise<WirePane> {
    const r = await this.request<{ type: string; pane: WirePane }>("pane.get", { pane_id: paneId }, this.timeoutMs, MAX_PANE_REPLY_CHARS);
    const pane = r.pane;
    if (r.type !== "pane_info" || !pane || pane.pane_id !== paneId ||
      typeof pane.terminal_id !== "string" || pane.terminal_id.trim() === "" ||
      typeof pane.workspace_id !== "string" || pane.workspace_id.trim() === "" ||
      typeof pane.tab_id !== "string" || pane.tab_id.trim() === "" ||
      typeof pane.agent_status !== "string" || parseAgentStatus(pane.agent_status) === null ||
      (pane.agent !== undefined && pane.agent !== null && typeof pane.agent !== "string") ||
      !validWireSession(pane.agent_session)) {
      throw new Error("herdr pane.get: pane identity mismatch");
    }
    return pane;
  }

  /** Read the exact agent target by pane ID; never resolve an agent name or ambient focus. */
  async getAgent(paneId: string): Promise<WireAgentInfo> {
    const exactUint64Marker = randomUUID();
    const raw = await this.request<JsonValue>("agent.get", { target: paneId }, this.timeoutMs, 64 * 1024, exactUint64Marker);
    const result = wireObject(raw, "agent.get result");
    if (result.type !== "agent_info" || result.agent === undefined) throw new Error("herdr agent.get: unexpected result type");
    const agent = parseWireAgentInfo(result.agent, exactUint64Marker);
    if (agent.pane_id !== paneId) throw new Error("herdr agent.get: target identity mismatch");
    return agent;
  }

  /** Fetch sensitive process metadata for the explicit target; never asks Herdr to infer focus. */
  async processInfo(paneId: string): Promise<WirePaneProcessInfo> {
    const r = await this.request<JsonValue>("pane.process_info", { pane_id: paneId }, this.timeoutMs, MAX_PROCESS_INFO_REPLY_CHARS);
    const result = wireObject(r, "result wrapper");
    if (result.type !== "pane_process_info") throw new Error("herdr pane.process_info: unexpected result type");
    return parsePaneProcessInfo(result.process_info ?? null);
  }

  /** All tabs across every workspace (`tab.list` with no filter returns the full set). */
  async listTabs(): Promise<WireTab[]> {
    const r = await this.request<{ tabs: WireTab[] }>("tab.list");
    return r.tabs;
  }

  /**
   * The whole herd in one round-trip (herdr ≥ 0.7.2). Replaces workspace.list + pane.list +
   * tab.list for the poll loop. An older server rejects the method with an "unknown variant" error
   * reply — StateEngine treats only that as a permanent signal to fall back to the three list calls.
   */
  async sessionSnapshot(): Promise<WireSnapshot> {
    const r = await this.request<{ type: string; snapshot: WireSnapshot }>("session.snapshot");
    return r.snapshot;
  }

  /**
   * Open a LONG-LIVED `events.subscribe` stream. Unlike every other method here (one-shot), this
   * connection stays open: after the ack, each line is an event. It exists ONLY to poke re-polls —
   * callers must not treat events as state. `onDown` fires exactly once when the stream ends for any
   * reason (error line, socket error, close, or a 5s ack timeout); `close()` is idempotent and also
   * ends it with reason "closed". Reconnect/backoff live in the caller (see EventPoker).
   */
  subscribeEvents(opts: SubscribeOptions): EventStream {
    const id = `es${++idCounter}`;
    const decoder = new TextDecoder("utf-8");
    let buf = "";
    let socket: SockHandle | null = null;
    let cancelDial: (() => void) | null = null;
    let down = false;
    let acked = false;

    // The single terminal path. Guarded so onDown never fires twice, and closes the FD once.
    const fireDown = (reason: string) => {
      if (down) return;
      down = true;
      clearTimeout(ackTimer);
      if (socket) {
        try {
          socket.end();
        } catch {
          /* ignore */
        }
        socket = null;
      } else if (cancelDial) {
        // Ack timeout (or close()) while the dial was still connecting — abort it so repeated
        // reconnect attempts can't stack pending OS handles.
        try {
          cancelDial();
        } catch {
          /* ignore */
        }
      }
      cancelDial = null;
      opts.onDown(reason);
    };

    // A server that accepts the connection but never acks (hung) counts as down, not healthy.
    const ackTimer = setTimeout(() => fireDown("ack timeout"), 5000);

    const handleLine = (line: string) => {
      if (line === "") return;
      let decoded;
      try {
        decoded = decodeStreamLine(line);
      } catch (e) {
        fireDown(`protocol error: ${e instanceof Error ? e.message : String(e)}`);
        return;
      }
      if (decoded.kind === "error") {
        fireDown(`${decoded.code}: ${decoded.message}`);
        return;
      }
      if (decoded.kind === "ack") {
        if (acked) return;
        acked = true;
        clearTimeout(ackTimer);
        opts.onUp();
        return;
      }
      opts.onEvent(decoded.event, decoded.data);
    };

    const dialed = dialHerdr(this.socketPath, {
      onDial(cancel) {
        cancelDial = cancel;
      },
      open(s) {
        socket = s;
      },
      // Multiple lines can arrive per chunk (bursty events); drain ALL complete lines and keep the
      // stream open. Stream-decode so a multi-byte codepoint split across chunks isn't corrupted.
      data(s, chunk) {
        socket = s;
        buf += decoder.decode(chunk, { stream: true });
        let nl = buf.indexOf("\n");
        while (nl >= 0) {
          if (down) break;
          const line = buf.slice(0, nl);
          buf = buf.slice(nl + 1);
          handleLine(line);
          nl = buf.indexOf("\n");
        }
      },
      error(_s, err) {
        fireDown(err.message || "socket error");
      },
      close() {
        fireDown("connection closed");
      },
    }, this.dialMode);

    // One catch for BOTH the dial failing and anything the post-connect block throws — the
    // `.then(…).catch(…)` this replaces funnelled them to the same `fireDown`.
    void (async () => {
      try {
        const s = await dialed;
        if (down) {
          try {
            s.end();
          } catch {
            /* ignore */
          }
          return;
        }
        socket = s;
        s.write(JSON.stringify({ id, method: "events.subscribe", params: { subscriptions: opts.subscriptions } }) + "\n");
        s.flush();
      } catch (err) {
        fireDown((err instanceof Error ? err.message : String(err)) || "connect failed");
      }
    })();

    return { close: () => fireDown("closed") };
  }

  /**
   * Create a new tab in a workspace, opening a fresh shell pane. `cwd` is optional — omitted, the
   * tab inherits the workspace's directory (verified). `focus:false` so we never yank the desktop
   * TUI's focus. Returns the new shell pane to navigate into.
   */
  async createTab(workspaceId: string, opts: { label?: string; cwd?: string } = {}): Promise<CreatedShell> {
    // Optional keys are ASSIGNED, not spread — an empty-string label must stay omitted (herdr
    // stores "" literally), which a `label: opts.label` field would not do.
    const params: TabCreateParams = { workspace_id: workspaceId, focus: false };
    if (opts.label) params.label = opts.label;
    if (opts.cwd) params.cwd = opts.cwd;
    const r = await this.request<{ root_pane: WirePane }>("tab.create", params);
    const p = r.root_pane;
    return { paneId: p.pane_id, workspaceId: p.workspace_id, tabId: p.tab_id, cwd: p.cwd };
  }

  /**
   * Create a new workspace ("space") with a fresh shell pane rooted at `cwd`. `focus:false` to
   * leave the desktop TUI undisturbed. Returns the new shell pane (with its workspace label).
   */
  async createWorkspace(opts: { cwd: string; label?: string }): Promise<CreatedShell> {
    // See createTab: an empty label stays omitted.
    const params: WorkspaceCreateParams = { cwd: opts.cwd, focus: false };
    if (opts.label) params.label = opts.label;
    const r = await this.request<{
      workspace: WireWorkspace;
      root_pane: WirePane;
    }>("workspace.create", params);
    const p = r.root_pane;
    return {
      paneId: p.pane_id,
      workspaceId: p.workspace_id,
      workspaceLabel: r.workspace.label,
      tabId: p.tab_id,
      cwd: p.cwd,
    };
  }

  /** Exact schema-22 organization methods. Ordering indices are Herdr's pre-removal indices. */
  focusWorkspace(workspaceId: string): Promise<void> {
    return this.request<void>("workspace.focus", { workspace_id: workspaceId });
  }
  renameWorkspace(workspaceId: string, label: string): Promise<void> {
    return this.request<void>("workspace.rename", { workspace_id: workspaceId, label });
  }
  moveWorkspace(workspaceId: string, insertIndex: number): Promise<void> {
    return this.request<void>("workspace.move", { workspace_id: workspaceId, insert_index: insertIndex });
  }
  moveWorkspaceBlock(workspaceIds: readonly string[], beforeWorkspaceId: string | null): Promise<void> {
    return this.request<void>("workspace.move_block", { workspace_ids: [...workspaceIds], before_workspace_id: beforeWorkspaceId });
  }
  closeWorkspace(workspaceId: string, closeGroup: boolean): Promise<void> {
    return this.request<void>("workspace.close", { workspace_id: workspaceId, close_group: closeGroup });
  }
  focusTab(tabId: string): Promise<void> {
    return this.request<void>("tab.focus", { tab_id: tabId });
  }
  moveTab(tabId: string, insertIndex: number): Promise<void> {
    return this.request<void>("tab.move", { tab_id: tabId, insert_index: insertIndex });
  }

  /**
   * The worktrees of the repo at `cwd` — including the repo's own checkout, which comes back with
   * `is_linked_worktree: false`.
   *
   * Takes a PATH, not a workspace: probed 2026-08-28 against herdr 0.8.2, `worktree.list --cwd`
   * answers for a repo nothing has open, which is what lets the sheet list a worktree before there
   * is any space to name it by.
   */
  async listWorktrees(cwd: string): Promise<WireWorktree[]> {
    const r = await this.request<{ worktrees: WireWorktree[] }>("worktree.list", { cwd });
    return r.worktrees;
  }

  /**
   * The repo a WORKSPACE sits in, and every checkout of it: `worktree.list` by `workspace_id`.
   *
   * herdr 0.9.3 stopped putting the repo on the workspace record (see {@link WireWorkspace.worktree}),
   * and this is where it went. On the client's own budget, not WORKTREE_TIMEOUT_MS: it runs `git
   * worktree list`, which reads and never writes. A folder outside Git answers `not_git_worktree`,
   * an unknown id `workspace_not_found` — both rejections, both told apart by the caller.
   */
  async workspaceWorktrees(workspaceId: string): Promise<WireWorktreeListing> {
    const r = await this.request<WireWorktreeListing>("worktree.list", { workspace_id: workspaceId });
    return { source: r.source, worktrees: r.worktrees };
  }

  /**
   * Create a worktree on a new branch and open it as its own workspace. `focus:false`, so the
   * operator's own screen stays where they left it (the phone navigates itself).
   *
   * NOT ATOMIC, and the caller must know: probed 2026-08-28 against herdr 0.8.2 in a session with no
   * window server, the checkout was created and the OPEN failed with `worktree_open_failed` — the
   * branch exists, nothing shows it. A retry then fails as `worktree_create_failed` (the path is
   * taken), so the recovery is to open it, never to create it again.
   *
   * `base` is `WorktreeCreateParams.base` (`herdr api schema --json`, 0.9.3, protocol 22; the socket
   * docs say a missing branch is created "from the requested base or `HEAD`"). It is sent only when
   * given, so a call without one is byte-for-byte the call this method always made.
   */
  async createWorktree(opts: HerdrWorktreeCreate): Promise<CreatedShell> {
    // No `trust_repository`: that flag grants Git trust for the request (`safe.directory`), and the
    // operator gives it in Herdr, never from the phone (ADR 0089). `path` is Herdr 0.9.3's
    // `WorktreeCreateParams.path`, absolute, checked by bridge/worktree-folder.ts (ADR 0093).
    const { cwd, branch, base, path } = opts;
    const params: JsonObject = { cwd, branch, focus: false };
    if (base !== undefined) params.base = base;
    if (path !== undefined) params.path = path;
    const r = await this.request<{ workspace: WireWorkspace; root_pane: WirePane }>(
      "worktree.create",
      params,
      WORKTREE_TIMEOUT_MS,
    );
    const p = r.root_pane;
    return {
      paneId: p.pane_id,
      workspaceId: p.workspace_id,
      workspaceLabel: r.workspace.label,
      tabId: p.tab_id,
      cwd: p.cwd,
    };
  }

  /**
   * Show a worktree that already exists on disk, by its checkout path.
   *
   * `cwd` (the repo) is required alongside it: probed 2026-08-28, `worktree.open` with `path` alone
   * answers `not_git_worktree`. A worktree already open answers `already_open: true` with the space
   * showing it — an answer, not a refusal.
   */
  async openWorktree(opts: {
    cwd: string;
    path: string;
  }): Promise<{ shell: CreatedShell; alreadyOpen: boolean }> {
    const r = await this.request<{
      workspace: WireWorkspace;
      root_pane: WirePane;
      already_open?: boolean;
    }>("worktree.open", { cwd: opts.cwd, path: opts.path, focus: false }, WORKTREE_TIMEOUT_MS);
    const p = r.root_pane;
    return {
      shell: {
        paneId: p.pane_id,
        workspaceId: p.workspace_id,
        workspaceLabel: r.workspace.label,
        tabId: p.tab_id,
        cwd: p.cwd,
      },
      alreadyOpen: r.already_open === true,
    };
  }


  async readPane(
    paneId: string,
    source: ReadSource,
    lines: number,
    format: ReadFormat = "text",
  ): Promise<PaneRead> {
    const r = await this.request<{ read: PaneRead }>("pane.read", {
      pane_id: paneId,
      source,
      lines,
      // "text" = plain (no escapes); "ansi" = SGR color codes (verified: no cursor sequences),
      // parsed + escaped safely on the client to render a faithful, colored terminal mirror.
      format,
    });
    return r.read;
  }

  /** Type literal text into a pane's terminal (does not submit). */
  sendPaneText(paneId: string, text: string): Promise<void> {
    return this.request<void>("pane.send_text", { pane_id: paneId, text });
  }

  /** Send key names (e.g. ["Enter"]) to a pane — used to submit a reply. */
  sendPaneKeys(paneId: string, keys: string[]): Promise<void> {
    return this.request<void>("pane.send_keys", { pane_id: paneId, keys });
  }

  /** Close a pane, terminating its agent ("kill"). Resolves on Herdr's `{type:"ok"}` reply. */
  closePane(paneId: string): Promise<void> {
    return this.request<void>("pane.close", { pane_id: paneId });
  }

  /**
   * Put a pane in front of the operator, on their own screen.
   *
   * ONE call does the whole act: live-probed 2026-08-25 against the `collie-demo` sandbox session,
   * `pane.focus {pane_id}` answered `pane_info` and the next `session.snapshot` reported
   * `focused_pane_id`, `focused_tab_id` AND `focused_workspace_id` all moved with it — so the tab and
   * the workspace need no calls of their own (`tab.focus` / `workspace.focus` exist and are not used).
   * A pane that has gone away answers `pane_not_found`, probed read-only against the live server the
   * same day, which the adapter's `GONE_CODES` turns into the contract's `gone`.
   */
  focusPane(paneId: string): Promise<void> {
    return this.request<void>("pane.focus", { pane_id: paneId });
  }

  /** Read the current pane layout; the caller validates the closed success DTO. */
  readPaneLayout(paneId: string): Promise<JsonValue> {
    return this.request<JsonValue>("pane.layout", { pane_id: paneId });
  }

  readPaneNeighbor(paneId: string, direction: "left" | "right" | "up" | "down"): Promise<JsonValue> {
    return this.request<JsonValue>("pane.neighbor", { pane_id: paneId, direction });
  }

  readPaneEdges(paneId: string): Promise<JsonValue> {
    return this.request<JsonValue>("pane.edges", { pane_id: paneId });
  }

  focusPaneDirection(paneId: string, direction: "left" | "right" | "up" | "down"): Promise<JsonValue> {
    return this.request<JsonValue>("pane.focus_direction", { pane_id: paneId, direction });
  }

  splitPane(params: HerdrSplitPaneParams): Promise<JsonValue> {
    return this.request<JsonValue>("pane.split", { ...params });
  }

  resizePane(paneId: string, direction: "left" | "right" | "up" | "down", amount: number | null): Promise<JsonValue> {
    return this.request<JsonValue>("pane.resize", { pane_id: paneId, direction, amount });
  }

  zoomPane(paneId: string, mode: "toggle" | "on" | "off"): Promise<JsonValue> {
    return this.request<JsonValue>("pane.zoom", { pane_id: paneId, mode });
  }

  swapPanes(params: { pane_id: string; direction: "left" | "right" | "up" | "down" | null; source_pane_id: string | null; target_pane_id: string | null }): Promise<JsonValue> {
    return this.request<JsonValue>("pane.swap", params);
  }

  movePane(params: { pane_id: string; destination: HerdrMoveDestination; focus: false }): Promise<JsonValue> {
    return this.request<JsonValue>("pane.move", params);
  }

  /**
   * Set or clear a pane's label. `label: null` clears it (the key then disappears from pane
   * records). Resolves on Herdr's `pane_info` reply — the returned pane isn't consumed here, the
   * next snapshot poll carries the new label (pane.rename emits no event). Bad id → `pane_not_found`.
   */
  renamePane(paneId: string, label: string | null): Promise<void> {
    return this.request<void>("pane.rename", { pane_id: paneId, label });
  }

  /**
   * Set a tab's label. Unlike {@link renamePane}, `label` is a NON-null string: herdr's `tab.rename`
   * rejects `null` (`invalid type: null, expected a string`) and stores an empty string literally
   * rather than clearing to the default number — both live-verified 2026-07-19 — so a tab has no
   * "clear". Resolves on herdr's `tab_info` reply; the new label surfaces on the next snapshot poll
   * (tab.rename also emits a `tab_renamed` event, which Collie doesn't consume). Bad id → `tab_not_found`.
   */
  renameTab(tabId: string, label: string): Promise<void> {
    return this.request<void>("tab.rename", { tab_id: tabId, label });
  }

  /**
   * Close a tab, terminating EVERY pane inside it (live-verified 2026-07-19: the tab's shell/agent
   * panes all disappear with it — closing a tab is a bulk pane-close). Resolves on herdr's
   * `{type:"ok"}` reply; the closure surfaces on the next `session.snapshot` poll (tab.close also
   * emits a `tab_closed` event, which Collie doesn't consume). Bad id → `tab_not_found`.
   */
  closeTab(tabId: string): Promise<void> {
    return this.request<void>("tab.close", { tab_id: tabId });
  }

  /** Validate the schema-22 pong variant rather than treating an arbitrary successful RPC as health. */
  async handshake(): Promise<HerdrHandshake> {
    let result: JsonObject;
    try { result = await this.request<JsonObject>("ping"); }
    catch (error) { this.retireHandshake(); throw error; }
    if (result.type !== "pong" || typeof result.version !== "string" || !result.version || result.version.length > 256 ||
        typeof result.protocol !== "number" || !Number.isInteger(result.protocol) || result.protocol < 0 || result.protocol > 0xffffffff) {
      this.retireHandshake();
      throw new Error("herdr ping: malformed pong");
    }
    this.verifiedHandshake = `${result.version}:${result.protocol}`;
    return { version: result.version, protocol: result.protocol };
  }

  /** Reachability check through the existing adapter health seam; never infer new capabilities. */
  async ping(): Promise<boolean> {
    try {
      await this.handshake();
      return true;
    } catch {
      return false;
    }
  }
}
