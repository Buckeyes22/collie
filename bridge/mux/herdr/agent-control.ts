import { matchedAgentEvent } from "./agent-events.ts";
import { toHerdrKey } from "./keys.ts";
import { createHash } from "node:crypto";
import { agentErrorCode, ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION, AGENT_LIMITS, sameAgentIdentity, sameAgentOccupant, type AgentAction, type AgentActionOutcome, type AgentControlInfo, type AgentExplain, type AgentOccupant, type AgentObservedMove, type AgentObservedTransition, AGENT_TERMINAL_TRANSITIONS, type AgentTerminalTransitionKind, type AgentObservationOwner, type AgentWaitCondition, type AgentWaitOutcome } from "../../control/agent-models.ts";
import { WriteAdmissionRetired } from "../../dial.ts";
import { muxGone, muxOk, muxRefused, muxUnreachable, muxUnsupported, type MuxOutcome } from "../types.ts";
import { agentBoolean, agentObject, agentOptionalString, agentStatus, agentString, agentUint64, parseAgentControlInfo } from "./agent-codecs.ts";
import { HerdrAgentRefusal, type HerdrRpc, type WirePane } from "./client.ts";

const sameTerminal = (a: AgentOccupant, b: AgentOccupant): boolean => a.paneId === b.paneId && a.terminalId === b.terminalId && a.workspaceId === b.workspaceId && a.tabId === b.tabId && (a[ORIGINAL_RUNTIME_INCARNATION] ?? null) === (b[ORIGINAL_RUNTIME_INCARNATION] ?? null) && a.runtimeIdentity === b.runtimeIdentity;
const session = (pane: WirePane): string | null => pane.agent_session ? createHash("sha256").update(JSON.stringify([pane.agent_session.source, pane.agent_session.agent, pane.agent_session.kind, pane.agent_session.value])).digest("hex") : null;
const NO_EFFECT = new Set(["invalid_agent_name", "unsupported_agent_kind", "invalid_agent_argument", "invalid_agent_timeout", "agent_pane_not_found", "agent_pane_busy", "agent_pane_unavailable", "agent_name_taken", "agent_not_found", "agent_target_ambiguous", "agent_launch_pending", "empty_agent_prompt", "agent_blocked", "agent_not_ready", "invalid_key", "pane_not_found", "original_input_retired_before_input", "original_input_unavailable", "original_dispatch_requires_completion"]);

/** Owns protocol translation and brackets every observation with the original runtime/occupant.
 * This is bridge custody evidence, not an atomic compare-and-set inside the vendor resolver. */
export class HerdrAgentControl {
  constructor(private readonly client: HerdrRpc, private readonly runtime: () => Promise<string | null>) {}

  async read(paneId: string): Promise<MuxOutcome<AgentControlInfo>> {
    if (!this.client.getAgentControl) return muxUnsupported("agentAutomation", "Named agent control is unavailable.");
    try {
      let resolved = paneId;
      if (/^[a-z][a-z0-9_-]{0,31}$/.test(paneId) && this.client.listAgentControls) {
        const originalRuntime = await this.runtime(); if (!originalRuntime) return muxGone("The original runtime changed.");
        const matches = (await this.client.listAgentControls()).filter((row) => row.name === paneId);
        if (await this.runtime() !== originalRuntime) return muxGone("The original runtime changed.");
        if (matches.length > 1) return muxRefused("The owning runtime has an ambiguous named target.");
        if (matches.length === 1) resolved = matches[0]!.paneId;
      }
      return muxOk(await this.info(resolved));
    }
    catch { return muxUnreachable("Current agent identity could not be proved."); }
  }

