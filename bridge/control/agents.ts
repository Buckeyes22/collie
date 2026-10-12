import { redactText } from "../redact.ts";
import { createHmac, timingSafeEqual } from "node:crypto";
import type { GateLevel } from "../crew/peer-gate.ts";
import type { JsonObject, JsonValue } from "../json.ts";
import type { MuxAdapter } from "../mux/types.ts";
import { AGENT_LIMITS, ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION, sameAgentOccupant, type AgentControlInfo, type AgentOccupant } from "./agent-models.ts";
import { agentKeys, agentRecord, agentRequestId, agentText, parseAgentAction, readAgentBody } from "./agent-requests.ts";
import { canonicalPayload, controlScope, refusal, refusalResponse } from "./contracts.ts";
import type { OperationStore, OperationView } from "./operations.ts";
import type { PlanBinding } from "./plans.ts";

export interface AgentRuntime {
  readonly host: string; readonly session: string | null; readonly mux: MuxAdapter;
  /** Checks the captured registry instance and current terminal, never resolves a successor. */
  current(expected: AgentOccupant): boolean;
  currentRuntime(): boolean;
}
export interface AgentCaller {
  gate(level: GateLevel): Response | null;
  pairedIdentity(): string | null;
  readonly redact: boolean;
  readonly receiptKey: Uint8Array | null | undefined;
  resolve(): Promise<AgentRuntime | Response>;
  composerSafe(agent: string, text: string): boolean;
  visiblePrompt(text: string): string;
  audit(action: string, paneId: string, state: string): void;
}
interface AgentPublishedResult { value: import("./agent-models.ts").AgentActionResult | null }

export interface AgentContext {
  readonly runtime: AgentRuntime; readonly owner: string; readonly key: Uint8Array;
  readonly original: AgentControlInfo; readonly witness: string;
  readonly current: (runtime: string) => boolean;
  /** Captured original paired/read owner, only for verified observation transitions. */
  readonly observationOwner: import("./agent-models.ts").AgentObservationOwner;
}
export const AGENT_ACTION_ROUTE = /^\/api\/pane\/([^/]{1,256})\/agent\/(control|explain|read|start|rename|prompt|focus|keys|input)$/;
const json = (value: JsonValue, status = 200): Response => Response.json(value, { status, headers: { "cache-control": "no-store" } });
export const agentFailure = (code: string, status: number, message: string): Response => refusalResponse(refusal(code, message, code.startsWith("device.") ? "device" : code.startsWith("scope.") ? "scope" : code.startsWith("capability.") ? "capability" : "operation"), status);

