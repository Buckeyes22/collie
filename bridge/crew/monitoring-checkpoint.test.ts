import {parseMonitoringEvent} from "../machine-alerts.ts";
import type {JsonValue} from "../json.ts";
import { afterAll, describe, expect, test } from "bun:test";
import { mkdtemp, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  checkpointRootDigest,
  buildMonitoringCheckpoint,
  buildMonitoringCheckpointSpool,
  MONITORING_CHECKPOINT_CAPABILITY,
  MONITORING_CHECKPOINT_PATH,
  MonitoringCheckpointReceiver,
  parseMonitoringCheckpointManifest,
  sha256,
  type CheckpointAuthority,
} from "./monitoring-checkpoint.ts";
import { canonicalMonitoringCheckpointRecord, type MonitoringCheckpointRecord, MonitoringCheckpointRecordVerifier } from "./monitoring-checkpoint-records.ts";
import { monitoringRecordsFromSnapshot, parseMonitoringCheckpointChunk } from "./monitoring-checkpoint-records.ts";
import { machineIncarnationOf } from "../machines.ts";
import { MachineAlertStore } from "../machine-alerts.ts";

function roster(id:string) {return {kind:"roster" as const,memberId:id,fingerprint:"d".repeat(64),enrolledAt:1};}
function incarnation(id:string) {return machineIncarnationOf({id,name:id,isLead:false,health:"reachable",fingerprint:"d".repeat(64),enrolledAt:1},"c".repeat(64))!;}
function alignFixtureRecords(records:readonly MonitoringCheckpointRecord[]):void {
  let filled=0;let previous:MonitoringCheckpointRecord|undefined;
  for(const record of records) {
    const size=Buffer.byteLength(canonicalMonitoringCheckpointRecord(record)!)+1;
    if(filled+size>262144 && previous?.kind==="machine") {
      const value=previous.value;
      if(typeof value!=="object" || value===null || Array.isArray(value) || typeof value.extensions!=="object" || value.extensions===null || Array.isArray(value.extensions) || typeof value.extensions.p!=="string")throw new Error("aligned fixture requires its explicit inert padding field");
      value.extensions.p+="x".repeat(262144-filled);filled=0;
    }
    filled=(filled+size)%262144;previous=record;
  }
}
const dirs: string[] = [];
async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), "collie-monitoring-checkpoint-"));
  dirs.push(dir);
  return dir;
}
afterAll(async () => { await Promise.all(dirs.map((dir) => rm(dir, { recursive: true, force: true }))); });

const authority: CheckpointAuthority = {
  crewId: "crew_1", leadMemberId: "lead_1", leadFingerprint: "a".repeat(64), deputyMemberId: "deputy_1",
  deputyFingerprint: "b".repeat(64), warrantGeneration: 4, secretGeneration: 7,
};

function manifestFor(payload: Uint8Array) {
  const verifier = new MonitoringCheckpointRecordVerifier();
  verifier.add({ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null });
  const digests = verifier.finish()!;
  const unsigned = {
    schema: 1 as const, ...authority, monitoringLineage: "c".repeat(64), checkpointRevision: "12", createdAt: 1000,
    historyFrom: digests.historyFrom, historyThrough: digests.historyThrough, rosterDigest: digests.rosterDigest, rulesDigest: digests.rulesDigest, producerDigest: digests.producerDigest,
    chunks: [{ index: 0, bytes: payload.byteLength, sha256: sha256(payload) }],
  };
  return { ...unsigned, rootDigest: checkpointRootDigest(unsigned) };
}

