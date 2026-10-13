import type { JsonObject, JsonValue } from "../json.ts";
import { redactText } from "../redact.ts";
import { AGENT_EVENTS, AGENT_TERMINAL_TRANSITIONS, AGENT_LIMITS } from "./agent-models.ts";
import { agentId, agentKeys, agentRecord, agentRequestId, agentText } from "./agent-requests.ts";

/* Closed response boundary; a peer cannot smuggle unmasked auxiliary strings. */
/* eslint-disable anti-slop/no-runtime-typeof */
const statuses = new Set(["idle", "working", "blocked", "done", "unknown"]);
const sequence = (value: JsonValue | undefined): boolean => value === null || (typeof value === "string" && /^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= 18_446_744_073_709_551_615n);
export function validAgentInfo(value: JsonValue | undefined): value is JsonObject {
  const row = agentRecord(value);
  return row !== null && agentKeys(row, ["paneId", "terminalId", "workspaceId", "tabId", "runtimeIdentity", "agent", "sessionIdentity", "processIdentity", "name", "status", "focused", "interactiveReady", "launchPending", "stateChangeSeq", "completionSeq"]) && [row.paneId, row.terminalId, row.workspaceId, row.tabId].every(agentId) && agentText(row.runtimeIdentity, 128) && row.runtimeIdentity.length > 0 && (row.agent === null || agentId(row.agent)) && (row.name === null || (agentText(row.name, 32) && /^[a-z][a-z0-9_-]{0,31}$/.test(row.name))) && [row.sessionIdentity, row.processIdentity].every((field) => field === null || (typeof field === "string" && /^[a-f0-9]{64}$/.test(field))) && typeof row.status === "string" && statuses.has(row.status) && typeof row.focused === "boolean" && typeof row.interactiveReady === "boolean" && typeof row.launchPending === "boolean" && sequence(row.stateChangeSeq) && sequence(row.completionSeq);
}
function matchesSelectedPane(paneId: JsonValue | undefined, selected: string | undefined): boolean {
  // Native uses public pane IDs. A named HTTP selector may resolve to a different public ID;
  // this closed display body never mints authority: the owning peer's HMAC witness binds it.
  return paneId === selected || (selected !== undefined && /^[a-z][a-z0-9_-]{0,31}$/.test(selected) && agentId(paneId));
}
function maskedInfo(info: JsonObject): JsonObject {
  return Object.assign({}, info, { name: typeof info.name === "string" && redactText(info.name) !== info.name ? "redacted" : info.name });
}
export function maskAgentControlAnswer(body: JsonObject, expectedPaneId?: string): string | null {
  if (body.ok !== true || body.schema !== 1) return null;
  if (body.agents !== undefined) {
    if (!agentKeys(body, ["ok", "schema", "agents"]) || !Array.isArray(body.agents) || body.agents.length > AGENT_LIMITS.agents || !body.agents.every(validAgentInfo)) return null;
    const identities = body.agents.map((info) => info.terminalId);
    return new Set(identities).size === identities.length ? JSON.stringify({ ...body, agents: body.agents.map(maskedInfo) }) : null;
  }
  if (body.text !== undefined) {
    if (!agentKeys(body, ["ok", "schema", "paneId", "terminalId", "text", "truncated"]) || !matchesSelectedPane(body.paneId, expectedPaneId) || !agentId(body.terminalId) || !agentText(body.text, AGENT_LIMITS.resultBytes) || typeof body.truncated !== "boolean") return null;
    return JSON.stringify({ ...body, text: redactText(body.text) });
  }
  if (body.info !== undefined) {
    if (!agentKeys(body, ["ok", "schema", "info", "witness"]) || !validAgentInfo(body.info) || !matchesSelectedPane(body.info.paneId, expectedPaneId) || !agentText(body.witness, 64) || !/^[a-f0-9]{64}$/.test(body.witness)) return null;
    return JSON.stringify({ ...body, info: maskedInfo(body.info) }); // Only opaque witnesses and closed owner-produced identities.
  }
  if (body.explain !== undefined) {
    const row = agentRecord(body.explain);
    if (!agentKeys(body, ["ok", "schema", "explain"]) || !row || !agentKeys(row, ["paneId", "terminalId", "agent", "status", "manifestSource", "manifestVersion", "fallbackReason", "warning", "visibleIdle", "visibleWorking", "visibleBlocked", "detectionSkipped"]) || !matchesSelectedPane(row.paneId, expectedPaneId) || !agentId(row.terminalId) || !agentId(row.agent) || typeof row.status !== "string" || !statuses.has(row.status) || ![row.manifestSource, row.manifestVersion, row.fallbackReason, row.warning].every((value) => value === null || agentText(value, 1024)) || ![row.visibleIdle, row.visibleWorking, row.visibleBlocked, row.detectionSkipped].every((value) => typeof value === "boolean")) return null;
    const masked = { ...row };
    for (const name of ["manifestSource", "manifestVersion", "fallbackReason", "warning"]) { const value = row[name]; if (typeof value === "string") masked[name] = redactText(value); }
    return JSON.stringify({ ...body, explain: masked });
  }
  if (!agentKeys(body, ["ok", "schema", "operation", "progress", "result"])) return null;
  const operation = agentRecord(body.operation); const progress = agentRecord(body.progress);
  if (!operation || !agentKeys(operation, ["schema", "requestId", "state", "capability", "targetIdentity", "at", "updatedAt", "code"]) || operation.schema !== 1 || !agentRequestId(operation.requestId) || operation.capability !== "agentAutomation" || !agentText(operation.targetIdentity, 64) || !/^[a-f0-9]{64}$/.test(operation.targetIdentity) || typeof operation.state !== "string" || !["pending", "succeeded", "refused", "unknown"].includes(operation.state) || typeof operation.at !== "number" || !Number.isSafeInteger(operation.at) || operation.at < 0 || typeof operation.updatedAt !== "number" || !Number.isSafeInteger(operation.updatedAt) || operation.updatedAt < operation.at || (operation.code !== null && !agentId(operation.code)) || !progress || !agentKeys(progress, ["submitted", "activityObserved", "settled"]) || ![progress.submitted, progress.activityObserved, progress.settled].every((value) => typeof value === "boolean") || (body.result !== null && (!validAgentInfo(body.result) || !matchesSelectedPane(body.result.paneId, expectedPaneId)))) return null;
  if ((progress.activityObserved === true && progress.submitted !== true) || (progress.settled === true && progress.activityObserved !== true) || (body.result !== null && operation.state !== "succeeded")) return null;
  return JSON.stringify({ ...body, result: body.result === null ? null : maskedInfo(agentRecord(body.result)!) });
}
export function maskAgentWaitAnswer(body: JsonObject): string | null {
  if (!agentKeys(body, ["ok", "wait"]) || body.ok !== true) return null;
  const wait = agentRecord(body.wait);
  if (!wait || !agentKeys(wait, ["schema", "id", "paneId", "state", "code", "deadline", "result"]) || (wait.code !== null && !agentId(wait.code)) || wait.schema !== 1 || !agentRequestId(wait.id) || !agentId(wait.paneId) || typeof wait.state !== "string" || !["pending", "matched", "cancelled", "retired", "timed-out", "interrupted"].includes(wait.state) || typeof wait.deadline !== "number" || !Number.isSafeInteger(wait.deadline) || wait.deadline < 0) return null;
  if (wait.result === null) return JSON.stringify(body);
  if (wait.state !== "matched") return null;
  const result = agentRecord(wait.result); if (!result || Buffer.byteLength(JSON.stringify(result)) > AGENT_LIMITS.resultBytes) return null;
  if (result.kind === "lifecycle") {
    if (!agentKeys(result, ["kind", "info"]) || !validAgentInfo(result.info) || result.info.paneId !== wait.paneId) return null;
  } else if (result.kind === "output") {
    if (!agentKeys(result, ["kind", "paneId", "matchedLine", "text", "truncated"]) || result.paneId !== wait.paneId || !agentText(result.text, AGENT_LIMITS.resultBytes) || (result.matchedLine !== null && !agentText(result.matchedLine, AGENT_LIMITS.resultBytes)) || typeof result.truncated !== "boolean") return null;
    return JSON.stringify({ ...body, wait: { ...wait, result: { ...result, text: redactText(result.text), matchedLine: result.matchedLine === null ? null : redactText(result.matchedLine) } } });
  } else if (result.kind === "event") {
    if (!agentKeys(result, ["kind", "event", "targetId", "status", ...(result.move === undefined ? [] : ["move"]), ...(result.transition === undefined ? [] : ["transition"])]) || !AGENT_EVENTS.some((event) => event === result.event) || (result.targetId !== null && !agentId(result.targetId)) || (result.status !== null && (typeof result.status !== "string" || !statuses.has(result.status)))) return null;
    if (result.transition !== undefined) {
      const transition = agentRecord(result.transition);
      if (result.move !== undefined || !transition || !agentKeys(transition, ["event", "previousPaneId", "previousWorkspaceId", "previousTabId", "terminalId", "after", "witness"]) || transition.event !== result.event || !AGENT_TERMINAL_TRANSITIONS.some((event) => event === transition.event) || transition.previousPaneId !== wait.paneId || ![transition.previousWorkspaceId, transition.previousTabId, transition.terminalId].every(agentId) || !agentText(transition.witness, 64) || !/^[a-f0-9]{64}$/.test(transition.witness) || (transition.after !== null && (!validAgentInfo(transition.after) || transition.after.terminalId !== transition.terminalId || transition.after.paneId !== transition.previousPaneId || transition.event !== "agent-detected")) || transition.event === "agent-detected" && transition.after === null) return null;
      return JSON.stringify({ ...body, wait: { ...wait, result: { ...result, transition: { ...transition, after: transition.after === null ? null : maskedInfo(agentRecord(transition.after)!) } } } });
    }
    if (result.move !== undefined) {
      const move = agentRecord(result.move);
      if (result.event !== "pane-moved" || !move || !agentKeys(move, ["previousPaneId", "previousWorkspaceId", "previousTabId", "info", "witness"]) || move.previousPaneId !== wait.paneId || ![move.previousWorkspaceId, move.previousTabId].every(agentId) || !validAgentInfo(move.info) || move.info.paneId !== result.targetId || !agentText(move.witness, 64) || !/^[a-f0-9]{64}$/.test(move.witness)) return null;
      return JSON.stringify({ ...body, wait: { ...wait, result: { ...result, move: { ...move, info: maskedInfo(move.info) } } } });
    }
  } else return null;
  return JSON.stringify(body);
}
/* eslint-enable anti-slop/no-runtime-typeof */
