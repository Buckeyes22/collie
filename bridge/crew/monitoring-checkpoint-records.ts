import { MonitoringFactIndex } from "./monitoring-fact-index.ts";
import { createHash } from "node:crypto";
import { MachineHistory } from "../machine-history.ts";
import type { JsonValue, JsonObject } from "../json.ts";
import { parseMachineStrictJson } from "../machine-parse.ts";
import { monitoringSlotCustodyValid } from "../machine-parse.ts";
import { parseMachineAlerts, isAlertMetric } from "../machine-parse.ts";
import { parseMonitoringEvent } from "../machine-alerts.ts";

export type MonitoringCheckpointRecord =
  | { readonly kind: "state"; readonly lineage: string; readonly evaluatedThrough: number | null }
  | { readonly kind: "roster"; readonly memberId: string; readonly fingerprint: string; readonly enrolledAt: number }
  | { readonly kind: "machine"; readonly machineId: string; readonly value: JsonValue }
  | { readonly kind: "history"; readonly machineId: string; readonly machineIncarnation: string; readonly value: JsonValue }
  | { readonly kind: "receipt"; readonly requestId: string; readonly value: JsonValue }
  | { readonly kind: "outbox"; readonly eventId: string; readonly value: JsonValue }
  | { readonly kind: "sequence"; readonly slot: string; readonly value: string }
  | { readonly kind: "tombstone"; readonly memberId: string; readonly fingerprint: string; readonly enrolledAt: number; readonly machineIncarnation: string; readonly rulesRevision: string }
  | { readonly kind: "reservation"; readonly slot: string; readonly sequence: string; readonly episodeId: string };

const ORDER = ["state", "roster", "machine", "history", "receipt", "outbox", "sequence", "reservation", "tombstone"] as const;
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const HEX = /^[0-9a-f]{64}$/;
const COUNTER = /^(0|[1-9][0-9]{0,19})$/;
const COUNTER_MAX = 0xffff_ffff_ffff_ffffn;

function record(value: JsonValue | undefined): value is JsonObject { return typeof value === "object" && value !== null && !Array.isArray(value); }
function exactKeys(value: JsonObject, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && Object.keys(value).every((key) => keys.includes(key));
}
function validCounter(value: JsonValue | undefined): value is string { return typeof value === "string" && COUNTER.test(value) && BigInt(value) <= COUNTER_MAX; }

