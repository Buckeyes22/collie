import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { decodeNativePush } from "./native-push-contract.ts";

// The native JVM tests consume this same wire corpus, including malicious/incompatible payloads.
const cases: { name: string; accept: boolean; payload: unknown; lastSequence?: number }[] = JSON.parse(
  readFileSync(new URL("../android/app/src/test/resources/native-push-v1.fixtures.json", import.meta.url), "utf8"),
);
for (const c of cases) {
  test(`native push v1: ${c.name}`, () => {
    const decoded = decodeNativePush(JSON.stringify(c.payload), "registration_demo", 1000, c.lastSequence ?? 0);
    expect(decoded !== null).toBe(c.accept);
    if (decoded) {
      if (c.name === "silent default") expect(decoded.renotify).toBe(false);
      else expect(JSON.parse(JSON.stringify(decoded))).toEqual(c.payload);
    }
  });
}
test("rejects malformed, oversized and invalid receiver context", () => {
  const raw = JSON.stringify(cases[0]!.payload);
  expect(decodeNativePush("{", "registration_demo", 1000)).toBeNull();
  expect(decodeNativePush(" ".repeat(4096) + raw, "registration_demo", 1000)).toBeNull();
  expect(decodeNativePush(raw, "registration_demo", -1)).toBeNull();
  expect(decodeNativePush(raw, "registration_demo", 1000, -1)).toBeNull();
  expect(decodeNativePush(raw, "", 1000)).toBeNull();
});
