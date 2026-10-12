import { createHash } from "node:crypto";
import type { JsonObject, JsonValue } from "../../json.ts";
import type { AgentStatus, AgentEventKind, AgentEventCondition } from "../../control/agent-models.ts";

export interface WireAgentControlInfo {
  readonly paneId: string; readonly terminalId: string; readonly workspaceId: string; readonly tabId: string;
  readonly name: string | null; readonly agent: string | null; readonly status: AgentStatus; readonly focused: boolean;
  readonly interactiveReady: boolean; readonly launchPending: boolean;
  readonly sessionIdentity: string | null; readonly stateChangeSeq: string | null; readonly completionSeq: string | null;
}
/* Parsing vendor JSON is the only runtime type boundary in this module. */
/* eslint-disable anti-slop/no-runtime-typeof */
export function agentObject(value: JsonValue | undefined): JsonObject {
  if (value === null || value === undefined || typeof value !== "object" || Array.isArray(value)) throw new Error("agent.protocol: expected object");
  return value;
}
export function agentString(value: JsonValue | undefined, maximum = 256): string {
  if (typeof value !== "string" || value.length > maximum || Array.from(value).some((character) => character.charCodeAt(0) < 32)) throw new Error("agent.protocol: invalid string");
  return value;
}
export function agentOptionalString(value: JsonValue | undefined, maximum = 256): string | null { return value === undefined || value === null ? null : agentString(value, maximum); }
export function agentBoolean(value: JsonValue | undefined): boolean { if (typeof value !== "boolean") throw new Error("agent.protocol: invalid boolean"); return value; }
export function agentStatus(value: JsonValue | undefined): AgentStatus {
  if (value !== "idle" && value !== "working" && value !== "blocked" && value !== "done" && value !== "unknown") throw new Error("agent.protocol: invalid status");
  return value;
}
export function agentUint64(value: JsonValue | undefined, marker: string): string | null {
  if (value === undefined || value === null) return null;
  const prefix = `\u0000collie.uint64.${marker}.`;
  if (typeof value === "string" && value.startsWith(prefix)) {
    const decimal = value.slice(prefix.length);
    if (!/^(0|[1-9][0-9]{0,19})$/.test(decimal) || BigInt(decimal) > 18_446_744_073_709_551_615n) throw new Error("agent.protocol: uint64 overflow");
    return decimal;
  }
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error("agent.protocol: invalid uint64");
  return String(value);
}
function identity(value: JsonValue | undefined): string {
  const id = agentString(value, 128); if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) throw new Error("agent.protocol: invalid identity"); return id;
}
export function parseAgentControlInfo(value: JsonValue | undefined, marker: string): WireAgentControlInfo {
  const row = agentObject(value); let sessionIdentity: string | null = null;
  if (row.agent_session !== null && row.agent_session !== undefined) {
    const session = agentObject(row.agent_session); const kind = agentString(session.kind);
    if (kind !== "id" && kind !== "path") throw new Error("agent.protocol: invalid session kind");
    sessionIdentity = createHash("sha256").update(JSON.stringify([agentString(session.source, 80), agentString(session.agent, 80), kind, agentString(session.value, 2048)])).digest("hex");
  }
  if (agentUint64(row.revision, marker) === null) throw new Error("agent.protocol: missing revision"); // Validate the shipped required field; never use it alone as freshness.
  return { paneId: identity(row.pane_id), terminalId: identity(row.terminal_id), workspaceId: identity(row.workspace_id), tabId: identity(row.tab_id), name: agentOptionalString(row.name, 32), agent: agentOptionalString(row.agent, 128), status: agentStatus(row.agent_status), focused: agentBoolean(row.focused), interactiveReady: row.interactive_ready === undefined ? false : agentBoolean(row.interactive_ready), launchPending: row.launch_pending === undefined ? false : agentBoolean(row.launch_pending), sessionIdentity, stateChangeSeq: agentUint64(row.state_change_seq, marker), completionSeq: agentUint64(row.completion_seq, marker) };
}
export const EVENT_WIRE = {
  "workspace-created": "workspace_created", "workspace-updated": "workspace_updated", "workspace-closed": "workspace_closed", "workspace-renamed": "workspace_renamed", "workspace-moved": "workspace_moved", "workspace-focused": "workspace_focused", "tab-created": "tab_created", "tab-closed": "tab_closed", "tab-renamed": "tab_renamed", "tab-moved": "tab_moved", "tab-focused": "tab_focused", "pane-created": "pane_created", "pane-closed": "pane_closed", "pane-focused": "pane_focused", "pane-moved": "pane_moved", "pane-output-changed": "pane_output_changed", "pane-exited": "pane_exited", "agent-detected": "pane_agent_detected", "agent-status-changed": "pane_agent_status_changed",
} satisfies Record<AgentEventKind, string>;
export function agentEventMatch(condition: AgentEventCondition): JsonObject {
  const event = EVENT_WIRE[condition.event]; const fields: JsonObject = { event };
  const targetKey = event.startsWith("workspace_") ? "workspace_id" : event.startsWith("tab_") ? "tab_id" : "pane_id";
  if (condition.targetId !== null) fields[targetKey] = condition.targetId;
  if (condition.workspaceId !== null && (event === "tab_created" || event === "pane_created")) fields.workspace_id = condition.workspaceId;
  if (condition.status !== null && event === "pane_agent_status_changed") fields.agent_status = condition.status;
  if (condition.agent !== null && event === "pane_agent_detected") fields.agent = condition.agent;
  if (condition.label !== null && (event === "workspace_renamed" || event === "tab_renamed")) fields.label = condition.label;
  return fields;
}
/* eslint-enable anti-slop/no-runtime-typeof */
