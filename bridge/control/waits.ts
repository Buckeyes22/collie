import { maskAgentWaitAnswer } from "./agent-response.ts";
import { randomUUID, createHmac } from "node:crypto";
import { mkdir, open, rename, unlink } from "node:fs/promises";
import { join } from "node:path";
import { agentErrorCode, ORIGINAL_AGENT_BINDING, AGENT_TERMINAL_TRANSITIONS, AGENT_LIMITS, sameAgentOccupant, type AgentErrorCode, type AgentWaitCondition, type AgentWaitResult } from "./agent-models.ts";
import { agentId, agentKeys, agentRecord, agentRequestId, agentText, agentTimeout, parseAgentWait, readAgentBody, strictAgentJson } from "./agent-requests.ts";
import { captureAgentContext, agentFailure, equalAgentWitness, type AgentCaller, type AgentContext } from "./agents.ts";
import { canonicalPayload, controlScope } from "./contracts.ts";

type WaitState = "pending" | "matched" | "cancelled" | "retired" | "timed-out" | "interrupted";
interface WaitReceipt {
  readonly id: string; readonly owner: string; readonly host: string; readonly session: string | null;
  readonly paneId: string; readonly hash: string; readonly witness: string;
  readonly at: number; readonly deadline: number; readonly state: WaitState; readonly code: AgentErrorCode | null;
}
export interface WaitView { readonly schema: 1; readonly id: string; readonly paneId: string; readonly state: WaitState; readonly code: AgentErrorCode | null; readonly deadline: number; readonly result: AgentWaitResult | null }
interface ActiveWait { readonly receipt: WaitReceipt; readonly abort: AbortController; readonly context: AgentContext; readonly followMove: boolean; timer: ReturnType<typeof setTimeout> | null }

/** Only bounded opaque custody is durable. Patterns, prompts and matched output stay in memory.
 * Restart turns unfinished observations into interrupted receipts; it never reissues vendor RPC. */
