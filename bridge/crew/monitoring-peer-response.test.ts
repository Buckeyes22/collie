import { expect,test } from "bun:test";
import { PeerClient } from "./peer-client.ts";
import { PROTOCOL_HEADER,MEMBER_HEADER } from "./admission.ts";
import { CREW_PROTOCOL_VERSION } from "./enrollment.ts";
import type { CheckpointFrame } from "./monitoring-checkpoint.ts";
const link={memberId:"peer",address:"peer.invalid:8787"};
const frame:CheckpointFrame={kind:"commit",checkpointRevision:"1",rootDigest:"a".repeat(64)};
function headers(mime="application/json",protocol=String(CREW_PROTOCOL_VERSION)) {return {[PROTOCOL_HEADER]:protocol,[MEMBER_HEADER]:"peer","content-type":mime};}
function client(response:()=>Response,timeoutMs=50) {return new PeerClient({self:"lead",secret:()=>"test-original-crew-secret",timeoutMs,fetch:async()=>response()});}
test("the production checkpoint dial's original timeout includes a held body after headers",async()=>{
  let canceled=0;
  const stream=new ReadableStream<Uint8Array>({pull:()=>new Promise(()=>{}),cancel:()=>{canceled++;}});
  const outcome=await client(()=>new Response(stream,{headers:headers()})).monitoringCheckpoint(link,frame);
  expect(outcome.ok).toBe(false);expect(!outcome.ok && outcome.state==="unreachable" && outcome.timedOut).toBe(true);expect(canceled).toBe(1);
});
test("checkpoint success/error/MIME/declared/chunked response bounds precede eager JSON allocation",async()=>{
  for(const mode of ["mime","declared","chunked","error"] as const) {
    let canceled=0,pulls=0;
    const stream=new ReadableStream<Uint8Array>({pull(controller){pulls++;controller.enqueue(new Uint8Array(17*1024));},cancel(){canceled++;}},{highWaterMark:0});
    const h=new Headers(headers(mode==="mime"?"text/plain":"application/json"));if(mode==="declared")h.set("content-length","17408");
    const response=new Response(stream,{headers:h,status:mode==="error"?503:200});
    response.json=()=>{throw new Error("original eager json must never be called");};
    const outcome=await client(()=>response).monitoringCheckpoint(link,frame);
    expect(outcome.ok).toBe(false);expect(canceled).toBe(1);expect(pulls).toBe(mode==="chunked"?1:0);
  }
  const outcome=await client(()=>Response.json({accepted:true,checkpointRevision:"1",rootDigest:"a".repeat(64)},{headers:headers()})).monitoringCheckpoint(link,frame);
  expect(outcome.ok).toBe(true);expect(outcome.ok&&outcome.value).toEqual({accepted:true,checkpointRevision:"1",rootDigest:"a".repeat(64)});
});
test("foreign protocol remains the original incompatible verdict without touching its body",async()=>{
  let canceled=0,pulls=0;
  const stream=new ReadableStream<Uint8Array>({pull(){pulls++;},cancel(){canceled++;}},{highWaterMark:0});
  const outcome=await client(()=>new Response(stream,{headers:headers("application/json","999")})).monitoringCheckpoint(link,frame);
  expect(outcome.ok).toBe(false);expect(!outcome.ok&&outcome.state).toBe("incompatible");expect(pulls).toBe(0);expect(canceled).toBe(1);
});
