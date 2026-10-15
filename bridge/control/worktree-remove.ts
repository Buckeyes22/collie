import { createHash } from "node:crypto";
import { realpath } from "node:fs/promises";
import { controlScope, payloadHash, refusal, refusalResponse } from "./contracts.ts";
import { executePlannedOperation, type PlanBinding, type PlanStore } from "./plans.ts";
import type { OperationStore, OperationView } from "./operations.ts";
import type { JsonObject } from "../json.ts";
import { worktreeStatusEvidence } from "../changes.ts";
import type { MuxAdapter, MuxWorktree } from "../mux/types.ts";
import type { GateLevel } from "../crew/peer-gate.ts";

export interface WorktreeRemoveRuntime {
  readonly host: string;
  readonly session: string | null;
  readonly mux: MuxAdapter;
}
export interface WorktreeRemoveCaller {
  gate(level: GateLevel): Response | null;
  pairedIdentity(): string | null;
  resolve(): Promise<WorktreeRemoveRuntime | Response>;
  audit(action: string, target: string, host: string, session: string | null, outcome: string): void;
}
export interface WorktreeRemoveStores { readonly plans: PlanStore; readonly operations: OperationStore }

interface Evidence {
  readonly workspaceId: string;
  readonly runtimeIdentity: string;
  readonly path: string;
  readonly repoRoot: string;
  readonly branch: string | null;
  readonly changedCount: number;
  readonly statusFingerprint: string;
  readonly paneIdentity: readonly { readonly paneId: string; readonly terminalIncarnation: string | null }[];
  readonly label: string;
}
interface PlanResponse {
  readonly ok: true;
  readonly schema: 1;
  readonly workspaceId: string;
  readonly label: string;
  readonly path: string;
  readonly repoRoot: string;
  readonly branch: string | null;
  readonly changedCount: number;
  readonly force: boolean;
  readonly paneCount: number;
  readonly planToken: string;
  readonly expiresAt: number;
  /** Herdr's shipped remove method has no expected-state/CAS parameters. */
  readonly vendorConditionalRemoval: false;
}

const workspaceRoute = /^\/api\/workspace\/([^/]+)\/worktree\/(?:remove-plan|remove)$/;
const opaqueId = /^[A-Za-z0-9_.:-]{1,128}$/;
const requestIdPattern = /^[A-Za-z0-9_-]{1,128}$/;
const pendingRemovals = new Set<string>();

