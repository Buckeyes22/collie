import { expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { CacheWatchStore } from "../cache/watch.ts";
import { watchKeyOf, type CacheWarnPane } from "../cache/watch-key.ts";
import { serveCacheWatchRoute } from "../server.ts";

test("joined cache watch refuses retired original caller after streamed body wait; fresh caller commits exact scoped watch", async () => {
  const root = await mkdtemp("/var/tmp/collie-b24-joined-watch-");
  const watch = new CacheWatchStore({ stateDir: root }, () => 100);
  const identity = { host: "east", session: "work", ref: "peer-pane:w1:p1" };
  const pane: CacheWarnPane = { ...identity, key: watchKeyOf(identity), paneId: "w1:p1", label: "owned peer", cache: { ruleId: "fixture", state: "warm", confidence: "documented", ttlSeconds: 300, expiresAt: 10000 } };
  const original = { id: "original" };
  let active = original;
  const url = new URL("https://fixture/api/notifications/cache-watch?pane=w1%3Ap1&host=east&session=work");
  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  let observed!: () => void;
  const entered = new Promise<void>((resolve) => { observed = resolve; });
  const body = new ReadableStream<Uint8Array>({
    async start(controller) {
      controller.enqueue(new TextEncoder().encode('{"on":'));
      observed();
      await held;
      controller.enqueue(new TextEncoder().encode("true}"));
      controller.close();
    },
  });
  try {
    const gate = () => active === original ? null : new Response("original caller retired", { status: 403 });
    const pending = serveCacheWatchRoute(new Request(url, { method: "POST", body }), url,
      watch, () => ({ pane }), () => false, 75, gate);
    await entered;
    active = { id: "replacement" };
    release();
    expect((await pending).status).toBe(403);
    expect(watch.current()).toHaveLength(0);
    active = original;
    const positive = await serveCacheWatchRoute(new Request(url, { method: "POST", body: '{"on":true}' }), url,
      watch, () => ({ pane }), () => false, 75, gate);
    expect(positive.status).toBe(200);
    expect(watch.current()).toHaveLength(1);
    expect(watch.current()[0]).toMatchObject(identity);
    const durable = new CacheWatchStore({ stateDir: root }, () => 100);
    await durable.load();
    expect(durable.current()).toEqual(watch.current());
    active = { id: "retired-again" };
    expect((await serveCacheWatchRoute(new Request(url), url, watch, () => ({ pane }), () => false, 75, gate)).status).toBe(403);
    expect(watch.current()).toHaveLength(1);
  } finally {
    release();
    await rm(root, { recursive: true, force: true });
  }
});
