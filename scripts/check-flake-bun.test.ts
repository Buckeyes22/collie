import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

// The flake's pinned Bun and MIN_BUN are two statements about ONE fact: the Bun this tree is built
// and measured on. If the flake ever pins a Bun BELOW MIN_BUN, the preflight warns the operator
// about the very Bun the release was built with — a check that calls its own build stale. So the
// two are read straight out of the files that hold them and compared here.

const ROOT = join(import.meta.dir, "..");

/** The pinned Bun, from the one line in flake.nix that states it. */
function flakeBunVersion(): string {
  const source = readFileSync(join(ROOT, "flake.nix"), "utf8");
  const match = /^\s*bunVersion\s*=\s*"([^"]+)";/m.exec(source);
  if (match === null) {
    throw new Error("flake.nix no longer declares `bunVersion = \"x.y.z\";` — this test reads that line");
  }
  return match[1]!;
}

/** MIN_BUN, from cli/update-check.ts. Read as text: the constant is module-private on purpose. */
function minBun(): string {
  const source = readFileSync(join(ROOT, "cli", "update-check.ts"), "utf8");
  const match = /^const MIN_BUN\s*=\s*"([^"]+)";/m.exec(source);
  if (match === null) {
    throw new Error("cli/update-check.ts no longer declares `const MIN_BUN = \"x.y.z\";`");
  }
  return match[1]!;
}

/** -1, 0 or 1. Numeric per component, so 1.10.0 sorts above 1.9.0 rather than below it. */
function compare(a: string, b: string): number {
  const left = a.split(".").map(Number);
  const right = b.split(".").map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const diff = (left[i] ?? 0) - (right[i] ?? 0);
    if (diff !== 0) return diff > 0 ? 1 : -1;
  }
  return 0;
}

describe("flake.nix and MIN_BUN", () => {
  test("the flake pins a Bun at least MIN_BUN", () => {
    const pinned = flakeBunVersion();
    const floor = minBun();
    expect(compare(pinned, floor)).toBeGreaterThanOrEqual(0);
  });

  test("both versions are plain three-part numbers", () => {
    // A range, a tag or a `latest` in either place would make the comparison above meaningless.
    expect(flakeBunVersion()).toMatch(/^\d+\.\d+\.\d+$/);
    expect(minBun()).toMatch(/^\d+\.\d+\.\d+$/);
  });

  test("the archive URLs the flake fetches carry the pinned version", () => {
    // The version and the three sources are one pin. A `version` bumped without new hashes would
    // fetch the old archives under the new name, and every claim above it would be a lie.
    const source = readFileSync(join(ROOT, "flake.nix"), "utf8");
    const urls = [...source.matchAll(/url = "([^"]*oven-sh\/bun[^"]*)";/g)].map((m) => m[1]!);
    expect(urls).toHaveLength(3);
    for (const url of urls) {
      expect(url).toContain("bun-v${finalAttrs.version}");
    }
  });

  test("the nixpkgs revision in flake.nix is the one flake.lock recorded", () => {
    // `inputs.nixpkgs.url` and the lock are one pin written twice, and only the LOCK is what `nix
    // build` fetches. Editing the rev without running `nix flake lock` therefore builds the old
    // nixpkgs under a new commit id, and the release would name a toolchain it did not use.
    const declared = /inputs\.nixpkgs\.url = "github:NixOS\/nixpkgs\/([0-9a-f]{40})";/.exec(
      readFileSync(join(ROOT, "flake.nix"), "utf8"),
    );
    if (declared === null) {
      throw new Error("flake.nix no longer pins nixpkgs by a 40-character revision — this test reads that line");
    }
    // SAFETY: the shape is asserted, not trusted — every step to `rev` is optional, so a lock that
    // does not have it reads as `undefined` and fails the comparison below rather than throwing.
    const lock = JSON.parse(readFileSync(join(ROOT, "flake.lock"), "utf8")) as {
      nodes: { nixpkgs?: { locked?: { rev?: string } } };
    };
    expect(lock.nodes.nixpkgs?.locked?.rev).toBe(declared[1]!);
  });

  test("compare orders versions numerically, not as text", () => {
    expect(compare("1.10.0", "1.9.0")).toBe(1);
    expect(compare("1.3.14", "1.3.14")).toBe(0);
    expect(compare("1.3.13", "1.3.14")).toBe(-1);
    expect(compare("1.4", "1.4.0")).toBe(0);
  });
});

