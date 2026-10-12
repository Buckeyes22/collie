import type {StoredMachineAlerts} from "./machine-parse.ts";
import { afterAll, expect, test } from "bun:test";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseMachineRuleRequest, parseMachineStrictJson } from "./machine-parse.ts";
import { MachineAlertStore, MACHINE_ALERTS_FILE } from "./machine-alerts.ts";
import { MachineHistory, MINUTE_MS, loadMachineHistoryForMigration, MACHINE_HISTORY_FILE } from "./machine-history.ts";

const roots: string[] = [];
async function root(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), "collie-monitoring-custody-")); roots.push(path); return path;
}
afterAll(async () => { for (const path of roots) await rm(path, { recursive: true, force: true }); });

test("strict rule JSON refuses terminal surrogate values and keys and nonfinite numeric tokens", () => {
  for (const source of ['{"extensions":{"x":"\\ud800"}}', '{"extensions":{"\\ud800":1}}', '{"extensions":{"x":1e309}}', '{"extensions":{"x":-1e309}}']) {
    expect(parseMachineStrictJson(source)).toBeNull();
  }
  expect(parseMachineRuleRequest({ extensions: { x: "\ud800" } })).toBeNull();
  expect(parseMachineRuleRequest({ extensions: { x: Infinity } })).toBeNull();
  const valid = parseMachineStrictJson('{"extensions":{"label":"\\ud83d\\ude00","number":1.1234567890123457}}');
  expect(valid).toEqual({ extensions: { label: "😀", number: 1.1234567890123457 } });
  expect(parseMachineRuleRequest(valid!)).toEqual({ alerts: {}, extensions: { label: "😀", number: 1.1234567890123457 } });
});

test("actual legacy v1 bare rows migrate without rounded network values or fabricated gaps", async () => {
  const directory = await root(); const now = Math.floor(Date.now() / MINUTE_MS) * MINUTE_MS;
  const rx = 123456789012345.67; const tx = 987654321012345.6;
  await writeFile(join(directory, MACHINE_HISTORY_FILE), JSON.stringify({ version: 1, machines: {
    desk: [[now - MINUTE_MS, 1, .9, .9, .5, rx, 1, tx, 1]],
  } }), { mode: 0o600 });
  const history = await loadMachineHistoryForMigration(directory, now);
  expect(history).not.toBeNull();
  const minute = history!.toMonitoringState(now).desk![0]!;
  expect(minute.rx).toBe(rx); expect(minute.tx).toBe(tx); expect(minute.disk).toBeNull();
  const restored = MachineHistory.fromMonitoringState(history!.toMonitoringState(now), now)!;
  expect(restored.toMonitoringState(now).desk![0]!.rx).toBe(rx);
  expect(restored.toMonitoringState(now).desk![0]!.tx).toBe(tx);
});

