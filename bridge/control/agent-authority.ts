import { open } from "node:fs/promises";
import { join } from "node:path";
import type { MuxAgentIdentity, MuxOutcome } from "../mux/types.ts";
import { refusal, refusalResponse } from "./contracts.ts";
import { MAX_AGENT_IDENTITY_BODY_BYTES } from "./agent-identity.ts";

/*
 * SAFETY: This module is the strict boundary parser for bounded operator JSON and HTTP JSON.
 * These validators accept unknown only to enforce exact schemas before constructing domain
 * intents; keep the representation checks together at the I/O boundary.
 */
/* eslint-disable anti-slop/no-unknown-parameters, anti-slop/no-unsafe-dictionary-type, anti-slop/no-runtime-typeof, anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-unknown-returns */

export const AGENT_REPORTER_SOURCES = ["claude", "codex", "opencode", "herdr", "tmux", "zellij", "collie"] as const;
export type AgentMutationKind = "state" | "session" | "release" | "authority-clear";
export type AgentLifecycleState = "idle" | "working" | "blocked" | "unknown";

export interface AgentReporterRegistration {
  readonly source: string;
  /** Plain PATH names only; no path, shell, or command string is accepted. */
  readonly resumeExecutables: readonly string[];
  /** Exact references explicitly admitted by the operator; never a path prefix or glob. */
  readonly sessionReferences: readonly { readonly agent: string; readonly kind: "id" | "path"; readonly value: string }[];
}

export interface AgentMutationIdentity {
  readonly paneId: string;
  readonly agent: string | null;
  readonly session?: NonNullable<MuxAgentIdentity["session"]>;
  readonly stateChangeSeq: string | null;
}

export interface AgentMutationIntent {
  readonly kind: AgentMutationKind;
  readonly paneId: string;
  readonly source: string;
  readonly expectedAgent: string;
  readonly expectedSession: NonNullable<MuxAgentIdentity["session"]>;
  readonly seq: number;
  readonly state?: AgentLifecycleState;
  readonly session?: NonNullable<MuxAgentIdentity["session"]>;
  readonly resumeArgv?: readonly string[];
}

const SOURCE_ID = /^collie\.custom\.[a-z0-9._-]{1,48}$/;
const PLAIN_EXECUTABLE = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$/;
function hasControl(value: string): boolean {
  return [...value].some((character) => {
    const code = character.codePointAt(0)!;
    return code < 32 || (code >= 127 && code <= 159);
  });
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function boundedText(value: unknown, maximum: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximum && !hasControl(value);
}
function exactKeys(row: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(row).length === keys.length && Object.keys(row).every((key) => keys.includes(key));
}
function validSession(value: unknown): value is NonNullable<MuxAgentIdentity["session"]> {
  return object(value) && exactKeys(value, ["source", "agent", "kind", "value"]) &&
    boundedText(value.source, 80) && boundedText(value.agent, 80) &&
    (value.kind === "id" || value.kind === "path") && boundedText(value.value, 2048);
}

/** Operator-owned allowlist. Official hook sources are rejected even if accidentally configured. */
export class AgentReporterRegistry {
  private readonly entries = new Map<string, AgentReporterRegistration>();
  constructor(rows: readonly AgentReporterRegistration[]) {
    for (const row of rows) {
      if (!SOURCE_ID.test(row.source) || this.entries.has(row.source)) {
        throw new Error("invalid or duplicate custom agent reporter source");
      }
      if (row.resumeExecutables.length > 32 || !row.resumeExecutables.every((name) => PLAIN_EXECUTABLE.test(name) && !name.includes("'")) ||
          row.sessionReferences.length > 128 || !row.sessionReferences.every((ref) => boundedText(ref.agent, 80) &&
            (ref.kind === "id" || ref.kind === "path") && boundedText(ref.value, 2048))) {
        throw new Error("invalid custom agent resume executable allowlist");
      }
      this.entries.set(row.source, {
        source: row.source,
        resumeExecutables: [...new Set(row.resumeExecutables)],
        sessionReferences: row.sessionReferences.map((ref) => ({ agent: ref.agent, kind: ref.kind, value: ref.value })),
      });
    }
  }
  get(source: string): AgentReporterRegistration | undefined { return this.entries.get(source); }
  sources(): readonly string[] { return [...this.entries.keys()].toSorted(); }

  /** Read only the operator-owned custom reporter allowlist; absence means no source is registered. */
  static async load(stateDir: string): Promise<AgentReporterRegistry> {
    let handle;
    try { handle = await open(join(stateDir, "agent-reporters.json"), "r"); }
    catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") return new AgentReporterRegistry([]);
      return new AgentReporterRegistry([]);
    }
    try {
      const info = await handle.stat();
      if (!info.isFile() || info.size > 16 * 1024) return new AgentReporterRegistry([]);
      const bytes = new Uint8Array(info.size + 1);
      const { bytesRead } = await handle.read(bytes, 0, bytes.length, 0);
      if (bytesRead > 16 * 1024) return new AgentReporterRegistry([]);
      const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, bytesRead)));
      if (!object(parsed) || !exactKeys(parsed, ["schema", "sources"]) || parsed.schema !== 1 || !Array.isArray(parsed.sources) || parsed.sources.length > 32) {
        return new AgentReporterRegistry([]);
      }
      const rows: AgentReporterRegistration[] = [];
      for (const entry of parsed.sources) {
        if (!object(entry) || !exactKeys(entry, ["source", "resumeExecutables", "sessionReferences"]) || !boundedText(entry.source, 64) ||
            !Array.isArray(entry.resumeExecutables) || !entry.resumeExecutables.every((name) => boundedText(name, 128)) ||
            !Array.isArray(entry.sessionReferences) || !entry.sessionReferences.every((ref) => object(ref) && exactKeys(ref, ["agent", "kind", "value"]))) {
          return new AgentReporterRegistry([]);
        }
        const refs = entry.sessionReferences;
        if (!refs.every((ref) => object(ref) && boundedText(ref.agent, 80) && (ref.kind === "id" || ref.kind === "path") && boundedText(ref.value, 2048))) {
          return new AgentReporterRegistry([]);
        }
        rows.push({ source: entry.source, resumeExecutables: entry.resumeExecutables, sessionReferences: refs as AgentReporterRegistration["sessionReferences"] });
      }
      return new AgentReporterRegistry(rows);
    } catch {
      return new AgentReporterRegistry([]);
    } finally { await handle.close(); }
  }
}