export class AgentWaitStore {
  private readonly rows = new Map<string, WaitReceipt>();
  private readonly active = new Map<string, ActiveWait>();
  private readonly results = new Map<string, AgentWaitResult>();
  private readonly custody = new Map<string, AgentContext>();
  private tail: Promise<void> = Promise.resolve(); private loaded = false; private failed = false;
  constructor(private readonly directory: string, private readonly now: () => number = Date.now) {}
  private serial<T>(job: () => Promise<T>): Promise<T> { const result = this.tail.then(job); this.tail = result.then(() => undefined, () => undefined); return result; }
  private path(): string { return join(this.directory, "agent-waits-v1.json"); }
  private async load(): Promise<void> {
    if (this.failed) throw new Error("wait.storage_unavailable"); if (this.loaded) return;
    let handle: Awaited<ReturnType<typeof open>> | null = null;
    try {
      try { handle = await open(this.path(), "r"); } catch (error) { if (error instanceof Error && "code" in error && error.code === "ENOENT") { this.loaded = true; return; } throw error; }
      const size = (await handle.stat()).size; if (size > 1024 * 1024) throw new Error("wait.storage_bound");
      const buffer = Buffer.alloc(size + 1); let length = 0;
      while (length < buffer.length) { const read = await handle.read(buffer, length, buffer.length - length, null); if (!read.bytesRead) break; length += read.bytesRead; }
      if (length > size) throw new Error("wait.storage_changed");
      // The durable document uses the same bounded grammar, with a separate row-array bound.
      const parsed = parseWaitJournal(new TextDecoder("utf-8", { fatal: true }).decode(buffer.subarray(0, length)));
      if (parsed.length > AGENT_LIMITS.retainedGlobal) throw new Error("wait.storage_bound");
      for (const row of parsed) { if (this.rows.has(row.id)) throw new Error("wait.duplicate_id"); this.rows.set(row.id, row.state === "pending" || row.state === "matched" ? { ...row, state: "interrupted" } : row); }
      this.loaded = true; await this.persist();
    } catch { this.failed = true; throw new Error("wait.storage_unavailable"); }
    finally { await handle?.close(); }
  }
  private async persist(): Promise<void> {
    const text = JSON.stringify([...this.rows.values()]); if (Buffer.byteLength(text) > 1024 * 1024) throw new Error("wait.storage_bound");
    // Production supplies cfg.stateDir, whose private Windows ACL is established at startup.
    const stateDir = this.directory; const target = join(stateDir, "agent-waits-v1.json");
    await mkdir(stateDir, { recursive: true, mode: 0o700 });
    const temporary = `${target}.${randomUUID()}.tmp`;
    let handle: Awaited<ReturnType<typeof open>> | null = null;
    try { handle = await open(temporary, "wx", 0o600); await handle.writeFile(text); await handle.sync(); await handle.close(); handle = null; await rename(temporary, target); }
    finally { await handle?.close(); await unlink(temporary).catch(() => {}); }
  }
  async inspect(id: string, owner: string, host: string, session: string | null): Promise<WaitView | null> {
    const row = await this.serial(async () => { await this.load(); const found = this.rows.get(id); return found && found.owner === owner && found.host === host && found.session === session ? found : null; });
    if (!row) return null;
    const context = this.custody.get(id);
    const result = this.results.get(id); const moving = result?.kind === "event" && (result.move !== undefined || result.transition !== undefined) || this.active.get(id)?.followMove === true;
    let valid = context === undefined || (moving ? context.observationOwner.current(context.original.runtimeIdentity) : context.current(context.original.runtimeIdentity));
    if (valid && context && this.results.has(id)) {
      try {
        if (result?.kind === "event" && result.transition) { const current = await context.runtime.mux.agentObservedTransition?.(context.original, result.transition, context.observationOwner); valid = current?.ok === true && context.observationOwner.current(context.original.runtimeIdentity); }
        else if (result?.kind === "event" && result.move) { const current = await context.runtime.mux.agentObservedMove?.(context.original, result.move, context.observationOwner); valid = current?.ok === true && context.observationOwner.current(context.original.runtimeIdentity); }
        else { const current = await context.runtime.mux.agentControl!(context.original.paneId); valid = current.ok && sameAgentOccupant(context.original, current.value) && context.current(context.original.runtimeIdentity); }
      }
      catch { valid = false; }
    }
    if (!valid) {
      const active = this.active.get(id);
      if (active) await this.finish(id, active, "retired", null);
      else await this.serial(async () => { const latest = this.rows.get(id); if (latest) { this.rows.set(id, { ...latest, state: "retired" }); this.results.delete(id); this.custody.delete(id); try { await this.persist(); } catch { this.failed = true; } } });
    }
    const latest = await this.serial(async () => { const found = this.rows.get(id); return found ? this.view(found) : null; });
    if (!latest || !context) return latest;
    // Journal reads/persistence can queue behind another operation. Reconcile only after that
    // final await, outside the journal, and never publish an earlier identity check as current.
    if (latest.result) {
      try {
        if (latest.result.kind === "event" && latest.result.transition) { const current = await context.runtime.mux.agentObservedTransition?.(context.original, latest.result.transition, context.observationOwner); valid = current?.ok === true; }
        else if (latest.result.kind === "event" && latest.result.move) { const current = await context.runtime.mux.agentObservedMove?.(context.original, latest.result.move, context.observationOwner); valid = current?.ok === true; }
        else { const current = await context.runtime.mux.agentControl!(context.original.paneId); valid = current.ok && sameAgentOccupant(context.original, current.value); }
      }
      catch { valid = false; }
    }
    const stillOwned = moving ? context.observationOwner.current(context.original.runtimeIdentity) : context.current(context.original.runtimeIdentity);
    if (!valid || !stillOwned) return { ...latest, state: "retired", result: null };
    return this.results.get(id) === latest.result || latest.result === null ? latest : { ...latest, result: null };
  }
  private view(row: WaitReceipt): WaitView { return { schema: 1, id: row.id, paneId: row.paneId, state: row.state, code: row.code, deadline: row.deadline, result: this.results.get(row.id) ?? null }; }
  async start(id: string, hash: string, context: AgentContext, condition: AgentWaitCondition, timeoutMs: number): Promise<WaitView> {
    const reservation = await this.serial(async () => {
      await this.load(); const old = this.rows.get(id);
      if (old) { if (old.owner !== context.owner || old.host !== context.runtime.host || old.session !== context.runtime.session || old.hash !== hash || old.witness !== context.witness) throw new Error("wait.request_id_reused"); return { fresh: false, row: old }; }
      const now = this.now(); if (!Number.isSafeInteger(now) || now < 0 || now > Number.MAX_SAFE_INTEGER - timeoutMs) throw new Error("wait.retired");
      for (const [key, row] of this.rows) if (!this.active.has(key) && now > row.deadline + AGENT_LIMITS.retentionMs) { this.rows.delete(key); this.results.delete(key); this.custody.delete(key); }
      if ([...this.rows.values()].filter((row) => row.state === "pending").length >= AGENT_LIMITS.activeGlobal || [...this.rows.values()].filter((row) => row.owner === context.owner && row.state === "pending").length >= AGENT_LIMITS.activePerOwner || this.rows.size >= AGENT_LIMITS.retainedGlobal || [...this.rows.values()].filter((row) => row.owner === context.owner).length >= AGENT_LIMITS.retainedPerOwner) throw new Error("wait.capacity");
      const row: WaitReceipt = { id, owner: context.owner, host: context.runtime.host, session: context.runtime.session, paneId: context.original.paneId, hash, witness: context.witness, at: now, deadline: now + timeoutMs, state: "pending", code: null };
      this.rows.set(id, row);
      try { await this.persist(); } catch { this.failed = true; throw new Error("wait.storage_unavailable"); }
      return { fresh: true, row };
    });
    if (!reservation.fresh) {
      // A replay can queue behind journal work after its caller captured the original occupant.
      // Reuse receipt reads' final original-custody proof; never reissue the vendor observation.
      const replay = await this.inspect(id, context.owner, context.runtime.host, context.runtime.session);
      if (!replay) throw new Error("wait.retired");
      return replay;
    }
    const active: ActiveWait = { receipt: reservation.row, abort: new AbortController(), context, followMove: condition.kind === "event" && (condition.event === "pane-moved" || context.original[ORIGINAL_AGENT_BINDING] !== undefined && AGENT_TERMINAL_TRANSITIONS.some((event) => event === condition.event)), timer: null };
    this.active.set(id, active); this.custody.set(id, context);
    active.timer = setTimeout(() => { active.abort.abort(); void this.finish(id, active, "timed-out", null); }, timeoutMs);
    // Outside storage/admission locks. The adapter owns and closes the exact observation socket.
    void (async () => {
      try {
        if (!context.current(context.original.runtimeIdentity) || !context.runtime.mux.agentWait) { await this.finish(id, active, "retired", null); return; }
        const outcome = await context.runtime.mux.agentWait(condition, context.original, timeoutMs, context.current, active.abort.signal, context.observationOwner);
        if (active.abort.signal.aborted) return;
        const movedResult = outcome.ok && outcome.value.kind === "event" && (outcome.value.move !== undefined || outcome.value.transition !== undefined);
        if (!(movedResult ? context.observationOwner.current(context.original.runtimeIdentity) : context.current(context.original.runtimeIdentity))) { await this.finish(id, active, "retired", null); return; }
        await this.finish(id, active, outcome.ok ? "matched" : "interrupted", outcome.ok ? outcome.value : null, outcome.ok ? null : outcome.agentCode ?? null);
      } catch { await this.finish(id, active, "interrupted", null); }
    })();
    return this.view(reservation.row);
  }
  private async finish(id: string, active: ActiveWait, state: WaitState, result: AgentWaitResult | null, code: AgentErrorCode | null = null): Promise<void> {
    if (active.timer) clearTimeout(active.timer); active.abort.abort();
    const current = active.followMove ? active.context.observationOwner.current(active.context.original.runtimeIdentity) : active.context.current(active.context.original.runtimeIdentity);
    await this.serial(async () => {
      if (this.active.get(id) !== active) return;
      const row = this.rows.get(id); if (!row || row.state !== "pending") return;
      const fits = result === null || Buffer.byteLength(JSON.stringify(result)) <= AGENT_LIMITS.resultBytes;
      const next: WaitReceipt = { ...row, code, state: current ? (fits ? state : "interrupted") : "retired" };
      this.rows.set(id, next); this.active.delete(id);
      this.results.delete(id);
      try {
        await this.persist();
        if (current && result && Buffer.byteLength(JSON.stringify(result)) <= AGENT_LIMITS.resultBytes) this.results.set(id, result.kind === "event" && result.transition ? { ...result, transition: { ...result.transition, witness: active.context.witness } } : result.kind === "event" && result.move ? { ...result, move: { ...result.move, witness: active.context.witness } } : result);
      }
      catch { this.failed = true; this.rows.set(id, { ...row, state: "interrupted" }); }
    });
  }
  async cancel(id: string, owner: string, host: string, session: string | null): Promise<WaitView | null> {
    const view = await this.inspect(id, owner, host, session); if (!view) return null;
    const active = this.active.get(id); if (active) { active.abort.abort(); await this.finish(id, active, "cancelled", null); }
    this.results.delete(id); return this.inspect(id, owner, host, session);
  }
  /** Shutdown interrupts only observations; no work or mutation is cancelled or resent. */
  async close(): Promise<void> { await Promise.all([...this.active].map(([id, active]) => this.finish(id, active, "interrupted", null))); }
}