  private async info(paneId: string, signal?: AbortSignal, admission?: import("./client.ts").HerdrDispatchAdmission): Promise<AgentControlInfo> {
    const runtimeIdentity = await this.runtime();
    if (!runtimeIdentity) throw new Error("agent.runtime_changed");
    if (signal?.aborted) throw new Error("agent.observation_cancelled");
    const before = await this.client.getPane(paneId, signal, admission);
    const resolvedPaneId = before.pane_id;
    const process = await this.client.processInfo(resolvedPaneId, signal, admission);
    const agent = before.agent ? await this.client.getAgentControl!(resolvedPaneId, signal, admission) : null;
    const after = await this.client.getPane(resolvedPaneId, signal, admission);
    if (await this.runtime() !== runtimeIdentity || before.pane_id !== after.pane_id || before.terminal_id !== after.terminal_id || before.workspace_id !== after.workspace_id || before.tab_id !== after.tab_id || (before.agent ?? null) !== (after.agent ?? null) || session(before) !== session(after) || process.pane_id !== resolvedPaneId) throw new Error("agent.occupant_changed");
    if (agent && (agent.paneId !== resolvedPaneId || agent.terminalId !== after.terminal_id || agent.workspaceId !== after.workspace_id || agent.tabId !== after.tab_id || agent.agent !== (after.agent ?? null) || agent.sessionIdentity !== session(after))) throw new Error("agent.occupant_changed");
    // Foreground group and shell roots, not a changing roster of agent subprocesses. Missing roots
    // remain explicit unknown evidence and cannot admit a mutation.
    const processIdentity = process.shell_pid && process.foreground_process_group_id ? createHash("sha256").update(JSON.stringify([after.terminal_id, process.shell_pid, process.foreground_process_group_id])).digest("hex") : null;
    const result: AgentControlInfo = { paneId: resolvedPaneId, terminalId: after.terminal_id, workspaceId: after.workspace_id, tabId: after.tab_id, runtimeIdentity, agent: after.agent ?? null, sessionIdentity: session(after), processIdentity, name: agent?.name ?? null, status: agent?.status ?? "unknown", focused: agent?.focused ?? after.focused, interactiveReady: agent?.interactiveReady ?? false, launchPending: agent?.launchPending ?? false, stateChangeSeq: agent?.stateChangeSeq ?? null, completionSeq: agent?.completionSeq ?? null };
    if (process.original_binding) Object.assign(result, { [ORIGINAL_AGENT_BINDING]: process.original_binding, [ORIGINAL_RUNTIME_INCARNATION]: process.original_incarnation });
    return result;
  }

  /** The old public slot may be absent after a proven move, but it may never be occupied by B. */
  private async originalSlot(original: AgentOccupant, owner: AgentObservationOwner, signal?: AbortSignal): Promise<"original" | "absent"> {
    const admission = { runtimeIdentity: original.runtimeIdentity, finalAdmission: owner.current };
    if (!owner.current(original.runtimeIdentity)) throw new HerdrAgentRefusal("observation_retired");
    try {
      const pane = await this.client.getPane(original.paneId, signal, admission);
      if (!owner.current(original.runtimeIdentity) || pane.terminal_id !== original.terminalId) throw new HerdrAgentRefusal("observation_retired");
      return "original";
    } catch (error) {
      if (error instanceof HerdrAgentRefusal && error.vendorCode === "pane_not_found" && owner.current(original.runtimeIdentity)) return "absent";
      throw error;
    }
  }
  async proveMove(original: AgentOccupant, observed: AgentObservedMove, owner: AgentObservationOwner, signal?: AbortSignal): Promise<MuxOutcome<AgentControlInfo>> {
    try {
      if (observed.previousPaneId !== original.paneId || observed.previousWorkspaceId !== original.workspaceId || observed.previousTabId !== original.tabId || !sameAgentIdentity(original, observed.info)) return muxGone("The moved observation belongs to another original actor.");
      await this.originalSlot(original, owner, signal);
      const admission = { runtimeIdentity: original.runtimeIdentity, finalAdmission: owner.current };
      const destination = await this.info(observed.info.paneId, signal, admission);
      if (!sameAgentOccupant(observed.info, destination) || !sameAgentIdentity(original, destination)) return muxGone("The original moved destination changed.");
      await this.originalSlot(original, owner, signal);
      const final = await this.info(observed.info.paneId, signal, admission);
      if (!sameAgentOccupant(destination, final) || !sameAgentIdentity(original, final) || !owner.current(original.runtimeIdentity)) return muxGone("The original moved observation retired before publication.");
      await this.originalSlot(original, owner, signal);
      if (!owner.current(original.runtimeIdentity)) return muxGone("The original moved read owner retired before publication.");
      return muxOk(final);
    } catch { return muxGone("No current original moved observation could be proved."); }
  }

