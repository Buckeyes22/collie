import { expect, test } from "bun:test";
import { createHash, generateKeyPairSync, type KeyObject, sign } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const id = (spki: Buffer) => createHash("sha256").update(spki).digest("hex");
const encode = (value: Buffer) => value.toString("base64url");
type KeyPairFixture = { publicKey: KeyObject; privateKey: KeyObject };
function publicDer(key: KeyObject): Buffer {
  const exported = key.export({ format: "der", type: "spki" });
  if (!Buffer.isBuffer(exported)) throw new Error("P-256 fixture key did not export DER bytes");
  return exported;
}
function makeLink(from: KeyPairFixture, to: KeyPairFixture, code: number, tag: string) {
  const spki = publicDer(to.publicKey);
  const fields = ["1", id(publicDer(from.publicKey)), id(spki), encode(spki), String(code), tag];
  const chunks = [Buffer.from("CollieAndroidMetadataKeyTransitionV1\n")];
  for (const value of fields) {
    const bytes = Buffer.from(value, "utf8");
    chunks.push(Buffer.from(`${bytes.length}:`), bytes);
  }
  const signature = sign("sha256", Buffer.concat(chunks), from.privateKey);
  return {
    schemaVersion: 1, fromKeyId: fields[1], toKeyId: fields[2], successorSpki: fields[3],
    activationVersionCode: code, activationTag: tag, signature: encode(signature),
  };
}
function run(pin: string, signer: string, chain: string, code = "9", tag = "android-v1.5.8") {
  return spawnSync("bun", ["run", join(import.meta.dir, "android-release-metadata-chain.ts"), pin, signer, chain, code, tag], { encoding: "utf8" });
}

test("release metadata chain accepts a skipped two-key rotation and rejects tampering, cycles, and excess links", () => {
  const dir = mkdtempSync(join(tmpdir(), "collie-metadata-chain-"));
  try {
    const a = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const b = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const c = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const pin = join(dir, "pin.pem"); const signer = join(dir, "signer.pem"); const chain = join(dir, "chain.json");
    writeFileSync(pin, a.publicKey.export({ type: "spki", format: "pem" }));
    writeFileSync(signer, c.publicKey.export({ type: "spki", format: "pem" }));
    const links = [makeLink(a, b, 4, "android-v1.5.3"), makeLink(b, c, 7, "android-v1.5.6")];
    writeFileSync(chain, JSON.stringify(links));
    const positive = run(pin, signer, chain);
    expect(positive.status).toBe(0);
    expect(JSON.parse(positive.stdout)).toHaveLength(2);

    const tampered = structuredClone(links);
    const first = tampered.at(0);
    if (!first) throw new Error("rotation fixture omitted its first link");
    first.activationTag = "android-v1.5.4";
    writeFileSync(chain, JSON.stringify(tampered));
    expect(run(pin, signer, chain).status).not.toBe(0);

    const cycle = [makeLink(a, b, 4, "android-v1.5.3"), makeLink(b, a, 7, "android-v1.5.6")];
    writeFileSync(chain, JSON.stringify(cycle));
    expect(run(pin, pin, chain).status).not.toBe(0);

    writeFileSync(chain, JSON.stringify(Array.from({ length: 9 }, (_, i) => ({ ...makeLink(a, b, i + 1, "android-v1.5.3") }))));
    expect(run(pin, signer, chain).status).not.toBe(0);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
