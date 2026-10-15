import type { JsonObject, JsonValue } from "../json.ts";
import type { MuxAdapter, MuxSnapshot } from "../mux/types.ts";
import type { GateLevel } from "../crew/peer-gate.ts";
import { controlScope, payloadHash, refusal, refusalResponse, supportsNew } from "./contracts.ts";
import { executePlannedOperation, PlanStore, type PlanBinding } from "./plans.ts";
import { OperationStore } from "./operations.ts";
import type { MuxCapability } from "../mux/capabilities.ts";

export interface OrganizationRuntime { readonly host: string; readonly session: string | null; readonly mux: MuxAdapter }
export interface OrganizationCaller {
  gate(level: GateLevel): Response | null;
  resolve(): Promise<OrganizationRuntime | Response>;
  pairedIdentity(): string | null;
  audit(action: string, target: string, host: string, session: string | null, outcome: string): void;
}
export interface OrganizationStores { readonly plans: PlanStore; readonly operations: OperationStore }

type Action =
  | { readonly kind: "workspace-rename"; readonly workspaceId: string; readonly label: string }
  | { readonly kind: "workspace-close"; readonly workspaceId: string; readonly closeGroup: boolean }
  | { readonly kind: "workspace-move"; readonly workspaceId: string; readonly insertIndex: number }
  | { readonly kind: "workspace-reorder"; readonly workspaceIds: readonly string[]; readonly beforeWorkspaceId: string | null }
  | { readonly kind: "workspace-focus"; readonly workspaceId: string }
  | { readonly kind: "tab-move"; readonly tabId: string; readonly insertIndex: number }
  | { readonly kind: "tab-focus"; readonly tabId: string }
  | { readonly kind: "tab-rename"; readonly tabId: string; readonly label: string }
  | { readonly kind: "tab-close"; readonly tabId: string };

const routes = [
  { re: /^\/api\/workspace\/([^/]+)\/(rename|close|move|focus)(?:\/plan)?$/, capability: null },
  { re: /^\/api\/workspaces\/reorder(?:\/plan)?$/, capability: "workspaceMoveBlock" },
  { re: /^\/api\/tab\/([^/]+)\/(rename|close|move|focus)(?:\/plan)?$/, capability: null },
] as const;
const capability = {
  "workspace-rename": "workspaceRename", "workspace-close": "workspaceClose", "workspace-move": "workspaceMove",
  "workspace-reorder": "workspaceMoveBlock", "workspace-focus": "workspaceFocus", "tab-move": "tabMove", "tab-focus": "tabFocus",
  "tab-rename": "renameTab", "tab-close": "closeTab",
} satisfies Readonly<Record<Action["kind"], MuxCapability>>;
const idPattern = /^[A-Za-z0-9_.:-]{1,128}$/;
const requestPattern = /^[A-Za-z0-9_-]{1,128}$/;
/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof */
const labelOk = (value: unknown): value is string => typeof value === "string" && value.length > 0 && value.length <= 128 && !Array.from(value).some((c) => c.charCodeAt(0) < 32);
const boundedIndex = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 2048;
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof */

/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */
function object(value: unknown): JsonObject | null {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
  // SAFETY: JSON.parse produced a plain JSON object; arrays and null were rejected immediately above.
  return value as JsonObject;
}
async function body(req: Request): Promise<JsonObject | null> {
  const declared = req.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > 8192)) { await req.body?.cancel(); return null; }
  if (!req.body) return null;
  const reader = req.body.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const row = await reader.read(); if (row.done) break;
      size += row.value.byteLength; if (size > 8192) { await reader.cancel(); return null; }
      chunks.push(row.value);
    }
  } catch { await reader.cancel().catch(() => {}); return null; } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return object(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes))); } catch { return null; }
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */

