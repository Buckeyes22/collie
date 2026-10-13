import type { JsonValue } from "../json.ts";

/** The exact owner-side identity carried by the existing pane keys route. */
export interface GuardedQueuedSendIdentity {
  generation: number;
  revision: number;
  queueDigest: string;
  occupant: string;
}

const DIGEST = /^[a-f0-9]{64}$/;
const IDENTITY_KEYS = ["generation", "revision", "queue_digest", "occupant"] as const;

/** Closed JSON grammar: no queue text, free-form recipe, or unknown future field is accepted. */
export function parseGuardedQueuedSend(value: JsonValue | undefined): GuardedQueuedSendIdentity | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const record = value;
  if (Object.keys(record).length !== IDENTITY_KEYS.length || IDENTITY_KEYS.some((key) => !Object.hasOwn(record, key))) {
    return null;
  }
  const { generation, revision, queue_digest: queueDigest, occupant } = record;
  if (
    typeof generation !== "number" || !Number.isSafeInteger(generation) || generation < 0 ||
    typeof revision !== "number" || !Number.isSafeInteger(revision) || revision < 0 ||
    typeof queueDigest !== "string" || !DIGEST.test(queueDigest) ||
    typeof occupant !== "string" || !DIGEST.test(occupant)
  ) return null;
  return { generation, revision, queueDigest, occupant };
}

export type QueuedSendAdmission = "admitted" | "busy" | "already-attempted" | "full";

/**
 * Process-local duplicate guard. It deliberately is not a durable server receipt: the adapter's
 * `sendKeys` port has no atomic occupant/queue precondition. The native encrypted action latch
 * survives phone process death; server restart dispatch-once is not promised here.
 */
export class QueuedSendAdmissionLedger {
  private readonly active = new Map<string, string>();
  private readonly activeOwners = new Map<string, string>();
  private readonly attempted = new Map<string, { owner: string; outcome: string }>();

  constructor(private readonly maxAttempts = 4096) {}

  begin(action: string, ownerPane = action): QueuedSendAdmission {
    if (this.activeOwners.has(ownerPane)) return "busy";
    if (this.active.has(action)) return "busy";
    if (this.attempted.has(action)) return "already-attempted";
    if (this.attempted.size >= this.maxAttempts) return "full";
    this.active.set(action, ownerPane);
    this.activeOwners.set(ownerPane, action);
    return "admitted";
  }

  /** Call synchronously before invoking the adapter; after this point every outcome remains latched. */
  retireBeforeDispatch(action: string): boolean {
    const owner = this.active.get(action);
    if (owner === undefined || this.attempted.size >= this.maxAttempts) return false;
    this.attempted.set(action, { owner, outcome: "outcome_unknown" });
    this.active.delete(action);
    if (this.activeOwners.get(owner) === action) this.activeOwners.delete(owner);
    return true;
  }

  /** A pre-dispatch refusal can release the reservation because no adapter write was attempted. */
  releaseBeforeDispatch(action: string): void {
    if (!this.attempted.has(action)) {
      const owner = this.active.get(action);
      this.active.delete(action);
      if (owner !== undefined && this.activeOwners.get(owner) === action) this.activeOwners.delete(owner);
    }
  }

  /** Authoritative newer queue/occupant evidence retires prior identities for exactly this owner pane. */
  observe(ownerPane: string, currentAction: string): void {
    for (const [action, record] of this.attempted) {
      if (record.owner === ownerPane && action !== currentAction) this.attempted.delete(action);
    }
  }

  outcome(action: string): string | undefined {
    return this.attempted.get(action)?.outcome;
  }

  size(): number {
    return this.attempted.size;
  }
}

/** Length-delimited opaque keys avoid separator ambiguities without retaining source identifiers. */
export function queuedSendActionKey(fields: readonly string[]): string {
  return fields.map((field) => `${field.length}:${field}`).join("|");
}