function digest(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function identityKey(runtime: WorktreeRemoveRuntime, workspaceId: string): string {
  return `${runtime.host}\0${runtime.session ?? ""}\0${workspaceId}`;
}
function stableJson(value: Evidence | Evidence["paneIdentity"]): string { return JSON.stringify(value); }
function wireValue(value: { readonly workspaceId: string; readonly force: boolean }): JsonObject { return { workspaceId: value.workspaceId, force: value.force }; }

async function discover(runtime: WorktreeRemoveRuntime, workspaceId: string): Promise<{ evidence: Evidence } | { code: string; error: string; status: number }> {
  const mux = runtime.mux;
  if (mux.capabilities.supports.removeWorktree !== true || mux.capabilities.supports.listWorktrees !== true || !mux.removeWorktree) {
    return { code: "worktree.remove_unsupported", error: "Safe worktree removal is unavailable on this runtime", status: 501 };
  }
  const runtimeIdentity = await mux.layoutRuntimeIdentity?.();
  if (!runtimeIdentity) return { code: "worktree.remove_unsupported", error: "The owning runtime has no verified incarnation", status: 501 };
  const snapshot = await mux.snapshot();
  const space = snapshot.spaces.find((row) => row.spaceId === workspaceId);
  if (!space) return { code: "worktree.workspace_not_found", error: "Workspace no longer exists", status: 404 };
  if (space.isWorktree !== true || !space.repoRoot || !space.folder) {
    return { code: "worktree.not_linked_worktree", error: "Only a discovered linked worktree can be removed", status: 409 };
  }
  let repoRoot: string;
  let path: string;
  try {
    repoRoot = await realpath(space.repoRoot);
    path = await realpath(space.folder);
  } catch {
    return { code: "worktree.stale_worktree_operation", error: "The discovered checkout changed or is no longer available", status: 409 };
  }
  if (path === repoRoot) return { code: "worktree.not_linked_worktree", error: "The main checkout cannot be removed", status: 409 };
  const listed = await mux.listWorktrees({ repoRoot });
  if (!listed.ok) {
    const code = listed.reason === "unsupported" ? "worktree.remove_unsupported" : listed.reason === "gone" ? "worktree.workspace_not_found" : "worktree.discovery_failed";
    return { code, error: listed.detail, status: listed.reason === "unsupported" ? 501 : 409 };
  }
  const matches = listed.value.filter((row) => row.openSpaceId === workspaceId);
  if (matches.length !== 1) return { code: matches.length === 0 ? "worktree.not_linked_worktree" : "worktree.stale_worktree_operation", error: "The workspace is not the unique owner of a discovered checkout", status: 409 };
  const worktree: MuxWorktree = matches[0]!;
  let listedPath: string;
  try { listedPath = await realpath(worktree.path); }
  catch { return { code: "worktree.stale_worktree_operation", error: "The discovered checkout changed or is no longer available", status: 409 }; }
  if (!worktree.linked || listedPath !== path || worktree.path !== space.folder || space.repoRoot !== (await realpath(space.repoRoot))) {
    return { code: "worktree.stale_worktree_operation", error: "The checkout no longer matches the owning workspace", status: 409 };
  }
  const status = await worktreeStatusEvidence(path, repoRoot);
  if (!status) return { code: "worktree.status_unavailable", error: "Git could not safely read the checkout status", status: 503 };
  const panes = snapshot.panes.filter((pane) => pane.spaceId === workspaceId)
    .map((pane) => ({ paneId: pane.paneId, terminalIncarnation: pane.terminalIncarnation ?? null }))
    .toSorted((a, b) => a.paneId.localeCompare(b.paneId));
  const after = await mux.snapshot();
  const afterSpace = after.spaces.find((row) => row.spaceId === workspaceId);
  const afterPanes = after.panes.filter((pane) => pane.spaceId === workspaceId)
    .map((pane) => ({ paneId: pane.paneId, terminalIncarnation: pane.terminalIncarnation ?? null }))
    .toSorted((a, b) => a.paneId.localeCompare(b.paneId));
  if (await mux.layoutRuntimeIdentity?.() !== runtimeIdentity || !afterSpace || afterSpace.isWorktree !== true || afterSpace.folder !== space.folder || afterSpace.repoRoot !== space.repoRoot || stableJson(panes) !== stableJson(afterPanes)) {
    return { code: "worktree.stale_worktree_operation", error: "The workspace or its panes changed during the removal check", status: 409 };
  }
  return { evidence: {
    workspaceId, runtimeIdentity, path, repoRoot, branch: worktree.branch, changedCount: status.changedCount,
    statusFingerprint: status.fingerprint, paneIdentity: panes, label: space.label,
  } };
}

function binding(device: string, runtime: WorktreeRemoveRuntime, evidence: Evidence, force: boolean): PlanBinding {
  const payload = wireValue({ workspaceId: evidence.workspaceId, force });
  return {
    device, host: runtime.host, session: runtime.session, capability: "worktreeRemove",
    payloadHash: payloadHash(payload), targetIdentity: digest(`worktree:${evidence.workspaceId}`),
    evidence: digest(stableJson(evidence)),
  };
}

/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */
function jsonObject(value: unknown): JsonObject | null {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
  // SAFETY: JSON.parse creates only JSON values; null and arrays are rejected before the object is returned.
  return value as JsonObject;
}
async function readBody(req: Request): Promise<JsonObject | null> {
  const rawLength = req.headers.get("content-length");
  if (rawLength !== null && (!/^\d+$/.test(rawLength) || Number(rawLength) > 8192)) { await req.body?.cancel(); return null; }
  if (!req.body) return null;
  const reader = req.body.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const next = await reader.read(); if (next.done) break;
      size += next.value.byteLength; if (size > 8192) { await reader.cancel(); return null; }
      chunks.push(next.value);
    }
  } catch { await reader.cancel().catch(() => {}); return null; } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    return jsonObject(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)));
  } catch { return null; }
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */

