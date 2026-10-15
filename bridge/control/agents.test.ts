import { forwardToPeer, crewRouteFor, forwardGate, forwardKind } from "../crew/forward.ts";
import { afterEach, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { HerdrClient } from "../mux/herdr/client.ts";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { AgentTestPeer } from "../mux/herdr/agent-test-peer.ts";
import { OperationStore } from "./operations.ts";
import { serveAgentRoute, type AgentCaller } from "./agents.ts";
import { AgentWaitStore, serveWaitRoute } from "./waits.ts";
import { strictAgentJson, parseAgentAction, parseAgentWait } from "./agent-requests.ts";
import type { JsonObject } from "../json.ts";
import { maskForwardedAnswer } from "../crew/mask.ts";
import type { AgentEventKind } from "./agent-models.ts";

const peers: AgentTestPeer[] = []; const roots: string[] = []; const waits: AgentWaitStore[] = [];
afterEach(async () => { await Promise.all(waits.splice(0).map((store) => store.close())); for (const peer of peers.splice(0)) peer.close(); for (const ownedRoot of roots.splice(0)) rmSync(ownedRoot, { recursive: true, force: true }); });
function root(): string { const path = mkdtempSync(join(tmpdir(), "collie-b19-actions-")); roots.push(path); return path; }
async function fixture(beforePersist?: () => Promise<void>) {
  const peer = new AgentTestPeer(); peers.push(peer); await peer.listen();
  const mux = new HerdrMux(new HerdrClient(peer.path, 1000, "auto"), () => []); expect(await mux.reachable()).toBe(true);
  let admitted = true; let owner = "a".repeat(64); let writes = true;
  const caller: AgentCaller = { redact: true, receiptKey: new Uint8Array(32).fill(9), gate: (level) => !admitted || (level === "write" && !writes) ? Response.json({ ok: false, code: "test.factor_required" }, { status: 403 }) : null, pairedIdentity: () => owner, composerSafe: () => true, visiblePrompt: (text) => text, audit: () => {}, resolve: async () => ({ host: "local", session: "fixture", mux, currentRuntime: () => admitted, current: (expected) => admitted && (expected.paneId === peer.paneId ? peer.terminal : expected.paneId === "w1:p1" ? peer.originalSlotReplacement : null) === expected.terminalId }) };
  const directory = root(); const operations = new OperationStore(directory, Date.now, beforePersist); await operations.prepare();
  const get = async (verb = "control") => {
    const url = new URL(`https://collie.invalid/api/pane/w1:p1/agent/${verb}`);
    const response = await serveAgentRoute(new Request(url), url, caller, operations); if (!response) throw new Error("route missing");
    // SAFETY: controlled owning handler creates JSON; selected fields are asserted below.
    return { response, body: await response.json() as JsonObject };
  };
  const post = async (verb: string, action: JsonObject, id: string, capturedWitness: string, expectedText: string | null = null) => {
    const url = new URL(`https://collie.invalid/api/pane/w1:p1/agent/${verb}`);
    const request = new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ requestId: id, witness: capturedWitness, action, expectedText }) });
    const response = await serveAgentRoute(request, url, caller, operations); if (!response) throw new Error("route missing");
    // SAFETY: controlled owning handler creates JSON; selected fields are asserted below.
    return { response, body: await response.json() as JsonObject };
  };
  return { peer, mux, caller, operations, directory, get, post, retire: () => { admitted = false; }, replaceOwner: () => { owner = "b".repeat(64); }, readOnly: () => { writes = false; } };
}
function row(body: JsonObject, key: string): JsonObject {
  const value = body[key];
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`missing ${key}`); return value;
}
function witness(body: JsonObject): string {
  // eslint-disable-next-line anti-slop/no-runtime-typeof
  if (typeof body.witness !== "string") throw new Error("missing witness"); return body.witness;
}

test("shipped named start, rename and atomic prompt use their exact owning envelopes, including revision zero", async () => {
  const f = await fixture(); f.peer.agent = null; f.peer.name = null;
  const initial = await f.get(); const start = await f.post("start", { name: "named", agent: "codex", args: [], timeoutMs: 4000 }, "start-1", witness(initial.body));
  expect(start.response.status).toBe(200); expect(row(start.body, "operation").state).toBe("succeeded"); expect(f.peer.effects).toBe(1);
  expect(f.peer.requests.find((request) => request.method === "agent.start")?.params).toEqual({ pane_id: "w1:p1", name: "named", kind: "codex", args: [], timeout_ms: 4000 });
  const named = await f.get(); const renamed = await f.post("rename", { name: "renamed" }, "rename-1", witness(named.body));
  expect(row(renamed.body, "operation").state).toBe("succeeded");
  const ready = await f.get(); const prompt = await f.post("prompt", { text: "own bounded prompt", until: ["idle"], timeoutMs: 4000 }, "prompt-1", witness(ready.body), "> ready");
  expect(row(prompt.body, "operation").state).toBe("succeeded"); expect(row(prompt.body, "progress")).toEqual({ submitted: true, activityObserved: true, settled: true });
  expect(f.peer.requests.find((request) => request.method === "agent.prompt")?.params).toEqual({ target: "w1:p1", text: "own bounded prompt", wait: { until: ["idle"], timeout_ms: 4000 } });
  expect(f.peer.effects).toBe(3);
  const durable = readFileSync(join(f.directory, "control-operations.json"), "utf8"); expect(durable).not.toContain("own bounded prompt"); expect(durable).not.toContain("session-original");
  expect(maskForwardedAnswer("agent-control", JSON.stringify(prompt.body), "w1:p1")).not.toBeNull();
});

test("same immutable action id reconciles without a second vendor dispatch, and a changed body refuses", async () => {
  const f = await fixture(); const initial = await f.get(); const token = witness(initial.body);
  const first = await f.post("prompt", { text: "once", until: null, timeoutMs: 4000 }, "once", token, "> ready");
  expect(row(first.body, "operation").state).toBe("succeeded");
  expect(row((await f.post("prompt", { text: "once", until: null, timeoutMs: 4000 }, "once", token, "> ready")).body, "operation").state).toBe("succeeded");
  expect((await f.post("prompt", { text: "different", until: null, timeoutMs: 4000 }, "once", token, "> ready")).response.status).toBe(409);
  expect(f.peer.effects).toBe(1);
});