test("existing nonprivate state root and public authoritative file fail closed instead of resetting", async () => {
  if (process.platform === "win32") return; // Genuine Windows ACL proof is ROOT-owned acceptance.
  const directory = await root();
  await writeFile(join(directory, MACHINE_ALERTS_FILE), JSON.stringify({ version: 1, machines: {} }), { mode: 0o600 });
  await chmod(directory, 0o755);
  expect((await MachineAlertStore.load(directory)).available()).toBe(false);
  await chmod(directory, 0o700);
  await chmod(join(directory, MACHINE_ALERTS_FILE), 0o644);
  expect((await MachineAlertStore.load(directory)).available()).toBe(false);
  await chmod(join(directory, MACHINE_ALERTS_FILE), 0o600);
  expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

test("a queued original producer cannot commit or send after retirement, and the fresh producer remains usable", async () => {
  const directory=await root(), now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS;
  const {MachineWatch,machineIncarnationOf}=await import("./machines.ts");
  const entry={id:"desk",name:"Desk",isLead:true,health:"reachable" as const,fingerprint:"a".repeat(64),enrolledAt:1};
  const store=new MachineAlertStore(directory),history=new MachineHistory();
  const incarnation=machineIncarnationOf(entry,store.producerLineage())!;
  await store.replaceRules({id:"desk",incarnation,rules:{cpu:{above:.9,forMin:5}},actor:"A"},()=>true);
  for(let i=5;i>0;i--) history.record("desk",{cpu:.95,cores:1,memUsed:1,memTotal:2},now-i*MINUTE_MS);
  let active=true,release!:()=>void,entered!:()=>void;
  const held=new Promise<void>(resolve=>release=resolve),started=new Promise<void>(resolve=>entered=resolve);
  const original=store.apply.bind(store);
  store.apply=async(pass)=>{entered();await held;await original(pass);};
  const sent:unknown[]=[];
  const watch=new MachineWatch({now:()=>now,roster:()=>[entry],history,alerts:store,saveHistory:async()=>{},muted:()=>false,enabled:()=>true,active:()=>active,send:message=>sent.push(message),log:()=>{}});
  watch.tick();await started;active=false;release();await watch.flush();
  expect(sent).toEqual([]);expect(store.open("desk")).toEqual([]);expect(store.pendingMonitoringEvents()).toEqual([]);expect(store.evaluatorWatermark()).toBeNull();
  store.apply=original;active=true;watch.tick();await watch.flush();
  expect(sent).toHaveLength(1);expect(store.open("desk")).toEqual(["cpu"]);expect(store.evaluatorWatermark()).toBe(now-MINUTE_MS);
});

test("an old evaluated revision cannot reopen a removed rule or poison the durable generation", async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),incarnation="a".repeat(64),rule={above:.9,forMin:5};
  await store.replaceRules({id:"desk",incarnation,rules:{cpu:rule},actor:"A"},()=>true);
  await store.replaceRules({id:"desk",incarnation,rules:{},actor:"A"},()=>true);
  await store.apply({opened:[{id:"desk",name:"Desk",metric:"cpu",rule,value:.95,machineIncarnation:incarnation,rulesRevision:"1"}],closed:[]},1000);
  expect(store.open("desk")).toEqual([]);expect(store.pendingMonitoringEvents()).toEqual([]);
  expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

test("a failed complete-minute write cannot separately advance the evaluator watermark",async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),history=new MachineHistory();
  await rm(directory,{recursive:true});await writeFile(directory,"occupied",{mode:0o600});
  await expect(store.evaluateMinute([],history,MINUTE_MS,false,()=>true)).rejects.toThrow();
  expect(store.evaluatorWatermark()).toBeNull();expect(store.currentCheckpointRevision()).toBe("0");
  await rm(directory);await import("node:fs/promises").then(fs=>fs.mkdir(directory,{mode:0o700}));
  await store.evaluateMinute([],history,MINUTE_MS,false,()=>true);
  expect(store.evaluatorWatermark()).toBe(0);expect((await MachineAlertStore.load(directory)).evaluatorWatermark()).toBe(0);
});

