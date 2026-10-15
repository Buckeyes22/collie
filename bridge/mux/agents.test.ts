import { describe, expect, test } from "bun:test";

import { AGENT_ALIASES, KNOWN_HARNESS_NAMES } from "../journal/registry.ts";
import { registeredAgents } from "../../web/src/lib/harness/registry.ts";
import { HARNESS_CATALOG_NAMES } from "../harness-catalog.ts";
import { isMuxAgentName, MUX_AGENT_NAMES, MUX_SCREENLESS_AGENT_NAMES } from "./agents.ts";
import { agentNameProblems } from "./conformance.ts";
import type { MuxPane } from "./types.ts";

// H12's catalog is intentionally broader than pane-agent identities. Launch IDs are accepted for
// panes created by explicit launch, but do not imply detection or grant a reader.

describe("the contract's exact agent names", () => {
  test("every implemented screen and journal reader names a canonical contract kind", () => {
    const readers = new Set([...registeredAgents(), ...KNOWN_HARNESS_NAMES, ...Object.keys(AGENT_ALIASES)]);
    for (const name of readers) expect(MUX_AGENT_NAMES).toContain(name);
    expect([...MUX_AGENT_NAMES]).toEqual([
      "agy", "amp", "antigravity", "claude", "cline", "codex", "copilot", "cursor", "devin",
      "droid", "gemini", "grok", "hermes", "kiro", "kimi", "kilo", "letta", "maki",
      "mastracode", "muse", "omp", "openclaw", "opencode", "pi", "qodercli", "qwen",
    ]);
    expect(HARNESS_CATALOG_NAMES).toHaveLength(24);
    expect(MUX_AGENT_NAMES).toContain("antigravity");
    expect(HARNESS_CATALOG_NAMES.every((name) => MUX_AGENT_NAMES.includes(name))).toBe(true);
    expect(MUX_AGENT_NAMES.filter((name) => name !== "antigravity" && name !== "openclaw")).toHaveLength(HARNESS_CATALOG_NAMES.length);
  });

  test("shell and every harness name pass; a multiplexer's own id does not", () => {
    expect(isMuxAgentName("shell")).toBe(true);
    for (const name of MUX_AGENT_NAMES) expect(isMuxAgentName(name)).toBe(true);
    for (const name of ["claude-code", "cursor-agent", "gemini-cli", "Claude", ""]) expect(isMuxAgentName(name)).toBe(false);
  });

  test("a screenless multiplexer's own name is accepted, and is not a reader's name", () => {
    for (const name of MUX_SCREENLESS_AGENT_NAMES) {
      expect(isMuxAgentName(name)).toBe(true);
      expect(MUX_AGENT_NAMES).not.toContain(name);
    }
  });
});

describe("the conformance check applies the rule", () => {
  const pane = (agent: string): MuxPane => ({
    paneId: `p-${agent || "empty"}`,
    spaceId: "s",
    spaceLabel: "s",
    spaceNumber: 1,
    tabId: "t",
    cwd: "",
    focused: false,
    alive: true,
    agent,
    status: agent === "shell" ? "unknown" : "idle",
  });

  test("a harness id from the multiplexer's own vocabulary is a problem", () => {
    expect(agentNameProblems([pane("claude-code")])).toEqual([
      `pane "p-claude-code" reports agent "claude-code", which is not a Collie harness name, so no screen reader or journal reader finds it (agents.ts)`,
    ]);
  });

  test("Collie's own names and a shell are not", () => {
    expect(agentNameProblems([pane("claude"), pane("pi"), pane("shell")])).toEqual([]);
  });

  test("the older two rules still hold: not empty, lower-cased", () => {
    expect(agentNameProblems([pane(""), pane("Claude")])).toHaveLength(2);
  });
});
