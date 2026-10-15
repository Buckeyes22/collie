import type { MuxReadLimits } from "./capabilities.ts";

/** The bridge's absolute safety ceiling for one pane read. */
export const MAX_PANE_READ_LINES = 10_000;
/** Older bridges have no negotiated declaration. Keep their request budget conservative. */
export const LEGACY_PANE_READ_LINES = 1_000;

export type ReadLimitSource = "declared" | "legacy" | "invalid";

export interface NegotiatedReadLimit {
  readonly maxRequestedLines: number;
  readonly source: ReadLimitSource;
}

/**
 * Interpret an optional peer declaration without ever increasing the bridge's hard ceiling.
 * Missing metadata is an old-peer case; malformed metadata fails closed to the legacy budget.
 */
export function negotiateReadLimit(
  value: MuxReadLimits | null | undefined,
  serverMaximum = MAX_PANE_READ_LINES,
): NegotiatedReadLimit {
  const safeServerMaximum = positiveInteger(serverMaximum) ? Math.min(serverMaximum, MAX_PANE_READ_LINES) : LEGACY_PANE_READ_LINES;
  if (value === undefined || value === null) {
    return { maxRequestedLines: Math.min(LEGACY_PANE_READ_LINES, safeServerMaximum), source: "legacy" };
  }
  if (!isReadLimits(value)) {
    return { maxRequestedLines: Math.min(LEGACY_PANE_READ_LINES, safeServerMaximum), source: "invalid" };
  }
  return { maxRequestedLines: Math.min(value.maxRequestedLines, safeServerMaximum), source: "declared" };
}

/** Clamp a desired read size to the negotiated request ceiling. */
export function admittedReadLines(desiredLines: number, limit: NegotiatedReadLimit): number {
  if (!positiveInteger(desiredLines)) throw new RangeError("requested pane lines must be a positive integer");
  return Math.min(desiredLines, limit.maxRequestedLines);
}

function isReadLimits(value: MuxReadLimits): boolean {
  return Number.isSafeInteger(value.maxRequestedLines) && value.maxRequestedLines > 0 && value.maxRequestedLines <= MAX_PANE_READ_LINES;
}

function positiveInteger(value: number): boolean {
  return Number.isSafeInteger(value) && value > 0;
}