test("actual completion persistence crossing A to B retains only original A as unknown and never repeats either action", async () => {
  let writes = 0; let release!: () => void; let entered!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; }); const seen = new Promise<void>((resolve) => { entered = resolve; });
  const f = await fixture(async () => { if (++writes === 2) { entered(); await gate; } });
  const original = await f.get(); const old = f.post("rename", { name: "old-action" }, "persisted-A", witness(original.body));
  await seen; f.peer.terminal = "terminal-B"; const fresh = await f.get();
  const successor = f.post("rename", { name: "fresh-action" }, "persisted-B", witness(fresh.body)); release();
  const a = await old; const b = await successor;
  expect(row(a.body, "operation").state).toBe("unknown"); expect(a.body.result).toBeNull();
  expect(row(b.body, "operation").state).toBe("succeeded"); expect(f.peer.name).toBe("fresh-action");
  expect((await f.operations.inspect("persisted-A", "a".repeat(64), "local", "fixture"))?.state).toBe("unknown");
  expect((await f.operations.inspect("persisted-B", "a".repeat(64), "local", "fixture"))?.state).toBe("succeeded");
  const restored = new OperationStore(f.directory); expect((await restored.inspect("persisted-A", "a".repeat(64), "local", "fixture"))?.state).toBe("unknown");
  expect(row((await f.post("rename", { name: "old-action" }, "persisted-A", witness(original.body))).body, "operation").state).toBe("unknown");
  expect(f.peer.requests.filter((request) => request.method === "agent.rename")).toHaveLength(2); expect(f.peer.effects).toBe(2);
});

test("both device write factors and exact fresh composer are checked before any named RPC", async () => {
  const f = await fixture(); const initial = await f.get(); const token = witness(initial.body);
  expect(row((await f.post("prompt", { text: "safe", until: null, timeoutMs: 4000 }, "stale-composer", token, "> stale")).body, "operation").state).toBe("refused");
  f.readOnly(); expect((await f.post("rename", { name: "name" }, "read-only", token)).response.status).toBe(403);
  expect(f.peer.effects).toBe(0); expect(f.peer.requests.some((request) => ["agent.prompt", "agent.rename"].includes(request.method))).toBe(false);
});

test("public target replacement after dispatch is unknown, not false completion or automatic replay", async () => {
  const f = await fixture(); const initial = await f.get();
  f.peer.beforeEffect = () => { f.peer.terminal = "terminal-B"; };
  const result = await f.post("prompt", { text: "one-shot", until: null, timeoutMs: 4000 }, "resolver-gap", witness(initial.body), "> ready");
  expect(row(result.body, "operation").state).toBe("unknown"); expect(result.body.result).toBeNull(); expect(f.peer.effects).toBe(1);
  const restored = new OperationStore(f.directory); await restored.prepare();
  expect((await restored.inspect("resolver-gap", "a".repeat(64), "local", "fixture"))?.state).toBe("unknown");
  // This schedule documents the stock public-target resolver gap; it does not claim vendor CAS.
  expect(f.peer.requests.filter((request) => request.method === "agent.prompt")).toHaveLength(1);
});

test("current explain stays typed and an unrelated or uncorrelated envelope never proves success", async () => {
  const f = await fixture(); expect((await f.get("explain")).response.status).toBe(200);
  const initial = await f.get(); f.peer.wrongId = true;
  const result = await f.post("rename", { name: "newname" }, "wrong-id", witness(initial.body));
  expect(result.response.status).toBe(503); expect(f.peer.effects).toBe(0); // Correlation fails during the original preflight read.
});

test("actual stock explain-unavailable has a closed native refusal without leaking private vendor detail", async () => {
  const f = await fixture(); f.peer.explainRejection = true;
  const result = await f.get("explain"); expect(result.response.status).toBe(409);
  expect(JSON.stringify(result.body)).toContain("agent.explain_unavailable"); expect(JSON.stringify(result.body)).not.toContain("owned private explanation detail");
  expect(f.peer.effects).toBe(0); expect(f.peer.requests.some((request) => request.method === "agent.explain")).toBe(true);
});

test("wait ownership, same-id observation, cancellation and restart remain separate from delivered work", async () => {
  const f = await fixture(); const original = await f.get(); const store = new AgentWaitStore(f.directory); waits.push(store);
  let release!: () => void; f.peer.held = new Promise<void>((resolve) => { release = resolve; });
  const url = new URL("https://collie.invalid/api/waits");
  const body = { requestId: "held-wait", paneId: "w1:p1", witness: witness(original.body), condition: { kind: "lifecycle", until: ["idle"] }, timeoutMs: 4000 };
  const request = () => new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  expect((await serveWaitRoute(request(), url, f.caller, store))?.status).toBe(202); await f.peer.waitEntered;
  expect((await serveWaitRoute(request(), url, f.caller, store))?.status).toBe(202);
  expect(f.peer.requests.filter((observed) => observed.method === "agent.wait")).toHaveLength(1);
  const restarted = new AgentWaitStore(f.directory); expect((await restarted.inspect("held-wait", "a".repeat(64), "local", "fixture"))?.state).toBe("interrupted");
  expect(await store.inspect("held-wait", "b".repeat(64), "local", "fixture")).toBeNull();
  expect((await store.cancel("held-wait", "a".repeat(64), "local", "fixture"))?.state).toBe("cancelled"); release();
  expect(f.peer.effects).toBe(0); expect((await store.inspect("held-wait", "a".repeat(64), "local", "fixture"))?.result).toBeNull();
});

test("closed bodies reject duplicates, overflow, arbitrary RPCs, unsafe kinds and broad event selectors", () => {
  for (const value of ['{"name":1,"name":2}', '{"a":"x","a":"y"}', '[1,]', '{"x":1}x', '{"x":1e999}', '{"x":"\\ud800"}']) expect(() => strictAgentJson(value)).toThrow();
  expect(parseAgentAction("start", { name: "valid", agent: "sh -c", args: [], timeoutMs: 4000 })).toBeNull();
  expect(parseAgentAction("start", { name: "valid", agent: "codex", args: [], timeoutMs: 3000 })).toBeNull();
  expect(parseAgentWait({ kind: "event", event: "arbitrary_rpc", targetId: null, workspaceId: null, status: null, agent: null, label: null })).toBeNull();
  expect(parseAgentWait({ kind: "event", event: "agent-status-changed", targetId: null, workspaceId: null, status: "idle", agent: null, label: null })).toBeNull();
});

