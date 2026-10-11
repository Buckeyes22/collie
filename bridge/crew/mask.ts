import {
  maskChatBody,
  maskDiff,
  maskFileBody,
  maskHistoryPage,
  maskPaneRead,
} from "../answer-mask.ts";
import { redactText } from "../redact.ts";
import { MAX_PROCESS_INFO_BODY_BYTES, MAX_PROCESS_INFO_TEXT_CHARS } from "../process-info-limits.ts";
import type { JsonObject, JsonValue } from "../json.ts";
import type { TranscriptEntry } from "../journal/types.ts";
import type { ChatBody } from "../journal/live.ts";
import type { ChangeDiff, FileReadAnswer, PaneReadResponse } from "../types.ts";
import type { AnswerMask, TextAnswer } from "./forward.ts";

// The LEAD's mask over a member's text answer (CREW_PROTOCOL.md §9.1). The member's body is parsed,
// checked for the shape its route answers with, and masked by the very function the lead's own route
// uses for the same answer (`bridge/answer-mask.ts`). Nothing here masks by a rule of its own.
//
// ── FAIL CLOSED, BY SHAPE ────────────────────────────────────────────────────
// `null` means "do not pass this on": the body is not JSON, or it is not the answer the route makes.
// The check is exactly as deep as the mask needs, no deeper: every field the mask walks must be the
// type it walks, because a field of another type would be passed over rather than masked. Turns are
// walked whole by `redactEntry` (every string in a part but a label the bridge wrote), so a turn needs
// only its `parts` list and an image part its `url` string. A field the mask does not touch on the
// lead's own route (a cursor, a count, a root, a commit list) is not checked and not touched here
// either, so a member one release ahead with a new field still answers.
//
// ── NO CHANGE, NO NEW BYTES ──────────────────────────────────────────────────
// An answer with nothing to mask (`available: false`, a listing, a commit list) goes back as the
// member's own bytes, unparsed past the shape check. A masked answer is re-serialised with
// `JSON.stringify`, the member's own serialiser, so a body a 1.18 member already masked comes back
// byte for byte: parse then stringify is a fixed point on stringify's output, and the mask is a fixed
// point on its own output.

/** A member's text answer, masked, or `null` when it cannot be read as that answer. */
export const maskForwardedAnswer: AnswerMask = (answer: TextAnswer, raw: string, expectedPaneId?: string): string | null => {
  let body: JsonValue;
  try {
    // SAFETY: `JSON.parse` returns a JSON value by definition; `JsonValue` is the name for that type.
    body = JSON.parse(raw) as JsonValue;
  } catch {
    return null;
  }
  if (!isObject(body)) return null;
  try {
    return maskBody(answer, body, raw, expectedPaneId);
  } catch {
    // The checks above the mask cover every field it reads; a throw past them is a shape nobody
    // planned for, and it is refused like any other.
    return null;
  }
};

function maskBody(answer: TextAnswer, body: JsonObject, raw: string, expectedPaneId?: string): string | null {
  switch (answer) {
    case "pane":
      return isPaneRead(body) ? JSON.stringify(maskPaneRead(body)) : null;
    case "history":
      if (body.available === false) return raw;
      return body.available === true && isHistoryPage(body)
        ? JSON.stringify(maskHistoryPage(body))
        : null;
    case "chat":
      if (body.available === false) return raw;
      return body.available === true && isChatBody(body) ? JSON.stringify(maskChatBody(body)) : null;
    case "changes":
      // A list and a commit carry no diff, and the lead's own route masks neither.
      if (!("diff" in body)) return raw;
      return isDiff(body) ? JSON.stringify(maskDiff(body, true)) : null;
    case "files":
      // A listing is names only; the lead's own route masks a file's text alone.
      if (!("text" in body)) return raw;
      return isFileText(body) ? JSON.stringify(maskFileBody(body, true)) : null;
    case "processes":
      return maskedProcessInfo(body, expectedPaneId);
  }
}

