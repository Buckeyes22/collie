/** Host administration is separate from the session-local multiplexer port. */
import type { JsonValue } from "../json.ts";
export const HOST_CAPABILITIES = [
  "integrations",
  "integrationInstall",
  "integrationUninstall",
  "experimentalLettaIntegration",
  "agentManifests",
  "manifestReload",
  "manifestRefresh",
  "plugins",
  "pluginOwnerOperations",
  "configuration",
  "persistence",
  "machines",
  "serverAdministration",
  "agentViews",
  "hostUiActions",
  "hostCommands",
] as const;
export type HostCapability = (typeof HOST_CAPABILITIES)[number];

export const INTEGRATION_TARGETS = [
  "pi", "omp", "claude", "codex", "copilot", "devin", "droid", "kimi", "opencode", "kilo",
  "hermes", "qodercli", "qwen", "cursor", "mastracode", "antigravity_cli", "grok",
] as const;
export type IntegrationTarget = (typeof INTEGRATION_TARGETS)[number];
export type IntegrationState = "not_installed" | "current" | "outdated" | "needs_repair";
export interface IntegrationVersionStatus {
  readonly target: IntegrationTarget | "letta";
  readonly state: IntegrationState;
  readonly installedVersion: string | null;
  readonly requiredVersion: string | null;
}
export interface IntegrationSummary {
  readonly target: IntegrationTarget;
  readonly label: string;
  /** Display-safe executable name; absolute paths and arguments are never returned. */
  readonly command: string;
  readonly available: boolean;
  readonly state: IntegrationState;
  readonly installedVersion?: string | null;
  readonly requiredVersion?: string | null;
  readonly versionStatus?: "available" | "unavailable" | "partial";
}
export interface AgentManifestSummary {
  readonly agent: string;
  readonly source: string;
  readonly sourceKind: string;
  readonly activeVersion: string | null;
  readonly cachedRemoteVersion: string | null;
  readonly localOverrideShadowingRemote: boolean;
  readonly remoteLastCheckedUnix: string | null;
  readonly remoteUpdateResult: string | null;
  readonly remoteUpdateError: string | null;
  readonly warning: string | null;
}
export interface AgentManifestSnapshot {
  readonly manifests: readonly AgentManifestSummary[];
  readonly lastCheckUnix?: string | null;
  readonly lastResult?: string | null;
}
export interface HostMutationResult {
  readonly messages: readonly string[];
}

