import { describe, expect, test } from "bun:test";
import { HerdrMux } from "./herdr/adapter.ts";
import { FakeHerdr } from "./herdr/fixture.ts";
import { TernMux } from "./tern/adapter.ts";
import { FakeTern } from "./tern/fixture.ts";
import { TmuxMux } from "./tmux/adapter.ts";
import { FakeTmux } from "./tmux/fixture.ts";
import { TuiosMux } from "./tuios/adapter.ts";
import { FakeTuios } from "./tuios/fixture.ts";
import { ZellijMux } from "./zellij/adapter.ts";
import { FakeZellij } from "./zellij/fixture.ts";
import { ZellijSessionBinding } from "./zellij/session.ts";

const rows = (count: number) => Array.from({ length: count }, (_, index) => `history-${String(index)}`);
const lineCount = (text: string) => (text.length === 0 ? 0 : text.split("\n").length);

describe("adapter read ceilings against pane-read fixtures", () => {
  test("Herdr 0.9.3 stops at its 1000-row API ceiling", async () => {
    const fake = new FakeHerdr();
    const mux = new HerdrMux(fake, () => []);
    const pane = (await mux.snapshot()).panes[0];
    if (pane === undefined) throw new Error("Herdr fixture has no pane");
    fake.setScrollback(pane.paneId, rows(1_400));

    const result = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_400, styling: "strip" });

    expect(mux.capabilities.readLimits.maxRequestedLines).toBe(1_000);
    expect(result.ok && lineCount(result.value.text)).toBe(1_000);
    // Herdr 0.9.3 sets this when its line helper omits older rows; false still is not an alt-screen guarantee.
    expect(result.ok && result.value.truncated).toBe(true);
  });

  test("tmux returns more than 1000 retained rows and reports a shrinking buffer honestly", async () => {
    const fake = new FakeTmux();
    const mux = new TmuxMux(fake);
    const pane = (await mux.snapshot()).panes[0];
    if (pane === undefined) throw new Error("tmux fixture has no pane");
    fake.setScrollback(pane.paneId, rows(1_400));

    const full = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    expect(mux.capabilities.readLimits.maxRequestedLines).toBe(10_000);
    expect(full.ok && lineCount(full.value.text)).toBe(1_200);
    expect(full.ok && full.value.truncated).toBe(true);

    fake.setScrollback(pane.paneId, rows(200));
    const shrunk = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    expect(shrunk.ok && lineCount(shrunk.value.text)).toBe(202);
    expect(shrunk.ok && shrunk.value.truncated).toBe(false);
  });

  test("tuios returns over 1000 rows while retaining its pane-specific available depth", async () => {
    const fake = new FakeTuios();
    const mux = new TuiosMux(fake);
    const pane = (await mux.snapshot()).panes[0];
    if (pane === undefined) throw new Error("tuios fixture has no pane");
    fake.setScrollback(pane.paneId, rows(1_400));

    const full = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    expect(mux.capabilities.readLimits.maxRequestedLines).toBe(10_000);
    expect(lineCount(full.ok ? full.value.text : "")).toBe(1_200);
    expect((await mux.snapshot()).panes.find((candidate) => candidate.paneId === pane.paneId)?.readableLines).toBe(1_424);

    fake.setScrollback(pane.paneId, rows(3));
    const shrunk = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    expect(lineCount(shrunk.ok ? shrunk.value.text : "")).toBe(5);
    expect(shrunk.ok && shrunk.value.truncated).toBe(false);
  });

  test("tern accepts requests above 1000 but leaves retained depth unknown", async () => {
    const fake = new FakeTern();
    const mux = new TernMux(fake);
    const pane = (await mux.snapshot()).panes[0];
    if (pane === undefined) throw new Error("tern fixture has no pane");
    expect(pane.readableLines).toBeUndefined();
    fake.setScrollback(pane.paneId, rows(1_400));

    const result = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    const shrunkPane = (await mux.snapshot()).panes.find((candidate) => candidate.paneId === pane.paneId);

    expect(mux.capabilities.readLimits.maxRequestedLines).toBe(10_000);
    expect(result.ok && lineCount(result.value.text)).toBe(1_200);
    expect(result.ok && result.value.truncated).toBe(true);
    expect(shrunkPane?.readableLines).toBeUndefined();
  });

  test("zellij accepts a request above 1000 but leaves available depth unknown", async () => {
    const fake = new FakeZellij();
    const mux = new ZellijMux(new ZellijSessionBinding(fake, ""));
    const pane = (await mux.snapshot()).panes[0];
    if (pane === undefined) throw new Error("zellij fixture has no pane");
    fake.setScrollback(pane.paneId, rows(1_400));

    const result = await mux.readGrid(pane.paneId, { scope: "recent", lines: 1_200, styling: "strip" });
    const refreshedPane = (await mux.snapshot()).panes.find((candidate) => candidate.paneId === pane.paneId);

    expect(mux.capabilities.readLimits.maxRequestedLines).toBe(10_000);
    expect(result.ok && lineCount(result.value.text)).toBe(1_200);
    expect(result.ok && result.value.truncated).toBe(true);
    expect(refreshedPane?.readableLines).toBeUndefined();
  });
});
