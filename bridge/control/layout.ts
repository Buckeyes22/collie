import { layoutSnapshotAuthority } from "../mux/layout-evidence.ts";
import { realpath, stat } from "node:fs/promises";
import { realpathSync, statSync } from "node:fs";
import type { JsonObject, JsonValue } from "../json.ts";
import { askedFolder, type FolderSurface } from "../folders.ts";
import type { MuxAdapter, MuxLayoutAction, MuxLayoutFinalAdmission, MuxLayoutMutation, MuxLayoutRead, MuxLayoutReadRequest, MuxPane, MuxPaneDirection, MuxSnapshot, MuxSplitCwdChoice } from "../mux/types.ts";
import { controlRoute, controlScope, payloadHash, refusal, refusalResponse, type ControlRoute } from "./contracts.ts";
import { executePlannedOperation, PlanStore, type ControlPlan, type PlanBinding } from "./plans.ts";
import { OperationStore, type OperationOutcome } from "./operations.ts";
import type { MuxCapability } from "../mux/capabilities.ts";
import type { GateLevel } from "../crew/peer-gate.ts";

export interface PaneLayoutRuntime {
  readonly host: string;
  readonly session: string | null;
  readonly mux: MuxAdapter;
  readonly folders?: FolderSurface;
}
export interface PaneLayoutCaller {
  gate(level: GateLevel): Response | null;
  resolve(): Promise<PaneLayoutRuntime | Response>;
  pairedIdentity(): string | null;
  audit(action: string, paneId: string, host: string, session: string | null, outcome: string): void;
}
export interface PaneLayoutStores {
  readonly plans: PlanStore;
  readonly operations: OperationStore;
}

type LayoutVerb = "split" | "resize" | "zoom" | "swap" | "move" | "focus-direction";
const ACTION_CAPABILITY = {
  split: "paneSplit", resize: "paneResize", zoom: "paneZoom", swap: "paneSwap", move: "paneMove", "focus-direction": "paneDirectionalFocus",
} as const satisfies Record<LayoutVerb, MuxCapability>;

