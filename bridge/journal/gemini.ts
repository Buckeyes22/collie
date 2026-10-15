// Gemini CLI's on-disk session journal.
//
// The supported record contract is from Google's current chatRecordingTypes.ts and
// chatRecordingService.ts: JSONL contains one session metadata record, message records, and
// append-only patch/rewind records. Main-session files use a timestamp plus the first eight UUID
// characters; subagent files use the complete UUID in a child folder. The path is discovered under
// the operator-configured ~/.gemini/tmp root and the full sessionId is checked in the file before
// it is returned. We never derive a project hash from cwd or trust a client path.

import { opendir } from "node:fs/promises";
import { join } from "node:path";

import type { JsonObject, JsonValue } from "../json.ts";
import {
  createUnknownCounter,
  type KnownTypes,
  NO_CHANGE,
  noQueue,
  parseWith,
  reduction,
  type Reduction,
  type RowReducer,
} from "./reduce.ts";
import { containedRealpath, loadTail, readSinceFile, rootList, statFile } from "./files.ts";
import { clamp, MAX_RESULT_CHARS, MAX_TEXT_CHARS, summarizeToolInput, type Clamped } from "./text.ts";
import { classifyToolCall } from "./tool-call.ts";
import type {
  AgentSessionRef,
  JournalAdapter,
  TranscriptEntry,
  TranscriptPart,
  TranscriptSource,
} from "./types.ts";

const SESSION_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PROJECT_DIR_MAX = 256;
const CHAT_ENTRY_MAX = 4096;
const SESSION_HEAD_BYTES = 16 * 1024;
const ROW_MAX_CHARS = 2 * 1024 * 1024;
const ORDER_ID_MAX = 10_000;
const SESSION_ENTRY_MAX = 10_000;

// Structural Part fields from Google's js-genai Part interface, checked 2026-10-11:
// https://github.com/googleapis/js-genai/blob/main/src/types.ts#L2034-L2076
// The SDK contract says exactly one content variant is set, with optional metadata alongside it.
// Keep metadata fields here too so ordinary SDK records don't become false drift alarms. Several
// recognized fields are intentionally not projected into this native transcript yet (function
// parts, code execution, file/media, audio/video and metadata); they are known omissions, not
// unsupported new kinds. Inline payloads are never copied into transcript content.
const GEMINI_PART_FIELDS = [
  "audioTranscription",
  "codeExecutionResult",
  "executableCode",
  "fileData",
  "functionCall",
  "functionResponse",
  "inlineData",
  "mediaProcessing",
  "mediaResolution",
  "partMetadata",
  "speechMetadata",
  "text",
  "thought",
  "thoughtSignature",
  "toolCall",
  "toolResponse",
  "videoMetadata",
] as const;
const GEMINI_PART_FIELD_SET: ReadonlySet<string> = new Set(GEMINI_PART_FIELDS);
const SAFE_PART_FIELD = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

interface GeminiToolResult extends Clamped {
  isError?: true;
}

export function isGeminiSessionId(value: string): boolean {
  return SESSION_ID_RE.test(value);
}

const KNOWN: KnownTypes = {
  rows: ["user", "gemini", "info", "error", "warning"],
  roles: [],
  // The format has structural field names, not a `type` tag. These are all fields from the
  // reviewed SDK Part interface, including known-but-not-rendered content and metadata fields.
  parts: GEMINI_PART_FIELDS,
};

function isRecord(value: JsonValue | undefined): value is JsonObject {
  return value !== null && value !== undefined && typeof value === "object" && !Array.isArray(value);
}

function object(value: JsonValue | undefined): JsonObject | null {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
  // SAFETY: JsonValue's remaining object variant is JsonObject after the null/array checks above.
  return value as JsonObject;
}

function boundedContent(value: JsonValue | undefined, limit: number): Clamped {
  if (typeof value === "string") return clamp(value, limit);
  if (!Array.isArray(value)) return { text: "" };
  const pieces: string[] = [];
  let size = 0;
  let truncated = false;
  for (const part of value) {
    if (!isRecord(part) || typeof part.text !== "string") continue;
    const separator = pieces.length === 0 ? "" : "\n";
    const room = Math.max(0, limit - size - separator.length);
    if (room === 0 && part.text.length > 0) {
      truncated = true;
      break;
    }
    const text = part.text.slice(0, room);
    if (text !== "") pieces.push(text);
    size += separator.length + text.length;
    if (text.length < part.text.length) {
      truncated = true;
      break;
    }
  }
  const result: Clamped = { text: pieces.join("\n") };
  if (truncated) result.truncated = true;
  return result;
}

