import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { constants } from "node:fs";
import { mkdir, open, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { HOST } from "./host.ts";

import type { JsonObject, JsonValue } from "./json.ts";
import type { WorktreeCreateResponse } from "./types.ts";

// ── One receipt per phone request, so a retried create never makes a second worktree (ADR 0089) ──
//
// A worktree create can take a minute: Herdr runs `git worktree add`, opens a workspace, and the
// bridge may then wait for the new shell and type a launcher into it. A phone on a train loses that
// reply all the time. The operator taps again, and without a receipt the second tap is a second
// worktree on a second branch, or a refusal because the path is taken, while the first one sits
// there unseen.
//
// The phone mints a `requestId` (a UUID) per intent and sends it with the create. This bounded file
// keeps recent successful response bodies for convenient replay. The shared durable OperationStore
// separately holds non-evicting owner-bound intent tombstones before the multiplexer effect; if a
// response body was evicted, a retry answers unknown and never creates a second worktree. A create
// still in flight is joined, not repeated: the second request waits for the first one's answer.
//
// ── WHAT IS STORED, AND WHAT IS NOT ─────────────────────────────────────────────
// Ids, the checkout path, the branch and whether the launcher started. No command line, no screen
// text. A refusal stores nothing, because nothing was made: the same id may simply be tried again.
//
// ── BOUNDED ─────────────────────────────────────────────────────────────────────
// At most {@link MAX_RECEIPTS}; the oldest response body goes first. Non-evicting duplicate
// prevention belongs to OperationStore, not this cache. The file is written on a create and on
// nothing else, so a bridge nobody asks for a worktree writes no file at all (solo-baseline.test.ts
// counts the written entries).

/** The most receipts kept. A 201st drops the oldest. */
export const MAX_RECEIPTS = 200;

/** The receipt file's name inside the state dir. */
export const RECEIPTS_FILE = "worktree-receipts.json";

/** A canonical UUID, any version, any case. What the phone mints with 128 random bits. */
const REQUEST_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

/** Whether `raw` is a request id the route may key a receipt on. */
export function isRequestId(raw: JsonValue | undefined): raw is string {
  return typeof raw === "string" && REQUEST_ID.test(raw);
}

/** The record inside a parsed JSON value, or null when it is not one (a scalar, an array). */
function asJsonRecord(value: JsonValue | undefined): JsonObject | null {
  if (value === null || value === undefined || typeof value !== "object" || Array.isArray(value)) return null;
  return value;
}

/** What one successful create left behind. */
export interface WorktreeReceipt {
  requestId: string;
  /** Epoch ms of the create. */
  at: number;
  workspaceId: string;
  paneId: string;
  /** The checkout path, as the multiplexer reported the new pane's cwd. */
  path: string;
  branch: string;
  launcherStarted: boolean;
  /** Absent on pre-binding receipts; they cannot promise owner-bound replay. */
  binding?: IntentReceiptBinding;
}

/** Metadata which makes a receipt specific to one paired caller, operation, runtime, and payload. */
export interface IntentReceiptBinding extends JsonObject {
  deviceIdentity: string;
  /** Stable owning-host id for new B02 receipts; absent only on pre-binding rows. */
  owningHost?: string;
  operation: string;
  runtime: string;
  /** HMAC-SHA-256 over the canonical request body. The HMAC key is never stored in a receipt. */
  intentFingerprint: string;
}

export type ReceiptBindingMatch = "match" | "legacy-unbound" | "mismatch";

/** The protected state file holding the stable HMAC key used for receipt intent fingerprints. */
export const INTENT_RECEIPT_KEY_FILE = "intent-receipt-hmac-key";

/**
 * Read or create the server-owned key only when an owner-bound receipt is first needed. A malformed
 * or too-permissive existing file fails closed and is never replaced, preserving old fingerprints.
 */
export async function loadOrCreateIntentReceiptKey(stateDir: string): Promise<Uint8Array> {
  await mkdir(stateDir, { recursive: true, mode: 0o700 });
  const path = join(stateDir, INTENT_RECEIPT_KEY_FILE);
  try {
    return await readReceiptKey(path);
  } catch (error) {
    if (!(error instanceof Error) || !("code" in error) || error.code !== "ENOENT") throw error;
  }
  const key = randomBytes(32);
  let handle;
  try {
    handle = await open(path, "wx", 0o600);
    await handle.writeFile(key);
    await handle.sync();
    return key;
  } catch (error) {
    if (!(error instanceof Error) || !("code" in error) || error.code !== "EEXIST") throw error;
    return await readReceiptKey(path);
  } finally {
    await handle?.close();
  }
}

async function readReceiptKey(path: string): Promise<Uint8Array> {
  const noFollow = HOST.platform === "win32" ? 0 : constants.O_NOFOLLOW;
  const handle = await open(path, constants.O_RDONLY | noFollow);
  try {
    const metadata = await handle.stat();
    if (!metadata.isFile() || metadata.size !== 32) {
      throw new Error("intent receipt key is invalid; refusing owner-bound receipts");
    }
    if (HOST.platform !== "win32" && (metadata.mode & 0o077) !== 0) {
      throw new Error("intent receipt key must be private to the owner");
    }
    // Read at most one byte beyond the required key length. `readFile()` could allocate without
    // bound if the same inode grows after the metadata check; keeping the descriptor open also
    // means an attacker cannot swap a different pathname target between stat and read.
    const key = Buffer.alloc(33);
    const { bytesRead } = await handle.read(key, 0, key.byteLength, 0);
    const after = await handle.stat();
    if (bytesRead !== 32 || after.size !== 32 || !after.isFile()) {
      throw new Error("intent receipt key is invalid; refusing owner-bound receipts");
    }
    return key.subarray(0, 32);
  } finally {
    await handle.close();
  }
}

/**
 * Fingerprint a request body with a stable server-owned secret. Callers must supply a key which is
 * persisted in the owner's protected state directory; do not use a pairing token or a rotating key.
 */
export function keyedIntentFingerprint(key: Uint8Array, body: JsonValue): string {
  if (key.byteLength < 32) throw new Error("receipt fingerprint key must contain at least 256 bits");
  const canonical = canonicalJson(body);
  return createHmac("sha256", key).update("collie-intent-v1\0", "utf8").update(canonical, "utf8").digest("hex");
}

/** Stable JSON for the request's already-parsed JSON value: object key order is immaterial. */
export function canonicalJson(value: JsonValue): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return "[" + value.map((entry) => canonicalJson(entry)).join(",") + "]";
  const fields: string[] = [];
  for (const key of Object.keys(value).toSorted((a, b) => a < b ? -1 : a > b ? 1 : 0)) {
    const entry = value[key];
    if (entry === undefined) continue;
    fields.push(JSON.stringify(key) + ":" + canonicalJson(entry));
  }
  return "{" + fields.join(",") + "}";
}