test("all named read and key/focus H10 methods reach exact stock envelopes without widening Crew or composer gates", async () => {
  const f = await fixture(); const original = await f.get();
  const censusUrl = new URL("https://collie.invalid/api/agents/named");
  const census = await serveAgentRoute(new Request(censusUrl), censusUrl, f.caller, f.operations); expect(census?.status).toBe(200);
  if (!census) throw new Error("census missing"); const censusBody = await census.text(); expect(maskForwardedAnswer("agent-control", censusBody)).not.toBeNull(); expect(censusBody).not.toContain("session-original");
  const readUrl = new URL("https://collie.invalid/api/pane/w1:p1/agent/read?source=recent&lines=200"); const read = await serveAgentRoute(new Request(readUrl), readUrl, f.caller, f.operations); expect(read?.status).toBe(200); expect(f.peer.requests.find((request) => request.method === "agent.read")?.params).toEqual({ target: "w1:p1", source: "recent", lines: 200, format: "ansi", strip_ansi: false });
  expect(row((await f.post("focus", {}, "focus", witness(original.body))).body, "operation").state).toBe("succeeded");
  expect(row((await f.post("keys", { keys: ["Enter"] }, "keys", witness(original.body), "> ready")).body, "operation").state).toBe("succeeded");
  expect(row((await f.post("input", { text: "exact", keys: ["Enter"] }, "input", witness(original.body), "> ready")).body, "operation").state).toBe("succeeded");
  expect(f.peer.requests.find((request) => request.method === "agent.send_keys")?.params).toEqual({ target: "w1:p1", keys: ["Enter"] });
  expect(f.peer.requests.find((request) => request.method === "pane.send_input")?.params).toEqual({ pane_id: "w1:p1", text: "exact", keys: ["Enter"] });
  expect(row((await f.post("keys", { keys: ["Enter"] }, "keys-stale", witness(original.body), "old screen")).body, "operation").state).toBe("refused"); expect(f.peer.effects).toBe(3);
});

test("named aliases resolve once to the actual terminal and prompt settling never reuses old idle", async () => {
  const f = await fixture(); const alias = new URL("https://collie.invalid/api/pane/original/agent/control"); const response = await serveAgentRoute(new Request(alias), alias, f.caller, f.operations); expect(response?.status).toBe(200);
  if (!response) throw new Error("alias missing");
  // SAFETY: the owning controlled handler emits JSON; selected identity fields are checked below.
  const value = await response.json() as JsonObject; expect(row(value, "info").paneId).toBe("w1:p1");
  f.peer.activityAfterPrompt = false; const outcome = await f.post("prompt", { text: "one shot", until: ["idle"], timeoutMs: 1000 }, "old-idle", witness(value), "> ready"); expect(row(outcome.body, "operation").state).toBe("unknown"); expect(outcome.body.result).toBeNull(); expect(f.peer.effects).toBe(1);
});
test("typed known pre-effect refusal stays distinct from possible delivered failure or stall", async () => {
  const f = await fixture(); const initial = await f.get(); let count = 0;
  for (const code of ["agent_blocked", "agent_not_found", "agent_not_ready", "empty_agent_prompt", "agent_prompt_failed", "agent_prompt_stalled", "agent_not_running", "timeout"]) {
    f.peer.rejection = code; const result = await f.post("prompt", { text: "owned bounded", until: null, timeoutMs: 1000 }, `error-${count++}`, witness(initial.body), "> ready"); const operation = row(result.body, "operation"); expect(operation.code).toBe(`agent.${code}`); expect(operation.state).toBe(["agent_blocked", "agent_not_found", "agent_not_ready", "empty_agent_prompt"].includes(code) ? "refused" : "unknown");
  }
  expect(f.peer.effects).toBe(0); expect(f.peer.requests.filter((request) => request.method === "agent.prompt")).toHaveLength(8);
});


test("actual Crew forwarding preserves all owning named methods, scopes and paired/read/write classifications", async () => {
  const f = await fixture(); const initial = await f.get(); let transports = 0;
  const state = { memberId: "member", health: "reachable" as const, lastSeenAt: 1, reason: null, version: "fixture", conflict: null, preflight: null };
  const deps: import("../crew/forward.ts").ForwardDeps = { link: { memberId: "member", address: "owned.invalid:8787" }, state, maxUploadBytes: 1024, device: "device", pairedIdentity: "a".repeat(64), mask: maskForwardedAnswer,
    transport: async (_link, route, params, init) => {
      transports++; expect(params.host).toBeUndefined(); expect(params.session).toBe("fixture");
      const member = new URL(`https://owned.invalid/api/${route}`); member.searchParams.set("session", params.session!);
      const request = new Request(member, init); const response = await serveAgentRoute(request, member, f.caller, f.operations); if (!response) throw new Error("owning route missing");
      return { ok: true, value: response, status: response.status, member: "member", receivedAt: 1, date: null };
    } };
  const classifications: readonly (readonly [string, string, import("../crew/peer-gate.ts").GateLevel])[] = [["agents/named", "GET", "device-read"], ["pane/w1:p1/agent/control", "GET", "device-read"], ["pane/w1:p1/agent/read", "GET", "device-read"], ["pane/w1:p1/agent/explain", "GET", "device-read"], ["waits/id", "DELETE", "device-read"], ...["start", "rename", "prompt", "focus", "keys", "input"].map((verb): readonly [string, string, "write"] => [`pane/w1:p1/agent/${verb}`, "POST", "write"])];
  for (const [path, method, gate] of classifications) {
    expect(crewRouteFor(`/api/${path}`)).toBe(path); expect(forwardGate(path!, method!)).toBe(gate); expect(forwardKind(path!, method!)).toBe(gate === "write" ? "write" : "read");
  }
  const url = new URL("https://lead.invalid/api/pane/w1:p1/agent/prompt?host=member&session=fixture");
  const request = new Request(url, { method: "POST", headers: { "content-type": "application/json", "x-crew-paired-identity": "forged" }, body: JSON.stringify({ requestId: "crew-once", witness: witness(initial.body), action: { text: "secret bounded", until: null, timeoutMs: 1000 }, expectedText: "> ready" }) });
  const result = await forwardToPeer(request, url, deps); expect(result.status).toBe(200); expect(transports).toBe(1); expect(f.peer.effects).toBe(1);
  const census = new URL("https://lead.invalid/api/agents/named?host=member&session=fixture"); const answer = await forwardToPeer(new Request(census), census, deps); expect(answer.status).toBe(200); expect((await answer.text())).not.toContain("session-original");
  const count = f.peer.effects; f.readOnly(); const refused = await forwardToPeer(new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: "{}" }), url, deps); expect(refused.status).toBe(403); expect(f.peer.effects).toBe(count);
});