/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */
function record(value: JsonValue | undefined): JsonObject | null {
  if (value === null || value === undefined || typeof value !== "object" || Array.isArray(value)) return null;
  // SAFETY: JsonValue's object branch is exactly a string-keyed JSON object; arrays were excluded.
  return value as JsonObject;
}
function exactKeys(value: JsonObject, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && Object.keys(value).every((key) => keys.includes(key));
}
function isPaneDirection(value: JsonValue | undefined): value is MuxPaneDirection {
  return value === "left" || value === "right" || value === "up" || value === "down";
}
function isPaneDirectionText(value: string | undefined): value is MuxPaneDirection {
  return value === "left" || value === "right" || value === "up" || value === "down";
}
function boundedId(value: JsonValue | undefined): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_.:-]{1,128}$/.test(value);
}
function validRequestId(value: JsonValue | undefined): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}
function parseEnv(value: JsonValue | undefined): Readonly<Record<string, string>> | null {
  if (value === undefined) return Object.freeze({});
  const row = record(value);
  if (!row || Object.keys(row).length > 32) return null;
  const entries: [string, string][] = [];
  let bytes = 2;
  for (const key of Object.keys(row).toSorted()) {
    const child = row[key];
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key) || ["__proto__", "prototype", "constructor", "HERDR_SOCKET_PATH", "HERDR_ENV", "HERDR_WORKSPACE_ID", "HERDR_TAB_ID", "HERDR_PANE_ID"].includes(key) || typeof child !== "string") return null;
    if (child.includes("\u0000") || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(child)) return null;
    const valueBytes = new TextEncoder().encode(child).byteLength;
    if (valueBytes > 1024) return null;
    bytes += new TextEncoder().encode(JSON.stringify(key)).byteLength + 1 + new TextEncoder().encode(JSON.stringify(child)).byteLength + 1;
    if (bytes > 8192) return null;
    entries.push([key, child]);
  }
  // SAFETY: Object.create(null) is a mutable string-keyed record with no inherited keys; every key/value pair below was validated above.
  const normalized = Object.create(null) as Record<string, string>;
  for (const [key, child] of entries) normalized[key] = child;
  return Object.freeze(normalized);
}
function parseSplitCwd(value: JsonValue | undefined): MuxSplitCwdChoice | null | false {
  if (value === undefined || value === null) return null;
  const row = record(value);
  if (!row || typeof row.kind !== "string") return false;
  if (row.kind === "source-pane" && exactKeys(row, ["kind"])) return { kind: "source-pane" };
  if (row.kind === "workspace" && exactKeys(row, ["kind", "workspaceId"]) && boundedId(row.workspaceId)) return { kind: "workspace", workspaceId: row.workspaceId };
  if (row.kind === "advertised-folder" && exactKeys(row, ["kind", "path"]) && typeof row.path === "string" && new TextEncoder().encode(row.path).byteLength <= 4096) return { kind: "advertised-folder", path: row.path };
  return false;
}
function ratio(value: JsonValue | undefined): value is number | null {
  return value === null || (typeof value === "number" && Number.isFinite(value) && value > 0 && value < 1 && Math.fround(value) > 0 && Math.fround(value) < 1);
}
function optionalRatio(value: JsonValue | undefined): value is number | null | undefined { return value === undefined || ratio(value); }
function amount(value: JsonValue | undefined): value is number | null {
  return value === null || (typeof value === "number" && Number.isFinite(value) && value !== 0 && Math.abs(value) <= 0.5);
}
function label(value: JsonValue | undefined): value is string | null {
  return value === null || (typeof value === "string" && value.length <= 128 && !Array.from(value).some((char) => char.charCodeAt(0) < 32));
}
function parseAction(value: JsonValue | undefined, paneId: string): MuxLayoutAction | null {
  const row = record(value);
  if (!row || typeof row.kind !== "string") return null;
  if (row.kind === "split" && Object.keys(row).every((key) => ["kind", "direction", "ratio", "workspaceId", "targetPaneId", "cwd", "env"].includes(key))) {
    const workspaceId = row.workspaceId ?? null;
    const targetPaneId = row.targetPaneId ?? null;
    if ((row.direction !== "right" && row.direction !== "down") || !optionalRatio(row.ratio) ||
        (workspaceId !== null && !boundedId(workspaceId)) || (targetPaneId !== null && !boundedId(targetPaneId))) return null;
    const cwdChoice = parseSplitCwd(row.cwd);
    const env = parseEnv(row.env);
    if (cwdChoice === false || env === null) return null;
    return { kind: "split", paneId, direction: row.direction, ratio: row.ratio ?? null, targetPaneId, workspaceId, cwdChoice, env, focus: false };
  }
  if (row.kind === "resize" && exactKeys(row, ["kind", "direction", "amount"])) {
    if (!isPaneDirection(row.direction) || !amount(row.amount)) return null;
    return { kind: "resize", paneId, direction: row.direction, amount: row.amount };
  }
  if (row.kind === "zoom" && exactKeys(row, ["kind", "mode"])) {
    if (row.mode !== "toggle" && row.mode !== "on" && row.mode !== "off") return null;
    return { kind: "zoom", paneId, mode: row.mode };
  }
  if (row.kind === "swap" && exactKeys(row, ["kind", "direction", "sourcePaneId", "targetPaneId"])) {
    const directional = row.direction === null || isPaneDirection(row.direction);
    if (!directional || (row.sourcePaneId !== null && !boundedId(row.sourcePaneId)) || (row.targetPaneId !== null && !boundedId(row.targetPaneId))) return null;
    const explicit = row.sourcePaneId !== null || row.targetPaneId !== null;
    if (explicit && (!boundedId(row.sourcePaneId) || !boundedId(row.targetPaneId) || row.direction !== null)) return null;
    if (explicit && row.sourcePaneId !== paneId) return null;
    if (!explicit && row.direction === null) return null;
    // SAFETY: the directional/explicit exclusivity checks above leave only the declared union.
    return { kind: "swap", paneId, direction: row.direction as MuxPaneDirection | null, sourcePaneId: row.sourcePaneId, targetPaneId: row.targetPaneId };
  }
  if (row.kind === "move" && exactKeys(row, ["kind", "destination"])) {
    const destination = record(row.destination);
    if (!destination || typeof destination.type !== "string") return null;
    if (destination.type === "tab" && exactKeys(destination, ["type", "tabId", "split", "targetPaneId", "ratio"])) {
      if (!boundedId(destination.tabId) || (destination.split !== "right" && destination.split !== "down") || !boundedId(destination.targetPaneId) || !ratio(destination.ratio)) return null;
      return { kind: "move", paneId, destination: { type: "tab", tabId: destination.tabId, split: destination.split, targetPaneId: destination.targetPaneId, ratio: destination.ratio }, focus: false };
    }
    if (destination.type === "new_tab" && exactKeys(destination, ["type", "workspaceId", "label"])) {
      if (!boundedId(destination.workspaceId) || !label(destination.label)) return null;
      return { kind: "move", paneId, destination: { type: "new_tab", workspaceId: destination.workspaceId, label: destination.label }, focus: false };
    }
    if (destination.type === "new_workspace" && exactKeys(destination, ["type", "label", "tabLabel"])) {
      if (!label(destination.label) || !label(destination.tabLabel)) return null;
      return { kind: "move", paneId, destination: { type: "new_workspace", label: destination.label, tabLabel: destination.tabLabel }, focus: false };
    }
  }
  if (row.kind === "focus-direction" && exactKeys(row, ["kind", "direction"])) {
    if (!isPaneDirection(row.direction)) return null;
    return { kind: "focus-direction", paneId, direction: row.direction };
  }
  return null;
}
async function readBody(req: Request, maximum: number): Promise<JsonValue | null> {
  const declared = req.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > maximum)) {
    await req.body?.cancel("layout body exceeds limit").catch(() => {});
    return null;
  }
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      const chunk = next.value;
      const remaining = maximum - size;
      if (chunk.byteLength > remaining) {
        // Keep at most one byte beyond the contract for overflow detection, and stop consuming.
        if (remaining > 0) chunks.push(chunk.subarray(0, remaining));
        await reader.cancel("layout body exceeds limit").catch(() => {});
        return null;
      }
      chunks.push(chunk);
      size += chunk.byteLength;
    }
  } catch {
    await reader.cancel("layout body read failed").catch(() => {});
    return null;
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    // SAFETY: JSON.parse creates only the recursive JSON value union; no prototype methods are used.
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as JsonValue;
  }
  catch { return null; }
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */

