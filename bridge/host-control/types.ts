/** Host administration is separate from the session-local multiplexer port. */
export const HOST_CAPABILITIES = ["integrations", "plugins", "configuration", "persistence", "machines", "serverAdministration"] as const;
export type HostCapability = (typeof HOST_CAPABILITIES)[number];
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
  /** Feature handlers may narrow this typed inspection seam; it is never an arbitrary RPC dispatcher. */
  inspect(request: HostInspection): Promise<HostControlOutcome<readonly HostRecord[]>>;
}
