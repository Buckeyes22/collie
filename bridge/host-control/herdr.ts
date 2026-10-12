import { redactText } from "../redact.ts";
import type { HerdrDispatchAdmission, HerdrHandshake, HerdrRpc } from "../mux/herdr/client.ts";
import type { AgentManifestSnapshot, HostControlOutcome, HostControlPort, HostMutationResult, IntegrationTarget, IntegrationVersionStatus } from "./types.ts";
import { HOST_CAPABILITIES, INTEGRATION_TARGETS } from "./types.ts";

type HostAdminCapability = "integrations" | "integrationInstall" | "integrationUninstall" | "experimentalLettaIntegration" | "agentManifests" | "manifestReload" | "manifestRefresh";
type HerdrEnvironmentKey = "PATH" | "HOME" | "USERPROFILE" | "APPDATA" | "LOCALAPPDATA" | "XDG_CONFIG_HOME" | "HERDR_CONFIG_PATH" | "SYSTEMROOT" | "WINDIR" | "TMP" | "TEMP";
type HerdrEnvironment = { HERDR_SOCKET_PATH: string } & Partial<Record<HerdrEnvironmentKey, string>>;
type RuntimeAdmission = { readonly kind: "ready"; readonly identity: string } | { readonly kind: "unavailable"; readonly outcome: HostControlOutcome<never> };
const HERDR_ENVIRONMENT_KEYS: readonly HerdrEnvironmentKey[] = ["PATH", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "HERDR_CONFIG_PATH", "SYSTEMROOT", "WINDIR", "TMP", "TEMP"];

const UNSUPPORTED = "This Herdr runtime has not completed a compatible host-administration handshake.";
const MAX_CLI_OUTPUT = 64 * 1024;
const CLI_TIMEOUT_MS = 15_000;

export interface HerdrCliResult { readonly exitCode: number; readonly output: string; readonly timedOut: boolean }
export type HerdrCliRunner = (args: readonly string[], socketPath: string) => Promise<HerdrCliResult>;

function compatible(handshake: HerdrHandshake): boolean {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(handshake.version);
  if (!match || handshake.protocol !== 22) return false;
  const [major, minor, patch] = match.slice(1).map(Number);
  const supported = major! > 0 || minor! > 9 || minor === 9 && patch! >= 3;
  // A prerelease of the floor itself is still below that stable release. Newer prereleases
  // remain above the floor (for example 0.9.4-rc.1 is newer than 0.9.3).
  return supported && !(major === 0 && minor === 9 && patch === 3 && handshake.version.includes("-"));
}

function safeMessage(text: string): string {
  return Array.from(redactText(text), (character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 0x1f || code === 0x7f ? " " : character;
  }).join("").slice(0, 512);
}

function cliEnvironment(socketPath: string): HerdrEnvironment {
  const env: HerdrEnvironment = { HERDR_SOCKET_PATH: socketPath };
  for (const name of HERDR_ENVIRONMENT_KEYS) {
    const value = process.env[name];
    if (value !== undefined && value.length <= 4096 && !value.includes("\0")) env[name] = value;
  }
  return env;
}

/** One bounded fixed-argv CLI call. Output is capped and never logged or returned verbatim. */
export const runHerdrCli: HerdrCliRunner = async (args, socketPath) => {
  const proc = Bun.spawn(["herdr", ...args], {
    stdin: "ignore", stdout: "pipe", stderr: "ignore", env: cliEnvironment(socketPath),
  });
  const reader = proc.stdout.getReader();
  const parts: Uint8Array[] = [];
  let size = 0;
  let timedOut = false;
  let tooLarge = false;
  const timer = setTimeout(() => { timedOut = true; proc.kill(); }, CLI_TIMEOUT_MS);
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_CLI_OUTPUT) { tooLarge = true; proc.kill(); break; }
      parts.push(value);
    }
    const exitCode = await proc.exited;
    const output = new TextDecoder("utf-8", { fatal: false }).decode(Buffer.concat(parts.map((part) => Buffer.from(part))));
    if (tooLarge) throw new Error("herdr CLI output exceeded its limit");
    // Callers either parse a closed status grammar or deliberately discard stdout. Keep line
    // boundaries for that parser; no output is logged or returned through the HTTP API.
    return { exitCode, output, timedOut };
  } finally {
    clearTimeout(timer);
    reader.releaseLock();
  }
};

function result<T>(value: T): HostControlOutcome<T> { return { kind: "available", value }; }
function unsupported<T>(capability: HostAdminCapability): HostControlOutcome<T> {
  return { kind: "unsupported", capability, reason: UNSUPPORTED };
}

