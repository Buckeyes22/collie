import { describe, expect, test } from "bun:test";
import type { AgentView } from "../types.ts";
import { queuedMessagesDigest, queuedSendOccupant } from "./queued-send.ts";
import { QueuedSendAdmissionLedger, parseGuardedQueuedSend } from "./queued-send-admission.ts";

const pane: AgentView = {
  paneId: "w1:p1",
  workspaceId: "w1",
  workspaceLabel: "repo",
  workspaceNumber: 1,
  tabId: "w1:t1",
  agent: "claude",
  status: "working",
  cwd: "/repo",
  focused: false,
  agentSession: { kind: "id", value: "session-secret-ref" },
};

describe("queued send identities", () => {
  test("queue digest binds order and exact text without returning it", () => {
    const one = queuedMessagesDigest(["first", "second"]);
    expect(one).toMatch(/^[0-9a-f]{64}$/);
    expect(one).not.toContain("first");
    expect(queuedMessagesDigest(["second", "first"])).not.toBe(one);
    expect(queuedMessagesDigest(["first", "second "])).not.toBe(one);
  });

  test("occupant identity binds the owning session, harness, pane placement and journal ref", () => {
    const initial = queuedSendOccupant("default", pane, "https://collie.example");
    expect(initial).toMatch(/^[0-9a-f]{64}$/);
    expect(initial).not.toContain("session-secret-ref");
    expect(queuedSendOccupant("other", pane, "https://collie.example")).not.toBe(initial);
    expect(queuedSendOccupant("default", { ...pane, agent: "codex" }, "https://collie.example")).not.toBe(initial);
    expect(queuedSendOccupant("default", { ...pane, agentSession: { kind: "id", value: "next" } }, "https://collie.example")).not.toBe(initial);
    expect(queuedSendOccupant("default", { ...pane, tabId: "w1:t2" }, "https://collie.example")).not.toBe(initial);
    expect(queuedSendOccupant("default", pane, "https://other.example")).not.toBe(initial);
  });

  test("guard grammar is closed and contains only opaque queue/occupant identities", () => {
    const digest = queuedMessagesDigest(["do not transmit this queued text"]);
    const occupant = queuedSendOccupant("default", pane, "https://collie.example");
    expect(parseGuardedQueuedSend({ generation: 4, revision: 9, queue_digest: digest, occupant })).toEqual({
      generation: 4, revision: 9, queueDigest: digest, occupant,
    });
    expect(parseGuardedQueuedSend({ generation: 4, revision: 9, queue_digest: digest, occupant, queued: "secret" })).toBeNull();
    expect(parseGuardedQueuedSend({ generation: -1, revision: 9, queue_digest: digest, occupant })).toBeNull();
  });

  test("serializes duplicates and keeps uncertain attempts retired until newer owner evidence", () => {
    const ledger = new QueuedSendAdmissionLedger(2);
    expect(ledger.begin("action", "session:pane")).toBe("admitted");
    expect(ledger.begin("action", "session:pane")).toBe("busy");
    expect(ledger.begin("new-generation", "session:pane")).toBe("busy");
    expect(ledger.retireBeforeDispatch("action")).toBe(true);
    expect(ledger.begin("action", "session:pane")).toBe("already-attempted");
    expect(ledger.outcome("action")).toBe("outcome_unknown");
    ledger.observe("session:pane", "new-queue");
    expect(ledger.outcome("action")).toBeUndefined();
    expect(ledger.begin("new-queue", "session:pane")).toBe("admitted");
  });
});
