import { afterAll, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { MachineAlertStore } from "../machine-alerts.ts";
import { machineIncarnationOf } from "../machines.ts";
import { monitoringCheckpointSource } from "./monitoring-checkpoint-source.ts";
import { monitoringAuthorityKey } from "./monitoring-authority.ts";
import { MonitoringCheckpointReceiver, MONITORING_CHECKPOINT_CAPABILITY, MONITORING_CHECKPOINT_RECORD_FORMAT, sendMonitoringCheckpoint, type CheckpointAuthority, type CheckpointFrame } from "./monitoring-checkpoint.ts";
import { TrustStore, serializeTrustStore } from "./trust-store.ts";
import { currentWarrant, mintWarrant, WARRANT_TTL_MS } from "./warrant.ts";
import { leadStore, member, neverProxy, T0 } from "./fixtures.ts";
import { PeerClient, DEFAULT_CREW_TIMEOUT_MS } from "./peer-client.ts";
import { PROTOCOL_HEADER, MEMBER_HEADER } from "./admission.ts";
import { CREW_PROTOCOL_VERSION } from "./enrollment.ts";
import { CrewLead } from "./lead.ts";
import { CrewRegistry } from "./registry.ts";
const directories: string[] = [];
afterAll(async () => { for (const dir of directories) await rm(dir, { recursive: true, force: true }); });
function gate() { return Promise.withResolvers<void>(); }
async function fixture() {
  const dir = await mkdtemp(join(tmpdir(), "collie-monitoring-live-progress-")); directories.push(dir);
  const now = Date.now();
  const initial = mintWarrant(leadStore({ peers: [member({ memberId: "nas" })] }), "nas", now)!.next;
  const trust = new TrustStore(dir, { read: async () => serializeTrustStore(initial), write: async () => {} }); await trust.load();
  let clock = now, role = true;
  const store = new MachineAlertStore(dir);
  store.attachRoster(() => [
    { id: initial.self.memberId, fingerprint: initial.self.fingerprint, enrolledAt: initial.self.createdAt },
    ...initial.peers.map(p => ({ id: p.memberId, fingerprint: p.fingerprint, enrolledAt: p.enrolledAt })),
  ].map(p => Object.assign({}, p, { incarnation: machineIncarnationOf({ ...p, name: p.id, isLead: p.id === "desk", health: "reachable" }, store.producerLineage())! })));
  await store.commitGeneration(0);
  const source = monitoringCheckpointSource({ stateDir: dir, trustStore: trust, store: () => store, isLead: () => role, warrant: currentWarrant, now: () => clock });
  const receiver = new MonitoringCheckpointReceiver(dir, () => clock);
  const authority: CheckpointAuthority = { crewId: initial.crew!.crewId, leadMemberId: initial.self.memberId, leadFingerprint: initial.self.fingerprint, deputyMemberId: "nas", deputyFingerprint: initial.warrant!.warrant.deputyFingerprint!, warrantGeneration: initial.warrant!.warrant.generation, secretGeneration: initial.crew!.secretGeneration };
  const request = (frame: CheckpointFrame) => new Request("https://collie.invalid/crew/v1/monitoring/checkpoint", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(frame) });
  return { dir, initial, trust, store, source, receiver, authority, request, advance: (ms: number) => { clock += ms; }, role: (live: boolean) => { role = live; } };
}
test("captured committed snapshot remains byte-identical across later durable minutes and a current latest capture advances", async () => {
  const f = await fixture(), snapshot = await f.store.checkpointSnapshot(), revision = f.store.currentCheckpointRevision();
  const original = f.store.checkpointSnapshot.bind(f.store), entered = gate(), release = gate();
  f.store.checkpointSnapshot = async () => { entered.resolve(); await release.promise; return snapshot; };
  const building = f.source.current(); await entered.promise; await f.store.commitGeneration(60000); release.resolve();
  const old = await building; expect(old).not.toBeNull(); expect(old!.checkpoint.manifest.checkpointRevision).toBe(revision); expect(old!.originalOwnerCurrent()).toBe(true);
  const digest = old!.checkpoint.manifest.rootDigest; await old!.checkpoint.cleanup?.();
  const retry = await f.source.current(); expect(retry!.checkpoint.manifest.rootDigest).toBe(digest); await retry!.checkpoint.cleanup?.();
  f.store.checkpointSnapshot = original;
  const latest = await f.source.current(); expect(latest!.checkpoint.manifest.checkpointRevision).toBe("2"); expect(latest!.checkpoint.manifest.rootDigest).not.toBe(digest); await latest!.checkpoint.cleanup?.();
});
test("a write queued before snapshot capture is identified by its committed snapshot stamp, not a pre-await revision", async () => {
  const f = await fixture(), original = f.store.checkpointSnapshot.bind(f.store), entered = gate(), release = gate();
  f.store.checkpointSnapshot = async () => { entered.resolve(); await release.promise; return original(); };
  const building = f.source.current(); await entered.promise; await f.store.commitGeneration(60000); release.resolve();
  const built = await building; expect(built!.checkpoint.manifest.checkpointRevision).toBe("2"); expect(built!.originalOwnerCurrent()).toBe(true); await built!.checkpoint.cleanup?.();
});
for (const change of ["secret", "roster", "designation", "generation", "ABA", "role", "expiry"] as const) test(`original ${change} authority still retires observational publication without a replacement grant`, async () => {
  const f = await fixture(), captured = await f.source.current(); expect(captured).not.toBeNull(); const before = f.trust.monitoringRevision(monitoringAuthorityKey);
  if (change === "role") f.role(false);
  else if (change === "expiry") {
    await f.trust.update(data => ({ next: { ...data!, warrant: { ...data!.warrant!, warrant: { ...data!.warrant!.warrant, refreshedAt: f.initial.warrant!.warrant.refreshedAt + 1000 } } }, result: true }));
    expect(f.trust.monitoringRevision(monitoringAuthorityKey)).toBe(before); f.advance(WARRANT_TTL_MS);
  } else {
    await f.trust.update(data => ({ next: change === "secret" ? { ...data!, crew: { ...data!.crew!, secretGeneration: 2 } } : change === "roster" ? { ...data!, peers: data!.peers.map(p => Object.assign({}, p, { enrolledAt: p.enrolledAt + 1 })) } : change === "designation" ? { ...data!, deputy: null } : change === "generation" ? { ...data!, warrant: { ...data!.warrant!, warrant: { ...data!.warrant!.warrant, generation: 2 } } } : { ...data!, deputy: null }, result: true }));
    if (change === "ABA") await f.trust.update(() => ({ next: f.initial, result: true }));
  }
  expect(captured!.originalOwnerCurrent()).toBe(false); let posts = 0;
  expect(await sendMonitoringCheckpoint(captured!.checkpoint, async () => { posts++; return Response.json({}); }, captured!.originalOwnerCurrent)).toBe("retired"); expect(posts).toBe(0);
});
test("original expiry after a held response prevents chunk dispatch and prevents receipt success", async () => {
  const f = await fixture(), captured = await f.source.current(), entered = gate(), release = gate(); let posts = 0;
  const sending = sendMonitoringCheckpoint(captured!.checkpoint, async frame => { posts++; const response = await f.receiver.handle(f.request(frame), f.authority, captured!.originalOwnerCurrent); entered.resolve(); await release.promise; return response; }, captured!.originalOwnerCurrent);
  await entered.promise; f.advance(WARRANT_TTL_MS); release.resolve(); expect(await sending).toBe("retired"); expect(posts).toBe(1); expect(await f.receiver.receipt(f.authority, captured!.originalOwnerCurrent)).toBeNull();
});
test("actual lead coalesces minute changes without a second sweep, sends intact older observation then exact latest receipt", async () => {
  const f = await fixture(); let writes = 0; const receiptReady = gate(); const commits: string[] = [];
  const client = new PeerClient({ self: "desk", secret: () => "test-original-crew-secret", timeoutMs: DEFAULT_CREW_TIMEOUT_MS, fetch: async (_url, init) => {
    // SAFETY: This owned fetch receives only typed frames serialized by production PeerClient.monitoringCheckpoint.
    const frame = JSON.parse(String(init.body)) as CheckpointFrame;
    if (writes < 3) { writes++; await f.store.commitGeneration(writes * 60000); }
    const response = await f.receiver.handle(f.request(frame), f.authority, () => true);
    const bytes = await response.text();
    if (frame.kind === "commit" && response.ok) { commits.push(frame.checkpointRevision); if (frame.checkpointRevision === f.store.currentCheckpointRevision()) receiptReady.resolve(); }
    return new Response(bytes, { status: response.status, headers: { "content-type": "application/json", [PROTOCOL_HEADER]: String(CREW_PROTOCOL_VERSION), [MEMBER_HEADER]: "nas" } });
  } });
  const registry = new CrewRegistry({ sessions: { get: () => undefined }, self: "desk", members: () => f.initial.peers });
  const lead = new CrewLead({ registry, self: { id: "desk", name: "crew" }, maxUploadBytes: 1024, proxy: neverProxy, now: Date.now,
    snapshot: async () => ({ ok: true, status: 200, member: null, receivedAt: T0, date: null, value: { sessions: [], agents: [], shellPanes: [], monitoringCheckpoint: { ...MONITORING_CHECKPOINT_CAPABILITY, recordFormat: MONITORING_CHECKPOINT_RECORD_FORMAT } } }),
    monitoringCheckpoint: { ...f.source, deputy: () => f.trust.current()?.deputy ?? null, push: (link, frame) => client.monitoringCheckpoint(link, frame) } });
  await lead.sweep(); await receiptReady.promise;
  expect(writes).toBe(3); expect(commits).toEqual(["1", "4"]); const latest = await f.source.current(); expect(await f.receiver.receipt(f.authority, () => true)).toEqual({ checkpointRevision: "4", rootDigest: latest!.checkpoint.manifest.rootDigest }); await latest!.checkpoint.cleanup?.();
  // An old receipt is still for revision1, never a claim that revision4 was received.
});