async function waitRecord(store: AgentWaitStore, id: string, wanted: string): Promise<import("./waits.ts").WaitView> {
  const end = Date.now() + 2500;
  for (;;) { const view = await store.inspect(id, "a".repeat(64), "local", "fixture"); if (view?.state === wanted) return view; if (Date.now() >= end) throw new Error(`bounded observation did not reach ${wanted}`); await Bun.sleep(1); }
}
async function startObserved(f: Awaited<ReturnType<typeof fixture>>, store: AgentWaitStore, id: string, condition: JsonObject, timeoutMs = 2000) {
  const initial = await f.get(); const url = new URL("https://collie.invalid/api/waits"); const response = await serveWaitRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ requestId: id, paneId: "w1:p1", witness: witness(initial.body), condition, timeoutMs }) }), url, f.caller, store); return response;
}
test("pre-existing output is an observation, invalid regex a distinct interruption, and oversized regex is zero RPC", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  f.peer.waitResult = { type: "output_matched", pane_id: "w1:p1", revision: 0, matched_line: "already here", read: { pane_id: "w1:p1", workspace_id: "w1", tab_id: "w1:t1", source: "recent", format: "text", text: "already here", truncated: false, revision: 0 } };
  expect((await startObserved(f, store, "existing-output", { kind: "output", source: "recent", lines: 200, match: { kind: "substring", value: "already" } }))?.status).toBe(202);
  const matched = await waitRecord(store, "existing-output", "matched"); expect(matched.result?.kind).toBe("output"); expect(f.peer.effects).toBe(0);
  const wire = f.peer.requests.find((request) => request.method === "pane.wait_for_output"); expect(wire?.params).toEqual({ pane_id: "w1:p1", source: "recent", match: { type: "substring", value: "already" }, lines: 200, strip_ansi: true, timeout_ms: 2000 });
  f.peer.rejection = "invalid_regex"; f.peer.waitResult = null;
  expect((await startObserved(f, store, "invalid-regex", { kind: "output", source: "recent", lines: 200, match: { kind: "regex", value: "[" } }))?.status).toBe(202);
  expect((await waitRecord(store, "invalid-regex", "interrupted")).code).toBe("agent.invalid_regex");
  const previous = f.peer.requests.filter((request) => request.method === "pane.wait_for_output").length;
  expect((await startObserved(f, store, "large-regex", { kind: "output", source: "recent", lines: 200, match: { kind: "regex", value: "x".repeat(257) } }))?.status).toBe(400); expect(f.peer.requests.filter((request) => request.method === "pane.wait_for_output")).toHaveLength(previous);
});
test("typed closed events retain original scoped filters and replacement never completes an old wait", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  f.peer.waitResult = { type: "wait_matched", event: { event: "pane_agent_status_changed", data: { type: "pane_agent_status_changed", pane_id: "w1:p1", workspace_id: "w1", agent_status: "idle" } } };
  const condition: JsonObject = { kind: "event", event: "agent-status-changed", targetId: "w1:p1", workspaceId: null, status: "idle", agent: null, label: null };
  expect((await startObserved(f, store, "closed-event", condition))?.status).toBe(202); expect((await waitRecord(store, "closed-event", "matched")).result).toEqual({ kind: "event", event: "agent-status-changed", targetId: "w1:p1", status: "idle" });
  expect(f.peer.requests.find((request) => request.method === "events.wait")?.params).toEqual({ match_event: { event: "pane_agent_status_changed", pane_id: "w1:p1", agent_status: "idle" }, timeout_ms: 2000 });
  const other = { ...condition, targetId: "w1:p2" }; expect((await startObserved(f, store, "other-pane", other))?.status).toBe(400);
  f.peer.waitResult = null; let release!: () => void; f.peer.held = new Promise<void>((resolve) => { release = resolve; });
  expect((await startObserved(f, store, "replaced", { kind: "lifecycle", until: ["idle"] }))?.status).toBe(202);
  // The original result can arrive after public id reuse; it remains its old observation.
  f.peer.terminal = "terminal-B"; release(); const retired = await waitRecord(store, "replaced", "retired"); expect(retired.result).toBeNull(); expect(f.peer.effects).toBe(0);
});

