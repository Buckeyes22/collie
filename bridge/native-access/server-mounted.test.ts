import { expect, test } from "bun:test";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { generateKeyPairSync, sign, randomUUID } from "node:crypto";
import { startServer } from "../server.ts";
import { loadConfig } from "../config.ts";
import { SessionRegistry } from "../sessions.ts";
import { StateEngine } from "../state-engine.ts";
import { EventPoker } from "../event-poker.ts";
import { NotificationCoordinator } from "../notifications.ts";
import { Push } from "../push.ts";
import { Snooze } from "../snooze.ts";
import { NotifyPrefsStore } from "../notify-prefs.ts";
import { UpdateMonitor, UpdateStateStore } from "../update.ts";
import { AuditLog } from "../audit.ts";
import { ActivityLedger } from "../activity.ts";
import { SOLO_RUNTIME } from "../crew/config.ts";
import { PairingStore, filePairingIo, newPending } from "../pairing.ts";
import { FakeHerdr } from "../mux/herdr/fixture.ts";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { NativeIdentityGateway } from "./gateway.ts";
import { DeviceBindings } from "./device-bindings.ts";
import { GatewayIngress } from "./ingress.ts";
import { ProofReplayStore } from "./replay.ts";
import { privateJsonIo } from "./private-io.ts";
import { parseProfile, NATIVE_REDIRECT, type EcPublicJwk, type NativeAccessProfile } from "./contracts.ts";
import { digest, thumbprint, strictJson } from "./jose.ts";
import type { JsonValue } from "../json.ts";

