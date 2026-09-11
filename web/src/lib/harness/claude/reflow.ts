// Soft-wrap reflow — joins the wraps the shared grid imposed on a paragraph so the phone can wrap
// it once at its own width (ADR 0008: `pane.read` returns a rendered grid, not logical lines; a
// pane rendered at desktop width soft-wraps mid-sentence long before a phone column count would).
// The wrap width comes from the read itself (the longest prose row); a seam is a wrap when the next
// row's first word would not have fit on the row above. Anything Claude prints as STRUCTURE — a table row, a
// box-drawing rule, a code fence, a bullet, a numbered item — is never joined in either direction,
// so tables keep their rows for table-run.ts to pan and lists keep their line breaks.

import type { StyledLine } from "../../blocks";
import { lineText } from "./chrome";

// ⏺ is deliberately NOT in the bullet class: it is the marker Claude paints on its own answer
// paragraphs, and the indented lines under it are exactly the soft wraps this module exists to
// join (pinned by "joins a full-width row with its indented continuation"). Real list markers —
// •, -, *, the checkbox glyphs, ⎿, and numbered items — stay structural in both directions.
const structural = /^\s*(?:[│┃|┌└├┬┴┼─━═]|```|[•\-*☐☒⎿]\s|\d+\.\s)/;

/**
 * The width prose wraps at. Claude draws rules and box borders across the whole terminal but
 * wraps prose a few columns short of that, so a width taken from every row never matched a
 * prose row and nothing joined (S25 Ultra, 2026-09-10). Structural rows are excluded.
 */
export function maxWidth(lines: StyledLine[]): number {
  return lines.reduce((w, l) => {
    const text = lineText(l);
    return structural.test(text) ? w : Math.max(w, text.trimEnd().length);
  }, 0);
}

/**
 * Claude wraps at word boundaries, so a soft-wrapped row ends up to one word short of the width.
 * The break was a wrap exactly when the next row's first word would not have fit on this row;
 * where it would have fit, the author broke the line (S25 Ultra, 2026-09-10).
 */
function canJoin(row: string, next: string, width: number): boolean {
  const trimmed = row.trimEnd();
  if (trimmed === "" || next.trim() === "") return false;
  if (structural.test(trimmed) || structural.test(next)) return false;
  const body = next.trimStart();
  if (next.length - body.length < 2) return false;
  return trimmed.length + 1 + body.split(" ")[0]!.length > width;
}

/** The continuation's leading indent dropped, segment styles kept. */
function trimLeading(line: StyledLine): StyledLine["segments"] {
  const segments = line.segments.map((s) => ({ ...s }));
  for (const s of segments) {
    const t = s.text.trimStart();
    if (t.length > 0) {
      s.text = t;
      break;
    }
    s.text = "";
  }
  return segments.filter((s) => s.text.length > 0);
}

/**
 * Join every soft-wrapped row with its indented continuation(s), one space at each seam (Claude
 * soft-wraps at word boundaries, so the break the grid inserted stood where a space was). The
 * joiner segment carries the FIRST row's style; a joined line keeps `noWrap` unset — the rows that
 * carry `noWrap` are all structural, so they are never joined in the first place.
 */
export function reflowSoftWraps(lines: StyledLine[], gridWidth = maxWidth(lines)): StyledLine[] {
  if (gridWidth <= 0 || lines.length < 2) return lines;
  const out: StyledLine[] = [];
  let i = 0;
  while (i < lines.length) {
    const first = lines[i]!;
    const segments = [...first.segments];
    let j = i + 1;
    // Each seam is judged by the row above it, never by the paragraph joined so far: a joined
    // paragraph is always wider than the grid and would swallow every indented row after it.
    while (j < lines.length && canJoin(lineText(lines[j - 1]!), lineText(lines[j]!), gridWidth)) {
      const next = lines[j]!;
      const last = segments.at(-1);
      segments.push(
        { ...(last ?? { text: "", style: {}, muted: false }), text: " " },
        ...trimLeading(next),
      );
      j++;
    }
    // An unjoined line is handed on as-is (same reference), so callers can treat an unchanged
    // result like stripChrome's. A joined line keeps the FIRST row's other fields — `noWrap` can
    // never reach here, since every noWrap row is structural and refused in both directions.
    out.push(segments.length === first.segments.length ? first : { ...first, segments });
    i = j;
  }
  return out;
}
