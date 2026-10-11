import { homedir, tmpdir } from "node:os";
import { basename } from "node:path";

import { HOST, type Host } from "./host.ts";
import { MUX_AGENT_NAMES } from "./mux/agents.ts";
import { envGet, findTool } from "./tools.ts";
import type { HarnessInfo } from "./types.ts";

// ── The agents the New sheet can start, by id, per machine (ADR 0091) ────────────────────────────
//
// The phone holds no list of harness names. It asks the machine a start would go to
// (`GET /api/launchers`, forwarded on `?host=` like the operator's rows), and that machine answers
// with the harnesses Collie knows and whether each binary is there. The phone then names one by its
// id, and the bridge, never the phone, turns the id into the line that is typed. So a phone cannot
// start a binary nobody listed, exactly as it cannot run a command line `launchers.toml` does not hold.
//
// ── WHICH NAMES ─────────────────────────────────────────────────────────────────────────────────
// The ids are exact names the mux contract accepts (`bridge/mux/agents.ts`). For launch-only names,
// accepting the pane identity enables the launch flow but does not imply a screen or journal reader;
// unknown reader identities remain raw. `harness-launch.test.ts` pins this relationship.
//
// ── FOUND, WITH THE LOGIN SHELL'S PATH ──────────────────────────────────────────────────────────
// A bridge under systemd or Herdr has a short PATH. The operator's shell, the one the new pane runs,
// sources a profile, and that is where `~/.opencode/bin` or an nvm folder is added (on bluefin both
// `opencode` and `pi` live there). So the search runs over the login shell's PATH, asked once per
// process with argv only and a short cap, and falls back to the bridge's own PATH when the shell
// cannot be asked. `findTool` then adds its fixed folders (`bridge/tools.ts`). A found result is kept
// for {@link FOUND_TTL_MS}, so a phone that opens the sheet twice in a row costs one look.

/** One agent Collie can start. */
export interface HarnessLaunch {
  /** The id the phone sends, and the agent name the pane will report. */
  id: string;
  /** The harness's own name for itself. Its own word, never translated. */
  label: string;
  /** The program looked for, and the line typed into the new shell. */
  binary: string;
  /** A read-only version spelling documented by this exact CLI, or no probe when undocumented. */
  versionCommand?: VersionCommand;
}

/** Closed set of version commands. Callers cannot turn the owner probe into an argv passthrough. */
export type VersionCommand = "double-dash" | "short" | "subcommand";

function versionArgv(command: VersionCommand | undefined): readonly string[] | null {
  switch (command) {
    case "double-dash": return ["--version"];
    case "short": return ["-v"];
    case "subcommand": return ["version"];
    case undefined: return null;
  }
}

/** Every agent the New sheet may start, in the order the sheet lists them. */
export const HARNESS_LAUNCHES: readonly HarnessLaunch[] = [
  { id: "claude", label: "Claude Code", binary: "claude", versionCommand: "double-dash" },
  { id: "codex", label: "Codex", binary: "codex", versionCommand: "double-dash" },
  { id: "gemini", label: "Gemini CLI", binary: "gemini", versionCommand: "double-dash" },
  { id: "cursor", label: "Cursor Agent", binary: "cursor-agent", versionCommand: "double-dash" },
  { id: "devin", label: "Devin CLI", binary: "devin" },
  { id: "opencode", label: "opencode", binary: "opencode", versionCommand: "double-dash" },
  { id: "pi", label: "pi", binary: "pi", versionCommand: "double-dash" },
  { id: "omp", label: "omp", binary: "omp", versionCommand: "double-dash" },
  { id: "grok", label: "Grok", binary: "grok", versionCommand: "double-dash" },
  { id: "hermes", label: "Hermes", binary: "hermes" },
  { id: "muse", label: "Muse", binary: "muse", versionCommand: "double-dash" },
  { id: "agy", label: "Antigravity", binary: "agy", versionCommand: "double-dash" },
  { id: "cline", label: "Cline", binary: "cline", versionCommand: "double-dash" },
  { id: "mastracode", label: "Mastra Code", binary: "mastracode" },
  { id: "copilot", label: "GitHub Copilot CLI", binary: "copilot", versionCommand: "double-dash" },
  { id: "kimi", label: "Kimi CLI", binary: "kimi", versionCommand: "double-dash" },
  { id: "kiro", label: "Kiro CLI", binary: "kiro-cli", versionCommand: "double-dash" },
  { id: "droid", label: "Factory Droid", binary: "droid", versionCommand: "short" },
  { id: "amp", label: "Amp", binary: "amp", versionCommand: "subcommand" },
  { id: "kilo", label: "Kilo CLI", binary: "kilo" },
  { id: "qodercli", label: "Qoder CLI", binary: "qoder", versionCommand: "double-dash" },
  { id: "qwen", label: "Qwen Code", binary: "qwen", versionCommand: "double-dash" },
  { id: "letta", label: "Letta Code", binary: "letta" },
  { id: "maki", label: "Maki", binary: "maki" },
];