/** The token authenticates bridge custody of the original facts. It does not add vendor CAS. */
export function agentWitness(key: Uint8Array, owner: string, runtime: AgentRuntime, occupant: AgentOccupant): string {
  const fields = ["CollieAgentOccupantV1", owner, runtime.host, runtime.session, occupant.paneId, occupant.terminalId, occupant.workspaceId, occupant.tabId, occupant.runtimeIdentity, occupant.agent, occupant.sessionIdentity, occupant.processIdentity, occupant.name];
  if (occupant[ORIGINAL_AGENT_BINDING] !== undefined) fields.push(occupant[ORIGINAL_AGENT_BINDING], occupant[ORIGINAL_RUNTIME_INCARNATION] ?? null);
  return createHmac("sha256", key).update(JSON.stringify(fields)).digest("hex");
}
export function equalAgentWitness(a: string, b: string): boolean { return /^[a-f0-9]{64}$/.test(a) && /^[a-f0-9]{64}$/.test(b) && timingSafeEqual(Buffer.from(a, "hex"), Buffer.from(b, "hex")); }
export async function captureAgentContext(caller: AgentCaller, url: URL, paneId: string, level: GateLevel): Promise<AgentContext | Response> {
  const denied = caller.gate(level); if (denied) return denied;
  if (!controlScope(url, "session") || [...url.searchParams.keys()].some((key) => key !== "host" && key !== "session")) return agentFailure("scope.malformed", 400, "Use one explicit host and session scope.");
  const owner = caller.pairedIdentity(); const key = caller.receiptKey;
  if (!owner || !/^[a-f0-9]{64}$/.test(owner) || !key || key.byteLength < 32) return agentFailure("device.identity_required", 403, "Agent control requires the original paired device.");
  const runtime = await caller.resolve(); if (runtime instanceof Response) return runtime;
  if (runtime.mux.capabilities.supports.agentAutomation !== true || !runtime.mux.agentControl) return agentFailure("capability.unsupported", 501, "This runtime does not support named agent control.");
  const result = await runtime.mux.agentControl(paneId);
  if (!result.ok) return agentFailure("agent.identity_unavailable", result.reason === "gone" ? 404 : 503, "The original occupant could not be proved.");
  const original = Object.freeze({ ...result.value });
  const current = (identity: string): boolean => identity === original.runtimeIdentity && caller.gate(level) === null && caller.pairedIdentity() === owner && runtime.mux.capabilities.supports.agentAutomation === true && runtime.current(original);
  if (!current(original.runtimeIdentity)) return agentFailure("scope.retired", 409, "The original agent scope changed.");
  const ownerCurrent = (identity: string): boolean => identity === original.runtimeIdentity && level === "device-read" && caller.gate("device-read") === null && caller.pairedIdentity() === owner && runtime.mux.capabilities.supports.agentAutomation === true && runtime.mux.capabilities.supports.agentWaits === true && runtime.currentRuntime();
  return { runtime, owner, key, original, witness: agentWitness(key, owner, runtime, original), current, observationOwner: Object.freeze({ current: ownerCurrent }) };
}

function censusInfo(row: AgentControlInfo, redact = false): JsonObject {
  return { paneId: row.paneId, terminalId: row.terminalId, workspaceId: row.workspaceId, tabId: row.tabId, runtimeIdentity: row.runtimeIdentity, agent: row.agent, sessionIdentity: row.sessionIdentity, processIdentity: row.processIdentity, name: row.name !== null && redact && redactText(row.name) !== row.name ? "redacted" : row.name, status: row.status, focused: row.focused, interactiveReady: row.interactiveReady, launchPending: row.launchPending, stateChangeSeq: row.stateChangeSeq, completionSeq: row.completionSeq };
}