/* Durable JSON is an explicit file boundary, not an authority supplier. */
/* eslint-disable anti-slop/no-runtime-typeof */
function parseWaitJournal(text: string): WaitReceipt[] {
  const parsed = strictAgentJson(text, AGENT_LIMITS.retainedGlobal); if (!Array.isArray(parsed)) throw new Error("wait.journal");
  return parsed.map((value) => {
    const row = agentRecord(value);
    if (!row || !agentKeys(row, ["id", "owner", "host", "session", "paneId", "hash", "witness", "at", "deadline", "state", "code"]) || (row.code !== null && (typeof row.code !== "string" || agentErrorCode(row.code.slice("agent.".length)) === undefined)) || !agentRequestId(row.id) || !agentText(row.owner, 64) || !/^[a-f0-9]{64}$/.test(row.owner) || !agentId(row.host) || (row.session !== null && !agentText(row.session, 256)) || !agentId(row.paneId) || !agentText(row.hash, 64) || !/^[a-f0-9]{64}$/.test(row.hash) || !agentText(row.witness, 64) || !/^[a-f0-9]{64}$/.test(row.witness) || typeof row.at !== "number" || !Number.isSafeInteger(row.at) || row.at < 0 || typeof row.deadline !== "number" || !Number.isSafeInteger(row.deadline) || row.deadline <= row.at || row.deadline - row.at > AGENT_LIMITS.timeoutMs || (row.state !== "pending" && row.state !== "matched" && row.state !== "cancelled" && row.state !== "retired" && row.state !== "timed-out" && row.state !== "interrupted")) throw new Error("wait.journal");
    return { id: row.id, owner: row.owner, host: row.host, session: row.session, paneId: row.paneId, hash: row.hash, witness: row.witness, at: row.at, deadline: row.deadline, state: row.state, code: row.code === null ? null : agentErrorCode(row.code.slice("agent.".length)) ?? null };
  });
}
/* eslint-enable anti-slop/no-runtime-typeof */