test("the reserved original clear commits under mixed opening pressure without advancing an incomplete judgement",async()=>{
  const directory=await root(),rule={above:.9,forMin:10},inc=(i:number)=>i.toString(16).padStart(64,"0");
  const entries=new Map<string,StoredMachineAlerts>(Array.from({length:2000},(_,i):[string,StoredMachineAlerts]=>[`host_${i}`,{rules:{cpu:rule},open:[],rulesRevision:"1",machineIncarnation:inc(i)}]));
  const store=new MachineAlertStore(directory,entries,{lineage:"a".repeat(64)});
  const opened=(i:number)=>({id:`host_${i}`,name:`Host ${i}`,metric:"cpu" as const,rule,value:.95,machineIncarnation:inc(i),rulesRevision:"1"});
  await store.apply({opened:Array.from({length:1500},(_,i)=>opened(i)),closed:[]},1000);
  await store.apply({opened:Array.from({length:500},(_,i)=>opened(i+1500)),closed:[{id:"host_0",metric:"cpu",machineIncarnation:inc(0),rulesRevision:"1"}]},2000);
  expect(store.open("host_0")).toEqual([]);
  expect(store.pendingMonitoringEvents().filter(event=>event.kind==="clear" && event.machineId==="host_0")).toHaveLength(1);
  expect(store.evaluatorWatermark()).toBeNull();expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

test("a checkpoint revision retains its exact committed history and timestamp through live samples and recreation",async()=>{
  const directory=await root(),now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS,store=new MachineAlertStore(directory),history=new MachineHistory();
  store.attachHistory(history,()=>now);
  history.record("desk",{cpu:.7,cores:1,memUsed:1,memTotal:2},now-MINUTE_MS);
  await store.commitGeneration(now-MINUTE_MS);
  const first=await store.checkpointSnapshot();
  history.record("desk",{cpu:.9,cores:1,memUsed:1,memTotal:2},now);
  expect(await store.checkpointSnapshot()).toEqual(first);
  expect(await (await MachineAlertStore.load(directory)).checkpointSnapshot()).toEqual(first);
  expect(Object.isFrozen(first)).toBe(true);
});

test("an actual v1 open episode receives a reserved clear in one migration without a repeated opening",async()=>{
  const directory=await root(),rule={above:.9,forMin:5};
  await writeFile(join(directory,MACHINE_ALERTS_FILE),JSON.stringify({version:1,machines:{desk:{rules:{cpu:rule},open:["cpu"]}}}),{mode:0o600});
  const store=await MachineAlertStore.load(directory),incarnation="b".repeat(64);
  const result=await store.replaceRules({id:"desk",incarnation,rules:{cpu:rule},actor:"A"},()=>true);
  expect(result.kind).toBe("accepted");expect(store.pendingMonitoringEvents()).toEqual([]);
  const restored=await MachineAlertStore.load(directory);expect(restored.available()).toBe(true);expect(restored.open("desk")).toEqual(["cpu"]);
  await restored.apply({opened:[],closed:[{id:"desk",metric:"cpu",machineIncarnation:incarnation,rulesRevision:"1"}]},1000);
  expect(restored.pendingMonitoringEvents().map(event=>event.kind)).toEqual(["clear"]);
  expect((await MachineAlertStore.load(directory)).available()).toBe(true);
});

test("actual browser Push rechecks the original monitoring lease after its real binding refresh await",async()=>{
  const {Push}=await import("./push.ts"),{loadConfig}=await import("./config.ts"),{DeviceBindings}=await import("./native-access/device-bindings.ts"),{privateJsonIo}=await import("./native-access/private-io.ts");
  const directory=await root(),bindings=new DeviceBindings(privateJsonIo(join(directory,"bindings.json"),64*1024));
  const sent:unknown[]=[];const push=new Push({...loadConfig(),stateDir:directory},async(subscription,payload)=>{sent.push({subscription,payload});},bindings);
  push["_enabled"]=true;push["subs"].set("browser",{endpoint:"browser",keys:{p256dh:"p",auth:"a"}});
  let original=true;
  const held=push.send({type:"clear",tag:"collie:machine:desk:cpu"},()=>original);original=false;await held;
  expect(sent).toEqual([]);
  await push.send({type:"clear",tag:"collie:machine:desk:cpu"},()=>true);expect(sent).toHaveLength(1);
});

test("successful actual TrustStore publication has a monotonic local witness even for the same original object restored",async()=>{
  const {TrustStore}=await import("./crew/trust-store.ts"),{leadStore}=await import("./crew/fixtures.ts");
  const original=leadStore();
  const directory=await root(),store=new TrustStore(directory);await store.load();
  await store.update(()=>({next:original,result:true}));const revision=store.currentRevision();
  await store.update(()=>({next:{...original,deputy:null,warrant:null},result:true}));
  await store.update(()=>({next:original,result:true}));
  expect(store.current()).toBe(original);expect(store.currentRevision()).toBe(revision+2n);
  expect((await new TrustStore(directory).load())?.self.fingerprint).toBe(original.self.fingerprint);
  await rm(directory,{recursive:true});await writeFile(directory,"occupied",{mode:0o600});
  const before=store.currentRevision();await expect(store.update(()=>({next:original,result:true}))).rejects.toThrow();
  expect(store.currentRevision()).toBe(before);expect(store.current()).toBe(original);
});

test("monitoring ownership ignores a same-live refresh and latches secret ABA despite exact original reference restoration",async()=>{
  const {TrustStore}=await import("./crew/trust-store.ts");
  const {leadStore}=await import("./crew/fixtures.ts");
  const {monitoringAuthorityKey}=await import("./crew/monitoring-authority.ts");
  const original=leadStore(),directory=await root(),store=new TrustStore(directory);
  await store.load();await store.update(()=>({next:original,result:true}));
  const captured=store.monitoringRevision(monitoringAuthorityKey);
  await store.update(()=>({next:original,result:true}));
  expect(store.monitoringRevision(monitoringAuthorityKey)).toBe(captured);
  expect(original.crew).not.toBeNull();
  if(original.crew===null)throw new Error("fixture requires original crew enrollment");
  await store.update(()=>({next:{...original,crew:{...original.crew!,secret:"rotated-monitoring-secret"}},result:true}));
  expect(store.monitoringRevision(monitoringAuthorityKey)).toBe(captured+1n);
  await store.update(()=>({next:original,result:true}));
  expect(store.current()).toBe(original);
  expect(store.monitoringRevision(monitoringAuthorityKey)).toBe(captured+2n);
  await rm(directory,{recursive:true});await writeFile(directory,"occupied",{mode:0o600});
  const before=store.monitoringRevision(monitoringAuthorityKey);
  await expect(store.update(()=>({next:{...original,crew:{...original.crew!,secret:"failed-rotation"}},result:true}))).rejects.toThrow();
  expect(store.monitoringRevision(monitoringAuthorityKey)).toBe(before);
  expect(store.current()).toBe(original);
});

test("an acknowledged rule cannot mutate a committed generation or later immutable checkpoint",async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),inc="a".repeat(64);
  const result=await store.replaceRules({id:"desk",incarnation:inc,rules:{cpu:{above:.9,forMin:5}},actor:"original"},()=>true);
  expect(result.kind).toBe("accepted");
  if(result.kind!=="accepted")throw new Error("fixture rule admission failed");
  expect(Object.isFrozen(result.current.rules.cpu)).toBe(true);
  const revision=store.currentCheckpointRevision();
  expect(()=>{result.current.rules.cpu!.above=.5;}).toThrow();
  expect(store.currentCheckpointRevision()).toBe(revision);
  expect(store.snapshot("desk",inc).rules).toEqual({cpu:{above:.9,forMin:5}});
  expect((await MachineAlertStore.load(directory)).snapshot("desk",inc).rules).toEqual({cpu:{above:.9,forMin:5}});
});

