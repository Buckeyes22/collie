import {HOST} from "./host.ts";
// When a machine's load earns a push: one rule per metric per machine, judged on minute buckets.
//
// ── THE JUDGEMENT IS PURE ─────────────────────────────────────────────────────
// {@link judgeAlert} and {@link evaluateMachineAlerts} take the minutes, the rules, the open episodes
// and a clock reading, and return what opened and what closed. No timer, no file, no push: the owner
// (`bridge/machines.ts`) calls them from the engine tick once a minute and does the sending and the
// saving. The cache warning set this shape (`bridge/cache/warn.ts`, ADR 0042) and the reason is the
// same: `bun test` drives every rule below as data.
//
// ── FOUR RULES ───────────────────────────────────────────────────────────────
// 1. A rule FIRES when every complete minute in its last `forMin` that has data is at or above
//    `above`, and at least 80 % of those minutes have data. CPU is judged on the minute's average,
//    so one busy second does not count as a busy minute. Disk is judged on the fullest filesystem's
//    fraction, and a minute without a disk reading is a minute without data. The open minute is never judged: it is
//    still filling.
// 2. ONE PUSH PER EPISODE. A fired rule opens an episode, and nothing more is sent until it closes.
// 3. An episode CLOSES after five straight complete minutes, each with data, below `above - 0.05`.
//    The gap is hysteresis: a value hovering at the threshold must not open and close an episode
//    every few minutes, which would be a push every few minutes.
// 4. An UNREACHABLE machine neither fires nor closes. Its minutes are missing, and missing is not
//    low: an episode on a machine that dropped off the network is still open when it comes back.
//
// Open episodes are persisted with the rules, so a restart in the middle of one does not push again.

import { renameSync } from "node:fs";
import { mkdir, open as openPrivateFile, rm } from "node:fs/promises";
import { createHash, randomBytes } from "node:crypto";
import { join } from "node:path";
import { assertMonitoringPrivateDirectory, readMonitoringPrivateFile } from "./monitoring-private-io.ts";
export { parseMonitoringEvent } from "./machine-parse.ts";
import type { JsonObject, JsonValue } from "./json.ts";
import { MachineHistory, MINUTE_MS, minuteOf, type MinuteReading } from "./machine-history.ts";
import { parseMachineStrictJson, parseVersion2AlertsFile, parseVersion1AlertsFile, isMachineFileMissing, type StoredMachineAlerts } from "./machine-parse.ts";
import { redactText } from "./redact.ts";
import { pushTitle } from "./push-titles.ts";
import { machineTopic, type PushMessage } from "./push.ts";
import type { AlertMetric, AlertRule, MachineAlerts } from "./types.ts";

/** The file in the state folder. Named here so `solo-baseline.test.ts`'s scan can read it. */
export const MACHINE_ALERTS_FILE = "machine-alerts.json";
export const MACHINE_RULE_REQUEST_RECEIPTS_MAX = 512;
export const MACHINE_MONITORING_OUTBOX_MAX_BYTES = 8 * 1024 * 1024;
const EVENT_MAX_BYTES = 4096;
const UINT64_MAX = 0xffff_ffff_ffff_ffffn;

/** At least this share of a rule's window must have data before it may fire. */
export const ALERT_COVERAGE = 0.8;
/** Straight minutes below the line that close an episode. */
export const ALERT_CLOSE_MINUTES = 5;
/** How far below `above` a minute must be to count towards closing. */
export const ALERT_HYSTERESIS = 0.05;

const METRICS: readonly AlertMetric[] = ["cpu", "mem", "disk"];

/** What one rule says about one machine right now. */
export type AlertVerdict =
  | { readonly kind: "fire"; readonly value: number }
  | { readonly kind: "close" }
  | { readonly kind: "hold" };

const HOLD: AlertVerdict = { kind: "hold" };

/** The value a rule reads off a minute, `null` where the minute has none (disk only). */
function valueOf(metric: AlertMetric, minute: MinuteReading): number | null {
  if (metric === "cpu") return minute.cpu;
  if (metric === "mem") return minute.mem;
  return minute.disk;
}

/** The complete minutes in `[open - count, open)`, where `open` is the minute `now` falls in. */
function windowOf(minutes: readonly MinuteReading[], count: number, now: number): MinuteReading[] {
  const open = minuteOf(now);
  const from = open - count * MINUTE_MS;
  return minutes.filter((m) => m.t >= from && m.t < open);
}

/**
 * One rule against one machine's minutes. `minutes` must reach back at least
 * `max(rule.forMin, ALERT_CLOSE_MINUTES)` complete minutes; anything older is ignored.
 */
export function judgeAlert(
  metric: AlertMetric,
  rule: AlertRule,
  open: boolean,
  minutes: readonly MinuteReading[],
  now: number,
): AlertVerdict {
  // A minute with no value for this metric (a disk not reported) is a missing minute, exactly like a
  // minute with no reading at all.
  const valued = (count: number): number[] =>
    windowOf(minutes, count, now).flatMap((m) => {
      const v = valueOf(metric, m);
      return v === null ? [] : [v];
    });
  if (open) {
    const recent = valued(ALERT_CLOSE_MINUTES);
    if (recent.length < ALERT_CLOSE_MINUTES) return HOLD;
    const line = rule.above - ALERT_HYSTERESIS;
    return recent.every((v) => v < line) ? { kind: "close" } : HOLD;
  }
  const window = valued(rule.forMin);
  if (window.length < rule.forMin * ALERT_COVERAGE) return HOLD;
  if (!window.every((v) => v >= rule.above)) return HOLD;
  const value = window.reduce((sum, v) => sum + v, 0) / window.length;
  return { kind: "fire", value };
}

/** One machine as the evaluator sees it. */
export interface AlertSubject {
  readonly id: string;
  readonly name: string;
  readonly reachable: boolean;
  readonly rules: MachineAlerts;
  readonly open: readonly AlertMetric[];
  readonly machineIncarnation?: string;
  readonly rulesRevision?: string;
  readonly producerHighWater?: string;
}

/** An episode that opened on this pass, with what the push says about it. */
export interface AlertOpened {
  readonly id: string;
  readonly name: string;
  readonly metric: AlertMetric;
  readonly rule: AlertRule;
  readonly value: number;
  readonly machineIncarnation?: string;
  readonly rulesRevision?: string;
  readonly producerHighWater?: string;
}

export interface AlertClosed {
  readonly id: string;
  readonly metric: AlertMetric;
  readonly machineIncarnation?: string;
  readonly rulesRevision?: string;
  readonly producerHighWater?: string;
}

export interface AlertPass {
  readonly opened: AlertOpened[];
  readonly closed: AlertClosed[];
}

export type MonitoringMessage =
  | { readonly type: "machine"; readonly tag: string; readonly titleCode: string; readonly title: string; readonly body: string; readonly machine: string; readonly topic: string; readonly renotify: true }
  | { readonly type: "clear"; readonly tag: string };

