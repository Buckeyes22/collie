import { realpath, stat } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";
import type { JsonObject, JsonValue } from "../json.ts";
import type { Launcher } from "../types.ts";
import type { FolderSurface } from "../folders.ts";
import { redactText } from "../redact.ts";
import type { MuxLayoutApplyRequest, MuxPortableLayout, MuxPortableLayoutNode, MuxSnapshot } from "../mux/types.ts";
import { controlRoute, controlScope, payloadHash, refusal, refusalResponse, type ControlRoute } from "./contracts.ts";
import { OperationStore, type OperationOutcome } from "./operations.ts";
import { PlanStore, type PlanBinding } from "./plans.ts";

const MAX_LAYOUT_BYTES = 64 * 1024;
// Leave room for the scoped request envelope so every exported document can be imported on-device.
const MAX_LAYOUT_DOCUMENT_BYTES = MAX_LAYOUT_BYTES - 4 * 1024;
const MAX_DEPTH = 12;
const MAX_NODES = 64;
const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const REQUEST_ID = /^[A-Za-z0-9_-]{1,128}$/;
const SECRET_ENV = /(?:TOKEN|SECRET|PASSWORD|CREDENTIAL|PRIVATE|AUTH|API[_-]?KEY)/i;
const FORBIDDEN_ENV = new Set(["__proto__", "prototype", "constructor", "HERDR_SOCKET_PATH", "HERDR_ENV", "HERDR_WORKSPACE_ID", "HERDR_TAB_ID", "HERDR_PANE_ID"]);

export interface LayoutRecipeRuntime {
  readonly host: string;
  readonly session: string | null;
  readonly mux: Pick<import("../mux/types.ts").MuxAdapter,
    "capabilities" | "snapshot" | "layoutRuntimeIdentity" | "exportPortableLayout" | "applyPortableLayout" | "setSplitRatio">;
  readonly folders?: FolderSurface;
}
export interface LayoutRecipeCaller {
  gate(level: "read" | "write" | "device-read"): Response | null;
  resolve(): Promise<LayoutRecipeRuntime | Response>;
  pairedIdentity(): string | null;
  launchers(): Promise<readonly Launcher[]>;
  audit(action: string, target: string, host: string, session: string | null, outcome: string): void;
}
interface LayoutRuntimeEvidence { readonly runtimeIdentity: string; readonly [key: string]: JsonValue }

