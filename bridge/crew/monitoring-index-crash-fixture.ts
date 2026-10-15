/** Owned test-only OS exit witness; never imported by the bridge or compiled CLI. */
import {MonitoringFactIndex} from "./monitoring-fact-index.ts";
import {buildMonitoringCheckpointSpool} from "./monitoring-checkpoint.ts";
import {readdir} from "node:fs/promises";
import {join} from "node:path";
const parent=process.argv[2];if(parent===undefined)process.exit(2);
if(process.argv[3]==="transfer") {
  const built=await buildMonitoringCheckpointSpool(parent,{crewId:"crew",leadMemberId:"lead",leadFingerprint:"a".repeat(64),deputyMemberId:"deputy",deputyFingerprint:"b".repeat(64),warrantGeneration:1,secretGeneration:1},[{kind:"state",lineage:"a".repeat(64),evaluatedThrough:null}],"1",1000);
  if(built===null)process.exit(3);
  const name=(await readdir(parent)).find(item=>item.startsWith(`monitoring-checkpoint.transfer-${process.pid}-`));
  if(name===undefined)process.exit(4);
  process.stdout.write(join(parent,name)+"\n");
}else {
  const index=new MonitoringFactIndex(parent);index.put("fixture","original","{}");process.stdout.write(index.directory+"\n");
}
await new Promise<void>(()=>{});