test("actual mounted central Collie requires verified proxy AND pairing; read-only binding reads config but not Files or terminal writes",async()=>{
 const root=await mkdtemp("/var/tmp/collie-b24-mounted-");
 const state=join(root,"state"),code=join(root,"code");await mkdir(state,{mode:0o700});await mkdir(code);await writeFile(join(code,"fixture.txt"),"bounded fixture content");
 const cfg={...loadConfig({COLLIE_HOST:"127.0.0.1",COLLIE_CONFIG_DIR:join(state,"config"),COLLIE_STATE_DIR:state,COLLIE_BASE_PATH:"/browser/",COLLIE_SKIP_SERVE:"1",COLLIE_TRUSTED_USER:"operator@example",COLLIE_DEVICE_HEADER:"X-Device",COLLIE_DEVICE_ALLOWLIST:"writer",COLLIE_ALLOWED_ORIGINS:"https://gateway.example"}),port:0};
 const fake=new FakeHerdr();const created=await fake.createWorkspace({cwd:code,label:"fixture-files"});const mux=new HerdrMux(fake,()=>[]),pane=(await mux.snapshot()).panes.find(row=>row.paneId===created.paneId)!;
 const registry=new SessionRegistry({configRoot:root,primarySocketPath:join(root,"socket"),multiSession:false,factory:()=>({herdr:mux,engine:new StateEngine(mux,1500),poker:new EventPoker(mux),notifications:new NotificationCoordinator({schedule:setTimeout,cancel:clearTimeout},{render:()=>{},clear:()=>{}},1000,()=>false)})});
 const engine=registry.get()!.engine;const ready=new Promise<void>(resolve=>{const stop=engine.onUpdate(()=>{stop();resolve();});});engine.start();await ready;
 const io=filePairingIo(root),pairing=new PairingStore(io);await io.writePending(newPending("fixture-code",Date.now()));const enrolled=await pairing.claim("fixture-code","reader");if(!enrolled.ok)throw new Error("pair fixture failed");const enrolledToken=enrolled.token;
 const issuer=generateKeyPairSync("ec",{namedCurve:"prime256v1"}),phone=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 // SAFETY: public export of actual generated P-256 key only; issuer profile checks public shape.
 const key=phone.publicKey.export({format:"jwk"}) as EcPublicJwk;
 const profile=profileFromDocument({schema:1,issuer:"https://issuer.example/",clientId:"native",audience:"gateway",redirectUri:NATIVE_REDIRECT,authorizationEndpoint:"https://issuer.example/authorize",tokenEndpoint:"https://issuer.example/token",jwksEndpoint:"https://issuer.example/jwks",gatewayOrigin:"https://gateway.example/",algorithms:["ES256"],jwks:[{...issuer.publicKey.export({format:"jwk"}),kid:"issuer"}]});
 const bindings=new DeviceBindings(privateJsonIo(join(root,"bindings.json")));const binding=bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolled.token)!,"reader");
 const ingress=new GatewayIngress(Buffer.alloc(32,1),bindings,new ProofReplayStore(privateJsonIo(join(root,"ingress-replay.json"))),"https://gateway.example/mount/",digest(JSON.stringify(profile)));
 const updateMonitor=new UpdateMonitor({repo:"fixture/fixture",current:"1.0.0",startupStamp:"fixture",fetchTags:async()=>[],bridgeStamp:()=>"fixture",installKind:"detached-checkout",instance:null,bootVersion:"1.0.0",liveVersion:()=>"1.0.0",exeReplaced:()=>false,packageCommand:null,store:new UpdateStateStore(cfg),now:Date.now,updatesEnabled:()=>false,notify:()=>{},crewProtocol:2,crewMode:()=>"solo",fetchReleaseReading:async()=>null,runState:()=>null});
 const activity = new ActivityLedger(cfg);
 const server=await startServer({cfg,registry,push:new Push(cfg),snooze:new Snooze(cfg),notifyPrefs:new NotifyPrefsStore(cfg),updateMonitor,version:"fixture",audit:new AuditLog(async()=>{}),activity:activity,crew:SOLO_RUNTIME,pairing,nativeIngress:{ingress,mappedLogin:"operator@example"}});
 const gateway=new NativeIdentityGateway({profile,publicBase:ingress.publicBase,upstream:`http://127.0.0.1:${server.port}/`,identityHeader:"Tailscale-User-Login",deviceHeader:"X-Device",mappedLogin:"operator@example",bindings,ingress,replay:new ProofReplayStore(privateJsonIo(join(root,"proof-replay.json"))),nonceKey:Buffer.alloc(32,2)});
 const jwt=(header:FixtureJoseHeader,payload:JsonValue,privateKey=issuer.privateKey)=>{const input=`${Buffer.from(JSON.stringify(header)).toString("base64url")}.${Buffer.from(JSON.stringify(payload)).toString("base64url")}`;return `${input}.${sign("sha256",Buffer.from(input),{key:privateKey,dsaEncoding:"ieee-p1363"}).toString("base64url")}`;};
 const now=Math.floor(Date.now()/1000);const access=jwt({alg:"ES256",typ:"at+jwt",kid:"issuer"},{iss:profile.issuer,client_id:profile.clientId,aud:profile.audience,sub:"operator",iat:now,exp:now+120,cnf:{jkt:thumbprint(key)}});
 async function request(path:string,method="GET",paired=enrolledToken,body?:string,seen=false){
  const url=`https://gateway.example/mount/api/${path}`;const headers=new Headers({authorization:`Bearer ${paired}`,"collie-proxy-authorization":`DPoP ${access}`,origin:"https://gateway.example"});
  if (seen) { headers.set("x-collie-seen","1"); headers.set("x-collie-response-source","spoof"); headers.set("x-crew-paired-identity","spoof"); }
  const nonce=(await gateway.fetch(new Request(url,{method,headers}))).headers.get("dpop-nonce")!;
  headers.set("dpop",jwt({alg:"ES256",typ:"dpop+jwt",jwk:key},{jti:randomUUID(),htm:method,htu:url.split('?')[0],iat:Math.floor(Date.now()/1000),ath:digest(access),nonce},phone.privateKey));
  const init: RequestInit = {method,headers}; if(body !== undefined) init.body=body;
  return gateway.fetch(new Request(url,init));
 }
 try{
  expect((await fetch(`http://127.0.0.1:${server.port}/mount/api/config`,{headers:{authorization:`Bearer ${enrolled.token}`,"X-Device":"writer","Tailscale-User-Login":"spoof"}})).status).toBe(403);
  expect((await request("config")).status).toBe(200);
  // The actual owning ledger, not a decoder/header assertion, distinguishes background
  // reads from an admitted visible read through the mounted native gateway.
  expect(activity.get(registry.get()!.name,pane.paneId)?.seenAt ?? 0).toBe(0);
  expect((await request(`pane/${encodeURIComponent(pane.paneId)}`)).status).toBe(200);
  expect(activity.get(registry.get()!.name,pane.paneId)?.seenAt ?? 0).toBe(0);
  expect((await request(`pane/${encodeURIComponent(pane.paneId)}`,"GET",enrolled.token,undefined,true)).status).toBe(200);
  expect(activity.get(registry.get()!.name,pane.paneId)!.seenAt).toBeGreaterThan(0);
  const originalBrowser = `http://127.0.0.1:${server.port}/browser/api/config`;
  // An existing authenticated browser front door retains the browser mount and injects its
  // authenticated identity; its device/pairing gates remain those of the original dispatcher.
  expect((await fetch(originalBrowser,{headers:{authorization:`Bearer ${enrolled.token}`,"X-Device":"reader","Tailscale-User-Login":"operator@example"}})).status).toBe(200);
  expect((await fetch(originalBrowser,{headers:{authorization:`Bearer ${enrolled.token}`,"X-Device":"reader"}})).status).toBe(403);
  const nativeWrongMount=await gateway.fetch(new Request("https://gateway.example/browser/api/config",{headers:{authorization:`Bearer ${enrolled.token}`,"collie-proxy-authorization":`DPoP ${access}`}}));expect(nativeWrongMount.status).toBe(400);
  expect((await fetch(originalBrowser,{headers:{authorization:"Bearer wrong-pairing","X-Device":"reader","Tailscale-User-Login":"operator@example"}})).status).toBe(403);
  expect((await fetch(`http://127.0.0.1:${server.port}/browser/api/pane/${encodeURIComponent(pane.paneId)}/keys`,{method:"POST",headers:{authorization:`Bearer ${enrolled.token}`,"X-Device":"reader","Tailscale-User-Login":"operator@example",origin:"https://gateway.example","content-type":"application/json"},body:JSON.stringify({keys:["Enter"]})})).status).toBe(403);

  const wrong=await request("config","GET","wrong-pairing");expect(wrong.status).toBe(403);expect(wrong.headers.get("x-collie-error-factor")).toBe("pairing");
  const files=await request(`pane/${encodeURIComponent(pane.paneId)}/files`);expect(files.status).toBe(403);expect(files.headers.get("x-collie-error-factor")).toBe("device");
  const denied=await request(`pane/${encodeURIComponent(pane.paneId)}/keys`,"POST",enrolled.token,JSON.stringify({keys:["Enter"]}));expect(denied.status).toBe(403);expect(fake.writes()).toHaveLength(0);
  await io.writePending(newPending("writer-code",Date.now())); const writer=await pairing.claim("writer-code","writer"); if(!writer.ok)throw new Error("writer fixture failed");
  const writerBinding=bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(writer.token)!,"writer");
  const positive=await request(`pane/${encodeURIComponent(pane.paneId)}/keys`,"POST",writer.token,JSON.stringify({keys:["Enter"]})); expect(positive.status).toBe(200);expect(fake.writes()).toHaveLength(1);
  const allowedFiles=await request(`pane/${encodeURIComponent(pane.paneId)}/files`,"GET",writer.token);expect(allowedFiles.status).toBe(200);expect(await allowedFiles.json()).toMatchObject({available:true,dir:"",entries:[{name:"fixture.txt",kind:"file"}]});
  const content=await request(`pane/${encodeURIComponent(pane.paneId)}/files?path=fixture.txt`,"GET",writer.token);expect(content.status).toBe(200);expect(await content.json()).toMatchObject({available:true,path:"fixture.txt",text:"bounded fixture content"});
  expect(bindings.pendingRetirements()).toHaveLength(1);expect(bindings.current(binding)).toBeNull();
  bindings.revoke(writerBinding.id);expect((await request("config")).status).toBe(403);expect(pairing.resolve(enrolled.token)?.label).toBe("reader");expect(bindings.pendingRetirements()).toHaveLength(2);
 }finally{registry.disposeAll();await server.stop(true);await pairing.idle();await rm(root,{recursive:true,force:true});}
});