function maskedProcessInfo(body: JsonObject, expectedPaneId?: string): string | null {
  const allowed = new Set(["ok", "paneId", "truncated", "shellPid", "foregroundProcessGroupId", "tty", "foregroundCwd", "restoreError", "processes"]);
  if (Object.keys(body).some((key) => !allowed.has(key)) || body.ok !== true || typeof body.paneId !== "string" ||
    body.paneId.length === 0 || body.paneId !== expectedPaneId || typeof body.truncated !== "boolean" ||
    !Array.isArray(body.processes) || body.processes.length > 64) return null;
  if (!optionalText(body.tty, 4096) || !optionalText(body.foregroundCwd, 4096) || !optionalText(body.restoreError, 4096) ||
    !optionalPid(body.shellPid) || !optionalPid(body.foregroundProcessGroupId)) return null;
  let aggregateChars = 0;
  const count = (value: JsonValue | undefined): boolean => {
    if (value === null || value === undefined) return true;
    if (typeof value !== "string") return false;
    aggregateChars += value.length;
    return aggregateChars <= MAX_PROCESS_INFO_TEXT_CHARS;
  };
  if (!count(body.tty) || !count(body.foregroundCwd) || !count(body.restoreError)) return null;
  for (const value of body.processes) {
    if (!isObject(value)) return null;
    const keys = new Set(["pid", "name", "argv", "argv0", "cmdline", "cwd"]);
    if (Object.keys(value).some((key) => !keys.has(key)) || typeof value.pid !== "number" ||
      !Number.isSafeInteger(value.pid) || value.pid < 0 || typeof value.name !== "string" || value.name.length > 256 ||
      !optionalText(value.argv0, 2048) || !optionalText(value.cmdline, 8192) || !optionalText(value.cwd, 4096)) return null;
    if (!count(value.name) || !count(value.argv0) || !count(value.cmdline) || !count(value.cwd)) return null;
    if (value.argv !== undefined) {
      if (!Array.isArray(value.argv) || value.argv.length > 64 || !value.argv.every((arg) => typeof arg === "string" && arg.length <= 2048)) return null;
      for (const arg of value.argv) if (typeof arg !== "string" || !count(arg)) return null;
    }
  }
  const processes: JsonValue[] = [];
  for (const value of body.processes) {
    if (!isObject(value)) return null;
    const keys = new Set(["pid", "name", "argv", "argv0", "cmdline", "cwd"]);
    if (Object.keys(value).some((key) => !keys.has(key)) || typeof value.pid !== "number" ||
      !Number.isSafeInteger(value.pid) || value.pid < 0 || typeof value.name !== "string" || value.name.length > 256 ||
      !optionalText(value.argv0, 2048) || !optionalText(value.cmdline, 8192) || !optionalText(value.cwd, 4096)) return null;
    const process: JsonObject = { pid: value.pid, name: redactText(value.name) };
    if (value.argv !== undefined) {
      if (!Array.isArray(value.argv) || value.argv.length > 64 || !value.argv.every((arg) => typeof arg === "string" && arg.length <= 2048)) return null;
      process.argv = value.argv.map((arg) => {
        if (typeof arg !== "string") throw new Error("invalid process argv");
        return redactText(arg);
      });
    }
    if (typeof value.argv0 === "string") process.argv0 = redactText(value.argv0);
    if (typeof value.cmdline === "string") process.cmdline = redactText(value.cmdline);
    if (typeof value.cwd === "string") process.cwd = redactText(value.cwd);
    processes.push(process);
  }
  const masked: JsonObject = { ok: true, paneId: body.paneId, truncated: body.truncated, processes };
  if (typeof body.shellPid === "number") masked.shellPid = body.shellPid;
  if (typeof body.foregroundProcessGroupId === "number") masked.foregroundProcessGroupId = body.foregroundProcessGroupId;
  if (typeof body.tty === "string") masked.tty = redactText(body.tty);
  if (typeof body.foregroundCwd === "string") masked.foregroundCwd = redactText(body.foregroundCwd);
  if (typeof body.restoreError === "string") masked.restoreError = redactText(body.restoreError);
  const serialized = JSON.stringify(masked);
  return new TextEncoder().encode(serialized).byteLength <= MAX_PROCESS_INFO_BODY_BYTES ? serialized : null;
}

function optionalText(value: JsonValue | undefined, max: number): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.length <= max);
}

function optionalPid(value: JsonValue | undefined): boolean {
  return value === undefined || value === null || (typeof value === "number" && Number.isSafeInteger(value) && value >= 0);
}

function isObject(value: JsonValue | undefined): value is JsonObject {
  return value !== null && value !== undefined && typeof value === "object" && !Array.isArray(value);
}

function isPaneRead(body: JsonObject): body is JsonObject & PaneReadResponse {
  return typeof body.text === "string" && (body.logicalText === undefined || typeof body.logicalText === "string");
}

/** A turn as `redactEntry` walks it: a `parts` list of objects, an image part with its `url` a string. */
function isEntry(value: JsonValue | undefined): value is JsonObject & TranscriptEntry {
  if (!isObject(value) || !Array.isArray(value.parts)) return false;
  return value.parts.every((part) => isObject(part) && (part.kind !== "image" || typeof part.url === "string"));
}

function isEntryList(value: JsonValue | undefined): value is (JsonObject & TranscriptEntry)[] {
  return Array.isArray(value) && value.every(isEntry);
}

function isHistoryPage(body: JsonObject): body is JsonObject & { entries: TranscriptEntry[] } {
  return isEntryList(body.entries);
}

function isChatBody(body: JsonObject): body is JsonObject & ChatBody {
  if (!isEntryList(body.upserts)) return false;
  // An older page carries no queue; a live window always does, as a list of strings.
  if (body.page === "older") return true;
  return body.page === "live" && Array.isArray(body.queued) && body.queued.every((q) => typeof q === "string");
}

function isDiff(body: JsonObject): body is JsonObject & Extract<ChangeDiff, { available: true }> {
  return body.available === true && typeof body.diff === "string";
}

function isFileText(body: JsonObject): body is JsonObject & Extract<FileReadAnswer, { available: true }> {
  return body.available === true && typeof body.text === "string";
}
