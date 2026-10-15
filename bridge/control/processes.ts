import { redactText } from "../redact.ts";
import type { MuxPaneProcessInfo, MuxOutcome } from "../mux/types.ts";
import { refusal, refusalResponse } from "./contracts.ts";
import { MAX_PROCESS_INFO_BODY_BYTES, MAX_PROCESS_INFO_TEXT_CHARS } from "../process-info-limits.ts";

export interface PaneProcessRuntime {
  readonly capabilities: { readonly supports: { readonly paneProcesses?: boolean } };
  readonly processInfo?: (paneId: string) => Promise<MuxOutcome<MuxPaneProcessInfo>>;
}

export interface PaneProcessSnapshot {
  current(): {
    readonly agents: readonly { readonly paneId: string }[];
    readonly shellPanes: readonly { readonly paneId: string }[];
  };
}

function ownsPane(engine: PaneProcessSnapshot, paneId: string): boolean {
  const snapshot = engine.current();
  return [...snapshot.agents, ...snapshot.shellPanes].some((candidate) => candidate.paneId === paneId);
}

/** Read process details from the pane's owning runtime, never by PID or from the lead's local mux. */
export async function servePaneProcesses(mux: PaneProcessRuntime, engine: PaneProcessSnapshot, paneId: string): Promise<Response> {
  if (!ownsPane(engine, paneId)) return refusalResponse(refusal("pane.not_found", "Pane not found", "scope"), 404);
  if (mux.capabilities.supports.paneProcesses !== true || !mux.processInfo) {
    return refusalResponse(refusal("capability.unsupported", "Process inspection is not supported by this runtime", "capability"), 501);
  }
  const result = await mux.processInfo(paneId);
  // The mux adapter fences terminal occupants; re-check the engine's owning pane
  // set too, so a pane removed while its RPC was in flight cannot leak a late result.
  if (!ownsPane(engine, paneId)) return refusalResponse(refusal("pane.not_found", "Pane changed or is no longer available", "scope"), 404);
  if (!result.ok) {
    if (result.reason === "unsupported") return refusalResponse(refusal("capability.unsupported", "Process inspection is not supported by this runtime", "capability"), 501);
    if (result.reason === "gone") return refusalResponse(refusal("pane.not_found", "Pane changed or is no longer available", "scope"), 404);
    if (result.reason === "refused") return refusalResponse(refusal("process.refused", "Runtime refused process inspection", "operation"), 409);
    return refusalResponse(refusal("process.unavailable", "Runtime process information is temporarily unavailable", "operation"), 503);
  }
  if (result.value.paneId !== paneId) return refusalResponse(refusal("process.identity_mismatch", "Runtime returned a different pane identity", "scope"), 409);
  // Process arguments and paths are always sensitive, even if terminal-content redaction is off.
  // Bound all string data at the HTTP boundary too: snapshot metadata and future adapters must not
  // bypass the process adapter's budget. The unit is UTF-16 code units, so the JSON remains finite
  // even for multi-byte paths and arguments.
  let remaining = MAX_PROCESS_INFO_TEXT_CHARS;
  let truncated = result.value.truncated || result.value.processes.length > 64;
  const redact = (value: string, fieldLimit: number): string => {
    const masked = redactText(value);
    const length = Math.min(masked.length, fieldLimit, remaining);
    if (length < masked.length) truncated = true;
    remaining -= length;
    return masked.slice(0, length);
  };
  const body = {
    ok: true,
    paneId,
    shellPid: result.value.shellPid,
    foregroundProcessGroupId: result.value.foregroundProcessGroupId,
    foregroundCwd: result.value.foregroundCwd === undefined ? undefined : redact(result.value.foregroundCwd, 4096),
    restoreError: result.value.restoreError === undefined ? undefined : redact(result.value.restoreError, 4096),
    tty: result.value.tty === undefined ? undefined : redact(result.value.tty, 4096),
    processes: result.value.processes.slice(0, 64).map((process) => ({
      pid: process.pid,
      name: redact(process.name, 256),
      argv: process.argv?.slice(0, 64).map((argument) => redact(argument, 2048)),
      argv0: process.argv0 === undefined ? undefined : redact(process.argv0, 2048),
      cmdline: process.cmdline === undefined ? undefined : redact(process.cmdline, 8192),
      cwd: process.cwd === undefined ? undefined : redact(process.cwd, 4096),
    })),
    truncated,
  };
  const serialized = JSON.stringify(body);
  if (new TextEncoder().encode(serialized).byteLength > MAX_PROCESS_INFO_BODY_BYTES) {
    return refusalResponse(refusal("process.too_large", "Process information exceeded its response limit", "operation"), 413);
  }
  return new Response(serialized, { headers: { "cache-control": "no-store", "content-type": "application/json; charset=utf-8" } });
}