export type MonitoringEvent = {
  readonly schema: 1;
  readonly eventId: string;
  readonly producerLineage: string;
  readonly slot: string;
  readonly producerSequence: string;
  readonly machineId: string;
  readonly machineIncarnation: string;
  readonly metric: AlertMetric;
  readonly rulesRevision: string;
  readonly episodeId: string;
  readonly kind: "alert" | "clear";
  readonly observedAt: number;
  readonly payloadHash: string;
  readonly message: MonitoringMessage;
}

export type MonitoringEnqueueResult =
  | { readonly kind: "accepted" | "duplicate" | "suppressed"; readonly eventId: string; readonly payloadHash: string; readonly receiptDigest: string; readonly reason?: string }
  | { readonly kind: "deferred"; readonly reason: string };

export interface MonitoringProducer {
  /** Original local lease, checked by the receiver again at its actual durable enqueue boundary. Never serialize this closure. */
  readonly originalProducerCurrent: () => boolean;
  readonly lineage: string;
  readonly slot: string;
  readonly sequence: string;
  readonly eventId: string;
  readonly payloadHash: string;
}

/**
 * Judge every rule of every machine. `mayOpen` is false while the bridge is snoozed or the operator
 * turned machine alerts off: nothing opens then, so nothing is recorded either, and a high value that
 * outlasts the snooze still pushes once it ends. Closing runs either way, because it sends nothing.
 */
export function evaluateMachineAlerts(
  subjects: readonly AlertSubject[],
  minutesOf: (id: string, from: number) => readonly MinuteReading[],
  now: number,
  mayOpen: boolean,
): AlertPass {
  const opened: AlertOpened[] = [];
  const closed: AlertClosed[] = [];
  for (const subject of subjects) {
    if (!subject.reachable) continue;
    const reach = Math.max(ALERT_CLOSE_MINUTES, ...METRICS.map((m) => subject.rules[m]?.forMin ?? 0));
    const minutes = minutesOf(subject.id, minuteOf(now) - reach * MINUTE_MS);
    for (const metric of METRICS) {
      const rule = subject.rules[metric];
      if (rule === undefined) continue;
      const isOpen = subject.open.includes(metric);
      if (!isOpen && !mayOpen) continue;
      const verdict = judgeAlert(metric, rule, isOpen, minutes, now);
      if(verdict.kind==="hold")continue;
      const event:AlertOpened|AlertClosed=verdict.kind==="fire" ? {id:subject.id,name:subject.name,metric,rule,value:verdict.value} : {id:subject.id,metric};
      if(subject.machineIncarnation!==undefined)Object.assign(event,{machineIncarnation:subject.machineIncarnation});
      if(subject.rulesRevision!==undefined)Object.assign(event,{rulesRevision:subject.rulesRevision});
      if("value" in event)opened.push(event);else closed.push(event);
    }
  }
  return { opened, closed };
}

const percent = (fraction: number): number => Math.round(fraction * 100);

/**
 * The push for one opened episode. The title is a catalogue code (ADR 0074), so the phone says it in
 * its own language; the body stays English like every push body and names the machine, the metric,
 * the value and the minutes.
 *
 * One tag per machine and metric, with `renotify`: the next episode on the same machine replaces this
 * one on a platform that collapses by tag, and still alerts. One collapse topic per machine and metric
 * too ({@link machineTopic}), so the push service keeps one queued alert of each instead of one in all.
 * `machine` and `target` are what a tap reads (`web/src/lib/push-decision.ts`), and `machine` is set
 * for the lead's own machine too, because the page it opens is addressed by machine id. There is no
 * `host`: an old service worker would open `/?h=<id>` with it, broken on a solo Collie (`local`), and
 * without it opens the dashboard.
 */
export function machineAlertMessage(opened: AlertOpened, mount?: string): PushMessage {
  const label = opened.metric === "cpu" ? "CPU" : opened.metric === "mem" ? "memory" : mount === undefined ? "disk" : `disk ${mount}`;
  const code = opened.metric === "cpu" ? "machine.cpu" : opened.metric === "mem" ? "machine.mem" : "machine.disk";
  return {
    type: "machine",
    tag: `collie:machine:${opened.id}:${opened.metric}`,
    ...pushTitle(code, { machine: opened.name }),
    body: `${opened.name}: ${label} ${percent(opened.value)}% for ${opened.rule.forMin} min (alert at ${percent(opened.rule.above)}%).`,
    machine: opened.id,
    target: "machine",
    topic: machineTopic(opened.id, opened.metric),
    renotify: true,
  };
}

export interface MachineRuleState { rules:MachineAlerts;open:AlertMetric[];rulesRevision:string;machineIncarnation:string;extensions?:JsonObject }
interface CompletedMinuteResult { pass:AlertPass;complete:boolean }
interface MinuteCommitRequest { subjects:readonly AlertSubject[];history:MachineHistory;now:number;mayOpen:boolean;current:()=>boolean;result?:CompletedMinuteResult }

/**
 * The rules and the open episodes, per machine, on disk in `machine-alerts.json`.
 *
 * Written on a change only: a rule set from the phone, an episode opening, an episode closing. A
 * bridge nobody gave a rule never writes the file, and the evaluator then has nothing to judge.
 */
export class MachineAlertStore {
  private readonly file: string;
  private saveChain: Promise<void> = Promise.resolve();
  private entries: Map<string, StoredMachineAlerts>;
  private lineage: string;
  private receipts: RuleRequestReceipt[];
  private historyState: Record<string, import("./machine-history.ts").LoadedMinute[]>;
  private historyProvider: (() => Record<string, import("./machine-history.ts").LoadedMinute[]>) | null = null;
  private evaluatedThrough: number | null;
  private outbox: MonitoringEvent[];
  private producerSequences: Record<string, string>;
  private reservedClear: Record<string, { sequence: string; episodeId: string }>;
  private loadFailure: string | null;
  private generationLoaded: boolean;
  private checkpointRevision: string;
  private createdAt = 0;
  private roster: MonitoringMember[] = [];
  private rosterProvider: (() => readonly MonitoringMember[]) | null = null;
  private tombstones: MonitoringTombstone[] = [];
  private historyOwners: Record<string,string> = {};
  private readonly retirementClearWitnesses=new WeakMap<object,Record<string,AlertClosed>>();
  private readonly clearWitnesses=new WeakMap<object,Record<string,string>>();
  private readonly minuteRequests = new WeakMap<AlertPass, { subjects: readonly AlertSubject[]; history: MachineHistory; now: number; mayOpen: boolean; current: () => boolean; result?: CompletedMinuteResult }>();

