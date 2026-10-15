/** Source-private producer custody. Symbol properties survive immutable object copies but never enter JSON/Crew/native DTOs. */
export const ORIGINAL_AGENT_BINDING: unique symbol = Symbol("original-agent-binding");
export const ORIGINAL_RUNTIME_INCARNATION: unique symbol = Symbol("original-runtime-incarnation");
/** Neutral original-occupant agent orchestration; no raw vendor RPC enters HTTP or Crew. */
export const AGENT_LIMITS = { timeoutMs: 300_000, defaultTimeoutMs: 30_000, bodyBytes: 32_768, promptBytes: 16_384, nameChars: 32, args: 32, argChars: 512, regexChars: 256, lines: 200, keys: 16, agents: 64, activePerOwner: 8, activeGlobal: 64, retainedPerOwner: 32, retainedGlobal: 512, resultBytes: 32_768, retentionMs: 300_000 } as const;
export type AgentStatus = "idle" | "working" | "blocked" | "done" | "unknown";
export interface AgentOccupant {
  readonly [ORIGINAL_AGENT_BINDING]?: string;
  readonly [ORIGINAL_RUNTIME_INCARNATION]?: string;
  readonly paneId: string;
  readonly terminalId: string;
  readonly workspaceId: string;
  readonly tabId: string;
  readonly runtimeIdentity: string;
  readonly agent: string | null;
  readonly sessionIdentity: string | null;
  readonly processIdentity: string | null;
  readonly name: string | null;
}
export interface AgentControlInfo extends AgentOccupant {
  readonly status: AgentStatus;
  readonly focused: boolean;
  readonly interactiveReady: boolean;
  readonly launchPending: boolean;
  readonly stateChangeSeq: string | null;
  readonly completionSeq: string | null;
}
export interface AgentExplain {
  readonly paneId: string;
  readonly terminalId: string;
  readonly agent: string;
  readonly status: AgentStatus;
  readonly manifestSource: string | null;
  readonly manifestVersion: string | null;
  readonly fallbackReason: string | null;
  readonly warning: string | null;
  readonly visibleIdle: boolean;
  readonly visibleWorking: boolean;
  readonly visibleBlocked: boolean;
  readonly detectionSkipped: boolean;
}
export type AgentAction =
  | { readonly kind: "start"; readonly name: string; readonly agent: string; readonly args: readonly string[]; readonly timeoutMs: number }
  | { readonly kind: "rename"; readonly name: string | null }
  | { readonly kind: "focus" }
  | { readonly kind: "keys"; readonly keys: readonly string[] }
  | { readonly kind: "input"; readonly text: string; readonly keys: readonly string[] }
  | { readonly kind: "prompt"; readonly text: string; readonly until: readonly AgentStatus[] | null; readonly timeoutMs: number };
export interface AgentActionResult {
  readonly info: AgentControlInfo;
  readonly submitted: true;
  readonly activityObserved: boolean;
  readonly settled: boolean;
}
export const AGENT_EVENTS = ["workspace-created", "workspace-updated", "workspace-closed", "workspace-renamed", "workspace-moved", "workspace-focused", "tab-created", "tab-closed", "tab-renamed", "tab-moved", "tab-focused", "pane-created", "pane-closed", "pane-focused", "pane-moved", "pane-output-changed", "pane-exited", "agent-detected", "agent-status-changed"] as const;
export type AgentEventKind = typeof AGENT_EVENTS[number];
export interface AgentEventCondition { readonly kind: "event"; readonly event: AgentEventKind; readonly targetId: string | null; readonly workspaceId: string | null; readonly status: AgentStatus | null; readonly agent: string | null; readonly label: string | null }
export type AgentWaitCondition =
  | { readonly kind: "lifecycle"; readonly until: readonly AgentStatus[] }
  | { readonly kind: "output"; readonly source: "visible" | "recent" | "recent_unwrapped" | "detection"; readonly match: { readonly kind: "substring" | "regex"; readonly value: string }; readonly lines: number }
  | AgentEventCondition;