function actionVerb(action: MuxLayoutAction): LayoutVerb { return action.kind; }
function capabilityOf(action: MuxLayoutAction): MuxCapability { return ACTION_CAPABILITY[actionVerb(action)]; }
function jsonValue<T extends object>(value: T): JsonValue {
  // SAFETY: the callers pass only already-normalized in-memory DTOs; JSON.stringify removes undefined fields.
  return JSON.parse(JSON.stringify(value)) as JsonValue;
}
function externalAction(action: MuxLayoutAction): JsonValue {
  if (action.kind === "split") return jsonValue({ kind: "split", direction: action.direction, ratio: action.ratio, targetPaneId: action.targetPaneId ?? null,
    workspaceId: action.workspaceId ?? null, cwd: action.cwdChoice ?? null, env: action.env ?? {} });
  if (action.kind === "swap") return jsonValue({ kind: action.kind, direction: action.direction, sourcePaneId: action.sourcePaneId, targetPaneId: action.targetPaneId });
  if (action.kind === "move") return jsonValue({ kind: action.kind, destination: action.destination });
  // The resolved neighbor is private plan evidence, never part of the phone's immutable body.
  if (action.kind === "focus-direction") return jsonValue({ kind: action.kind, paneId: action.paneId, direction: action.direction });
  return jsonValue(action);
}
async function resolveSplitAction(runtime: PaneLayoutRuntime, snapshot: MuxSnapshot, source: MuxPane, action: Extract<MuxLayoutAction, { kind: "split" }>): Promise<{ action: MuxLayoutAction; cwdEvidence: JsonValue } | null> {
  if ((action.targetPaneId !== null && action.targetPaneId !== source.paneId) || (action.workspaceId !== null && action.workspaceId !== source.spaceId)) return null;
  let cwd: string | null = null;
  let cwdEvidence: JsonValue = { choice: action.cwdChoice ?? null, sourceCwd: source.cwd };
  let cwdDevice: string | null = null;
  let cwdInode: string | null = null;
  let cwdCatalogPath: string | null = null;
  const choice = action.cwdChoice ?? null;
  if (choice !== null) {
    let requested: string | undefined;
    if (choice.kind === "source-pane") requested = source.cwd;
    else if (choice.kind === "workspace") {
      if (choice.workspaceId !== source.spaceId) return null;
      requested = snapshot.spaces.find((space) => space.spaceId === source.spaceId)?.folder;
      if (!requested) return null;
    } else {
      const folders = runtime.folders?.current();
      if (!folders || !folders.recent.includes(choice.path) && !folders.favourites.includes(choice.path)) return null;
      requested = choice.path;
      cwdCatalogPath = choice.path;
    }
    const selected = askedFolder(requested, runtime.folders?.home ?? "");
    if (selected === false || selected === undefined) return null;
    try {
      const resolved = await realpath(selected);
      const details = await stat(resolved, { bigint: true });
      if (!details.isDirectory()) return null;
      cwd = resolved;
      cwdDevice = details.dev.toString();
      cwdInode = details.ino.toString();
      cwdEvidence = { choice, path: resolved, dev: details.dev.toString(), ino: details.ino.toString(), sourceCwd: source.cwd };
    } catch { return null; }
  }
  const normalized: MuxLayoutAction = { ...action, resolvedTargetPaneId: source.paneId, resolvedWorkspaceId: source.spaceId,
    resolvedSourceTabId: source.tabId, resolvedSourceWorkspaceId: source.spaceId, resolvedSourceCwd: source.cwd,
    cwdDevice, cwdInode, cwdCatalogPath, cwdSelectedPath: choice?.kind === "source-pane" ? source.cwd :
      choice?.kind === "workspace" ? snapshot.spaces.find((space) => space.spaceId === source.spaceId)?.folder ?? null : cwdCatalogPath,
    cwd, env: action.env ?? Object.freeze({}) };
  return { action: normalized, cwdEvidence };
}
function finalSplitCwdCurrent(runtime: PaneLayoutRuntime, action: Extract<MuxLayoutAction, { kind: "split" }>): boolean {
  const choice = action.cwdChoice ?? null;
  if (choice === null) return action.cwd === null && action.cwdDevice === null && action.cwdInode === null;
  if (choice.kind === "advertised-folder") {
    const current = runtime.folders?.current();
    if (!current || (!current.recent.includes(choice.path) && !current.favourites.includes(choice.path)) || action.cwdCatalogPath !== choice.path) return false;
  }
  if (!action.cwd || !action.cwdDevice || !action.cwdInode) return false;
  try {
    const selected = choice.kind === "advertised-folder" ? choice.path : action.cwdSelectedPath;
    if (!selected) return false;
    const requested = askedFolder(selected, runtime.folders?.home ?? "");
    if (requested === false || requested === undefined) return false;
    const resolved = realpathSync(requested);
    const details = statSync(resolved, { bigint: true });
    return resolved === action.cwd && details.isDirectory() && details.dev.toString() === action.cwdDevice && details.ino.toString() === action.cwdInode;
  } catch { return false; }
}
function opaquePane(pane: MuxPane | undefined): string | null {
  if (!pane?.terminalIncarnation) return null;
  return `${pane.paneId}:${pane.terminalIncarnation}`;
}
function paneById(snapshot: MuxSnapshot, id: string): MuxPane | undefined { return snapshot.panes.find((pane) => pane.paneId === id); }
function routePaneId(url: URL, contract: ControlRoute): string | null {
  const raw = contract.pattern.exec(url.pathname)?.[1];
  if (!raw) return null;
  try {
    const decoded = decodeURIComponent(raw);
    return /^[A-Za-z0-9_.:-]{1,128}$/.test(decoded) ? decoded : null;
  } catch { return null; }
}
async function authoritative(runtime: PaneLayoutRuntime, paneId: string, action: MuxLayoutAction): Promise<{ binding: PlanBinding; layout: MuxLayoutRead; destinationLayouts: MuxLayoutRead[]; snapshot: MuxSnapshot; action: MuxLayoutAction; runtimeIdentity: string } | null> {
  if (!runtime.mux.readLayout || !runtime.mux.capabilities.supports.paneLayout || !runtime.mux.layoutRuntimeIdentity) return null;
  const runtimeIdentity = await runtime.mux.layoutRuntimeIdentity();
  if (!runtimeIdentity) return null;
  const runtimeBefore = runtimeIdentity;
  const before = await runtime.mux.snapshot();
  const result = await runtime.mux.readLayout(paneId, { kind: "layout" });
  if (!result.ok || result.value.kind !== "layout") return null;
  const layout = result.value;
  let snapshot = await runtime.mux.snapshot();
  const source = paneById(snapshot, paneId);
  const beforeSource = paneById(before, paneId);
  const sourceIdentity = opaquePane(source);
  if (!source || !sourceIdentity || opaquePane(beforeSource) !== sourceIdentity || source.tabId !== layout.layout.tabId || source.spaceId !== layout.layout.workspaceId || !layout.layout.panes.some((pane) => pane.paneId === paneId)) return null;
  let routeAction = action;
  let cwdEvidence: JsonValue = null;
  if (action.kind === "split") {
    const split = await resolveSplitAction(runtime, snapshot, source, action);
    if (!split) return null;
    routeAction = split.action;
    cwdEvidence = split.cwdEvidence;
  }
  const targets: string[] = [sourceIdentity];
  const destinationLayouts: MuxLayoutRead[] = [];
  const destinationPaneIds: string[] = [];
  if (routeAction.kind === "swap" || routeAction.kind === "focus-direction") {
    let targetId = routeAction.kind === "swap" ? routeAction.targetPaneId : null;
    const direction = routeAction.direction;
    if (direction !== null) {
      const neighbor = await runtime.mux.readLayout(paneId, { kind: "neighbor", direction });
      if (!neighbor.ok || neighbor.value.kind !== "neighbor" || neighbor.value.layout.tabId !== layout.layout.tabId || payloadHash(jsonValue(neighbor.value.layout)) !== payloadHash(jsonValue(layout.layout))) return null;
      targetId = neighbor.value.neighborPaneId;
      const afterNeighbor = await runtime.mux.snapshot();
      if (opaquePane(paneById(afterNeighbor, paneId)) !== sourceIdentity || (targetId !== null && opaquePane(paneById(afterNeighbor, targetId)) !== opaquePane(paneById(snapshot, targetId)))) return null;
      snapshot = afterNeighbor;
    }
    if (!targetId) return null;
    const target = paneById(snapshot, targetId);
    const identity = opaquePane(target);
    if (!target || target.tabId !== source.tabId || !identity || opaquePane(paneById(before, targetId)) !== identity) return null;
    targets.push(identity);
    routeAction = { ...routeAction, resolvedTargetPaneId: targetId };
  }
  if (routeAction.kind === "move") {
    const destination = routeAction.destination;
    if (destination.type === "tab") {
      const target = paneById(snapshot, destination.targetPaneId ?? "");
      const targetTab = snapshot.tabs.find((tab) => tab.tabId === destination.tabId);
      const identity = opaquePane(target);
      if (!target || !targetTab || target.tabId !== targetTab.tabId || target.spaceId !== targetTab.spaceId || !identity || opaquePane(paneById(before, target.paneId)) !== identity) return null;
      const targetLayout = await runtime.mux.readLayout(target.paneId, { kind: "layout" });
      if (!targetLayout.ok || targetLayout.value.kind !== "layout" || targetLayout.value.layout.tabId !== target.tabId || targetLayout.value.layout.workspaceId !== target.spaceId || !targetLayout.value.layout.panes.some((pane) => pane.paneId === target.paneId)) return null;
      destinationLayouts.push(targetLayout.value);
      destinationPaneIds.push(target.paneId);
      const finalSnapshot = await runtime.mux.snapshot();
      if (opaquePane(paneById(finalSnapshot, paneId)) !== sourceIdentity || opaquePane(paneById(finalSnapshot, target.paneId)) !== identity) return null;
      snapshot = finalSnapshot;
      targets.push(identity);
    } else if (destination.type === "new_tab") {
      if (!snapshot.spaces.some((space) => space.spaceId === destination.workspaceId)) return null;
      targets.push(`workspace:${destination.workspaceId}`);
    } else {
      // New workspace creation is an explicit operator choice, not an implicit focused-workspace action.
      targets.push("new-workspace");
    }
  }
  if (routeAction.kind === "move") {
    const target = routeAction.destination.type === "tab" ? paneById(snapshot, routeAction.destination.targetPaneId) : undefined;
    routeAction = { ...routeAction, resolvedSourceTabId: source.tabId, resolvedSourceWorkspaceId: source.spaceId,
      resolvedTargetPaneId: target?.paneId, resolvedTargetTabId: target?.tabId, resolvedTargetWorkspaceId: target?.spaceId };
  }
  if (routeAction.kind === "split" && (routeAction.resolvedWorkspaceId !== source.spaceId || routeAction.resolvedTargetPaneId !== source.paneId)) return null;
  // Re-read every explicit destination and then the source after the last awaited lookup. The
  // before/after snapshots bind occupant identity; these final layout reads close geometry races
  // caused by a slow destination or folder lookup without pretending the vendor RPC is atomic.
  for (let index = 0; index < destinationPaneIds.length; index++) {
    const id = destinationPaneIds[index]!;
    const expected = destinationLayouts[index]!;
    const verified = await runtime.mux.readLayout(id, { kind: "layout" });
    if (!verified.ok || verified.value.kind !== "layout" || payloadHash(jsonValue(verified.value.layout)) !== payloadHash(jsonValue(expected.layout))) return null;
  }
  const sourceVerified = await runtime.mux.readLayout(paneId, { kind: "layout" });
  if (!sourceVerified.ok || sourceVerified.value.kind !== "layout" || payloadHash(jsonValue(sourceVerified.value.layout)) !== payloadHash(jsonValue(layout.layout))) return null;
  const finalSnapshot = await runtime.mux.snapshot();
  if (opaquePane(paneById(finalSnapshot, paneId)) !== sourceIdentity ||
      destinationPaneIds.some((id) => opaquePane(paneById(finalSnapshot, id)) !== opaquePane(paneById(snapshot, id)))) return null;
  snapshot = finalSnapshot;
  if (await runtime.mux.layoutRuntimeIdentity() !== runtimeBefore) return null;
  const current = jsonValue({ runtimeIdentity, layout: layout.layout, destinationLayouts, targetIds: targets, cwdEvidence, tab: snapshot.tabs.find((tab) => tab.tabId === source.tabId) ?? null });
  const binding: PlanBinding = {
    device: "", // Set by the route after the current paired caller is validated.
    host: runtime.host,
    session: runtime.session,
    capability: capabilityOf(routeAction),
    payloadHash: payloadHash(externalAction(routeAction)),
    targetIdentity: payloadHash(jsonValue({ targets })),
    evidence: payloadHash(current),
  };
  return { binding, layout, destinationLayouts, snapshot, action: routeAction, runtimeIdentity };
}