export const WAIT_ACTION_ROUTE = /^\/api\/waits(?:\/([A-Za-z0-9_-]{1,128}))?$/;
export async function serveWaitRoute(request: Request, url: URL, caller: AgentCaller, waits: AgentWaitStore): Promise<Response | null> {
  const match = WAIT_ACTION_ROUTE.exec(url.pathname); if (!match) return null; const id = match[1];
  if ((!id && request.method !== "POST") || (id && request.method !== "GET" && request.method !== "DELETE")) return agentFailure("wait.method_not_allowed", 405, "Unsupported observation method.");
  const denied = caller.gate("device-read"); if (denied) return denied;
  const owner = caller.pairedIdentity(); if (!owner || !/^[a-f0-9]{64}$/.test(owner)) return agentFailure("device.identity_required", 403, "Observation custody requires its original paired device.");
  if (!controlScope(url, "session") || [...url.searchParams.keys()].some((key) => key !== "host" && key !== "session")) return agentFailure("scope.malformed", 400, "Use one explicit host and session scope.");
  if (id) {
    const runtime = await caller.resolve(); if (runtime instanceof Response) return runtime;
    try {
      const result = request.method === "DELETE" ? await waits.cancel(id, owner, runtime.host, runtime.session) : await waits.inspect(id, owner, runtime.host, runtime.session);
      if (caller.pairedIdentity() !== owner || caller.gate("device-read")) return agentFailure("scope.retired", 409, "The original observation owner changed.");
      return result ? waitResponse(result, caller.redact) : agentFailure("wait.not_found", 404, "No observation belongs to this owner and scope.");
    } catch { return agentFailure("wait.unavailable", 503, "The observation registry is unavailable."); }
  }
  const body = agentRecord(await readAgentBody(request));
  if (!body || !agentKeys(body, ["requestId", "paneId", "witness", "condition", "timeoutMs"]) || !agentRequestId(body.requestId) || !agentId(body.paneId) || !agentText(body.witness, 64) || !agentTimeout(body.timeoutMs)) return agentFailure("wait.invalid_body", 400, "Invalid bounded observation.");
  const condition = parseAgentWait(body.condition); if (!condition) return agentFailure("wait.invalid_body", 400, "Choose one closed observation condition.");
  const context = await captureAgentContext(caller, url, body.paneId, "device-read"); if (context instanceof Response) return context;
  if (context.runtime.mux.capabilities.supports.agentWaits !== true || !context.runtime.mux.agentWait) return agentFailure("capability.unsupported", 501, "This runtime does not support bounded waits.");
  if (context.owner !== owner || !equalAgentWitness(body.witness, context.witness)) return agentFailure("scope.retired", 409, "Review the current original occupant before waiting.");
  if (condition.kind === "event") {
    const event = condition.event; const target = event.startsWith("workspace-") ? context.original.workspaceId : event.startsWith("tab-") ? context.original.tabId : context.original.paneId;
    if ((condition.targetId !== null && condition.targetId !== target) || (condition.workspaceId !== null && condition.workspaceId !== context.original.workspaceId)) return agentFailure("scope.malformed", 400, "Event conditions must stay within the original occupant's scope.");
  }
  try {
    const originalCurrent = context.current;
    const admittedContext: AgentContext = { ...context, current: (identity) => originalCurrent(identity) && context.runtime.mux.capabilities.supports.agentWaits === true };
    const hash = createHmac("sha256", context.key).update(canonicalPayload(body)).digest("hex");
    const wait = await waits.start(body.requestId, hash, admittedContext, condition, body.timeoutMs);
    return waitResponse(wait, caller.redact, wait.state === "pending" ? 202 : 200);
  } catch (error) { return agentFailure(error instanceof Error && error.message === "wait.request_id_reused" ? "wait.request_id_reused" : "wait.unavailable", 409, "Reconcile the original observation id; no observation was repeated."); }
}

function waitResponse(wait: WaitView, redact: boolean, status = 200): Response {
  const raw = JSON.stringify({ ok: true, wait });
  if (!redact) return new Response(raw, { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
  // SAFETY: only our bounded in-memory DTO is serialized; the shared closed mask validates it.
  const masked = maskAgentWaitAnswer(JSON.parse(raw) as import("../json.ts").JsonObject);
  return masked === null ? agentFailure("wait.invalid_result", 503, "No bounded current observation result is available.") : new Response(masked, { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
}
