import { expect, test } from "bun:test";
import { mkdtemp, rm, mkdir, writeFile, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { OperationStore } from "../control/operations.ts";
import { PlanStore } from "../control/plans.ts";
import { serveHostPluginsRoute } from "./plugins.ts";
import type { JsonObject } from "../json.ts";
import { HOST_CAPABILITIES, type HostControlPort, type HostPluginActionDescriptor, type HostPluginSummary } from "./types.ts";

function fixture() {
  const calls: unknown[][] = [];
  const plugin: HostPluginSummary = {
    id: "example.safe", name: "Safe example", version: "1.2.3", minimumHerdrVersion: "0.9.3", description: "A test plugin",
    enabled: true, platforms: ["linux"], source: { kind: "github", owner: "author", repo: "repo", subdir: null, requestedRef: "v1", resolvedCommit: "abc123" },
    actions: [], panes: [], warnings: [], userDirsRevision: "a".repeat(64),
  };
  const action: HostPluginActionDescriptor = { id: "example.safe.run", qualifiedId: "example.safe.run", pluginId: "example.safe", actionId: "run",
    title: "Run", description: null, contexts: ["global", "pane"], platforms: ["linux"], enabled: true, platformSupported: true };
  const port: HostControlPort = {
    provider: "herdr",
    // SAFETY: This fixture supplies exactly the complete host capability vocabulary and marks only plugin listing as supported.
    capabilities: Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, name === "plugins"])) as Record<(typeof HOST_CAPABILITIES)[number], boolean>,
    notes: {},
    async listIntegrations() { return { kind: "unsupported", capability: "integrations", reason: "unused" }; },
    async lettaIntegrationStatus() { return { kind: "unsupported", capability: "experimentalLettaIntegration", reason: "unused" }; },
    async installIntegration() { return { kind: "unsupported", capability: "integrationInstall", reason: "unused" }; },
    async uninstallIntegration() { return { kind: "unsupported", capability: "integrationUninstall", reason: "unused" }; },
    async installExperimentalLetta() { return { kind: "unsupported", capability: "experimentalLettaIntegration", reason: "unused" }; },
    async uninstallExperimentalLetta() { return { kind: "unsupported", capability: "experimentalLettaIntegration", reason: "unused" }; },
    async agentManifests() { return { kind: "unsupported", capability: "agentManifests", reason: "unused" }; },
    async reloadAgentManifests() { return { kind: "unsupported", capability: "manifestReload", reason: "unused" }; },
    async refreshAgentManifests() { return { kind: "unsupported", capability: "manifestRefresh", reason: "unused" }; },
    async inspect() { return { kind: "unsupported", capability: "plugins", reason: "unused" }; },
    async listPlugins() { return { kind: "available", value: [plugin] }; },
    async listPluginActions() { return { kind: "available", value: [action] }; },
    async listPluginLogs() { return { kind: "available", value: [{ id: "log1", pluginId: plugin.id, actionId: "run", event: null, status: "failed" as const,
      startedAt: "1", finishedAt: "2", exitCode: 1, stdout: "token=[REDACTED]", stderr: null, error: null }] }; },
    async invokePluginAction(...args) { calls.push(args); return { kind: "available", value: { pluginId: plugin.id, actionId: "run", logId: "log1", status: "running" as const } }; },
  };
  const caller = { gate: () => null, pairedIdentity: () => "b".repeat(64), resolve: async () => ({ host: "host-a", port }) };
  return { caller, calls, port };
}

test("plugin list omits owner-private preparation revision and paths", async () => {
  const { caller } = fixture();
  const response = await serveHostPluginsRoute(new Request("http://collie/api/host/plugins"), new URL("http://collie/api/host/plugins"), caller);
  expect(response?.status).toBe(200);
  const body = await response!.text();
  expect(body).toContain("example.safe");
  expect(body).not.toContain("userDirsRevision");
  expect(body).not.toContain("a".repeat(64));
  expect(body).not.toContain("pluginRoot");
});

