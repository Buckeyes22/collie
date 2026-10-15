import {HOST} from "../host.ts";
/** Private external verification index. The 128 MiB aggregate scratch allowance replaces the
 * original 1 MiB metadata allowance; it never reduces the 64 MiB generation capacity.
 * SQLite cache settings are tuning, not an OS memory guarantee. No WAL or external helper. */
import { deflateRawSync, inflateRawSync } from "node:zlib";
import { Database } from "bun:sqlite";
import { constants, closeSync, lstatSync, mkdirSync, mkdtempSync, openSync, opendirSync, rmSync, statfsSync } from "node:fs";
import { isOwnerOnly } from "../owner-only.ts";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const MONITORING_INDEX_MAX_BYTES = 128 * 1024 * 1024;
const INDEX_PAGE_BYTES=32*1024;
const INDEX_KINDS=["state","roster","machine","history","receipt","outbox","sequence","reservation","tombstone","_owner","_events","_receipt-owner","fixture"] as const;
const BINARY_IDS=new Set(["outbox","sequence","reservation","tombstone","_owner","_events","_receipt-owner"]);
function kindCode(kind:string):number {const code=INDEX_KINDS.findIndex(known=>known===kind);if(code<0)throw new Error("checkpoint.index_kind");return code;}
function indexKey(kind:string,id:string):string|Uint8Array {if(BINARY_IDS.has(kind)) {if(!/^[0-9a-f]{64}$/.test(id)) throw new Error("checkpoint.index_key");return Buffer.from(id,"hex");}return id;}
const activeIndices=new Set<MonitoringFactIndex>();
export class MonitoringFactIndex {
  readonly database: Database;
  readonly directory: string;
  private closed = false;
  constructor(parent = tmpdir()) {
    if(activeIndices.size>=2) throw new Error("checkpoint.index_pressure");
    let remaining=MONITORING_INDEX_MAX_BYTES;
    for(const index of activeIndices)remaining-=index.bytes();
    const initialPages=Math.floor(remaining/INDEX_PAGE_BYTES);
    // SQLite's schema and first table need two pages; reserve them before creating any file.
    if(initialPages<2)throw new Error("checkpoint.index_pressure");
    const root = lstatSync(parent);
    if (!root.isDirectory() || root.isSymbolicLink()) throw new Error("checkpoint.index_parent_invalid");
    const namespace=join(parent,parent===tmpdir()?`collie-monitoring-verification-private-${process.pid}`:"collie-monitoring-verification-private");
    try {mkdirSync(namespace,{mode:0o700});} catch(error) {if(!(typeof error==="object" && error!==null && "code" in error && error.code==="EEXIST")) throw error;}
    assertPrivate(namespace);
    removeDeadIndexScratch(namespace);
    const space=statfsSync(namespace);
    if(space.bavail*space.bsize<MONITORING_INDEX_MAX_BYTES+64*1024*1024+1024*1024) throw new Error("checkpoint.index_disk_pressure");
    this.directory = mkdtempSync(join(namespace, `collie-monitoring-index-${process.pid}-`));
    const privateRoot = lstatSync(this.directory);
    if (HOST.platform !== "win32" && (privateRoot.mode & 0o077 || privateRoot.uid !== process.getuid?.())) {
      rmSync(this.directory, { recursive: true }); throw new Error("checkpoint.index_owner_invalid");
    }
    if(HOST.platform==="win32") {const proof = isOwnerOnly(this.directory, HOST); if (proof.state !== "private") {rmSync(this.directory,{recursive:true});throw new Error("checkpoint.index_acl");}}
    const path = join(this.directory, "facts.sqlite");
    const descriptor = openSync(path, constants.O_CREAT | constants.O_EXCL | constants.O_RDWR | (constants.O_NOFOLLOW ?? 0), 0o600);
    closeSync(descriptor);
    if(HOST.platform==="win32") {const proof = isOwnerOnly(path, HOST); if (proof.state !== "private") {rmSync(this.directory,{recursive:true});throw new Error("checkpoint.index_acl");}}
    try {
      this.database = new Database(path, { strict: true });
      this.database.exec(`PRAGMA page_size=${INDEX_PAGE_BYTES}; PRAGMA max_page_count=${initialPages}; PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF; PRAGMA temp_store=FILE; PRAGMA mmap_size=0; PRAGMA cache_size=-64;
        PRAGMA cache_spill=ON; CREATE TABLE facts(kind INTEGER NOT NULL,id NOT NULL,value BLOB NOT NULL,
          PRIMARY KEY(kind,id)) WITHOUT ROWID;`);
      activeIndices.add(this);
    } catch(error) { rmSync(this.directory, { recursive: true, force: true }); throw error; }
  }
  /** Owned scratch only. Callers complete this synchronous operation before any cooperative await. */
  batch<T>(operation:()=>T):T {return this.database.transaction(operation)();}
  put(kind: string, id: string, value: string): boolean {
    const old = this.get(kind,id);
    if (old !== null) return old === value;
    this.reserveGrowth();
    this.database.query("INSERT INTO facts(kind,id,value) VALUES(?,?,?)").run(kindCode(kind),indexKey(kind,id),this.encode(value));
    return true;
  }
  replace(kind:string,id:string,value:string):void {
    this.reserveGrowth();
    this.database.query("INSERT INTO facts(kind,id,value) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET value=excluded.value").run(kindCode(kind),indexKey(kind,id),this.encode(value));
  }
  bytes():number {const row=this.database.query<IndexPageCount,[]>("PRAGMA page_count").get();if(row===null)throw new Error("checkpoint.index_page_count");return row.page_count*INDEX_PAGE_BYTES;}
  private reserveGrowth():void {
    let others=0;for(const index of activeIndices) if(index!==this) others+=index.bytes();
    const pages=Math.floor((MONITORING_INDEX_MAX_BYTES-others)/INDEX_PAGE_BYTES);
    if(pages<1) throw new Error("checkpoint.index_pressure");
    const result=this.database.query<IndexPageMaximum,[]>(`PRAGMA max_page_count=${pages}`).get();
    if(result===null || result.max_page_count>pages) throw new Error("checkpoint.index_pressure");
  }
  get(kind: string,id: string): string | null {
    const row=this.database.query<IndexValue,[number,string|Uint8Array]>("SELECT value FROM facts WHERE kind=? AND id=?").get(kindCode(kind),indexKey(kind,id));
    return row===null ? null : inflateRawSync(row.value,{maxOutputLength:512*1024}).toString("utf8");
  }
  *rows(kind: string): Iterable<{id:string;value:string}> {
    for (const row of this.database.query<IndexRow,[number]>("SELECT id,value FROM facts WHERE kind=? ORDER BY id").iterate(kindCode(kind))) {yield {id:typeof row.id==="string" ? row.id : Buffer.from(row.id).toString("hex"),value:inflateRawSync(row.value,{maxOutputLength:512*1024}).toString("utf8")};}
  }
  private encode(value:string):Uint8Array {
    if(Buffer.byteLength(value,"utf8")>512*1024) throw new Error("checkpoint.record_quota");
    return deflateRawSync(value,{level:1,maxOutputLength:512*1024+1024});
  }
  close(): void {
    if(this.closed) return;
    this.closed=true;activeIndices.delete(this);
    try { this.database.close(true); } finally { rmSync(this.directory,{recursive:true,force:true}); }
  }
}

