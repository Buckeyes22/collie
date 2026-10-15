import { expect, test } from "bun:test";
import { createECDH, createPublicKey, randomBytes, verify } from "node:crypto";
import { NativePush } from "./native-push.ts";
import { NtfyNativePushProvider } from "./native-push-provider.ts";
import type { JsonValue } from "./json.ts";
import type { PrivateJsonIo } from "./native-access/private-io.ts";
const owner = "a".repeat(64);
function destination(endpoint = "https://push.example.test/" + "x".repeat(32)) {
  const ec = createECDH("prime256v1"); ec.generateKeys();
  return { endpoint, keys: { p256dh: ec.getPublicKey().toString("base64url"), auth: randomBytes(16).toString("base64url") } };
}
function memory(): PrivateJsonIo { let value: JsonValue | null = null; return { read: () => value, write: (v) => { value = JSON.parse(JSON.stringify(v)); }, exclusive: (f) => f() }; }
class CapturingProvider extends NtfyNativePushProvider {
  sent: { endpoint: string; envelope: string }[] = [];
  retry = false;
  override async deliver(d: ReturnType<typeof destination>, envelope: string) { this.sent.push({ endpoint: d.endpoint, envelope }); return { kind: this.retry ? "retry" as const : "accepted" as const }; }
}
test("ntfy endpoint is strictly operator-bound and encryption keys must be usable", () => {
  const provider = new NtfyNativePushProvider("https://push.example.test");
  expect(provider.validate(destination())).not.toBeNull();
  expect(provider.validate(destination("https://push.example.test/up_" + "x".repeat(32) + "?up=1"))).not.toBeNull();
  for (const endpoint of ["http://push.example.test/" + "x".repeat(32), "https://evil.test/" + "x".repeat(32), "https://push.example.test/short", "https://push.example.test/" + "x".repeat(32) + "?url=http://localhost"]) expect(provider.validate(destination(endpoint))).toBeNull();
  expect(() => new NtfyNativePushProvider("https://ntfy.sh")).toThrow();
  expect(provider.validate({ ...destination(), keys: { p256dh: "A".repeat(87), auth: "A".repeat(22) } })).toBeNull();
});
test("official ntfy up plus twelve alphanumeric topics enroll without admitting other short paths", () => {
  const provider = new CapturingProvider("https://push.example.test");
  const official = destination("https://push.example.test/upAbCdEf012345?up=1");
  expect(provider.validate(official)).not.toBeNull();
  const push = new NativePush(memory(), provider, () => true);
  expect(push.register(owner, { installationId: "installation_demo", ...official })).not.toBeNull();
  for (const path of ["upAbCdEf01234", "xxAbCdEf012345", "upAbCdEf01234_", "upAbCdEf012345/", "upAbCdEf012345?up=2"]) {
    expect(provider.validate(destination(`https://push.example.test/${path}`))).toBeNull();
  }
  expect(provider.validate(destination("https://other.example.test/upAbCdEf012345?up=1"))).toBeNull();
});
test("provider encrypts and sets UnifiedPush/no-Firebase, refuses redirects, maps retirement", async () => {
  let called = false;
  const provider = new NtfyNativePushProvider("https://push.example.test", undefined, async (_input, init) => {
    called = true; expect(init?.redirect).toBe("error");
    const h = new Headers(init?.headers); expect(h.get("x-unifiedpush")).toBe("1"); expect(h.get("x-firebase")).toBe("no");
    expect(h.get("content-encoding")).toBe("aes128gcm");
    // SAFETY: the provider sends a binary ArrayBuffer, verified through this fetch seam.
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).not.toContain("private notification");
    return new Response(null, { status: 410 });
  });
  expect(await provider.deliver(destination(), "private notification")).toEqual({ kind: "retired" }); expect(called).toBe(true);
});
test("only owning device replaces endpoints; persisted signer authenticates every event", async () => {
  const io = memory(); const provider = new CapturingProvider("https://push.example.test");
  const push = new NativePush(io, provider, () => true, (s) => s.replace("SECRET", "[masked]"), () => 1000);
  const d = destination(); const body = { installationId: "installation_demo", ...d };
  const enrolled = push.register(owner, body)!; expect(enrolled).not.toBeNull();
  expect(push.register("b".repeat(64), body)).toBeNull();
  expect(push.register(owner, body)?.registrationId).toBe(enrolled.registrationId);
  push.send({ title: "Blocked", body: "SECRET", titleCode: "agent.blocked", paneId: "p1", tag: "one", host: "host1", session: "session1" }); await push.flush();
  const envelope = JSON.parse(provider.sent[0]!.envelope);
  const key = createPublicKey({ key: Buffer.from(enrolled.signingPublicKey, "base64url"), format: "der", type: "spki" });
  expect(verify(null, Buffer.from(envelope.payload), key, Buffer.from(envelope.signature, "base64url"))).toBe(true);
  const payload = JSON.parse(envelope.payload); expect(payload.body).toBe("[masked]"); expect(payload.target).toEqual({ screen: "pane", paneId: "p1", host: "host1", session: "session1" }); expect(payload.sequence).toBe(1);
  const reloaded = new NativePush(io, provider, () => true); expect(reloaded.signingPublicKey).toBe(enrolled.signingPublicKey);
  reloaded.retire(owner, "someone_else"); await reloaded.flush(); expect(provider.sent).toHaveLength(1);
  reloaded.retire(owner, "installation_demo"); await reloaded.flush(); expect(JSON.parse(JSON.parse(provider.sent[1]!.envelope).payload).type).toBe("retired");
});
test("revoke commits tombstone before ACK and retains failed retirement across restart", async () => {
  const io = memory(); const provider = new CapturingProvider("https://push.example.test"); provider.retry = true;
  let active = true; let now = 1000; const push = new NativePush(io, provider, () => active, undefined, () => now);
  const binding = { id: "binding_demo_1234", revision: 1, pairedDeviceId: owner };
  push.register(owner, { installationId: "installation_demo", ...destination() }, binding);
  active = false;
  push.consumeRetirement({ schema: 1, type: "binding-retired", eventId: "retirement_demo", bindingId: binding.id, revision: 1, pairedDeviceId: owner });
  await push.flush();
  // SAFETY: the real NativePush writer populated this memory file immediately above.
  const stored = io.read() as { rows: { retired: boolean; pending: Record<string,string> }[] };
  expect(stored.rows[0]!.retired).toBe(true); expect(stored.rows[0]!.pending.retirement).toBeDefined();
  provider.retry = false; now += 30001; const restarted = new NativePush(io, provider, () => false, undefined, () => now); await restarted.flush();
  // SAFETY: the real NativePush writer rewrote this memory file during flush.
  expect((io.read() as { rows: unknown[] }).rows).toHaveLength(0);
});