  constructor(
    private readonly stateDir: string,
    entries: Map<string, StoredMachineAlerts> = new Map(),
    options: { lineage?: string; receipts?: RuleRequestReceipt[]; loadFailure?: string | null; history?: Record<string, import("./machine-history.ts").LoadedMinute[]>; evaluatedThrough?: number | null; outbox?: MonitoringEvent[]; producerSequences?: Record<string, string>; reservedClear?: Record<string, { sequence: string; episodeId: string }>; generationLoaded?: boolean; checkpointRevision?: string; createdAt?: number; roster?: MonitoringMember[]; tombstones?: MonitoringTombstone[]; historyOwners?: Record<string,string> } = {},
  ) {
    this.file = join(this.stateDir, MACHINE_ALERTS_FILE);
    this.entries = entries;
    this.lineage = options.lineage ?? randomBytes(32).toString("hex");
    this.receipts = options.receipts ?? [];
    this.loadFailure = options.loadFailure ?? null;
    this.historyState = options.history ?? {};
    this.evaluatedThrough = options.evaluatedThrough ?? null;
    this.outbox = options.outbox ?? [];
    this.producerSequences = options.producerSequences ?? {};
    this.reservedClear = options.reservedClear ?? {};
    this.generationLoaded = options.generationLoaded ?? false;
    this.checkpointRevision = options.checkpointRevision ?? "0";
    this.createdAt = options.createdAt ?? 0;
    this.roster = options.roster ?? []; this.tombstones = options.tombstones ?? []; this.historyOwners = options.historyOwners ?? {};
  }

  /** The store as the file holds it. A missing or unreadable file is no rule at all. */
  static async load(stateDir: string): Promise<MachineAlertStore> {
    let bytes: Buffer;
    try {
      const path = join(stateDir, MACHINE_ALERTS_FILE);
      bytes = await readMonitoringPrivateFile(path, 64 * 1024 * 1024);
    } catch (error) {
      if (isMachineFileMissing(error)) {
        return new MachineAlertStore(stateDir);
      }
      return new MachineAlertStore(stateDir, new Map(), { loadFailure: "machine.monitoring_state_unavailable" });
    }
    let raw: JsonValue | undefined;
    try { raw = parseMachineStrictJson(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) ?? undefined; } catch { raw = undefined; }
    if (raw === undefined) return new MachineAlertStore(stateDir, new Map(), { loadFailure: "machine.monitoring_state_corrupt" });
    const v2 = parseVersion2AlertsFile(raw, Date.now(), stateDir);
    if (v2 !== null) return new MachineAlertStore(stateDir, v2.entries, { lineage: v2.lineage, receipts: v2.receipts, history: v2.history, evaluatedThrough: v2.evaluatedThrough, outbox: v2.outbox, producerSequences: v2.producerSequences, reservedClear: v2.reservedClear, generationLoaded: true, checkpointRevision: v2.checkpointRevision, roster: v2.roster, tombstones: v2.tombstones, historyOwners: v2.historyOwners, createdAt: v2.createdAt });
    const v1 = parseVersion1AlertsFile(raw);
    if (v1 !== null) return new MachineAlertStore(stateDir, v1);
    return new MachineAlertStore(stateDir, new Map(), { loadFailure: "machine.monitoring_state_corrupt" });
  }

  /** A failed/unknown authoritative file is visible and refuses writes; it is never reset to empty. */
  available(): boolean { return this.loadFailure === null; }

  /** Stable random lineage for a solo monitor; never derived from hostname or network address. */
  producerLineage(): string { return this.lineage; }

  /** Bind the live precision-preserving cache into every authoritative generation commit. */
  attachHistory(history: MachineHistory, now: () => number): void {
    this.historyProvider = () => history.toMonitoringState(now());
  }

  attachRoster(provider: () => readonly MonitoringMember[]): void { this.rosterProvider = provider; }

  monitoringHistory(now: number): MachineHistory | null {
    return this.generationLoaded ? MachineHistory.fromMonitoringState(this.historyState, now) : null;
  }

  failClosed(code: string): void { this.loadFailure = code; }

  pendingMonitoringEvents(): readonly MonitoringEvent[] { return this.outbox.map((event) => ({ ...event, message: { ...event.message } })); }

  /** Sequential narrow-port handoff. Deferred/error keeps the exact durable event for retry. */
  async enqueuePendingMonitoringEvents(
    enqueue: (event: MonitoringEvent, producer: MonitoringProducer) => Promise<MonitoringEnqueueResult>,
    producerCurrent: () => boolean,
  ): Promise<void> {
    for (const event of this.pendingMonitoringEvents()) {
      if (!producerCurrent()) return;
      const producer: MonitoringProducer = { originalProducerCurrent: producerCurrent, lineage: event.producerLineage, slot: event.slot, sequence: event.producerSequence, eventId: event.eventId, payloadHash: event.payloadHash };
      const result = await enqueue(event, producer);
      if (!producerCurrent()) return;
      if (result.kind === "deferred") return;
      if (result.eventId !== event.eventId || result.payloadHash !== event.payloadHash || !/^[0-9a-f]{64}$/.test(result.receiptDigest)) return;
      if (!await this.settleMonitoringEvent(result, producerCurrent)) return;
    }
  }

  /** A producer tombstone is cleared only by a matching durable B22 receipt. */
  async settleMonitoringEvent(result: Exclude<MonitoringEnqueueResult, { kind: "deferred" }>, producerCurrent: () => boolean = () => true): Promise<boolean> {
    const event = this.outbox.find((item) => item.eventId === result.eventId);
    if (event === undefined || event.payloadHash !== result.payloadHash || !/^[0-9a-f]{64}$/.test(result.receiptDigest)) return false;
    await this.transact((_entries, _receipts, generation) => {
      generation.outbox = generation.outbox.filter((item) => item.eventId !== result.eventId);
      generation.outboxBytes=eventBytes(generation.outbox);
    }, producerCurrent);
    return true;
  }

  /** Commit a complete monitoring generation at the completed-minute boundary. */
  async commitGeneration(watermark: number): Promise<void> {
    await this.transact((_entries, _receipts, generation) => {
      generation.setWatermark(watermark);
    });
  }

  /** Immutable source snapshot for the bounded checkpoint serializer; waits for prior durable writes. */
  async checkpointSnapshot(): Promise<JsonValue | null> {
    try { await this.saveChain; } catch { return null; }
    if (this.loadFailure !== null) return null;
    const history = this.historyState;
    const snapshot:JsonObject={createdAt:this.createdAt,schema:1,checkpointRevision:this.checkpointRevision,lineage:this.lineage,machines:serializeEntries(this.entries),
      receipts:this.receipts.map(receipt=>({...receipt,result:{...receipt.result,rules:{...receipt.result.rules},open:[...receipt.result.open],cleared:[...receipt.result.cleared]}})),
      history,evaluatedThrough:this.evaluatedThrough,outbox:this.outbox,producerSequences:this.producerSequences,reservedClear:this.reservedClear};
    if(this.rosterProvider!==null || this.roster.length!==0){snapshot.roster=this.roster.map(member=>({...member}));snapshot.tombstones=this.tombstones.map(item=>({...item}));snapshot.historyOwners=this.historyOwners;}
    return freezeMonitoringValue(snapshot);
  }