  async proveTransition(original: AgentOccupant, transition: AgentObservedTransition, owner: AgentObservationOwner, signal?: AbortSignal): Promise<MuxOutcome<void>> {
    try {
      if (!original[ORIGINAL_AGENT_BINDING] || !original[ORIGINAL_RUNTIME_INCARNATION] || transition.previousPaneId !== original.paneId || transition.previousWorkspaceId !== original.workspaceId || transition.previousTabId !== original.tabId || transition.terminalId !== original.terminalId || !owner.current(original.runtimeIdentity)) return muxGone("Original transition custody changed.");
      const slot = await this.originalSlot(original, owner, signal);
      const admission = { runtimeIdentity: original.runtimeIdentity, finalAdmission: owner.current };
      if (transition.event === "agent-detected") {
        if (slot !== "original" || !transition.after) return muxGone("The original detected terminal changed.");
        const after = await this.info(original.paneId, signal, admission);
        if (after.terminalId !== original.terminalId || after[ORIGINAL_RUNTIME_INCARNATION] !== original[ORIGINAL_RUNTIME_INCARNATION] || !sameAgentOccupant(transition.after, after)) return muxGone("The original detected result changed.");
      } else if (slot === "original") {
        if (transition.event !== "pane-exited") return muxGone("A closed original terminal is still present.");
        try {
          const process = await this.client.processInfo(original.paneId, signal, admission);
          if (process.runtime_incarnation !== original[ORIGINAL_RUNTIME_INCARNATION]) return muxGone("An original exit cannot describe a replacement runtime.");
        } catch (error) { if (!(error instanceof HerdrAgentRefusal) || error.vendorCode !== "pane_not_found") throw error; }
      }
      // Reconcile the original public slot after the final network await. An absent slot is
      // permitted only by this retained registered transition; a B occupant always refuses.
      const finalSlot = await this.originalSlot(original, owner, signal);
      if (transition.event !== "pane-exited" && transition.event !== "agent-detected" && finalSlot !== "absent") return muxGone("The original closed slot changed.");
      if (transition.event === "agent-detected") {
        if (finalSlot !== "original" || !transition.after) return muxGone("The detected original slot disappeared.");
        const final = await this.info(original.paneId, signal, admission);
        if (!sameAgentOccupant(transition.after, final) || final[ORIGINAL_RUNTIME_INCARNATION] !== original[ORIGINAL_RUNTIME_INCARNATION]) return muxGone("The detected role changed before publication.");
      }
      return owner.current(original.runtimeIdentity) ? muxOk(undefined) : muxGone("The original transition owner retired.");
    } catch { return muxGone("No current original transition could be proved."); }
  }

