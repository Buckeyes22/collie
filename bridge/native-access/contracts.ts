import type { JsonValue } from "../json.ts";

export const NATIVE_REDIRECT = "com.lateapex.collie:/oauth2redirect";
export const NATIVE_ACCESS_LIMITS = Object.freeze({ tokenBytes: 16_384, proofBytes: 8192, jwksBytes: 65_536, profileBytes: 65_536, bodyBytes: 10 * 1024 * 1024, replayEntries: 8192, bindingEntries: 1024, clockSkewSeconds: 30 });
export interface NativeAccessProfile {
  readonly schema: 1;
  readonly issuer: string;
  readonly clientId: string;
  readonly audience: string;
  /** Additional operator-declared API scopes; OpenID/offline scopes remain mandatory. */
  readonly scopes?: readonly string[];
  readonly redirectUri: typeof NATIVE_REDIRECT;
  readonly authorizationEndpoint: string;
  readonly tokenEndpoint: string;
  readonly jwksEndpoint: string;
  readonly gatewayOrigin: string;
  readonly algorithms: readonly ["ES256"];
  /** Operator-pinned issuer public keys; discovery never replaces these with a phone-supplied key. */
  readonly jwks: readonly EcPublicJwk[];
}
export interface EcPublicJwk { readonly kty: "EC"; readonly crv: "P-256"; readonly x: string; readonly y: string; readonly kid?: string; readonly alg?: "ES256"; readonly use?: "sig" }
export interface NativeGatewayError { readonly source: "native_gateway"; readonly factor: "identity" | "device"; readonly code: string; readonly requestId: string }
export interface VerifiedIdentity { readonly issuer: string; readonly subject: string; readonly thumbprint: string; readonly expiresAt: number }
export function hasControl(value: string): boolean { return Array.from(value).some((char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127); }
export function isObject(value: JsonValue | undefined): value is { [key: string]: JsonValue } { return value !== null && typeof value === "object" && !Array.isArray(value); }
function profileEndpoint(value: string, issuer: boolean): URL {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.hash || url.search || (value !== url.href && !(issuer && url.pathname === "/" && value + "/" === url.href)) || /%(?:2f|5c|00)/iu.test(url.pathname)) throw new Error("native.profile_endpoint");
  return url;
}
export function httpsEndpoint(value: string): URL { return profileEndpoint(value, false); }
// Issuer identifiers may omit the root slash; authentication retains the original literal.
export function publicRequestUri(origin: string, path: string): string {
  const base = httpsEndpoint(origin);
  if (!path.startsWith("/") || path.startsWith("//") || (path.includes("\\") || hasControl(path)) || /%(?:2f|5c|00)/iu.test(path)) throw new Error("native.request_uri");
  const result = new URL(path, base);
  if (result.origin !== base.origin || result.pathname !== path || result.search || result.hash) throw new Error("native.request_uri");
  return result.href;
}
export function validJwk(value: JsonValue | undefined): value is JsonValue & EcPublicJwk {
  return isObject(value) && value.kty === "EC" && value.crv === "P-256" && typeof value.x === "string" && /^[A-Za-z0-9_-]{43}$/u.test(value.x) && typeof value.y === "string" && /^[A-Za-z0-9_-]{43}$/u.test(value.y) && (Buffer.from(value.x, "base64url").toString("base64url") === value.x && Buffer.from(value.y, "base64url").toString("base64url") === value.y) && Object.keys(value).every((key) => ["kty","crv","x","y","kid","alg","use"].includes(key)) && (value.kid === undefined || typeof value.kid === "string" && /^[A-Za-z0-9._-]{1,128}$/u.test(value.kid)) && (value.alg === undefined || value.alg === "ES256") && (value.use === undefined || value.use === "sig");
}
export function parseProfile(value: JsonValue): NativeAccessProfile {
  if (!isObject(value) || value.schema !== 1 || value.redirectUri !== NATIVE_REDIRECT || !Array.isArray(value.algorithms) || value.algorithms.length !== 1 || value.algorithms[0] !== "ES256" || !Array.isArray(value.jwks) || value.jwks.length < 1 || value.jwks.length > 8 || !value.jwks.every(validJwk)) throw new Error("native.profile");
  if (value.jwks.some((key) => typeof key.kid !== "string") || new Set(value.jwks.map((key) => key.kid)).size !== value.jwks.length) throw new Error("native.profile_keys");
  if (Object.keys(value).some((key) => !["schema","issuer","clientId","audience","scopes","redirectUri","authorizationEndpoint","tokenEndpoint","jwksEndpoint","gatewayOrigin","algorithms","jwks"].includes(key))) throw new Error("native.profile");
  let scopes: string[] | undefined;
  if (value.scopes !== undefined) {
    if (!Array.isArray(value.scopes) || value.scopes.length < 1 || value.scopes.length > 8 ||
        !value.scopes.every((scope): scope is string => typeof scope === "string" && /^[\x21\x23-\x5b\x5d-\x7e]{1,128}$/u.test(scope) && scope !== "openid" && scope !== "offline_access")) throw new Error("native.profile_scopes");
    scopes = value.scopes;
    if (new Set(scopes).size !== scopes.length || ["openid", "offline_access", ...scopes].join(" ").length > 512) throw new Error("native.profile_scopes");
  }
  const strings = ["issuer","clientId","audience","authorizationEndpoint","tokenEndpoint","jwksEndpoint","gatewayOrigin"];
  for (const key of strings) if (typeof value[key] !== "string" || value[key].length < 1 || value[key].length > 2048 || hasControl(value[key])) throw new Error("native.profile");
  if (String(value.issuer).length > 256 || String(value.clientId).length > 256 || String(value.audience).length > 256) throw new Error("native.profile");
  const issuer = profileEndpoint(String(value.issuer), true);
  for (const key of ["authorizationEndpoint","tokenEndpoint","jwksEndpoint"]) if (httpsEndpoint(String(value[key])).origin !== issuer.origin) throw new Error("native.issuer_endpoint");
  const gateway = httpsEndpoint(String(value.gatewayOrigin));
  if (gateway.pathname !== "/") throw new Error("native.gateway_origin");
  // SAFETY: all required fields and the closed algorithm/key shapes are validated above; project only those fields.
  const profile: NativeAccessProfile = { schema: 1, issuer: String(value.issuer), clientId: String(value.clientId), audience: String(value.audience), redirectUri: NATIVE_REDIRECT, authorizationEndpoint: String(value.authorizationEndpoint), tokenEndpoint: String(value.tokenEndpoint), jwksEndpoint: String(value.jwksEndpoint), gatewayOrigin: gateway.href, algorithms: ["ES256"], jwks: value.jwks as EcPublicJwk[] };
  if (scopes === undefined) return profile;
  return { ...profile, scopes: [...scopes] };
}

export function validDeviceHeader(value: string): boolean {
 return /^[A-Za-z0-9-]{1,64}$/u.test(value) && !["authorization","collie-proxy-authorization","dpop","cookie","host","origin","tailscale-user-login"].includes(value.toLowerCase()) && !value.toLowerCase().startsWith("x-collie-") && !value.toLowerCase().startsWith("x-crew-");
}
