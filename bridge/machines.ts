import type {JsonObject} from "./json.ts";
// The lead's watch over every machine's load: the last sample each one gave, a day of minutes, and
// the alert rules (ADR 0084). A solo collie runs the same watch over its one machine.
//
// ── NO TIMER, AND NOTHING HERE DIALS ─────────────────────────────────────────
// Samples arrive two ways and both are hooks that already run: this machine's own sampler, read from
// the state engine's tick, and each member's `machineStats`, read off the answer the lead's sweep
// already parsed (CREW_PROTOCOL.md §10.1). {@link MachineWatch.tick} runs from that same engine tick,
// judges the alert rules once a minute and saves the history at most every five minutes. The class
// arms nothing, which is the rule `CacheWarden` and `CrewLead` keep for the same reason.
//
// ── WHAT THE PHONE READS IS ALREADY HERE ─────────────────────────────────────
// `rows()` and `history()` read memory. The roster comes from the same closure `GET /api/crew` reads,
// so asking about machines can no more make the lead dial a member than asking about the crew can.
//
// ── THE SAMPLE RATE FOLLOWS THE PHONE ────────────────────────────────────────
// `rows()` notes when a phone last asked. For {@link MACHINES_WATCH_MS} after that, this machine
// samples at most every 5 s (`SAMPLE_WATCHED_MS`); otherwise at most every 15 s (`SAMPLE_IDLE_MS`).
// Nothing is armed: the engine tick asks {@link MachineWatch.sampleEveryMs} each time it runs. The
// Machines list and a machine's page ask on every poll (4 to 6 s), the dashboard's Crew tab every
// 15 s, so all three hold the fast rate, and a phone put away drops it within 30 s.
//
// The idle arithmetic, which the alert rules lean on (ALERT_COVERAGE in machine-alerts.ts: 80 % of
// a window's minutes must hold data). The tick runs every 12 s while the multiplexer's event stream
// is healthy and every 1.5 s otherwise, plus once per event poke. A 15 s floor on a 12 s tick takes a
// sample on every second tick, 24 s apart, so every minute bucket holds two or three samples and
// none is empty. On a 1.5 s tick it is one every 15 s, four per minute. Any idle tick up to 60 s
// still leaves no minute empty, because the gap between two samples is then the tick itself.
//
// A member has no phone asking, so it samples at the idle rate and serves the sample it holds to
// every sweep. The lead skips a sample equal to the last one it took ({@link MachineWatch.observe}),
// so it records each of the member's samples once: a new one every 24 s at most, stamped on the
// lead's clock within one lead tick of the member taking it. The gap between two stamps is at most
// 24 s plus one 12 s lead tick, 36 s, so a member's minute buckets are never empty either.

import type { MachineAlertStore } from "./machine-alerts.ts";
import { createHash } from "node:crypto";
import { machineAlertMessage } from "./machine-alerts.ts";
import type { RuleReplaceResult } from "./machine-alerts.ts";
import { MINUTE_MS, minuteOf, SPARK_MAX_MINUTES, type MachineHistory } from "./machine-history.ts";
import { SAMPLE_IDLE_MS, SAMPLE_WATCHED_MS } from "./machine-stats.ts";
import type { PushMessage } from "./push.ts";
import type {
  CrewStatusResponse,
  MachineAlerts,
  MachineHistoryResponse,
  MachineRow,
  MachineSample,
  MachinesResponse,
} from "./types.ts";

/** The longest the history goes unsaved while it is changing. */
export const HISTORY_SAVE_EVERY_MS = 5 * 60_000;

/**
 * How long one `GET /api/machines` keeps this machine sampling at the fast rate. Twice the Crew tab's
 * 15 s pace, so a tab that skips one round on a slow link does not drop the rate.
 */
export const MACHINES_WATCH_MS = 30_000;

/** The machine id of a solo collie that never enrolled: it has no member id to use. */
export const SOLO_MACHINE_ID = "local";

/** One machine as the roster names it. The lead first; a solo collie is one entry. */
export interface MachineRosterEntry {
  readonly id: string;
  readonly name: string;
  readonly isLead: boolean;
  readonly health: MachineRow["health"];
  /** Public pinned identity material used only to derive the incarnation hash. */
  readonly fingerprint?: string;
  readonly enrolledAt?: number;
  readonly solo?: boolean;
}