  async list(): Promise<MuxOutcome<readonly AgentControlInfo[]>> {
    if (!this.client.listAgentControls) return muxUnsupported("agentAutomation", "Named agent listing is unavailable.");
    try {
      const runtimeIdentity = await this.runtime(); if (!runtimeIdentity) return muxGone("The original runtime changed.");
      const rows = await this.client.listAgentControls();
      if (await this.runtime() !== runtimeIdentity) return muxGone("The original runtime changed.");
      return muxOk(rows.map((row) => Object.assign({}, row, { runtimeIdentity, processIdentity: null })));
    } catch { return muxUnreachable("Named agent listing could not be proved."); }
  }
  async readNamed(expected: AgentOccupant, source: "visible" | "recent" | "recent_unwrapped" | "detection", lines: number, finalAdmission: (runtime: string) => boolean): Promise<MuxOutcome<import("../types.ts").MuxGrid>> {
    if (!this.client.readAgentControl) return muxUnsupported("agentAutomation", "Named agent reads are unavailable.");
    try {
      if (!sameAgentOccupant(expected, await this.info(expected.paneId)) || !finalAdmission(expected.runtimeIdentity)) return muxGone("The original agent changed.");
      const { read, marker } = await this.client.readAgentControl(expected.paneId, source, lines, { runtimeIdentity: expected.runtimeIdentity, finalAdmission });
      if (read.pane_id !== expected.paneId || read.workspace_id !== expected.workspaceId || read.tab_id !== expected.tabId || read.source !== source || read.format !== "ansi" || agentUint64(read.revision, marker) === null || !sameAgentOccupant(expected, await this.info(expected.paneId)) || !finalAdmission(expected.runtimeIdentity)) return muxGone("The original agent changed.");
      return muxOk({ paneId: expected.paneId, text: boundedText(read.text), truncated: agentBoolean(read.truncated), revision: 0 });
    } catch { return muxUnreachable("Named agent content could not be proved."); }
  }

  async explain(expected: AgentOccupant): Promise<MuxOutcome<AgentExplain>> {
    if (!this.client.explainAgent) return muxUnsupported("agentAutomation", "Agent explanation is unavailable.");
    try {
      if (!sameAgentOccupant(expected, await this.info(expected.paneId))) return muxGone("The original agent changed.");
      const row = await this.client.explainAgent(expected.paneId);
      if (!sameAgentOccupant(expected, await this.info(expected.paneId))) return muxGone("The original agent changed.");
      const agent = agentString(row.agent, 128);
      if (agent !== expected.agent) return muxGone("The explanation belongs to another agent.");
      return muxOk({ paneId: expected.paneId, terminalId: expected.terminalId, agent, status: agentStatus(row.state), manifestSource: agentOptionalString(row.manifest_source, 1024), manifestVersion: agentOptionalString(row.manifest_version, 128), fallbackReason: agentOptionalString(row.fallback_reason, 1024), warning: agentOptionalString(row.warning, 1024), visibleIdle: agentBoolean(row.visible_idle), visibleWorking: agentBoolean(row.visible_working), visibleBlocked: agentBoolean(row.visible_blocker), detectionSkipped: agentBoolean(row.screen_detection_skipped) });
    } catch { return muxUnreachable("Agent explanation could not be proved."); }
  }