/** Classify a replay without treating legacy ID-only receipts as an owner-bound guarantee. */
export function matchReceiptBinding(
  stored: IntentReceiptBinding | undefined,
  incoming: IntentReceiptBinding | undefined,
): ReceiptBindingMatch {
  if (stored === undefined) return "legacy-unbound";
  if (incoming === undefined) return "mismatch";
  const a = bindingBytes(stored);
  const b = bindingBytes(incoming);
  return a.length === b.length && timingSafeEqual(a, b) ? "match" : "mismatch";
}

function bindingBytes(binding: IntentReceiptBinding): Buffer {
  return Buffer.from(JSON.stringify([
    binding.deviceIdentity,
    binding.owningHost ?? null,
    binding.operation,
    binding.runtime,
    binding.intentFingerprint,
  ]), "utf8");
}

/**
 * What a route needs of a receipt store: one stored receipt per request id, and the answer still
 * being worked out for an id. Generic so a launch (ADR 0091) keeps its receipts on the same pattern
 * a worktree create does, in a file of its own.
 */
export interface ReceiptSurface<R extends { requestId: string }, A> {
  /** The receipt stored under `requestId`, if any. */
  get(requestId: string): R | undefined;
  /** Store one receipt. A failed durable write must reach the route as an unknown outcome. */
  record(receipt: R): Promise<void>;
  /** The answer a request with this id is still working on, if one is. */
  inflight(requestId: string): Promise<A> | undefined;
  /** Binding captured before dispatch, including while the success receipt is not yet durable. */
  inflightBinding(requestId: string): IntentReceiptBinding | undefined;
  /** Mark `answer` as the request in flight for `requestId` until it settles. */
  track(requestId: string, answer: Promise<A>, binding?: IntentReceiptBinding): void;
}