/** Strictly parse one-shot intent against a currently joined owner/session identity. */
export function validateAgentMutationIntent(
  kind: AgentMutationKind,
  paneId: string,
  raw: unknown,
  registry: AgentReporterRegistry,
  current: AgentMutationIdentity,
): AgentMutationIntent | null {
  if (!object(raw) || current.paneId !== paneId || !boundedText(current.agent, 80) || !validSession(current.session)) return null;
  const observedSeq = current.stateChangeSeq === null ? null : Number(current.stateChangeSeq);
  if (!boundedText(raw.source, 64) || !SOURCE_ID.test(raw.source) || !boundedText(raw.expectedAgent, 80) || raw.expectedAgent !== current.agent ||
      !validSession(raw.expectedSession) || raw.expectedSession.source !== current.session.source || raw.expectedSession.agent !== current.session.agent ||
      raw.expectedSession.kind !== current.session.kind || raw.expectedSession.value !== current.session.value ||
      typeof raw.seq !== "number" || !Number.isSafeInteger(raw.seq) || raw.seq < 0 || raw.seq !== observedSeq || !registry.get(raw.source)) return null;

  const shared = { kind, paneId, source: raw.source, expectedAgent: raw.expectedAgent, expectedSession: raw.expectedSession, seq: raw.seq } as const;
  if (kind === "state") {
    if (!exactKeys(raw, ["source", "expectedAgent", "expectedSession", "seq", "state"]) ||
        typeof raw.state !== "string" || !["idle", "working", "blocked", "unknown"].includes(raw.state)) return null;
    return { ...shared, state: raw.state as AgentLifecycleState };
  }
  if (kind === "session") {
    const keys = raw.resumeArgv === undefined
      ? ["source", "expectedAgent", "expectedSession", "seq", "session"]
      : ["source", "expectedAgent", "expectedSession", "seq", "session", "resumeArgv"];
    if (!exactKeys(raw, keys) || !validSession(raw.session) ||
        raw.session.source !== raw.source || raw.session.agent !== raw.expectedAgent) return null;
    const session = raw.session;
    const registration = registry.get(raw.source)!;
    const exactCurrentRef = session.kind === current.session.kind && session.value === current.session.value;
    const explicitlyRegisteredRef = registration.sessionReferences.some((ref) =>
      ref.agent === session.agent && ref.kind === session.kind && ref.value === session.value,
    );
    if (!exactCurrentRef && !explicitlyRegisteredRef) return null;
    if (raw.resumeArgv !== undefined && !validRegisteredResumeArgv(raw.resumeArgv, registration, session.value)) return null;
    if (raw.resumeArgv === undefined) return { ...shared, session };
    return { ...shared, session, resumeArgv: raw.resumeArgv };
  }
  if (kind === "release" || kind === "authority-clear") {
    if (!exactKeys(raw, ["source", "expectedAgent", "expectedSession", "seq"])) return null;
    return shared;
  }
  return null;
}