test("plugin invocation rejects caller-injected authority context and uses the server-owned action identity", async () => {
  const { caller, calls } = fixture();
  const path = "http://collie/api/host/plugins/example.safe/actions/run/invoke";
  const response = await serveHostPluginsRoute(new Request(path, { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ correlationId: "request-1", context: { focused_pane_agent: "forged", focused_pane_status: "working" } }) }), new URL(path), caller);
  expect(response?.status).toBe(400);
  expect(calls).toHaveLength(0);
  const accepted = await serveHostPluginsRoute(new Request(path, { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ correlationId: "request-2" }) }), new URL(path), caller);
  expect(accepted?.status).toBe(200);
  expect(calls).toEqual([["example.safe", "run", "native", "request-2"]]);
});

test("plugin API enforces host scope, bounded log limits, and fixed route vocabulary", async () => {
  const { caller } = fixture();
  const badScope = new URL("http://collie/api/host/plugins?session=other");
  expect((await serveHostPluginsRoute(new Request(badScope), badScope, caller))?.status).toBe(400);
  const badLimit = new URL("http://collie/api/host/plugins/example.safe/logs?limit=1000");
  expect((await serveHostPluginsRoute(new Request(badLimit), badLimit, caller))?.status).toBe(400);
  const unknown = new URL("http://collie/api/host/plugins/example.safe/arbitrary");
  expect(await serveHostPluginsRoute(new Request(unknown), unknown, caller)).toBeNull();
});

test("plugin install plans disclose a pinned artifact and retain an unknown outcome without retrying", async () => {
  const { caller, port } = fixture();
  Object.assign(port.capabilities, { pluginOwnerOperations: true });
  const requests: string[] = [];
  Object.assign(port, {
    async pluginOwnerContext() { return { kind: "available" as const, value: { revision: "b".repeat(64), linkSelections: [] } }; },
    async planPluginInstall(input: { owner: string; repo: string; subdir: string | null; ref: string | null }) {
      return { kind: "available" as const, value: { id: "11111111-1111-4111-8111-111111111111", ownerRevision: "b".repeat(64), plugin: {
        id: "example.safe", name: "Safe example", version: "1.2.3", minimumHerdrVersion: "0.9.3", description: null, enabled: true, platforms: ["linux"],
        source: { kind: "github" as const, owner: input.owner, repo: input.repo, subdir: input.subdir, requestedRef: input.ref, resolvedCommit: "c".repeat(40) }, actions: [], panes: [], warnings: [],
      }, source: { ...input, commit: "c".repeat(40) }, checksum: "d".repeat(64), buildCommands: [["npm", "run", "build"]], startupCommands: [["node", "server.js"]] } };
    },
    async applyPluginInstall(planId: string, checksum: string, ownerRevision: string, requestId: string) {
      expect(planId).toBe("11111111-1111-4111-8111-111111111111");
      expect(checksum).toBe("d".repeat(64));
      expect(ownerRevision).toBe("b".repeat(64));
      requests.push(requestId);
      return { kind: "available" as const, value: { requestId, state: "unknown" as const, plugin: null, removed: false, code: "plugin_owner_outcome_unknown" } };
    },
    async getPluginOwnerOperation(requestId: string) {
      return { kind: "available" as const, value: { requestId, state: "unknown" as const, plugin: null, removed: false, code: "plugin_owner_outcome_unknown" } };
    },
  });
  const root = await mkdtemp(join(tmpdir(), "collie-plugin-owner-"));
  const operations = new OperationStore(root);
  await operations.prepare();
  const plans = new PlanStore();
  try {
    const planUrl = new URL("http://collie/api/host/plugins/install/plan");
    const planned = await serveHostPluginsRoute(new Request(planUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ owner: "author", repo: "repo" }) }), planUrl, caller, plans, operations);
    expect(planned?.status).toBe(200);
    // SAFETY: The response status is asserted above and the endpoint's contract returns the plan envelope.
    const planResponse = await planned!.json() as { plan: { token: string; checksum: string; source: { commit: string }; effects: string[] } };
    expect(planResponse.plan.source.commit).toBe("c".repeat(40));
    expect(planResponse.plan.checksum).toBe("d".repeat(64));
    expect(planResponse.plan.effects.join(" ")).toContain("will be registered for Herdr daemon startup, not run during install");
    expect(planResponse.plan.effects.join(" ")).not.toContain("npm run build");
    expect(JSON.stringify(planResponse)).not.toContain("server.js");
    const requestId = "_" + "a".repeat(127);
    const request = { requestId, planToken: planResponse.plan.token, checksum: planResponse.plan.checksum, confirmed: true };
    const applyUrl = new URL("http://collie/api/host/plugins/install");
    const apply = () => serveHostPluginsRoute(new Request(applyUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(request) }), applyUrl, caller, plans, operations);
    const firstApply = await apply();
    expect(firstApply?.status).toBe(200);
    // SAFETY: A successful apply route returns the operation envelope asserted by status above.
    expect(((await firstApply!.json()) as { operation: { state: string } }).operation.state).toBe("unknown");
    const replay = await apply();
    expect(replay?.status).toBe(200);
    // SAFETY: The idempotent replay response preserves the operation envelope.
    expect(((await replay!.json()) as { operation?: { state: string } }).operation?.state).toBe("unknown");
    expect(requests).toEqual([requestId]);
    const receiptUrl = new URL(`http://collie/api/host/plugins/operations/${requestId}`);
    const receipt = await serveHostPluginsRoute(new Request(receiptUrl), receiptUrl, caller, plans, operations);
    expect(receipt?.status).toBe(200);
    // SAFETY: The receipt endpoint returns its named ownerOperation envelope after the successful status check.
    expect((await receipt!.json() as { ownerOperation: { state: string } }).ownerOperation.state).toBe("unknown");
  } finally { await rm(root, { recursive: true, force: true }); }
});