/**
 * The machines a lead or a solo collie answers for. A lead's list IS the crew overview's rows — the
 * same `GET /api/crew` body, so a machine's id here is its `CrewMemberStatus.id`, the lead's own row
 * included, and the crew page's link to `/machines/<id>` lands on the right machine by construction.
 * With no crew to report (`status` null) the one row is this collie itself.
 */
export function machineRosterOf(
  status: CrewStatusResponse | null,
  self: { readonly id: string; readonly name: string; readonly fingerprint?: string; readonly createdAt?: number },
): MachineRosterEntry[] {
  if (status !== null) {
    return status.members.map(m=>{
      const row:MachineRosterEntry={id:m.id,name:m.name,isLead:m.isLead,health:m.health};
      if(m.enrolledAt!==undefined)Object.assign(row,{enrolledAt:m.enrolledAt});
      return row;
    });
  }
  const row:MachineRosterEntry={id:self.id,name:self.name,isLead:true,health:"reachable",solo:true};
  if(self.fingerprint!==undefined)Object.assign(row,{fingerprint:self.fingerprint});
  if(self.createdAt!==undefined)Object.assign(row,{enrolledAt:self.createdAt});
  return [row];
}

/** Length-prefixed identity hash: no hostname, address, label, or concatenation ambiguity. */
export function machineIncarnationOf(entry: MachineRosterEntry, soloLineage: string): string | null {
  if (entry.solo !== true && (!/^[0-9a-f]{64}$/.test(entry.fingerprint ?? "") || !Number.isSafeInteger(entry.enrolledAt) || (entry.enrolledAt ?? -1) < 0)) return null;
  const hash = createHash("sha256");
  const fields = entry.solo === true
    ? ["collie.machine.solo.incarnation.v1", soloLineage]
    : ["collie.machine.incarnation.v1", entry.id, entry.fingerprint ?? "", String(entry.enrolledAt ?? "")];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    const size = Buffer.alloc(4); size.writeUInt32BE(bytes.length);
    hash.update(size); hash.update(bytes);
  }
  return hash.digest("hex");
}

export interface MachineWatchDeps {
  readonly now: () => number;
  /** Every machine this collie answers for, read on every call: a member can join or leave live. */
  readonly roster: () => readonly MachineRosterEntry[];
  readonly history: MachineHistory;
  readonly alerts: MachineAlertStore;
  /** `saveMachineHistory` in production, a recorder in the test. */
  readonly saveHistory: (history: MachineHistory, now: number) => Promise<void>;
  /** `snooze.isMuted()`, read live. */
  readonly muted: () => boolean;
  /** `notifyPrefs.current().machines`, read live. */
  readonly enabled: () => boolean;
  /** `push.send`. */
  readonly send: (msg: PushMessage, originalProducerCurrent?: () => boolean) => void;
  readonly captureProducer?: () => (() => boolean);
  /**
   * Whether this collie still answers for its machines, read live. False once it is deposed
   * (CREW_PROTOCOL.md §18.12): a deposed lead's roster is void, so it records, judges and pushes
   * nothing. Absent means always.
   */
  readonly active?: () => boolean;
  readonly log?: (line: string) => void;
}

/** What `bridge/server.ts` asks of the watch. Structural, so a route test can pass a small object. */
export interface MachineSurface {
  /** `spark` is the minutes of the small charts the request asked for (1..60), or none. */
  rows(opts?: { spark?: number }): MachinesResponse;
  /** `since` keeps the minutes starting at or after it. */
  history(id: string, since?: number): MachineHistoryResponse | null;
  entry(id: string): MachineRosterEntry | undefined;
  setAlerts(id: string, alerts: MachineAlerts): Promise<MachineAlerts | null>;
  /** Conditional native/modern rule write; the legacy method remains for source-compatible callers. */
  replaceRules?(input: { id: string; alerts: MachineAlerts; rulesRevision?: string; machineIncarnation?: string; requestId?: string; actor: string | null; extensions?: JsonObject; observedAt?: number }, authorityCurrent: () => boolean): Promise<RuleReplaceResult>;
}

