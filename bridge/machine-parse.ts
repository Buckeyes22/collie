// Every untrusted value the machines feature reads, in one file (ADR 0084).
//
// Four readers, and each is the parse at its boundary: a member's `machineStats` sibling off the crew
// link, the body of `POST /api/machines/:id/alerts`, and the two state files, `machine-history.json`
// and `machine-alerts.json`. They live together for the reason `stt/json.ts` is one file: every
// `typeof` the feature needs sits in here, so the lint override names this file and no feature module.
//
// Each reader returns a value the rest of the feature can trust, or `null` (or an empty map for a
// state file). A state file that does not parse is read as empty: history is a cache of the last day,
// and an unreadable rule file means no rule, which is the closed reading (it pushes nothing).

import type { JsonObject, JsonValue } from "./json.ts";
import { MachineHistory, MINUTE_MS, HISTORY_MINUTES, type LoadedMinute } from "./machine-history.ts";
import { monitoringRecordsFromSnapshot } from "./crew/monitoring-checkpoint-records.ts";
import { slotFor, eventBytes, hashText, lengthHash, outboxUsage, MACHINE_MONITORING_OUTBOX_MAX_BYTES, MACHINE_RULE_REQUEST_RECEIPTS_MAX, type MutableMonitoringGeneration, type MonitoringEvent, type RuleRequestReceipt, type MonitoringMember, type MonitoringTombstone } from "./machine-alerts.ts";
const UINT64_MAX = 0xffff_ffff_ffff_ffffn;
import { MAX_DISKS } from "./machine-disks.ts";
import { CREW_MACHINE_FIELD, isMachineSample } from "./machine-stats.ts";
import type { AlertMetric, AlertRule, MachineAlerts, MachineDisk, MachineSample } from "./types.ts";

/** The bounds of a rule, as the phone offers them and the bridge accepts them. */
export const ALERT_ABOVE_MIN = 0.5;
export const ALERT_ABOVE_MAX = 0.99;
export const ALERT_FOR_MIN_MIN = 5;
export const ALERT_FOR_MIN_MAX = 120;
export const MACHINE_RULE_BODY_MAX_BYTES = 16 * 1024;

const METRICS: readonly AlertMetric[] = ["cpu", "mem", "disk"];

function recordOf(value: JsonValue | undefined): JsonObject | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
}