/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type, anti-slop/require-safety-comment-for-type-assertion -- casts follow the immediately preceding closed JSON and layout validators */
function record(value: JsonValue | undefined): JsonObject | null {
  return value !== null && value !== undefined && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : null;
}
function exactKeys(row: JsonObject, expected: readonly string[]): boolean {
  return Object.keys(row).length === expected.length && Object.keys(row).every((key) => expected.includes(key));
}
function text(value: JsonValue | undefined, maximum = 128): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximum && !Array.from(value).some((char) => char.charCodeAt(0) < 32);
}
function nullableText(value: JsonValue | undefined, maximum = 4096): value is string | null {
  return value === null || value === undefined || text(value, maximum);
}
function parseNode(value: JsonValue | undefined): MuxPortableLayoutNode | null {
  let count = 0;
  const visit = (child: JsonValue | undefined, depth: number): MuxPortableLayoutNode | null => {
    if (depth > MAX_DEPTH || ++count > MAX_NODES) return null;
    const row = record(child);
    if (!row || typeof row.type !== "string") return null;
    if (row.type === "pane") {
      if (!Object.keys(row).every((key) => ["type", "label", "cwd", "paneId", "command", "env"].includes(key)) ||
          !nullableText(row.label, 128) || !nullableText(row.cwd, 4096) || !nullableText(row.paneId, 128)) return null;
      let command: readonly string[] | null = null;
      if (row.command !== undefined && row.command !== null) {
        if (!Array.isArray(row.command) || row.command.length < 1 || row.command.length > 64 || row.command.some((arg) => !text(arg, 2048) || arg.includes("\u0000"))) return null;
        command = row.command as string[];
      }
      const envRaw = row.env === undefined ? {} : record(row.env);
      if (!envRaw || Object.keys(envRaw).length > 32) return null;
      const env: Record<string, string> = Object.create(null) as Record<string, string>;
      let envBytes = 0;
      for (const [key, item] of Object.entries(envRaw)) {
        if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key) || SECRET_ENV.test(key) || FORBIDDEN_ENV.has(key) || !text(item, 1024) || item.includes("\u0000")) return null;
        envBytes += Buffer.byteLength(key) + Buffer.byteLength(item);
        if (envBytes > 8192) return null;
        env[key] = item;
      }
      return { type: "pane", label: row.label ?? null, cwd: row.cwd ?? null, paneId: row.paneId ?? null, command, env: Object.freeze(env) };
    }
    if (row.type !== "split" || !exactKeys(row, ["type", "direction", "ratio", "first", "second"]) ||
        (row.direction !== "right" && row.direction !== "down") || typeof row.ratio !== "number" || !Number.isFinite(row.ratio) || row.ratio <= 0 || row.ratio >= 1 || Math.fround(row.ratio) <= 0 || Math.fround(row.ratio) >= 1) return null;
    const first = visit(row.first, depth + 1);
    const second = visit(row.second, depth + 1);
    return first && second ? { type: "split", direction: row.direction, ratio: row.ratio, first, second } : null;
  };
  return visit(value, 0);
}
function leaves(node: MuxPortableLayoutNode): Extract<MuxPortableLayoutNode, { type: "pane" }>[] {
  return node.type === "pane" ? [node] : [...leaves(node.first), ...leaves(node.second)];
}
function cleanImported(node: MuxPortableLayoutNode): MuxPortableLayoutNode {
  return node.type === "split" ? { ...node, first: cleanImported(node.first), second: cleanImported(node.second) } : { ...node, paneId: null };
}
function safeArgv(argv: readonly string[]): boolean {
  return argv.length > 0 && argv.length <= 64 && argv.every((arg) => arg.length > 0 && arg.length <= 2048 && /^[A-Za-z0-9_./:@+=,-]+$/.test(arg));
}
function declaredCommands(root: MuxPortableLayoutNode, launchers: readonly Launcher[]): MuxPortableLayoutNode | null {
  const allowed = new Set(launchers.map((row) => row.command).filter((line) => /^[A-Za-z0-9_./:@+=,-]+(?: +[A-Za-z0-9_./:@+=,-]+)*$/.test(line)));
  const visit = (node: MuxPortableLayoutNode): MuxPortableLayoutNode | null => {
    if (node.type === "split") {
      const first = visit(node.first); const second = visit(node.second);
      return first && second ? { ...node, first, second } : null;
    }
    if (!node.command) return { ...node, paneId: null };
    if (!safeArgv(node.command) || !allowed.has(node.command.join(" "))) return null;
    return { ...node, paneId: null };
  };
  return visit(root);
}
function safeId(value: JsonValue | undefined): value is string { return typeof value === "string" && ID.test(value); }
function safeRatio(value: JsonValue | undefined): value is number { return typeof value === "number" && Number.isFinite(value) && value > 0 && value < 1 && Math.fround(value) > 0 && Math.fround(value) < 1; }
function parseBooleanPath(value: JsonValue | undefined): boolean[] | null {
  if (!Array.isArray(value) || value.length > 63) return null;
  // SAFETY: JSON was decoded at the HTTP boundary; checking every element here creates the domain path type.
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (value.some((bit) => typeof bit !== "boolean")) return null;
  return value as boolean[];
}
function selectSplit(node: MuxPortableLayoutNode, path: readonly boolean[]): Extract<MuxPortableLayoutNode, { type: "split" }> | null {
  let current = node;
  for (const bit of path) {
    if (current.type !== "split") return null;
    current = bit ? current.second : current.first;
  }
  return current.type === "split" ? current : null;
}
async function readBoundedBody(request: Request, maxBytes: number): Promise<JsonValue | null> {
  const bodyLimit = Math.min(MAX_LAYOUT_BYTES, maxBytes);
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > bodyLimit)) { await request.body?.cancel(); return null; }
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const result = await reader.read();
      if (result.done) break;
      size += result.value.byteLength;
      if (size > bodyLimit) { await reader.cancel(); return null; }
      chunks.push(result.value);
    }
  } catch { await reader.cancel().catch(() => {}); return null; }
  finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as JsonValue; }
  catch { return null; }
}
function safePath(root: string, candidate: string): boolean {
  const rel = relative(root, candidate);
  return rel === "" || (rel !== ".." && !rel.startsWith(`..${sep}`) && !isAbsolute(rel));
}
async function resolveCwds(root: MuxPortableLayoutNode, workspaceRoot: string): Promise<MuxPortableLayoutNode | null> {
  const visit = async (node: MuxPortableLayoutNode): Promise<MuxPortableLayoutNode | null> => {
    if (node.type === "split") {
      const first = await visit(node.first); const second = await visit(node.second);
      return first && second ? { ...node, first, second } : null;
    }
    if (node.cwd === null || node.cwd === "") return node;
    if (isAbsolute(node.cwd) || node.cwd.split(/[\\/]/u).includes("..")) return null;
    try {
      const actual = await realpath(resolve(workspaceRoot, node.cwd));
      const details = await stat(actual);
      if (!details.isDirectory() || !safePath(workspaceRoot, actual)) return null;
      return { ...node, cwd: actual };
    } catch { return null; }
  };
  return visit(root);
}
async function portableExport(layout: MuxPortableLayout, workspaceRoot: string): Promise<MuxPortableLayout | null> {
  const resolvedRoot = await realpath(workspaceRoot).catch(() => null);
  if (!resolvedRoot) return null;
  const visit = async (node: MuxPortableLayoutNode): Promise<MuxPortableLayoutNode | null> => {
    if (node.type === "split") {
      const first = await visit(node.first); const second = await visit(node.second);
      return first && second ? { ...node, first, second } : null;
    }
    let cwd: string | null = null;
    if (node.cwd !== null && node.cwd !== "") {
      const actual = await realpath(node.cwd).catch(() => null);
      if (!actual || !safePath(resolvedRoot, actual) || !(await stat(actual).catch(() => null))?.isDirectory()) return null;
      cwd = relative(resolvedRoot, actual) || ".";
    }
    const env = Object.fromEntries(Object.entries(node.env).filter(([key, value]) => !SECRET_ENV.test(key) && redactText(value) === value));
    return { ...node, label: node.label === null ? null : redactText(node.label), command: node.command?.map(redactText) ?? null,
      paneId: null, cwd: cwd === null ? null : redactText(cwd), env };
  };
  const root = await visit(layout.root);
  // Portable files contain geometry and redacted launch recipes, never live workspace/pane selection IDs.
  return root ? { ...layout, workspaceId: "", tabId: "", focusedPaneId: "", root } : null;
}
async function currentTargetLayout(mux: LayoutRecipeRuntime["mux"], snapshot: MuxSnapshot, workspaceId: string, tabId: string | null): Promise<MuxPortableLayout | null> {
  if (!mux.exportPortableLayout) return null;
  if (tabId !== null) {
    const result = await mux.exportPortableLayout({ tabId });
    return result.ok ? result.value : null;
  }
  const pane = snapshot.panes.find((row) => row.spaceId === workspaceId);
  if (!pane) return null;
  const result = await mux.exportPortableLayout({ paneId: pane.paneId });
  return result.ok ? result.value : null;
}
function snapshotEvidence(snapshot: MuxSnapshot, workspaceId: string, tabId: string | null): JsonValue {
  const tab = tabId === null ? null : snapshot.tabs.find((row) => row.tabId === tabId) ?? null;
  const workspace = snapshot.spaces.find((row) => row.spaceId === workspaceId) ?? null;
  const panes = snapshot.panes.filter((pane) => pane.spaceId === workspaceId && (tabId === null || pane.tabId === tabId));
  return JSON.parse(JSON.stringify({ workspaceId, workspace: workspace ? { spaceId: workspace.spaceId, folder: workspace.folder ?? null } : null, tab, panes: panes.map(({ paneId, tabId: paneTab, terminalIncarnation, cwd }) => ({ paneId, tabId: paneTab, terminalIncarnation: terminalIncarnation ?? null, cwd })) })) as JsonValue;
}
function binding(owner: string, host: string, session: string | null, payload: JsonValue, target: JsonValue, evidence: JsonValue): PlanBinding {
  return { device: owner, host, session, capability: "portableLayouts", payloadHash: payloadHash(payload), targetIdentity: payloadHash(target), evidence: payloadHash(evidence) };
}
function statusError(code: string, message: string, status: number): Response { return refusalResponse(refusal(code, message, status === 404 ? "capability" : "operation"), status); }
function layoutJsonResponse(payload: JsonValue, document: MuxPortableLayout | null = null): Response {
  const encoded = JSON.stringify(payload);
  if (Buffer.byteLength(encoded, "utf8") > MAX_LAYOUT_BYTES ||
      (document !== null && Buffer.byteLength(JSON.stringify(document), "utf8") > MAX_LAYOUT_DOCUMENT_BYTES)) {
    return statusError("layout.response_too_large", "The portable layout response exceeds the supported size", 413);
  }
  return new Response(encoded, { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}
function routeTabId(url: URL, route: ControlRoute): string | null {
  const raw = route.pattern.exec(url.pathname)?.[1];
  if (!raw) return null;
  try { const value = decodeURIComponent(raw); return ID.test(value) ? value : null; } catch { return null; }
}
function operationAnswer(view: import("./operations.ts").OperationView): Response {
  if (view.state === "pending" || view.state === "unknown") return Response.json({ ok: false, code: "layout.outcome_unknown", operation: view }, { status: 202, headers: { "cache-control": "no-store" } });
  if (view.state === "refused") return refusalResponse(refusal(view.code ?? "layout.refused", "The portable layout action was refused", "operation"), 409);
  return Response.json({ ok: true, operation: view }, { headers: { "cache-control": "no-store" } });
}
/** Local and Crew both call this handler through the same scoped route caller. */
export async function serveLayoutRecipeRoute(req: Request, url: URL, caller: LayoutRecipeCaller,
  stores: { readonly plans: PlanStore; readonly operations: OperationStore }): Promise<Response | null> {
  const route = controlRoute(url.pathname, req.method);
  if (!route || !["layout-export", "layout-recipes-plan", "layout-apply", "layout-split-ratio"].includes(route.name)) return null;
  const denied = caller.gate(route.gate);
  if (denied) return denied;
  if (!controlScope(url, "session")) return statusError("scope.malformed", "Malformed portable layout scope", 400);
  const owner = caller.pairedIdentity();
  if (!owner || !/^[a-f0-9]{64}$/.test(owner)) return statusError("control.identity_unavailable", "A validated paired-device identity is required", 403);
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  if (runtime.mux.capabilities.supports.portableLayouts !== true || !runtime.mux.layoutRuntimeIdentity) return statusError("layout.capability_unavailable", "Portable layouts are unavailable on this runtime", 501);
  const runtimeIdentity = await runtime.mux.layoutRuntimeIdentity();
  if (!runtimeIdentity) return statusError("layout.capability_unavailable", "Portable layouts are unavailable on this runtime", 501);

  if (route.name === "layout-export") {
    const tabId = routeTabId(url, route);
    if (!tabId || !runtime.mux.exportPortableLayout) return statusError("layout.target_invalid", "Choose a valid tab to export", 400);
    const snapshot = await runtime.mux.snapshot();
    const tab = snapshot.tabs.find((item) => item.tabId === tabId);
    const workspace = tab && snapshot.spaces.find((item) => item.spaceId === tab.spaceId);
    if (!tab || !workspace?.folder) return statusError("layout.target_invalid", "The selected tab has no supported workspace folder", 409);
    const layout = await runtime.mux.exportPortableLayout({ tabId });
    if (!layout.ok) return statusError(layout.reason === "unsupported" ? "layout.capability_unavailable" : "layout.export_failed", "The layout could not be exported", layout.reason === "unsupported" ? 501 : 409);
    const portable = await portableExport(layout.value, workspace.folder);
    if (!portable) return statusError("layout.export_unsafe", "The layout contains a path outside the workspace", 409);
    caller.audit("layout.export", tabId, runtime.host, runtime.session, "succeeded");
    // SAFETY: the domain layout was built only from bounded, JSON-compatible validated fields.
    const payload = JSON.parse(JSON.stringify({ ok: true, schema: 1, layout: portable })) as JsonValue;
    return layoutJsonResponse(payload, portable);
  }

  if (!stores.operations.isDurable) return statusError("layout.receipts_unavailable", "Durable layout operation custody is unavailable", 503);
  const bodyValue = await readBoundedBody(req, route.maxBodyBytes);
  if (!bodyValue) return statusError("layout.request_invalid", "Malformed or oversized portable layout request", 400);
  const body = record(bodyValue);
  if (!body) return statusError("layout.request_invalid", "Malformed portable layout request", 400);

  if (route.name === "layout-recipes-plan") {
    if (body.kind === "split-ratio") {
      const path = parseBooleanPath(body.path);
      if (!exactKeys(body, ["kind", "tabId", "paneId", "path", "ratio"]) || !safeId(body.tabId) || (body.paneId !== null && !safeId(body.paneId)) ||
          path === null || !safeRatio(body.ratio)) return statusError("layout.invalid_ratio", "The split-ratio plan is malformed", 400);
      const snapshot = await runtime.mux.snapshot();
      const tab = snapshot.tabs.find((row) => row.tabId === body.tabId);
      const exported = await runtime.mux.exportPortableLayout?.({ tabId: body.tabId });
      if (!tab || !exported?.ok) return statusError("layout.invalid_target", "The selected tab is stale or unsupported", 409);
      let current: MuxPortableLayoutNode = exported.value.root;
      for (const bit of path) {
        if (current.type !== "split") return statusError("layout.split_not_found", "The split path does not identify a split", 409);
        current = bit ? current.second : current.first;
      }
      if (current.type !== "split") return statusError("layout.split_not_found", "The split path does not identify a split", 409);
      const payload = JSON.parse(JSON.stringify(body)) as JsonValue;
      const evidence: LayoutRuntimeEvidence = { runtimeIdentity, layout: JSON.parse(JSON.stringify(exported.value)) as JsonValue, state: snapshotEvidence(snapshot, tab.spaceId, tab.tabId) };
      const plan = stores.plans.issue(binding(owner, runtime.host, runtime.session, payload, { tabId: tab.tabId, workspaceId: tab.spaceId }, evidence));
      return layoutJsonResponse({ ok: true, planToken: plan.token, expiresAt: plan.expiresAt, workspaceId: "", tabId: null,
        effects: { starts: [], stops: [], replacementClosesOldTabAfterCreation: false, focusChanges: false } });
    }
    if (!exactKeys(body, ["root", "workspaceId", "tabId", "tabLabel"])) return statusError("layout.request_invalid", "Malformed layout plan", 400);
    const root = parseNode(body.root);
    if (!root || (body.workspaceId !== null && !safeId(body.workspaceId)) || (body.tabId !== null && !safeId(body.tabId)) ||
        !nullableText(body.tabLabel, 128) || (body.workspaceId === null && body.tabId === null)) return statusError("layout.invalid_layout", "The layout shape or target is invalid", 400);
    const launchers = await caller.launchers();
    const resolvedCommands = declaredCommands(root, launchers);
    if (!resolvedCommands) return statusError("layout.command_not_declared", "Every layout command must match a current host-declared launcher", 409);
    const snapshot = await runtime.mux.snapshot();
    const targetTab = body.tabId === null ? null : snapshot.tabs.find((tab) => tab.tabId === body.tabId) ?? null;
    const workspaceId = body.workspaceId ?? targetTab?.spaceId;
    const workspace = workspaceId ? snapshot.spaces.find((space) => space.spaceId === workspaceId) : null;
    if (!workspaceId || !workspace || (targetTab && targetTab.spaceId !== workspaceId) || (body.tabId !== null && !targetTab) || !workspace.folder) return statusError("layout.invalid_target", "The target workspace or tab is stale or unsupported", 409);
    const resolvedRoot = await resolveCwds(resolvedCommands, workspace.folder);
    if (!resolvedRoot) return statusError("layout.cwd_outside_workspace", "Every layout directory must remain inside the selected workspace", 409);
    const normalized = { root: resolvedRoot, workspaceId, tabId: body.tabId ?? null, tabLabel: body.tabLabel ?? null, focus: false };
    const payload = JSON.parse(JSON.stringify(normalized)) as JsonValue;
    const currentLayout = await currentTargetLayout(runtime.mux, snapshot, workspaceId, targetTab?.tabId ?? null);
    const evidence: LayoutRuntimeEvidence = { runtimeIdentity, state: snapshotEvidence(snapshot, workspaceId, targetTab?.tabId ?? null), currentLayout: currentLayout ? JSON.parse(JSON.stringify(currentLayout)) as JsonValue : null };
    if (!evidence || !runtime.mux.applyPortableLayout || !runtime.mux.layoutRuntimeIdentity) return statusError("layout.capability_unavailable", "Portable layout apply is unavailable", 501);
    const target = { workspaceId, tabId: targetTab?.tabId ?? null };
    const plan = stores.plans.issue(binding(owner, runtime.host, runtime.session, payload, target, evidence));
    const starts = leaves(resolvedRoot).map((leaf) => ({ label: redactText(leaf.label ?? "Shell"), command: redactText(leaf.command?.join(" ") ?? "Shell"), cwd: redactText(leaf.cwd ?? ".") }));
    const stops = targetTab ? snapshot.panes.filter((pane) => pane.tabId === targetTab.tabId).map((pane) => ({ label: redactText(pane.paneLabel ?? pane.terminalTitle ?? "Pane") })) : [];
    return layoutJsonResponse({ ok: true, planToken: plan.token, expiresAt: plan.expiresAt, workspaceId: "", tabId: null,
      effects: { starts, stops, replacementClosesOldTabAfterCreation: targetTab !== null, focusChanges: false } });
  }

  if (route.name === "layout-apply") {
    if (!exactKeys(body, ["requestId", "planToken", "root", "workspaceId", "tabId", "tabLabel"]) || !text(body.requestId, 128) || !REQUEST_ID.test(body.requestId) || !text(body.planToken, 128)) return statusError("layout.request_invalid", "Malformed layout apply", 400);
    const planToken = body.planToken;
    const root = parseNode(body.root);
    if (!root || !safeId(body.workspaceId) || (body.tabId !== null && !safeId(body.tabId)) || !nullableText(body.tabLabel, 128)) return statusError("layout.invalid_layout", "The layout shape or target is invalid", 400);
    const launchers = await caller.launchers();
    const resolved = declaredCommands(root, launchers);
    if (!resolved) return statusError("layout.command_not_declared", "Every layout command must match a current host-declared launcher", 409);
    const snapshot = await runtime.mux.snapshot();
    const targetTab = body.tabId === null ? null : snapshot.tabs.find((tab) => tab.tabId === body.tabId) ?? null;
    const workspace = snapshot.spaces.find((space) => space.spaceId === body.workspaceId);
    if (!workspace?.folder || (targetTab && targetTab.spaceId !== workspace.spaceId) || (body.tabId !== null && !targetTab)) return statusError("layout.invalid_target", "The target workspace or tab is stale", 409);
    const resolvedRoot = await resolveCwds(resolved, workspace.folder);
    if (!resolvedRoot) return statusError("layout.cwd_outside_workspace", "Every layout directory must remain inside the selected workspace", 409);
    const normalized = { root: resolvedRoot, workspaceId: workspace.spaceId, tabId: targetTab?.tabId ?? null, tabLabel: body.tabLabel ?? null, focus: false };
    const payload = JSON.parse(JSON.stringify(normalized)) as JsonValue;
    const layoutRead = await currentTargetLayout(runtime.mux, snapshot, workspace.spaceId, targetTab?.tabId ?? null);
    const evidence: LayoutRuntimeEvidence = { runtimeIdentity, state: snapshotEvidence(snapshot, workspace.spaceId, targetTab?.tabId ?? null), currentLayout: layoutRead ? JSON.parse(JSON.stringify(layoutRead)) as JsonValue : null };
    const target = { workspaceId: workspace.spaceId, tabId: targetTab?.tabId ?? null };
    const bound = binding(owner, runtime.host, runtime.session, payload, target, evidence);
    const existing = await stores.operations.inspectIntent(body.requestId, owner, runtime.host, runtime.session, "portableLayouts", bound.payloadHash, body.planToken);
    if (existing.kind === "conflict") return statusError("operation.request_id_reused", "This request ID belongs to another layout action", 409);
    if (existing.kind === "match") return operationAnswer(existing.operation);
    const plan = stores.plans.lookup(body.planToken);
    if (!plan || stores.plans.validate(body.planToken, bound) !== "valid" || plan.binding.device !== owner || plan.binding.host !== runtime.host || plan.binding.session !== runtime.session) return statusError("layout.plan_changed", "The layout target or plan changed; review a fresh preview", 409);
    const request: MuxLayoutApplyRequest = { root: cleanImported(resolvedRoot), workspaceId: workspace.spaceId, tabId: targetTab?.tabId ?? null, tabLabel: body.tabLabel ?? null, focus: false };
    const outcome = await stores.operations.execute(body.requestId, bound, body.planToken, async (): Promise<OperationOutcome> => {
      if (stores.plans.validate(planToken, bound) !== "valid") return { state: "refused", code: "layout.plan_changed" };
      stores.plans.consume(planToken);
      const result = await runtime.mux.applyPortableLayout?.(request, async (identity, finalSnapshot) => {
        if (identity !== evidence.runtimeIdentity || caller.pairedIdentity() !== owner) return false;
        if (payloadHash(snapshotEvidence(finalSnapshot, workspace.spaceId, targetTab?.tabId ?? null)) !== payloadHash(snapshotEvidence(snapshot, workspace.spaceId, targetTab?.tabId ?? null))) return false;
        const now = await currentTargetLayout(runtime.mux, finalSnapshot, workspace.spaceId, targetTab?.tabId ?? null);
        const sameLayout = layoutRead === null ? now === null : now !== null &&
          payloadHash(JSON.parse(JSON.stringify(now)) as JsonValue) === payloadHash(JSON.parse(JSON.stringify(layoutRead)) as JsonValue);
        return sameLayout && stores.plans.isUnexpired(plan.expiresAt);
      });
      if (!result) return { state: "refused", code: "layout.capability_unavailable" };
      if (!result.ok) return result.reason === "unreachable" ? { state: "unknown", code: "layout.outcome_unknown" } : { state: "refused", code: result.reason === "gone" ? "layout.invalid_target" : result.reason === "unsupported" ? "layout.capability_unavailable" : "layout.refused" };
      const requestedPanes = leaves(request.root);
      if (result.value.workspaceId !== workspace.spaceId || (targetTab !== null && result.value.tabId !== targetTab.tabId) ||
          result.value.paneIds.length !== requestedPanes.length || new Set(result.value.paneIds).size !== requestedPanes.length ||
          !result.value.paneIds.includes(result.value.focusedPaneId)) return { state: "unknown", code: "layout.outcome_unknown" };
      return { state: "succeeded", result: { kind: "portable-layout", workspaceId: result.value.workspaceId, tabId: result.value.tabId, paneIds: result.value.paneIds, focusedPaneId: result.value.focusedPaneId } };
    });
    caller.audit("layout.apply", targetTab?.tabId ?? workspace.spaceId, runtime.host, runtime.session, outcome.state);
    return operationAnswer(outcome);
  }

  const tabId = routeTabId(url, route);
  const path = parseBooleanPath(body.path);
  if (!tabId || !exactKeys(body, ["requestId", "planToken", "path", "ratio"]) || !text(body.requestId, 128) || !REQUEST_ID.test(body.requestId) || !text(body.planToken, 128) ||
      path === null || !safeRatio(body.ratio)) return statusError("layout.invalid_ratio", "The split-ratio request is malformed", 400);
  const planToken = body.planToken;
  const snapshot = await runtime.mux.snapshot();
  const tab = snapshot.tabs.find((row) => row.tabId === tabId);
  if (!tab) return statusError("layout.invalid_target", "The selected tab is stale", 409);
  const paneId = snapshot.panes.find((pane) => pane.tabId === tabId)?.paneId;
  if (!paneId) return statusError("layout.invalid_target", "The selected tab has no panes", 409);
  const exported = await runtime.mux.exportPortableLayout?.({ tabId });
  if (!exported?.ok) return statusError("layout.export_failed", "The current split tree could not be read", 409);
  if (!selectSplit(exported.value.root, path)) return statusError("layout.split_not_found", "The split path no longer identifies a split", 409);
  const payload = JSON.parse(JSON.stringify({ kind: "split-ratio", tabId, paneId: null, path: body.path, ratio: body.ratio })) as JsonValue;
  const evidence: LayoutRuntimeEvidence = { runtimeIdentity, layout: JSON.parse(JSON.stringify(exported.value)) as JsonValue, state: snapshotEvidence(snapshot, tab.spaceId, tabId) };
  const bound = binding(owner, runtime.host, runtime.session, payload, { tabId, workspaceId: tab.spaceId }, evidence);
  const existing = await stores.operations.inspectIntent(body.requestId, owner, runtime.host, runtime.session, "portableLayouts", bound.payloadHash, body.planToken);
  if (existing.kind === "conflict") return statusError("operation.request_id_reused", "This request ID belongs to another ratio change", 409);
  if (existing.kind === "match") return operationAnswer(existing.operation);
  const plan = stores.plans.lookup(body.planToken);
  if (!plan || stores.plans.validate(body.planToken, bound) !== "valid") return statusError("layout.plan_changed", "The split changed; review a fresh plan", 409);
  const ratioRequest = { tabId, paneId: null, path, ratio: body.ratio };
  const outcome = await stores.operations.execute(body.requestId, bound, body.planToken, async (): Promise<OperationOutcome> => {
    if (stores.plans.validate(planToken, bound) !== "valid") return { state: "refused", code: "layout.plan_changed" };
    stores.plans.consume(planToken);
    const result = await runtime.mux.setSplitRatio?.(ratioRequest, async (identity, finalSnapshot) => {
      if (identity !== evidence.runtimeIdentity || caller.pairedIdentity() !== owner ||
          payloadHash(snapshotEvidence(finalSnapshot, tab.spaceId, tabId)) !== payloadHash(snapshotEvidence(snapshot, tab.spaceId, tabId))) return false;
      const current = await runtime.mux.exportPortableLayout?.({ tabId });
      return current?.ok === true && payloadHash(JSON.parse(JSON.stringify(current.value)) as JsonValue) === payloadHash(JSON.parse(JSON.stringify(exported.value)) as JsonValue) &&
        stores.plans.isUnexpired(plan.expiresAt);
    });
    if (!result) return { state: "refused", code: "layout.capability_unavailable" };
    if (!result.ok) return result.reason === "unreachable" ? { state: "unknown", code: "layout.outcome_unknown" } : { state: "refused", code: result.reason === "gone" ? "layout.invalid_target" : "layout.refused" };
    if (result.value.tabId !== tabId || selectSplit(result.value.root, path)?.ratio !== body.ratio) return { state: "unknown", code: "layout.outcome_unknown" };
    return { state: "succeeded", result: { kind: "split-ratio", tabId } };
  });
  caller.audit("layout.split_ratio", tabId, runtime.host, runtime.session, outcome.state);
  return operationAnswer(outcome);
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type, anti-slop/require-safety-comment-for-type-assertion */