function toolResultContent(value: JsonValue | undefined): Clamped {
  return boundedContent(value, MAX_RESULT_CHARS);
}

function countStructuralContent(value: JsonValue | undefined, unknown: ReturnType<typeof createUnknownCounter>): void {
  if (!Array.isArray(value)) return;
  for (const item of value.slice(0, 256)) {
    const part = object(item);
    if (part === null) continue;
    for (const field of Object.keys(part)) {
      if (GEMINI_PART_FIELD_SET.has(field)) continue;
      // A future schema field name is diagnostic metadata, never the associated payload. Avoid
      // reflecting attacker-shaped strings/control characters into canary output.
      unknown.part(SAFE_PART_FIELD.test(field) ? field : "(unclassified Gemini Part field)");
    }
  }
}

function messageEntry(row: JsonObject, unknown: ReturnType<typeof createUnknownCounter>): TranscriptEntry | null {
  countStructuralContent(row.content, unknown);
  if (typeof row.id !== "string" || row.id.length === 0 || row.id.length > 256) return null;
  if (row.type !== "user" && row.type !== "gemini" && row.type !== "info" && row.type !== "error" && row.type !== "warning") return null;
  const timestamp = typeof row.timestamp === "string" ? row.timestamp : "";
  const noticePrefix = row.type === "info" ? "Info: " : row.type === "error" ? "Error: " : row.type === "warning" ? "Warning: " : "";
  const role = row.type === "user" ? "user" : noticePrefix === "" ? "assistant" : "note";
  const parts: TranscriptPart[] = [];
  const text = boundedContent(row.content, Math.max(0, MAX_TEXT_CHARS - noticePrefix.length));
  if (text.text.trim() !== "") parts.push({ kind: "text", ...text, text: `${noticePrefix}${text.text}` });

  if (row.type === "gemini") {
    if (Array.isArray(row.thoughts)) {
      for (const item of row.thoughts.slice(0, 64)) {
        const thought = object(item);
        if (thought === null) continue;
        const subject = typeof thought.subject === "string" ? thought.subject : "";
        const description = typeof thought.description === "string" ? thought.description : "";
        const summary = [subject, description].filter(Boolean).join("\n");
        if (summary.trim() !== "") parts.push({ kind: "thinking", ...clamp(summary, MAX_TEXT_CHARS) });
      }
    }
    if (Array.isArray(row.toolCalls)) {
      for (const item of row.toolCalls.slice(0, 256)) {
        const call = object(item);
        if (call === null) continue;
        const name = typeof call.name === "string" && call.name.length > 0 ? call.name.slice(0, 256) : "tool";
        const args = call.args;
        const summary = summarizeToolInput(args);
        const part: Extract<TranscriptPart, { kind: "tool" }> = {
          kind: "tool",
          name,
          summary,
          call: classifyToolCall(name, args, summary),
        };
        if (typeof call.id === "string" && call.id.length > 0 && call.id.length <= 256) part.id = call.id;
        if (call.result !== undefined && call.result !== null) {
          const result: GeminiToolResult = toolResultContent(call.result);
          if (call.status === "error") result.isError = true;
          part.result = result;
        }
        parts.push(part);
      }
    }
  }

  if (parts.length === 0) return null;
  return { uuid: row.id, ts: timestamp, role, parts };
}

function replaceEntry(current: TranscriptEntry, next: TranscriptEntry): void {
  current.ts = next.ts;
  current.role = next.role;
  current.parts.splice(0, current.parts.length, ...next.parts);
  delete current.abandoned;
}