function numberOf(value: JsonValue | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * Read a member's `machineStats` off the answer its snapshot rode on (CREW_PROTOCOL.md §5, §7.1).
 *
 * `null` for every shape this build cannot trust: absent (a member older than the field), not an
 * object, a key of the wrong type, or any number that is not finite or not in range. `null` means
 * **not reported**, never zero. The whole sample is dropped rather than repaired: a reading with one
 * impossible number has told us its sender is broken, and the other numbers came from the same place.
 * Unknown keys are ignored, so a later build may add one without this build refusing it (§7.1).
 */
export function parsePeerMachineStats(value: JsonValue): MachineSample | null {
  const field = recordOf(recordOf(value)?.[CREW_MACHINE_FIELD]);
  if (field === null) return null;
  const cpu = numberOf(field.cpu);
  const cores = numberOf(field.cores);
  const memUsed = numberOf(field.memUsed);
  const memTotal = numberOf(field.memTotal);
  if (cpu === null || cores === null || memUsed === null || memTotal === null) return null;
  const sample: MachineSample = { cpu, cores, memUsed, memTotal };
  for (const key of ["load1", "rxBps", "txBps"] as const) {
    if (field[key] === undefined) continue;
    const n = numberOf(field[key]);
    if (n === null) return null;
    sample[key] = n;
  }
  if (field.disks !== undefined) {
    const disks = disksOf(field.disks);
    if (disks === null) return null;
    // An empty list says what an absent one says: nothing to report.
    if (disks.length > 0) sample.disks = disks;
  }
  return isMachineSample(sample) ? sample : null;
}

/**
 * A member's `disks`: an array of `{ mount, used, total }`. `null` (the whole sample drops) for any
 * entry that is not that shape; the ranges are `isMachineSample`'s. A later build that sends more
 * than {@link MAX_DISKS} is cut to the first ones rather than refused.
 */
function disksOf(value: JsonValue): MachineDisk[] | null {
  if (!Array.isArray(value)) return null;
  const out: MachineDisk[] = [];
  for (const entry of value.slice(0, MAX_DISKS)) {
    const rec = recordOf(entry);
    const used = numberOf(rec?.used);
    const total = numberOf(rec?.total);
    const mount = rec?.mount;
    if (rec === null || typeof mount !== "string" || used === null || total === null) return null;
    out.push({ mount, used, total });
  }
  return out;
}

/** One rule, or `null` when it is outside the bounds the phone offers. */
function ruleOf(value: JsonValue | undefined): AlertRule | null {
  const rec = recordOf(value);
  if (rec === null) return null;
  const above = numberOf(rec.above);
  const forMin = numberOf(rec.forMin);
  if (above === null || above < ALERT_ABOVE_MIN || above > ALERT_ABOVE_MAX) return null;
  if (forMin === null || !Number.isInteger(forMin) || forMin < ALERT_FOR_MIN_MIN || forMin > ALERT_FOR_MIN_MAX) {
    return null;
  }
  return { above, forMin };
}

/**
 * The body of `POST /api/machines/:id/alerts`: the WHOLE rule set for one machine.
 *
 * A missing key removes that rule. A present key must be a rule inside the bounds, or the whole body
 * is refused (`null`, a 400): a half-applied write would leave the phone showing a rule the bridge
 * did not take. Unknown keys are ignored, the way `parseNotifyPrefsPatch` ignores them.
 */
export function parseMachineAlerts(value: JsonValue | undefined): MachineAlerts | null {
  const rec = recordOf(value);
  if (rec === null) return null;
  const out: MachineAlerts = {};
  for (const metric of METRICS) {
    if (rec[metric] === undefined) continue;
    const rule = ruleOf(rec[metric]);
    if (rule === null) return null;
    out[metric] = rule;
  }
  return out;
}

export interface MachineRuleRequest {
  readonly alerts: MachineAlerts;
  readonly rulesRevision?: string;
  readonly machineIncarnation?: string;
  readonly requestId?: string;
  readonly extensions?: JsonObject;
}

/**
 * Closed machine-rule mutation shape. Old bridge clients may omit all concurrency fields; a request
 * that supplies any of them must supply the complete revision/incarnation/request-id triplet.
 */
export function parseMachineRuleRequest(value: JsonValue | undefined): MachineRuleRequest | null {
  const rec = recordOf(value);
  if (rec === null) return null;
  const allowed = new Set(["cpu", "mem", "disk", "rulesRevision", "machineIncarnation", "requestId", "extensions"]);
  if (Object.keys(rec).some((key) => !allowed.has(key))) return null;
  const alerts = parseMachineAlerts(rec);
  if (alerts === null) return null;
  for (const metric of METRICS) {
    const candidate = rec[metric];
    if (candidate === undefined) continue;
    const rule = recordOf(candidate);
    if (rule === null || Object.keys(rule).some((key) => key !== "above" && key !== "forMin")) return null;
  }
  const revision = rec.rulesRevision;
  const incarnation = rec.machineIncarnation;
  const requestId = rec.requestId;
  const hasConcurrency = revision !== undefined || incarnation !== undefined || requestId !== undefined;
  if (hasConcurrency) {
    if (typeof revision !== "string" || !/^(0|[1-9][0-9]{0,19})$/.test(revision) || !uint64Decimal(revision)) return null;
    if (typeof incarnation !== "string" || !/^[0-9a-f]{64}$/.test(incarnation)) return null;
    if (typeof requestId !== "string" || !/^[A-Za-z0-9_-]{16,96}$/.test(requestId)) return null;
  }
  const extensions = rec.extensions;
  if (extensions !== undefined) {
    const ext = recordOf(extensions);
    if (ext === null || !validExtensionTree(ext, 4, { left: 64 })) return null;
    if (Buffer.byteLength(JSON.stringify(ext), "utf8") > 4096) return null;
  }
  const result:MutableMachineRuleRequest={alerts};
  if(typeof revision==="string")result.rulesRevision=revision;
  if(typeof incarnation==="string")result.machineIncarnation=incarnation;
  if(typeof requestId==="string")result.requestId=requestId;
  if(extensions!==undefined){const ext=recordOf(extensions);if(ext===null)return null;result.extensions=ext;}
  return result;
}

function uint64Decimal(value: string): boolean {
  try {
    return BigInt(value) <= 0xffff_ffff_ffff_ffffn;
  } catch {
    return false;
  }
}

function validExtensionTree(value: JsonValue | undefined, depth: number, budget: { left: number }): boolean {
  if (value === undefined || budget.left-- <= 0 || depth < 0) return false;
  if (Array.isArray(value)) return value.every((v) => validExtensionTree(v, depth - 1, budget));
  if (typeof value !== "object" || value === null)
    return (typeof value !== "number" || Number.isFinite(value)) && (typeof value !== "string" || !hasUnpairedSurrogate(value));
  const entries = Object.entries(value);
  return entries.every(([key, v]) => key.length <= 128 && !hasUnpairedSurrogate(key) && validExtensionTree(v, depth - 1, budget));
}

/** Parse bounded UTF-8 JSON while rejecting duplicate keys and unpaired UTF-16 surrogates. */
export function parseMachineStrictJson(source: string): JsonValue | null {
  let i = 0;
  const ws = (): void => { while (i < source.length && [9,10,13,32].includes(source.charCodeAt(i))) i++; };
  const string = (): string | null => {
    const start = i;
    if (source[i] !== '"') return null;
    i++;
    while (i < source.length) {
      const c = source.charCodeAt(i++);
      if (c === 0x22) {
        try {
          const decoded:string=JSON.parse(source.slice(start,i));
          return hasUnpairedSurrogate(decoded) ? null : decoded;
        } catch { return null; }
      }
      if (c < 0x20) return null;
      if (c === 0x5c) {
        if (i >= source.length) return null;
        const escaped = source[i++]!;
        if (escaped === "u") {
          if (!/^[0-9a-fA-F]{4}$/.test(source.slice(i, i + 4))) return null;
          i += 4;
        } else if (!'"\\/bfnrt'.includes(escaped)) return null;
      }
    }
    return null;
  };
  const value = (depth: number): boolean => {
    if (depth > 32) return false;
    ws();
    if (source[i] === '"') return string() !== null;
    if (source[i] === "{") {
      i++; ws();
      const keys = new Set<string>();
      if (source[i] === "}") { i++; return true; }
      while (i < source.length) {
        ws(); const key = string();
        if (key === null || keys.has(key)) return false;
        keys.add(key); ws();
        if (source[i++] !== ":" || !value(depth + 1)) return false;
        ws(); const c = source[i++];
        if (c === "}") return true;
        if (c !== ",") return false;
      }
      return false;
    }
    if (source[i] === "[") {
      i++; ws(); if (source[i] === "]") { i++; return true; }
      while (i < source.length) {
        if (!value(depth + 1)) return false;
        ws(); const c = source[i++];
        if (c === "]") return true;
        if (c !== ",") return false;
      }
      return false;
    }
    const start = i;
    while (i < source.length && !/[\s,\]}]/.test(source[i]!)) i++;
    if (start === i) return false;
    try {
      const token:JsonValue=JSON.parse(source.slice(start,i));
      return typeof token !== "number" || Number.isFinite(token);
    } catch { return false; }
  };
  if (!value(0)) return null;
  ws();
  if (i !== source.length) return null;
  try { return JSON.parse(source); } catch { return null; }
}

