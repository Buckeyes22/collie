import { createHash, createPublicKey, timingSafeEqual, verify } from "node:crypto";
import type { JsonValue } from "../json.ts";
import { hasControl, isObject, NATIVE_ACCESS_LIMITS, validJwk, type EcPublicJwk, type NativeAccessProfile, type VerifiedIdentity } from "./contracts.ts";

export function digest(value: string): string { return createHash("sha256").update(value, "utf8").digest("base64url"); }
export function equal(left: string, right: string): boolean { const a = Buffer.from(left); const b = Buffer.from(right); return a.length === b.length && timingSafeEqual(a, b); }
export function thumbprint(jwk: EcPublicJwk): string { return digest(JSON.stringify({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y })); }
function segment(value: string, max: number): Buffer {
  if (!value || value.length > max || !/^[A-Za-z0-9_-]+$/u.test(value)) throw new Error("native.jwt_encoding");
  const bytes = Buffer.from(value, "base64url");
  if (bytes.toString("base64url") !== value) throw new Error("native.jwt_encoding");
  return bytes;
}
/** Closed JSON boundary additionally rejects duplicate property names, including escaped aliases. */
export function strictJson(text: string): JsonValue {
  const keys: Set<string>[] = []; let i = 0; let depth = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === "[") { if(++depth>32)throw new Error("native.json_depth");i++;continue; }
    if (ch === "]") { if(--depth<0)throw new Error("native.json_depth");i++;continue; }
    if (ch === "{") { if(++depth>32)throw new Error("native.json_depth");keys.push(new Set()); i++; continue; }
    if (ch === "}") { if(--depth<0)throw new Error("native.json_depth"); keys.pop(); i++; continue; }
    if (ch !== '"') { i++; continue; }
    const start = i++; while (i < text.length) { if (text[i] === "\\") { i += 2; continue; } if (text[i++] === '"') break; }
    const end = i; while (/\s/u.test(text[i] ?? "") && i < text.length) i++;
    if (text[i] === ":") {
      // SAFETY: a JSON string is parsed only to compare its actual property name; the complete JSON is parsed below.
      const key = JSON.parse(text.slice(start, end)) as string;
      const set = keys.at(-1); if (!set || set.has(key)) throw new Error("native.json_duplicate"); set.add(key);
    }
  }
  // SAFETY: JSON.parse returns JSON values; callers validate every selected field before use.
  return JSON.parse(text) as JsonValue;
}
interface Jwt { readonly header: { [key: string]: JsonValue }; readonly claims: { [key: string]: JsonValue }; readonly signed: string; readonly signature: Buffer }
function jwt(value: string, limit: number): Jwt {
  if (value.length > limit) throw new Error("native.jwt_size");
  const pieces = value.split("."); if (pieces.length !== 3) throw new Error("native.jwt_encoding");
  const header = strictJson(new TextDecoder("utf8", { fatal: true }).decode(segment(pieces[0]!, limit)));
  const claims = strictJson(new TextDecoder("utf8", { fatal: true }).decode(segment(pieces[1]!, limit)));
  if (!isObject(header) || !isObject(claims) || header.alg !== "ES256" || header.crit !== undefined || header.b64 !== undefined || header.jku !== undefined || header.x5u !== undefined) throw new Error("native.jwt_header");
  const signature = segment(pieces[2]!, 128); if (signature.length !== 64) throw new Error("native.jwt_signature");
  return { header, claims, signature, signed: `${pieces[0]}.${pieces[1]}` };
}
function verifySignature(token: Jwt, jwk: EcPublicJwk): void {
  const key = createPublicKey({ key: { ...jwk }, format: "jwk" });
  if (!verify("sha256", Buffer.from(token.signed, "ascii"), { key, dsaEncoding: "ieee-p1363" }, token.signature)) throw new Error("native.jwt_signature");
}
function integer(value: JsonValue | undefined): value is number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0; }
export function verifyAccessToken(value: string, profile: NativeAccessProfile, now: number): VerifiedIdentity {
  const token = jwt(value, NATIVE_ACCESS_LIMITS.tokenBytes); const c = token.claims;
  if (token.header.typ !== "at+jwt" || token.header.jwk !== undefined || typeof token.header.kid !== "string") throw new Error("native.access_header");
  const keys = profile.jwks.filter((key) => key.kid === token.header.kid); if (keys.length !== 1) throw new Error("native.issuer_key"); verifySignature(token, keys[0]!);
  if (c.iss !== profile.issuer || c.aud !== profile.audience || c.client_id !== profile.clientId || typeof c.sub !== "string" || c.sub.length < 1 || c.sub.length > 256 || hasControl(c.sub) || !integer(c.exp) || !integer(c.iat) || c.exp <= now || c.iat > now + 30 || c.exp <= c.iat || c.nbf !== undefined && (!integer(c.nbf) || c.nbf > now + 30) || !isObject(c.cnf) || typeof c.cnf.jkt !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(c.cnf.jkt)) throw new Error(c.exp !== undefined && Number(c.exp) <= now ? "identity.expired" : "native.access_claims");
  return { issuer: profile.issuer, subject: c.sub, thumbprint: c.cnf.jkt, expiresAt: c.exp };
}
export function verifyProof(value: string, identity: VerifiedIdentity, accessToken: string, method: string, uri: string, nonce: string, now: number): string {
  const token = jwt(value, NATIVE_ACCESS_LIMITS.proofBytes); const c = token.claims;
  if (token.header.typ !== "dpop+jwt" || !validJwk(token.header.jwk) || token.header.kid !== undefined) throw new Error("native.proof_header");
  verifySignature(token, token.header.jwk);
  if (!equal(thumbprint(token.header.jwk), identity.thumbprint) || c.htm !== method || c.htu !== uri || !integer(c.iat) || Math.abs(c.iat - now) > 30 || typeof c.jti !== "string" || !/^[A-Za-z0-9_-]{16,128}$/u.test(c.jti) || typeof c.ath !== "string" || !equal(c.ath, digest(accessToken)) || typeof c.nonce !== "string" || !equal(c.nonce, nonce)) throw new Error("identity.proof_invalid");
  return c.jti;
}
