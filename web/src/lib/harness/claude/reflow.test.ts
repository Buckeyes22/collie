import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { reflowSoftWraps, maxWidth } from "./reflow";
import { lineText } from "./markers";
import { parseAnsi } from "@/lib/ansi";
import type { AnsiSegment } from "@/lib/ansi";
import { splitLines, type StyledLine } from "@/lib/blocks";

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
  it("joins word-wrapped prose that ends short of the width", () => {
    // Claude wraps at word boundaries, so a wrapped row ends up to one word short of the width;
    // requiring a full row left live panes almost unjoined (S25 Ultra, 2026-09-10).
    const a = "⏺ The quick brown fox jumps over the";
    const out = reflowSoftWraps([line(a), line("  lazy dog and keeps running")], 40);
    expect(out.map(text)).toEqual([`${a} lazy dog and keeps running`]);
  });
  it("keeps a hard break where the next word would have fit", () => {
    const rows = [line("⏺ Short line."), line("  Next thought")];
    expect(reflowSoftWraps(rows, 40).map(text)).toEqual(["⏺ Short line.", "  Next thought"]);
  });
  it("judges each seam by its own row, not the joined paragraph", () => {
    const a = "x".repeat(40);
    const out = reflowSoftWraps([line(a), line("  cont"), line("  separate short line")], 40);
    expect(out.map(text)).toEqual([`${a} cont`, "  separate short line"]);
  });
  it("maxWidth ignores full-width rules so prose wrapped short of them still joins", () => {
    const rule = line("─".repeat(60));
    const a = line("● " + "p".repeat(54));
    expect(maxWidth([rule, a, line("  tail")])).toBe(56);
    expect(reflowSoftWraps([rule, a, line("  tail")]).map(text)).toEqual(["─".repeat(60), "● " + "p".repeat(54) + " tail"]);
  });
  it("maxWidth is the longest visible row", () => {
    expect(maxWidth([line("ab"), line("twelve chars")])).toBe(12);
  });

  // Anchored on this file's own directory (NOT `new URL(..., import.meta.url)`, which Vite
  // statically rewrites into a root-relative asset path) — the same convention as chrome.test.ts.
  const CAPTURE = join(
    import.meta.dirname,
    "..",
    "..",
    "..",
    "fixtures",
    "panes",
    "claude--soft-wrapped-paragraph.txt",
  );

  it("reflows the S25 capture to fewer rows without touching its tables", () => {
    const raw = readFileSync(CAPTURE, "utf8");
    const lines = splitLines(parseAnsi(raw));
    const out = reflowSoftWraps(lines);
    expect(out.length).toBeLessThan(lines.length);
    // The capture's table sits inside Claude's indented answer block, so the rows are counted
    // after their own indent is dropped — the count must survive the reflow untouched either way.
    const tableRows = (ls: StyledLine[]) => ls.filter((l) => lineText(l).trimStart().startsWith("│")).length;
    expect(tableRows(out)).toBe(tableRows(lines));
  });
});