function decoded(raw: string | undefined): string | null {
  if (!raw) return null;
  try { const value = decodeURIComponent(raw); return idPattern.test(value) ? value : null; } catch { return null; }
}
/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/require-safety-comment-for-type-assertion, unicorn/no-array-sort */
function routeAction(url: URL, parsed: JsonObject): { action: Action; plan: boolean } | null {
  const plan = url.pathname.endsWith("/plan");
  const payload = { ...parsed };
  delete payload.requestId;
  delete payload.planToken;
  const match = routes.map((row) => ({ ...row, match: row.re.exec(url.pathname) })).find((row) => row.match);
  if (!match) return null;
  const verb = match.match?.[2];
  if (url.pathname === "/api/workspaces/reorder" || url.pathname === "/api/workspaces/reorder/plan") {
    if (Object.keys(payload).sort().join(",") !== "beforeWorkspaceId,workspaceIds" || !Array.isArray(payload.workspaceIds) || payload.workspaceIds.length === 0 || payload.workspaceIds.length > 256 || !payload.workspaceIds.every((id) => typeof id === "string" && idPattern.test(id)) || new Set(payload.workspaceIds).size !== payload.workspaceIds.length || !(payload.beforeWorkspaceId === null || typeof payload.beforeWorkspaceId === "string" && idPattern.test(payload.beforeWorkspaceId))) return null;
    // SAFETY: the branch above validates every row as an opaque bounded id and the anchor as string|null.
    return { action: { kind: "workspace-reorder", workspaceIds: payload.workspaceIds as string[], beforeWorkspaceId: payload.beforeWorkspaceId as string | null }, plan };
  }
  const target = decoded(match.match?.[1]); if (!target) return null;
  const isTab = url.pathname.includes("/tab/");
  if (!isTab && verb === "rename" && Object.keys(payload).toSorted().join(",") === "label" && labelOk(payload.label)) return { action: { kind: "workspace-rename", workspaceId: target, label: payload.label }, plan };
  if (!isTab && verb === "close" && Object.keys(payload).toSorted().join(",") === "closeGroup" && typeof payload.closeGroup === "boolean") return { action: { kind: "workspace-close", workspaceId: target, closeGroup: payload.closeGroup }, plan };
  if (verb === "move" && Object.keys(payload).toSorted().join(",") === "insertIndex" && boundedIndex(payload.insertIndex)) {
    if (isTab) return { action: { kind: "tab-move", tabId: target, insertIndex: payload.insertIndex }, plan };
    return { action: { kind: "workspace-move", workspaceId: target, insertIndex: payload.insertIndex }, plan };
  }
  if (isTab && verb === "rename" && Object.keys(payload).toSorted().join(",") === "label" && labelOk(payload.label)) return { action: { kind: "tab-rename", tabId: target, label: payload.label }, plan };
  if (isTab && verb === "close" && Object.keys(payload).length === 0) return { action: { kind: "tab-close", tabId: target }, plan };
  if (verb === "focus" && Object.keys(payload).length === 0) {
    if (isTab) return { action: { kind: "tab-focus", tabId: target }, plan };
    return { action: { kind: "workspace-focus", workspaceId: target }, plan };
  }
  return null;
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/require-safety-comment-for-type-assertion, unicorn/no-array-sort */
/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/require-safety-comment-for-type-assertion */
function json(value: unknown): JsonValue {
  // SAFETY: this function receives only closed mux model values and drops undefined fields by JSON encoding.
  return JSON.parse(JSON.stringify(value)) as JsonValue;
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/require-safety-comment-for-type-assertion */
function capabilityFor(action: Action): MuxCapability { return capability[action.kind]; }
function actionJson(action: Action): JsonValue { return json(action); }
function allSnapshot(snapshot: MuxSnapshot): JsonValue { return json({ spaces: snapshot.spaces, tabs: snapshot.tabs, panes: snapshot.panes.map((p) => ({ paneId: p.paneId, terminalIncarnation: p.terminalIncarnation, spaceId: p.spaceId, tabId: p.tabId, alive: p.alive, focused: p.focused })) }); }
function closeGroup(snapshot: MuxSnapshot, workspaceId: string): string[] {
  const target = snapshot.spaces.find((space) => space.spaceId === workspaceId);
  if (!target?.repoRoot) return [workspaceId];
  // Herdr's worktree listing binds every linked workspace to the same source repo root.
  return snapshot.spaces.filter((space) => space.spaceId === workspaceId || (space.repoRoot === target.repoRoot && (space.isWorktree === true || target.isWorktree === true))).map((space) => space.spaceId).toSorted((a, b) => snapshot.spaces.findIndex((s) => s.spaceId === a) - snapshot.spaces.findIndex((s) => s.spaceId === b));
}
function validateAgainstSnapshot(action: Action, snapshot: MuxSnapshot): string | null {
  if (action.kind === "workspace-reorder") {
    const spaces = snapshot.spaces;
    if (!action.workspaceIds.every((id) => spaces.some((s) => s.spaceId === id)) || (action.beforeWorkspaceId !== null && !spaces.some((s) => s.spaceId === action.beforeWorkspaceId)) || action.workspaceIds.includes(action.beforeWorkspaceId ?? "")) return "organization.target_changed";
    return null;
  }
  if (action.kind === "workspace-rename" || action.kind === "workspace-close" || action.kind === "workspace-move" || action.kind === "workspace-focus") {
    const spaces = snapshot.spaces;
    const workspaceId = action.workspaceId;
    if (!spaces.some((space) => space.spaceId === workspaceId)) return "organization.target_gone";
    if (action.kind === "workspace-move" && action.insertIndex > spaces.length) return "organization.index_invalid";
    return null;
  }
  const tabId = action.tabId;
  const tab = snapshot.tabs.find((row) => row.tabId === tabId);
  if (!tab) return "organization.target_gone";
  if (action.kind === "tab-move") {
    const siblings = snapshot.tabs.filter((row) => row.spaceId === tab.spaceId);
    if (action.insertIndex > siblings.length) return "organization.index_invalid";
  }
  return null;
}
function effect(action: Action, snapshot: MuxSnapshot): JsonValue {
  if (action.kind === "workspace-close") {
    const ids = action.closeGroup ? closeGroup(snapshot, action.workspaceId) : [action.workspaceId];
    const groupIds = closeGroup(snapshot, action.workspaceId);
    const spaces = new Set(ids);
    const panes = snapshot.panes.filter((pane) => spaces.has(pane.spaceId)).map((pane) => ({ paneId: pane.paneId, workspaceId: pane.spaceId, tabId: pane.tabId, label: pane.paneLabel ?? pane.paneId }));
    return { kind: "close", workspaceIds: ids, workspaceLabels: ids.map((id) => snapshot.spaces.find((space) => space.spaceId === id)?.label ?? id), panes, groupPreviewIds: groupIds, groupCloseRequired: groupIds.length > 1, deletesCheckouts: false };
  }
  if (action.kind === "tab-close") return { kind: action.kind, tabId: action.tabId, workspaceId: snapshot.tabs.find((tab) => tab.tabId === action.tabId)?.spaceId, panes: snapshot.panes.filter((pane) => pane.tabId === action.tabId).map((pane) => ({ paneId: pane.paneId, label: pane.paneLabel ?? pane.paneId })), closesProcesses: true };
  if (action.kind === "workspace-move") return { kind: action.kind, workspaceId: action.workspaceId, insertIndex: action.insertIndex, focusChanges: false };
  if (action.kind === "workspace-reorder") return { kind: action.kind, workspaceIds: [...action.workspaceIds], beforeWorkspaceId: action.beforeWorkspaceId, focusChanges: false };
  if (action.kind === "tab-move") return { kind: action.kind, tabId: action.tabId, insertIndex: action.insertIndex, workspaceId: snapshot.tabs.find((t) => t.tabId === action.tabId)?.spaceId, crossWorkspace: false, focusChanges: false };
  if (action.kind === "tab-rename") return { kind: action.kind, tabId: action.tabId, label: action.label };
  if (action.kind === "workspace-focus" || action.kind === "tab-focus") return { kind: action.kind, targetId: action.kind === "workspace-focus" ? action.workspaceId : action.tabId, focusChanges: true, explicitOperatorAction: true };
  return { kind: action.kind, targetId: action.workspaceId, label: action.label, focusChanges: false };
}
function call(mux: MuxAdapter, action: Action, finalAdmission: import("../mux/types.ts").MuxOrganizationFinalAdmission): Promise<import("../mux/types.ts").MuxAck> | null {
  if (action.kind === "workspace-rename") return mux.renameSpace?.(action.workspaceId, action.label, finalAdmission) ?? null;
  if (action.kind === "workspace-close") return mux.closeSpace?.(action.workspaceId, action.closeGroup, finalAdmission) ?? null;
  if (action.kind === "workspace-move") return mux.moveSpace?.(action.workspaceId, action.insertIndex, finalAdmission) ?? null;
  if (action.kind === "workspace-reorder") return mux.moveSpaceBlock?.(action.workspaceIds, action.beforeWorkspaceId, finalAdmission) ?? null;
  if (action.kind === "workspace-focus") return mux.focusSpace?.(action.workspaceId, finalAdmission) ?? null;
  if (action.kind === "tab-move") return mux.moveTab?.(action.tabId, action.insertIndex, finalAdmission) ?? null;
  if (action.kind === "tab-focus") return mux.focusTab?.(action.tabId, finalAdmission) ?? null;
  if (action.kind === "tab-rename") return mux.renameTab(action.tabId, action.label, finalAdmission);
  return mux.closeTab(action.tabId, finalAdmission);
}
async function runtimeIdentity(mux: MuxAdapter): Promise<string | null> {
  // Herdr supplies a stronger positive handshake/endpoint witness. Other adapters need only
  // declare these concrete verbs; their owner-scoped snapshot remains the plan evidence.
  try { return mux.layoutRuntimeIdentity ? await mux.layoutRuntimeIdentity() : `adapter:${mux.mux}`; }
  catch { return null; }
}
function currentBinding(device: string, runtime: OrganizationRuntime, action: Action, snapshot: MuxSnapshot, runtimeProof: string): PlanBinding {
  const targetIdentity = payloadHash(json(action.kind === "workspace-reorder" ? [...action.workspaceIds] : action.kind === "workspace-rename" || action.kind === "workspace-close" || action.kind === "workspace-move" || action.kind === "workspace-focus" ? [action.workspaceId] : [action.tabId]));
  return { device, host: runtime.host, session: runtime.session, capability: capabilityFor(action), payloadHash: payloadHash(actionJson(action)), targetIdentity, evidence: payloadHash(json({ runtimeIdentity: runtimeProof, snapshot: allSnapshot(snapshot) })) };
}
function statusFor(view: import("./operations.ts").OperationView): Response {
  if (view.state === "unknown" || view.state === "pending") return Response.json({ ok: false, code: "organization.outcome_unknown", operation: view }, { status: 202, headers: { "cache-control": "no-store" } });
  if (view.state === "refused") return refusalResponse(refusal(view.code ?? "organization.refused", "The organization action was refused", "operation"), 409);
  return Response.json({ ok: true, operation: view }, { headers: { "cache-control": "no-store" } });
}

export async function serveOrganizationRoute(req: Request, url: URL, caller: OrganizationCaller, stores: OrganizationStores): Promise<Response | null> {
  if (req.method !== "POST" || !routes.some((route) => route.re.test(url.pathname))) return null;
  const planRoute = url.pathname.endsWith("/plan");
  // Tab rename/close already have a legacy one-step contract. The additive reviewed form is
  // distinguished by its durable operation fields; keep the original body available to the
  // existing handler for callers that have not upgraded.
  const legacyTabAction = !planRoute && /^\/api\/tab\/[^/]+\/(?:rename|close)$/.test(url.pathname);
  const bodyRequest = legacyTabAction ? req.clone() : req;
  const denied = caller.gate(planRoute ? "read" : "write"); if (denied) return denied;
  const values = (await body(bodyRequest));
  if (legacyTabAction && (!values || (!Object.hasOwn(values, "requestId") && !Object.hasOwn(values, "planToken")))) return null;
  if (!values) return refusalResponse(refusal("organization.body_invalid", "Invalid organization request", "operation"), 400);
  const parsed = routeAction(url, values); if (!parsed) return refusalResponse(refusal("organization.request_invalid", "Invalid organization request", "operation"), 400);
  if (parsed.plan !== planRoute) return refusalResponse(refusal("organization.request_invalid", "Invalid organization request", "operation"), 400);
  const metadata = ["planToken", "requestId"].filter((key) => Object.hasOwn(values, key));
  if (planRoute ? metadata.length !== 0 : metadata.length !== 2) return refusalResponse(refusal("organization.request_invalid", "Organization plan identity does not match this request", "operation"), 400);
  const actionKeys = Object.keys(values).filter((key) => key !== "planToken" && key !== "requestId").toSorted();
  const expectedKeys = parsed.action.kind === "workspace-rename" || parsed.action.kind === "tab-rename" ? ["label"] : parsed.action.kind === "workspace-close" ? ["closeGroup"] : parsed.action.kind === "workspace-move" || parsed.action.kind === "tab-move" ? ["insertIndex"] : parsed.action.kind === "workspace-reorder" ? ["beforeWorkspaceId", "workspaceIds"] : [];
  if (actionKeys.join(",") !== expectedKeys.toSorted().join(",")) return refusalResponse(refusal("organization.request_invalid", "Organization request has unexpected fields", "operation"), 400);
  if (!controlScope(url, "session")) return refusalResponse(refusal("scope.malformed", "Malformed organization scope", "scope"), 400);
  const device = caller.pairedIdentity(); if (!device || !/^[a-f0-9]{64}$/.test(device)) return refusalResponse(refusal("device.identity_required", "Organization actions require the originating paired device", "device"), 403);
  const runtime = await caller.resolve(); if (runtime instanceof Response) return runtime;
  const cap = capabilityFor(parsed.action);
  if (!supportsNew(runtime.mux.capabilities.supports, cap) || !callAvailable(runtime.mux, parsed.action)) return refusalResponse(refusal("capability.unsupported", "This multiplexer does not support this organization action", "capability"), 501);
  const identity = await runtimeIdentity(runtime.mux);
  if (!identity) return refusalResponse(refusal("organization.runtime_unverified", "The owning multiplexer runtime is not verified", "identity"), 503);
  let snapshot: MuxSnapshot;
  try { snapshot = await runtime.mux.snapshot(); } catch { return refusalResponse(refusal("organization.snapshot_unavailable", "The owning multiplexer snapshot is unavailable", "unknown"), 503); }
  const validation = validateAgainstSnapshot(parsed.action, snapshot);
  if (validation) return refusalResponse(refusal(validation, "The target or ordering changed", "scope"), 409);
  const binding = currentBinding(device, runtime, parsed.action, snapshot, identity);
  if (planRoute) {
    const plan = stores.plans.issue(binding);
    return Response.json({ ok: true, planToken: plan.token, expiresAt: plan.expiresAt, action: actionJson(parsed.action), effects: effect(parsed.action, snapshot), workspaceOrder: snapshot.spaces.map((s) => s.spaceId), tabOrder: snapshot.tabs.filter((t) => t.spaceId === (snapshot.tabs.find((row) => row.tabId === ("tabId" in parsed.action ? parsed.action.tabId : ""))?.spaceId)).map((t) => t.tabId) }, { headers: { "cache-control": "no-store" } });
  }
  // SAFETY: `values` is a bounded, fatal-UTF8 JSON object decoded at the request boundary above.
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (typeof values.requestId !== "string" || !requestPattern.test(values.requestId) || typeof values.planToken !== "string") return refusalResponse(refusal("organization.request_invalid", "Mutation requires its plan and request identity", "operation"), 400);
  if (!Object.keys(values).includes("requestId") || !Object.keys(values).includes("planToken")) return refusalResponse(refusal("organization.request_invalid", "Mutation requires its plan and request identity", "operation"), 400);
  try {
    const existing = await stores.operations.inspectIntent(values.requestId, device, runtime.host, runtime.session, cap, binding.payloadHash, values.planToken);
    if (existing.kind === "match") return statusFor(existing.operation);
    if (existing.kind === "conflict") return refusalResponse(refusal("operation.request_id_reused", "This request identity belongs to a different organization action", "operation"), 409);
  } catch { return refusalResponse(refusal("operation.unavailable", "The durable operation registry is temporarily unavailable", "operation"), 503); }
  const plan = stores.plans.lookup(values.planToken);
  if (!plan || plan.binding.device !== device || plan.binding.host !== runtime.host || plan.binding.session !== runtime.session || plan.binding.capability !== cap || plan.binding.payloadHash !== binding.payloadHash || plan.binding.targetIdentity !== binding.targetIdentity) return refusalResponse(refusal("plan.invalid", "Organization plan is missing or does not match this action", "operation"), 409);
  const current = async (): Promise<PlanBinding> => {
    try {
      const latest: MuxSnapshot = await runtime.mux.snapshot();
      const latestIdentity = await runtimeIdentity(runtime.mux);
      if (!latestIdentity || latestIdentity !== identity || validateAgainstSnapshot(parsed.action, latest) !== null) return { ...binding, evidence: "changed" };
      return currentBinding(device, runtime, parsed.action, latest, latestIdentity);
    } catch { return { ...binding, evidence: "unavailable" }; }
  };
  const finalAdmission = (): boolean => caller.gate("write") === null && caller.pairedIdentity() === device;
  const post = await executePlannedOperation(stores.operations, stores.plans, values.requestId, plan, current, async () => {
    try {
      const pending = call(runtime.mux, parsed.action, finalAdmission); if (!pending) return { state: "refused", code: "capability.unavailable" };
      const outcome = await pending;
      if (!outcome.ok) return outcome.reason === "unreachable"
        ? { state: "unknown", code: "organization.mux_unreachable" }
        : { state: "refused", code: outcome.reason === "unsupported" ? "capability.unsupported" : `mux.${outcome.reason}` };
      return { state: "succeeded" };
    } catch { return { state: "unknown", code: "organization.outcome_unknown" }; }
  });
  caller.audit(`organization.${parsed.action.kind}`, plan.binding.targetIdentity, runtime.host, runtime.session, post.state);
  return statusFor(post);
}
function callAvailable(mux: MuxAdapter, action: Action): boolean {
  if (action.kind === "workspace-rename") return mux.renameSpace !== undefined;
  if (action.kind === "workspace-close") return mux.closeSpace !== undefined;
  if (action.kind === "workspace-move") return mux.moveSpace !== undefined;
  if (action.kind === "workspace-reorder") return mux.moveSpaceBlock !== undefined;
  if (action.kind === "workspace-focus") return mux.focusSpace !== undefined;
  if (action.kind === "tab-move") return mux.moveTab !== undefined;
  if (action.kind === "tab-focus") return mux.focusTab !== undefined;
  if (action.kind === "tab-rename") return true;
  return true;
}