async function subscriptionReady(peer: AgentTestPeer): Promise<void> {
  const deadline = Date.now() + 2000; while (peer.subscriptions.size === 0 && Date.now() < deadline) await Bun.sleep(1); expect(peer.subscriptions.size).toBe(1);
}
test("actual production subscription equivalence delivers scoped rename metadata while wrong scope and stream loss stay inert", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: "tab-renamed", targetId: "w1:t1", workspaceId: null, status: null, agent: null, label: "owned" };
  expect((await startObserved(f, store, "stream-rename", condition))?.status).toBe(202); await subscriptionReady(f.peer);
  expect(f.peer.requests.find((request) => request.method === "events.subscribe")?.params).toEqual({ subscriptions: [{ type: "tab.renamed" }] });
  f.peer.emitEvent("tab_renamed", { type: "tab_renamed", tab_id: "w1:other", workspace_id: "w1", label: "owned" }); await Bun.sleep(1); expect((await store.inspect("stream-rename", "a".repeat(64), "local", "fixture"))?.state).toBe("pending");
  f.peer.emitEvent("tab_renamed", { type: "tab_renamed", tab_id: "w1:t1", workspace_id: "w1", label: "owned" }); expect((await waitRecord(store, "stream-rename", "matched")).result).toEqual({ kind: "event", event: "tab-renamed", targetId: "w1:t1", status: null });
  expect((await startObserved(f, store, "stream-lost", condition))?.status).toBe(202); await subscriptionReady(f.peer); f.peer.loseEventStreams(); expect((await waitRecord(store, "stream-lost", "interrupted")).result).toBeNull(); expect(f.peer.requests.filter((request) => request.method === "events.subscribe")).toHaveLength(2); expect(f.peer.effects).toBe(0);
});
test("original subscription replacement, retained history loss and over-bound frames never produce an event result", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: "pane-focused", targetId: "w1:p1", workspaceId: null, status: null, agent: null, label: null };
  expect((await startObserved(f, store, "event-history-loss", condition))?.status).toBe(202); await subscriptionReady(f.peer);
  f.peer.emitRawFrame(`${JSON.stringify({ error: { code: "events_lost", message: "owned fixture retained history gap" } })}\n`); expect((await waitRecord(store, "event-history-loss", "interrupted")).code).toBe("agent.events_lost");
  expect((await startObserved(f, store, "event-bound", condition))?.status).toBe(202); await subscriptionReady(f.peer); f.peer.emitRawFrame("x".repeat(65_537)); expect((await waitRecord(store, "event-bound", "interrupted")).result).toBeNull();
  expect((await startObserved(f, store, "event-replaced", condition))?.status).toBe(202); await subscriptionReady(f.peer); f.peer.terminal = "terminal-B"; f.peer.emitEvent("pane_focused", { type: "pane_focused", pane_id: "w1:p1", workspace_id: "w1" }); expect((await waitRecord(store, "event-replaced", "retired")).result).toBeNull();
  expect(f.peer.requests.filter((request) => request.method === "events.subscribe")).toHaveLength(3); expect(f.peer.effects).toBe(0);
});
test("original output revision equivalence ignores a pre-existing value and completes only a later original revision", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: "pane-output-changed", targetId: "w1:p1", workspaceId: null, status: null, agent: null, label: null };
  expect((await startObserved(f, store, "revision-change", condition, 2500))?.status).toBe(202);
  const deadline = Date.now() + 1000; while (!f.peer.requests.some((request) => request.method === "pane.read" && request.params.intent === "passive") && Date.now() < deadline) await Bun.sleep(1);
  expect((await store.inspect("revision-change", "a".repeat(64), "local", "fixture"))?.state).toBe("pending");
  f.peer.outputRevision = 1; expect((await waitRecord(store, "revision-change", "matched")).result).toEqual({ kind: "event", event: "pane-output-changed", targetId: "w1:p1", status: null });
  expect(f.peer.requests.some((request) => request.method === "events.wait")).toBe(false); expect(f.peer.effects).toBe(0);
});

async function movedFixture(id: string) {
  const f = await fixture(); f.peer.originalObservations = true; expect(await f.mux.reachable()).toBe(true);
  const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: "pane-moved", targetId: "w1:p1", workspaceId: null, status: null, agent: null, label: null };
  const original = await f.get(); const originalWitness = witness(original.body);
  expect((await startObserved(f, store, id, condition, 3000))?.status).toBe(202); await subscriptionReady(f.peer);
  return { ...f, store, originalWitness, condition };
}
function movedFrame(peer: AgentTestPeer): JsonObject {
  return { type: "pane_moved", previous_pane_id: "w1:p1", previous_workspace_id: "w1", previous_tab_id: "w1:t1", pane: peer.pane() };
}
test("registered live move rereads the same original terminal at its destination and publishes no new action authority", async () => {
  const f = await movedFixture("live-move"); f.peer.paneId = "w1:p2"; f.peer.tabId = "w1:t2";
  f.peer.emitEvent("pane_moved", movedFrame(f.peer));
  const view = await waitRecord(f.store, "live-move", "matched");
  expect(view.paneId).toBe("w1:p1"); expect(view.result?.kind).toBe("event");
  if (view.result?.kind !== "event" || !view.result.move) throw new Error("original move proof missing");
  expect(view.result.targetId).toBe("w1:p2"); expect(view.result.move.previousPaneId).toBe("w1:p1");
  expect(view.result.move.previousWorkspaceId).toBe("w1"); expect(view.result.move.previousTabId).toBe("w1:t1");
  expect(view.result.move.info.terminalId).toBe("terminal-A"); expect(view.result.move.witness).toBe(f.originalWitness);
  expect(maskForwardedAnswer("waits", JSON.stringify({ ok: true, wait: view }))).toBe(JSON.stringify({ ok: true, wait: view }));
  const fresh = await f.store.inspect("live-move", "a".repeat(64), "local", "fixture"); expect(fresh?.state).toBe("matched");
  f.peer.originalSlotReplacement = "terminal-B";
  expect((await f.store.inspect("live-move", "a".repeat(64), "local", "fixture"))?.result).toBeNull();
  expect(f.peer.effects).toBe(0); expect(f.peer.requests.some((request) => request.method === "agent.prompt")).toBe(false);
});
test("a successor at the original slot cannot satisfy an old original move even when A remains elsewhere", async () => {
  const f = await movedFixture("old-slot-B"); f.peer.paneId = "w1:p2"; f.peer.tabId = "w1:t2"; f.peer.originalSlotReplacement = "terminal-B";
  f.peer.emitEvent("pane_moved", movedFrame(f.peer));
  expect((await waitRecord(f.store, "old-slot-B", "retired")).result).toBeNull(); expect(f.peer.effects).toBe(0);
});
test("old registered cursor, foreign registration and changed destination session never complete a move", async () => {
  for (const failure of ["cursor", "registration", "session", "process"] as const) {
    const f = await movedFixture(`invalid-move-${failure}`); f.peer.paneId = "w1:p2"; f.peer.tabId = "w1:t2";
    const registration = [...f.peer.registrations.values()][0]; if (!registration) throw new Error("actual registration missing");
    if (failure === "session" || failure === "process") { if (failure === "session") f.peer.sessionValue = "session-successor"; else f.peer.process++; f.peer.emitEvent("pane_moved", movedFrame(f.peer)); }
    else f.peer.emitRawFrame(`${JSON.stringify({ event: "pane_moved", data: movedFrame(f.peer), original_observation: { registration_id: failure === "registration" ? "other-registration" : registration.id, sequence: String(failure === "cursor" ? registration.start : registration.start + 1) } })}\n`);
    const end = Date.now() + 2000; let view;
    do { view = await f.store.inspect(`invalid-move-${failure}`, "a".repeat(64), "local", "fixture"); if (view?.state !== "pending") break; await Bun.sleep(1); } while (Date.now() < end);
    expect(view?.state).not.toBe("matched"); expect(view?.state).not.toBe("pending"); expect(view?.result).toBeNull(); expect(f.peer.effects).toBe(0);
  }
});
test("retired original read owner closes the registered observer without reading the moved destination", async () => {
  const f = await movedFixture("retired-move"); f.retire(); f.peer.paneId = "w1:p2"; f.peer.tabId = "w1:t2";
  f.peer.emitEvent("pane_moved", movedFrame(f.peer));
  expect((await waitRecord(f.store, "retired-move", "retired")).result).toBeNull();
  expect(f.peer.requests.some((request) => request.method === "pane.get" && request.params.pane_id === "w1:p2")).toBe(false); expect(f.peer.effects).toBe(0);
});

