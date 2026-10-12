/** Host administration is separate from the session-local multiplexer port. */
export const HOST_CAPABILITIES = [
  "integrations",
  "integrationInstall",
  "integrationUninstall",
  "experimentalLettaIntegration",
  "agentManifests",
  "manifestReload",
  "manifestRefresh",
  "plugins",
  "configuration",
  "persistence",
  "machines",
  "serverAdministration",
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
}
