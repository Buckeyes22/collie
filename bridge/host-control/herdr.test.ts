import { expect, test } from "bun:test";
import { createHerdrHostControl, parseIntegrationStatus, type HerdrCliRunner } from "./herdr.ts";
import { HerdrClient, HerdrAgentRefusal } from "../mux/herdr/client.ts";
import { HOST_CAPABILITIES } from "./types.ts";
import type { JsonObject } from "../json.ts";

function client(handshake = { version: "0.9.3", protocol: 22 }) {
  let identity: string | null = "incarnation-one";
  const calls: string[] = [];
  const rpc = Object.assign(new HerdrClient("/unused-test-socket"), {
    layoutHandshakeSample: async () => identity ? { handshake, identity } : null,
    layoutRuntimeIdentity: () => identity,
    listIntegrations: async () => [{ target: "claude", label: "Claude", command: "/private/path/claude", available: true, state: "current" as const }],
    installIntegration: async (_target: string, admission?: { runtimeIdentity: string; finalAdmission: (identity: string) => boolean }) => {
      calls.push("integration.install");
      if (!admission?.finalAdmission(admission.runtimeIdentity)) throw new Error("retired");
      return { messages: [] };
    },
    uninstallIntegration: async () => ({ messages: [] }),
    agentManifests: async () => ({ manifests: [] }),
    reloadAgentManifests: async () => ({ manifests: [] }),
  });
  return { rpc, calls, retire: () => { identity = null; } };
}

test("host admin capabilities require a compatible positive runtime sample", async () => {
  const old = createHerdrHostControl(client({ version: "0.9.2", protocol: 22 }).rpc, "/socket", async () => ({ exitCode: 0, output: "", timedOut: false }));
  await old.prepare?.();
  expect(Object.values(old.capabilities).some(Boolean)).toBe(false);

  const prerelease = createHerdrHostControl(client({ version: "0.9.3-rc.1", protocol: 22 }).rpc, "/socket", async () => ({ exitCode: 0, output: "", timedOut: false }));
  await prerelease.prepare?.();
  expect(Object.values(prerelease.capabilities).some(Boolean)).toBe(false);

  const ready = client();
  const port = createHerdrHostControl(ready.rpc, "/socket", async () => ({ exitCode: 0, output: "", timedOut: false }));
  expect(Object.values(port.capabilities).some(Boolean)).toBe(false);
  await port.prepare?.();
  expect(port.capabilities.integrations).toBe(true);
  expect(port.capabilities.integrationInstall).toBe(true);
  const installed = await port.installIntegration("claude");
  expect(installed.kind).toBe("available");
  expect(ready.calls).toEqual(["integration.install"]);
  ready.retire();
  await port.prepare?.();
  expect(Object.values(port.capabilities).some(Boolean)).toBe(false);
  for (const capability of HOST_CAPABILITIES.filter((name) => name !== "integrations")) {
    if (capability in port.capabilities && capability.startsWith("manifest")) expect(port.capabilities[capability]).toBe(false);
  }
});

test("integration status parser keeps versions and drops config paths", () => {
  const rows = parseIntegrationStatus([
    "claude: current (v2) (/home/operator/.claude/hooks/collie.json)",
    "codex: outdated (v1 < v3) (/home/operator/.codex/hooks.json)",
    "copilot: needs repair (v2) (/home/operator/.config/herdr/private.json)",
    "antigravity-cli: not installed (/home/operator/.config/antigravity)",
  ].join("\n"));
  expect(rows).toEqual([
    { target: "claude", state: "current", installedVersion: "2", requiredVersion: null },
    { target: "codex", state: "outdated", installedVersion: "1", requiredVersion: "3" },
    { target: "copilot", state: "needs_repair", installedVersion: "2", requiredVersion: null },
    { target: "antigravity_cli", state: "not_installed", installedVersion: null, requiredVersion: null },
  ]);
  expect(JSON.stringify(rows)).not.toContain("/home/operator");
  expect(rows?.find((row) => row.target === "copilot")?.state).toBe("needs_repair");
  expect(parseIntegrationStatus("claude: current (v2) (/home/x)\nunsafe: current (v1) (/home/x)")).toBeNull();
});

