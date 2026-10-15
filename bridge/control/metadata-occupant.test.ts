import { describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION, type AgentControlInfo } from "./agent-models.ts";
import { MetadataStateStore, serveMetadataRoute } from "./metadata.ts";
import { OperationStore } from "./operations.ts";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { FakeHerdr } from "../mux/herdr/fixture.ts";
import { muxOk } from "../mux/types.ts";
import { currentOriginalHerdrPane } from "../server.ts";

/** Same public terminal and agent label; only immutable producer custody changes. */
async function fixture(replaceAfterAdmission: boolean) {
  const producer = new FakeHerdr();
  producer.metadataCaps.paneOriginalMetadataV1 = true;
  const mux = new HerdrMux(producer, () => []);
  await mux.reachable();
  const pane = (await producer.listPanes())[0]!;
  const original: AgentControlInfo = Object.freeze({
    paneId: pane.pane_id, terminalId: pane.terminal_id, workspaceId: pane.workspace_id, tabId: pane.tab_id,
    runtimeIdentity: producer.runtimeIdentity, agent: pane.agent ?? null, sessionIdentity: "same-session", processIdentity: "same-process-roots", name: null,
    status: "working", focused: true, interactiveReady: true, launchPending: false, stateChangeSeq: "0", completionSeq: null,
    [ORIGINAL_AGENT_BINDING]: "c".repeat(64), [ORIGINAL_RUNTIME_INCARNATION]: "runtime-original",
  });
  let reads = 0;
  mux.agentControl = async () => {
    reads++;
    const binding = replaceAfterAdmission && reads > 1 ? "d".repeat(64) : original[ORIGINAL_AGENT_BINDING];
    return muxOk(Object.freeze({ ...original, [ORIGINAL_AGENT_BINDING]: binding }));
  };
  const device = "a".repeat(64);
  const caller = {
    gate: () => null, pairedIdentity: () => device, audit: () => {},
    resolve: async () => ({ host: "host-a", session: "session-a", mux, currentRuntime: () => true }),
  };
  const store = new MetadataStateStore();
  const url = new URL(`http://localhost/api/pane/${encodeURIComponent(pane.pane_id)}/metadata`);
  const request = () => new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ requestId: "metadata-original", agent: pane.agent, seq: 1, tokens: { ticket: "owned" } }) });
  return { producer, original, caller, store, url, request, reads: () => reads };
}

describe("B31 metadata retains original route admission", () => {
  test("same-terminal same-label successor cannot replace the admission binding", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-b31-original-race-"));
    try {
      const f = await fixture(true);
      const operations = new OperationStore(root);
      expect(await operations.prepare()).toBe(true);
      const response = await serveMetadataRoute(f.request(), f.url, f.caller, f.store, operations);
      expect(response?.status).toBe(409);
      expect(await response?.json()).toMatchObject({ ok: false, code: "metadata.gone" });
      expect(f.reads()).toBe(2);
      expect(f.producer.lastOriginalMetadata).toBeNull();
      expect(await operations.inspect("metadata-original", "a".repeat(64), "host-a", "session-a")).toMatchObject({ state: "refused", code: "metadata.gone" });
    } finally { await rm(root, { recursive: true, force: true }); }
  });

  test("unchanged original owner dispatches retained binding once and keeps custody private", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-b31-original-positive-"));
    try {
      const f = await fixture(false);
      const operations = new OperationStore(root);
      expect(await operations.prepare()).toBe(true);
      const response = await serveMetadataRoute(f.request(), f.url, f.caller, f.store, operations);
      expect(response?.status).toBe(200);
      const text = await response?.text();
      expect(text).toContain('"receipt":true');
      expect(await operations.inspect("metadata-original", "a".repeat(64), "host-a", "session-a")).toMatchObject({ state: "succeeded" });
      expect(text).not.toContain(f.original[ORIGINAL_AGENT_BINDING]!);
      expect(text).not.toContain(f.original[ORIGINAL_RUNTIME_INCARNATION]!);
      expect(f.producer.lastOriginalMetadata).toMatchObject({ original_binding: f.original[ORIGINAL_AGENT_BINDING], report: { tokens: { ticket: "owned" } } });
      expect(f.reads()).toBe(3);
      const sent = f.producer.lastOriginalMetadata;
      const duplicate = await serveMetadataRoute(f.request(), f.url, f.caller, f.store, operations);
      expect(duplicate?.status).toBe(200);
      expect(f.producer.lastOriginalMetadata).toBe(sent);
      expect(f.reads()).toBe(4); // duplicate can capture, but never dispatches again.
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});


describe("B19 joined production terminal incarnation admission", () => {
  test("raw producer terminal id remains admitted by its actual hashed pane projection", async () => {
    const f = await fixture(false);
    const pane = (await f.caller.resolve()).mux;
    const snapshot = await pane.snapshot();
    const publicPane = snapshot.panes.find((row) => row.paneId === f.original.paneId)!;
    expect(publicPane.terminalIncarnation).toMatch(/^[a-f0-9]{64}$/u);
    expect(publicPane.terminalIncarnation).not.toBe(f.original.terminalId);
    const enginePane = { paneId: publicPane.paneId, terminalIncarnation: publicPane.terminalIncarnation, workspaceId: publicPane.spaceId, tabId: publicPane.tabId };
    expect(currentOriginalHerdrPane(enginePane, f.original)).toBe(true);
    expect(currentOriginalHerdrPane({ ...enginePane, tabId: "other-tab" }, f.original)).toBe(false);
    expect(currentOriginalHerdrPane({ ...enginePane, workspaceId: "other-workspace" }, f.original)).toBe(false);
  });

  test("a replacement terminal at the same pane and agent label retires the original", async () => {
    const f = await fixture(false);
    f.producer.changeTerminalOccupant(f.original.paneId, "replacement-producer-terminal");
    const snapshot = await (await f.caller.resolve()).mux.snapshot();
    const publicPane = snapshot.panes.find((row) => row.paneId === f.original.paneId)!;
    expect(publicPane.agent).toBe(f.original.agent!);
    expect(currentOriginalHerdrPane({ paneId: publicPane.paneId, terminalIncarnation: publicPane.terminalIncarnation, workspaceId: publicPane.spaceId, tabId: publicPane.tabId }, f.original)).toBe(false);
    expect(currentOriginalHerdrPane(undefined, f.original)).toBe(false);
  });
});
