import { expect, test } from "bun:test";
import { gzipSync, brotliCompressSync } from "node:zlib";
import { generateKeyPairSync, sign, randomUUID, createHmac } from "node:crypto";
import { NATIVE_REDIRECT, parseProfile, isObject, type EcPublicJwk, type NativeAccessProfile } from "./contracts.ts";
import { digest, strictJson, thumbprint } from "./jose.ts";
import { DeviceBindings } from "./device-bindings.ts";
import { GatewayIngress } from "./ingress.ts";
import { ProofReplayStore } from "./replay.ts";
import { NativeIdentityGateway } from "./gateway.ts";
import type { PrivateJsonIo } from "./private-io.ts";
import type { JsonObject, JsonValue } from "../json.ts";
function memory(): PrivateJsonIo { let value: JsonValue | null = null; return { read: () => structuredClone(value), write: (v) => { value = structuredClone(v); }, exclusive: (op) => op() }; }
function setup(options: {forward?: (request: Request) => Promise<Response>; requestBudgetMs?: number; issuer?: string; upstream?: string; network?: boolean} = {}) {
  const issuer = generateKeyPairSync("ec", { namedCurve: "prime256v1" }); const phone = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  // SAFETY: node exports the generated P-256 public key, never its private key; profile parser validates the full JWK.
  const jwk = phone.publicKey.export({ format: "jwk" }) as EcPublicJwk;
  const profile = profileFromDocument({ schema: 1, issuer: options.issuer ?? "https://issuer.example/", clientId: "collie-native", audience: "collie-gateway", redirectUri: NATIVE_REDIRECT, authorizationEndpoint: "https://issuer.example/authorize", tokenEndpoint: "https://issuer.example/token", jwksEndpoint: "https://issuer.example/jwks", gatewayOrigin: "https://collie.example/", algorithms: ["ES256"], jwks: [{ ...issuer.publicKey.export({ format: "jwk" }), kid: "issuer-1" }] });
  let now = 100_000; const bindingsIo = memory(); const bindings = new DeviceBindings(bindingsIo); const paired = "1".repeat(64);
  const binding = bindings.enroll({ issuer: profile.issuer, subject: "operator", thumbprint: thumbprint(jwk) }, paired, "phone");
  const replayIo = memory(); const replay = new ProofReplayStore(replayIo, () => now); const ingress = new GatewayIngress(Buffer.alloc(32, 1), bindings, new ProofReplayStore(memory(), () => now), "https://collie.example/mounted/", digest(JSON.stringify(profile)), () => now);
  const jwt = (header: FixtureJoseHeader, body: JsonValue, key = issuer.privateKey) => { const input = `${Buffer.from(JSON.stringify(header)).toString("base64url")}.${Buffer.from(JSON.stringify(body)).toString("base64url")}`; return `${input}.${sign("sha256", Buffer.from(input), { key, dsaEncoding: "ieee-p1363" }).toString("base64url")}`; };
  const token = (fields: JsonObject = {}) => jwt({ alg: "ES256", typ: "at+jwt", kid: "issuer-1" }, { iss: profile.issuer, aud: profile.audience, sub: "operator", client_id: profile.clientId, iat: 90, exp: 400, cnf: { jkt: thumbprint(jwk) }, ...fields });
  const access = token(); let calls = 0; let forwarded: Request | null = null;
  const gateway = new NativeIdentityGateway({ profile, publicBase: ingress.publicBase, upstream: options.upstream ?? "http://127.0.0.1:8787/", identityHeader: "Tailscale-User-Login", deviceHeader: "X-Device", mappedLogin: "operator@example", bindings, ingress, replay, nonceKey: Buffer.alloc(32, 2), requestBudgetMs: options.requestBudgetMs, now: () => now, forward: options.network ? undefined : options.forward ?? (async (req) => { calls++; forwarded = req; const owner = ingress.verify(req, paired); if (!owner) return new Response("refused", { status: 403 }); return new Response("paired response", { headers: { "x-collie-error-source": "collie", "x-collie-native-ingress": "hostile", "set-cookie": "never" } }); }) });
  const request = (proof?: string, bearer = "pairing-bearer", credential = access, method = "GET", path = "/mounted/api/files?pane=p1") => { const headers = new Headers({ authorization: `Bearer ${bearer}`, "collie-proxy-authorization": `DPoP ${credential}`, "X-Device": "spoofed", "Tailscale-User-Login": "spoofed", "Forwarded": "host=evil.example", "x-collie-response-source": "collie", "x-crew-paired-identity": "spoof" }); if (proof) headers.set("dpop", proof); return new Request(`https://collie.example${path}`, { method, headers }); };
  const proof = (nonce: string, fields: JsonObject = {}) => jwt({ alg: "ES256", typ: "dpop+jwt", jwk }, { jti: randomUUID(), htm: "GET", htu: "https://collie.example/mounted/api/files", iat: Math.floor(now / 1000), ath: digest(access), nonce, ...fields }, phone.privateKey);
  return { gateway, request, proof, token, freshIngress: (profileId = digest(JSON.stringify(profile))) => new GatewayIngress(Buffer.alloc(32,1),bindings,new ProofReplayStore(memory(),()=>now),ingress.publicBase,profileId,()=>now), bindings, bindingsIo, binding, access, paired, profile, ingress, advance: (ms: number) => { now += ms; }, calls: () => calls, forwarded: () => forwarded, replayIo };
}
test("actual gateway keeps pairing header and consumes independent identity/proof, strips all phone trust headers", async () => {
  const s = setup(); const challenge = await s.gateway.fetch(s.request()); expect(challenge.status).toBe(401); expect(s.calls()).toBe(0); const nonce = challenge.headers.get("dpop-nonce")!;
  const answer = await s.gateway.fetch(s.request(s.proof(nonce))); expect(answer.status).toBe(200); expect(await answer.text()).toBe("paired response"); expect(s.calls()).toBe(1);
  const req = s.forwarded()!; expect(req.url).toBe("http://127.0.0.1:8787/api/files?pane=p1"); expect(req.headers.get("authorization")).toBe("Bearer pairing-bearer"); expect(req.headers.get("X-Device")).toBe("phone"); expect(req.headers.get("Tailscale-User-Login")).toBe("operator@example"); for (const header of ["dpop","collie-proxy-authorization","forwarded","x-crew-paired-identity","x-collie-response-source"]) expect(req.headers.get(header)).toBeNull(); expect(answer.headers.get("set-cookie")).toBeNull(); expect(answer.headers.get("x-collie-native-ingress")).toBeNull();
});
test("proof replay is durably refused before a second upstream call", async () => { const s = setup(); const nonce = (await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!; const p = s.proof(nonce); expect((await s.gateway.fetch(s.request(p))).status).toBe(200); expect((await s.gateway.fetch(s.request(p))).status).toBe(401); expect(s.calls()).toBe(1); });
test("wrong method/path/token hash/nonce/key witnesses and token audience/issuer/expiry never reach local Collie", async () => {
 const s=setup();const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;
 for(const fields of [{htm:"POST"},{htu:"https://evil.example/mounted/api/files"},{htu:"https://collie.example/api/files"},{ath:"x".repeat(43)},{nonce:"wrong"}])expect((await s.gateway.fetch(s.request(s.proof(nonce,fields)))).status).toBe(401);
 for(const fields of [{aud:"wrong"},{iss:"https://evil.example/"},{exp:99},{cnf:{jkt:"x".repeat(43)}}])expect((await s.gateway.fetch(s.request(s.proof(nonce),"pairing",s.token(fields)))).status).toBe(401);expect(s.calls()).toBe(0);
});
test("expiry is identity reauthentication without binding retirement; revoked map is separate device refusal and durable exact outbox", async()=>{
 const s=setup();s.advance(301_000);const expired=await s.gateway.fetch(s.request());expect(await expired.json()).toMatchObject({factor:"identity",code:"identity.expired"});expect(s.bindings.pendingRetirements()).toHaveLength(0);
 const t=setup();expect(t.bindings.revoke(t.binding.id)).toBe(true);const n=(await t.gateway.fetch(t.request())).headers.get("dpop-nonce")!;expect(await (await t.gateway.fetch(t.request(t.proof(n)))).json()).toMatchObject({factor:"device",code:"device.binding_revoked"});expect(t.calls()).toBe(0);const restarted=new DeviceBindings(t.bindingsIo);expect(restarted.pendingRetirements()).toHaveLength(1);expect(restarted.current(t.binding)).toBeNull();
});
test("trusted ingress rejects mismatched pairing, changed method/mount/body trust stamp and missing or replaced binding",async()=>{
 const s=setup();const n=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;await s.gateway.fetch(s.request(s.proof(n)));const r=s.forwarded()!;
 expect(s.ingress.verify(new Request(r,{headers:new Headers({authorization:"Bearer different","x-collie-native-ingress":r.headers.get("x-collie-native-ingress")!})}),s.paired)).toBeNull();expect(s.ingress.verify(r,"2".repeat(64))).toBeNull();expect(s.ingress.verify(new Request(r,{method:"POST"}),s.paired)).toBeNull();
});
test("closed crypto JSON refuses duplicate escaped property aliases, issuer key extensions, wrong profile and token type",()=>{
 expect(()=>strictJson('{"iss":"a","\\u0069ss":"b"}')).toThrow();expect(()=>strictJson('{"a":{"x":1,"x":2}}')).toThrow(); const s=setup();expect(()=>profileFromDocument({...s.profile,clientSecret:"must-not-import"})).toThrow();expect(()=>profileFromDocument({...s.profile,redirectUri:"com.evil:/callback"})).toThrow();expect(()=>profileFromDocument({...s.profile,tokenEndpoint:"https://evil.example/token"})).toThrow();expect(()=>profileFromDocument({...s.profile,jwks:[{...s.profile.jwks[0],d:"private"}]})).toThrow();
});

test("gateway deadlines bound stalled upstream and unread streams without exhausting request slots", async () => {
  const stalled = setup({forward: () => new Promise<Response>(() => {}), requestBudgetMs: 10});
  const nonce = (await stalled.gateway.fetch(stalled.request())).headers.get("dpop-nonce")!;
  for(let n=0;n<18;n++) expect((await stalled.gateway.fetch(stalled.request(stalled.proof(nonce)))).status).toBe(503);
  const stream = setup({forward: async () => new Response(new ReadableStream({pull: () => new Promise<void>(() => {}), cancel: () => new Promise<void>(() => {})})), requestBudgetMs: 10});
  const challenge = (await stream.gateway.fetch(stream.request())).headers.get("dpop-nonce")!;
  for(let n=0;n<18;n++) { const response=await stream.gateway.fetch(stream.request(stream.proof(challenge)));expect(response.status).toBe(200); await expect(response.text()).rejects.toThrow(); }
});
test("current binding and expiry are checked at every actual response chunk; capacity returns after refusal",async()=>{
 const s=setup({forward:async()=>new Response(new ReadableStream({start(c){c.enqueue(new Uint8Array([1]));c.enqueue(new Uint8Array([2]));c.close();}}))});
 const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;const response=await s.gateway.fetch(s.request(s.proof(nonce)));const reader=response.body!.getReader();expect((await reader.read()).value).toEqual(new Uint8Array([1]));
 s.bindings.revoke(s.binding.id);await expect(reader.read()).rejects.toThrow();
 expect((await s.gateway.fetch(s.request(s.proof(nonce)))).status).toBe(403);
});

test("private ingress independently binds full stripped target/query and exact declared profile, with a fresh replay positive control",async()=>{
 const s=setup();const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;const response=await s.gateway.fetch(s.request(s.proof(nonce)));await response.text();const original=s.forwarded()!;
 const fresh=s.freshIngress();const changed=new URL(original.url);changed.search="?pane=replacement&session=other";
 expect(fresh.verify(new Request(changed,original),s.paired)).toBeNull();expect(s.freshIngress("B".repeat(43)).verify(original,s.paired)).toBeNull();expect(fresh.verify(original,s.paired)).toMatchObject({id:s.binding.id,pairedDeviceId:s.paired,revision:s.binding.revision});
});

test("crypto parser bounds nesting and numeric JWT clock claims never coerce strings/fractions",async()=>{
 expect(()=>strictJson('{"x":'+"[".repeat(33)+"0"+"]".repeat(33)+'}')).toThrow();
 const s=setup();const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;
 for(const exp of ["400",100.5,-1,Number.MAX_SAFE_INTEGER+1]) expect((await s.gateway.fetch(s.request(s.proof(nonce),"pairing",s.token({exp})))).status).toBe(401);
 expect(s.calls()).toBe(0);expect((await s.gateway.fetch(s.request(s.proof(nonce)))).status).toBe(200);expect(s.calls()).toBe(1);
});

function profileFromDocument(value: FixtureProfileDocument) { return parseProfile(strictJson(JSON.stringify(value))); }

interface FixtureJoseHeader { alg: string; typ?: string; kid?: string; jwk?: EcPublicJwk }
type FixtureProfileDocument = Omit<NativeAccessProfile, "redirectUri" | "algorithms" | "jwks"> & { redirectUri: string; algorithms: readonly string[]; jwks: readonly (import("node:crypto").webcrypto.JsonWebKey & { readonly kid?: string })[]; clientSecret?: string };


test("only closed visible pane/history/chat GET read intent survives stripping; spoofed native and Crew headers never survive",async()=>{
 for (const path of ["/mounted/api/pane/p1","/mounted/api/pane/p1/history","/mounted/api/pane/p1/chat","/mounted/api/config"]) {
  const s=setup(); const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;
  const raw=s.request(s.proof(nonce,{htu:`https://collie.example${path}`}),"pairing-bearer",s.access,"GET",path);
  const headers=new Headers(raw.headers);headers.set("x-collie-seen","1");headers.set("x-collie-native-binding-id","spoof");headers.set("x-collie-native-ingress","spoof");
  const answer=await s.gateway.fetch(new Request(raw,{headers}));expect(answer.status).toBe(200);await answer.text();
  const forwarded=s.forwarded()!;expect(forwarded.headers.get("x-collie-seen")).toBe(path.endsWith("config")?null:"1");
  expect(forwarded.headers.get("x-collie-native-binding-id")).toBeNull();expect(forwarded.headers.get("x-crew-paired-identity")).toBeNull();
  expect(forwarded.headers.get("x-collie-native-ingress")).not.toBe("spoof");expect(forwarded.headers.get("x-collie-response-source")).toBeNull();
 }
});


test("private ingress uses strict bounded seconds expiry, refuses completed expiry at final dispatch and never accepts numeric aliases",async()=>{
 const s=setup();const nonce=(await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;await (await s.gateway.fetch(s.request(s.proof(nonce)))).text();
 const request=s.forwarded()!;
 const header=request.headers.get("x-collie-native-ingress")!;
 const stamp=strictJson(Buffer.from(header.split(".")[0]!,"base64url").toString("utf8"));
 if (!isObject(stamp)) throw new Error("fixture stamp missing");
 expect(stamp.expiresAtSeconds).toBe(130);
 for(const expiry of ["130",130.5,100,-1,130_000,Number.MAX_SAFE_INTEGER+1]) {
  const body=Buffer.from(JSON.stringify({...stamp,expiresAtSeconds:expiry})).toString("base64url");
  const mac=createHmac("sha256",Buffer.alloc(32,1)).update("collie.native.ingress.v1\0").update(body).digest("base64url");
  const headers=new Headers(request.headers);headers.set("x-collie-native-ingress",`${body}.${mac}`);
  expect(s.freshIngress().verify(new Request(request,{headers}),s.paired)).toBeNull();
 }
 const fresh=s.freshIngress();const actor=fresh.verify(request,s.paired)!;expect(actor).not.toBeNull();expect(fresh.identityCurrent(actor)).toBe(true);
 s.advance(30_000);expect(fresh.identityCurrent(actor)).toBe(false);expect(s.freshIngress().verify(request,s.paired)).toBeNull();
 expect(s.bindings.current(s.binding)).not.toBeNull();expect(s.bindings.pendingRetirements()).toHaveLength(0);
});


test("root issuer without slash remains literal through signed-token and binding verification", async () => {
  const s = setup({ issuer: "https://issuer.example" });
  expect(s.profile.issuer).toBe("https://issuer.example");
  const nonce = (await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;
  for (const iss of ["https://issuer.example/", "https://issuer.example.evil", "https://ISSUER.example"]) {
    expect((await s.gateway.fetch(s.request(s.proof(nonce), "pairing", s.token({ iss })))).status).toBe(401);
  }
  expect(s.calls()).toBe(0);
  expect((await s.gateway.fetch(s.request(s.proof(nonce)))).status).toBe(200);
  expect(s.calls()).toBe(1);
  expect(s.bindings.current(s.binding)?.issuer).toBe("https://issuer.example");
});

test("issuer slash exception does not relax other endpoint or canonical authority guards", () => {
  const s = setup();
  for (const issuer of ["https://ISSUER.example", "https://issuer.example:443", "https://user@issuer.example", "https://issuer.example?x=1", "https://issuer.example#fragment", "https://issuer.example/%2f", "https://issuer.example/%5c", "https://issuer.example/../", "https://issuer.example.evil"]) {
    expect(() => profileFromDocument({ ...s.profile, issuer })).toThrow();
  }
  for (const key of ["authorizationEndpoint", "tokenEndpoint", "jwksEndpoint", "gatewayOrigin"] as const) {
    expect(() => profileFromDocument({ ...s.profile, [key]: "https://issuer.example" })).toThrow();
  }
  const local = profileFromDocument({ ...s.profile, issuer: "https://127.0.0.1:46817", authorizationEndpoint: "https://127.0.0.1:46817/authorize", tokenEndpoint: "https://127.0.0.1:46817/token", jwksEndpoint: "https://127.0.0.1:46817/jwks" });
  expect(local.issuer).toBe("https://127.0.0.1:46817");
});


test("default gateway forwarding preserves compressed wire bytes and real downstream decoding", async () => {
 const plain = Buffer.from(JSON.stringify({ available: true, text: "bounded protected response" }));
 for (const [encoding, compressed] of [["gzip", gzipSync(plain)], ["br", brotliCompressSync(plain)]] as const) {
  let calls = 0;
  const upstream = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch(req) {
   calls++;
   expect(new URL(req.url).pathname).toBe("/api/files");
   expect(req.headers.get("authorization")).toBe("Bearer pairing-bearer");
   return new Response(compressed, { headers: { "content-type": "application/json", "content-encoding": encoding, "content-length": String(compressed.length) } });
  }});
  const s = setup({ network: true, upstream: `http://127.0.0.1:${upstream.port}/` });
  const front = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: req => s.gateway.fetch(req) });
  try {
   const nonce = (await s.gateway.fetch(s.request())).headers.get("dpop-nonce")!;
   const forwarded = await s.gateway.fetch(s.request(s.proof(nonce)));
   expect(forwarded.status).toBe(200);
   expect(forwarded.headers.get("content-encoding")).toBe(encoding);
   expect(forwarded.headers.get("content-length")).toBe(String(compressed.length));
   expect(Buffer.from(await forwarded.arrayBuffer())).toEqual(compressed);
   const original = s.request(s.proof(nonce));
   const decoded = await fetch(`http://127.0.0.1:${front.port}/mounted/api/files?pane=p1`, { headers: original.headers });
   expect(decoded.status).toBe(200);
   expect(await decoded.json()).toEqual({ available: true, text: "bounded protected response" });
   expect(calls).toBe(2);
  } finally { front.stop(true); upstream.stop(true); }
 }
});