function hasUnpairedSurrogate(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = value.charCodeAt(i + 1);
      if (i + 1 >= value.length || next < 0xdc00 || next > 0xdfff) return true;
      i++;
    } else if (c >= 0xdc00 && c <= 0xdfff) return true;
  }
  return false;
}

/**
 * `machine-history.json` as minutes per machine, oldest first. Two versions load: version 2, the
 * compact rows `machine-history.ts` writes, and version 1, the nine sums per minute the first 1.17.0
 * builds wrote. A row that is not the right count of finite numbers is dropped; the rest of the file
 * still loads.
 */
export function coerceHistoryFile(raw: JsonValue | undefined, maxMinutes: number): Map<string, LoadedMinute[]> {
  const out = new Map<string, LoadedMinute[]>();
  const file = recordOf(raw);
  const machines = recordOf(file?.machines);
  if (machines === null) return out;
  const version = numberOf(file?.version);
  for (const [id, entry] of Object.entries(machines)) {
    const minutes = version === 2 ? minutesOfV2(entry) : minutesOfV1(entry);
    if (minutes.length > 0) out.set(id, minutes.toSorted((a, b) => a.t - b.t).slice(-maxMinutes));
  }
  return out;
}

/** A fraction off disk, or `null` when it is not one. */
function fractionOf(value: JsonValue | undefined): number | null {
  const n = numberOf(value);
  return n !== null && n >= 0 && n <= 1 ? n : null;
}

/** A rate off disk: a non-negative number, or `null` for "no reading" (and for anything else). */
function rateOf(value: JsonValue | undefined): number | null {
  const n = numberOf(value);
  return n !== null && n >= 0 ? n : null;
}

/**
 * Version 2: `{ t, n, rows: [[gap, cpu, cpuMax, mem, rx | null, tx | null, disk?], ...] }`. The
 * seventh value is the fullest disk's fraction, absent on a minute with none and in every file
 * written before it.
 */
function minutesOfV2(entry: JsonValue | undefined): LoadedMinute[] {
  const rec = recordOf(entry);
  const start = numberOf(rec?.t);
  const lastN = numberOf(rec?.n);
  const rows = rec?.rows;
  if (rec === null || start === null || !Array.isArray(rows)) return [];
  const out: LoadedMinute[] = [];
  let t = start;
  rows.forEach((row, index) => {
    if (!Array.isArray(row) || (row.length !== 6 && row.length !== 7)) return;
    const gap = numberOf(row[0]);
    if (gap === null || !Number.isInteger(gap) || gap < 0) return;
    t += gap * 60_000;
    const cpu = fractionOf(row[1]);
    const cpuMax = fractionOf(row[2]);
    const mem = fractionOf(row[3]);
    if (cpu === null || cpuMax === null || mem === null) return;
    const n = index === rows.length - 1 && lastN !== null && Number.isInteger(lastN) && lastN > 0 ? lastN : 1;
    out.push({ t, n, cpu, cpuMax, mem, rx: rateOf(row[4]), tx: rateOf(row[5]), disk: fractionOf(row[6]) });
  });
  return out;
}

/** Version 1: one row per minute, `[t, n, cpuSum, cpuMax, memSum, rxSum, rxN, txSum, txN]`. */
function minutesOfV1(entry: JsonValue | undefined): LoadedMinute[] {
  if (!Array.isArray(entry)) return [];
  const out: LoadedMinute[] = [];
  for (const row of entry) {
    if (!Array.isArray(row) || row.length !== 9) continue;
    const [t, n, cpuSum, cpuMax, memSum, rxSum, rxN, txSum, txN] = row.map((v) => numberOf(v));
    if (t == null || n == null || cpuSum == null || cpuMax == null || memSum == null) continue;
    if (rxSum == null || rxN == null || txSum == null || txN == null) continue;
    if (!Number.isInteger(n) || n <= 0) continue;
    out.push({
      t,
      n,
      cpu: cpuSum / n,
      cpuMax,
      mem: memSum / n,
      rx: rxN > 0 ? rxSum / rxN : null,
      tx: txN > 0 ? txSum / txN : null,
      disk: null,
    });
  }
  return out;
}

/** One machine's stored rules and the episodes open for it. */
export interface StoredMachineAlerts {
  readonly rules: MachineAlerts;
  readonly open: readonly AlertMetric[];
  readonly rulesRevision?: string;
  readonly machineIncarnation?: string;
  readonly extensions?: JsonObject;
}

