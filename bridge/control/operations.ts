import { mkdir, open, rename, unlink } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { HOST } from "../host.ts";
import { CONTROL_LIMITS } from "./contracts.ts";
import { type PlanBinding, sameBinding } from "./plans.ts";

/** Per pending row: worst-case 256-code-point UTF-8 code plus closed 4×128-byte move aliases,
 * discriminant/key overhead and state growth stays below 2 KiB. Held once per pending row so
 * concurrent completions cannot consume one another's terminal-result space. */
export const OPERATION_COMPLETION_HEADROOM_BYTES = 4096;
/** UTF-16 limits may require six JSON bytes per code unit (escaped branch controls). Include all
 * keys, aliases, state/timestamps and bounded code; held separately for each concurrent pending row. */
export const WORKTREE_REMOVE_COMPLETION_HEADROOM_BYTES = 32 * 1024;
function completionHeadroom(capability: string): number {
  return capability === "worktreeRemove" ? WORKTREE_REMOVE_COMPLETION_HEADROOM_BYTES : OPERATION_COMPLETION_HEADROOM_BYTES;
}

export type OperationState = "pending" | "succeeded" | "refused" | "unknown";
export interface OperationReceipt {
  readonly requestId: string;
  readonly binding: PlanBinding;
  readonly planToken: string | null;
  readonly state: OperationState;
  readonly at: number;
  readonly updatedAt: number;
  readonly code: string | null;
  readonly result?: DurableOperationResult;
}
/** Persisted structured result is limited to the identity aliases needed after pane.move. */
export interface OperationResult {
  readonly kind: "pane-move" | "portable-layout" | "split-ratio";
  readonly paneId?: string;
  readonly previousPaneId?: string;
  readonly previousTabId?: string;
  readonly previousWorkspaceId?: string;
  readonly workspaceId?: string;
  readonly tabId?: string;
  readonly paneIds?: readonly string[];
  readonly focusedPaneId?: string;
  readonly transfer?: {
    readonly schema: 1;
    readonly changed: boolean;
    readonly targetTabId: string;
    readonly targetWorkspaceId: string;
    readonly processWitness: string;
    readonly createdTabId: string | null;
    readonly createdWorkspaceId: string | null;
    readonly closedTabId: string | null;
    readonly closedWorkspaceId: string | null;
  };
}
export interface WorktreeRemoveOperationResult {
  readonly kind: "worktree-remove";
  readonly workspaceId: string;
  readonly path: string;
  readonly branch: string | null;
  readonly forced: boolean;
}
export type DurableOperationResult = OperationResult | WorktreeRemoveOperationResult;
export interface OperationOutcome { readonly state: "succeeded" | "refused" | "unknown"; readonly code?: string; readonly result?: DurableOperationResult }
export interface OperationView {
  readonly schema: 1;
  readonly requestId: string;
  readonly state: OperationState;
  readonly capability: string;
  readonly targetIdentity: string;
  readonly at: number;
  readonly updatedAt: number;
  readonly code: string | null;
  readonly result?: DurableOperationResult;
}
export type ExistingOperation = { readonly kind: "missing" } | { readonly kind: "conflict" } | { readonly kind: "match"; readonly operation: OperationView };
type BeforeOperationPersist = () => Promise<void>;
// These three parsers validate untrusted on-disk JSON and caller bindings before reservation.
/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */
function bounded(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= CONTROL_LIMITS.identityChars && !Array.from(value).some((char) => char.charCodeAt(0) < 32);
}
function validOperationResultId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_.:-]{1,128}$/.test(value);
}
export function validBinding(value: unknown): value is PlanBinding {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  // SAFETY: non-null object checked above; each selected field is validated below.
  const b = value as Record<string, unknown>;
  if (Object.keys(b).length !== 7 || !Object.keys(b).every((key) => ["device", "host", "session", "capability", "payloadHash", "targetIdentity", "evidence"].includes(key))) return false;
  return bounded(b.device) && bounded(b.host) && (b.session === null || bounded(b.session)) && bounded(b.capability) && typeof b.payloadHash === "string" && /^[a-f0-9]{64}$/.test(b.payloadHash) && bounded(b.targetIdentity) && bounded(b.evidence);
}
function validReceipt(value: unknown): value is OperationReceipt {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  // SAFETY: non-null object checked above; no field is used without its exact validator below.
  const r = value as Record<string, unknown>;
  if ((Object.keys(r).length !== 7 && Object.keys(r).length !== 8) || !Object.keys(r).every((key) => ["requestId", "binding", "planToken", "state", "at", "updatedAt", "code", "result"].includes(key))) return false;
  return typeof r.requestId === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(r.requestId) && validBinding(r.binding) && (r.planToken === null || bounded(r.planToken)) && typeof r.state === "string" && ["pending", "succeeded", "refused", "unknown"].includes(r.state) && typeof r.at === "number" && Number.isSafeInteger(r.at) && r.at >= 0 && typeof r.updatedAt === "number" && Number.isSafeInteger(r.updatedAt) && r.updatedAt >= r.at && (r.code === null || bounded(r.code)) && (r.result === undefined || (r.state === "succeeded" && validOperationResult(r.result)));
}
function validOperationResult(value: unknown): value is DurableOperationResult {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  // SAFETY: null and arrays are excluded; the key set and every value are checked before the guard returns true.
  const r = value as Record<string, unknown>;
  if (r.kind === "worktree-remove") {
    return Object.keys(r).length === 5 && Object.keys(r).every((key) => ["kind", "workspaceId", "path", "branch", "forced"].includes(key)) &&
      typeof r.workspaceId === "string" && /^[A-Za-z0-9_.:-]{1,128}$/.test(r.workspaceId) &&
      typeof r.path === "string" && r.path.length > 0 && r.path.length <= 4096 && !Array.from(r.path).some((char) => char.charCodeAt(0) < 32) &&
      (r.branch === null || typeof r.branch === "string" && r.branch.length > 0 && r.branch.length <= 256) && typeof r.forced === "boolean";
  }

  if (r.kind === "portable-layout") return (Object.keys(r).length === 5 && Object.keys(r).every((key) => ["kind", "workspaceId", "tabId", "paneIds", "focusedPaneId"].includes(key))) &&
    validOperationResultId(r.workspaceId) && validOperationResultId(r.tabId) && validOperationResultId(r.focusedPaneId) && Array.isArray(r.paneIds) && r.paneIds.length >= 1 && r.paneIds.length <= 64 &&
    r.paneIds.every(validOperationResultId) && new Set(r.paneIds).size === r.paneIds.length && r.paneIds.includes(r.focusedPaneId);
  if (r.kind === "split-ratio") return Object.keys(r).length === 2 && Object.keys(r).every((key) => ["kind", "tabId"].includes(key)) && validOperationResultId(r.tabId);
  if (!(Object.keys(r).length === 5 || Object.keys(r).length === 6) || !Object.keys(r).every((key) => ["kind", "paneId", "previousPaneId", "previousTabId", "previousWorkspaceId", "transfer"].includes(key)) ||
      r.kind !== "pane-move" || !validOperationResultId(r.paneId) || !validOperationResultId(r.previousPaneId) || !validOperationResultId(r.previousTabId) || !validOperationResultId(r.previousWorkspaceId)) return false;
  if (r.transfer === undefined) return true;
  if (r.transfer === null || typeof r.transfer !== "object" || Array.isArray(r.transfer)) return false;
  // SAFETY: null and arrays were rejected; exact closed result fields and their values are checked below.
  const t = r.transfer as Record<string, unknown>;
  return Object.keys(t).length === 9 && Object.keys(t).every((key) => ["schema", "changed", "targetTabId", "targetWorkspaceId", "processWitness", "createdTabId", "createdWorkspaceId", "closedTabId", "closedWorkspaceId"].includes(key)) &&
    t.schema === 1 && typeof t.changed === "boolean" && validOperationResultId(t.targetTabId) && validOperationResultId(t.targetWorkspaceId) && typeof t.processWitness === "string" && /^[a-f0-9]{64}$/.test(t.processWitness) &&
    [t.createdTabId, t.createdWorkspaceId, t.closedTabId, t.closedWorkspaceId].every((id) => id === null || validOperationResultId(id));
}
/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-runtime-typeof, anti-slop/no-unsafe-dictionary-type */
/**
 * Reserve durably BEFORE dispatch, complete durably AFTER it. A crash/disconnect leaves unknown;
 * the same intent is never sent again. This does not promise exactly-once upstream effects.
 * One instance per owning host, shared by all handlers. No terminal text is persisted.
 */