/**
 * Contract names the sheet does not offer, each with its reason. `antigravity` is the desktop app's
 * name; the agent that runs in a terminal is `agy`, listed above.
 */
export const NOT_STARTED = {
  antigravity: "the desktop app; its terminal agent is agy",
} as const satisfies Readonly<Record<string, string>>;

/** The launch for `id`, or `undefined` for a name Collie does not start. Exact match only. */
export function harnessLaunch(id: string): HarnessLaunch | undefined {
  return HARNESS_LAUNCHES.find((h) => h.id === id);
}

/** Whether every launch id is a contract name. `harness-launch.test.ts` reads it. */
export function launchIdsAreContractNames(): boolean {
  return HARNESS_LAUNCHES.every((h) => MUX_AGENT_NAMES.includes(h.id));
}

/** How long a found result is kept. */
export const FOUND_TTL_MS = 60_000;
/** The login shell is given this long to print its PATH. */
export const LOGIN_PATH_TIMEOUT_MS = 3_000;
/** A PATH past this is not one a shell printed. */
const LOGIN_PATH_MAX_BYTES = 64 * 1024;

/** Asks a login shell for its PATH. `null` when it cannot be asked or does not answer in time. */
export type LoginPathAsk = (shell: string) => Promise<string | null>;

/**
 * The real thing: `<shell> -lc 'printf %s "$PATH"'`, argv only, no stdin, killed at the cap. `-l`
 * sources the login profile, which is where a profile adds tool folders. A shell that prints more
 * than one line (a profile that echoes) is read as its LAST line.
 */
export const askLoginPath: LoginPathAsk = async (shell) => {
  try {
    const proc = Bun.spawn([shell, "-lc", 'printf %s "$PATH"'], {
      stdin: "ignore",
      stdout: "pipe",
      stderr: "ignore",
    });
    const timer = setTimeout(() => proc.kill(), LOGIN_PATH_TIMEOUT_MS);
    try {
      const out = await new Response(proc.stdout).text();
      const code = await proc.exited;
      if (code !== 0 || out.length > LOGIN_PATH_MAX_BYTES) return null;
      const last = out.split("\n").at(-1)?.trim() ?? "";
      return last === "" ? null : last;
    } finally {
      clearTimeout(timer);
    }
  } catch {
    return null;
  }
};

/** Shells whose `-lc` takes a POSIX-quoted script. Another shell is not asked. */
const POSIX_LOGIN_SHELLS: ReadonlySet<string> = new Set(["bash", "zsh", "sh", "dash", "ksh", "fish"]);

export interface HarnessProbeDeps {
  env?: Record<string, string | undefined>;
  home?: string;
  host?: Host;
  askPath?: LoginPathAsk;
  /** Whether a binary is found on a PATH. Defaults to {@link findTool}. */
  find?: (binary: string, path: string | undefined) => boolean;
  /** Resolves a registered binary for bounded version probing. */
  resolve?: (binary: string, path: string | undefined) => string | null;
  /** Test seam for the read-only, fixed-argv version probe. */
  version?: (executable: string, searchPath: string | undefined, command: VersionCommand) => Promise<string | null>;
  now?: () => number;
}

/** What the routes need: this machine's harnesses with their found flag. */
export interface HarnessProbe {
  list(): Promise<HarnessInfo[]>;
  /** Safe, allowlisted documented version results. Values are parsed versions only, never raw output. */
  versions(): Promise<ReadonlyMap<string, string>>;
  /** Whether `id` is a harness Collie starts here, found or not. */
  launch(id: string): HarnessLaunch | undefined;
}

/** A version command is fixed, bounded, and only run when documented for an explicit launch row. */
export const VERSION_PROBE_TIMEOUT_MS = 1_500;
export const VERSION_PROBE_MAX_BYTES = 4_096;
const VERSION_RE = /(?:^|[^0-9A-Za-z])v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?)(?=$|[^0-9A-Za-z.+-]|\.(?=$|[\s,);]))/;

/**
 * Ask an already allowlisted executable for its version with a fixed `--version` argument.
 * The child receives no pairing, cloud, provider, or Collie secrets from the bridge environment.
 * Only a bounded semantic version is returned; arbitrary command output never crosses the API.
 */
