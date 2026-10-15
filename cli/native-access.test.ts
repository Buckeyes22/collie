import { expect, test } from "bun:test";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { generateKeyPairSync } from "node:crypto";
import { cmdNativeAccess } from "./native-access.ts";
import { EXIT } from "./io.ts";
import { filePairingIo, PairingStore, newPending } from "../bridge/pairing.ts";
import { NATIVE_REDIRECT } from "../bridge/native-access/contracts.ts";
import { nativeAccessFor } from "../bridge/native-access/config.ts";

test("operator configure/enroll/revoke is concrete, checks existing opaque pairing owner, prints only public profile and preserves bearer registry", async()=>{
 const dir=await mkdtemp(join(tmpdir(), "collie-b24-cli-"));const oldState=process.env.COLLIE_STATE_DIR,oldConfig=process.env.COLLIE_CONFIG_DIR;
 process.env.COLLIE_STATE_DIR=dir;process.env.COLLIE_CONFIG_DIR=dir;
 const output:string[]=[],errors:string[]=[];const io={out:(line:string)=>{output.push(line);},err:(line:string)=>{errors.push(line);}};
 try {
  const issuer=generateKeyPairSync("ec",{namedCurve:"prime256v1"});const input=join(dir,"operator.json");
  await writeFile(input,JSON.stringify({schema:1,port:8788,upstream:"http://127.0.0.1:8787/",publicBase:"https://collie.example/native/",deviceHeader:"X-Device",mappedLogin:"operator@example",profile:{schema:1,issuer:"https://issuer.example/",clientId:"native",audience:"gateway",redirectUri:NATIVE_REDIRECT,authorizationEndpoint:"https://issuer.example/authorize",tokenEndpoint:"https://issuer.example/token",jwksEndpoint:"https://issuer.example/jwks",gatewayOrigin:"https://collie.example/",algorithms:["ES256"],jwks:[{...issuer.publicKey.export({format:"jwk"}),kid:"issuer"}]}}));
  expect(await cmdNativeAccess(["configure",input],io)).toBe(EXIT.OK);
  expect(await cmdNativeAccess(["enroll","https://issuer.example/","operator","A".repeat(43),"absent"],io)).toBe(EXIT.FAIL);
  const pairingIo=filePairingIo(dir);await pairingIo.writePending(newPending("fixture-code",Date.now()));const pairing=new PairingStore(pairingIo);const claim=await pairing.claim("fixture-code","phone");if(!claim.ok)throw new Error("fixture claim failed");
  const before=await readFile(join(dir,"paired-devices.json"),"utf8");
  expect(await cmdNativeAccess(["enroll","https://issuer.example/","operator","A".repeat(43),"phone"],io)).toBe(EXIT.OK);
  const access=nativeAccessFor(dir)!;const binding=access.bindings.list()[0]!;expect(binding.pairedDeviceId).toBe(pairing.identity(claim.token)!);
  expect(await cmdNativeAccess(["profile"],io)).toBe(EXIT.OK);expect(await cmdNativeAccess(["list"],io)).toBe(EXIT.OK);expect(await cmdNativeAccess(["revoke",binding.id],io)).toBe(EXIT.OK);expect(access.bindings.pendingRetirements()).toHaveLength(1);
  expect(await readFile(join(dir,"paired-devices.json"),"utf8")).toBe(before);
  const visible=[...output,...errors].join("\n");expect(visible).not.toContain(claim.token);expect(visible).not.toContain(access.config.ingressKey);expect(visible).not.toContain(access.config.nonceKey);expect(visible).not.toContain("tokenHash");
 }finally{if(oldState===undefined)delete process.env.COLLIE_STATE_DIR;else process.env.COLLIE_STATE_DIR=oldState;if(oldConfig===undefined)delete process.env.COLLIE_CONFIG_DIR;else process.env.COLLIE_CONFIG_DIR=oldConfig;await rm(dir,{recursive:true,force:true});}
});
