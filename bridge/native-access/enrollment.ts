import type { BindingRetired, DeviceBindings, IngressBinding } from "./device-bindings.ts";

/** Shared B22 seam. Enrollment is derived only from Collie's validated ingress, never from the
 * registration body. Signed clear custody must commit before acknowledging the durable outbox. */
export interface NativeBindingRetirementPort {
  retire(event: BindingRetired): Promise<boolean>;
}
export function nativeEnrollmentBinding(bindings: DeviceBindings, ingress: IngressBinding | undefined, owner: string): IngressBinding | null {
  if (!ingress || ingress.pairedDeviceId !== owner || !bindings.current(ingress)) return null;
  return { id: ingress.id, revision: ingress.revision, pairedDeviceId: ingress.pairedDeviceId };
}
export async function drainBindingRetirements(bindings: DeviceBindings, dispatcher: NativeBindingRetirementPort): Promise<number> {
  let completed = 0;
  for (const event of bindings.pendingRetirements()) {
    if (!await dispatcher.retire(event)) continue;
    bindings.acknowledge(event.eventId); completed++;
  }
  return completed;
}
