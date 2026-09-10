// Soft-wrap reflow — joins the wraps the shared grid imposed on a paragraph so the phone can wrap
// it once at its own width (ADR 0008: `pane.read` returns a rendered grid, not logical lines; a
// pane rendered at desktop width soft-wraps mid-sentence long before a phone column count would).
// The grid width comes from the read itself (the longest visible row); a row is a wrap candidate
// only when it fills that width exactly. Anything Claude prints as STRUCTURE — a table row, a
// box-drawing rule, a code fence, a bullet, a numbered item — is never joined in either direction,
// so tables keep their rows for table-run.ts to pan and lists keep their line breaks.

import type { StyledLine } from "../../blocks";
import { lineText } from "./chrome";

// ⏺ is deliberately NOT in the bullet class: it is the marker Claude paints on its own answer
// paragraphs, and the indented lines under it are exactly the soft wraps this module exists to
// join (pinned by "joins a full-width row with its indented continuation"). Real list markers —
// •, -, *, the checkbox glyphs, ⎿, and numbered items — stay structural in both directions.
const structural = /^\s*(?:[│┃|┌└├┬┴┼─━═]|```|[•\-*☐☒⎿]\s|\d+\.\s)/;

/** The grid width of a read: its longest visible row, trailing padding ignored. */
export function maxWidth(lines: StyledLine[]): number {
  return lines.reduce((w, l) => Math.max(w, lineText(l).trimEnd().length), 0);
}

function canJoin(current: string, next: string, width: number): boolean {
  const trimmed = current.trimEnd();
  if (trimmed.length < width || next.trim() === "") return false;
  if (structural.test(trimmed) || structural.test(next)) return false;
  return next.length - next.trimStart().length >= 2;
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
 * Join every full-width row with its indented continuation(s), one space at each seam (Claude
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
    let currentText = lineText(first);
    let j = i + 1;
    while (j < lines.length && canJoin(currentText, lineText(lines[j]!), gridWidth)) {
      const next = lines[j]!;
      const last = segments.at(-1);
      segments.push(
        { ...(last ?? { text: "", style: {}, muted: false }), text: " " },
        ...trimLeading(next),
      );
      currentText = currentText.trimEnd() + " " + lineText(next).trim();
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