  async action(supplied: AgentAction, expected: AgentOccupant, finalAdmission: (runtime: string) => boolean, signal?: AbortSignal, beforeFirstBytes?: () => Promise<boolean>): Promise<AgentActionOutcome> {
    if (!this.client.actOnAgent) return muxUnsupported("agentAutomation", "Named agent actions are unavailable.");
    const original = Object.freeze({ ...expected }); const action = immutableAction(supplied);
    let prepared = action;
    if (action.kind === "keys" || action.kind === "input") {
      const keys = action.keys.map(toHerdrKey);
      if (keys.some((key) => !key.ok)) return muxRefused("The owning runtime cannot deliver this key sequence.");
      prepared = { ...action, keys: keys.filter((key) => key.ok).map((key) => key.key) };
    }
    const admitted = (runtime: string): boolean => runtime === original.runtimeIdentity && !signal?.aborted && finalAdmission(runtime);
    let before: AgentControlInfo; let dispatchBaseline: AgentControlInfo | null = null;
    try {
      before = await this.info(original.paneId);
      if (!original.processIdentity || !admitted(original.runtimeIdentity) || !sameAgentOccupant(original, before) || !admitted(original.runtimeIdentity)) return muxRefused("The original agent action is no longer admitted.");
    } catch { return muxRefused("The original agent could not be proved before dispatch."); }
    try {
      const originalPreflight = async (): Promise<boolean> => {
        if (!admitted(original.runtimeIdentity) || !sameAgentOccupant(original, await this.info(original.paneId, signal))) return false;
        if (beforeFirstBytes && !await beforeFirstBytes()) return false;
        const latest = await this.info(original.paneId, signal);
        if (!admitted(original.runtimeIdentity) || !sameAgentOccupant(original, latest)) return false;
        dispatchBaseline = latest; return admitted(original.runtimeIdentity);
      };
      const dispatch: import("./client.ts").HerdrDispatchAdmission = { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted, beforeFirstBytes: originalPreflight };
      if (original[ORIGINAL_AGENT_BINDING] !== undefined) Object.assign(dispatch, { originalBinding: original[ORIGINAL_AGENT_BINDING] });
      const result = await this.client.actOnAgent(original.paneId, prepared, dispatch, signal);
      const current = await this.info(original.paneId);
      if (!admitted(original.runtimeIdentity) || !sameTerminal(original, current) || result.terminalId !== original.terminalId || result.paneId !== original.paneId || result.workspaceId !== original.workspaceId || result.tabId !== original.tabId || result.agent !== current.agent || result.name !== current.name || result.sessionIdentity !== current.sessionIdentity) return muxUnreachable("Agent dispatch may have occurred; its original outcome is unknown.");
      const intended = action.kind === "start" ? current.agent === action.agent && current.name === action.name : action.kind === "rename" ? current.name === action.name && current.agent === original.agent && current.sessionIdentity === original.sessionIdentity && current.processIdentity === original.processIdentity : sameAgentOccupant(original, current) && (action.kind !== "focus" || result.focused && current.focused);
      if (action.kind === "prompt" && action.until !== null) {
        // SAFETY: the production client invokes originalPreflight immediately before first bytes.
        const baseline = dispatchBaseline as AgentControlInfo | null;
        const advanced = baseline?.stateChangeSeq !== null && baseline?.stateChangeSeq !== undefined && result.stateChangeSeq !== null && BigInt(result.stateChangeSeq) > BigInt(baseline.stateChangeSeq);
        if (!advanced || baseline?.status === "working" || !action.until.includes(result.status) || !action.until.includes(current.status)) return muxUnreachable("Prompt may have been delivered; activity and settling could not both be proved.");
      }
      if (!intended) return muxUnreachable("Agent dispatch may have occurred; its original outcome is unknown.");
      return muxOk({ info: current, submitted: true, activityObserved: action.kind === "prompt" && action.until !== null, settled: action.kind === "prompt" && action.until !== null });
    } catch (error) {
      // Prefix acceptance wins over every diagnostic latch. Only a proven zero-byte boundary or
      // the exact owning vendor's documented pre-effect code is a known refusal.
      if (error instanceof WriteAdmissionRetired) return error.bytesMayHaveBeenSent ? muxUnreachable("Agent dispatch may have occurred; its original outcome is unknown.") : muxRefused("Agent action retired before any request bytes.");
      const code = error instanceof HerdrAgentRefusal ? error.vendorCode : undefined;
      if (code && NO_EFFECT.has(code)) return Object.assign(muxRefused("The owning runtime refused the agent action before its input effect."), { agentCode: agentErrorCode(code) });
      return Object.assign(muxUnreachable("Agent dispatch may have occurred; its original outcome is unknown."), code === undefined ? {} : { agentCode: agentErrorCode(code) });
    }
  }

