# 0103: Owner-scoped worktree removal is separate from lead-local creation

- **Status:** Accepted
- **Date:** 2026-10-11
- **Shipped in:** pending
- **Relates to:** [ADR 0032](./0032-a-worktree-is-opened-by-the-multiplexer-not-by-git.md) (the owning mux retains checkout lifecycle), [ADR 0089](./0089-a-second-agent-on-a-branch.md) (branch creation and receipts), and [ADR 0093](./0093-a-branch-folder-is-named-by-its-parent.md) (lead-local planning and safe parent selection).
- **Trail:** B15's copied C11 requirement says worktree plan/create/list/open stay lead-local; copied H08 requires owner-host removal and Crew. The B15 resolution preserves the former and adds only an explicit neutral owner-scoped removal route: `bridge/control/worktree-remove.ts`, `bridge/crew/forward.ts`, `CREW_PROTOCOL.md` §5, `MUX_CONTRACT.md`, and native New/workspace controls.

## Context

The existing folder planner and create/list/open routes are lead-local. They use the lead's
filesystem and Herdr runtime and must not begin forwarding merely because New lets a user choose a
machine or because ordinary agent launch has a Crew route. Removal has a different target: an open
workspace and linked checkout owned by a particular runtime. Sending its removal to the lead would
remove the wrong tree; inventing a remote path or forwarding a Herdr RPC would widen the authority
of the Crew wire.

Herdr 0.9.3 exposes `worktree.remove` with only `workspace_id` and `force`. It does not accept an
expected path, branch, status fingerprint, pane identity, or compare-and-set token. Therefore a
bridge can inspect exact current owner state and recheck it immediately before dispatch, but cannot
claim that those preconditions are atomic with Herdr's action. The final source checks narrow the observable interval; the vendor interval remains an acceptance limitation.

## Decision

Keep existing folder plan/create/list/open routes lead-local. Add a separately named owner-runtime
read plan and removal write. Crew may forward only these neutral scoped Collie routes, using the
addressed peer's own mux, device admission, paired identity and durable operation store. No generic
launch fallback, caller-selected filesystem path, raw Herdr method or trust option is permitted.

The owner derives the linked checkout only from its snapshot and worktree listing. It binds a short-
lived plan to canonical repo and checkout paths, branch, bounded Git status evidence and pane plus
terminal identities and the authenticated owning runtime incarnation. It repeats discovery and those reads after durable reservation and adapter preflight, then checks the original plan expiry, paired owner, current device/write policy and runtime witness synchronously before the single named remove call. The main
checkout is refused. A clean checkout is the default. Dirty or untracked entries require a distinct
force plan and a product confirmation that names the possible loss. The bridge never deletes a
branch. A lost or mismatched response stays unknown in a durable owner-bound operation receipt;
same-ID reconciliation returns that record without a second remove call. Known owner refusal codes remain refused receipts; transport loss and malformed or mismatched success remain unknown. Each pending removal reserves 32 KiB of serialized completion space within the unchanged 4 MiB registry cap.

The status, pane, path and branch checks are honest best-effort preconditions, not atomic locks.
Herdr removal may independently refuse when it detects a busy or untrusted checkout; Collie
propagates that refusal and never enables a trust flag. If Herdr later provides a documented
conditional removal primitive, the owner may bind those checks atomically and revise the contract.

## Consequences

- Existing local planning and open/reopen behavior keep their route and compatibility semantics.
- A Crew member can remove only a linked checkout discovered by that same member, with its own
  authorization and operation receipts; an older or unsupported peer refuses explicitly.
- Pane identity changes, changed status and checkout substitution are caught by a fresh owner read,
  while the remaining interval before vendor dispatch is documented as a race because the vendor
  API has no CAS field.
- A successful remove closes the owner workspace and removes its checkout while leaving the branch
  in Git; actual vendor refusal, pane closure and platform-specific behavior remain acceptance work.
