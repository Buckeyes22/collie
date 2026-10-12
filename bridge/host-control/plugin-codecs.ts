import { isAbsolute } from "node:path";
import { redactText } from "../redact.ts";
import { HOST } from "../host.ts";
import type { JsonObject, JsonValue } from "../json.ts";
import type { HostPluginActionDescriptor, HostPluginContext, HostPluginLinkSelection, HostPluginLog, HostPluginOwnerContext, HostPluginOwnerOperation, HostPluginPlatform, HostPluginSummary, HostPluginUninstallPlan, HostPluginUserDirsPrepared } from "./types.ts";

export function safeMessage(message: string): string {
  return Array.from(redactText(message), (character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 0x1f || code === 0x7f ? " " : character;
  }).join("").slice(0, 512);
}

export const PLUGIN_ID = /^[A-Za-z0-9][A-Za-z0-9:._-]{0,119}$/u;
export const PLUGIN_ACTION_ID = /^[A-Za-z0-9][A-Za-z0-9:_-]{0,119}$/u;
const PLUGIN_CONTEXTS = ["global", "workspace", "tab", "pane", "selection"] as const;
const PLUGIN_PLATFORMS = ["linux", "macos", "windows"] as const;
export const PLUGIN_PLACEMENTS = ["overlay", "popup", "split", "tab", "zoomed"] as const;
export function object(value: JsonValue | undefined, field: string): JsonObject {
  if (value === undefined || value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`malformed ${field}`);
  return value;
}
export function text(row: JsonObject, field: string, max = 2048, nullable = false): string | null {
  const value = row[field];
  if (nullable && (value === undefined || value === null)) return null;
  if (typeof value !== "string" || value.length > max) throw new Error(`malformed ${field}`);
  return value;
}
export function optionalEnum<T extends string>(value: JsonValue | undefined, allowed: readonly T[], field: string): T | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") throw new Error(`malformed ${field}`);
  const parsed = allowed.find((candidate) => candidate === value);
  if (parsed === undefined) throw new Error(`malformed ${field}`);
  return parsed;
}
function pluginContext(value: JsonValue): HostPluginContext {
  if (typeof value !== "string") throw new Error("malformed plugin action context");
  const parsed = PLUGIN_CONTEXTS.find((candidate) => candidate === value);
  if (parsed === undefined) throw new Error("malformed plugin action context");
  return parsed;
}
export function pluginRows(raw: JsonObject): readonly HostPluginSummary[] {
  if (raw.type !== "plugin_list" || !Array.isArray(raw.plugins) || raw.plugins.length > 256) throw new Error("malformed plugin list");
  const ids = new Set<string>();
  return raw.plugins.map((pluginValue) => {
    const row = object(pluginValue, "plugin");
    const id = text(row, "plugin_id", 120)!;
    if (!PLUGIN_ID.test(id) || ids.has(id)) throw new Error("invalid or duplicate plugin id");
    ids.add(id);
    if (typeof row.enabled !== "boolean") throw new Error("malformed plugin enabled state");
    const platforms = parsePlatforms(row.platforms);
    const source = object(row.source, "plugin source");
    const kind = source.kind === "github" ? "github" : source.kind === "local" || source.kind === undefined ? "local" : null;
    if (!kind) throw new Error("malformed plugin source kind");
    const nullableSource = (key: string) => text(source, key, 512, true);
    const actions = row.actions === undefined ? [] : Array.isArray(row.actions) ? row.actions : null;
    const panes = row.panes === undefined ? [] : Array.isArray(row.panes) ? row.panes : null;
    const warnings = Array.isArray(row.warnings) ? row.warnings : [];
    const userDirsRevision = row.user_dirs_revision;
    if (userDirsRevision !== undefined && userDirsRevision !== null && (typeof userDirsRevision !== "string" || !/^[a-f0-9]{64}$/u.test(userDirsRevision))) throw new Error("malformed plugin user directory revision");
    if (!actions || actions.length > 256 || !panes || panes.length > 256 || warnings.length > 128 || !warnings.every((entry) => typeof entry === "string" && entry.length <= 2048)) throw new Error("malformed plugin manifest rows");
    const safe: HostPluginSummary = {
      id, name: text(row, "name", 256)!, version: text(row, "version", 128)!, minimumHerdrVersion: text(row, "min_herdr_version", 128)!,
      description: text(row, "description", 2048, true), enabled: row.enabled, platforms,
      source: { kind, owner: nullableSource("owner"), repo: nullableSource("repo"), subdir: nullableSource("subdir"), requestedRef: nullableSource("requested_ref"), resolvedCommit: nullableSource("resolved_commit") },
      actions: actions.map((actionValue) => {
        const action = object(actionValue, "plugin manifest action");
        const actionId = text(action, "id", 120)!;
        const actionContexts = action.contexts === undefined ? [] : action.contexts;
        if (!PLUGIN_ACTION_ID.test(actionId) || !Array.isArray(actionContexts) || actionContexts.length > 5) throw new Error("malformed plugin manifest action");
        const contexts = actionContexts.map(pluginContext);
        const actionPlatforms = parsePlatforms(action.platforms) ?? platforms;
        return { id: `${id}.${actionId}`, qualifiedId: `${id}.${actionId}`, pluginId: id, actionId, title: text(action, "title", 256)!,
          description: text(action, "description", 2048, true), contexts, platforms: actionPlatforms, enabled: row.enabled === true,
          platformSupported: actionPlatforms === null || actionPlatforms.includes(currentPluginPlatform()) };
      }),
      panes: panes.map((paneValue) => {
        const pane = object(paneValue, "plugin manifest pane");
        const placement = optionalEnum(pane.placement, PLUGIN_PLACEMENTS, "plugin pane placement");
        const paneId = text(pane, "id", 120)!;
        const panePlatforms = parsePlatforms(pane.platforms) ?? platforms;
        const size = (key: "width" | "height"): number | string | null => {
          const dimensionValue = pane[key];
          return typeof dimensionValue === "number" && Number.isFinite(dimensionValue) || typeof dimensionValue === "string" && dimensionValue.length <= 128 ? dimensionValue : null;
        };
        if (!PLUGIN_ACTION_ID.test(paneId) || placement === null) throw new Error("malformed plugin manifest pane");
        return { id: paneId, title: text(pane, "title", 256)!, description: text(pane, "description", 2048, true), platforms: panePlatforms,
          placement, width: size("width"), height: size("height") };
      }),
      warnings: warnings.map((warning) => safeMessage(text({ warning }, "warning", 2048)!)),
      userDirsRevision: typeof userDirsRevision === "string" ? userDirsRevision : null,
    };
    return safe;
  });
}
function currentPluginPlatform(): HostPluginPlatform {
  return HOST.platform === "win32" ? "windows" : HOST.platform === "darwin" ? "macos" : "linux";
}
function parsePlatforms(value: JsonValue | undefined): readonly HostPluginPlatform[] | null {
  if (value === undefined || value === null) return null;
  if (!Array.isArray(value) || value.length > 3) throw new Error("malformed plugin platforms");
  const seen = new Set<string>();
  return value.map((item) => {
    if (typeof item !== "string" || !PLUGIN_PLATFORMS.some((candidate) => candidate === item) || seen.has(item)) throw new Error("malformed plugin platform");
    seen.add(item);
    return PLUGIN_PLATFORMS.find((candidate) => candidate === item)!;
  });
}
export function actionRows(raw: JsonObject, plugins: readonly HostPluginSummary[]): readonly HostPluginActionDescriptor[] {
  if (raw.type !== "plugin_action_list" || !Array.isArray(raw.actions) || raw.actions.length > 1024) throw new Error("malformed plugin action list");
  const installed = new Map(plugins.map((plugin) => [plugin.id, plugin]));
  const seen = new Set<string>();
  return raw.actions.map((actionValue) => {
    const row = object(actionValue, "plugin action");
    const pluginId = text(row, "plugin_id", 120)!;
    const actionId = text(row, "action_id", 120)!;
    const plugin = installed.get(pluginId);
    const qualifiedId = `${pluginId}.${actionId}`;
    if (!PLUGIN_ID.test(pluginId) || !PLUGIN_ACTION_ID.test(actionId) || !plugin || seen.has(qualifiedId)) throw new Error("invalid or duplicate plugin action");
    seen.add(qualifiedId);
    if (!Array.isArray(row.contexts) || row.contexts.length > 5) throw new Error("malformed plugin action contexts");
    const contexts: HostPluginContext[] = row.contexts.map(pluginContext);
    const platforms = parsePlatforms(row.platforms) ?? plugin.platforms;
    return { id: qualifiedId, qualifiedId, pluginId, actionId, title: text(row, "title", 256)!, description: text(row, "description", 2048, true), contexts,
      platforms, enabled: plugin.enabled, platformSupported: platforms === null || platforms.includes(currentPluginPlatform()) };
  });
}
export function logRows(raw: JsonObject): readonly HostPluginLog[] {
  if (raw.type !== "plugin_log_list" || !Array.isArray(raw.logs) || raw.logs.length > 100) throw new Error("malformed plugin logs");
  return raw.logs.map((logValue) => {
    const row = object(logValue, "plugin log");
    const status = optionalEnum(row.status, ["running", "succeeded", "failed"] as const, "plugin log status");
    if (!status) throw new Error("missing plugin log status");
    const nullableText = (key: string, max: number) => text(row, key, max, true);
    const started = row.started_unix_ms;
    const finished = row.finished_unix_ms;
    if (typeof started !== "number" || !Number.isSafeInteger(started) || started < 0 || finished !== undefined && finished !== null && (typeof finished !== "number" || !Number.isSafeInteger(finished) || finished < 0)) throw new Error("malformed plugin log time");
    const exitCode = row.exit_code;
    if (exitCode !== undefined && exitCode !== null && (typeof exitCode !== "number" || !Number.isSafeInteger(exitCode))) throw new Error("malformed plugin log exit code");
    return { id: text(row, "log_id", 256)!, pluginId: text(row, "plugin_id", 120)!, actionId: nullableText("action_id", 120), event: nullableText("event", 256), status,
      startedAt: String(started), finishedAt: typeof finished === "number" ? String(finished) : null, exitCode: typeof exitCode === "number" ? exitCode : null,
      stdout: nullableText("stdout", 16_384) === null ? null : safeMessage(nullableText("stdout", 16_384)!),
      stderr: nullableText("stderr", 16_384) === null ? null : safeMessage(nullableText("stderr", 16_384)!),
      error: nullableText("error", 4096) === null ? null : safeMessage(nullableText("error", 4096)!), };
  });
}
export function commandRows(value: JsonValue | undefined, field: string): readonly (readonly string[])[] {
  if (!Array.isArray(value) || value.length > 32) throw new Error(`malformed ${field}`);
  let total = 0;
  const commands: string[][] = [];
  for (const command of value) {
    if (!Array.isArray(command) || command.length < 1 || command.length > 64) throw new Error(`malformed ${field}`);
    const args: string[] = [];
    for (const argument of command) {
      if (typeof argument !== "string" || argument.length > 2048 || argument.includes("\0")) throw new Error(`malformed ${field}`);
      total += argument.length;
      if (total > 64 * 1024) throw new Error(`${field} exceeds its aggregate size limit`);
      args.push(argument);
    }
    commands.push(args);
  }
  return commands;
}
export function parsePluginOwnerContext(raw: JsonObject): HostPluginOwnerContext {
  if (raw.type !== "plugin_owner_context") throw new Error("malformed plugin owner context");
  const revision = text(raw, "owner_revision", 64)!;
  if (!/^[a-f0-9]{64}$/u.test(revision)) throw new Error("malformed plugin owner revision");
  const configRoot = text(raw, "config_root", 4096)!;
  const stateRoot = text(raw, "state_root", 4096)!;
  const executable = text(raw, "executable", 4096)!;
  const version = text(raw, "version", 128)!;
  if (!isAbsolute(configRoot) || !isAbsolute(stateRoot) || !executable || !version || raw.protocol !== 22 || !Array.isArray(raw.link_selections) || raw.link_selections.length > 256) throw new Error("malformed plugin owner context");
  const ids = new Set<string>();
  const selections: HostPluginLinkSelection[] = raw.link_selections.map((value) => {
    const row = object(value, "plugin link selection");
    const id = text(row, "selection_id", 256)!;
    const pluginId = text(row, "plugin_id", 120)!;
    const name = text(row, "name", 256)!;
    const pluginVersion = text(row, "version", 128)!;
    if (!/^[a-f0-9]{64}$/u.test(id) || ids.has(id) || !PLUGIN_ID.test(pluginId)) throw new Error("invalid or duplicated plugin link selection");
    ids.add(id);
    return { id, pluginId, name, version: pluginVersion };
  });
  return { revision, linkSelections: selections };
}
export function parsePluginOwnerOperation(raw: JsonObject): HostPluginOwnerOperation {
  if (raw.type !== "plugin_owner_operation") throw new Error("malformed plugin owner operation");
  const requestId = text(raw, "request_id", 128)!;
  const state = optionalEnum(raw.state, ["running", "succeeded", "refused", "unknown"] as const, "plugin owner operation state");
  if (!/^[A-Za-z0-9_-]{1,128}$/u.test(requestId) || !state || typeof raw.removed !== "boolean") throw new Error("malformed plugin owner operation");
  const plugin = raw.plugin === null ? null : pluginRows({ type: "plugin_list", plugins: [object(raw.plugin, "plugin operation result")] })[0]!;
  const code = text(raw, "code", 128, true);
  if (code !== null && !/^[a-z][a-z0-9_]{1,127}$/u.test(code)) throw new Error("malformed plugin operation refusal code");
  return { requestId, state, plugin, removed: raw.removed, code };
}