  revision(id: string): string { return this.entries.get(id)?.rulesRevision ?? "0"; }
  currentCheckpointRevision(): string { return this.checkpointRevision; }
  evaluatorWatermark(): number | null { return this.evaluatedThrough; }
  originalEventCurrent(event:AlertOpened|AlertClosed):boolean {
    if(event.machineIncarnation===undefined || event.producerHighWater===undefined || this.producerSequences[slotFor(event.machineIncarnation,event.metric)]!==event.producerHighWater)return false;
    const entry=this.entries.get(event.id);
    if("rule" in event)return entry?.machineIncarnation===event.machineIncarnation && entry.open.includes(event.metric) && JSON.stringify(entry.rules[event.metric])===JSON.stringify(event.rule);
    return entry===undefined || entry.machineIncarnation===event.machineIncarnation && !entry.open.includes(event.metric);
  }
  originalRetirementClearCurrent(result:Awaited<ReturnType<MachineAlertStore["drop"]>>|Awaited<ReturnType<MachineAlertStore["retain"]>>,id:string,metric:AlertMetric):boolean {
    const event=this.retirementClearWitnesses.get(result)?.[`${id}:${metric}`];return event!==undefined && this.originalEventCurrent(event);
  }
  originalRuleClearCurrent(result:RuleReplaceResult,id:string,incarnation:string,metric:AlertMetric):boolean {
    const witness=this.clearWitnesses.get(result);
    return witness!==undefined && this.originalEventCurrent({id,metric,machineIncarnation:incarnation,producerHighWater:witness[metric]});
  }
  committedHistoryOwner(id:string):string|undefined {return this.historyOwners[id];}

  machineIncarnation(id: string, current: string): string {
    const saved = this.entries.get(id)?.machineIncarnation;
    return saved ?? current;
  }

  /** Exact rule state for a current machine identity; old-incarnation rules are not inherited. */
  snapshot(id: string, incarnation: string): MachineRuleState {
    const entry = this.entries.get(id);
    if (entry === undefined || (entry.machineIncarnation !== undefined && entry.machineIncarnation !== incarnation)) {
      return { rules: {}, open: [], rulesRevision: entry?.rulesRevision ?? "0", machineIncarnation: incarnation };
    }
    const snapshot:ReturnType<MachineAlertStore["snapshot"]>={rules:{...entry.rules},open:[...entry.open],rulesRevision:entry.rulesRevision??"0",machineIncarnation:incarnation};
    if(entry.extensions!==undefined)snapshot.extensions=structuredClone(entry.extensions);
    return snapshot;
  }

  rules(id: string): MachineAlerts {
    const rules = this.entries.get(id)?.rules;
    return rules === undefined ? {} : { ...rules };
  }

  open(id: string): AlertMetric[] {
    return [...(this.entries.get(id)?.open ?? [])];
  }

  /**
   * Replace one machine's rules. An episode stays open only while its metric still has a rule: a
   * removed rule has nothing left that could close it.
   */
  async set(id: string, rules: MachineAlerts): Promise<MachineAlerts> {
    const result = await this.transact((next, _receipts, generation) => {
      const old = next.get(id);
      const revision = nextRevision(old?.rulesRevision ?? "0");
      if (revision === null) throw new Error("machine.rules_revision_exhausted");
      const open = (old?.open ?? []).filter((m) => rules[m] !== undefined);
      // Low-level compatibility helper used by older callers/tests. Production writes go through
      // MachineWatch.replaceRules, which supplies the pinned current incarnation.
      const incarnation = old?.machineIncarnation ?? hashText(`legacy-machine-incarnation-v1\0${this.lineage}\0${id}`);
      for (const metric of (old?.open ?? []).filter((m) => rules[m] === undefined)) appendClearEvent(generation, this.lineage, {
        id, incarnation, metric, rulesRevision: revision, observedAt: Date.now(),
      });
      next.set(id, { ...old, rules: { ...rules }, open, rulesRevision: revision, machineIncarnation: incarnation });
      return { ...rules };
    });
    return result;
  }

  /**
   * Compare-and-swap a whole rule replacement and its request receipt in one durable write. The
   * callback is invoked after the temporary file is written and immediately before rename, so an
   * actor that retired during disk I/O cannot commit under a newly captured identity.
   */
  async replaceRules(
    input: { id: string; incarnation: string; rules: MachineAlerts; expectedRevision?: string; requestId?: string; actor: string | null; extensions?: JsonObject; observedAt?: number },
    authorityCurrent: () => boolean,
  ): Promise<RuleReplaceResult> {
    if (this.loadFailure !== null) return { kind: "unavailable" };
    try {
      return await this.transact(async (next, nextReceipts, generation) => {
      const actorHash = hashText(input.actor ?? "legacy-unbound");
      const requestHash = hashText(JSON.stringify({ id: input.id, incarnation: input.incarnation, expectedRevision: input.expectedRevision ?? null, rules: input.rules, extensions: input.extensions ?? null }));
      if (input.requestId !== undefined) {
        const oldReceipt = nextReceipts.find((r) => r.requestId === input.requestId);
        if (oldReceipt !== undefined) {
          if (oldReceipt.actorHash !== actorHash || oldReceipt.machineId !== input.id || oldReceipt.requestHash !== requestHash) {
            return { kind: "conflict", current: this.snapshot(input.id, input.incarnation) };
          }
          return { kind: "replay", current: oldReceipt.result };
        }
        if (nextReceipts.length >= MACHINE_RULE_REQUEST_RECEIPTS_MAX) return { kind: "unavailable" };
      }
      const old = next.get(input.id);
      const currentRevision = old?.rulesRevision ?? "0";
      const currentIncarnation = old?.machineIncarnation ?? input.incarnation;
      if (input.extensions !== undefined && JSON.stringify(input.extensions) !== JSON.stringify(old?.extensions ?? {})) {
        return { kind: "conflict", current: this.snapshot(input.id, input.incarnation) };
      }
      const current:ReturnType<MachineAlertStore["snapshot"]>={rules:{},open:[],rulesRevision:currentRevision,machineIncarnation:input.incarnation};
      if(old!==undefined && currentIncarnation===input.incarnation) {
        current.rules={...old.rules};current.open=[...old.open];
        if(old.extensions!==undefined)current.extensions=structuredClone(old.extensions);
      }
      if (input.expectedRevision !== undefined && (input.expectedRevision !== currentRevision || input.incarnation !== currentIncarnation)) {
        return { kind: "conflict", current };
      }
      const revision = nextRevision(currentRevision);
      if (revision === null) return { kind: "conflict", current };
      const open = current.open.filter((metric) => input.rules[metric] !== undefined);
      const result:ReturnType<MachineAlertStore["snapshot"]>={rules:{...input.rules},open,rulesRevision:revision,machineIncarnation:input.incarnation};
      if(old?.extensions!==undefined)result.extensions=structuredClone(old.extensions);
      const cleared = current.open.filter((metric) => input.rules[metric] === undefined);
      for (const metric of cleared) appendClearEvent(generation, this.lineage, {
        id: input.id, incarnation: input.incarnation, metric, rulesRevision: revision, observedAt: input.observedAt ?? Date.now(),
      });
      next.set(input.id,result);
      if (input.requestId !== undefined) {
        nextReceipts.push({ requestId: input.requestId, actorHash, machineId: input.id, requestHash, result: { ...result, cleared } });
      }
      if (!authorityCurrent()) return { kind: "retired" };
      const accepted:RuleReplaceResult={kind:"accepted",current:result,cleared};
      const witnesses:Record<string,string>={};for(const metric of cleared)witnesses[metric]=generation.producerSequences[slotFor(input.incarnation,metric)]!;
      this.clearWitnesses.set(accepted,witnesses);
      return accepted;
      }, authorityCurrent, (result) => result.kind === "accepted");
    } catch (error) {
      if (error instanceof RuleAuthorityRetired) return { kind: "retired" };
      return { kind: "unavailable" };
    }
  }