describe("the Windows check runs the flake's Bun", () => {
  test("windows.yml pins setup-bun to flake.nix's bunVersion", () => {
    const workflow = readFileSync(join(ROOT, ".github", "workflows", "windows.yml"), "utf8");
    // SAFETY: `Bun.YAML.parse` answers plain data; the one field read is compared against a string.
    const parsed = Bun.YAML.parse(workflow) as { jobs: Record<string, { steps: { uses?: string; with?: Record<string, string> }[] }> };
    const steps = Object.values(parsed.jobs).flatMap((j) => j.steps);
    const setup = steps.filter((s) => s.uses?.startsWith("oven-sh/setup-bun@") === true);
    expect(setup).toHaveLength(1);
    expect(String(setup[0]?.with?.["bun-version"])).toBe(flakeBunVersion());
  });

  test("windows-suites.ps1 allows only its documented Windows conditions, per suite", () => {
    const script = readFileSync(join(ROOT, "scripts", "windows-suites.ps1"), "utf8");
    const budget = (suite: string): number => Number(new RegExp(`\\[int\\]\\$MaxSkips${suite} = (\\d+)`).exec(script)?.[1] ?? Number.NaN);
    expect([budget("Bridge"), budget("Cli"), budget("Scripts")]).toEqual([62, 1, 23]);

    // These are the four existing POSIX-only local-cli tests; their exact names and count are the
    // whole STT allowance. The durable cases are a separate documented census below.
    const localCliPath = join(ROOT, "bridge", "stt", "local-cli.test.ts");
    const localCli = readFileSync(localCliPath, "utf8");
    const posixOnly = [...localCli.matchAll(/test\.if\(posix\)\("([^"]+)"/g)].map((m) => m[1]!);
    expect(posixOnly).toEqual([
      "commandFileProblem: regular executable passes, symlinks are followed",
      "status refuses a directory and a non-executable file, and names neither",
      "only an old directory with the prefix, owned by this user, is removed",
      "loading the provider sweeps its temp root once, in the background",
    ]);
    expect(script).toContain("bridge 4, in bridge/stt/local-cli.test.ts");
    for (const name of posixOnly) expect(script).toContain(name);

    // WIRE.md section 1A and ADR 0103 define the socket-only cases. Keep each file/count and its
    // reason explicit so a changed platform condition cannot silently borrow another suite's budget.
    const durable = [
      ["bridge/durable/log.test.ts", 1, "test.skipIf(HOST.platform === \"win32\")", "1 POSIX chmod repair test"],
      ["bridge/mux/durable/adapter.test.ts", 6, "SOCKET_ONLY_SKIP", "6 socket-only cases"],
      ["bridge/mux/durable/asks.test.ts", 1, "SOCKET_ONLY_SKIP", "1 socket-only case"],
      ["bridge/mux/durable/client.test.ts", 27, "SOCKET_ONLY_SKIP", "27 Unix socket privacy/path/wire cases"],
      ["bridge/mux/durable/events.test.ts", 1, "SOCKET_ONLY_SKIP", "1 socket log-folder factory case"],
      ["bridge/mux/durable/example.test.ts", 10, "describe.skipIf(!RUNS)", "10 real example Unix-socket cases"],
      ["bridge/mux/durable/example-cloudflare.test.ts", 11, "describe.skipIf(!RUNS)", "11 optional Wrangler process-"],
      ["bridge/mux/durable/http-client.test.ts", 1, "SOCKET_ONLY_SKIP", "1 comparison against socket transport"],
    ] as const;
    expect(durable).toHaveLength(8);
    expect(durable.reduce((sum, [, count]) => sum + count, 0)).toBe(58);
    expect(script).toContain("bridge also includes 58 existing conditional tests added with the durable adapter:");
    expect(script).toContain("WIRE.md section 1A and ADR 0103 define the private Unix socket and Windows HTTPS");
    for (const [relativePath, _count, guard, rationale] of durable) {
      const path = join(ROOT, relativePath);
      expect(rationale.startsWith(`${_count} `)).toBe(true);
      expect(existsSync(path)).toBe(true);
      expect(readFileSync(path, "utf8")).toContain(guard);
      expect(script).toContain(`${relativePath}: ${rationale}`);
    }
    expect(script).toContain("Today, 86 in all.");
    expect(script).not.toContain("[int]$MaxSkips = ");
    expect(script).toContain("if ($skip -gt $skipBudget[$name])");
  });
});