export function canonicalMonitoringCheckpointRecord(value: JsonValue | undefined): string | null {
  if (!record(value) || typeof value.kind !== "string") return null;
  if (value.kind === "state") {
    if (!exactKeys(value, ["kind", "lineage", "evaluatedThrough"]) || typeof value.lineage !== "string" || !HEX.test(value.lineage) ||
      !(value.evaluatedThrough === null || (Number.isSafeInteger(value.evaluatedThrough) && typeof value.evaluatedThrough === "number" && value.evaluatedThrough >= 0))) return null;
  } else if (value.kind === "roster") {
    if (!exactKeys(value, ["kind", "memberId", "fingerprint", "enrolledAt"]) || typeof value.memberId !== "string" || !ID.test(value.memberId) ||
      typeof value.fingerprint !== "string" || !HEX.test(value.fingerprint) || !Number.isSafeInteger(value.enrolledAt) || typeof value.enrolledAt !== "number" || value.enrolledAt < 0) return null;
  } else if (value.kind === "machine") {
    if (!exactKeys(value, ["kind", "machineId", "value"]) || typeof value.machineId !== "string" || !ID.test(value.machineId) || !record(value.value)) return null;
    if (!exactKeys(value.value, ["rules", "open", "rulesRevision", "machineIncarnation", ...(value.value.extensions === undefined ? [] : ["extensions"])])) return null;
    if (!record(value.value.rules) || Object.keys(value.value.rules).some((key) => !["cpu", "mem", "disk"].includes(key)) || !Array.isArray(value.value.open) ||
      !value.value.open.every(isAlertMetric) || new Set(value.value.open).size !== value.value.open.length ||
      typeof value.value.rulesRevision !== "string" || !validCounter(value.value.rulesRevision) || typeof value.value.machineIncarnation !== "string" || !HEX.test(value.value.machineIncarnation)) return null;
    for (const rule of Object.values(value.value.rules)) {
      if (!record(rule) || !exactKeys(rule, ["above", "forMin"]) || typeof rule.above !== "number" || !Number.isFinite(rule.above) || rule.above < 0.5 || rule.above > 0.99 ||
        typeof rule.forMin !== "number" || !Number.isInteger(rule.forMin) || rule.forMin < 5 || rule.forMin > 120) return null;
    }
    const machineRules = value.value.rules;
    if (value.value.open.some((metric) => machineRules[metric] === undefined) ||
      value.value.extensions !== undefined && (!validExtensions(value.value.extensions, 4, { left: 64 }) || Buffer.byteLength(JSON.stringify(value.value.extensions), "utf8") > 4096)) return null;
    if (parseMachineAlerts(value.value.rules) === null) return null;
  } else if (value.kind === "history") {
    if (!exactKeys(value, ["kind", "machineId", "machineIncarnation", "value"]) || typeof value.machineId !== "string" || !ID.test(value.machineId) || typeof value.machineIncarnation!=="string" || !HEX.test(value.machineIncarnation) || !record(value.value)) return null;
    if (Object.keys(value.value).length !== 1 || !Array.isArray(value.value[value.machineId])) return null;
    if (MachineHistory.fromMonitoringState(value.value, Date.now()) === null) return null;
  } else if (value.kind === "receipt") {
    if (!exactKeys(value, ["kind", "requestId", "value"]) || typeof value.requestId !== "string" || !/^[A-Za-z0-9_-]{16,96}$/.test(value.requestId) || !record(value.value) ||
      !exactKeys(value.value, ["requestId", "actorHash", "machineId", "requestHash", "result"]) || value.value.requestId !== value.requestId ||
      typeof value.value.actorHash !== "string" || !HEX.test(value.value.actorHash) || typeof value.value.machineId !== "string" || !ID.test(value.value.machineId) ||
      typeof value.value.requestHash !== "string" || !HEX.test(value.value.requestHash) || !validReceiptResult(value.value.result)) return null;
  } else if (value.kind === "outbox") {
    if (!exactKeys(value, ["kind", "eventId", "value"]) || typeof value.eventId !== "string" || !HEX.test(value.eventId) || !record(value.value) || value.value.eventId !== value.eventId) return null;
  } else if (value.kind === "sequence") {
    if (!exactKeys(value, ["kind", "slot", "value"]) || typeof value.slot !== "string" || !HEX.test(value.slot) || !validCounter(value.value)) return null;
  } else if (value.kind === "reservation") {
    if (!exactKeys(value, ["kind", "slot", "sequence", "episodeId"]) || typeof value.slot !== "string" || !HEX.test(value.slot) ||
      !validCounter(value.sequence) || value.sequence === "0" || typeof value.episodeId !== "string" || !HEX.test(value.episodeId)) return null;
  } else if (value.kind === "tombstone") {
    if (!exactKeys(value,["kind","memberId","fingerprint","enrolledAt","machineIncarnation","rulesRevision"]) ||
      typeof value.memberId !== "string" || !ID.test(value.memberId) || typeof value.fingerprint !== "string" || !HEX.test(value.fingerprint) ||
      typeof value.enrolledAt !== "number" || !Number.isSafeInteger(value.enrolledAt) || value.enrolledAt < 0 ||
      typeof value.machineIncarnation !== "string" || value.machineIncarnation !== rosterIncarnation(value.memberId,value.fingerprint,value.enrolledAt) ||
      !validCounter(value.rulesRevision)) return null;
  } else return null;
  const encoded=JSON.stringify(value);
  return Buffer.byteLength(encoded,"utf8")<=512*1024 && parseMachineStrictJson(encoded)!==null ? encoded : null;
}