test("Letta and manifest refresh use fixed bounded CLI argv and do not expose output", async () => {
  const seen: string[][] = [];
  const statusOutput = "letta: current (v4) (/private/letta/config.json)";
  const runner: HerdrCliRunner = async (args, socketPath) => {
    expect(socketPath).toBe("/verified/herdr.sock");
    seen.push([...args]);
    return { exitCode: 0, output: args.join(" ") === "integration status" ? statusOutput : "changed /home/user/.config/herdr", timedOut: false };
  };
  const rpc = client().rpc;
  const port = createHerdrHostControl(rpc, "/verified/herdr.sock", runner);
  await port.prepare?.();
  const outcome = await port.installExperimentalLetta();
  expect(outcome).toEqual({ kind: "available", value: { messages: [] } });
  const status = await port.lettaIntegrationStatus();
  expect(status).toEqual({ kind: "available", value: { target: "letta", state: "current", installedVersion: "4", requiredVersion: null } });
  expect(seen).toEqual([["integration", "install", "letta"], ["integration", "status"]]);
});

test("unsupported runtime does not invoke the host CLI", async () => {
  let spawned = false;
  const runner: HerdrCliRunner = async () => { spawned = true; return { exitCode: 0, output: "", timedOut: false }; };
  const stale = client({ version: "0.8.0", protocol: 20 });
  const port = createHerdrHostControl(stale.rpc, "/socket", runner);
  expect((await port.installExperimentalLetta()).kind).toBe("unsupported");
  expect(spawned).toBe(false);
});


test("actual producer omitted empty plugin vectors parse without admitting malformed values", async () => {
  let row: JsonObject = {
    plugin_id: "example.empty", name: "Empty plugin", version: "0.1.0", min_herdr_version: "0.9.3",
    enabled: true, source: { kind: "local" },
  };
  const ready = client();
  const rpc = Object.assign(ready.rpc, { listPlugins: async () => ({ type: "plugin_list", plugins: [row] }) });
  const port = createHerdrHostControl(rpc, "/socket");
  const inventory = await port.listPlugins!();
  expect(inventory.kind).toBe("available");
  if (inventory.kind !== "available") throw new Error("Expected valid omitted-field inventory");
  expect(inventory.value[0]?.actions).toEqual([]);
  expect(inventory.value[0]?.panes).toEqual([]);
  row = { ...row, actions: [{ id: "echo", title: "Echo" }] };
  const actions = await port.listPlugins!();
  expect(actions.kind).toBe("available");
  if (actions.kind !== "available") throw new Error("Expected valid omitted contexts");
  expect(actions.value[0]?.actions[0]?.contexts).toEqual([]);
  for (const field of ["actions", "panes"]) {
    const valid = row;
    row = { ...row, [field]: "invalid" };
    expect((await port.listPlugins!()).kind).toBe("refused");
    row = valid;
  }
  row = { ...row, actions: [{ id: "echo", title: "Echo", contexts: "invalid" }] };
  expect((await port.listPlugins!()).kind).toBe("refused");
});


