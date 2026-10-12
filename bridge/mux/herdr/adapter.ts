// HERDR, BEHIND THE CONTRACT — the reference adapter (M10/02).
//
// Everything above this file talks the mux port (../types.ts); everything below it (`client.ts`)
// talks Herdr's socket. So this module is the whole translation, and it is deliberately the ONLY
// place that holds both vocabularies at once:
//
//   • the mapping from Herdr's wire records to `MuxPane` / `MuxSpace` / `MuxTab`, including the
//     three derivations the state engine used to do inline (the workspace join, the meaningful
//     tab label, the meaningful terminal title) — those are *what the multiplexer knows*, so they
//     belong to the adapter that knows it;
//   • the `session.snapshot` → three-list-calls fallback for a server that predates the method,
//     which is a Herdr version fact and nothing the port should ever hear about;
//   • the key spelling (keys.ts) and the event subscriptions (events.ts).
//
// WHAT IT DOES NOT DO. It never reaches across a machine boundary: `MuxTarget.endpoint` is a local
// socket path and `dial.ts` picks a LOCAL dialer for it (unix socket / Windows named pipe). A remote
// herd is reached by talking to the Collie running on that machine, never by dialling its Herdr
// (ADR 0011, ADR 0022) — so nothing here grows a host, and neither does dial.ts.
//
// CAPABILITIES ARE DECLARED FROM THE METHODS BELOW, one for one. Herdr implements every one of the
// contract's fourteen, which is unsurprising — Collie's routes were written against Herdr — and is
// exactly why the declaration must be read off the implementation rather than assumed: the value of
// the list is that the NEXT adapter's is shorter.

import { layoutSnapshotAuthority } from "../layout-evidence.ts";
import { createHash } from "node:crypto";
import type { JsonObject, JsonValue } from "../../json.ts";
import { meaningfulTabLabel, meaningfulTerminalTitle } from "../../activity.ts";
import { isUnnamedTab } from "../../pane-name.ts";
import { WriteAdmissionRetired, type DialMode } from "../../dial.ts";
import { declareCapabilities } from "../capabilities.ts";
import { herdrMachineCandidates } from "./machine-list.ts";
import { herdrSessionSource } from "./sessions.ts";
import { HERDR_LOGO_SVG } from "./logo.ts";
import { herdrHostControl } from "../../host-control/herdr.ts";
import type { MuxAdapterFactory, MuxTarget } from "../registry.ts";
import {
  muxAck,
  muxGone,
  muxOk,
  muxRefused,
  muxUnsupported,
  muxUnreachable,
  requestedCwd,
  type MuxAck,
  type MuxAdapter,
  type MuxAgentIdentity,
  type MuxCreatedPane,
  type MuxGrid,
  type MuxGridRequest,
  type MuxLayoutAction,
  type MuxLayoutFinalAdmission,
  type MuxLayoutFinalEvidence,
  type MuxLayoutMutation,
  type MuxLayoutRead,
  type MuxLayoutReadRequest,
  type MuxLayoutSnapshot,
  type MuxPortableLayout,
  type MuxPortableLayoutNode,
  type MuxLayoutApplyRequest,
  type MuxLayoutCreated,
  type MuxSplitRatioRequest,
  type MuxMoveDestination,
  type MuxOutcome,
  type MuxPane,
  type MuxPaneProcessInfo,
  type MuxRefusalOutcome,
  type MuxSession,
  type MuxSnapshot,
  type MuxSpace,
  type MuxSpaceRequest,
  type MuxSubscription,
  type MuxTab,
  type MuxTabRequest,
  type MuxWatchOptions,
  type MuxWorktree,
  type MuxWorktreeCreateRequest,
  type MuxWorktreeOpenRequest,
  type MuxWorktreeOpened,
  type MuxWorktreeRemoveRequest,
  type MuxWorktreeRemoved,
  type MuxWorktreeScope,
} from "../types.ts";
import {
  DEFAULT_TIMEOUT_MS,
  HerdrClient,
  paneAgentSession,
  type CreatedShell,
  type HerdrRpc,
  type HerdrLayoutNode,
  type HerdrSplitPaneParams,
  type HerdrMoveDestination,
  type HerdrWorktreeCreate,
  type WirePane,
  type WireTab,
  type WireWorkspace,
  type WireWorktree,
  type WireWorktreeListing,
} from "./client.ts";
import { buildSubscriptions, changedPaneId } from "./events.ts";
import { HERDR_UNSENDABLE_KEYS, toHerdrKey } from "./keys.ts";
import { redactText } from "../../redact.ts";
import { MAX_PROCESS_INFO_TEXT_CHARS } from "../../process-info-limits.ts";

/** The registry name this adapter answers to, and the value of {@link HerdrMux.mux}. */
export const HERDR_MUX = "herdr";

interface BoundedProcessInfoRow {
  pid: number;
  name: string;
  argv?: readonly string[];
  argv0?: string;
  cmdline?: string;
  cwd?: string;
}

interface BoundedProcessInfo {
  paneId: string;
  foregroundCwd?: string;
  restoreError?: string;
  shellPid?: number;
  foregroundProcessGroupId?: number;
  processes: readonly BoundedProcessInfoRow[];
  truncated: boolean;
  tty?: string;
}

/**
 * What Herdr can do, read off the methods in this file.
 *
 * Every capability is claimed, and each one has a method below that implements it — `paneGrid` and
 * `gridScrollback` are `pane.read`'s two sources, `agentDetection`/`agentSessionRef` are fields the
 * pane record carries, the six structural verbs are one RPC each, and both push capabilities are the
 * one `events.subscribe` stream. `unsupportedKeys` is where Herdr's actual hole is: six named keys
 * the server refuses (keys.ts).
 */
const HERDR_BASE_CAPABILITIES = [
    "paneGrid",
    "gridScrollback",
    "agentDetection",
    "agentSessionRef",
    "agentIdentity",
    "paneProcesses",
    "typeText",
    "sendKeys",
    "renamePane",
    "closePane",
    "setFocus",
    "createTab",
    "renameTab",
    "closeTab",
    "createSpace",
    "listWorktrees",
    "createWorktree",
    "openWorktree",
    "pushTopologyEvents",
    "pushPaneEvents",
    "listSessions",
] as const;
const HERDR_CAPABILITIES = declareCapabilities({
  supports: [
    ...HERDR_BASE_CAPABILITIES,
    "removeWorktree",
    "paneLayout", "portableLayouts", "tabTransfer", "paneSplit", "paneResize", "paneZoom", "paneSwap", "paneMove", "paneDirectionalFocus",
    "workspaceRename", "workspaceClose", "workspaceMove", "workspaceMoveBlock", "workspaceFocus", "tabMove", "tabFocus",
  ],
  unsupportedKeys: HERDR_UNSENDABLE_KEYS,
  // Herdr announces every structure change on `events.subscribe`, so there is no census and no
  // number to state (HERDR_API.md § Event stream). `refresh()` is correspondingly a no-op below.
  topologyLatency: { kind: "push" },
  readLimits: { maxRequestedLines: 1_000 },
  notes: {
    gridScrollback:
      "Herdr reports the pane's scroll-ring depth through readableLines; its bounded read also reports omitted rows. Neither fact proves exhaustive agent-owned alternate-screen history.",
  },
  // Herdr's workspaces are Collie's spaces, and there are as many as the operator created.
  spaces: "many",
});

function supportsPaneLayoutVersion(version: string, protocol: number): boolean {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(version);
  if (!match || protocol < 22) return false;
  const parts = match.slice(1).map(Number);
  return parts[0]! > 0 || parts[1]! > 9 || (parts[1] === 9 && parts[2]! >= 3);
}

