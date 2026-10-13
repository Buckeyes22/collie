import type { MachineAlertStore } from "../machine-alerts.ts";
import { parseMonitoringSnapshotStamp } from "../machine-parse.ts";
import { monitoringAuthorityKey } from "./monitoring-authority.ts";
import { buildMonitoringCheckpointSpool } from "./monitoring-checkpoint.ts";
import { monitoringRecordsFromSnapshot } from "./monitoring-checkpoint-records.ts";
import type { MonitoringCheckpointDistribution } from "./lead.ts";
import type { StoredWarrant, TrustStore, TrustStoreData } from "./trust-store.ts";
import { WARRANT_TTL_MS } from "./warrant.ts";

/** Observational checkpoint custody is the captured immutable generation plus original authority.
 * New minute/rule/outbox data is queued for the next capture; it does not revoke this observation.
 * Producer enqueue/settlement still uses its separate original rule/slot-current predicates. */
export function monitoringCheckpointSource(deps: {
  readonly stateDir: string;
  readonly trustStore: Pick<TrustStore, "current" | "monitoringRevision">;
  readonly store: () => MachineAlertStore | undefined;
  readonly isLead: () => boolean;
  readonly warrant: (data: TrustStoreData | null) => StoredWarrant | null | undefined;
  readonly now?: () => number;
}): Pick<MonitoringCheckpointDistribution, "sourceRevision" | "current"> {
  const now = deps.now ?? Date.now;
  const admission = () => {
    const held = deps.trustStore.current();
    const store = deps.store();
    const issued = deps.warrant(held)?.warrant;
    if (!deps.isLead() || held === null || held.crew === null || held.deputy === null ||
      store === undefined || !store.available() || issued === undefined ||
      issued.crewId !== held.crew.crewId || issued.leadMemberId !== held.self.memberId ||
      issued.deputyMemberId !== held.deputy || issued.deputyFingerprint === null ||
      now() >= issued.refreshedAt + WARRANT_TTL_MS) return null;
    return { held, store, issued };
  };
  return {
    sourceRevision: () => admission()?.store.currentCheckpointRevision() ?? null,
    current: async () => {
      const original = admission();
      if (original === null) return null;
      const { held, store, issued } = original;
      const originalTrustRevision = deps.trustStore.monitoringRevision(monitoringAuthorityKey);
      const originalOwnerCurrent = (): boolean => deps.isLead() && deps.store() === store &&
        store.available() && now() < issued.refreshedAt + WARRANT_TTL_MS &&
        deps.trustStore.current() !== null &&
        deps.trustStore.monitoringRevision(monitoringAuthorityKey) === originalTrustRevision;
      // The snapshot waits for committed writes and carries its OWN revision. Sampling a live
      // revision before that await, or treating later data commits as authority, starves large jobs.
      const snapshot = await store.checkpointSnapshot();
      const stamp = parseMonitoringSnapshotStamp(snapshot);
      if (snapshot === null || stamp === null || !originalOwnerCurrent()) return null;
      const roster = [
        { id: held.self.memberId, fingerprint: held.self.fingerprint, enrolledAt: held.self.createdAt },
        ...held.peers.filter(member => member.status === "enrolled").map(member =>
          ({ id: member.memberId, fingerprint: member.fingerprint, enrolledAt: member.enrolledAt })),
      ];
      const records = monitoringRecordsFromSnapshot(snapshot, roster, deps.stateDir);
      if (records === null) return null;
      const checkpoint = await buildMonitoringCheckpointSpool(deps.stateDir, {
        crewId: held.crew!.crewId, leadMemberId: held.self.memberId, leadFingerprint: held.self.fingerprint,
        deputyMemberId: held.deputy!, deputyFingerprint: issued.deputyFingerprint!,
        warrantGeneration: issued.generation, secretGeneration: held.crew!.secretGeneration,
      }, records, stamp.revision, stamp.createdAt, originalOwnerCurrent);
      if (checkpoint !== null && !originalOwnerCurrent()) { await checkpoint.cleanup?.(); return null; }
      return checkpoint === null ? null : { checkpoint, originalOwnerCurrent };
    },
  };
}