/** What the worktree route needs of the store. `bridge/server.ts` holds this, never the class. */
export type WorktreeReceiptSurface = ReceiptSurface<WorktreeReceipt, WorktreeCreateResponse>;

/** One receipt read off disk, or `null` when the entry is not one. */
function coerceReceipt(raw: JsonValue): WorktreeReceipt | null {
  const r = asJsonRecord(raw);
  if (r === null) return null;
  const { requestId, at, workspaceId, paneId, path, branch, launcherStarted, binding } = r;
  if (!isRequestId(requestId)) return null;
  if (typeof at !== "number" || !Number.isFinite(at)) return null;
  if (typeof workspaceId !== "string" || typeof paneId !== "string") return null;
  if (typeof path !== "string" || typeof branch !== "string") return null;
  if (typeof launcherStarted !== "boolean") return null;
  if (binding !== undefined && !isIntentReceiptBinding(binding)) return null;
  const receipt: WorktreeReceipt = { requestId, at, workspaceId, paneId, path, branch, launcherStarted };
  if (binding !== undefined) receipt.binding = binding;
  return receipt;
}

export function isIntentReceiptBinding(value: JsonValue): value is IntentReceiptBinding {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const binding: JsonObject = value;
  return typeof binding.deviceIdentity === "string" && binding.deviceIdentity !== "" && binding.deviceIdentity.length <= 2048 &&
    (binding.owningHost === undefined || (typeof binding.owningHost === "string" && binding.owningHost !== "" && binding.owningHost.length <= 2048)) &&
    typeof binding.operation === "string" && binding.operation !== "" && binding.operation.length <= 128 &&
    typeof binding.runtime === "string" && binding.runtime !== "" && binding.runtime.length <= 2048 &&
    typeof binding.intentFingerprint === "string" && /^[0-9a-f]{64}$/u.test(binding.intentFingerprint);
}

/** The receipts a parsed file holds, oldest first, capped, each read by `coerce`. */
export function coerceReceiptList<R>(raw: JsonValue, coerce: (entry: JsonValue) => R | null): R[] {
  const list = asJsonRecord(raw)?.receipts;
  if (!Array.isArray(list)) return [];
  const out: R[] = [];
  for (const entry of list) {
    const receipt = coerce(entry);
    if (receipt !== null) out.push(receipt);
  }
  return out.slice(-MAX_RECEIPTS);
}

/** The receipts a file holds, oldest first, capped. Anything that is not a receipt is dropped. */
export function coerceReceipts(raw: JsonValue): WorktreeReceipt[] {
  return coerceReceiptList(raw, coerceReceipt);
}

/** `receipts` with `receipt` appended (replacing an entry under the same id), capped oldest-first. */
export function withReceipt<R extends { requestId: string }>(receipts: readonly R[], receipt: R): R[] {
  const next = receipts.filter((r) => r.requestId !== receipt.requestId);
  next.push(receipt);
  return next.slice(-MAX_RECEIPTS);
}

type InflightEntry<A> = { answer: Promise<A>; binding?: IntentReceiptBinding };

/** The in-flight half, shared by every store: it never touches a file. */
class Inflight<A> {
  private readonly running = new Map<string, InflightEntry<A>>();

  get(requestId: string): Promise<A> | undefined {
    return this.running.get(requestId)?.answer;
  }

  binding(requestId: string): IntentReceiptBinding | undefined {
    return this.running.get(requestId)?.binding;
  }

  track(requestId: string, answer: Promise<A>, binding?: IntentReceiptBinding): void {
    const entry: InflightEntry<A> = { answer };
    if (binding !== undefined) entry.binding = binding;
    this.running.set(requestId, entry);
    const clear = () => {
      if (this.running.get(requestId)?.answer === answer) this.running.delete(requestId);
    };
    answer.then(clear, clear);
  }
}

