// Test-only current protocol peer. Production never imports or discovers this owned socket.
import net from "node:net";
import { createHash, randomUUID } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { JsonObject, JsonValue } from "../../json.ts";
export class AgentTestPeer {
  readonly directory = mkdtempSync(join(tmpdir(), "collie-b19-peer-"));
  // Match the shipping dialer's endpoint on each host. Windows runtime custody requires
  // the actual named-pipe server PID/creation time, never a filesystem socket alias.
  readonly path = process.platform === "win32"
    ? `\\\\.\\pipe\\collie-agent-test-${randomUUID()}`
    : join(this.directory, "herdr.sock");
  readonly requests: { readonly id: string; readonly method: string; readonly params: JsonObject }[] = [];
  readonly connections = new Set<net.Socket>();
  readonly subscriptions = new Map<net.Socket, string>();
  readonly registrations = new Map<net.Socket, { id: string; start: number; before: JsonObject | null }>();
  originalObservations = false; eventSequence = 0;
  originalInputs = false; panePresent = true;
  originalCustody = false; runtimeIncarnation = randomUUID(); inputEpoch = 0;
  paneId = "w1:p1"; workspaceId = "w1"; tabId = "w1:t1";
  originalSlotReplacement: string | null = null;
  outputRevision = 0;
  outputText = "> ready";
  readonly server: net.Server;
  terminal = "terminal-A"; agent: string | null = "codex"; name: string | null = "original";
  sessionValue = "session-original";
  status = "idle"; process = 202; pending = false; sequence = 0; completion = 0;
  effects = 0; held: Promise<void> | null = null; beforeEffect: (() => void) | null = null;
  afterAcceptedRejection: string | null = null;
  rejection: string | null = null; wrongId = false; activityAfterPrompt = true;
  explainRejection = false;
  waitResult: JsonObject | null = null;
  private seenWait: (() => void) | null = null;
  readonly waitEntered = new Promise<void>((resolve) => { this.seenWait = resolve; });
  constructor() {
    this.server = net.createServer((connection) => {
      this.connections.add(connection); connection.on("close", () => { this.connections.delete(connection); this.subscriptions.delete(connection); this.registrations.delete(connection); }); connection.on("error", () => {});
      let text = "";
      connection.on("data", (chunk) => {
        text += chunk.toString(); const newline = text.indexOf("\n"); if (newline < 0) return;
        // SAFETY: only the production client under test creates this request at the owned socket.
        const request = JSON.parse(text.slice(0, newline)) as { id: string; method: string; params: JsonObject };
        text = ""; this.requests.push(request);
        if (request.method === "events.subscribe") {
          const list = request.params.subscriptions;
          // SAFETY: only the production client creates this exact closed owned request.
          const subscriptions = list as JsonObject[];
          const kind = string(subscriptions[0]?.type); const marker = subscriptions.find((row) => row.type === "original.agent_observation");
          if (marker && !this.originalObservations) { connection.end(`${JSON.stringify({ id: request.id, error: { code: "invalid_original_observation", message: "owned old producer" } })}\n`); return; }
          if (marker?.original_binding !== undefined && (marker.original_binding !== this.custody().original_binding || marker.pane_id !== this.paneId)) { connection.end(`${JSON.stringify({ id: request.id, error: { code: "original_observation_unavailable", message: "owned original changed" } })}\n`); return; }
          this.subscriptions.set(connection, kind);
          const proof = marker ? { registration_id: string(marker.registration_id), start_sequence: String(this.eventSequence) } : null;
          if (proof) this.registrations.set(connection, { id: proof.registration_id, start: this.eventSequence, before: marker?.original_binding === undefined ? null : this.transitionProof() });
          const result: JsonObject = { type: "subscription_started" }; if (proof) result.original_observation = proof;
          connection.write(`${JSON.stringify({ id: request.id, result })}\n`); return;
        }
        void this.answer(request).then((answer) => { if (!connection.destroyed) connection.end(`${JSON.stringify({ id: this.wrongId ? "another-call" : request.id, ...answer })}\n`); return undefined; }, () => connection.destroy());
      });
    });
  }
  async listen(): Promise<void> { await new Promise<void>((resolve, reject) => { this.server.once("error", reject); this.server.listen(this.path, resolve); }); }
  close(): void { for (const connection of this.connections) connection.destroy(); this.server.close(); rmSync(this.directory, { recursive: true, force: true }); }
  emitEvent(event: string, data: JsonObject): void {
    this.eventSequence++;
    for (const [connection, subscription] of this.subscriptions) if (subscription === event.replace("_", ".")) {
      const registration = this.registrations.get(connection);
      const payload: JsonObject = { ...data }; const frame: JsonObject = { event, data: payload };
      if (["pane_closed", "pane_exited", "tab_closed", "workspace_closed", "pane_agent_detected"].includes(event) && registration?.before) payload.original_transition = registration.before;
      if (registration) frame.original_observation = { registration_id: registration.id, sequence: String(this.eventSequence) };
      connection.write(`${JSON.stringify(frame)}\n`);
    }
  }
  emitRawFrame(text: string): void { for (const connection of this.subscriptions.keys()) connection.write(text); }
  loseEventStreams(): void { for (const connection of this.subscriptions.keys()) connection.destroy(); }
  pane(): JsonObject { return { pane_id: this.paneId, terminal_id: this.terminal, workspace_id: this.workspaceId, tab_id: this.tabId, agent: this.agent, agent_status: this.status, focused: true, cwd: "/fixture", revision: 0, agent_session: this.agent === null ? null : { source: "codex", agent: this.agent, kind: "id", value: this.sessionValue } }; }
  custody(): JsonObject {
    const original_binding = createHash("sha256").update(JSON.stringify([this.runtimeIncarnation, this.inputEpoch, this.paneId, this.terminal, this.workspaceId, this.tabId, this.agent, this.sessionValue, this.process, this.name])).digest("hex");
    return { original_binding, original_incarnation: this.runtimeIncarnation, original_epoch: String(this.inputEpoch) };
  }
  transitionProof(): JsonObject { return { binding: this.custody().original_binding!, incarnation: this.runtimeIncarnation, epoch: String(this.inputEpoch), role_epoch: String(this.inputEpoch), pane_id: this.paneId, terminal_id: this.terminal, workspace_id: this.workspaceId, tab_id: this.tabId }; }
  info(): JsonObject { return { ...this.pane(), name: this.name, interactive_ready: true, launch_pending: this.pending, state_change_seq: this.sequence, completion_seq: this.completion }; }
  private async answer(request: { readonly method: string; readonly params: JsonObject }): Promise<JsonObject> {
    if (request.method === "ping") { const result: JsonObject = { type: "pong", version: "0.9.3", protocol: 22 }; const capabilities: JsonObject = {};
      if (this.originalObservations) capabilities.agent_observation_cursor_v1 = true; if (this.originalInputs) capabilities.agent_original_input_v1 = true;
      if (this.originalObservations || this.originalInputs) result.capabilities = capabilities; return { result }; }
    if (request.method === "pane.get") {
      if (this.panePresent && request.params.pane_id === this.paneId) return { result: { type: "pane_info", pane: this.pane() } };
      if (request.params.pane_id === "w1:p1" && this.originalSlotReplacement) return { result: { type: "pane_info", pane: { ...this.pane(), pane_id: "w1:p1", terminal_id: this.originalSlotReplacement } } };
      return { error: { code: "pane_not_found", message: "owned absent original slot" } };
    }
    if (request.method === "pane.process_info") { const process_info: JsonObject = { pane_id: this.paneId, shell_pid: 101, foreground_process_group_id: this.process, foreground_processes: [{ pid: this.process, name: this.agent ?? "shell" }], truncated: false };
      if (this.originalCustody) Object.assign(process_info, this.custody(), { runtime_incarnation: this.runtimeIncarnation }); return { result: { type: "pane_process_info", process_info } }; }
    if (request.method === "agent.list") return { result: { type: "agent_list", agents: [this.info()] } };
    if (request.method === "agent.get") return { result: { type: "agent_info", agent: this.info() } };
    if (request.method === "pane.read" || request.method === "agent.read") return { result: { type: "pane_read", read: { pane_id: "w1:p1", workspace_id: "w1", tab_id: "w1:t1", source: request.method === "agent.read" ? request.params.source ?? "recent" : "visible", format: request.params.format ?? "ansi", text: this.outputText, revision: this.outputRevision, truncated: false } } };
    if (request.method === "agent.explain") return this.explainRejection ? { error: { code: "agent_explain_unavailable", message: "owned private explanation detail" } } : { result: { type: "agent_explain", explain: { agent: this.agent, state: this.status, manifest_source: "embedded", manifest_version: "1", fallback_reason: null, warning: null, visible_idle: true, visible_working: false, visible_blocker: false, screen_detection_skipped: false } } };
    if (["agent.start", "agent.rename", "agent.prompt", "agent.focus", "agent.send_keys", "pane.send_input"].includes(request.method)) {
      if (this.held) await this.held; this.beforeEffect?.();
      if (this.originalInputs && request.params.original_binding !== this.custody().original_binding) return { error: { code: "original_input_retired_before_input", message: "owned original queued input retired" } };
      if (this.rejection) return { error: { code: this.rejection, message: "owned fixture refusal" } };
      this.effects++;
      if (this.afterAcceptedRejection) return { error: { code: this.afterAcceptedRejection, message: "owned accepted prefix lost" } };
      if (request.method === "agent.start") { this.agent = string(request.params.kind); this.name = string(request.params.name); this.process++; return { result: { type: "agent_started", agent: this.info(), argv: [this.agent] } }; }
      if (request.method === "agent.rename") { this.name = request.params.name === null ? null : string(request.params.name); return { result: { type: "agent_info", agent: this.info() } }; }
      if (request.method === "agent.focus") return { result: { type: "agent_info", agent: this.info() } };
      if (request.method === "agent.send_keys" || request.method === "pane.send_input") return { result: { type: "ok" } };
      if (request.params.wait !== undefined && this.activityAfterPrompt) { this.sequence++; this.completion++; }
      return { result: { type: "agent_prompted", agent: this.info() } };
    }
    if (["agent.wait", "pane.wait_for_output", "events.wait"].includes(request.method)) {
      this.seenWait?.(); if (this.held) await this.held;
      if (this.rejection) return { error: { code: this.rejection, message: "owned fixture wait refusal" } };
      if (this.waitResult) return { result: this.waitResult };
      return { result: { type: "agent_info", agent: this.info() } };
    }
    throw new Error("unexpected owned fixture method");
  }
}
function string(value: JsonValue | undefined): string {
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (typeof value !== "string") throw new Error("fixture expected string"); return value;
}
