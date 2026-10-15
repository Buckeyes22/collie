import { describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { MuxAdapter } from "../mux/types.ts";
import { OperationStore } from "./operations.ts";

/* eslint-disable anti-slop/require-safety-comment-for-type-assertion, anti-slop/no-chained-type-assertions
 * SAFETY: route fixtures intentionally provide only the invoked mux surface and bind it at the seam.
 */
import { MetadataStateStore, serveMetadataRoute } from "./metadata.ts";
import { ORIGINAL_AGENT_BINDING, ORIGINAL_RUNTIME_INCARNATION } from "./agent-models.ts";

describe("bounded metadata HTTP route", () => {
  test("requires the current agent, durable receipt and rechecks scope before its one-shot report", async () => {
    const root = await mkdtemp(join(tmpdir(), "collie-b31-luna-current1-metadata-"));
    try {
      const operations = new OperationStore(root);
      expect(await operations.prepare()).toBe(true);
      let reports = 0;
      let guardedReports = 0;
      let currentAgent = "codex";
      let uncertain = false;
      const mux = {
        capabilities: { supports: { agentMetadata: true } },
        snapshot: async () => ({
          panes: [{ paneId: "p1", terminalIncarnation: "terminal-a", agent: currentAgent, alive: true }], spaces: [], tabs: [],
        }),
        agentControl: async () => ({ ok: true, value: {
          paneId: "p1", terminalId: "terminal-a", workspaceId: "w1", tabId: "t1", runtimeIdentity: "runtime-a", agent: currentAgent,
          sessionIdentity: null, processIdentity: "process-a", name: null, status: "working", focused: true, interactiveReady: true,
          launchPending: false, stateChangeSeq: "0", completionSeq: null,
          [ORIGINAL_AGENT_BINDING]: "c".repeat(64), [ORIGINAL_RUNTIME_INCARNATION]: "runtime-original",
        } }),
        reportPaneMetadata: async () => { reports += 1; return uncertain ? { ok: false, reason: "unreachable", detail: "post-dispatch occupant race" } : { ok: true, value: undefined }; },
        reportPaneMetadataOriginal: async () => { guardedReports += 1; return uncertain ? { ok: false, reason: "unreachable", detail: "post-dispatch occupant race" } : { ok: true, value: undefined }; },
      } as unknown as MuxAdapter;
      const caller = {
        gate: () => null,
        pairedIdentity: () => "a".repeat(64),
        audit: () => {},
        resolve: async () => ({ host: "host-a", session: "session-a", mux }),
      };
      const request = (requestId: string, agent: string, tokens?: Record<string, string>) => {
        const body = { requestId, agent, title: "task", seq: requestId === "token" ? 1 : requestId === "right" ? 2 : 3, ttl_ms: 60_000, tokens: undefined as Record<string, string> | undefined };
        if (tokens !== undefined) body.tokens = tokens;
        return new Request("https://collie.test/api/pane/p1/metadata", {
          method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
        });
      };
      const store = new MetadataStateStore();
      const wrong = await serveMetadataRoute(request("wrong", "claude"), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(wrong?.status).toBe(409);
      expect(reports).toBe(0);
      const token = await serveMetadataRoute(request("token", "codex", { private: "only-codex" }), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(token?.status).toBe(200);
      expect(guardedReports).toBe(1);
      currentAgent = "codex";
      const response = await serveMetadataRoute(request("right", "codex"), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(response?.status).toBe(200);
      expect(reports).toBe(1);
      const duplicate = await serveMetadataRoute(request("right", "codex"), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(duplicate?.status).toBe(200);
      expect(reports).toBe(1);
      currentAgent = "claude";
      const stale = await serveMetadataRoute(request("stale", "codex"), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(stale?.status).toBe(409);
      expect(reports).toBe(1);
      currentAgent = "codex";
      uncertain = true;
      const race = await serveMetadataRoute(request("race", "codex"), new URL("https://collie.test/api/pane/p1/metadata"), caller, store, operations);
      expect(race?.status).toBe(202);
      expect((await race!.json()).operation.state).toBe("unknown");
      expect(reports).toBe(2);
      const reconciliationUrl = new URL("https://collie.test/api/pane/p1/metadata?request_id=right");
      const reconciled = await serveMetadataRoute(new Request(reconciliationUrl), reconciliationUrl, caller, store, operations);
      expect(await reconciled?.json()).toMatchObject({ operation: { requestId: "right", state: "succeeded" } });
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