export type HostPluginContext = "global" | "workspace" | "tab" | "pane" | "selection";
export type HostPluginPlatform = "linux" | "macos" | "windows";
export type HostPluginPlacement = "overlay" | "popup" | "split" | "tab" | "zoomed";
/** Safe, manifest-derived plugin data. Host paths and executable argv are deliberately absent. */
export interface HostPluginActionDescriptor {
  readonly id: string;
  readonly qualifiedId: string;
  readonly pluginId: string;
  readonly actionId: string;
  readonly title: string;
  readonly description: string | null;
  readonly contexts: readonly HostPluginContext[];
  readonly platforms: readonly HostPluginPlatform[] | null;
  readonly enabled: boolean;
  readonly platformSupported: boolean;
}
export interface HostPluginPaneDescriptor {
  readonly id: string;
  readonly title: string;
  readonly description: string | null;
  readonly platforms: readonly HostPluginPlatform[] | null;
  readonly placement: HostPluginPlacement;
  readonly width: string | number | null;
  readonly height: string | number | null;
}
export interface HostPluginSummary {
  readonly id: string;
  readonly name: string;
  readonly version: string;
  readonly minimumHerdrVersion: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly platforms: readonly HostPluginPlatform[] | null;
  readonly source: { readonly kind: "local" | "github"; readonly owner: string | null; readonly repo: string | null; readonly subdir: string | null; readonly requestedRef: string | null; readonly resolvedCommit: string | null };
  readonly actions: readonly HostPluginActionDescriptor[];
  readonly panes: readonly HostPluginPaneDescriptor[];
  readonly warnings: readonly string[];
  /** Producer-private revision used only to bind an explicit config-directory preparation plan. */
  readonly userDirsRevision?: string | null;
}
export interface HostPluginUserDirsPrepared {
  readonly pluginId: string;
  readonly revision: string;
  readonly configDir: string;
  readonly stateDir: string;
  readonly createdConfigDir: boolean;
  readonly createdStateDir: boolean;
  readonly seededLegacyConfig: boolean;
}
export interface HostPluginLog {
  readonly id: string;
  readonly pluginId: string;
  readonly actionId: string | null;
  readonly event: string | null;
  readonly status: "running" | "succeeded" | "failed";
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly exitCode: number | null;
  readonly stdout: string | null;
  readonly stderr: string | null;
  readonly error: string | null;
}
export interface HostPluginActionResult { readonly pluginId: string; readonly actionId: string; readonly logId: string | null; readonly status: "running" | "succeeded" | "failed" }
export interface HostPluginPaneResult { readonly pluginId: string; readonly entrypoint: string; readonly paneId: string | null; readonly placement: HostPluginPlacement }
export interface HostPluginLinkSelection { readonly id: string; readonly pluginId: string; readonly name: string; readonly version: string }
export interface HostPluginOwnerContext { readonly revision: string; readonly linkSelections: readonly HostPluginLinkSelection[] }
export interface HostPluginInstallPlanInput { readonly owner: string; readonly repo: string; readonly subdir: string | null; readonly ref: string | null }
/** Private host-side install plan; raw declared argv remains bridge-private until sanitized for review. */
export interface HostPluginInstallPlan { readonly id: string; readonly ownerRevision: string; readonly plugin: HostPluginSummary; readonly source: { readonly owner: string; readonly repo: string; readonly subdir: string | null; readonly ref: string | null; readonly commit: string }; readonly checksum: string; readonly buildCommands: readonly (readonly string[])[]; readonly startupCommands: readonly (readonly string[])[] }
export interface HostPluginUninstallPlan { readonly id: string; readonly ownerRevision: string; readonly pluginId: string; readonly revision: string; readonly removeManagedCheckout: boolean; readonly preservesConfigState: true }
export interface HostPluginOwnerOperation { readonly requestId: string; readonly state: "running" | "succeeded" | "refused" | "unknown"; readonly plugin: HostPluginSummary | null; readonly removed: boolean; readonly code: string | null }
export interface HostPluginConfigSnapshot { readonly pluginId: string; readonly revision: string; readonly schema: JsonValue | null; readonly value: JsonValue | null; readonly editable: boolean; readonly reason: string | null }
export type HostPluginInvocationSource = "native" | "command-palette" | "api";
export type HostUiCommandContextKind = "pane" | "selection" | "tab" | "workspace";
export interface HostUiCommandDescriptor { readonly id: string; readonly label: string; readonly effect: string; readonly contexts: readonly HostUiCommandContextKind[]; readonly enabled: boolean }
export interface HostUiCommandContext { readonly paneId?: string; readonly tabId?: string; readonly workspaceId?: string; readonly selection?: string }
export interface HostUiActionControl {
  setWindowTitle(title: string): Promise<HostControlOutcome<void>>;
  clearWindowTitle(): Promise<HostControlOutcome<void>>;
  setSurfaceInterest(active: boolean): Promise<HostControlOutcome<void>>;
  showNotification(input: { readonly title: string; readonly body?: string | null; readonly position?: "top-left" | "top-right" | "bottom-left" | "bottom-right" | null; readonly sound?: "none" | "done" | "request" }): Promise<HostControlOutcome<void>>;
  closePopup(): Promise<HostControlOutcome<void>>;
  dismissAnnouncement(id: string, version: string): Promise<HostControlOutcome<void>>;
  dismissReleaseNotes(version: string): Promise<HostControlOutcome<void>>;
  listBuiltInCommands(): Promise<HostControlOutcome<readonly HostUiCommandDescriptor[]>>;
  invokeBuiltInCommand(id: string, context: HostUiCommandContext): Promise<HostControlOutcome<void>>;
}

import type { AgentViewRequest } from "../control/metadata.ts";
export type HostAgentViewOutcome =
  | { readonly kind: "available"; readonly active: boolean; readonly source: string | null; readonly label: string | null; readonly view?: AgentViewRequest | null }
  | { readonly kind: "unsupported"; readonly reason: string }
  | { readonly kind: "refused"; readonly code: string; readonly reason: string }
  | { readonly kind: "unavailable"; readonly reason: string };
export interface AgentViewHostControl {
  get(source: string): Promise<HostAgentViewOutcome>;
  set(request: AgentViewRequest): Promise<HostAgentViewOutcome>;
  clear(source?: string): Promise<HostAgentViewOutcome>;
}
export interface HostInspection { readonly capability: HostCapability; readonly objectId?: string }
/** Server-owned opaque IDs only; no filesystem roots, argv or credential-bearing records. */
export interface HostRecord { readonly id: string; readonly label: string; readonly state: string }
export type HostControlOutcome<T> =
  | { readonly kind: "available"; readonly value: T }
  | { readonly kind: "unsupported"; readonly capability: HostCapability; readonly reason: string }
  | { readonly kind: "refused"; readonly code: string; readonly reason: string }
  | { readonly kind: "unavailable"; readonly reason: string };