export interface CheckpointRecordDigests { readonly rosterDigest: string; readonly rulesDigest: string; readonly producerDigest: string; readonly historyFrom: number; readonly historyThrough: number }

export function monitoringRecordsFromSnapshot(
  snapshot: JsonValue,
  roster: readonly { readonly id: string; readonly fingerprint?: string; readonly enrolledAt?: number }[],
  indexParent?: string,
): IndexedMonitoringRecords | null {
  if (!record(snapshot) || snapshot.schema !== 1 || typeof snapshot.lineage !== "string" || !HEX.test(snapshot.lineage) || !record(snapshot.machines) ||
    !record(snapshot.history) || !Array.isArray(snapshot.receipts) || !Array.isArray(snapshot.outbox) || !record(snapshot.producerSequences) || !record(snapshot.reservedClear)) return null;
  if (!(snapshot.evaluatedThrough === null || (typeof snapshot.evaluatedThrough === "number" && Number.isSafeInteger(snapshot.evaluatedThrough) && snapshot.evaluatedThrough >= 0))) return null;
  if (roster.some((item) => item.fingerprint === undefined || item.enrolledAt === undefined)) return null;
  const checked = snapshot;
  const machines=snapshot.machines,history=snapshot.history,receipts=snapshot.receipts,outbox=snapshot.outbox,producerSequences=snapshot.producerSequences,reservedClear=snapshot.reservedClear,lineage=snapshot.lineage,evaluatedThrough=snapshot.evaluatedThrough;
  let index: MonitoringFactIndex | null = null;
  let borrowed=false;
  function* originals():Generator<MonitoringCheckpointRecord> {
      yield ({kind:"state",lineage:lineage,evaluatedThrough:evaluatedThrough});
      for(const member of roster) yield ({kind:"roster",memberId:member.id,fingerprint:member.fingerprint!,enrolledAt:member.enrolledAt!});
      if(Array.isArray(checked.roster)) {
        if(checked.roster.length!==roster.length) throw new Error("checkpoint.roster_changed");
        for(const member of checked.roster) {
          if(!record(member) || typeof member.id!=="string" || typeof member.fingerprint!=="string" || typeof member.enrolledAt!=="number") throw new Error("checkpoint.roster_invalid");
          yield ({kind:"roster",memberId:member.id,fingerprint:member.fingerprint,enrolledAt:member.enrolledAt});
        }
      }
      for(const id in machines) yield ({kind:"machine",machineId:id,value:(machines)[id]!});
      for(const id in history) {
        const machine=(machines)[id];
        const owners=checked.historyOwners;
        const incarnation=record(owners) ? owners[id] : record(machine) ? machine.machineIncarnation : undefined;
        if(typeof incarnation!=="string") throw new Error("checkpoint.history_owner_missing");
        yield (historyRecordFromState(id,(history)[id]!,incarnation));
      }
      for(const receipt of receipts) {
        if(!record(receipt) || typeof receipt.requestId !== "string") throw new Error("checkpoint.receipt_invalid");
        yield ({kind:"receipt",requestId:receipt.requestId,value:receipt});
      }
      for(const event of outbox) {
        if(!record(event) || typeof event.eventId !== "string") throw new Error("checkpoint.event_invalid");
        yield ({kind:"outbox",eventId:event.eventId,value:event});
      }
      for(const slot in producerSequences) yield ({kind:"sequence",slot,value:String((producerSequences)[slot])});
      for(const slot in reservedClear) {
        const value=(reservedClear)[slot];
        if(!record(value) || typeof value.sequence !== "string" || typeof value.episodeId !== "string") throw new Error("checkpoint.reservation_invalid");
        yield ({kind:"reservation",slot,sequence:value.sequence,episodeId:value.episodeId});
      }
      if(Array.isArray(checked.tombstones)) for(const value of checked.tombstones) {
        if(!record(value) || value.kind!=="tombstone" || canonicalMonitoringCheckpointRecord(value)===null)throw new Error("checkpoint.tombstone_invalid");
        // SAFETY: The closed tombstone fields and original incarnation binding passed the canonical validator.
        yield value as Extract<MonitoringCheckpointRecord,{kind:"tombstone"}>;
      }
  }
  const putRecord=(item:MonitoringCheckpointRecord):void=>{
    const canonical=canonicalMonitoringCheckpointRecord(item);
    if(canonical===null || !index!.put(item.kind,recordId(item),canonical))throw new Error("checkpoint.record_invalid");
  };
  const getIndex=():MonitoringFactIndex=>{
    if(index!==null)return index;
    index=new MonitoringFactIndex(indexParent);
    try {for(const item of originals())putRecord(item);return index;}
    catch(error){index.close();index=null;throw error;}
  };
  const prepare=async(current:()=>boolean):Promise<void>=>{
    if(index!==null)return;
    index=new MonitoringFactIndex(indexParent);
    const source=originals();
    try {
      let complete=false;
      while(!complete){
        complete=index.batch(()=>{
          for(let count=0;count<64;count++){
            if(!current())throw new Error("checkpoint.authority_changed");
            const step=source.next();
            if(step.done)return true;
            putRecord(step.value);
          }
          return false;
        });
        // Every private SQL transaction is committed before releasing the event loop.
        if(!complete){await Bun.sleep(0);if(!current())throw new Error("checkpoint.authority_changed");}
      }
    }catch(error){index.close();index=null;throw error;}
    finally{source.return(undefined);}
  };
  return {
    *[Symbol.iterator](): Generator<MonitoringCheckpointRecord> {
      const facts=getIndex();
      try {for(const kind of ORDER) for(const row of facts.rows(kind)) yield decodeIndexedRecord(kind,row.value);}
      finally {if(!borrowed) {index?.close();index=null;}}
    },
    prepare,
    createVerifier: () => {borrowed=true;return new MonitoringCheckpointRecordVerifier(getIndex(),false);},
    dispose: () => { index?.close(); index=null; },
  };
}

