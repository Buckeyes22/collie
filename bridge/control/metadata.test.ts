import { describe, expect, test } from "bun:test";
import { MetadataStateStore, validateAgentView, validateAgentViewFilter, validatePaneMetadata, validateWorkspaceMetadata, NATIVE_METADATA_SOURCE, METADATA_MAX_SEQUENCE_SOURCES, type AgentViewFilter, type PaneMetadataRequest } from "./metadata.ts";

describe("bounded metadata contracts", () => {
  test("accepts declared metadata and preserves nullable token values", () => {
    expect(validatePaneMetadata({ source: NATIVE_METADATA_SOURCE, seq: 1, ttl_ms: 60_000, tokens: { ticket: "B31", cleared: null } }).ok).toBe(true);
    expect(validateWorkspaceMetadata({ source: "plugin.review", tokens: { branch: "main" } }).ok).toBe(true);
  });
  test("reserves native namespace and rejects invalid source, token keys, limits and TTL", () => {
    expect(validatePaneMetadata({ source: "collie.other", tokens: {} })).toEqual({ ok: false, code: "invalid_metadata_source" });
    expect(validatePaneMetadata({ source: "plugin.review", tokens: { "bad key": "x" } })).toEqual({ ok: false, code: "invalid_metadata_token" });
    expect(validatePaneMetadata({ source: "plugin.review", ttl_ms: 86_400_001 })).toEqual({ ok: false, code: "invalid_metadata_ttl" });
    expect(validateWorkspaceMetadata({ source: "plugin.review", tokens: Object.fromEntries(Array.from({ length: 17 }, (_, i) => [`k${i}`, "v"])) })).toEqual({ ok: false, code: "invalid_metadata_token" });
  });
  test("rejects lifecycle authority spoofing and invalid display labels", () => {
    expect(validatePaneMetadata({ source: "plugin.review", state_labels: { done: "done", authority: "trusted" } })).toEqual({ ok: false, code: "invalid_state_label" });
    // SAFETY: the fixture intentionally violates the declared field type to exercise runtime validation.
    const invalidMetadata = JSON.parse('{"source":"plugin.review","clear_title":"yes"}') as PaneMetadataRequest;
    expect(validatePaneMetadata(invalidMetadata)).toEqual({ ok: false, code: "invalid_metadata_request" });
  });
  test("accepts closed declarative filters and rejects executable or overdeep definitions", () => {
    expect(validateAgentView({ source: "plugin.review", filter: { op: "all", filters: [{ op: "eq", field: "status", value: "working" }, { op: "exists", field: { token: "ticket" } }] }, sort: [{ field: "attention", order: "desc" }] }).ok).toBe(true);
    expect(validateAgentView({ source: "plugin.review", filter: { op: "eq", field: "agent_status", value: "working" } })).toEqual({ ok: false, code: "invalid_agent_view" });
    expect(validateAgentViewFilter({ op: "eq", field: "agent", value: { command: "rm -rf /" } })).toEqual({ ok: false, code: "invalid_agent_view" });
    const filter = Array.from({ length: 10 }).reduce<AgentViewFilter>((child) => ({ op: "not", filter: child }), { op: "exists", field: "agent" });
    expect(validateAgentViewFilter(filter)).toEqual({ ok: false, code: "invalid_agent_view" });
    expect(validateAgentViewFilter({ op: "eq", field: "status", value: { context: "current_workspace_id" } })).toEqual({ ok: false, code: "invalid_agent_view" });
    expect(validateAgentViewFilter({ op: "in", field: "tab_id", values: [{ context: "current_workspace_id" }] })).toEqual({ ok: false, code: "invalid_agent_view" });
    expect(validateAgentViewFilter({ op: "eq", field: "workspace_id", value: { context: "current_workspace_id" } }).ok).toBe(true);
  });
  test("replaces source state, rejects stale sequence, expires TTL and bounds sequence sources", () => {
    const store = new MetadataStateStore();
    expect(store.apply("pane-1", "plugin.review", 2, 10, { title: "current" }, 100)).toBe(true);
    expect(store.apply("pane-1", "plugin.review", 1, 10, { title: "stale" }, 101)).toBe(false);
    expect(store.read("pane-1", 102)).toEqual([{ source: "plugin.review", seq: 2, expiresAt: 110, tokenExpiresAt: {}, value: { title: "current", tokens: {} } }]);
    expect(store.read("pane-1", 110)).toEqual([]);
    for (let i = 0; i < METADATA_MAX_SEQUENCE_SOURCES - 1; i++) expect(store.apply("pane-1", `plugin.${i}`, i, null, {}, 200)).toBe(true);
    expect(store.apply("pane-1", "plugin.too-many", 1, null, {}, 200)).toBe(false);
    store.clear("pane-1", "plugin.0", 200);
    expect(store.apply("pane-1", "plugin.replacement", null, null, {}, 200)).toBe(true);
    expect(store.apply("pane-1", "plugin.0", 0, null, {}, 200)).toBe(false);
  });
  test("matches upstream presentation bounds and retained token-name capacity", () => {
    expect(validatePaneMetadata({ source: "plugin." + "x".repeat(72), title: "t".repeat(81) })).toEqual({ ok: false, code: "invalid_metadata_request" });
    const store = new MetadataStateStore();
    expect(store.apply("pane-2", "plugin.review", null, null, { tokens: Object.fromEntries(Array.from({ length: 16 }, (_, i) => [`key${i}`, "v"])) })).toBe(true);
    expect(store.apply("pane-2", "plugin.review", null, null, { tokens: Object.fromEntries(Array.from({ length: 16 }, (_, i) => [`key${i + 16}`, "v"])) })).toBe(true);
    const extra = { source: "plugin.review", tokens: { extra: "v" } };
    expect(store.canApply("pane-2", "plugin.review", null, extra)).toBe(false);
  });
  test("expires token keys independently while presentation keeps source-scoped TTL", () => {
    const store = new MetadataStateStore();
    expect(store.apply("workspace:w1", "plugin.review", null, 1_000, { title: "live", tokens: { a: "one" } }, 0)).toBe(true);
    expect(store.apply("workspace:w1", "plugin.review", null, 5_000, { tokens: { b: "two" } }, 500)).toBe(true);
    expect(store.read("workspace:w1", 1_000)).toMatchObject([{ expiresAt: null, value: { tokens: { b: "two" } } }]);
    expect(store.read("workspace:w1", 1_000)[0]?.tokenExpiresAt).toEqual({ b: 5_500 });
    expect(store.read("workspace:w1", 5_500)).toEqual([]);
  });
  test("token-only clear TTL does not expire other keys or presentation", () => {
    const store = new MetadataStateStore();
    store.apply("workspace:w1", "plugin.review", null, null, { title: "keep", tokens: { a: "one", b: "two" } }, 0);
    store.apply("workspace:w1", "plugin.review", null, 1, { tokens: { a: null } }, 5);
    expect(store.read("workspace:w1", 6)).toMatchObject([{ expiresAt: null, value: { title: "keep", tokens: { b: "two" } } }]);
  });
  test("resource close cleanup drops every occupant generation and keeps unrelated targets", () => {
    const store = new MetadataStateStore();
    store.apply("host\0session\0pane\0p1\0generation-a", "plugin.review", null, null, { title: "old" });
    store.apply("host\0session\0pane\0p1\0generation-b", "plugin.review", null, null, { title: "new" });
    store.apply("host\0session\0pane\0p2\0generation-a", "plugin.review", null, null, { title: "keep" });
    store.clearTarget("host\0session\0pane\0p1");
    expect(store.read("host\0session\0pane\0p1\0generation-a")).toEqual([]);
    expect(store.read("host\0session\0pane\0p1\0generation-b")).toEqual([]);
    expect(store.read("host\0session\0pane\0p2\0generation-a")).toHaveLength(1);
  });
});