  /** A pass is a witness to the original exact rule, revision and incarnation. */
  async apply(pass: AlertPass, observedAt = Date.now(), producerCurrent: () => boolean = () => true): Promise<void> {
    const minute = this.minuteRequests.get(pass);
    if (minute !== undefined) {
      minute.result = await this.commitMinute(minute.subjects, minute.history, minute.now, minute.mayOpen, minute.current);
      return;
    }
    if (pass.opened.length === 0 && pass.closed.length === 0) return;
    await this.transact((next, _receipts, generation) => {
      const result = this.applyWitness(next, generation, pass, observedAt);
      if (!result.complete && result.pass.closed.length === 0) throw new Error("machine.monitoring_outbox_full");
      return result.pass;
    }, producerCurrent);
  }

  /** Evaluation and its complete-minute watermark share one serialized durable generation. */
  async evaluateMinute(subjects: readonly AlertSubject[], history: MachineHistory, now: number, mayOpen: boolean,
    producerCurrent: () => boolean): Promise<{ pass: AlertPass; complete: boolean }> {
    const pass: AlertPass = { opened: [], closed: [] };
    const request: MinuteCommitRequest = { subjects,history,now,mayOpen,current:producerCurrent };
    this.minuteRequests.set(pass, request);
    try {
      await this.apply(pass);
      if (request.result === undefined) throw new Error("machine.monitoring_minute_uncommitted");
      return request.result;
    } finally { this.minuteRequests.delete(pass); }
  }

  private async commitMinute(subjects: readonly AlertSubject[], history: MachineHistory, now: number, mayOpen: boolean,
    producerCurrent: () => boolean): Promise<{ pass: AlertPass; complete: boolean }> {
    let unconfiguredSolo=false;
    return this.transact((entries, _receipts, generation) => {
      // An unconfigured solo watch remains the original lazy chart cache. It has no monitoring
      // producer facts to persist until a rule exists; Crew generations always use the transaction.
      const roster=this.rosterProvider?.();
      unconfiguredSolo=!this.generationLoaded && roster!==undefined && roster.length>0 && roster.every(member=>member.solo===true) &&
        _receipts.length===0 && generation.outbox.length===0 && Object.keys(generation.reservedClear).length===0 &&
        [...entries.values()].every(entry=>entry.open.length===0 && Object.keys(entry.rules).length===0);
      if(unconfiguredSolo)return {pass:{opened:[],closed:[]},complete:true};
      const frozenHistory = history.toMonitoringState(now);
      const evaluatedHistory = MachineHistory.fromMonitoringState(frozenHistory, now);
      if (evaluatedHistory === null) throw new Error("machine.monitoring_history_invalid");
      generation.history = frozenHistory;
      const current = subjects.map(subject => {
        const stored = entries.get(subject.id);
        return { ...subject, rules: stored !== undefined && stored.machineIncarnation === subject.machineIncarnation ? stored.rules : {},
          open: stored !== undefined && stored.machineIncarnation === subject.machineIncarnation ? stored.open : [], rulesRevision: stored?.rulesRevision ?? "0" };
      });
      const pass = evaluateMachineAlerts(current, (id, from) => evaluatedHistory.minutes(id, from, now), now, mayOpen);
      const result = this.applyWitness(entries, generation, pass, now, true);
      if (result.complete) generation.setWatermark(minuteOf(now) - MINUTE_MS);
      return result;
    }, producerCurrent,()=>!unconfiguredSolo);
  }

  private applyWitness(entries: Map<string, StoredMachineAlerts>, generation: MutableMonitoringGeneration,
    witness: AlertPass, now: number, completedMinute=false): CompletedMinuteResult {
    const pass: AlertPass = { opened: [], closed: [] };
    let complete = true;
    const matches = (event: AlertOpened | AlertClosed): StoredMachineAlerts | undefined => {
      const entry = entries.get(event.id);
      if (entry === undefined || event.machineIncarnation !== undefined && event.machineIncarnation !== entry.machineIncarnation ||
        event.rulesRevision !== undefined && event.rulesRevision !== entry.rulesRevision || entry.rules[event.metric] === undefined) return undefined;
      if ("rule" in event && JSON.stringify(event.rule) !== JSON.stringify(entry.rules[event.metric])) return undefined;
      return entry;
    };
    // Closing custody was reserved when the episode opened. Opening backpressure cannot undo it.
    for (const event of witness.closed) {
      const entry = matches(event);
      if (entry === undefined || !entry.open.includes(event.metric) || entry.machineIncarnation === undefined) continue;
      appendClearEvent(generation, this.lineage, { id: event.id, incarnation: entry.machineIncarnation, metric: event.metric,
        rulesRevision: entry.rulesRevision ?? "0", observedAt: now });
      this.mark(entries, event.id, event.metric, false);
      pass.closed.push({...event,machineIncarnation:entry.machineIncarnation,rulesRevision:entry.rulesRevision??"0",producerHighWater:generation.producerSequences[slotFor(entry.machineIncarnation,event.metric)]!});
    }
    for (const event of witness.opened) {
      const entry = matches(event);
      if (entry === undefined || entry.open.includes(event.metric) || entry.machineIncarnation === undefined) continue;
      const slot = slotFor(entry.machineIncarnation, event.metric);
      // Original pending episode custody blocks only this opening; reserved clears already
      // decided in the same minute still commit, and the incomplete minute remains retryable.
      if (generation.reservedClear[slot] !== undefined || generation.outbox.some(item => item.slot === slot)) {
        if(!completedMinute)throw new Error("machine.monitoring_slot_pending");
        complete=false;continue;
      }
      const prior = generation.producerSequences[slot];
      const firstSequence=incrementCounter(prior??"0");
      if(firstSequence===null || incrementCounter(firstSequence)===null){
        if(!completedMinute)throw new Error("machine.monitoring_sequence_exhausted");
        complete=false;continue;
      }
      try {
        appendAlertEvent(generation, this.lineage, { ...event, machineIncarnation: entry.machineIncarnation, rulesRevision: entry.rulesRevision ?? "0" }, now);
      } catch (error) {
        if (!(error instanceof Error) || error.message !== "machine.monitoring_outbox_full") throw error;
        const rejected=generation.outbox.pop()!;generation.outboxBytes-=eventBytes([rejected]);delete generation.reservedClear[slot];generation.reservedClearCount--;
        if (prior === undefined) delete generation.producerSequences[slot]; else generation.producerSequences[slot] = prior;
        complete = false; continue;
      }
      this.mark(entries, event.id, event.metric, true);
      pass.opened.push({...event,machineIncarnation:entry.machineIncarnation,rulesRevision:entry.rulesRevision??"0",producerHighWater:generation.producerSequences[slot]!});
    }
    return { pass, complete };
  }

