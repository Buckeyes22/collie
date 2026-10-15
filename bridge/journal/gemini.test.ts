import { describe, expect, test } from "bun:test";
import { appendFile, mkdir, mkdtemp, realpath, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import type { JsonValue } from "../json.ts";
import { geminiJournal, isGeminiSessionId, parseGeminiTranscript } from "./gemini.ts";
import { LiveWindows, SEQ_BASE, TICK_FLOOR_MS } from "./live.ts";
import { TranscriptStore } from "./store.ts";

const SESSION = "a1b2c3d4-e5f6-4789-abcd-0123456789ab";
const OTHER = "a1b2c3d4-aaaa-4789-abcd-0123456789ab";
const row = (value: Record<string, JsonValue>) => JSON.stringify(value);

describe("Gemini CLI JSONL journal", () => {
  test("accepts only canonical session identifiers", () => {
    expect(isGeminiSessionId(SESSION)).toBe(true);
    expect(isGeminiSessionId("../../session.jsonl")).toBe(false);
    expect(isGeminiSessionId(SESSION.slice(0, 8))).toBe(false);
  });

  test("reads the documented metadata and message record forms without leaking inline media", () => {
    const entries = parseGeminiTranscript([
      row({ sessionId: SESSION, projectHash: "fixture-project", startTime: "2026-10-01T00:00:00Z" }),
      row({ id: "user-1", timestamp: "2026-10-01T00:00:01Z", type: "user", content: [{ text: "hello" }] }),
      row({
        id: "assistant-1", timestamp: "2026-10-01T00:00:02Z", type: "gemini",
        content: [{ text: "Hi there." }, { inlineData: { mimeType: "image/png", data: "secret-base64" } }],
        thoughts: [{ subject: "Plan", description: "Answer briefly.", timestamp: "2026-10-01T00:00:02Z" }],
        toolCalls: [{ id: "call-1", name: "read_file", args: { path: "src/a.ts" }, result: [{ text: "permission denied" }], status: "error", timestamp: "2026-10-01T00:00:02Z" }],
      }),
    ].join("\n"));

    expect(entries).toHaveLength(2);
    expect(entries[0]).toMatchObject({ uuid: "user-1", role: "user", parts: [{ kind: "text", text: "hello" }] });
    expect(entries[1]?.parts).toEqual([
      { kind: "text", text: "Hi there." },
      { kind: "thinking", text: "Plan\nAnswer briefly." },
      {
        kind: "tool", id: "call-1", name: "read_file", summary: "src/a.ts",
        call: { kind: "read", path: "src/a.ts" },
        result: { text: "permission denied", isError: true },
      },
    ]);
    expect(JSON.stringify(entries)).not.toContain("secret-base64");
  });

  test("keeps the documented info, error and warning records distinct from spoken turns", () => {
    const entries = parseGeminiTranscript([
      row({ id: "i1", timestamp: "t1", type: "info", content: "session restored" }),
      row({ id: "e1", timestamp: "t2", type: "error", content: "tool failed" }),
      row({ id: "w1", timestamp: "t3", type: "warning", content: "recording is limited" }),
    ].join("\n"));
    expect(entries.map((entry) => [entry.uuid, entry.role, entry.parts[0]])).toEqual([
      ["i1", "note", { kind: "text", text: "Info: session restored" }],
      ["e1", "note", { kind: "text", text: "Error: tool failed" }],
      ["w1", "note", { kind: "text", text: "Warning: recording is limited" }],
    ]);
  });

  test("applies append-only message patches and rewind identity to the live reducer", () => {
    const entries = parseGeminiTranscript([
      row({ id: "u1", timestamp: "t1", type: "user", content: "hello" }),
      row({ id: "a1", timestamp: "t2", type: "gemini", content: "partial" }),
      row({ $patch: { id: "a1", content: "complete" } }),
      row({ id: "u2", timestamp: "t3", type: "user", content: "discard this branch" }),
      row({ $rewindTo: "u2" }),
    ].join("\n"));
    expect(entries.map((entry) => entry.uuid)).toEqual(["u1", "a1"]);
    expect(entries[1]?.parts).toEqual([{ kind: "text", text: "complete" }]);
  });

  test("a null tool-result patch clears a previously recorded result", () => {
    const entries = parseGeminiTranscript([
      row({ id: "a1", timestamp: "t", type: "gemini", content: "", toolCalls: [
        { id: "c1", name: "read_file", args: {}, result: [{ text: "old result" }], status: "success" },
      ] }),
      row({ $patch: { updates: [{ id: "a1", toolCalls: [{ id: "c1", result: null }] }] } }),
    ].join("\n"));
    const tool = entries[0]?.parts[0];
    expect(tool?.kind).toBe("tool");
    if (tool?.kind === "tool") expect(tool.result).toBeUndefined();
  });

  test("applies bounded orderIds and removeIds as ordered history replacement", () => {
    const entries = parseGeminiTranscript([
      row({ id: "u1", timestamp: "t1", type: "user", content: "first" }),
      row({ id: "a1", timestamp: "t2", type: "gemini", content: "second" }),
      row({ id: "u2", timestamp: "t3", type: "user", content: "third" }),
      row({ $patch: { orderIds: ["u2", "u1", "a1", "missing", "u2"] } }),
      row({ $patch: { removeIds: ["u1"] } }),
    ].join("\n"));
    expect(entries.map((entry) => entry.uuid)).toEqual(["u2", "a1"]);
  });

  test("resolves full UUIDs under project chats and subagent chats, not a short-ID collision or symlink escape", async () => {
    const base = await realpath(await mkdtemp(join(tmpdir(), "collie-gemini-journal-")));
    try {
      const chats = join(base, "project-opaque", "chats");
      const nestedChats = join(chats, "parent-session");
      const outside = join(base, "outside");
      await mkdir(chats, { recursive: true });
      await mkdir(nestedChats, { recursive: true });
      await mkdir(outside, { recursive: true });

      const good = join(chats, `session-2026-10-01T00-00-${SESSION.slice(0, 8)}.jsonl`);
      const collision = join(chats, `session-2026-10-01T00-01-${OTHER.slice(0, 8)}.jsonl`);
      const nested = join(nestedChats, `${SESSION}.jsonl`);
      const escape = join(chats, `session-2026-10-01T00-02-${OTHER.slice(0, 8)}.jsonl`);
      await Bun.write(good, `${row({ sessionId: SESSION, projectHash: "p" })}\n`);
      await Bun.write(collision, `${row({ sessionId: OTHER, projectHash: "p" })}\n`);
      await Bun.write(nested, `${row({ sessionId: SESSION, projectHash: "p", kind: "subagent" })}\n`);
      const outsidePath = join(outside, "escape.jsonl");
      await Bun.write(outsidePath, `${row({ sessionId: OTHER, projectHash: "p" })}\n`);
      await symlink(outsidePath, escape);

      const source = geminiJournal([base]).source;
      const resolved = await source.resolve({ kind: "id", value: SESSION });
      expect(resolved).not.toBeNull();
      expect([good, nested]).toContain(resolved!);
      expect(await source.resolve({ kind: "id", value: "../../outside" })).toBeNull();
      expect(await source.resolve({ kind: "path", value: good })).toBeNull();

      const escapeBase = join(base, "escape-root");
      const escapeChats = join(escapeBase, "project", "chats");
      await mkdir(escapeChats, { recursive: true });
      const outsideSameId = join(outside, "same-session.jsonl");
      await Bun.write(outsideSameId, `${row({ sessionId: SESSION, projectHash: "p" })}\n`);
      const escapedCandidate = join(escapeChats, `session-2026-10-01T00-02-${SESSION.slice(0, 8)}.jsonl`);
      await symlink(outsideSameId, escapedCandidate);
      expect(await geminiJournal([escapeBase]).source.resolve({ kind: "id", value: SESSION })).toBeNull();
    } finally {
      await rm(base, { recursive: true, force: true });
    }
  });

  test("does not parse an oversized single JSONL row", () => {
    const oversized = `{"id":"huge","type":"user","content":"${"x".repeat(2 * 1024 * 1024)}"}`;
    expect(parseGeminiTranscript(oversized)).toEqual([]);
  });

  test("caps retained messages and replaces the history snapshot when the oldest turn rolls off", () => {
    const rows = Array.from({ length: 10_001 }, (_, index) => row({
      id: `m${index}`,
      timestamp: `t${index}`,
      type: "user",
      content: `message ${index}`,
    }));
    const entries = parseGeminiTranscript(rows.join("\n"));
    expect(entries).toHaveLength(10_000);
    expect(entries[0]?.uuid).toBe("m1");
    expect(entries.at(-1)?.uuid).toBe("m10000");
  });

  test("live order and deletion replacement starts a new safe sequence generation", async () => {
    const base = await realpath(await mkdtemp(join(tmpdir(), "collie-gemini-live-")));
    let now = 100_000;
    try {
      const chats = join(base, "project", "chats");
      await mkdir(chats, { recursive: true });
      const path = join(chats, `session-2026-10-01T00-00-${SESSION.slice(0, 8)}.jsonl`);
      await Bun.write(path, [
        row({ sessionId: SESSION, projectHash: "p" }),
        row({ id: "u1", timestamp: "t1", type: "user", content: "first" }),
        row({ id: "a1", timestamp: "t2", type: "gemini", content: "second" }),
        row({ id: "u2", timestamp: "t3", type: "user", content: "third" }),
      ].join("\n") + "\n");
      const live = new LiveWindows(new TranscriptStore(), () => now);
      const adapter = geminiJournal([base]);
      const ref = { kind: "id" as const, value: SESSION };
      const first = await live.window(adapter, ref, { limit: 10 });
      expect(first?.upserts.map((entry) => [entry.uuid, entry.seq])).toEqual([
        ["u1", SEQ_BASE], ["a1", SEQ_BASE + 1], ["u2", SEQ_BASE + 2],
      ]);

      await appendFile(path, row({ $patch: { orderIds: ["u2", "u1", "a1"] } }) + "\n");
      now += TICK_FLOOR_MS + 1;
      const reordered = await live.window(adapter, ref, {
        limit: 10,
        after: { gen: first!.gen, rev: first!.rev },
      });
      expect(reordered?.gen).not.toBe(first?.gen);
      expect(reordered?.upserts.map((entry) => [entry.uuid, entry.seq])).toEqual([
        ["u2", SEQ_BASE], ["u1", SEQ_BASE + 1], ["a1", SEQ_BASE + 2],
      ]);

      await appendFile(path, row({ $patch: { removeIds: ["u1"] } }) + "\n");
      now += TICK_FLOOR_MS + 1;
      const removed = await live.window(adapter, ref, {
        limit: 10,
        after: { gen: reordered!.gen, rev: reordered!.rev },
      });
      expect(removed?.gen).not.toBe(reordered?.gen);
      expect(removed?.upserts.map((entry) => [entry.uuid, entry.seq])).toEqual([
        ["u2", SEQ_BASE], ["a1", SEQ_BASE + 1],
      ]);
    } finally {
      await rm(base, { recursive: true, force: true });
    }
  });
});