export class MachineWatch implements MachineSurface {
  private readonly latest = new Map<string, { readonly sample: MachineSample; readonly at: number }>();
  private readonly historyOwners = new Map<string,string>();
  private lastJudgedMinute: number | null = null;
  private lastSave: number;
  private judging = false;
  private pendingJudgement: number | null = null;
  private judgeChain: Promise<void> = Promise.resolve();
  /** When a phone last asked for the list; 0 is never. */
  private askedAt = 0;
  /** Every history write, one after another: a tick's save and the shutdown flush never interleave. */
  private saveChain: Promise<void> = Promise.resolve();
  private readonly log: (line: string) => void;

  constructor(private readonly deps: MachineWatchDeps) {
    this.lastSave = deps.now();
    this.log = deps.log ?? ((line) => console.warn(line));
    this.deps.alerts.attachHistory(deps.history, deps.now);
    this.deps.alerts.attachRoster(()=>deps.roster().flatMap(entry=>{
      const incarnation=machineIncarnationOf(entry,deps.alerts.producerLineage());
      if(incarnation===null)return [];
      const owner:import("./machine-alerts.ts").MonitoringMember={id:entry.id,incarnation};
      if(entry.fingerprint!==undefined)Object.assign(owner,{fingerprint:entry.fingerprint});
      if(entry.enrolledAt!==undefined)Object.assign(owner,{enrolledAt:entry.enrolledAt});
      if(entry.solo!==undefined)Object.assign(owner,{solo:entry.solo});
      return [owner];
    }));
    for(const member of deps.roster()) {const incarnation=deps.alerts.committedHistoryOwner(member.id) ?? machineIncarnationOf(member,deps.alerts.producerLineage());if(incarnation!==null && incarnation!==undefined) this.historyOwners.set(member.id,incarnation);}
    const watermark = deps.alerts.evaluatorWatermark();
    if (watermark !== null) this.lastJudgedMinute = watermark + MINUTE_MS;
  }

  /**
   * One sample for one machine, stamped with THIS bridge's clock (CREW_PROTOCOL.md §10.2).
   *
   * A sample equal in every field to the last one taken for that machine is skipped: not recorded,
   * and `sampledAt` does not move. It is the same reading served again. That is the ordinary case when
   * the lead sweeps faster than a member samples (the member serves the sample it holds), and the
   * only signature a member whose sampler hangs leaves: it keeps answering with its last sample, and
   * stamping that fresh on every sweep would draw a live, flat machine forever and let an alert judge
   * a minute nobody measured.
   *
   * A real reading that repeats another in every field is not expected. `cpu` and the network rates
   * are ratios of counters over the time between two reads, and memory is counted in bytes. The
   * likeliest real repeat is a machine pinned at 100 % CPU (`cpu` is exactly 1) on a host with no
   * network counters, whose memory also held still to the byte; skipping that one sample costs a
   * point, and the next one that differs records as usual.
   */
  observe(id: string, sample: MachineSample, at: number): void {
    if (!this.isActive()) return;
    const member=this.entry(id);
    const incarnation=member===undefined ? null : machineIncarnationOf(member,this.deps.alerts.producerLineage());
    if(incarnation===null) return;
    if(this.historyOwners.get(id)!==incarnation) {this.latest.delete(id);this.deps.history.drop(id);this.historyOwners.set(id,incarnation);}
    const previous = this.latest.get(id);
    if (previous !== undefined && sameSample(previous.sample, sample)) return;
    this.latest.set(id, { sample, at });
    this.deps.history.record(id, sample, at);
  }

  /** A member that left the crew takes its last sample, its history and its alert rules with it. */
  forget(id: string): void {
    const producerCurrent = this.captureProducer();
    if (!producerCurrent()) return;
    this.latest.delete(id);
    this.historyOwners.delete(id);
    this.deps.history.drop(id);
    void this.deps.alerts
      .drop(id, producerCurrent)
      .then((open) => { if (!producerCurrent()) return undefined; for (const metric of open) this.deps.send({ type: "clear", tag: `collie:machine:${id}:${metric}` }, ()=>producerCurrent() && this.deps.alerts.originalRetirementClearCurrent(open,id,metric)); return undefined; })
      .catch((err) => this.log(`[machines] could not save the alert rules: ${String(err)}`));
  }

