import { controlScope, payloadHash, refusal, refusalResponse } from "../control/contracts.ts";
import { executePlannedOperation, type PlanBinding, type PlanStore } from "../control/plans.ts";
import type { OperationStore, OperationView } from "../control/operations.ts";
import type { JsonObject, JsonValue } from "../json.ts";
import type { HostControlOutcome, HostPluginActionDescriptor, HostPluginSummary, HostControlPort, HostPluginUserDirsPrepared } from "./types.ts";
import type { HostPluginInstallPlan, HostPluginInstallPlanInput, HostPluginUninstallPlan, HostPluginOwnerOperation } from "./types.ts";
import { listPluginConfigFiles, readPluginConfigFile, redactPluginConfigContent, restorePluginConfigSecrets, writePluginConfigFile } from "./plugin-config.ts";
import { basename } from "node:path";
import { hasControlCharacters, jsonBoolean, jsonNumber, jsonObject, jsonString, pluginPlacement } from "./request.ts";

export interface HostPluginsRouteCaller {
  gate(level: "read" | "device-read" | "write"): Response | null;
  pairedIdentity(): string | null;
  resolve(): Promise<{ readonly host: string; readonly port: HostControlPort } | Response>;
}
const preparedConfigDirs = new Map<string, HostPluginUserDirsPrepared>();
const preparedConfigDirPlans = new Map<string, { readonly host: string; readonly device: string; readonly pluginId: string; readonly revision: string; readonly payload: JsonObject }>();
const preparedConfigEditPlans = new Map<string, { readonly host: string; readonly device: string; readonly pluginId: string; readonly file: string; readonly content: string; readonly submittedContent: string; readonly revision: string }>();
const preparedInstalls = new Map<string, { readonly host: string; readonly device: string; readonly input: HostPluginInstallPlanInput; readonly plan: HostPluginInstallPlan }>();
const preparedLinks = new Map<string, { readonly host: string; readonly device: string; readonly selectionId: string; readonly pluginId: string; readonly ownerRevision: string }>();
const preparedUninstalls = new Map<string, { readonly host: string; readonly device: string; readonly pluginId: string; readonly plan: HostPluginUninstallPlan }>();
function rememberPlan<T>(store: Map<string, T>, token: string, value: T): void {
  store.set(token, value);
  while (store.size > 512) {
    const oldest = store.keys().next().value;
    if (oldest === undefined) break;
    store.delete(oldest);
  }
}