/**
 * `machine-alerts.json`. A rule outside the bounds is dropped on its own; an open episode for a metric
 * that has no rule is dropped too, because nothing could ever close it.
 */
export function coerceAlertsFile(raw: JsonValue | undefined): Map<string, StoredMachineAlerts> {
  const out = new Map<string, StoredMachineAlerts>();
  const machines = recordOf(recordOf(raw)?.machines);
  if (machines === null) return out;
  for (const [id, entry] of Object.entries(machines)) {
    const rec = recordOf(entry);
    const rulesRec = recordOf(rec?.rules);
    if (rec === null || rulesRec === null) continue;
    const rules: MachineAlerts = {};
    for (const metric of METRICS) {
      const rule = ruleOf(rulesRec[metric]);
      if (rule !== null) rules[metric] = rule;
    }
    const openRaw = Array.isArray(rec.open) ? rec.open : [];
    const open = METRICS.filter((m) => openRaw.includes(m) && rules[m] !== undefined);
    if (METRICS.some((m) => rules[m] !== undefined)) out.set(id, { rules, open });
  }
  return out;
}

// Authoritative monitoring generation readers: all raw JSON is narrowed here.
function validCounter(value: JsonValue | undefined): value is string {
  return typeof value === "string" && /^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= UINT64_MAX;
}
export function parseMonitoringEvent(value: JsonValue | undefined, lineage: string): MonitoringEvent | null {
  if (!isRecord(value) || Object.keys(value).some((key) => !["schema", "eventId", "producerLineage", "slot", "producerSequence", "machineId", "machineIncarnation", "metric", "rulesRevision", "episodeId", "kind", "observedAt", "payloadHash", "message"].includes(key))) return null;
  if (value.schema !== 1 || value.producerLineage !== lineage || typeof value.eventId !== "string" || !/^[0-9a-f]{64}$/.test(value.eventId) ||
    typeof value.slot !== "string" || !/^[0-9a-f]{64}$/.test(value.slot) || !validCounter(value.producerSequence) || value.producerSequence === "0" ||
    typeof value.machineId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value.machineId) ||
    typeof value.machineIncarnation !== "string" || !/^[0-9a-f]{64}$/.test(value.machineIncarnation) ||
    !isAlertMetric(value.metric) || !validCounter(value.rulesRevision) ||
    typeof value.episodeId !== "string" || !/^[0-9a-f]{64}$/.test(value.episodeId) ||
    (value.kind !== "alert" && value.kind !== "clear") || typeof value.observedAt !== "number" || !Number.isSafeInteger(value.observedAt) || value.observedAt < 0 ||
    typeof value.payloadHash !== "string" || !/^[0-9a-f]{64}$/.test(value.payloadHash) || !isRecord(value.message)) return null;
  const message = value.message;
  if (value.kind === "clear") {
    if (Object.keys(message).length !== 2 || message.type !== "clear" || message.tag !== `collie:machine:${value.machineId}:${value.metric}`) return null;
  } else if (Object.keys(message).length !== 8 || message.type !== "machine" || message.tag !== `collie:machine:${value.machineId}:${value.metric}` ||
    typeof message.titleCode !== "string" || !["machine.cpu", "machine.mem", "machine.disk"].includes(message.titleCode) ||
    typeof message.title !== "string" || Buffer.byteLength(message.title) > 640 || typeof message.body !== "string" || Buffer.byteLength(message.body) > 2048 ||
    message.machine !== value.machineId || typeof message.topic !== "string" || message.renotify !== true) return null;
  if (value.slot !== slotFor(value.machineIncarnation, value.metric) ||
    value.payloadHash !== hashText(JSON.stringify(message)) ||
    value.eventId !== lengthHash("collie.monitoring.event.v1", lineage, value.slot, value.producerSequence, value.payloadHash)) return null;
  // SAFETY: Every closed event/message field, metric, digest, slot and lineage binding was validated above.
  return value as MonitoringEvent;
}


/** One authoritative per-slot event/sequence/episode predicate, shared with checkpoint verification.
 * Events have already passed parseMonitoringEvent; retaining at most two original events is bounded. */
export function monitoringSlotCustodyValid(
  events:readonly MonitoringEvent[], highWater:string|undefined,
  reservation:Readonly<{sequence:string;episodeId:string}>|undefined,
):boolean {
  if(events.length>2 || highWater===undefined || !validCounter(highWater) ||
    events.some(event=>!validCounter(event.producerSequence) || event.producerSequence==="0" || BigInt(event.producerSequence)>BigInt(highWater)))return false;
  if(reservation!==undefined && (!validCounter(reservation.sequence) || reservation.sequence==="0" || reservation.sequence!==highWater))return false;
  if(events.length===2){
    const first=events[0]!,second=events[1]!;
    const opened=BigInt(first.producerSequence)<BigInt(second.producerSequence)?first:second;
    const cleared=opened===first?second:first;
    return reservation===undefined && opened.kind==="alert" && cleared.kind==="clear" &&
      BigInt(cleared.producerSequence)===BigInt(opened.producerSequence)+1n &&
      opened.slot===cleared.slot && opened.producerLineage===cleared.producerLineage &&
      opened.episodeId===cleared.episodeId && opened.machineId===cleared.machineId &&
      opened.machineIncarnation===cleared.machineIncarnation && opened.metric===cleared.metric;
  }
  const event=events[0];
  if(event===undefined)return true;
  if(event.kind==="clear")return reservation===undefined;
  return reservation!==undefined && BigInt(reservation.sequence)===BigInt(event.producerSequence)+1n && reservation.episodeId===event.episodeId;
}