export function parsePluginUninstallPlan(raw: JsonObject, pluginId: string, ownerRevision: string): HostPluginUninstallPlan {
  if (raw.type !== "plugin_uninstall_plan" || text(raw, "plugin_id", 120) !== pluginId || text(raw, "owner_revision", 64) !== ownerRevision || raw.preserves_config_state !== true) throw new Error("malformed plugin uninstall plan");
  const removeManagedCheckout = booleanField(raw, "remove_managed_checkout");
  const revision = text(raw, "revision", 64)!;
  const id = text(raw, "plan_id", 256)!;
  if (!/^[a-f0-9]{64}$/u.test(revision) || !/^[0-9a-f-]{36}$/u.test(id)) throw new Error("malformed plugin uninstall revision");
  return { id, ownerRevision, pluginId, revision, removeManagedCheckout, preservesConfigState: true };
}

export function parsePluginUserDirsPrepared(raw: JsonObject, pluginId: string, expectedRevision: string): HostPluginUserDirsPrepared {
  if (raw.type !== "plugin_user_dirs_prepared" || raw.plugin_id !== pluginId || raw.revision !== expectedRevision) throw new Error("malformed plugin user directory preparation receipt");
  const configDir = boundedString(raw, "config_dir", 4096);
  const stateDir = boundedString(raw, "state_dir", 4096);
  const createdConfigDir = booleanField(raw, "created_config_dir");
  const createdStateDir = booleanField(raw, "created_state_dir");
  const seededLegacyConfig = booleanField(raw, "seeded_legacy_config");
  return { pluginId, revision: expectedRevision, configDir, stateDir, createdConfigDir, createdStateDir, seededLegacyConfig };
}

function boundedString(row: JsonObject, field: string, max: number): string {
  const value = text(row, field, max);
  if (value === null) throw new Error(`malformed ${field}`);
  return value;
}

function booleanField(row: JsonObject, field: string): boolean {
  const value = row[field];
  if (typeof value !== "boolean") throw new Error(`malformed ${field}`);
  return value;
}
