// Prepared wire contract only. No provider, registration route or delivery service is enabled.
import type { JsonObject, JsonValue } from "./json.ts";
import { jsonNumberField, jsonRecord, jsonStringField } from "./stt/json.ts";
export interface NativePushTarget {
  screen: "home" | "pane" | "updates";
  paneId?: string;
  host?: string;
  session?: string;
}
export interface NativePushPayload {
  schemaVersion: 1;
  registrationId: string;
  slot: string;
  sequence: number;
  expiresAt: number;
  kind: "blocked" | "done" | "update" | "clear";
  title?: string;
  body?: string;
  /** Omission means false. Decoders normalize display messages to an explicit boolean. */
  renotify?: boolean;
  target?: NativePushTarget;
}
export const MAX_NATIVE_PUSH_TTL_MS = 24 * 60 * 60 * 1000;

/** Providers normalize outcomes without returning endpoint credentials or raw response bodies. */
export type NativePushDelivery =
  | { kind: "accepted" }
  | { kind: "retired" }
  | { kind: "retry"; retryAfterMs?: number };

/** Destination is the selected provider's validated, server-private address type. */
export interface NativePushProvider<Destination> {
  deliver(destination: Destination, payload: NativePushPayload): Promise<NativePushDelivery>;
}

/** Stored server-side only; registrationId is a generation identifier, never an auth credential. */
export interface NativePushRegistration<Destination> {
  registrationId: string;
  pairedDeviceId: string;
  installationId: string;
  destination: Destination;
}

function text(value: JsonValue | undefined, limit: number): value is string {
  const s = jsonStringField(value);
  return s !== null && s.trim().length > 0 && s.length <= limit && [...s].every((c) => c.charCodeAt(0) >= 32 && c.charCodeAt(0) !== 127);
}
function identifier(value: JsonValue | undefined): value is string {
  const s = jsonStringField(value);
  return s !== null && /^[A-Za-z0-9_-]{1,128}$/.test(s);
}
function allowedKeys(value: JsonObject, keys: string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key));
}

/** The provider must first authenticate delivery and bind it to a locally stored registration.
 * This decoder rejects expired, duplicate and out-of-order slot updates; it grants no authority. */
export function decodeNativePush(
  raw: string, registrationId: string, nowMs: number, lastSequence = 0,
): NativePushPayload | null {
  if (Buffer.byteLength(raw, "utf8") > 4096 || !identifier(registrationId) ||
      !Number.isSafeInteger(nowMs) || nowMs < 0 || !Number.isSafeInteger(lastSequence) || lastSequence < 0) return null;
  let document: JsonValue;
  try {
    // SAFETY: JSON.parse can only produce a JsonValue; field readers validate its domain below.
    document = JSON.parse(raw) as JsonValue;
  } catch { return null; }
  const p = jsonRecord(document);
  if (!p || !allowedKeys(p, ["schemaVersion", "registrationId", "slot", "sequence", "expiresAt", "kind", "title", "body", "renotify", "target"])) return null;
  const sequence = jsonNumberField(p.sequence);
  const expiresAt = jsonNumberField(p.expiresAt);
  const kind = jsonStringField(p.kind);
  if (p.schemaVersion !== 1 || p.registrationId !== registrationId || !identifier(p.slot) ||
      sequence === null || !Number.isSafeInteger(sequence) || sequence <= lastSequence ||
      expiresAt === null || !Number.isSafeInteger(expiresAt) || expiresAt <= nowMs || expiresAt - nowMs > MAX_NATIVE_PUSH_TTL_MS) return null;
  if (kind === "clear") {
    if (["title", "body", "renotify", "target"].some((key) => key in p)) return null;
    return { schemaVersion: 1, registrationId, slot: p.slot, sequence, expiresAt, kind };
  }
  if ((kind !== "blocked" && kind !== "done" && kind !== "update") || !text(p.title, 160) || !text(p.body, 512) ||
      ("renotify" in p && p.renotify !== true && p.renotify !== false)) return null;
  const t = jsonRecord(p.target);
  if (!t || !allowedKeys(t, ["screen", "paneId", "host", "session"])) return null;
  let target: NativePushTarget;
  if (kind === "update") {
    if (t.screen !== "updates" || Object.keys(t).length !== 1) return null;
    target = { screen: "updates" };
  } else {
    if (t.screen !== "home" && t.screen !== "pane") return null;
    if (("host" in t && !text(t.host, 256)) || ("session" in t && !text(t.session, 256))) return null;
    const host = jsonStringField(t.host) ?? undefined;
    const session = jsonStringField(t.session) ?? undefined;
    if (t.screen === "pane") {
      if (!text(t.paneId, 256)) return null;
      target = { screen: "pane", paneId: t.paneId, host, session };
    } else {
      if ("paneId" in t) return null;
      target = { screen: "home", host, session };
    }
  }
  return { schemaVersion: 1, registrationId, slot: p.slot, sequence, expiresAt, kind, title: p.title,
    body: p.body, renotify: p.renotify === true, target };
}