const PLUGIN_ROUTE = /^\/api\/host\/plugins(?:\/([^/]+)(?:\/(actions(?:\/[^/]+\/invoke)?|logs|config(?:\/(?:prepare(?:\/plan)?|plan|apply))?|enable|disable|unlink|uninstall(?:\/plan)?|panes\/open|panes\/[^/]+\/(?:focus|close)))?)?$/u;
const PLUGIN_INSTALL_ROUTE = /^\/api\/host\/plugins\/install(?:\/plan)?$/u;
const PLUGIN_LINK_ROUTE = /^\/api\/host\/plugins\/link(?:\/(?:selections|plan))?$/u;
const PLUGIN_OPERATION_ROUTE = /^\/api\/host\/plugins\/operations\/([A-Za-z0-9_-]{1,128})$/u;
function response(code: string, message: string, status: number): Response {
  return refusalResponse(refusal(code, message, status === 403 ? "device" : "capability"), status);
}
function cleanPlugin(value: HostPluginSummary): JsonObject {
  return {
    id: value.id, name: value.name, version: value.version, minimumHerdrVersion: value.minimumHerdrVersion,
    description: value.description, enabled: value.enabled, platforms: value.platforms === null ? null : [...value.platforms],
    source: { kind: value.source.kind, owner: value.source.owner, repo: value.source.repo, subdir: value.source.subdir,
      requestedRef: value.source.requestedRef, resolvedCommit: value.source.resolvedCommit },
    actions: value.actions.map(cleanAction), panes: value.panes.map((pane) => ({ ...pane, platforms: pane.platforms === null ? null : [...pane.platforms] })), warnings: [...value.warnings],
  };
}
function cleanAction(value: HostPluginActionDescriptor): JsonObject {
  return { id: value.id, qualifiedId: value.qualifiedId, pluginId: value.pluginId, actionId: value.actionId,
    title: value.title, description: value.description, contexts: [...value.contexts], platforms: value.platforms === null ? null : [...value.platforms],
    enabled: value.enabled, platformSupported: value.platformSupported };
}
function commandEffects(stage: "build" | "startup", commands: readonly (readonly string[])[]): readonly string[] {
  if (commands.length === 0) return [`No manifest-declared ${stage} commands are declared.`];
  const programs = [...new Set(commands.map((command) => basename(command[0] ?? "unknown")))].filter((name) => /^[A-Za-z0-9._+-]{1,80}$/u.test(name)).toSorted();
  const timing = stage === "build" ? "will run during install" : "will be registered for Herdr daemon startup, not run during install";
  return [`${commands.length} manifest-declared ${stage} command${commands.length === 1 ? "" : "s"} ${timing} in Herdr's owner environment${programs.length ? ` (programs: ${programs.join(", ")})` : ""}. These commands are supplied by the plugin.`];
}
function cleanInstallPlan(token: string, expiresAt: number, plan: HostPluginInstallPlan): JsonObject {
  return { token, expiresAt, plugin: cleanPlugin(plan.plugin), source: { owner: plan.source.owner, repo: plan.source.repo,
    subdir: plan.source.subdir, ref: plan.source.ref, commit: plan.source.commit }, checksum: plan.checksum,
    effects: [...commandEffects("build", plan.buildCommands), ...commandEffects("startup", plan.startupCommands)] };
}
function mutationOutcome(value: HostControlOutcome<HostPluginOwnerOperation>): import("../control/operations.ts").OperationOutcome {
  if (value.kind === "available") {
    if (value.value.state === "succeeded") return { state: "succeeded" };
    if (value.value.state === "refused") return { state: "refused", code: value.value.code ?? "plugin_operation_refused" };
    return { state: "unknown", code: value.value.code ?? "plugin_operation_unknown" };
  }
  if (value.kind === "refused") return { state: "refused", code: value.code };
  return { state: "unknown", code: value.kind === "unsupported" ? "plugin_owner_operation_unsupported" : "plugin_owner_operation_unknown" };
}
function boundedBody(req: Request, maximum = 16 * 1024): Promise<JsonValue | null> {
  const declared = req.headers.get("content-length");
  if (declared !== null && (!/^\d+$/u.test(declared) || Number(declared) > maximum)) throw new Error("body too large");
  return (async () => {
    if (!req.body) return null;
    const chunks: Uint8Array[] = []; let size = 0;
    const reader = req.body.getReader();
    try {
      while (true) {
        const { done, value } = await reader.read(); if (done) break;
        size += value.byteLength; if (size > maximum) { await reader.cancel(); throw new Error("body too large"); }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    if (!size) return null;
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    // SAFETY: JSON.parse returns only JSON primitives, arrays, and plain objects, the closed JsonValue union.
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as JsonValue;
  })();
}
function outcome<T>(value: HostControlOutcome<T>): Response | T {
  if (value.kind === "available") return value.value;
  if (value.kind === "unsupported") return response("host.capability_unsupported", value.reason, 501);
  if (value.kind === "refused") return response(value.code, value.reason, 409);
  return response("host.operation_unavailable", value.reason, 503);
}
function unsupported(capability: string): Response {
  return response("host.capability_unsupported", `This host does not provide ${capability}.`, 501);
}
const pluginIdValid = (id: string): boolean => /^[A-Za-z0-9][A-Za-z0-9:._-]{0,119}$/u.test(id);
function binding(device: string, host: string, capability: string, payload: JsonValue, targetIdentity: string, evidence: string): PlanBinding {
  return { device, host, session: null, capability, payloadHash: payloadHash(payload), targetIdentity, evidence };
}
function keyFor(host: string, pluginId: string): string { return `${host}\0${pluginId}`; }
function validFileId(value: JsonValue | undefined): value is string {
  return jsonString(value) && value.length <= 512 && /^[A-Za-z0-9._/-]+$/u.test(value) && !value.startsWith("/") && !value.includes("\\") && !value.split("/").some((part) => part === "" || part === "." || part === "..");
}
async function currentPlugin(port: HostControlPort, pluginId: string) {
  if (!port.listPlugins) return null;
  const inventory = await port.listPlugins(pluginId);
  return inventory.kind === "available" ? inventory.value.find((plugin) => plugin.id === pluginId) ?? null : null;
}
function encodePluginOperation(operation: OperationView, extra: JsonObject = {}): Response {
  return Response.json({ ok: operation.state === "succeeded", operation, ...extra }, { headers: { "cache-control": "no-store" } });
}

/** Native plugin management surface. Request bodies never carry a Herdr context or executable data. */
export async function serveHostPluginsRoute(req: Request, url: URL, caller: HostPluginsRouteCaller, plans?: PlanStore, operations?: OperationStore): Promise<Response | null> {
  let match = PLUGIN_ROUTE.exec(url.pathname);
  let special: "install-plan" | "install-apply" | "link-selections" | "link-plan" | "link-apply" | "owner-operation" | null = null;
  let specialId: string | null = null;
  if (PLUGIN_INSTALL_ROUTE.test(url.pathname)) special = url.pathname.endsWith("/plan") ? "install-plan" : "install-apply";
  else if (PLUGIN_LINK_ROUTE.test(url.pathname)) special = url.pathname.endsWith("/selections") ? "link-selections" : url.pathname.endsWith("/plan") ? "link-plan" : "link-apply";
  else {
    const operationMatch = PLUGIN_OPERATION_ROUTE.exec(url.pathname);
    if (operationMatch) { special = "owner-operation"; specialId = operationMatch[1]!; }
  }
  if (!match && special === null) return null;
  if (!(req.method === "GET" || req.method === "POST")) return response("route.method", "Method is not supported", 405);
  const tail = special === "install-plan" ? "install/plan" : special === "install-apply" ? "install" : special === "link-selections" ? "link/selections" : special === "link-plan" ? "link/plan" : special === "link-apply" ? "link" : special === "owner-operation" ? "operations" : match?.[2] ?? null;
  let id: string | null = special === "owner-operation" ? specialId : null;
  try { if (special === null) id = match?.[1] ? decodeURIComponent(match[1]) : null; } catch { return response("invalid_plugin_id", "Plugin id encoding is invalid", 400); }
  const write = req.method === "POST";
  const isPlan = special === "install-plan" || special === "link-plan" || tail === "uninstall/plan" || tail === "config/prepare/plan" || tail === "config/plan";
  const level = write && !isPlan ? "write" : "device-read";
  const denied = caller.gate(level);
  if (denied) return denied;
  if (!controlScope(url, "host")) return response("scope.malformed", "Plugin management requires host-only scope", 400);
  if (id !== null && special === "owner-operation" && !/^[A-Za-z0-9_-]{1,128}$/u.test(id)) return response("invalid_request_id", "Operation request id is invalid", 400);
  if (id !== null && special !== "owner-operation" && !pluginIdValid(id)) return response("invalid_plugin_id", "Plugin id is invalid", 400);
  const resolved = await caller.resolve();
  if (resolved instanceof Response) return resolved;
  const port = resolved.port;
  if (!port.capabilities.plugins) return unsupported("plugin controls on the selected runtime");
  const isOwnerOperation = special !== null && special !== "owner-operation" && special !== "link-selections" || tail === "uninstall/plan" || tail === "uninstall";
  if (isOwnerOperation && !port.capabilities.pluginOwnerOperations) return unsupported("owner-scoped plugin installation and linking");
  if (special === "link-selections" && req.method === "GET") {
    if (!port.listPluginLinkSelections) return unsupported("server-discovered plugin link selections");
    const rows = outcome(await port.listPluginLinkSelections());
    return rows instanceof Response ? rows : Response.json({ ok: true, selections: rows.map((row) => ({ id: row.id, pluginId: row.pluginId, name: row.name, version: row.version })) }, { headers: { "cache-control": "no-store" } });
  }
  if (special === "owner-operation" && req.method === "GET") {
    const device = caller.pairedIdentity();
    if (!device || !operations || !port.getPluginOwnerOperation) return unsupported("plugin owner operation receipts");
    const stored = await operations.inspect(id!, device, resolved.host, null);
    if (!stored || stored.capability !== "pluginOwnerOperations") return response("plugin_operation_not_found", "No owner operation receipt exists for this paired device and host.", 404);
    const owner = outcome(await port.getPluginOwnerOperation(id!));
    if (owner instanceof Response) return owner;
    return Response.json({ ok: true, operation: stored, ownerOperation: { requestId: owner.requestId, state: owner.state, plugin: owner.plugin ? cleanPlugin(owner.plugin) : null, removed: owner.removed, code: owner.code } }, { headers: { "cache-control": "no-store" } });
  }
  if (special === "install-plan") {
    if (!write || !plans || !port.planPluginInstall) return response("route.method", "Plugin install planning requires POST", 405);
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin installation planning requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_install", "Plugin install source is malformed", 400); }
    if (!body || Object.keys(body).some((key) => !["owner", "repo", "subdir", "ref"].includes(key)) || !jsonString(body.owner) || !/^[A-Za-z0-9-]{1,100}$/u.test(body.owner) || !jsonString(body.repo) || !/^[A-Za-z0-9._-]{1,100}$/u.test(body.repo) || body.subdir !== undefined && (!jsonString(body.subdir) || body.subdir.length > 512 || body.subdir.startsWith("/") || body.subdir.split("/").some((part) => !part || part === "." || part === "..")) || body.ref !== undefined && (!jsonString(body.ref) || !body.ref || body.ref.length > 256 || hasControlCharacters(body.ref))) return response("invalid_plugin_install", "Plugin install source is malformed", 400);
    const input: HostPluginInstallPlanInput = { owner: body.owner, repo: body.repo, subdir: jsonString(body.subdir) ? body.subdir : null, ref: jsonString(body.ref) ? body.ref : null };
    const planned = outcome(await port.planPluginInstall(input));
    if (planned instanceof Response) return planned;
    const plan = planned;
    const payload = { action: "plugin_install", owner: input.owner, repo: input.repo, subdir: input.subdir, ref: input.ref };
    try {
      const issued = plans.issue(binding(device, resolved.host, "pluginOwnerOperations", payload, `plugin:${plan.plugin.id}:install`, `${plan.ownerRevision}:${plan.id}:${plan.checksum}`));
      rememberPlan(preparedInstalls, issued.token, { host: resolved.host, device, input, plan });
      return Response.json({ ok: true, plan: cleanInstallPlan(issued.token, issued.expiresAt, plan) }, { headers: { "cache-control": "no-store" } });
    } catch { return response("plugin_plan_unavailable", "A plugin install plan could not be created", 503); }
  }
  if (special === "install-apply") {
    if (!write || !plans || !operations || !operations.isDurable || !port.pluginOwnerContext || !port.applyPluginInstall) return unsupported("durable plugin installation");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin installation requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_install_plan", "Plugin install confirmation is malformed", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "planToken", "checksum", "confirmed"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !jsonString(body.planToken) || !jsonString(body.checksum) || !/^[a-f0-9]{64}$/u.test(body.checksum) || body.confirmed !== true) return response("invalid_plugin_install_plan", "Plugin install confirmation is malformed", 400);
    const requestId = body.requestId;
    const captured = preparedInstalls.get(body.planToken);
    if (captured) {
      const payload = { action: "plugin_install", owner: captured.input.owner, repo: captured.input.repo, subdir: captured.input.subdir, ref: captured.input.ref };
      const existing = await operations.inspectIntent(body.requestId, device, resolved.host, null, "pluginOwnerOperations", payloadHash(payload), body.planToken);
      if (existing.kind === "conflict") return response("operation.request_id_reused", "This request ID is already bound to a different plugin operation", 409);
      if (existing.kind === "match") return encodePluginOperation(existing.operation, { effect: existing.operation.state === "succeeded" ? "Plugin installed from the reviewed immutable commit." : "Plugin installation was recorded once; inspect its receipt before retrying." });
    }
    const plan = plans.lookup(body.planToken);
    if (!captured || captured.device !== device || captured.host !== resolved.host || !plan || plan.binding.device !== device || plan.binding.host !== resolved.host || plan.binding.capability !== "pluginOwnerOperations" || plan.binding.targetIdentity !== `plugin:${captured.plan.plugin.id}:install` || body.checksum !== captured.plan.checksum || plan.binding.payloadHash !== payloadHash({ action: "plugin_install", owner: captured.input.owner, repo: captured.input.repo, subdir: captured.input.subdir, ref: captured.input.ref })) return response("plan.scope_changed", "Plugin install plan is missing, expired, or differs from the reviewed artifact", 409);
    const currentBinding = async (): Promise<PlanBinding> => {
      const current = await port.pluginOwnerContext!();
      const revision = current.kind === "available" ? current.value.revision : "missing";
      return binding(device, resolved.host, "pluginOwnerOperations", { action: "plugin_install", owner: captured.input.owner, repo: captured.input.repo, subdir: captured.input.subdir, ref: captured.input.ref }, `plugin:${captured.plan.plugin.id}:install`, `${revision}:${captured.plan.id}:${captured.plan.checksum}`);
    };
    const view = await executePlannedOperation(operations, plans, body.requestId, plan, currentBinding, async () => {
      return mutationOutcome(await port.applyPluginInstall!(captured.plan.id, captured.plan.checksum, captured.plan.ownerRevision, requestId));
    });
    return encodePluginOperation(view, { effect: view.state === "succeeded" ? "Plugin installed from the reviewed immutable commit." : "Plugin installation was recorded once; inspect its receipt before retrying." });
  }
  if (special === "link-plan") {
    if (!write || !plans || !port.pluginOwnerContext || !port.listPluginLinkSelections) return unsupported("plugin link planning");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin linking requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_link", "Plugin link selection is malformed", 400); }
    if (!body || Object.keys(body).length !== 1 || !jsonString(body.selectionId) || !/^[a-f0-9]{64}$/u.test(body.selectionId)) return response("invalid_plugin_link", "Plugin link selection is malformed", 400);
    const owner = outcome(await port.pluginOwnerContext());
    if (owner instanceof Response) return owner;
    const selection = owner.linkSelections.find((row) => row.id === body.selectionId);
    if (!selection) return response("plugin_owner_selection_not_found", "The selected plugin folder is no longer available.", 409);
    try {
      const plan = plans.issue(binding(device, resolved.host, "pluginOwnerOperations", { selectionId: selection.id, pluginId: selection.pluginId }, `plugin:${selection.pluginId}:link`, owner.revision));
      rememberPlan(preparedLinks, plan.token, { host: resolved.host, device, selectionId: selection.id, pluginId: selection.pluginId, ownerRevision: owner.revision });
      return Response.json({ ok: true, plan: { token: plan.token, expiresAt: plan.expiresAt, pluginId: selection.pluginId, name: selection.name, version: selection.version,
        effect: "Register this plugin from a server-discovered managed plugin folder. Existing config and state are preserved." } }, { headers: { "cache-control": "no-store" } });
    } catch { return response("plugin_plan_unavailable", "A plugin link plan could not be created", 503); }
  }
  if (special === "link-apply") {
    if (!write || !plans || !operations || !operations.isDurable || !port.pluginOwnerContext || !port.linkPlugin) return unsupported("durable plugin linking");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin linking requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_link_plan", "Plugin link confirmation is malformed", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "planToken", "confirmed"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !jsonString(body.planToken) || body.confirmed !== true) return response("invalid_plugin_link_plan", "Plugin link confirmation is malformed", 400);
    const requestId = body.requestId;
    const captured = preparedLinks.get(body.planToken); const plan = plans.lookup(body.planToken);
    if (captured) {
      const payload = { selectionId: captured.selectionId, pluginId: captured.pluginId };
      const existing = await operations.inspectIntent(body.requestId, device, resolved.host, null, "pluginOwnerOperations", payloadHash(payload), body.planToken);
      if (existing.kind === "conflict") return response("operation.request_id_reused", "This request ID is already bound to a different plugin operation", 409);
      if (existing.kind === "match") return encodePluginOperation(existing.operation, { effect: "Plugin link operation was recorded once; plugin config and state are preserved." });
    }
    if (!captured || captured.device !== device || captured.host !== resolved.host || !plan || plan.binding.device !== device || plan.binding.host !== resolved.host || plan.binding.capability !== "pluginOwnerOperations" || plan.binding.targetIdentity !== `plugin:${captured.pluginId}:link`) return response("plan.scope_changed", "Plugin link plan expired or changed", 409);
    const payload = { selectionId: captured.selectionId, pluginId: captured.pluginId };
    if (plan.binding.payloadHash !== payloadHash(payload)) return response("plan.scope_changed", "Plugin link plan differs from the reviewed folder", 409);
    const currentBinding = async (): Promise<PlanBinding> => {
      const owner = await port.pluginOwnerContext!(); const revision = owner.kind === "available" ? owner.value.revision : "missing";
      return binding(device, resolved.host, "pluginOwnerOperations", payload, `plugin:${captured.pluginId}:link`, revision);
    };
    const view = await executePlannedOperation(operations, plans, requestId, plan, currentBinding, async () => mutationOutcome(await port.linkPlugin!(captured.selectionId, captured.ownerRevision, requestId)));
    return encodePluginOperation(view, { effect: "Plugin link operation was recorded once; plugin config and state are preserved." });
  }
  if (tail === "uninstall/plan") {
    if (!write || !id || !plans || !port.pluginOwnerContext || !port.planPluginUninstall) return unsupported("plugin uninstall planning");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin removal planning requires the originating paired device", 403);
    const planned = outcome(await port.planPluginUninstall(id));
    if (planned instanceof Response) return planned;
    const uninstall = planned;
    try {
      const issued = plans.issue(binding(device, resolved.host, "pluginOwnerOperations", { pluginId: id, revision: uninstall.revision }, `plugin:${id}:uninstall`, `${uninstall.ownerRevision}:${uninstall.id}:${uninstall.revision}`));
      rememberPlan(preparedUninstalls, issued.token, { host: resolved.host, device, pluginId: id, plan: uninstall });
      return Response.json({ ok: true, plan: { token: issued.token, expiresAt: issued.expiresAt, pluginId: id, removeManagedCheckout: uninstall.removeManagedCheckout,
        preservesConfigState: uninstall.preservesConfigState, effect: `${uninstall.removeManagedCheckout ? "Remove the managed plugin checkout and registry entry" : "Unlink the plugin registry entry"}; preserve user config and state.` } }, { headers: { "cache-control": "no-store" } });
    } catch { return response("plugin_plan_unavailable", "A plugin uninstall plan could not be created", 503); }
  }
  if (tail === "uninstall") {
    if (!write || !id || !plans || !operations || !operations.isDurable || !port.pluginOwnerContext || !port.uninstallPlugin) return unsupported("durable plugin uninstall");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Plugin removal requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_uninstall_plan", "Plugin uninstall confirmation is malformed", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "planToken", "confirmed"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !jsonString(body.planToken) || body.confirmed !== true) return response("invalid_plugin_uninstall_plan", "Plugin uninstall confirmation is malformed", 400);
    const requestId = body.requestId;
    const captured = preparedUninstalls.get(body.planToken); const plan = plans.lookup(body.planToken);
    if (captured) {
      const payload = { pluginId: id, revision: captured.plan.revision };
      const existing = await operations.inspectIntent(body.requestId, device, resolved.host, null, "pluginOwnerOperations", payloadHash(payload), body.planToken);
      if (existing.kind === "conflict") return response("operation.request_id_reused", "This request ID is already bound to a different plugin operation", 409);
      if (existing.kind === "match") return encodePluginOperation(existing.operation, { effect: "Plugin uninstall operation was recorded once; user config and state are preserved." });
    }
    if (!captured || captured.device !== device || captured.host !== resolved.host || captured.pluginId !== id || !plan || plan.binding.device !== device || plan.binding.host !== resolved.host || plan.binding.capability !== "pluginOwnerOperations" || plan.binding.targetIdentity !== `plugin:${id}:uninstall`) return response("plan.scope_changed", "Plugin uninstall plan expired or changed", 409);
    const payload = { pluginId: id, revision: captured.plan.revision };
    if (plan.binding.payloadHash !== payloadHash(payload)) return response("plan.scope_changed", "Plugin uninstall plan differs from the reviewed plugin revision", 409);
    const currentBinding = async (): Promise<PlanBinding> => {
      const owner = await port.pluginOwnerContext!(); const revision = owner.kind === "available" ? owner.value.revision : "missing";
      const plugin = await currentPlugin(port, id); return binding(device, resolved.host, "pluginOwnerOperations", payload, `plugin:${id}:uninstall`, `${revision}:${captured.plan.id}:${plugin ? captured.plan.revision : "missing"}`);
    };
    const view = await executePlannedOperation(operations, plans, requestId, plan, currentBinding, async () => mutationOutcome(await port.uninstallPlugin!(captured.plan.id, captured.plan.revision, captured.plan.ownerRevision, requestId)));
    return encodePluginOperation(view, { effect: "Plugin uninstall operation was recorded once; user config and state are preserved." });
  }
  if (req.method === "GET" && tail === null) {
    if (!port.listPlugins) return unsupported("plugin inventory");
    const result = outcome(await port.listPlugins());
    return result instanceof Response ? result : Response.json({ ok: true, plugins: result.map(cleanPlugin) }, { headers: { "cache-control": "no-store" } });
  }
  if (req.method === "GET" && tail === "actions") {
    if (!port.listPluginActions) return unsupported("plugin action inventory");
    const result = outcome(await port.listPluginActions(id ?? undefined));
    return result instanceof Response ? result : Response.json({ ok: true, actions: result.map(cleanAction) }, { headers: { "cache-control": "no-store" } });
  }
  if (req.method === "GET" && tail === "logs") {
    if (!id || !port.listPluginLogs) return unsupported("plugin logs");
    const limitRaw = url.searchParams.get("limit");
    const limit = limitRaw === null ? 50 : Number(limitRaw);
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) return response("invalid_plugin_log_limit", "Log limit must be between 1 and 100", 400);
    const result = outcome(await port.listPluginLogs(id, limit));
    return result instanceof Response ? result : Response.json({ ok: true, logs: result }, { headers: { "cache-control": "no-store" } });
  }
  if (req.method === "GET" && tail === "config") {
    if (!id) return response("invalid_plugin_id", "Plugin id is required", 400);
    const prepared = preparedConfigDirs.get(keyFor(resolved.host, id));
    if (!prepared) return Response.json({ ok: false, code: "plugin_config_prepare_required", effect: "Create this plugin's owner-scoped config and state directories, including any eligible one-time legacy seed." }, { status: 428, headers: { "cache-control": "no-store" } });
    const plugin = await currentPlugin(port, id);
    if (!plugin || plugin.userDirsRevision !== prepared.revision) { preparedConfigDirs.delete(keyFor(resolved.host, id)); return response("plugin_revision_changed", "Plugin metadata changed; prepare the config directory again before editing.", 409); }
    const inventory = await listPluginConfigFiles(prepared.configDir);
    if (inventory.kind !== "available") return response("plugin_config_unavailable", inventory.reason, inventory.kind === "refused" ? 409 : 503);
    const fileId = url.searchParams.get("file");
    if (fileId === null) return Response.json({ ok: true, files: inventory.value }, { headers: { "cache-control": "no-store" } });
    if (!validFileId(fileId) || !inventory.value.some((file) => file.id === fileId)) return response("plugin_config_file_not_found", "Config file is not in the prepared plugin directory.", 404);
    const file = await readPluginConfigFile(prepared.configDir, fileId);
    if (file.kind !== "available") return response("plugin_config_unavailable", file.reason, file.kind === "refused" ? 409 : 503);
    return Response.json({ ok: true, file: { ...file.value, content: redactPluginConfigContent(file.value.content) } }, { headers: { "cache-control": "no-store" } });
  }
  if (!write || !id) return response("route.not_found", "Plugin route is not supported", 404);
  if (tail === "config/prepare/plan") {
    if (!plans || !port.listPlugins || !port.preparePluginUserDirs) return unsupported("owner-scoped plugin config preparation");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Config preparation requires the originating paired device", 403);
    const plugin = await currentPlugin(port, id);
    if (!plugin) return response("plugin_not_found", "Plugin is not installed", 404);
    if (!plugin.userDirsRevision || !/^[a-f0-9]{64}$/u.test(plugin.userDirsRevision)) return unsupported("owner-scoped plugin config preparation");
    const payload = { action: "prepare_plugin_user_dirs", pluginId: id, revision: plugin.userDirsRevision };
    try {
      const plan = plans.issue(binding(device, resolved.host, "plugins", payload, `plugin:${id}:config-user-dirs`, plugin.userDirsRevision));
      rememberPlan(preparedConfigDirPlans, plan.token, { host: resolved.host, device, pluginId: id, revision: plugin.userDirsRevision, payload });
      return Response.json({ ok: true, plan: { token: plan.token, expiresAt: plan.expiresAt, pluginId: id,
        effect: "Create this plugin's config and state directories. Herdr may seed legacy config files on first creation." } }, { headers: { "cache-control": "no-store" } });
    } catch { return response("plugin_plan_unavailable", "A plugin configuration plan could not be created", 503); }
  }
  if (tail === "config/prepare") {
    if (!plans || !operations || !operations.isDurable || !port.listPlugins || !port.preparePluginUserDirs) return unsupported("durable owner-scoped plugin config preparation");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Config preparation requires the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req)); } catch { return response("invalid_plugin_config_plan", "Config preparation request is malformed", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "planToken", "confirmed"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !jsonString(body.planToken) || body.confirmed !== true) return response("invalid_plugin_config_plan", "Config preparation confirmation is malformed", 400);
    const captured = preparedConfigDirPlans.get(body.planToken);
    if (captured && captured.host === resolved.host && captured.device === device && captured.pluginId === id) {
      const existing = await operations.inspectIntent(body.requestId, device, resolved.host, null, "plugins", payloadHash(captured.payload), body.planToken);
      if (existing.kind === "conflict") return response("operation.request_id_reused", "This request ID is already bound to a different plugin config operation", 409);
      if (existing.kind === "match") return encodePluginOperation(existing.operation, { effect: "Config and state directories were prepared. Reopen config to inspect current files." });
    }
    const plan = plans.lookup(body.planToken);
    if (!plan) return response("plan.expired", "Config preparation plan expired; review a new plan", 409);
    if (!captured || captured.host !== resolved.host || captured.device !== device || captured.pluginId !== id || captured.revision !== plan.binding.evidence) return response("plan.scope_changed", "Config preparation plan is missing or belongs to a different scope", 409);
    const payload = plan.binding.payloadHash;
    if (plan.binding.device !== device || plan.binding.host !== resolved.host || plan.binding.targetIdentity !== `plugin:${id}:config-user-dirs` || plan.binding.capability !== "plugins" || plan.binding.session !== null) return response("plan.scope_changed", "Config preparation plan belongs to a different scope", 409);
    const currentBinding = async (): Promise<PlanBinding> => {
      const plugin = await currentPlugin(port, id);
      return binding(device, resolved.host, "plugins", { action: "prepare_plugin_user_dirs", pluginId: id, revision: plugin?.userDirsRevision ?? "missing" }, `plugin:${id}:config-user-dirs`, plugin?.userDirsRevision ?? payload);
    };
    const view = await executePlannedOperation(operations, plans, body.requestId, plan, currentBinding, async () => {
      const revision = captured.revision;
      const prepared = await port.preparePluginUserDirs!(id, revision);
      if (prepared.kind === "available") {
        preparedConfigDirs.set(keyFor(resolved.host, id), prepared.value);
        return { state: "succeeded" as const };
      }
      if (prepared.kind === "refused") return { state: "refused" as const, code: prepared.code };
      return { state: "unknown" as const, code: "plugin_user_dirs_outcome_unknown" };
    });
    return encodePluginOperation(view, { effect: "Config and state directories were prepared. Reopen config to inspect current files." });
  }
  if (tail === "config/plan") {
    if (!plans || !port.listPlugins) return unsupported("plugin config editing");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Config edits require the originating paired device", 403);
    const prepared = preparedConfigDirs.get(keyFor(resolved.host, id));
    if (!prepared) return Response.json({ ok: false, code: "plugin_config_prepare_required" }, { status: 428, headers: { "cache-control": "no-store" } });
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req, 80 * 1024)); } catch { return response("invalid_plugin_config_edit", "Config edit plan is malformed or too large", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "file", "content"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !validFileId(body.file) || !jsonString(body.content)) return response("invalid_plugin_config_edit", "Config edit plan is malformed", 400);
    const plugin = await currentPlugin(port, id);
    if (!plugin || plugin.userDirsRevision !== prepared.revision) return response("plugin_revision_changed", "Plugin metadata changed; prepare the config directory again", 409);
    const inventory = await listPluginConfigFiles(prepared.configDir);
    if (inventory.kind !== "available" || !inventory.value.some((file) => file.id === body.file)) return response("plugin_config_file_not_found", "Config file is not in the prepared plugin directory", 404);
    const current = await readPluginConfigFile(prepared.configDir, body.file);
    if (current.kind !== "available") return response("plugin_config_unavailable", current.reason, 409);
    if (body.content.length > 64 * 1024) return response("plugin_config_too_large", "Config edit exceeds 64 KiB", 413);
    try {
      JSON.parse(body.content);
      const content = restorePluginConfigSecrets(body.content, current.value.content);
      const plan = plans.issue(binding(device, resolved.host, "configuration", { pluginId: id, file: body.file, content }, `plugin:${id}:config:${body.file}`, current.value.revision));
      rememberPlan(preparedConfigEditPlans, plan.token, { host: resolved.host, device, pluginId: id, file: body.file, content, submittedContent: body.content, revision: current.value.revision });
      return Response.json({ ok: true, plan: { token: plan.token, expiresAt: plan.expiresAt, pluginId: id, file: body.file,
        effect: `Replace ${body.file} after JSON validation and create a private backup of the current file.` } }, { headers: { "cache-control": "no-store" } });
    } catch (error) { return response("invalid_plugin_config", error instanceof Error ? "Config must be valid JSON and the edit plan must be available" : "Config must be valid JSON", 400); }
  }
  if (tail === "config/apply") {
    if (!plans || !operations || !operations.isDurable || !port.listPlugins) return unsupported("durable plugin config editing");
    const device = caller.pairedIdentity();
    if (!device) return response("device.identity_required", "Config edits require the originating paired device", 403);
    let body: JsonObject | null;
    try { body = jsonObject(await boundedBody(req, 80 * 1024)); } catch { return response("invalid_plugin_config_edit", "Config edit confirmation is malformed or too large", 400); }
    if (!body || Object.keys(body).some((key) => !["requestId", "planToken", "file", "content", "confirmed"].includes(key)) || !jsonString(body.requestId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.requestId) || !jsonString(body.planToken) || !validFileId(body.file) || !jsonString(body.content) || body.confirmed !== true) return response("invalid_plugin_config_edit", "Config edit confirmation is malformed", 400);
    const fileId = body.file;
    const content = body.content;
    const captured = preparedConfigEditPlans.get(body.planToken);
    if (captured && captured.host === resolved.host && captured.device === device && captured.pluginId === id && captured.file === fileId && captured.submittedContent === content) {
      const payload = { pluginId: id, file: fileId, content: captured.content };
      const existing = await operations.inspectIntent(body.requestId, device, resolved.host, null, "configuration", payloadHash(payload), body.planToken);
      if (existing.kind === "conflict") return response("operation.request_id_reused", "This request ID is already bound to a different config operation", 409);
      if (existing.kind === "match") return encodePluginOperation(existing.operation, { effect: "Config edit completed once; read the file again to confirm the resulting revision." });
    }
    const prepared = preparedConfigDirs.get(keyFor(resolved.host, id));
    if (!prepared) return response("plugin_config_prepare_required", "Prepare the plugin config directory before editing", 428);
    const currentFile = await readPluginConfigFile(prepared.configDir, fileId);
    if (currentFile.kind !== "available") return response("plugin_config_unavailable", currentFile.reason, 409);
    const restoredContent = restorePluginConfigSecrets(content, currentFile.value.content);
    const plan = plans.lookup(body.planToken);
    if (!plan) return response("plan.expired", "Config edit plan expired; review a new plan", 409);
    if (!captured || captured.host !== resolved.host || captured.device !== device || captured.pluginId !== id || captured.file !== fileId || captured.submittedContent !== content || captured.content !== restoredContent || captured.revision !== plan.binding.evidence) return response("plan.scope_changed", "Config edit plan is missing or differs from the reviewed content", 409);
    if (plan.binding.device !== device || plan.binding.host !== resolved.host || plan.binding.targetIdentity !== `plugin:${id}:config:${fileId}` || plan.binding.capability !== "configuration" || plan.binding.session !== null || plan.binding.payloadHash !== payloadHash({ pluginId: id, file: fileId, content: restoredContent })) return response("plan.scope_changed", "Config edit differs from the reviewed plan", 409);
    const plugin = await currentPlugin(port, id);
    if (!plugin || plugin.userDirsRevision !== prepared.revision) return response("plugin_revision_changed", "Plugin metadata changed; prepare the config directory again", 409);
    const currentBinding = async (): Promise<PlanBinding> => {
      const current = await readPluginConfigFile(prepared.configDir, fileId);
      return binding(device, resolved.host, "configuration", { pluginId: id, file: fileId, content: restoredContent }, `plugin:${id}:config:${fileId}`, current.kind === "available" ? current.value.revision : "missing");
    };
    const view = await executePlannedOperation(operations, plans, body.requestId, plan, currentBinding, async () => {
      const written = await writePluginConfigFile(prepared.configDir, fileId, plan.binding.evidence, restoredContent);
      if (written.kind === "available") return { state: "succeeded" as const };
      if (written.kind === "refused") return { state: "refused" as const, code: "plugin_config_changed" };
      return { state: "unknown" as const, code: "plugin_config_write_unknown" };
    });
    return encodePluginOperation(view, { effect: "Config edit completed once; read the file again to confirm the resulting revision." });
  }
  if (tail === "enable" || tail === "disable") {
    const dispatch = tail === "enable" ? port.enablePlugin : port.disablePlugin;
    if (!dispatch) return unsupported(`plugin ${tail}`);
    const result = outcome(await dispatch.call(port, id));
    return result instanceof Response ? result : Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
  }
  if (tail === "unlink") {
    if (!port.unlinkPlugin) return unsupported("plugin unlink");
    const result = outcome(await port.unlinkPlugin(id));
    return result instanceof Response ? result : Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
  }
  if (tail?.startsWith("panes/") && tail !== "panes/open") {
    const paneId = tail.slice("panes/".length).split("/")[0]!;
    if (!/^[A-Za-z0-9:._-]{1,128}$/u.test(paneId)) return response("invalid_plugin_pane", "Plugin pane id is invalid", 400);
    const verb = tail.endsWith("/focus") ? "focus" : tail.endsWith("/close") ? "close" : null;
    const method = verb === "focus" ? port.focusPluginPane : verb === "close" ? port.closePluginPane : null;
    if (!method) return unsupported("plugin pane navigation");
    const result = outcome(await method.call(port, paneId));
    return result instanceof Response ? result : Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
  }
  if (tail?.startsWith("actions/")) {
    const body = jsonObject(await boundedBody(req));
    const actionId = tail.split("/")[1];
    if (!body || !actionId || !jsonString(body.correlationId) || !/^[A-Za-z0-9_-]{1,128}$/u.test(body.correlationId) || Object.keys(body).some((key) => key !== "correlationId")) {
      return response("invalid_plugin_action", "Plugin action request is malformed", 400);
    }
    if (!port.invokePluginAction) return unsupported("plugin action invocation");
    const result = outcome(await port.invokePluginAction(id, actionId, "native", body.correlationId));
    return result instanceof Response ? result : Response.json({ ok: true, action: result }, { headers: { "cache-control": "no-store" } });
  }
  if (tail === "panes/open") {
    const body = jsonObject(await boundedBody(req));
    const allowed = new Set(["entrypoint", "placement", "width", "height", "workspaceId", "targetPaneId", "direction", "cwd", "focus", "env"]);
    if (!body || Object.keys(body).some((key) => !allowed.has(key)) || !jsonString(body.entrypoint) || !/^[A-Za-z0-9][A-Za-z0-9:_-]{0,119}$/u.test(body.entrypoint)) return response("invalid_plugin_entrypoint", "Plugin pane request is malformed", 400);
    const placement = pluginPlacement(body.placement);
    if (body.placement !== undefined && placement === null) return response("invalid_plugin_entrypoint", "Plugin pane placement is invalid", 400);
    const directionValue = body.direction;
    if (directionValue !== undefined && (!jsonString(directionValue) || !["right", "down"].includes(directionValue))) return response("invalid_plugin_entrypoint", "Plugin pane direction is invalid", 400);
    const direction = jsonString(directionValue) ? directionValue : null;
    if (body.focus !== undefined && !jsonBoolean(body.focus)) return response("invalid_plugin_entrypoint", "Plugin pane focus option is invalid", 400);
    if (body.cwd !== undefined && (!jsonString(body.cwd) || body.cwd.length > 4096 || body.cwd.includes("\0"))) return response("invalid_plugin_entrypoint", "Plugin pane working directory is invalid", 400);
    const env: Record<string, string> = {};
    if (body.env !== undefined) {
      const envRow = jsonObject(body.env);
      if (!envRow || Object.keys(envRow).length > 32) return response("invalid_plugin_entrypoint", "Plugin pane environment is invalid", 400);
      for (const [key, value] of Object.entries(envRow)) {
        if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/u.test(key) || !jsonString(value) || value.length > 2048 || value.includes("\0")) return response("invalid_plugin_entrypoint", "Plugin pane environment is invalid", 400);
        env[key] = value;
      }
    }
    const size = (key: "width" | "height"): number | string | undefined => {
      const value = body[key];
      return jsonNumber(value) || jsonString(value) && value.length <= 128 ? value : undefined;
    };
    for (const key of ["width", "height"] as const) if (body[key] !== undefined && size(key) === undefined) return response("invalid_plugin_entrypoint", `Plugin pane ${key} is invalid`, 400);
    if (!port.openPluginPane) return unsupported("plugin pane opening");
    const options: Record<string, JsonValue> = {};
    const width = size("width"); const height = size("height");
    if (placement !== null) options.placement = placement;
    if (width !== undefined) options.width = width;
    if (height !== undefined) options.height = height;
    if (jsonString(body.workspaceId)) options.workspaceId = body.workspaceId;
    if (jsonString(body.targetPaneId)) options.targetPaneId = body.targetPaneId;
    if (jsonString(direction)) options.direction = direction;
    if (jsonString(body.cwd)) options.cwd = body.cwd;
    if (jsonBoolean(body.focus)) options.focus = body.focus;
    if (Object.keys(env).length) options.env = env;
    const result = outcome(await port.openPluginPane(id, body.entrypoint, options));
    return result instanceof Response ? result : Response.json({ ok: true, pane: result }, { headers: { "cache-control": "no-store" } });
  }
  if (tail === "install" || tail === "uninstall") return unsupported(`plugin ${tail} plan/operation`);
  return response("route.not_found", "Plugin route is not supported", 404);
}