  async wait(supplied: AgentWaitCondition, expected: AgentOccupant, timeoutMs: number, finalAdmission: (runtime: string) => boolean, signal: AbortSignal, observationOwner?: AgentObservationOwner): Promise<AgentWaitOutcome> {
    if (!this.client.waitForAgentCondition) return muxUnsupported("agentWaits", "Agent observations are unavailable.");
    const waitForCondition = this.client.waitForAgentCondition.bind(this.client);
    const condition = immutableCondition(supplied);
    const original = Object.freeze({ ...expected }); const controller = new AbortController();
    const moved = condition.kind === "event" && condition.event === "pane-moved";
    const transitioned = condition.kind === "event" && AGENT_TERMINAL_TRANSITIONS.some((event) => event === condition.event) && original[ORIGINAL_AGENT_BINDING] !== undefined;
    const originalObserver = moved || transitioned;
    if (originalObserver && (!observationOwner || this.client.originalAgentObservations?.() !== true)) return muxUnsupported("agentWaits", "Original registered move observations are unavailable.");
    const observationAdmission = originalObserver && observationOwner ? observationOwner.current : finalAdmission;
    const abort = () => controller.abort(); signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
    let current = true; let finished = false;
    const admitted = (runtime: string) => current && runtime === original.runtimeIdentity && !controller.signal.aborted && observationAdmission(runtime);
    // One read chain per observation. It never replaces the captured actor with a new occupant.
    const monitor = async () => {
      while (!controller.signal.aborted) {
        await delayObservation(1000, controller.signal);
        if (finished || controller.signal.aborted) break;
        try {
          if (originalObserver && observationOwner) { await this.originalSlot(original, observationOwner, controller.signal); current = admitted(original.runtimeIdentity); }
          else current = admitted(original.runtimeIdentity) && sameAgentOccupant(original, await this.info(original.paneId, controller.signal));
        }
        catch { current = false; }
        if (!current) controller.abort();
      }
    };
    let watching: Promise<void> | null = null;
    const observe = async (): Promise<AgentWaitOutcome> => {
    try {
      if (!admitted(original.runtimeIdentity) || !sameAgentOccupant(original, await this.info(original.paneId, controller.signal)) || !admitted(original.runtimeIdentity)) return muxGone("The original observation actor changed.");
      watching = monitor();
      if (condition.kind === "event" && condition.event === "pane-output-changed") {
        if (!this.client.sampleAgentOutputRevision) return muxUnsupported("agentWaits", "Original output revision observation is unavailable.");
        const sample = async (): Promise<{ revision: string; digest: string }> => {
          const { read, marker: sampleMarker } = await this.client.sampleAgentOutputRevision!(original.paneId, { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted }, controller.signal);
          const revision = agentUint64(read.revision, sampleMarker);
          if (read.pane_id !== original.paneId || read.workspace_id !== original.workspaceId || read.tab_id !== original.tabId || read.source !== "visible" || read.format !== "text" || revision === null) throw new Error("agent.output_revision_protocol");
          const text = boundedText(read.text); const truncated = agentBoolean(read.truncated);
          return { revision, digest: createHash("sha256").update(JSON.stringify([text, truncated])).digest("hex") };
        };
        const baseline = await sample(); const deadline = performance.now() + timeoutMs;
        while (performance.now() < deadline && admitted(original.runtimeIdentity)) {
          await delayObservation(1000, controller.signal);
          const observed = await sample();
          if (BigInt(observed.revision) < BigInt(baseline.revision)) throw new Error("agent.output_revision_rollback");
          // Stock v0.9.3 returns revision zero. A bounded content change still observes
          // output; neither a pre-existing value nor a zero counter means new activity.
          if (observed.digest !== baseline.digest || BigInt(observed.revision) > BigInt(baseline.revision)) {
            if (!sameAgentOccupant(original, await this.info(original.paneId, controller.signal)) || !admitted(original.runtimeIdentity)) return muxGone("The original output observer retired.");
            return muxOk({ kind: "event", event: condition.event, targetId: original.paneId, status: null });
          }
        }
        throw new HerdrAgentRefusal("timeout");
      }
      let raw: import("../../json.ts").JsonObject; let marker: string;
      if (condition.kind === "event" && condition.event !== "agent-status-changed") {
        if (!this.client.observeAgentEvent) return muxUnsupported("agentWaits", "Bounded original event subscription is unavailable.");
        const observation: import("./client.ts").HerdrDispatchAdmission = { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted, beforeFirstBytes: async () => finalAdmission(original.runtimeIdentity) && sameAgentOccupant(original, await this.info(original.paneId, controller.signal)) && finalAdmission(original.runtimeIdentity) };
        if (original[ORIGINAL_AGENT_BINDING] !== undefined) Object.assign(observation, { originalBinding: original[ORIGINAL_AGENT_BINDING], originalPaneId: original.paneId, observationTimeoutMs: timeoutMs });
        raw = await this.client.observeAgentEvent(condition, observation, controller.signal, (event, data) => matchedAgentEvent(event, data, condition, original) !== null, originalObserver);
        marker = "subscription-does-not-publish-counters";
      } else {
        const oneShot = await waitForCondition(original.paneId, condition, timeoutMs, { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted }, controller.signal);
        raw = oneShot.raw; marker = oneShot.marker;
      }
      if (transitioned && observationOwner && condition.kind === "event") {
        const data = agentObject(raw.data);
        if (data.original_transition === undefined) throw new HerdrAgentRefusal("event_original_terminal_unproved");
        // SAFETY: transitioned is set only for a condition in AGENT_TERMINAL_TRANSITIONS.
        const event = condition.event as AgentTerminalTransitionKind;
        const after = event === "agent-detected" ? await this.info(original.paneId, controller.signal, { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted }) : null;
        if (after && (after.agent !== agentOptionalString(data.agent, 128) || after.terminalId !== original.terminalId || after[ORIGINAL_RUNTIME_INCARNATION] !== original[ORIGINAL_RUNTIME_INCARNATION])) return muxGone("The original detected frame changed.");
        const transition: AgentObservedTransition = { event, previousPaneId: original.paneId, previousWorkspaceId: original.workspaceId, previousTabId: original.tabId, terminalId: original.terminalId, after };
        const proved = await this.proveTransition(original, transition, observationOwner, controller.signal);
        if (!proved.ok) return proved;
        const result = matchedAgentEvent(agentString(raw.event, 64), data, condition, original); if (!result || result.kind !== "event") throw new HerdrAgentRefusal("event_original_terminal_unproved");
        return muxOk({ ...result, transition });
      }
      if (moved && observationOwner && condition.kind === "event") {
        const data = agentObject(raw.data); const destinationRow = agentObject(data.pane);
        const destination = await this.info(agentString(destinationRow.pane_id, 128), controller.signal, { runtimeIdentity: original.runtimeIdentity, finalAdmission: admitted });
        if (destination.terminalId !== destinationRow.terminal_id || destination.workspaceId !== destinationRow.workspace_id || destination.tabId !== destinationRow.tab_id || !sameAgentIdentity(original, destination)) return muxGone("The original move frame destination changed.");
        const move: AgentObservedMove = { previousPaneId: original.paneId, previousWorkspaceId: original.workspaceId, previousTabId: original.tabId, info: destination };
        const proved = await this.proveMove(original, move, observationOwner, controller.signal);
        if (!proved.ok) return proved;
        return muxOk({ kind: "event", event: "pane-moved", targetId: destination.paneId, status: null, move: { ...move, info: proved.value } });
      }
      if (!admitted(original.runtimeIdentity) || !sameAgentOccupant(original, await this.info(original.paneId, controller.signal)) || !admitted(original.runtimeIdentity)) return muxGone("The original observation actor changed.");
      if (condition.kind === "lifecycle") {
        if (raw.type !== "agent_info") throw new Error("agent.protocol");
        const row = parseAgentControlInfo(raw.agent, marker);
        if (row.paneId !== original.paneId || row.terminalId !== original.terminalId || row.agent !== original.agent || row.sessionIdentity !== original.sessionIdentity || !condition.until.includes(row.status)) throw new Error("agent.protocol");
        return muxOk({ kind: "lifecycle", info: { ...original, ...row } });
      }
      if (condition.kind === "output") {
        if (raw.type !== "output_matched" || raw.pane_id !== original.paneId || agentUint64(raw.revision, marker) === null) throw new Error("agent.protocol");
        const read = agentObject(raw.read);
        if (read.pane_id !== original.paneId || read.workspace_id !== original.workspaceId || read.tab_id !== original.tabId || read.source !== condition.source || read.format !== "text" || agentUint64(read.revision, marker) === null) throw new Error("agent.protocol");
        return muxOk({ kind: "output", paneId: original.paneId, text: boundedText(read.text), matchedLine: raw.matched_line === undefined || raw.matched_line === null ? null : boundedText(raw.matched_line), truncated: agentBoolean(read.truncated) });
      }
      const envelope = condition.event === "agent-status-changed" ? agentObject(raw.event) : raw;
      if (condition.event === "agent-status-changed" && raw.type !== "wait_matched") throw new Error("agent.protocol");
      const result = matchedAgentEvent(agentString(envelope.event, 64), agentObject(envelope.data), condition, original);
      if (result === null) throw new Error("agent.event_protocol"); return muxOk(result);
    } catch (error) { return Object.assign(signal.aborted ? muxRefused("Observation cancelled; delivered agent work was not undone.") : muxGone("The observation ended without a current original-actor result."), error instanceof HerdrAgentRefusal ? { agentCode: agentErrorCode(error.vendorCode) } : {}); }
    };
    let outcome: AgentWaitOutcome;
    try { outcome = await observe(); }
    finally { finished = true; controller.abort(); signal.removeEventListener("abort", abort); if (watching) await watching; }
    // Cancelling and draining the observer can itself await socket callbacks. The final
    // original proof comes after that cleanup, without minting a successor observation.
    if (outcome.ok) {
      try {
        if (outcome.value.kind === "event" && outcome.value.transition && observationOwner) {
          if (signal.aborted) return muxGone("The original transition observer was cancelled.");
          const final = await this.proveTransition(original, outcome.value.transition, observationOwner);
          return final.ok ? outcome : final;
        }
        if (outcome.value.kind === "event" && outcome.value.move && observationOwner) {
          if (signal.aborted) return muxGone("The original moved observer was cancelled.");
          const final = await this.proveMove(original, outcome.value.move, observationOwner);
          return final.ok ? muxOk({ ...outcome.value, move: { ...outcome.value.move, info: final.value } }) : final;
        }
        if (signal.aborted || !finalAdmission(original.runtimeIdentity) || !sameAgentOccupant(original, await this.info(original.paneId)) || signal.aborted || !finalAdmission(original.runtimeIdentity)) return muxGone("The original observation retired before publication.");
      } catch { return muxGone("The original observation could not be proved at publication."); }
    }
    return outcome;
  }
}

