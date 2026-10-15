import { controlRoute, controlScope, refusal, refusalResponse } from "./contracts.ts";
import type { OperationStore } from "./operations.ts";
import type { GateLevel } from "../crew/peer-gate.ts";

export interface ControlRouteCaller {
  gate(level: GateLevel): Response | null;
  /** Resolves exactly once. A peer response terminates dispatch; never fall through to the lead. */
  resolve(): Promise<{ readonly host: string; readonly session: string | null } | Response>;
  /** Stable opaque pairing incarnation, independent of the mutable display label. */
  device(): string | null;
  pairedIdentity(): string | null;
}
/** Mounted by the existing browser AND admitted Crew dispatch; no new public front door. */
export async function serveControlRoute(req: Request, url: URL, caller: ControlRouteCaller, operations: OperationStore): Promise<Response | null> {
  const contract = controlRoute(url.pathname, req.method);
  if (!contract || contract.name !== "operation") return null;
  const denied = caller.gate(contract.gate);
  if (denied) return denied;
  if (!controlScope(url, contract.scope)) return refusalResponse(refusal("scope.malformed", "Malformed control scope", "scope"), 400);
  const device = caller.pairedIdentity();
  if (!device || !/^[a-f0-9]{64}$/.test(device)) return refusalResponse(refusal("device.identity_required", "Operation reads require the originating paired device", "device"), 403);
  const runtime = await caller.resolve();
  if (runtime instanceof Response) return runtime;
  const id = url.pathname.slice("/api/control/operations/".length); // Contract regex guarantees the opaque id.
  try {
    const operation = await operations.inspect(id, device, runtime.host, runtime.session);
    if (!operation) return refusalResponse(refusal("operation.not_found", "Operation not found for this device and scope", "operation"), 404);
    return Response.json({ ok: true, operation }, { headers: { "cache-control": "no-store" } });
  } catch {
    return refusalResponse(refusal("operation.unavailable", "Operation registry is temporarily unavailable", "operation"), 503);
  }
}