/** Resume arguments are a bounded argv vector, never shell text or a client-selected path. */
export function validRegisteredResumeArgv(argv: unknown, registration: AgentReporterRegistration, expectedSessionRef?: string): argv is readonly string[] {
  if (!Array.isArray(argv) || argv.length === 0 || argv.length > 64 || !argv.every((part) => boundedText(part, 2048) && !part.includes("'"))) return false;
  const total = argv.reduce((bytes, part) => bytes + new TextEncoder().encode(part).byteLength + 1, 0);
  return total <= 8192 && PLAIN_EXECUTABLE.test(argv[0]!) && !argv[0]!.includes("/") && !argv[0]!.includes("\\") &&
    registration.resumeExecutables.includes(argv[0]!) && (expectedSessionRef === undefined || argv.includes(expectedSessionRef));
}

export interface AgentMutationRuntime {
  readonly capabilities: { readonly supports: { readonly agentIdentity?: boolean; readonly agentLifecycleWrites?: boolean } };
  readonly agentIdentity?: (paneId: string) => Promise<MuxOutcome<MuxAgentIdentity>>;
}
export interface AgentMutationSnapshot {
  current(): { readonly agents: readonly { readonly paneId: string }[]; readonly shellPanes: readonly { readonly paneId: string }[] };
}

/** Validate intent and current refs, then refuse before upstream dispatch until owner-CAS exists. */
export async function refuseUnsafeAgentMutation(
  mux: AgentMutationRuntime,
  engine: AgentMutationSnapshot,
  paneId: string,
  kind: AgentMutationKind,
  req: Request,
  registry: AgentReporterRegistry,
): Promise<Response> {
  if (!(await ownsPane(engine, paneId))) return refusalResponse(refusal("pane.not_found", "Pane not found", "scope"), 404);
  const raw = await boundedJson(req);
  if (raw === null) return refusalResponse(refusal("agent.intent_invalid", "Agent lifecycle request was invalid or exceeded its limit", "operation"), 400);
  if (!object(raw) || !boundedText(raw.source, 64) || !registry.get(raw.source)) {
    return refusalResponse(refusal("agent.source_unregistered", "The requested custom reporter source is not registered on this host", "capability"), 403);
  }
  if (mux.capabilities.supports.agentIdentity !== true || !mux.agentIdentity) {
    return refusalResponse(refusal("capability.unsupported", "This runtime cannot verify current agent identity", "capability"), 501);
  }
  const observed = await mux.agentIdentity(paneId);
  if (!observed.ok || !(await ownsPane(engine, paneId))) {
    return refusalResponse(refusal("agent.identity_unavailable", "Current agent identity could not be verified", "scope"), 409);
  }
  const intent = validateAgentMutationIntent(kind, paneId, raw, registry, observed.value);
  if (!intent) return refusalResponse(refusal("agent.intent_invalid", "Source, occupant, session reference or sequence did not match", "scope"), 409);
  if (!mux.capabilities.supports.agentLifecycleWrites) {
    return refusalResponse(refusal("agent.occupant_cas_required", "This runtime cannot atomically bind lifecycle writes to the current pane occupant; no update was sent", "capability"), 501);
  }
  // Future dispatch remains gated until an adapter proves occupant-CAS.
  return refusalResponse(refusal("agent.occupant_cas_required", "This runtime cannot atomically bind lifecycle writes to the current pane occupant; no update was sent", "capability"), 501);
}

async function ownsPane(engine: AgentMutationSnapshot, paneId: string): Promise<boolean> {
  const current = engine.current();
  return [...current.agents, ...current.shellPanes].some((pane) => pane.paneId === paneId);
}

async function boundedJson(req: Request): Promise<unknown | null> {
  if (!req.body) return null;
  if (req.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase() !== "application/json") return null;
  const declaredLength = req.headers.get("content-length");
  if (declaredLength !== null && /^\d+$/.test(declaredLength) && Number(declaredLength) > MAX_AGENT_IDENTITY_BODY_BYTES) return null;
  try {
    const reader = req.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.byteLength;
      if (size > MAX_AGENT_IDENTITY_BODY_BYTES) { await reader.cancel(); return null; }
      chunks.push(part.value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch { return null; }
}

/* eslint-enable anti-slop/no-unknown-parameters, anti-slop/no-unsafe-dictionary-type, anti-slop/no-runtime-typeof, anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-unknown-returns */