test("endpoint replacement retires the old generation without capturing another installation", async () => {
  const io = memory(); const provider = new CapturingProvider("https://push.example.test");
  const push = new NativePush(io, provider, () => true);
  const first = destination(); const second = destination("https://push.example.test/" + "y".repeat(32));
  const old = push.register(owner, { installationId: "installation_demo", ...first })!;
  const newer = push.register(owner, { installationId: "installation_demo", ...second })!;
  expect(newer.registrationId).not.toBe(old.registrationId); await push.flush();
  const retirement = provider.sent.find((message) => message.endpoint === first.endpoint)!;
  expect(JSON.parse(JSON.parse(retirement.envelope).payload)).toEqual({ schemaVersion: 1, registrationId: old.registrationId, type: "retired" });
  push.send({ title: "Done", titleCode: "agent.done", body: "Work" }); await push.flush();
  expect(provider.sent.filter((message) => message.endpoint === second.endpoint)).toHaveLength(1);
});

test("disk failure cannot deliver a memory-only update before durable custody recovers", async () => {
  const backing = memory(); let writable = true;
  const io: PrivateJsonIo = { read: backing.read, exclusive: backing.exclusive, write: (value) => { if (!writable) throw new Error("disk full"); backing.write(value); } };
  const provider = new CapturingProvider("https://push.example.test");
  const push = new NativePush(io, provider, () => true);
  push.register(owner, { installationId: "installation_demo", ...destination() }); await push.flush();
  writable = false;
  expect(() => push.send({ title: "Blocked", body: "Work" })).toThrow();
  await expect(push.flush()).rejects.toThrow(); expect(provider.sent).toHaveLength(0);
  writable = true; await push.flush(); expect(provider.sent).toHaveLength(1);
});

test("request identity/token expiry changes do not rotate a stable gateway enrollment", async () => {
  const io = memory(); const provider = new CapturingProvider("https://push.example.test");
  const push = new NativePush(io, provider, () => true);
  const body = { installationId: "installation_demo", ...destination() };
  const actor = { id: "binding_demo_1234", revision: 1, pairedDeviceId: owner, requestId: "request1", expiresAtSeconds: 1000 };
  const first = push.register(owner, body, actor)!;
  const nextActor = { ...actor, requestId: "request2", expiresAtSeconds: 2000 };
  const second = push.register(owner, body, nextActor)!;
  expect(second.registrationId).toBe(first.registrationId); await push.flush(); expect(provider.sent).toHaveLength(0);
  expect(JSON.stringify(io.read())).not.toContain("request1"); expect(JSON.stringify(io.read())).not.toContain("expiresAtSeconds");
});
