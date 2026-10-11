import { HARNESS_CATALOG_NAMES } from "./harness-catalog.ts";
import { HARNESS_LAUNCHES, type HarnessProbe } from "./harness-launch.ts";
import { adapterFor as journalAdapterFor } from "./journal/registry.ts";
import type { JournalAdapter } from "./journal/types.ts";
import type { MuxAdapter } from "./mux/types.ts";
import type { AgentCapabilityRow, AgentCapabilitiesResponse } from "./types.ts";

/** The current PWA registry is deliberately exact-name keyed. Keep these implementation claims
 * aligned with web/src/lib/harness/registry.ts in agent-capabilities.test.ts. */
export const PWA_SEMANTIC_ADAPTERS = [
  "agy", "antigravity", "claude", "codex", "grok", "muse", "omp", "opencode",
] as const;

/** These journal parsers currently extract image attachments from their source logs. */
// OMP is the public pane name for pi's on-disk journal and image format.
export const JOURNAL_IMAGE_ADAPTERS = ["claude", "muse", "omp", "opencode", "pi"] as const;

export interface AgentCapabilitiesInput {
  mux: Pick<MuxAdapter, "mux" | "capabilities" | "snapshot">;
  harnesses: Pick<HarnessProbe, "list"> & Partial<Pick<HarnessProbe, "versions">>;
  journals: Record<string, JournalAdapter>;
}

/** A read-only, per-owning-runtime matrix. It reports source implementation separately from a
 * runtime-version claim: only the bounded, registered `--version` probe runs vendor binaries. */
export async function agentCapabilities(input: AgentCapabilitiesInput): Promise<AgentCapabilitiesResponse> {
  const found = new Map((await input.harnesses.list()).map((item) => [item.id, item.found]));
  const versions = await input.harnesses.versions?.() ?? new Map<string, string>();
  // This is an actual owner-runtime observation, not a mux-wide capability bit. A snapshot that
  // cannot be read leaves those cells unverified; it never turns a generic flag into per-kind proof.
  const panes = await input.mux.snapshot().then((snapshot) => snapshot.panes, () => []);
  const rows: AgentCapabilityRow[] = HARNESS_CATALOG_NAMES.map((kind) => {
    const launch = HARNESS_LAUNCHES.find((item) => item.id === kind);
    const journal = journalAdapterFor(input.journals, kind);
    const pwaSemantic = PWA_SEMANTIC_ADAPTERS.some((name) => name === kind);
    const imageAdapter = JOURNAL_IMAGE_ADAPTERS.some((name) => name === kind);
    const journalImages = journal !== undefined && imageAdapter;
    const observed = panes.filter((pane) => pane.agent === kind);
    // An exited harness is reported as shell with its original exact harness in sessionAgent. Keep
    // the current-pane identity fact and the retained session-reference fact distinct.
    const observedSessionReferences = panes.filter((pane) =>
      (pane.agent === kind || pane.sessionAgent === kind) && pane.agentSession !== undefined,
    ).length;
    return {
      kind,
      label: launch?.label ?? kind,
      executableFound: launch === undefined ? null : found.get(kind) === true,
      version: versions.get(kind) ?? null,
      launch: launch === undefined ? "unverified" : "implemented",
      // A positive cell requires an actual exact identity in this owner's current snapshot. The
      // generic mux declaration alone is deliberately insufficient.
      detection: input.mux.capabilities.supports.agentDetection && observed.length > 0 ? "implemented" : "unverified",
      sessionReference: input.mux.capabilities.supports.agentSessionRef && observedSessionReferences > 0
        ? "implemented"
        : "unverified",
      journal: journal === undefined ? "unverified" : "implemented",
      webSemanticDialog: pwaSemantic ? "implemented" : "unverified",
      webComposer: pwaSemantic ? "implemented" : "unverified",
      // These adapters can extract bounded vendor bytes and hand them to the settled B34 port.
      // There is not yet an owner-mounted ingestion/recorder in this dependency snapshot, so do
      // not advertise end-to-end media as implemented.
      media: journalImages ? "interface-ready" : "unverified",
      observedPanes: observed.length,
      observedSessionReferences,
      evidence: {
        version: versions.has(kind)
          ? "Owner-host executable returned a bounded semantic version from its registered fixed version command."
          : launch === undefined
            ? "No exact executable registration; no version command was attempted."
            : launch.versionCommand === undefined
              ? "No documented version command is registered; no version command was attempted."
            : found.get(kind) === true
              ? "Executable is present, but its bounded documented version command returned no valid semantic version."
              : "Executable was not found; no version command was attempted.",
        journal: journal === undefined ? "No source-backed adapter is registered." : "bridge/journal/registry.ts",
        semantic: pwaSemantic
          ? "web/src/lib/harness/registry.ts; native parity is assessed by the Android client separately."
          : "No exact-name PWA adapter is registered; a raw view is not closure.",
        media: journalImages
          ? "bridge/journal/media-producer.ts; source bytes only. B34 owner admission, ingestion, storage, and Crew blob authorization are required for end-to-end service."
          : "No source-backed image extraction claim is registered.",
      },
    };
  });
  return {
    schema: 1,
    mux: input.mux.mux,
    muxCapabilities: {
      agentDetection: input.mux.capabilities.supports.agentDetection,
      agentSessionRef: input.mux.capabilities.supports.agentSessionRef,
    },
    runtimeVersion: null,
    rows,
    note: "Versions come only from fixed, bounded probes of registered binaries. Detection/session counts are current owner-runtime snapshot observations; zero means not observed now. Source/interface status is not live vendor acceptance; media marked interface-ready requires the B34 owner port.",
  };
}