export interface IndexedMonitoringRecords extends Iterable<MonitoringCheckpointRecord> {
  prepare(current:()=>boolean):Promise<void>;
  createVerifier(): MonitoringCheckpointRecordVerifier;
  dispose(): void;
}
function recordId(item: MonitoringCheckpointRecord): string {
  if(item.kind==="tombstone") return item.machineIncarnation;
  return "memberId" in item ? item.memberId : "machineId" in item ? item.machineId : "requestId" in item ? item.requestId : "eventId" in item ? item.eventId : "slot" in item ? item.slot : "state";
}
function rosterIncarnation(id:string,fingerprint:string,enrolledAt:number):string {
  const hash=createHash("sha256");
  for(const value of ["collie.machine.incarnation.v1",id,fingerprint,String(enrolledAt)]) {
    const bytes=Buffer.from(value,"utf8"),length=Buffer.alloc(4);length.writeUInt32BE(bytes.length);hash.update(length).update(bytes);
  }
  return hash.digest("hex");
}

export function digestMonitoringCheckpointRecords(records: readonly MonitoringCheckpointRecord[]): CheckpointRecordDigests | null {
  const verifier=new MonitoringCheckpointRecordVerifier();
  try {
    for(const item of records) if(!verifier.add(item)) return null;
    return verifier.finish();
  } finally { verifier.close(); }
}