  /**
   * The engine tick's share: judge the rules when a minute has closed, save the history when it is
   * due. Never throws and never awaits: the poll it rides must not wait on a file.
   */
  tick(): void {
    if (!this.isActive()) return;
    const now = this.deps.now();
    const minute = minuteOf(now);
    if (minute !== this.lastJudgedMinute) {
      if (this.judging) this.pendingJudgement = now; else this.judge(now);
    }
    // A negative gap is a clock that stepped back: a save is due, not held until it catches up.
    const sinceSave = now - this.lastSave;
    if (this.deps.history.dirty() && (sinceSave >= HISTORY_SAVE_EVERY_MS || sinceSave < 0)) void this.save(now);
  }

  /**
   * Save now, whatever the clock says, after any write already under way. The shutdown path; a store
   * with nothing new writes nothing. The alert store's pending write is waited for too.
   */
  async flush(): Promise<void> {
    if (this.deps.history.dirty()) void this.save(this.deps.now());
    while (this.judging) await this.judgeChain;
    await this.saveChain;
    await this.deps.alerts.settled();
  }

  /**
   * How often this machine should sample itself now: fast while a phone asked for the list in the
   * last {@link MACHINES_WATCH_MS}, slow otherwise. Read by the engine tick, which arms nothing.
   */
  sampleEveryMs(now = this.deps.now()): number {
    // A phone's ask stamped in the future (the clock stepped back) would hold the watched pace until
    // the clock caught up: clamp it to now, so it lapses in the usual window.
    if (this.askedAt > now) this.askedAt = now;
    return now - this.askedAt <= MACHINES_WATCH_MS ? SAMPLE_WATCHED_MS : SAMPLE_IDLE_MS;
  }

  /** `GET /api/machines`, with each row's spark when `opts.spark` asks for one. */
  rows(opts: { spark?: number } = {}): MachinesResponse {
    const now = this.deps.now();
    this.askedAt = now;
    const spark = opts.spark !== undefined && Number.isInteger(opts.spark) ? Math.min(SPARK_MAX_MINUTES, Math.max(1, opts.spark)) : undefined;
    const machines = this.deps.roster().map((entry): MachineRow => {
      const incarnation = machineIncarnationOf(entry, this.deps.alerts.producerLineage());
      const state:Omit<ReturnType<MachineAlertStore["snapshot"]>,"machineIncarnation"> & {machineIncarnation?:string} = incarnation === null
        ? { rules: {}, open: [], rulesRevision: this.deps.alerts.revision(entry.id), machineIncarnation: undefined }
        : this.deps.alerts.snapshot(entry.id, incarnation);
      const row: MachineRow = {
        id: entry.id,
        name: entry.name,
        isLead: entry.isLead,
        health: entry.health,
        alerts: state.rules,
        rulesRevision: state.rulesRevision,
        firing: state.open,
      };
      if(state.extensions!==undefined)row.extensions=state.extensions;
      if(state.machineIncarnation!==undefined)row.machineIncarnation=state.machineIncarnation;
      // Assigned, never spread from undefined: an absent sample is an absent pair of keys.
      const last = this.latest.get(entry.id);
      if (last !== undefined && incarnation!==null && this.historyOwners.get(entry.id)===incarnation) {
        row.sample = last.sample;
        row.sampledAt = last.at;
      }
      const lines = spark === undefined || incarnation===null || this.historyOwners.get(entry.id)!==incarnation ? null : this.deps.history.spark(entry.id, now, spark);
      if (lines !== null) row.spark = lines;
      return row;
    });
    return { ts: now, machines };
  }

  /** `GET /api/machines/:id/history`, or `null` for a machine this collie does not answer for. */
  history(id: string, since?: number): MachineHistoryResponse | null {
    if (!this.known(id)) return null;
    const now = this.deps.now();
    const entry=this.entry(id)!;const incarnation=machineIncarnationOf(entry,this.deps.alerts.producerLineage());
    return { ts: now, stepMs: 60_000, points: incarnation!==null && this.historyOwners.get(id)===incarnation ? this.deps.history.points(id, now, since) : [] };
  }

