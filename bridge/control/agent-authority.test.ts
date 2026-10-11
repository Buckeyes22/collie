import { describe, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AgentReporterRegistry, refuseUnsafeAgentMutation, validRegisteredResumeArgv, validateAgentMutationIntent } from "./agent-authority.ts";
import type { MuxAgentIdentity } from "../mux/types.ts";

const current: MuxAgentIdentity = {
  paneId: "w1:p4", agent: "codex", status: "working",
  session: { source: "codex", agent: "codex", kind: "id", value: "session-7" },
  stateAuthority: { observed: false, source: null }, interactiveReady: true, launchPending: false,
  stateChangeSeq: "8", completionSeq: null,
};
const registry = new AgentReporterRegistry([{ source: "collie.custom.1", resumeExecutables: ["codex"], sessionReferences: [{ agent: "codex", kind: "id", value: "session-8" }] }]);
const base = {
  source: "collie.custom.1", expectedAgent: "codex", expectedSession: current.session, seq: 8,
};

describe("operator registered agent mutation intent validation", () => {
  test("loads only a bounded, strict operator-owned custom source registry", async () => {
    const dir = await mkdtemp(join(tmpdir(), "collie-agent-reporters-"));
    try {
      expect((await AgentReporterRegistry.load(dir)).sources()).toEqual([]);
      await writeFile(join(dir, "agent-reporters.json"), JSON.stringify({
        schema: 1, sources: [{ source: "collie.custom.1", resumeExecutables: ["codex"], sessionReferences: [] }],
      }), { mode: 0o600 });
      expect((await AgentReporterRegistry.load(dir)).sources()).toEqual(["collie.custom.1"]);
      await writeFile(join(dir, "agent-reporters.json"), JSON.stringify({
        schema: 1, sources: [{ source: "codex", resumeExecutables: ["codex"] }],
      }), { mode: 0o600 });
      expect((await AgentReporterRegistry.load(dir)).sources()).toEqual([]);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });

  test("requires an exact current pane, agent, factual session reference and sequence", () => {
    expect(validateAgentMutationIntent("state", "w1:p4", { ...base, state: "blocked" }, registry, current)).toMatchObject({ state: "blocked" });
    expect(validateAgentMutationIntent("state", "w1:p4", { ...base, seq: 7, state: "blocked" }, registry, current)).toBeNull();
    expect(validateAgentMutationIntent("state", "w1:p4", { ...base, expectedSession: { ...current.session!, value: "other" }, state: "blocked" }, registry, current)).toBeNull();
    expect(validateAgentMutationIntent("state", "w1:p5", { ...base, state: "blocked" }, registry, current)).toBeNull();
  });

  test("rejects unregistered and official hook sources, and never accepts runtime-owned done", () => {
    expect(() => new AgentReporterRegistry([{ source: "codex", resumeExecutables: ["codex"], sessionReferences: [] }])).toThrow();
    expect(validateAgentMutationIntent("state", "w1:p4", { ...base, source: "herdr:claude", state: "blocked" }, registry, current)).toBeNull();
    expect(validateAgentMutationIntent("state", "w1:p4", { ...base, state: "done" }, registry, current)).toBeNull();
  });

  test("state is a closed original JSON string type, never a coerced composite", () => {
    for (const state of ["idle", "working", "blocked", "unknown"]) {
      expect(validateAgentMutationIntent("state", "w1:p4", { ...base, state }, registry, current)).toMatchObject({ state });
    }
    for (const state of [["idle"], {}, null, true, 1, "future", "done"]) {
      expect(validateAgentMutationIntent("state", "w1:p4", { ...base, state }, registry, current)).toBeNull();
    }
  });

  test("validates session-only updates and the registered, bounded argv form", () => {
    const session = { ...current.session!, source: "collie.custom.1", value: "session-8" };
    expect(validateAgentMutationIntent("session", "w1:p4", { ...base, session }, registry, current)).toMatchObject({ kind: "session", session });
    expect(validateAgentMutationIntent("session", "w1:p4", { ...base, session, resumeArgv: ["codex", "resume", "session-8"] }, registry, current)).toMatchObject({ resumeArgv: ["codex", "resume", "session-8"] });
    expect(validRegisteredResumeArgv(["codex", "resume", "session-8"], registry.get("collie.custom.1")!, "session-8")).toBe(true);
    expect(validRegisteredResumeArgv(["codex", "resume", "other-session"], registry.get("collie.custom.1")!, "session-8")).toBe(false);
    expect(validRegisteredResumeArgv(["/usr/bin/codex", "resume"], registry.get("collie.custom.1")!)).toBe(false);
    expect(validRegisteredResumeArgv(["codex", "--note='please'"], registry.get("collie.custom.1")!)).toBe(false);
    expect(validRegisteredResumeArgv(["codex", "x".repeat(9000)], registry.get("collie.custom.1")!)).toBe(false);
  });

  test("rejects C0, DEL and C1 controls from session references and resume argv without excluding Unicode", () => {
    const registration = registry.get("collie.custom.1")!;
    const controls = [...Array.from({ length: 32 }, (_, code) => code), 127, ...Array.from({ length: 32 }, (_, index) => index + 128)];
    for (const code of controls) {
      const control = String.fromCodePoint(code);
      for (const field of ["source", "agent", "value"] as const) {
        const invalidCurrent = {
          ...current,
          session: { ...current.session!, [field]: `session${control}` },
        };
        expect(validateAgentMutationIntent("state", "w1:p4", { ...base, state: "blocked" }, registry, invalidCurrent)).toBeNull();
        expect(validateAgentMutationIntent("state", "w1:p4", {
          ...base,
          expectedSession: { ...current.session!, [field]: `session${control}` },
          state: "blocked",
        }, registry, current)).toBeNull();
      }
      expect(() => new AgentReporterRegistry([{
        source: "collie.custom.1",
        resumeExecutables: ["codex"],
        sessionReferences: [{ agent: "codex", kind: "id", value: `session${control}` }],
      }])).toThrow();
      expect(validRegisteredResumeArgv(["codex", `resume${control}`], registration)).toBe(false);
    }
    const unicodeCurrent = {
      ...current,
      session: { ...current.session!, value: "session-7-日本語-β" },
    };
    expect(validateAgentMutationIntent("state", "w1:p4", {
      ...base,
      expectedSession: unicodeCurrent.session,
      state: "blocked",
    }, registry, unicodeCurrent)).toMatchObject({ state: "blocked" });
    expect(validRegisteredResumeArgv(["codex", "resume", "session-7-日本語-β"], registration)).toBe(true);
  });

  test("requires a source-specific clear/release request and never permits global clear", () => {
    expect(validateAgentMutationIntent("release", "w1:p4", base, registry, current)).toMatchObject({ kind: "release" });
    expect(validateAgentMutationIntent("authority-clear", "w1:p4", { ...base, source: undefined }, registry, current)).toBeNull();
    expect(validateAgentMutationIntent("authority-clear", "w1:p4", { ...base, extra: true }, registry, current)).toBeNull();
  });

  test("a valid intent remains refused when the owning runtime lacks occupant compare-and-set", async () => {
    let identityReads = 0;
    const mux = {
      capabilities: { supports: { agentIdentity: true, agentLifecycleWrites: false } },
      agentIdentity: async (): Promise<{ ok: true; value: MuxAgentIdentity }> => {
        identityReads += 1;
        return { ok: true, value: current };
      },
    };
    const req = new Request("https://collie.test/api/pane/w1%3Ap4/agent/state", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ ...base, state: "blocked" }),
    });
    const response = await refuseUnsafeAgentMutation(mux, {
      current: () => ({ agents: [{ paneId: "w1:p4" }], shellPanes: [] }),
    }, "w1:p4", "state", req, registry);
    expect(response.status).toBe(501);
    expect(await response.text()).toContain("agent.occupant_cas_required");
    expect(identityReads).toBe(1);
  });

  test("oversized or non-JSON mutation bodies are refused before diagnostic dispatch", async () => {
    let identityReads = 0;
    const mux = {
      capabilities: { supports: { agentIdentity: true, agentLifecycleWrites: false } },
      agentIdentity: async (): Promise<{ ok: true; value: MuxAgentIdentity }> => {
        identityReads += 1;
        return { ok: true, value: current };
      },
    };
    const engine = { current: () => ({ agents: [{ paneId: "w1:p4" }], shellPanes: [] }) };
    const oversized = await refuseUnsafeAgentMutation(mux, engine, "w1:p4", "state", new Request(
      "https://collie.test/api/pane/w1%3Ap4/agent/state", { method: "POST", headers: { "content-type": "application/json" }, body: "{".padEnd(16 * 1024 + 1, " ") },
    ), registry);
    expect(oversized.status).toBe(400);
    const wrongType = await refuseUnsafeAgentMutation(mux, engine, "w1:p4", "state", new Request(
      "https://collie.test/api/pane/w1%3Ap4/agent/state", { method: "POST", headers: { "content-type": "text/plain" }, body: JSON.stringify({ ...base, state: "blocked" }) },
    ), registry);
    expect(wrongType.status).toBe(400);
    expect(identityReads).toBe(0);
  });

  test("an unregistered source gets a typed refusal and no runtime read or write", async () => {
    let identityReads = 0;
    const mux = {
      capabilities: { supports: { agentIdentity: true, agentLifecycleWrites: false } },
      agentIdentity: async (): Promise<{ ok: true; value: MuxAgentIdentity }> => {
        identityReads += 1;
        return { ok: true, value: current };
      },
    };
    const response = await refuseUnsafeAgentMutation(mux, {
      current: () => ({ agents: [{ paneId: "w1:p4" }], shellPanes: [] }),
    }, "w1:p4", "state", new Request("https://collie.test/api/pane/w1%3Ap4/agent/state", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...base, state: "blocked" }),
    }), new AgentReporterRegistry([]));
    expect(response.status).toBe(403);
    expect(await response.text()).toContain("agent.source_unregistered");
    expect(identityReads).toBe(0);
  });
});
