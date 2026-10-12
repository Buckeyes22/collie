import { expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { OperationStore } from "../control/operations.ts";
import { PlanStore } from "../control/plans.ts";
import type { HostControlOutcome, HostControlPort, IntegrationSummary, IntegrationVersionStatus } from "./types.ts";
import { serveHostIntegrationRoute } from "./integrations.ts";

const device = "a".repeat(64);
type IntegrationRouteBody = { readonly requestId: string; readonly planToken: string; readonly confirmed: true };

function fixture() {
  let host = "host-a";
  let paired: string | null = device;
  let integrations: IntegrationSummary[] = [{ target: "claude", label: "Claude", command: "/usr/bin/claude", available: true, state: "current" }];
  let lettaStatus: HostControlOutcome<IntegrationVersionStatus> = { kind: "available", value: { target: "letta", state: "not_installed", installedVersion: null, requiredVersion: null } };
  let dispatches = 0;
  let outcome: "available" | "unavailable" = "available";
  const port: HostControlPort = {
    provider: "herdr",
    capabilities: {
      integrations: true, integrationInstall: true, integrationUninstall: true,
      experimentalLettaIntegration: true, agentManifests: true, manifestReload: true,
      manifestRefresh: true, plugins: true, configuration: true, persistence: true,
      machines: true, serverAdministration: true, agentViews: false,
    },
    notes: {},
    async listIntegrations() { return { kind: "available", value: integrations }; },
    async lettaIntegrationStatus() { return lettaStatus; },
    async installIntegration() { dispatches++; return outcome === "available" ? { kind: "available", value: { messages: [] } } : { kind: "unavailable", reason: "outcome unknown" }; },
    async uninstallIntegration() { dispatches++; return outcome === "available" ? { kind: "available", value: { messages: [] } } : { kind: "unavailable", reason: "outcome unknown" }; },
    async installExperimentalLetta() { dispatches++; return { kind: "available", value: { messages: [] } }; },
    async uninstallExperimentalLetta() { dispatches++; return { kind: "available", value: { messages: [] } }; },
    async agentManifests() { return { kind: "available", value: { manifests: [] } }; },
    async reloadAgentManifests() { dispatches++; return { kind: "available", value: { manifests: [] } }; },
    async refreshAgentManifests() { dispatches++; return { kind: "available", value: { manifests: [] } }; },
    async inspect() { return { kind: "unsupported", capability: "plugins", reason: "unsupported" }; },
  };
  const plans = new PlanStore();
  let gate: Response | null = null;
  const caller = {
    gate: () => gate,
    pairedIdentity: () => paired,
    resolve: async () => ({ host, port }),
  };
  const route = async (method: "GET" | "POST", path: string, body?: IntegrationRouteBody) => {
    const init: RequestInit = { method };
    if (body !== undefined) {
      init.body = JSON.stringify(body);
      init.headers = { "content-type": "application/json" };
    }
    return serveHostIntegrationRoute(new Request(`http://collie${path}`, init), new URL(`http://collie${path}`), caller, plans, operations);
  };
  const operationsDirPromise = mkdtemp(join(tmpdir(), "collie-host-control-"));
  let operations: OperationStore;
  return {
    async ready() { operations = new OperationStore(await operationsDirPromise); await operations.prepare(); },
    route,
    port,
    plans,
    get operations() { return operations; },
    get dispatches() { return dispatches; },
    set host(value: string) { host = value; },
    set integrations(value: IntegrationSummary[]) { integrations = value; },
    set lettaStatus(value: HostControlOutcome<IntegrationVersionStatus>) { lettaStatus = value; },
    set outcome(value: "available" | "unavailable") { outcome = value; },
    set gate(value: Response | null) { gate = value; },
    set pairedIdentity(value: string | null) { paired = value; },
    async cleanup() { await rm(await operationsDirPromise, { recursive: true, force: true }); },
  };
}

test("integration plan is host/device/evidence bound and dispatches at most once", async () => {
  const f = fixture();
  await f.ready();
  try {
    const planned = await f.route("POST", "/api/host/integrations/claude/uninstall/plan");
    expect(planned?.status).toBe(200);
    const plan = (await planned!.json()).plan;
    const request: IntegrationRouteBody = { requestId: "single-attempt", planToken: plan.token, confirmed: true };
    const applied = await f.route("POST", "/api/host/integrations/claude/uninstall", request);
    expect((await applied!.json()).operation.state).toBe("succeeded");
    const replay = await f.route("POST", "/api/host/integrations/claude/uninstall", request);
    expect((await replay!.json()).operation.state).toBe("succeeded");
    expect(f.dispatches).toBe(1);
  } finally { await f.cleanup(); }
});

test("plan endpoints reject request bodies before issuing a plan", async () => {
  const f = fixture();
  await f.ready();
  try {
    const response = await serveHostIntegrationRoute(
      new Request("http://collie/api/host/integrations/claude/install/plan", {
        method: "POST", headers: { "content-type": "application/json" }, body: "{}",
      }),
      new URL("http://collie/api/host/integrations/claude/install/plan"),
      { gate: () => null, pairedIdentity: () => device, resolve: async () => ({ host: "host-a", port: f.port }) },
      f.plans,
      // Plan issuance must not depend on operation-store state.
      f.operations,
    );
    expect(response?.status).toBe(400);
    expect(await response!.json()).toMatchObject({ code: "operation.malformed" });
  } finally { await f.cleanup(); }
});

test("changed target evidence refuses before dispatch", async () => {
  const f = fixture();
  await f.ready();
  try {
    const planned = await f.route("POST", "/api/host/integrations/claude/install/plan");
    const token = (await planned!.json()).plan.token;
    f.integrations = [{ target: "claude", label: "Claude", command: "/usr/bin/claude", available: true, state: "outdated" }];
    const changed = await f.route("POST", "/api/host/integrations/claude/install", { requestId: "changed", planToken: token, confirmed: true });
    expect((await changed!.json()).operation).toMatchObject({ state: "refused", code: "plan.changed" });
    expect(f.dispatches).toBe(0);
  } finally { await f.cleanup(); }
});

test("a plan cannot be replayed against a different host", async () => {
  const f = fixture();
  await f.ready();
  try {
    const planned = await f.route("POST", "/api/host/integrations/claude/install/plan");
    const token = (await planned!.json()).plan.token;
    f.host = "host-b";
    const changed = await f.route("POST", "/api/host/integrations/claude/install", {
      requestId: "wrong-host", planToken: token, confirmed: true,
    });
    expect(await changed!.json()).toMatchObject({ code: "plan.changed" });
    expect(f.dispatches).toBe(0);
  } finally { await f.cleanup(); }
});

test("Letta install and uninstall plans bind current CLI status and refuse a changed state", async () => {
  const f = fixture();
  await f.ready();
  try {
    for (const scenario of [
      { action: "install", before: "not_installed", after: "current" },
      { action: "uninstall", before: "current", after: "not_installed" },
    ] as const) {
      f.lettaStatus = { kind: "available", value: { target: "letta", state: scenario.before, installedVersion: scenario.before === "current" ? "1" : null, requiredVersion: scenario.before === "current" ? "1" : null } };
      const planned = await f.route("POST", `/api/host/integrations/letta/${scenario.action}/plan`);
      expect(planned?.status).toBe(200);
      const token = (await planned!.json()).plan.token;
      f.lettaStatus = { kind: "available", value: { target: "letta", state: scenario.after, installedVersion: scenario.after === "current" ? "1" : null, requiredVersion: scenario.after === "current" ? "1" : null } };
      const applied = await f.route("POST", `/api/host/integrations/letta/${scenario.action}`, {
        requestId: `letta-${scenario.action}`, planToken: token, confirmed: true,
      });
      expect((await applied!.json()).operation).toMatchObject({ state: "refused", code: "plan.changed" });
    }
    expect(f.dispatches).toBe(0);
  } finally { await f.cleanup(); }
});

test("Letta plans refuse when bounded CLI status is unavailable", async () => {
  const f = fixture();
  await f.ready();
  try {
    f.lettaStatus = { kind: "unavailable", reason: "status unavailable" };
    const planned = await f.route("POST", "/api/host/integrations/letta/install/plan");
    expect(planned?.status).toBe(503);
    expect(await planned!.json()).toMatchObject({ code: "host.target_unavailable" });
    expect(f.dispatches).toBe(0);
  } finally { await f.cleanup(); }
});

test("uncertain outcomes are durable and never automatically dispatched twice", async () => {
  const f = fixture();
  await f.ready();
  try {
    const planned = await f.route("POST", "/api/host/integrations/claude/install/plan");
    const token = (await planned!.json()).plan.token;
    f.outcome = "unavailable";
    const body: IntegrationRouteBody = { requestId: "unknown-once", planToken: token, confirmed: true };
    const first = await f.route("POST", "/api/host/integrations/claude/install", body);
    expect((await first!.json()).operation.state).toBe("unknown");
    const replay = await f.route("POST", "/api/host/integrations/claude/install", body);
    expect((await replay!.json()).operation.state).toBe("unknown");
    expect(f.dispatches).toBe(1);
  } finally { await f.cleanup(); }
});

test("scope, missing device-read identity, gate, and unsupported targets fail closed", async () => {
  const f = fixture();
  await f.ready();
  try {
    expect((await f.route("GET", "/api/host/integrations?session=s1"))?.status).toBe(400);
    expect(await f.route("POST", "/api/host/integrations/unsafe-target/install/plan")).toBeNull();
    f.pairedIdentity = null;
    const missingDevice = await f.route("GET", "/api/host/integrations/status");
    expect(missingDevice?.status).toBe(403);
    expect(await missingDevice!.json()).toMatchObject({ code: "device.identity_required" });
    f.pairedIdentity = device;
    const denied = new Response("denied", { status: 403 });
    f.gate = denied;
    expect(await f.route("GET", "/api/host/integrations/status")).toBe(denied);
  } finally { await f.cleanup(); }
});


test("integration dispatcher leaves agent-view routes for their own handler", async () => {
  const f = fixture();
  await f.ready();
  let touched = 0;
  const caller = {
    gate: () => { touched++; return null; },
    pairedIdentity: () => { touched++; return device; },
    resolve: async () => { touched++; return { host: "host-a", port: f.port }; },
  };
  try {
    const url = new URL("http://collie/api/host/agent-view");
    for (const method of ["GET", "POST", "DELETE"]) {
      expect(await serveHostIntegrationRoute(new Request(url, { method }), url, caller, f.plans, f.operations)).toBeNull();
    }
    expect(touched).toBe(0);
    expect(f.dispatches).toBe(0);
  } finally { await f.cleanup(); }
});