function safeLayout(layout: MuxLayoutRead): JsonValue { return jsonValue(layout); }
function requiredCapability(action: MuxLayoutAction): MuxCapability { return capabilityOf(action); }
function operationResponse(action: MuxLayoutAction, view: import("./operations.ts").OperationView): Response {
  if (view.state === "unknown" || view.state === "pending") return Response.json({ ok: false, code: "layout.outcome_unknown", operation: view }, { status: 202, headers: { "cache-control": "no-store" } });
  if (view.state === "refused") return refusalResponse(refusal(view.code ?? "layout.refused", "The layout action was refused", "operation"), 409);
  if (action.kind === "move" && view.result?.kind !== "pane-move") return Response.json({ ok: false, code: "layout.outcome_unknown", operation: view }, { status: 202, headers: { "cache-control": "no-store" } });
  const response: JsonObject = { ok: true, operation: jsonValue(view) };
  if (view.result?.kind === "pane-move") response.move = jsonValue(view.result);
  return Response.json(response, { headers: { "cache-control": "no-store" } });
}
function effectSummary(action: MuxLayoutAction, snapshot: MuxSnapshot): JsonValue {
  if (action.kind === "split") return { verb: "split", targetPaneId: action.paneId, direction: action.direction, ratio: action.ratio, focusChanges: false };
  if (action.kind === "resize") return { verb: "resize", targetPaneId: action.paneId, direction: action.direction, amount: action.amount };
  if (action.kind === "zoom") return { verb: action.mode === "toggle" ? "toggle-zoom" : `${action.mode}-zoom`, targetPaneId: action.paneId, mode: action.mode, focusChanges: false };
  if (action.kind === "swap") return { verb: "swap", sourcePaneId: action.sourcePaneId ?? action.paneId, targetPaneId: action.resolvedTargetPaneId ?? action.targetPaneId, direction: action.direction };
  if (action.kind === "focus-direction") return { verb: "focus", sourcePaneId: action.paneId, direction: action.direction, focusChanges: true };
  const source = paneById(snapshot, action.paneId);
  const closesEmptyTab = source !== undefined && snapshot.panes.filter((pane) => pane.tabId === source.tabId).length === 1;
  return { verb: "move", sourcePaneId: action.paneId, destination: action.destination.type, focusChanges: false, mayCloseSourceTab: closesEmptyTab };
}

