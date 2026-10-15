import type { JsonObject, JsonValue } from "../json.ts";

export interface HostOperationRequest {
  readonly requestId: string;
  readonly planToken: string;
  readonly confirmed: true;
}

/** B32 uses the same closed JSON parse boundary for host plugin HTTP bodies. */
export function jsonObject(value: JsonValue | null | undefined): JsonObject | null {
  return value !== null && value !== undefined && typeof value === "object" && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype ? value : null;
}
export function jsonString(value: JsonValue | undefined): value is string { return typeof value === "string"; }
export function jsonBoolean(value: JsonValue | undefined): value is boolean { return typeof value === "boolean"; }
export function jsonNumber(value: JsonValue | undefined): value is number { return typeof value === "number" && Number.isFinite(value); }
export function hasControlCharacters(value: string): boolean { return Array.from(value).some((character) => { const code = character.codePointAt(0) ?? 0; return code < 0x20 || code === 0x7f; }); }
const SECRET_KEY = /(?:secret|token|password|credential|api[_-]?key|private[_-]?key|authorization|auth[_-]?header)/iu;
export const PLUGIN_CONFIG_REDACTED = "__COLLIE_REDACTED_SECRET__";
function redactJsonValue(value: JsonValue): JsonValue {
  if (Array.isArray(value)) return value.map(redactJsonValue);
  const row = jsonObject(value);
  if (row) {
    const redacted: JsonObject = {};
    for (const [key, child] of Object.entries(row)) {
      if (child === undefined) continue;
      Object.defineProperty(redacted, key, { value: SECRET_KEY.test(key) ? PLUGIN_CONFIG_REDACTED : redactJsonValue(child), enumerable: true, writable: true, configurable: true });
    }
    return redacted;
  }
  return value;
}
function restoreJsonSecrets(proposed: JsonValue, current: JsonValue): JsonValue {
  if (Array.isArray(proposed)) return proposed.map((value, index) => restoreJsonSecrets(value, Array.isArray(current) ? current[index] ?? null : null));
  const row = jsonObject(proposed);
  if (row) {
    const old = jsonObject(current) ?? {};
    const result: JsonObject = {};
    for (const [key, value] of Object.entries(row)) {
      if (value === undefined) continue;
      const restored = SECRET_KEY.test(key) && value === PLUGIN_CONFIG_REDACTED && Object.hasOwn(old, key)
        ? old[key] : restoreJsonSecrets(value, Object.hasOwn(old, key) ? old[key] ?? null : null);
      Object.defineProperty(result, key, { value: restored, enumerable: true, writable: true, configurable: true });
    }
    return result;
  }
  return proposed;
}
export function redactPluginConfigDocument(content: string): string {
  try {
    // SAFETY: JSON.parse returns only JSON primitives, arrays, and plain objects; the recursive redactor preserves that closed shape.
    const value = JSON.parse(content) as JsonValue;
    return JSON.stringify(redactJsonValue(value));
  } catch { return "[unavailable]"; }
}
export function restorePluginConfigDocument(proposedContent: string, currentContent: string): string {
  try {
    // SAFETY: Both strings are validated JSON documents at the filesystem plan boundary, so parsing yields the closed JSON union.
    const proposed = JSON.parse(proposedContent) as JsonValue;
    // SAFETY: The current document is validated by the same filesystem boundary immediately before this restoration pass.
    const current = JSON.parse(currentContent) as JsonValue;
    return JSON.stringify(restoreJsonSecrets(proposed, current));
  } catch { return proposedContent; }
}
export function pluginPlacement(value: JsonValue | undefined): import("./types.ts").HostPluginPlacement | null {
  if (!jsonString(value) || !["overlay", "popup", "split", "tab", "zoomed"].includes(value)) return null;
  // SAFETY: membership in the closed plugin placement vocabulary above establishes the target union.
  return value as import("./types.ts").HostPluginPlacement;
}

/** The request body has already been size-bounded by the caller; decode its closed write schema. */
export function parseHostOperationRequest(value: JsonValue | null): HostOperationRequest | null {
  if (value === null || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) return null;
  // SAFETY: JSON.parse produced this object; exact own keys and each field's domain are checked below.
  const row = value as JsonObject;
  if (Object.keys(row).length !== 3 || !Object.keys(row).every((key) => key === "requestId" || key === "planToken" || key === "confirmed")) return null;
  const requestId = readString(row.requestId);
  const planToken = readString(row.planToken);
  if (requestId === null || !/^[A-Za-z0-9_-]{1,128}$/u.test(requestId) || planToken === null || planToken.length > 128 || row.confirmed !== true) return null;
  return { requestId, planToken, confirmed: true };
}

function readString(value: JsonValue | undefined): string | null {
  if (typeof value !== "string") return null;
  return value;
}