function isRecord(value: JsonValue | undefined): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validStoredExtensionTree(value: JsonValue | undefined, depth: number, budget: { left: number }): boolean {
  if (budget.left-- <= 0 || depth < 0) return false;
  if (Array.isArray(value)) return value.every((item) => validStoredExtensionTree(item, depth - 1, budget));
  if (!isRecord(value)) return value === null || typeof value === "string" || typeof value === "boolean" || (typeof value === "number" && Number.isFinite(value));
  return Object.entries(value).every(([key, item]) => key.length <= 128 && validStoredExtensionTree(item, depth - 1, budget));
}

export function parseVersion2AlertsFile(raw: JsonValue, now: number, indexParent?: string): { lineage: string; checkpointRevision: string; entries: Map<string, StoredMachineAlerts>; receipts: RuleRequestReceipt[]; history: Record<string, import("./machine-history.ts").LoadedMinute[]>; evaluatedThrough: number | null; outbox: MonitoringEvent[]; producerSequences: Record<string, string>; reservedClear: Record<string, { sequence: string; episodeId: string }>; roster: MonitoringMember[]; tombstones: MonitoringTombstone[]; historyOwners: Record<string,string>; createdAt: number } | null {
  if (!isRecord(raw) || raw.schema !== 1 || typeof raw.lineage !== "string" || !/^[0-9a-f]{64}$/.test(raw.lineage)) return null;
  if (Object.keys(raw).some((key) => !["createdAt", "roster", "historyOwners", "tombstones", "schema", "checkpointRevision", "lineage", "machines", "receipts", "history", "evaluatedThrough", "outbox", "producerSequences", "reservedClear"].includes(key))) return null;
  if(raw.createdAt!==undefined && (typeof raw.createdAt!=="number" || !Number.isSafeInteger(raw.createdAt) || raw.createdAt<0)) return null;
  if(raw.roster!==undefined) {
    if(!Array.isArray(raw.roster) || !Array.isArray(raw.tombstones) || !isRecord(raw.historyOwners)) return null;
    try {
      const roster=raw.roster.map(member=>{
        if(!isRecord(member) || typeof member.id!=="string" || typeof member.incarnation!=="string" || !/^[0-9a-f]{64}$/.test(member.incarnation) ||
          Object.keys(member).some(key=>!["id","incarnation","fingerprint","enrolledAt","solo"].includes(key))) throw new Error("machine.roster_invalid");
        return member;
      });
      const crewRoster=roster.filter(member=>member.solo!==true);
      if(crewRoster.length===roster.length) {
        const owners:{id:string;fingerprint:string;enrolledAt:number}[]=[];
        for(const member of crewRoster){
          if(typeof member.id!=="string" || typeof member.fingerprint!=="string" || typeof member.enrolledAt!=="number")return null;
          owners.push({id:member.id,fingerprint:member.fingerprint,enrolledAt:member.enrolledAt});
        }
        const records=monitoringRecordsFromSnapshot(raw,owners,indexParent);
        if(records===null) return null;
        try {const verifier=records.createVerifier();for(const item of records) if(!verifier.add(item)) return null;if(verifier.finish()===null) return null;}
        finally {records.dispose();}
      }
    } catch {return null;}
  }
  const parsedRoster: MonitoringMember[]=[];
  const parsedTombstones: MonitoringTombstone[]=[];
  const parsedHistoryOwners: Record<string,string>={};
  if(raw.roster!==undefined) {
    if(!Array.isArray(raw.roster) || !Array.isArray(raw.tombstones) || !isRecord(raw.historyOwners))return null;
    for(const member of raw.roster) {
      if(!isRecord(member) || typeof member.id!=="string" || typeof member.incarnation!=="string" ||
        member.fingerprint!==undefined && (typeof member.fingerprint!=="string" || !/^[0-9a-f]{64}$/.test(member.fingerprint)) ||
        member.enrolledAt!==undefined && (typeof member.enrolledAt!=="number" || !Number.isSafeInteger(member.enrolledAt) || member.enrolledAt<0) ||
        member.solo!==undefined && typeof member.solo!=="boolean")return null;
      const parsed: MonitoringMember={id:member.id,incarnation:member.incarnation};
      if(member.fingerprint!==undefined)Object.assign(parsed,{fingerprint:member.fingerprint});
      if(member.enrolledAt!==undefined)Object.assign(parsed,{enrolledAt:member.enrolledAt});
      if(member.solo!==undefined)Object.assign(parsed,{solo:member.solo});
      parsedRoster.push(parsed);
    }
    for(const item of raw.tombstones) {
      if(!isRecord(item) || item.kind!=="tombstone" || typeof item.memberId!=="string" || typeof item.fingerprint!=="string" ||
        typeof item.enrolledAt!=="number" || typeof item.machineIncarnation!=="string" || typeof item.rulesRevision!=="string")return null;
      parsedTombstones.push({kind:"tombstone",memberId:item.memberId,fingerprint:item.fingerprint,enrolledAt:item.enrolledAt,machineIncarnation:item.machineIncarnation,rulesRevision:item.rulesRevision});
    }
    for(const [id,incarnation]of Object.entries(raw.historyOwners)) {
      if(typeof incarnation!=="string" || !/^[0-9a-f]{64}$/.test(incarnation))return null;
      parsedHistoryOwners[id]=incarnation;
    }
  }
  const checkpointRevision = raw.checkpointRevision === undefined ? "0" : raw.checkpointRevision;
  if (!validCounter(checkpointRevision)) return null;
  if (!isRecord(raw.machines) || !Array.isArray(raw.receipts) || raw.receipts.length > MACHINE_RULE_REQUEST_RECEIPTS_MAX) return null;
  const entries = new Map<string, StoredMachineAlerts>();
  for (const [id, value] of Object.entries(raw.machines)) {
    if (!isRecord(value) || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return null;
    if (Object.keys(value).some((key) => !["rules", "open", "rulesRevision", "machineIncarnation", "extensions"].includes(key))) return null;
    if (!isRecord(value.rules) || Object.keys(value.rules).some((metric) => !isAlertMetric(metric)) ||
      Object.values(value.rules).some((rule) => !isRecord(rule) || Object.keys(rule).some((key) => key !== "above" && key !== "forMin"))) return null;
    const rules = parseMachineAlerts(value.rules);
    const open = value.open;
    const revision = value.rulesRevision;
    const incarnation = value.machineIncarnation;
    if (rules === null || !Array.isArray(open) || !open.every(isAlertMetric) ||
      (revision !== undefined && (typeof revision !== "string" || !/^(0|[1-9][0-9]{0,19})$/.test(revision) || BigInt(revision) > UINT64_MAX)) ||
      (incarnation !== undefined && (typeof incarnation !== "string" || !/^[0-9a-f]{64}$/.test(incarnation))) ||
      new Set(open).size !== open.length || (open).some((metric) => rules[metric] === undefined)) return null;
    const extensions = value.extensions;
    if (extensions !== undefined && (!isRecord(extensions) || !validStoredExtensionTree(extensions, 4, { left: 64 }) || Buffer.byteLength(JSON.stringify(extensions)) > 4096)) return null;
    const stored:StoredMachineAlerts={rules,open:[...new Set(open)]};
    if(typeof revision==="string")Object.assign(stored,{rulesRevision:revision});
    if(typeof incarnation==="string")Object.assign(stored,{machineIncarnation:incarnation});
    if(extensions!==undefined)Object.assign(stored,{extensions});
    entries.set(id,stored);
  }
  const receipts: RuleRequestReceipt[] = [];
  const seenRequestIds = new Set<string>();
  for (const item of raw.receipts) {
    if (!isRecord(item) || typeof item.requestId !== "string" || typeof item.actorHash !== "string" || !/^[0-9a-f]{64}$/.test(item.actorHash) ||
      !/^[A-Za-z0-9_-]{16,96}$/.test(item.requestId) || seenRequestIds.has(item.requestId) ||
      typeof item.machineId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(item.machineId) ||
      typeof item.requestHash !== "string" || !/^[0-9a-f]{64}$/.test(item.requestHash) || !isRecord(item.result) ||
      Object.keys(item).some((key) => !["requestId", "actorHash", "machineId", "requestHash", "result"].includes(key))) return null;
    seenRequestIds.add(item.requestId);
    const result = item.result;
    if (Object.keys(result).some((key) => !["rules", "open", "rulesRevision", "machineIncarnation", "cleared", "extensions"].includes(key))) return null;
    if (!isRecord(result.rules) || Object.keys(result.rules).some((metric) => !isAlertMetric(metric)) ||
      Object.values(result.rules).some((rule) => !isRecord(rule) || Object.keys(rule).some((key) => key !== "above" && key !== "forMin"))) return null;
    const rules = parseMachineAlerts(result.rules);
    if (rules === null || !Array.isArray(result.open) || !result.open.every(isAlertMetric) ||
      typeof result.rulesRevision !== "string" || !/^(0|[1-9][0-9]{0,19})$/.test(result.rulesRevision) || BigInt(result.rulesRevision) > UINT64_MAX ||
      typeof result.machineIncarnation !== "string" || !/^[0-9a-f]{64}$/.test(result.machineIncarnation) ||
      new Set(result.open).size !== result.open.length || (result.open).some((metric) => rules[metric] === undefined) ||
      !Array.isArray(result.cleared) || !result.cleared.every(isAlertMetric) || new Set(result.cleared).size !== result.cleared.length) return null;
    if (result.extensions !== undefined && (!isRecord(result.extensions) || !validStoredExtensionTree(result.extensions, 4, { left: 64 }) || Buffer.byteLength(JSON.stringify(result.extensions)) > 4096)) return null;
    const storedResult:RuleRequestReceipt["result"]={rules,open:result.open,rulesRevision:result.rulesRevision,machineIncarnation:result.machineIncarnation,cleared:result.cleared};
    if(result.extensions!==undefined)Object.assign(storedResult,{extensions:result.extensions});
    receipts.push({requestId:item.requestId,actorHash:item.actorHash,machineId:item.machineId,requestHash:item.requestHash,result:storedResult});
  }
  if (raw.history === undefined || !isRecord(raw.history)) return null;
  const history = MachineHistory.fromMonitoringState(raw.history, now);
  if (history === null) return null;
  const evaluatedThrough = raw.evaluatedThrough;
  if (!(evaluatedThrough === null || (typeof evaluatedThrough === "number" && Number.isSafeInteger(evaluatedThrough) && evaluatedThrough >= 0))) return null;
  if (!Array.isArray(raw.outbox) || !isRecord(raw.producerSequences) || !isRecord(raw.reservedClear)) return null;
  const lineage=raw.lineage;
  const outbox = raw.outbox.map(item=>parseMonitoringEvent(item,lineage));
  if (outbox.some((event) => event === null)) return null;
  const typedOutbox = outbox.filter(item=>item!==null);
  const producerSequences: Record<string, string> = {};
  for (const [slot, sequence] of Object.entries(raw.producerSequences)) {
    if (!/^[0-9a-f]{64}$/.test(slot) || !validCounter(sequence)) return null;
    producerSequences[slot] = sequence;
  }
  const reservedClear: Record<string, { sequence: string; episodeId: string }> = {};
  for (const [slot, rawReservation] of Object.entries(raw.reservedClear)) {
    if (!/^[0-9a-f]{64}$/.test(slot) || !isRecord(rawReservation) || Object.keys(rawReservation).some((key) => key !== "sequence" && key !== "episodeId") ||
      !validCounter(rawReservation.sequence) || rawReservation.sequence === "0" || typeof rawReservation.episodeId !== "string" || !/^[0-9a-f]{64}$/.test(rawReservation.episodeId) ||
      producerSequences[slot] !== rawReservation.sequence) return null;
    reservedClear[slot] = { sequence: rawReservation.sequence, episodeId: rawReservation.episodeId };
  }
  const eventIds = new Set<string>();
  const perSlot = new Map<string, MonitoringEvent[]>();
  for (const event of typedOutbox) {
    if (eventIds.has(event.eventId) || producerSequences[event.slot] === undefined || BigInt(event.producerSequence) > BigInt(producerSequences[event.slot]!)) return null;
    eventIds.add(event.eventId);
    const events = perSlot.get(event.slot) ?? [];
    events.push(event); perSlot.set(event.slot, events);
  }
  for (const [slot, events] of perSlot) {
    if (!monitoringSlotCustodyValid(events,producerSequences[slot],reservedClear[slot])) return null;
  }
  const parsedState: MutableMonitoringGeneration = { outbox: typedOutbox,outboxBytes:eventBytes(typedOutbox),reservedClearCount:Object.keys(reservedClear).length, producerSequences, reservedClear, setWatermark: () => {} };
  if (outboxUsage(parsedState) > MACHINE_MONITORING_OUTBOX_MAX_BYTES) return null;
  const activeSlots = new Set<string>();
  for (const entry of entries.values()) if (entry.machineIncarnation !== undefined) for (const metric of entry.open) activeSlots.add(slotFor(entry.machineIncarnation, metric));
  for (const [slot, reservation] of Object.entries(reservedClear)) {
    if (producerSequences[slot] !== reservation.sequence || !activeSlots.has(slot) || typedOutbox.some((event) => event.slot === slot && event.kind === "clear")) return null;
  }
  for (const slot of activeSlots) if (reservedClear[slot] === undefined) return null;
  return { roster:parsedRoster,tombstones:parsedTombstones,historyOwners:parsedHistoryOwners,createdAt:raw.createdAt??0, lineage: raw.lineage, checkpointRevision, entries, receipts, history: Object.fromEntries(parseMonitoringHistory(raw.history)!), evaluatedThrough, outbox: typedOutbox, producerSequences, reservedClear };
}

export function parseVersion1AlertsFile(raw: JsonValue): Map<string, StoredMachineAlerts> | null {
  if (!isRecord(raw) || raw.version !== 1 || !isRecord(raw.machines)) return null;
  const entries = new Map<string, StoredMachineAlerts>();
  for (const [id, value] of Object.entries(raw.machines)) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id) || !isRecord(value) || Object.keys(value).some((key) => key !== "rules" && key !== "open")) return null;
    const rules = parseMachineAlerts(value.rules);
    if (rules === null || !Array.isArray(value.open) || !value.open.every(isAlertMetric)) return null;
    const open = [...new Set(value.open)];
    if (open.some((metric) => rules[metric] === undefined)) return null;
    entries.set(id, { rules, open });
  }
  return entries;
}


