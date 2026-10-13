import { randomUUID } from "node:crypto";
import { validBinding, type OperationStore, type OperationView } from "./operations.ts";
import { CONTROL_LIMITS } from "./contracts.ts";

export interface PlanBinding {
  readonly device: string;
  readonly host: string;
  readonly session: string | null;
  readonly capability: string;
  readonly payloadHash: string;
  readonly targetIdentity: string;
  /** Authoritative current state fingerprint, including occupant identity where relevant. */
  readonly evidence: string;
}
export interface ControlPlan { readonly token: string; readonly expiresAt: number; readonly binding: PlanBinding }
export function sameBinding(a: PlanBinding, b: PlanBinding): boolean {
  return a.device === b.device && a.host === b.host && a.session === b.session && a.capability === b.capability && a.payloadHash === b.payloadHash && a.targetIdentity === b.targetIdentity && a.evidence === b.evidence;
}
/** Opaque server-owned tokens. A plan is evidence to revalidate, never a reusable grant. */
export class PlanStore {
  private readonly plans = new Map<string, ControlPlan>();
  constructor(private readonly now: () => number = Date.now) {}
  issue(binding: PlanBinding, ttlMs: number = CONTROL_LIMITS.planTtlMs): ControlPlan {
    if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0 || ttlMs > CONTROL_LIMITS.planTtlMs || !validBinding(binding)) throw new Error("invalid plan lifetime");
    for (const [token, plan] of this.plans) if (plan.expiresAt <= this.now()) this.plans.delete(token);
    if (this.plans.size >= CONTROL_LIMITS.receiptCount) throw new Error("plan capacity reached");
    const plan = { token: randomUUID(), expiresAt: this.now() + ttlMs, binding: { ...binding } };
    this.plans.set(plan.token, plan);
    return plan;
  }
  validate(token: string, current: PlanBinding): "valid" | "expired" | "changed" {
    const plan = this.plans.get(token);
    if (!plan || plan.expiresAt <= this.now()) return "expired";
    return sameBinding(plan.binding, current) ? "valid" : "changed";
  }
  lookup(token: string): ControlPlan | null {
    const plan = this.plans.get(token);
    if (!plan || plan.expiresAt <= this.now()) {
      if (plan) this.plans.delete(token);
      return null;
    }
    return { ...plan, binding: { ...plan.binding } };
  }
  /** Consuming a token prevents competitors; its captured lifetime still gates final dispatch. */
  isUnexpired(expiresAt: number): boolean { return Number.isSafeInteger(expiresAt) && expiresAt > this.now(); }
  consume(token: string): void { this.plans.delete(token); }
}

/** Reserve first, then re-read authoritative state immediately before the one-shot dispatch. */
export async function executePlannedOperation(operations: OperationStore, plans: PlanStore, requestId: string,
  plan: ControlPlan, current: () => Promise<PlanBinding>, dispatch: () => Promise<import("./operations.ts").OperationOutcome>): Promise<OperationView> {
  return operations.execute(requestId, plan.binding, plan.token, async () => {
    const verdict = plans.validate(plan.token, await current());
    if (verdict !== "valid") return { state: "refused", code: `plan.${verdict}` };
    plans.consume(plan.token);
    return dispatch();
  });
}