function request(frame:CheckpointFixtureFrame): Request {
  return new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`, { method: "POST", headers: { "content-type": "application/octet-stream" }, body: JSON.stringify(typeof frame==="object" && frame!==null && "kind" in frame && frame.kind==="begin" ? {...frame,recordFormat:"jsonl-continuation-v1"} : frame) });
}

describe("monitoring checkpoint receiver", () => {
  test("manifest is closed, root-bound, and has the explicit bounded capability", () => {
    const payload = new TextEncoder().encode(JSON.stringify({ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }) + "\n");
    const manifest = manifestFor(payload);
    expect(parseMonitoringCheckpointManifest(manifest)?.rootDigest).toBe(manifest.rootDigest);
    expect(parseMonitoringCheckpointManifest({ ...manifest, extra: true })).toBeNull();
    expect(parseMonitoringCheckpointManifest({ ...manifest, checkpointRevision: "012" })).toBeNull();
    expect(MONITORING_CHECKPOINT_CAPABILITY).toEqual({ schema: 1, maxChunkBytes: 262144, maxGenerationBytes: 67108864 });
  });

  test("only exact current authority can stage, commit is durable/idempotent, and a wrong peer cannot inspect", async () => {
    const dir = await tempDir();
    let now = 1000;
    let current = true;
    const receiver = new MonitoringCheckpointReceiver(dir, () => now);
    const payload = new TextEncoder().encode(JSON.stringify({ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }) + "\n");
    const manifest = manifestFor(payload);
    const call = (frame:CheckpointFixtureFrame, auth = authority) => receiver.handle(request(frame), auth, () => current);

    const alienUnsigned = { ...manifest, deputyMemberId: "other" };
    const alien = { ...alienUnsigned, rootDigest: checkpointRootDigest(alienUnsigned) };
    expect((await call({ kind: "begin", manifest: alien })).status).toBe(403);
    expect((await call({ kind: "begin", manifest })).status).toBe(200);
    expect((await call({ kind: "chunk", checkpointRevision: "12", rootDigest: manifest.rootDigest, index: 0, data: Buffer.from(payload).toString("base64") })).status).toBe(200);
    expect((await call({ kind: "commit", checkpointRevision: "12", rootDigest: manifest.rootDigest })).status).toBe(200);
    expect(await (await receiver.handle(new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`), authority, () => current)).json()).toMatchObject({ checkpointRevision: "12", rootDigest: manifest.rootDigest });
    expect((await call({ kind: "begin", manifest })).status).toBe(200);
    const wrong = { ...authority, leadFingerprint: "9".repeat(64) };
    expect((await receiver.handle(new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`), wrong, () => true)).status).toBe(404);

    now += 1;
    current = false;
    expect((await call({ kind: "begin", manifest })).status).toBe(403);
  });

  test("a chunk stream cannot change authority or replace bytes after body admission", async () => {
    const dir = await tempDir();
    let current = true;
    const receiver = new MonitoringCheckpointReceiver(dir, () => 1000);
    const payload = new TextEncoder().encode(JSON.stringify({ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }) + "\n");
    const manifest = manifestFor(payload);
    await receiver.handle(request({ kind: "begin", manifest }), authority, () => current);
    const chunkReq = request({ kind: "chunk", checkpointRevision: "12", rootDigest: manifest.rootDigest, index: 0, data: Buffer.from(payload).toString("base64") });
    current = false;
    expect((await receiver.handle(chunkReq, authority, () => current)).status).toBe(403);
  });

  test("records are canonical, bounded, ordered and digest-bound without assembling a generation", () => {
    const state = { kind: "state" as const, lineage: "c".repeat(64), evaluatedThrough: null };
    const built = buildMonitoringCheckpoint(authority, [state], "13", 1001);
    expect(built).not.toBeNull();
    expect(parseMonitoringCheckpointChunk(built!.chunks[0]!)).toEqual([state]);
    expect(parseMonitoringCheckpointChunk(new TextEncoder().encode('{"kind":"state","kind":"state","lineage":"' + "c".repeat(64) + '","evaluatedThrough":null}\n'))).toBeNull();
    const tooLarge = { kind: "machine" as const, machineId: "host_1", value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: "d".repeat(64), extensions: { x: "a".repeat(270_000) } } };
    expect(buildMonitoringCheckpoint(authority, [state, tooLarge], "14", 1002)).toBeNull();
    expect(buildMonitoringCheckpoint(authority, [state, state], "15", 1003)).toBeNull();
    const verifier = new MonitoringCheckpointRecordVerifier();
    expect(verifier.add(state)).toBe(true);
    expect(verifier.add({ kind: "machine", machineId: "host_1", value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: "d".repeat(64) } })).toBe(true);
    expect(verifier.add({ kind: "machine", machineId: "host_1", value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: "d".repeat(64) } })).toBe(false);
    const nested = { kind: "machine" as const, machineId: "host_2", value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: "d".repeat(64), extensions: { a: { b: { c: { d: { e: "too deep" } } } } } } };
    expect(buildMonitoringCheckpoint(authority, [state, nested], "15", 1003)).toBeNull();
    const boundaryRecords = [state, ...Array.from({length:1500},(_,index)=>roster(`host_${String(index).padStart(4,"0")}`)), ...Array.from({ length: 1500 }, (_, index) => ({ kind: "machine" as const,
      machineId: `host_${String(index).padStart(4, "0")}`, value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: incarnation(`host_${String(index).padStart(4,"0")}`), extensions:{p:""} } }))];
    alignFixtureRecords(boundaryRecords);
    const boundary=buildMonitoringCheckpoint(authority,boundaryRecords,"15",1003);
    expect(boundary).not.toBeNull();
    expect(boundary!.chunks.length).toBeGreaterThan(1);
    expect(boundary!.chunks.every((chunk) => chunk.byteLength <= MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes && chunk.at(-1) === 0x0a && parseMonitoringCheckpointChunk(chunk) !== null)).toBe(true);
    expect(parseMonitoringCheckpointChunk(new TextEncoder().encode('{"kind":"state","lineage":"' + "c".repeat(64) + '","evaluatedThrough":null}'))).toBeNull();
  });

  test("commit recovers a durable generation rename that preceded the accepted-head update", async () => {
    const dir = await tempDir();
    const receiver = new MonitoringCheckpointReceiver(dir, () => 1005);
    const built = buildMonitoringCheckpoint(authority, [{ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }], "21", 1005)!;
    const manifest = built.manifest;
    await receiver.handle(request({ kind: "begin", manifest }), authority, () => true);
    await receiver.handle(request({ kind: "chunk", checkpointRevision: "21", rootDigest: manifest.rootDigest, index: 0, data: Buffer.from(built.chunks[0]!).toString("base64") }), authority, () => true);
    await rename(join(dir, `monitoring-checkpoint.stage-${manifest.rootDigest}`), join(dir, `monitoring-checkpoint.generation-${manifest.rootDigest}`));
    const committed = await receiver.handle(request({ kind: "commit", checkpointRevision: "21", rootDigest: manifest.rootDigest }), authority, () => true);
    expect(committed.status).toBe(200);
    expect(await receiver.receipt(authority, () => true)).toEqual({ checkpointRevision: "21", rootDigest: manifest.rootDigest });
  });

  test("a self-consistent root cannot hide a record-set digest mismatch", async () => {
    const dir = await tempDir();
    const receiver = new MonitoringCheckpointReceiver(dir, () => 1005);
    const built = buildMonitoringCheckpoint(authority, [{ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }], "22", 1005)!;
    const wrongUnsigned = { ...built.manifest, rulesDigest: "e".repeat(64) };
    const wrongManifest = { ...wrongUnsigned, rootDigest: checkpointRootDigest(wrongUnsigned) };
    await receiver.handle(request({ kind: "begin", manifest: wrongManifest }), authority, () => true);
    await receiver.handle(request({ kind: "chunk", checkpointRevision: "22", rootDigest: wrongManifest.rootDigest, index: 0, data: Buffer.from(built.chunks[0]!).toString("base64") }), authority, () => true);
    const rejected = await receiver.handle(request({ kind: "commit", checkpointRevision: "22", rootDigest: wrongManifest.rootDigest }), authority, () => true);
    expect(rejected.status).toBe(400);
    expect(await receiver.receipt(authority, () => true)).toBeNull();
  });

  test("checkpoint carries a real durable alert, its reserved clear, and the closed generation", async () => {
    const dir = await tempDir();
    const store = new MachineAlertStore(dir);
    store.attachRoster(()=>[{id:"host_1",fingerprint:"d".repeat(64),enrolledAt:1,incarnation:incarnation("host_1")}]);
    const rules = { cpu: { above: 0.9, forMin: 10 } };
    await store.set("host_1", rules);
    await store.apply({ opened: [{ id: "host_1", name: "Host", metric: "cpu", rule: rules.cpu!, value: 0.95 }], closed: [] }, 1000);
    const opened = await store.checkpointSnapshot();
    const originalRoster = [{ id: "host_1", fingerprint: "d".repeat(64), enrolledAt: 1 }];
    const openRecords = monitoringRecordsFromSnapshot(opened!, originalRoster);
    expect(openRecords).not.toBeNull();
    expect(buildMonitoringCheckpoint(authority, [...openRecords!], "24", 1005)).not.toBeNull();

    await store.apply({ opened: [], closed: [{ id: "host_1", metric: "cpu" }] }, 2000);
    const closed = await store.checkpointSnapshot();
    const closedRecords = monitoringRecordsFromSnapshot(closed!, originalRoster);
    const closedList = [...closedRecords!];
    const built = buildMonitoringCheckpoint(authority, closedList, "25", 1006);
    expect(built).not.toBeNull();
    const eventKinds=closedList.filter(item=>item.kind==="outbox").map(item=>{
      const event=parseMonitoringEvent(item.value,store.producerLineage());
      if(event===null)throw new Error("fixture requires a verified original monitoring event");
      return event.kind;
    });
    expect(eventKinds.toSorted()).toEqual(["alert", "clear"]);
  });

  test("file-backed sender chunks on record boundaries and receiver rejects reorder, truncation and corruption", async () => {
    const dir = await tempDir();
    const records = [
      { kind: "state" as const, lineage: "c".repeat(64), evaluatedThrough: null },
      ...Array.from({length:1800},(_,index)=>roster(`m${String(index).padStart(4,"0")}`)),
      ...Array.from({ length: 1800 }, (_, index) => ({
        kind: "machine" as const,
        machineId: `m${String(index).padStart(4, "0")}`,
        value: { rules: {}, open: [], rulesRevision: "0", machineIncarnation: incarnation(`m${String(index).padStart(4,"0")}`), extensions:{p:""} },
      })),
    ];
    // These specific small records align at chunk ends so the original boundary assertions
    // continue to control that fixture. Arbitrary continuations have separate full-precision tests.
    alignFixtureRecords(records);
    const built = await buildMonitoringCheckpointSpool(dir, authority, records, "20", 1005);
    expect(built).not.toBeNull();
    expect(built!.manifest.chunks.length).toBeGreaterThan(1);
    const chunks: Uint8Array[] = [];
    for (let i = 0; i < built!.manifest.chunks.length; i++) chunks.push(await built!.readChunk!(i));
    expect(chunks.every((chunk) => chunk.at(-1) === 0x0a && parseMonitoringCheckpointChunk(chunk) !== null)).toBe(true);

    const receiver = new MonitoringCheckpointReceiver(dir, () => 1005);
    const manifest = built!.manifest;
    const begin = await receiver.handle(request({ kind: "begin", manifest }), authority, () => true);
    expect(begin.status).toBe(200);
    const frame = (index: number, bytes: Uint8Array) => request({ kind: "chunk", checkpointRevision: manifest.checkpointRevision,
      rootDigest: manifest.rootDigest, index, data: Buffer.from(bytes).toString("base64") });
    expect((await receiver.handle(frame(1, chunks[1]!), authority, () => true)).status).toBe(409);
    const damaged = new Uint8Array(chunks[0]!); damaged[0] = damaged[0]! ^ 1;
    expect((await receiver.handle(frame(0, damaged), authority, () => true)).status).toBe(400);
    expect((await receiver.handle(frame(0, chunks[0]!), authority, () => true)).status).toBe(200);
    expect((await receiver.handle(request({ kind: "commit", checkpointRevision: manifest.checkpointRevision, rootDigest: manifest.rootDigest }), authority, () => true)).status).toBe(409);
    await built!.cleanup?.();
  });

  test("sender rechecks its captured owner after a delayed response and before each later frame", async () => {
    const state = { kind: "state" as const, lineage: "c".repeat(64), evaluatedThrough: null };
    const built = buildMonitoringCheckpoint(authority, [state], "16", 1004)!;
    let current = true;
    let calls = 0;
    const sent = await import("./monitoring-checkpoint.ts").then(({ sendMonitoringCheckpoint }) => sendMonitoringCheckpoint(built, async () => {
      calls++;
      current = false;
      return Response.json({ accepted: true, checkpointRevision: built.manifest.checkpointRevision, rootDigest: built.manifest.rootDigest });
    }, () => current));
    expect(sent).toBe("retired");
    expect(calls).toBe(1);
  });

  test("B38 preparation validates the whole accepted stream and writes only a paused one-use marker", async () => {
    const dir = await tempDir();
    const receiver = new MonitoringCheckpointReceiver(dir, () => 1005);
    const payload = new TextEncoder().encode(JSON.stringify({ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }) + "\n");
    const built = buildMonitoringCheckpoint(authority, [{ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }], "19", 1005)!;
    expect(built.chunks).toHaveLength(1);
    await receiver.handle(request({ kind: "begin", manifest: built.manifest }), authority, () => true);
    await receiver.handle(request({ kind: "chunk", checkpointRevision: "19", rootDigest: built.manifest.rootDigest, index: 0, data: Buffer.from(payload).toString("base64") }), authority, () => true);
    await receiver.handle(request({ kind: "commit", checkpointRevision: "19", rootDigest: built.manifest.rootDigest }), authority, () => true);
    const inspection = await receiver.inspectForAdoption(authority, () => true);
    expect(inspection?.manifest.rootDigest).toBe(built.manifest.rootDigest);
    const records: unknown[] = [];
    for await (const item of inspection!.records) records.push(item.record);
    expect(records).toEqual([{ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }]);
    const transition = { transitionId: "transition_1234567890", crewId: authority.crewId, newLeadMemberId: authority.deputyMemberId,
      newLeadFingerprint: authority.deputyFingerprint, warrantGeneration: authority.warrantGeneration + 1 };
    expect(await receiver.prepareMonitoringAdoption(transition, built.manifest.rootDigest, authority, () => true)).toBe("prepared");
    expect(await receiver.prepareMonitoringAdoption(transition, built.manifest.rootDigest, authority, () => true)).toBe("already_prepared");
    expect(await receiver.prepareMonitoringAdoption({ ...transition, transitionId: "transition_2234567890" }, built.manifest.rootDigest, authority, () => true)).toBe("refused");
    expect(await receiver.prepareMonitoringAdoption({ ...transition, warrantGeneration: authority.warrantGeneration }, built.manifest.rootDigest, authority, () => true)).toBe("refused");
  });

  test("an invalid durable adoption marker is not treated as an empty slot", async () => {
    const dir = await tempDir();
    const receiver = new MonitoringCheckpointReceiver(dir, () => 1005);
    const built = buildMonitoringCheckpoint(authority, [{ kind: "state", lineage: "c".repeat(64), evaluatedThrough: null }], "23", 1005)!;
    await receiver.handle(request({ kind: "begin", manifest: built.manifest }), authority, () => true);
    await receiver.handle(request({ kind: "chunk", checkpointRevision: "23", rootDigest: built.manifest.rootDigest, index: 0, data: Buffer.from(built.chunks[0]!).toString("base64") }), authority, () => true);
    await receiver.handle(request({ kind: "commit", checkpointRevision: "23", rootDigest: built.manifest.rootDigest }), authority, () => true);
    await writeFile(join(dir, "monitoring-adoption.marker.json"), "not-json", { mode: 0o600 });
    const transition = { transitionId: "transition_3234567890", crewId: authority.crewId, newLeadMemberId: authority.deputyMemberId,
      newLeadFingerprint: authority.deputyFingerprint, warrantGeneration: authority.warrantGeneration + 1 };
    expect(await receiver.prepareMonitoringAdoption(transition, built.manifest.rootDigest, authority, () => true)).toBe("refused");
  });
});

type CheckpointFixtureFrame=JsonValue|{kind:"begin";manifest:import("./monitoring-checkpoint.ts").MonitoringCheckpointManifest};
