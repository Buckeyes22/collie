import { closeSync, constants, fstatSync, openSync, readSync } from "node:fs";
import { join } from "node:path";
import { type Io, EXIT } from "./io.ts";
import { loadContext } from "./context.ts";
import { filePairingIo, PairingStore, pairedDeviceIdentity, isExpired } from "../bridge/pairing.ts";
import { privateJsonIo } from "../bridge/native-access/private-io.ts";
import { nativeAccessFor, parseGatewayConfig } from "../bridge/native-access/config.ts";
import { strictJson } from "../bridge/native-access/jose.ts";
import type { JsonValue } from "../bridge/json.ts";

export async function cmdNativeAccess(args: readonly string[], io: Io): Promise<number> {
  const ctx = loadContext(io.err);
  try {
    if (args[0] === "configure" && args.length === 2) {
      const config = parseGatewayConfig(strictJson(operatorJson(args[1]!)), true);
      // SAFETY: the parser projects the complete fixed operator config; generated keys remain private on disk.
      privateJsonIo(join(ctx.stateDir,"native-access.json"),65_536).write(JSON.parse(JSON.stringify(config)) as JsonValue);
      io.out("Native gateway configured. Route the HTTPS native API mount through this loopback gateway and configure matching Collie device/identity gates; no front door was changed."); return EXIT.OK;
    }
    const access = nativeAccessFor(ctx.stateDir); if (!access) { io.err("Native gateway is not configured."); return EXIT.STATE; }
    if (args[0] === "profile" && args.length === 1) { io.out(JSON.stringify(access.config.profile)); return EXIT.OK; }
    if (args[0] === "enroll" && args.length === 5) {
      const [_, issuer, subject, thumbprint, label] = args;
      if (issuer !== access.config.profile.issuer) throw new Error("native.issuer_mismatch");
      const registry = new PairingStore(filePairingIo(ctx.stateDir)).registry(); const matches = registry.devices.filter((row) => row.label === label && !isExpired(row, Date.now()));
      if (matches.length !== 1) throw new Error("native.pairing_not_current"); const pairedId = pairedDeviceIdentity(matches[0]!); if (!pairedId) throw new Error("native.pairing_identity_unavailable");
      const binding = access.bindings.enroll({ issuer: issuer!, subject: subject!, thumbprint: thumbprint! }, pairedId, label!);
      io.out(`Enrolled existing paired device binding ${binding.id} revision ${binding.revision}; pairing registry unchanged.`); return EXIT.OK;
    }
    if (args[0] === "revoke" && args.length === 2) { const revoked = access.bindings.revoke(args[1]!); io.out(revoked ? "Binding revoked; associated retirement event is durable." : "Binding was already absent or revoked."); return EXIT.OK; }
    if (args[0] === "list" && args.length === 1) { for (const row of access.bindings.list()) io.out(`${row.id} revision=${row.revision} state=${row.state} device=${row.deviceName}`); return EXIT.OK; }
    if (args[0] === "serve" && args.length === 1) {
      const server = Bun.serve({ hostname: "127.0.0.1", port: access.config.port, maxRequestBodySize: 10 * 1024 * 1024, fetch: (request, listener) => { const peer = listener.requestIP(request)?.address; return peer === "127.0.0.1" || peer === "::1" || peer === "::ffff:127.0.0.1" ? access.gateway.fetch(request) : new Response("Gateway loopback peer required", { status: 403 }); } });
      io.out(`Native identity gateway listening on loopback port ${server.port}; HTTPS/front-door routing remains operator owned.`);
      await new Promise<void>((resolve) => { const stop = () => { server.stop(true); resolve(); }; process.once("SIGTERM",stop); process.once("SIGINT",stop); }); return EXIT.OK;
    }
    io.err("Usage: collie native-access configure <operator-json> | profile | enroll <issuer> <subject> <jkt> <existing-device-label> | revoke <binding-id> | list | serve"); return EXIT.USAGE;
  } catch { io.err("Native identity operation refused; check owner-only configuration, declared issuer and existing paired-device mapping. No credentials were printed."); return EXIT.FAIL; }
}

function operatorJson(path: string): string {
  const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
    const stat=fstatSync(fd);if(!stat.isFile()||stat.size>65_536)throw new Error("native.config_bound");
    const bytes=Buffer.alloc(65_537);let total=0;
    while(total<bytes.length){const count=readSync(fd,bytes,total,bytes.length-total,total);if(!count)break;total+=count;}
    if(total>65_536)throw new Error("native.config_bound");
    return new TextDecoder("utf8",{fatal:true}).decode(bytes.subarray(0,total));
  }finally{closeSync(fd);}
}
