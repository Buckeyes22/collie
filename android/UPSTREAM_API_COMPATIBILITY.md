# Native Android API compatibility

The server foundation is synced to AltanS/collie `632a2bf784045b1e2576da112d89ed21fecc9eeb`
(Collie 1.19.0, 2026-10-09). Android keeps its independent Kotlin screens, APK versioning,
TLS verification, encrypted bearer storage, English-only UI and conservative terminal write gates.
The Codex composer audit also examines openai/codex
`c3d3b142d10f4316b46e35aad7e5317e7e506cb7` from a shallow clone fetched on 2026-10-10.

## Existing native calls

The audit covers every method in `CollieApi`; additive fields remain optional for mixed-version
servers. The bridge's `bridge/types.ts`, routes in `bridge/server.ts`, and PWA `web/src/lib/api.ts`
are the contract sources. Unknown JSON fields are ignored, but missing required response evidence
is refused. A mutation is attempted once; transport redirects and automatic write retries remain off.

| Contract | Native adaptation |
| --- | --- |
| `/api/health`, `/api/pair` | Health remains the open connectivity probe. Pairing request and response bodies stay excluded from diagnostics. |
| All other `/api/*` reads and writes | Send the paired bearer and `X-Requested-With`; only a typed authoritative Collie/pairing refusal with a known expired/revoked/unpaired code clears the credential and sensitive custody. Bare or proxy-origin 401/403 freezes writes and retains pairing. Upstream now requires pairing for reads too. |
| Mounted deployment URLs | Preserve the HTTPS mount when constructing routes. Write `Origin` names only scheme and authority. Reject ambiguous paths, embedded credentials, query strings and fragments. |
| `/api/config` | Request the pane or space's actual host/session. Decode upload limits, allowed extensions, redact and STT capabilities alongside operator commands, keys and replies. |
| `/api/snapshot`, `/api/refresh` | Retain session names from narrow requests because upstream omits their row tags. Widened snapshots retain the server's tags. Pane checks resolve the implicit local lead without remapping peers or explicit host requests. Decode folder, tab placement, sole-tab name, git/cache and model metadata. |
| `/api/pane/:id` | Preserve scoped ETags, exact requested line windows, seen headers and optional link text. |
| `/api/pane/:id/processes` | Fetch sensitive diagnostics only from a runtime that positively declares `paneProcesses`; older peers and unsupported adapters remain explicit failures. Redact before clipping; cap aggregate text at 220 Ki UTF-16 units and raw JSON at 1,000,000 UTF-8 bytes on the owner, Crew lead and Android reader. Android streams the body through the byte ceiling before JSON decoding. |
| `/api/pane/:id/reply`, `/keys` | Accept the current 32 KiB prompt-binding limit. Codex typing and submission remain separate, verified operations. An ambiguous delivery keeps the phone draft and never triggers an automatic resend. |
| `/api/pane/:id/chat` | Live polls use `after=gen:rev` and ETag; older reads use `before=seq:uuid` without ETag. Generation changes replace the thread. Whole-turn upserts replace partial text/results, abandoned turns are hidden, queued messages are displayed separately, stale older pages are discarded. Structured call details, question choices and answers are shown as inert transcript data; denials remain distinct from execution failures. A legacy 404 falls back to history. |
| `/api/pane/:id/history` | Existing full history/search remains supported with UUID pagination. History merging replaces updated turns. Structured calls and read-only historic question cards retain supported fields; malformed or unknown calls stay bounded text. The fork's conditional JSON response remains available for legacy polls. |
| `/api/blobs/:sha256` | Read only the exact mounted 64-hex Collie route with the transcript's captured host/session. Reject redirects and arbitrary origins; stream at most 10 MiB, accept sniffed PNG/JPEG/GIF/WebP raster bytes, cap source/decode at 16 million pixels, downsample to the card viewport, and cap total decoded residency at 24 MiB (8 MiB unpinned cache target). Authority retirement clears drawables before releasing their counted leases. |
| `/api/pane/:id/reply`, `/keys` | Accept the current 32 KiB prompt-binding limit. Codex typing and submission remain separate, verified operations. The guarded queued-send form requires the additive `guardedQueuedSend` mux declaration and carries only the declared keys, prompt expectation, generation/revision and opaque queue/occupant digests—never queued text. An ambiguous delivery stays latched and is never automatically retried. |
| `/api/pane/:id/chat` | Live polls use `after=gen:rev` and ETag; older reads use `before=seq:uuid` without ETag. Generation changes replace the thread. Whole-turn upserts replace partial text/results, abandoned turns are hidden, queued messages are displayed separately, and the optional Send now recipe is shown only when the owner supplies a current guarded action. Stale older pages are discarded. A legacy 404 falls back to history. |
| `/api/pane/:id/history` | Existing full history/search remains supported with UUID pagination. History merging replaces updated turns. The fork's conditional JSON response remains available for legacy polls. |
| `/api/pane/:id/changes`, `/api/workspace/:id/changes` | Native Changes uses only bridge-listed repositories and paths for status and diffs. It displays a bounded first-parent commit history in pages pinned to the initial HEAD, then reads the selected commit and its listed file diffs. Page size and the 10,000-row history window are checked at both ends; only a terminal cap page may claim truncation. Subject/author display bounds (500/1,024 characters) and a 1 MiB server page envelope compose with the unchanged 5 MiB native streaming ceiling; delayed results are discarded after scope changes. Older peers that lack the history response are reported as unsupported. This is a paired ordinary read and does not use Files' device-read capability. |
| `/api/{pane|workspace}/:id/files`, `/files/image`, `/files/exist` | Browse only the server-selected Files root with relative validated paths, a 2,000-entry cap and bounded source reads. Files and image reads use device-read admission; images are streamed to 16 MiB, sniffed and decoded through the shared actor-bound loader with a 16M-pixel limit. File image caching is disabled unless source and byte-response `size` plus exact `mtimeMs` agree. The existence POST carries at most 64 relative paths, stays device-read on Crew lead and peer, and never grants authority to read an unconfirmed path. |
| Pane/tab close, focus and rename; tab/workspace create | Existing route/body contracts remain compatible, with scoped identities and write authorization retained. |
| `/api/crew` | Use the renamed endpoint and `crew` field. A legacy 404 permits one `/api/pack` read fallback; a current `crew.not_lead` refusal does not. Legacy response aliases remain decodable. Native labels say Crew. |
| `/api/devices`, `/api/devices/revoke` | Decode expiry fields and retain the existing confirmed revocation flow. |
| `/api/launchers`, `/api/launch` | Decode harness discovery, richer launcher items, availability and recent runs. Existing command launchers remain supported. A legacy launch remains a one-shot initial request; ambiguous legacy outcomes do not gain a retry action. Owner-bound launch intents require the owning host's positive `receiptContextBound` declaration and retain the exact encrypted POST body/request ID for deliberate replay. Native confirmation is required for launchers marked `noPrompts`. |
| `/api/launch/check`, `/api/launch`, `/api/launch/recent/*`, `/api/launchers/added/*` | The native Recipes/Run screens use the existing read, run, history and phone-owned launcher routes. `adding.run`, `adding.adds` and `adding.freeText` must be positively declared; old peers fail closed for those actions. A syntax check does not probe PATH or run policy. Run and add requests retain their exact body/request ID in the B02 encrypted owner-bound journal; unknown results never trigger a new automatic request. Recent rows only fill the editor, and no-prompts options require acknowledgement frozen with the request. |
| `/api/folders`, `/api/folders/star` | Read recent and favourite folder strings from the selected machine/session. Star writes address a folder already supplied by that host; recent/favourite collections remain machine-owned and manual remote-folder entry remains available when an older bridge lacks these routes. |
| Workspace worktree list/create/open | Decode default branch, launcher result and replay metadata. Carry a request ID on create. Allow the server's longer worktree execution budget. Workspace creation uses the same frozen owner-bound intent and positive capability gate when available; old/mixed servers permit only the legacy initial attempt, with no same-ID recovery retry after uncertainty. |
| `/api/worktree/plan`, `/api/worktree` | Add scoped plan/create transport and repository methods. Encode `base.kind=default` or `ref` with its ref; encode `folder.kind=default` or `parent` with its parent. Folder worktree requests and retries preserve the exact caller-held request ID and frozen POST body, and deliberate replay is available only when the owning host positively declares bound receipts. |
| Pane upload and `/api/stt` | Existing multipart image/audio contracts and draft insertion remain compatible. Generic documents use the same pane multipart endpoint and upload-capability type/byte policy. Android streams SAF content through a cancellable owned descriptor into private storage, enforcing `min(server maxBytes, 10 MiB)` even for unknown-length/nonseekable providers; advertised extensions are the policy boundary and provider MIME is advisory. Each POST is one-shot; 400/413/415 refusals are surfaced without automatic replay. Photo Picker image behavior and normal mounted-HTTPS/TLS/bearer handling remain unchanged. |
| `GET /api/agents/capabilities` | Schema `1` returns the 24 canonical harness kinds and separate launch, detection, session, journal, web semantic/composer and media states for the requested owning host/session. Registered binaries may receive a fixed `--version` probe with a 1.5-second timeout and 4 KiB output ceiling in a minimal environment; only a parsed semantic version is returned. Per-kind detection/session counts come from exact identities and session references in the current owner-runtime snapshot; generic mux declarations alone do not establish a per-kind result. The paired read is owner-scoped and forwarded through Crew; an older peer's 404 is surfaced as unsupported, not synthesized as all-supported. Unknown additive fields are ignored; an unsupported schema is refused by the native decoder and shown as unsupported by Settings. |
| Harness transcript journal | Gemini CLI's current JSONL session record format is read from the owner-configured `GEMINI_CLI_HOME/.gemini/tmp` root, with exact UUID verification, bounded path discovery and contained reads. Message order/removal updates reset the live chat generation because UUID upserts alone cannot move/delete a turn. It does not advertise media or interactive parity; no live Gemini session canary was available for this source change. |
| Notification preferences and snooze | Preserve existing patches; decode machine/cache preference fields without overwriting them when editing older switches. Native background delivery remains unimplemented. |
| Update check/state/start/snooze and standby status | Decode Crew census, top-level peer-only progress, package-manager instructions, restart requirements, urgent/link metadata and settled times. Active peer legs block duplicate starts. Packaged installs show their package command and cannot invoke the source updater. Packaged peers do not count as source-update retry targets. |
| `/api/update/dismiss` | Paired-read POST sends the exact displayed version with `scope=offer` or `scope=crew`. Decode server-persisted `dismissedVersion` and `dismissedCrewVersion`; hide only the acknowledged version/scope. Reconcile an uncertain response through a state read without repeating the POST. Active update progress and newer versions remain visible. Digest snooze is separate. |