export class OperationStore {
  private receipts = new Map<string, OperationReceipt>();
  private loaded = false;
  private storageReady = false;
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private readonly stateDir?: string, private readonly now: () => number = Date.now, private readonly beforePersist?: BeforeOperationPersist) {}
  /** Validate and warm the owning host's durable registry before advertising receipt-bound writes. */
  async prepare(): Promise<boolean> {
    try {
      await this.serial(async () => this.load());
      return this.storageReady;
    } catch {
      this.storageReady = false;
      return false;
    }
  }
  isReady(): boolean { return this.storageReady; }
  /** New mutation APIs require the owning host durable registry. */
  get isDurable(): boolean { return this.stateDir !== undefined; }
  private serial<T>(action: () => Promise<T>): Promise<T> {
    const next = this.tail.then(action);
    this.tail = next.catch(() => {});
    return next;
  }
  private async load(): Promise<void> {
    if (this.loaded) return;
    if (this.stateDir) {
      let text: string;
      try {
        const handle = await open(join(this.stateDir, "control-operations.json"), "r");
        try {
          const maximum = 4 * 1024 * 1024;
          const bytes = Buffer.alloc(maximum + 1);
          let size = 0;
          while (size < bytes.length) {
            const read = await handle.read(bytes, size, bytes.length - size, null);
            if (read.bytesRead === 0) break;
            size += read.bytesRead;
          }
          if (size > maximum) throw new Error("operation registry too large");
          text = bytes.subarray(0, size).toString("utf8");
        } finally { await handle.close(); }
      }
      catch (error) {
        if (!(error instanceof Error) || !("code" in error) || error.code !== "ENOENT") throw error;
        this.loaded = true;
        this.storageReady = true;
        return;
      }
      if (text.length > 4 * 1024 * 1024) throw new Error("operation registry too large");
      const rows: unknown = JSON.parse(text);
      if (!Array.isArray(rows) || rows.length > CONTROL_LIMITS.receiptCount || !rows.every(validReceipt)) throw new Error("operation registry unreadable");
      const restored = new Map<string, OperationReceipt>();
      for (const row of rows) {
        if (restored.has(row.requestId)) throw new Error("duplicate operation registry entry");
        restored.set(row.requestId, row.state === "pending" ? { ...row, state: "unknown", code: "operation.interrupted" } : row);
      }
      this.receipts = restored;
    }
    this.loaded = true;
    this.storageReady = true;
  }
  private async persist(): Promise<void> {
    if (!this.stateDir) return;
    await this.beforePersist?.();
    await mkdir(this.stateDir, { recursive: true, mode: 0o700 });
    const serialized = JSON.stringify([...this.receipts.values()]);
    if (Buffer.byteLength(serialized, "utf8") > 4 * 1024 * 1024) throw new Error("operation.capacity");
    const file = join(this.stateDir, "control-operations.json");
    const temporary = `${file}.${randomUUID()}.tmp`;
    let committed = false;
    try {
      const handle = await open(temporary, "wx", 0o600);
      try {
        await handle.writeFile(serialized);
        await handle.sync();
      } finally { await handle.close(); }
      await rename(temporary, file);
      committed = true;
    } finally {
      if (!committed) await unlink(temporary).catch(() => {});
    }
    // POSIX persists the directory entry too. Windows does not expose directory fsync.
    if (HOST.platform !== "win32") {
      const directory = await open(this.stateDir, "r");
      try { await directory.sync(); } finally { await directory.close(); }
    }
    this.storageReady = true;
  }
  async inspect(requestId: string, device: string, host: string, session: string | null): Promise<OperationView | null> {
    return this.serial(async () => {
      await this.load();
      const r = this.receipts.get(requestId);
      if (!r || r.binding.device !== device || r.binding.host !== host || r.binding.session !== session) return null;
      return operationView(r);
    });
  }
  /** Owner-bound same-request reconciliation, including the exact canonical action body and plan. */
  async inspectIntent(requestId: string, device: string, host: string, session: string | null, capability: string, payloadHash: string, planToken: string): Promise<ExistingOperation> {
    return this.serial(async () => {
      await this.load();
      const row = this.receipts.get(requestId);
      if (!row || row.binding.device !== device || row.binding.host !== host || row.binding.session !== session) return { kind: "missing" };
      if (row.binding.capability !== capability || row.binding.payloadHash !== payloadHash || row.planToken !== planToken) return { kind: "conflict" };
      return { kind: "match", operation: operationView(row) };
    });
  }
  /** A named action's final original proof can be lost while its completion is persisted.
   * This only downgrades that exact immutable binding; it never dispatches or samples authority. */
  async retainOriginalAgentUnknown(requestId: string, binding: PlanBinding, planToken: string): Promise<OperationView | null> {
    if (binding.capability !== "agentAutomation" || !validBinding(binding) || !bounded(planToken)) throw new Error("invalid original agent binding");
    return this.serial(async () => {
      await this.load(); const row = this.receipts.get(requestId);
      if (!row || !sameBinding(row.binding, binding) || row.planToken !== planToken) return null;
      if (row.state !== "succeeded") return operationView(row);
      const { result: _discarded, ...original } = row;
      const at = this.now();
      const unknown: OperationReceipt = { ...original, state: "unknown", code: "agent.outcome_unknown", updatedAt: Number.isSafeInteger(at) ? Math.max(at, row.updatedAt) : row.updatedAt };
      this.receipts.set(requestId, unknown);
      try { await this.persist(); } catch { this.storageReady = false; throw new Error("operation.persistence_failed"); }
      return operationView(unknown);
    });
  }
  async execute(requestId: string, binding: PlanBinding, planToken: string | null, dispatch: () => Promise<OperationOutcome>): Promise<OperationView> {
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(requestId) || !validBinding(binding) || (planToken !== null && !bounded(planToken))) throw new Error("invalid operation binding");
    const reservation = await this.serial(async () => {
      await this.load();
      const previous = this.receipts.get(requestId);
      if (previous) {
        if (!sameBinding(previous.binding, binding) || previous.planToken !== planToken) throw new Error("operation.request_id_reused");
        return { fresh: false, receipt: previous };
      }
      if (this.receipts.size >= CONTROL_LIMITS.receiptCount) throw new Error("operation.capacity");
      const at = this.now();
      if (!Number.isSafeInteger(at) || at < 0) throw new Error("operation.invalid_clock");
      const receipt: OperationReceipt = { requestId, binding: { ...binding }, planToken, state: "pending", at, updatedAt: at, code: null };
      // Keep every file we create within the loader's byte bound, with completion headroom.
      if (Buffer.byteLength(JSON.stringify([...this.receipts.values(), receipt]), "utf8") + completionHeadroom(binding.capability) + [...this.receipts.values()].filter((row) => row.state === "pending").reduce((sum, row) => sum + completionHeadroom(row.binding.capability), 0) > 4 * 1024 * 1024) throw new Error("operation.capacity");
      this.receipts.set(requestId, receipt);
      // A failed reserve must not dispatch. Leave the process-local entry unknown as well.
      try { await this.persist(); } catch (error) {
        this.storageReady = false;
        this.receipts.set(requestId, { ...receipt, state: "unknown", code: "operation.persistence_failed" });
        throw error;
      }
      return { fresh: true, receipt };
    });
    if (!reservation.fresh) return operationView(reservation.receipt);
    let outcome: OperationOutcome;
    try { outcome = await dispatch(); } catch { outcome = { state: "unknown", code: "operation.outcome_unknown" }; }
    if (!["succeeded", "refused", "unknown"].includes(outcome.state) || (outcome.code !== undefined && !bounded(outcome.code)) || (outcome.result !== undefined && (outcome.state !== "succeeded" || !validOperationResult(outcome.result))) || (["paneMove", "worktreeRemove"].includes(reservation.receipt.binding.capability) && outcome.state === "succeeded" && (outcome.result === undefined || !validOperationResult(outcome.result)))) outcome = { state: "unknown", code: "operation.invalid_outcome" };
    return this.serial(async () => {
      const observedAt = this.now();
      const updatedAt = Number.isSafeInteger(observedAt) ? Math.max(reservation.receipt.at, observedAt) : reservation.receipt.at;
      const base = { ...reservation.receipt, state: outcome.state, code: outcome.code ?? null, updatedAt };
      const receipt: OperationReceipt = outcome.result === undefined ? base : { ...base, result: outcome.result };
      this.receipts.set(requestId, receipt);
      try { await this.persist(); } catch {
        this.storageReady = false;
        // A terminal move may have happened, but its aliases are not durable. Do not expose them
        // on UNKNOWN or let a later unrelated write persist an invalid unknown-with-result row.
        const { result: _discarded, ...withoutResult } = receipt;
        const unknown: OperationReceipt = { ...withoutResult, state: "unknown", code: "operation.persistence_failed" };
        this.receipts.set(requestId, unknown);
        return operationView(unknown);
      }
      return operationView(receipt);
    });
  }
}
function operationView(r: OperationReceipt): OperationView {
  const view = { schema: 1 as const, requestId: r.requestId, state: r.state, capability: r.binding.capability, targetIdentity: r.binding.targetIdentity, at: r.at, updatedAt: r.updatedAt, code: r.code };
  return r.result === undefined ? view : { ...view, result: r.result };
}
