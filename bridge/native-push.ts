import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import { join } from "node:path";
import { privateJsonIo, type PrivateJsonIo } from "./native-access/private-io.ts";
import type { IngressBinding, BindingRetired } from "./native-access/device-bindings.ts";
import type { JsonValue } from "./json.ts";
import type { NativePushPayload } from "./native-push-contract.ts";
import type { PushMessage } from "./push.ts";
import { NtfyNativePushProvider, type NativePushDestination } from "./native-push-provider.ts";
interface Row { installationId: string; registrationId: string; pairedDeviceId: string; binding?: IngressBinding; destination: NativePushDestination; sequence: number; retired: boolean; retryAt?: number; pending: Record<string, string> }
interface State { schema: 1; privateKey: string; rows: Row[] }
const installation = /^[A-Za-z0-9_-]{16,128}$/u;
/** Server custody is private, durable and bounded. Payloads convey navigation, never authority. */
export class NativePush {
  private state: State;
  private running: Promise<void> | null = null;
  private requested = false;
  readonly signingPublicKey: string;
  constructor(private readonly io: PrivateJsonIo, private readonly provider: NtfyNativePushProvider,
    private readonly ownerCurrent: (pairedDeviceId: string, binding?: IngressBinding) => boolean,
    private readonly redact: (text: string) => string = (s) => s, private readonly now: () => number = Date.now) {
    const stored = io.read();
    if (stored === null) {
      this.state = { schema: 1, privateKey: generateKeyPairSync("ed25519").privateKey.export({ format: "der", type: "pkcs8" }).toString("base64url"), rows: [] };
      this.persist();
    } else {
      // SAFETY: validate every stored row and key below; all disk JSON is untrusted.
      const state = JSON.parse(JSON.stringify(stored)) as State;
      if (state.schema !== 1 || typeof state.privateKey !== "string" || !Array.isArray(state.rows) || state.rows.length > 256) throw new Error("native.push_state_invalid");
      if (createPrivateKey({ key: Buffer.from(state.privateKey, "base64url"), format: "der", type: "pkcs8" }).asymmetricKeyType !== "ed25519") throw new Error("native.push_key_invalid");
      for (const row of state.rows) {
        if (!row || !installation.test(row.installationId) || !installation.test(row.registrationId) || !/^[0-9a-f]{64}$/u.test(row.pairedDeviceId) ||
            !provider.validate(row.destination) || !Number.isSafeInteger(row.sequence) || row.sequence < 0 || typeof row.retired !== "boolean" || (row.retryAt !== undefined && (!Number.isSafeInteger(row.retryAt) || row.retryAt < 0)) ||
            !row.pending || typeof row.pending !== "object" || Object.keys(row.pending).length > 128 || Object.values(row.pending).some((p) => typeof p !== "string" || p.length > 3600)) throw new Error("native.push_state_invalid");
      }
      this.state = state;
    }
    this.signingPublicKey = createPublicKey(this.key().export({ format: "pem", type: "pkcs8" })).export({ format: "der", type: "spki" }).toString("base64url");
  }
  static configured(stateDir: string, ownerCurrent: (id: string, binding?: IngressBinding) => boolean, redact?: (text: string) => string): NativePush | null {
    const origin = process.env.COLLIE_NATIVE_PUSH_ORIGIN;
    return origin ? new NativePush(privateJsonIo(join(stateDir, "native-push.json")), new NtfyNativePushProvider(origin, process.env.COLLIE_NATIVE_PUSH_TOKEN), ownerCurrent, redact) : null;
  }
  register(owner: string, body: JsonValue, binding?: IngressBinding): { registrationId: string; signingPublicKey: string; bindingId?: string; bindingRevision?: number } | null {
    if (!owner.match(/^[0-9a-f]{64}$/u) || !this.ownerCurrent(owner, binding) || !body || typeof body !== "object" || Array.isArray(body) ||
        Object.keys(body).some((k) => !["installationId", "endpoint", "keys"].includes(k)) || typeof body.installationId !== "string" || !installation.test(body.installationId)) return null;
    const ownerBinding = binding === undefined ? undefined : { id: binding.id, revision: binding.revision, pairedDeviceId: binding.pairedDeviceId };
    if (ownerBinding && (ownerBinding.pairedDeviceId !== owner || !/^[A-Za-z0-9_-]{16,64}$/u.test(ownerBinding.id) || !Number.isSafeInteger(ownerBinding.revision) || ownerBinding.revision < 1)) return null;
    const destination = this.provider.validate(body); if (!destination) return null;
    const old = this.state.rows.find((r) => r.pairedDeviceId === owner && r.installationId === body.installationId && !r.retired);
    if (old && JSON.stringify(old.destination) === JSON.stringify(destination) && JSON.stringify(old.binding) === JSON.stringify(ownerBinding)) return { registrationId: old.registrationId, signingPublicKey: this.signingPublicKey, bindingId: binding?.id, bindingRevision: binding?.revision };
    if (this.state.rows.length >= 256) return null;
    // An endpoint cannot be captured by a different pairing/installation.
    if (this.state.rows.some((r) => !r.retired && r.destination.endpoint === destination.endpoint && r !== old)) return null;
    if (old) this.retireRow(old);
    const row: Row = { installationId: body.installationId, registrationId: randomUUID(), pairedDeviceId: owner, binding: ownerBinding, destination, sequence: 0, retired: false, pending: {} };
    this.state.rows.push(row); this.persist(); void this.flush().catch(() => { /* The periodic flush retries durable custody. */ });
    return { registrationId: row.registrationId, signingPublicKey: this.signingPublicKey, bindingId: binding?.id, bindingRevision: binding?.revision };
  }
  retire(owner: string, installationId?: string): void {
    for (const row of this.state.rows) if (row.pairedDeviceId === owner && (!installationId || row.installationId === installationId)) this.retireRow(row);
    this.persist(); void this.flush().catch(() => { /* The periodic flush retries durable custody. */ });
  }
  consumeRetirement(event: BindingRetired): void {
    for (const row of this.state.rows) if (row.binding?.id === event.bindingId && row.binding.revision === event.revision && row.pairedDeviceId === event.pairedDeviceId) this.retireRow(row);
    this.persist(); void this.flush().catch(() => { /* The periodic flush retries durable custody. */ });
  }
  send(message: PushMessage): void {
    for (const row of this.state.rows) {
      if (row.retired) continue;
      if (!this.ownerCurrent(row.pairedDeviceId, row.binding)) { this.retireRow(row); continue; }
      if (row.sequence >= Number.MAX_SAFE_INTEGER - 1) { this.retireRow(row); continue; }
      const slot = createHash("sha256").update(message.tag ?? message.type ?? "herd").digest("hex");
      const payload: NativePushPayload = { schemaVersion: 1, registrationId: row.registrationId, slot, sequence: ++row.sequence, expiresAt: this.now() + 24 * 3600000, kind: message.type === "clear" ? "clear" : message.type === "update" ? "update" : message.titleCode?.endsWith("done") ? "done" : "blocked" };
      if (payload.kind !== "clear") {
        payload.title = this.clean(message.title ?? "Collie", 160); payload.body = this.clean(message.body ?? "Open Collie", 512); payload.renotify = message.renotify === true;
        payload.target = payload.kind === "update" ? { screen: "updates" } : message.paneId ? { screen: "pane", paneId: message.paneId, host: message.host, session: message.session } : { screen: "home", host: message.host, session: message.session };
      }
      if (!(slot in row.pending) && Object.keys(row.pending).length >= 128) continue;
      row.pending[slot] = this.signed(JSON.stringify(payload));
    }
    this.persist(); void this.flush().catch(() => { /* The periodic flush retries durable custody. */ });
  }
  /** Retries retained ciphertext/signatures on the next flush; call periodically and at startup. */
  flush(): Promise<void> {
    this.requested = true;
    if (this.running) return this.running;
    this.running = (async () => { do { this.requested = false; await this.drain(); } while (this.requested); })().finally(() => { this.running = null; }); return this.running;
  }
  private async drain(): Promise<void> {
    // A previous enqueue may have met disk-full. Never deliver its memory-only update.
    this.persist();
    for (const row of this.state.rows) {
      if (!row.retired && !this.ownerCurrent(row.pairedDeviceId, row.binding)) { this.retireRow(row); this.persist(); }
      if (row.retryAt !== undefined && row.retryAt > this.now()) continue;
      for (const [slot, bytes] of Object.entries(row.pending)) {
        const result = await this.provider.deliver(row.destination, bytes);
        if (result.kind === "retry") { row.retryAt = this.now() + Math.max(30000, result.retryAfterMs ?? 30000); this.persist(); break; }
        row.retryAt = undefined;
        // Never acknowledge a newer replacement while an older request is in flight.
        if (row.pending[slot] === bytes && (result.kind === "accepted" || result.kind === "retired")) { delete row.pending[slot]; this.persist(); }
        if (result.kind === "retired") { row.retired = true; row.pending = {}; this.persist(); break; }
      }
    }
    this.state.rows = this.state.rows.filter((r) => !r.retired || Object.keys(r.pending).length > 0); this.persist();
  }
  private retireRow(row: Row): void {
    if (row.retired) return;
    row.retired = true;
    row.retryAt = undefined;
    row.pending = { retirement: this.signed(JSON.stringify({ schemaVersion: 1, registrationId: row.registrationId, type: "retired" })) };
  }
  private clean(text: string, limit: number): string { return [...this.redact(text)].map((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127 ? " " : c).join("").slice(0, limit).trim() || "Collie"; }
  private key() { return createPrivateKey({ key: Buffer.from(this.state.privateKey, "base64url"), format: "der", type: "pkcs8" }); }
  private signed(payload: string): string { return JSON.stringify({ payload, signature: sign(null, Buffer.from(payload), this.key()).toString("base64url") }); }
  private persist(): void {
    // SAFETY: exact private state projection serialized into the shared private-file writer.
    this.io.write(JSON.parse(JSON.stringify(this.state)) as JsonValue);
  }
}
