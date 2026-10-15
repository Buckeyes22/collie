import { describe, expect, test } from "bun:test";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  FOUND_TTL_MS,
  HARNESS_LAUNCHES,
  NOT_STARTED,
  createHarnessProbe,
  harnessLaunch,
  launchIdsAreContractNames,
  probeHarnessVersion,
} from "./harness-launch.ts";
import { hostFor } from "./host.ts";
import { MUX_AGENT_NAMES } from "./mux/agents.ts";

// The agents the New sheet starts by id (ADR 0091): the bridge's list, never the phone's.

describe("the harness table", () => {
  test("every id is an agent name the mux contract accepts", () => {
    expect(launchIdsAreContractNames()).toBe(true);
  });

  test("every contract name is either started or named with a reason", () => {
    const started = new Set(HARNESS_LAUNCHES.map((h) => h.id));
    const missing = MUX_AGENT_NAMES.filter((name) => !started.has(name) && !Object.hasOwn(NOT_STARTED, name));
    expect(missing).toEqual([]);
  });

  test("ids are unique and a binary is one bare word, never a line", () => {
    expect(new Set(HARNESS_LAUNCHES.map((h) => h.id)).size).toBe(HARNESS_LAUNCHES.length);
    for (const h of HARNESS_LAUNCHES) expect(h.binary).toMatch(/^[a-z][a-z0-9-]*$/);
  });

  test("a lookup is exact: no prefix, no prototype key", () => {
    expect(harnessLaunch("claude")?.binary).toBe("claude");
    expect(harnessLaunch("claude-danger")).toBeUndefined();
    expect(harnessLaunch("toString")).toBeUndefined();
    expect(harnessLaunch("antigravity")).toBeUndefined();
  });

  test("officially documented terminal CLIs have exact launch binaries without reader claims", () => {
    const expected = new Map([
      ["gemini", "gemini"], ["cursor", "cursor-agent"], ["devin", "devin"],
      ["mastracode", "mastracode"], ["kiro", "kiro-cli"], ["droid", "droid"],
      ["amp", "amp"], ["kilo", "kilo"], ["qodercli", "qoder"],
      ["letta", "letta"], ["maki", "maki"],
    ]);
    for (const [id, binary] of expected) expect(harnessLaunch(id)?.binary).toBe(binary);
    expect(new Set(HARNESS_LAUNCHES.map((h) => h.id))).toEqual(new Set([
      "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode",
      "opencode", "copilot", "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo",
      "qodercli", "qwen", "letta", "maki", "muse", "pi",
    ]));
  });
});