function assertPrivate(path:string):void {
  const stat=lstatSync(path);
  if(!stat.isDirectory() || stat.isSymbolicLink() || HOST.platform!=="win32" && (stat.mode&0o077 || stat.uid!==process.getuid?.())) throw new Error("checkpoint.index_owner_invalid");
  if(HOST.platform==="win32") {const proof = isOwnerOnly(path, HOST); if (proof.state !== "private") throw new Error("checkpoint.index_acl");}
}
/** Crash files are disposable scratch, never accepted data. A live/unknown PID is preserved. */
function removeDeadIndexScratch(namespace:string):void {
  const directory=opendirSync(namespace);
  try {
    let entry;let count=0;
    while((entry=directory.readSync())!==null) {
      if(++count>128) throw new Error("checkpoint.index_orphan_pressure");
      const match=/^collie-monitoring-index-([1-9][0-9]{0,9})-[A-Za-z0-9]+$/.exec(entry.name);
      if(match===null) throw new Error("checkpoint.index_unknown_custody");
      const path=join(namespace,entry.name);assertPrivate(path);
      const pid=Number(match[1]);
      if([...activeIndices].some(index=>index.directory===path)) continue;
      try {process.kill(pid,0);throw new Error("checkpoint.index_live_custody");}
      catch(error) {if(!(typeof error==="object" && error!==null && "code" in error && error.code==="ESRCH")) throw error;}
      const files=opendirSync(path);
      try {
        let file;
        while((file=files.readSync())!==null) {
          if(file.name!=="facts.sqlite") throw new Error("checkpoint.index_unknown_custody");
          const stat=lstatSync(join(path,file.name));
          if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink!==1 || stat.size>MONITORING_INDEX_MAX_BYTES || HOST.platform!=="win32" && (stat.mode&0o077 || stat.uid!==process.getuid?.())) throw new Error("checkpoint.index_owner_invalid");
        }
      } finally {files.closeSync();}
      rmSync(path,{recursive:true});
    }
  } finally {directory.closeSync();}
}

interface IndexPageCount {page_count:number}
interface IndexPageMaximum {max_page_count:number}
interface IndexValue {value:Uint8Array}
interface IndexRow extends IndexValue {id:string|Uint8Array}