## Additive upstream features

This sync imports all upstream bridge, CLI and PWA features. Native parity includes the read-only
Changes browser and scoped folder favorites/recents through the native New, workspace and worktree
folder picker. Files browsers, machine resource charts and alerts, and PWA push retain separate
parity work. These APIs do not replace existing native calls.
Native launch and worktree flows use frozen, owner-bound intents when the owning host advertises
`receiptContextBound: true`; the receipt mode is saved with each intent and cannot be upgraded by a
later config read. Old/mixed servers retain one-shot legacy initial creation without an ambiguous
retry action. Native Recipes/Run management uses the owning host's declared switches, exact existing
API routes and the same encrypted receipt journal; it does not invent a receipt GET route or infer
executable availability from a syntax check.

Unknown terminal dialogs remain readable as raw terminal content. Current upstream captures are
explicitly classified in the native fixture tests; incomplete footers and unproven selection
recipes do not become automated key sequences.

## Regression evidence

Files uses native views only: Markdown is projected through the existing bounded parser, JSON is
depth/node/output limited before projection, HTML uses pinned jsoup 1.23.2 with an explicit
Safelist and native block projection, CSV uses bounded rows/cells, and SVG uses pinned AndroidSVG
1.4 after a strict streaming preflight that refuses active/external/entity constructs. The reviewed
renderer preserves ordinary viewBox, transform and inherited paint behavior on Canvas. Unsupported
or hostile content remains available in Source; see [Files preview security](FILES_PREVIEW_SECURITY.md).
No preview opens remote image or document content. Relative Markdown raster references are capped
at 20 and must pass the same scoped Files/exist request before the shared `ScopedImageLoader` reads
them.