interface CliIntegrationVersion extends IntegrationVersionStatus {}
function isIntegrationTarget(value: string): value is IntegrationTarget {
  return INTEGRATION_TARGETS.some((target) => target === value);
}
/** Parses only version/state fields; Herdr's CLI status paths are deliberately discarded. */
export function parseIntegrationStatus(output: string): readonly CliIntegrationVersion[] | null {
  if (output.length > MAX_CLI_OUTPUT) return null;
  const rows: CliIntegrationVersion[] = [];
  const seen = new Set<string>();
  for (const line of output.split(/\r?\n/u).filter((value) => value.trim() !== "")) {
    const match = /^([^:()]+?)(?: \(experimental\))?: (not installed|current(?: \((?:v(\d+)|legacy)\))?|outdated(?: \((?:(?:v(\d+)(?: < v(\d+))?)|legacy)\))?|needs repair \(v(\d+)\)) \(([^\r\n]{1,4096})\)$/u.exec(line);
    if (!match) return null;
    const rawTarget = match[1]!.trim();
    const normalizedTarget = rawTarget === "antigravity-cli" ? "antigravity_cli" : rawTarget;
    if ((!isIntegrationTarget(normalizedTarget) && normalizedTarget !== "letta") || seen.has(normalizedTarget)) return null;
    const target: IntegrationTarget | "letta" = normalizedTarget === "letta" ? "letta" : normalizedTarget;
    seen.add(target);
    const stateText = match[2]!;
    const state = stateText === "not installed" ? "not_installed"
      : stateText.startsWith("needs repair") ? "needs_repair"
        : stateText === "current" || stateText.startsWith("current ") ? "current" : "outdated";
    const installedVersion = match[3] ?? match[4] ?? match[6] ?? null;
    const requiredVersion = match[5] ?? null;
    rows.push({ target, state, installedVersion, requiredVersion });
  }
  return rows.length > 0 ? rows : null;
}

