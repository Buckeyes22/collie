import { randomUUID } from "node:crypto";
import type { JsonValue } from "../json.ts";
import { hasControl, isObject, NATIVE_ACCESS_LIMITS, type VerifiedIdentity } from "./contracts.ts";
import type { PrivateJsonIo } from "./private-io.ts";

export interface DeviceBinding {
  readonly id: string; readonly issuer: string; readonly subject: string; readonly thumbprint: string;
  readonly pairedDeviceId: string; readonly deviceName: string; readonly revision: number; readonly state: "active" | "revoked";
}
export interface BindingRetired { readonly schema: 1; readonly eventId: string; readonly bindingId: string; readonly revision: number; readonly pairedDeviceId: string; readonly type: "binding-retired" }
export interface IngressBinding { readonly id: string; readonly revision: number; readonly pairedDeviceId: string }
interface State { readonly schema: 1; readonly revision: number; readonly bindings: DeviceBinding[]; readonly events: BindingRetired[] }
const id = (value: JsonValue | undefined) => typeof value === "string" && /^[A-Za-z0-9_-]{16,64}$/u.test(value);
const text = (value: JsonValue | undefined) => typeof value === "string" && value.length > 0 && value.length <= 256 && !hasControl(value);
function binding(value: JsonValue): value is JsonValue & DeviceBinding {
  return isObject(value) && Object.keys(value).length === 8 && id(value.id) && text(value.issuer) && text(value.subject) && typeof value.thumbprint === "string" && /^[A-Za-z0-9_-]{43}$/u.test(value.thumbprint) && typeof value.pairedDeviceId === "string" && /^[0-9a-f]{64}$/u.test(value.pairedDeviceId) && text(value.deviceName) && typeof value.revision === "number" && Number.isSafeInteger(value.revision) && value.revision > 0 && (value.state === "active" || value.state === "revoked");
}
function retired(value: JsonValue): value is JsonValue & BindingRetired {
  return isObject(value) && Object.keys(value).length === 6 && value.schema === 1 && value.type === "binding-retired" && id(value.eventId) && id(value.bindingId) && typeof value.revision === "number" && Number.isSafeInteger(value.revision) && value.revision > 0 && typeof value.pairedDeviceId === "string" && /^[0-9a-f]{64}$/u.test(value.pairedDeviceId);
}
/** A single shared owner registry, freshly read on every verification. Operator writes serialize
 * across processes with a fail-closed filesystem lease; stale leases require operator recovery. */
export class DeviceBindings {
  constructor(private readonly io: PrivateJsonIo) {}
  private read(): State {
    const value = this.io.read(); if (value === null) return { schema: 1, revision: 0, bindings: [], events: [] };
    if (!isObject(value) || Object.keys(value).length !== 4 || value.schema !== 1 || typeof value.revision !== "number" || !Number.isSafeInteger(value.revision) || value.revision < 0 || !Array.isArray(value.bindings) || value.bindings.length > NATIVE_ACCESS_LIMITS.bindingEntries || !value.bindings.every(binding) || !Array.isArray(value.events) || value.events.length > 4096 || !value.events.every(retired)) throw new Error("native.bindings_invalid");
    const ids = new Set(value.bindings.map((row) => row.id)); if (ids.size !== value.bindings.length || value.bindings.some((row) => row.revision > Number(value.revision)) || value.events.some((row) => row.revision > Number(value.revision))) throw new Error("native.bindings_invalid");
    return { schema: 1, revision: value.revision, bindings: value.bindings, events: value.events };
  }
  list(): readonly DeviceBinding[] { return this.read().bindings; }
  resolve(identity: VerifiedIdentity): DeviceBinding | null { const matches = this.read().bindings.filter((row) => row.state === "active" && row.issuer === identity.issuer && row.subject === identity.subject && row.thumbprint === identity.thumbprint); if (matches.length > 1) throw new Error("native.bindings_ambiguous"); return matches[0] ?? null; }
  current(value: IngressBinding): DeviceBinding | null { return this.read().bindings.find((row) => row.id === value.id && row.revision === value.revision && row.pairedDeviceId === value.pairedDeviceId && row.state === "active") ?? null; }
  enroll(identity: Pick<VerifiedIdentity, "issuer" | "subject" | "thumbprint">, pairedDeviceId: string, deviceName: string): DeviceBinding {
    return this.io.exclusive(() => {
      const old = this.read(); if (old.bindings.length >= NATIVE_ACCESS_LIMITS.bindingEntries || old.events.length >= 4096 || old.revision >= Number.MAX_SAFE_INTEGER - 1) throw new Error("native.bindings_full");
      const revision = old.revision + 1; const record: DeviceBinding = { id: randomUUID(), issuer: identity.issuer, subject: identity.subject, thumbprint: identity.thumbprint, pairedDeviceId, deviceName, revision, state: "active" };
      // SAFETY: serialization produces only the exact declared fields; the same on-disk validator checks all enrollment inputs.
      if (!binding(JSON.parse(JSON.stringify(record)) as JsonValue)) throw new Error("native.binding_invalid");
      const events = [...old.events]; const bindings = old.bindings.map((row) => {
        if (row.state !== "active" || row.issuer !== identity.issuer || row.subject !== identity.subject || row.thumbprint !== identity.thumbprint) return row;
        events.push({ schema: 1, type: "binding-retired", eventId: randomUUID(), bindingId: row.id, revision: row.revision, pairedDeviceId: row.pairedDeviceId }); return Object.assign({}, row, { state: "revoked" as const });
      });
      if (events.length > 4096) throw new Error("native.bindings_full");
      this.persist({ schema: 1, revision, bindings: [...bindings, record], events }); return record;
    });
  }
  revoke(bindingId: string): boolean {
    return this.io.exclusive(() => {
      const old = this.read(); const target = old.bindings.find((row) => row.id === bindingId && row.state === "active"); if (!target) return false;
      if (old.events.length >= 4096 || old.revision >= Number.MAX_SAFE_INTEGER) throw new Error("native.bindings_full");
      this.persist({ schema: 1, revision: old.revision + 1, bindings: old.bindings.map((row) => row === target ? Object.assign({}, row, { state: "revoked" as const }) : row), events: [...old.events, { schema: 1, type: "binding-retired", eventId: randomUUID(), bindingId: target.id, revision: target.revision, pairedDeviceId: target.pairedDeviceId }] }); return true;
    });
  }
  /** Typed durable outbox. Dispatcher must acknowledge only after its own signed clear/tombstone
   * custody is durable. Replay is deliberate/idempotent; token expiry never enters this outbox. */
  pendingRetirements(): readonly BindingRetired[] { return this.read().events; }
  acknowledge(eventId: string): void { this.io.exclusive(() => { const state = this.read(); this.persist({ ...state, events: state.events.filter((event) => event.eventId !== eventId) }); }); }
  private persist(state: State): void {
    // SAFETY: State is constructed exclusively from validated rows and projected closed records above.
    this.io.write(JSON.parse(JSON.stringify(state)) as JsonValue);
  }
}
