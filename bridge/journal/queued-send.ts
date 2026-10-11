import { createHash } from "node:crypto";
import type { AgentView } from "../types.ts";
import type { MuxPane } from "../mux/types.ts";

/** Digest the ordered queue without putting any queued text on a write request. */
export function queuedMessagesDigest(queued: readonly string[]): string {
  return createHash("sha256").update(JSON.stringify(queued), "utf8").digest("hex");
}

/**
 * An opaque owner-side identity for a pane occupant. `agentSession` is hashed rather than returned;
 * it may be an upstream path or session reference and is not a phone-facing field.
 */
export function queuedSendOccupant(
  session: string,
  pane: AgentView | MuxPane,
  origin: string,
): string {
  const identity = [
    origin,
    session,
    pane.paneId,
    "workspaceId" in pane ? pane.workspaceId : pane.spaceId,
    pane.tabId,
    "kind" in pane ? pane.kind ?? "agent" : pane.agent === "shell" ? "shell" : "agent",
    pane.agent,
    pane.cwd,
    pane.agentSession === undefined ? null : [pane.agentSession.kind, pane.agentSession.value],
    pane.sessionAgent ?? null,
  ];
  return createHash("sha256").update(JSON.stringify(identity), "utf8").digest("hex");
}