test("actual stock zero-revision output changes are observed by bounded content, never by the pre-existing grid", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: "pane-output-changed", targetId: "w1:p1", workspaceId: null, status: null, agent: null, label: null };
  expect((await startObserved(f, store, "stock-zero-output", condition, 3000))?.status).toBe(202);
  const deadline = Date.now() + 1500;
  while (f.peer.requests.filter((request) => request.method === "pane.read" && request.params.intent === "passive").length < 2 && Date.now() < deadline) await Bun.sleep(1);
  const reads = f.peer.requests.filter((request) => request.method === "pane.read" && request.params.intent === "passive");
  expect(reads.length).toBeGreaterThanOrEqual(2); expect(reads[0]?.params).toEqual({ pane_id: "w1:p1", source: "visible", lines: 200, format: "text", strip_ansi: true, intent: "passive" });
  expect((await store.inspect("stock-zero-output", "a".repeat(64), "local", "fixture"))?.state).toBe("pending");
  f.peer.outputText = "> ready\nactual new bounded output";
  expect((await waitRecord(store, "stock-zero-output", "matched")).result).toEqual({ kind: "event", event: "pane-output-changed", targetId: "w1:p1", status: null });
  expect(f.peer.outputRevision).toBe(0); expect(f.peer.effects).toBe(0); expect(f.peer.requests.some((request) => request.method === "events.subscribe" || request.method === "events.wait")).toBe(false);
});

// These are the general EventEnvelope shapes emitted by the pinned producer, not
// synthetic events.wait replies. Each passes through the owning IPC client and handler.
const originalEventCases: readonly { event: AgentEventKind; wire: string; target: string | null; filter: string | null; data: JsonObject; result: string }[] = [
  { event: "workspace-created", wire: "workspace_created", target: "w1", filter: null, data: { workspace: { workspace_id: "w1" } }, result: "w1" },
  { event: "workspace-updated", wire: "workspace_updated", target: "w1", filter: null, data: { workspace: { workspace_id: "w1" } }, result: "w1" },
  { event: "workspace-renamed", wire: "workspace_renamed", target: "w1", filter: "owned", data: { workspace_id: "w1", label: "owned" }, result: "w1" },
  { event: "workspace-moved", wire: "workspace_moved", target: "w1", filter: null, data: { workspace_id: "w1", insert_index: 1, workspaces: [] }, result: "w1" },
  { event: "workspace-focused", wire: "workspace_focused", target: "w1", filter: null, data: { workspace_id: "w1" }, result: "w1" },
  { event: "tab-created", wire: "tab_created", target: "w1:t1", filter: null, data: { tab: { tab_id: "w1:t1", workspace_id: "w1" } }, result: "w1:t1" },
  { event: "tab-renamed", wire: "tab_renamed", target: "w1:t1", filter: "owned", data: { tab_id: "w1:t1", workspace_id: "w1", label: "owned" }, result: "w1:t1" },
  { event: "tab-moved", wire: "tab_moved", target: "w1:t1", filter: null, data: { tab_id: "w1:t1", workspace_id: "w1", insert_index: 1, tabs: [] }, result: "w1:t1" },
  { event: "tab-focused", wire: "tab_focused", target: "w1:t1", filter: null, data: { tab_id: "w1:t1", workspace_id: "w1" }, result: "w1:t1" },
  { event: "pane-created", wire: "pane_created", target: null, filter: null, data: { pane: { pane_id: "w1:p2", terminal_id: "created-terminal", workspace_id: "w1", tab_id: "w1:t1" } }, result: "w1:p2" },
  { event: "pane-focused", wire: "pane_focused", target: "w1:p1", filter: null, data: { pane_id: "w1:p1", workspace_id: "w1" }, result: "w1:p1" },
];
for (const capture of originalEventCases) test(`official ${capture.wire} reaches the original owning wait without raw vendor payload`, async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const condition: JsonObject = { kind: "event", event: capture.event, targetId: capture.target, workspaceId: capture.event === "pane-created" ? "w1" : null, status: null, agent: null, label: capture.filter };
  expect((await startObserved(f, store, capture.wire, condition))?.status).toBe(202); await subscriptionReady(f.peer);
  expect(f.peer.requests.find((request) => request.method === "events.subscribe")?.params).toEqual({ subscriptions: [{ type: capture.wire.replace("_", ".") }] });
  f.peer.emitEvent(capture.wire, { type: capture.wire, ...capture.data });
  const view = await waitRecord(store, capture.wire, "matched");
  expect(view.result).toEqual({ kind: "event", event: capture.event, targetId: capture.result, status: null });
  expect(f.peer.effects).toBe(0); expect(f.peer.requests.some((request) => request.method === "events.wait")).toBe(false);
  expect(maskForwardedAnswer("waits", JSON.stringify({ ok: true, wait: view }))).toBe(JSON.stringify({ ok: true, wait: view }));
});

