# 0104: Named agent delivery and observation

- **Status:** Proposed for independent source review
- **Date:** 2026-10-12
- **Trail:** B19 H10 implementation brief and the orchestrator's October 12 settlement rejecting bridge locks, unique aliases and HMAC witnesses as proof of stock Herdr compare-and-set. `bridge/control/agents.ts`, `bridge/control/waits.ts`, `bridge/mux/herdr/agent-control.ts`, `bridge/mux/herdr/client.ts`, `bridge/dial.ts`, native `AgentAutomationViewModel` and `AgentAutomationCustody`.
- **Relates to:** [ADR 0010](./0010-long-sends-are-verified-via-the-paste-placeholder.md), [ADR 0011](./0011-the-pack-protocol-is-the-mux-driver-seam.md).

## Context

Herdr v0.9.3 names agent start, prompt and observation methods. A submitted prompt is not a settled prompt: a prompt wait must first see working or a later activity sequence. The stock resolver looks up a public pane or agent name inside the request handler. It has no supported expected-terminal compare-and-set parameter. An occupant can change after Collie's final preflight but before that lookup. Retrying after a lost reply can deliver twice.

## Decision

Capture the original host, runtime, public pane, terminal, agent, session and foreground-process witness. Resolve an alias before this capture. Carry the original caller predicate through read/preparation waits, actual dial, the first socket write and every drain. The bridge HMAC authenticates custody of these facts; it supplies no vendor atomicity.

Use one immutable action ID and body. Persist its pending receipt before dispatch. A known zero-byte refusal or the vendor's documented pre-effect refusal can become refused. Any accepted prefix, ambiguous transport loss, possible delivered vendor failure or missing original postcondition remains unknown. Reconcile the exact retained ID; never recreate or resend it. Native custody encrypts only opaque IDs, scope/witness hashes and outcomes, not prompts, arguments, output or credentials. Retirement fences the captured original version even before its first reservation. A successor's current custody remains intact.

Observe lifecycle, bounded output and a closed event set separately from delivery. General emitted conditions use the official bounded subscription stream; output change compares a bounded visible window because stock pane reads return revision zero. An unchanged baseline never counts as new output. A finite lease, bounded active/retained counts and an original-occupant monitor close observation sockets. Cancelling stops observation; it cannot undo a prompt. Server restart interrupts observations rather than recreating them. Native recreation resumes GET of the same opaque ID under the same durable owner and occupant witness, without restarting a wait or delivering a prompt.

## Consequences

Existing guarded reply and agent identity diagnostics keep their contracts. New methods require positive owning-runtime capabilities and paired read or both write factors, locally and through Crew. Supported transport and native consumers require independent review and actual validation. Physical Pixel, local/Crew stock-vendor workflows, current harness semantics and joint B18/H11 guarantees remain acceptance work.

The stock pre-dispatch resolver race remains a stock limitation. The accompanying owned source fork implements the negotiated original-terminal logical guard and captured live transition provenance. It remains uncompiled and requires independent whole review, actual runtime acceptance and canonical joint integration. A late replacement cannot prove completion; Collie returns unknown. It cannot prove that the vendor sent no bytes to the replacement in that gap. Closed events that remove their original anchor likewise need authoritative producer evidence before a stale observation may claim completion. Refusal, an HMAC or a controlled protocol fixture does not close either requirement.


The extension contract and bounds are documented in [Native agent automation](../android/AGENT_AUTOMATION.md#original-terminal-producer-extension). App serialization and observed foreground-group checks around each write satisfy the bounded logical custody design; no kernel-atomic foreground primitive is introduced or claimed.