/** The sole Herdr host-admin adapter: exact typed methods, positive handshake, fixed CLI argv only. */
export function createHerdrHostControl(client: HerdrRpc, socketPath: string, runCli: HerdrCliRunner = runHerdrCli): HostControlPort {
  let runtimeIdentity: string | null = null;
  let compatibleRuntime = false;
  const sample = async (): Promise<string | null> => {
    if (!client.layoutHandshakeSample) return null;
    const observed = await client.layoutHandshakeSample();
    if (!observed || !compatible(observed.handshake)) { runtimeIdentity = null; compatibleRuntime = false; return null; }
    runtimeIdentity = observed.identity;
    compatibleRuntime = true;
    return runtimeIdentity;
  };
  const stillCurrent = (identity: string): boolean => client.layoutRuntimeIdentity?.() === identity;
  const check = async (capability: HostAdminCapability): Promise<RuntimeAdmission> => {
    try {
      const identity = await sample();
      return identity ? { kind: "ready", identity } : { kind: "unavailable", outcome: unsupported(capability) };
    } catch (error) {
      return { kind: "unavailable", outcome: { kind: "unavailable", reason: safeMessage(error instanceof Error ? error.message : "Herdr is unavailable") } };
    }
  };
  const cliMutation = async (args: readonly string[]): Promise<HostControlOutcome<HostMutationResult>> => {
    const identity = await sample();
    if (!identity) return unsupported("experimentalLettaIntegration");
    try {
      if (!stillCurrent(identity)) return { kind: "refused", code: "scope.changed", reason: "The Herdr runtime changed before dispatch." };
      const reply = await runCli(args, socketPath);
      if (reply.timedOut) return { kind: "unavailable", reason: "The command timed out; its outcome is unknown. Check integration status before trying again." };
      if (reply.exitCode !== 0) return { kind: "refused", code: "integration_cli_failed", reason: "Herdr refused the experimental integration command." };
      if (!stillCurrent(identity)) return { kind: "unavailable", reason: "The Herdr runtime changed during the command; its outcome is unknown." };
      return result({ messages: [] });
    } catch (error) {
      return { kind: "unavailable", reason: `Herdr CLI could not be started; no success was confirmed (${safeMessage(error instanceof Error ? error.message : "executable unavailable")}).` };
    }
  };
  const emptySnapshot = async (method: "agentManifests" | "reloadAgentManifests"): Promise<AgentManifestSnapshot> => {
    const call = client[method];
    if (!call) throw new Error("Herdr socket method unavailable");
    const value = await call.call(client);
    return value;
  };
  const integrationVersions = async (identity: string): Promise<readonly CliIntegrationVersion[] | null> => {
    try {
      const status = await runCli(["integration", "status"], socketPath);
      if (status.exitCode !== 0 || status.timedOut || !stillCurrent(identity)) return null;
      return parseIntegrationStatus(status.output);
    } catch { return null; }
  };
  const falseCapabilities = {
    integrations: false, integrationInstall: false, integrationUninstall: false,
    experimentalLettaIntegration: false, agentManifests: false, manifestReload: false,
    manifestRefresh: false, plugins: false, configuration: false, persistence: false,
    machines: false, serverAdministration: false, agentViews: false,
  } as const satisfies Readonly<Record<(typeof HOST_CAPABILITIES)[number], boolean>>;
  const port: HostControlPort = {
    provider: "herdr",
    get capabilities() {
      const available = compatibleRuntime && runtimeIdentity !== null;
      return available ? {
        ...falseCapabilities, integrations: !!client.listIntegrations,
        integrationInstall: !!client.installIntegration, integrationUninstall: !!client.uninstallIntegration,
        experimentalLettaIntegration: true, agentManifests: !!client.agentManifests,
        manifestReload: !!client.reloadAgentManifests, manifestRefresh: true,
      } : falseCapabilities;
    },
    get notes() {
      return Object.fromEntries(Object.keys(falseCapabilities).map((key) => [key, compatibleRuntime ? "This Herdr host operation is unavailable in the current adapter." : UNSUPPORTED]));
    },
    async prepare() { if (!runtimeIdentity || !stillCurrent(runtimeIdentity)) await sample(); },
    async listIntegrations() {
      const ready = await check("integrations");
      if (ready.kind !== "ready") return ready.outcome;
      if (!client.listIntegrations) return unsupported("integrations");
      try {
        const value = await client.listIntegrations();
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed while reading integrations." };
        let versionRows: readonly CliIntegrationVersion[] | null = null;
        let versionStatus: "available" | "unavailable" | "partial" = "unavailable";
        versionRows = await integrationVersions(ready.identity);
        if (versionRows !== null) versionStatus = "available";
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed while reading integration versions." };
        const byTarget = new Map(versionRows?.map((row) => [row.target, row]) ?? []);
        const rows = value.map((row) => {
          const versions = byTarget.get(row.target);
          const merged = Object.assign({}, row, {
            command: row.command.split(/[\\/]/u).at(-1) ?? row.command,
            versionStatus: versions ? "available" as const : versionStatus === "available" ? "partial" as const : versionStatus,
          });
          if (versions) Object.assign(merged, { state: versions.state, installedVersion: versions.installedVersion, requiredVersion: versions.requiredVersion });
          return merged;
        });
        if (versionStatus === "available" && rows.some((row) => row.versionStatus === "partial")) versionStatus = "partial";
        return result(rows.map((row) => Object.assign({}, row, { versionStatus })));
      } catch (cause) {
        return { kind: "unavailable", reason: safeMessage(cause instanceof Error ? cause.message : "Integration inventory is unavailable") };
      }
    },
    async lettaIntegrationStatus() {
      const ready = await check("experimentalLettaIntegration");
      if (ready.kind !== "ready") return ready.outcome;
      const rows = await integrationVersions(ready.identity);
      const status = rows?.find((row) => row.target === "letta");
      return status ? result(status) : { kind: "unavailable", reason: "Herdr did not provide an authoritative Letta integration status." };
    },
    async installIntegration(target: IntegrationTarget) {
      const ready = await check("integrationInstall");
      if (ready.kind !== "ready") return ready.outcome;
      if (!client.installIntegration) return unsupported("integrationInstall");
      const admission: HerdrDispatchAdmission = { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) };
      try {
        const value = await client.installIntegration(target, admission);
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed during installation; its outcome is unknown." };
        return result({ messages: value.messages.map(safeMessage) });
      } catch (error) { return herdrMutationRefusal(error, "integration_install_failed"); }
    },
    async uninstallIntegration(target: IntegrationTarget) {
      const ready = await check("integrationUninstall");
      if (ready.kind !== "ready") return ready.outcome;
      if (!client.uninstallIntegration) return unsupported("integrationUninstall");
      const admission: HerdrDispatchAdmission = { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) };
      try {
        const value = await client.uninstallIntegration(target, admission);
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed during removal; its outcome is unknown." };
        return result({ messages: value.messages.map(safeMessage) });
      } catch (error) { return herdrMutationRefusal(error, "integration_uninstall_failed"); }
    },
    async installExperimentalLetta() { return cliMutation(["integration", "install", "letta"]); },
    async uninstallExperimentalLetta() { return cliMutation(["integration", "uninstall", "letta"]); },
    async agentManifests() {
      const ready = await check("agentManifests");
      if (ready.kind !== "ready") return ready.outcome;
      if (!client.agentManifests) return unsupported("agentManifests");
      try {
        const value = await emptySnapshot("agentManifests");
        return stillCurrent(ready.identity) ? result(value) : { kind: "unavailable", reason: "The Herdr runtime changed while reading manifests." };
      } catch (error) { return { kind: "unavailable", reason: safeMessage(error instanceof Error ? error.message : "Manifest status is unavailable") }; }
    },
    async reloadAgentManifests() {
      const ready = await check("manifestReload");
      if (ready.kind !== "ready") return ready.outcome;
      if (!client.reloadAgentManifests) return unsupported("manifestReload");
      const admission: HerdrDispatchAdmission = { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) };
      try {
        const value = await client.reloadAgentManifests(admission);
        return stillCurrent(ready.identity) ? result(value) : { kind: "unavailable", reason: "The Herdr runtime changed during reload; its outcome is unknown." };
      } catch (error) {
        const message = safeMessage(error instanceof Error ? error.message : "Herdr refused manifest reload.");
        return { kind: "refused", code: message.includes("agent_manifest_reload_failed") ? "agent_manifest_reload_failed" : "manifest_reload_failed", reason: message };
      }
    },
    async refreshAgentManifests() {
      const ready = await check("manifestRefresh");
      if (ready.kind !== "ready") return ready.outcome;
      try {
        if (!stillCurrent(ready.identity)) return { kind: "refused", code: "scope.changed", reason: "The Herdr runtime changed before dispatch." };
        const reply = await runCli(["server", "update-agent-manifests", "--json"], socketPath);
        if (reply.timedOut) return { kind: "unavailable", reason: "Manifest refresh timed out; its outcome is unknown. Read manifest status before trying again." };
        if (reply.exitCode !== 0) return { kind: "refused", code: "manifest_refresh_failed", reason: "Herdr refused the manifest refresh." };
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed during refresh; its outcome is unknown." };
        return await port.agentManifests();
      } catch (error) { return { kind: "unavailable", reason: safeMessage(error instanceof Error ? error.message : "Manifest refresh is unavailable") }; }
    },
    async inspect(request) { return { kind: "unsupported", capability: request.capability, reason: "This Herdr adapter exposes no generic host inspection method." }; },
  };
  return port;
}