for (const event of ["pane-closed", "pane-exited", "tab-closed", "workspace-closed"]) test(`stock ${event} without terminal provenance cannot falsely complete an original wait`, async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  const target = event.startsWith("workspace-") ? "w1" : event.startsWith("tab-") ? "w1:t1" : "w1:p1";
  const condition: JsonObject = { kind: "event", event, targetId: target, workspaceId: null, status: null, agent: null, label: null };
  expect((await startObserved(f, store, event, condition))?.status).toBe(202); await subscriptionReady(f.peer);
  const wire = event.replace("-", "_"); const key = event.startsWith("workspace-") ? "workspace_id" : event.startsWith("tab-") ? "tab_id" : "pane_id";
  f.peer.emitEvent(wire, { type: wire, [key]: target, workspace_id: "w1" });
  const view = await waitRecord(store, event, "interrupted"); expect(view.code).toBe("agent.event_original_terminal_unproved"); expect(view.result).toBeNull(); expect(f.peer.effects).toBe(0);
});

test("owning wait admission caps eight concurrent observers and cancellation frees only its original slot", async () => {
  const f = await fixture(); const store = new AgentWaitStore(f.directory); waits.push(store);
  let release!: () => void; f.peer.held = new Promise<void>((resolve) => { release = resolve; });
  const admitted = await Promise.all(Array.from({ length: 8 }, (_, i) => startObserved(f, store, `bounded-${i}`, { kind: "lifecycle", until: ["idle"] }, 4000)));
  expect(admitted.every((response) => response?.status === 202)).toBe(true);
  const end = Date.now() + 1000; while (f.peer.requests.filter((request) => request.method === "agent.wait").length < 8 && Date.now() < end) await Bun.sleep(1);
  expect(f.peer.requests.filter((request) => request.method === "agent.wait")).toHaveLength(8);
  expect((await startObserved(f, store, "ninth", { kind: "lifecycle", until: ["idle"] }, 4000))?.status).toBe(409);
  expect(f.peer.requests.filter((request) => request.method === "agent.wait")).toHaveLength(8);
  expect((await store.cancel("bounded-0", "a".repeat(64), "local", "fixture"))?.state).toBe("cancelled");
  expect((await startObserved(f, store, "fresh-slot", { kind: "lifecycle", until: ["idle"] }, 4000))?.status).toBe(202);
  expect((await store.inspect("bounded-1", "a".repeat(64), "local", "fixture"))?.state).toBe("pending");
  release(); expect(f.peer.effects).toBe(0);
});

test("pre-existing working activity cannot prove that a newly submitted prompt settled", async () => {
  const f = await fixture(); f.peer.status = "working"; const initial = await f.get();
  const result = await f.post("prompt", { text: "once while prior work active", until: ["working"], timeoutMs: 1000 }, "prior-work", witness(initial.body), "> ready");
  expect(row(result.body, "operation").state).toBe("unknown"); expect(result.body.result).toBeNull(); expect(f.peer.effects).toBe(1); expect(row(result.body, "progress").settled).toBe(false);
});
test("the actual adapter retains a frozen named intent through a held production RPC", async () => {
  const f = await fixture(); const read = await f.mux.agentControl("w1:p1"); if (!read.ok) throw new Error("actual occupant unavailable");
  let release!: () => void; f.peer.held = new Promise<void>((resolve) => { release = resolve; });
  const supplied = { kind: "rename" as const, name: "admitted" }; const pending = f.mux.agentAction(supplied, read.value, (runtime) => runtime === read.value.runtimeIdentity);
  const end = Date.now() + 1000; while (!f.peer.requests.some((request) => request.method === "agent.rename") && Date.now() < end) await Bun.sleep(1);
  supplied.name = "changed-after-await"; release(); const result = await pending; expect(result.ok).toBe(true); expect(f.peer.name).toBe("admitted"); expect(f.peer.effects).toBe(1);
});

test("original producer incarnation is private and same public terminal reactivation cannot borrow its action witness", async () => {
  const f = await fixture(); f.peer.originalCustody = true; const original = await f.get(); const token = witness(original.body);
  const oldIncarnation = f.peer.runtimeIncarnation; f.peer.runtimeIncarnation = "5e0d22ba-28d3-4749-a91f-80759c76395a";
  expect(f.peer.runtimeIncarnation).not.toBe(oldIncarnation);
  const rejected = await f.post("rename", { name: "must-not-write" }, "old-runtime-incarnation", token);
  expect(rejected.response.status).toBe(409); expect(f.peer.effects).toBe(0);
  expect(f.peer.requests.filter((request) => request.method === "agent.rename")).toHaveLength(0);
  expect(JSON.stringify(original.body)).not.toContain(oldIncarnation); expect(JSON.stringify(original.body)).not.toContain("original_binding");
  const fresh = await f.get(); expect(witness(fresh.body)).not.toBe(token);
  expect(row((await f.post("rename", { name: "current-positive" }, "current-runtime-incarnation", witness(fresh.body))).body, "operation").state).toBe("succeeded");
  expect(f.peer.effects).toBe(1);
});

const guardedActions: readonly { verb: string; action: JsonObject; expectedText?: string; shell?: boolean }[] = [
  { verb: "start", action: { name: "guarded", agent: "codex", args: [], timeoutMs: 4000 }, shell: true },
  { verb: "rename", action: { name: "guarded" } },
  { verb: "prompt", action: { text: "bounded guarded", until: null, timeoutMs: 1000 }, expectedText: "> ready" },
  { verb: "focus", action: {} }, { verb: "keys", action: { keys: ["Enter"] }, expectedText: "> ready" },
  { verb: "input", action: { text: "bounded input", keys: ["Enter"] }, expectedText: "> ready" },
];
for (const sample of guardedActions) test(`negotiated original ${sample.verb} refuses a changed foreground at owning producer dispatch and a fresh original succeeds once`, async () => {
  const f = await fixture(); f.peer.originalInputs = true; f.peer.originalCustody = true; if (sample.shell) { f.peer.agent = null; f.peer.name = null; }
  expect(await f.mux.reachable()).toBe(true); const original = await f.get();
  f.peer.beforeEffect = () => { f.peer.process++; f.peer.beforeEffect = null; };
  const refused = await f.post(sample.verb, sample.action, `guard-${sample.verb}-old`, witness(original.body), sample.expectedText ?? null);
  expect(row(refused.body, "operation").state).toBe("refused"); expect(f.peer.effects).toBe(0);
  const dispatched = f.peer.requests.find((request) => request.method === ({ start: "agent.start", rename: "agent.rename", prompt: "agent.prompt", focus: "agent.focus", keys: "agent.send_keys", input: "pane.send_input" }[sample.verb]));
  expect(dispatched?.params.original_binding).toMatch(/^[a-f0-9]{64}$/);
  const fresh = await f.get(); const answer = await f.post(sample.verb, sample.action, `guard-${sample.verb}-new`, witness(fresh.body), sample.expectedText ?? null);
  expect(row(answer.body, "operation").state).toBe("succeeded"); expect(f.peer.effects).toBe(1);
  expect(JSON.stringify(answer.body)).not.toContain("original_binding"); expect(JSON.stringify(answer.body)).not.toContain(f.peer.runtimeIncarnation);
});
for (const verb of ["prompt", "keys", "input"] as const) test(`owning ${verb} accepted-prefix retirement is durable UNKNOWN and reconciliation never repeats bytes`, async () => {
  const sample = guardedActions.find((item) => item.verb === verb)!; const f = await fixture(); f.peer.originalInputs = true; f.peer.originalCustody = true; expect(await f.mux.reachable()).toBe(true);
  const original = await f.get(); f.peer.afterAcceptedRejection = "original_input_retired_after_input";
  const first = await f.post(verb, sample.action, `prefix-${verb}`, witness(original.body), sample.expectedText ?? null);
  expect(row(first.body, "operation").state).toBe("unknown"); expect(first.body.result).toBeNull(); expect(f.peer.effects).toBe(1);
  f.peer.afterAcceptedRejection = null;
  const recovered = await f.post(verb, sample.action, `prefix-${verb}`, witness(original.body), sample.expectedText ?? null);
  expect(row(recovered.body, "operation").state).toBe("unknown"); expect(f.peer.effects).toBe(1);
  expect((await new OperationStore(f.directory).inspect(`prefix-${verb}`, "a".repeat(64), "local", "fixture"))?.state).toBe("unknown");
});

