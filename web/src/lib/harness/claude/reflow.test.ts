import { describe, expect, it } from "vitest";
import { reflowSoftWraps, maxWidth } from "./reflow";
import type { AnsiSegment } from "@/lib/ansi";
import type { StyledLine } from "@/lib/blocks";

const line = (text: string): StyledLine => ({ segments: [{ text, style: {}, muted: false }] });
const text = (l: StyledLine) => l.segments.map((s: AnsiSegment) => s.text).join("");

describe("reflowSoftWraps", () => {
  it("joins a full-width row with its indented continuation", () => {
    const first = "⏺ " + "x".repeat(38);
    const out = reflowSoftWraps([line(first), line("  continues"), line("")], 40);
    expect(out.map(text)).toEqual([`${first} continues`, ""]);
  });
  it("never joins tables, rules, fences or bullets", () => {
    const rows = ["│" + "a".repeat(38) + "│", "│ b │", "─".repeat(40), "  c", "x".repeat(40), "  • bullet"].map(line);
    expect(reflowSoftWraps(rows, 40).map(text)).toEqual(rows.map(text));
  });
  it("keeps the first row's style on the joined text", () => {
    const a: StyledLine = { segments: [{ text: "y".repeat(40), bold: true, style: {}, muted: false }] };
    const out = reflowSoftWraps([a, line("  tail")], 40);
    expect(out[0]!.segments[0]!.bold).toBe(true);
  });
  it("maxWidth is the longest visible row", () => {
    expect(maxWidth([line("ab"), line("twelve chars")])).toBe(12);
  });
});