/**
 * A file-backed receipt store. Writes are atomic and owner-only (a fresh 0600 temp file renamed over
 * the target) and queued, so two requests finishing together cannot interleave their writes.
 */
export class ReceiptFileStore<R extends { requestId: string }, A> implements ReceiptSurface<R, A> {
  private receipts: R[] = [];
  private queue: Promise<void> = Promise.resolve();
  private readonly running = new Inflight<A>();
  private readonly file: string;

  /** `file` is the full path, built by the subclass so each names its own `<stateDir>` entry. */
  constructor(
    private readonly stateDir: string,
    file: string,
    private readonly coerce: (entry: JsonValue) => R | null,
    private readonly warn: (line: string) => void = (line) => console.warn(line),
    private readonly tag = "receipts",
  ) {
    this.file = file;
  }

  /** Read the file once at start. A missing or broken file is an empty list, and nothing is written. */
  async load(): Promise<void> {
    try {
      // SAFETY: `Bun.file().json()` output IS a JsonValue by construction; `coerce` checks every field.
      this.receipts = coerceReceiptList((await Bun.file(this.file).json()) as JsonValue, this.coerce);
    } catch {
      /* nothing recorded yet, or a file that is not JSON; asking must not create the file */
    }
  }

  get(requestId: string): R | undefined {
    return this.receipts.find((r) => r.requestId === requestId);
  }

  async record(receipt: R): Promise<void> {
    this.receipts = withReceipt(this.receipts, receipt);
    try {
      await this.save();
    } catch (err) {
      this.warn(`[${this.tag}] could not save ${this.file}: ${err instanceof Error ? err.message : String(err)}`);
      // Keep the in-memory receipt to prevent a duplicate during this process, but tell the route
      // that restart recovery is not guaranteed. The native journal must retain an unknown outcome.
      throw err;
    }
  }

  inflight(requestId: string): Promise<A> | undefined {
    return this.running.get(requestId);
  }

  inflightBinding(requestId: string): IntentReceiptBinding | undefined {
    return this.running.binding(requestId);
  }

  track(requestId: string, answer: Promise<A>, binding?: IntentReceiptBinding): void {
    this.running.track(requestId, answer, binding);
  }

  /** Queue one write of whatever the list holds when its turn comes. */
  private save(): Promise<void> {
    const run = this.queue.then(() => this.write());
    this.queue = run.catch(() => {});
    return run;
  }

  private async write(): Promise<void> {
    await mkdir(this.stateDir, { recursive: true, mode: 0o700 });
    const tmp = `${this.file}.tmp`;
    await writeFile(tmp, JSON.stringify({ receipts: this.receipts }, null, 2), { mode: 0o600 });
    await rename(tmp, this.file);
  }
}

/** The worktree store `bridge/index.ts` builds once per bridge. */
export class WorktreeReceiptStore extends ReceiptFileStore<WorktreeReceipt, WorktreeCreateResponse> {
  constructor(stateDir: string, warn: (line: string) => void = (line) => console.warn(line)) {
    super(stateDir, join(stateDir, RECEIPTS_FILE), coerceReceipt, warn, "worktree");
  }
}

/**
 * A store with no file: the default when the server is built without one, which is every test that
 * builds a server by hand. Replays work for the life of the process and are lost on restart.
 */
export function memoryReceipts<R extends { requestId: string }, A>(): ReceiptSurface<R, A> {
  let receipts: R[] = [];
  const running = new Inflight<A>();
  return {
    get: (requestId) => receipts.find((r) => r.requestId === requestId),
    record: (receipt) => {
      receipts = withReceipt(receipts, receipt);
      return Promise.resolve();
    },
    inflight: (requestId) => running.get(requestId),
    inflightBinding: (requestId) => running.binding(requestId),
    track: (requestId, answer, binding) => running.track(requestId, answer, binding),
  };
}

/** {@link memoryReceipts} for the worktree route. */
export function memoryWorktreeReceipts(): WorktreeReceiptSurface {
  return memoryReceipts<WorktreeReceipt, WorktreeCreateResponse>();
}