test("a held original enqueue receipt cannot settle retired producer custody; the current retry retains the exact event",async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),inc="e".repeat(64),rule={above:.9,forMin:5};
  await store.replaceRules({id:"desk",incarnation:inc,rules:{cpu:rule},actor:"original"},()=>true);
  await store.apply({opened:[{id:"desk",name:"Desk",metric:"cpu",rule,value:.95,machineIncarnation:inc,rulesRevision:"1"}],closed:[]},1000);
  const original=store.pendingMonitoringEvents()[0]!;
  const revision=store.currentCheckpointRevision();
  let current=true,release:(()=>void)|undefined;
  const held=new Promise<void>(resolve=>{release=resolve;});
  let entered:(()=>void)|undefined;
  const admitted=new Promise<void>(resolve=>{entered=resolve;});
  const pending=store.enqueuePendingMonitoringEvents(async(event,producer)=>{
    expect(event).toEqual(original);expect(producer.originalProducerCurrent()).toBe(true);entered!();await held;
    expect(producer.originalProducerCurrent()).toBe(false);
    return {kind:"accepted",eventId:event.eventId,payloadHash:event.payloadHash,receiptDigest:"a".repeat(64)};
  },()=>current);
  await admitted;current=false;release!();await pending;
  expect(store.currentCheckpointRevision()).toBe(revision);
  expect(store.pendingMonitoringEvents()).toEqual([original]);
  expect((await MachineAlertStore.load(directory)).pendingMonitoringEvents()).toEqual([original]);
  const received:typeof original[]=[];
  await store.enqueuePendingMonitoringEvents(async(event,producer)=>{
    expect(producer.originalProducerCurrent()).toBe(true);received.push(event);
    return {kind:"accepted",eventId:event.eventId,payloadHash:event.payloadHash,receiptDigest:"a".repeat(64)};
  },()=>true);
  expect(received).toEqual([original]);expect(store.pendingMonitoringEvents()).toEqual([]);
  expect((await MachineAlertStore.load(directory)).pendingMonitoringEvents()).toEqual([]);
});

