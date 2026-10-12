import {afterAll,expect,test} from "bun:test";
import {mkdtemp,mkdir,readdir,readFile,rm,writeFile} from "node:fs/promises";
import {mkdirSync,readdirSync} from "node:fs";
import {join} from "node:path";
import {tmpdir} from "node:os";
import type {JsonObject,JsonValue} from "../json.ts";
import {MachineAlertStore,hashText,slotFor} from "../machine-alerts.ts";
import {MachineHistory,MINUTE_MS} from "../machine-history.ts";
import {parseMachineStrictJson,parseVersion2AlertsFile} from "../machine-parse.ts";
import {MachineWatch,machineIncarnationOf} from "../machines.ts";
import {serveMachinesRoute,type MachineRouteCaller} from "../server.ts";
import {monitoringRecordsFromSnapshot,MonitoringCheckpointRecordStream,type MonitoringCheckpointRecord} from "./monitoring-checkpoint-records.ts";
import {buildMonitoringCheckpoint,buildMonitoringCheckpointSpool,MonitoringCheckpointReceiver,MONITORING_CHECKPOINT_PATH,parseMonitoringCheckpointManifest,sendMonitoringCheckpoint,type BuiltMonitoringCheckpoint,type CheckpointAuthority,type CheckpointFrame} from "./monitoring-checkpoint.ts";
const roots:string[]=[];
async function root(){const path=await mkdtemp(join(tmpdir(),"collie-monitoring-corrective2-"));roots.push(path);return path;}
afterAll(async()=>{for(const path of roots)await rm(path,{recursive:true,force:true});});
const authority:CheckpointAuthority={crewId:"crew",leadMemberId:"lead",leadFingerprint:"a".repeat(64),deputyMemberId:"deputy",deputyFingerprint:"b".repeat(64),warrantGeneration:1,secretGeneration:1};
const state={kind:"state" as const,lineage:"a".repeat(64),evaluatedThrough:null};
const request=(frame:CheckpointFrame)=>new Request("https://collie.invalid"+MONITORING_CHECKPOINT_PATH,{method:"POST",headers:{"content-type":"application/octet-stream"},body:JSON.stringify(frame)});
function object(value:JsonValue|undefined):JsonObject {if(typeof value!=="object" || value===null || Array.isArray(value))throw new Error("fixture requires a closed object");return value;}
function reconstruct(records:readonly MonitoringCheckpointRecord[],built:BuiltMonitoringCheckpoint):JsonObject {
  const machines:JsonObject={},history:JsonObject={},historyOwners:JsonObject={},sequences:JsonObject={},reserved:JsonObject={};
  const roster:JsonValue[]=[],tombstones:JsonValue[]=[],receipts:JsonValue[]=[],outbox:JsonValue[]=[];
  let lineage:JsonValue=null,evaluatedThrough:JsonValue=null;
  for(const record of records){
    switch(record.kind){
      case "state":lineage=record.lineage;evaluatedThrough=record.evaluatedThrough;break;
      case "machine":machines[record.machineId]=record.value;break;
      case "history":Object.assign(history,object(record.value));historyOwners[record.machineId]=record.machineIncarnation;break;
      case "receipt":receipts.push(record.value);break;
      case "outbox":outbox.push(record.value);break;
      case "sequence":sequences[record.slot]=record.value;break;
      case "reservation":reserved[record.slot]={sequence:record.sequence,episodeId:record.episodeId};break;
      case "tombstone":tombstones.push({...record});break;
      case "roster":{
        const machine=records.find(item=>item.kind==="machine"&&item.machineId===record.memberId);
        if(machine?.kind!=="machine")throw new Error("fixture requires the original enrolled machine");
        roster.push({id:record.memberId,fingerprint:record.fingerprint,enrolledAt:record.enrolledAt,incarnation:object(machine.value).machineIncarnation});break;
      }
    }
  }
  return {createdAt:built.manifest.createdAt,schema:1,checkpointRevision:built.manifest.checkpointRevision,lineage,evaluatedThrough,machines,history,historyOwners,roster,tombstones,receipts,outbox,producerSequences:sequences,reservedClear:reserved};
}

