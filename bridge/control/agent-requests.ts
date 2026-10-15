import { parseMuxKey } from "../mux/keys.ts";
import { AGENT_EVENTS, AGENT_LIMITS, type AgentAction, type AgentStatus, type AgentWaitCondition } from "./agent-models.ts";
import type { JsonObject, JsonValue } from "../json.ts";

export const NAMED_AGENT_KINDS = ["pi", "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode", "opencode", "copilot", "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli", "qwen", "letta", "maki", "muse"] as const;
/* Closed untrusted HTTP JSON is the runtime boundary. */
/* eslint-disable anti-slop/no-runtime-typeof */
export function agentRecord(value: JsonValue | undefined): JsonObject | null { return value !== undefined && value !== null && typeof value === "object" && !Array.isArray(value) ? value : null; }
export function agentKeys(row: JsonObject, keys: readonly string[]): boolean { return Object.keys(row).length === keys.length && Object.keys(row).every((key) => keys.includes(key)); }
export function agentId(value: JsonValue | undefined): value is string { return typeof value === "string" && /^[A-Za-z0-9_.:-]{1,128}$/.test(value); }
export function agentRequestId(value: JsonValue | undefined): value is string { return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value); }
export function agentText(value: JsonValue | undefined, maximum: number): value is string { return typeof value === "string" && Buffer.byteLength(value) <= maximum && !value.includes("\u0000") && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value); }
function status(value: JsonValue): value is AgentStatus { return value === "idle" || value === "working" || value === "blocked" || value === "done" || value === "unknown"; }
function statuses(value: JsonValue | undefined): value is AgentStatus[] { return Array.isArray(value) && value.length > 0 && value.length <= 5 && value.every(status) && new Set(value).size === value.length; }
export function agentTimeout(value: JsonValue | undefined): value is number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 1 && value <= AGENT_LIMITS.timeoutMs; }
export function parseAgentAction(kind: string, value: JsonValue | undefined): AgentAction | null {
  const row = agentRecord(value); if (!row) return null;
  if (kind === "focus" && agentKeys(row, [])) return { kind: "focus" };
  if ((kind === "keys" || kind === "input") && agentKeys(row, kind === "input" ? ["text", "keys"] : ["keys"]) && Array.isArray(row.keys) && row.keys.length <= AGENT_LIMITS.keys && row.keys.every((key) => agentText(key, 64) && parseMuxKey(key) !== null)) {
    const keys = row.keys.filter((key): key is string => typeof key === "string");
    if (kind === "keys" && keys.length > 0) return { kind: "keys", keys };
    if (kind === "input" && agentText(row.text, AGENT_LIMITS.promptBytes) && (row.text.length > 0 || keys.length > 0)) return { kind: "input", text: row.text, keys };
  }
  if (kind === "start" && agentKeys(row, ["name", "agent", "args", "timeoutMs"]) && typeof row.name === "string" && /^[a-z][a-z0-9_-]{0,31}$/.test(row.name) && typeof row.agent === "string" && NAMED_AGENT_KINDS.some((item) => item === row.agent) && Array.isArray(row.args) && row.args.length <= AGENT_LIMITS.args && row.args.every((arg) => agentText(arg, AGENT_LIMITS.argChars) && !Array.from(arg).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) && agentTimeout(row.timeoutMs) && row.timeoutMs > 3000) return { kind: "start", name: row.name, agent: row.agent, args: row.args.filter((arg): arg is string => typeof arg === "string"), timeoutMs: row.timeoutMs };
  if (kind === "rename" && agentKeys(row, ["name"]) && (row.name === null || (typeof row.name === "string" && /^[a-z][a-z0-9_-]{0,31}$/.test(row.name)))) return { kind: "rename", name: row.name };
  if (kind === "prompt" && agentKeys(row, ["text", "until", "timeoutMs"]) && agentText(row.text, AGENT_LIMITS.promptBytes) && row.text.trim().length > 0 && (row.until === null || statuses(row.until)) && agentTimeout(row.timeoutMs)) return { kind: "prompt", text: row.text, until: row.until, timeoutMs: row.timeoutMs };
  return null;
}
export function parseAgentWait(value: JsonValue | undefined): AgentWaitCondition | null {
  const row = agentRecord(value); if (!row) return null;
  if (row.kind === "lifecycle" && agentKeys(row, ["kind", "until"]) && statuses(row.until)) return { kind: "lifecycle", until: row.until };
  if (row.kind === "output" && agentKeys(row, ["kind", "source", "match", "lines"]) && (row.source === "visible" || row.source === "recent" || row.source === "recent_unwrapped" || row.source === "detection") && typeof row.lines === "number" && Number.isSafeInteger(row.lines) && row.lines >= 1 && row.lines <= AGENT_LIMITS.lines) {
    const match = agentRecord(row.match);
    if (match && agentKeys(match, ["kind", "value"]) && (match.kind === "substring" || match.kind === "regex") && agentText(match.value, match.kind === "regex" ? AGENT_LIMITS.regexChars : 1024) && match.value.length > 0) return { kind: "output", source: row.source, lines: row.lines, match: { kind: match.kind, value: match.value } };
  }
  if (row.kind === "event" && agentKeys(row, ["kind", "event", "targetId", "workspaceId", "status", "agent", "label"]) && AGENT_EVENTS.some((event) => event === row.event) && (row.targetId === null || agentId(row.targetId)) && (row.workspaceId === null || agentId(row.workspaceId)) && (row.status === null || (row.status !== undefined && status(row.status))) && (row.agent === null || agentId(row.agent)) && (row.label === null || agentText(row.label, 128))) {
    const event = AGENT_EVENTS.find((candidate) => candidate === row.event)!;
    const created = event === "workspace-created" || event === "tab-created" || event === "pane-created";
    if ((!created && row.targetId === null) || (row.workspaceId !== null && event !== "tab-created" && event !== "pane-created") || (event === "agent-status-changed" ? row.status === null : row.status !== null) || (row.agent !== null && event !== "agent-detected") || (row.label !== null && event !== "workspace-renamed" && event !== "tab-renamed")) return null;
    return { kind: "event", event, targetId: row.targetId, workspaceId: row.workspaceId, status: row.status, agent: row.agent, label: row.label };
  }
  return null;
}
/* eslint-enable anti-slop/no-runtime-typeof */