describe("createHarnessProbe — found, on the login shell's PATH", () => {
  const linux = hostFor("linux");

  test("searches the PATH the login shell printed, not the bridge's own", async () => {
    const seen: Array<string | undefined> = [];
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin", SHELL: "/bin/bash" },
      host: linux,
      askPath: () => Promise.resolve("/home/op/.opencode/bin:/usr/bin"),
      find: (binary, path) => {
        seen.push(path);
        return binary === "opencode";
      },
    });
    const list = await probe.list();
    expect(seen.every((p) => p === "/home/op/.opencode/bin:/usr/bin")).toBe(true);
    expect(list.find((h) => h.id === "opencode")).toEqual({ id: "opencode", label: "opencode", found: true });
    expect(list.find((h) => h.id === "claude")?.found).toBe(false);
  });

  test("a shell that cannot be asked falls back to the bridge's own PATH", async () => {
    const seen: Array<string | undefined> = [];
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin", SHELL: "/bin/zsh" },
      host: linux,
      askPath: () => Promise.resolve(null),
      find: (_binary, path) => {
        seen.push(path);
        return false;
      },
    });
    await probe.list();
    expect(new Set(seen)).toEqual(new Set(["/usr/bin"]));
  });

  test("an unknown shell is never asked", async () => {
    let asked = 0;
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin", SHELL: "/opt/weird/xonsh" },
      host: linux,
      askPath: () => {
        asked++;
        return Promise.resolve("/nope");
      },
      find: () => false,
    });
    await probe.list();
    expect(asked).toBe(0);
  });

  test("Windows asks no login shell", async () => {
    let asked = 0;
    const probe = createHarnessProbe({
      env: { Path: "C:\\bin", SHELL: "/bin/bash" },
      host: hostFor("win32"),
      askPath: () => {
        asked++;
        return Promise.resolve("/nope");
      },
      find: () => false,
    });
    await probe.list();
    expect(asked).toBe(0);
  });

  test("the login PATH is asked once per process, and found flags are kept for the TTL", async () => {
    let asked = 0;
    let finds = 0;
    let clock = 0;
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin", SHELL: "/bin/bash" },
      host: linux,
      askPath: () => {
        asked++;
        return Promise.resolve("/usr/bin");
      },
      find: () => {
        finds++;
        return true;
      },
      now: () => clock,
    });
    await probe.list();
    await probe.list();
    expect(finds).toBe(HARNESS_LAUNCHES.length);
    clock += FOUND_TTL_MS;
    await probe.list();
    expect(finds).toBe(HARNESS_LAUNCHES.length * 2);
    expect(asked).toBe(1);
  });

  test("a caller cannot change the cached list", async () => {
    const probe = createHarnessProbe({ env: {}, host: linux, askPath: () => Promise.resolve(null), find: () => true });
    const first = await probe.list();
    first[0]!.found = false;
    expect((await probe.list())[0]!.found).toBe(true);
  });

  test("versions probe only resolved allowlisted binaries and return parsed values, never output", async () => {
    const calls: Array<{ executable: string; path: string | undefined; command: string }> = [];
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin", SHELL: "/bin/bash" },
      host: linux,
      askPath: () => Promise.resolve("/login/bin:/usr/bin"),
      resolve: (binary) => binary === "claude" ? "/opt/claude/bin/claude" : null,
      version: async (executable, path, command) => {
        calls.push({ executable, path, command });
        return "2.1.296";
      },
    });
    const first = await probe.versions();
    expect([...first]).toEqual([["claude", "2.1.296"]]);
    const callerCopy = new Map(first);
    callerCopy.set("codex", "attacker-controlled");
    expect([...await probe.versions()]).toEqual([["claude", "2.1.296"]]);
    expect(calls).toEqual([{ executable: "/opt/claude/bin/claude", path: "/login/bin:/usr/bin", command: "double-dash" }]);
  });

  test("documented version commands are fixed, and undocumented launchers are not probed", async () => {
    const calls: Array<{ id: string; command: string }> = [];
    const probe = createHarnessProbe({
      env: { PATH: "/usr/bin" }, host: linux, find: () => true,
      resolve: (binary) => `/bin/${binary}`,
      version: async (_executable, _path, command) => {
        calls.push({ id: _executable.slice("/bin/".length), command });
        return "1.2.3";
      },
    });
    const versions = await probe.versions();
    expect(versions.get("droid")).toBe("1.2.3");
    expect(versions.get("amp")).toBe("1.2.3");
    expect(versions.get("maki")).toBeUndefined();
    expect(calls).toContainEqual({ id: "droid", command: "short" });
    expect(calls).toContainEqual({ id: "amp", command: "subcommand" });
    expect(calls.some(({ id }) => id === "maki" || id === "devin" || id === "letta")).toBe(false);
  });

  test("the bounded process probe extracts only the semantic version", async () => {
    expect(await probeHarnessVersion(process.execPath)).toBe(Bun.version);
  });

  if (process.platform !== "win32") {
    test("the process probe passes one fixed argument, drops unrelated environment, and caps output", async () => {
      const dir = await mkdtemp(join(tmpdir(), "collie-version-probe-"));
      const executable = join(dir, "version-probe");
      const previous = process.env.COLLIE_VERSION_PROBE_SECRET;
      process.env.COLLIE_VERSION_PROBE_SECRET = "must-not-cross-the-probe-boundary";
      try {
        await writeFile(executable, [
          "#!/bin/sh",
          "[ \"$#\" -eq 1 ] && [ \"$1\" = \"--version\" ] || exit 51",
          "[ -z \"${COLLIE_VERSION_PROBE_SECRET:-}\" ] || { printf '9.9.9'; exit 52; }",
          "printf 'tool version v2.3.4-build.\\n'",
        ].join("\n"));
        await chmod(executable, 0o700);
        expect(await probeHarnessVersion(executable, "/usr/bin:/bin")).toBe("2.3.4-build");

        await writeFile(executable, "#!/bin/sh\nprintf '%5000s' ''\n");
        expect(await probeHarnessVersion(executable, "/usr/bin:/bin")).toBeNull();
      } finally {
        if (previous === undefined) delete process.env.COLLIE_VERSION_PROBE_SECRET;
        else process.env.COLLIE_VERSION_PROBE_SECRET = previous;
        await rm(dir, { recursive: true, force: true });
      }
    });

    test("the process probe accepts only its closed documented version forms", async () => {
      const dir = await mkdtemp(join(tmpdir(), "collie-version-argv-"));
      const executable = join(dir, "version-probe");
      try {
        await writeFile(executable, [
          "#!/bin/sh",
          "[ \"$#\" -eq 1 ] || exit 51",
          "case \"$1\" in -v|version|--version) printf 'tool 4.5.6\\n' ;; *) exit 52 ;; esac",
        ].join("\n"));
        await chmod(executable, 0o700);
        expect(await probeHarnessVersion(executable, "/usr/bin:/bin", "short")).toBe("4.5.6");
        expect(await probeHarnessVersion(executable, "/usr/bin:/bin", "subcommand")).toBe("4.5.6");
      } finally {
        await rm(dir, { recursive: true, force: true });
      }
    });

    test("the process probe terminates a fixed executable that exceeds the timeout", async () => {
      const dir = await mkdtemp(join(tmpdir(), "collie-version-timeout-"));
      const executable = join(dir, "version-probe");
      try {
        await writeFile(executable, "#!/bin/sh\nexec /bin/sleep 3\n");
        await chmod(executable, 0o700);
        const started = Date.now();
        expect(await probeHarnessVersion(executable, "/usr/bin:/bin")).toBeNull();
        expect(Date.now() - started).toBeLessThan(2_500);
      } finally {
        await rm(dir, { recursive: true, force: true });
      }
    });
  }
});
