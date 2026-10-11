import { lstatSync } from "node:fs";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import type { JsonValue } from "../json.ts";
import { hasControl, isObject, validDeviceHeader, parseProfile, httpsEndpoint, type NativeAccessProfile } from "./contracts.ts";
import { privateJsonIo } from "./private-io.ts";
import { DeviceBindings } from "./device-bindings.ts";
import { ProofReplayStore } from "./replay.ts";
import { GatewayIngress } from "./ingress.ts";
import { digest } from "./jose.ts";
import { NativeIdentityGateway } from "./gateway.ts";
export interface NativeGatewayConfig { readonly schema: 1; readonly profile: NativeAccessProfile; readonly publicBase: string; readonly upstream: string; readonly port: number; readonly deviceHeader: string; readonly mappedLogin: string; readonly ingressKey: string; readonly nonceKey: string }
export function parseGatewayConfig(value: JsonValue, generateKeys = false): NativeGatewayConfig {
  if (!isObject(value) || value.schema !== 1 || value.profile === undefined || typeof value.publicBase !== "string" || !value.publicBase.endsWith("/") || typeof value.upstream !== "string" || typeof value.port !== "number" || !Number.isInteger(value.port) || value.port < 1024 || value.port > 65535 || typeof value.deviceHeader !== "string" || !/^[A-Za-z0-9-]{1,64}$/u.test(value.deviceHeader) || typeof value.mappedLogin !== "string" || value.mappedLogin.length > 256 || !value.mappedLogin || hasControl(value.mappedLogin)) throw new Error("native.gateway_config");
  if (!validDeviceHeader(value.deviceHeader)) throw new Error("native.device_header");
  const profile = parseProfile(value.profile); const base = httpsEndpoint(value.publicBase); const upstream = new URL(value.upstream);
  if (base.origin + "/" !== profile.gatewayOrigin || upstream.protocol !== "http:" || !["127.0.0.1","[::1]"].includes(upstream.hostname) || upstream.pathname !== "/" || upstream.username || upstream.password || upstream.search || upstream.hash || upstream.port === String(value.port)) throw new Error("native.gateway_config");
  const checked = value;
  function key(name: string): string { if (generateKeys) return randomBytes(32).toString("base64url"); const k = checked[name]; if (typeof k !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(k) || Buffer.from(k,"base64url").toString("base64url") !== k) throw new Error("native.gateway_key"); return k; }
  return { schema: 1, profile, publicBase: value.publicBase, upstream: value.upstream, port: value.port, deviceHeader: value.deviceHeader, mappedLogin: value.mappedLogin, ingressKey: key("ingressKey"), nonceKey: key("nonceKey") };
}
export function nativeAccessFor(stateDir: string) {
  const configFile = join(stateDir, "native-access.json");
  // The optional gateway imposes private custody only when its fixed operator file exists.
  // lstat preserves dangling-symlink refusal; permission/IO errors never mean disabled.
  try { lstatSync(configFile); }
  catch (error) { if (error instanceof Error && "code" in error && error.code === "ENOENT") return null; throw error; }
  const raw = privateJsonIo(configFile, 65_536).read(); if (raw === null) return null;
  const config = parseGatewayConfig(raw); const bindings = new DeviceBindings(privateJsonIo(join(stateDir,"native-device-bindings.json")));
  const ingress = new GatewayIngress(Buffer.from(config.ingressKey,"base64url"), bindings, new ProofReplayStore(privateJsonIo(join(stateDir,"native-ingress-replay.json"))), config.publicBase, digest(JSON.stringify(config.profile)));
  const gateway = new NativeIdentityGateway({ profile: config.profile, publicBase: config.publicBase, upstream: config.upstream, identityHeader: "Tailscale-User-Login", deviceHeader: config.deviceHeader, mappedLogin: config.mappedLogin, bindings, ingress, replay: new ProofReplayStore(privateJsonIo(join(stateDir,"native-proof-replay.json"))), nonceKey: Buffer.from(config.nonceKey,"base64url") });
  return { config, bindings, ingress, gateway };
}