test("actual mixed-minute pending refire retains another reserved clear and retries after exact original receipts",async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),history=new MachineHistory(),rule={above:.9,forMin:5},now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS,inc=(id:string)=>id.repeat(64);
  for(const id of ["a","b"])await store.replaceRules({id,incarnation:inc(id),rules:{cpu:rule},actor:"original"},()=>true);
  await store.apply({opened:["a","b"].map(id=>({id,name:id,metric:"cpu" as const,rule,value:.95,machineIncarnation:inc(id),rulesRevision:"1"})),closed:[]},now-10*MINUTE_MS);
  await store.apply({opened:[],closed:[{id:"b",metric:"cpu",machineIncarnation:inc("b"),rulesRevision:"1"}]},now-6*MINUTE_MS);
  const originalB=store.pendingMonitoringEvents().filter(event=>event.machineId==="b");
  for(let minute=5;minute>0;minute--)for(const id of ["a","b"])history.record(id,{cpu:id==="a"?.5:.95,cores:1,memUsed:1,memTotal:2},now-minute*MINUTE_MS);
  const subjects=["a","b"].map(id=>({id,name:id,reachable:true,rules:{cpu:rule},open:store.open(id),machineIncarnation:inc(id),rulesRevision:"1"}));
  const result=await store.evaluateMinute(subjects,history,now,true,()=>true);
  expect(result.complete).toBe(false);expect(result.pass.closed.map(event=>event.id)).toEqual(["a"]);expect(result.pass.opened).toEqual([]);
  expect(store.pendingMonitoringEvents().filter(event=>event.machineId==="b")).toEqual(originalB);
  expect(store.pendingMonitoringEvents().filter(event=>event.kind==="clear"&&event.machineId==="a")).toHaveLength(1);
  expect(store.open("a")).toEqual([]);expect(store.open("b")).toEqual([]);expect(store.evaluatorWatermark()).toBeNull();
  const reloaded=await MachineAlertStore.load(directory);expect(reloaded.available()).toBe(true);expect(reloaded.open("a")).toEqual([]);expect(reloaded.evaluatorWatermark()).toBeNull();
  // Deliberate narrow-port receipt fixture; this is not a B22 signature/provider proof.
  for(const event of originalB)expect(await reloaded.settleMonitoringEvent({kind:"accepted",eventId:event.eventId,payloadHash:event.payloadHash,receiptDigest:hashText(event.eventId)},()=>true)).toBe(true);
  const retry=await reloaded.evaluateMinute(subjects,history,now,true,()=>true);
  expect(retry.complete).toBe(true);expect(retry.pass.opened.map(event=>event.id)).toEqual(["b"]);expect(reloaded.open("a")).toEqual([]);expect(reloaded.open("b")).toEqual(["cpu"]);expect(reloaded.evaluatorWatermark()).toBe(now-MINUTE_MS);
  const newB=reloaded.pendingMonitoringEvents().find(event=>event.machineId==="b");expect(newB?.producerSequence).toBe("3");expect(originalB.map(event=>event.producerSequence)).toEqual(["1","2"]);
});

test("two validation failures, original-authority retirement and actual file failure release exact owned transfers",async()=>{
  const directory=await root();
  for(let attempt=0;attempt<2;attempt++)expect(await buildMonitoringCheckpointSpool(directory,authority,[],"1",1000)).toBeNull();
  for(let attempt=0;attempt<2;attempt++){
    let current=true;
    function* retired(){yield state;current=false;yield state;}
    expect(await buildMonitoringCheckpointSpool(directory,authority,retired(),"1",1000,()=>current)).toBeNull();
  }
  function* fileFailure(){const spool=readdirSync(directory).find(name=>name.startsWith("monitoring-checkpoint.transfer-"));if(spool===undefined)throw new Error("fixture requires original owned spool");mkdirSync(join(directory,spool,"chunk-0.bin"));yield state;}
  await expect(buildMonitoringCheckpointSpool(directory,authority,fileFailure(),"1",1000)).rejects.toThrow();
  const accepted=await buildMonitoringCheckpointSpool(directory,authority,[state],"1",1000);expect(accepted).not.toBeNull();await accepted!.cleanup?.();await accepted!.cleanup?.();
  expect((await readdir(directory)).filter(name=>name.startsWith("monitoring-checkpoint.transfer-"))).toEqual([]);
});

