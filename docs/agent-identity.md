# Agent identity and lifecycle authority

The pane Actions menu opens **Agent identity** for a bounded, device-authorized read from the
runtime that owns the pane. Herdr-backed details join `agent.get(target = paneId)` to `pane.get` by the exact pane
identifier and a nonempty terminal occupant, with bounded reply sizes and matching agent/status/session
facts before and after the join. Session provenance is displayed separately from lifecycle authority.
Sequence values are preserved as exact decimal uint64 strings. Herdr 0.9.3 does not expose the current
authority source, so the screen says that it is unknown rather than treating a session source or agent
name as proof. Identity and lifecycle-route diagnostics record request metadata only and never read
their request or response bodies. The screen clears copied identity when its authority retires or it
stops, distinguishes refusal classes, and offers a deliberate current-context refresh.

Lifecycle requests are accepted only for an explicit operator-registered source under the bridge's
state directory. The optional `agent-reporters.json` file is a bounded JSON document:

```json
{
  "schema": 1,
  "sources": [
    {
      "source": "collie.custom.example",
      "resumeExecutables": ["codex"],
      "sessionReferences": [
        { "agent": "codex", "kind": "id", "value": "an-exact-session-id" }
      ]
    }
  ]
}
```

Custom source names must use the `collie.custom.` namespace. Built-in hook identities cannot be
registered. Session references are exact values, not paths or prefixes; resume executables are
plain PATH names and resume arguments are bounded argv values, never shell text. A clear request
must identify its registered source and cannot clear all authorities.

These checks do not make an upstream write safe by themselves. Herdr 0.9.3's report and session
handlers have no atomic expected-occupant/sequence precondition. A preceding pane read cannot close
the race with an external hook replacing the pane. Collie therefore advertises
`agentLifecycleWrites: false` for Herdr, validates a proposed intent, and returns a typed refusal
without sending it. State/session mutation, release and resume acceptance remain open until the
upstream protocol can compare-and-set the current occupant atomically. A fixture validates the local
contract; it is not live vendor acceptance.
