import type { JsonObject, JsonValue } from "../json.ts";

export interface HostOperationRequest {
  readonly requestId: string;
  readonly planToken: string;
  readonly confirmed: true;
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
