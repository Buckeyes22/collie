import { describe, expect, test } from "bun:test";
import { declareCapabilities } from "./capabilities.ts";
import { admittedReadLines, negotiateReadLimit } from "./read-limits.ts";
import type { MuxReadLimits } from "./capabilities.ts";

describe("pane read limit negotiation", () => {
  test("records adapter ceilings separately from retained pane history", () => {
    const herdr = declareCapabilities({
      supports: ["paneGrid", "gridScrollback"],
      topologyLatency: { kind: "push" },
      readLimits: { maxRequestedLines: 1_000 },
    });
    const tmux = declareCapabilities({
      supports: ["paneGrid", "gridScrollback"],
      topologyLatency: { kind: "push" },
      readLimits: { maxRequestedLines: 10_000 },
    });

    expect(herdr.readLimits).toEqual({ maxRequestedLines: 1_000 });
    expect(tmux.readLimits).toEqual({ maxRequestedLines: 10_000 });
    expect(negotiateReadLimit(tmux.readLimits).maxRequestedLines).toBe(10_000);
  });

  test("keeps legacy and malformed peers at the conservative budget", () => {
    expect(negotiateReadLimit(undefined)).toEqual({ maxRequestedLines: 1_000, source: "legacy" });
    // SAFETY: These intentionally malformed runtime payloads exercise the decoder's closed fallback.
    expect(negotiateReadLimit({ maxRequestedLines: 0 } as MuxReadLimits)).toEqual({ maxRequestedLines: 1_000, source: "invalid" });
    // SAFETY: This over-ceiling payload is intentionally impossible through the validated type contract.
    expect(negotiateReadLimit({ maxRequestedLines: 10_001 } as MuxReadLimits)).toEqual({ maxRequestedLines: 1_000, source: "invalid" });
    expect(negotiateReadLimit({ maxRequestedLines: 9_000 }, 2_000)).toEqual({
      maxRequestedLines: 2_000,
      source: "declared",
    });
  });

  test("clamps desired depth and rejects invalid requests", () => {
    const limit = negotiateReadLimit({ maxRequestedLines: 1_000 });
    expect(admittedReadLines(1_001, limit)).toBe(1_000);
    expect(admittedReadLines(512, limit)).toBe(512);
    expect(() => admittedReadLines(0, limit)).toThrow(RangeError);
    expect(() => declareCapabilities({
      supports: [],
      topologyLatency: { kind: "push" },
      readLimits: { maxRequestedLines: 10_001 },
    })).toThrow(RangeError);
  });
});
