import { validateAgentView, type AgentViewRequest } from "../control/metadata.ts";
import { controlScope, payloadHash, refusal, refusalResponse } from "../control/contracts.ts";
import type { OperationStore, OperationView } from "../control/operations.ts";
import type { PlanBinding } from "../control/plans.ts";
import type { HerdrRpc } from "../mux/herdr/client.ts";
import type { HostControlPort, AgentViewHostControl, HostAgentViewOutcome } from "./types.ts";
import { herdrHostControl } from "./herdr.ts";

/* eslint-disable anti-slop/no-runtime-typeof, anti-slop/no-unknown-parameters, anti-slop/no-unsafe-dictionary-type, anti-slop/no-known-value-widening, anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-chained-type-assertions
 * SAFETY: this file parses JSON at the HTTP boundary and validates closed Herdr response records;
 * only those bounded domain objects are allowed into receipts or the host-control port.
 */

export function herdrAgentViews(client: HerdrRpc, ready: () => boolean | Promise<string | null>): AgentViewHostControl {
  const identity = async (): Promise<string | null> => {
    const value = await ready();
    return typeof value === "string" ? value : value ? "legacy-ready" : null;
  };
  return {
    async get(source: string): Promise<HostAgentViewOutcome> {
      const before = await identity();
      if (!before || !client.getAgentView || client.metadataProtocolCapabilities?.().agentViewReadV1 !== true) return { kind: "unsupported", reason: "This Herdr runtime does not advertise authoritative owner-scoped agent-view reads." };
      if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$/u.test(source)) return { kind: "refused", code: "invalid_agent_view", reason: "Agent view source is invalid." };
      try {
        const row = await client.getAgentView({ source });
        if (await identity() !== before) return { kind: "unavailable", reason: "Herdr runtime identity changed during agent-view reconciliation." };
        const decoded = stateResponse(row, source);
        return decoded;
      } catch (error) { return upstreamFailure(error); }
    },
    async set(request: AgentViewRequest): Promise<HostAgentViewOutcome> {
      const before = await identity();
      if (!before || !client.setAgentView) return { kind: "unsupported", reason: "A compatible Herdr runtime is required for terminal agent views." };
      const validation = validateAgentView(request);
      if (!validation.ok) return { kind: "refused", code: validation.code, reason: "Agent view is outside the bounded declarative schema." };
      try {
        const result = await client.setAgentView(JSON.parse(JSON.stringify(request)) as import("../json.ts").JsonObject);
        if (await identity() !== before) return { kind: "unavailable", reason: "Herdr runtime identity changed while setting the agent view; reconcile before retrying." };
        const acknowledged = response(result);
        if (acknowledged.kind === "available" && (!acknowledged.active || acknowledged.source !== request.source)) return { kind: "unavailable", reason: "Herdr did not confirm the requested agent-view source." };
        return acknowledged;
      } catch (error) { return upstreamFailure(error); }
    },
    async clear(source?: string): Promise<HostAgentViewOutcome> {
      const before = await identity();
      if (!before || !client.clearAgentView) return { kind: "unsupported", reason: "A compatible Herdr runtime is required for terminal agent views." };
      if (source !== undefined && !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$/u.test(source)) return { kind: "refused", code: "invalid_agent_view", reason: "Agent view source is invalid." };
      try {
        const result = await client.clearAgentView(source === undefined ? {} : { source });
        if (await identity() !== before) return { kind: "unavailable", reason: "Herdr runtime identity changed while clearing the agent view; reconcile before retrying." };
        const acknowledged = response(result);
        if (acknowledged.kind === "available" && (acknowledged.active || source !== undefined && acknowledged.source !== null && acknowledged.source !== source)) return { kind: "unavailable", reason: "Herdr did not confirm clearing the requested agent-view source." };
        return acknowledged;
      } catch (error) { return upstreamFailure(error); }
    },
  };
}

export function herdrHostControlWithAgentViews(client: HerdrRpc, ready: () => boolean, identity: () => Promise<string | null> = async () => ready() ? "legacy-ready" : null, base: HostControlPort = herdrHostControl): HostControlPort {
  const views = herdrAgentViews(client, identity);
  return {
    ...base,
    get capabilities() { return { ...base.capabilities, agentViews: ready() && client.setAgentView !== undefined && client.clearAgentView !== undefined && client.getAgentView !== undefined && client.metadataProtocolCapabilities?.().agentViewReadV1 === true }; },
    get notes() { return { ...base.notes, agentViews: ready() ? "Requires current positive agent_view_read_v1 capability." : "Requires a current compatible Herdr protocol handshake." }; },
    agentViews: views,
  };
}

