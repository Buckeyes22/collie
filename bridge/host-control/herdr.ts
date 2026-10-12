import { HerdrAgentRefusal } from "../mux/herdr/client.ts";
import type { HerdrDispatchAdmission, HerdrHandshake, HerdrRpc } from "../mux/herdr/client.ts";
import type { AgentManifestSnapshot, HostControlOutcome, HostControlPort, HostMutationResult, IntegrationTarget, IntegrationVersionStatus, HostPluginActionResult, HostPluginPaneResult, HostPluginUserDirsPrepared, HostPluginInstallPlanInput, HostPluginInstallPlan } from "./types.ts";
import { HOST_CAPABILITIES, INTEGRATION_TARGETS } from "./types.ts";
import { actionRows, commandRows, logRows, object, optionalEnum, parsePluginOwnerContext, parsePluginOwnerOperation, parsePluginUninstallPlan, parsePluginUserDirsPrepared, pluginRows, PLUGIN_ACTION_ID, PLUGIN_ID, PLUGIN_PLACEMENTS, safeMessage, text } from "./plugin-codecs.ts";

type HostAdminCapability = "integrations" | "integrationInstall" | "integrationUninstall" | "experimentalLettaIntegration" | "agentManifests" | "manifestReload" | "manifestRefresh" | "plugins" | "pluginOwnerOperations";
type HerdrEnvironmentKey = "PATH" | "HOME" | "USERPROFILE" | "APPDATA" | "LOCALAPPDATA" | "XDG_CONFIG_HOME" | "HERDR_CONFIG_PATH" | "SYSTEMROOT" | "WINDIR" | "TMP" | "TEMP";
type HerdrEnvironment = { HERDR_SOCKET_PATH: string } & Partial<Record<HerdrEnvironmentKey, string>>;
type RuntimeAdmission = { readonly kind: "ready"; readonly identity: string } | { readonly kind: "unavailable"; readonly outcome: HostControlOutcome<never> };
interface PluginInstallRequest { owner: string; repo: string; expectedOwnerRevision: string; subdir?: string; ref?: string }
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