export function isMachineFileMissing(cause: unknown): boolean { return typeof cause === "object" && cause !== null && "code" in cause && cause.code === "ENOENT"; }

/** Full-precision checkpoint rows; constructs the domain values only after every field check. */
export function parseMonitoringHistory(value: JsonValue | undefined): Map<string,LoadedMinute[]> | null {
  if (!isRecord(value)) return null;
  const loaded = new Map<string, LoadedMinute[]>();
  for (const [id, raw] of Object.entries(value)) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id) || !Array.isArray(raw) || raw.length > HISTORY_MINUTES) return null;
    const minutes: LoadedMinute[] = [];
    let previous = -1;
    for (const item of raw) {
    if (!isRecord(item)) return null;
    const m = item;
    const keys = MONITORING_MINUTE_KEYS;
    if (Object.keys(m).some((key) => !keys.has(key))) return null;



    if (!finite(m.t) || !Number.isSafeInteger(m.t) || m.t % MINUTE_MS !== 0 || m.t <= previous ||
      !count(m.n) || m.n < 1 || !fraction(m.cpu) || !fraction(m.cpuMax) || m.cpu > m.cpuMax || !fraction(m.mem) ||
      !(m.rx === null || (finite(m.rx) && m.rx >= 0)) || !(m.tx === null || (finite(m.tx) && m.tx >= 0)) ||
      !(m.disk === null || fraction(m.disk)) || !count(m.rxN) || !count(m.txN) || !count(m.diskN) ||
      (m.rx === null) !== (m.rxN === 0) || (m.tx === null) !== (m.txN === 0) || (m.disk === null) !== (m.diskN === 0) || m.rxN>m.n || m.txN>m.n || m.diskN>m.n) return null;
    previous = m.t;
    minutes.push({t:m.t,n:m.n,cpu:m.cpu,cpuMax:m.cpuMax,mem:m.mem,rx:m.rx,tx:m.tx,disk:m.disk,rxN:m.rxN,txN:m.txN,diskN:m.diskN});
    }
    loaded.set(id, minutes);
  }
  return loaded;
}