test("failed-build cleanup preserves live and unknown external custody and the exact two-transfer admission bound",async()=>{
  const directory=await root();
  const original=["monitoring-checkpoint.transfer-legacy",`monitoring-checkpoint.transfer-${process.pid}-external`];
  for(const name of original){await mkdir(join(directory,name),{mode:0o700});await writeFile(join(directory,name,"chunk-0.bin"),name,{mode:0o600});}
  for(let attempt=0;attempt<2;attempt++)expect(await buildMonitoringCheckpointSpool(directory,authority,[],"1",1000)).toBeNull();
  const first=await buildMonitoringCheckpointSpool(directory,authority,[state],"1",1000),second=await buildMonitoringCheckpointSpool(directory,authority,[state],"1",1000);
  expect(first).not.toBeNull();expect(second).not.toBeNull();
  await expect(buildMonitoringCheckpointSpool(directory,authority,[state],"1",1000)).rejects.toThrow("checkpoint.transfer_pressure");
  await first!.cleanup?.();await second!.cleanup?.();
  const fresh=await buildMonitoringCheckpointSpool(directory,authority,[state],"1",1000);expect(fresh).not.toBeNull();await fresh!.cleanup?.();
  for(const name of original)expect(await readFile(join(directory,name,"chunk-0.bin"),"utf8")).toBe(name);
});

test("the pre-fix hash-valid two-clear checkpoint rejects at source, authoritative reconstruction and actual receiver commit",async()=>{
  const directory=await root();
  const fixture=object(parseMachineStrictJson(await readFile(new URL("./fixtures/monitoring-two-clear-checkpoint.json",import.meta.url),"utf8")));
  const manifest=parseMonitoringCheckpointManifest(fixture.manifest);if(manifest===null || !Array.isArray(fixture.chunks))throw new Error("fixture manifest invalid");
  const chunks=fixture.chunks.map(value=>{if(typeof value!=="string")throw new Error("fixture chunk invalid");return new Uint8Array(Buffer.from(value,"base64"));});
  const stream=new MonitoringCheckpointRecordStream(),records:MonitoringCheckpointRecord[]=[];
  for(const chunk of chunks)for(const record of stream.push(chunk))records.push(record);expect(stream.finish()).toBe(true);
  const oldBuilt={manifest,chunks};expect(buildMonitoringCheckpoint(authority,records,"1",1000)).toBeNull();expect(parseVersion2AlertsFile(reconstruct(records,oldBuilt),1000,directory)).toBeNull();
  const receiver=new MonitoringCheckpointReceiver(directory,()=>1000);
  expect((await receiver.handle(request({kind:"begin",recordFormat:"jsonl-continuation-v1",manifest}),authority,()=>true)).status).toBe(200);
  for(const [index,bytes]of chunks.entries())expect((await receiver.handle(request({kind:"chunk",checkpointRevision:"1",rootDigest:manifest.rootDigest,index,data:Buffer.from(bytes).toString("base64")}),authority,()=>true)).status).toBe(200);
  expect((await receiver.handle(request({kind:"commit",checkpointRevision:"1",rootDigest:manifest.rootDigest}),authority,()=>true)).status).toBe(400);expect(await receiver.receipt(authority,()=>true)).toBeNull();
});