test("the unchanged production frame deadline defers a held commit, and only its later exact verified receipt certifies that observation", async () => {
  const f = await fixture(), captured = await f.source.current(), entered = gate(), release = gate();
  let completed: Promise<Response> | null = null;
  const client = new PeerClient({ self: "desk", secret: () => "test-original-crew-secret", timeoutMs: DEFAULT_CREW_TIMEOUT_MS, fetch: async (_url, init) => {
    // SAFETY: This owned fetch receives only typed frames serialized by production PeerClient.monitoringCheckpoint.
    const frame = JSON.parse(String(init.body)) as CheckpointFrame;
    const work = (async () => { if (frame.kind === "commit") { entered.resolve(); await release.promise; } return f.receiver.handle(f.request(frame), f.authority, captured!.originalOwnerCurrent); })();
    if (frame.kind === "commit") completed = work;
    const response = await new Promise<Response>((resolve, reject) => {
      const signal = init.signal;
      const abort = () => reject(new Error("fixture observed original transport abort"));
      signal?.addEventListener("abort", abort, { once: true });
      void work.then(resolve, reject).finally(() => signal?.removeEventListener("abort", abort));
    });
    return new Response(await response.text(), { status: response.status, headers: { "content-type": "application/json", [PROTOCOL_HEADER]: String(CREW_PROTOCOL_VERSION), [MEMBER_HEADER]: "nas" } });
  } });
  const started = performance.now();
  const sending = sendMonitoringCheckpoint(captured!.checkpoint, async frame => {
    const outcome = await client.monitoringCheckpoint({ memberId: "nas", address: "nas.example:8787" }, frame);
    return outcome.ok ? Response.json(outcome.value, { status: outcome.status }) : new Response(null, { status: 503 });
  }, captured!.originalOwnerCurrent);
  await entered.promise; await f.store.commitGeneration(60000);
  expect(await sending).toBe("deferred"); expect(performance.now() - started).toBeGreaterThanOrEqual(DEFAULT_CREW_TIMEOUT_MS);
  expect(captured!.originalOwnerCurrent()).toBe(true); expect(await f.receiver.receipt(f.authority, () => true)).toBeNull();
  release.resolve(); expect((await completed!)!.status).toBe(200);
  const oldReceipt = await f.receiver.receipt(f.authority, () => true);
  expect(oldReceipt).toEqual({ checkpointRevision: "1", rootDigest: captured!.checkpoint.manifest.rootDigest }); expect(oldReceipt!.checkpointRevision).not.toBe(f.source.sourceRevision());
  const latest = await f.source.current();
  expect(await sendMonitoringCheckpoint(latest!.checkpoint, frame => f.receiver.handle(f.request(frame), f.authority, latest!.originalOwnerCurrent), latest!.originalOwnerCurrent)).toBe("accepted");
  expect(await f.receiver.receipt(f.authority, () => true)).toEqual({ checkpointRevision: "2", rootDigest: latest!.checkpoint.manifest.rootDigest });
});