/** Stateful verifier used by the receiver; memory stays constant as chunks arrive. */
export class MonitoringCheckpointRecordVerifier {
  private readonly hashes = new Map<string, ReturnType<typeof createHash>>();
  private readonly previous = new Map<string, string>();
  private order = -1;
  private stateCount = 0;
  private lineage: string | null = null;
  private evaluatedThrough: number | null = null;
  private historyFrom = 0;
  private historyThrough = 0;
  private hasHistory = false;
  private readonly index: MonitoringFactIndex;
  constructor(index?: MonitoringFactIndex, private readonly ownsIndex=true) {
    this.index=index ?? new MonitoringFactIndex();
    for(const kind of ORDER) if(kind !== "state") this.hashes.set(kind,createHash("sha256"));
  }
  close():void { if(this.ownsIndex) this.index.close(); }
  get monitoringLineage(): string | null { return this.lineage; }

  private invalid=false;
  add(item:JsonValue | undefined):boolean {
    if(this.invalid) return false;
    try {const accepted=this.index.batch(()=>this.addRecord(item));if(!accepted) {this.invalid=true;this.close();}return accepted;}
    catch(error) {this.invalid=true;this.close();throw error;}
  }
  private addRecord(item: JsonValue | undefined): boolean {
    const encoded = canonicalMonitoringCheckpointRecord(item);
    if (encoded === null) return false;
    // SAFETY: canonicalMonitoringCheckpointRecord checked the complete closed record union above.
    const checked=item as MonitoringCheckpointRecord;
    const kind=checked.kind;
    const order=ORDER.indexOf(kind);
    if (order < this.order) return false;
    this.order = order;
    if (checked.kind === "state") {
      this.stateCount++;
      if (this.stateCount !== 1) return false;
      const state = checked;
      this.lineage = state.lineage; this.evaluatedThrough = state.evaluatedThrough;
      return true;
    }
    if(checked.kind === "outbox" && (this.lineage === null || parseMonitoringEvent(checked.value,this.lineage) === null)) return false;
    if(checked.kind==="machine") {
      // SAFETY: The machine record passed closed rules/open/incarnation/revision validation before any index insert.
      const machine=checked.value as {machineIncarnation:string;open:string[];rulesRevision:string};
      for(const metric of ["cpu","mem","disk"]) {
        const slot=monitoringSlotHash(machine.machineIncarnation,metric);
        if(!this.index.put("_owner",slot,machine.open.includes(metric)?"1":"0")) return false;
      }
    } else if(checked.kind==="tombstone") {
      for(const metric of ["cpu","mem","disk"]) {
        if(!this.index.put("_owner",monitoringSlotHash(checked.machineIncarnation,metric),"0")) return false;
      }
    } else if(checked.kind==="receipt") {
      // SAFETY: Canonical receipt validation checked its closed result and incarnation before this owner marker.
      const receipt=checked.value as {result:{machineIncarnation:string}};
      this.index.put("_receipt-owner",receipt.result.machineIncarnation,"1");
    } else if(checked.kind==="outbox") {
      const event=parseMonitoringEvent(checked.value,this.lineage!);
      if(event===null)return false;
      const priorEvents=this.index.get("_events",event.slot);
      const events:import("../machine-alerts.ts").MonitoringEvent[]=priorEvents===null ? [] : JSON.parse(priorEvents);
      if(events.length>=2 || events.some(peer=>peer.producerSequence===event.producerSequence)) return false;
      events.push(event);
      this.index.replace("_events",event.slot,JSON.stringify(events));
    }
    const id=recordId(checked);
    if(!this.index.put(kind,id,encoded)) return false;
    const prior = this.previous.get(kind);
    if (prior !== undefined && id <= prior) return false;
    this.previous.set(kind, id);
    this.hashes.get(kind)!.update(encoded, "utf8").update("\n", "utf8");
    if (checked.kind === "history") {
      const entry = checked;
      // SAFETY: Canonical history validation checked the matching machine key and every full-precision minute field.
      const minutes = (entry.value as Record<string, { t: number }[]>)[entry.machineId]!;
      if (minutes.length > 0) {
        const first = minutes[0]!.t; const last = minutes.at(-1)!.t;
        this.historyFrom = this.hasHistory ? Math.min(this.historyFrom, first) : first;
        this.historyThrough = this.hasHistory ? Math.max(this.historyThrough, last) : last;
        this.hasHistory = true;
      }
    }
    return true;
  }

