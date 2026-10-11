import { muxDataFields, muxRefused, type MuxAdapter, type MuxOutcome } from "../mux/types.ts";

/** A closed, named port decorator. Every write enters the captured caller's final synchronous
 * admission after route/body/prompt preflight, immediately before its one adapter invocation.
 * This does not claim an upstream atomic compare-and-send primitive. */
export function withNativeFinalAdmission(adapter: MuxAdapter, admitted: () => boolean): MuxAdapter {
  const effect = <T>(invoke: () => Promise<MuxOutcome<T>>): Promise<MuxOutcome<T>> =>
    admitted() ? invoke() : Promise.resolve(muxRefused("Native caller authority changed before dispatch"));
  const wrapped: MuxAdapter = {
    ...muxDataFields(adapter), mux: adapter.mux, capabilities: adapter.capabilities,
    reachable: () => adapter.reachable(), snapshot: () => adapter.snapshot(), refresh: () => adapter.refresh(),
    readGrid: (pane, request) => adapter.readGrid(pane, request),
    typeText: (pane, value) => effect(() => adapter.typeText(pane, value)),
    sendKeys: (pane, keys) => effect(() => adapter.sendKeys(pane, keys)),
    renamePane: (pane, label) => effect(() => adapter.renamePane(pane, label)),
    closePane: (pane) => effect(() => adapter.closePane(pane)),
    setFocus: (pane) => effect(() => adapter.setFocus(pane)),
    createTab: (request) => effect(() => adapter.createTab(request)),
    renameTab: (tab, label) => effect(() => adapter.renameTab(tab, label)),
    closeTab: (tab) => effect(() => adapter.closeTab(tab)),
    createSpace: (request) => effect(() => adapter.createSpace(request)),
    listWorktrees: (scope) => adapter.listWorktrees(scope),
    createWorktree: (request) => effect(() => adapter.createWorktree(request)),
    openWorktree: (request) => effect(() => adapter.openWorktree(request)),
    listSessions: () => adapter.listSessions(), watch: (options) => adapter.watch(options),
  };
  if (adapter.readLogicalText) wrapped.readLogicalText = adapter.readLogicalText.bind(adapter);
  if (adapter.processInfo) wrapped.processInfo = adapter.processInfo.bind(adapter);
  if (adapter.readLayout) wrapped.readLayout = adapter.readLayout.bind(adapter);
  if (adapter.layoutRuntimeIdentity) wrapped.layoutRuntimeIdentity = adapter.layoutRuntimeIdentity.bind(adapter);
  const applyLayout = adapter.applyLayout?.bind(adapter);
  if (applyLayout) wrapped.applyLayout = (action, finalAdmission) => effect(() => applyLayout(action,
    (prepared, identity, evidence) => admitted() && finalAdmission(prepared, identity, evidence)));
  return wrapped;
}