/** Parse one JSONL row. Gemini's JSONL can end in a partial line; malformed rows are ignored. */
export function createGeminiReducer(): RowReducer {
  const unknown = createUnknownCounter(KNOWN);
  const entries: TranscriptEntry[] = [];
  const byId = new Map<string, TranscriptEntry>();

  function insert(row: JsonObject, changed: Set<string>, added: TranscriptEntry[]): boolean {
    const entry = messageEntry(row, unknown);
    if (entry === null) return false;
    const old = byId.get(entry.uuid);
    if (old !== undefined) {
      replaceEntry(old, entry);
      changed.add(old.uuid);
      return false;
    }
    entries.push(entry);
    byId.set(entry.uuid, entry);
    added.push(entry);
    if (entries.length > SESSION_ENTRY_MAX) {
      const retired = entries.shift();
      if (retired !== undefined) byId.delete(retired.uuid);
      return true;
    }
    return false;
  }

  function replaceSnapshot(): Reduction {
    return { added: NO_CHANGE.added, changed: NO_CHANGE.changed, replace: [...entries] };
  }

  function patchMessage(raw: JsonValue | undefined, changed: Set<string>): void {
    const patch = object(raw);
    if (patch === null || typeof patch.id !== "string") return;
    const current = byId.get(patch.id);
    if (current === undefined) return;
    if (Object.hasOwn(patch, "content")) {
      countStructuralContent(patch.content, unknown);
      const text = boundedContent(patch.content, MAX_TEXT_CHARS);
      const prior = current.parts.find((part) => part.kind === "text");
      if (text.text.trim() === "") {
        current.parts.splice(0, current.parts.length, ...current.parts.filter((part) => part.kind !== "text"));
      } else if (prior?.kind === "text") {
        prior.text = text.text;
        if (text.truncated) prior.truncated = true;
        else delete prior.truncated;
      } else {
        current.parts.unshift({ kind: "text", ...text });
      }
      changed.add(current.uuid);
    }
    if (Array.isArray(patch.toolCalls)) {
      for (const rawTool of patch.toolCalls.slice(0, 256)) {
        const toolPatch = object(rawTool);
        if (toolPatch === null || typeof toolPatch.id !== "string" || !Object.hasOwn(toolPatch, "result")) continue;
        const target = current.parts.find((part) => part.kind === "tool" && part.id === toolPatch.id);
        if (target?.kind === "tool") {
          if (toolPatch.result === null) delete target.result;
          else target.result = toolResultContent(toolPatch.result);
          changed.add(current.uuid);
        }
      }
    }
  }

  return {
    push(line: string): Reduction {
      if (line.trim() === "" || line.length > ROW_MAX_CHARS) return NO_CHANGE;
      let parsed: JsonValue;
      try {
        // SAFETY: JSON.parse is the only conversion at this I/O boundary; the subsequent isRecord
        // guard narrows the parsed value before any vendor field is read.
        parsed = JSON.parse(line) as JsonValue;
      } catch {
        return NO_CHANGE;
      }
      if (!isRecord(parsed)) return NO_CHANGE;
      const row = parsed;
      const changed = new Set<string>();
      const added: TranscriptEntry[] = [];
      unknown.row(row.type);

      if (typeof row.$rewindTo === "string") {
        const at = entries.findIndex((entry) => entry.uuid === row.$rewindTo);
        // Gemini truncates from the target onward. If the target fell outside our bounded tail,
        // clear the visible branch instead of retaining turns that may have been removed.
        const removed = entries.splice(at < 0 ? 0 : at);
        for (const entry of removed) byId.delete(entry.uuid);
        return replaceSnapshot();
      }

      if (isRecord(row.$patch)) {
        const patch = row.$patch;
        let needsReplace = false;
        if (typeof patch.id === "string") patchMessage(patch, changed);
        if (Array.isArray(patch.updates)) {
          for (const item of patch.updates.slice(0, 256)) patchMessage(item, changed);
        }
        if (Array.isArray(patch.removeIds)) {
          if (patch.removeIds.length > ORDER_ID_MAX) unknown.row("patch/removeIds-overflow");
          let removed = false;
          for (const rawId of patch.removeIds.slice(0, ORDER_ID_MAX)) {
            if (typeof rawId !== "string") continue;
            const target = byId.get(rawId);
            if (target !== undefined) {
              byId.delete(rawId);
              const index = entries.indexOf(target);
              if (index >= 0) entries.splice(index, 1);
              removed = true;
            }
          }
          needsReplace ||= removed;
        }
        if (Array.isArray(patch.orderIds)) {
          if (patch.orderIds.length > ORDER_ID_MAX) {
            unknown.row("patch/orderIds-overflow");
            return needsReplace ? replaceSnapshot() : reduction(added, changed);
          }
          const seen = new Set<string>();
          const ordered: TranscriptEntry[] = [];
          for (const rawId of patch.orderIds) {
            if (typeof rawId !== "string" || seen.has(rawId)) continue;
            const entry = byId.get(rawId);
            if (entry === undefined) continue;
            seen.add(rawId);
            ordered.push(entry);
          }
          // Match Gemini's loader: retained ids omitted from orderIds keep their existing prefix
          // order, followed by the explicitly ordered ids. Bound both source history and wire cost.
          const prefix = entries.filter((entry) => !seen.has(entry.uuid));
          const next = [...prefix, ...ordered].slice(-ORDER_ID_MAX);
          if (next.some((entry, index) => entries[index]?.uuid !== entry.uuid) || next.length !== entries.length) {
            entries.splice(0, entries.length, ...next);
            byId.clear();
            for (const entry of entries) byId.set(entry.uuid, entry);
            needsReplace = true;
          }
        }
        if (needsReplace) return replaceSnapshot();
        return reduction(added, changed);
      }

      if (isRecord(row.$set) && Array.isArray(row.$set.messages)) {
        // Full-history replacement carries deletion and ordering; publish it as a new position set.
        entries.splice(0, entries.length);
        byId.clear();
        const replacement = row.$set.messages.slice(-ORDER_ID_MAX);
        for (const item of replacement) {
          if (!isRecord(item)) continue;
          unknown.row(item.type);
          insert(item, changed, added);
        }
        return replaceSnapshot();
      }

      if (insert(row, changed, added)) return replaceSnapshot();
      return reduction(added, changed);
    },
    unknowns: () => unknown.tally(),
    queued: noQueue,
  };
}

