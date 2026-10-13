import { ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION } from "./agent-models.ts";
import type { MuxAdapter } from "../mux/types.ts";
import { refusal, refusalResponse, controlScope, payloadHash } from "./contracts.ts";
import type { GateLevel } from "../crew/peer-gate.ts";
import type { OperationStore, OperationView } from "./operations.ts";
import type { PlanBinding } from "./plans.ts";
import type { JsonValue } from "../json.ts";

/* eslint-disable anti-slop/no-runtime-typeof, anti-slop/no-unknown-parameters, anti-slop/no-unsafe-dictionary-type, anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-chained-type-assertions
 * SAFETY: validators in this module are the explicit JSON boundary for proposed HTTP and Herdr
 * data. Each representation is closed and bounded before it is serialized, stored or dispatched.
 */

export const NATIVE_METADATA_SOURCE = "collie.native";
export const METADATA_MAX_TTL_MS = 86_400_000;
export const METADATA_MAX_TOKENS = 16;
export const METADATA_MAX_SEQUENCE_SOURCES = 32;
export const AGENT_VIEW_MAX_DEPTH = 8;
export const AGENT_VIEW_MAX_NODES = 128;

export type AgentViewField = string | { readonly token: string };
export type AgentViewValue = string | boolean | number | { readonly context: "current_workspace_id" | "current_tab_id" };
export type AgentViewFilter =
  | { readonly op: "all" | "any"; readonly filters: readonly AgentViewFilter[] }
  | { readonly op: "not"; readonly filter: AgentViewFilter }
  | { readonly op: "eq"; readonly field: AgentViewField; readonly value: AgentViewValue }
  | { readonly op: "in"; readonly field: AgentViewField; readonly values: readonly AgentViewValue[] }
  | { readonly op: "exists"; readonly field: AgentViewField };
export interface AgentViewSort { readonly field: AgentViewField; readonly order?: "asc" | "desc" }
export interface PaneMetadataRequest {
  readonly source: string;
  readonly seq?: number | null;
  readonly ttl_ms?: number | null;
  readonly agent?: string | null;
  readonly applies_to_source?: string | null;
  readonly tokens?: Readonly<Record<string, string | null>>;
  readonly display_agent?: string | null;
  readonly title?: string | null;
  readonly state_labels?: Readonly<Record<string, string>>;
  readonly clear_display_agent?: boolean;
  readonly clear_title?: boolean;
  readonly clear_state_labels?: boolean;
}
export interface WorkspaceMetadataRequest {
  readonly source: string;
  readonly seq?: number | null;
  readonly ttl_ms?: number | null;
  readonly tokens: Readonly<Record<string, string | null>>;
}
export interface AgentViewRequest {
  readonly source: string;
  readonly label?: string | null;
  readonly filter?: AgentViewFilter | null;
  readonly sort?: readonly AgentViewSort[];
}

