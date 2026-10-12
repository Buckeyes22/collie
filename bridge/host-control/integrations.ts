import { controlRoute, controlScope, payloadHash, refusal, refusalResponse, supportsNew } from "../control/contracts.ts";
import { executePlannedOperation, type PlanBinding, type PlanStore } from "../control/plans.ts";
import type { OperationStore } from "../control/operations.ts";
import type { JsonObject, JsonValue } from "../json.ts";
import { redactText } from "../redact.ts";
import type { AgentManifestSnapshot, HostCapability, HostControlOutcome, HostControlPort, HostMutationResult, IntegrationSummary, IntegrationTarget, IntegrationVersionStatus } from "./types.ts";
import { INTEGRATION_TARGETS } from "./types.ts";
import { parseHostOperationRequest, type HostOperationRequest } from "./request.ts";

export interface HostIntegrationRouteCaller {
  gate(level: "read" | "device-read" | "write"): Response | null;
  pairedIdentity(): string | null;
  resolve(): Promise<{ readonly host: string; readonly port: HostControlPort } | Response>;
  audit?(action: string, target: string, host: string, outcome: string): void;
}

type Action = "integrationInstall" | "integrationUninstall" | "lettaInstall" | "lettaUninstall" | "manifestReload" | "manifestRefresh";
type HostActionValue = readonly IntegrationSummary[] | IntegrationVersionStatus | HostMutationResult | AgentManifestSnapshot;
interface ActionSpec {
  readonly action: Action;
  readonly capability: HostCapability;
  readonly targetIdentity: string;
  readonly payload: JsonValue;
  readonly evidence: (port: HostControlPort) => Promise<string | null>;
  readonly dispatch: (port: HostControlPort) => Promise<HostControlOutcome<HostActionValue>>;
}

