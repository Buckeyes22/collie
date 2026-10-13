import { describe, expect, test } from "bun:test";

import { agentCapabilities, PWA_SEMANTIC_ADAPTERS } from "./agent-capabilities.ts";
import { HARNESS_LAUNCHES, type HarnessProbe } from "./harness-launch.ts";
import { HARNESS_CATALOG_NAMES } from "./harness-catalog.ts";
import { buildJournalRegistry } from "./journal/registry.ts";
import { declareCapabilities } from "./mux/capabilities.ts";
import type { MuxPane, MuxSnapshot } from "./mux/types.ts";
import type { AgentCapabilitiesInput } from "./agent-capabilities.ts";
import { registeredAgents } from "../web/src/lib/harness/registry.ts";

const journals = buildJournalRegistry({
  claude: ["/claude"], codex: ["/codex"], gemini: ["/gemini"], pi: ["/pi"], opencode: ["/opencode"],
  grok: ["/grok"], hermes: ["/hermes"], muse: ["/muse"],
});

function input(found: string[] = [], observed: Array<{ kind: string; session?: string; exited?: boolean }> = []): AgentCapabilitiesInput & {
  supports: { agentDetection: boolean; agentSessionRef: boolean };
} {
  const supports = { agentDetection: true, agentSessionRef: true };
  const panes: MuxPane[] = observed.map(({ kind, session, exited }, index) => {
    const base: MuxPane = {
      paneId: `pane-${index}`, spaceId: "fixture-space", spaceLabel: "Fixture", spaceNumber: 1,
      tabId: "fixture-tab", cwd: "/tmp", focused: false, alive: !exited,
      agent: exited ? "shell" : kind, status: "unknown",
    };
    if (session === undefined) return base;
    const withSession = { ...base, agentSession: { kind: "id" as const, value: session } };
    if (exited) return { ...withSession, sessionAgent: kind };
    return withSession;
  });
  const snapshot: MuxSnapshot = { panes, spaces: [], tabs: [] };
  const mux: AgentCapabilitiesInput["mux"] = {
    mux: "fixture",
    get capabilities() {
      const claimed = [
        ...(supports.agentDetection ? ["agentDetection" as const] : []),
        ...(supports.agentSessionRef ? ["agentSessionRef" as const] : []),
      ];
      return declareCapabilities({ supports: claimed, topologyLatency: { kind: "push" } });
    },
    snapshot: async () => snapshot,
  };
  const harnesses: Pick<HarnessProbe, "list" | "versions"> = {
    list: async () => HARNESS_LAUNCHES.map(({ id, label }) => ({ id, label, found: found.includes(id) })),
    versions: async () => new Map(found.includes("claude") ? [["claude", "2.1.296"]] : []),
  };
  return { mux, supports, harnesses, journals };
}

describe("GET /api/agents/capabilities response", () => {
  test("has all 24 distinct canonical rows and keeps executable/version claims bounded", async () => {
    const additionalLaunches = ["cline", "copilot", "kimi", "qwen"];
    const result = await agentCapabilities(input(["claude", ...additionalLaunches]));
    expect(result.schema).toBe(1);
    expect(result.rows.map((row) => row.kind)).toEqual([...HARNESS_CATALOG_NAMES]);
    expect(result.rows).toHaveLength(24);
    expect(result.rows.find((row) => row.kind === "claude")).toMatchObject({
      executableFound: true, version: "2.1.296", launch: "implemented", journal: "implemented",
    });
    expect(result.rows.find((row) => row.kind === "cursor")).toMatchObject({
      executableFound: false, version: null, launch: "implemented", journal: "unverified", media: "unverified",
    });
    expect(result.rows.find((row) => row.kind === "agy")?.launch).toBe("implemented");
    for (const kind of additionalLaunches) {
      expect(result.rows.find((row) => row.kind === kind)).toMatchObject({
        launch: "implemented", executableFound: true, version: null,
        detection: "unverified", sessionReference: "unverified", journal: "unverified",
      });
    }
    expect(JSON.stringify(result)).not.toMatch(/\/home\/|\/usr\/|PATH|argv/i);
  });

  test("distinguishes registered PWA semantics from source-backed journal and image adapters", async () => {
    const registered: string[] = [...registeredAgents()];
    const semanticAdapters: string[] = [...PWA_SEMANTIC_ADAPTERS];
    expect(semanticAdapters.toSorted()).toEqual(registered.toSorted());
    const result = await agentCapabilities(input());
    expect(result.rows.find((row) => row.kind === "omp")).toMatchObject({
      journal: "implemented", webSemanticDialog: "implemented", webComposer: "implemented", media: "interface-ready",
    });
    expect(result.rows.find((row) => row.kind === "muse")).toMatchObject({
      journal: "implemented", webSemanticDialog: "implemented", media: "interface-ready",
    });
    expect(result.rows.find((row) => row.kind === "gemini")).toMatchObject({
      journal: "implemented", webSemanticDialog: "unverified", media: "unverified",
    });
    expect(result.muxCapabilities).toEqual({ agentDetection: true, agentSessionRef: true });
  });

  test("does not turn a backend's generic detection/session capability into per-kind evidence", async () => {
    const data = input();
    data.supports.agentDetection = false;
    data.supports.agentSessionRef = false;
    const result = await agentCapabilities(data);
    expect(result.rows.every((row) => row.detection === "unverified" && row.sessionReference === "unverified")).toBe(true);
  });

  test("a conflicting snapshot cannot override explicitly absent detection or session-reference support", async () => {
    const data = input([], [{ kind: "claude", session: "opaque-1" }]);
    data.supports.agentDetection = false;
    data.supports.agentSessionRef = false;

    const result = await agentCapabilities(data);

    expect(result.rows.find((row) => row.kind === "claude")).toMatchObject({
      detection: "unverified", sessionReference: "unverified", observedPanes: 1, observedSessionReferences: 1,
    });
  });

  test("reports only exact owner-snapshot detections and refs, with counts for current observations", async () => {
    const result = await agentCapabilities(input([], [
      { kind: "claude", session: "opaque-1" },
      { kind: "claude" },
      { kind: "pi", session: "opaque-closed", exited: true },
      { kind: "shell" },
      { kind: "unknown-vendor", session: "ignored" },
    ]));
    expect(result.rows.find((row) => row.kind === "claude")).toMatchObject({
      detection: "implemented", sessionReference: "implemented", observedPanes: 2,
      observedSessionReferences: 1,
    });
    expect(result.rows.find((row) => row.kind === "codex")).toMatchObject({
      detection: "unverified", sessionReference: "unverified", observedPanes: 0,
      observedSessionReferences: 0,
    });
    expect(result.rows.find((row) => row.kind === "pi")).toMatchObject({
      detection: "unverified", sessionReference: "implemented", observedPanes: 0,
      observedSessionReferences: 1,
    });
    expect(result.rows).toHaveLength(24);
  });
});
