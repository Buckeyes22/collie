/** H12's frozen inventory, distinct from observed pane agent identifiers and executable support. */
export const HARNESS_CATALOG_NAMES = [
  "pi", "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode",
  "opencode", "copilot", "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli",
  "qwen", "letta", "maki", "muse",
] as const;

export type HarnessCatalogName = (typeof HARNESS_CATALOG_NAMES)[number];
