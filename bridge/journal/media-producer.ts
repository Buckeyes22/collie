import { createHash } from "node:crypto";

import type { TranscriptEntry, TranscriptPart } from "./types.ts";
import {
  JOURNAL_MEDIA_LIMITS,
  type MediaBinding,
  type MediaMetadata,
  type MediaMimeType,
  type MediaProducerId,
  type MediaProducerPort,
  type MediaSourceIdentity,
  type RegisteredMediaSource,
} from "../media/types.ts";

const ALLOWED_TYPES: readonly MediaMimeType[] = ["image/png", "image/jpeg", "image/webp"];
const INLINE_URI = /^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]*={0,2})$/;
const BLOB_URI = /^\/api\/blobs\/([0-9a-f]{64})$/;
const MAX_ENCODED_BYTES = Math.ceil(JOURNAL_MEDIA_LIMITS.maxImageBytes / 3) * 4;

export type TrustedBlobReader = (hash: string, maxBytes: number) => Promise<Uint8Array | null>;
export type BindingForImage = (entry: TranscriptEntry, partIndex: number) => MediaBinding;

export interface JournalImageRefusal {
  entry: string;
  partIndex: number;
  reason: "malformed-reference" | "unsupported-mime" | "oversized" | "invalid-base64" |
    "signature-mismatch" | "trusted-blob-unavailable" | "owner-refused";
}

export interface JournalMediaPublishResult {
  entry: TranscriptEntry;
  refusals: JournalImageRefusal[];
  published: number;
}

export interface JournalMediaProducer {
  readonly source: MediaSourceIdentity;
  register(): Promise<RegisteredMediaSource>;
  publish(entry: TranscriptEntry, bindingFor: BindingForImage): Promise<JournalMediaPublishResult>;
  revoke(): Promise<void>;
}

const SOURCE_BY_AGENT = {
  pi: "pi.journal",
  omp: "pi.journal",
  claude: "claude.journal",
  muse: "muse.journal",
  opencode: "opencode.journal",
} satisfies Readonly<Record<string, MediaProducerId>>;

/**
 * B09's journal producers do bounded format extraction only. The owner supplies the registered
 * source and every authority-bearing binding; this adapter cannot choose a host, session, pane,
 * occupant generation, sequence, retention rule or media id.
 */
export function createJournalMediaProducer(
  agent: string,
  adapterVersion: string | null,
  port: MediaProducerPort,
  readTrustedBlob: TrustedBlobReader,
): JournalMediaProducer | null {
  if (!isJournalImageAgent(agent)) return null;
  const producer = SOURCE_BY_AGENT[agent];
  const source: MediaSourceIdentity = { producer, kind: agent, adapterVersion };
  let registration: Promise<RegisteredMediaSource> | null = null;
  const register = () => {
    if (registration === null) {
      registration = port.register(source, ALLOWED_TYPES, JOURNAL_MEDIA_LIMITS).catch(() => {
        registration = null;
        throw new Error("The owner did not register this journal media source.");
      });
    }
    return registration;
  };
  return {
    source,
    register,
    revoke: async () => {
      registration = null;
      await port.revoke(source);
    },
    publish: async (entry, bindingFor) => {
      const parts = entry.parts.slice();
      const refusals: JournalImageRefusal[] = [];
      let published = 0;
      let partCount = 0;
      for (let partIndex = 0; partIndex < parts.length; partIndex++) {
        const part = parts[partIndex]!;
        const parsed = imageAddress(part);
        if (parsed === null) continue;
        if ("reason" in parsed) {
          refusals.push({ entry: entry.uuid, partIndex, reason: parsed.reason });
          parts[partIndex] = refusedImage(part);
          continue;
        }
        const address = parsed.address;
        if (++partCount > JOURNAL_MEDIA_LIMITS.maxItems) {
          refusals.push({ entry: entry.uuid, partIndex, reason: "owner-refused" });
          parts[partIndex] = refusedImage(part);
          continue;
        }
        try {
          const sourceHandle = await register();
          const binding = bindingFor(entry, partIndex);
          if (binding.source.handle !== sourceHandle.handle || binding.source.generation !== sourceHandle.generation) {
            refusals.push({ entry: entry.uuid, partIndex, reason: "owner-refused" });
            parts[partIndex] = refusedImage(part);
            continue;
          }
          const extracted = await extractBytes(address, readTrustedBlob);
          if ("reason" in extracted) {
            refusals.push({ entry: entry.uuid, partIndex, reason: extracted.reason });
            parts[partIndex] = refusedImage(part);
            continue;
          }
          const metadata: MediaMetadata = {
            mimeType: extracted.mimeType,
            provenance: extracted.provenance,
            harness: agent,
            journalEntry: entry.uuid,
            partIndex,
            sha256: createHash("sha256").update(extracted.bytes).digest("hex"),
          };
          const media = await port.submit(binding, extracted.bytes, metadata);
          if (!isCanonicalBlobReference(media.reference)) {
            refusals.push({ entry: entry.uuid, partIndex, reason: "owner-refused" });
            parts[partIndex] = refusedImage(part);
            continue;
          }
          parts[partIndex] = replaceImageReference(part, media.reference);
          published++;
        } catch {
          refusals.push({ entry: entry.uuid, partIndex, reason: "owner-refused" });
          parts[partIndex] = refusedImage(part);
        }
      }
      return { entry: { ...entry, parts }, refusals, published };
    },
  };
}