Native tests cover mounted URL/header construction, scoped session/lead identity, expiry versus
unrelated forbidden reads, live and older chat cursors, whole-turn replacement, queue clearing,
stale generations, Crew aliases, worktree choices and request IDs, packaged updates and peer-only
progress. Published Codex terminal captures cover current composer/status shapes and verified sends.
`ShippedAgentCommandsTest` compares the complete native command catalog with the merged PWA catalog.

Verified locally on 2026-10-10: 576 native unit tests; Android lint, debug/release APK builds and
instrumentation APK compilation; both TypeScript checks and full-tree lint; 20,634 passing web tests
(with 33 upstream expected failures and 44 todos); 254 browser checks (96 conditional skips); and
66 Crew integration checks. The server/CLI and shell guard suites are also run, including the
large-merge staged-path regression. No check is disabled for this sync. Installing and exercising the APK against real Codex threads still requires an attached
Android device; no device was attached during this sync. Server code is built in an isolated worktree
so validation does not replace the running deployment's assets.


## Scoped control compatibility

Cache/model/Git fields decode as optional typed snapshot facts. The owning bridge carries a bounded
sourced rule label with a cache reading. The native detail screen uses the snapshot's server timestamp
for cache timing and shows confidence/reset/provenance without deriving model identity from terminal
text or billing savings. `/api/cache-rules` is fetched only for a local lead pane and uses its ETag;
peer catalog provenance is reported unavailable while the peer-carried label/confidence/override flag
stay visible. Cache-watch query
preferences keep the exact pane/host/session selectors but remain lead-owned, and the Settings list
never links an entry without a current pane ID. Machine/cache switches preserve the other server
preference fields. Native background notification delivery remains unsupported and is stated in the UI.

