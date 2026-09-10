import { describe, expect, it } from "vitest";

import { en, type MessageKey } from "./messages/en";

// ── THE BAND'S 40-CHARACTER BUDGET ──────────────────────────────────────────────────────────────
//
// The update band is ONE truncating row at the top of a phone, which is about forty characters wide.
// A string that overflows it is a string nobody can read, so the English catalog is tested here
// rather than relying on a recommended budget in a comment.
//
// The budget is measured with the SLOTS FILLED, because a slot is not what reaches the screen: the
// template `Collie {version} available. Tap to update.` is 42 characters and the line it prints is
// 37. Each slot gets one representative value, and they are deliberately generous — a five-part
// version and an eight-letter machine name.
//
// `{reason}` is the exception, and it is filled EMPTY: a peer's rollback reason is that machine's own
// prose of unbounded length, so it can never be budgeted as a fixed string. It is cut on a word
// boundary to `REASON_BUDGET` before it reaches a message (`lib/update-ribbon.ts`), and the Updates
// page carries it whole.
//
// `pwa.updateAvailable` is NOT in this block and is NOT held to the budget. The band renders it in
// the one state where no Collie update is involved, and M16/02's rule there is that the existing
// self-updater's behaviour AND ITS WORDS are unchanged.

/** Every key the band can print, plus the aria-label on its dismiss. */
const BAND_PREFIX = "updateRibbon.";

/** One representative value per slot. See the header for why `reason` is empty. */
const SAMPLE = {
  version: "1.5.0",
  count: "1",
  name: "minibuch",
  names: "minibuch",
  reason: "",
} as const;

const BUDGET = 40;

function fill(template: string): string {
  let out = template;
  for (const [slot, value] of Object.entries(SAMPLE)) out = out.split(`{${slot}}`).join(value);
  return out;
}

function bandKeys(): MessageKey[] {
  // SAFETY: `MessageKey` is `keyof typeof en` by construction, so every own key of `en` is one.
  const keys = Object.keys(en) as MessageKey[];
  return keys.filter((key) => key.startsWith(BAND_PREFIX));
}

describe("i18n — the update band", () => {
  it("holds every band string to the 40 character budget", () => {
    const keys = bandKeys();
    expect(keys.length).toBeGreaterThan(0); // the assertion must never pass by finding nothing

    const over: string[] = [];
    for (const key of keys) {
      const line = fill(en[key]);
      if (line.length > BUDGET) over.push(`${key}: ${line.length} — "${line}"`);
    }
    expect(over, `over the ${BUDGET}-character band budget`).toEqual([]);
  });

  it("carries every band key in the English catalog", () => {
    const keys = bandKeys();
    for (const key of keys) {
      expect(en[key], `English is missing ${key}`).toMatch(/\S/);
    }
  });

  it("leaves no slot unfilled — every {slot} a band string uses has a sample above", () => {
    const unknown = new Set<string>();
    for (const key of bandKeys()) {
      for (const slot of en[key].matchAll(/\{(\w+)\}/g)) {
        if (!Object.hasOwn(SAMPLE, slot[1])) unknown.add(slot[1]);
      }
    }
    expect([...unknown], "add a sample value, or the budget is measured against a placeholder").toEqual([]);
  });
});
