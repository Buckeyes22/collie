# 0097 — Agent lifecycle reports require occupant compare-and-set

Status: Proposed — implementation and live acceptance pending.

## Context

The native app needs to inspect the agent currently associated with a pane, the session reference reported for that agent, readiness and sequence diagnostics. A pane may be reused for another agent while a request or saved screen is in flight. A write based only on a read immediately before dispatch still races with an external hook that changes the occupant between those calls.

Herdr 0.9.3 exposes `pane.get`, `agent.get`, `pane.report_agent`, `pane.report_agent_session`, `pane.release_agent`, and `pane.clear_agent_authority`. The report handlers accept a pane id, source, and report data; they do not accept the previously observed agent, terminal occupant id, lifecycle sequence or expected authority source as an atomic condition. A source-scoped clear can avoid clearing other sources, but it does not make a preceding validation atomic with a later report. Omitted-source clear is global.

## Decision

Expose diagnostics only from the owning scoped runtime. Bound each `pane.get` witness and `agent.get` reply; require the exact pane id plus a nonempty terminal occupant and matching agent/status/session before and after the join. Preserve uint64 sequence values as exact decimal strings. Session provenance remains distinct from state authority. If the runtime does not report the authority source, encode it as unknown; never infer it from `agent`, `session.source`, or a hook name. Normalize controls and redact the full bounded owner text before clipping. Diagnostics for identity and lifecycle routes must not consume either body.

Never dispatch lifecycle state, session, resume, release or authority-clear mutations unless the owning runtime provides an atomic expected-occupant / expected-sequence precondition and source-scoped clear semantics. Keep the new mutation capability false for Herdr 0.9.3. Operator-registered custom sources and executable/session-reference allowlists are exact, bounded and must never impersonate built-in hook sources. Clear always names a registered source; global clear is not exposed.

## Consequences

The native identity screen can report owner-supplied facts and explains why lifecycle controls are unavailable. The bridge validates proposed mutation intent and reports a typed refusal without sending it to an upstream handler. H11 lifecycle mutation and resume acceptance remain open until a compatible atomic upstream contract or separately reviewed adapter is available. Fixture-level validation is not evidence of a live custom reporter or resumed session.