/* eslint-disable anti-slop/no-runtime-typeof */
function boundedText(value: import("../../json.ts").JsonValue | undefined): string {
  if (typeof value !== "string" || Buffer.byteLength(value) > AGENT_LIMITS.resultBytes || value.includes("\u0000")) throw new Error("agent.protocol: invalid bounded output");
  return value;
}
/* eslint-enable anti-slop/no-runtime-typeof */

function delayObservation(ms: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise<void>((resolve) => {
    let completed = false;
    // Timeout and abort share one completion. The guard is set before resolving and both sources are detached.
    // eslint-disable-next-line promise/no-multiple-resolved
    const finish = () => { if (completed) return; completed = true; clearTimeout(timer); signal.removeEventListener("abort", finish); resolve(); };
    const timer = setTimeout(finish, ms); signal.addEventListener("abort", finish, { once: true });
  });
}

function immutableAction(action: AgentAction): AgentAction {
  switch (action.kind) {
    case "start": return Object.freeze({ ...action, args: Object.freeze([...action.args]) });
    case "prompt": return Object.freeze({ ...action, until: action.until === null ? null : Object.freeze([...action.until]) });
    case "keys": case "input": return Object.freeze({ ...action, keys: Object.freeze([...action.keys]) });
    default: return Object.freeze({ ...action });
  }
}
function immutableCondition(condition: AgentWaitCondition): AgentWaitCondition {
  if (condition.kind === "lifecycle") return Object.freeze({ ...condition, until: Object.freeze([...condition.until]) });
  if (condition.kind === "output") return Object.freeze({ ...condition, match: Object.freeze({ ...condition.match }) });
  return Object.freeze({ ...condition });
}