  finish():CheckpointRecordDigests|null {
    const steps=this.finishSteps();for(;;){const step=steps.next();if(step.done)return step.value;}
  }
  async finishAsync(current:()=>boolean):Promise<CheckpointRecordDigests|null> {
    const steps=this.finishSteps();let count=0;
    try {
      for(;;){if(!current())throw new Error("checkpoint.authority_changed");const step=steps.next();if(step.done)return step.value;if(++count%64===0)await Bun.sleep(0);}
    }finally{steps.return(null);this.close();}
  }
  private *finishSteps():Generator<void,CheckpointRecordDigests|null> {
    if(this.invalid) return null;
    try {
    if (this.stateCount !== 1 || this.lineage === null) return null;
    const get=<K extends MonitoringCheckpointRecord["kind"]>(kind:K,id:string):Extract<MonitoringCheckpointRecord,{kind:K}>|null=>{
      const value=this.index.get(kind,id);
      if(value===null)return null;
      // Only canonical records already validated by addRecord enter this private kind-keyed index.
      return decodeIndexedRecord(kind,value);
    };
    const owner=(id:string,incarnation:string,revision:string,allowRetired:boolean):boolean => {
      const machine=get("machine",id);
      const value=machine===null || !record(machine.value) ? null : machine.value;
      if(value?.machineIncarnation===incarnation && validCounter(value.rulesRevision) && BigInt(revision)<=BigInt(value.rulesRevision)) return true;
      if(!allowRetired) return false;
      const tombstone=get("tombstone",incarnation);
      return tombstone!==null && tombstone.memberId===id && BigInt(revision)<=BigInt(tombstone.rulesRevision);
    };
    for(const row of this.index.rows("roster")) {yield;if(get("machine",row.id)===null) return null;}
    for(const row of this.index.rows("machine")) {
      yield;
      const machine=decodeIndexedRecord("machine",row.value);
      const roster=get("roster",machine.machineId);
      // SAFETY: This private machine row was inserted only after the complete canonical machine field checks.
      const value=machine.value as {machineIncarnation:string;open:string[]};
      if(roster===null || value.machineIncarnation!==rosterIncarnation(roster.memberId,roster.fingerprint,roster.enrolledAt)) return null;
      for(const metric of value.open) if(get("reservation",monitoringSlotHash(value.machineIncarnation,metric))===null) return null;
    }
    for(const row of this.index.rows("history")) {
      yield;
      const history=decodeIndexedRecord("history",row.value);
      const machine=get("machine",row.id);
      // SAFETY: A private machine-kind row has the incarnation field established by its canonical insertion check.
      if(get("roster",row.id)===null || machine===null || (machine.value as {machineIncarnation:string}).machineIncarnation!==history.machineIncarnation) return null;
    }
    for(const row of this.index.rows("receipt")) {
      yield;
      const receipt=decodeIndexedRecord("receipt",row.value);
      // SAFETY: The private receipt row passed closed machine/result incarnation/revision validation on insertion.
      const value=receipt.value as {machineId:string;result:{machineIncarnation:string;rulesRevision:string}};
      if(!owner(value.machineId,value.result.machineIncarnation,value.result.rulesRevision,true)) return null;
    }
    for(const row of this.index.rows("outbox")) {
      yield;
      const outboxRecord=get("outbox",row.id);
      const event=outboxRecord===null ? null : parseMonitoringEvent(outboxRecord.value,this.lineage);
      if(event===null)return null;
      if(!owner(event.machineId,event.machineIncarnation,event.rulesRevision,true)) return null;
      const sequence=get("sequence",event.slot);
      if(sequence===null || BigInt(event.producerSequence)>BigInt(sequence.value)) return null;
    }
    for(const row of this.index.rows("_events")) {
      yield;
      // Only at most two closed parsed original MonitoringEvents enter this private slot row.
      const events:import("../machine-alerts.ts").MonitoringEvent[]=JSON.parse(row.value);
      const sequence=get("sequence",row.id),reservation=get("reservation",row.id);
      if(!monitoringSlotCustodyValid(events,sequence?.value,reservation??undefined))return null;
    }
    for(const row of this.index.rows("reservation")) {
      yield;
      const reservation=decodeIndexedRecord("reservation",row.value);
      const sequence=get("sequence",row.id);
      if(sequence===null || sequence.value!==reservation.sequence) return null;
      const originalOwner=this.index.get("_owner",row.id);
      if(originalOwner===null || JSON.parse(originalOwner)!==1) return null;
      const events=this.index.get("_events",row.id);
      if(events!==null){const slotEvents:import("../machine-alerts.ts").MonitoringEvent[]=JSON.parse(events);if(slotEvents.some(event=>event.kind==="clear"))return null;}
    }
    for(const row of this.index.rows("sequence")) {yield;if(this.index.get("_owner",row.id)===null) return null;}
    for(const row of this.index.rows("tombstone")) {
      yield;
      const tombstone=decodeIndexedRecord("tombstone",row.value);
      let outstanding=false;
      for(const metric of ["cpu","mem","disk"]) if(this.index.get("_events",monitoringSlotHash(tombstone.machineIncarnation,metric))!==null) outstanding=true;
      if(!outstanding && this.index.get("_receipt-owner",tombstone.machineIncarnation)!==null) outstanding=true;
      if(!outstanding) return null;
    }

    const digest = (kind: string): string => this.hashes.get(kind)!.digest("hex");
    return { rosterDigest: digest("roster"),
      rulesDigest: createHash("sha256").update(["machine", "history", "receipt", "tombstone"].map(digest).join("\0")).digest("hex"),
      producerDigest: createHash("sha256").update([this.lineage, String(this.evaluatedThrough), digest("outbox"), digest("sequence"), digest("reservation")].join("\0")).digest("hex"),
      historyFrom: this.hasHistory ? this.historyFrom : 0, historyThrough: this.hasHistory ? this.historyThrough : 0 };
    } finally { this.close(); }
  }
}