export async function probeHarnessVersion(
  executable: string,
  searchPath?: string,
  command: VersionCommand = "double-dash",
): Promise<string | null> {
  let proc: ReturnType<typeof Bun.spawn> | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const inherited = Object.fromEntries(
      ["SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL"]
        .flatMap((key) => process.env[key] === undefined ? [] : [[key, process.env[key]!]]),
    );
    const env = { PATH: searchPath ?? process.env.PATH ?? "", NO_COLOR: "1", CI: "1", ...inherited };
    const argv = versionArgv(command);
    if (argv === null) return null;
    proc = Bun.spawn([executable, ...argv], {
      stdin: "ignore",
      stdout: "pipe",
      stderr: "ignore",
      env,
      cwd: tmpdir(),
    });
    let expired = false;
    timer = setTimeout(() => {
      expired = true;
      try { proc?.kill(); } catch { /* already exited */ }
    }, VERSION_PROBE_TIMEOUT_MS);

    const stdout = proc.stdout;
    if (!(stdout instanceof ReadableStream)) {
      try { proc.kill(); } catch { /* already exited */ }
      return null;
    }
    const reader = stdout.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    while (length <= VERSION_PROBE_MAX_BYTES) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > VERSION_PROBE_MAX_BYTES) {
        try { proc.kill(); await proc.exited; } catch { /* already exited */ }
        return null;
      }
      chunks.push(value);
    }
    const exitCode = await proc.exited;
    if (expired || exitCode !== 0) return null;
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const output = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
    const match = VERSION_RE.exec(output);
    return match?.[1] ?? null;
  } catch {
    try { proc?.kill(); } catch { /* already exited */ }
    return null;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/**
 * The probe `bridge/index.ts` builds once. The login PATH is asked at most once per process (a
 * profile does not change under a running bridge often enough to pay a shell per sheet), and the
 * found flags are kept for {@link FOUND_TTL_MS}.
 */
export function createHarnessProbe(deps: HarnessProbeDeps = {}): HarnessProbe {
  const env = deps.env ?? process.env;
  const home = deps.home ?? homedir();
  const host = deps.host ?? HOST;
  const askPath = deps.askPath ?? askLoginPath;
  const now = deps.now ?? (() => Date.now());
  const resolve = deps.resolve ?? ((binary: string, path: string | undefined) => findTool(binary, { ...env, PATH: path }, home, host));
  const find =
    deps.find ?? ((binary: string, path: string | undefined) => resolve(binary, path) !== null);

  let loginPath: Promise<string | undefined> | null = null;
  const pathForSearch = (): Promise<string | undefined> => {
    if (loginPath !== null) return loginPath;
    const own = envGet(env, "PATH", host);
    const shell = envGet(env, "SHELL", host);
    // Windows has no login shell to ask, and an unknown shell may not read `-lc` as a script.
    if (host.platform === "win32" || shell === undefined || !POSIX_LOGIN_SHELLS.has(basename(shell))) {
      loginPath = Promise.resolve(own);
      return loginPath;
    }
    loginPath = askPath(shell).then((asked) => asked ?? own);
    return loginPath;
  };

  let cached: { at: number; list: HarnessInfo[] } | null = null;
  let versionCache: { at: number; versions: ReadonlyMap<string, string> } | null = null;
  return {
    async list() {
      // A copy each time, so a caller that edits the answer never edits the cache.
      if (cached !== null && now() - cached.at < FOUND_TTL_MS) return structuredClone(cached.list);
      const path = await pathForSearch();
      const list = HARNESS_LAUNCHES.map((h) => ({ id: h.id, label: h.label, found: find(h.binary, path) }));
      cached = { at: now(), list };
      return structuredClone(list);
    },
    async versions() {
      if (versionCache !== null && now() - versionCache.at < FOUND_TTL_MS) return new Map(versionCache.versions);
      const path = await pathForSearch();
      const version = deps.version ?? probeHarnessVersion;
      const found = new Set((await this.list()).filter((item) => item.found).map((item) => item.id));
      const candidates = HARNESS_LAUNCHES.filter((h) => found.has(h.id));
      const entries: Array<readonly [string, string]> = [];
      let next = 0;
      const worker = async () => {
        while (next < candidates.length) {
          const h = candidates[next++];
          if (h === undefined) return;
          const executable = resolve(h.binary, path);
          if (executable === null) continue;
          if (h.versionCommand === undefined) continue;
          const value = await version(executable, path, h.versionCommand);
          if (value !== null) entries.push([h.id, value]);
        }
      };
      await Promise.all(Array.from({ length: Math.min(3, candidates.length) }, worker));
      const versions = new Map(entries);
      versionCache = { at: now(), versions };
      return new Map(versions);
    },
    launch: harnessLaunch,
  };
}
