import { afterEach, expect, test } from "bun:test";
import { AgentTestPeer } from "./agent-test-peer.ts";
import { HerdrClient } from "./client.ts";
import { createBunWriteStream, dialHerdr, WriteAdmissionRetired, type SockHandle } from "../../dial.ts";
const peers: AgentTestPeer[] = [];
afterEach(() => { for (const peer of peers.splice(0)) peer.close(); });
async function peerFixture() { const peer = new AgentTestPeer(); peers.push(peer); await peer.listen(); return peer; }
function runtime(client: HerdrClient): string { const identity = client.layoutRuntimeIdentity(); if (!identity) throw new Error("missing actual socket witness"); return identity; }
test("production client checks original authority after awaited dial before first named bytes and fresh current stays positive", async () => {
  const peer = await peerFixture(); let hold = false; let admitted = true; let release!: () => void; let entered!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; }); const seen = new Promise<void>((resolve) => { entered = resolve; });
  const client = new HerdrClient(peer.path, 1000, "auto", async (path, handlers, mode) => { if (hold) { entered(); await gate; } return dialHerdr(path, handlers, mode); });
  expect(await client.layoutHandshakeSample()).not.toBeNull(); const original = runtime(client); hold = true;
  const pending = client.actOnAgent("w1:p1", { kind: "rename", name: "newname" }, { runtimeIdentity: original, finalAdmission: () => admitted }).catch((error: Error) => error);
  await seen; admitted = false; release(); const failure = await pending;
  expect(failure).toBeInstanceOf(WriteAdmissionRetired); if (!(failure instanceof WriteAdmissionRetired)) throw new Error("typed refusal missing"); expect(failure.bytesMayHaveBeenSent).toBe(false);
  expect(peer.requests.filter((request) => request.method === "agent.rename")).toHaveLength(0);
  admitted = true; hold = false; expect((await client.actOnAgent("w1:p1", { kind: "rename", name: "fresh" }, { runtimeIdentity: original, finalAdmission: () => admitted })).name).toBe("fresh"); expect(peer.effects).toBe(1);
});
test("production writer carries accepted-prefix uncertainty through original retirement at a drain and closes its tail", async () => {
  const peer = await peerFixture(); let custom = false; let admitted = true; let stream: ReturnType<typeof createBunWriteStream> | null = null; let endCount = 0; let bytes = 0; let calls = 0;
  const raw = { write(_data: Uint8Array, _offset?: number, _length?: number) { calls++; const accepted = calls === 1 ? 1 : 0; bytes += accepted; return accepted; }, flush() {}, end() { endCount++; } };
  const client = new HerdrClient(peer.path, 1000, "auto", async (path, handlers, mode): Promise<SockHandle> => {
    if (!custom) return dialHerdr(path, handlers, mode);
    stream = createBunWriteStream(handlers); stream.attach(raw); handlers.open?.(stream.handle); return stream.handle;
  });
  expect(await client.layoutHandshakeSample()).not.toBeNull(); const original = runtime(client); custom = true;
  const pending = client.actOnAgent("w1:p1", { kind: "prompt", text: "private bounded", until: null, timeoutMs: 1000 }, { runtimeIdentity: original, finalAdmission: () => admitted }).catch((error: Error) => error);
  await Bun.sleep(0); expect(bytes).toBe(1); admitted = false;
  // The production client's injected dial returned this exact production writer.
  // SAFETY: the injected dial above assigns only this exact production writer; null is checked.
  const held = stream as ReturnType<typeof createBunWriteStream> | null; if (!held) throw new Error("writer did not open"); held.drain(raw);
  const failure = await pending; expect(failure).toBeInstanceOf(WriteAdmissionRetired); if (!(failure instanceof WriteAdmissionRetired)) throw new Error("uncertainty missing"); expect(failure.bytesMayHaveBeenSent).toBe(true); expect(endCount).toBe(1); const previous = calls; held.drain(raw); expect(calls).toBe(previous); expect(peer.effects).toBe(0);
});
