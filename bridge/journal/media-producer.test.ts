import { describe, expect, test } from "bun:test";

import { createJournalMediaProducer } from "./media-producer.ts";
import type { TranscriptEntry } from "./types.ts";
import type { MediaBinding, MediaMetadata, MediaProducerPort, RegisteredMediaSource } from "../media/types.ts";

const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/tmcAAAAASUVORK5CYII=",
  "base64",
);
const HASH = "a".repeat(64);

function fixtureEntry(url: string): TranscriptEntry {
  return {
    uuid: "entry-1", ts: "2026-10-10T12:00:00.000Z", role: "assistant",
    parts: [{ kind: "image", url, mimeType: "image/png" }],
  };
}

function setup(admit: (binding: MediaBinding) => boolean = () => true) {
  const source: RegisteredMediaSource = { handle: "opaque-owner-handle", generation: 7 };
  const submitted: Array<{ binding: MediaBinding; bytes: Uint8Array; metadata: MediaMetadata }> = [];
  let registered = 0;
  const port: MediaProducerPort = {
    register: async (identity, types, limits) => {
      registered++;
      expect(identity).toMatchObject({ producer: "pi.journal", kind: "omp", adapterVersion: "18.4.10" });
      expect(types).toEqual(["image/png", "image/jpeg", "image/webp"]);
      expect(limits.maxImageBytes).toBe(10 * 1024 * 1024);
      expect(limits.maxPixels).toBe(20_000_000);
      expect(limits.maxItems).toBe(32);
      expect(limits.maxSessionBytes).toBe(100 * 1024 * 1024);
      return source;
    },
    submit: async (binding, bytes, metadata) => {
      if (!admit(binding)) throw new Error("owner refused this pane occupant");
      submitted.push({ binding, bytes, metadata });
      return { reference: `/api/blobs/${HASH}` };
    },
    revoke: async () => undefined,
  };
  const producer = createJournalMediaProducer("omp", "18.4.10", port, async () => null)!;
  const binding: MediaBinding = {
    source, ownerHost: "host-1", runtime: "herdr", session: "session-2", paneId: "pane-3",
    occupantFingerprint: "opaque-fingerprint", occupantGeneration: 4, producerSequence: 10,
    observationRef: "journal:entry-1",
  };
  return { producer, port, binding, submitted, get registered() { return registered; } };
}

describe("journal media producer port", () => {
  test("publishes an actual inline pi/OMP image through an owner-bound injected port", async () => {
    const state = setup();
    const result = await state.producer.publish(
      fixtureEntry(`data:image/png;base64,${PNG.toString("base64")}`),
      () => state.binding,
    );
    expect(result.published).toBe(1);
    expect(result.refusals).toEqual([]);
    expect(result.entry.parts[0]).toMatchObject({ kind: "image", url: `/api/blobs/${HASH}` });
    expect(state.registered).toBe(1);
    expect(state.submitted).toHaveLength(1);
    expect(Buffer.from(state.submitted[0]!.bytes)).toEqual(PNG);
    expect(state.submitted[0]!.metadata).toMatchObject({
      mimeType: "image/png", provenance: "vendor-native", harness: "omp", journalEntry: "entry-1", partIndex: 0,
    });
    expect(state.submitted[0]!.binding.occupantGeneration).toBe(4);
  });

  test("does not send mismatched signatures or replace references on an owner-binding mismatch", async () => {
    const state = setup();
    const mismatch = await state.producer.publish(
      fixtureEntry(`data:image/jpeg;base64,${PNG.toString("base64")}`),
      () => state.binding,
    );
    expect(mismatch.published).toBe(0);
    expect(state.submitted).toHaveLength(0);

    const changed = { ...state.binding, source: { ...state.binding.source, generation: 8 } };
    const stale = await state.producer.publish(
      fixtureEntry(`data:image/png;base64,${PNG.toString("base64")}`),
      () => changed,
    );
    expect(stale.published).toBe(0);
    expect(stale.entry.parts[0]).toMatchObject({ kind: "text", text: expect.stringContaining("Image unavailable") });
    expect(state.submitted).toHaveLength(0);
  });

  test("passes owner, runtime, session and occupant custody unchanged to the owner admission port", async () => {
    const expected = setup();
    const image = fixtureEntry(`data:image/png;base64,${PNG.toString("base64")}`);
    const result = await expected.producer.publish(image, () => expected.binding);
    expect(result.published).toBe(1);
    expect(expected.submitted[0]!.binding).toEqual({
      source: expected.binding.source,
      ownerHost: "host-1", runtime: "herdr", session: "session-2", paneId: "pane-3",
      occupantFingerprint: "opaque-fingerprint", occupantGeneration: 4, producerSequence: 10,
      observationRef: "journal:entry-1",
    });

    const owner = setup((binding) => binding.ownerHost === "host-1" && binding.runtime === "herdr" &&
      binding.session === "session-2" && binding.paneId === "pane-3" &&
      binding.occupantFingerprint === "opaque-fingerprint" && binding.occupantGeneration === 4);
    const stale = await owner.producer.publish(image, () => ({
      ...owner.binding, occupantFingerprint: "replacement-occupant", occupantGeneration: 5,
    }));
    expect(stale.published).toBe(0);
    expect(stale.entry.parts[0]).toMatchObject({ kind: "text", text: expect.stringContaining("Image unavailable") });
    expect(owner.submitted).toHaveLength(0);
  });

  test("rejects oversized payloads before base64 allocation and accepts only contained blob refs", async () => {
    let trustedReads = 0;
    const state = setup();
    const tooLarge = `data:image/png;base64,${"A".repeat(14 * 1024 * 1024)}`;
    const large = await state.producer.publish(fixtureEntry(tooLarge), () => state.binding);
    expect(large.published).toBe(0);
    expect(state.submitted).toHaveLength(0);

    const blobProducer = createJournalMediaProducer("omp", "18.4.10", state.port, async (hash, maxBytes) => {
      trustedReads++;
      expect(hash).toBe(HASH);
      expect(maxBytes).toBe(10 * 1024 * 1024);
      return PNG;
    });
    const result = await blobProducer!.publish(fixtureEntry(`/api/blobs/${HASH}`), () => state.binding);
    expect(result.published).toBe(1);
    expect(trustedReads).toBe(1);
    expect(state.submitted).toHaveLength(1);
    expect(large.entry.parts[0]).toMatchObject({ kind: "text" });
  });

  test("turns malformed, external and invalid base64 addresses into a safe unavailable marker", async () => {
    let trustedReads = 0;
    const state = setup();
    const externalProducer = createJournalMediaProducer("omp", "18.4.10", state.port, async () => {
      trustedReads++;
      return null;
    })!;
    const external = await externalProducer.publish(fixtureEntry("https://outside.invalid/image.png"), () => state.binding);
    expect(external.published).toBe(0);
    expect(external.entry.parts[0]).toMatchObject({ kind: "text", text: expect.stringContaining("Image unavailable") });
    const badData = await state.producer.publish(fixtureEntry("data:image/png;base64,%%%"), () => state.binding);
    expect(badData.published).toBe(0);
    expect(badData.entry.parts[0]).toMatchObject({ kind: "text" });
    expect(state.submitted).toHaveLength(0);
    expect(trustedReads).toBe(0);
  });

  test("unknown vendors receive no journal producer", () => {
    expect(createJournalMediaProducer("gemini", null, setup().port, async () => null)).toBeNull();
  });
});