/** Compatibility/test fixture only: static declaration is deliberately all false. */
export const herdrHostControl: HostControlPort = {
  provider: "herdr",
  capabilities: {
    integrations: false, integrationInstall: false, integrationUninstall: false,
    experimentalLettaIntegration: false, agentManifests: false, manifestReload: false,
    manifestRefresh: false, plugins: false, configuration: false, persistence: false,
    machines: false, serverAdministration: false, agentViews: false,
  },
  notes: Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, UNSUPPORTED])),
  async listIntegrations() { return unsupported("integrations"); },
  async lettaIntegrationStatus() { return unsupported("experimentalLettaIntegration"); },
  async installIntegration() { return unsupported("integrationInstall"); },
  async uninstallIntegration() { return unsupported("integrationUninstall"); },
  async installExperimentalLetta() { return unsupported("experimentalLettaIntegration"); },
  async uninstallExperimentalLetta() { return unsupported("experimentalLettaIntegration"); },
  async agentManifests() { return unsupported("agentManifests"); },
  async reloadAgentManifests() { return unsupported("manifestReload"); },
  async refreshAgentManifests() { return unsupported("manifestRefresh"); },
  async inspect(request) { return { kind: "unsupported", capability: request.capability, reason: UNSUPPORTED }; },
};

function herdrMutationRefusal(cause: unknown, fallbackCode: string): HostControlOutcome<HostMutationResult> {
  const message = safeMessage(cause instanceof Error ? cause.message : "Herdr refused the operation.");
  const known = /(?:^|: )((?:integration_install_failed|integration_uninstall_failed|invalid_target|capability\.unsupported))(?=:|$)/u.exec(message)?.[1];
  return { kind: "refused", code: known ?? fallbackCode, reason: message || "Herdr refused the operation." };
}
