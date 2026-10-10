import { beforeEach, expect, test } from "vitest";
import { __resetLocale } from "@/lib/i18n";
import { PUSH_TITLE_CODES } from "@/lib/push-title-codes";
import { pushTitleTemplates } from "@/lib/push-titles";

beforeEach(() => {
  localStorage.clear();
  __resetLocale();
});

test("every push code has an English template with its wire placeholders intact", () => {
  const templates = pushTitleTemplates();
  expect(Object.keys(templates)).toEqual([...PUSH_TITLE_CODES]);
  expect(templates["agent.blocked"]).toBe("{agent} needs you");
  expect(templates["herd.mixed"]).toBe("{count} agents need attention");
  expect(templates["cache.cold_soon"]).toContain("{minutes}");
});
