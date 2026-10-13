import { describe, expect, test } from "bun:test";
import { FakeHerdr } from "../mux/herdr/fixture.ts";
import { HerdrMux } from "../mux/herdr/adapter.ts";
import { servePaneProcesses, type PaneProcessRuntime, type PaneProcessSnapshot } from "./processes.ts";

interface FixtureProcessRow {
  readonly pid: number;
  readonly name: string;
  readonly argv: string[];
  cwd?: string;
}

function engine(paneId: string): PaneProcessSnapshot {
  return { current: () => ({ agents: [{ paneId }], shellPanes: [] }) };
}

function noProcessMux(): PaneProcessRuntime {
  return {
    capabilities: { supports: { paneProcesses: false } },
    processInfo: undefined,
  };
}

describe("GET /api/pane/:id/processes response boundary", () => {
  test("unsupported adapter fails closed and a missing pane is not guessed from a PID", async () => {
    expect((await servePaneProcesses(noProcessMux(), engine("p1"), "p1")).status).toBe(501);
    expect((await servePaneProcesses(noProcessMux(), engine("p1"), "p2")).status).toBe(404);
  });

  test("admits a current bare shell pane through its owning mux", async () => {
    const calls: string[] = [];
    const mux: PaneProcessRuntime = {
      capabilities: { supports: { paneProcesses: true } },
      processInfo: async (paneId) => {
        calls.push(paneId);
        return { ok: true, value: { paneId, shellPid: 42, processes: [], truncated: false } };
      },
    };
    const shellEngine: PaneProcessSnapshot = {
      current: () => ({ agents: [], shellPanes: [{ paneId: "shell:7" }] }),
    };

    const response = await servePaneProcesses(mux, shellEngine, "shell:7");
    expect(response.status).toBe(200);
    expect(calls).toEqual(["shell:7"]);
  });

  test("refuses a shell pane that is absent before dispatch or retires during the RPC", async () => {
    let calls = 0;
    let present = false;
    const mux: PaneProcessRuntime = {
      capabilities: { supports: { paneProcesses: true } },
      processInfo: async (paneId) => {
        calls += 1;
        present = false;
        return { ok: true, value: { paneId, shellPid: 99, processes: [{ pid: 1, name: "private-shell" }], truncated: false } };
      },
    };
    const shellEngine: PaneProcessSnapshot = {
      current: () => ({ agents: [], shellPanes: present ? [{ paneId: "shell:8" }] : [] }),
    };
    expect((await servePaneProcesses(mux, shellEngine, "shell:8")).status).toBe(404);
    expect(calls).toBe(0);

    present = true;
    const response = await servePaneProcesses(mux, shellEngine, "shell:8");
    expect(response.status).toBe(404);
    expect(calls).toBe(1);
    expect(await response.text()).not.toContain("private-shell");
  });

  test("redacts secret arguments even when the terminal redaction preference is off", async () => {
    const fake = new FakeHerdr();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    fake.processInfo = async (target) => ({
      pane_id: target,
      shell_pid: null,
      foreground_process_group_id: null,
      foreground_processes: [{ pid: 100, name: "node", argv: ["node", "--token=abcDEF123xyz"], cmdline: "node --token=abcDEF123xyz" }],
      tty: null,
    });
    const mux = new HerdrMux(fake, () => []);
    const response = await servePaneProcesses(mux, engine(paneId), paneId);
    const body = await response.text();
    expect(response.status).toBe(200);
    expect(body).not.toContain("abcDEF123xyz");
    expect(body).toContain("••••••••••••");
  });

  test("redacts before the aggregate adapter cutoff can split a credential", async () => {
    const fake = new FakeHerdr();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const args = Array.from({ length: 64 * 64 }, (_, index) => "x".repeat(index < 4084 ? 51 : 50));
    const rows = Array.from({ length: 64 }, (_, index) => {
      const row: FixtureProcessRow = {
        pid: index + 1,
        name: "n".repeat(256),
        argv: args.slice(index * 64, (index + 1) * 64),
      };
      if (index === 63) row.cwd = "password=KnownCredentialThatMustNeverLeak123";
      return row;
    });
    fake.processInfo = async (target) => ({ pane_id: target, foreground_processes: rows });
    const response = await servePaneProcesses(new HerdrMux(fake, () => []), engine(paneId), paneId);
    const body = await response.text();
    expect(response.status).toBe(200);
    expect(body).not.toContain("KnownCredentialThatMustNeverLeak123");
    expect(body).toContain("password=••");
  });

  test("bounds process rows and snapshot diagnostics at the final HTTP boundary", async () => {
    const fake = new FakeHerdr();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    fake.changePaneMetadata(paneId, { foreground_cwd: "/".repeat(5000) });
    fake.processInfo = async (target) => ({
      pane_id: target,
      foreground_processes: Array.from({ length: 70 }, (_, pid) => ({ pid, name: "n".repeat(300) })),
    });
    const response = await servePaneProcesses(new HerdrMux(fake, () => []), {
      current: () => ({ agents: [{ paneId, foregroundCwd: "/stale-private-metadata" }], shellPanes: [] }),
    } satisfies PaneProcessSnapshot, paneId);
    // SAFETY: the process endpoint returns the exact test fields asserted below.
    const body = await response.json() as { processes: unknown[]; foregroundCwd: string; truncated: boolean };
    expect(body.processes).toHaveLength(64);
    expect(body.foregroundCwd).toHaveLength(4096);
    expect(body.truncated).toBe(true);
    expect(JSON.stringify(body)).not.toContain("stale-private-metadata");
  });

  test("reports a changed occupant as gone and does not accept matching PID as identity", async () => {
    class Replaced extends FakeHerdr {
      override async processInfo(target: string) {
        const result = await super.processInfo(target);
        this.changeTerminalOccupant(target, "new-terminal");
        return result;
      }
    }
    const fake = new Replaced();
    const paneId = (await fake.listPanes())[0]!.pane_id;
    const response = await servePaneProcesses(new HerdrMux(fake, () => []), engine(paneId), paneId);
    expect(response.status).toBe(404);
    const body = await response.text();
    expect(body).not.toContain("/home");
    expect(body).not.toContain("zsh");
  });
});