export type AgentWaitResult =
  | { readonly kind: "lifecycle"; readonly info: AgentControlInfo }
  | { readonly kind: "output"; readonly paneId: string; readonly matchedLine: string | null; readonly text: string; readonly truncated: boolean }
  | { readonly kind: "event"; readonly event: AgentEventKind; readonly targetId: string | null; readonly status: AgentStatus | null; readonly move?: AgentObservedMove; readonly transition?: AgentObservedTransition };
/** Read-only transition metadata. This never contains a new action witness or actor. */
export interface AgentObservedMove { readonly previousPaneId: string; readonly previousWorkspaceId: string; readonly previousTabId: string; readonly info: AgentControlInfo; readonly witness?: string }
export const AGENT_TERMINAL_TRANSITIONS = ["pane-closed", "pane-exited", "tab-closed", "workspace-closed", "agent-detected"] as const;
export type AgentTerminalTransitionKind = typeof AGENT_TERMINAL_TRANSITIONS[number];
/** Original transition metadata supplies no successor action/composer/navigation witness. */
export interface AgentObservedTransition { readonly event: AgentTerminalTransitionKind; readonly previousPaneId: string; readonly previousWorkspaceId: string; readonly previousTabId: string; readonly terminalId: string; readonly after: AgentControlInfo | null; readonly witness?: string }
export interface AgentObservationOwner { readonly current: (runtimeIdentity: string) => boolean }
export const sameAgentIdentity = (a: AgentOccupant, b: AgentOccupant): boolean => a.terminalId === b.terminalId && (a[ORIGINAL_RUNTIME_INCARNATION] ?? null) === (b[ORIGINAL_RUNTIME_INCARNATION] ?? null) && a.runtimeIdentity === b.runtimeIdentity && a.agent === b.agent && a.sessionIdentity === b.sessionIdentity && a.processIdentity === b.processIdentity && a.name === b.name;
export interface AgentProgress { readonly submitted: boolean; readonly activityObserved: boolean; readonly settled: boolean }
export const sameAgentOccupant = (a: AgentOccupant, b: AgentOccupant): boolean =>
  (a[ORIGINAL_AGENT_BINDING] ?? null) === (b[ORIGINAL_AGENT_BINDING] ?? null) && a.paneId === b.paneId && a.terminalId === b.terminalId && a.workspaceId === b.workspaceId && a.tabId === b.tabId && a.runtimeIdentity === b.runtimeIdentity && a.agent === b.agent && a.sessionIdentity === b.sessionIdentity && a.processIdentity === b.processIdentity && a.name === b.name;

/** Closed safe error vocabulary. Codes display cause; accepted-byte provenance still decides effect. */
export const AGENT_VENDOR_ERRORS = ["invalid_agent_name", "unsupported_agent_kind", "invalid_agent_argument", "invalid_agent_timeout", "agent_pane_not_found", "agent_pane_busy", "agent_pane_unavailable", "agent_name_taken", "agent_not_found", "agent_target_ambiguous", "agent_launch_pending", "empty_agent_prompt", "agent_blocked", "agent_not_ready", "agent_not_running", "agent_start_input_failed", "agent_prompt_failed", "agent_prompt_stalled", "agent_send_keys_failed", "pane_send_failed", "invalid_key", "pane_not_found", "invalid_regex", "unsupported_event_wait_match", "timeout", "server_unavailable", "events_lost", "event_stream_lost", "event_budget_exceeded", "event_protocol", "event_original_terminal_unproved", "observation_retired", "observation_cancelled", "original_input_retired_before_input", "original_input_retired_after_input", "original_input_unavailable", "original_dispatch_requires_completion", "original_observation_unavailable", "original_observation_capacity"] as const;
export type AgentErrorCode = `agent.${typeof AGENT_VENDOR_ERRORS[number]}`;
export function agentErrorCode(vendor: string): AgentErrorCode | undefined { const matched = AGENT_VENDOR_ERRORS.find((code) => code === vendor); return matched === undefined ? undefined : `agent.${matched}`; }
export type AgentActionOutcome = import("../mux/types.ts").MuxOutcome<AgentActionResult> & { readonly agentCode?: AgentErrorCode };
export type AgentWaitOutcome = import("../mux/types.ts").MuxOutcome<AgentWaitResult> & { readonly agentCode?: AgentErrorCode };
