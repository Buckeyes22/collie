import type {JsonValue} from "../json.ts";
import {afterAll,expect,test} from "bun:test";
import {mkdtemp,readFile,rm,writeFile} from "node:fs/promises";
import {join} from "node:path";
import {tmpdir} from "node:os";
import {MachineAlertStore} from "../machine-alerts.ts";
import {machineIncarnationOf} from "../machines.ts";
import {MINUTE_MS} from "../machine-history.ts";
import {canonicalMonitoringCheckpointRecord,monitoringRecordsFromSnapshot,MonitoringCheckpointRecordStream,type MonitoringCheckpointRecord} from "./monitoring-checkpoint-records.ts";
import {buildMonitoringCheckpoint,buildMonitoringCheckpointSpool,MonitoringCheckpointReceiver,MONITORING_CHECKPOINT_PATH,type BuiltMonitoringCheckpoint,type CheckpointAuthority} from "./monitoring-checkpoint.ts";
const roots:string[]=[];
async function root(){const path=await mkdtemp(join(tmpdir(),"collie-checkpoint-custody-"));roots.push(path);return path;}
afterAll(async()=>{for(const path of roots)await rm(path,{recursive:true,force:true});});
const authority:CheckpointAuthority={crewId:"crew",leadMemberId:"lead",leadFingerprint:"a".repeat(64),deputyMemberId:"deputy",deputyFingerprint:"b".repeat(64),warrantGeneration:1,secretGeneration:1};
const state={kind:"state" as const,lineage:"a".repeat(64),evaluatedThrough:null};
const member=(id:string,fingerprint="c".repeat(64),enrolledAt=1)=>({id,fingerprint,enrolledAt});
const incarnation=(owner:ReturnType<typeof member>)=>machineIncarnationOf({...owner,name:owner.id,isLead:false,health:"reachable"},state.lineage)!;
const request=(body:CheckpointFixtureFrame)=>new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`,{method:"POST",headers:{"content-type":"application/octet-stream"},body:JSON.stringify(typeof body==="object" && body!==null && "kind" in body && body.kind==="begin" ? {...body,recordFormat:"jsonl-continuation-v1"} : body)});
async function accept(receiver:MonitoringCheckpointReceiver,built:BuiltMonitoringCheckpoint){
  const manifest=built.manifest;expect((await receiver.handle(request({kind:"begin",manifest}),authority,()=>true)).status).toBe(200);
  for(const descriptor of manifest.chunks){const bytes=built.readChunk===undefined?built.chunks[descriptor.index]!:await built.readChunk(descriptor.index);expect((await receiver.handle(request({kind:"chunk",checkpointRevision:manifest.checkpointRevision,rootDigest:manifest.rootDigest,index:descriptor.index,data:Buffer.from(bytes).toString("base64")}),authority,()=>true)).status).toBe(200);}
  expect((await receiver.handle(request({kind:"commit",checkpointRevision:manifest.checkpointRevision,rootDigest:manifest.rootDigest}),authority,()=>true)).status).toBe(200);
}
test("a genuine full-precision1440-minute record spans256KiB chunks and remains usable after receiver recreation",async()=>{
  const directory=await root(),now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS,owner=member("desk"),inc=incarnation(owner);
  const minutes=Array.from({length:1440},(_,i)=>({t:now-(1440-i)*MINUTE_MS,n:65535,cpu:.12345678901234566,cpuMax:.9999999999999999,mem:.8765432109876543,rx:123456789012345.67,tx:987654321012345.6,disk:.5555555555555555,rxN:65535,txN:65535,diskN:65535}));
  const history={kind:"history" as const,machineId:"desk",machineIncarnation:inc,value:{desk:minutes}};
  expect(Buffer.byteLength(canonicalMonitoringCheckpointRecord(history)!)).toBeGreaterThan(262144);
  const records=[state,{kind:"roster" as const,memberId:owner.id,fingerprint:owner.fingerprint,enrolledAt:owner.enrolledAt},{kind:"machine" as const,machineId:"desk",value:{rules:{},open:[],rulesRevision:"0",machineIncarnation:inc}},history];
  const built=await buildMonitoringCheckpointSpool(directory,authority,records,"1",now);expect(built).not.toBeNull();expect(built!.manifest.chunks.length).toBeGreaterThan(1);
  const decoder=new MonitoringCheckpointRecordStream();const restored:MonitoringCheckpointRecord[]=[];
  for(const descriptor of built!.manifest.chunks){const chunk=await built!.readChunk!(descriptor.index);expect(chunk.length).toBeLessThanOrEqual(262144);for(const record of decoder.push(chunk))restored.push(record);}
  expect(decoder.finish()).toBe(true);expect(restored).toEqual(records);
  const receiver=new MonitoringCheckpointReceiver(directory,()=>now);await accept(receiver,built!);
  const inspection=await new MonitoringCheckpointReceiver(directory,()=>now).inspectForAdoption(authority,()=>true);expect(inspection).not.toBeNull();
  const adopted=[];for await(const item of inspection!.records)adopted.push(item.record);expect(adopted).toEqual(records);
  await built!.cleanup?.();
});
test("missing chunks cannot certify a receipt or already-current ACK, exact immutable bytes can repair, corrupt head refuses without deleting them",async()=>{
  const directory=await root(),receiver=new MonitoringCheckpointReceiver(directory,()=>1000),built=buildMonitoringCheckpoint(authority,[state],"2",1000)!;
  await accept(receiver,built);const generation=join(directory,`monitoring-checkpoint.generation-${built.manifest.rootDigest}`);
  await rm(join(generation,"chunk-0.bin"));expect(await receiver.receipt(authority,()=>true)).toBeNull();
  expect((await receiver.handle(new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`),authority,()=>true)).status).toBe(503);
  const begin=await receiver.handle(request({kind:"begin",manifest:built.manifest}),authority,()=>true);expect(begin.status).toBe(200);expect(await begin.json()).toEqual({accepted:true,checkpointRevision:"2",rootDigest:built.manifest.rootDigest});
  await accept(receiver,built);expect(await receiver.receipt(authority,()=>true)).toEqual({checkpointRevision:"2",rootDigest:built.manifest.rootDigest});
  const original=await readFile(join(generation,"chunk-0.bin"));await writeFile(join(directory,"monitoring-checkpoint.accepted.json"),"{broken",{mode:0o600});
  expect((await receiver.handle(request({kind:"begin",manifest:built.manifest}),authority,()=>true)).status).toBe(503);
  expect(await readFile(join(generation,"chunk-0.bin"))).toEqual(original);expect(await receiver.receipt(authority,()=>true)).toBeNull();
});
test("closed roster/history/receipt/highwater owners cannot be invented; actual retired original alert+clear tombstone coexists with fresh enrolled B",async()=>{
  const now=1000,ownerA=member("desk"),ownerB=member("desk","d".repeat(64),2),incA=incarnation(ownerA),incB=incarnation(ownerB);
  const row=(inc:string)=>({kind:"machine" as const,machineId:"desk",value:{rules:{},open:[],rulesRevision:"0",machineIncarnation:inc}});
  expect(buildMonitoringCheckpoint(authority,[state,row(incA)],"1",now)).toBeNull();
  const roster={kind:"roster" as const,memberId:"desk",fingerprint:ownerB.fingerprint,enrolledAt:ownerB.enrolledAt};
  expect(buildMonitoringCheckpoint(authority,[state,roster,row(incA)],"1",now)).toBeNull();
  expect(buildMonitoringCheckpoint(authority,[state,roster,row(incB),{kind:"history",machineId:"desk",machineIncarnation:incA,value:{desk:[]}}],"1",now)).toBeNull();
  expect(buildMonitoringCheckpoint(authority,[state,{kind:"sequence",slot:"1".repeat(64),value:"1"}],"1",now)).toBeNull();
  const directory=await root(),store=new MachineAlertStore(directory);let current=ownerA;
  store.attachRoster(()=>[{...current,incarnation:incarnation(current)}]);
  const rule={above:.9,forMin:5};await store.replaceRules({id:"desk",incarnation:incA,rules:{cpu:rule},actor:"A"},()=>true);
  await store.apply({opened:[{id:"desk",name:"Desk",metric:"cpu",rule,value:.95,machineIncarnation:incA,rulesRevision:"1"}],closed:[]},now);
  current=ownerB;await store.replaceRules({id:"desk",incarnation:incB,rules:{mem:rule},actor:"B"},()=>true);
  const snapshot=await store.checkpointSnapshot(),records=monitoringRecordsFromSnapshot(snapshot!,[ownerB]);expect(records).not.toBeNull();
  const list=[...records!];expect(list.filter(item=>item.kind==="tombstone")).toHaveLength(1);expect(buildMonitoringCheckpoint(authority,list,"5",now)).not.toBeNull();
  expect(store.snapshot("desk",incB).rules).toEqual({mem:rule});expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

type CheckpointFixtureFrame=JsonValue|{kind:"begin";manifest:import("./monitoring-checkpoint.ts").MonitoringCheckpointManifest};

test("record continuations cross UTF8 scalar and JSON boundaries exactly, malformed scalar/number records refuse",()=>{
  const owner=member("unicode"),inc=incarnation(owner);
  const original={kind:"machine" as const,machineId:owner.id,value:{rules:{},open:[],rulesRevision:"0",machineIncarnation:inc,extensions:{label:"🚀é漢字"}}};
  const bytes=Buffer.from(canonicalMonitoringCheckpointRecord(original)!+"\n");
  const decoder=new MonitoringCheckpointRecordStream(),output:MonitoringCheckpointRecord[]=[];
  for(let offset=0;offset<bytes.length;offset++)for(const item of decoder.push(bytes.subarray(offset,offset+1)))output.push(item);
  expect(decoder.finish()).toBe(true);expect(output).toEqual([original]);
  expect(canonicalMonitoringCheckpointRecord({...original,value:{...original.value,extensions:{label:"\ud800"}}})).toBeNull();
  expect(canonicalMonitoringCheckpointRecord({...original,value:{...original.value,extensions:{value:Infinity}}})).toBeNull();
  const incomplete=new MonitoringCheckpointRecordStream();for(const _item of incomplete.push(bytes.subarray(0,bytes.length-1)))throw new Error("partial record was emitted");
  expect(incomplete.finish()).toBe(false);
});
test("old or mixed aligned readers are explicitly unsupported while the original continuation format admits",async()=>{
  const directory=await root(),receiver=new MonitoringCheckpointReceiver(directory,()=>1000),built=buildMonitoringCheckpoint(authority,[state],"1",1000)!;
  const raw=(format?:string)=>new Request(`https://collie.invalid${MONITORING_CHECKPOINT_PATH}`,{method:"POST",headers:{"content-type":"application/octet-stream"},body:JSON.stringify(format===undefined?{kind:"begin",manifest:built.manifest}:{kind:"begin",recordFormat:format,manifest:built.manifest})});
  for(const format of [undefined,"jsonl-aligned-v1"]){
    const response=await receiver.handle(raw(format),authority,()=>true);
    expect(response.status).toBe(404);expect(await response.json()).toEqual({code:"checkpoint.unsupported_format"});
    expect(await receiver.receipt(authority,()=>true)).toBeNull();
  }
  expect((await receiver.handle(raw("jsonl-continuation-v1"),authority,()=>true)).status).toBe(200);
  await accept(receiver,built);expect(await receiver.receipt(authority,()=>true)).toEqual({checkpointRevision:"1",rootDigest:built.manifest.rootDigest});
});
test("adding a self-described retired tombstone never admits an unlisted live machine",()=>{
  const owner=member("unlisted"),inc=incarnation(owner),tombstone={kind:"tombstone" as const,memberId:owner.id,fingerprint:owner.fingerprint,enrolledAt:owner.enrolledAt,machineIncarnation:inc,rulesRevision:"1"};
  const machine={kind:"machine" as const,machineId:owner.id,value:{rules:{},open:[],rulesRevision:"1",machineIncarnation:inc}};
  expect(buildMonitoringCheckpoint(authority,[state,machine,tombstone],"1",1000)).toBeNull();
  const snapshot={schema:1,lineage:state.lineage,evaluatedThrough:null,machines:{},history:{},receipts:[],outbox:[],producerSequences:{},reservedClear:{},tombstones:[{kind:"roster",memberId:owner.id,fingerprint:owner.fingerprint,enrolledAt:owner.enrolledAt}]};
  const records=monitoringRecordsFromSnapshot(snapshot,[]);expect(records).not.toBeNull();
  expect(()=>[...records!]).toThrow("checkpoint.tombstone_invalid");
});

test("checkpoint history field counts cannot exceed their original total sample count",()=>{
  const owner=member("desk"),inc=incarnation(owner),minute={t:0,n:1,cpu:.5,cpuMax:.6,mem:.7,rx:1,tx:2,disk:.8,rxN:2,txN:1,diskN:1};
  expect(canonicalMonitoringCheckpointRecord({kind:"history",machineId:"desk",machineIncarnation:inc,value:{desk:[minute]}})).toBeNull();
  expect(canonicalMonitoringCheckpointRecord({kind:"history",machineId:"desk",machineIncarnation:inc,value:{desk:[{...minute,rxN:1}]}})).not.toBeNull();
});
