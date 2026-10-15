/** Neutral media boundary shared with B34. It carries no vendor RPC or recorder policy. */

export type MediaMimeType = "image/png" | "image/jpeg" | "image/webp";
export type MediaProducerId = "pi.journal" | "claude.journal" | "muse.journal" | "opencode.journal";
export type MediaProvenance = "vendor-native" | "verified-adapter-derived" | "collie-rendered-observation";

export interface MediaSourceIdentity {
  readonly producer: MediaProducerId;
  readonly kind: string;
  readonly adapterVersion: string | null;
}

export interface MediaLimits {
  readonly maxImageBytes: number;
  readonly maxPixels: number;
  readonly maxItems: number;
  readonly maxSessionBytes: number;
}

/** The opaque source handle and generation are issued by the owner port after source admission. */
export interface RegisteredMediaSource {
  readonly handle: string;
  readonly generation: number;
}

/** All authority-bearing fields are supplied by B34's owning runtime observer, never an adapter. */
export interface MediaBinding {
  readonly source: RegisteredMediaSource;
  readonly ownerHost: string;
  readonly runtime: string;
  readonly session: string;
  readonly paneId: string;
  readonly occupantFingerprint: string;
  readonly occupantGeneration: number;
  readonly producerSequence: number;
  readonly observationRef?: string;
}

export interface MediaMetadata {
  readonly mimeType: MediaMimeType;
  readonly provenance: "vendor-native" | "verified-adapter-derived";
  readonly harness: string;
  readonly journalEntry: string;
  readonly partIndex: number;
  readonly sha256: string;
}

export interface RegisteredMedia {
  /** Opaque owner-generated media reference, suitable for the existing scoped blob route. */
  readonly reference: string;
}

/** B34 implements this port with owner-only ingestion, quotas, durable custody and revoke. */
export interface MediaProducerPort {
  register(
    source: MediaSourceIdentity,
    allowedTypes: readonly MediaMimeType[],
    limits: MediaLimits,
  ): Promise<RegisteredMediaSource>;
  submit(binding: MediaBinding, bytes: Uint8Array, metadata: MediaMetadata): Promise<RegisteredMedia>;
  revoke(source: MediaSourceIdentity): Promise<void>;
}

export const JOURNAL_MEDIA_LIMITS: MediaLimits = Object.freeze({
  maxImageBytes: 10 * 1024 * 1024,
  maxPixels: 20_000_000,
  maxItems: 32,
  maxSessionBytes: 100 * 1024 * 1024,
});