const PLUGIN_UNCERTAIN_ERRORS = new Set(["plugin_owner_outcome_unknown", "plugin_owner_io_failed", "plugin_user_dir_create_failed", "plugin_user_dirs_outcome_unknown"]);
function pluginMutationFailure(error: Error, reason: string): HostControlOutcome<never> {
  if (error instanceof HerdrAgentRefusal && !PLUGIN_UNCERTAIN_ERRORS.has(error.vendorCode)) {
    return { kind: "refused", code: error.vendorCode, reason: safeMessage(error.message) };
  }
  return { kind: "unavailable", reason };
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
  let pluginUserDirsSupported = false;
  let pluginOwnerOperationsSupported = false;
  const sample = async (): Promise<string | null> => {
    if (!client.layoutHandshakeSample) return null;
    const observed = await client.layoutHandshakeSample();
    if (!observed || !compatible(observed.handshake)) { runtimeIdentity = null; compatibleRuntime = false; pluginUserDirsSupported = false; pluginOwnerOperationsSupported = false; return null; }
    runtimeIdentity = observed.identity;
    compatibleRuntime = true;
    pluginUserDirsSupported = observed.handshake.pluginUserDirsV1 === true;
    pluginOwnerOperationsSupported = observed.handshake.pluginOwnerOperationsV1 === true;
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
  const pluginCall = async <T>(_method: string, operation: () => Promise<T>, mutation = false): Promise<HostControlOutcome<T>> => {
    const ready = await check("plugins");
    if (ready.kind !== "ready") return ready.outcome;
    try {
      const value = await operation();
      if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: mutation ? "The Herdr runtime changed during the plugin operation; its outcome is unknown." : "The Herdr runtime changed while reading plugin state." };
      return result(value);
    } catch (error) {
      const message = safeMessage(error instanceof Error ? error.message : "Herdr plugin operation failed");
      if (mutation) return pluginMutationFailure(error instanceof Error ? error : new Error("unknown plugin operation failure"), "The plugin operation outcome is unknown. Read plugin state and logs before retrying.");
      if (error instanceof HerdrAgentRefusal) return { kind: "refused", code: error.vendorCode, reason: message };
      const match = /(?:herdr [^:]+: )?([a-z][a-z0-9_]{1,63}):/u.exec(message);
      return { kind: "refused", code: match?.[1] ?? "plugin_operation_failed", reason: message };
    }
  };
  const pluginAdmission = async (): Promise<HerdrDispatchAdmission | null> => {
    const ready = await check("plugins");
    return ready.kind === "ready" ? { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) } : null;
  };
  const pluginOwnerCall = async <T>(operation: (admission: HerdrDispatchAdmission) => Promise<T>, mutation = false): Promise<HostControlOutcome<T>> => {
    const ready = await check("pluginOwnerOperations");
    if (ready.kind !== "ready") return ready.outcome;
    if (!pluginOwnerOperationsSupported) return unsupported("pluginOwnerOperations");
    const admission: HerdrDispatchAdmission = { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) };
    try {
      const value = await operation(admission);
      if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: mutation ? "The Herdr runtime changed during the plugin owner operation; its outcome is unknown." : "The Herdr runtime changed while reading plugin owner state." };
      return result(value);
    } catch (error) {
      const message = safeMessage(error instanceof Error ? error.message : "Plugin owner operation failed");
      if (mutation) return pluginMutationFailure(error instanceof Error ? error : new Error("unknown plugin owner operation failure"), "Plugin owner operation outcome is unknown; query its operation receipt before retrying.");
      if (error instanceof HerdrAgentRefusal) return { kind: "refused", code: error.vendorCode, reason: message };
      const match = /(?:herdr [^:]+: )?([a-z][a-z0-9_]{1,63}):/u.exec(message)?.[1];
      return { kind: "refused", code: match ?? "plugin_owner_operation_failed", reason: message };
    }
  };
  const falseCapabilities = {
    integrations: false, integrationInstall: false, integrationUninstall: false,
    experimentalLettaIntegration: false, agentManifests: false, manifestReload: false,
    manifestRefresh: false, plugins: false, pluginOwnerOperations: false, configuration: false, persistence: false,
    machines: false, serverAdministration: false, agentViews: false, hostUiActions: false, hostCommands: false,
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
        plugins: !!client.listPlugins && !!client.listPluginActions && !!client.listPluginLogs && !!client.setPluginEnabled && !!client.unlinkPlugin && !!client.invokePluginAction && !!client.openPluginPane && !!client.focusPluginPane && !!client.closePluginPane,
        pluginOwnerOperations: pluginOwnerOperationsSupported && !!client.pluginOwnerContext && !!client.planPluginInstall && !!client.applyPluginInstall && !!client.applyPluginLink && !!client.planPluginUninstall && !!client.applyPluginUninstall && !!client.getPluginOwnerOperation,
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
    async listPlugins(pluginId?: string) {
      if (pluginId !== undefined && !PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!client.listPlugins) return unsupported("plugins");
      return pluginCall("plugin.list", async () => pluginRows(await client.listPlugins!(pluginId)));
    },
    async pluginOwnerContext() {
      if (!client.pluginOwnerContext) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => parsePluginOwnerContext(await client.pluginOwnerContext!(admission)));
    },
    async listPluginLinkSelections() {
      if (!client.pluginOwnerContext) return unsupported("pluginOwnerOperations");
      const context = await port.pluginOwnerContext!();
      return context.kind === "available" ? result(context.value.linkSelections) : context;
    },
    async planPluginInstall(input: HostPluginInstallPlanInput) {
      if (!client.pluginOwnerContext || !client.planPluginInstall) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const owner = parsePluginOwnerContext(await client.pluginOwnerContext!(admission));
        const installRequest: PluginInstallRequest = {
          owner: input.owner, repo: input.repo, expectedOwnerRevision: owner.revision,
        };
        if (input.subdir !== null) installRequest.subdir = input.subdir;
        if (input.ref !== null) installRequest.ref = input.ref;
        const raw = await client.planPluginInstall!(installRequest, admission);
        if (raw.type !== "plugin_install_plan" || text(raw, "plan_id", 256) === null || text(raw, "owner_revision", 64) !== owner.revision) throw new Error("malformed plugin install plan");
        const rawPlugin = object(raw.plugin, "plugin install plan metadata");
        const plugin = pluginRows({ type: "plugin_list", plugins: [rawPlugin] })[0]!;
        const source = object(raw.source, "plugin install source");
        const sourceOwner = text(source, "owner", 100)!;
        const repo = text(source, "repo", 100)!;
        const subdir = text(source, "subdir", 512, true);
        const ref = text(source, "ref", 256, true);
        const commit = text(source, "commit", 64)!;
        const checksum = text(raw, "checksum", 64)!;
        const planId = text(raw, "plan_id", 256)!;
        if (!/^[0-9a-f-]{36}$/u.test(planId) || sourceOwner.toLowerCase() !== input.owner.toLowerCase() || repo.toLowerCase() !== input.repo.toLowerCase() || subdir !== input.subdir || (input.ref !== null && ref !== input.ref) || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/u.test(commit) || !/^[a-f0-9]{64}$/u.test(checksum) || plugin.source.resolvedCommit !== commit) throw new Error("plugin install plan does not match its requested source");
        return { id: planId, ownerRevision: owner.revision, plugin, source: { owner: sourceOwner, repo, subdir, ref, commit }, checksum,
          buildCommands: commandRows(raw.build_commands, "plugin build commands"), startupCommands: commandRows(raw.startup_commands, "plugin startup commands") } satisfies HostPluginInstallPlan;
      });
    },
    async applyPluginInstall(planId: string, checksum: string, ownerRevision: string, requestId: string) {
      if (!client.pluginOwnerContext || !client.applyPluginInstall) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const owner = parsePluginOwnerContext(await client.pluginOwnerContext!(admission));
        if (owner.revision !== ownerRevision) throw new HerdrAgentRefusal("plugin_owner_revision_mismatch");
        const raw = await client.applyPluginInstall!(planId, checksum, ownerRevision, requestId, admission);
        const operation = parsePluginOwnerOperation(raw);
        if (operation.requestId !== requestId) throw new Error("malformed plugin install operation identity");
        return operation;
      }, true);
    },
    async linkPlugin(selectionId: string, ownerRevision: string, requestId: string) {
      if (!client.pluginOwnerContext || !client.applyPluginLink) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const owner = parsePluginOwnerContext(await client.pluginOwnerContext!(admission));
        if (owner.revision !== ownerRevision || !owner.linkSelections.some((selection) => selection.id === selectionId)) throw new HerdrAgentRefusal("plugin_owner_revision_mismatch");
        const operation = parsePluginOwnerOperation(await client.applyPluginLink!(selectionId, ownerRevision, requestId, admission));
        if (operation.requestId !== requestId) throw new Error("malformed plugin link operation identity");
        return operation;
      }, true);
    },
    async planPluginUninstall(pluginId: string) {
      if (!client.pluginOwnerContext || !client.planPluginUninstall || !client.listPlugins) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const owner = parsePluginOwnerContext(await client.pluginOwnerContext!(admission));
        const installed = pluginRows(await client.listPlugins!(pluginId)).find((plugin) => plugin.id === pluginId);
        if (!installed) throw new Error("plugin_not_found: plugin is not installed");
        const raw = await client.planPluginUninstall!(pluginId, owner.revision, admission);
        return parsePluginUninstallPlan(raw, pluginId, owner.revision);
      });
    },
    async uninstallPlugin(planId: string, revision: string, ownerRevision: string, requestId: string) {
      if (!client.pluginOwnerContext || !client.applyPluginUninstall) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const owner = parsePluginOwnerContext(await client.pluginOwnerContext!(admission));
        if (owner.revision !== ownerRevision) throw new HerdrAgentRefusal("plugin_owner_revision_mismatch");
        const operation = parsePluginOwnerOperation(await client.applyPluginUninstall!(planId, revision, ownerRevision, requestId, admission));
        if (operation.requestId !== requestId) throw new Error("malformed plugin uninstall operation identity");
        return operation;
      }, true);
    },
    async getPluginOwnerOperation(requestId: string) {
      if (!client.getPluginOwnerOperation) return unsupported("pluginOwnerOperations");
      return pluginOwnerCall(async (admission) => {
        const operation = parsePluginOwnerOperation(await client.getPluginOwnerOperation!(requestId, admission));
        if (operation.requestId !== requestId) throw new Error("plugin owner operation identity mismatch");
        return operation;
      });
    },
    async listPluginActions(pluginId?: string) {
      if (pluginId !== undefined && !PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!client.listPluginActions || !client.listPlugins) return unsupported("plugins");
      return pluginCall("plugin.action.list", async () => {
        const plugins = pluginRows(await client.listPlugins!());
        return actionRows(await client.listPluginActions!(pluginId), plugins);
      });
    },
    async listPluginLogs(pluginId: string, limit: number) {
      if (!PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) return { kind: "refused", code: "invalid_plugin_log_limit", reason: "Plugin log limit must be between 1 and 100." };
      if (!client.listPluginLogs) return unsupported("plugins");
      return pluginCall("plugin.log.list", async () => logRows(await client.listPluginLogs!(pluginId, limit)));
    },
    async enablePlugin(pluginId: string) {
      if (!PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!client.setPluginEnabled) return unsupported("plugins");
      const admission = await pluginAdmission();
      if (!admission) return unsupported("plugins");
      return pluginCall("plugin.enable", async () => { await client.setPluginEnabled!(pluginId, true, admission); return { messages: [] }; }, true);
    },
    async disablePlugin(pluginId: string) {
      if (!PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!client.setPluginEnabled) return unsupported("plugins");
      const admission = await pluginAdmission();
      if (!admission) return unsupported("plugins");
      return pluginCall("plugin.disable", async () => { await client.setPluginEnabled!(pluginId, false, admission); return { messages: [] }; }, true);
    },
    async invokePluginAction(pluginId: string, actionId: string, _invocationSource, correlationId: string) {
      if (!PLUGIN_ID.test(pluginId) || !PLUGIN_ACTION_ID.test(actionId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(correlationId)) return { kind: "refused", code: "invalid_plugin_action", reason: "Plugin action identity or correlation id is invalid." };
      if (!client.invokePluginAction) return unsupported("plugins");
      const admission = await pluginAdmission();
      if (!admission) return unsupported("plugins");
      return pluginCall("plugin.action.invoke", async () => {
        const raw = await client.invokePluginAction!(pluginId, actionId, correlationId, admission);
        if (raw.type !== "plugin_action_invoked") throw new Error("malformed plugin action result");
        const action = object(raw.action, "plugin action result");
        const log = object(raw.log, "plugin action log");
        if (text(action, "plugin_id", 120) !== pluginId || text(action, "action_id", 120) !== actionId) throw new Error("plugin action result identity mismatch");
        const status = optionalEnum(log.status, ["running", "succeeded", "failed"] as const, "plugin action status");
        if (!status) throw new Error("malformed plugin action status");
        return { pluginId, actionId, logId: text(log, "log_id", 256, true), status } satisfies HostPluginActionResult;
      }, true);
    },
    async openPluginPane(pluginId: string, entrypoint: string, options = {}) {
      if (!PLUGIN_ID.test(pluginId) || !PLUGIN_ACTION_ID.test(entrypoint)) return { kind: "refused", code: "invalid_plugin_entrypoint", reason: "Plugin pane identity is invalid." };
      if (!client.openPluginPane) return unsupported("plugins");
      const admission = await pluginAdmission();
      if (!admission) return unsupported("plugins");
      const inventory = await port.listPlugins!(pluginId);
      if (inventory.kind !== "available") return inventory;
      const declared = inventory.value.find((plugin) => plugin.id === pluginId)?.panes.find((pane) => pane.id === entrypoint);
      if (!declared) return { kind: "refused", code: "invalid_plugin_entrypoint", reason: "Plugin pane entrypoint is not in the installed manifest." };
      let placement: HostPluginPaneResult["placement"];
      try { placement = optionalEnum(options.placement, PLUGIN_PLACEMENTS, "plugin pane placement") ?? declared.placement; }
      catch { return { kind: "refused", code: "invalid_plugin_entrypoint", reason: "Plugin pane placement is invalid." }; }
      // SAFETY: the HTTP parser bounds each permitted pane option; these original IDs and resolved enum are pinned after options.
      const params = { ...options, pluginId, entrypoint, placement } as Parameters<NonNullable<HerdrRpc["openPluginPane"]>>[0];
      return pluginCall("plugin.pane.open", async () => {
        const raw = await client.openPluginPane!(params, admission);
        try {
          if (placement === "popup" && raw.type === "ok") return { pluginId, entrypoint, paneId: null, placement };
          if (raw.type !== "plugin_pane_opened") throw new Error("malformed plugin pane result");
          const opened = object(raw.plugin_pane, "opened plugin pane");
          if (opened.plugin_id !== pluginId || opened.entrypoint !== entrypoint) throw new Error("plugin pane result identity mismatch");
          const pane = object(opened.pane, "opened pane");
          const paneId = text(pane, "pane_id", 128)!;
          if (!/^[A-Za-z0-9:._-]{1,128}$/u.test(paneId) || placement === "popup") throw new Error("malformed plugin pane identity");
          return { pluginId, entrypoint, paneId, placement } satisfies HostPluginPaneResult;
        } catch { throw new Error("Plugin pane acknowledgment could not be verified; outcome unknown."); }
      }, true);
    },
    async focusPluginPane(paneId: string) {
      if (!/^[A-Za-z0-9:._-]{1,128}$/u.test(paneId)) return { kind: "refused", code: "invalid_plugin_pane", reason: "Plugin pane id is invalid." };
      if (!client.focusPluginPane) return unsupported("plugins");
      const admission = await pluginAdmission(); if (!admission) return unsupported("plugins");
      return pluginCall("plugin.pane.focus", async () => { await client.focusPluginPane!(paneId, admission); return { messages: [] }; }, true);
    },
    async closePluginPane(paneId: string) {
      if (!/^[A-Za-z0-9:._-]{1,128}$/u.test(paneId)) return { kind: "refused", code: "invalid_plugin_pane", reason: "Plugin pane id is invalid." };
      if (!client.closePluginPane) return unsupported("plugins");
      const admission = await pluginAdmission(); if (!admission) return unsupported("plugins");
      return pluginCall("plugin.pane.close", async () => { await client.closePluginPane!(paneId, admission); return { messages: [] }; }, true);
    },
    async unlinkPlugin(pluginId: string) {
      if (!PLUGIN_ID.test(pluginId)) return { kind: "refused", code: "invalid_plugin_id", reason: "Plugin id is invalid." };
      if (!client.unlinkPlugin) return unsupported("plugins");
      const admission = await pluginAdmission(); if (!admission) return unsupported("plugins");
      return pluginCall("plugin.unlink", async () => { await client.unlinkPlugin!(pluginId, admission); return { messages: [] }; }, true);
    },
    async preparePluginUserDirs(pluginId: string, expectedRevision: string): Promise<HostControlOutcome<HostPluginUserDirsPrepared>> {
      if (!PLUGIN_ID.test(pluginId) || !/^[a-f0-9]{64}$/u.test(expectedRevision)) return { kind: "refused", code: "invalid_plugin_config_revision", reason: "Plugin configuration preparation binding is invalid." };
      if (!client.preparePluginUserDirs || !client.listPlugins) return unsupported("plugins");
      const ready = await check("plugins");
      if (ready.kind !== "ready") return ready.outcome;
      if (!pluginUserDirsSupported) return { kind: "unsupported", capability: "plugins", reason: "This Herdr runtime does not advertise owner-scoped plugin user-directory preparation." };
      const admission: HerdrDispatchAdmission = { runtimeIdentity: ready.identity, finalAdmission: (identity) => identity === ready.identity && stillCurrent(ready.identity) };
      try {
        const plugins = pluginRows(await client.listPlugins());
        const plugin = plugins.find((candidate) => candidate.id === pluginId);
        if (!plugin) return { kind: "refused", code: "plugin_not_found", reason: "The plugin is no longer installed." };
        if (plugin.userDirsRevision !== expectedRevision) return { kind: "refused", code: "plugin_revision_changed", reason: "Plugin metadata changed after this preparation plan was created." };
        const raw = await client.preparePluginUserDirs(pluginId, expectedRevision, admission);
        if (!stillCurrent(ready.identity)) return { kind: "unavailable", reason: "The Herdr runtime changed during plugin directory preparation; its outcome is unknown." };
        return result(parsePluginUserDirsPrepared(raw, pluginId, expectedRevision));
      } catch (error) {
        return pluginMutationFailure(error instanceof Error ? error : new Error("unknown plugin directory preparation failure"), "Plugin directory preparation outcome is unknown. Refresh plugin status before retrying.");
      }
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
    manifestRefresh: false, plugins: false, pluginOwnerOperations: false, configuration: false, persistence: false,
    machines: false, serverAdministration: false, agentViews: false, hostUiActions: false, hostCommands: false,
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