function errorResponse(code: string, error: string, factor: "scope" | "capability" | "operation", status: number): Response {
  return refusalResponse(refusal(code, error, factor), status);
}
const KNOWN_REMOVAL_REFUSALS = ["dirty_worktree_requires_force", "not_linked_worktree", "worktree_operation_in_progress", "stale_worktree_operation", "workspace_not_found", "repository_not_trusted", "ownership_refused", "worktree_remove_failed"] as const;
function knownRemovalRefusal(detail: string): string {
  const bounded = detail.slice(0, 256).replace(/^herdr worktree\.remove: /, "");
  const code = KNOWN_REMOVAL_REFUSALS.find((value) => bounded === value || bounded.startsWith(value + ":") || bounded.startsWith(value + " "));
  return code ? `worktree.${code}` : "worktree.remove_refused";
}
function removalExplanation(code: string | null): string {
  switch (code) {
    case "worktree.dirty_worktree_requires_force": return "The checkout changed. Review a separate force plan before discarding local changes.";
    case "worktree.worktree_operation_in_progress": return "Another worktree operation is in progress. Refresh before reviewing a new plan.";
    case "worktree.stale_worktree_operation": case "plan.changed": return "The planned checkout or runtime changed. Refresh and review the target again.";
    case "worktree.repository_not_trusted": return "The owning runtime refused this repository under its trust policy.";
    case "worktree.ownership_refused": return "The owning runtime refused ownership of this checkout.";
    case "worktree.workspace_not_found": return "The planned workspace no longer exists.";
    case "plan.expired": return "The removal plan expired before dispatch. Review a fresh plan.";
    case "worktree.authority_changed": return "Write authority changed before dispatch. Refresh before reviewing a new plan.";
    default: return code?.startsWith("worktree.") ? "The owning runtime refused worktree removal. No automatic retry was made." : "Worktree removal outcome requires reconciliation";
  }
}
function resultResponse(operation: OperationView): Response {
  if (operation.state === "succeeded" && operation.result?.kind === "worktree-remove") {
    return Response.json({ ok: true, type: "worktree_removed", workspaceId: operation.result.workspaceId, path: operation.result.path, branch: operation.result.branch, forced: operation.result.forced, operation }, { headers: { "cache-control": "no-store" } });
  }
  const status = operation.state === "pending" || operation.state === "unknown" ? 202 : 409;
  return Response.json({ ok: false, operation, code: operation.code ?? `operation.${operation.state}`, error: removalExplanation(operation.code), source: "collie", factor: "operation" }, { status, headers: { "cache-control": "no-store", "x-collie-error-source": "collie", "x-collie-error-factor": "operation", "x-collie-error-code": operation.code ?? `operation.${operation.state}` } });
}