export interface AgentViewRouteCaller {
  gate(level: import("../crew/peer-gate.ts").GateLevel): Response | null;
  pairedIdentity(): string | null;
  audit(action: string, host: string, outcome: string): void;
  resolve(): Promise<{ readonly host: string; readonly port: HostControlPort } | Response>;
}
export interface AgentViewReceiptStore { read(host: string): AgentViewRequest | null; write(host: string, value: AgentViewRequest | null): void }
export function memoryAgentViewReceipts(): AgentViewReceiptStore {
  const rows = new Map<string, AgentViewRequest>();
  return { read: (host) => rows.get(host) ?? null, write: (host, value) => { if (value) rows.set(host, value); else rows.delete(host); } };
}
const route = /^\/api\/host\/agent-view$/u;
export async function serveAgentViewRoute(req: Request, url: URL, caller: AgentViewRouteCaller, receipts: AgentViewReceiptStore, operations: OperationStore): Promise<Response | null> {
  if (!route.test(url.pathname)) return null;
  if (!(["GET", "POST", "DELETE"] as const).includes(req.method as "GET" | "POST" | "DELETE")) return refusalResponse(refusal("route.method", "Method is not supported", "capability"), 405);
  const denied = caller.gate(req.method === "GET" ? "device-read" : "write");
  if (denied) return denied;
  if (!controlScope(url, "host")) return refusalResponse(refusal("scope.malformed", "Agent views require host-only scope", "scope"), 400);
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  if (req.method === "GET") {
    const device = caller.pairedIdentity();
    const source = device && /^[a-f0-9]{64}$/u.test(device) ? `collie.native.${device.slice(0, 12)}` : null;
    const result = source && runtime.port.capabilities.agentViews === true && runtime.port.agentViews ? await runtime.port.agentViews.get(source) : null;
    if (result?.kind === "available" && result.source === source) return Response.json({ ok: true, view: result.active ? result.view ?? null : null, authoritative: true, reconcileRequired: false }, { headers: { "cache-control": "no-store" } });
    return Response.json({ ok: true, view: receipts.read(runtime.host), authoritative: false, reconcileRequired: true }, { headers: { "cache-control": "no-store" } });
  }
  const device = caller.pairedIdentity();
  if (!device || !/^[a-f0-9]{64}$/u.test(device)) return refusalResponse(refusal("device.identity_required", "Agent-view changes require the originating paired device", "device"), 403);
  if (!operations.isDurable) return refusalResponse(refusal("agent_view.receipts_unavailable", "Durable agent-view receipts are unavailable", "capability"), 501);
  const body = await boundedBody(req);
  if (!body || typeof body.requestId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId)) return refusalResponse(refusal("invalid_agent_view", "Agent-view request is malformed", "operation"), 400);
  const requestId = body.requestId;
  const source = `collie.native.${device.slice(0, 12)}`;
  const method = req.method === "POST" ? "set" : "clear";
  let view: AgentViewRequest | null = null;
  if (method === "set") {
    const data: Record<string, unknown> = { ...body, source };
    delete data.requestId;
    if (Object.keys(data).some((key) => !["source", "label", "filter", "sort"].includes(key))) return refusalResponse(refusal("invalid_agent_view", "Agent-view request contains unsupported fields", "scope"), 400);
    // SAFETY: source is assigned above; the exact allowlist below this line and validator close every remaining field.
    const validation = validateAgentView(data as unknown as AgentViewRequest);
    if (!validation.ok) return refusalResponse(refusal(validation.code, "Agent view is outside the bounded declarative schema", "scope"), 400);
    // SAFETY: the bounded validator accepted this exact closed object.
    view = data as unknown as AgentViewRequest;
  } else {
    if (Object.keys(body).some((key) => key !== "requestId")) return refusalResponse(refusal("invalid_agent_view", "Agent-view clear accepts only a request ID", "scope"), 400);
  }
  const binding: PlanBinding = { device, host: runtime.host, session: null, capability: "agentViews", payloadHash: payloadHash(view ? JSON.parse(JSON.stringify(view)) as import("../json.ts").JsonValue : { clear: source }), targetIdentity: payloadHash({ host: runtime.host }), evidence: payloadHash({ host: runtime.host, scope: "agent-view" }) };
  let operation: OperationView;
  try {
    operation = await operations.execute(requestId, binding, null, async () => {
      if (caller.gate("write") || caller.pairedIdentity() !== device) return { state: "refused", code: "agent_view.admission_changed" };
      if (runtime.port.capabilities.agentViews !== true || !runtime.port.agentViews) return { state: "refused", code: "agent_view.unsupported" };
      const result = method === "set" ? await runtime.port.agentViews.set(view!) : await runtime.port.agentViews.clear(source);
      if (result.kind !== "available") return { state: "refused", code: result.kind === "refused" ? result.code : "agent_view.unsupported" };
      if (result.source !== null && result.source !== source) return { state: "refused", code: "agent_view.source_mismatch" };
      return { state: "succeeded" };
    });
  } catch (error) {
    const code = operationFailureCode(error);
    caller.audit(`agent.view.${method}`, runtime.host, code);
    return refusalResponse(refusal(code, "The agent-view operation could not be durably reserved", "operation"), code === "operation.request_id_reused" ? 409 : 503);
  }
  caller.audit(`agent.view.${method}`, runtime.host, operation.state);
  if (operation.state !== "succeeded") return operationResponse(operation);
  receipts.write(runtime.host, view);
  return Response.json({ ok: true, requestId, source, receipt: true, reconcileRequired: true }, { headers: { "cache-control": "no-store" } });
}