export type MetadataValidation = { readonly ok: true } | { readonly ok: false; readonly code: string };
const hasControlChars = (value: string): boolean => Array.from(value).some((char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127);
const validId = (value: string): boolean => value.length > 0 && value.length <= 256 && !hasControlChars(value);
const validTargetKey = (value: string): boolean => value.length > 0 && value.length <= 1024 &&
  !Array.from(value).some((char) => char.charCodeAt(0) < 32 && char !== "\0" || char.charCodeAt(0) === 127);
const validMetadataText = (value: string): boolean => value.length <= 80 && !hasControlChars(value);
const tokenName = /^[A-Za-z0-9_-]{1,32}$/u;
const stateName = /^[A-Za-z][A-Za-z0-9_-]{0,31}$/u;
const stateNames = new Set(["idle", "working", "blocked", "done", "unknown"]);
const builtinFields = new Set(["status", "workspace_id", "tab_id", "pane_id", "agent", "seen", "state_change_seq"]);
const builtinSortFields = new Set(["workspace_order", "tab_order", "pane_order", "attention", ...builtinFields]);
const safeValue = (value: unknown): value is AgentViewValue => typeof value === "string" && value.length <= 256 && !hasControlChars(value) ||
  typeof value === "boolean" || typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ||
  value !== null && typeof value === "object" && !Array.isArray(value) && Object.keys(value).length === 1 &&
    ((value as Record<string, unknown>).context === "current_workspace_id" || (value as Record<string, unknown>).context === "current_tab_id");
function contextMatchesField(fieldValue: unknown, operand: unknown): boolean {
  if (!operand || typeof operand !== "object" || Array.isArray(operand) || !("context" in operand)) return true;
  const context = (operand as { context?: unknown }).context;
  return fieldValue === (context === "current_workspace_id" ? "workspace_id" : "tab_id");
}

function validateTokens(tokens: unknown): boolean {
  if (tokens === null || typeof tokens !== "object" || Array.isArray(tokens)) return false;
  const entries = Object.entries(tokens);
  return entries.length <= METADATA_MAX_TOKENS && entries.every(([key, value]) => tokenName.test(key) &&
    (value === null || typeof value === "string" && validMetadataText(value)));
}
function validateSource(source: unknown): boolean {
  return typeof source === "string" && validId(source) && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$/u.test(source);
}
function validateCommon(source: unknown, seq: unknown, ttl: unknown): string | null {
  if (!validateSource(source)) return "invalid_metadata_source";
  if (seq !== undefined && seq !== null && (!Number.isSafeInteger(seq) || (seq as number) < 0)) return "invalid_metadata_request";
  if (ttl !== undefined && ttl !== null && (!Number.isSafeInteger(ttl) || (ttl as number) < 1 || (ttl as number) > METADATA_MAX_TTL_MS)) return "invalid_metadata_ttl";
  return null;
}
export function validatePaneMetadata(value: PaneMetadataRequest): MetadataValidation {
  const common = validateCommon(value.source, value.seq, value.ttl_ms);
  if (common) return { ok: false, code: common };
  if (value.source !== NATIVE_METADATA_SOURCE && !value.source.startsWith(`${NATIVE_METADATA_SOURCE}.`) && value.source.startsWith("collie.")) return { ok: false, code: "invalid_metadata_source" };
  if (value.agent !== undefined && value.agent !== null && !validId(value.agent) || value.applies_to_source !== undefined && value.applies_to_source !== null && !validateSource(value.applies_to_source)) return { ok: false, code: "invalid_metadata_request" };
  if (value.tokens !== undefined && !validateTokens(value.tokens)) return { ok: false, code: "invalid_metadata_token" };
  for (const text of [value.display_agent, value.title]) if (text !== undefined && text !== null && !validMetadataText(text)) return { ok: false, code: "invalid_metadata_request" };
  if (value.state_labels !== undefined && (!value.state_labels || Object.entries(value.state_labels).length > 5 || Object.entries(value.state_labels).some(([key, label]) => !stateName.test(key) || !stateNames.has(key) || typeof label !== "string" || !validMetadataText(label)))) return { ok: false, code: "invalid_state_label" };
  if ([value.clear_display_agent, value.clear_title, value.clear_state_labels].some((flag) => flag !== undefined && typeof flag !== "boolean")) return { ok: false, code: "invalid_metadata_request" };
  return { ok: true };
}
export function validateWorkspaceMetadata(value: WorkspaceMetadataRequest): MetadataValidation {
  const common = validateCommon(value.source, value.seq, value.ttl_ms);
  if (common) return { ok: false, code: common };
  if (value.source !== NATIVE_METADATA_SOURCE && !value.source.startsWith(`${NATIVE_METADATA_SOURCE}.`) && value.source.startsWith("collie.")) return { ok: false, code: "invalid_metadata_source" };
  return validateTokens(value.tokens) ? { ok: true } : { ok: false, code: "invalid_metadata_token" };
}
function field(value: unknown): value is AgentViewField {
  if (typeof value === "string") return builtinFields.has(value);
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  return Object.keys(row).length === 1 && typeof row.token === "string" && tokenName.test(row.token);
}
function sortField(value: unknown): boolean {
  if (typeof value === "string") return builtinSortFields.has(value);
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  return Object.keys(row).length === 1 && typeof row.token === "string" && tokenName.test(row.token);
}
export function validateAgentViewFilter(value: unknown): MetadataValidation {
  let nodes = 0;
  const walk = (node: unknown, depth: number): boolean => {
    if (++nodes > AGENT_VIEW_MAX_NODES || depth > AGENT_VIEW_MAX_DEPTH || !node || typeof node !== "object" || Array.isArray(node)) return false;
    const row = node as Record<string, unknown>;
    switch (row.op) {
      case "all": case "any": return Object.keys(row).length === 2 && Array.isArray(row.filters) && row.filters.length <= 32 && row.filters.every((child) => walk(child, depth + 1));
      case "not": return Object.keys(row).length === 2 && walk(row.filter, depth + 1);
      case "eq": return Object.keys(row).length === 3 && field(row.field) && safeValue(row.value) && contextMatchesField(row.field, row.value);
      case "in": return Object.keys(row).length === 3 && field(row.field) && Array.isArray(row.values) && row.values.length > 0 && row.values.length <= 32 && row.values.every((item) => safeValue(item) && contextMatchesField(row.field, item));
      case "exists": return Object.keys(row).length === 2 && field(row.field);
      default: return false;
    }
  };
  return walk(value, 0) ? { ok: true } : { ok: false, code: "invalid_agent_view" };
}
export function validateAgentView(value: AgentViewRequest): MetadataValidation {
  if (!validateSource(value.source) || value.source.startsWith("collie.") && value.source !== NATIVE_METADATA_SOURCE && !value.source.startsWith(`${NATIVE_METADATA_SOURCE}.`)) return { ok: false, code: "invalid_agent_view" };
  if (value.label !== undefined && value.label !== null && !validId(value.label)) return { ok: false, code: "invalid_agent_view" };
  if (value.filter !== undefined && value.filter !== null && !validateAgentViewFilter(value.filter).ok) return { ok: false, code: "invalid_agent_view" };
  if (value.sort !== undefined && (!Array.isArray(value.sort) || value.sort.length > 8 || value.sort.some((row) => !row || !sortField(row.field) || row.order !== undefined && row.order !== "asc" && row.order !== "desc"))) return { ok: false, code: "invalid_agent_view" };
  return { ok: true };
}

/** Drop unsafe metadata keys before including display data in any native response. */
export function redactMetadataTokens(tokens: Readonly<Record<string, string | null>>): Readonly<Record<string, string | null>> {
  return Object.fromEntries(Object.entries(tokens).filter(([key]) => tokenName.test(key)).slice(0, METADATA_MAX_TOKENS));
}

export interface MetadataContribution {
  readonly source: string;
  readonly seq: number | null;
  readonly expiresAt: number | null;
  readonly tokenExpiresAt: Readonly<Record<string, number | null>>;
  readonly value: Readonly<StoredMetadataValue>;
}
export interface StoredMetadataValue {
  readonly agent?: string | null;
  readonly seq?: number | null;
  readonly ttl_ms?: number | null;
  readonly applies_to_source?: string | null;
  readonly tokens?: Readonly<Record<string, string | null>>;
  readonly display_agent?: string | null;
  readonly title?: string | null;
  readonly state_labels?: Readonly<Record<string, string>>;
  readonly clear_display_agent?: boolean;
  readonly clear_title?: boolean;
  readonly clear_state_labels?: boolean;
}
type MutableStoredMetadataValue = { -readonly [Key in keyof StoredMetadataValue]?: StoredMetadataValue[Key] };
const presentationMetadataKeys = ["agent", "applies_to_source", "display_agent", "title", "state_labels", "clear_display_agent", "clear_title", "clear_state_labels"] as const;

/** Bounded bridge-side receipt/cache for the report-only protocol; there is no vendor getter RPC. */
export class MetadataStateStore {
  private readonly targets = new Map<string, Map<string, MetadataContribution>>();
  private readonly sequenceSources = new Map<string, Set<string>>();
  private readonly sequenceHighWater = new Map<string, Map<string, number>>();

  apply(target: string, source: string, seq: number | null, ttlMs: number | null, value: Readonly<StoredMetadataValue>, now = Date.now()): boolean {
    this.expire(now);
    if (!validTargetKey(target) || !validateSource(source) || seq !== null && (!Number.isSafeInteger(seq) || seq < 0) ||
      ttlMs !== null && (!Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > METADATA_MAX_TTL_MS)) return false;
    let rows = this.targets.get(target);
    if (!rows) { rows = new Map(); this.targets.set(target, rows); }
    const prior = rows.get(source);
    const highWater = this.sequenceHighWater.get(target)?.get(source);
    if (highWater !== undefined && seq !== null && seq <= highWater) return false;
    if (!prior && rows.size >= METADATA_MAX_SEQUENCE_SOURCES) return false;
    const sequenceSources = this.sequenceSources.get(target) ?? new Set<string>();
    if (seq !== null && !sequenceSources.has(source) && sequenceSources.size >= METADATA_MAX_SEQUENCE_SOURCES) return false;
    const priorTokens = prior?.value.tokens ?? {};
    const tokenDeadlines = new Map(Object.entries(prior?.tokenExpiresAt ?? {}));
    const incomingTokens = value.tokens ?? {};
    const tokens: Record<string, string> = Object.fromEntries(Object.entries(priorTokens).filter((entry): entry is [string, string] => typeof entry[1] === "string"));
    for (const [name, text] of Object.entries(incomingTokens)) {
      if (text === null) { delete tokens[name]; tokenDeadlines.delete(name); }
      else if (typeof text === "string") { tokens[name] = text; tokenDeadlines.set(name, ttlMs === null ? null : now + ttlMs); }
    }
    const merged = { ...prior?.value, ...value, tokens };
    if (value.clear_title === true) delete merged.title;
    if (value.clear_display_agent === true) delete merged.display_agent;
    if (value.clear_state_labels === true) delete merged.state_labels;
    delete merged.ttl_ms;
    delete merged.clear_title;
    delete merged.clear_display_agent;
    delete merged.clear_state_labels;
    const allTokenNames = new Set<string>();
    for (const row of rows.values()) {
      const sourceTokens = row.source === source ? tokens : row.value.tokens && typeof row.value.tokens === "object" && !Array.isArray(row.value.tokens)
        ? row.value.tokens as Record<string, string | null> : {};
      for (const [name, text] of Object.entries(sourceTokens)) if (typeof text === "string") allTokenNames.add(name);
    }
    for (const [name, text] of Object.entries(tokens)) if (typeof text === "string") allTokenNames.add(name);
    if (allTokenNames.size > 32) return false;
    const updatesPresentation = presentationMetadataKeys.some((key) => Object.hasOwn(value, key));
    const expiresAt = updatesPresentation ? ttlMs === null ? null : now + ttlMs : prior?.expiresAt ?? null;
    const hasPresentation = ["agent", "applies_to_source", "display_agent", "title", "state_labels"].some((key) => Object.hasOwn(merged, key));
    if (!hasPresentation && Object.keys(tokens).length === 0) rows.delete(source);
    else rows.set(source, { source, seq: seq ?? prior?.seq ?? null, expiresAt, tokenExpiresAt: Object.fromEntries(tokenDeadlines), value: merged });
    if (seq !== null) {
      sequenceSources.add(source); this.sequenceSources.set(target, sequenceSources);
      const highWaterRows = this.sequenceHighWater.get(target) ?? new Map<string, number>();
      highWaterRows.set(source, seq); this.sequenceHighWater.set(target, highWaterRows);
    }
    return true;
  }

  canApply(target: string, source: string, seq: number | null, value?: Readonly<StoredMetadataValue>, now = Date.now()): boolean {
    this.expire(now);
    const rows = this.targets.get(target);
    const prior = rows?.get(source);
    const highWater = this.sequenceHighWater.get(target)?.get(source);
    if (highWater !== undefined && seq !== null && seq <= highWater) return false;
    if (!prior && (rows?.size ?? 0) >= METADATA_MAX_SEQUENCE_SOURCES) return false;
    const sequenceSources = this.sequenceSources.get(target);
    if (seq !== null && !sequenceSources?.has(source) && (sequenceSources?.size ?? 0) >= METADATA_MAX_SEQUENCE_SOURCES) return false;
    const incoming = value?.tokens ?? {};
    const previous = prior?.value.tokens ?? {};
    const names = new Set<string>();
    for (const row of rows?.values() ?? []) {
      const tokens = row.source === source ? { ...previous, ...incoming } : row.value.tokens ?? {};
      for (const [name, text] of Object.entries(tokens)) if (typeof text === "string") names.add(name);
    }
    if (!prior) for (const [name, text] of Object.entries(incoming)) if (typeof text === "string") names.add(name);
    return names.size <= 32;
  }

  clear(target: string, source?: string, now = Date.now()): void {
    this.expire(now);
    const rows = this.targets.get(target);
    if (!rows) return;
    if (source === undefined) { this.targets.delete(target); this.sequenceSources.delete(target); this.sequenceHighWater.delete(target); }
    else { rows.delete(source); if (rows.size === 0) this.targets.delete(target); }
  }

  /** Drop all cached incarnations for a closed pane/workspace target. */
  clearTarget(targetPrefix: string, keepTarget?: string): void {
    for (const target of this.targets.keys()) {
      if ((target === targetPrefix || target.startsWith(`${targetPrefix}\0`)) && target !== keepTarget) {
        this.targets.delete(target); this.sequenceSources.delete(target); this.sequenceHighWater.delete(target);
      }
    }
  }

  /** Retire closed or replaced pane/workspace generations whenever the owning session snapshot advances. */
  retainSessionTargets(host: string, session: string, activeKeys: ReadonlySet<string>): void {
    const prefix = `${host}\0${session}\0`;
    for (const target of this.targets.keys()) {
      if (target.startsWith(prefix) && !activeKeys.has(target)) {
        this.targets.delete(target); this.sequenceSources.delete(target); this.sequenceHighWater.delete(target);
      }
    }
  }

  read(target: string, now = Date.now()): readonly MetadataContribution[] {
    this.expire(now);
    return [...(this.targets.get(target)?.values() ?? [])];
  }

  private expire(now: number): void {
    for (const [target, rows] of this.targets) {
      for (const [source, row] of rows) {
        const tokens = { ...row.value.tokens };
        const tokenExpiresAt = { ...row.tokenExpiresAt };
        for (const [name, deadline] of Object.entries(tokenExpiresAt)) if (deadline !== null && deadline <= now) { delete tokens[name]; delete tokenExpiresAt[name]; }
        const value: MutableStoredMetadataValue = { ...row.value, tokens };
        let expiresAt = row.expiresAt;
        if (expiresAt !== null && expiresAt <= now) {
          for (const key of presentationMetadataKeys) delete value[key];
          expiresAt = null;
        }
        const hasPresentation = presentationMetadataKeys.slice(0, 5).some((key) => Object.hasOwn(value, key));
        if (!hasPresentation && Object.keys(tokens).length === 0) rows.delete(source);
        else if (expiresAt !== row.expiresAt || Object.keys(tokens).length !== Object.keys(row.value.tokens ?? {}).length) rows.set(source, { ...row, expiresAt, tokenExpiresAt, value });
      }
      if (rows.size === 0) this.targets.delete(target);
    }
  }
}

export interface MetadataRouteCaller {
  gate(level: GateLevel): Response | null;
  pairedIdentity(): string | null;
  audit(action: string, target: string, host: string, session: string | null, outcome: string): void;
  resolve(): Promise<{ readonly host: string; readonly session: string | null; readonly mux: MuxAdapter; readonly currentRuntime?: () => boolean } | Response>;
}
const paneMetadataPath = /^\/api\/pane\/([^/]{1,128})\/metadata$/u;
const workspaceMetadataPath = /^\/api\/workspace\/([^/]{1,128})\/metadata$/u;
/* eslint-disable anti-slop/no-runtime-typeof, anti-slop/no-unknown-parameters */
function requestObject(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function safeJson(value: unknown): JsonValue {
  // SAFETY: callers pass the closed request shapes after validation; JSON cloning strips prototypes.
  return JSON.parse(JSON.stringify(value)) as JsonValue;
}
async function readMetadataBody(req: Request): Promise<Record<string, unknown> | null> {
  const length = req.headers.get("content-length");
  if (length !== null && (!/^\d+$/u.test(length) || Number(length) > 16 * 1024)) { await req.body?.cancel(); return null; }
  if (!req.body) return null;
  try {
    const text = await req.text();
    if (new TextEncoder().encode(text).byteLength > 16 * 1024) return null;
    return requestObject(JSON.parse(text));
  } catch { return null; }
}
/** Session-scoped H14 routes. GET reads only local write receipts and tells the caller to reconcile. */
export async function serveMetadataRoute(req: Request, url: URL, caller: MetadataRouteCaller, store: MetadataStateStore, operations: OperationStore): Promise<Response | null> {
  const pane = paneMetadataPath.exec(url.pathname);
  const workspace = workspaceMetadataPath.exec(url.pathname);
  if (!pane && !workspace) return null;
  if (req.method !== "GET" && req.method !== "POST") return refusalResponse(refusal("route.method", "Method is not supported", "capability"), 405);
  const level: GateLevel = req.method === "GET" ? "device-read" : "write";
  const denied = caller.gate(level);
  if (denied) return denied;
  if (!controlScope(url, "session")) return refusalResponse(refusal("scope.malformed", "Malformed control scope", "scope"), 400);
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  let target: string;
  try { target = decodeURIComponent((pane ?? workspace)![1]!); }
  catch { return refusalResponse(refusal("metadata.target_invalid", "Metadata target identifier is malformed", "scope"), 400); }
  const keyPrefix = `${runtime.host}\0${runtime.session ?? ""}\0${pane ? "pane" : "workspace"}\0${target}`;
  const paired = caller.pairedIdentity();
  if (!paired || !/^[a-f0-9]{64}$/u.test(paired)) return refusalResponse(refusal("device.identity_required", "Metadata requires the originating paired device", "device"), 403);
  const source = `${NATIVE_METADATA_SOURCE}.${paired.slice(0, 12)}`;
  const snapshot = await runtime.mux.snapshot();
  const paneRow = pane ? snapshot.panes.find((row) => row.paneId === target) : undefined;
  const workspaceRow = workspace ? snapshot.spaces.find((row) => row.spaceId === target) : undefined;
  if (pane && !paneRow || workspace && !workspaceRow) {
    store.clearTarget(keyPrefix);
    if (req.method === "GET") return Response.json({ ok: true, target, contributions: [], authoritative: false, reconcileRequired: true }, { headers: { "cache-control": "no-store" } });
    return refusalResponse(refusal("metadata.target_gone", "Metadata target no longer exists", "scope"), 409);
  }
  const targetIdentity = paneRow ? payloadHash({ pane: target, terminal: paneRow.terminalIncarnation ?? null, agent: paneRow.agent }) : payloadHash({ workspace: target });
  const key = `${keyPrefix}\0${targetIdentity}`;
  store.clearTarget(keyPrefix, key);
  const evidence = payloadHash(paneRow ? { pane: target, terminal: paneRow.terminalIncarnation ?? null, agent: paneRow.agent, alive: paneRow.alive } : { workspace: target, label: workspaceRow?.label, tabs: workspaceRow?.tabCount });
  if (req.method === "GET") {
    const requestId = url.searchParams.get("request_id");
    if (requestId !== null && !/^[A-Za-z0-9_-]{1,128}$/u.test(requestId)) return refusalResponse(refusal("metadata.request_invalid", "Metadata reconciliation request ID is malformed", "operation"), 400);
    const operation = requestId === null ? null : await operations.inspect(requestId, paired, runtime.host, runtime.session);
    return Response.json({ ok: true, target, contributions: store.read(key).filter((row) => row.source === source), authoritative: false, reconcileRequired: true, operation: operation ?? undefined }, { headers: { "cache-control": "no-store" } });
  }
  if (runtime.mux.capabilities.supports.agentMetadata !== true) return refusalResponse(refusal("capability.unsupported", "Bounded metadata is unavailable on this runtime", "capability"), 501);
  // Capture the source-private B19 actor once at route admission, before reading a
  // potentially delayed request body. Its custody is never replaced during dispatch.
  const originalRead = pane && runtime.mux.agentControl ? await runtime.mux.agentControl(target) : null;
  const original = originalRead?.ok ? Object.freeze({ ...originalRead.value }) : null;
  const body = await readMetadataBody(req);
  if (!body) return refusalResponse(refusal("metadata.request_invalid", "Metadata request must be a bounded JSON object", "scope"), 400);
  const requestId = body.requestId;
  if (typeof requestId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/u.test(requestId)) return refusalResponse(refusal("metadata.request_invalid", "Metadata request requires a valid request ID", "operation"), 400);
  delete body.requestId;
  if (!operations.isDurable) return refusalResponse(refusal("metadata.receipts_unavailable", "Durable metadata operation receipts are unavailable", "capability"), 501);
  if (pane) {
    const allowed = new Set(["agent", "seq", "ttl_ms", "applies_to_source", "tokens", "display_agent", "title", "state_labels", "clear_display_agent", "clear_title", "clear_state_labels"]);
    if (Object.keys(body).some((keyName) => !allowed.has(keyName))) return refusalResponse(refusal("metadata.request_invalid", "Metadata request contains unsupported fields", "scope"), 400);
    const request = { ...body, source } as unknown as PaneMetadataRequest;
    const validation = validatePaneMetadata(request);
    if (!validation.ok) return refusalResponse(refusal(validation.code, "Metadata request was refused by the bounded schema", "scope"), 400);
    if (request.agent !== paneRow?.agent) return refusalResponse(refusal("metadata.agent_mismatch", "Metadata owner does not match the current pane agent", "scope"), 409);
    if (typeof request.agent !== "string") return refusalResponse(refusal("metadata.agent_required", "Pane metadata must name the current agent", "scope"), 400);
    const hasTokens = request.tokens !== undefined && Object.keys(request.tokens).length > 0;
    if (hasTokens && (!original || !original[ORIGINAL_AGENT_BINDING] || !original[ORIGINAL_RUNTIME_INCARNATION])) return refusalResponse(refusal("metadata.original_binding_unavailable", "The original pane occupant could not be captured", "capability"), 501);
    if (hasTokens && original && (original.paneId !== target || original.agent !== paneRow?.agent)) return refusalResponse(refusal("metadata.target_changed", "The metadata admission occupant changed", "scope"), 409);
    const originalCurrent = (identity: string): boolean => original !== null && identity === original.runtimeIdentity && caller.gate("write") === null && caller.pairedIdentity() === paired && (runtime.currentRuntime?.() ?? true);
    const method = hasTokens ? runtime.mux.reportPaneMetadataOriginal : runtime.mux.reportPaneMetadata;
    if (!method) return refusalResponse(refusal("capability.unsupported", "Bounded pane metadata is unavailable on this runtime", "capability"), 501);
    const binding: PlanBinding = { device: paired, host: runtime.host, session: runtime.session, capability: "agentMetadata", payloadHash: payloadHash(safeJson(request)), targetIdentity, evidence };
    let operation: OperationView;
    try {
      operation = await operations.execute(requestId, binding, null, async () => {
        if (caller.gate("write") || caller.pairedIdentity() !== paired) return { state: "refused", code: "metadata.admission_changed" };
        const current = await runtime.mux.snapshot();
        const paneNow = current.panes.find((row) => row.paneId === target);
        if (!paneNow || paneNow.terminalIncarnation !== paneRow?.terminalIncarnation || paneNow.agent !== paneRow?.agent) return { state: "refused", code: "metadata.target_changed" };
        if (!store.canApply(key, source, request.seq ?? null, request)) return { state: "refused", code: "metadata.sequence_source_limit" };
        const result = hasTokens && original
          ? await runtime.mux.reportPaneMetadataOriginal!(target, request, original, originalCurrent)
          : await runtime.mux.reportPaneMetadata!(target, request);
        if (!result.ok) return { state: result.reason === "unreachable" ? "unknown" : "refused", code: result.reason === "unsupported" ? "capability.unsupported" : `metadata.${result.reason}` };
        return { state: "succeeded" };
      });
    } catch (error) {
      const code = operationFailureCode(error);
      caller.audit("pane.metadata.report", target, runtime.host, runtime.session, code);
      return refusalResponse(refusal(code, "The metadata report could not be durably reserved", "operation"), code === "operation.request_id_reused" ? 409 : 503);
    }
    caller.audit("pane.metadata.report", target, runtime.host, runtime.session, operation.state);
    if (operation.state !== "succeeded") return operationResponse(operation);
    store.apply(key, source, request.seq ?? null, request.ttl_ms ?? null, safeJson(request) as StoredMetadataValue);
  } else {
    const allowed = new Set(["seq", "ttl_ms", "tokens"]);
    if (Object.keys(body).some((keyName) => !allowed.has(keyName))) return refusalResponse(refusal("metadata.request_invalid", "Metadata request contains unsupported fields", "scope"), 400);
    const request = { ...body, source } as unknown as WorkspaceMetadataRequest;
    const validation = validateWorkspaceMetadata(request);
    if (!validation.ok) return refusalResponse(refusal(validation.code, "Metadata request was refused by the bounded schema", "scope"), 400);
    const method = runtime.mux.reportWorkspaceMetadata;
    if (!method) return refusalResponse(refusal("capability.unsupported", "Bounded workspace metadata is unavailable on this runtime", "capability"), 501);
    const binding: PlanBinding = { device: paired, host: runtime.host, session: runtime.session, capability: "agentMetadata", payloadHash: payloadHash(safeJson(request)), targetIdentity, evidence };
    let operation: OperationView;
    try {
      operation = await operations.execute(requestId, binding, null, async () => {
        if (caller.gate("write") || caller.pairedIdentity() !== paired) return { state: "refused", code: "metadata.admission_changed" };
        const current = await runtime.mux.snapshot();
        if (!current.spaces.some((row) => row.spaceId === target)) return { state: "refused", code: "metadata.target_changed" };
        if (!store.canApply(key, source, request.seq ?? null, request)) return { state: "refused", code: "metadata.sequence_source_limit" };
        const result = await method.call(runtime.mux, target, request);
        if (!result.ok) return { state: result.reason === "unreachable" ? "unknown" : "refused", code: result.reason === "unsupported" ? "capability.unsupported" : `metadata.${result.reason}` };
        return { state: "succeeded" };
      });
    } catch (error) {
      const code = operationFailureCode(error);
      caller.audit("workspace.metadata.report", target, runtime.host, runtime.session, code);
      return refusalResponse(refusal(code, "The metadata report could not be durably reserved", "operation"), code === "operation.request_id_reused" ? 409 : 503);
    }
    caller.audit("workspace.metadata.report", target, runtime.host, runtime.session, operation.state);
    if (operation.state !== "succeeded") return operationResponse(operation);
    store.apply(key, source, request.seq ?? null, request.ttl_ms ?? null, safeJson(request) as StoredMetadataValue);
  }
  return Response.json({ ok: true, source, receipt: true, reconcileRequired: true, requestId }, { headers: { "cache-control": "no-store" } });
}

function operationResponse(operation: OperationView): Response {
  if (operation.state === "pending" || operation.state === "unknown") return Response.json({ ok: false, code: "metadata.outcome_unknown", operation }, { status: 202, headers: { "cache-control": "no-store" } });
  return refusalResponse(refusal(operation.code ?? "metadata.refused", "Metadata report was refused", "operation"), 409);
}

function operationFailureCode(error: unknown): string {
  if (!(error instanceof Error)) return "operation.receipts_unavailable";
  if (error.message === "operation.capacity") return "operation.capacity";
  if (error.message.includes("operation.request_id_reused")) return "operation.request_id_reused";
  return "operation.receipts_unavailable";
}
