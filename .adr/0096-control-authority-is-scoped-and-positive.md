# 0096 — Control authority is scoped and positive

Amended in scope by 0104.

Trail: [B01 foundation brief](../android/acceptance/capability-work-items/B01.md), A00/C01/H01 rejects carrying legacy absent-capability support into new workflows, interpreting proxy prose as pairing revocation, and replaying ambiguous writes. Reaches native transport/repository/screens, Crew forwarding and the control receipt store.

## Context

Existing mux clients preserve compatibility when old servers omit legacy capability fields. New operations cannot use that fallback: the lead's capabilities do not describe a peer, and a server name does not prove an administration interface exists. The same HTTPS origin can also answer after a Crew takeover. A delayed successful snapshot can otherwise grant authority to a replacement credential or target.

A POST timeout proves neither failure nor success. Replaying a structural action can affect a changed occupant. A proxy can return the same 401/403 status and prose as a paired-device refusal, so prose alone cannot justify destroying an encrypted bearer.

## Decision

Use one native repository to capture origin including mount, credential generation, lead incarnation, host, session and subject. Invalidate protected actors when authority changes; discard old results before they reach a screen or cache. Admit writes only from a current live snapshot of that exact scope. Saved copies and unknown access never grant write authority.

Require positive declarations for new control capabilities, independently negotiated in Crew hello. Keep host management behind a separate typed port with explicit unsupported outcomes. Its negative declarations do not advertise future handlers.

Classify refresh POST and operation-receipt GET as reads at both lead and peer. Bind receipts to originating paired-device identity, canonical owning host/session, capability, canonical payload hash, occupant evidence and optional plan. Reserve durably before dispatch; a pending receipt after restart becomes unknown and is never dispatched again. Revalidate a plan immediately before its single dispatch. Bound every reservation including completion headroom so persistence remains reloadable.

Retire pairing only for declared Collie source, pairing factor and exact pairing code. Gateway and unknown access failures freeze actions while retaining a still-valid pairing. Configured gateways must strip spoofed upstream provenance; that identity integration remains R02's responsibility.

## Consequences

New features stay unavailable on older peers until positively negotiated. Older refresh 404s produce a labeled ordinary snapshot reload. The new control GET receipt endpoint does not replace legacy launch/worktree/add POST replay receipts. Source fixtures and fake-vendor TLS tests validate the contracts but do not establish live Herdr, device or joint B02/B03 acceptance.

New receipt ownership uses an opaque validated pairing incarnation rather than a mutable label. The primitive seam fails closed and declares negative capability until the shared local/Crew identity producer is integrated. Native saved attachment custody binds a local credential fingerprint plus host/session/pane, so same-origin credential replacement cannot restore an old attachment.
