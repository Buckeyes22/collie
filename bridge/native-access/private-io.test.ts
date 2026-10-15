import { expect, test } from "bun:test";
import { mkdtempSync, readdirSync, rmSync, writeFileSync, symlinkSync, linkSync, chmodSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HOST } from "../host.ts";
import { aclTool } from "../icacls.ts";
import { isOwnerOnly } from "../owner-only.ts";
import { privateJsonIo } from "./private-io.ts";
import { nativeAccessFor } from "./config.ts";
import { DeviceBindings } from "./device-bindings.ts";
import { ProofReplayStore } from "./replay.ts";
import { digest } from "./jose.ts";
import { drainBindingRetirements, nativeEnrollmentBinding } from "./enrollment.ts";

// Only these newly created fixture paths are ever changed. Windows checks an actual ACL grant;
// chmod on NTFS cannot demonstrate another account being allowed to read a private file.
function fixturePrivacy(path: string, loose: boolean, directory = false): void {
 if(HOST.platform!=="win32"){chmodSync(path,directory?(loose?0o755:0o700):(loose?0o644:0o600));return;}
 const run=aclTool().icacls(loose?[path,"/grant","*S-1-1-0:(R)"]:[path,"/remove:g","*S-1-1-0"]);
 expect(run?.timedOut).toBe(false);expect(run?.code).toBe(0);
 expect(isOwnerOnly(path,HOST).state).toBe(loose?"loose":"private");
}

test("real owner-only atomic registry survives restart and lease cleanup; revoke outbox stays durable until signed dispatcher ACK",async()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-access-"));try{
 const io=privateJsonIo(join(dir,"bindings.json"));const owner=new DeviceBindings(io);const b=owner.enroll({issuer:"https://issuer.example/",subject:"operator",thumbprint:"a".repeat(43)},"a".repeat(64),"phone");
 expect(new DeviceBindings(io).current(b)).toEqual(b);expect(nativeEnrollmentBinding(owner,b,"b".repeat(64))).toBeNull();expect(nativeEnrollmentBinding(owner,b,b.pairedDeviceId)).toEqual({id:b.id,revision:b.revision,pairedDeviceId:b.pairedDeviceId});
 owner.revoke(b.id);expect(await drainBindingRetirements(owner,{retire:async()=>false})).toBe(0);expect(new DeviceBindings(io).pendingRetirements()).toHaveLength(1);
 const received:string[]=[];expect(await drainBindingRetirements(new DeviceBindings(io),{retire:async(event)=>{received.push(event.eventId);return true;}})).toBe(1);expect(received).toHaveLength(1);expect(owner.pendingRetirements()).toHaveLength(0);
 const text=readFileSync(join(dir,"bindings.json"),"utf8");expect(text).not.toContain("accessToken");expect(text).not.toContain("authorization");
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test("real file boundary refuses symlink, hardlink, loose permissions and pre-read byte bound",()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-private-"));try{
 const target=join(dir,"target.json");writeFileSync(target,"{}",{mode:0o600});const link=join(dir,"link.json");symlinkSync(target,link);expect(()=>privateJsonIo(link).read()).toThrow();rmSync(link);linkSync(target,link);expect(()=>privateJsonIo(link).read()).toThrow();rmSync(link);
 fixturePrivacy(target,true);expect(()=>privateJsonIo(target).read()).toThrow();fixturePrivacy(target,false);writeFileSync(target,"x".repeat(1025));expect(()=>privateJsonIo(target,1024).read()).toThrow();
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test("proof consumed before dispatch cannot replay after constructing a new actual filesystem registry",()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-replay-"));try{const io=privateJsonIo(join(dir,"replay.json"));const hash=digest("actual-proof");expect(new ProofReplayStore(io,()=>1000).consume(hash)).toBe(true);expect(new ProofReplayStore(io,()=>1001).consume(hash)).toBe(false);expect(new ProofReplayStore(io,()=>91001).consume(hash)).toBe(true);}finally{rmSync(dir,{recursive:true,force:true});}
});
test("binding replacement retires exact old enrollment; unrelated binding and token-expiry lifetime never retired",()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-bindings-"));try{const store=new DeviceBindings(privateJsonIo(join(dir,"bindings.json")));const identity={issuer:"https://issuer.example/",subject:"operator",thumbprint:"a".repeat(43)};const first=store.enroll(identity,"a".repeat(64),"phone");const other=store.enroll({...identity,subject:"other"},"b".repeat(64),"tablet");const newer=store.enroll(identity,"c".repeat(64),"new-phone");expect(store.current(first)).toBeNull();expect(store.current(other)).toEqual(other);expect(store.current(newer)).toEqual(newer);expect(store.pendingRetirements()).toMatchObject([{bindingId:first.id,revision:first.revision,pairedDeviceId:first.pairedDeviceId}]);}finally{rmSync(dir,{recursive:true,force:true});}
});

test("an unconfigured native gateway read has no filesystem tax or background custody",()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-off-"));try{expect(nativeAccessFor(dir)).toBeNull();expect(readdirSync(dir)).toEqual([]);}finally{rmSync(dir,{recursive:true,force:true});}
});

test("optional gateway leaves existing nonprivate state alone but present unsafe configuration still refuses",()=>{
 const dir=mkdtempSync(join(tmpdir(),"collie-native-legacy-"));try{
  fixturePrivacy(dir,true,true);expect(nativeAccessFor(dir)).toBeNull();expect(readdirSync(dir)).toEqual([]);
  const config=join(dir,"native-access.json");writeFileSync(config,"{}",{mode:0o600});expect(()=>nativeAccessFor(dir)).toThrow(HOST.platform==="win32"?"native.private_acl":"native.private_owner");
  rmSync(config);fixturePrivacy(dir,false,true);symlinkSync(join(dir,"missing-target.json"),config);expect(()=>nativeAccessFor(dir)).toThrow("native.private_file");
 }finally{rmSync(dir,{recursive:true,force:true});}
});
