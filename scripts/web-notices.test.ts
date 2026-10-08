import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const read = (path: string) => readFileSync(resolve(root, path), "utf8");

describe("web notices", () => {
  const SHADCN_UI = ["button", "badge", "card", "sheet", "switch"].map(
    (c) => `web/src/components/ui/${c}.tsx`,
  );
  const SHADCN_CHAT = [
    "web/src/components/ui/chat/chat-input.tsx",
    "web/src/components/ui/chat/chat-message-list.tsx",
    "web/src/hooks/use-auto-scroll.ts",
  ];

  test("the PWA ships shadcn/ui's and shadcn-chat's MIT notices", () => {
    expect(read("web/public/licenses/shadcn-ui.txt")).toMatch(/Copyright \(c\) \d{4} shadcn/);
    expect(read("web/public/licenses/shadcn-chat.txt")).toContain("Permission is hereby granted");
  });

  test.each(SHADCN_UI)("%s names its shadcn/ui origin", (path) => {
    expect(read(path)).toContain("licenses/shadcn-ui.txt");
  });

  test.each(SHADCN_CHAT)("%s names its shadcn-chat origin", (path) => {
    expect(read(path)).toContain("licenses/shadcn-chat.txt");
  });
});
