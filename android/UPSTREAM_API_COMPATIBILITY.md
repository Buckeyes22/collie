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
| Pane/tab close, focus and rename; tab/workspace create | Existing route/body contracts remain compatible, with scoped identities and write authorization retained. |
| `/api/crew` | Use the renamed endpoint and `crew` field. A legacy 404 permits one `/api/pack` read fallback; a current `crew.not_lead` refusal does not. Legacy response aliases remain decodable. Native labels say Crew. |
| `/api/devices`, `/api/devices/revoke` | Decode expiry fields and retain the existing confirmed revocation flow. |
| `/api/launchers`, `/api/launch` | Decode harness discovery, richer launcher items, availability and recent runs. Existing command launchers remain supported. A legacy launch remains a one-shot initial request; ambiguous legacy outcomes do not gain a retry action. Owner-bound launch intents require the owning host's positive `receiptContextBound` declaration and retain the exact encrypted POST body/request ID for deliberate replay. Native confirmation is required for launchers marked `noPrompts`. |
| Workspace worktree list/create/open | Decode default branch, launcher result and replay metadata. Carry a request ID on create. Allow the server's longer worktree execution budget. Workspace creation uses the same frozen owner-bound intent and positive capability gate when available; old/mixed servers permit only the legacy initial attempt, with no same-ID recovery retry after uncertainty. |
| `/api/worktree/plan`, `/api/worktree` | Add scoped plan/create transport and repository methods. Encode `base.kind=default` or `ref` with its ref; encode `folder.kind=default` or `parent` with its parent. Folder worktree requests and retries preserve the exact caller-held request ID and frozen POST body, and deliberate replay is available only when the owning host positively declares bound receipts. |
| Pane upload and `/api/stt` | Existing multipart image/audio contracts and draft insertion remain compatible. Server errors preserve their typed meaning. Native image selection keeps its 10 MiB memory limit; upload capabilities are decoded and the server enforces its configured limits/types. |
| Notification preferences and snooze | Preserve existing patches; decode machine/cache preference fields without overwriting them when editing older switches. Native background delivery remains unimplemented. |
| Update check/state/start/snooze and standby status | Decode Crew census, top-level peer-only progress, package-manager instructions, restart requirements, urgent/link metadata and settled times. Active peer legs block duplicate starts. Packaged installs show their package command and cannot invoke the source updater. Packaged peers do not count as source-update retry targets. |

## Additive upstream features

This sync imports all upstream bridge, CLI and PWA features. It does not claim new native screen
parity for Changes/Files browsers, machine resource charts and alerts, launcher recipe editing,
free-text runs, folder pickers or PWA push. These APIs do not replace existing native calls.
Native launch and worktree flows use frozen, owner-bound intents when the owning host advertises
`receiptContextBound: true`; the receipt mode is saved with each intent and cannot be upgraded by a
later config read. Old/mixed servers retain one-shot legacy initial creation without an ambiguous
retry action. The repository/API also expose an encrypted `launcher.add` intent and exact same-POST
recovery seam for the later launcher-management workflow; it does not invent a receipt GET route.

Unknown terminal dialogs remain readable as raw terminal content. Current upstream captures are
explicitly classified in the native fixture tests; incomplete footers and unproven selection
recipes do not become automated key sequences.

## Regression evidence

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