/** One explicit host-owned read/plan and one owner-dispatched removal route. */
export async function serveWorktreeRemoveRoute(req: Request, url: URL, caller: WorktreeRemoveCaller, stores: WorktreeRemoveStores): Promise<Response | null> {
  const match = url.pathname.match(workspaceRoute);
  if (!match) return null;
  const isPlan = url.pathname.endsWith("/remove-plan");
  const denied = caller.gate(isPlan ? "read" : "write");
  if (denied) return denied;
  if (!controlScope(url, "session")) return errorResponse("scope.malformed", "Malformed worktree scope", "scope", 400);
  let workspaceId: string;
  try { workspaceId = decodeURIComponent(match[1]!); } catch { return errorResponse("worktree.workspace_id_invalid", "Invalid workspace identity", "scope", 400); }
  if (!opaqueId.test(workspaceId)) return errorResponse("worktree.workspace_id_invalid", "Invalid workspace identity", "scope", 400);
  const forceRows = url.searchParams.getAll("force");
  if (forceRows.length > (isPlan ? 1 : 0) || url.searchParams.getAll("host").length > 1 || url.searchParams.getAll("session").length > 1) return errorResponse("scope.malformed", "Malformed worktree scope", "scope", 400);
  if ([...url.searchParams.keys()].some((key) => !["host", "session", ...(isPlan ? ["force"] : [])].includes(key))) return errorResponse("scope.malformed", "Unexpected worktree selector", "scope", 400);
  const forceText = forceRows[0];
  if (forceText !== undefined && forceText !== "true" && forceText !== "false") return errorResponse("worktree.force_invalid", "Force must be true or false", "operation", 400);
  const force = forceText === "true";
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  const device = caller.pairedIdentity();
  if (!device || !/^[a-f0-9]{64}$/.test(device)) return errorResponse("device.identity_required", "Worktree removal requires an identified paired device", "capability", 403);
  if (!stores.operations.isDurable || !stores.operations.isReady()) return errorResponse("worktree.receipts_unavailable", "Durable worktree removal receipts are unavailable", "capability", 503);
  if (runtime.mux.capabilities.supports.removeWorktree !== true) return errorResponse("worktree.remove_unsupported", "Worktree removal is unavailable on this runtime", "capability", 501);

  const lockKey = identityKey(runtime, workspaceId);
  if (isPlan) {
    if (req.method !== "GET") return new Response("method not allowed", { status: 405 });
    if (pendingRemovals.has(lockKey)) return errorResponse("worktree.worktree_operation_in_progress", "A worktree operation is already in progress", "operation", 409);
    const found = await discover(runtime, workspaceId);
    if (!("evidence" in found)) return errorResponse(found.code, found.error, "operation", found.status);
    if (found.evidence.changedCount > 0 && !force) {
      return Response.json({ ok: false, code: "worktree.dirty_worktree_requires_force", error: `This checkout has ${found.evidence.changedCount} changed or untracked entries. A separate force confirmation is required.`, changedCount: found.evidence.changedCount, forceAvailable: true, source: "collie", factor: "operation" }, { status: 409, headers: { "cache-control": "no-store" } });
    }
    const plan = stores.plans.issue(binding(device, runtime, found.evidence, force));
    const body: PlanResponse = {
      ok: true, schema: 1, workspaceId, label: found.evidence.label, path: found.evidence.path,
      repoRoot: found.evidence.repoRoot, branch: found.evidence.branch, changedCount: found.evidence.changedCount,
      force, paneCount: found.evidence.paneIdentity.length, planToken: plan.token, expiresAt: plan.expiresAt,
      vendorConditionalRemoval: false,
    };
    return Response.json(body, { headers: { "cache-control": "no-store" } });
  }

  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  const body = await readBody(req);
  /* eslint-disable anti-slop/no-runtime-typeof */
  if (!body || Object.keys(body).toSorted().join(",") !== "force,planToken,requestId" || typeof body.force !== "boolean" || typeof body.planToken !== "string" || body.planToken.length > 128 || typeof body.requestId !== "string" || !requestIdPattern.test(body.requestId)) return errorResponse("worktree.request_invalid", "Malformed worktree removal request", "operation", 400);
  /* eslint-enable anti-slop/no-runtime-typeof */
  const forceRequested = body.force === true;
  const planToken = body.planToken;
  const requestId = body.requestId;
  const requestPayload = wireValue({ workspaceId, force: forceRequested });
  const prior = await stores.operations.inspectIntent(requestId, device, runtime.host, runtime.session, "worktreeRemove", payloadHash(requestPayload), planToken);
  if (prior.kind === "conflict") return errorResponse("operation.request_mismatch", "This request ID is already bound to another action", "operation", 409);
  if (prior.kind === "match") return resultResponse(prior.operation);
  const plan = stores.plans.lookup(planToken);
  if (!plan) return errorResponse("plan.expired", "The worktree removal plan expired; review it again", "operation", 409);
  const expected = binding(device, runtime, {
    workspaceId,
    runtimeIdentity: "", path: "", repoRoot: "", branch: null, changedCount: 0,
    statusFingerprint: "", paneIdentity: [], label: "",
  }, forceRequested);
  // The intent payload and server-issued target stay fixed while evidence is freshly collected below.
  if (plan.binding.payloadHash !== payloadHash(requestPayload) || plan.binding.device !== device || plan.binding.host !== runtime.host || plan.binding.session !== runtime.session || plan.binding.targetIdentity !== digest(`worktree:${workspaceId}`) || plan.binding.capability !== "worktreeRemove" || expected.payloadHash !== plan.binding.payloadHash) return errorResponse("plan.changed", "The worktree removal plan belongs to another target or choice", "operation", 409);
  if (pendingRemovals.has(lockKey)) return errorResponse("worktree.worktree_operation_in_progress", "A worktree operation is already in progress", "operation", 409);
  pendingRemovals.add(lockKey);
  try {
    const operation = await executePlannedOperation(stores.operations, stores.plans, requestId, plan, async () => {
      const current = await discover(runtime, workspaceId);
      return "evidence" in current
        ? binding(device, runtime, current.evidence, forceRequested)
        : { ...plan.binding, evidence: digest(current.code) };
    }, async () => {
      const final = await discover(runtime, workspaceId);
      if (!("evidence" in final)) return { state: "refused", code: final.code };
      if (digest(stableJson(final.evidence)) !== plan.binding.evidence) return { state: "refused", code: "plan.changed" };
      // No awaits after this final gate: the adapter repeats it at its named RPC boundary.
      let refusedAtBoundary: string | null = null;
      const admission = (runtimeIdentity: string): boolean => {
        if (!stores.plans.isUnexpired(plan.expiresAt)) { refusedAtBoundary = "plan.expired"; return false; }
        const allowed = caller.gate("write") === null && caller.pairedIdentity() === device &&
          runtimeIdentity === final.evidence.runtimeIdentity && runtime.mux.capabilities.supports.removeWorktree === true;
        if (!allowed) refusedAtBoundary = "worktree.authority_changed";
        return allowed;
      };
      if (!stores.plans.isUnexpired(plan.expiresAt)) return { state: "refused", code: "plan.expired" };
      if (!admission(final.evidence.runtimeIdentity)) return { state: "refused", code: "worktree.authority_changed" };
      const result = await runtime.mux.removeWorktree!({ workspaceId, force: forceRequested }, admission, async () => {
        const prepared = await discover(runtime, workspaceId);
        const same = "evidence" in prepared && digest(stableJson(prepared.evidence)) === plan.binding.evidence;
        if (!same) refusedAtBoundary = "plan.changed";
        return same;
      });
      // A later admission diagnosis cannot prove no effect after an accepted prefix.
      if (!result.ok && result.reason === "unreachable") return { state: "unknown", code: "worktree.outcome_unknown" };
      if (!result.ok && refusedAtBoundary !== null) return { state: "refused", code: refusedAtBoundary };
      if (!result.ok) return { state: "refused", code: result.reason === "gone" ? "worktree.workspace_not_found" : result.reason === "unsupported" ? "worktree.remove_unsupported" : knownRemovalRefusal(result.detail) };
      if (result.value.workspaceId !== workspaceId || result.value.path !== final.evidence.path || result.value.forced !== forceRequested) return { state: "unknown", code: "worktree.response_mismatch" };
      await runtime.mux.refresh().catch(() => {});
      return { state: "succeeded", result: { kind: "worktree-remove", workspaceId, path: final.evidence.path, branch: final.evidence.branch, forced: forceRequested } };
    });
    caller.audit("worktree.remove", workspaceId, runtime.host, runtime.session, operation.state);
    return resultResponse(operation);
  } catch (error) {
    const message = error instanceof Error ? error.message : "operation.unavailable";
    const code = message.includes("request_id_reused") ? "operation.request_mismatch" : message.includes("capacity") ? "operation.capacity" : "worktree.receipts_unavailable";
    return errorResponse(code, "The removal request could not be recorded safely", "operation", code === "operation.request_mismatch" ? 409 : 503);
  } finally {
    pendingRemovals.delete(lockKey);
  }
}