  /**
   * Forget one machine's rules and open episodes: a member that left the crew takes them with it.
   * Writes only when it held any.
   */
  async drop(id: string, producerCurrent: () => boolean = () => true): Promise<AlertMetric[]> {
    const original=this.entries.get(id);if(original===undefined)return [];
    return this.transact((next,_receipts,generation)=>{
      const current=next.get(id),open=[...original.open];
      if(original.machineIncarnation!==undefined)for(const metric of open)appendClearEvent(generation,this.lineage,{id,incarnation:original.machineIncarnation,metric,rulesRevision:original.rulesRevision??"0",observedAt:Date.now()});
      if(current===undefined || current.machineIncarnation===original.machineIncarnation)next.delete(id);
      const witnesses:Record<string,AlertClosed>={};
      if(original.machineIncarnation!==undefined)for(const metric of open)witnesses[`${id}:${metric}`]={id,metric,machineIncarnation:original.machineIncarnation,producerHighWater:generation.producerSequences[slotFor(original.machineIncarnation,metric)]!};
      this.retirementClearWitnesses.set(open,witnesses);return open;
    },producerCurrent);
  }

  /** Keep only the machines in `ids`. A member removed while this bridge was down goes here. */
  async retain(ids: ReadonlySet<string>, producerCurrent: () => boolean = () => true): Promise<{ id: string; open: AlertMetric[] }[]> {
    const originals=[...this.entries].filter(([id])=>!ids.has(id));if(originals.length===0)return [];
    return this.transact((next,_receipts,generation)=>{
      const removed:{id:string;open:AlertMetric[]}[]=[],witnesses:Record<string,AlertClosed>={};
      for(const [id,entry] of originals){
        const current=next.get(id),open=[...entry.open];
        if(entry.machineIncarnation!==undefined)for(const metric of open){
          appendClearEvent(generation,this.lineage,{id,incarnation:entry.machineIncarnation,metric,rulesRevision:entry.rulesRevision??"0",observedAt:Date.now()});
          witnesses[`${id}:${metric}`]={id,metric,machineIncarnation:entry.machineIncarnation,producerHighWater:generation.producerSequences[slotFor(entry.machineIncarnation,metric)]!};
        }
        if(current===undefined || current.machineIncarnation===entry.machineIncarnation)next.delete(id);
        if(open.length>0)removed.push({id,open});
      }
      this.retirementClearWitnesses.set(removed,witnesses);return removed;
    },producerCurrent);
  }

  /** Resolves when every write handed to the chain so far is done. The shutdown path awaits it. */
  settled(): Promise<void> {
    return this.saveChain;
  }

  private mark(entries: Map<string, StoredMachineAlerts>, id: string, metric: AlertMetric, open: boolean): void {
    const entry = entries.get(id);
    if (entry === undefined) return;
    const rest = entry.open.filter((m) => m !== metric);
    entries.set(id, { ...entry, open: open ? [...rest, metric] : rest });
  }

