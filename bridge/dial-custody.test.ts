import { expect, test } from "bun:test";
import { createBunWriteStream, WriteAdmissionRetired } from "./dial.ts";

// Controlled raw acceptance drives the SAME factory used by production Bun dial/drain. The
// separate real Unix-socket HerdrClient cohort proves protected request byte admission.
for (const firstAccepted of [0, 2]) {
  test(`original retirement stops managed drain, preserving ${firstAccepted ? "partial uncertainty" : "zero-byte refusal"}`, () => {
    let allowed = true; let calls = 0; let closed = 0;
    const accepted: Uint8Array[] = []; const failures: Error[] = [];
    const raw = { write(bytes: Uint8Array, offset = 0) { calls++; const count = calls === 1 ? firstAccepted : 0; accepted.push(bytes.slice(offset, offset + count)); return count; }, flush() {}, end() { closed++; } };
    const stream = createBunWriteStream({ error(_socket, error) { failures.push(error); } });
    stream.attach(raw); stream.handle.write("abcde", () => allowed);
    const beforeDrain = calls; allowed = false; stream.drain(raw);
    expect(calls).toBe(beforeDrain); expect(Buffer.concat(accepted)).toEqual(Buffer.from("abcde".slice(0, firstAccepted)));
    const failure = failures[0]; expect(failure).toBeInstanceOf(WriteAdmissionRetired);
    if (!(failure instanceof WriteAdmissionRetired)) throw new Error("expected original admission retirement");
    expect(failure.bytesMayHaveBeenSent).toBe(firstAccepted > 0); expect(closed).toBe(1);
    stream.drain(raw); expect(calls).toBe(beforeDrain); // No retained tail can resurrect.
  });
}
test("unchanged original owner drains each byte once without a second request", () => {
  let calls = 0; const accepted: Uint8Array[] = [];
  const raw = { write(bytes: Uint8Array, offset = 0, length = bytes.length) { const count = ++calls === 1 ? 2 : calls === 2 ? 0 : length; accepted.push(bytes.slice(offset, offset + count)); return count; }, flush() {}, end() {} };
  const stream = createBunWriteStream({ error() { throw new Error("unchanged original admission must not fail"); } });
  stream.attach(raw); stream.handle.write("abcde", () => true); expect(Buffer.concat(accepted).toString()).toBe("ab");
  stream.drain(raw); stream.drain(raw); expect(Buffer.concat(accepted).toString()).toBe("abcde"); expect(calls).toBe(3);
});