  /** `POST /api/machines/:id/alerts`, or `null` for an unknown machine. Written before it answers. */
  async setAlerts(id: string, alerts: MachineAlerts): Promise<MachineAlerts | null> {
    const producerCurrent = this.captureProducer();
    const entry = this.entry(id);
    if (entry === undefined) return null;
    const incarnation = machineIncarnationOf(entry, this.deps.alerts.producerLineage());
    if (incarnation === null) return null;
    const outcome = await this.deps.alerts.replaceRules({ id, incarnation, rules: alerts, actor: null, observedAt: this.deps.now() }, () => {
      const current = this.entry(id);
      return producerCurrent() && current !== undefined && machineIncarnationOf(current, this.deps.alerts.producerLineage()) === incarnation;
    });
    if(!producerCurrent()) return null;
    if (outcome.kind === "accepted" && producerCurrent()) {
      for (const metric of outcome.cleared) this.deps.send({ type: "clear", tag: `collie:machine:${id}:${metric}` }, ()=>producerCurrent() && this.deps.alerts.originalRuleClearCurrent(outcome,id,incarnation,metric));
      return outcome.current.rules;
    }
    return outcome.kind === "replay" ? outcome.current.rules : null;
  }

  async replaceRules(
    input: { id: string; alerts: MachineAlerts; rulesRevision?: string; machineIncarnation?: string; requestId?: string; actor: string | null; extensions?: JsonObject; observedAt?: number },
    authorityCurrent: () => boolean,
  ): Promise<RuleReplaceResult> {
    const producerCurrent = this.captureProducer();
    const entry = this.entry(input.id);
    if (entry === undefined) return { kind: "retired" };
    const incarnation = machineIncarnationOf(entry, this.deps.alerts.producerLineage());
    if (incarnation === null) return { kind: "unavailable" };
    if (input.machineIncarnation !== undefined && input.machineIncarnation !== incarnation) {
      return { kind: "conflict", current: this.deps.alerts.snapshot(input.id, incarnation) };
    }
    const replacement:Parameters<MachineAlertStore["replaceRules"]>[0]={id:input.id,incarnation,rules:input.alerts,actor:input.actor,observedAt:input.observedAt??this.deps.now()};
    if(input.rulesRevision!==undefined)replacement.expectedRevision=input.rulesRevision;
    if(input.requestId!==undefined)replacement.requestId=input.requestId;
    if(input.extensions!==undefined)replacement.extensions=input.extensions;
    const outcome = await this.deps.alerts.replaceRules(replacement, () => {
      const current = this.entry(input.id);
      return current !== undefined && machineIncarnationOf(current, this.deps.alerts.producerLineage()) === incarnation && producerCurrent() && authorityCurrent();
    });
    if(!producerCurrent() || !authorityCurrent()) return {kind:"retired"};
    if (outcome.kind === "accepted" && producerCurrent()) {
      for (const metric of outcome.cleared) this.deps.send({ type: "clear", tag: `collie:machine:${input.id}:${metric}` }, ()=>producerCurrent() && this.deps.alerts.originalRuleClearCurrent(outcome,input.id,incarnation,metric));
    }
    return outcome;
  }

  /** The roster's entry for `id`, or `undefined` for a machine this collie does not answer for. */
  entry(id: string): MachineRosterEntry | undefined {
    return this.deps.roster().find((entry) => entry.id === id);
  }

  /** The mount of `id`'s fullest disk in its last sample, which a disk push names. */
  private fullestMount(id: string): string | undefined {
    let best: { mount: string; frac: number } | undefined;
    for (const d of this.latest.get(id)?.sample.disks ?? []) {
      const frac = d.total > 0 ? d.used / d.total : 0;
      if (best === undefined || frac > best.frac) best = { mount: d.mount, frac };
    }
    return best?.mount;
  }

  private known(id: string): boolean {
    return this.entry(id) !== undefined;
  }