  /** Transactional shadow copy: failed persistence never changes the acknowledged in-memory state. */
  private transact<T>(
    mutate: (entries: Map<string, StoredMachineAlerts>, receipts: RuleRequestReceipt[], generation: MutableMonitoringGeneration) => T | Promise<T>,
    authorityCurrent: () => boolean = () => true,
    shouldCommit: (result: T) => boolean = () => true,
  ): Promise<T> {
    const commit=async ():Promise<T> => {
      if (this.loadFailure !== null) throw new Error(this.loadFailure);
      if (!authorityCurrent()) throw new RuleAuthorityRetired();
      const entries = new Map(this.entries);
      const receipts = [...this.receipts];
      const roster = this.rosterProvider === null ? this.roster : this.rosterProvider().map(member=>Object.assign({},member));
      const tombstones = [...this.tombstones];
      for(const previous of this.roster) {
        const current=roster.find(member=>member.id===previous.id);
        if(current?.incarnation===previous.incarnation) continue;
        const entry=entries.get(previous.id);
        if(entry?.machineIncarnation===previous.incarnation) {
          if(previous.fingerprint!==undefined && previous.enrolledAt!==undefined && !tombstones.some(item=>item.machineIncarnation===previous.incarnation)) tombstones.push({kind:"tombstone",memberId:previous.id,fingerprint:previous.fingerprint,enrolledAt:previous.enrolledAt,machineIncarnation:previous.incarnation,rulesRevision:entry.rulesRevision??"0"});
        }
      }
      let watermark = this.evaluatedThrough;
      const generation: MutableMonitoringGeneration = {
        outbox: [...this.outbox],outboxBytes:eventBytes(this.outbox),reservedClearCount:Object.keys(this.reservedClear).length, producerSequences: { ...this.producerSequences },
        reservedClear: structuredClone(this.reservedClear), setWatermark: (value) => { watermark = value; return undefined; },
      };
      for(const previous of this.roster) {
        if(roster.some(member=>member.id===previous.id && member.incarnation===previous.incarnation)) continue;
        const entry=entries.get(previous.id);
        if(entry?.machineIncarnation!==previous.incarnation) continue;
        for(const metric of entry.open) appendClearEvent(generation,this.lineage,{id:previous.id,incarnation:previous.incarnation,metric,rulesRevision:entry.rulesRevision??"0",observedAt:Date.now()});
        entries.delete(previous.id);
      }
      if(this.rosterProvider!==null) for(const [id,entry] of entries) if(entry.machineIncarnation===undefined && !roster.some(member=>member.id===id)) {
        this.loadFailure="machine.legacy_enrollment_unavailable";throw new Error(this.loadFailure);
      }
      if(this.rosterProvider!==null) for(const member of roster) {
        const entry=entries.get(member.id);
        if(entry?.machineIncarnation===undefined) {
          const adopted:StoredMachineAlerts={rules:entry?.rules??{},open:entry?.open??[],rulesRevision:entry?.rulesRevision??"0",machineIncarnation:member.incarnation};
          if(entry?.extensions!==undefined)Object.assign(adopted,{extensions:entry.extensions});
          entries.set(member.id,adopted);
        }
        else if(entry.machineIncarnation!==member.incarnation) entries.set(member.id,{rules:{},open:[],rulesRevision:"0",machineIncarnation:member.incarnation});
      }
      const result = await mutate(entries, receipts, generation);
      // Admission is captured before the request waits for this serialized store, and every
      // outcome (including an idempotent replay or conflict) must still belong to that owner.
      if (!authorityCurrent()) throw new RuleAuthorityRetired();
      if (!shouldCommit(result)) return result;
      const nextCheckpointRevision = incrementCounter(this.checkpointRevision);
      if (nextCheckpointRevision === null) throw new Error("machine.monitoring_revision_exhausted");
      for(const entry of entries.values()) {
        if(entry.machineIncarnation===undefined) continue;
        for(const metric of entry.open) {
          const slot=slotFor(entry.machineIncarnation,metric);
          if(generation.reservedClear[slot]!==undefined) continue;
          const sequence=sequenceAfter(generation.producerSequences[slot]??"0");
          generation.producerSequences[slot]=sequence;
          generation.reservedClear[slot]={sequence,episodeId:lengthHash("collie.monitoring.legacy.episode.v1",this.lineage,slot,entry.rulesRevision??"0")};generation.reservedClearCount++;
        }
      }
      const capturedHistory = generation.history ?? this.historyProvider?.() ?? this.historyState;
      const history: typeof capturedHistory = {};
      const historyOwners: Record<string,string> = {};
      for(const id in capturedHistory) {
        const member=roster.find(item=>item.id===id);
        if(this.rosterProvider!==null && member===undefined) continue;
        if(member!==undefined && this.historyOwners[id]!==undefined && this.historyOwners[id]!==member.incarnation) continue;
        history[id]=capturedHistory[id]!;
        if(member!==undefined) historyOwners[id]=member.incarnation;
      }
      const outstandingTombstones=tombstones.filter(item=>generation.outbox.some(event=>event.machineIncarnation===item.machineIncarnation) || receipts.some(receipt=>receipt.result.machineIncarnation===item.machineIncarnation));
      for(const item of tombstones) if(!outstandingTombstones.includes(item)) for(const metric of METRICS) delete generation.producerSequences[slotFor(item.machineIncarnation,metric)];
      for (const [id, minutes] of Object.entries(history)) {
        if (Buffer.byteLength(JSON.stringify({ id, minutes }), "utf8") > 512 * 1024) throw new Error("machine.monitoring_record_quota");
      }
      const createdAt = Date.now();
      const persisted={createdAt,schema:1,checkpointRevision:nextCheckpointRevision,lineage:this.lineage,machines:serializeEntries(entries),receipts,history,evaluatedThrough:watermark,
        outbox:generation.outbox,producerSequences:generation.producerSequences,reservedClear:generation.reservedClear};
      if(this.rosterProvider!==null || this.roster.length!==0)Object.assign(persisted,{roster,tombstones:outstandingTombstones,historyOwners});
      const data=JSON.stringify(persisted,null,2);
      if (Buffer.byteLength(data, "utf8") > 64 * 1024 * 1024) throw new Error("machine.monitoring_state_quota");
      await mkdir(this.stateDir, { recursive: true, mode: 0o700 });
      await assertMonitoringPrivateDirectory(this.stateDir);
      const tmp = `${this.file}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
      try {
        const handle = await openPrivateFile(tmp, "wx", 0o600);
        try {
          await handle.writeFile(data, { encoding: "utf8" });
          await handle.sync();
        } finally {
          await handle.close();
        }
        if (!authorityCurrent()) throw new RuleAuthorityRetired();
        renameSync(tmp, this.file);
        // Persist the directory entry as well as the generation bytes before acknowledging a
        // revision or episode transition. Platforms without directory fsync still have atomic
        // rename semantics; the explicit attempt avoids treating a buffered rename as durable.
        try {
          const directory = await openPrivateFile(this.stateDir, "r");
          try { await directory.sync(); } finally { await directory.close(); }
        } catch (error) {
          if (HOST.platform !== "win32") {
            this.loadFailure = "machine.monitoring_state_unavailable";
            throw error;
          }
        }
        // Returned acknowledgements and caller-owned history cannot mutate the committed
        // revision after its durable bytes were published.
        for(const entry of entries.values())freezeMonitoringValue(entry);
        freezeMonitoringValue(receipts);freezeMonitoringValue(history);freezeMonitoringValue(generation.outbox);
        freezeMonitoringValue(generation.producerSequences);freezeMonitoringValue(generation.reservedClear);
        freezeMonitoringValue(roster);freezeMonitoringValue(outstandingTombstones);freezeMonitoringValue(historyOwners);
        this.entries = entries;
        this.receipts = receipts;
        this.historyState = history;
        this.evaluatedThrough = watermark;
        this.outbox = generation.outbox;
        this.producerSequences = generation.producerSequences;
        this.reservedClear = generation.reservedClear;
        this.generationLoaded = true;
        this.checkpointRevision = nextCheckpointRevision;
        this.createdAt = createdAt;
        this.roster=roster; this.tombstones=outstandingTombstones; this.historyOwners=historyOwners;

      } finally {
        await rm(tmp, { force: true }).catch(() => undefined);
      }
      return freezeMonitoringValue(result);
    };
    const run=this.saveChain.then(commit);
    this.saveChain = run.then(() => undefined, () => undefined);
    return run;
  }
}

export interface MonitoringMember { readonly id:string; readonly incarnation:string; readonly fingerprint?:string; readonly enrolledAt?:number; readonly solo?:boolean }
export interface MonitoringTombstone { readonly kind:"tombstone"; readonly memberId:string; readonly fingerprint:string; readonly enrolledAt:number; readonly machineIncarnation:string; readonly rulesRevision:string }

class RuleAuthorityRetired extends Error {}

export interface MutableMonitoringGeneration {
  history?: Record<string, import("./machine-history.ts").LoadedMinute[]>;
  outbox: MonitoringEvent[];
  outboxBytes:number;
  reservedClearCount:number;
  producerSequences: Record<string, string>;
  reservedClear: Record<string, { sequence: string; episodeId: string }>;
  setWatermark(value: number): void;
}

export function lengthHash(domain: string, ...fields: string[]): string {
  const hash = createHash("sha256");
  for (const value of [domain, ...fields]) {
    const bytes = Buffer.from(value, "utf8");
    const length = Buffer.alloc(4); length.writeUInt32BE(bytes.length);
    hash.update(length); hash.update(bytes);
  }
  return hash.digest("hex");
}

export function slotFor(incarnation: string, metric: AlertMetric): string {
  return lengthHash("collie.monitoring.slot.v1", incarnation, metric);
}

function sequenceAfter(value: string): string {
  const next = BigInt(value) + 1n;
  if (next > UINT64_MAX) throw new Error("machine.monitoring_sequence_exhausted");
  return next.toString(10);
}

function makeMonitoringEvent(
  lineage: string,
  fields: Omit<MonitoringEvent, "schema" | "eventId" | "producerLineage" | "payloadHash">,
): MonitoringEvent {
  const payloadHash = hashText(JSON.stringify(fields.message));
  const eventId = lengthHash("collie.monitoring.event.v1", lineage, fields.slot, fields.producerSequence, payloadHash);
  const event: MonitoringEvent = { schema: 1, eventId, producerLineage: lineage, ...fields, payloadHash };
  const encoded=JSON.stringify(event);
  if(parseMachineStrictJson(encoded)===null)throw new Error("machine.monitoring_event_invalid_json");
  if (Buffer.byteLength(encoded, "utf8") > EVENT_MAX_BYTES) throw new Error("machine.monitoring_event_too_large");
  return event;
}

export function eventBytes(events:readonly MonitoringEvent[]):number {return events.reduce((total,event)=>total+Buffer.byteLength(JSON.stringify(event),"utf8"),0);}
export function outboxUsage(generation:MutableMonitoringGeneration):number {return generation.outboxBytes+generation.reservedClearCount*EVENT_MAX_BYTES;}

function appendAlertEvent(generation: MutableMonitoringGeneration, lineage: string, opened: AlertOpened, observedAt: number): void {
  const incarnation = opened.machineIncarnation!;
  const slot = slotFor(incarnation, opened.metric);
  if (generation.reservedClear[slot] !== undefined || generation.outbox.some((event) => event.slot === slot)) {
    throw new Error("machine.monitoring_slot_pending");
  }
  const sequence = sequenceAfter(generation.producerSequences[slot] ?? "0");
  const clearSequence = sequenceAfter(sequence);
  const episodeId = lengthHash("collie.monitoring.episode.v1", lineage, slot, sequence);
  const name = boundedMonitoringText(opened.name,80);
  const rendered = machineAlertMessage({ ...opened, name });
  const message: MonitoringMessage = {
    type: "machine", tag: rendered.tag!, titleCode: rendered.titleCode!, title: boundedMonitoringText(rendered.title??"",160),
    body: boundedMonitoringText(rendered.body??"",512), machine: opened.id, topic: rendered.topic!, renotify: true,
  };
  const event = makeMonitoringEvent(lineage, {
    slot, producerSequence: sequence, machineId: opened.id, machineIncarnation: incarnation, metric: opened.metric,
    rulesRevision: opened.rulesRevision!, episodeId, kind: "alert", observedAt, message,
  });
  generation.producerSequences[slot] = clearSequence;
  generation.reservedClear[slot] = { sequence: clearSequence, episodeId };
  generation.reservedClearCount++;
  generation.outbox.push(event);
  generation.outboxBytes+=eventBytes([event]);
  if (outboxUsage(generation) > MACHINE_MONITORING_OUTBOX_MAX_BYTES) throw new Error("machine.monitoring_outbox_full");
}

function appendClearEvent(
  generation: MutableMonitoringGeneration,
  lineage: string,
  fields: { id: string; incarnation: string; metric: AlertMetric; rulesRevision: string; observedAt: number },
): void {
  const slot = slotFor(fields.incarnation, fields.metric);
  const reserved = generation.reservedClear[slot];
  if (generation.outbox.some((event) => event.slot === slot && event.kind === "clear")) return;
  const sequence = reserved?.sequence ?? sequenceAfter(generation.producerSequences[slot] ?? "0");
  const episodeId = reserved?.episodeId ?? lengthHash("collie.monitoring.episode.retired.v1", lineage, slot, sequence);
  const event = makeMonitoringEvent(lineage, {
    slot, producerSequence: sequence, machineId: fields.id, machineIncarnation: fields.incarnation, metric: fields.metric,
    rulesRevision: fields.rulesRevision, episodeId, kind: "clear", observedAt: fields.observedAt,
    message: { type: "clear", tag: `collie:machine:${fields.id}:${fields.metric}` },
  });
  delete generation.reservedClear[slot];
  if(reserved!==undefined)generation.reservedClearCount--;
  generation.producerSequences[slot] = sequence;
  generation.outbox.push(event);
  generation.outboxBytes+=eventBytes([event]);
  if (outboxUsage(generation) > MACHINE_MONITORING_OUTBOX_MAX_BYTES) throw new Error("machine.monitoring_clear_reservation_broken");
}

function incrementCounter(value: string): string | null {
  const next = BigInt(value) + 1n;
  return next > UINT64_MAX ? null : next.toString();
}

export interface RuleRequestReceipt {
  readonly requestId: string;
  readonly actorHash: string;
  readonly machineId: string;
  readonly requestHash: string;
  readonly result: { readonly rules: MachineAlerts; readonly open: readonly AlertMetric[]; readonly rulesRevision: string; readonly machineIncarnation: string; readonly cleared: readonly AlertMetric[]; readonly extensions?: JsonObject };
}

export type RuleReplaceResult =
  | { readonly kind: "accepted"; readonly current: Omit<RuleRequestReceipt["result"], "cleared">; readonly cleared: readonly AlertMetric[] }
  | { readonly kind: "replay"; readonly current: RuleRequestReceipt["result"] }
  | { readonly kind: "conflict"; readonly current: ReturnType<MachineAlertStore["snapshot"]> }
  | { readonly kind: "retired" }
  | { readonly kind: "unavailable" };

export function hashText(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function nextRevision(value: string): string | null {
  const next = BigInt(value) + 1n;
  return next <= UINT64_MAX ? next.toString(10) : null;
}

function serializeEntries(entries: Map<string, StoredMachineAlerts>): JsonObject {
  const out: JsonObject = {};
  for (const [id, value] of entries) out[id] = { ...value, rules: { ...value.rules }, open: [...value.open] };
  return out;
}

function freezeMonitoringValue<T>(value:T):T {
  if(Object(value)!==value || Object.isFrozen(value)) return value;
  for(const child of Object.values(Object(value))) freezeMonitoringValue(child);
  return Object.freeze(value);
}

function boundedMonitoringText(value:string,limit:number):string {
  const trimmed=redactText(value).slice(0,limit),last=trimmed.charCodeAt(trimmed.length-1);
  return last>=0xd800 && last<=0xdbff ? trimmed.slice(0,-1) : trimmed;
}
