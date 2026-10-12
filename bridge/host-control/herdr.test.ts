import { expect, test } from "bun:test";
import { createHerdrHostControl, parseIntegrationStatus, type HerdrCliRunner } from "./herdr.ts";
import { HerdrClient } from "../mux/herdr/client.ts";
import { HOST_CAPABILITIES } from "./types.ts";

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
