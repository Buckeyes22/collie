import { createPublicKey } from "node:crypto";
import { generateRequestDetails } from "web-push";
import type { JsonValue } from "./json.ts";
import type { NativePushDelivery } from "./native-push-contract.ts";

export interface NativePushDestination { endpoint: string; keys: { p256dh: string; auth: string } }
/** Only a fixed operator-owned HTTPS origin is reachable. Redirects are never followed. */
export class NtfyNativePushProvider {
  readonly origin: string;
  constructor(origin: string, private readonly token?: string, private readonly request: (input: string | URL | Request, init?: RequestInit) => Promise<Response> = fetch) {
    const u = new URL(origin);
    if (u.protocol !== "https:" || u.username || u.password || u.pathname !== "/" || u.search || u.hash ||
        u.hostname === "ntfy.sh" || token?.match(/[\r\n]/u)) throw new Error("native.push_configuration_invalid");
    this.origin = u.origin;
  }
  validate(value: NativePushDestination | JsonValue): NativePushDestination | null {
    if (!value || typeof value !== "object" || !("endpoint" in value) || !("keys" in value) ||
        typeof value.endpoint !== "string" || value.endpoint.length > 2048) return null;
    const keys = value.keys;
    if (!keys || typeof keys !== "object" || !("p256dh" in keys) || !("auth" in keys) ||
        typeof keys.p256dh !== "string" || typeof keys.auth !== "string" ||
        !/^[A-Za-z0-9_-]{87}$/u.test(keys.p256dh) || !/^[A-Za-z0-9_-]{22}$/u.test(keys.auth)) return null;
    try {
      const url = new URL(value.endpoint);
      if (url.origin !== this.origin || url.username || url.password || url.hash || (url.search !== "" && url.search !== "?up=1") ||
          // ntfy's distributor generates "up" plus twelve random ASCII alphanumerics.
          !/^\/(?:up[A-Za-z0-9]{12}|[A-Za-z0-9_-]{16,256})$/u.test(url.pathname) || url.href !== value.endpoint) return null;
      // Reject invalid curve points at enrollment, before encryption/provider calls.
      createPublicKey({ key: { kty: "EC", crv: "P-256", x: Buffer.from(keys.p256dh, "base64url").subarray(1, 33).toString("base64url"), y: Buffer.from(keys.p256dh, "base64url").subarray(33).toString("base64url") }, format: "jwk" });
      if (Buffer.from(keys.p256dh, "base64url")[0] !== 4 || Buffer.from(keys.auth, "base64url").length !== 16) return null;
      return { endpoint: value.endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth } };
    } catch { return null; }
  }
  async deliver(destination: NativePushDestination, signedPayload: string): Promise<NativePushDelivery> {
    if (!this.validate(destination) || Buffer.byteLength(signedPayload) > 3600) return { kind: "retired" };
    try {
      const details = generateRequestDetails(destination, signedPayload, { TTL: 24 * 3600, contentEncoding: "aes128gcm" });
      const headers = new Headers(details.headers);
      headers.set("X-UnifiedPush", "1"); headers.set("X-Firebase", "no");
      if (this.token) headers.set("Authorization", `Bearer ${this.token}`);
      const response = await this.request(destination.endpoint, { method: "POST", headers, body: new Uint8Array(details.body ?? Buffer.alloc(0)).buffer,
        redirect: "error", signal: AbortSignal.timeout(10000) });
      await response.body?.cancel();
      if (response.ok) return { kind: "accepted" };
      if (response.status === 404 || response.status === 410) return { kind: "retired" };
      const seconds = Number(response.headers.get("retry-after"));
      return { kind: "retry", retryAfterMs: Number.isFinite(seconds) && seconds > 0 ? Math.min(seconds * 1000, 3600000) : undefined };
    } catch { return { kind: "retry" }; }
  }
}