test("redacted config edit replays its restored-content intent without a second write", async () => {
  const { caller, port } = fixture();
  const root = await mkdtemp(join(tmpdir(), "collie-plugin-config-replay-"));
  const configDir = join(root, "config");
  await mkdir(configDir);
  await writeFile(join(configDir, "settings.json"), JSON.stringify({ api_key: "fixture-secret", label: "before" }));
  port.preparePluginUserDirs = async () => ({ kind: "available", value: {
    pluginId: "example.safe", revision: "a".repeat(64), configDir, stateDir: root,
    createdConfigDir: false, createdStateDir: false, seededLegacyConfig: false,
  } });
  const operations = new OperationStore(join(root, "receipts"));
  await operations.prepare();
  const plans = new PlanStore();
  const route = (tail: string, body: JsonObject) => {
    const url = new URL(`http://collie/api/host/plugins/example.safe/config/${tail}`);
    return serveHostPluginsRoute(new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }), url, caller, plans, operations);
  };
  try {
    const preparation = await route("prepare/plan", {});
    expect(preparation?.status).toBe(200);
    // SAFETY: The asserted preparation endpoint returns its bounded plan token envelope.
    const preparationPlan = await preparation!.json() as { plan: { token: string } };
    expect((await route("prepare", { requestId: "prepare-config", planToken: preparationPlan.plan.token, confirmed: true }))?.status).toBe(200);
    const content = JSON.stringify({ api_key: "__COLLIE_REDACTED_SECRET__", label: "after" });
    const planned = await route("plan", { requestId: "edit-config", file: "settings.json", content });
    expect(planned?.status).toBe(200);
    // SAFETY: The asserted edit-plan endpoint returns its bounded plan token envelope.
    const plan = await planned!.json() as { plan: { token: string } };
    const request = { requestId: "edit-config", planToken: plan.plan.token, file: "settings.json", content, confirmed: true };
    expect((await route("apply", request))?.status).toBe(200);
    const first = await readFile(join(configDir, "settings.json"), "utf8");
    expect(first).toContain("fixture-secret");
    expect(first).toContain("after");
    const duplicate = await route("apply", request);
    expect(duplicate?.status).toBe(200);
    // SAFETY: The successful replay returns the same durable operation envelope.
    expect((await duplicate!.json() as { operation: { state: string } }).operation.state).toBe("succeeded");
    expect(await readFile(join(configDir, "settings.json"), "utf8")).toBe(first);
  } finally { await rm(root, { recursive: true, force: true }); }
});