async function transitionFixture(event: string, id: string) {
  const f = await fixture(); f.peer.originalObservations = true; f.peer.originalCustody = true; expect(await f.mux.reachable()).toBe(true);
  const store = new AgentWaitStore(f.directory); waits.push(store); const original = await f.get();
  const target = event.startsWith("workspace-") ? "w1" : event.startsWith("tab-") ? "w1:t1" : "w1:p1";
  const condition: JsonObject = { kind: "event", event, targetId: target, workspaceId: null, status: null, agent: null, label: null };
  expect((await startObserved(f, store, id, condition, 3000))?.status).toBe(202); await subscriptionReady(f.peer);
  return { ...f, store, originalWitness: witness(original.body), condition, target };
}
function transitionFrame(event: string, target: string, agent: string | null = null): JsonObject {
  const wire = event === "agent-detected" ? "pane_agent_detected" : event.replace("-", "_");
  const key = event.startsWith("workspace-") ? "workspace_id" : event.startsWith("tab-") ? "tab_id" : "pane_id";
  const result: JsonObject = { type: wire, [key]: target, workspace_id: "w1" }; if (event === "agent-detected") result.agent = agent; return result;
}
const transitionKinds = ["pane-closed", "pane-exited", "tab-closed", "workspace-closed", "agent-detected"] as const;
for (const event of transitionKinds) test(`registered original ${event} completes through actual IPC as neutral metadata without granting successor actions`, async () => {
  const id = `owned-${event}`; const f = await transitionFixture(event, id);
  if (event === "agent-detected") { f.peer.agent = "claude"; f.peer.sessionValue = "detected-session"; f.peer.process++; f.peer.inputEpoch++; }
  else if (event !== "pane-exited") f.peer.panePresent = false;
  const wire = event === "agent-detected" ? "pane_agent_detected" : event.replace("-", "_");
  f.peer.emitEvent(wire, transitionFrame(event, f.target, f.peer.agent));
  const view = await waitRecord(f.store, id, "matched"); if (view.result?.kind !== "event" || !view.result.transition) throw new Error("registered transition missing");
  expect(view.result.transition.event).toBe(event); expect(view.result.transition.terminalId).toBe("terminal-A"); expect(view.result.transition.witness).toBe(f.originalWitness);
  expect(view.result.transition.after?.agent ?? null).toBe(event === "agent-detected" ? "claude" : null);
  expect(maskForwardedAnswer("waits", JSON.stringify({ ok: true, wait: view }))).toBe(JSON.stringify({ ok: true, wait: view }));
  expect(JSON.stringify(view)).not.toContain("original_binding"); expect(JSON.stringify(view)).not.toContain(f.peer.runtimeIncarnation); expect(f.peer.effects).toBe(0);
  f.peer.paneId = "w1:p2"; f.peer.originalSlotReplacement = "terminal-B";
  expect((await f.store.inspect(id, "a".repeat(64), "local", "fixture"))?.result).toBeNull();
});
for (const failure of ["replacement", "runtime", "binding", "counter", "old-frame", "stream", "retired-owner"] as const) test(`original transition rejects ${failure} without settling an old owner from B`, async () => {
  const id = `invalid-transition-${failure}`; const f = await transitionFixture("pane-exited", id);
  const reg = [...f.peer.registrations.values()][0]!; if (!reg.before) throw new Error("original registration required");
  if (failure === "replacement") { f.peer.paneId = "w1:p2"; f.peer.originalSlotReplacement = "terminal-B"; }
  if (failure === "runtime") f.peer.runtimeIncarnation = "5e0d22ba-28d3-4749-a91f-80759c76395a";
  if (failure === "retired-owner") f.retire();
  if (failure === "stream") f.peer.loseEventStreams();
  else {
    const proof: JsonObject = { ...reg.before }; if (failure === "binding") proof.binding = "b".repeat(64); if (failure === "counter") proof.epoch = 1;
    f.peer.emitRawFrame(`${JSON.stringify({ event: "pane_exited", data: { ...transitionFrame("pane-exited", f.target), original_transition: proof }, original_observation: { registration_id: reg.id, sequence: String(failure === "old-frame" ? reg.start : reg.start + 1) } })}\n`);
  }
  const deadline = Date.now() + 2500; let view;
  do { view = await f.store.inspect(id, "a".repeat(64), "local", "fixture"); if (view?.state !== "pending") break; await Bun.sleep(1); } while (Date.now() < deadline);
  expect(view?.state).not.toBe("matched"); expect(view?.state).not.toBe("pending"); expect(view?.result).toBeNull(); expect(f.peer.effects).toBe(0);
});