function integrationJson(value: IntegrationSummary): JsonObject {
  const row: JsonObject = { target: value.target, label: value.label, command: value.command, available: value.available, state: value.state };
  if (Object.prototype.hasOwnProperty.call(value, "installedVersion")) row.installedVersion = value.installedVersion;
  if (Object.prototype.hasOwnProperty.call(value, "requiredVersion")) row.requiredVersion = value.requiredVersion;
  if (Object.prototype.hasOwnProperty.call(value, "versionStatus")) row.versionStatus = value.versionStatus;
  return row;
}
function manifestSnapshotJson(value: AgentManifestSnapshot): JsonObject {
  const snapshot: JsonObject = {
    manifests: value.manifests.map((manifest) => ({
      agent: manifest.agent, source: manifest.source, sourceKind: manifest.sourceKind,
      activeVersion: manifest.activeVersion, cachedRemoteVersion: manifest.cachedRemoteVersion,
      localOverrideShadowingRemote: manifest.localOverrideShadowingRemote,
      remoteLastCheckedUnix: manifest.remoteLastCheckedUnix, remoteUpdateResult: manifest.remoteUpdateResult,
      remoteUpdateError: manifest.remoteUpdateError, warning: manifest.warning,
    })),
  };
  if (Object.prototype.hasOwnProperty.call(value, "lastCheckUnix")) snapshot.lastCheckUnix = value.lastCheckUnix;
  if (Object.prototype.hasOwnProperty.call(value, "lastResult")) snapshot.lastResult = value.lastResult;
  return snapshot;
}
function isIntegrationList(value: IntegrationSummary | readonly IntegrationSummary[] | IntegrationVersionStatus | AgentManifestSnapshot): value is readonly IntegrationSummary[] {
  return Array.isArray(value);
}
function jsonValue(value: IntegrationSummary | readonly IntegrationSummary[] | IntegrationVersionStatus | AgentManifestSnapshot): JsonValue {
  if (isIntegrationList(value)) return value.map(integrationJson);
  if ("manifests" in value) return manifestSnapshotJson(value);
  if ("available" in value) return integrationJson(value);
  return { target: value.target, state: value.state, installedVersion: value.installedVersion, requiredVersion: value.requiredVersion };
}
async function boundedJson(req: Request, maximum: number): Promise<JsonValue | null> {
  const declared = req.headers.get("content-length");
  if (declared !== null && (!/^\d+$/u.test(declared) || Number(declared) > maximum)) throw new Error("body too large");
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximum) { await reader.cancel(); throw new Error("body too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  if (size === 0) return null;
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  // SAFETY: JSON.parse returns a JSON-compatible value; no fields are trusted before parseHostOperationRequest validates them.
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as JsonValue;
}
function clean(value: string): string {
  return Array.from(redactText(value), (character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 0x1f || code === 0x7f ? " " : character;
  }).join("").slice(0, 1024);
}
function isIntegrationTarget(value: string): value is IntegrationTarget {
  return INTEGRATION_TARGETS.some((target) => target === value);
}
function errorResponse(code: string, message: string, status: number, factor: "scope" | "capability" | "operation" | "device" = "capability"): Response {
  return refusalResponse(refusal(code, message, factor), status);
}
function capabilityFor(action: Action): HostCapability {
  switch (action) {
    case "integrationInstall": return "integrationInstall";
    case "integrationUninstall": return "integrationUninstall";
    case "lettaInstall": case "lettaUninstall": return "experimentalLettaIntegration";
    case "manifestReload": return "manifestReload";
    case "manifestRefresh": return "manifestRefresh";
  }
}

async function integrationEvidence(port: HostControlPort, target: IntegrationTarget): Promise<string | null> {
  const result = await port.listIntegrations();
  if (result.kind !== "available") return null;
  const row = result.value.find((item) => item.target === target);
  return row === undefined ? null : payloadHash(jsonValue(row));
}
async function manifestEvidence(port: HostControlPort): Promise<string | null> {
  const result = await port.agentManifests();
  return result.kind === "available" ? payloadHash(jsonValue(result.value)) : null;
}
function specFor(path: string): ActionSpec | null {
  const match = /^\/api\/host\/integrations\/([a-z_]+)\/(install|uninstall)(?:\/plan)?$/.exec(path);
  if (match) {
    const target = match[1]!;
    const mode = match[2]!;
    if (target === "letta") {
      const action: Action = mode === "install" ? "lettaInstall" : "lettaUninstall";
      return { action, capability: capabilityFor(action), targetIdentity: "integration:letta", payload: { action, target: "letta" }, evidence: async (port) => {
        const result = await port.lettaIntegrationStatus();
        return result.kind === "available" ? payloadHash(jsonValue(result.value)) : null;
      }, dispatch: (port) => mode === "install" ? port.installExperimentalLetta() : port.uninstallExperimentalLetta() };
    }
    if (!isIntegrationTarget(target)) return null;
    const integrationTarget = target;
    const action: Action = mode === "install" ? "integrationInstall" : "integrationUninstall";
    return {
      action, capability: capabilityFor(action), targetIdentity: `integration:${integrationTarget}`,
      payload: { action, target: integrationTarget },
      evidence: (port) => integrationEvidence(port, integrationTarget),
      dispatch: (port) => mode === "install" ? port.installIntegration(integrationTarget) : port.uninstallIntegration(integrationTarget),
    };
  }
  const manifest = /^\/api\/host\/agent-manifests\/(reload|refresh)(?:\/plan)?$/.exec(path);
  if (manifest) {
    const action: Action = manifest[1] === "reload" ? "manifestReload" : "manifestRefresh";
    return {
      action, capability: capabilityFor(action), targetIdentity: "agent-manifests",
      payload: { action }, evidence: manifestEvidence,
      dispatch: (port) => manifest[1] === "reload" ? port.reloadAgentManifests() : port.refreshAgentManifests(),
    };
  }
  return null;
}

function effect(action: Action, target: string): string {
  if (action === "integrationInstall") return `Install the ${target} integration for agents on this host. Herdr may update only its own hook entries and integration files.`;
  if (action === "integrationUninstall") return `Remove the ${target} integration from agents on this host. Herdr removes only its own hook entries and integration files.`;
  if (action === "lettaInstall") return "Install the experimental Letta integration using Herdr's CLI-only integration command.";
  if (action === "lettaUninstall") return "Remove the experimental Letta integration using Herdr's CLI-only integration command.";
  if (action === "manifestReload") return "Reload the cached agent detection manifests into the running Herdr server. This does not fetch updates.";
  return "Fetch agent detection manifest updates and reload them in the running Herdr server.";
}

function operationReply(operation: Awaited<ReturnType<OperationStore["execute"]>>): Response {
  // The receipt is the authoritative answer for every outcome, including refusal and uncertainty.
  // Keeping it in a normal JSON response lets the native client preserve the one-shot request ID.
  return Response.json({ ok: operation.state === "succeeded", operation }, { headers: { "cache-control": "no-store" } });
}

/** Narrow host-only admin API. All request routing stays through paired Collie HTTP and Crew. */
export async function serveHostIntegrationRoute(req: Request, url: URL, caller: HostIntegrationRouteCaller,
  plans: PlanStore, operations: OperationStore): Promise<Response | null> {
  const contract = controlRoute(url.pathname, req.method);
  if (!contract || !contract.name.startsWith("host-")) return null;
  const denied = caller.gate(contract.gate);
  if (denied) return denied;
  if (!controlScope(url, "host")) return errorResponse("scope.malformed", "Malformed host administration scope", 400, "scope");
  const device = caller.pairedIdentity();
  if (!device || !/^[a-f0-9]{64}$/u.test(device)) return errorResponse("device.identity_required", "Host administration requires the originating paired device identity", 403, "device");
  const resolved = await caller.resolve();
  if (resolved instanceof Response) return resolved;
  const { host, port } = resolved;
  await port.prepare?.();

  if (contract.name === "host-integrations" || contract.name === "host-integration-status") {
    if (!supportsNew(port.capabilities, "integrations")) return errorResponse("host.integrations_unsupported", "Integration management is unsupported on this host", 501);
    const result = await port.listIntegrations();
    if (result.kind === "unsupported") return errorResponse("capability.unsupported", clean(result.reason), 501);
    if (result.kind !== "available") return errorResponse("host.unavailable", clean(result.reason), 503, "operation");
    const versionStatus = result.value.every((item) => item.versionStatus === "available") ? "available"
      : result.value.some((item) => item.versionStatus === "partial") ? "partial" : "unavailable";
    const integrations = contract.name === "host-integrations"
      ? result.value.map(({ installedVersion: _installed, requiredVersion: _required, versionStatus: _status, ...row }) => row)
      : result.value;
    return Response.json({ ok: true, integrations, status: "authoritative", versionStatus: contract.name === "host-integrations" ? "unavailable" : versionStatus }, { headers: { "cache-control": "no-store" } });
  }
  if (contract.name === "host-agent-manifests") {
    if (!supportsNew(port.capabilities, "agentManifests")) return errorResponse("host.manifests_unsupported", "Agent manifest status is unsupported on this host", 501);
    const result = await port.agentManifests();
    if (result.kind === "unsupported") return errorResponse("capability.unsupported", clean(result.reason), 501);
    if (result.kind !== "available") return errorResponse("host.unavailable", clean(result.reason), 503, "operation");
    return Response.json({ ok: true, status: cleanSnapshot(result.value) }, { headers: { "cache-control": "no-store" } });
  }

  const spec = specFor(url.pathname);
  if (!spec) return errorResponse("host.target_unsupported", "This integration target or host operation is unsupported", 501);
  if (!supportsNew(port.capabilities, spec.capability)) return errorResponse("capability.unsupported", "This host operation is unsupported by the selected Herdr runtime", 501);
  const bindingFor = async (): Promise<PlanBinding | null> => {
    const evidence = await spec.evidence(port);
    if (evidence === null) return null;
    return { device, host, session: null, capability: spec.action, payloadHash: payloadHash(spec.payload), targetIdentity: spec.targetIdentity, evidence };
  };
  const isPlan = contract.name.endsWith("-plan");
  if (isPlan) {
    if (req.body !== null) return errorResponse("operation.malformed", "Plan requests must not contain a body", 400, "operation");
    const binding = await bindingFor();
    if (binding === null) return errorResponse("host.target_unavailable", "The current target state could not be verified; nothing changed", 503, "operation");
    try {
      const plan = plans.issue(binding);
      return Response.json({ ok: true, plan: { token: plan.token, expiresAt: plan.expiresAt, effect: effect(spec.action, spec.targetIdentity.replace(/^integration:/u, "")), targetIdentity: spec.targetIdentity } }, { headers: { "cache-control": "no-store" } });
    } catch { return errorResponse("operation.plan_unavailable", "A safe operation plan could not be created", 503, "operation"); }
  }

  let body: JsonValue | null;
  try { body = await boundedJson(req, 8 * 1024); } catch { return errorResponse("operation.malformed", "Malformed or oversized operation request", 400, "operation"); }
  const record: HostOperationRequest | null = parseHostOperationRequest(body);
  if (record === null) {
    return errorResponse("operation.malformed", "A request ID, current plan, and explicit confirmation are required", 400, "operation");
  }
  if (!operations.isDurable || !operations.isReady()) return errorResponse("operation.unavailable", "Durable operation receipts are unavailable; no host change was attempted", 503, "operation");
  const existing = await operations.inspectIntent(record.requestId, device, host, null, spec.action, payloadHash(spec.payload), record.planToken);
  if (existing.kind === "match") return operationReply(existing.operation);
  if (existing.kind === "conflict") return errorResponse("operation.request_id_reused", "This request ID is already bound to a different host action", 409, "operation");
  const plan = plans.lookup(record.planToken);
  if (!plan) return errorResponse("plan.expired", "The operation plan expired; review the current target again", 409, "operation");
  if (plan.binding.device !== device || plan.binding.host !== host || plan.binding.session !== null || plan.binding.capability !== spec.action || plan.binding.payloadHash !== payloadHash(spec.payload) || plan.binding.targetIdentity !== spec.targetIdentity) {
    return errorResponse("plan.changed", "The operation plan belongs to a different target or intent", 409, "operation");
  }
  try {
    const current = async () => await bindingFor() ?? { ...plan.binding, evidence: "unavailable" };
    const operation = await executePlannedOperation(operations, plans, record.requestId, plan, current, async () => {
      const outcome = await spec.dispatch(port);
      if (outcome.kind === "available") return { state: "succeeded" };
      if (outcome.kind === "refused" || outcome.kind === "unsupported") return { state: "refused", code: clean(outcome.kind === "refused" ? outcome.code : "capability.unsupported") };
      return { state: "unknown", code: "operation.outcome_unknown" };
    });
    caller.audit?.(spec.action, spec.targetIdentity, host, operation.state);
    return operationReply(operation);
  } catch (error) {
    const message = error instanceof Error ? error.message : "operation unavailable";
    const status = /request_id_reused/u.test(message) ? 409 : 503;
    return errorResponse(/request_id_reused/u.test(message) ? "operation.request_id_reused" : "operation.unavailable", "The host operation could not be safely reconciled; inspect its status before trying again", status, "operation");
  }
}

function cleanSnapshot(value: AgentManifestSnapshot): AgentManifestSnapshot {
  return {
    ...value,
    lastResult: value.lastResult === undefined || value.lastResult === null ? value.lastResult : clean(value.lastResult),
    manifests: value.manifests.map((manifest) => ({ ...manifest,
      source: clean(manifest.source), sourceKind: clean(manifest.sourceKind),
      remoteUpdateResult: manifest.remoteUpdateResult === null ? null : clean(manifest.remoteUpdateResult),
      remoteUpdateError: manifest.remoteUpdateError === null ? null : clean(manifest.remoteUpdateError),
      warning: manifest.warning === null ? null : clean(manifest.warning),
    })),
  };
}