  private judge(now: number): void {
    const subjects = this.deps.roster().map((entry) => {
      const incarnation = machineIncarnationOf(entry, this.deps.alerts.producerLineage());
      const snapshot = incarnation === null ? { rules: {}, open: [], rulesRevision: this.deps.alerts.revision(entry.id) } : this.deps.alerts.snapshot(entry.id, incarnation);
      const subject:import("./machine-alerts.ts").AlertSubject={
        id: entry.id, name: entry.name, reachable: entry.health === "reachable", rules: snapshot.rules, open: snapshot.open,
        rulesRevision: snapshot.rulesRevision,
      };
      if(incarnation!==null)Object.assign(subject,{machineIncarnation:incarnation});
      return subject;
    });
    const producerCurrent = this.captureProducer();
    this.judging = true;
    this.judgeChain = this.deps.alerts.evaluateMinute(subjects, this.deps.history, now,
      this.deps.enabled() && !this.deps.muted(), producerCurrent)
      .then(({ pass, complete }) => {
        if (!producerCurrent()) return undefined;
        if (complete) this.lastJudgedMinute = minuteOf(now);
        for (const opened of pass.opened) this.deps.send(machineAlertMessage(opened, this.fullestMount(opened.id)), ()=>producerCurrent() && this.deps.alerts.originalEventCurrent(opened));
        for (const closed of pass.closed) this.deps.send({ type: "clear", tag: `collie:machine:${closed.id}:${closed.metric}` }, ()=>producerCurrent() && this.deps.alerts.originalEventCurrent(closed));
        return undefined;
      })
      .catch((err) => this.log(`[machines] could not save the monitoring generation: ${String(err)}`))
      .finally(() => {
        this.judging = false;
        const pending = this.pendingJudgement; this.pendingJudgement = null;
        if (pending !== null && minuteOf(pending) !== this.lastJudgedMinute && this.isActive()) this.judge(pending);
      });
  }

  private captureProducer(): () => boolean {
    const original = this.deps.captureProducer?.();
    const roster = original===undefined ? JSON.stringify(this.deps.roster().map(entry => [entry.id, machineIncarnationOf(entry, this.deps.alerts.producerLineage())])) : null;
    let retired = !this.isActive();
    return () => {
      if (!this.isActive() || original !== undefined && !original() ||
        roster!==null && JSON.stringify(this.deps.roster().map(entry => [entry.id, machineIncarnationOf(entry, this.deps.alerts.producerLineage())])) !== roster) retired = true;
      return !retired;
    };
  }

  private isActive(): boolean {
    return this.deps.active?.() ?? true;
  }

  /**
   * Prune to the roster and queue one history write behind the last. Never rejects: a failed write is
   * logged, and the chain goes on.
   */
  private save(now: number): Promise<void> {
    const producerCurrent = this.captureProducer();
    this.lastSave = now;
    const ids = new Set(this.deps.roster().map((entry) => entry.id));
    this.deps.history.retain(ids);
    void this.deps.alerts
      .retain(ids, producerCurrent)
      .then((removed) => {
        if (!producerCurrent()) return undefined;
        for (const machine of removed) {
          for (const metric of machine.open) this.deps.send({ type: "clear", tag: `collie:machine:${machine.id}:${metric}` }, ()=>producerCurrent() && this.deps.alerts.originalRetirementClearCurrent(removed,machine.id,metric));
        }
        return undefined;
      })
      .catch((err) => this.log(`[machines] could not save the alert rules: ${String(err)}`));
    this.deps.history.markSaved();
    const write = async (): Promise<void> => {
      try {
        if (producerCurrent()) await this.deps.saveHistory(this.deps.history, now);
      } catch (err) {
        this.log(`[machines] could not save the history: ${String(err)}`);
      }
    };
    this.saveChain = this.saveChain.then(write);
    return this.saveChain;
  }
}

/**
 * Whether two samples say the same thing in every field, absent fields included.
 *
 * `disks` is compared too. A healthy machine repeats its disks for a minute by design (they are read
 * once a minute), but its CPU and memory still move, so a repeated `disks` alone never makes two
 * samples equal. A member whose sampler hung repeats every field, disks with them, and is still caught.
 */
export function sameSample(a: MachineSample, b: MachineSample): boolean {
  return (
    a.cpu === b.cpu &&
    a.cores === b.cores &&
    a.memUsed === b.memUsed &&
    a.memTotal === b.memTotal &&
    a.load1 === b.load1 &&
    a.rxBps === b.rxBps &&
    a.txBps === b.txBps &&
    sameDisks(a.disks, b.disks)
  );
}

function sameDisks(a: MachineSample["disks"], b: MachineSample["disks"]): boolean {
  if (a === undefined || b === undefined) return a === b;
  return a.length === b.length && a.every((d, i) => d.mount === b[i]!.mount && d.used === b[i]!.used && d.total === b[i]!.total);
}
