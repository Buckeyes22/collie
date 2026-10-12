/** Local monitoring ownership key. A refresh of the same warrant never extends an already
 * captured lease: callers retain its original expiry separately. No tokens or content are logged. */
import {createHash} from "node:crypto";
import type {TrustStoreData} from "./trust-store.ts";
export function monitoringAuthorityKey(data:TrustStoreData|null):string {
  const hash=createHash("sha256");
  if(data===null)return hash.update("solo-unenrolled").digest("hex");
  hash.update(JSON.stringify({crewId:data.crew?.crewId??null,secret:data.crew?.secret??null,secretGeneration:data.crew?.secretGeneration??null,selfId:data.self.memberId,selfFingerprint:data.self.fingerprint,selfEnrolledAt:data.self.createdAt,lead:data.lead===null?null:{id:data.lead.memberId,fingerprint:data.lead.fingerprint,enrolledAt:data.lead.enrolledAt,status:data.lead.status},deputy:data.deputy??null,warrantGeneration:data.warrant?.warrant.generation??null,deputyFingerprint:data.warrant?.warrant.deputyFingerprint??null}));
  // Preserve the immutable original trust-store order; reordering conservatively retires it.
  // Streaming these original facts avoids a second full roster/sorted-key array.
  for(const peer of data.peers)hash.update(JSON.stringify({id:peer.memberId,fingerprint:peer.fingerprint,enrolledAt:peer.enrolledAt,status:peer.status})).update("\0");
  return hash.digest("hex");
}
