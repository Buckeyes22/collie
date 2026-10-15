import { join } from "node:path";

import type { JsonObject, JsonValue } from "./json.ts";
import type { CreateResponse } from "./types.ts";
import { isIntentReceiptBinding, isRequestId, memoryReceipts, ReceiptFileStore, type IntentReceiptBinding, type ReceiptSurface } from "./worktree-receipts.ts";

// ── One receipt per launch request, so a retried Start never opens a second pane (ADR 0091) ──────
//
// The worktree create's pattern (ADR 0089, `worktree-receipts.ts`), for `POST /api/launch`: this
// bounded file keeps recent success response bodies for convenient replay. The shared durable
// OperationStore reserves owner-bound intent tombstones before an effect and does not evict them. If
// an old response body is gone, a matching retry answers unknown and never opens another pane.
// A launch still in flight is joined. A known pre-effect refusal may still be corrected and retried.
//
// Ids, the pane and space it made and the folder. No command line. Written on a launch with an id
// and on nothing else, so a bridge nobody launches on writes no file (solo-baseline.test.ts).

/** The receipt file's name inside the state dir. */
export const LAUNCH_RECEIPTS_FILE = "launch-receipts.json";

/** What one successful launch left behind. */
export interface LaunchReceipt {
  requestId: string;
  /** Epoch ms of the launch. */
  at: number;
  workspaceId: string;
  workspaceLabel: string;
  paneId: string;
  tabId: string;
  /** The folder the new pane opened in, as the multiplexer reported it. */
  cwd: string;
  /** Absent on pre-binding receipts; they cannot promise owner-bound replay. */
  binding?: IntentReceiptBinding;
}

/** What the launch route needs of the store. */
export type LaunchReceiptSurface = ReceiptSurface<LaunchReceipt, CreateResponse>;

function asRecord(value: JsonValue): JsonObject | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
}

/** One receipt read off disk, or `null` when the entry is not one. */
export function coerceLaunchReceipt(raw: JsonValue): LaunchReceipt | null {
  const r = asRecord(raw);
  if (r === null) return null;
  const { requestId, at, workspaceId, workspaceLabel, paneId, tabId, cwd, binding } = r;
  if (!isRequestId(requestId)) return null;
  if (typeof at !== "number" || !Number.isFinite(at)) return null;
  if (typeof workspaceId !== "string" || typeof workspaceLabel !== "string") return null;
  if (typeof paneId !== "string" || typeof tabId !== "string" || typeof cwd !== "string") return null;
  if (binding !== undefined && !isIntentReceiptBinding(binding)) return null;
  const receipt: LaunchReceipt = { requestId, at, workspaceId, workspaceLabel, paneId, tabId, cwd };
  if (binding !== undefined) receipt.binding = binding;
  return receipt;
}

/** The answer a replay gives: the pane the first request made. */
export function launchReplay(receipt: LaunchReceipt): CreateResponse {
  return {
    ok: true,
    replayed: true,
    pane: {
      paneId: receipt.paneId,
      workspaceId: receipt.workspaceId,
      workspaceLabel: receipt.workspaceLabel,
      tabId: receipt.tabId,
      cwd: receipt.cwd,
    },
  };
}

/** The file-backed store `bridge/index.ts` builds once per bridge. */
export class LaunchReceiptStore extends ReceiptFileStore<LaunchReceipt, CreateResponse> {
  constructor(stateDir: string, warn: (line: string) => void = (line) => console.warn(line)) {
    super(stateDir, join(stateDir, LAUNCH_RECEIPTS_FILE), coerceLaunchReceipt, warn, "launch");
  }
}

/** A store with no file, for a server built without one. */
export function memoryLaunchReceipts(): LaunchReceiptSurface {
  return memoryReceipts<LaunchReceipt, CreateResponse>();
}
