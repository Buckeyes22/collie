import { afterAll, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { peerWatchPane, resolvePeerWatchPane, watchKeyOf } from "./watch-key.ts";
import { CacheWatchStore } from "./watch.ts";
import { serveCacheWatchRoute } from "../server.ts";
import type { PaneWire } from "../types.ts";

const dirs: string[] = [];
afterAll(async () => { await Promise.all(dirs.map((d) => rm(d, { recursive: true, force: true }))); });
function pane(session?: string): PaneWire {
  const out: PaneWire = { paneId: "w1:p1", workspaceId: "w1", workspaceLabel: "work", workspaceNumber: 1,
    tabId: "w1:t1", agent: "claude", status: "idle", cwd: "/repo", focused: false, hasSession: true,
    cache: { ruleId: "fixture", state: "warm", confidence: "documented", ttlSeconds: 300, expiresAt: 10000 } };
  return session === undefined ? out : { ...out, session };
}

test("actual preference handler and durable store separate host/session/pane collisions and orphan old unscoped rows", async () => {
  const dir = await mkdtemp(join(tmpdir(), "collie-b20-scope-")); dirs.push(dir);
  const store = new CacheWatchStore({ stateDir: dir }, () => 100);
  const body = { agents: [pane("primary"), pane("work")], sessions: [{ name: "primary", isPrimary: true }, { name: "work" }] };
  const call = (host: string, session: string | undefined, on?: boolean) => {
    const url = new URL("https://fixture/api/notifications/cache-watch");
    url.searchParams.set("pane", "w1:p1"); url.searchParams.set("host", host);
    if (session !== undefined) url.searchParams.set("session", session);
    return serveCacheWatchRoute(new Request(url, on === undefined ? undefined : { method: "POST", body: JSON.stringify({ on }) }), url,
      store, (id) => {
        const selected = resolvePeerWatchPane(body, id, url.searchParams.get("session") ?? undefined);
        const target = selected === undefined ? undefined : peerWatchPane(selected, host);
        return target === undefined ? { error: "cache.pane_unknown" } : { pane: target };
      }, () => false, 75);
  };
  await store.set({ host: "east", ref: "pane:w1:p1" }, "legacy ambiguous", true);
  expect((await call("east", "work", true)).status).toBe(200);
  expect((await call("east", "primary", true)).status).toBe(200);
  expect((await call("west", "work", true)).status).toBe(200);
  expect(store.current()).toHaveLength(4);
  expect((await (await call("east", "missing", true)).json()).code).toBe("cache.pane_unknown");
  expect(store.current()).toHaveLength(4);
  const ids = new Map(body.agents.map((p) => [peerWatchPane(p, "east")!.key, p.paneId]));
  const rows = store.list((e) => ids.get(watchKeyOf(e)));
  expect(rows.find((r) => r.label === "legacy ambiguous")?.paneId).toBeUndefined();
  expect(rows.filter((r) => r.host === "east" && r.paneId !== undefined).map((r) => r.session).toSorted()).toEqual(["primary", "work"]);
  expect((await (await call("east", undefined)).json()).on).toBe(true);
  await call("east", "work", false);
  expect((await (await call("east", "work")).json()).on).toBe(false);
  expect((await (await call("east", "primary")).json()).on).toBe(true);
  expect((await (await call("west", "work")).json()).on).toBe(true);
  const loaded = new CacheWatchStore({ stateDir: dir }, () => 100); await loaded.load();
  expect(loaded.current()).toEqual(store.current());
  await loaded.forget(rows.find((r) => r.label === "legacy ambiguous")!.id);
  expect(loaded.current()).toHaveLength(2);
});

test("older untagged snapshots prove only unique default or the single advertised session", () => {
  const untagged = pane();
  expect(resolvePeerWatchPane({ agents: [untagged], sessions: [] }, "w1:p1", undefined)).toBe(untagged);
  expect(resolvePeerWatchPane({ agents: [untagged], sessions: [] }, "w1:p1", "guessed")).toBeUndefined();
  expect(resolvePeerWatchPane({ agents: [untagged], sessions: [{ name: "work", isPrimary: true }] }, "w1:p1", "work")).toBe(untagged);
  expect(resolvePeerWatchPane({ agents: [untagged, untagged], sessions: [] }, "w1:p1", undefined)).toBeUndefined();
  expect(resolvePeerWatchPane({ agents: [untagged], sessions: [{ name: "one" }, { name: "two" }] }, "w1:p1", undefined)).toBeUndefined();
  expect(resolvePeerWatchPane({ agents: [pane("work"), pane("work")], sessions: [{ name: "work" }] }, "w1:p1", "work")).toBeUndefined();
});

test("peer addresses cannot inject the private watch key separator", () => {
  const nul = String.fromCharCode(0);
  expect(peerWatchPane(pane(`work${nul}peer-pane:w1:p1`), "east")).toBeUndefined();
  expect(peerWatchPane({ ...pane("work"), paneId: `w1:p1${nul}extra` }, "east")).toBeUndefined();
});