/** Bounded streaming, strict UTF-8, duplicate-free closed JSON; a trickling body has a finite lease. */
export async function readAgentBody(request: Request): Promise<JsonValue | null> {
  if (request.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase() !== "application/json" || !request.body) return null;
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^(0|[1-9][0-9]*)$/.test(declared) || Number(declared) > AGENT_LIMITS.bodyBytes)) { await request.body.cancel().catch(() => {}); return null; }
  const reader = request.body.getReader(); let bytes = 0; const chunks: Uint8Array[] = []; let expired = false;
  const timer = setTimeout(() => { expired = true; void reader.cancel().catch(() => {}); }, 5000);
  try {
    while (true) { const next = await reader.read(); if (expired) return null; if (next.done) break; bytes += next.value.byteLength; if (bytes > AGENT_LIMITS.bodyBytes) { await reader.cancel(); return null; } chunks.push(next.value); }
    const buffer = new Uint8Array(bytes); let offset = 0; for (const chunk of chunks) { buffer.set(chunk, offset); offset += chunk.byteLength; }
    return strictAgentJson(new TextDecoder("utf-8", { fatal: true }).decode(buffer));
  } catch { return null; }
  finally { clearTimeout(timer); reader.releaseLock(); }
}

export function strictAgentJson(text: string, arrayBound: number = 64): JsonValue {
  if (!Number.isSafeInteger(arrayBound) || arrayBound < 1 || arrayBound > AGENT_LIMITS.retainedGlobal) throw new Error("JSON array limit");
  let at = 0; const space = () => { while ([" ", "\t", "\n", "\r"].includes(text[at] ?? "")) at++; };
  const string = (): string => {
    const start = at++; let escaped = false;
    while (at < text.length) { const character = text[at++]!; if (escaped) escaped = false; else if (character === "\\") escaped = true; else if (character === '"') { const value: string = JSON.parse(text.slice(start, at)); if (!agentText(value, AGENT_LIMITS.bodyBytes)) throw new Error("invalid JSON string"); return value; } }
    throw new Error("unterminated JSON string");
  };
  const value = (depth: number): JsonValue => {
    space(); if (depth > 8) throw new Error("JSON depth");
    if (text[at] === '"') return string();
    if (text[at] === "{") {
      at++; space(); const row: JsonObject = Object.create(null); const keys = new Set<string>();
      if (text[at] === "}") { at++; return row; }
      while (true) { space(); if (text[at] !== '"') throw new Error("JSON key"); const key = string(); if (keys.has(key) || keys.size >= 64) throw new Error("JSON duplicate/key bound"); keys.add(key); space(); if (text[at++] !== ":") throw new Error("JSON colon"); row[key] = value(depth + 1); space(); const end = text[at++]; if (end === "}") return row; if (end !== ",") throw new Error("JSON object separator"); }
    }
    if (text[at] === "[") { at++; space(); const array: JsonValue[] = []; if (text[at] === "]") { at++; return array; } while (true) { if (array.length >= arrayBound) throw new Error("JSON array bound"); array.push(value(depth + 1)); space(); const end = text[at++]; if (end === "]") return array; if (end !== ",") throw new Error("JSON array separator"); } }
    const token = /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(at))?.[0];
    if (!token) throw new Error("JSON value"); at += token.length; const result: JsonValue = JSON.parse(token); if (!Number.isFinite(Number(token)) && token !== "null" && token !== "true" && token !== "false") throw new Error("JSON number"); return result;
  };
  const result = value(0); space(); if (at !== text.length) throw new Error("JSON trailing bytes"); return result;
}
