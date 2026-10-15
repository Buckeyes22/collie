import { describe, expect, test } from "bun:test";
import { FakeHerdr } from "../mux/herdr/fixture.ts";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { servePaneAgentIdentity, type AgentIdentitySnapshot } from "./agent-identity.ts";
import type { MuxAgentIdentity, MuxOutcome } from "../mux/types.ts";

function snapshot(paneId: string, current = () => paneId): AgentIdentitySnapshot {
  return { current: () => ({ agents: [{ paneId: current() }], shellPanes: [] }) };
}

describe("GET /api/pane/:id/agent", () => {
  test("joins exact pane and agent.get facts while keeping authority explicitly unknown", async () => {
    const fake = new FakeHerdr();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const wire = {
      agent: "codex", agent_status: "working" as const,
      agent_session: { source: "codex", agent: "codex", kind: "id" as const, value: "session-42" },
      interactive_ready: true, launch_pending: false, state_change_seq: "9", completion_seq: "7",
    };
    fake.changePaneMetadata(paneId, wire);
    fake.agentInfoRows.push({ pane_id: paneId, ...wire });
    const response = await servePaneAgentIdentity(new HerdrMux(fake, () => []), snapshot(paneId), paneId);
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toMatchObject({
      paneId, agent: "codex", status: "working", stateAuthority: { observed: false, source: null },
      interactiveReady: true, launchPending: false, stateChangeSeq: "9", completionSeq: "7",
      session: { source: "codex", kind: "id", value: "session-42" },
    });
  });

  test("fails closed when a pane disappears or changes occupant during the joined read", async () => {
    class Replaced extends FakeHerdr {
      private reads = 0;
      override async getPane(target: string) {
        this.reads += 1;
        if (this.reads === 2) this.changeTerminalOccupant(target, "replacement-terminal");
        return super.getPane(target);
      }
    }
    const fake = new Replaced();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const result = await new HerdrMux(fake, () => []).agentIdentity(paneId);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe("gone");
  });

  test("rejects adapter claims that infer lifecycle authority", async () => {
    const invalidAuthority: MuxAgentIdentity["stateAuthority"] = { observed: false, source: null };
    Object.assign(invalidAuthority, { observed: true, source: "codex" });
    const invalid: MuxAgentIdentity = {
      paneId: "p1", agent: "codex", status: "working",
      stateAuthority: invalidAuthority,
      interactiveReady: true, launchPending: false, stateChangeSeq: "1", completionSeq: null,
    };
    const mux = { capabilities: { supports: { agentIdentity: true } }, agentIdentity: async (): Promise<MuxOutcome<MuxAgentIdentity>> => ({ ok: true, value: invalid }) };
    const response = await servePaneAgentIdentity(mux, snapshot("p1"), "p1");
    expect(response.status).toBe(409);
  });

  test("refuses invalid or overflowing sequence facts from an adapter before returning JSON", async () => {
    const paneId = "p1";
    const result = await servePaneAgentIdentity({
      capabilities: { supports: { agentIdentity: true } },
      agentIdentity: async () => ({ ok: true, value: {
        paneId, agent: "codex", status: "working", stateAuthority: { observed: false, source: null },
        interactiveReady: true, launchPending: false, stateChangeSeq: "18446744073709551616", completionSeq: null,
      } }),
    }, snapshot(paneId), paneId);
    expect(result.status).toBe(409);
    expect(await result.text()).toContain("agent.identity_mismatch");
  });

  test("does not return a result after the pane leaves the admitted snapshot", async () => {
    let pane = "p1";
    const response = await servePaneAgentIdentity({
      capabilities: { supports: { agentIdentity: true } },
      agentIdentity: async (paneId) => {
        pane = "p2";
        return { ok: true, value: {
          paneId, agent: "codex", status: "working", stateAuthority: { observed: false, source: null },
          interactiveReady: true, launchPending: false, stateChangeSeq: "1", completionSeq: null,
        } };
      },
    }, snapshot("p1", () => pane), "p1");
    expect(response.status).toBe(404);
  });
});