function profileFromDocument(value: FixtureProfileDocument) { return parseProfile(strictJson(JSON.stringify(value))); }

interface FixtureJoseHeader { alg: string; typ?: string; kid?: string; jwk?: EcPublicJwk }
type FixtureProfileDocument = Omit<NativeAccessProfile, "redirectUri" | "algorithms" | "jwks"> & { redirectUri: string; algorithms: readonly string[]; jwks: readonly (import("node:crypto").webcrypto.JsonWebKey & { readonly kid?: string })[]; clientSecret?: string };


test("mounted final dispatch refuses completed binding revoke/replacement, role change and access expiry during actual prompt preflight; fresh owner sends once",async()=>{
 const root=await mkdtemp("/var/tmp/collie-b24-mounted-");
 const state=join(root,"state"),code=join(root,"code");await mkdir(state,{mode:0o700});await mkdir(code);await writeFile(join(code,"fixture.txt"),"bounded fixture content");
 const cfg={...loadConfig({COLLIE_HOST:"127.0.0.1",COLLIE_CONFIG_DIR:join(state,"config"),COLLIE_STATE_DIR:state,COLLIE_BASE_PATH:"/browser/",COLLIE_SKIP_SERVE:"1",COLLIE_TRUSTED_USER:"operator@example",COLLIE_DEVICE_HEADER:"X-Device",COLLIE_DEVICE_ALLOWLIST:"writer",COLLIE_ALLOWED_ORIGINS:"https://gateway.example"}),port:0};
 const fake=new FakeHerdr();const created=await fake.createWorkspace({cwd:code,label:"fixture-files"});const mux=new HerdrMux(fake,()=>[]),pane=(await mux.snapshot()).panes.find(row=>row.paneId===created.paneId)!;
 const registry=new SessionRegistry({configRoot:root,primarySocketPath:join(root,"socket"),multiSession:false,factory:()=>({herdr:mux,engine:new StateEngine(mux,1500),poker:new EventPoker(mux),notifications:new NotificationCoordinator({schedule:setTimeout,cancel:clearTimeout},{render:()=>{},clear:()=>{}},1000,()=>false)})});
 const engine=registry.get()!.engine;const ready=new Promise<void>(resolve=>{const stop=engine.onUpdate(()=>{stop();resolve();});});engine.start();await ready;
 const io=filePairingIo(root),pairing=new PairingStore(io);await io.writePending(newPending("fixture-code",Date.now()));const enrolled=await pairing.claim("fixture-code","writer");if(!enrolled.ok)throw new Error("pair fixture failed");let enrolledToken=enrolled.token;
 const issuer=generateKeyPairSync("ec",{namedCurve:"prime256v1"}),phone=generateKeyPairSync("ec",{namedCurve:"prime256v1"});
 // SAFETY: public export of actual generated P-256 key only; issuer profile checks public shape.
 const key=phone.publicKey.export({format:"jwk"}) as EcPublicJwk;
 const profile=profileFromDocument({schema:1,issuer:"https://issuer.example/",clientId:"native",audience:"gateway",redirectUri:NATIVE_REDIRECT,authorizationEndpoint:"https://issuer.example/authorize",tokenEndpoint:"https://issuer.example/token",jwksEndpoint:"https://issuer.example/jwks",gatewayOrigin:"https://gateway.example/",algorithms:["ES256"],jwks:[{...issuer.publicKey.export({format:"jwk"}),kid:"issuer"}]});
 const bindings=new DeviceBindings(privateJsonIo(join(root,"bindings.json")));bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolled.token)!,"writer");
 let clock = Date.now();
 const ingress=new GatewayIngress(Buffer.alloc(32,1),bindings,new ProofReplayStore(privateJsonIo(join(root,"ingress-replay.json"))),"https://gateway.example/mount/",digest(JSON.stringify(profile)),()=>clock);
 const updateMonitor=new UpdateMonitor({repo:"fixture/fixture",current:"1.0.0",startupStamp:"fixture",fetchTags:async()=>[],bridgeStamp:()=>"fixture",installKind:"detached-checkout",instance:null,bootVersion:"1.0.0",liveVersion:()=>"1.0.0",exeReplaced:()=>false,packageCommand:null,store:new UpdateStateStore(cfg),now:Date.now,updatesEnabled:()=>false,notify:()=>{},crewProtocol:2,crewMode:()=>"solo",fetchReleaseReading:async()=>null,runState:()=>null});
 const activity = new ActivityLedger(cfg);
 let deposed = false;
 const server=await startServer({cfg,registry,push:new Push(cfg),snooze:new Snooze(cfg),notifyPrefs:new NotifyPrefsStore(cfg),updateMonitor,version:"fixture",audit:new AuditLog(async()=>{}),activity:activity,crew:SOLO_RUNTIME,pairing,deposed:()=>deposed?new Response("fixture deposed",{status:503}):null,nativeIngress:{ingress,mappedLogin:"operator@example"}});
 const gateway=new NativeIdentityGateway({profile,publicBase:ingress.publicBase,upstream:`http://127.0.0.1:${server.port}/`,identityHeader:"Tailscale-User-Login",deviceHeader:"X-Device",mappedLogin:"operator@example",bindings,ingress,replay:new ProofReplayStore(privateJsonIo(join(root,"proof-replay.json"))),nonceKey:Buffer.alloc(32,2),now:()=>clock});
 const jwt=(header:FixtureJoseHeader,payload:JsonValue,privateKey=issuer.privateKey)=>{const input=`${Buffer.from(JSON.stringify(header)).toString("base64url")}.${Buffer.from(JSON.stringify(payload)).toString("base64url")}`;return `${input}.${sign("sha256",Buffer.from(input),{key:privateKey,dsaEncoding:"ieee-p1363"}).toString("base64url")}`;};
 let now=Math.floor(clock/1000);const token=()=>jwt({alg:"ES256",typ:"at+jwt",kid:"issuer"},{iss:profile.issuer,client_id:profile.clientId,aud:profile.audience,sub:"operator",iat:now,exp:now+120,cnf:{jkt:thumbprint(key)}});let access=token();
 async function request(path:string,method="GET",paired=enrolledToken,body?:string,seen=false){
  const url=`https://gateway.example/mount/api/${path}`;const headers=new Headers({authorization:`Bearer ${paired}`,"collie-proxy-authorization":`DPoP ${access}`,origin:"https://gateway.example"});
  if (seen) { headers.set("x-collie-seen","1"); headers.set("x-collie-response-source","spoof"); headers.set("x-crew-paired-identity","spoof"); }
  const nonce=(await gateway.fetch(new Request(url,{method,headers}))).headers.get("dpop-nonce")!;
  headers.set("dpop",jwt({alg:"ES256",typ:"dpop+jwt",jwk:key},{jti:randomUUID(),htm:method,htu:url.split('?')[0],iat:Math.floor(clock/1000),ath:digest(access),nonce},phone.privateKey));
  const init: RequestInit = {method,headers}; if(body !== undefined) init.body=body;
  return gateway.fetch(new Request(url,init));
 }
 const originalRead = mux.readGrid.bind(mux);
 const expected = `pane ${pane.paneId} on screen`;
 try {
  for (const change of ["revoke","replace","role","permission","pairing","expiry"] as const) {
   const current = bindings.resolve({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key),expiresAt:now+120}) ?? bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolledToken)!,"writer");
   let entered!: () => void, release!: () => void;
   const held = new Promise<void>(resolve => { release = resolve; });
   const atPreflight = new Promise<void>(resolve => { entered = resolve; });
   mux.readGrid = async (id,query) => { entered(); await held; return originalRead(id,query); };
   const old = request(`pane/${encodeURIComponent(pane.paneId)}/keys`,"POST",enrolledToken,JSON.stringify({keys:["Enter"],expected_prompt:expected}));
   await atPreflight;
   if (change === "revoke") bindings.revoke(current.id);
   else if (change === "replace") bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolledToken)!,"writer");
   if (change === "role") deposed = true;
   if (change === "permission") cfg.deviceAllowlist=[];
   if (change === "pairing") await pairing.revoke("writer");
   if (change === "expiry") clock += 121_000;
   release(); const refused = await old; expect(refused.status).toBe(change === "role" ? 503 : change === "expiry" ? 401 : 403); await refused.text();
   deposed = false; cfg.deviceAllowlist=["writer"];
   if (change === "pairing") { await io.writePending(newPending("fixture-code-new",Date.now()));const freshPair=await pairing.claim("fixture-code-new","writer");if(!freshPair.ok)throw new Error("fresh pair refused");enrolledToken=freshPair.token;bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolledToken)!,"writer"); }
   if (change === "expiry") { now=Math.floor(clock/1000);access=token(); }
   expect(fake.writes()).toHaveLength(0);
   mux.readGrid = originalRead;
  }
  bindings.enroll({issuer:profile.issuer,subject:"operator",thumbprint:thumbprint(key)},pairing.identity(enrolledToken)!,"writer");
  const fresh = await request(`pane/${encodeURIComponent(pane.paneId)}/keys`,"POST",enrolledToken,JSON.stringify({keys:["Enter"],expected_prompt:expected}));
  expect(fresh.status).toBe(200); await fresh.text(); expect(fake.writes()).toHaveLength(1);
 } finally { mux.readGrid=originalRead; registry.disposeAll(); await server.stop(true); await pairing.idle(); await rm(root,{recursive:true,force:true}); }
});
