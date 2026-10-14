import { afterEach, expect, test } from "bun:test";
import { DurableMux } from "../mux/durable/adapter.ts";
import { FakeDurableProgram } from "../mux/durable/fixture.ts";
import { withNativeFinalAdmission } from "./final-effect.ts";
import type { ControlRequest } from "../types.ts";

const programs: FakeDurableProgram[] = [];
afterEach(async () => { for (const program of programs.splice(0)) await program.close(); });
const requestId = "0b9e6a1c-3f2d-4c5e-8a7b-1d2e3f4a5b6c";
const send: ControlRequest = { act: "send", requestId, target: { kind: "turn", id: "turn-03-c" }, text: "continue", steer: false };

async function fixture() {
  const program = await FakeDurableProgram.startWire();
  programs.push(program);
  const adapter = new DurableMux(program.wire());
  return { program, adapter };
}

test("the native gate preserves durable read methods and guarded controls", async () => {
  const { program, adapter } = await fixture();
  const gated = withNativeFinalAdmission(adapter, () => true);
  expect((await adapter.poll()).ok).toBe(true);
  expect((await gated.snapshot()).panes.length).toBeGreaterThan(0);
  expect(gated.paneTurn?.("conv-03")).toBe("turn-03-c");
  expect(gated.openAsks?.("conv-02")?.length).toBeGreaterThan(0);
  expect((await gated.listStarters?.())?.starters.length).toBeGreaterThan(0);
  expect((await gated.controlTurn?.("conv-03", send))?.kind).toBe("ok");
  expect(program.requests.filter((r) => r.method === "POST")).toHaveLength(1);
});

test("an owner retired during durable control preflight sends no POST", async () => {
  const { program, adapter } = await fixture();
  let admitted = true;
  program.override = () => { admitted = false; return undefined; };
  const gated = withNativeFinalAdmission(adapter, () => admitted);
  expect((await gated.controlTurn?.("conv-03", send))?.kind).toBe("refused");
  expect(program.requests.filter((r) => r.method === "POST")).toHaveLength(0);
});

test("an owner retired during starter preflight starts nothing", async () => {
  const { program, adapter } = await fixture();
  let admitted = true;
  program.override = () => { admitted = false; return undefined; };
  const gated = withNativeFinalAdmission(adapter, () => admitted);
  expect((await gated.startStarter?.("notes-new", requestId))?.kind).toBe("refused");
  expect(program.requests.filter((r) => r.method === "POST")).toHaveLength(0);
});

test("a retired caller answers no ask", async () => {
  const { program, adapter } = await fixture();
  await adapter.poll();
  const ask = adapter.openAsks("conv-02")?.[0];
  if (ask === undefined) throw new Error("fixture has no ask");
  const gated = withNativeFinalAdmission(adapter, () => false);
  expect((await gated.answerAsk?.("conv-02", { requestId, askId: ask.id, revision: ask.revision, optionId: "allow" }))?.ok).toBe(false);
  expect(program.requests.filter((r) => r.method === "POST")).toHaveLength(0);
});
