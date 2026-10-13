import { agentObject, agentOptionalString, agentStatus, agentString, EVENT_WIRE } from "./agent-codecs.ts";
import { HerdrAgentRefusal } from "./client.ts";
import { ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION, type AgentEventCondition, type AgentOccupant, type AgentWaitResult } from "../../control/agent-models.ts";
import type { JsonObject } from "../../json.ts";

/** Decode only the requested original event. Raw vendor payloads never become HTTP results. */
export function matchedAgentEvent(event: string, data: JsonObject, condition: AgentEventCondition, original: AgentOccupant): AgentWaitResult | null {
  const expected = EVENT_WIRE[condition.event]; if (event !== expected) return null;
  if (data.type !== expected) throw new Error("agent.event_protocol");
  const row = event === "workspace_created" || event === "workspace_updated" ? agentObject(data.workspace) : event === "tab_created" ? agentObject(data.tab) : event === "pane_created" || event === "pane_moved" ? agentObject(data.pane) : data;
  const key = event.startsWith("workspace_") ? "workspace_id" : event.startsWith("tab_") ? "tab_id" : "pane_id";
  const targetId = agentString(row[key], 128);
  const selected = event === "pane_moved" ? agentString(data.previous_pane_id, 128) : targetId;
  if (event === "pane_moved" && (data.previous_pane_id !== original.paneId || data.previous_workspace_id !== original.workspaceId || data.previous_tab_id !== original.tabId || row.terminal_id !== original.terminalId)) return null;
  if (condition.targetId !== null && selected !== condition.targetId) return null;
  if (condition.workspaceId !== null && row.workspace_id !== condition.workspaceId) return null;
  const status = event === "pane_agent_status_changed" ? agentStatus(data.agent_status) : null;
  if (condition.status !== null && status !== condition.status) return null;
  if (condition.agent !== null && agentOptionalString(data.agent, 128) !== condition.agent) return null;
  if (condition.label !== null && agentString(data.label, 128) !== condition.label) return null;
  if (event.startsWith("pane_") && row.workspace_id !== original.workspaceId && event !== "pane_moved") return null;
  if (event.startsWith("tab_") && row.workspace_id !== original.workspaceId) return null;
  // The actual emitted removal envelopes have no terminal UUID. A stale public-ID close
  // cannot prove that this original occupant closed; do not invent producer provenance.
  if (["pane_closed", "pane_exited", "tab_closed", "workspace_closed", "pane_agent_detected"].includes(event)) {
    if (data.original_transition === undefined && event === "pane_agent_detected") return { kind: "event", event: condition.event, targetId, status };
    const before = data.original_transition === undefined ? null : agentObject(data.original_transition);
    if (!before || Object.keys(before).toSorted().join(",") !== "binding,epoch,incarnation,pane_id,role_epoch,tab_id,terminal_id,workspace_id" || !original[ORIGINAL_AGENT_BINDING] || before.binding !== original[ORIGINAL_AGENT_BINDING] || before.incarnation !== original[ORIGINAL_RUNTIME_INCARNATION] || before.pane_id !== original.paneId || before.terminal_id !== original.terminalId || before.workspace_id !== original.workspaceId || before.tab_id !== original.tabId || !counter(before.epoch) || !counter(before.role_epoch)) throw new HerdrAgentRefusal("event_original_terminal_unproved");
  }
  if ((event === "pane_created" || event === "pane_moved") && row.terminal_id !== original.terminalId && condition.targetId !== null) return null;
  return { kind: "event", event: condition.event, targetId, status };
}

function counter(value: import("../../json.ts").JsonValue | undefined): boolean {
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  return typeof value === "string" && /^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= 18446744073709551615n;
}