New control support requires a positive declaration in `/api/config.control.capabilities`; legacy mux absent-field compatibility applies only to existing actions. Crew negotiates the peer's independent `hello.controlCapabilities`. `hostControl` declares a separate typed host-management provider/capability/reason surface: current unsupported providers report every administration capability false and typed inspection returns unsupported. This is an interface for later workflows, not a claim that plugin/configuration/install actions exist.

Native request contexts bind the HTTPS origin including its mount, credential and leader generation, host/session and subject. Space Look now uses the owning session's read-gated POST refresh before snapshot; older 404 peers get a labeled ordinary reload. Captured media scope is checked before upload and before applying its result. Saved copies never authorize terminal or structural actions. Unknown/gateway 401/403 freezes actions while retaining pairing; only exact authoritative Collie pairing provenance retires it.

Control-operation receipts use GET `/api/control/operations/<id>` and never return secret
payload/evidence. Pending after restart is unknown, with no automatic repeat dispatch. Launch,
worktree, and launcher-add receipt reconciliation deliberately replays the exact original POST only
for an intent frozen in owner-bound mode and only after a positive capability read from the actual
owning host. A legacy-mode intent stays one-shot even if later configuration changes; an uncertain
legacy outcome requires operator inspection and explicit duplicate-risk abandonment. No receipt GET
is assumed for those existing routes. The bridge health adapter retains its existing Herdr ping
mapping; source/schema fixtures do not prove a live vendor handshake.

See [scoped control authority decision](../.adr/0096-control-authority-is-scoped-and-positive.md). Joint B02/B03 and live vendor/device acceptance are tracked in the B01 handoff.

New receipt ownership requires the stable opaque pairing incarnation, independent of a mutable device label. The B01 isolated seam advertises `controlOperations:false` until the joint B02 local and admitted Crew identity producer is integrated; missing identity refuses. Primitive injected-owner fixtures establish the shared store/reader seam, not final runtime support.

Document preparation admits the picker-captured scope/credential actor for config reads and upload writes. A two-slot provider budget keeps timeout/caller interruption bounded even when provider cancel or close stalls; private spool ownership is retired before provider cleanup, and Ready files are retained through the cancellable IO handoff until consumption/disposal. Document chips include sanitized filename, detected MIME, and actual bytes; process recreation restores only existing credential-bound staged-path metadata, never a URI grant or read/upload job.

## Pane layout control (B12)

The native client adds bounded layout, neighbor and edge reads plus a dedicated layout screen. New layout actions require positive mux declarations and `controlOperations` durable receipt readiness. Actions use the mounted Collie HTTP API with exact scoped targets, a plan token and caller-held request ID; the client never calls a mux socket or forwards vendor RPC. Unknown mutation outcomes remain visible for operation inspection and are not automatically repeated. A successful move returns a new pane ID; native navigation discards the old pane state before opening the new ID. Unsupported/mixed-version peers hide the actions or return a typed unavailable result.
B20 watch authority correction: peer watch keys bind the advertised host/session/pane. An older
untagged snapshot proves only a unique default pane or its single advertised session; ambiguous
selectors refuse. Previously unscoped peer watches remain nonlinkable with Forget available,
without guessed migration. Catalog validators and cached rules belong to the admitted read actor
and clear with retirement. Cache details wipe copied facts on stop and acknowledge an authority
wipe on the main thread before its listener returns.

Cache details also observe the real connection store: replacing a credential retires their
private actor, catalog and copied facts without waiting for another API read. A delayed old
connection notification cannot retire an actor already admitted for the current connection.


Automatic printed Files links require positive owning `control.capabilities.filesDiscovery` config and `filesReadReady=true` on an accepted pane body under the same scoped actor. Missing, false or stale facts leave mirror/transcript paths plain with no automatic Files/exist reads; explicit Files navigation remains a deliberate read through existing device-read gates. Crew support comes from the owning hello, and the pane verdict comes from that peer’s own device policy, not the lead’s merged snapshot.
Withdrawal retires mirror confirmations without reading Files. Transcript callbacks retain the admission generation that installed them, so restored same-owner access needs fresh confirmation while the existing turn-batch path quota remains unchanged.
