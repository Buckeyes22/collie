import { describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { HerdrRpc } from "../mux/herdr/client.ts";
import { OperationStore } from "../control/operations.ts";
import { memoryAgentViewReceipts, herdrAgentViews, serveAgentViewRoute } from "./agent-views.ts";
import type { HostControlPort } from "./types.ts";
import { herdrHostControl } from "./herdr.ts";

/* eslint-disable anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-chained-type-assertions
 * SAFETY: these fixtures implement only the fake Herdr methods exercised by each test.
 */

describe("bounded Herdr agent views", () => {
  test("requires current compatible support, rejects invalid definitions and preserves plugin refusal", async () => {
    const client = {
      setAgentView: async () => { throw new Error("plugin_disabled"); },
      clearAgentView: async () => ({ type: "agent_view", active: false, source: null, label: null }),
    } as unknown as HerdrRpc;
    const views = herdrAgentViews(client, () => true);
    expect((await views.set({ source: "tool.review", filter: { op: "exists", field: "agent" } })).kind).toBe("refused");
    expect(await views.set({ source: "tool.review", filter: { op: "exists", field: "agent" } })).toMatchObject({ kind: "refused", code: "plugin_disabled" });
    expect((await herdrAgentViews(client, () => false).clear()).kind).toBe("unsupported");
  });

  test("runtime identity change after dispatch reports reconcile-needed uncertainty without retry", async () => {
    let calls = 0;
    let samples = 0;
    const client = { setAgentView: async () => { calls += 1; return { type: "agent_view", active: true, source: "tool.review", label: null }; } } as unknown as HerdrRpc;
    const views = herdrAgentViews(client, async () => ++samples === 1 ? "runtime-a" : "runtime-b");
    expect(await views.set({ source: "tool.review" })).toMatchObject({ kind: "unavailable" });
    expect(calls).toBe(1);
  });

  test("owner read returns only the requested source and decodes its exact definition", async () => {
    const source = "collie.native." + "b".repeat(12);
    const client = {
      metadataProtocolCapabilities: () => ({ paneOriginalMetadataV1: false, agentViewReadV1: true }),
      getAgentView: async (params: { source: string }) => ({ type: "agent_view_state", source: params.source, active: true, view: { source, label: "Working", filter: { op: "eq", field: "status", value: "working" }, sort: [] } }),
    } as unknown as HerdrRpc;
    const views = herdrAgentViews(client, async () => "runtime-a");
    expect(await views.get(source)).toMatchObject({ kind: "available", active: true, source, view: { source, label: "Working" } });
    expect((await views.get("other.source")).kind).toBe("unavailable");
  });

  test("host route binds operations to host/device, rejects session selectors and never repeats a dispatch", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-b31-luna-current1-agent-view-"));
    try {
      const operations = new OperationStore(root);
      expect(await operations.prepare()).toBe(true);
      let sets = 0;
      let clears = 0;
      const port: HostControlPort = {
        ...herdrHostControl,
        provider: "herdr",
        capabilities: { integrations: false, integrationInstall: false, integrationUninstall: false, experimentalLettaIntegration: false, agentManifests: false, manifestReload: false, manifestRefresh: false, plugins: false, pluginOwnerOperations: false, configuration: false, persistence: false, machines: false, serverAdministration: false, agentViews: true, hostUiActions: false, hostCommands: false },
        notes: {},
        inspect: async () => ({ kind: "unsupported", capability: "agentViews", reason: "unused" }),
        agentViews: {
          get: async (source) => ({ kind: "available", active: true, source, label: "Working", view: { source, label: "Working", filter: { op: "eq", field: "status", value: "working" } } }),
          set: async (view) => { sets += 1; return { kind: "available", active: true, source: view.source, label: view.label ?? null }; },
          clear: async (source) => { clears += 1; return { kind: "available", active: false, source: source ?? null, label: null }; },
        },
      };
      const caller = { gate: () => null, pairedIdentity: () => "b".repeat(64), audit: () => {}, resolve: async () => ({ host: "host-a", port }) };
      const receipts = memoryAgentViewReceipts();
      const url = new URL("https://collie.test/api/host/agent-view");
      const post = () => new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ requestId: "set-1", label: "Working", filter: { op: "eq", field: "status", value: "working" } }) });
      const first = await serveAgentViewRoute(post(), url, caller, receipts, operations);
      expect(first?.status).toBe(200);
      const duplicate = await serveAgentViewRoute(post(), url, caller, receipts, operations);
      expect(duplicate?.status).toBe(200);
      expect(sets).toBe(1);
      expect(receipts.read("host-a")?.source).toBe(`collie.native.${"b".repeat(12)}`);
      const badScope = new URL("https://collie.test/api/host/agent-view?session=session-a");
      expect((await serveAgentViewRoute(new Request(badScope, { method: "GET" }), badScope, caller, receipts, operations))?.status).toBe(400);
      const clear = await serveAgentViewRoute(new Request(url, { method: "DELETE", headers: { "content-type": "application/json" }, body: JSON.stringify({ requestId: "clear-1" }) }), url, caller, receipts, operations);
      expect(clear?.status).toBe(200);
      expect(clears).toBe(1);
      expect(receipts.read("host-a")).toBeNull();
      const reconciled = await serveAgentViewRoute(new Request(url, { method: "GET" }), url, caller, receipts, operations);
      expect(await reconciled?.json()).toMatchObject({ authoritative: true, reconcileRequired: false, view: { source: `collie.native.${"b".repeat(12)}`, label: "Working" } });
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