export interface HostControlPort {
  readonly provider: string;
  readonly capabilities: Readonly<Record<HostCapability, boolean>>;
  readonly notes: Readonly<Partial<Record<HostCapability, string>>>;
  prepare?(): Promise<void>;
  listIntegrations(): Promise<HostControlOutcome<readonly IntegrationSummary[]>>;
  /** Bounded CLI status for the experimental target omitted from Herdr's socket enum. */
  lettaIntegrationStatus(): Promise<HostControlOutcome<IntegrationVersionStatus>>;
  installIntegration(target: IntegrationTarget): Promise<HostControlOutcome<HostMutationResult>>;
  uninstallIntegration(target: IntegrationTarget): Promise<HostControlOutcome<HostMutationResult>>;
  installExperimentalLetta(): Promise<HostControlOutcome<HostMutationResult>>;
  uninstallExperimentalLetta(): Promise<HostControlOutcome<HostMutationResult>>;
  agentManifests(): Promise<HostControlOutcome<AgentManifestSnapshot>>;
  reloadAgentManifests(): Promise<HostControlOutcome<AgentManifestSnapshot>>;
  refreshAgentManifests(): Promise<HostControlOutcome<AgentManifestSnapshot>>;
  /** Feature handlers may narrow this typed inspection seam; it is never an arbitrary RPC dispatcher. */
  inspect(request: HostInspection): Promise<HostControlOutcome<readonly HostRecord[]>>;
  listPlugins?(pluginId?: string): Promise<HostControlOutcome<readonly HostPluginSummary[]>>;
  listPluginActions?(pluginId?: string): Promise<HostControlOutcome<readonly HostPluginActionDescriptor[]>>;
  listPluginLogs?(pluginId: string, limit: number): Promise<HostControlOutcome<readonly HostPluginLog[]>>;
  enablePlugin?(pluginId: string): Promise<HostControlOutcome<HostMutationResult>>;
  disablePlugin?(pluginId: string): Promise<HostControlOutcome<HostMutationResult>>;
  invokePluginAction?(pluginId: string, actionId: string, invocationSource: HostPluginInvocationSource, correlationId: string): Promise<HostControlOutcome<HostPluginActionResult>>;
  openPluginPane?(pluginId: string, entrypoint: string, options?: Readonly<Record<string, JsonValue>>): Promise<HostControlOutcome<HostPluginPaneResult>>;
  focusPluginPane?(paneId: string): Promise<HostControlOutcome<HostMutationResult>>;
  closePluginPane?(paneId: string): Promise<HostControlOutcome<HostMutationResult>>;
  unlinkPlugin?(pluginId: string): Promise<HostControlOutcome<HostMutationResult>>;
  listPluginLinkSelections?(): Promise<HostControlOutcome<readonly HostPluginLinkSelection[]>>;
  pluginOwnerContext?(): Promise<HostControlOutcome<HostPluginOwnerContext>>;
  planPluginInstall?(input: HostPluginInstallPlanInput): Promise<HostControlOutcome<HostPluginInstallPlan>>;
  applyPluginInstall?(planId: string, checksum: string, ownerRevision: string, requestId: string): Promise<HostControlOutcome<HostPluginOwnerOperation>>;
  linkPlugin?(selectionId: string, ownerRevision: string, requestId: string): Promise<HostControlOutcome<HostPluginOwnerOperation>>;
  planPluginUninstall?(pluginId: string): Promise<HostControlOutcome<HostPluginUninstallPlan>>;
  uninstallPlugin?(planId: string, revision: string, ownerRevision: string, requestId: string): Promise<HostControlOutcome<HostPluginOwnerOperation>>;
  getPluginOwnerOperation?(requestId: string): Promise<HostControlOutcome<HostPluginOwnerOperation>>;
  pluginConfig?(pluginId: string): Promise<HostControlOutcome<HostPluginConfigSnapshot>>;
  preparePluginUserDirs?(pluginId: string, expectedRevision: string): Promise<HostControlOutcome<HostPluginUserDirsPrepared>>;
  readonly agentViews?: AgentViewHostControl;
  readonly uiActions?: HostUiActionControl;
}
