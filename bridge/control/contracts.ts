import { createHash } from "node:crypto";
import type { JsonValue } from "../json.ts";
import type { GateLevel } from "../crew/peer-gate.ts";

/** Closed HTTP vocabulary. Feature owners add concrete entries; no vendor RPC crosses this seam. */
export const CONTROL_ROUTES = [
  { name: "paneProcesses", method: "GET", pattern: /^\/api\/pane\/[^/]+\/processes$/, gate: "device-read", scope: "session", capability: "paneProcesses", maxBodyBytes: 0 },
  { name: "refresh", method: "POST", pattern: /^\/api\/refresh$/, gate: "read", scope: "session", capability: null, maxBodyBytes: 0 },
  { name: "operation", method: "GET", pattern: /^\/api\/control\/operations\/([a-zA-Z0-9_-]{1,128})$/, gate: "read", scope: "session", capability: "controlOperations", maxBodyBytes: 0 },
  { name: "pane-layout", method: "GET", pattern: /^\/api\/pane\/([^/]{1,256})\/layout$/, gate: "read", scope: "session", capability: "paneLayout", maxBodyBytes: 0 },
  { name: "pane-neighbor", method: "GET", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/neighbor$/, gate: "read", scope: "session", capability: "paneLayout", maxBodyBytes: 0 },
  { name: "pane-edges", method: "GET", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/edges$/, gate: "read", scope: "session", capability: "paneLayout", maxBodyBytes: 0 },
  { name: "pane-layout-plan", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/plan$/, gate: "read", scope: "session", capability: "paneLayout", maxBodyBytes: 16 * 1024 },
  { name: "tab-transfer-plan", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/transfer\/plan$/, gate: "read", scope: "session", capability: "tabTransfer", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-split", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/split$/, gate: "write", scope: "session", capability: "paneSplit", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-resize", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/resize$/, gate: "write", scope: "session", capability: "paneResize", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-zoom", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/zoom$/, gate: "write", scope: "session", capability: "paneZoom", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-swap", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/swap$/, gate: "write", scope: "session", capability: "paneSwap", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-move", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/move$/, gate: "write", scope: "session", capability: "paneMove", maxBodyBytes: 16 * 1024 },
  { name: "pane-layout-focus-direction", method: "POST", pattern: /^\/api\/pane\/([^/]{1,256})\/layout\/focus-direction$/, gate: "write", scope: "session", capability: "paneDirectionalFocus", maxBodyBytes: 16 * 1024 },
  { name: "workspace-rename", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/rename$/, gate: "write", scope: "session", capability: "workspaceRename", maxBodyBytes: 8 * 1024 },
  { name: "workspace-close-plan", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/close\/plan$/, gate: "read", scope: "session", capability: "workspaceClose", maxBodyBytes: 8 * 1024 },
  { name: "workspace-rename-plan", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/rename\/plan$/, gate: "read", scope: "session", capability: "workspaceRename", maxBodyBytes: 8 * 1024 },
  { name: "workspace-close", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/close$/, gate: "write", scope: "session", capability: "workspaceClose", maxBodyBytes: 8 * 1024 },
  { name: "workspace-move-plan", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/move\/plan$/, gate: "read", scope: "session", capability: "workspaceMove", maxBodyBytes: 8 * 1024 },
  { name: "workspace-move", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/move$/, gate: "write", scope: "session", capability: "workspaceMove", maxBodyBytes: 8 * 1024 },
  { name: "workspace-reorder-plan", method: "POST", pattern: /^\/api\/workspaces\/reorder\/plan$/, gate: "read", scope: "session", capability: "workspaceMoveBlock", maxBodyBytes: 8 * 1024 },
  { name: "workspace-reorder", method: "POST", pattern: /^\/api\/workspaces\/reorder$/, gate: "write", scope: "session", capability: "workspaceMoveBlock", maxBodyBytes: 8 * 1024 },
  { name: "workspace-focus-plan", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/focus\/plan$/, gate: "read", scope: "session", capability: "workspaceFocus", maxBodyBytes: 8 * 1024 },
  { name: "workspace-focus", method: "POST", pattern: /^\/api\/workspace\/([^/]{1,256})\/focus$/, gate: "write", scope: "session", capability: "workspaceFocus", maxBodyBytes: 8 * 1024 },
  { name: "tab-move", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/move$/, gate: "write", scope: "session", capability: "tabMove", maxBodyBytes: 8 * 1024 },
  { name: "tab-move-plan", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/move\/plan$/, gate: "read", scope: "session", capability: "tabMove", maxBodyBytes: 8 * 1024 },
  { name: "tab-focus-plan", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/focus\/plan$/, gate: "read", scope: "session", capability: "tabFocus", maxBodyBytes: 8 * 1024 },
  { name: "tab-rename-plan", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/rename\/plan$/, gate: "read", scope: "session", capability: "renameTab", maxBodyBytes: 8 * 1024 },
  { name: "tab-close-plan", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/close\/plan$/, gate: "read", scope: "session", capability: "closeTab", maxBodyBytes: 8 * 1024 },
  { name: "tab-rename", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/rename$/, gate: "write", scope: "session", capability: "renameTab", maxBodyBytes: 8 * 1024 },
  { name: "tab-close", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/close$/, gate: "write", scope: "session", capability: "closeTab", maxBodyBytes: 8 * 1024 },
  { name: "tab-focus", method: "POST", pattern: /^\/api\/tab\/([^/]{1,256})\/focus$/, gate: "write", scope: "session", capability: "tabFocus", maxBodyBytes: 8 * 1024 },
] as const;
export type ControlRoute = (typeof CONTROL_ROUTES)[number];
export type ControlScope = { readonly host: string | null; readonly session: string | null };
export type FailureSource = "collie" | "gateway" | "unknown";
export type FailureFactor = "pairing" | "identity" | "device" | "scope" | "capability" | "operation" | "unknown";
export interface ControlRefusal {
  readonly ok: false;
  readonly code: string;
  readonly error: string;
  readonly source: FailureSource;
  readonly factor: FailureFactor;
}
export const CONTROL_LIMITS = { bodyBytes: 64 * 1024, identityChars: 256, planTtlMs: 60_000, receiptCount: 2048 } as const;

export function controlRoute(path: string, method: string): ControlRoute | null {
  return CONTROL_ROUTES.find((route) => route.method === method && route.pattern.test(path)) ?? null;
}
export function controlGate(path: string, method: string): GateLevel | null {
  return controlRoute(path, method)?.gate ?? null;
}
/** Reject duplicated/irrelevant selectors before resolving any host or runtime. */
export function controlScope(url: URL, kind: "session" | "host"): ControlScope | null {
  if (url.searchParams.has("sessions") || (kind === "host" && url.searchParams.has("session"))) return null;
  for (const name of ["host", "session"]) {
    const values = url.searchParams.getAll(name);
    if (values.length > 1 || values.some((v) => !v.trim() || v.length > CONTROL_LIMITS.identityChars || Array.from(v).some((char) => char.charCodeAt(0) < 32))) return null;
  }
  return { host: url.searchParams.get("host"), session: url.searchParams.get("session") };
}
export function supportsNew(capabilities: Readonly<Record<string, boolean>> | null | undefined, name: string): boolean {
  return capabilities?.[name] === true;
}
/** Canonical JSON; key insertion order cannot turn one intent into a different intent. */
export function canonicalPayload(value: JsonValue): string {
  if (Array.isArray(value)) return `[${value.map(canonicalPayload).join(",")}]`;
  // Canonical JSON dispatch is itself the parser boundary for this recursive union.
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).toSorted().map((key) => `${JSON.stringify(key)}:${canonicalPayload(value[key] ?? null)}`).join(",")}}`;
  }
  // Reject invalid JSON numbers at the hashing boundary.
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (typeof value === "number" && !Number.isFinite(value)) throw new Error("non-finite payload");
  return JSON.stringify(value);
}
export function payloadHash(value: JsonValue): string {
  return createHash("sha256").update(canonicalPayload(value)).digest("hex");
}
export function refusal(code: string, error: string, factor: FailureFactor): ControlRefusal {
  return { ok: false, code, error, source: "collie", factor };
}
export function refusalResponse(value: ControlRefusal, status: number): Response {
  return Response.json(value, { status, headers: { "cache-control": "no-store", "x-collie-error-source": value.source, "x-collie-error-factor": value.factor, "x-collie-error-code": value.code } });
}