export function parseGeminiTranscript(text: string): TranscriptEntry[] {
  return parseWith(createGeminiReducer(), text);
}

async function firstSessionId(path: string): Promise<string | null> {
  try {
    const head = await Bun.file(path).slice(0, SESSION_HEAD_BYTES).text();
    const line = head.split("\n", 1)[0];
    if (line === undefined) return null;
    const row = object(JSON.parse(line));
    return row !== null && typeof row.sessionId === "string" ? row.sessionId : null;
  } catch {
    return null;
  }
}

class GeminiTranscriptSource implements TranscriptSource {
  private readonly roots: string[];
  private readonly paths = new Map<string, { path: string; root: string }>();

  constructor(roots: string | readonly string[]) {
    this.roots = rootList(roots);
  }

  async resolve(ref: AgentSessionRef): Promise<string | null> {
    if (ref.kind !== "id" || !isGeminiSessionId(ref.value)) return null;
    const sessionId = ref.value.toLowerCase();
    const cached = this.paths.get(sessionId);
    if (cached !== undefined) {
      const real = await containedRealpath(cached.path, cached.root);
      if (real !== null && (await firstSessionId(real))?.toLowerCase() === sessionId) return real;
      this.paths.delete(sessionId);
    }

    for (const root of this.roots) {
      const found = await this.findInRoot(root, sessionId);
      if (found !== null) {
        if (this.paths.size >= 4096) this.paths.delete(this.paths.keys().next().value ?? "");
        this.paths.set(sessionId, { path: found, root });
        return found;
      }
    }
    return null;
  }

  private async findInRoot(root: string, sessionId: string): Promise<string | null> {
    let visitedProjects = 0;
    let visitedEntries = 0;
    let chatsDirectories = 0;
    const shortId = sessionId.slice(0, 8);
    try {
      for await (const project of await opendir(root)) {
        if (!project.isDirectory()) continue;
        if (++visitedProjects > PROJECT_DIR_MAX) return null;
        const chats = join(root, project.name, "chats");
        let chatsDir;
        try {
          chatsDir = await opendir(chats);
        } catch {
          continue;
        }
        chatsDirectories++;
        if (chatsDirectories > PROJECT_DIR_MAX) {
          await chatsDir.close().catch(() => undefined);
          return null;
        }
        for await (const item of chatsDir) {
          if (++visitedEntries > CHAT_ENTRY_MAX) return null;
          if (item.isFile() && this.isCandidate(item.name, shortId)) {
            const candidate = join(chats, item.name);
            const real = await containedRealpath(candidate, root);
            if (real !== null && (await firstSessionId(real))?.toLowerCase() === sessionId) return real;
          } else if (item.isDirectory()) {
            // Gemini subagents are stored exactly one directory below chats, named by the parent
            // session UUID. Read only direct JSONL children; do not recurse into arbitrary trees.
            if (++visitedEntries > CHAT_ENTRY_MAX) return null;
            const nested = join(chats, item.name);
            let nestedDir;
            try {
              nestedDir = await opendir(nested);
            } catch {
              continue;
            }
            for await (const child of nestedDir) {
              if (++visitedEntries > CHAT_ENTRY_MAX) return null;
              if (!child.isFile() || child.name !== `${sessionId}.jsonl`) continue;
              const candidate = join(nested, child.name);
              const real = await containedRealpath(candidate, root);
              if (real !== null && (await firstSessionId(real))?.toLowerCase() === sessionId) return real;
            }
          }
        }
      }
    } catch {
      // Missing roots are normal when Gemini CLI has never run under that configuration.
      return null;
    }
    return null;
  }

  private isCandidate(name: string, shortId: string): boolean {
    return name.startsWith("session-") && name.endsWith(`-${shortId}.jsonl`);
  }

  stat = statFile;
  load = loadTail;
  readSince = readSinceFile;
}

export function geminiJournal(roots: string | readonly string[]): JournalAdapter {
  return {
    agent: "gemini",
    source: new GeminiTranscriptSource(roots),
    parse: parseGeminiTranscript,
    reducer: createGeminiReducer,
  };
}