test("real plugin pane acknowledgments preserve all placements and unknown post-dispatch results", async () => {
  let placement = "overlay";
  let malformed = false;
  let dispatchedPlacement: string | undefined;
  const ready = client();
  const rpc = Object.assign(ready.rpc, {
    listPlugins: async () => ({ type: "plugin_list", plugins: [{
      plugin_id: "example.panes", name: "Panes", version: "1.0.0", min_herdr_version: "0.9.3",
      enabled: true, source: { kind: "local" }, panes: [{ id: "panel", title: "Panel", placement: "overlay" }],
    }] }),
    openPluginPane: async (params: { placement?: string }) => {
      dispatchedPlacement = params.placement;
      return malformed ? { type: "plugin_pane_opened", plugin_pane: {} }
        : placement === "popup" ? { type: "ok" } : { type: "plugin_pane_opened", plugin_pane: {
          plugin_id: "example.panes", entrypoint: "panel", pane: { pane_id: "w1:p2" },
        } };
    },
  });
  const port = createHerdrHostControl(rpc, "/socket");
  for (const selected of ["overlay", "popup", "split", "tab", "zoomed"] as const) {
    placement = selected;
    expect(await port.openPluginPane!("example.panes", "panel", { placement: selected })).toEqual({ kind: "available", value: {
      pluginId: "example.panes", entrypoint: "panel", paneId: selected === "popup" ? null : "w1:p2", placement: selected,
    } });
  }
  placement = "overlay";
  expect((await port.openPluginPane!("example.panes", "panel")).kind).toBe("available");
  expect(dispatchedPlacement).toBe("overlay");
  malformed = true;
  expect((await port.openPluginPane!("example.panes", "panel", { placement: "overlay" })).kind).toBe("unavailable");
});


test("malformed owner mutation acknowledgment remains unknown after the actual effect", async () => {
  let effects = 0;
  let refusalCode: string | null = null;
  const ready = client();
  const rpc = Object.assign(ready.rpc, {
    layoutHandshakeSample: async () => ({ handshake: { version: "0.9.3", protocol: 22, pluginOwnerOperationsV1: true }, identity: "incarnation-one" }),
    pluginOwnerContext: async () => ({ type: "plugin_owner_context", owner_revision: "a".repeat(64), config_root: "/owned/config", state_root: "/owned/state", executable: "/owned/herdr", version: "0.9.3", protocol: 22,
      link_selections: [{ selection_id: "b".repeat(64), plugin_id: "example.safe", name: "Safe", version: "1.0.0" }] }),
    applyPluginLink: async () => {
      if (refusalCode) throw new HerdrAgentRefusal(refusalCode);
      effects++;
      return { type: "plugin_owner_operation", state: "invalid" };
    },
  });
  const port = createHerdrHostControl(rpc, "/socket");
  expect((await port.linkPlugin!("b".repeat(64), "a".repeat(64), "request1")).kind).toBe("unavailable");
  expect(effects).toBe(1);
  expect(await port.linkPlugin!("b".repeat(64), "c".repeat(64), "retired-owner")).toMatchObject({ kind: "refused", code: "plugin_owner_revision_mismatch" });
  expect(effects).toBe(1);
  refusalCode = "plugin_disabled";
  expect(await port.linkPlugin!("b".repeat(64), "a".repeat(64), "request2")).toMatchObject({ kind: "refused", code: "plugin_disabled" });
  expect(effects).toBe(1);
  refusalCode = "plugin_owner_outcome_unknown";
  expect((await port.linkPlugin!("b".repeat(64), "a".repeat(64), "request3")).kind).toBe("unavailable");
});


test("partially completed directory preparation error envelopes remain unknown", async () => {
  let code = "plugin_user_dir_create_failed";
  const ready = client();
  const rpc = Object.assign(ready.rpc, {
    layoutHandshakeSample: async () => ({ handshake: { version: "0.9.3", protocol: 22, pluginUserDirsV1: true }, identity: "incarnation-one" }),
    listPlugins: async () => ({ type: "plugin_list", plugins: [{ plugin_id: "example.safe", name: "Safe", version: "1.0.0", min_herdr_version: "0.9.3", enabled: true, source: { kind: "local" }, user_dirs_revision: "a".repeat(64) }] }),
    preparePluginUserDirs: async () => { throw new HerdrAgentRefusal(code); },
  });
  const port = createHerdrHostControl(rpc, "/socket");
  for (const partial of ["plugin_user_dir_create_failed", "plugin_user_dirs_outcome_unknown"]) {
    code = partial;
    expect((await port.preparePluginUserDirs!("example.safe", "a".repeat(64))).kind).toBe("unavailable");
  }
  code = "plugin_user_dirs_revision_mismatch";
  expect(await port.preparePluginUserDirs!("example.safe", "a".repeat(64))).toMatchObject({ kind: "refused", code });
});
