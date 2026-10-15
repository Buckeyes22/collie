import type { JsonValue } from "../json.ts";
import type { MuxSnapshot, MuxLayoutAction } from "./types.ts";

/** Only effect-relevant metadata: transient grid/status revisions do not invalidate a layout. */
export function layoutSnapshotAuthority(snapshot: MuxSnapshot, action: MuxLayoutAction): JsonValue {
  const ids = [action.paneId];
  if (action.kind === "swap" || action.kind === "focus-direction") {
    const target = action.resolvedTargetPaneId ?? (action.kind === "swap" ? action.targetPaneId : null);
    if (target) ids.push(target);
  }
  if (action.kind === "move" && action.destination.type === "tab") ids.push(action.destination.targetPaneId);
  const panes = ids.map((id) => {
    const pane = snapshot.panes.find((candidate) => candidate.paneId === id);
    if (!pane) return null;
    return { id, incarnation: pane.terminalIncarnation ? `${pane.paneId}:${pane.terminalIncarnation}` : null, tabId: pane.tabId, workspaceId: pane.spaceId, cwd: pane.cwd,
      workspaceFolder: snapshot.spaces.find((space) => space.spaceId === pane.spaceId)?.folder ?? null };
  });
  const destinationWorkspace = action.kind === "move" && action.destination.type === "new_tab" ? action.destination.workspaceId : null;
  const workspace = destinationWorkspace ? snapshot.spaces.find((space) => space.spaceId === destinationWorkspace) : undefined;
  return { panes, newTabWorkspace: workspace ? { id: workspace.spaceId, folder: workspace.folder ?? null } : null };
}
