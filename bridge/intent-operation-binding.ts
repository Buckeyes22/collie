import type { JsonObject, JsonValue } from "./json.ts";
import { keyedIntentFingerprint } from "./worktree-receipts.ts";
import type { PlanBinding } from "./control/plans.ts";
import type { OperationStore } from "./control/operations.ts";
import { validBinding } from "./control/operations.ts";

export type IntentEffect = "launch" | "worktree.create" | "worktree.createAt" | "launcher.add";

export interface IntentOwnerContext {
  readonly key: Uint8Array;
  readonly deviceIdentity: string;
  /** Omitted only by legacy direct route fixtures, which do not enable the bound production contract. */
  readonly owningHost?: string;
  readonly runtime: string;
}

/** Owner-bound operations are a capability only when both local key custody and durable state work. */
export function ownerBoundOperationReady(key: Uint8Array | null | undefined, storeReady: boolean): boolean {
  return key !== null && key !== undefined && key.byteLength >= 32 && storeReady;
}

/** A local phone capability additionally needs the authenticated stable pairing identity. */
export function ownerBoundCallerReady(key: Uint8Array | null | undefined, storeReady: boolean, pairedIdentity: string | null): boolean {
  return ownerBoundOperationReady(key, storeReady) && pairedIdentity !== null && pairedIdentity.length > 0;
}

/**
 * Durable identity for one owner-bound effect. The authenticated pairing incarnation, owning host,
 * runtime, operation and route target are explicit fields; the canonical body (including requestId)
 * is HMAC-bound under the owner's protected host key. No display label or volatile lead epoch enters
 * this durable identity.
 */
export function intentOperationBinding(
  key: Uint8Array,
  deviceIdentity: string,
  owningHost: string,
  runtime: string,
  operation: IntentEffect,
  targetIdentity: string,
  body: JsonValue,
): PlanBinding {
  const payload: JsonObject = { operation, targetIdentity, body };
  const evidencePayload: JsonObject = {
    domain: "collie-operation-evidence-v1",
    deviceIdentity,
    owningHost,
    runtime,
    operation,
    targetIdentity,
  };
  const binding: PlanBinding = {
    device: deviceIdentity,
    host: owningHost,
    session: runtime,
    capability: operation,
    payloadHash: keyedIntentFingerprint(key, payload),
    targetIdentity,
    evidence: keyedIntentFingerprint(key, evidencePayload),
  };
  if (!validBinding(binding)) throw new Error("invalid intent operation binding");
  return binding;
}

/** A dispatched failure is ambiguous unless a caller has separately proven that no effect landed. */
export function dispatchedOutcome(ok: boolean): { state: "succeeded" } | { state: "unknown"; code: string } {
  return ok ? { state: "succeeded" } : { state: "unknown", code: "operation.outcome_unknown" };
}

/**
 * Reserve a frozen effect in the shared durable operation registry before dispatch. A matching old
 * registry row without a corresponding success receipt is never dispatched again: the response may
 * have been evicted, or the process may have died after the effect.
 */
export async function executeBoundIntent<T extends { readonly ok: boolean }>(args: {
  operations: OperationStore | undefined;
  requestId: string | undefined;
  context: IntentOwnerContext | null | undefined;
  operation: IntentEffect;
  targetIdentity: string;
  body: JsonValue;
  dispatch: () => Promise<T>;
  classify?: (value: T) => { readonly state: "succeeded" | "refused" | "unknown"; readonly code?: string };
  knownReceipt: () => T | undefined;
  unknown: () => T;
  unavailable: () => T;
  mismatch: () => T;
}): Promise<T> {
  // Missing request IDs and callers predating the receipt contract preserve their legacy route.
  if (args.requestId === undefined || args.context === undefined || (args.context !== null && args.context.owningHost === undefined)) return args.dispatch();
  if (args.context === null || args.operations === undefined) return args.unavailable();
  const owningHost = args.context.owningHost;
  if (owningHost === undefined) return args.dispatch();

  let binding: PlanBinding;
  try {
    binding = intentOperationBinding(
      args.context.key,
      args.context.deviceIdentity,
      owningHost,
      args.context.runtime,
      args.operation,
      args.targetIdentity,
      args.body,
    );
  } catch {
    return args.unavailable();
  }
  let dispatched: T | undefined;
  let view;
  try {
    view = await args.operations.execute(args.requestId, binding, null, async () => {
      dispatched = await args.dispatch();
      return args.classify?.(dispatched) ?? dispatchedOutcome(dispatched.ok);
    });
  } catch (error) {
    if (error instanceof Error && error.message === "operation.request_id_reused") return args.mismatch();
    return args.unavailable();
  }

  if (dispatched !== undefined) {
    if (view.state === "refused") return dispatched;
    return dispatched.ok && view.state === "succeeded" ? dispatched : args.unknown();
  }
  // A previous succeeded row is only answerable if the operation's success receipt is still
  // available. The registry intentionally stores no pane/terminal response body.
  const receipt = args.knownReceipt();
  if (receipt !== undefined) return receipt;
  if (view.state === "refused") return args.mismatch();
  // pending is recovered as unknown at restart; every terminal row without a response body is
  // equally non-replayable. Never turn it into a second effect.
  void view;
  return args.unknown();
}
