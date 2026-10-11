import { createHmac, randomUUID } from "node:crypto";
import { verifyAccessToken, verifyProof, digest, equal } from "./jose.ts";
import { NATIVE_ACCESS_LIMITS, publicRequestUri, validDeviceHeader, type NativeAccessProfile } from "./contracts.ts";
import type { DeviceBindings } from "./device-bindings.ts";
import { GatewayIngress, gatewayRefusal } from "./ingress.ts";
import type { ProofReplayStore } from "./replay.ts";
export interface GatewayOptions {
  readonly profile: NativeAccessProfile; readonly publicBase: string; readonly upstream: string;
  readonly identityHeader: string; readonly deviceHeader: string; readonly mappedLogin: string;
  readonly bindings: DeviceBindings; readonly ingress: GatewayIngress; readonly replay: ProofReplayStore;
  readonly nonceKey: Buffer; readonly requestBudgetMs?: number; readonly now?: () => number; readonly forward?: (request: Request) => Promise<Response>;
}
/** No redirect following, cookie import, bearer replacement or automatic protected request replay. */
export class NativeIdentityGateway {
  private active = 0;
  private readonly now: () => number;
  constructor(private readonly options: GatewayOptions) {
    this.now = options.now ?? Date.now;
    const upstream = new URL(options.upstream); const publicBase = new URL(options.publicBase);
    if (upstream.protocol !== "http:" || !["127.0.0.1","[::1]"].includes(upstream.hostname) || upstream.username || upstream.password || upstream.search || upstream.hash || upstream.pathname !== "/" || publicBase.origin + "/" !== options.profile.gatewayOrigin || !options.publicBase.endsWith("/") || options.ingress.publicBase !== options.publicBase || options.nonceKey.length !== 32 || options.requestBudgetMs !== undefined && (!Number.isInteger(options.requestBudgetMs) || options.requestBudgetMs < 1 || options.requestBudgetMs > 30_000) || options.identityHeader !== "Tailscale-User-Login" || !validDeviceHeader(options.deviceHeader)) throw new Error("native.gateway_config");
  }
  async fetch(req: Request): Promise<Response> {
    const suppliedId = req.headers.get("x-collie-native-request-id"); const requestId = suppliedId && /^[A-Za-z0-9_-]{16,64}$/u.test(suppliedId) ? suppliedId : randomUUID(); const opt = this.options; const url = new URL(req.url); const publicOrigin = new URL(opt.publicBase).origin; const publicHost = new URL(opt.publicBase).host; const prefix = `${new URL(opt.publicBase).pathname}api/`;
    if (!(url.origin === publicOrigin || url.protocol === "http:" && (["127.0.0.1", "[::1]"].includes(url.hostname) || url.host === publicHost)) || !url.pathname.startsWith(prefix) || !["GET","HEAD","POST","DELETE","PUT","PATCH"].includes(req.method) || req.headers.get("origin") !== null && req.headers.get("origin") !== publicOrigin) return gatewayRefusal("identity", "identity.request_uri", 400, requestId);
    if (this.active >= 16) return gatewayRefusal("identity", "identity.busy", 503, requestId);
    this.active++;
    let released = false; const release = () => { if(!released) { released=true; this.active--; } };
    let transferred = false;
    try {
      const credential = req.headers.get("collie-proxy-authorization");
      if (!credential?.startsWith("DPoP ") || credential.length > NATIVE_ACCESS_LIMITS.tokenBytes + 5) return gatewayRefusal("identity", "identity.required", 401, requestId);
      const token = credential.slice(5); const identity = verifyAccessToken(token, opt.profile, Math.floor(this.now() / 1000));
      const nonce = this.nonce(identity.thumbprint, token); const proof = req.headers.get("dpop");
      if (!proof) return gatewayRefusal("identity", "use_dpop_nonce", 401, requestId, nonce);
      let jti: string;
      try { jti = verifyProof(proof, identity, token, req.method, publicRequestUri(opt.profile.gatewayOrigin, url.pathname), nonce, Math.floor(this.now() / 1000)); }
      catch { return gatewayRefusal("identity", "use_dpop_nonce", 401, requestId, nonce); }
      if (!opt.replay.consume(digest(`proof\0${identity.thumbprint}\0${jti}`))) return gatewayRefusal("identity", "identity.proof_replayed", 401, requestId);
      const binding = opt.bindings.resolve(identity);
      if (!binding) {
        const old = opt.bindings.list().filter((row) => row.issuer === identity.issuer && row.subject === identity.subject && row.thumbprint === identity.thumbprint && row.state === "revoked").toSorted((a,b) => b.revision - a.revision)[0];
        const refusal = gatewayRefusal("device", old ? "device.binding_revoked" : "device.binding_required", 403, requestId);
        if (old) { refusal.headers.set("x-collie-native-binding-id", old.id); refusal.headers.set("x-collie-native-binding-revision", String(old.revision)); }
        return refusal;
      }
      const headers = new Headers(req.headers);
      const incomingNames = Array.from(headers.keys());
      for (const key of incomingNames) if (/^(?:collie-proxy-authorization|dpop|cookie|forwarded|x-forwarded-.*|tailscale-.*|x-crew-.*|x-collie-.*)$/iu.test(key) || equal(key.toLowerCase(), opt.deviceHeader.toLowerCase()) || equal(key.toLowerCase(), opt.identityHeader.toLowerCase())) headers.delete(key);
      // This existing UI read-intent grants no authority. Reconstruct only its closed value on
      // pane/history/chat GETs; all native/provenance/Crew/identity spoof headers remain stripped.
      if (req.method === "GET" && /^\/[^?#]*api\/pane\/[^/]+(?:\/(?:history|chat))?$/.test(url.pathname) && req.headers.get("x-collie-seen") === "1") headers.set("x-collie-seen", "1");
      headers.set(opt.identityHeader, opt.mappedLogin); headers.set(opt.deviceHeader, binding.deviceName); headers.set("host", publicHost);
      const localPath = `/api/${url.pathname.slice(prefix.length)}`;
      const abort = new AbortController(); const onAbort = () => abort.abort(); req.signal.addEventListener("abort", onAbort, { once: true });
      const timer = setTimeout(() => abort.abort(), opt.requestBudgetMs ?? 30_000);
      const body = req.body ? bounded(req.body, NATIVE_ACCESS_LIMITS.bodyBytes, () => !abort.signal.aborted && opt.bindings.current(binding) !== null && identity.expiresAt > Math.floor(this.now() / 1000), abort.signal) : undefined;
      const local = new Request(`${opt.upstream.replace(/\/$/u, "")}${localPath}${url.search}`, { method: req.method, headers, body, signal: abort.signal });
      headers.set("x-collie-native-ingress", opt.ingress.sign(binding, local, url.pathname, requestId, identity.expiresAt));
      const forwarded = new Request(local, { headers });
      let cleaned = false;
      const cleanup = () => { if (cleaned) return; cleaned = true; clearTimeout(timer); req.signal.removeEventListener("abort", onAbort); abort.signal.removeEventListener("abort", cleanup); abort.abort(); release(); };
      abort.signal.addEventListener("abort", cleanup, {once:true});
      try {
        if (!opt.bindings.current(binding)) { cleanup(); transferred = true; return gatewayRefusal("device", "device.binding_revoked", 403, requestId); }
        const answer = await abortable((opt.forward ?? ((request) => fetch(request, { redirect: "manual" })))(forwarded), abort.signal);
        if (answer.status >= 300 && answer.status < 400 || !opt.bindings.current(binding) || identity.expiresAt <= Math.floor(this.now() / 1000)) { await answer.body?.cancel(); cleanup(); transferred = true; return identity.expiresAt <= Math.floor(this.now() / 1000) ? gatewayRefusal("identity", "identity.expired", 401, requestId) : gatewayRefusal("device", "device.binding_changed", 403, requestId); }
        const responseHeaders = new Headers(answer.headers);
        const responseNames = Array.from(responseHeaders.keys());
        for (const key of responseNames) if (/^(?:x-collie-native-.*|x-collie-response-source|dpop-nonce|set-cookie)$/iu.test(key)) responseHeaders.delete(key);
        responseHeaders.set("x-collie-native-binding-id", binding.id); responseHeaders.set("x-collie-native-binding-revision", String(binding.revision));
        responseHeaders.set("x-collie-response-source", "collie"); responseHeaders.set("x-collie-native-request-id", requestId); responseHeaders.set("dpop-nonce", this.nonce(identity.thumbprint, token)); responseHeaders.set("cache-control", "no-store");
        if (!answer.body) { cleanup(); transferred = true; return new Response(null, { status: answer.status, headers: responseHeaders }); }
        transferred = true;
        return new Response(bounded(answer.body, NATIVE_ACCESS_LIMITS.bodyBytes, () => !abort.signal.aborted && opt.bindings.current(binding) !== null && identity.expiresAt > Math.floor(this.now() / 1000), abort.signal, cleanup), { status: answer.status, headers: responseHeaders });
      } catch { cleanup(); transferred = true; return gatewayRefusal("identity", "identity.upstream_unavailable", 503, requestId); }
    } catch (error) { return gatewayRefusal("identity", error instanceof Error && error.message === "identity.expired" ? "identity.expired" : "identity.invalid", 401, requestId); }
    finally { if (!transferred) release(); }
  }
  private nonce(key: string, token: string): string { return createHmac("sha256", this.options.nonceKey).update(`collie.dpop.nonce.v1\0${key}\0${digest(token)}\0${Math.floor(this.now() / 60_000)}`).digest("base64url"); }
}
async function abortable(pending: Promise<Response>, signal: AbortSignal): Promise<Response> {
  let listener: (() => void) | undefined;
  const cancelled = new Promise<Response>((_resolve, reject) => {
    listener = () => reject(new Error("native.deadline"));
    if(signal.aborted) listener(); else signal.addEventListener("abort", listener, {once:true});
  });
  // An upstream that completes after cancellation never keeps a response stream alive.
  void pending.then((response) => { if(signal.aborted) void response.body?.cancel().catch(() => {}); return undefined; }, () => {});
  try { return await Promise.race([pending, cancelled]); }
  finally { if(listener) signal.removeEventListener("abort", listener); }
}
function bounded(source: ReadableStream<Uint8Array>, max: number, current: () => boolean, signal: AbortSignal, cleanup: () => void = () => {}): ReadableStream<Uint8Array> {
  const reader = source.getReader(); let size = 0; let ended = false; let controllerRef: ReadableStreamDefaultController<Uint8Array> | undefined;
  const finish = () => { if (!ended) { ended = true; signal.removeEventListener("abort", onAbort); cleanup(); } };
  const onAbort = () => { if(ended) return; finish(); controllerRef?.error(new Error("native.deadline")); void reader.cancel().catch(() => {}); };
  return new ReadableStream({ start(controller) { controllerRef=controller; if(signal.aborted) onAbort(); else signal.addEventListener("abort", onAbort, {once:true}); }, async pull(controller) {
    try { if (!current() || ended) throw new Error("native.retired"); const next = await reader.read(); if (!current() || ended) throw new Error("native.retired"); if (next.done) { finish(); controller.close(); return; } size += next.value.byteLength; if (size > max) throw new Error("native.response_bound"); controller.enqueue(next.value); }
    catch (error) { if(ended) return; finish(); void reader.cancel().catch(() => {}); controller.error(error); }
  }, cancel() { finish(); void reader.cancel().catch(() => {}); } }, { highWaterMark: 0 });
}