/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof */
function object(value: JsonValue): JsonObject {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("layout.protocol: expected object");
  // SAFETY: JsonValue objects have string keys; the null/array cases are rejected immediately above.
  return value as JsonObject;
}
function field(value: JsonObject, key: string): JsonValue {
  const result = value[key];
  if (result === undefined) throw new Error(`layout.protocol: missing ${key}`);
  return result;
}
function stringField(value: JsonObject, key: string): string {
  const result = field(value, key);
  if (typeof result !== "string" || result.length < 1 || result.length > 256 || Array.from(result).some((char) => char.charCodeAt(0) < 32)) throw new Error(`layout.protocol: invalid ${key}`);
  return result;
}
function paneIdField(value: JsonObject, key: string): string {
  const result = stringField(value, key);
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(result)) throw new Error(`layout.protocol: invalid ${key}`);
  return result;
}
function nullableStringField(value: JsonObject, key: string): string | null {
  const result = field(value, key);
  if (result === null) return null;
  if (typeof result !== "string" || result.length < 1 || result.length > 256 || Array.from(result).some((char) => char.charCodeAt(0) < 32)) throw new Error(`layout.protocol: invalid ${key}`);
  return result;
}
function booleanField(value: JsonObject, key: string): boolean {
  const result = field(value, key);
  if (typeof result !== "boolean") throw new Error(`layout.protocol: invalid ${key}`);
  return result;
}
function uint16Field(value: JsonObject, key: string): number {
  const result = field(value, key);
  if (typeof result !== "number" || !Number.isInteger(result) || result < 0 || result > 65_535) throw new Error(`layout.protocol: invalid ${key}`);
  return result;
}
function neutralRect(raw: JsonValue): MuxLayoutSnapshot["area"] {
  const value = object(raw);
  return { x: uint16Field(value, "x"), y: uint16Field(value, "y"), width: uint16Field(value, "width"), height: uint16Field(value, "height") };
}
function neutralLayout(raw: JsonValue): MuxLayoutSnapshot {
  const value = object(raw);
  const rawPanes = field(value, "panes");
  const rawSplits = field(value, "splits");
  if (!Array.isArray(rawPanes) || rawPanes.length > 64 || !Array.isArray(rawSplits) || rawSplits.length > 64) throw new Error("layout.protocol: invalid pane or split count");
  const panes = rawPanes.map((entry) => {
    const pane = object(entry);
    return { paneId: paneIdField(pane, "pane_id"), focused: booleanField(pane, "focused"), rect: neutralRect(field(pane, "rect")) };
  });
  const splits = rawSplits.map((entry) => {
    const split = object(entry);
    const direction = field(split, "direction");
    const ratio = field(split, "ratio");
    if ((direction !== "right" && direction !== "down") || typeof ratio !== "number" || !Number.isFinite(ratio) || ratio < 0 || ratio > 1) throw new Error("layout.protocol: invalid split");
    // SAFETY: the two-value check above excludes every other string/value.
    const splitDirection: "right" | "down" = direction as "right" | "down";
    return { id: paneIdField(split, "id"), direction: splitDirection, ratio, rect: neutralRect(field(split, "rect")) };
  });
  const workspaceId = paneIdField(value, "workspace_id");
  const tabId = paneIdField(value, "tab_id");
  const focusedPaneId = paneIdField(value, "focused_pane_id");
  if (!panes.some((pane) => pane.paneId === focusedPaneId) || new Set(panes.map((pane) => pane.paneId)).size !== panes.length) throw new Error("layout.protocol: inconsistent focused pane");
  return { workspaceId, tabId, zoomed: booleanField(value, "zoomed"), area: neutralRect(field(value, "area")), focusedPaneId, panes, splits };
}
function portableNode(raw: JsonValue, depth = 0, count = { value: 0 }): MuxPortableLayoutNode {
  if (depth > 12 || ++count.value > 64) throw new Error("layout.protocol: layout exceeds bounds");
  const value = object(raw);
  if (field(value, "type") === "pane") {
    if (!Object.keys(value).every((key) => ["type", "label", "cwd", "pane_id", "command", "env"].includes(key))) throw new Error("layout.protocol: unexpected pane field");
    const optionalText = (key: string, max: number): string | null => {
      const item = value[key];
      if (item === undefined || item === null) return null;
      if (typeof item !== "string" || item.length > max || Array.from(item).some((char) => char.charCodeAt(0) < 32)) throw new Error(`layout.protocol: invalid ${key}`);
      return item;
    };
    const paneId = optionalText("pane_id", 128);
    if (paneId !== null && !/^[A-Za-z0-9_.:-]{1,128}$/.test(paneId)) throw new Error("layout.protocol: invalid pane_id");
    let command: readonly string[] | null = null;
    if (value.command !== undefined && value.command !== null) {
      if (!Array.isArray(value.command) || value.command.length < 1 || value.command.length > 64 || value.command.some((arg) => typeof arg !== "string" || arg.length > 2048 || arg.includes("\u0000"))) throw new Error("layout.protocol: invalid command");
      // SAFETY: The guarded array contains only bounded, NUL-free strings.
      command = value.command as string[];
    }
    const envRaw = value.env === undefined ? {} : object(value.env);
    if (Object.keys(envRaw).length > 32) throw new Error("layout.protocol: invalid env");
    // SAFETY: A null-prototype record prevents decoded environment keys from mutating prototypes.
    const env: Record<string, string> = Object.create(null) as Record<string, string>;
    for (const [key, item] of Object.entries(envRaw)) {
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key) || ["__proto__", "prototype", "constructor", "HERDR_SOCKET_PATH", "HERDR_ENV", "HERDR_WORKSPACE_ID", "HERDR_TAB_ID", "HERDR_PANE_ID"].includes(key) || typeof item !== "string" || item.length > 1024 || item.includes("\u0000")) throw new Error("layout.protocol: invalid env");
      env[key] = item;
    }
    return { type: "pane", label: optionalText("label", 128), cwd: optionalText("cwd", 4096), paneId, command, env: Object.freeze(env) };
  }
  if (field(value, "type") !== "split" || !Object.keys(value).every((key) => ["type", "direction", "ratio", "first", "second"].includes(key))) throw new Error("layout.protocol: invalid node");
  const direction = field(value, "direction");
  const splitRatio = field(value, "ratio");
  if ((direction !== "right" && direction !== "down") || typeof splitRatio !== "number" || !Number.isFinite(splitRatio) || splitRatio <= 0 || splitRatio >= 1 || Math.fround(splitRatio) <= 0 || Math.fround(splitRatio) >= 1) throw new Error("layout.protocol: invalid ratio");
  return { type: "split", direction, ratio: splitRatio, first: portableNode(field(value, "first"), depth + 1, count), second: portableNode(field(value, "second"), depth + 1, count) };
}
function portableLayout(raw: JsonValue): MuxPortableLayout {
  const value = object(raw);
  const workspaceId = paneIdField(value, "workspace_id");
  const tabId = paneIdField(value, "tab_id");
  const focusedPaneId = paneIdField(value, "focused_pane_id");
  const zoomed = booleanField(value, "zoomed");
  const root = portableNode(field(value, "root"));
  return { workspaceId, tabId, focusedPaneId, zoomed, root };
}
interface OptionalApplyParams { workspace_id?: string; tab_id?: string; tab_label?: string }
interface OptionalRatioParams { pane_id?: string }
function portableNodeWire(node: MuxPortableLayoutNode): HerdrLayoutNode {
  if (node.type === "split") return { type: "split", direction: node.direction, ratio: node.ratio, first: portableNodeWire(node.first), second: portableNodeWire(node.second) };
  return { type: "pane", label: node.label, cwd: node.cwd, pane_id: node.paneId, command: node.command, env: node.env };
}
function portablePaneIds(node: MuxPortableLayoutNode): string[] {
  return node.type === "pane" ? (node.paneId === null ? [] : [node.paneId]) : [...portablePaneIds(node.first), ...portablePaneIds(node.second)];
}
function portableTreeMatches(actual: MuxPortableLayoutNode, expected: MuxPortableLayoutNode): boolean {
  if (actual.type !== expected.type) return false;
  if (actual.type === "split" && expected.type === "split") return actual.direction === expected.direction && actual.ratio === expected.ratio &&
    portableTreeMatches(actual.first, expected.first) && portableTreeMatches(actual.second, expected.second);
  if (actual.type !== "pane" || expected.type !== "pane") return false;
  const actualEnv = Object.entries(actual.env).toSorted(([a], [b]) => a.localeCompare(b));
  const expectedEnv = Object.entries(expected.env).toSorted(([a], [b]) => a.localeCompare(b));
  return actual.label === expected.label && actual.cwd === expected.cwd && JSON.stringify(actual.command) === JSON.stringify(expected.command) &&
    JSON.stringify(actualEnv) === JSON.stringify(expectedEnv);
}
function portableSplitRatio(root: MuxPortableLayoutNode, path: readonly boolean[]): number | null {
  let node = root;
  for (const bit of path) {
    if (node.type !== "split") return null;
    node = bit ? node.second : node.first;
  }
  return node.type === "split" ? node.ratio : null;
}
function layoutRefusal(error: unknown): MuxRefusalOutcome {
  const detail = reason(error);
  if (GONE_CODES.some((code) => detail.includes(code)) || ["target_pane_not_found", "tab_not_found", "workspace_not_found"].some((code) => detail.includes(code))) return muxGone(detail);
  if (["invalid_layout", "invalid_ratio", "invalid_target", "split_not_found", "invalid_pane_swap", "pane_layout_unavailable", "pane_move_failed", "confirmation_required", "unknown variant"].some((code) => detail.includes(code))) return muxRefused(detail);
  return muxUnreachable(detail);
}
function moveDestinationWire(destination: MuxMoveDestination): HerdrMoveDestination {
  if (destination.type === "tab") return { type: "tab", tab_id: destination.tabId, split: destination.split, target_pane_id: destination.targetPaneId, ratio: destination.ratio };
  if (destination.type === "new_tab") return { type: "new_tab", workspace_id: destination.workspaceId, label: destination.label };
  return { type: "new_workspace", label: destination.label, tab_label: destination.tabLabel };
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof */

/** `MuxTarget.options` key carrying which local dialer to use. Opaque to the registry, by rule. */
export const HERDR_DIAL_MODE_OPTION = "dialMode";

/**
 * A `dialMode` option, or `auto` when the target carries none.
 *
 * Checked against the three names rather than trusted, because `MuxTarget.options` is an untyped
 * string map by design — the registry passes it through without reading a key.
 */
export function dialModeOf(options: Readonly<Record<string, string>>): DialMode {
  const mode = options[HERDR_DIAL_MODE_OPTION];
  return mode === "bun" || mode === "net" ? mode : "auto";
}

/** What a rejected call says, when the rejection is an exception rather than an answer. */
function reason<T>(err: T): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * The Herdr error codes that mean "it existed and does not any more" (HERDR_API.md § Errors).
 *
 * Matched on the message because `client.ts` folds Herdr's `{code, message}` into one Error — the
 * code is the first token of what it throws (`herdr pane.close: pane_not_found: …`). This is the one
 * transport failure the contract distinguishes: `gone` tells the operator their screen is stale and
 * to go back, where `unreachable` invites a retry that can only fail the same way (types.ts).
 */
const GONE_CODES: readonly string[] = ["pane_not_found", "tab_not_found", "workspace_not_found"];

/** Which refusal an exception off the socket is: a dead pane/tab, or a multiplexer that did not answer. */
function transportRefusal<T>(err: T): MuxRefusalOutcome {
  const detail = reason(err);
  if (err instanceof WriteAdmissionRetired && !err.bytesMayHaveBeenSent) return muxRefused(detail);
  if (GONE_CODES.some((code) => detail.includes(code))) return muxGone(detail);
  if (["workspace_group_close_required", "confirmation_required", "invalid_workspace_order", "invalid_insert_index"].some((code) => detail.includes(code))) return muxRefused(detail);
  return muxUnreachable(detail);
}

/**
 * The Herdr worktree codes that are the OPERATOR'S problem, not the transport's.
 *
 * Matched on the message for the same reason {@link GONE_CODES} is: `client.ts` folds Herdr's
 * `{code, message}` into one Error whose text opens with the code. Every code below was seen
 * first-hand on herdr 0.8.2 (2026-08-28) except the three marked, which come from herdr's own
 * source (`src/app/api/worktrees.rs`) — they are classified, never declared, so an unprobed one
 * still lands as a refusal the operator can read rather than a retry that cannot help.
 */
const WORKTREE_REFUSAL_CODES: readonly string[] = [
  "dirty_worktree_requires_force",
  "not_git_worktree",
  "worktree_create_failed",
  "worktree_open_failed",
  "worktree_list_failed",
  "worktree_remove_failed",
  "repository_not_trusted",
  "ownership_refused",
  "ambiguous_worktree_branch", // source-only
  "not_linked_worktree", // source-only
  "worktree_operation_in_progress", // source-only
  "stale_worktree_operation", // source-only
];

/** A checkout that is not there any more is `gone`, exactly like a pane that is not. */
const WORKTREE_GONE_CODES: readonly string[] = ["worktree_not_found"];

/**
 * Which refusal a worktree exception is.
 *
 * Three buckets, and the split matters: `gone` says re-read, `refused` says read the sentence,
 * `unreachable` says try again. Folding the middle one into `unreachable` would invite a retry of
 * something a retry cannot fix — a dirty checkout stays dirty.
 */
function worktreeRefusal<T>(err: T): MuxRefusalOutcome {
  const detail = reason(err);
  if (err instanceof WriteAdmissionRetired && !err.bytesMayHaveBeenSent) return muxRefused(detail);
  if (WORKTREE_GONE_CODES.some((code) => detail.includes(code))) return muxGone(detail);
  if (GONE_CODES.some((code) => detail.includes(code))) return muxGone(detail);
  if (WORKTREE_REFUSAL_CODES.some((code) => detail.includes(code))) return muxRefused(detail);
  return muxUnreachable(detail);
}

/**
 * The repo one workspace sits in, as {@link HerdrMux} settled it. `null` in a {@link RepoEntry} is
 * "no repo", which is the fail-closed answer: no repo, no worktree rows (MuxSpace.repoRoot).
 */
interface WorkspaceRepo {
  readonly repoRoot: string;
  readonly isWorktree: boolean;
  /** The checkout the workspace sits in, when Herdr names it; see {@link workspaceRepoOf}. */
  readonly folder?: string;
}

/** One cached repo lookup. `retryAt` is Infinity for an answer, a time for a lookup that failed. */
interface RepoEntry {
  readonly repo: WorkspaceRepo | null;
  readonly retryAt: number;
}

/**
 * How long a FAILED repo lookup stands before the next snapshot asks again.
 *
 * Only a failure — a timeout, a closed socket, a server without the method — is retried, and not
 * on every poll: the lookup runs `git worktree list` on the host, and a repo that timed out once
 * would time out on every poll at the cost of the whole snapshot's latency.
 */
export const REPO_LOOKUP_RETRY_MS = 60_000;

/**
 * Herdr's `worktree.list` answer for one workspace, in the port's words.
 *
 * `repoRoot` is the answer's `source.repo_root`. Which checkout the workspace is, is the entry that
 * names the workspace as its `open_workspace_id` — NOT `source.source_checkout_path`, which names
 * the repo's main checkout even when asked from inside a linked worktree (probed 2026-10-07, herdr
 * 0.9.3). No such entry reads as the repo's own checkout with no folder: the conservative side,
 * since a worktree row only ever nests under `isWorktree: false`.
 */
export function workspaceRepoOf(workspaceId: string, listing: WireWorktreeListing): WorkspaceRepo | null {
  const repoRoot = listing.source.repo_root;
  if (repoRoot.length === 0) return null;
  const own = listing.worktrees.find((worktree) => worktree.open_workspace_id === workspaceId);
  if (own === undefined) return { repoRoot, isWorktree: false };
  return { repoRoot, isWorktree: own.is_linked_worktree, folder: own.path };
}

/** One Herdr worktree record in the port's words. */
function toMuxWorktree(raw: WireWorktree): MuxWorktree {
  return {
    path: raw.path,
    branch: raw.branch ?? null,
    openSpaceId: raw.open_workspace_id ?? null,
    linked: raw.is_linked_worktree,
    prunable: raw.is_prunable,
  };
}

export class HerdrMux implements MuxAdapter {
  readonly mux = HERDR_MUX;
  readonly logo = HERDR_LOGO_SVG;
  readonly hostControl = herdrHostControl;
  private paneLayoutReady = false;
  private layoutIdentity: string | null = null;
  private readonly legacyCapabilities = declareCapabilities({
    supports: HERDR_BASE_CAPABILITIES,
    unsupportedKeys: HERDR_UNSENDABLE_KEYS,
    topologyLatency: { kind: "push" },
    readLimits: { maxRequestedLines: 1_000 },
  });
  get capabilities(): typeof HERDR_CAPABILITIES | ReturnType<typeof declareCapabilities> {
    return this.paneLayoutReady ? HERDR_CAPABILITIES : this.legacyCapabilities;
  }

  // session.snapshot is the fast path; flipped off PERMANENTLY once a server proves it predates the
  // method (see snapshot()), after which every read uses the legacy three-call path. Adapter state
  // rather than engine state: which methods a server has is a fact about this multiplexer.
  private supportsSessionSnapshot = true;

  /**
   * Which repo each workspace sits in, by workspace id (herdr 0.9.3 dropped it from the record).
   *
   * THE CACHING RULE. A workspace is looked up when it first appears in a snapshot, and its answer
   * stands for as long as it stays: a repo, or `not_git_worktree` ("no repo"), is never asked again
   * on a poll. An entry goes when its workspace leaves the snapshot, and the whole map goes when the
   * event stream comes (back) up, since anything may have changed while Collie was not listening. A
   * lookup that FAILED is asked again after {@link REPO_LOOKUP_RETRY_MS}. A workspace whose record
   * still carries `worktree` (herdr 0.8.2) is never looked up at all.
   */
  private readonly repoByWorkspace = new Map<string, RepoEntry>();
  /** Lookups in flight, so two overlapping snapshots ask Herdr once. */
  private readonly repoLookups = new Map<string, Promise<void>>();

  /**
   * The client is the socket; `sessions` is where the OTHER sessions of this Herdr install are.
   *
   * Injected rather than read from the filesystem in here, so the whole translation layer stays
   * testable with no filesystem and no socket (`./sessions.ts` § herdrSessionSource). The factory
   * below builds the real one from this adapter's own endpoint.
   */
  constructor(
    private readonly client: HerdrRpc,
    private readonly sessions: () => readonly MuxSession[],
  ) {}

  /** Reachability for the connected/disconnected banner — one cheap list call. */
  async reachable(): Promise<boolean> {
    try {
      if (this.client.layoutHandshakeSample) {
        this.paneLayoutReady = false;
        this.layoutIdentity = null;
        const sample = await this.sampleLayoutRuntime();
        const supported = sample !== null && supportsPaneLayoutVersion(sample.handshake.version, sample.handshake.protocol);
        const identity = supported ? sample.identity : null;
        this.paneLayoutReady = supported && identity !== null;
        this.layoutIdentity = this.paneLayoutReady ? identity : null;
        // Missing layout evidence may still describe a healthy older runtime. It cannot prove
        // ordinary reachability: the production sample also returns null on a failed ping.
        return sample !== null || await this.client.ping();
      }
      this.paneLayoutReady = false;
      this.layoutIdentity = null;
      return await this.client.ping();
    } catch {
      this.paneLayoutReady = false;
      this.layoutIdentity = null;
      return false;
    }
  }

  /** A positive handshake plus an unchanged local endpoint witness is required for layout control. */
  async layoutRuntimeIdentity(): Promise<string | null> {
    if (!this.paneLayoutReady || !this.layoutIdentity) return null;
    try {
      const sample = await this.sampleLayoutRuntime();
      if (!sample || !supportsPaneLayoutVersion(sample.handshake.version, sample.handshake.protocol) || sample.identity !== this.layoutIdentity) throw new Error("layout runtime changed");
      return sample.identity;
    } catch {
      this.paneLayoutReady = false;
      this.layoutIdentity = null;
      return null;
    }
  }

  /** A positive handshake is bracketed by the same OS witness; protocol text alone is not identity. */
  private async sampleLayoutRuntime(): Promise<{ handshake: { version: string; protocol: number }; identity: string | null } | null> {
    if (!this.client.layoutHandshakeSample) return null;
    const sample = await this.client.layoutHandshakeSample();
    if (!sample) return null;
    if (!supportsPaneLayoutVersion(sample.handshake.version, sample.handshake.protocol)) return { handshake: sample.handshake, identity: null };
    if (this.client.layoutRuntimeIdentity?.() !== sample.identity) return null;
    return sample;
  }

  /**
   * The whole herd, preferring the single `session.snapshot` round-trip (herdr ≥ 0.7.2).
   *
   * Only an "unknown variant" error (the server predates the method) trips a PERMANENT fallback —
   * and the three list calls run in the SAME call so no poll is missed. Any other failure (timeout,
   * closed socket) is transient and PROPAGATES: `snapshot` is the contract's floor and has no
   * refusal shape, so a caller learns the multiplexer is unreachable the way it always did.
   */
  async snapshot(): Promise<MuxSnapshot> {
    const wire = await this.fetchWire();
    await this.settleRepos(wire.workspaces);
    const spaceById = new Map(wire.workspaces.map((w) => [w.workspace_id, w]));
    const tabById = new Map(wire.tabs.map((t) => [t.tab_id, t]));
    return {
      panes: wire.panes.map((p) => toMuxPane(p, spaceById, tabById)),
      spaces: wire.workspaces.map((w) => toMuxSpace(w, this.repoByWorkspace.get(w.workspace_id)?.repo ?? null)),
      tabs: wire.tabs.map(toMuxTab),
    };
  }

  async readLayout(paneId: string, request: MuxLayoutReadRequest = { kind: "layout" }): Promise<MuxOutcome<MuxLayoutRead>> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity || !this.client.readPaneLayout || (request.kind === "neighbor" && !this.client.readPaneNeighbor) || (request.kind === "edges" && !this.client.readPaneEdges)) return muxUnsupported("paneLayout", "This Herdr runtime does not declare pane layout APIs.");
    try {
      const raw = request.kind === "neighbor" ? await this.client.readPaneNeighbor!(paneId, request.direction) : request.kind === "edges" ? await this.client.readPaneEdges!(paneId) : await this.client.readPaneLayout(paneId);
      const response = object(raw);
      if (request.kind === "neighbor") {
        if (response.type !== "pane_neighbor") throw new Error("layout.protocol: invalid pane neighbor response");
        const row = object(field(response, "neighbor"));
        if (field(row, "direction") !== request.direction || stringField(row, "pane_id") !== paneId) throw new Error("layout.protocol: mismatched pane neighbor response");
        const value = { kind: "neighbor" as const, paneId, direction: request.direction, neighborPaneId: nullableStringField(row, "neighbor_pane_id"), layout: neutralLayout(field(row, "layout")) };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed during read");
        return muxOk(value);
      }
      if (request.kind === "edges") {
        if (response.type !== "pane_edges") throw new Error("layout.protocol: invalid pane edges response");
        const row = object(field(response, "edges"));
        if (stringField(row, "pane_id") !== paneId) throw new Error("layout.protocol: mismatched pane edges response");
        const value = { kind: "edges" as const, paneId, left: booleanField(row, "left"), right: booleanField(row, "right"), up: booleanField(row, "up"), down: booleanField(row, "down"), layout: neutralLayout(field(row, "layout")) };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed during read");
        return muxOk(value);
      }
      if (response.type !== "pane_layout") throw new Error("layout.protocol: invalid pane layout response");
      const value = { kind: "layout" as const, layout: neutralLayout(field(response, "layout")) };
      if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed during read");
      return muxOk(value);
    }
    catch (err) { this.paneLayoutReady = false; this.layoutIdentity = null; return layoutRefusal(err); }
  }

  async exportPortableLayout(target: { readonly tabId: string } | { readonly paneId: string }): Promise<MuxOutcome<MuxPortableLayout>> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity || !this.client.exportLayout) return muxUnsupported("portableLayouts", "This Herdr runtime does not declare portable layouts.");
    try {
      const raw = await this.client.exportLayout("tabId" in target ? { tab_id: target.tabId } : { pane_id: target.paneId });
      const response = object(raw);
      if (response.type !== "layout_export") throw new Error("layout.protocol: invalid export response");
      const value = portableLayout(field(response, "layout"));
      if (("tabId" in target && target.tabId !== value.tabId) || ("paneId" in target && !portablePaneIds(value.root).includes(target.paneId)) ||
          await this.layoutRuntimeIdentity() !== identity) throw new Error("layout.protocol: export target or runtime changed");
      return muxOk(value);
    } catch (error) { return layoutRefusal(error); }
  }

  async applyPortableLayout(request: MuxLayoutApplyRequest, finalAdmission: (runtimeIdentity: string, snapshot: MuxSnapshot) => boolean | Promise<boolean>): Promise<MuxOutcome<MuxLayoutCreated>> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity || !this.client.applyLayout) return muxUnsupported("portableLayouts", "This Herdr runtime does not declare portable layouts.");
    try {
      const before = await this.snapshot();
      if (await this.layoutRuntimeIdentity() !== identity || !await finalAdmission(identity, before)) return muxRefused("Layout plan or runtime changed before apply.");
      const optional: OptionalApplyParams = {};
      if (request.workspaceId !== null) optional.workspace_id = request.workspaceId;
      if (request.tabId !== null) optional.tab_id = request.tabId;
      if (request.tabLabel !== null) optional.tab_label = request.tabLabel;
      const params: Parameters<NonNullable<HerdrRpc["applyLayout"]>>[0] = { root: portableNodeWire(request.root), focus: false, ...optional };
      const response = object(await this.client.applyLayout(params));
      if (response.type !== "layout_apply") throw new Error("layout.protocol: invalid apply response");
      const layout = portableLayout(field(response, "layout"));
      const paneIds = portablePaneIds(layout.root);
      if (!paneIds.includes(layout.focusedPaneId) || paneIds.length < 1 || paneIds.length > 64 || new Set(paneIds).size !== paneIds.length ||
          layout.workspaceId !== request.workspaceId || (request.tabId !== null && layout.tabId !== request.tabId) ||
          !portableTreeMatches(layout.root, request.root)) throw new Error("layout.protocol: apply result does not match requested layout");
      if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after apply");
      return muxOk({ workspaceId: layout.workspaceId, tabId: layout.tabId, paneIds, focusedPaneId: layout.focusedPaneId });
    } catch (error) { return layoutRefusal(error); }
  }

  async setSplitRatio(request: MuxSplitRatioRequest, finalAdmission: (runtimeIdentity: string, snapshot: MuxSnapshot) => boolean | Promise<boolean>): Promise<MuxOutcome<MuxPortableLayout>> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity || !this.client.setSplitRatio) return muxUnsupported("portableLayouts", "This Herdr runtime does not declare split-ratio control.");
    try {
      const before = await this.snapshot();
      if (await this.layoutRuntimeIdentity() !== identity || !await finalAdmission(identity, before)) return muxRefused("Layout plan or runtime changed before ratio change.");
      const optional: OptionalRatioParams = {};
      if (request.paneId !== null) optional.pane_id = request.paneId;
      const params: Parameters<NonNullable<HerdrRpc["setSplitRatio"]>>[0] = {
        tab_id: request.tabId, path: request.path, ratio: request.ratio, ...optional,
      };
      const response = object(await this.client.setSplitRatio(params));
      if (response.type !== "layout_split_ratio_set") throw new Error("layout.protocol: invalid ratio response");
      const layout = portableLayout(field(response, "layout"));
      if (layout.tabId !== request.tabId || portableSplitRatio(layout.root, request.path) !== request.ratio || await this.layoutRuntimeIdentity() !== identity) throw new Error("layout.protocol: ratio target or requested effect does not match");
      return muxOk(layout);
    } catch (error) { return layoutRefusal(error); }
  }

  /** Read the exact mutation source/destination after async adapter preflight and before admission. */
  private async finalLayoutEvidence(action: MuxLayoutAction, identity: string,
    neighborLayout?: MuxLayoutRead): Promise<MuxLayoutFinalEvidence | null> {
    const readPaneLayout = this.client.readPaneLayout;
    if (!readPaneLayout) return null;
    const read = async (paneId: string): Promise<MuxLayoutRead | null> => {
      const response = object(await readPaneLayout.call(this.client, paneId));
      if (response.type !== "pane_layout") return null;
      const layout = neutralLayout(field(response, "layout"));
      if (!layout.panes.some((pane) => pane.paneId === paneId)) return null;
      return { kind: "layout", layout };
    };
    // Finish handshake preflight first. Every following await is part of the bounded witness,
    // never a later capability ping that can invalidate an already-read source layout.
    if (await this.layoutRuntimeIdentity() !== identity) return null;
    const revision = this.layoutEvidenceRevision;
    const before = await this.layoutSourceSnapshot(action);
    let destinationLayout: MuxLayoutRead | undefined;
    if (action.kind === "move" && action.destination.type === "tab") {
      destinationLayout = await read(action.destination.targetPaneId) ?? undefined;
      if (!destinationLayout || destinationLayout.layout.tabId !== action.destination.tabId) return null;
    }
    const after = await this.layoutSourceSnapshot(action);
    if (JSON.stringify(layoutSnapshotAuthority(before, action)) !== JSON.stringify(layoutSnapshotAuthority(after, action))) return null;
    // Source geometry is LAST, after destination, occupant/workspace metadata and handshake awaits.
    const sourceLayout = await read(action.paneId);
    if (!sourceLayout || (neighborLayout && JSON.stringify(neighborLayout.layout) !== JSON.stringify(sourceLayout.layout))) return null;
    if (this.layoutEvidenceRevision !== revision || !this.paneLayoutReady) return null;
    if (this.client.layoutRuntimeIdentity?.() !== identity) {
      this.paneLayoutReady = false; this.layoutIdentity = null;
      return null;
    }
    let evidence: MuxLayoutFinalEvidence = { sourceLayout, snapshot: after };
    if (destinationLayout) evidence = { ...evidence, destinationLayout };
    if (neighborLayout) evidence = { ...evidence, neighborLayout };
    return evidence;
  }

  private layoutEvidenceRevision = 0;

  /** Source facts only: no awaited repository enrichment after the vendor snapshot observation. */
  private async layoutSourceSnapshot(action: MuxLayoutAction): Promise<MuxSnapshot> {
    let selectedRepo: WorkspaceRepo | null = null;
    if (action.kind === "split" && action.cwdChoice?.kind === "workspace") {
      // Selected workspace cwd is an authority input, not the ordinary indefinitely cached UI repo.
      selectedRepo = workspaceRepoOf(action.cwdChoice.workspaceId, await this.client.workspaceWorktrees(action.cwdChoice.workspaceId));
    }
    const wire = await this.fetchWire();
    const spaces = new Map(wire.workspaces.map((space) => [space.workspace_id, space]));
    const tabs = new Map(wire.tabs.map((tab) => [tab.tab_id, tab]));
    return { panes: wire.panes.map((pane) => toMuxPane(pane, spaces, tabs)),
      spaces: wire.workspaces.map((space) => toMuxSpace(space,
        action.kind === "split" && action.cwdChoice?.kind === "workspace" && space.workspace_id === action.cwdChoice.workspaceId ? selectedRepo :
          this.repoByWorkspace.get(space.workspace_id)?.repo ?? null)),
      tabs: wire.tabs.map(toMuxTab) };
  }

  async applyLayout(action: MuxLayoutAction, finalAdmission: MuxLayoutFinalAdmission): Promise<MuxOutcome<MuxLayoutMutation>> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity) {
      const capability = action.kind === "split" ? "paneSplit" : action.kind === "resize" ? "paneResize" : action.kind === "zoom" ? "paneZoom" : action.kind === "swap" ? "paneSwap" : action.kind === "move" ? "paneMove" : "paneDirectionalFocus";
      return muxUnsupported(capability, "This Herdr runtime does not declare pane layout APIs.");
    }
    if ((action.kind === "split" && (!this.client.splitPane || !this.client.readPaneLayout)) ||
      (action.kind === "resize" && !this.client.resizePane) || (action.kind === "zoom" && !this.client.zoomPane) ||
      (action.kind === "swap" && !this.client.swapPanes) || (action.kind === "move" && !this.client.movePane) ||
      (action.kind === "focus-direction" && !this.client.focusPaneDirection)) return muxUnreachable("The Herdr layout transport is unavailable.");
    try {
      let neighborLayoutEvidence: MuxLayoutRead | undefined;
      if (action.kind === "split") {
        const splitPane = this.client.splitPane;
        const readPaneLayout = this.client.readPaneLayout;
        const targetPaneId = action.resolvedTargetPaneId ?? action.targetPaneId;
        const workspaceId = action.resolvedWorkspaceId ?? action.workspaceId;
        if (!splitPane || !readPaneLayout || !workspaceId || targetPaneId !== action.paneId) return muxUnreachable("The Herdr layout transport is unavailable.");
        const splitParams: HerdrSplitPaneParams = {
          direction: action.direction, ratio: action.ratio, target_pane_id: targetPaneId,
          workspace_id: workspaceId, env: { ...action.env }, focus: false, right_click: "herdr" };
        if (action.cwd !== null) splitParams.cwd = action.cwd;
        const dispatchIdentity = await this.layoutRuntimeIdentity();
        if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
        const evidence = await this.finalLayoutEvidence(action, dispatchIdentity);
        if (!evidence || !finalAdmission(action, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
        const raw = await splitPane.call(this.client, splitParams);
        const response = object(raw);
        if (response.type !== "pane_info") throw new Error("layout.protocol: invalid pane split response");
        const pane = object(field(response, "pane"));
        const paneId = paneIdField(pane, "pane_id");
        if (paneId === action.paneId || paneIdField(pane, "workspace_id") !== workspaceId ||
            (action.resolvedSourceTabId !== undefined && paneIdField(pane, "tab_id") !== action.resolvedSourceTabId)) throw new Error("layout.protocol: pane split target mismatch");
        const layoutResponse = object(await readPaneLayout.call(this.client, paneId));
        if (layoutResponse.type !== "pane_layout") throw new Error("layout.protocol: invalid pane layout response");
        const layout = neutralLayout(field(layoutResponse, "layout"));
        if (layout.workspaceId !== workspaceId || (action.resolvedSourceTabId !== undefined && layout.tabId !== action.resolvedSourceTabId) ||
            !layout.panes.some((row) => row.paneId === paneId) || !layout.panes.some((row) => row.paneId === action.paneId)) throw new Error("layout.protocol: split pane absent from target layout");
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
        return muxOk({ kind: "split", paneId, layout });
      }
      if (action.kind === "resize") {
        const resizePane = this.client.resizePane;
        if (!resizePane) return muxUnreachable("The Herdr layout transport is unavailable.");
        const dispatchIdentity = await this.layoutRuntimeIdentity();
        if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
        const evidence = await this.finalLayoutEvidence(action, dispatchIdentity);
        if (!evidence || !finalAdmission(action, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
        const response = object(await resizePane.call(this.client, action.paneId, action.direction, action.amount));
        if (response.type !== "pane_resize") throw new Error("layout.protocol: invalid pane resize variant");
        const row = object(field(response, "resize"));
        const layout = neutralLayout(field(row, "layout"));
        if (paneIdField(row, "pane_id") !== action.paneId || !layout.panes.some((pane) => pane.paneId === action.paneId) || paneIdField(row, "focused_pane_id") !== layout.focusedPaneId) throw new Error("layout.protocol: resize identity mismatch");
        const value = { kind: "resize" as const, changed: booleanField(row, "changed"), layout };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
        return muxOk(value);
      }
      if (action.kind === "zoom") {
        const zoomPane = this.client.zoomPane;
        if (!zoomPane) return muxUnreachable("The Herdr layout transport is unavailable.");
        const dispatchIdentity = await this.layoutRuntimeIdentity();
        if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
        const evidence = await this.finalLayoutEvidence(action, dispatchIdentity);
        if (!evidence || !finalAdmission(action, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
        const response = object(await zoomPane.call(this.client, action.paneId, action.mode));
        if (response.type !== "pane_zoom") throw new Error("layout.protocol: invalid pane zoom variant");
        const row = object(field(response, "zoom"));
        const layout = neutralLayout(field(row, "layout"));
        if (paneIdField(row, "pane_id") !== action.paneId || paneIdField(row, "focused_pane_id") !== layout.focusedPaneId || !layout.panes.some((pane) => pane.paneId === action.paneId)) throw new Error("layout.protocol: zoom identity mismatch");
        const changed = booleanField(row, "changed");
        const zoomed = booleanField(row, "zoomed");
        const zoomChanged = booleanField(row, "zoom_changed");
        const focusChanged = booleanField(row, "focus_changed");
        if (zoomChanged !== changed || focusChanged || zoomed !== layout.zoomed ||
            (action.mode === "on" && !zoomed) || (action.mode === "off" && zoomed)) throw new Error("layout.protocol: zoom effect mismatch");
        const value = { kind: "zoom" as const, changed, zoomed, layout };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
        return muxOk(value);
      }
      if (action.kind === "swap") {
        const swapPanes = this.client.swapPanes;
        if (!swapPanes) return muxUnreachable("The Herdr layout transport is unavailable.");
        let expectedTarget = action.resolvedTargetPaneId ?? action.targetPaneId;
        if (action.direction !== null) {
          const adjacent = await this.client.readPaneNeighbor?.(action.paneId, action.direction);
          if (!adjacent) throw new Error("layout.protocol: missing directional swap evidence");
          const neighborResponse = object(adjacent);
          if (neighborResponse.type !== "pane_neighbor") throw new Error("layout.protocol: invalid directional swap evidence");
          const neighbor = object(field(neighborResponse, "neighbor"));
          const neighborLayout = neutralLayout(field(neighbor, "layout"));
          if (stringField(neighbor, "pane_id") !== action.paneId || stringField(neighbor, "direction") !== action.direction) throw new Error("layout.protocol: stale directional swap evidence");
          expectedTarget = nullableStringField(neighbor, "neighbor_pane_id");
          if (!expectedTarget) return muxRefused("There is no pane in that direction.");
          if (!neighborLayout.panes.some((pane) => pane.paneId === expectedTarget) || !neighborLayout.panes.some((pane) => pane.paneId === action.paneId)) throw new Error("layout.protocol: neighbor absent from layout evidence");
          neighborLayoutEvidence = { kind: "layout", layout: neighborLayout };
        }
        const prepared = expectedTarget === null ? action : { ...action, resolvedTargetPaneId: expectedTarget };
        const dispatchIdentity = await this.layoutRuntimeIdentity();
        if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
        const evidence = await this.finalLayoutEvidence(prepared, dispatchIdentity, neighborLayoutEvidence);
        if (!evidence || !finalAdmission(prepared, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
        const response = object(await swapPanes.call(this.client, { pane_id: action.paneId, direction: action.direction, source_pane_id: action.sourcePaneId, target_pane_id: action.targetPaneId }));
        if (response.type !== "pane_swap") throw new Error("layout.protocol: invalid pane swap variant");
        const row = object(field(response, "swap"));
        const layout = neutralLayout(field(row, "layout"));
        const changed = booleanField(row, "changed");
        if (paneIdField(row, "source_pane_id") !== (action.sourcePaneId ?? action.paneId) || !layout.panes.some((pane) => pane.paneId === action.paneId) || paneIdField(row, "focused_pane_id") !== layout.focusedPaneId) throw new Error("layout.protocol: swap identity mismatch");
        const targetWire = row.target_pane_id === undefined ? null : nullableStringField(row, "target_pane_id");
        if ((targetWire !== null && targetWire !== expectedTarget) || (changed && expectedTarget !== null && !layout.panes.some((pane) => pane.paneId === expectedTarget)) ||
            (changed && action.resolvedTargetPaneId !== undefined && action.resolvedTargetPaneId !== expectedTarget)) throw new Error("layout.protocol: swap target mismatch");
        const value = { kind: "swap" as const, changed, layout };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
        return muxOk(value);
      }
      if (action.kind === "move") {
        const movePane = this.client.movePane;
        if (!movePane) return muxUnreachable("The Herdr layout transport is unavailable.");
        const dispatchIdentity = await this.layoutRuntimeIdentity();
        if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
        const evidence = await this.finalLayoutEvidence(action, dispatchIdentity);
        if (!evidence || !finalAdmission(action, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
        const selectedIncarnation = action.transfer ? evidence.snapshot.panes.find((item) => item.paneId === action.paneId)?.terminalIncarnation : undefined;
        if (action.transfer && !selectedIncarnation) return muxRefused("The selected process identity is unavailable.");
        const beforeTabIds = new Set(evidence.snapshot.tabs.map((item) => item.tabId));
        const beforeWorkspaceIds = new Set(evidence.snapshot.spaces.map((item) => item.spaceId));
        const response = object(await movePane.call(this.client, { pane_id: action.paneId, destination: moveDestinationWire(action.destination), focus: false }));
        if (response.type !== "pane_move") throw new Error("layout.protocol: invalid pane move variant");
        const row = object(field(response, "move_result"));
        const pane = object(field(row, "pane"));
        const paneId = paneIdField(pane, "pane_id");
        const previousPaneId = paneIdField(row, "previous_pane_id");
        const previousTabId = paneIdField(row, "previous_tab_id");
        const previousWorkspaceId = paneIdField(row, "previous_workspace_id");
        const layout = neutralLayout(field(row, "target_layout"));
        const changed = booleanField(row, "changed");
        if (previousPaneId !== action.paneId ||
            (action.resolvedSourceTabId !== undefined && previousTabId !== action.resolvedSourceTabId) ||
            (action.resolvedSourceWorkspaceId !== undefined && previousWorkspaceId !== action.resolvedSourceWorkspaceId) ||
            (changed ? paneId === previousPaneId || !layout.panes.some((item) => item.paneId === paneId) : paneId !== previousPaneId) ||
            paneIdField(pane, "tab_id") !== layout.tabId || paneIdField(pane, "workspace_id") !== layout.workspaceId || (!action.transfer && paneIdField(row, "focused_pane_id") !== layout.focusedPaneId)) throw new Error("layout.protocol: move result does not match source or target layout");
        const destination = action.destination;
        if (destination.type === "tab" && (layout.tabId !== destination.tabId || !layout.panes.some((item) => item.paneId === destination.targetPaneId))) throw new Error("layout.protocol: move destination tab mismatch");
        if (action.destination.type === "new_tab" && layout.workspaceId !== action.destination.workspaceId) throw new Error("layout.protocol: move destination workspace mismatch");
        const afterSnapshot = action.transfer ? await this.layoutSourceSnapshot(action) : undefined;
        const movedPane = afterSnapshot?.panes.find((item) => item.paneId === paneId);
        const reportedFocus = paneIdField(row, "focused_pane_id");
        if (action.transfer && (!changed || !movedPane || movedPane.terminalIncarnation !== selectedIncarnation ||
            movedPane.tabId !== layout.tabId || movedPane.spaceId !== layout.workspaceId ||
            afterSnapshot?.panes.some((item) => item.paneId === action.paneId) ||
            afterSnapshot?.panes.some((item) => item.focused) && afterSnapshot.panes.find((item) => item.focused)?.paneId !== reportedFocus ||
            (!afterSnapshot?.panes.some((item) => item.focused) && reportedFocus !== layout.focusedPaneId))) throw new Error("layout.protocol: transferred process identity did not survive pane.move");
        const transferResult = action.transfer && afterSnapshot ? {
          createdTabId: beforeTabIds.has(layout.tabId) ? undefined : layout.tabId,
          createdWorkspaceId: beforeWorkspaceIds.has(layout.workspaceId) ? undefined : layout.workspaceId,
          closedTabId: afterSnapshot.tabs.some((item) => item.tabId === previousTabId) ? undefined : previousTabId,
          closedWorkspaceId: afterSnapshot.spaces.some((item) => item.spaceId === previousWorkspaceId) ? undefined : previousWorkspaceId,
        } : {};
        const value = { kind: "move" as const, changed, paneId, previousPaneId, previousTabId, previousWorkspaceId, layout, ...transferResult };
        if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
        return muxOk(value);
      }
      const focusPaneDirection = this.client.focusPaneDirection;
      if (!focusPaneDirection) return muxUnreachable("The Herdr layout transport is unavailable.");
      const dispatchIdentity = await this.layoutRuntimeIdentity();
      if (!dispatchIdentity || dispatchIdentity !== identity) return muxUnreachable("The Herdr runtime changed before layout dispatch.");
      const evidence = await this.finalLayoutEvidence(action, dispatchIdentity);
      if (!evidence || !finalAdmission(action, dispatchIdentity, evidence)) return muxRefused("Layout authority or target evidence changed before dispatch.");
      const response = object(await focusPaneDirection.call(this.client, action.paneId, action.direction));
      if (response.type !== "pane_focus_direction") throw new Error("layout.protocol: invalid pane direction variant");
      const row = object(field(response, "focus"));
      if (paneIdField(row, "source_pane_id") !== action.paneId) throw new Error("layout.protocol: direction source mismatch");
      const changed = booleanField(row, "changed");
      const paneId = nullableStringField(row, "focused_pane_id");
      if (paneId === null) {
        if (changed) throw new Error("layout.protocol: changed direction result has no focused pane");
        return muxRefused("There is no pane in that direction.");
      }
      const layout = neutralLayout(field(row, "layout"));
      if (!layout.panes.some((pane) => pane.paneId === action.paneId) || !layout.panes.some((pane) => pane.paneId === paneId) || layout.focusedPaneId !== paneId) throw new Error("layout.protocol: direction result mismatch");
      if (action.resolvedTargetPaneId !== undefined && paneId !== action.resolvedTargetPaneId)
        throw new Error("layout.protocol: direction result does not match planned neighbor");
      if (changed && paneId === action.paneId) throw new Error("layout.protocol: direction result did not focus a neighbor");
      const value = { kind: "focus-direction" as const, changed, paneId, layout };
      if (await this.layoutRuntimeIdentity() !== identity) throw new Error("layout runtime changed after dispatch");
      return muxOk(value);
    } catch (err) { this.paneLayoutReady = false; this.layoutIdentity = null; return layoutRefusal(err); }
  }

  /**
   * Make {@link repoByWorkspace} answer for every workspace in this snapshot, by the caching rule
   * written there. Never throws: the repo is a fact ON a space, and a lookup that fails leaves the
   * space without one rather than failing the snapshot it rides on.
   */
  private async settleRepos(workspaces: readonly WireWorkspace[]): Promise<void> {
    const live = new Set(workspaces.map((w) => w.workspace_id));
    for (const id of this.repoByWorkspace.keys()) if (!live.has(id)) this.repoByWorkspace.delete(id);
    const now = Date.now();
    const pending: Promise<void>[] = [];
    for (const workspace of workspaces) {
      // herdr 0.8.2 carries the repo on the record; no call needed (toMuxSpace reads it).
      if (workspace.worktree?.repo_root !== undefined) continue;
      const id = workspace.workspace_id;
      const cached = this.repoByWorkspace.get(id);
      if (cached !== undefined && now < cached.retryAt) continue;
      pending.push(this.lookupRepo(id));
    }
    await Promise.all(pending);
  }

  /** One workspace's `worktree.list`, deduplicated while in flight, into the cache. */
  private lookupRepo(id: string): Promise<void> {
    const inFlight = this.repoLookups.get(id);
    if (inFlight !== undefined) return inFlight;
    const lookup = this.readRepo(id).finally(() => this.repoLookups.delete(id));
    this.repoLookups.set(id, lookup);
    return lookup;
  }

  /** The lookup itself. Every outcome lands in the cache or leaves it; nothing is thrown. */
  private async readRepo(id: string): Promise<void> {
    try {
      const listing = await this.client.workspaceWorktrees(id);
      this.repoByWorkspace.set(id, { repo: workspaceRepoOf(id, listing), retryAt: Infinity });
    } catch (err) {
      const detail = reason(err);
      // Closed between the list and the lookup: the next snapshot no longer has it either.
      if (detail.includes("workspace_not_found")) this.repoByWorkspace.delete(id);
      // A folder outside Git — an answer, and the fail-closed one.
      else if (detail.includes("not_git_worktree")) this.repoByWorkspace.set(id, { repo: null, retryAt: Infinity });
      else this.repoByWorkspace.set(id, { repo: null, retryAt: Date.now() + REPO_LOOKUP_RETRY_MS });
    }
  }

  /**
   * A no-op that resolves, and it is the honest implementation rather than a stub.
   *
   * {@link snapshot} is one fresh RPC every time — the topology is never cached on this side; only
   * each workspace's repo is (see {@link repoByWorkspace}) — so "the very next snapshot reflects the
   * current topology" is already true before this is called. And the
   * watch is a real event stream, not a census, so there is no interval to pull back to a floor.
   * Spending a round trip here would buy nothing and would put load on the socket that types into
   * the operator's terminals.
   */
  refresh(): Promise<void> {
    return Promise.resolve();
  }

  private async fetchWire(): Promise<{
    workspaces: WireWorkspace[];
    panes: WirePane[];
    tabs: WireTab[];
  }> {
    if (this.supportsSessionSnapshot) {
      try {
        const snap = await this.client.sessionSnapshot();
        return { workspaces: snap.workspaces, panes: snap.panes, tabs: snap.tabs };
      } catch (err) {
        if (!(err instanceof Error && err.message.includes("unknown variant"))) throw err;
        this.supportsSessionSnapshot = false;
        console.log("[state] herdr predates session.snapshot — using list-call polling");
      }
    }
    const [workspaces, panes, tabs] = await Promise.all([
      this.client.listWorkspaces(),
      this.client.listPanes(),
      this.client.listTabs(),
    ]);
    return { workspaces, panes, tabs };
  }

  /**
   * One pane's rendered screen.
   *
   * `scope` picks Herdr's read source and `styling` its format, and the pairing is not cosmetic: a
   * `recent` read in `text` format makes Herdr harvest the pages above an alt-screen pane by driving
   * the agent's own scroll interface — the operator watches their terminal jump and snap back. The
   * mirror asks for `recent` + `preserve`; the session-name scrape asks for `viewport` + `preserve`.
   * See HERDR_API.md → `pane.read` and the note above SESSION_NAME_READ_LINES.
   */
  async readGrid(paneId: string, request: MuxGridRequest): Promise<MuxOutcome<MuxGrid>> {
    try {
      const read = await this.client.readPane(
        paneId,
        request.scope === "viewport" ? "visible" : "recent",
        request.lines,
        request.styling === "strip" ? "text" : "ansi",
      );
      return muxOk({
        paneId: read.pane_id,
        text: read.text,
        truncated: read.truncated,
        revision: read.revision,
      });
    } catch (err) {
      return transportRefusal(err);
    }
  }

  /** Sensitive process metadata, read for an explicit pane and fenced by its runtime terminal id. */
  async processInfo(paneId: string): Promise<MuxOutcome<MuxPaneProcessInfo>> {
    try {
      const before = (await this.client.listPanes()).find((pane) => pane.pane_id === paneId);
      if (!before) return muxGone("The pane no longer exists.");
      const terminalId = before.terminal_id;
      const raw = await this.client.processInfo(paneId);
      const after = (await this.client.listPanes()).find((pane) => pane.pane_id === paneId);
      if (!after || after.terminal_id !== terminalId || raw.pane_id !== paneId) {
        return muxGone("The pane occupant changed while process details were being read.");
      }
      const included = raw.foreground_processes.slice(0, 64);
      let remaining = MAX_PROCESS_INFO_TEXT_CHARS;
      let truncated = raw.truncated === true || raw.foreground_processes.length > 64 || included.some((process) => (process.argv?.length ?? 0) > 64) ||
        (raw.tty !== undefined && raw.tty !== null && raw.tty.length > 4096) ||
        (after.foreground_cwd !== undefined && after.foreground_cwd.length > 4096) ||
        (after.restore_error !== undefined && after.restore_error !== null && after.restore_error.length > 4096);
      const clip = (value: string, limit: number): string => {
        const redacted = redactText(value);
        const length = Math.min(redacted.length, limit, remaining);
        if (length < redacted.length) truncated = true;
        remaining -= length;
        return redacted.slice(0, length);
      };
      const processes: BoundedProcessInfoRow[] = included.map((process) => {
        const bounded: BoundedProcessInfoRow = {
          pid: process.pid,
          name: clip(process.name, 256),
        };
        if (process.argv !== undefined && process.argv !== null) bounded.argv = process.argv.slice(0, 64).map((arg) => clip(arg, 2048));
        if (process.argv0 !== undefined && process.argv0 !== null) bounded.argv0 = clip(process.argv0, 2048);
        if (process.cmdline !== undefined && process.cmdline !== null) bounded.cmdline = clip(process.cmdline, 8192);
        if (process.cwd !== undefined && process.cwd !== null) bounded.cwd = clip(process.cwd, 4096);
        return bounded;
      });
      const value: BoundedProcessInfo = { paneId, processes, truncated };
      if (raw.shell_pid !== undefined && raw.shell_pid !== null) value.shellPid = raw.shell_pid;
      if (raw.foreground_process_group_id !== undefined && raw.foreground_process_group_id !== null) value.foregroundProcessGroupId = raw.foreground_process_group_id;
      if (after.foreground_cwd !== undefined) value.foregroundCwd = clip(after.foreground_cwd, 4096);
      if (after.restore_error !== undefined && after.restore_error !== null) value.restoreError = clip(after.restore_error, 4096);
      if (raw.tty !== undefined && raw.tty !== null) value.tty = clip(raw.tty, 4096);
      value.truncated = truncated;
      return muxOk(value);
    } catch (err) {
      return transportRefusal(err);
    }
  }

  /** Read the two vendor views and join on the same public pane id and terminal occupant. */
  async agentIdentity(paneId: string): Promise<MuxOutcome<MuxAgentIdentity>> {
    try {
      const before = await this.client.getPane(paneId);
      const agent = await this.client.getAgent(paneId);
      const after = await this.client.getPane(paneId);
      if (before.terminal_id !== after.terminal_id || before.pane_id !== after.pane_id) {
        return muxGone("The pane occupant changed while agent identity was being read.");
      }
      if (
        (agent.agent ?? null) !== (after.agent ?? null) ||
        agent.agent_status !== after.agent_status ||
        !sameWireSession(agent.agent_session, after.agent_session)
      ) {
        return muxGone("The agent identity changed while its details were being read.");
      }
      const session = agent.agent_session ?? after.agent_session ?? null;
      const validSession: NonNullable<MuxAgentIdentity["session"]> | undefined = session !== null && session.source && session.agent &&
        (session.kind === "id" || session.kind === "path") && session.value
        ? {
            source: safeAgentText(session.source, 80),
            agent: safeAgentText(session.agent, 80),
            kind: session.kind,
            value: safeAgentText(session.value, 2048),
          }
        : undefined;
      const displaySession = validSession && validSession.source !== "" && validSession.agent !== "" && validSession.value !== ""
        ? validSession
        : undefined;
      const identityBase = {
        paneId,
        agent: safeAgentText(agent.agent ?? after.agent ?? "", 256) || null,
        status: agent.agent_status,
        stateAuthority: { observed: false, source: null } satisfies MuxAgentIdentity["stateAuthority"],
        interactiveReady: agent.interactive_ready ?? null,
        launchPending: agent.launch_pending ?? null,
        stateChangeSeq: safeUint64(agent.state_change_seq),
        completionSeq: safeUint64(agent.completion_seq),
      };
      const identity: MuxAgentIdentity = displaySession === undefined
        ? identityBase
        : { ...identityBase, session: displaySession };
      return muxOk(identity);
    } catch (err) {
      return transportRefusal(err);
    }
  }

  /**
   * The same scrollback with soft wraps undone (`recent_unwrapped`), for URL repair.
   *
   * Read in the same escape-carrying form as `readGrid` on purpose: `ansi` is the format whose read
   * was never observed to harvest an alt-screen pane, which is the property that keeps the mirror
   * from scrolling somebody's terminal (HERDR_API.md → `pane.read`). Callers strip the styling;
   * this read exists only for the URLs in it. On an agent pane the unwrapped rows equal the wrapped
   * ones — the bridge asks for this only when a URL is actually split.
   */
  async readLogicalText(paneId: string, lines: number): Promise<MuxOutcome<string>> {
    try {
      const read = await this.client.readPane(paneId, "recent_unwrapped", lines, "ansi");
      return muxOk(read.text);
    } catch (err) {
      return transportRefusal(err);
    }
  }

  async typeText(paneId: string, text: string, finalAdmission?: (runtimeIdentity: string) => boolean): Promise<MuxAck> {
    const identity = this.client.layoutRuntimeIdentity?.();
    if (finalAdmission && (!identity || !finalAdmission(identity))) return muxRefused("worktree.authority_changed");
    return this.attempt(() => this.client.sendPaneText(paneId, text, finalAdmission && identity ? { runtimeIdentity: identity, finalAdmission } : undefined));
  }

  /**
   * Keys in the contract's spelling, translated and applied in order.
   *
   * A batch containing one chord Herdr cannot express sends NOTHING — the keys of one call are a
   * sequence, and delivering its front half is worse than delivering none of it (the pane would be
   * left mid-chord with no way for the caller to know where it stopped).
   */
  async sendKeys(paneId: string, keys: readonly string[], finalAdmission?: (runtimeIdentity: string) => boolean): Promise<MuxAck> {
    const translated: string[] = [];
    for (const key of keys) {
      const result = toHerdrKey(key);
      if (!result.ok) {
        return muxRefused(
          result.reason === "unsendable"
            ? `herdr cannot send ${key} — no key sends it (paging and edit keys); scroll the mirror instead`
            : `not a key: ${key}`,
        );
      }
      translated.push(result.key);
    }
    const identity = this.client.layoutRuntimeIdentity?.();
    if (finalAdmission && (!identity || !finalAdmission(identity))) return muxRefused("worktree.authority_changed");
    return this.attempt(() => this.client.sendPaneKeys(paneId, translated, finalAdmission && identity ? { runtimeIdentity: identity, finalAdmission } : undefined));
  }

  async renamePane(paneId: string, label: string | null): Promise<MuxAck> {
    return this.attempt(() => this.client.renamePane(paneId, label));
  }

  async closePane(paneId: string): Promise<MuxAck> {
    return this.attempt(() => this.client.closePane(paneId));
  }

  /** Show this pane on the operator's own screen. One RPC moves pane, tab and workspace (client.ts). */
  async setFocus(paneId: string): Promise<MuxAck> {
    return this.attempt(() => this.client.focusPane(paneId));
  }

  /**
   * A new tab in a space, opening a fresh shell.
   *
   * `spaceLabel` comes back as the space id: `tab.create` returns the pane, not the workspace
   * record, and a second round-trip to learn a label the caller already has in its snapshot would be
   * a worse trade. That is the same fallback the route applied before this seam existed.
   */
  async createTab(request: MuxTabRequest): Promise<MuxOutcome<MuxCreatedPane>> {
    // Assigned, never spread: an absent label must stay absent (herdr stores "" literally).
    const opts: Parameters<HerdrClient["createTab"]>[1] = {};
    if (request.label !== undefined) opts.label = request.label;
    // A blank `cwd` is "none asked for" and is left off entirely, so the daemon opens where it would
    // have anyway (MUX_CONTRACT.md § Contract-owned rules, *A blank cwd*).
    const asked = requestedCwd(request.cwd);
    if (asked !== undefined) opts.cwd = asked;
    try {
      const created = await this.client.createTab(request.spaceId, opts);
      return muxOk(toCreatedPane(created));
    } catch (err) {
      return transportRefusal(err);
    }
  }

  async renameTab(tabId: string, label: string, finalAdmission?: () => boolean): Promise<MuxAck> {
    if (finalAdmission) return this.organizationAttempt("renameTab", finalAdmission, () => this.client.renameTab(tabId, label));
    return this.attempt(() => this.client.renameTab(tabId, label));
  }

  async closeTab(tabId: string, finalAdmission?: () => boolean): Promise<MuxAck> {
    if (finalAdmission) return this.organizationAttempt("closeTab", finalAdmission, () => this.client.closeTab(tabId));
    return this.attempt(() => this.client.closeTab(tabId));
  }

  async renameSpace(spaceId: string, label: string, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("workspaceRename", finalAdmission, () => this.client.renameWorkspace(spaceId, label));
  }
  async closeSpace(spaceId: string, closeGroup: boolean, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("workspaceClose", finalAdmission, () => this.client.closeWorkspace(spaceId, closeGroup));
  }
  async moveSpace(spaceId: string, insertIndex: number, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("workspaceMove", finalAdmission, () => this.client.moveWorkspace(spaceId, insertIndex));
  }
  async moveSpaceBlock(spaceIds: readonly string[], beforeSpaceId: string | null, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("workspaceMoveBlock", finalAdmission, () => this.client.moveWorkspaceBlock(spaceIds, beforeSpaceId));
  }
  async focusSpace(spaceId: string, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("workspaceFocus", finalAdmission, () => this.client.focusWorkspace(spaceId));
  }
  async moveTab(tabId: string, insertIndex: number, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("tabMove", finalAdmission, () => this.client.moveTab(tabId, insertIndex));
  }
  async focusTab(tabId: string, finalAdmission: () => boolean): Promise<MuxAck> {
    return this.organizationAttempt("tabFocus", finalAdmission, () => this.client.focusTab(tabId));
  }

  /** Organization calls are absent until the existing schema-22 runtime witness is current. */
  private async organizationAttempt(capability: import("../capabilities.ts").MuxCapability, finalAdmission: () => boolean, action: () => Promise<void>): Promise<MuxAck> {
    const identity = await this.layoutRuntimeIdentity();
    if (!identity) return muxUnsupported(capability, "This Herdr runtime has not proved the organization API contract.");
    if (!finalAdmission()) return muxRefused("Organization write authority changed before dispatch.");
    try {
      await action();
      if (await this.layoutRuntimeIdentity() !== identity) return muxUnreachable("Herdr runtime changed during organization action");
      return muxAck();
    } catch (err) {
      return transportRefusal(err);
    }
  }

  /** A new space with a fresh shell pane. Herdr returns the workspace record, so its label is real. */
  async createSpace(request: MuxSpaceRequest): Promise<MuxOutcome<MuxCreatedPane>> {
    // `cwd` is REQUIRED by herdr's own `workspace.create`, so there is no "leave it off" here. A
    // blank one is passed through as blank rather than replaced with a path Collie invented: the
    // daemon owns its default, and inventing one is the guess the contract forbids everywhere else.
    const opts: Parameters<HerdrClient["createWorkspace"]>[0] = { cwd: request.cwd };
    if (request.label !== undefined) opts.label = request.label;
    try {
      const created = await this.client.createWorkspace(opts);
      return muxOk(toCreatedPane(created));
    } catch (err) {
      return transportRefusal(err);
    }
  }

  async listWorktrees(scope: MuxWorktreeScope): Promise<MuxOutcome<readonly MuxWorktree[]>> {
    try {
      const raw = await this.client.listWorktrees(scope.repoRoot);
      return muxOk(raw.map(toMuxWorktree));
    } catch (err) {
      return worktreeRefusal(err);
    }
  }

  async createWorktree(request: MuxWorktreeCreateRequest, finalAdmission?: (runtimeIdentity: string) => boolean, beforeDispatch?: () => Promise<boolean>): Promise<MuxOutcome<MuxCreatedPane>> {
    try {
      const { repoRoot: cwd, branch, base, path } = request;
      // Assigned, never spread from `undefined`: an absent field must stay off the wire, so a call
      // without it is byte for byte the call Herdr has always had.
      const opts: HerdrWorktreeCreate = { cwd, branch };
      if (base !== undefined) opts.base = base;
      if (path !== undefined) opts.path = path;
      const identity = finalAdmission ? await this.layoutRuntimeIdentity() : null;
      if (finalAdmission && !identity) return muxRefused("worktree.authority_changed");
      if (beforeDispatch && !await beforeDispatch()) return muxRefused("worktree.target_changed");
      if (finalAdmission && (this.client.layoutRuntimeIdentity?.() !== identity || !finalAdmission(identity!))) return muxRefused("worktree.authority_changed");
      const created = await this.client.createWorktree(opts, finalAdmission && identity ? { runtimeIdentity: identity, finalAdmission } : undefined);
      return muxOk(toCreatedPane(created));
    } catch (err) {
      return worktreeRefusal(err);
    }
  }

  async openWorktree(request: MuxWorktreeOpenRequest, finalAdmission?: (runtimeIdentity: string) => boolean, beforeDispatch?: () => Promise<boolean>): Promise<MuxOutcome<MuxWorktreeOpened>> {
    try {
      const identity = finalAdmission ? await this.layoutRuntimeIdentity() : null;
      if (beforeDispatch && !await beforeDispatch()) return muxRefused("worktree.target_changed");
      if (finalAdmission && (!identity || this.client.layoutRuntimeIdentity?.() !== identity || !finalAdmission(identity))) return muxRefused("worktree.authority_changed");
      const opened = await this.client.openWorktree({
        cwd: request.repoRoot,
        path: request.path,
      }, finalAdmission && identity ? { runtimeIdentity: identity, finalAdmission } : undefined);
      return muxOk({ pane: toCreatedPane(opened.shell), alreadyOpen: opened.alreadyOpen });
    } catch (err) {
      return worktreeRefusal(err);
    }
  }

  async removeWorktree(request: MuxWorktreeRemoveRequest, finalAdmission?: (runtimeIdentity: string) => boolean, beforeDispatch?: () => Promise<boolean>): Promise<MuxOutcome<MuxWorktreeRemoved>> {
    const identity = await this.layoutRuntimeIdentity();
    const remove = this.client.removeWorktree;
    if (this.capabilities.supports.removeWorktree !== true || !identity || !remove) return muxUnsupported("removeWorktree", "This Herdr runtime has not completed a current compatible protocol handshake.");
    try {
      if (beforeDispatch && !await beforeDispatch()) return muxRefused("worktree.target_changed");
      if (this.client.layoutRuntimeIdentity?.() !== identity || (finalAdmission && !finalAdmission(identity))) return muxRefused("worktree.authority_changed");
      const removed = await remove.call(this.client, { workspace_id: request.workspaceId, force: request.force }, { runtimeIdentity: identity, finalAdmission: finalAdmission ?? (() => true) });
      if (this.client.layoutRuntimeIdentity?.() !== identity) return muxUnreachable("Herdr runtime changed during worktree removal; the outcome is unknown.");
      return muxOk({ workspaceId: removed.workspace_id, path: removed.path, forced: removed.forced });
    } catch (err) {
      return worktreeRefusal(err);
    }
  }


  /**
   * The contract's watch over Herdr's one `events.subscribe` stream.
   *
   * Both push capabilities ride the same connection: a `pane_*` event naming a pane is that pane's
   * change, everything else is the herd's structure. Nothing here reads an event as state — the
   * caller re-reads, which is why an event naming no pane falls to the topology side (re-read more,
   * never less).
   */
  /**
   * Every Herdr session on this machine, the configured one included.
   *
   * A directory listing and a few `exists` calls, no round trip: a session that is running has its
   * socket on disk and a cleanly stopped one has removed it, so the answer costs nothing on the
   * socket that types into the operator's terminals. Never a throw, an unreadable directory reads
   * as "nothing named", which is the same answer a box with one session gives.
   */
  listSessions(): Promise<MuxOutcome<readonly MuxSession[]>> {
    return Promise.resolve(muxOk(this.sessions()));
  }

  watch(options: MuxWatchOptions): MuxSubscription {
    return this.client.subscribeEvents({
      subscriptions: buildSubscriptions(options.panes, this.paneLayoutReady),
      onUp: () => {
        // Whatever moved while the stream was down, a repo binding included, is re-read.
        this.repoByWorkspace.clear();
        this.paneLayoutReady = false;
        this.layoutIdentity = null;
        this.client.retireLayoutRuntime?.();
        options.onUp();
      },
      onEvent: (event, data) => {
        this.layoutEvidenceRevision += 1;
        const paneId = changedPaneId(event, data);
        if (paneId === null) options.onTopologyChange();
        else options.onPaneChange(paneId);
      },
      onDown: (why) => {
        this.paneLayoutReady = false;
        this.layoutIdentity = null;
        this.client.retireLayoutRuntime?.();
        options.onDown(why);
      },
    });
  }

  /** One RPC that answers nothing but "it happened", as the contract's ack-or-refusal. */
  private async attempt(call: () => Promise<void>): Promise<MuxAck> {
    try {
      await call();
      return muxAck();
    } catch (err) {
      return transportRefusal(err);
    }
  }
}

/** The freshly-created shell pane, in the port's words. */
function toCreatedPane(created: CreatedShell): MuxCreatedPane {
  return {
    paneId: created.paneId,
    spaceId: created.workspaceId,
    spaceLabel: created.workspaceLabel ?? created.workspaceId,
    tabId: created.tabId,
    cwd: created.cwd,
  };
}

function safeUint64(value: string | null | undefined): string | null {
  return value !== undefined && value !== null && /^(0|[1-9][0-9]{0,19})$/.test(value) &&
    BigInt(value) <= 18_446_744_073_709_551_615n ? value : null;
}

/** Bound and redact vendor identity text before it becomes a phone-visible owner fact. */
function safeAgentText(value: string, maximum: number): string {
  const normalized = Array.from(value)
    .filter((character) => {
      const code = character.codePointAt(0)!;
      return code >= 32 && !(code >= 127 && code <= 159);
    })
    .join("");
  // Mask across the complete bounded upstream field before clipping; clipping first can split a
  // credential token and leave a recognizable prefix that the redactor no longer recognizes.
  return Array.from(redactText(normalized)).slice(0, maximum).join("");
}

function sameWireSession(
  left: WirePane["agent_session"] | null | undefined,
  right: WirePane["agent_session"] | null | undefined,
): boolean {
  const a = left ?? null;
  const b = right ?? null;
  return a === null || b === null
    ? a === b
    : a.source === b.source && a.agent === b.agent && a.kind === b.kind && a.value === b.value;
}

/**
 * One Herdr pane record as a {@link MuxPane}.
 *
 * The joins live here because the space and tab a pane belongs to are things the MULTIPLEXER knows;
 * a caller re-deriving them would have to learn Herdr's record shapes to do it.
 */
type MutableMuxPane = { -readonly [K in keyof MuxPane]: MuxPane[K] };

function toMuxPane(
  raw: WirePane,
  spaceById: Map<string, WireWorkspace>,
  tabById: Map<string, WireTab>,
): MuxPane {
  const space = spaceById.get(raw.workspace_id);
  const spaceLabel = space?.label ?? raw.workspace_id;
  // Herdr reports the agent name already lower-cased (`claude`, `codex`, `pi`, `omp`) and reports
  // nothing at all for a bare shell. Passed through rather than re-cased, so the name the harness
  // and journal registries key on is byte-identical to what Herdr said.
  const agent = raw.agent !== null && raw.agent !== undefined && raw.agent.length > 0 ? raw.agent : "shell";
  // Built mutable and returned readonly: every optional field below is ASSIGNED rather than
  // conditionally spread, so absent stays absent and each condition reads as the one rule it is.
  const pane: MutableMuxPane = {
    paneId: raw.pane_id,
    terminalIncarnation: createHash("sha256").update("collie/herdr-terminal/v1\0", "utf8").update(raw.terminal_id, "utf8").digest("hex"),
    spaceId: raw.workspace_id,
    spaceLabel,
    spaceNumber: space?.number ?? 0,
    tabId: raw.tab_id,
    cwd: raw.cwd,
    focused: raw.focused,
    // Herdr drops a pane from `pane.list` when it goes away — a listed pane is a live one, and an
    // id that has left the snapshot answers `pane_not_found` on the next write.
    alive: true,
    agent,
    status: raw.agent_status,
  };
  // Optional fields are ASSIGNED, never conditionally spread: absent stays absent, and each
  // condition below stays readable as the one rule it encodes.
  //
  // A user-set pane label (herdr pane.rename); omitted when unset.
  if (raw.label !== null && raw.label !== undefined && raw.label.length > 0) pane.paneLabel = raw.label;
  if (raw.foreground_cwd !== undefined && raw.foreground_cwd.length > 0) pane.foregroundCwd = raw.foreground_cwd;
  if (raw.restore_error !== undefined && raw.restore_error !== null && raw.restore_error.length > 0) pane.restoreError = raw.restore_error;
  // The tab's label, dropped when it's Herdr's positional default in a single-tab space.
  const tabLabel = meaningfulTabLabel(tabById.get(raw.tab_id)?.label);
  if (tabLabel) {
    pane.tabLabel = tabLabel;
    // Herdr labels an unnamed tab by position, so any other label is one the operator gave it.
    if (!isUnnamedTab(tabLabel)) pane.tabNamed = true;
  }
  // What the pane says it is doing, dropped when it only repeats the agent name or the space label.
  const terminalTitle = meaningfulTerminalTitle(
    raw.terminal_title,
    raw.terminal_title_stripped,
    agent,
    spaceLabel,
  );
  if (terminalTitle) pane.terminalTitle = terminalTitle;
  // How the agent named its session. BOTH kinds are kept: Claude and Codex report an `id`, while pi
  // reports a `path`. Which kinds are meaningful is the journal adapter's call, not this function's.
  //
  // The ref must also BELONG to the agent currently in the pane. Herdr keeps reporting the last
  // session announced for a pane, so relaunching a pane's agent as a different harness leaves the
  // old one's ref behind — live-observed: a pane running `pi` still advertising a claude `id` from
  // the claude that had been there before. Serving that would hand pi's adapter a Claude uuid.
  // Absence of `agent` stays permissive, so an older server that omits the field still works.
  const session = paneAgentSession(raw.agent_session);
  if (session !== null && (session.agent === undefined || session.agent === agent)) {
    pane.agentSession = { kind: session.kind, value: session.value };
  }
  // Scrollback depth + viewport = what a `recent` read can yield. Omitted when the server predates
  // `scroll`, so an older Herdr reads as "unknown" rather than "zero".
  if (raw.scroll) pane.readableLines = raw.scroll.max_offset_from_bottom + raw.scroll.viewport_rows;
  pane.revision = raw.revision;
  return pane;
}

/**
 * One Herdr workspace as a {@link MuxSpace}. `agent_status` is carried on the wire and unused.
 *
 * `looked` is the repo {@link HerdrMux} read off `worktree.list` (herdr 0.9.3), used only when the
 * record itself carries none (herdr 0.8.2 did).
 */
function toMuxSpace(raw: WireWorkspace, looked: WorkspaceRepo | null): MuxSpace {
  const space: MutableMuxSpace = {
    spaceId: raw.workspace_id,
    number: raw.number,
    label: raw.label,
    focused: raw.focused,
    activeTabId: raw.active_tab_id,
    tabCount: raw.tab_count,
    paneCount: raw.pane_count,
  };
  // herdr 0.8.2 carries the repo on the workspace itself (`worktree.repo_root`), so no extra call
  // and no filesystem walk — see MuxSpace.repoRoot. OMITTED, never set to undefined, when there is
  // none.
  if (raw.worktree?.repo_root !== undefined) {
    space.repoRoot = raw.worktree.repo_root;
    // Herdr's own word for it. `is_linked_worktree` is false for the repo's checkout and true for
    // every worktree of it — probed 2026-08-28, and the pair is what lets a list nest one under the
    // other without a second call.
    space.isWorktree = raw.worktree.is_linked_worktree === true;
    // The workspace's own folder: the checkout it sits in. Herdr's workspace record carries no cwd
    // of its own (probed 2026-09-23 on herdr 0.9.0), so this is the only folder it names.
    const folder = raw.worktree.checkout_path ?? raw.worktree.repo_root;
    if (folder) space.folder = folder;
  } else if (looked !== null) {
    // herdr 0.9.3: the same three facts, read off `worktree.list` (see workspaceRepoOf).
    space.repoRoot = looked.repoRoot;
    space.isWorktree = looked.isWorktree;
    if (looked.folder) space.folder = looked.folder;
  }
  return space;
}

type MutableMuxSpace = { -readonly [K in keyof MuxSpace]: MuxSpace[K] };

/** One Herdr tab as a {@link MuxTab}. */
function toMuxTab(raw: WireTab): MuxTab {
  return {
    tabId: raw.tab_id,
    spaceId: raw.workspace_id,
    number: raw.number,
    label: raw.label,
    focused: raw.focused,
    paneCount: raw.pane_count,
  };
}

/**
 * Herdr's entry in the mux registry.
 *
 * The factory is where a socket path becomes a client: `endpoint` is the Herdr socket, `timeoutMs`
 * the per-request budget, and the one adapter-private option is which local dialer opens it.
 */
export const herdrMuxFactory: MuxAdapterFactory = {
  mux: HERDR_MUX,
  create(target: MuxTarget) {
    return new HerdrMux(
      new HerdrClient(target.endpoint, target.timeoutMs || DEFAULT_TIMEOUT_MS, dialModeOf(target.options)),
      herdrSessionSource(target.endpoint),
    );
  },
  describeTarget(endpoint: string) {
    return `socket ${endpoint}`;
  },
  // Herdr 0.9.0 links machines, so it is the one adapter with a list of ssh targets to offer
  // (ADR 0036 (c)). It is NOT read off the socket and it is not a pane question — see
  // `./machine-list.ts`. Nothing here enrols anything; `collie crew add` offers the list and the
  // operator picks.
  hostCandidates: herdrMachineCandidates,
};