/** Same owning handlers are mounted for local and Crew requests. Bodies contain named verbs only. */
export async function serveAgentRoute(request: Request, url: URL, caller: AgentCaller, operations: OperationStore): Promise<Response | null> {
  if (url.pathname === "/api/agents/named") {
    if (request.method !== "GET") return agentFailure("agent.method_not_allowed", 405, "Named census is read-only.");
    const denied = caller.gate("device-read"); if (denied) return denied;
    if (!controlScope(url, "session") || [...url.searchParams.keys()].some((key) => key !== "host" && key !== "session")) return agentFailure("scope.malformed", 400, "Use one explicit host and session scope.");
    const owner = caller.pairedIdentity(); if (!owner) return agentFailure("device.identity_required", 403, "Named census requires the original paired device.");
    const runtime = await caller.resolve(); if (runtime instanceof Response) return runtime;
    if (!runtime.mux.agentList || runtime.mux.capabilities.supports.agentAutomation !== true || !runtime.currentRuntime()) return agentFailure("capability.unsupported", 501, "Named census is unavailable on this original runtime.");
    const result = await runtime.mux.agentList();
    if (!result.ok || !runtime.currentRuntime() || runtime.mux.capabilities.supports.agentAutomation !== true || caller.gate("device-read") !== null || caller.pairedIdentity() !== owner) return agentFailure("scope.retired", 409, "The original named census scope changed.");
    // Read-only census is never a mutation witness: each selected pane must be freshly reviewed.
    return json({ ok: true, schema: 1, agents: result.value.map((info) => censusInfo(info, caller.redact)) });
  }
  const match = AGENT_ACTION_ROUTE.exec(url.pathname); if (!match) return null;
  const verb = match[2]!; const read = verb === "control" || verb === "explain" || verb === "read";
  if (request.method !== (read ? "GET" : "POST")) return agentFailure("agent.method_not_allowed", 405, "Unsupported agent method.");
  let paneId: string; try { paneId = decodeURIComponent(match[1]!); } catch { return agentFailure("scope.malformed", 400, "Malformed pane identity."); }
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(paneId)) return agentFailure("scope.malformed", 400, "Malformed pane identity.");
  const scopeUrl = new URL(url);
  let source: "visible" | "recent" | "recent_unwrapped" | "detection" = "recent"; let lines: number = AGENT_LIMITS.lines;
  if (verb === "read") {
    const sources = url.searchParams.getAll("source"); const counts = url.searchParams.getAll("lines");
    if (sources.length !== 1 || counts.length !== 1 || !/^[1-9][0-9]{0,2}$/.test(counts[0]!) || Number(counts[0]) > AGENT_LIMITS.lines || !["visible", "recent", "recent_unwrapped", "detection"].includes(sources[0]!)) return agentFailure("agent.invalid_read", 400, "Choose a bounded named read source and line count.");
    // SAFETY: the closed source membership was checked directly above.
    source = sources[0] as typeof source; lines = Number(counts[0]); scopeUrl.searchParams.delete("source"); scopeUrl.searchParams.delete("lines");
  }
  const context = await captureAgentContext(caller, scopeUrl, paneId, read ? "device-read" : "write"); if (context instanceof Response) return context;
  const { runtime, original, current, witness, owner } = context;
  if (read && verb === "control") return json({ ok: true, schema: 1, info: censusInfo(original, caller.redact), witness });
  if (verb === "read") {
    if (!runtime.mux.agentRead) return agentFailure("capability.unsupported", 501, "Named agent reads are unavailable.");
    const result = await runtime.mux.agentRead(original, source, lines, current);
    if (!result.ok || !current(original.runtimeIdentity)) return agentFailure("agent.read_unavailable", 409, "No current original-agent read is available.");
    return json({ ok: true, schema: 1, paneId: original.paneId, terminalId: original.terminalId, text: caller.visiblePrompt(result.value.text), truncated: result.value.truncated });
  }
  if (read) {
    if (!runtime.mux.agentExplain) return agentFailure("capability.unsupported", 501, "Agent explanations are unavailable.");
    const result = await runtime.mux.agentExplain(original);
    if (!current(original.runtimeIdentity)) return agentFailure("scope.retired", 409, "The original agent scope changed.");
    const details = result.ok ? { ...result.value } : null;
    if (details && caller.redact) for (const field of ["manifestSource", "manifestVersion", "fallbackReason", "warning"] as const) { const value = details[field]; if (value !== null) details[field] = redactText(value); }
    return details ? json({ ok: true, schema: 1, explain: details }) : agentFailure("agent.explain_unavailable", 409, "No current original-agent explanation is available.");
  }
  const body = agentRecord(await readAgentBody(request));
  if (!body || !agentKeys(body, ["requestId", "witness", "action", "expectedText"]) || !agentRequestId(body.requestId) || !agentText(body.witness, 64) || (body.expectedText !== null && !agentText(body.expectedText, AGENT_LIMITS.resultBytes))) return agentFailure("agent.invalid_body", 400, "Invalid bounded agent action.");
  const action = parseAgentAction(verb, body.action); if (!action || (!["prompt", "keys", "input"].includes(action.kind) && body.expectedText !== null)) return agentFailure("agent.invalid_body", 400, "Invalid named agent action.");
  // An exact same id/body reconciles its original durable receipt BEFORE requiring new occupant
  // evidence. A changed or retired occupant cannot cause a repeat under a replacement witness.
  const hash = createHmac("sha256", context.key).update(canonicalPayload({ verb, paneId, witness: body.witness, action: body.action ?? null, expectedText: body.expectedText })).digest("hex");
  try {
    const existing = await operations.inspectIntent(body.requestId, owner, runtime.host, runtime.session, "agentAutomation", hash, body.witness);
    if (existing.kind === "conflict") return agentFailure("operation.request_id_reused", 409, "This action id already belongs to another immutable intent.");
    if (existing.kind === "match") return json({ ok: true, schema: 1, operation: operationJson(existing.operation), progress: { submitted: existing.operation.state === "succeeded", activityObserved: false, settled: false }, result: null });
  } catch { return agentFailure("operation.unavailable", 503, "The durable action registry is unavailable."); }
  if (!equalAgentWitness(witness, body.witness) || !current(original.runtimeIdentity)) return agentFailure("scope.retired", 409, "Review the current original occupant before acting.");
  if (!runtime.mux.agentAction) return agentFailure("capability.unsupported", 501, "Named agent actions are unavailable.");
  const expectedText = body.expectedText;
  const promptAdmitted = async (): Promise<boolean> => {
    if (action.kind !== "prompt" && action.kind !== "keys" && action.kind !== "input") return true;
    if (!original.agent || original.launchPending || !original.interactiveReady || original.status === "blocked" || expectedText === null) return false;
    const screen = await runtime.mux.readGrid(paneId, { scope: "recent", lines: AGENT_LIMITS.lines, styling: "preserve" });
    if (!screen.ok || caller.visiblePrompt(screen.value.text) !== expectedText || !current(original.runtimeIdentity) || !caller.composerSafe(original.agent, screen.value.text)) return false;
    const info = await runtime.mux.agentControl!(paneId);
    return info.ok && sameAgentOccupant(original, info.value) && current(original.runtimeIdentity);
  };
  const result: AgentPublishedResult = { value: null };
  const binding: PlanBinding = { device: owner, host: runtime.host, session: runtime.session, capability: "agentAutomation", payloadHash: hash, targetIdentity: witness, evidence: witness };
  try {
    let operation = await operations.execute(body.requestId, binding, body.witness, async () => {
      if (!current(original.runtimeIdentity) || !await promptAdmitted() || !current(original.runtimeIdentity)) return { state: "refused", code: "agent.original_admission_retired" };
      const outcome = await runtime.mux.agentAction!(action, original, current, undefined, promptAdmitted);
      if (!outcome.ok) return { state: outcome.reason === "refused" || outcome.reason === "unsupported" ? "refused" : "unknown", code: outcome.agentCode ?? (outcome.reason === "refused" ? "agent.zero_effect_refusal" : "agent.outcome_unknown") };
      if (!current(original.runtimeIdentity)) return { state: "unknown", code: "agent.outcome_unknown" };
      result.value = outcome.value; return { state: "succeeded", code: "agent.submitted" };
    });
    const held: import("./agent-models.ts").AgentActionResult | null = result.value;
    if (operation.state === "succeeded" && held) {
      let proved = false;
      if (current(original.runtimeIdentity)) {
        try { const fresh = await runtime.mux.agentControl!(held.info.paneId); proved = fresh.ok && sameAgentOccupant(held.info, fresh.value) && current(original.runtimeIdentity); }
        catch { proved = false; }
      }
      if (!proved) {
        result.value = null;
        const unknown = await operations.retainOriginalAgentUnknown(body.requestId, binding, body.witness);
        if (!unknown) throw new Error("operation.original_binding_lost"); operation = unknown;
      }
    }
    caller.audit(`agent-${verb}`, paneId, operation.state);
    const published: import("./agent-models.ts").AgentActionResult | null = result.value;
    return json({ ok: true, schema: 1, operation: operationJson(operation), progress: published === null ? { submitted: operation.state === "succeeded", activityObserved: false, settled: false } : { submitted: published.submitted, activityObserved: published.activityObserved, settled: published.settled }, result: published === null || operation.state !== "succeeded" ? null : { ...published.info } });
  } catch { return agentFailure("operation.unavailable", 503, "Reconcile this same action id; do not repeat the dispatch."); }
}

export function operationJson(operation: OperationView): JsonValue { return { schema: operation.schema, requestId: operation.requestId, state: operation.state, capability: operation.capability, targetIdentity: operation.targetIdentity, at: operation.at, updatedAt: operation.updatedAt, code: operation.code }; }
