import {HOST} from "../host.ts";
/** Physical accepted, staged, repair and transfer payload accounting; metadata has its separate
 * allowance. Unknown custody is refused and retained. No caller paths enter this namespace. */
import {lstat,opendir,mkdtemp,rm} from "node:fs/promises";
import {join} from "node:path";
import {assertMonitoringPrivateDirectory} from "../monitoring-private-io.ts";
import {isOwnerOnly} from "../owner-only.ts";
export const MONITORING_PAYLOAD_MAX_BYTES=128*1024*1024;
export async function monitoringPayloadBytes(root:string):Promise<number> {
  await assertMonitoringPrivateDirectory(root);let total=0,directories=0;
  const namespace=await opendir(root);
  try {
    for await(const entry of namespace) {
      if(!/^monitoring-checkpoint\.(?:(?:generation|stage)-[0-9a-f]{64}(?:\.repair)?|transfer-(?:[1-9][0-9]{0,9}-)?[A-Za-z0-9]+)$/.test(entry.name))continue;
      if(++directories>8)throw new Error("checkpoint.payload_custody_pressure");
      const directory=join(root,entry.name);await assertMonitoringPrivateDirectory(directory);
      const files=await opendir(directory);let count=0;
      for await(const file of files) {
        if(++count>256 || !/^chunk-(?:0|[1-9][0-9]{0,2})\.bin$/.test(file.name))throw new Error("checkpoint.payload_unknown_custody");
        const path=join(directory,file.name),stat=await lstat(path);
        if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink!==1 || stat.size>262144 || HOST.platform!=="win32" && (stat.mode&0o077 || stat.uid!==process.getuid?.()))throw new Error("checkpoint.payload_private");
        if(HOST.platform==="win32") {const proof = isOwnerOnly(path, HOST); if (proof.state !== "private")throw new Error("checkpoint.payload_acl");}
        total+=stat.size;if(total>MONITORING_PAYLOAD_MAX_BYTES)throw new Error("checkpoint.payload_quota");
      }
    }
  }finally {await namespace.close().catch(()=>{});}
  return total;
}

export const MONITORING_TRANSFER_PREFIX = "monitoring-checkpoint.transfer-";
const activeTransfers=new Set<string>();
let pendingTransfers=0;
/** A transfer is disposable source scratch, never an accepted generation or resumable receiver stage. */
export async function createMonitoringTransfer(stateDir:string,current:()=>boolean):Promise<string> {
  if(!current())throw new Error("checkpoint.authority_changed");
  await removeDeadMonitoringTransfers(stateDir,current);
  if(!current())throw new Error("checkpoint.authority_changed");
  if(activeTransfers.size+pendingTransfers>=2)throw new Error("checkpoint.transfer_pressure");
  pendingTransfers++;
  let path:string;
  try{path=await mkdtemp(join(stateDir,MONITORING_TRANSFER_PREFIX)+process.pid+"-");activeTransfers.add(path);}finally{pendingTransfers--; }
  if(!current()){await closeMonitoringTransfer(path);throw new Error("checkpoint.authority_changed");}
  return path;
}
export async function closeMonitoringTransfer(path:string):Promise<void> {
  if(!activeTransfers.has(path))return;
  try{await rm(path,{recursive:true,force:true});}finally{activeTransfers.delete(path);}
}
async function removeDeadMonitoringTransfers(stateDir:string,current:()=>boolean):Promise<void> {
  await assertMonitoringPrivateDirectory(stateDir);
  const namespace=await opendir(stateDir);let count=0;
  for await(const entry of namespace){
    if(!entry.name.startsWith(MONITORING_TRANSFER_PREFIX))continue;
    if(++count>8)throw new Error("checkpoint.transfer_orphan_pressure");
    if(!current())throw new Error("checkpoint.authority_changed");
    const match=/^monitoring-checkpoint\.transfer-([1-9][0-9]{0,9})-[A-Za-z0-9]+$/.exec(entry.name);
    // Unqualified legacy names have no OS owner witness; preserve their bytes and quota custody.
    if(match===null)continue;
    const path=join(stateDir,entry.name);
    if(activeTransfers.has(path))continue;
    let exited=false;
    try{process.kill(Number(match[1]),0);}catch(cause){exited=typeof cause==="object" && cause!==null && "code" in cause && cause.code==="ESRCH";}
    // Unsupported probes, live processes and unknown errors never authorize deletion.
    if(!exited)continue;
    await assertMonitoringPrivateDirectory(path);
    const files=await opendir(path);let chunks=0;
    for await(const file of files){
      if(++chunks>256 || !/^chunk-(?:0|[1-9][0-9]{0,2})\.bin$/.test(file.name))throw new Error("checkpoint.transfer_unknown_custody");
      const stat=await lstat(join(path,file.name));
      if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink!==1 || stat.size>262144 || HOST.platform!=="win32" && (stat.mode&0o077 || stat.uid!==process.getuid?.()))throw new Error("checkpoint.transfer_private");
      if(HOST.platform==="win32"){const proof = isOwnerOnly(join(path,file.name), HOST); if (proof.state !== "private")throw new Error("checkpoint.transfer_acl");}
    }
    if(!current())throw new Error("checkpoint.authority_changed");
    await rm(path,{recursive:true});
  }
}
