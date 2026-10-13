import type { JsonValue } from "../json.ts";
import { isObject, NATIVE_ACCESS_LIMITS } from "./contracts.ts";
import type { PrivateJsonIo } from "./private-io.ts";
interface Replay { readonly hash: string; readonly expiresAt: number }
/** Persist before forwarding. Restart and a bounded/full registry never forgive a live proof. */
export class ProofReplayStore {
  constructor(private readonly io: PrivateJsonIo, private readonly now: () => number = Date.now) {}
  consume(hash: string, lifetimeMs = 90_000): boolean {
    return this.io.exclusive(() => {
      const raw = this.io.read(); const rows: Replay[] = [];
      if (raw !== null) {
        if (!Array.isArray(raw) || raw.length > NATIVE_ACCESS_LIMITS.replayEntries) throw new Error("native.replay_store");
        for (const row of raw) {
          if (!isObject(row) || Object.keys(row).length !== 2 || typeof row.hash !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(row.hash) || typeof row.expiresAt !== "number" || !Number.isSafeInteger(row.expiresAt)) throw new Error("native.replay_store");
          if (row.expiresAt > this.now()) rows.push({ hash: row.hash, expiresAt: row.expiresAt });
        }
      }
      if (rows.some((row) => row.hash === hash)) return false;
      if (rows.length >= NATIVE_ACCESS_LIMITS.replayEntries || !/^[A-Za-z0-9_-]{43}$/u.test(hash) || !Number.isSafeInteger(lifetimeMs) || lifetimeMs < 1 || lifetimeMs > 90_000) throw new Error("native.replay_bound");
      rows.push({ hash, expiresAt: this.now() + lifetimeMs });
      // SAFETY: constructed only from the validated exact two-field metadata projection.
      this.io.write(JSON.parse(JSON.stringify(rows)) as JsonValue); return true;
    });
  }
}