/** Legacy sidecar input is validated before it can join the durable monitoring generation. */
export function parseLegacyMonitoringHistory(raw: JsonValue | null): Map<string,LoadedMinute[]> | null {
  if (!isRecord(raw)) return null;
  const file = raw;
  if (Object.keys(file).some(key=>key!=="version" && key!=="machines") || (file.version !== 1 && file.version !== 2) || typeof file.machines !== "object" || file.machines === null || Array.isArray(file.machines)) return null;
  const sourceMachines = file.machines;
  const loaded = coerceHistoryFile(raw, HISTORY_MINUTES);
  for (const [id, source] of Object.entries(sourceMachines)) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(id)) return null;
    const rows = file.version === 1 ? source :
      typeof source === "object" && source !== null && !Array.isArray(source) ? source.rows : null;
    if (!Array.isArray(rows) || rows.length > HISTORY_MINUTES || loaded.get(id)?.length !== rows.length) return null;
    if(file.version===1) {
      let previous=-1;
      for(const row of rows) {
        if(!Array.isArray(row) || row.length!==9 || !row.every((value):value is number=>typeof value==="number" && Number.isFinite(value))) return null;
        const t=row[0]!,n=row[1]!,cpu=row[2]!,cpuMax=row[3]!,mem=row[4]!,rx=row[5]!,rxN=row[6]!,tx=row[7]!,txN=row[8]!;
        if(!Number.isSafeInteger(t) || t<0 || t%MINUTE_MS!==0 || t<=previous || !Number.isInteger(n) || n<1 || n>0xffff || cpu<0 || cpu/n>1 || cpuMax<cpu/n || cpuMax>1 || mem<0 || mem/n>1 || rx<0 || tx<0 || !Number.isInteger(rxN) || rxN<0 || rxN>n || !Number.isInteger(txN) || txN<0 || txN>n || rxN===0 && rx!==0 || txN===0 && tx!==0) return null;
        previous=t;
      }
    }    else {
      if(!isRecord(source) || Object.keys(source).some(key=>key!=="t" && key!=="n" && key!=="rows") ||
        typeof source.t!=="number" || !Number.isSafeInteger(source.t) || source.t<0 || source.t%MINUTE_MS!==0 ||
        typeof source.n!=="number" || !Number.isInteger(source.n) || source.n<1 || source.n>0xffff)return null;
      let timestamp=source.t;
      for(let i=0;i<rows.length;i++){
        const row=rows[i];
        if(!Array.isArray(row) || row.length!==6 && row.length!==7 || typeof row[0]!=="number" || !Number.isSafeInteger(row[0]) ||
          (i===0 ? row[0]!==0 : row[0]<1) || !fraction(row[1]) || !fraction(row[2]) || row[1]>row[2] || !fraction(row[3]) ||
          !(row[4]===null || finite(row[4]) && row[4]>=0) || !(row[5]===null || finite(row[5]) && row[5]>=0) ||
          row.length===7 && !(row[6]===null || fraction(row[6])))return null;
        timestamp+=row[0]*MINUTE_MS;if(!Number.isSafeInteger(timestamp))return null;
      }
    }

  }
  if (loaded.size !== Object.keys(sourceMachines).length) return null;
  return loaded;
}

