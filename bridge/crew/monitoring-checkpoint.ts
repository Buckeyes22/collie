/**
 * Closed monitoring checkpoint wire schema and bounded receiver storage.
 *
 * The caller must be the Crew router: it constructs `CheckpointAuthority` only after pinned TLS,
 * current secret/protocol admission, and exact current lead/warrant/deputy comparison. This module
 * never grants Crew authority and never activates a takeover.
 */
import {monitoringPayloadBytes,MONITORING_PAYLOAD_MAX_BYTES,createMonitoringTransfer,closeMonitoringTransfer} from "./monitoring-payload-budget.ts";
import { MonitoringFactIndex } from "./monitoring-fact-index.ts";
import { createHash, randomBytes } from "node:crypto";
import { constants, renameSync } from "node:fs";
import { lstat, mkdir, open, readdir, rename, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { parseMachineStrictJson, isMachineFileMissing } from "../machine-parse.ts";
import type { JsonValue, JsonObject } from "../json.ts";
import { HOST } from "../host.ts";
import { isOwnerOnly } from "../owner-only.ts";
import { assertMonitoringPrivateDirectory as assertPrivateDirectory, readMonitoringPrivateFile as readPrivateFile } from "../monitoring-private-io.ts";
import {
  canonicalMonitoringCheckpointRecord,
  MonitoringCheckpointRecordVerifier,
  MonitoringCheckpointRecordStream,
  type IndexedMonitoringRecords,
  type MonitoringCheckpointRecord,
} from "./monitoring-checkpoint-records.ts";

export const MONITORING_CHECKPOINT_PATH = "/crew/v1/monitoring/checkpoint";
export const MONITORING_CHECKPOINT_RECORD_FORMAT = "jsonl-continuation-v1" as const;
export const MONITORING_CHECKPOINT_CAPABILITY = Object.freeze({ schema: 1, maxChunkBytes: 262144, maxGenerationBytes: 67108864 });
export type MonitoringCheckpointCapabilityReport = typeof MONITORING_CHECKPOINT_CAPABILITY & {
  readonly recordFormat: typeof MONITORING_CHECKPOINT_RECORD_FORMAT;
  readonly checkpointRevision?: string;
  readonly rootDigest?: string;
};
const MAX_CHUNKS = 256;
const MAX_MANIFEST_BYTES = 256 * 1024;
const MAX_CHUNK_FRAME_BYTES = 352 * 1024;
const STAGING_TTL_MS = 10 * 60_000;
const UINT64_MAX = 0xffff_ffff_ffff_ffffn;
const HEX = /^[0-9a-f]{64}$/;
const MANIFEST_KEYS = ["schema", "crewId", "leadMemberId", "leadFingerprint", "deputyMemberId", "deputyFingerprint", "warrantGeneration", "secretGeneration", "monitoringLineage", "checkpointRevision", "createdAt", "historyFrom", "historyThrough", "rosterDigest", "rulesDigest", "producerDigest", "chunks", "rootDigest"] as const;

export interface MonitoringCheckpointChunk {
  readonly index: number;
  readonly bytes: number;
  readonly sha256: string;
}

export interface MonitoringCheckpointManifest {
  readonly schema: 1;
  readonly crewId: string;
  readonly leadMemberId: string;
  readonly leadFingerprint: string;
  readonly deputyMemberId: string;
  readonly deputyFingerprint: string;
  readonly warrantGeneration: number;
  readonly secretGeneration: number;
  readonly monitoringLineage: string;
  readonly checkpointRevision: string;
  readonly createdAt: number;
  readonly historyFrom: number;
  readonly historyThrough: number;
  readonly rosterDigest: string;
  readonly rulesDigest: string;
  readonly producerDigest: string;
  readonly chunks: readonly MonitoringCheckpointChunk[];
  readonly rootDigest: string;
}

export interface CheckpointAuthority {
  readonly crewId: string;
  readonly leadMemberId: string;
  readonly leadFingerprint: string;
  readonly deputyMemberId: string;
  readonly deputyFingerprint: string;
  readonly warrantGeneration: number;
  readonly secretGeneration: number;
}

export type CheckpointFrame =
  | { readonly kind: "begin"; readonly recordFormat: typeof MONITORING_CHECKPOINT_RECORD_FORMAT; readonly manifest: MonitoringCheckpointManifest }
  | { readonly kind: "chunk"; readonly checkpointRevision: string; readonly rootDigest: string; readonly index: number; readonly data: string }
  | { readonly kind: "commit"; readonly checkpointRevision: string; readonly rootDigest: string };

export function sha256(bytes: Uint8Array | string): string {
  return createHash("sha256").update(bytes).digest("hex");
}

function isRecord(value: JsonValue | undefined): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function safeIdentifier(value: JsonValue | undefined): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function uint64(value: JsonValue | undefined): value is string {
  return typeof value === "string" && /^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= UINT64_MAX;
}

export function checkpointRootDigest(manifest: Omit<MonitoringCheckpointManifest, "rootDigest"> | MonitoringCheckpointManifest): string {
  const fields = MANIFEST_KEYS.filter((key) => key !== "rootDigest");
  const canonical = Object.fromEntries(fields.map(key=>[key,manifest[key]]));
  const hash = createHash("sha256");
  hash.update("collie.monitoring.checkpoint.root.v1\0", "utf8");
  hash.update(JSON.stringify(canonical), "utf8");
  for (const chunk of manifest.chunks) {
    hash.update(`\0${chunk.index}:${chunk.bytes}:${chunk.sha256}`, "utf8");
  }
  return hash.digest("hex");
}

export function parseMonitoringCheckpointManifest(value: JsonValue | undefined): MonitoringCheckpointManifest | null {
  if (!isRecord(value) || Object.keys(value).length !== MANIFEST_KEYS.length || Object.keys(value).some((key) => !MANIFEST_KEYS.some(known=>known===key))) return null;
  if (value.schema !== 1 || !safeIdentifier(value.crewId) || !safeIdentifier(value.leadMemberId) || !safeIdentifier(value.deputyMemberId) ||
    typeof value.leadFingerprint !== "string" || !/^[0-9a-f]{64}$/.test(value.leadFingerprint) ||
    typeof value.deputyFingerprint !== "string" || !/^[0-9a-f]{64}$/.test(value.deputyFingerprint) ||
    typeof value.warrantGeneration !== "number" || !Number.isSafeInteger(value.warrantGeneration) || value.warrantGeneration < 0 ||
    typeof value.secretGeneration !== "number" || !Number.isSafeInteger(value.secretGeneration) || value.secretGeneration < 0 ||
    typeof value.monitoringLineage !== "string" || !/^[0-9a-f]{64}$/.test(value.monitoringLineage) || !uint64(value.checkpointRevision) ||
    typeof value.createdAt !== "number" || !Number.isSafeInteger(value.createdAt) || value.createdAt < 0 ||
    typeof value.historyFrom !== "number" || !Number.isSafeInteger(value.historyFrom) || value.historyFrom < 0 ||
    typeof value.historyThrough !== "number" || !Number.isSafeInteger(value.historyThrough) || value.historyThrough < value.historyFrom ||
    typeof value.rosterDigest !== "string" || !/^[0-9a-f]{64}$/.test(value.rosterDigest) ||
    typeof value.rulesDigest !== "string" || !/^[0-9a-f]{64}$/.test(value.rulesDigest) ||
    typeof value.producerDigest !== "string" || !/^[0-9a-f]{64}$/.test(value.producerDigest) ||
    typeof value.rootDigest !== "string" || !/^[0-9a-f]{64}$/.test(value.rootDigest) || !Array.isArray(value.chunks) || value.chunks.length === 0 || value.chunks.length > MAX_CHUNKS) return null;
  const chunks: MonitoringCheckpointChunk[] = [];
  let total = 0;
  for (let i = 0; i < value.chunks.length; i++) {
    const item = value.chunks[i];
    if (!isRecord(item) || Object.keys(item).length !== 3 || Object.keys(item).some((key) => key !== "index" && key !== "bytes" && key !== "sha256") ||
      item.index !== i || typeof item.bytes !== "number" || !Number.isSafeInteger(item.bytes) || item.bytes < 1 || item.bytes > MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes ||
      typeof item.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(item.sha256)) return null;
    total += item.bytes;
    if (total > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) return null;
    chunks.push({ index: item.index, bytes: item.bytes, sha256: item.sha256 });
  }
  const manifest: MonitoringCheckpointManifest = {schema:1,crewId:value.crewId,leadMemberId:value.leadMemberId,leadFingerprint:value.leadFingerprint,
    deputyMemberId:value.deputyMemberId,deputyFingerprint:value.deputyFingerprint,warrantGeneration:value.warrantGeneration,secretGeneration:value.secretGeneration,
    monitoringLineage:value.monitoringLineage,checkpointRevision:value.checkpointRevision,createdAt:value.createdAt,historyFrom:value.historyFrom,
    historyThrough:value.historyThrough,rosterDigest:value.rosterDigest,rulesDigest:value.rulesDigest,producerDigest:value.producerDigest,chunks,rootDigest:value.rootDigest};
  if (checkpointRootDigest(manifest) !== manifest.rootDigest || Buffer.byteLength(JSON.stringify(manifest), "utf8") > MAX_MANIFEST_BYTES) return null;
  return { ...manifest, chunks };
}

export function parseMonitoringCheckpointFrame(value: JsonValue | undefined): CheckpointFrame | null {
  if (!isRecord(value) || typeof value.kind !== "string") return null;
  if (value.kind === "begin" && value.recordFormat===MONITORING_CHECKPOINT_RECORD_FORMAT && Object.keys(value).length === 3 && Object.keys(value).every((key) => key === "kind" || key === "manifest" || key === "recordFormat")) {
    const manifest = parseMonitoringCheckpointManifest(value.manifest);
    return manifest === null ? null : { kind: "begin", recordFormat:MONITORING_CHECKPOINT_RECORD_FORMAT, manifest };
  }
  if (value.kind === "chunk" && Object.keys(value).length === 5 && Object.keys(value).every((key) => ["kind", "checkpointRevision", "rootDigest", "index", "data"].includes(key)) &&
    uint64(value.checkpointRevision) && typeof value.rootDigest === "string" && /^[0-9a-f]{64}$/.test(value.rootDigest) &&
    typeof value.index === "number" && Number.isInteger(value.index) && value.index >= 0 && value.index < MAX_CHUNKS && typeof value.data === "string") {
    return { kind: "chunk", checkpointRevision: value.checkpointRevision, rootDigest: value.rootDigest, index: value.index, data: value.data };
  }
  if (value.kind === "commit" && Object.keys(value).length === 3 && Object.keys(value).every((key) => ["kind", "checkpointRevision", "rootDigest"].includes(key)) &&
    uint64(value.checkpointRevision) && typeof value.rootDigest === "string" && /^[0-9a-f]{64}$/.test(value.rootDigest)) {
    return { kind: "commit", checkpointRevision: value.checkpointRevision, rootDigest: value.rootDigest };
  }
  return null;
}

export function checkpointMatchesAuthority(manifest: MonitoringCheckpointManifest, authority: CheckpointAuthority): boolean {
  return manifest.crewId === authority.crewId && manifest.leadMemberId === authority.leadMemberId && manifest.leadFingerprint === authority.leadFingerprint &&
    manifest.deputyMemberId === authority.deputyMemberId && manifest.deputyFingerprint === authority.deputyFingerprint &&
    manifest.warrantGeneration === authority.warrantGeneration && manifest.secretGeneration === authority.secretGeneration;
}

export function boundedCheckpointFrame(value: JsonValue | undefined): CheckpointFrame | null {
  return parseMonitoringCheckpointFrame(value);
}

export interface BuiltMonitoringCheckpoint {
  readonly manifest: MonitoringCheckpointManifest;
  readonly chunks: readonly Uint8Array[];
  readonly readChunk?: (index: number) => Promise<Uint8Array>;
  readonly cleanup?: () => Promise<void>;
}

export interface MonitoringCheckpointInspection {
  readonly manifest: MonitoringCheckpointManifest;
  readonly records: AsyncGenerator<{ readonly index: number; readonly record: MonitoringCheckpointRecord }, void, void>;
}

export interface VerifiedMonitoringRoleTransition {
  readonly transitionId: string;
  readonly crewId: string;
  readonly newLeadMemberId: string;
  readonly newLeadFingerprint: string;
  readonly warrantGeneration: number;
}

/** Deterministic record serializer shared by the lead sender and deputy receiver. */
export function buildMonitoringCheckpoint(
  authority: CheckpointAuthority,
  records: readonly MonitoringCheckpointRecord[],
  checkpointRevision: string,
  createdAt: number,
): BuiltMonitoringCheckpoint | null {
  if (!uint64(checkpointRevision) || !Number.isSafeInteger(createdAt) || createdAt < 0) return null;
  const verifier = new MonitoringCheckpointRecordVerifier();
  try {
  const chunks: Uint8Array[] = [];
  let pending: Uint8Array[] = [];
  let pendingBytes = 0;
  for (const record of records) {
    if (!verifier.add(record)) return null;
    const canonical = canonicalMonitoringCheckpointRecord(record);
    if (canonical === null) return null;
    const line = new TextEncoder().encode(`${canonical}\n`);
    if(line.byteLength>512*1024+1) return null;
    for(let offset=0;offset<line.length;) {
      const size=Math.min(MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes-pendingBytes,line.length-offset);
      pending.push(line.slice(offset,offset+size));pendingBytes+=size;offset+=size;
      if(pendingBytes===MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes) {chunks.push(concatChunks(pending,pendingBytes));pending=[];pendingBytes=0;}
    }
  }
  if (pendingBytes > 0) chunks.push(concatChunks(pending, pendingBytes));
  if (chunks.length === 0 || chunks.length > MAX_CHUNKS) return null;
  const digests = verifier.finish();
  if (digests === null) return null;
  const state = records.find((item) => item.kind === "state");
  if (state?.kind !== "state") return null;
  const unsigned = {
    schema: 1 as const, ...authority, monitoringLineage: state.lineage, checkpointRevision, createdAt,
    historyFrom: digests.historyFrom, historyThrough: digests.historyThrough,
    rosterDigest: digests.rosterDigest, rulesDigest: digests.rulesDigest, producerDigest: digests.producerDigest,
    chunks: chunks.map((bytes, index) => ({ index, bytes: bytes.byteLength, sha256: sha256(bytes) })),
  };
  const manifest: MonitoringCheckpointManifest = { ...unsigned, rootDigest: checkpointRootDigest(unsigned) };
  if (Buffer.byteLength(JSON.stringify(manifest), "utf8") + chunks.reduce((sum, bytes) => sum + bytes.byteLength, 0) > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) return null;
  verifier.close();
  return { manifest, chunks };
  } finally { verifier.close(); }
}

/** Source-side transfer spool: only one <=256 KiB record chunk is held while descriptors are built. */
export async function buildMonitoringCheckpointSpool(
  stateDir: string,
  authority: CheckpointAuthority,
  records: Iterable<MonitoringCheckpointRecord>,
  checkpointRevision: string,
  createdAt: number,
  originalOwnerCurrent: () => boolean = () => true,
): Promise<BuiltMonitoringCheckpoint | null> {
  if (!uint64(checkpointRevision) || !Number.isSafeInteger(createdAt) || createdAt < 0) return null;
  await mkdir(stateDir, { recursive: true, mode: 0o700 });
  await assertPrivateDirectory(stateDir);
  const spool=await createMonitoringTransfer(stateDir,originalOwnerCurrent);
  // SAFETY: This source-only input is either the owned indexed snapshot factory or an ordinary
  // record iterable. The optional hooks belong to that factory and are absent on ordinary inputs.
  const indexed=records as Partial<IndexedMonitoringRecords>;
  let verifier: MonitoringCheckpointRecordVerifier | undefined;
  try {
    await assertPrivateDirectory(spool);
    await indexed.prepare?.(originalOwnerCurrent);
    if(!originalOwnerCurrent())throw new Error("checkpoint.authority_changed");
    verifier = indexed.createVerifier?.() ?? new MonitoringCheckpointRecordVerifier(new MonitoringFactIndex(stateDir));
    const chunks: MonitoringCheckpointChunk[] = [];
    const existingPayload=await monitoringPayloadBytes(stateDir);
    if(!originalOwnerCurrent())throw new Error("checkpoint.authority_changed");
    const pending = new Uint8Array(MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
    let pendingBytes = 0;
    let totalBytes = 0;
    let state: Extract<MonitoringCheckpointRecord, { kind: "state" }> | null = null;
    const flush = async (): Promise<void> => {
      if (pendingBytes === 0) return;
      if(!originalOwnerCurrent())throw new Error("checkpoint.authority_changed");
      const index = chunks.length;
      const bytes = pending.subarray(0, pendingBytes);
      if(existingPayload+totalBytes+bytes.byteLength>MONITORING_PAYLOAD_MAX_BYTES)throw new Error("checkpoint.payload_pressure");
      const path = join(spool, `chunk-${index}.bin`);
      const handle = await open(path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | (constants.O_NOFOLLOW ?? 0), 0o600);
      try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
      if(!originalOwnerCurrent())throw new Error("checkpoint.authority_changed");
      chunks.push({ index, bytes: bytes.byteLength, sha256: sha256(bytes) });
      totalBytes += bytes.byteLength;
      pendingBytes = 0;
      if (chunks.length > MAX_CHUNKS || totalBytes > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) throw new Error("checkpoint.generation_quota");
    };
    for (const record of records) {
      if(!originalOwnerCurrent())throw new Error("checkpoint.authority_changed");
      if (!verifier.add(record)) throw new Error("checkpoint.record_invalid");
      if (record.kind === "state") state = record;
      const canonical = canonicalMonitoringCheckpointRecord(record);
      if (canonical === null) throw new Error("checkpoint.record_invalid");
      const line = new TextEncoder().encode(`${canonical}\n`);
      if(line.byteLength>512*1024+1) throw new Error("checkpoint.record_too_large");
      for(let offset=0;offset<line.length;) {
        const size=Math.min(pending.length-pendingBytes,line.length-offset);
        pending.set(line.subarray(offset,offset+size),pendingBytes);pendingBytes+=size;offset+=size;
        if(pendingBytes===pending.length) await flush();
      }
    }
    await flush();
    if (chunks.length === 0 || state === null) throw new Error("checkpoint.state_missing");
    const digests = await verifier.finishAsync(originalOwnerCurrent);
    if (digests === null) throw new Error("checkpoint.record_set_invalid");
    const unsigned = {
      schema: 1 as const, ...authority, monitoringLineage: state.lineage, checkpointRevision, createdAt,
      historyFrom: digests.historyFrom, historyThrough: digests.historyThrough,
      rosterDigest: digests.rosterDigest, rulesDigest: digests.rulesDigest, producerDigest: digests.producerDigest, chunks,
    };
    const manifest: MonitoringCheckpointManifest = { ...unsigned, rootDigest: checkpointRootDigest(unsigned) };
    if (totalBytes + Buffer.byteLength(JSON.stringify(manifest), "utf8") > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) throw new Error("checkpoint.generation_quota");
    return {
      manifest,
      chunks: [],
      readChunk: async (index) => {
        const descriptor = chunks[index];
        if (descriptor === undefined) throw new Error("checkpoint.chunk_missing");
        const bytes = await readPrivateFile(join(spool, `chunk-${index}.bin`), MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
        if (bytes.length !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) throw new Error("checkpoint.chunk_corrupt");
        return new Uint8Array(bytes);
      },
      cleanup: () => closeMonitoringTransfer(spool),
    };
  } catch (error) {
    await closeMonitoringTransfer(spool).catch(() => {});
    if (error instanceof Error && error.message.startsWith("checkpoint.")) return null;
    throw error;
  } finally { verifier?.close(); indexed.dispose?.(); }
}

function concatChunks(chunks: readonly Uint8Array[], size: number): Uint8Array {
  const out = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.byteLength; }
  return out;
}

/** The caller injects the existing authenticated PeerClient POST; this helper adds no second channel. */
export async function sendMonitoringCheckpoint(
  checkpoint: BuiltMonitoringCheckpoint,
  post: (frame: CheckpointFrame) => Promise<Response>,
  originalOwnerCurrent: () => boolean,
): Promise<"accepted" | "deferred" | "retired"> {
  try { return await sendMonitoringCheckpointFrames(checkpoint, post, originalOwnerCurrent); }
  finally { await checkpoint.cleanup?.().catch(() => {}); }
}

async function sendMonitoringCheckpointFrames(
  checkpoint: BuiltMonitoringCheckpoint,
  post: (frame: CheckpointFrame) => Promise<Response>,
  originalOwnerCurrent: () => boolean,
): Promise<"accepted" | "deferred" | "retired"> {
  const begin: CheckpointFrame = { kind: "begin", recordFormat:MONITORING_CHECKPOINT_RECORD_FORMAT, manifest: checkpoint.manifest };
  if (!originalOwnerCurrent()) return "retired";
  let response = await post(begin);
  if (!originalOwnerCurrent()) return "retired";
  const beginBody = response.ok ? await responseBody(response, checkpoint.manifest, "begin") : null;
  if (!originalOwnerCurrent()) return "retired";
  if (beginBody?.code === "checkpoint.already_current") return "accepted";
  if (beginBody === null || beginBody.accepted !== true) return "deferred";
  for (const [index, descriptor] of checkpoint.manifest.chunks.entries()) {
    if (!originalOwnerCurrent()) return "retired";
    const bytes = checkpoint.readChunk === undefined ? checkpoint.chunks[index] : await checkpoint.readChunk(index);
    if (!originalOwnerCurrent()) return "retired";
    if (bytes === undefined || bytes.byteLength !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) return "deferred";
    response = await post({ kind: "chunk", checkpointRevision: checkpoint.manifest.checkpointRevision,
      rootDigest: checkpoint.manifest.rootDigest, index, data: Buffer.from(bytes).toString("base64") });
    if (!originalOwnerCurrent()) return "retired";
    const body = response.ok ? await responseBody(response, checkpoint.manifest, "chunk") : null;
    if (!originalOwnerCurrent()) return "retired";
    if (body === null || body.accepted !== true || body.index !== index || body.sha256 !== checkpoint.manifest.chunks[index]?.sha256) return "deferred";
  }
  if (!originalOwnerCurrent()) return "retired";
  response = await post({ kind: "commit", checkpointRevision: checkpoint.manifest.checkpointRevision, rootDigest: checkpoint.manifest.rootDigest });
  if (!originalOwnerCurrent()) return "retired";
  const commitBody = response.ok ? await responseBody(response, checkpoint.manifest, "commit") : null;
  if (!originalOwnerCurrent()) return "retired";
  return commitBody !== null && commitBody.accepted === true ? "accepted" : "deferred";
}

async function responseBody(response: Response, manifest: MonitoringCheckpointManifest, phase: "begin" | "chunk" | "commit"): Promise<JsonObject | null> {
  const parsed = await readBoundedCheckpointBody(response, 16 * 1024);
  if (!("value" in parsed) || !isRecord(parsed.value)) return null;
  const body = parsed.value;
  if (phase !== "chunk" && (body.checkpointRevision !== manifest.checkpointRevision || body.rootDigest !== manifest.rootDigest)) return null;
  if (phase === "begin" && body.code === "checkpoint.already_current") return body.accepted === undefined ? body : null;
  if (body.code !== undefined || body.accepted !== true) return null;
  if ((body.checkpointRevision !== undefined && body.checkpointRevision !== manifest.checkpointRevision) ||
      (body.rootDigest !== undefined && body.rootDigest !== manifest.rootDigest)) return null;
  return body;
}

/** Read one body without trusting MIME, declared length, or an eager request.json allocation. */
export async function readBoundedCheckpointBody(req: Request | Response, maxBytes = MAX_CHUNK_FRAME_BYTES): Promise<{ readonly value: JsonValue } | { readonly status: 400 | 413 }> {
  const declared = req.headers.get("content-length");
  if (declared !== null && /^\d+$/.test(declared) && Number(declared) > maxBytes) return { status: 413 };
  if (req.body === null) return { status: 400 };
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) { await reader.cancel().catch(() => {}); return { status: 413 }; }
      chunks.push(value);
    }
  } catch { return { status: 400 }; }
  finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    const value = parseMachineStrictJson(text);
    return value === null ? { status: 400 } : { value };
  } catch { return { status: 400 }; }
}