type ImageAddress =
  | { kind: "inline"; declared: MediaMimeType; encoded: string }
  | { kind: "blob"; hash: string };

function imageAddress(part: TranscriptPart): { address: ImageAddress } | { reason: JournalImageRefusal["reason"] } | null {
  const value = part.kind === "image" ? part.url : part.kind === "tool" ? part.result?.imageUrl : undefined;
  if (value === undefined) return null;
  if (value.length > MAX_ENCODED_BYTES + 64) return { reason: "oversized" };
  const inline = INLINE_URI.exec(value);
  if (inline !== null) {
    if (part.kind === "image" && part.mimeType !== undefined && part.mimeType !== inline[1]) return { reason: "unsupported-mime" };
    const declared = parseMime(inline[1] ?? "");
    if (declared === null) return { reason: "unsupported-mime" };
    return { address: { kind: "inline", declared, encoded: inline[2]! } };
  }
  if (value.startsWith("data:image/")) {
    const mime = /^data:([^;,]+)/.exec(value)?.[1];
    return { reason: mime === undefined || parseMime(mime) === null ? "unsupported-mime" : "invalid-base64" };
  }
  const blob = BLOB_URI.exec(value);
  if (blob !== null) return { address: { kind: "blob", hash: blob[1]! } };
  return { reason: "malformed-reference" };
}

function parseMime(value: string): MediaMimeType | null {
  return ALLOWED_TYPES.find((mime) => mime === value) ?? null;
}

function isJournalImageAgent(value: string): value is keyof typeof SOURCE_BY_AGENT {
  return Object.hasOwn(SOURCE_BY_AGENT, value);
}

async function extractBytes(
  address: ImageAddress,
  readTrustedBlob: TrustedBlobReader,
): Promise<{ bytes: Uint8Array; mimeType: MediaMimeType; provenance: "vendor-native" } | { reason: JournalImageRefusal["reason"] }> {
  let bytes: Uint8Array;
  let declared: MediaMimeType | null;
  if (address.kind === "inline") {
    if (address.encoded.length > MAX_ENCODED_BYTES || address.encoded.length % 4 !== 0 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(address.encoded)) {
      return { reason: "invalid-base64" };
    }
    bytes = Buffer.from(address.encoded, "base64");
    if (bytes.byteLength > JOURNAL_MEDIA_LIMITS.maxImageBytes || Buffer.from(bytes).toString("base64") !== address.encoded) {
      return { reason: "oversized" };
    }
    declared = address.declared;
  } else {
    bytes = await readTrustedBlob(address.hash, JOURNAL_MEDIA_LIMITS.maxImageBytes) ?? new Uint8Array();
    if (bytes.byteLength === 0) return { reason: "trusted-blob-unavailable" };
    if (bytes.byteLength > JOURNAL_MEDIA_LIMITS.maxImageBytes) return { reason: "oversized" };
    declared = null;
  }
  const actual = sniffMime(bytes);
  if (actual === null || (declared !== null && declared !== actual)) return { reason: "signature-mismatch" };
  return {
    bytes,
    mimeType: actual,
    provenance: "vendor-native",
  };
}

function sniffMime(bytes: Uint8Array): MediaMimeType | null {
  if (bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((byte, i) => bytes[i] === byte)) {
    return "image/png";
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" &&
    String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") return "image/webp";
  return null;
}

function replaceImageReference(part: TranscriptPart, reference: string): TranscriptPart {
  if (part.kind === "image") return { ...part, url: reference };
  if (part.kind === "tool" && part.result?.imageUrl !== undefined) {
    return { ...part, result: { ...part.result, imageUrl: reference } };
  }
  return part;
}

function refusedImage(part: TranscriptPart): TranscriptPart {
  const text = "[Image unavailable: source or owner did not admit this media]";
  if (part.kind === "image") return { kind: "text", text };
  if (part.kind === "tool" && part.result !== undefined) {
    const result = { ...part.result };
    delete result.imageUrl;
    return { ...part, result: { ...result, text: [part.result.text, text].filter(Boolean).join("\n") } };
  }
  return part;
}

function isCanonicalBlobReference(reference: string): boolean {
  return /^\/api\/blobs\/[0-9a-f]{64}$/.test(reference);
}
