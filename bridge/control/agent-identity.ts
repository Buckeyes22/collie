import type { MuxAgentIdentity, MuxOutcome } from "../mux/types.ts";
import { refusal, refusalResponse } from "./contracts.ts";

export const MAX_AGENT_IDENTITY_BODY_BYTES = 16 * 1024;

export interface AgentIdentityRuntime {
  readonly capabilities: { readonly supports: { readonly agentIdentity?: boolean } };
  readonly agentIdentity?: (paneId: string) => Promise<MuxOutcome<MuxAgentIdentity>>;
}

export interface AgentIdentitySnapshot {
  current(): {
    readonly agents: readonly { readonly paneId: string }[];
    readonly shellPanes: readonly { readonly paneId: string }[];
  };
}

function validSequence(value: MuxAgentIdentity["stateChangeSeq"]): boolean {
  return value === null || (/^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= 18_446_744_073_709_551_615n);
}

function ownsPane(engine: AgentIdentitySnapshot, paneId: string): boolean {
  const current = engine.current();
  return [...current.agents, ...current.shellPanes].some((pane) => pane.paneId === paneId);
}

/** Read bounded, owner-supplied identity facts; unknown authority never becomes an inferred source. */
export async function servePaneAgentIdentity(
  mux: AgentIdentityRuntime,
  engine: AgentIdentitySnapshot,
  paneId: string,
): Promise<Response> {
  if (!ownsPane(engine, paneId)) return refusalResponse(refusal("pane.not_found", "Pane not found", "scope"), 404);
  if (mux.capabilities.supports.agentIdentity !== true || !mux.agentIdentity) {
    return refusalResponse(refusal("capability.unsupported", "Agent identity diagnostics are not supported by this runtime", "capability"), 501);
  }
  const result = await mux.agentIdentity(paneId);
  if (!ownsPane(engine, paneId)) return refusalResponse(refusal("pane.not_found", "Pane changed or is no longer available", "scope"), 404);
  if (!result.ok) {
    if (result.reason === "unsupported") return refusalResponse(refusal("capability.unsupported", "Agent identity diagnostics are not supported by this runtime", "capability"), 501);
    if (result.reason === "gone") return refusalResponse(refusal("pane.not_found", "Pane changed or is no longer available", "scope"), 404);
    if (result.reason === "refused") return refusalResponse(refusal("agent.identity_refused", "Runtime refused agent identity diagnostics", "operation"), 409);
    return refusalResponse(refusal("agent.identity_unavailable", "Agent identity diagnostics are temporarily unavailable", "operation"), 503);
  }
  const identity = result.value;
  if (identity.paneId !== paneId || identity.stateAuthority.observed !== false || identity.stateAuthority.source !== null ||
    !validSequence(identity.stateChangeSeq) || !validSequence(identity.completionSeq)) {
    return refusalResponse(refusal("agent.identity_mismatch", "Runtime returned an invalid agent identity record", "scope"), 409);
  }
  const body = JSON.stringify({ ok: true, ...identity });
  if (new TextEncoder().encode(body).byteLength > MAX_AGENT_IDENTITY_BODY_BYTES) {
    return refusalResponse(refusal("agent.identity_too_large", "Agent identity details exceeded their response limit", "operation"), 413);
  }
  return new Response(body, { headers: { "cache-control": "no-store", "content-type": "application/json; charset=utf-8" } });
}