test("bounded source notification text preserves scalar validity at a UTF16 clipping boundary",async()=>{
  const directory=await root(),store=new MachineAlertStore(directory),inc="f".repeat(64),rule={above:.9,forMin:5};
  await store.replaceRules({id:"desk",incarnation:inc,rules:{cpu:rule},actor:"original"},()=>true);
  await store.apply({opened:[{id:"desk",name:"a".repeat(79)+"🚀",metric:"cpu",rule,value:.95,machineIncarnation:inc,rulesRevision:"1"}],closed:[]},1000);
  const event=store.pendingMonitoringEvents()[0]!;
  expect(parseMachineStrictJson(JSON.stringify(event))).not.toBeNull();
  expect((await MachineAlertStore.load(directory)).pendingMonitoringEvents()).toEqual([event]);
});

test("actual compact v2 history migration refuses coercion, overflow and malformed raw rows",async()=>{
  const directory=await root(),now=MINUTE_MS*10;
  const write=async(rows:number[][],t=MINUTE_MS)=>writeFile(join(directory,MACHINE_HISTORY_FILE),JSON.stringify({version:2,machines:{desk:{t,n:1,rows}}}),{mode:0o600});
  await write([[0,.5,.6,.7,123456789.123456,987654321.987654]]);
  const valid=await loadMachineHistoryForMigration(directory,now);expect(valid).not.toBeNull();
  expect(valid!.toMonitoringState(now).desk![0]!.rx).toBe(123456789.123456);
  for(const rows of [[[.5,.5,.6,.7,1,2]],[[0,.5,.4,.7,1,2]],[[0,.5,.6,.7,-1,2]],[[0,.5,.6,.7,1,2,2]],[[0,.5,.6,.7,1,2],[.5,.5,.6,.7,1,2]]]){
    await write(rows);expect(await loadMachineHistoryForMigration(directory,now)).toBeNull();
  }
  await write([[0,.5,.6,.7,1,2]],Number.MAX_VALUE);expect(await loadMachineHistoryForMigration(directory,now)).toBeNull();
});

test("the actual completed-minute evaluator preserves a reserved clear and refuses excess openings atomically",async()=>{
  const directory=await root(),rule={above:.9,forMin:10},inc=(i:number)=>i.toString(16).padStart(64,"0"),now=Math.floor(Date.now()/MINUTE_MS)*MINUTE_MS;
  const entries=new Map<string,StoredMachineAlerts>(Array.from({length:2000},(_,i):[string,StoredMachineAlerts]=>[`host_${i}`,{rules:{cpu:rule},open:[],rulesRevision:"1",machineIncarnation:inc(i)}]));
  const store=new MachineAlertStore(directory,entries,{lineage:"a".repeat(64)}),history=new MachineHistory();
  await store.apply({opened:Array.from({length:1500},(_,i)=>({id:`host_${i}`,name:`Host ${i}`,metric:"cpu" as const,rule,value:.95,machineIncarnation:inc(i),rulesRevision:"1"})),closed:[]},now-11*MINUTE_MS);
  const subjects=Array.from({length:501},(_,i)=>{const index=i===0?0:i+1499;return {id:`host_${index}`,name:`Host ${index}`,reachable:true,rules:{cpu:rule},open:store.open(`host_${index}`),machineIncarnation:inc(index),rulesRevision:"1"};});
  for(const subject of subjects)for(let minute=10;minute>0;minute--)history.record(subject.id,{cpu:subject.id==="host_0"?.1:.95,cores:1,memUsed:1,memTotal:2},now-minute*MINUTE_MS);
  const result=await store.evaluateMinute(subjects,history,now,true,()=>true);
  expect(result.complete).toBe(false);expect(result.pass.closed.map(item=>item.id)).toEqual(["host_0"]);
  expect(store.open("host_0")).toEqual([]);expect(store.pendingMonitoringEvents().filter(item=>item.kind==="clear"&&item.machineId==="host_0")).toHaveLength(1);
  expect(store.evaluatorWatermark()).toBeNull();const reloaded=await MachineAlertStore.load(directory);
  expect(reloaded.available()).toBe(true);expect(reloaded.open("host_0")).toEqual([]);expect(reloaded.evaluatorWatermark()).toBeNull();
});
