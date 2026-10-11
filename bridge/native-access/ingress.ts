import { createHmac, randomUUID } from "node:crypto";
import { equal, digest, strictJson } from "./jose.ts";
import { isObject, publicRequestUri } from "./contracts.ts";
import type { DeviceBindings, IngressBinding } from "./device-bindings.ts";
import type { ProofReplayStore } from "./replay.ts";
export const INGRESS_HEADER = "x-collie-native-ingress";
export interface VerifiedIngressActor extends IngressBinding { readonly expiresAtSeconds: number; readonly requestId: string }
interface Stamp extends VerifiedIngressActor { readonly at: number; readonly requestId: string; readonly method: string; readonly path: string; readonly bearerHash: string; readonly profileId: string; readonly target: string }
export class GatewayIngress {
  constructor(private readonly key: Buffer, readonly bindings: DeviceBindings, private readonly replay: ProofReplayStore, readonly publicBase: string, readonly profileId: string, private readonly now: () => number = Date.now) { if (key.length !== 32 || !/^[A-Za-z0-9_-]{43}$/u.test(profileId) || !publicBase.endsWith("/")) throw new Error("native.ingress_config"); publicRequestUri(new URL(publicBase).origin + "/", new URL(publicBase).pathname); }
  sign(binding: IngressBinding, req: Request, publicPath: string, requestId: string, verifiedAccessExpiresAtSeconds: number): string {
    const at = this.now();
    if (!Number.isSafeInteger(verifiedAccessExpiresAtSeconds) || verifiedAccessExpiresAtSeconds <= Math.floor(at / 1000)) throw new Error("native.ingress_expiry");
    // Seconds, never milliseconds: this private effect lease can only shorten verified JWT exp.
    // A gateway request lasts at most 30 seconds; no queued dispatch inherits a longer token life.
    const expiresAtSeconds = Math.min(verifiedAccessExpiresAtSeconds, Math.floor(at / 1000) + 30);
    const stamp: Stamp = { profileId: this.profileId, target: new URL(req.url).pathname + new URL(req.url).search, id: binding.id, revision: binding.revision, pairedDeviceId: binding.pairedDeviceId, at, expiresAtSeconds, requestId, method: req.method, path: publicPath, bearerHash: digest(req.headers.get("authorization") ?? "") };
    const body = Buffer.from(JSON.stringify(stamp)).toString("base64url"); return `${body}.${this.mac(body)}`;
  }
  verify(req: Request, pairedDeviceId: string | null, localPath = new URL(req.url).pathname): VerifiedIngressActor | null {
    const header = req.headers.get(INGRESS_HEADER); if (!header || header.length > 4096) return null;
    const parts = header.split("."); if (parts.length !== 2 || !equal(this.mac(parts[0]!), parts[1]!)) return null;
    try {
      const v = strictJson(new TextDecoder("utf8", { fatal: true }).decode(Buffer.from(parts[0]!, "base64url")));
      const path = `${new URL(this.publicBase).pathname.replace(/\/$/u, "")}${localPath}`;
      if (!isObject(v) || Object.keys(v).length !== 11 || v.target !== localPath + new URL(req.url).search || v.profileId !== this.profileId || typeof v.id !== "string" || typeof v.revision !== "number" || typeof v.pairedDeviceId !== "string" || typeof v.at !== "number" || !Number.isSafeInteger(v.at) || Math.abs(this.now() - v.at) > 5000 || typeof v.expiresAtSeconds !== "number" || !Number.isSafeInteger(v.expiresAtSeconds) || v.expiresAtSeconds <= Math.floor(this.now() / 1000) || v.expiresAtSeconds > Math.floor(v.at / 1000) + 30 || typeof v.requestId !== "string" || !/^[A-Za-z0-9_-]{16,64}$/u.test(v.requestId) || v.method !== req.method || v.path !== path || v.bearerHash !== digest(req.headers.get("authorization") ?? "") || pairedDeviceId !== null && v.pairedDeviceId !== pairedDeviceId) return null;
      const binding: IngressBinding = { id: v.id, revision: v.revision, pairedDeviceId: v.pairedDeviceId };
      if (!this.bindings.current(binding) || !this.replay.consume(digest(`ingress\0${v.requestId}`), 10_000)) return null; return { ...binding, expiresAtSeconds: v.expiresAtSeconds, requestId: v.requestId };
    } catch { return null; }
  }
  identityCurrent(actor: VerifiedIngressActor): boolean { return actor.expiresAtSeconds > Math.floor(this.now() / 1000); }
  private mac(text: string): string { return createHmac("sha256", this.key).update("collie.native.ingress.v1\0").update(text).digest("base64url"); }
}
export function gatewayRefusal(factor: "identity" | "device", code: string, status: number, requestId: string = randomUUID(), nonce?: string): Response {
  const headers = new Headers({ "cache-control": "no-store", "x-collie-error-source": "native_gateway", "x-collie-error-factor": factor, "x-collie-error-code": code, "x-collie-native-request-id": requestId, "x-collie-response-source": "native_gateway" });
  if (nonce) { headers.set("dpop-nonce", nonce); headers.set("www-authenticate", 'DPoP error="use_dpop_nonce"'); }
  return Response.json({ source: "native_gateway", factor, code, requestId }, { status, headers });
}
