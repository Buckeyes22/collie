# 0105 — Offline content is encrypted display custody

Status: Proposed implementation; independent review, current native gates and physical Pixel acceptance pending.

Trail: [B25 C24/A14](../android/acceptance/capability-work-items/B25.md), [scoped authority](0096-control-authority-is-scoped-and-positive.md), and the [native client](../android/README.md#offline-saved-copies-and-drafts).

## Context

The existing native pane and transcript caches disappear with the process. A retained read must not
become a live prompt, a capability declaration, an image actor or permission to dispatch. The
baseline draft implementation is plaintext SharedPreferences without an independent consent
switch, contrary to the planning assumption that drafts were already encrypted and opted in.
Installing this change must not silently discard those existing user drafts.

## Decision

Use one application-scoped `OfflineContentStore` facade and a private `RetainedReadDatabase` under
`noBackupFilesDir`. Read retention defaults Off. A deliberate choice enables a one-day or seven-day
Chat tail; Dashboard and terminal mirrors expire within 24 hours. Changing that choice clears the
previous read cache. Draft retention is a separate, default-Off choice with a 48-hour limit and the
existing 8,192-character ceiling. Off still permits volatile editing before an accepted read. A local form witness captures the original
connection reference, committed incarnation and pane address without producing a read actor or
remote permission; an empty connection slot uses process/revision identity. It survives recreation
and harmless same-instance recommits, while changed credentials and selection reject late writes.
Off still permits volatile editing; enabling reads never enables
drafts. Retained files, images, uploads, audio, prompt bindings and queued-send guards are excluded.

AES-256-GCM uses a dedicated non-exportable Android Keystore key, a random 12-byte IV and 128-bit tag.
The schema-2 encrypted envelope binds row key, original owner, durable retirement epoch, target,
kind, capture and expiry times, legal maximum age and exact UTF-8 payload length. Read compares
each authenticated field to its SQL row and validates actual IV/ciphertext accounting from length-only
queries before selecting BLOBs. Old or unknown schemas retire only that cache record. SQLite
stores only hashes, kind, time, byte accounting and ciphertext. The owner derives from the exact
normalized HTTPS origin including mount and committed credential incarnation; targets include
host, session, pane and Dashboard breadth. Ordinary delimiter-free local draft tuples retain their
existing raw key. A tuple containing `|`, or distinguished by viewAll, uses a disjoint versioned
length-framed key; both RAM retrieval and authenticated SQL draft reads also compare the exact
requested target. Legacy lookup/migration remains separate and never guesses ambiguous raw tuples.
A different credential, origin, mount or target cannot
borrow a copy. Display DTOs retain bounded masked text and minimum topology, strip cwd, credentials,
device/control capabilities, raw tool calls, images and media URLs, and preserve admitted opaque IDs
without shortening them into collisions.

Count the IV and ciphertext toward the 256 KiB combined pane/Chat ceiling and 10 MiB read ceiling.
Bound read rows to 512, draft rows to 64 and draft ciphertext to 1 MiB total. Access updates the LRU
clock, while capture/expiry remain original. Every access prunes expiry and quota. Clock rollback
clears content. There are at most 8,192 bounded retirement metadata entries; exhaustion refuses
storage rather than evicting a tombstone or guessing that old content is safe. Every new metadata
key reserves a slot inside the same SQL transaction; existing-key updates remain possible. A
retirement requiring more slots rolls back and keeps its fsynced deny intent for every later access. Storage/key/schema
failure affects this cache, never pairing or cosmetic preferences. Encrypted row replay after an
acknowledged clear fails against the retained epoch; no claim of hardware anti-rollback against a
privileged replacement of the entire app-private database is made.

Before destructive deletion, fsync an AtomicFile wipe intent. A transaction increments the affected
epoch, deletes rows and commits policy. Delete the intent only after that commit. Startup consumes
base/backup intents before any read; directory enumeration must succeed before any SQL open or transaction,
including direct wipes. A null listing retains the fsynced intent and denies access; an interrupted policy change closes that policy. Failed
persistence remains unavailable and is disclosed; it is never reported as successful absence.
Every save captures an epoch and rechecks both that epoch and the original actor after encryption.
Password/no-echo detection retires that target's read and draft rows and persistently blocks Chat-only
saves. The same facade retires exact-owner/pane RAM drafts and copied editor content even without
an active Activity, with revision-cutoff Main ACK outside every lock. Failed deletion remains
unavailable; a durable legacy-target retirement flag prevents the old plaintext source from returning
on a later clean read. Only an accepted clean mirror of that exact current target can reopen future
ordinary RAM or encrypted writes, independently of read-retention consent; Chat cannot reopen it.

Saved-copy selection is a local committed-owner value, never `ScopedReadContext`. Cold Dashboard,
Pane and Chat use it only for read-only display with capture age, expiry, origin, host and session.
They have no current file/image actor, writable prompt, capability or queued-send action. The pane ignores only the unchanged initial cache-change replay. Copied installation and live-read
start share a local completion lease, with no lock held across canonical callbacks/Main ACK; cache
IO itself does not delay a fresh GET. A waiting read retains its original owner/selection version,
and an active read or accepted pane body refuses implicit copied publication. Explicit failed-read
fallback must still own the original read lease. Mirror and Chat expiry erase only the exact copied
version in RAM before storage/network waits, preserve still-valid Chat, and never clear a fresh
accepted body or remint navigation under a held GET. Clear/lifecycle cancellation releases the
exact local installation lease outside locks; this is not a network retry.
Returning to live content requires a fresh accepted response; 304 cannot mint or revive an actor. Failed
reads preserve their typed refusal alongside the saved label. Bare 401/403 and gateway refusals
freeze actions while preserving pairing and owned ciphertext. Exact declared Collie pairing
expiry/revocation/unpairing, explicit disconnect and credential/origin replacement retire the
original owner. B22 provider retirement must join that same canonical event; this change does not
claim its not-yet-integrated delivery contract.

Local clear and genuine read-custody retirement callbacks wipe public state and copied Views even
while stopped. An ordinary transport/optional supporting failure that only freezes write admission
must keep an already opted, read-only saved display; its unchanged version alone is not retirement.
The same helper still acknowledges actual owner/scope/pairing retirement before the caller returns.
An unaffected copied version also keeps its pending asynchronous draw through a write-only freeze;
link/media/action authority remains disarmed. Actual affected read retirement cancels that pending
draw and its generation fence rejects late CPU completion. Text preparation uses the normal
`PrecomputedTextCompat.create` factory captured per render, with an instance-only dependency seam
for a held actual factory regression; no global factory or actor/UI-state injection is introduced. Main-thread ACK, cancellation and callbacks stay outside storage/admission locks. Revision
cutoffs and exact original owner/version checks prevent queued old cleanup from erasing a newer
copy or edit. The consumer rechecks copied custody after storage waits; no saved content enters a
Bundle or produces an original read/dispatch actor.

Preserve old plaintext drafts until an explicit migration or discard choice. Consent migrates only
the exact legacy key of the currently selected pane, with its original owner and full origin/mount,
host, session, subject and committed incarnation witness; ambiguous, hashed or unmatched old keys
remain untouched until an explicit discard choice. The encrypted replacement carries that pane
target, so headless password retirement also finds the migrated draft; delete a legacy source only after committed
encrypted replacement. Unknown legacy ownership remains for a separately confirmed discard. New
writes never use plaintext persistence. This is a bounded upgrade exception, not authorization to
continue writing plaintext or infer an owner from a display label.

## Consequences

The feature needs no new bridge or Crew route. It consumes existing admitted read DTOs and original
retirement events, and does not change launch/queued-send/structural intent tombstones. The current
backup/transfer exclusion covers every app-private domain and the new database additionally lives
in the no-backup directory. Hardware Keystore, process death, current physical Pixel, font scale,
TalkBack, real Crew and joined-provider retirement remain acceptance requirements. AES/GCM JVM
fixtures and mounted HTTPS tests do not substitute for those checks.