/* eslint-disable anti-slop/no-runtime-typeof, anti-slop/no-unknown-parameters */
async function boundedBody(req: Request): Promise<Record<string, unknown> | null> {
  const length = req.headers.get("content-length");
  if (length !== null && (!/^\d+$/u.test(length) || Number(length) > 16 * 1024)) { await req.body?.cancel(); return null; }
  if (!req.body) return null;
  try { const text = await req.text(); if (new TextEncoder().encode(text).byteLength > 16 * 1024) return null; const value: unknown = JSON.parse(text); return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null; }
  catch { return null; }
}
function operationResponse(operation: OperationView): Response {
  if (operation.state === "pending" || operation.state === "unknown") return Response.json({ ok: false, code: "agent_view.outcome_unknown", operation }, { status: 202, headers: { "cache-control": "no-store" } });
  return refusalResponse(refusal(operation.code ?? "agent_view.refused", "Agent-view operation was refused", "operation"), 409);
}

function operationFailureCode(error: unknown): string {
  if (!(error instanceof Error)) return "operation.receipts_unavailable";
  if (error.message === "operation.capacity") return "operation.capacity";
  if (error.message.includes("operation.request_id_reused")) return "operation.request_id_reused";
  return "operation.receipts_unavailable";
}

function response(row: Record<string, unknown>): HostAgentViewOutcome {
  if (row.type !== "agent_view" || typeof row.active !== "boolean" || row.source !== undefined && row.source !== null && typeof row.source !== "string" || row.label !== undefined && row.label !== null && typeof row.label !== "string") {
    return { kind: "unavailable", reason: "Herdr returned an invalid agent-view acknowledgment." };
  }
  return { kind: "available", active: row.active, source: typeof row.source === "string" ? row.source.slice(0, 80) : null, label: typeof row.label === "string" ? row.label.slice(0, 80) : null };
}
function stateResponse(row: Record<string, unknown>, requestedSource: string): HostAgentViewOutcome {
  if (row.type !== "agent_view_state" || row.source !== requestedSource || typeof row.active !== "boolean" || row.view !== null && (row.view === undefined || typeof row.view !== "object" || Array.isArray(row.view))) return { kind: "unavailable", reason: "Herdr returned an invalid owner-scoped agent-view state." };
  if (!row.active) return row.view === null ? { kind: "available", active: false, source: requestedSource, label: null, view: null } : { kind: "unavailable", reason: "Herdr returned a view for a non-active source." };
  if (row.view === null) return { kind: "unavailable", reason: "Herdr returned an active agent-view owner without its definition." };
  const view = row.view as Record<string, unknown>;
  if (Object.keys(view).some((key) => !["source", "label", "filter", "sort"].includes(key)) || view.source !== requestedSource || !validateAgentView(view as unknown as AgentViewRequest).ok) return { kind: "unavailable", reason: "Herdr returned an invalid owner-scoped agent-view definition." };
  return { kind: "available", active: true, source: requestedSource, label: typeof view.label === "string" ? view.label : null, view: view as unknown as AgentViewRequest };
}
function upstreamFailure(error: unknown): HostAgentViewOutcome {
  const detail = error instanceof Error ? error.message.slice(0, 256) : "Herdr request failed";
  const code = ["invalid_agent_view", "plugin_disabled", "plugin_not_found"].find((item) => detail.includes(item));
  return code ? { kind: "refused", code, reason: "Herdr refused this agent-view source." } : { kind: "unavailable", reason: "Herdr did not confirm the agent-view operation." };
}