function encodeBase64Chunk(data: string): Uint8Array | null {
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data) || data.length > MAX_CHUNK_FRAME_BYTES) return null;
  const bytes = Buffer.from(data, "base64");
  if (bytes.toString("base64") !== data || bytes.length > MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes) return null;
  return new Uint8Array(bytes);
}

async function durableWrite(path: string, bytes: Uint8Array, beforeRename: () => boolean = () => true): Promise<void> {
  const tmp = `${path}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  try {
    const handle = await open(tmp, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | (constants.O_NOFOLLOW ?? 0), 0o600);
    try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
    const stat = await lstat(tmp);
    if (stat.isSymbolicLink() || !stat.isFile() || stat.nlink !== 1 || (HOST.platform !== "win32" && (stat.mode & 0o077) !== 0)) throw new Error("checkpoint.private_temp");
    if (HOST.platform === "win32") {
      const verdict = isOwnerOnly(tmp, HOST); if (verdict.state !== "private") throw new Error("checkpoint.private_acl");
    }
    if (!beforeRename()) throw new Error("checkpoint.authority_changed");
    renameSync(tmp, path);
    if (HOST.platform !== "win32") {
      const dir = await open(dirname(path), constants.O_RDONLY | (constants.O_DIRECTORY ?? 0));
      try { await dir.sync(); } finally { await dir.close(); }
    }
  } finally { await rm(tmp, { force: true }).catch(() => {}); }
}

/**
 * Single accepted generation plus one fixed-name stage. No filenames, URLs, or executable values
 * come from a frame. The caller rechecks its immutable authority before each await and before the
 * accepted-head rename.
 */
export class MonitoringCheckpointReceiver {
  private chain: Promise<void> = Promise.resolve();
  private adoptionChain: Promise<void> = Promise.resolve();
  constructor(private readonly stateDir: string, private readonly now: () => number = Date.now) {}

  async handle(
    req: Request,
    authority: CheckpointAuthority,
    currentAuthority: () => boolean,
  ): Promise<Response> {
    if (req.method === "GET") return this.readReceipt(authority, currentAuthority);
    if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
    const parsed = await readBoundedCheckpointBody(req);
    if (!("value" in parsed)) return new Response(parsed.status === 413 ? "payload too large" : "bad request", { status: parsed.status });
    if(isRecord(parsed.value) && parsed.value.kind==="begin" && parsed.value.recordFormat!==MONITORING_CHECKPOINT_RECORD_FORMAT) return Response.json({code:"checkpoint.unsupported_format"},{status:404});
    const frame = boundedCheckpointFrame(parsed.value);
    if (frame === null) return new Response(JSON.stringify({ code: "checkpoint.malformed" }), { status: 400 });
    if (!currentAuthority()) return new Response(JSON.stringify({ code: "checkpoint.authority_changed" }), { status: 403 });
    return this.serialize(() => this.apply(frame, authority, currentAuthority)).catch(() =>
      Response.json({code:currentAuthority()?"checkpoint.unavailable":"checkpoint.authority_changed"},{status:currentAuthority()?503:403}));
  }

  async receipt(authority: CheckpointAuthority, currentAuthority: () => boolean): Promise<{ readonly checkpointRevision: string; readonly rootDigest: string } | null> {
    if (!currentAuthority()) return null;
    try {
      const accepted = await this.readAccepted(authority);
      if (!currentAuthority() || accepted === null || (!accepted.verified || !await this.verifyAcceptedChunks(accepted.manifest,currentAuthority)) || !currentAuthority()) return null;
      return { checkpointRevision: accepted.manifest.checkpointRevision, rootDigest: accepted.manifest.rootDigest };
    } catch { return null; }
  }

  /**
   * Read-only adoption preparation. It returns the immutable manifest plus a bounded async stream;
   * the caller must validate the complete monitoring generation and may only commit it through the
   * normal B38 takeover transaction. This API does not mutate local monitoring state or grant lead
   * authority. Authority is rechecked before and after every disk await and before each yielded chunk.
   */
  async inspectForAdoption(
    authority: CheckpointAuthority,
    currentAuthority: () => boolean,
  ): Promise<MonitoringCheckpointInspection | null> {
    if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
    const acceptedBytes = await readPrivateFile(join(this.stateDir, "monitoring-checkpoint.accepted.json"), MAX_MANIFEST_BYTES + 1024);
    if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
    const record = parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(acceptedBytes));
    const manifest = !isRecord(record) ? null : parseMonitoringCheckpointManifest(record.manifest);
    if (manifest === null || !checkpointMatchesAuthority(manifest, authority)) return null;
    const verifier = await this.verifyAcceptedGeneration(manifest, currentAuthority);
    if (verifier === null) return null;
    const records = async function* (receiver: MonitoringCheckpointReceiver): AsyncGenerator<{ readonly index: number; readonly record: MonitoringCheckpointRecord }, void, void> {
      const stream=new MonitoringCheckpointRecordStream();
      await assertPrivateDirectory(receiver.generationDirectory(manifest.rootDigest));
      for (const descriptor of manifest.chunks) {
        if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
        const bytes = await readPrivateFile(join(receiver.generationDirectory(manifest.rootDigest), `chunk-${descriptor.index}.bin`), MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
        if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
        if (bytes.length !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) throw new Error("checkpoint.chunk_corrupt");
        for (const item of stream.push(bytes)) {
          if(!currentAuthority()) throw new Error("checkpoint.authority_changed");
          yield { index: descriptor.index, record: item };
        }
        if(stream.failed) throw new Error("checkpoint.record_invalid");
      }
      if(!stream.finish()) throw new Error("checkpoint.record_truncated");
    }(this);
    return { manifest, records };
  }

  async prepareMonitoringAdoption(
    transition: VerifiedMonitoringRoleTransition,
    expectedRootDigest: string,
    authority: CheckpointAuthority,
    transitionIsCurrent: () => boolean,
  ): Promise<"prepared" | "already_prepared" | "refused"> {
    const result = this.adoptionChain.then(() => this.prepareMonitoringAdoptionOnce(transition, expectedRootDigest, authority, transitionIsCurrent));
    this.adoptionChain = result.then(() => undefined, () => undefined);
    return result;
  }

  private async prepareMonitoringAdoptionOnce(
    transition: VerifiedMonitoringRoleTransition,
    expectedRootDigest: string,
    authority: CheckpointAuthority,
    transitionIsCurrent: () => boolean,
  ): Promise<"prepared" | "already_prepared" | "refused"> {
    if (!/^[A-Za-z0-9_-]{16,128}$/.test(transition.transitionId) || !HEX.test(expectedRootDigest) ||
      transition.crewId !== authority.crewId || transition.newLeadMemberId !== authority.deputyMemberId ||
      transition.newLeadFingerprint !== authority.deputyFingerprint || !Number.isSafeInteger(transition.warrantGeneration) ||
      transition.warrantGeneration <= authority.warrantGeneration || !transitionIsCurrent()) return "refused";
    const inspection = await this.inspectForAdoption(authority, transitionIsCurrent);
    if (inspection === null || !transitionIsCurrent() || inspection.manifest.rootDigest !== expectedRootDigest) return "refused";
    const markerPath = join(this.stateDir, "monitoring-adoption.marker.json");
    let existingBytes: Buffer | null = null;
    try {
      existingBytes = await readPrivateFile(markerPath, 16 * 1024);
    } catch (error) {
      if (!isMachineFileMissing(error)) return "refused";
    }
    if (existingBytes !== null) {
      if (!transitionIsCurrent()) return "refused";
      let existing: JsonValue | undefined;
      try { existing = parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(existingBytes)); } catch { return "refused"; }
      const expectedKeys = ["schema", "transitionId", "crewId", "newLeadMemberId", "newLeadFingerprint", "warrantGeneration", "checkpointDigest", "monitoringLineage", "producerState", "preparedAt"];
      if (isRecord(existing) && Object.keys(existing).length === expectedKeys.length && Object.keys(existing).every((key) => expectedKeys.includes(key)) &&
        existing.schema === 1 && existing.transitionId === transition.transitionId && existing.crewId === transition.crewId &&
        existing.checkpointDigest === expectedRootDigest && existing.monitoringLineage === inspection.manifest.monitoringLineage &&
        existing.newLeadMemberId === transition.newLeadMemberId && existing.newLeadFingerprint === transition.newLeadFingerprint &&
        existing.warrantGeneration === transition.warrantGeneration && existing.producerState === "paused" &&
        typeof existing.preparedAt === "number" && Number.isSafeInteger(existing.preparedAt) && existing.preparedAt >= 0) return "already_prepared";
      return "refused";
    }
    const marker = {
      schema: 1, transitionId: transition.transitionId, crewId: transition.crewId, newLeadMemberId: transition.newLeadMemberId,
      newLeadFingerprint: transition.newLeadFingerprint, warrantGeneration: transition.warrantGeneration,
      checkpointDigest: expectedRootDigest, monitoringLineage: inspection.manifest.monitoringLineage,
      producerState: "paused", preparedAt: this.now(),
    };
    if (!transitionIsCurrent()) return "refused";
    await durableWrite(markerPath, Buffer.from(JSON.stringify(marker), "utf8"), transitionIsCurrent);
    return transitionIsCurrent() ? "prepared" : "refused";
  }

  private async verifyAcceptedGeneration(manifest: MonitoringCheckpointManifest, currentAuthority: () => boolean): Promise<MonitoringCheckpointRecordVerifier | null> {
    const verifier = new MonitoringCheckpointRecordVerifier(new MonitoringFactIndex(this.stateDir));
    const stream = new MonitoringCheckpointRecordStream();
    try {
    await assertPrivateDirectory(this.generationDirectory(manifest.rootDigest));
    let total = 0,recordCount=0;
    for (const descriptor of manifest.chunks) {
      if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
      const bytes = await readPrivateFile(join(this.generationDirectory(manifest.rootDigest), `chunk-${descriptor.index}.bin`), MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
      if (!currentAuthority()) throw new Error("checkpoint.authority_changed");
      if (bytes.length !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) return null;
      for(const item of stream.push(bytes)){if(!verifier.add(item)) return null;if(++recordCount%64===0){await Bun.sleep(0);if(!currentAuthority())throw new Error("checkpoint.authority_changed");}}
      if(stream.failed) return null;
      total += bytes.length;
    }
    const digests = stream.finish() ? await verifier.finishAsync(currentAuthority) : null;
    if (digests === null || verifier.monitoringLineage !== manifest.monitoringLineage || digests.rosterDigest !== manifest.rosterDigest ||
      digests.rulesDigest !== manifest.rulesDigest || digests.producerDigest !== manifest.producerDigest ||
      digests.historyFrom !== manifest.historyFrom || digests.historyThrough !== manifest.historyThrough ||
      total + Buffer.byteLength(JSON.stringify(manifest), "utf8") > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) return null;
    return verifier;
    } finally { verifier.close(); }
  }

  private async readReceipt(authority: CheckpointAuthority, currentAuthority: () => boolean): Promise<Response> {
    if (!currentAuthority()) return new Response(JSON.stringify({ code: "checkpoint.authority_changed" }), { status: 403 });
    let hadHead=false;
    try {
      const acceptedBytes = await readPrivateFile(join(this.stateDir, "monitoring-checkpoint.accepted.json"), MAX_MANIFEST_BYTES + 1024);
      hadHead=true;
      const record = parseAcceptedCheckpointHead(parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(acceptedBytes)));
      if (record === null) throw new Error("checkpoint.corrupt");
      const manifest = record.manifest;
      if (manifest === null || !checkpointMatchesAuthority(manifest, authority) || !currentAuthority()) return new Response(JSON.stringify({ code: "checkpoint.unsupported" }), { status: 404 });
      if(!record.verified || !await this.verifyAcceptedChunks(manifest,currentAuthority) || !currentAuthority()) throw new Error("checkpoint.unusable");
      return Response.json({ schema: 1, checkpointRevision: manifest.checkpointRevision, rootDigest: manifest.rootDigest, createdAt: manifest.createdAt, generationBytes: record.generationBytes });
    } catch(error) {
      return !hadHead && isMachineFileMissing(error)
        ? Response.json({schema:1,supported:true,checkpoint:null})
        : Response.json({code:"checkpoint.unavailable"},{status:503});
    }
  }

  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.chain.then(operation);
    this.chain = result.then(() => undefined, () => undefined);
    return result;
  }

  private async apply(frame: CheckpointFrame, authority: CheckpointAuthority, currentAuthority: () => boolean): Promise<Response> {
    if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
    const stagePath = join(this.stateDir, "monitoring-checkpoint.stage.json");
    if (frame.kind === "begin") {
      const manifest = frame.manifest;
      if (!checkpointMatchesAuthority(manifest, authority)) return Response.json({ code: "checkpoint.authority_mismatch" }, { status: 403 });
      if (manifest.createdAt > this.now() + 60_000) return Response.json({ code: "checkpoint.future" }, { status: 400 });
      const accepted = await this.readAccepted(authority);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      if (accepted !== null) {
        const old = BigInt(accepted.manifest.checkpointRevision); const proposed = BigInt(manifest.checkpointRevision);
        if (proposed < old) return Response.json({ code: "checkpoint.stale" }, { status: 409 });
        if (proposed === old && accepted.manifest.rootDigest !== manifest.rootDigest) return Response.json({ code: "checkpoint.equivocation" }, { status: 409 });
        if (proposed === old && accepted.manifest.rootDigest === manifest.rootDigest) {
          if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
          try {
            if(accepted.verified && await this.verifyAcceptedChunks(accepted.manifest,currentAuthority) && currentAuthority()) return Response.json({ code: "checkpoint.already_current", checkpointRevision: manifest.checkpointRevision, rootDigest: manifest.rootDigest });
          } catch(error) { if(!isMachineFileMissing(error)) throw error; }
          // Exact same immutable digest may repair missing/corrupt chunks; it never certifies them.

        }
      }
      const existingStage = await this.readStage(stagePath);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      if (existingStage !== null) {
        if (existingStage.manifest.checkpointRevision === manifest.checkpointRevision && existingStage.manifest.rootDigest === manifest.rootDigest) {
          if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
          return Response.json({ accepted: true, checkpointRevision: manifest.checkpointRevision, rootDigest: manifest.rootDigest, received: existingStage.received });
        }
        if (BigInt(existingStage.manifest.checkpointRevision) === BigInt(manifest.checkpointRevision) && existingStage.manifest.rootDigest !== manifest.rootDigest) return Response.json({ code: "checkpoint.stage_equivocation" }, { status: 409 });
        await this.removeStageFiles(existingStage.manifest.chunks.length);
      }
      await this.removeOrphanGenerations(currentAuthority);
      await this.removeOrphanStageDirectories(currentAuthority);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      await mkdir(this.stateDir, { recursive: true, mode: 0o700 });
      await assertPrivateDirectory(this.stateDir);
      const stage:MonitoringCheckpointStage={manifest,startedAt:this.now(),received:[]};
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      await mkdir(this.stageDirectory(manifest.rootDigest), { recursive: false, mode: 0o700 });
      await assertPrivateDirectory(this.stageDirectory(manifest.rootDigest));
      await durableWrite(stagePath, Buffer.from(JSON.stringify(stage), "utf8"), currentAuthority);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      return Response.json({ accepted: true, checkpointRevision: manifest.checkpointRevision, rootDigest: manifest.rootDigest });
    }
    const stage = await this.readStage(stagePath);
    if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
    if (stage === null) return Response.json({ code: "checkpoint.stage_expired" }, { status: 409 });
    if (this.now() - stage.startedAt > STAGING_TTL_MS) {
      await this.removeStageFiles(stage.manifest.chunks.length);
      return Response.json({ code: "checkpoint.stage_expired" }, { status: 409 });
    }
    if (!checkpointMatchesAuthority(stage.manifest, authority) || stage.manifest.checkpointRevision !== frame.checkpointRevision || stage.manifest.rootDigest !== frame.rootDigest) return Response.json({ code: "checkpoint.stage_mismatch" }, { status: 409 });
    if (frame.kind === "chunk") {
      const descriptor = stage.manifest.chunks[frame.index];
      const bytes = encodeBase64Chunk(frame.data);
      if (descriptor === undefined || bytes === null || bytes.length !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) return Response.json({ code: "checkpoint.chunk_invalid" }, { status: 400 });
      const existing = stage.received.find((chunk) => chunk.index === frame.index);
      if (existing !== undefined && (existing.sha256 !== descriptor.sha256 || existing.bytes !== descriptor.bytes)) return Response.json({ code: "checkpoint.chunk_equivocation" }, { status: 409 });
      if (existing === undefined && frame.index !== stage.received.length) return Response.json({ code: "checkpoint.chunk_order" }, { status: 409 });
      const chunkPath = join(this.stageDirectory(stage.manifest.rootDigest), `chunk-${frame.index}.bin`);
      await mkdir(this.stateDir, { recursive: true, mode: 0o700 });
      await assertPrivateDirectory(this.stateDir);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      const payload=await monitoringPayloadBytes(this.stateDir);
      if(!currentAuthority())return Response.json({code:"checkpoint.authority_changed"},{status:403});
      if(existing===undefined && payload+bytes.length>MONITORING_PAYLOAD_MAX_BYTES)return Response.json({code:"checkpoint.payload_pressure"},{status:503});
      await durableWrite(chunkPath, bytes, currentAuthority);
      stage.received = existing === undefined
        ? [...stage.received, { index: frame.index, sha256: descriptor.sha256, bytes: descriptor.bytes }]
        : stage.received;
      await durableWrite(stagePath, Buffer.from(JSON.stringify(stage), "utf8"), currentAuthority);
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      return Response.json({ accepted: true, index: frame.index, sha256: descriptor.sha256 });
    }
    if (stage.received.length !== stage.manifest.chunks.length) return Response.json({ code: "checkpoint.incomplete" }, { status: 409 });
    const verifier = new MonitoringCheckpointRecordVerifier(new MonitoringFactIndex(this.stateDir));
    const stream = new MonitoringCheckpointRecordStream();
    try {
    let generationBytes = 0,recordCount=0;
    const stageDirectory = this.stageDirectory(stage.manifest.rootDigest);
    const generationPath = this.generationDirectory(stage.manifest.rootDigest);
    let sourceDirectory = stageDirectory;
    try {
      await lstat(stageDirectory);
    } catch {
      // Recover the exact durable bytes if the process stopped after the generation rename
      // but before it atomically replaced the accepted head pointer.
      try {
        const generation = await lstat(generationPath);
        if (generation.isSymbolicLink() || !generation.isDirectory()) throw new Error("checkpoint.generation_type");
        sourceDirectory = generationPath;
      } catch { return Response.json({ code: "checkpoint.stage_missing" }, { status: 409 }); }
    }
    await assertPrivateDirectory(sourceDirectory);
    for (const descriptor of stage.manifest.chunks) {
      if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
      const bytes = await readPrivateFile(join(sourceDirectory, `chunk-${descriptor.index}.bin`), MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
      if (bytes.length !== descriptor.bytes || sha256(bytes) !== descriptor.sha256) return Response.json({ code: "checkpoint.chunk_corrupt" }, { status: 400 });
      for(const record of stream.push(bytes)){if(!verifier.add(record)) return Response.json({code:"checkpoint.record_invalid"},{status:400});if(++recordCount%64===0){await Bun.sleep(0);if(!currentAuthority())return Response.json({code:"checkpoint.authority_changed"},{status:403});}}
      if(stream.failed) return Response.json({code:"checkpoint.record_invalid"},{status:400});
      generationBytes += bytes.length;
    }
    const digests = stream.finish() ? await verifier.finishAsync(currentAuthority) : null;
    if (digests === null || verifier.monitoringLineage !== stage.manifest.monitoringLineage ||
      digests.rosterDigest !== stage.manifest.rosterDigest || digests.rulesDigest !== stage.manifest.rulesDigest || digests.producerDigest !== stage.manifest.producerDigest ||
      digests.historyFrom !== stage.manifest.historyFrom || digests.historyThrough !== stage.manifest.historyThrough ||
      generationBytes + Buffer.byteLength(JSON.stringify(stage.manifest), "utf8") > MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes) {
      return Response.json({ code: "checkpoint.generation_invalid" }, { status: 400 });
    }
    if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
    const oldAccepted = await this.readAccepted(authority);
    if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
    let repairBackup: string | null = null;
    if (sourceDirectory === stageDirectory) {
      try {
        const existing=await lstat(generationPath);
        if(!existing.isDirectory() || existing.isSymbolicLink()) throw new Error("checkpoint.generation_type");
        await assertPrivateDirectory(generationPath);
        if(!currentAuthority()) throw new Error("checkpoint.authority_changed");
        repairBackup=`${generationPath}.repair`;
        renameSync(generationPath,repairBackup);
      } catch(error) { if(!isMachineFileMissing(error)) throw error; }
      if(!currentAuthority()) throw new Error("checkpoint.authority_changed");
      renameSync(stageDirectory, generationPath);
      if (HOST.platform !== "win32") {
        const dir = await open(this.stateDir, constants.O_RDONLY | (constants.O_DIRECTORY ?? 0));
        try { await dir.sync(); } finally { await dir.close(); }
      }
    }
    const accepted = JSON.stringify({ manifest: stage.manifest, generationBytes, verifiedFormat:MONITORING_CHECKPOINT_RECORD_FORMAT, verificationDigest:checkpointVerificationDigest(stage.manifest) });
    try {
      await durableWrite(join(this.stateDir, "monitoring-checkpoint.accepted.json"), Buffer.from(accepted, "utf8"), currentAuthority);
    } catch (error) {
      if (sourceDirectory === stageDirectory) {
        await rename(generationPath, stageDirectory).catch(() => {});
        if(repairBackup!==null) await rename(repairBackup,generationPath).catch(() => {});
      }
      throw error;
    }
    if(repairBackup!==null && currentAuthority()) await rm(repairBackup,{recursive:true,force:true});
    await this.removeStageFiles(stage.manifest.chunks.length);
    if (oldAccepted !== null && oldAccepted.manifest.rootDigest !== stage.manifest.rootDigest) {
      await rm(this.generationDirectory(oldAccepted.manifest.rootDigest), { recursive: true, force: true });
    }
    if (!currentAuthority()) return Response.json({ code: "checkpoint.authority_changed" }, { status: 403 });
    return Response.json({ accepted: true, checkpointRevision: stage.manifest.checkpointRevision, rootDigest: stage.manifest.rootDigest });
    } finally { verifier.close(); }
  }

  private stageDirectory(rootDigest: string): string { return join(this.stateDir, `monitoring-checkpoint.stage-${rootDigest}`); }
  private generationDirectory(rootDigest: string): string { return join(this.stateDir, `monitoring-checkpoint.generation-${rootDigest}`); }

  private async removeOrphanGenerations(currentAuthority: () => boolean): Promise<void> {
    let acceptedRoot: string | null = null;
    try {
      const bytes = await readPrivateFile(join(this.stateDir, "monitoring-checkpoint.accepted.json"), MAX_MANIFEST_BYTES + 1024);
      const raw = parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
      const manifest = !isRecord(raw) ? null : parseMonitoringCheckpointManifest(raw.manifest);
      if(manifest===null) throw new Error("checkpoint.accepted_corrupt");
      acceptedRoot = manifest.rootDigest;
    } catch(error) {
      if(!isMachineFileMissing(error)) throw error;
    }
    const names = await readdir(this.stateDir).catch(() => []);
    if (!currentAuthority()) return;
    for (const name of names) {
      if (!/^monitoring-checkpoint\.generation-[0-9a-f]{64}$/.test(name)) continue;
      const root = name.slice("monitoring-checkpoint.generation-".length);
      if (root !== acceptedRoot) {
        if (!currentAuthority()) return;
        await rm(this.generationDirectory(root), { recursive: true, force: true });
      }
    }
  }

  private async removeOrphanStageDirectories(currentAuthority: () => boolean): Promise<void> {
    const names = await readdir(this.stateDir).catch(() => []);
    if (!currentAuthority()) return;
    for (const name of names) {
      if (!/^monitoring-checkpoint\.stage-[0-9a-f]{64}$/.test(name)) continue;
      if (!currentAuthority()) return;
      await rm(this.stageDirectory(name.slice("monitoring-checkpoint.stage-".length)), { recursive: true, force: true });
    }
  }

  private async verifyAcceptedChunks(manifest:MonitoringCheckpointManifest,currentAuthority:()=>boolean):Promise<boolean> {
    await assertPrivateDirectory(this.generationDirectory(manifest.rootDigest));
    for(const descriptor of manifest.chunks) {
      if(!currentAuthority())throw new Error("checkpoint.authority_changed");
      const bytes=await readPrivateFile(join(this.generationDirectory(manifest.rootDigest),`chunk-${descriptor.index}.bin`),MONITORING_CHECKPOINT_CAPABILITY.maxChunkBytes);
      if(!currentAuthority())throw new Error("checkpoint.authority_changed");
      if(bytes.length!==descriptor.bytes || sha256(bytes)!==descriptor.sha256)return false;
    }
    return true;
  }
  private async readAccepted(authority: CheckpointAuthority): Promise<AcceptedCheckpointHead | null> {
    try {
      const bytes = await readPrivateFile(join(this.stateDir, "monitoring-checkpoint.accepted.json"), MAX_MANIFEST_BYTES + 1024);
      const accepted=parseAcceptedCheckpointHead(parseMachineStrictJson(new TextDecoder("utf-8",{fatal:true}).decode(bytes)));
      if(accepted===null)throw new Error("checkpoint.accepted_corrupt");
      return checkpointMatchesAuthority(accepted.manifest,authority)?accepted:null;
    }catch(error){if(isMachineFileMissing(error))return null;throw error;}
  }

  private async readStage(path: string): Promise<{ manifest: MonitoringCheckpointManifest; startedAt: number; received: { index: number; sha256: string; bytes: number }[] } | null> {
    try {
      const stageBytes = await readPrivateFile(path, MAX_MANIFEST_BYTES);
      const stage = parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(stageBytes));
      if (!isRecord(stage)) return null;
      const manifest = parseMonitoringCheckpointManifest(stage.manifest);
      if (manifest === null || typeof stage.startedAt !== "number" || !Number.isSafeInteger(stage.startedAt) || !Array.isArray(stage.received)) return null;
      const received: { index: number; sha256: string; bytes: number }[] = [];
      for (let i = 0; i < stage.received.length; i++) {
        const item = stage.received[i];
        if (!isRecord(item) || item.index !== i || item.index >= manifest.chunks.length || item.sha256 !== manifest.chunks[i]?.sha256 || item.bytes !== manifest.chunks[i]?.bytes) return null;
        const descriptor=manifest.chunks[i]!;
        received.push({index:i,sha256:descriptor.sha256,bytes:descriptor.bytes});
      }
      return { manifest, startedAt: stage.startedAt, received };
    } catch { return null; }
  }

  private async removeStageFiles(_count: number): Promise<void> {
    let rootDigest: string | null = null;
    try {
      const stage = await this.readStage(join(this.stateDir, "monitoring-checkpoint.stage.json"));
      rootDigest = stage?.manifest.rootDigest ?? null;
    } catch { /* remove the fixed manifest below */ }
    await rm(join(this.stateDir, "monitoring-checkpoint.stage.json"), { force: true });
    if (rootDigest !== null) await rm(this.stageDirectory(rootDigest), { recursive: true, force: true });
  }
}

interface AcceptedCheckpointHead {readonly manifest:MonitoringCheckpointManifest;readonly generationBytes:number;readonly verified:boolean}
function checkpointVerificationDigest(manifest:MonitoringCheckpointManifest):string {
  return sha256(`collie.monitoring.cross-set-verified.jsonl-continuation.v1\0${manifest.rootDigest}`);
}
/** A legacy head retains custody, but cannot certify usable data until an admitted exact repair. */
function parseAcceptedCheckpointHead(raw:JsonValue | undefined):AcceptedCheckpointHead|null {
  if(!isRecord(raw))return null;
  const legacy=Object.keys(raw).length===2 && Object.keys(raw).every(key=>key==="manifest" || key==="generationBytes");
  if(!legacy && (Object.keys(raw).length!==4 || Object.keys(raw).some(key=>!["manifest","generationBytes","verifiedFormat","verificationDigest"].includes(key))))return null;
  const manifest=parseMonitoringCheckpointManifest(raw.manifest);
  if(manifest===null || typeof raw.generationBytes!=="number" || !Number.isSafeInteger(raw.generationBytes) ||
    raw.generationBytes!==manifest.chunks.reduce((sum,descriptor)=>sum+descriptor.bytes,0) || raw.generationBytes+Buffer.byteLength(JSON.stringify(manifest))>MONITORING_CHECKPOINT_CAPABILITY.maxGenerationBytes)return null;
  if(!legacy && (raw.verifiedFormat!==MONITORING_CHECKPOINT_RECORD_FORMAT || raw.verificationDigest!==checkpointVerificationDigest(manifest)))return null;
  return {manifest,generationBytes:raw.generationBytes,verified:!legacy};
}

interface MonitoringCheckpointStage {manifest:MonitoringCheckpointManifest;startedAt:number;received:{index:number;sha256:string;bytes:number}[]}