for(const kind of ["alert-reservation","alert-clear","clear-only","retired-tombstone"] as const)test(`actual ${kind} event custody survives receiver inspection and reconstructs an available authoritative store`,async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),owner={id:"desk",name:"Desk",isLead:true,health:"reachable" as const,fingerprint:"c".repeat(64),enrolledAt:1},inc=machineIncarnationOf(owner,store.producerLineage())!,rule={above:.9,forMin:5};
  let enrolled=true;store.attachRoster(()=>enrolled?[{id:owner.id,fingerprint:owner.fingerprint,enrolledAt:owner.enrolledAt,incarnation:inc}]:[]);
  await store.replaceRules({id:owner.id,incarnation:inc,rules:{cpu:rule},actor:"original"},()=>true);
  await store.apply({opened:[{id:owner.id,name:owner.name,metric:"cpu",rule,value:.95,machineIncarnation:inc,rulesRevision:"1"}],closed:[]},1000);
  if(kind==="retired-tombstone"){enrolled=false;await store.evaluateMinute([],new MachineHistory(),60000,false,()=>true);}
  else if(kind!=="alert-reservation")await store.apply({opened:[],closed:[{id:owner.id,metric:"cpu",machineIncarnation:inc,rulesRevision:"1"}]},2000);
  if(kind==="clear-only"){const event=store.pendingMonitoringEvents().find(item=>item.kind==="alert")!;expect(await store.settleMonitoringEvent({kind:"accepted",eventId:event.eventId,payloadHash:event.payloadHash,receiptDigest:hashText(event.eventId)},()=>true)).toBe(true);}
  const snapshot=await store.checkpointSnapshot(),indexed=monitoringRecordsFromSnapshot(snapshot!,enrolled?[owner]:[],directory);expect(indexed).not.toBeNull();const records=[...indexed!],built=buildMonitoringCheckpoint(authority,records,store.currentCheckpointRevision(),1000)!;expect(built).not.toBeNull();
  const receiver=new MonitoringCheckpointReceiver(directory,()=>1000);
  expect(await sendMonitoringCheckpoint(built,frame=>receiver.handle(request(frame),authority,()=>true),()=>true)).toBe("accepted");expect(await receiver.receipt(authority,()=>true)).toEqual({checkpointRevision:built.manifest.checkpointRevision,rootDigest:built.manifest.rootDigest});
  const inspection=await receiver.inspectForAdoption(authority,()=>true);expect(inspection).not.toBeNull();const restored:MonitoringCheckpointRecord[]=[];for await(const item of inspection!.records)restored.push(item.record);expect(restored).toEqual(records);
  const reconstructed=reconstruct(restored,built);expect(parseVersion2AlertsFile(reconstructed,1000,directory)).not.toBeNull();await writeFile(join(directory,"machine-alerts.json"),JSON.stringify(reconstructed),{mode:0o600});expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

test("each checkpoint acknowledgement binds its phase and exact immutable revision, root, index and chunk hash",async()=>{
  const built=buildMonitoringCheckpoint(authority,[state],"1",1000)!;
  for(const phase of ["begin","commit","already"] as const)for(const corrupt of ["missing","revision","digest"] as const){
    const frames:string[]=[];
    const result=await sendMonitoringCheckpoint(built,async(frame)=>{
      frames.push(frame.kind);if(frame.kind==="chunk")return Response.json({accepted:true,index:frame.index,sha256:built.manifest.chunks[frame.index]!.sha256});
      const body:JsonObject=phase==="already"?{code:"checkpoint.already_current"}:{accepted:true};
      const invalid=phase==="already" || frame.kind===phase;
      if(!invalid || corrupt!=="missing"){body.checkpointRevision=invalid&&corrupt==="revision"?"2":built.manifest.checkpointRevision;body.rootDigest=invalid&&corrupt==="digest"?"c".repeat(64):built.manifest.rootDigest;}
      return Response.json(body);
    },()=>true);
    expect(result).toBe("deferred");expect(frames).toEqual(phase==="commit"?["begin","chunk","commit"]:["begin"]);
  }
  for(const corrupt of ["index","hash"] as const){
    const frames:string[]=[];expect(await sendMonitoringCheckpoint(built,async(frame)=>{frames.push(frame.kind);return Response.json(frame.kind==="chunk"?{accepted:true,index:corrupt==="index"?1:frame.index,sha256:corrupt==="hash"?"c".repeat(64):built.manifest.chunks[frame.index]!.sha256}:{accepted:true,checkpointRevision:built.manifest.checkpointRevision,rootDigest:built.manifest.rootDigest});},()=>true)).toBe("deferred");expect(frames).toEqual(["begin","chunk"]);
  }
  const directory=await root(),receiver=new MonitoringCheckpointReceiver(directory,()=>1000),post=(frame:CheckpointFrame)=>receiver.handle(request(frame),authority,()=>true);
  expect(await sendMonitoringCheckpoint(built,post,()=>true)).toBe("accepted");const frames:string[]=[];expect(await sendMonitoringCheckpoint(built,frame=>{frames.push(frame.kind);return post(frame);},()=>true)).toBe("accepted");expect(frames).toEqual(["begin"]);
  let current=true;expect(await sendMonitoringCheckpoint(built,async frame=>{const response=await post(frame);current=false;return response;},()=>current)).toBe("retired");
});