export async function servePaneLayoutRoute(req: Request, url: URL, caller: PaneLayoutCaller, stores: PaneLayoutStores): Promise<Response | null> {
  const contract = controlRoute(url.pathname, req.method);
  if (!contract || !contract.name.startsWith("pane-layout") && !contract.name.startsWith("pane-neighbor") && !contract.name.startsWith("pane-edges")) return null;
  const denied = caller.gate(contract.gate);
  if (denied) return denied;
  if (!controlScope(url, "session")) return refusalResponse(refusal("scope.malformed", "Malformed layout scope", "scope"), 400);
  const paneId = routePaneId(url, contract);
  if (!paneId) return refusalResponse(refusal("pane.target_invalid", "Invalid pane identity", "scope"), 400);
  let postedBody: JsonObject | null = null;
  let postedAction: MuxLayoutAction | null = null;
  if (contract.name === "pane-layout-plan" || contract.name.startsWith("pane-layout-") && contract.name !== "pane-layout") {
    postedBody = record(await readBody(req, contract.maxBodyBytes));
    if (!postedBody) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout request", "operation"), 400);
    if (contract.name === "pane-layout-plan") {
      if (!exactKeys(postedBody, ["action"])) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout plan", "operation"), 400);
      postedAction = parseAction(postedBody.action, paneId);
      if (!postedAction) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout action", "operation"), 400);
    } else {
      if (!exactKeys(postedBody, ["requestId", "planToken", "action"]) || !validRequestId(postedBody.requestId) || !boundedId(postedBody.planToken)) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout operation", "operation"), 400);
      postedAction = parseAction(postedBody.action, paneId);
      if (!postedAction || postedAction.kind !== contract.name.replace("pane-layout-", "")) return refusalResponse(refusal("layout.request_invalid", "Layout action does not match its route", "operation"), 400);
    }
  }
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  const getRead = async (request: MuxLayoutReadRequest): Promise<Response> => {
    if (!runtime.mux.capabilities.supports.paneLayout || !runtime.mux.readLayout) return refusalResponse(refusal("pane.layout_unavailable", "Pane layout is unavailable on this runtime", "capability"), 501);
    const result = await runtime.mux.readLayout(paneId, request);
    if (!result.ok) return muxRefusalResponse(result);
    return Response.json({ ok: true, result: result.value }, { headers: { "cache-control": "no-store" } });
  };
  if (contract.name === "pane-layout") return getRead({ kind: "layout" });
  if (contract.name === "pane-edges") return getRead({ kind: "edges" });
  if (contract.name === "pane-neighbor") {
    const directions = url.searchParams.getAll("direction");
    if (directions.length !== 1 || !isPaneDirectionText(directions[0])) return refusalResponse(refusal("layout.direction_invalid", "Choose one valid pane direction", "operation"), 400);
    return getRead({ kind: "neighbor", direction: directions[0] });
  }
  if (contract.name === "pane-layout-plan") {
    const action = postedAction;
    if (!action) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout plan", "operation"), 400);
    const owner = caller.pairedIdentity();
    if (!owner || !/^[a-f0-9]{64}$/.test(owner)) return refusalResponse(refusal("device.identity_required", "Layout plans require an identified paired device", "device"), 403);
    if (!stores.operations.isDurable) return refusalResponse(refusal("layout.receipts_unavailable", "Durable layout operation custody is unavailable", "operation"), 503);
    if (!runtime.mux.capabilities.supports["paneLayout"] || !runtime.mux.capabilities.supports[requiredCapability(action)]) return refusalResponse(refusal("layout.capability_unavailable", "This layout action is not supported by the current runtime", "capability"), 501);
    const observed = await authoritative(runtime, paneId, action);
    if (!observed) return refusalResponse(refusal("layout.target_stale", "The pane or destination changed; refresh the layout before planning", "scope"), 409);
    const canonicalAction = observed.action;
    const binding = { ...observed.binding, device: owner };
    try {
      const plan = stores.plans.issue(binding);
      return Response.json({ ok: true, planToken: plan.token, expiresAt: plan.expiresAt, action: canonicalAction.kind, effects: effectSummary(canonicalAction, observed.snapshot), layout: safeLayout(observed.layout) }, { headers: { "cache-control": "no-store" } });
    } catch {
      return refusalResponse(refusal("layout.plan_unavailable", "A layout plan could not be created", "operation"), 503);
    }
  }
  const actionName = contract.name.replace("pane-layout-", "");
  if (!(actionName in ACTION_CAPABILITY)) return null;
  const body = postedBody;
  if (!body) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout operation", "operation"), 400);
  const requestId = body.requestId;
  const planToken = body.planToken;
  if (!validRequestId(requestId) || !boundedId(planToken)) return refusalResponse(refusal("layout.request_invalid", "Malformed pane layout operation", "operation"), 400);
  const action = postedAction;
  if (!action || action.kind !== actionName) return refusalResponse(refusal("layout.request_invalid", "Layout action does not match its route", "operation"), 400);
  const owner = caller.pairedIdentity();
  if (!owner || !/^[a-f0-9]{64}$/.test(owner)) return refusalResponse(refusal("device.identity_required", "Layout operations require an identified paired device", "device"), 403);
  if (!stores.operations.isDurable) return refusalResponse(refusal("layout.receipts_unavailable", "Durable layout operation custody is unavailable", "operation"), 503);
  const bodyHash = payloadHash(externalAction(action));
  const existing = await stores.operations.inspectIntent(requestId, owner, runtime.host, runtime.session, capabilityOf(action), bodyHash, planToken);
  if (existing.kind === "match") return operationResponse(action, existing.operation);
  if (existing.kind === "conflict") return refusalResponse(refusal("operation.request_id_reused", "This operation ID is already bound to another layout action", "operation"), 409);
  if (!runtime.mux.capabilities.supports[requiredCapability(action)] || !runtime.mux.applyLayout) return refusalResponse(refusal("layout.capability_unavailable", "This layout action is not supported by the current runtime", "capability"), 501);
  const plan: ControlPlan | null = stores.plans.lookup(planToken);
  if (!plan || plan.binding.device !== owner || plan.binding.host !== runtime.host || plan.binding.session !== runtime.session || plan.binding.capability !== capabilityOf(action) || plan.binding.payloadHash !== bodyHash) return refusalResponse(refusal("layout.plan_invalid", "The layout plan expired or does not match this action", "operation"), 409);
  let dispatchMux: MuxAdapter | null = null;
  let dispatchAction: MuxLayoutAction | null = null;
  let dispatchRuntime: PaneLayoutRuntime | null = null;
  let dispatchRuntimeIdentity: string | null = null;
  let dispatchSourceLayout: MuxLayoutRead | null = null;
  let dispatchSnapshot: MuxSnapshot | null = null;
  let dispatchDestinationLayout: MuxLayoutRead | null = null;
  try {
    const view = await executePlannedOperation(stores.operations, stores.plans, requestId, plan, async () => {
      const fresh = await caller.resolve();
      if (fresh instanceof Response || fresh.host !== runtime.host || fresh.session !== runtime.session || caller.pairedIdentity() !== owner || caller.gate(contract.gate)) {
        dispatchMux = null;
        dispatchAction = null;
        return { ...plan.binding, device: owner === "0".repeat(64) ? "1".repeat(64) : "0".repeat(64) };
      }
      const current = await authoritative(fresh, paneId, action);
      if (!current || !fresh.mux.capabilities.supports[requiredCapability(action)] || !fresh.mux.applyLayout) {
        dispatchMux = null;
        dispatchAction = null;
        return { ...plan.binding, evidence: plan.binding.evidence === "0".repeat(64) ? "1".repeat(64) : "0".repeat(64) };
      }
      dispatchMux = fresh.mux;
      dispatchAction = current.action;
      dispatchRuntime = fresh;
      dispatchRuntimeIdentity = current.runtimeIdentity;
      dispatchSourceLayout = current.layout;
      dispatchSnapshot = current.snapshot;
      dispatchDestinationLayout = current.destinationLayouts[0] ?? null;
      return { ...current.binding, device: owner };
    }, async (): Promise<OperationOutcome> => {
      if (!dispatchRuntime || !dispatchMux || !dispatchAction || !dispatchRuntimeIdentity || !dispatchSnapshot ||
          caller.gate(contract.gate) || caller.pairedIdentity() !== owner ||
          !dispatchMux.capabilities.supports[requiredCapability(dispatchAction)] || !dispatchMux.applyLayout) {
        return { state: "refused", code: "layout.authority_changed" };
      }
      if (dispatchAction.kind !== action.kind) return { state: "refused", code: "layout.action_changed" };
      const preparedAction = dispatchAction;
      const finalMux = dispatchMux;
      const finalRuntime = dispatchRuntime;
      const expectedRuntimeIdentity = dispatchRuntimeIdentity;
      const expectedSourceLayout = dispatchSourceLayout;
      const expectedDestinationLayout = dispatchDestinationLayout;
      const expectedSnapshot = await finalMux.snapshot();
      if (payloadHash(layoutSnapshotAuthority(expectedSnapshot, preparedAction)) !== payloadHash(layoutSnapshotAuthority(dispatchSnapshot, preparedAction)))
        return { state: "refused", code: "layout.target_stale" };
      const applyLayout = finalMux.applyLayout;
      if (!applyLayout) return { state: "refused", code: "layout.capability_unavailable" };
      let expiredAtDispatch = false;
      const finalAdmission: MuxLayoutFinalAdmission = (prepared, runtimeIdentity, evidence) => {
        if (runtimeIdentity !== expectedRuntimeIdentity || finalRuntime.mux !== finalMux ||
            caller.gate(contract.gate) !== null || caller.pairedIdentity() !== owner ||
            !finalMux.capabilities.supports[requiredCapability(prepared)] ||
            payloadHash(jsonValue(prepared)) !== payloadHash(jsonValue(preparedAction))) return false;
        if (payloadHash(layoutSnapshotAuthority(evidence.snapshot, prepared)) !== payloadHash(layoutSnapshotAuthority(expectedSnapshot, preparedAction))) return false;
        if (!expectedSourceLayout || payloadHash(jsonValue(evidence.sourceLayout)) !== payloadHash(jsonValue(expectedSourceLayout))) return false;
        if (expectedDestinationLayout === null) {
          if (evidence.destinationLayout !== undefined) return false;
        } else if (!evidence.destinationLayout ||
            payloadHash(jsonValue(evidence.destinationLayout)) !== payloadHash(jsonValue(expectedDestinationLayout))) return false;
        if (evidence.neighborLayout && payloadHash(jsonValue(evidence.neighborLayout)) !== payloadHash(jsonValue(expectedSourceLayout))) return false;
        if (prepared.kind === "split" && !finalSplitCwdCurrent(finalRuntime, prepared)) return false;
        // Same PlanStore clock as issue/consume; after all awaits and synchronous filesystem
        // evidence, immediately before the adapter's single named RPC. Consumption is not TTL.
        expiredAtDispatch = !stores.plans.isUnexpired(plan.expiresAt);
        return !expiredAtDispatch;
      };
      const result = await applyLayout.call(finalMux, preparedAction, finalAdmission);
      if (!result.ok) {
        if (expiredAtDispatch) return { state: "refused", code: "plan.expired" };
        if (result.reason === "unreachable") return { state: "unknown", code: "layout.outcome_unknown" };
        return { state: "refused", code: result.reason === "gone" ? "layout.target_gone" : result.reason === "unsupported" ? "layout.capability_unavailable" : "layout.refused" };
      }
      const mutation: MuxLayoutMutation = result.value;
      if (mutation.kind !== action.kind) return { state: "unknown", code: "layout.protocol_mismatch" };
      if (mutation.kind === "move") return { state: "succeeded", result: { kind: "pane-move", paneId: mutation.paneId, previousPaneId: mutation.previousPaneId, previousTabId: mutation.previousTabId, previousWorkspaceId: mutation.previousWorkspaceId } };
      return { state: "succeeded" };
    });
    caller.audit(`pane.layout.${action.kind}`, paneId, runtime.host, runtime.session, view.state);
    return operationResponse(action, view);
  } catch (error) {
    const code = error instanceof Error && error.message === "operation.capacity" ? "operation.capacity" : error instanceof Error && error.message.includes("operation.request_id_reused") ? "operation.request_id_reused" : "layout.operation_unavailable";
    return refusalResponse(refusal(code, "The layout operation could not be reserved or reconciled", "operation"), 503);
  }
}

function muxRefusalResponse(result: { readonly reason: string; readonly detail: string }): Response {
  const status = result.reason === "gone" ? 404 : result.reason === "unsupported" ? 501 : result.reason === "refused" ? 409 : 503;
  const code = result.reason === "gone" ? "pane.target_gone" : result.reason === "unsupported" ? "pane.layout_unavailable" : result.reason === "refused" ? "layout.refused" : "layout.unavailable";
  return refusalResponse(refusal(code, result.detail.slice(0, 256), result.reason === "unsupported" ? "capability" : "operation"), status);
}
