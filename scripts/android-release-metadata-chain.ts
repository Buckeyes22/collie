#!/usr/bin/env bun
import { createHash, createPublicKey, type KeyObject, verify as verifySignature } from "node:crypto";
import { readFile } from "node:fs/promises";

type Link = {
  schemaVersion: number; fromKeyId: string; toKeyId: string; successorSpki: string;
  activationVersionCode: number; activationTag: string; signature: string;
};
const fail = (message: string): never => { throw new Error(`metadata chain: ${message}`); };
const b64url = (value: string): Buffer => {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) fail("base64url must be unpadded and nonempty");
  const bytes = Buffer.from(value, "base64url");
  if (bytes.toString("base64url") !== value) fail("noncanonical base64url");
  return bytes;
};
const sha = (bytes: Buffer) => createHash("sha256").update(bytes).digest("hex");
const spkiDer = (key: KeyObject): Buffer => {
  const exported = key.export({ format: "der", type: "spki" });
  if (!Buffer.isBuffer(exported)) fail("public key did not export DER SPKI bytes");
  return exported;
};
const keyId = (key: KeyObject) => sha(spkiDer(key));
const isP256 = (key: KeyObject) =>
  key.asymmetricKeyType === "ec" && key.asymmetricKeyDetails?.namedCurve === "prime256v1";
const canonicalDer = (der: Buffer): boolean => {
  if (der.length < 8 || der.length > 72 || der[0] !== 0x30 || der[1] !== der.length - 2) return false;
  let offset = 2;
  const integer = (): boolean => {
    if (der[offset++] !== 0x02) return false;
    const n = der[offset++];
    if (!n || n > 33 || offset + n > der.length) return false;
    const bytes = der.subarray(offset, offset + n); offset += n;
    const first = bytes[0];
    const second = bytes[1];
    return first !== undefined && (first & 0x80) === 0 && !(n > 1 && first === 0 && second !== undefined && (second & 0x80) === 0) && bytes.some(x => x !== 0);
  };
  return integer() && integer() && offset === der.length;
};
const signedBytes = (link: Link): Buffer => {
  const fields = ["1", link.fromKeyId, link.toKeyId, link.successorSpki,
    String(link.activationVersionCode), link.activationTag];
  const chunks = [Buffer.from("CollieAndroidMetadataKeyTransitionV1\n", "ascii")];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    chunks.push(Buffer.from(`${bytes.length}:`, "ascii"), bytes);
  }
  return Buffer.concat(chunks);
};

const args = process.argv.slice(2);
const argument = (index: number): string => {
  const value = args[index];
  if (value === undefined || value.length === 0) return fail("missing command-line input");
  return value;
};
if (args.length !== 5) {
  fail("usage: bun scripts/android-release-metadata-chain.ts PIN.pem SIGNING_PUBLIC.pem CHAIN.json VERSION_CODE android-vX.Y.Z");
}
const pinPath = argument(0);
const signingPublicPath = argument(1);
const chainPath = argument(2);
const versionText = argument(3);
const tag = argument(4);
const versionCode = Number(versionText);
if (!Number.isSafeInteger(versionCode) || versionCode <= 0 || !/^android-v[0-9]+\.[0-9]+\.[0-9]+$/.test(tag)) fail("invalid target version or tag");
const pin = createPublicKey(await readFile(pinPath));
const signer = createPublicKey(await readFile(signingPublicPath));
if (!isP256(pin) || !isP256(signer)) fail("trust keys must be P-256");
const parsed = chainPath === "-" ? [] : JSON.parse(await readFile(chainPath, "utf8"));
if (!Array.isArray(parsed) || parsed.length > 8) fail("chain must contain at most eight signed links");
const links: Link[] = parsed;
let current = pin;
let id = keyId(current);
let priorActivation = 0;
const seen = new Set([id]);
for (const item of links) {
  if (!item || Object.keys(item).toSorted().join(",") !== ["activationTag","activationVersionCode","fromKeyId","schemaVersion","signature","successorSpki","toKeyId"].toSorted().join(",")) fail("link fields do not match schema");
  if (item.schemaVersion !== 1 || item.fromKeyId !== id || !/^[0-9a-f]{64}$/.test(item.toKeyId) ||
      !Number.isSafeInteger(item.activationVersionCode) || item.activationVersionCode <= priorActivation ||
      item.activationVersionCode > versionCode || !/^android-v[0-9]+\.[0-9]+\.[0-9]+$/.test(item.activationTag)) fail("invalid link ordering or activation");
  const spki = b64url(item.successorSpki);
  const successor = createPublicKey({ key: spki, format: "der", type: "spki" });
  if (!isP256(successor) || !spkiDer(successor).equals(spki)) fail("successor is not canonical P-256 SPKI");
  const computed = keyId(successor);
  if (computed !== item.toKeyId || seen.has(computed)) fail("KID mismatch or cycle");
  const signature = b64url(item.signature);
  if (!canonicalDer(signature) || !verifySignature("sha256", signedBytes(item), current, signature)) fail("old-key transition signature failed");
  current = successor; id = computed; priorActivation = item.activationVersionCode; seen.add(id);
}
if (id !== keyId(signer)) fail("active metadata key does not match the chain target");
if (links.length && versionCode === links.at(-1)!.activationVersionCode && tag !== links.at(-1)!.activationTag) fail("activation tag mismatch");
process.stdout.write(JSON.stringify(links));