test("actual machine reads, conflict, replay and recreation retain inert extensions and reject their mutation",async()=>{
  const directory=await root(),lineage="b".repeat(64),member={id:"desk",name:"Desk",isLead:true,health:"reachable" as const,fingerprint:"a".repeat(64),enrolledAt:1},inc=machineIncarnationOf(member,lineage)!,extensions={unknown:{value:7}};
  const store=new MachineAlertStore(directory,new Map([[member.id,{rules:{},open:[],rulesRevision:"0",machineIncarnation:inc,extensions}]]),{lineage}),watch=new MachineWatch({now:()=>1000,roster:()=>[member],history:new MachineHistory(),alerts:store,saveHistory:async()=>{},muted:()=>false,enabled:()=>true,send:()=>{}});
  const caller:MachineRouteCaller={gate:()=>null,device:()=>null,pairedIdentity:()=>"original",audit:()=>{}};
  const route=async(body:JsonObject)=>{const response=await serveMachinesRoute(new Request("http://127.0.0.1/api/machines/desk/alerts",{method:"POST",body:JSON.stringify(body)}),"/api/machines/desk/alerts",caller,watch);if(response===null)throw new Error("fixture route absent");return {status:response.status,body:object(parseMachineStrictJson(await response.text()))};};
  const body={cpu:{above:.9,forMin:5},rulesRevision:"0",machineIncarnation:inc,requestId:"request_000000001"};expect(watch.rows().machines[0]!.extensions).toEqual(extensions);
  const accepted=await route(body);expect(accepted.status).toBe(200);expect(accepted.body.extensions).toEqual(extensions);expect(await route(body)).toEqual(accepted);
  const conflict=await route({...body,requestId:"request_000000002"});expect(conflict.status).toBe(409);expect(object(conflict.body.current).extensions).toEqual(extensions);
  expect((await route({...body,rulesRevision:"1",requestId:"request_000000003",extensions:{unknown:{value:8}}})).status).toBe(409);expect((await MachineAlertStore.load(directory)).snapshot(member.id,inc).extensions).toEqual(extensions);
});

test("original uint64 sequence headroom refuses only its opening and retains another reserved clear",async()=>{
  const directory=await root(),rule={above:.9,forMin:5},now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS,inc=(id:string)=>id.repeat(64),maximum="18446744073709551615",slot=slotFor(inc("b"),"cpu");
  const store=new MachineAlertStore(directory,new Map(["a","b"].map(id=>[id,{rules:{cpu:rule},open:[],rulesRevision:"1",machineIncarnation:inc(id)}])),{producerSequences:{[slot]:maximum}}),history=new MachineHistory();
  await store.apply({opened:[{id:"a",name:"a",metric:"cpu",rule,value:.95,machineIncarnation:inc("a"),rulesRevision:"1"}],closed:[]},now-10*MINUTE_MS);
  for(let minute=5;minute>0;minute--)for(const id of ["a","b"])history.record(id,{cpu:id==="a"?.5:.95,cores:1,memUsed:1,memTotal:2},now-minute*MINUTE_MS);
  const result=await store.evaluateMinute(["a","b"].map(id=>({id,name:id,reachable:true,rules:{cpu:rule},open:store.open(id),machineIncarnation:inc(id),rulesRevision:"1"})),history,now,true,()=>true);
  expect(result.complete).toBe(false);expect(result.pass.closed.map(item=>item.id)).toEqual(["a"]);expect(result.pass.opened).toEqual([]);expect(store.open("a")).toEqual([]);expect(store.evaluatorWatermark()).toBeNull();
  expect(store.pendingMonitoringEvents().filter(item=>item.machineId==="b")).toEqual([]);expect(object(await store.checkpointSnapshot()).producerSequences).toEqual({[slot]:maximum,[slotFor(inc("a"),"cpu")]:"2"});expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});
