// THE AGENT NAMES THE CONTRACT ACCEPTS — a contract rule, like the key spelling in keys.ts.
//
// `MuxPane.agent` picks two things above the seam: the phone's screen reader for the pane
// (web/src/lib/harness/registry.ts) and the bridge's journal reader for its history
// (bridge/journal/registry.ts). Both look the name up EXACTLY. So a multiplexer's own id for a
// harness (tuios says `claude-code`, where Collie says `claude`) reaches neither reader, and the
// pane is rendered raw with its prompts unread. Nothing used to say so: the conformance suite only
// asked for a lower-cased, non-empty name.
//
// The rule: an agent name is `shell` or one of the exact names below. An adapter translates its own
// ids into these. The list includes actual reader IDs, journal aliases, and launch-only IDs; a
// launch-only ID without an exact reader stays raw and is not positive detection/session evidence.

/** Every exact agent identity the mux contract accepts, sorted. */
export const MUX_AGENT_NAMES: readonly string[] = [
  "agy",
  "amp",
  "antigravity",
  "claude",
  "cline",
  "codex",
  "copilot",
  "cursor",
  "devin",
  "droid",
  "gemini",
  "grok",
  "hermes",
  "kiro",
  "kimi",
  "kilo",
  "letta",
  "maki",
  "mastracode",
  "muse",
  "omp",
  "opencode",
  "pi",
  "qodercli",
  "qwen",
];

/** The agent name of a pane with no agent. */
export const MUX_SHELL_AGENT = "shell";

/** Whether `agent` is a name the contract accepts on a pane. */
export function isMuxAgentName(agent: string): boolean {
  return agent === MUX_SHELL_AGENT || MUX_AGENT_NAMES.includes(agent);
}
