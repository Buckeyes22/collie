import {afterAll,expect,test} from "bun:test";
import {mkdtemp,readFile,readdir,rm,writeFile,chmod,symlink} from "node:fs/promises";
import {join} from "node:path";
import {tmpdir} from "node:os";
import {MonitoringFactIndex,MONITORING_INDEX_MAX_BYTES} from "./monitoring-fact-index.ts";
const roots:string[]=[];async function root(){const path=await mkdtemp(join(tmpdir(),"collie-index-custody-"));roots.push(path);return path;}
afterAll(async()=>{for(const path of roots)await rm(path,{recursive:true,force:true});});
test("the private external index preserves exact counters and has a hard aggregate page limit with cleanup",async()=>{
  const parent=await root(),first=new MonitoringFactIndex(parent),second=new MonitoringFactIndex(parent);
  try {
    expect(first.put("sequence","a".repeat(64),'"18446744073709551615"')).toBe(true);
    expect(first.get("sequence","a".repeat(64))).toBe('"18446744073709551615"');
    expect(first.put("sequence","a".repeat(64),'"18446744073709551614"')).toBe(false);
    expect([...first.rows("sequence")]).toEqual([{id:"a".repeat(64),value:'"18446744073709551615"'}]);
    expect(first.bytes()+second.bytes()).toBeLessThanOrEqual(MONITORING_INDEX_MAX_BYTES);
    expect(()=>new MonitoringFactIndex(parent)).toThrow("checkpoint.index_pressure");
  } finally {first.close();second.close();}
  expect(await readdir(join(parent,"collie-monitoring-verification-private"))).toEqual([]);
});
test("index refuses nonprivate/symlink/unknown scratch custody while preserving original files",async()=>{
  if(process.platform==="win32")return; // Actual Windows ACL gate is ROOT-owned.
  const parent=await root(),namespace=join(parent,"collie-monitoring-verification-private");
  const first=new MonitoringFactIndex(parent);first.close();
  await chmod(namespace,0o755);expect(()=>new MonitoringFactIndex(parent)).toThrow("checkpoint.index_owner_invalid");await chmod(namespace,0o700);
  const unknown=join(namespace,"unowned");await writeFile(unknown,"held",{mode:0o600});
  expect(()=>new MonitoringFactIndex(parent)).toThrow("checkpoint.index_unknown_custody");expect(await readFile(unknown,"utf8")).toBe("held");await rm(unknown);
  const link=join(namespace,"collie-monitoring-index-999999999-A");await symlink(parent,link);
  expect(()=>new MonitoringFactIndex(parent)).toThrow("checkpoint.index_owner_invalid");await rm(link);
});
test("an actual exited owned crash process leaves only bounded private scratch which the next index retires",async()=>{
  if(process.platform==="win32")return; // Windows OS-exit/ACL evidence remains acceptance OPEN.
  const parent=await root();
  const child=Bun.spawn([process.execPath,new URL("./monitoring-index-crash-fixture.ts",import.meta.url).pathname,parent],{env:{},stdout:"pipe",stderr:"pipe"});
  try {
    const reader=child.stdout.getReader();let line="";
    while(!line.includes("\n")){const next=await reader.read();if(next.done)throw new Error("crash fixture exited before ready");line+=new TextDecoder().decode(next.value);if(line.length>1024)throw new Error("crash fixture output exceeded bound");}
    reader.releaseLock();const directory=line.trim();expect(directory.startsWith(join(parent,"collie-monitoring-verification-private"))).toBe(true);
    child.kill("SIGKILL");await child.exited;
    const next=new MonitoringFactIndex(parent);next.close();
    expect(await readdir(join(parent,"collie-monitoring-verification-private"))).toEqual([]);
  } finally {child.kill();await child.exited;}
});

test("actual exited source transfer scratch retires while live, accepted and anonymous legacy custody is preserved",async()=>{
  if(process.platform==="win32")return; // ROOT owns the separate actual Windows exit/ACL acceptance.
  const {createMonitoringTransfer,closeMonitoringTransfer,monitoringPayloadBytes}=await import("./monitoring-payload-budget.ts");
  const {mkdir}=await import("node:fs/promises");
  const directory=await root();
  const child=Bun.spawn([process.execPath,join(import.meta.dir,"monitoring-index-crash-fixture.ts"),directory,"transfer"],{env:{},stdout:"pipe",stderr:"pipe"});
  const reader=child.stdout.getReader();const first=await reader.read();expect(first.done).toBe(false);
  if(first.done)throw new Error("owned transfer fixture exited before publication");
  const path=new TextDecoder().decode(first.value).trim();expect(path.startsWith(join(directory,`monitoring-checkpoint.transfer-${child.pid}-`))).toBe(true);
  const live=await createMonitoringTransfer(directory,()=>true);expect((await readdir(directory)).includes(path.slice(directory.length+1))).toBe(true);
  await closeMonitoringTransfer(live);child.kill("SIGKILL");await child.exited;
  const accepted=join(directory,"monitoring-checkpoint.generation-"+"a".repeat(64)),legacy=join(directory,"monitoring-checkpoint.transfer-legacy");
  await mkdir(accepted,{mode:0o700});await mkdir(legacy,{mode:0o700});
  await writeFile(join(accepted,"chunk-0.bin"),"accepted original",{mode:0o600});await writeFile(join(legacy,"chunk-0.bin"),"unproven legacy",{mode:0o600});
  const fresh=await createMonitoringTransfer(directory,()=>true);
  expect((await readdir(directory)).includes(path.slice(directory.length+1))).toBe(false);
  expect(await readFile(join(accepted,"chunk-0.bin"),"utf8")).toBe("accepted original");expect(await readFile(join(legacy,"chunk-0.bin"),"utf8")).toBe("unproven legacy");
  expect(await monitoringPayloadBytes(directory)).toBe(Buffer.byteLength("accepted originalunproven legacy"));
  await closeMonitoringTransfer(fresh);expect((await readdir(directory)).some(name=>name.startsWith(`monitoring-checkpoint.transfer-${process.pid}-`))).toBe(false);
});