function monitoringSlotHash(incarnation: string, metric: string): string {
  const hash = createHash("sha256");
  for (const value of ["collie.monitoring.slot.v1", incarnation, metric]) {
    const bytes = Buffer.from(value, "utf8");
    const length = Buffer.alloc(4); length.writeUInt32BE(bytes.length);
    hash.update(length).update(bytes);
  }
  return hash.digest("hex");
}

function validReceiptResult(value: JsonValue | undefined): boolean {
  if (!record(value) || !exactKeys(value, ["rules", "open", "rulesRevision", "machineIncarnation", "cleared", ...(value.extensions === undefined ? [] : ["extensions"])]) || !record(value.rules) ||
    !Array.isArray(value.open) || !Array.isArray(value.cleared) || typeof value.rulesRevision !== "string" || !validCounter(value.rulesRevision) ||
    typeof value.machineIncarnation !== "string" || !HEX.test(value.machineIncarnation)) return false;
  if (value.extensions !== undefined && (!validExtensions(value.extensions, 4, { left: 64 }) || Buffer.byteLength(JSON.stringify(value.extensions)) > 4096)) return false;
  if (parseMachineAlerts(value.rules) === null || [...value.open, ...value.cleared].some((item) => !["cpu", "mem", "disk"].includes(String(item)))) return false;
  const rules=value.rules;
  return new Set(value.open).size === value.open.length && new Set(value.cleared).size === value.cleared.length &&
    value.open.every(item=>rules[String(item)]!==undefined);
}