export function isAlertMetric(value: JsonValue | undefined): value is AlertMetric { return value === "cpu" || value === "mem" || value === "disk"; }

type MutableMachineRuleRequest={-readonly [K in keyof MachineRuleRequest]:MachineRuleRequest[K]};

/** Metadata from the original already-captured immutable generation, never a fresh store read. */
export function parseMonitoringSnapshotStamp(value:JsonValue|null):{revision:string;createdAt:number}|null {
  const snapshot=recordOf(value);
  if(snapshot===null || !validCounter(snapshot.checkpointRevision) || typeof snapshot.createdAt!=="number" || !Number.isSafeInteger(snapshot.createdAt) || snapshot.createdAt<0)return null;
  return {revision:snapshot.checkpointRevision,createdAt:snapshot.createdAt};
}

const finite = (v: JsonValue | undefined): v is number => typeof v === "number" && Number.isFinite(v);

const fraction = (v: JsonValue | undefined): v is number => finite(v) && v >= 0 && v <= 1;

const count = (v: JsonValue | undefined): v is number => Number.isInteger(v) && typeof v === "number" && v >= 0 && v <= 0xffff;

const MONITORING_MINUTE_KEYS=new Set(["t","n","cpu","cpuMax","mem","rx","tx","disk","rxN","txN","diskN"]);