function validExtensions(value: JsonValue | undefined, depth: number, budget: { left: number }): boolean {
  if (budget.left-- <= 0 || depth < 0) return false;
  if (Array.isArray(value)) return value.length <= 64 && value.every((item) => validExtensions(item, depth - 1, budget));
  if (record(value)) return Object.entries(value).every(([key, child]) => key.length <= 128 && validExtensions(child, depth - 1, budget));
  return value === null || typeof value === "string" || typeof value === "boolean" || (typeof value === "number" && Number.isFinite(value));
}

export function parseMonitoringCheckpointChunk(bytes: Uint8Array): readonly MonitoringCheckpointRecord[] | null {
  if (bytes.length === 0 || bytes.at(-1) !== 0x0a) return null;
  let source: string;
  try { source = new TextDecoder("utf-8", { fatal: true }).decode(bytes); } catch { return null; }
  const lines = source.slice(0, -1).split("\n");
  if (lines.some((line) => line.length === 0 || Buffer.byteLength(line, "utf8") > 512 * 1024)) return null;
  const output: MonitoringCheckpointRecord[] = [];
  for (const line of lines) {
    const value = parseStrictRecord(line);
    const canonical = canonicalMonitoringCheckpointRecord(value);
    if (canonical === null || canonical !== line) return null;
    // SAFETY: Canonical validation checked this complete strict-JSON record and exact encoded bytes above.
    output.push(value as MonitoringCheckpointRecord);
  }
  return output;
}

function parseStrictRecord(source: string): JsonValue | null {
  return parseMachineStrictJson(source);
}

export function monitoringCheckpointLines(records: readonly MonitoringCheckpointRecord[]): readonly Uint8Array[] | null {
  const digest = digestMonitoringCheckpointRecords(records);
  if (digest === null) return null;
  const lines: Uint8Array[] = [];
  for (const value of records) {
    const encoded = canonicalMonitoringCheckpointRecord(value);
    if (encoded === null) return null;
    const bytes = new TextEncoder().encode(`${encoded}\n`);
    if (bytes.byteLength > 512 * 1024 + 1) return null;
    lines.push(bytes);
  }
  return lines;
}

export function historyRecordFromState(machineId: string, value: JsonValue, machineIncarnation: string): MonitoringCheckpointRecord {
  return { kind: "history", machineId, machineIncarnation, value: { [machineId]: value } };
}

/** One <=512 KiB record may span chunk boundaries; no chunk/line array is retained. */
export class MonitoringCheckpointRecordStream {
  private readonly pending = new Uint8Array(512 * 1024);
  private size = 0;
  failed = false;
  *push(bytes: Uint8Array): Generator<MonitoringCheckpointRecord> {
    if(this.failed) return;
    for(let start=0;start<bytes.length;) {
      const newline=bytes.indexOf(0x0a,start);
      const end=newline<0 ? bytes.length : newline;
      if(end-start>this.pending.length-this.size) { this.failed=true;return; }
      this.pending.set(bytes.subarray(start,end),this.size);this.size+=end-start;
      if(newline<0) return;
      let source:string;
      try {source=new TextDecoder("utf8",{fatal:true}).decode(this.pending.subarray(0,this.size));} catch {this.failed=true;return;}
      const value=parseStrictRecord(source);
      if(source.length===0 || canonicalMonitoringCheckpointRecord(value)!==source) {this.failed=true;return;}
      this.size=0;start=end+1;
      // SAFETY: Strict JSON and the complete closed record validator matched the reconstructed source bytes.
      yield value as MonitoringCheckpointRecord;
    }
  }
  finish(): boolean {return !this.failed && this.size===0;}
}

function decodeIndexedRecord<K extends MonitoringCheckpointRecord["kind"]>(kind:K,value:string):Extract<MonitoringCheckpointRecord,{kind:K}> {
  const checked:Extract<MonitoringCheckpointRecord,{kind:K}>=JSON.parse(value);
  if(checked.kind!==kind)throw new Error("checkpoint.index_record_kind");
  return checked;
}
