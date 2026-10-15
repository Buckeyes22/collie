# Complete capability gap closure plan — 2026-10-10

> **Execution scope superseded, 2026-10-13.** Use `/home/chris/git/collie/android/acceptance/course-correction-2026-10-13.md` and its JSON crosswalk for the remaining Android work. Speech-to-text is excluded. The former all41/75/132/102 closure counts and private Herdr producer requirements are historical, not the current completion goal. Preserve this document as the earlier plan/evidence.

Every genuine gap in the four capability reports is required work. Herdr layouts, pane movement, process inspection, waits, integrations, plugins and host/session administration have the same closure requirement as missing native consumers of existing Collie endpoints. A missing bridge contract adds implementation steps; it does not move a feature out of scope. Earlier labels such as “optional,” “candidate,” “separate product intent,” and “deliberate boundary” do not waive functional gaps in this plan.

This is a code-grounded implementation plan, not a claim that the features have been implemented. The completed Kotlin alignment remains independently recorded in [/home/chris/git/collie/android/acceptance/audit-kotlin-alignment.md](/home/chris/git/collie/android/acceptance/audit-kotlin-alignment.md). Existing tests establish the starting point and do not close these new work items.

## Required implementation specifications

The following three specifications are part of this plan, not alternative backlogs. Their stable task IDs are the implementation units. The crosswalk below maps every original finding to them.

- [/home/chris/git/collie/android/acceptance/capability-fix-plan-collie.md](/home/chris/git/collie/android/acceptance/capability-fix-plan-collie.md): existing Collie contracts, their missing native workflows, new access/deployment contracts and platform parity.
- [/home/chris/git/collie/android/acceptance/capability-fix-plan-herdr.md](/home/chris/git/collie/android/acceptance/capability-fix-plan-herdr.md): neutral bridge/adapter contracts, Crew transport, native organization, automation and administration; complete shipped-method disposition.
- [/home/chris/git/collie/android/acceptance/capability-fix-plan-android.md](/home/chris/git/collie/android/acceptance/capability-fix-plan-android.md): native integration, semantic grammars, persistence, push, entry links, APK update lifecycle and device acceptance.

The machine-readable coverage register is [/home/chris/git/collie/android/acceptance/capability-fix-plan.json](/home/chris/git/collie/android/acceptance/capability-fix-plan.json). One finding may map to several implementation units; overlapping specifications implement one shared workflow, not competing endpoints or duplicate screens.

## Evidence boundary and precedence

The inspected implementation is repository baseline `8cc67d40d6195b5a5189d038ff2ec8409e0692de` plus the current working tree, including the completed native corrections. Source citations in the companion specifications identify existing files; paths marked “proposed” identify files to create. Proposed HTTP routes and capabilities are design decisions to implement, not endpoints claimed to exist today.

Herdr evidence is the installed 0.9.3 offline schema: protocol 22, schema 1, 102 request methods; binary SHA-256 `18a8dc65f1c2fa485884344356dea1cfd911c6f06cf46fa78e193f4087f4dba7`. The archived official documentation is pinned to revision `7b116c05bfda646af39d2524c54e70c751f57ee8` and fetched 2026-10-10. This establishes a target contract, not the version of any running Herdr server. Runtime handshake/version/capability evidence is required before new writes. The exact schema is retained in `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10-capability-gaps/herdr-api-schema.json`.

Doclayer routed first: `wiki/collie.md@cad2899a337a16aa0d65045d9aa80f6d3be5559e`; archived Collie `URL@2026-10-10T07:59:58Z`. Flags remain: brain index `69e22e31` trails Git `cad2899a` by two commits; unrelated Android documentation archive deliveries encountered budget errors; Context7 did not establish the requested new contract. Current checkout, shipped schema and linked official sources resolve the actionable facts. Existing retrieval gap `gap-6cd57263df239e51873a` remains recorded; no missing archive is treated as proof that a feature is unsupported.

Official source index, checked 2026-10-10:

| Source | What it establishes |
| --- | --- |
| [Herdr socket API](https://herdr.dev/docs/socket-api/) and [agent automation](https://herdr.dev/docs/agent-automation/) | Exact upstream operations and occupant/wait/event semantics. Shipped versioned schema takes precedence over later site changes. |
| [UnifiedPush application integration](https://unifiedpush.org/developers/intro/) and [Android connector documentation](https://unifiedpush.org/kdoc/connector/) | Distributor registration and receive lifecycle, encryption, endpoint validation and current service integration. |
| [ntfy publishing](https://docs.ntfy.sh/publish/) | Concrete self-hosted distributor delivery behavior. |
| [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291) and [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292) | Push encryption and VAPID; VAPID alone cannot establish application message authenticity when a distributor ignores it. |
| [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) | API 33+ permission lifecycle, applicable to native Views without changing UI framework. |
| [Verified Android App Links](https://developer.android.com/training/app-links/verify-applinks) | Manifest-declared host verification and assetlinks; arbitrary runtime private hosts require the planned confirmed import/share path. |
| [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) | User-confirmed APK installation lifecycle. |

## Shared foundation and ordering

**Phase 0 — lock contracts and architecture.** Implement the neutral capability/schema foundation specified in the Herdr plan before enabling its actions. New capabilities require explicit affirmative advertisement; preserve the existing legacy absent-field compatibility behavior only for existing contracts. Add the required superseding ADRs for native push, administrative workflows and font preferences, retaining the native HTTP boundary. Update the neutral mux contract, public HTTP schema, Crew compatibility decision and native models together. New read routes must receive an explicit read/device-read classification: `/home/chris/git/collie/bridge/crew/forward.ts` presently defaults non-pane forwarding to writes. An unknown peer cannot silently enable a new action.

**Phase 1 — recover existing content and complete small actions.** Journal images and structured tools, queued-send guards, durable update dismissal, force-refresh/freshness and read-depth negotiation. Start shared authenticated media and scoped request cancellation here. These components also support Files, fonts and subsequent screens.

**Phase 2 — inspect, launch and monitor.** Files/Changes and all native previews/path links; rich New/folders/worktree planning; recipes and one-off runs; document attachments; resource history/alerts and cache/watch preferences. In parallel after Phase 0, implement layout/process/space organization contracts and their native controls. Contract design does not require waiting for unrelated UI polish.

**Phase 3 — complete Herdr automation and administration.** Atomic prompts, occupant-pinned waits, agent identity/reporting, full harness support, integration/plugin operations, metadata/views, saved hosts and named session/persistence/handoff workflows, operator scroll/copy/link/client surface controls and HTTP event observation. Each vertical slice includes port, adapter, HTTP gates, Crew dispatch and reachable native UI before it is considered implemented.

**Phase 4 — durable native workflows and host parity.** UnifiedPush with self-hosted ntfy, encrypted offline dashboard/mirror/chat retention, all local ergonomics and fonts, exact-target entry/QR, APK update discovery/installation, deployment-specific native proxy access, deputy takeover and Windows Crew/build/action compatibility. These remain required deliverables; their position follows shared scope/lifecycle foundations and concrete dependencies, not lower acceptance standards. Native semantic grammar work can proceed alongside Phases 1–4 using the current fixture corpus and controlled harness captures.

**Phase 5 — integration acceptance and reconciliation.** Execute all task-specific tests and controlled acceptance, correct findings, re-review coverage and documentation, and publish a completed evidence ledger. No item is closed by adding only a transport, decoder, stub, test fixture, screenshot, capability flag or documentation paragraph.

## Decisions applying to every implementation unit

1. Use `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt` for HTTPS, mount normalization, encrypted bearer custody and authoritative refusal handling. Navigation scope is `(connection generation, origin, host, session, subject identity)`; cancel requests and discard mismatched responses on scope change.
2. Preserve paired reads, snapshot-derived terminal write authority and the independent proxy/device factor. Files retains `device-read`. Metadata, plugin/session operations and external administration get declared, narrow permissions and confirmation, never an unrestricted RPC or command pass-through.
3. Writes remain one-shot. Preserve existing launch receipt IDs across a deliberate retry of the same intent; do not invent general exactly-once guarantees for upstream methods without receipts. For an uncertain mutation, reconcile authoritative state and require an explicit repeat if the result remains unknown.
4. Keep Android native: no embedded browser, direct Herdr socket, Android SSH, cross-host socket, shared PTY attach/control or raw vendor vocabulary over Crew. The missing *functions* enter neutral Collie HTTP contracts. Server-side CLI adapters use fixed executables, bounded argv and operator-declared identifiers; no arbitrary shell interpolation or client-supplied filesystem root.
5. Unsupported backends get explicit negative capability declarations and honest native refusal. Implement equivalents where a backend's official interface provides them; do not emulate support by applying Herdr keys or methods to another mux. The new feature must work fully on the target supported backend and through Crew, with mixed-version refusal covered.
6. Native push uses the concrete UnifiedPush/ntfy baseline, an authenticated registration lifecycle and origin-signed encrypted messages. The current decoder is reusable inner validation, not authentication. Provider replacement, replay/clear tombstones, device revocation and process death are mandatory acceptance, not future follow-up.
7. New user-facing native strings belong in English Android resources. Large-font, TalkBack, narrow-screen, loading, refusal and process-death behavior are part of each UI slice. Per-device font preferences never restyle agent-authored terminal/transcript text.
8. A new feature updates `/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md`, `/home/chris/git/collie/android/README.md` and applicable changelogs/ADRs. Server/CLI/Crew changes retain repository versioning and wire-change checks. This planning turn changes no versions and deploys nothing.

## Reconciled owners and shared modules

The overlapping C/A/H IDs are layer specifications for one implementation. Use the following canonical choices; add facades only when they share the same underlying state/contract.

| Workflow | Canonical contract/module owner | Integration requirement |
| --- | --- | --- |
| Scope and admission | C01/A00/H01; native `ScopedRequestKey`/read context and existing repository | One connection generation and typed source/factor refusal model. Changes is paired read; Files is device-read. |
| Refresh/read ceilings | H02–H04 plus C01/A13 | One declared read-limit schema and owning-runtime refresh path, including explicit Crew read forwarding. |
| Journal/file media | C03/C06–C08/A02/A05 with H19 producer equivalents | One scoped bounded media loader; content-supplied URLs never receive credentials. |
| New/folders/worktrees/recipes/runs | C09–C14/A03/A04/A06/A07; H08 adds removal | One New workflow; current lead-local worktree API gains new peer operations only through H01/H08's expressly designed neutral contract. |
| Offline retention | C24 `OfflineContentStore` facade plus A14 `RetainedReadDatabase` backing | New dashboard/mirror/transcript persistence Off by default. Enabled transcript policy is 1 or 7 days; dashboard/mirror <=24 hours only while enabled. Existing separately opted encrypted draft retention <=48 hours never persists password input and is not enabled by the read cache. |
| Push | C21/A15 | One signed v2 protocol, strict P-256 DER signature framing, device-owned lifecycle, shared golden fixtures and five event channels. |
| Typeface/operator fonts | C25/A16 | Bounded local WOFF2-to-SFNT decoder; one per-device preference and same-origin declared-font store. |
| Local settings/pins/keypad | C25/C26/A19 | One versioned preference store and complete board import/export/editor, queue lifetime unchanged. |
| Entry/QR/proxy login | C27/A20 and R02 | One exported `OpenLinkActivity` dispatches to internal import/sign-in screens using a single parser and transaction store; configured-host App Links addon is implemented and tested, alongside universal confirmed import. |
| APK updates | C28/A21 `AndroidReleaseRepository`, `AndroidUpdatesActivity`, bounded download worker | Extend the existing `/home/chris/git/collie/scripts/android-release.sh`, its test and `/home/chris/git/collie/.github/workflows/android-release.yml` outputs (`collie-android-$version.apk`, `release-metadata.json`, SHA256SUMS) with a detached signed metadata sidecar; one integrity contract and existing-signer PackageInstaller workflow. |
| Machine alerts and deputy | C19/C20/C23 with C21 and W01 | Implement state/checkpoint schemas first; activation/delivery/takeover integrate after them. Keep histories, alert revisions and registration generations honest across role transitions. |

For the ntfy baseline, implement the documented private-instance ACL: default deny-all, distributor-authenticated subscription, and anonymous write-only access limited to the registered unpredictable `up` topic (or the documented UnifiedPush prefix where explicitly configured). UnifiedPush requires application-server anonymous write; do not depend on an unsupported publisher credential flow. RFC8291 encryption and the verified Collie signature protect message content/authenticity; topic secrecy alone does not. See [ntfy UnifiedPush configuration](https://docs.ntfy.sh/config/).

Push's installation-proof-only retirement endpoint outside `/api` is a proposed narrowly scoped exception requiring a new ADR, strict signature/registration generation/replay counter checks and rate limits. It may only retire its own installation and cannot read, enroll, renew, alter pairing or operate a terminal. It closes offline/proxy-blocked retirement; a bounded registration lease remains required. No implementation should silently exempt existing `/api` routes from pairing to create it.

## Terminal acceptance requirement

The complete plan is fulfilled only when every required implementation unit and every original genuine-gap crosswalk row has passing implementation, review and feature-specific acceptance evidence. Retain evidence for failures and corrections; rerun the affected checks after corrections. Run the complete relevant bridge/CLI/native suites, native lint, native contract/security checks, release/debug builds and meaningful Android instrumentation once the integrated changes are ready. Build bridge/PWA assets in isolated output locations, preserving `/home/chris/git/collie/bin/collie` and `/home/chris/git/collie/web/dist`.

Controlled acceptance must cover local and Crew scopes, installed Herdr target version, at least one unsupported/mixed-version peer, signed APK upgrades with the existing release certificate, physical background push including doze/reboot, native proxy access against a declared supported deployment, Windows host workflows and real harness grammar/journal captures. Obtain live operational evidence only through existing supported access; read `/home/chris/agents/context/homelab-fleet.md` before fleet probing. Missing credentials or unavailable fixtures leave the affected acceptance item open with a precise evidence limitation while other items continue. They do not remove an implementation unit or justify declaring global closure.

No new direct file editing/staging/commit/revert feature is inferred from the read-only Files/Changes reports: those operations were explicitly classified as absent from the platform, not existing functionality the app fails to expose. Likewise removed `pane.graphics.*` calls are not restored as fictional RPCs: required image output is closed by the journal/file native image workflow. “Infinite scrollback” is replaced by negotiated bounded reads and the bounded journal search task. Functional host/session/plugin/layout gaps formerly bundled with forbidden transport shortcuts are included through the neutral contracts.

## R01 — Bounded search of the owning session journal

**Evidence:** The original Android report distinguishes loaded-turn Find from absent whole-log search. Existing history returns `fileTruncated` when only a capped tail was parsed in [/home/chris/git/collie/bridge/journal/types.ts](/home/chris/git/collie/bridge/journal/types.ts); session discovery is adapter-owned in [/home/chris/git/collie/bridge/journal/registry.ts](/home/chris/git/collie/bridge/journal/registry.ts), and existing read gates/routes are in [/home/chris/git/collie/bridge/server.ts](/home/chris/git/collie/bridge/server.ts). This task closes the wider search functionality without promising unbounded terminal history.

**Depends on:** H01, H04 and existing journal adapters. Extend the adapter source contract with incremental normalized-turn search for file logs and database-backed logs; do not search only the existing capped in-memory tail. Add proposed `POST /api/pane/<id>/history/search` as a paired read with explicit lead/peer read classification. Input: literal query (1–256 Unicode characters), case sensitivity, `limit` (1–50), opaque continuation cursor. No regex, arbitrary path, global-server scope or executable query. Resolve the pane's current journal adapter/session on the owning runtime. Normalize text/tool summaries/results, apply the same effective masking as the text ultimately returned to native (including owning-peer and applicable lead masking), then match only masked normalized text. If a peer cannot execute that effective policy, scan its bounded already masked text pages at the lead and apply the remaining policy before matching; never expose raw-match hit counts. Never match raw secrets and merely mask the excerpt: hit counts would leak hidden values. Retain current-branch/abandoned semantics; span offsets are UTF-16 code-unit indices into the returned masked text, with code-point-safe boundaries. Bind cursors to connection/host/session/journal generation, query, redaction-policy revision and adapter version, and return typed stale-cursor/reset rather than matching another log. Freeze an initial file high-water mark or database read watermark; ordinary appends after it do not reset a long scan. Rotation/truncation, rewinds or edits of already scanned rows invalidate the generation; appended data appears in a subsequent deliberate fresh search. Do not keep a database transaction open between HTTP requests.

**Bounds and responses:** Stream at most 8 MiB or 250 ms of scanning work per continuation, cap a search generation at 512 MiB and 10,000 hits, and return `matches`, `nextCursor`, `scannedBytes`, `hasMore`, `coverage` (`complete`, `partial`, `limit`), `limitReason` and journal generation. File decoding uses a bounded incremental parser with a 1 MiB maximum record; oversized records produce explicit partial-coverage gaps rather than being silently skipped. Run scan/decode in a worker terminated on a 250 ms budget and check cancellation between <=64 KiB chunks; elapsed checks after an unbounded synchronous parse are insufficient. Database adapters use bounded keyset pages and interruptible worker-owned read connections; document scan watermarks for mutable records. Limit each paired device to two concurrent scans and ten continuation requests per second, with aggregate server worker quotas and typed rate limits. Database adapters enforce equivalent bounded statement/page budgets. Match records contain turn UUID, timestamp, part index, bounded text excerpt and explicit span offsets. Support a bounded matching-turn/context read by discovered turn UUID so native can navigate to a result older than the existing parsed tail. Whole-session coverage is claimed only for a completed scan; capped or reset results say so. Returned text is inert, current masking applies, and audit logs exclude queries/content.

**Files and native entry:** Proposed `/home/chris/git/collie/bridge/journal/search.ts`, `/home/chris/git/collie/bridge/journal/search.test.ts`; extend the adapter source contract and per-harness source modules, server/Crew route schemas and read allowlists. Extend native API/models/repository; add a “Search session” mode to [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt), separate from loaded-turn Find. Cancel on scope change, label scan progress/partial coverage, and open the matching turn using its scoped identity. This is a foreground paginated read, not background ingestion or a new transcript export service.

**Closure:** Meaningful tests cover a hit preceding the old tail cap, multi-byte spans, enormous records and limits, database mutation/append/rotation, pi abandoned branches, invalid/foreign cursors, masking, Crew scope and cancellation. Controlled acceptance searches file-backed and database-backed scratch sessions with known old matches and confirms exact result navigation. R01 stays open until these contracts, adapter implementations and native workflow pass; a local Find widget is insufficient.

## R02 — Native access through an identity-aware front door

**Evidence and scope:** [/home/chris/git/collie/docs/deployment.md](/home/chris/git/collie/docs/deployment.md) defines the conforming front door and trusted proxy headers. [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt) already launches external sign-in, but browser cookie state cannot authorize native HTTP. Preserve working native Tailscale access as the existing baseline. Implement a concrete supported native identity-gateway profile for deployments needing interactive identity authentication; publish its requirements and acceptance matrix instead of claiming that every arbitrary proxy already supports native tokens.

**Chosen contract:** An operator-configured OIDC authorization-code public client uses PKCE S256 and a DPoP-capable issuer/resource profile. The native app starts an external `ACTION_VIEW` browser and receives only the registered reverse-domain callback `com.lateapex.collie:/oauth2redirect` through the single exported `OpenLinkActivity` and routes the validated transaction to internal sign-in state; no embedded browser or cookie import. This follows [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252), [PKCE](https://www.rfc-editor.org/rfc/rfc7636), [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html) and [DPoP](https://www.rfc-editor.org/info/rfc9449/). Configure issuer, public client ID, audience, redirect URI and exact allowed gateway origin through an explicit operator profile, imported with native origin review. Discovery/JWKS/token calls stay on declared issuer endpoints and use a separate credential client. State/nonce/verifier belong to one expiring connection-bound transaction; validate issuer, audience, signatures, code binding and token expiry. Send DPoP proofs on authorization-code and refresh-token requests using the same Keystore key; accept only `token_type=DPoP`. The chosen profile uses signed JWT access tokens, pinned issuer JWKS/algorithms and `cnf.jkt`; opaque tokens require a separately implemented authenticated introspection profile before being advertised, not a guess about token contents. Store identity tokens separately from the Collie pairing bearer in Keystore-backed encrypted custody. No embedded client secret.

**Gateway implementation:** Add proposed `/home/chris/git/collie/bridge/native-access/gateway.ts`, `/home/chris/git/collie/bridge/native-access/contracts.ts` and `/home/chris/git/collie/cli/native-access.ts`. This is an operator-run loopback identity adapter behind the existing single HTTPS front door, not another managed tunnel or exemption in Collie's dispatcher. The supported deployment explicitly routes the native API mount through TLS termination to this identity gateway, which authenticates every request before the local Collie. A cookie-only SSO gate must not reject that mount before the native gateway can inspect credentials. Operator routing replaces its authentication on this mount with the required DPoP gateway; it never exposes a direct unauthenticated Collie path. Keep browser routes under their original configured authentication. Test this exact ingress chain; merely putting a gateway behind an incompatible cookie gate is insufficient. To keep Collie's `Authorization: Bearer` pairing header intact, the explicitly documented gateway extension carries the identity DPoP access token in `Collie-Proxy-Authorization: DPoP <token>` and its proof in `DPoP`; it consumes both before forwarding. Verify `cnf.jkt`, access-token issuer/audience/expiry and proof method/URI/time/jti/ath/nonce against the configured public HTTPS scheme/authority and mounted path. The DPoP `htu` excludes query and fragment as RFC 9449 specifies; bind actual method and public path without decoding into a different resource. Derive public URL from operator configuration or the explicitly trusted front-door contract, never an arbitrary client `Forwarded`/`X-Forwarded-*` header. This header separation is a proposed Collie gateway extension; it is not asserted to be standard reverse-proxy behavior. The public issuer/client profile must actually support DPoP; provider-only browser sessions get a precise unsupported-profile response until this adapter is configured.

The gateway strips every incoming trusted identity/device header and injects only a verified mapped login and approved device identity. Operator CLI enrollment maps the issuer plus identity subject plus DPoP key thumbprint to an allowed device name; enrollment never writes the Collie pairing registry or silently approves a device. Pairing and proxy factors therefore remain independent and compose by AND. Add proposed `/home/chris/git/collie/bridge/native-access/device-bindings.ts`: owner-only shared binding records include issuer, subject, key thumbprint, explicitly operator-mapped existing pairedDeviceId, active/revoked state and monotonic binding revision. The CLI confirms the mapping against the existing paired-device catalog without changing its authority. A trusted configured gateway verifies the request binding; Collie reads its own validated binding store, not an untrusted phone identity header. C21 registrations enrolled through this gateway record a server-derived ingress binding/revision. Revoking a map atomically emits a local typed binding-retired event to the notification dispatcher and retires only registrations associated with that binding; no new content is delivered, and connected native clients cancel their notifications. Offline clients process the signed clear/retirement when transport resumes; already delivered offline content cannot be remotely guaranteed erased. OAuth token expiry is transient reauthentication, never device-map revocation, and does not retire push. Direct Tailscale enrollments remain governed by their actual pairing lifecycle. Test event crash/replay, binding replacement and revoked-map versus token-expiry behavior. The gateway forwards only to the configured local Collie, preserves Origin/Host requirements, keeps authenticated browser ingress behavior separately configured, and never interprets callback URLs as arbitrary upstream destinations. Server reads do not gain terminal write authority merely because identity login succeeds.

**Native integration:** Proposed `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/ProxyIdentityStore.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/ProxyIdentityInterceptor.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ProxySignInActivity.kt`; share C27/A20 transaction dispatch and A00/C01 scope cancellation. Use an Android Keystore P-256 DPoP key, strict JOSE ES256 encoding distinct from C21's DER signature format, and official known-answer fixtures. Prepare/refetch credentials before a write; on a gateway refusal do not transparently resend the terminal mutation. A read may retry once after successful reauthentication with the same scope. Define typed gateway error envelopes `{source:"native_gateway",factor:"identity"|"device",code,requestId}` and a gateway-owned response-source marker; the gateway removes any supplied marker, stamps its own refusal and preserves only verified local upstream Collie responses as source `collie`. Native parser binds these to the configured authenticated ingress/request generation and exact schema; unknown or inconsistent provenance freezes actions pending reauthentication/authoritative refresh without destructive pairing wipe. Collie authoritative paired-device revoked/expired errors alone trigger connection credential/content retirement. Add body/header spoofing and mixed-source 401/403 tests. Missing/expired credentials lead to the native sign-in/retry workflow with the exact failing factor; proxy login failure does not revoke a still-valid Collie bearer unless Collie itself reports authoritative pairing refusal.

**Dependencies and closure:** C01/A00 scope and C27/A20 callback infrastructure; superseding ADR for configured gateway token ingress plus documentation for deployment Variant C/B and operator enrollment. Tests cover intercepted/foreign callback, code replay, issuer mix-up, proof replay/wrong URL/method/token/hash/nonce/key, header spoofing, mount handling, expired tokens, revoked device mapping and zero automatic terminal retries. Controlled acceptance uses a disposable DPoP-capable OIDC issuer and the new gateway behind a conforming HTTPS front door: native login/read/Files/write succeed only with both required factors and fail after device-map revocation. R02 closes only with actual gateway/client implementation and this acceptance; documentation or an external browser opening alone is insufficient. Existing access routes are checked before requesting operator credentials for later deployment testing.

## W01 — Windows Crew admission, persistence and failover parity

**Evidence:** [/home/chris/git/collie/cli/crew.ts](/home/chris/git/collie/cli/crew.ts) explicitly refuses Windows Crew because it has not been checked; [/home/chris/git/collie/cli/crew-deputy.ts](/home/chris/git/collie/cli/crew-deputy.ts) shares the restriction. [/home/chris/git/collie/docs/windows.md](/home/chris/git/collie/docs/windows.md) consequently documents Windows 11 x64 standalone only. This is a server portability gap in the full plan, not a limitation Android can fix alone.

Use the existing dependency-free certificate implementation in [/home/chris/git/collie/bridge/crew/identity.ts](/home/chris/git/collie/bridge/crew/identity.ts), pinned TLS/admission, [/home/chris/git/collie/bridge/crew/trust-store.ts](/home/chris/git/collie/bridge/crew/trust-store.ts), [/home/chris/git/collie/bridge/host.ts](/home/chris/git/collie/bridge/host.ts), [/home/chris/git/collie/cli/task-scheduler.ts](/home/chris/git/collie/cli/task-scheduler.ts) and [/home/chris/git/collie/cli/sys.ts](/home/chris/git/collie/cli/sys.ts). Do not invent an OpenSSL requirement: current identity code already uses Node-compatible crypto. Add proposed `/home/chris/git/collie/bridge/private-state.ts` and `/home/chris/git/collie/bridge/private-state.test.ts`: a shared private-state writer with owner-only Windows directory/file DACLs, secure temporary-file creation, inherited temp/replacement ACL verification and tested replacement/locking behavior, reused by Crew trust, pairing, push keys and checkpoint state. POSIX `0600` is not evidence of Windows access control. The official basis is [Microsoft access control](https://learn.microsoft.com/en-us/windows/security/identity-protection/access-control/access-control) and [icacls](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls); validate the actual service account and read/write denial under another user.

Extend Task Scheduler lifecycle/config to preserve role/standby settings across restart; support explicit manually configured HTTPS ingress with no managed tunnel assumption. Audit certificate key generation/X509 parsing, mutual TLS server/client, SAN/pinning, clock/expiry, private-state atomicity, Windows path/junction/UNC containment, service account permissions and CLI argument quoting. Implement real equivalents for any discovered portability mismatch before changing `refuseCrewOnWindows`, `soloCrewHint`, CLI help/doctor and published support docs. Refusal removal follows a single capability/platform acceptance gate, not a blanket OS string deletion. Preserve mixed-version protocol decisions and existing quorum/deputy safeguards.

**Acceptance:** Add meaningful Windows cases to the existing identity/trust-store/crew/deputy/task-scheduler suites, with native Windows jobs in the existing Windows test lane. Run a disposable Windows 11 x64 lead, peer and deputy against Linux peers: invite/join, add, restart, leave, credential rotation, pinned-TLS refusal, update/rollback, deputy arm/takeover/lead-return race, machine/cache alerts and C21 registration/clear state. Include installed `collie.exe` and scoped Android acceptance after C19–C23 implementation. Record Windows/Bun/Herdr/build identities and DACL evidence. A Linux test using `hostFor("win32")` supplements but cannot replace this run. W01 stays open if the VM or required access is unavailable; continue platform-neutral work. The documented release rehearsal that resets a VM must use a disposable approved test target; this plan does not authorize resetting an operator VM.

## W02 — Windows build and action workflow without bash

**Evidence:** [/home/chris/git/collie/docs/windows.md](/home/chris/git/collie/docs/windows.md) documents a source-build bash dependency and unavailable Herdr action buttons. [/home/chris/git/collie/herdr-plugin.toml](/home/chris/git/collie/herdr-plugin.toml) has bash bootstrap/action argv; [/home/chris/git/collie/cli/build.ts](/home/chris/git/collie/cli/build.ts) owns transactional builds. Close the functional build/actions gap with portable Collie implementations; do not promise upstream native Windows builds of tmux/zellij/tuios that their projects do not provide.

Add proposed `/home/chris/git/collie/scripts/collie-bootstrap.ts` that resolves a validated Bun executable, checks the version contract in TypeScript, installs/typechecks/builds in the existing staged transaction, and invokes fixed CLI argv. Keep cached Unix bash wrappers compatible. Add a generated platform-specific Windows plugin manifest/payload invoking `collie.exe` for supported actions and Bun only for source bootstrap, without constructing shell command strings. Inspect the stable Herdr Windows plugin loader before enabling registration; if it cannot display action buttons, expose the same declared lifecycle actions through H15/H18's Collie HTTP/native host controls and CLI with explicit effect labels. Native functional parity is required; a Windows-specific UI omission is not repaired by making Android spawn bash.

**Files and acceptance:** Extend `/home/chris/git/collie/scripts/build-windows-payload.ps1`, `/home/chris/git/collie/cli/build.ts`, `/home/chris/git/collie/cli/build.test.ts`, `/home/chris/git/collie/scripts/build-cli.test.ts` and plugin/payload tests. Add a published Windows source-build/action support crosswalk with exact executable/version requirements. On a disposable Windows host lacking Git Bash, build into isolated outputs, freeze all eleven existing manifest action IDs (`start`, `stop`, `restart`, `uninstall`, `update`, `update-major`, `url`, `status`, `version`, `push-keys`, `push-test`) in the Windows crosswalk and exercise each through its declared CLI/native equivalent, including explicit no-TTY major-update consent and scoped uninstall effects. Exercise native `push-keys`/`push-test` equivalents without exposing private push keys or sending an unsolicited real notification; use the isolated test subscriber. Inject a failed build and verify old binary/PWA bytes survive. Verify native host controls execute the same safe verbs via the owning server. W02 closes only with a real no-bash build and complete supported action equivalents. MSI/winget/new Windows service packaging and unsupported Windows architectures were platform inventory limits, not reported supported features omitted by this app; no invented packaging parity is claimed.

## Original finding crosswalk

The generated tables below are backed by the machine-readable coverage register. “Baseline” rows describe already implemented behavior that must continue passing regression acceptance. “Required” rows are closure work, including all previous optional and bridge-prerequisite findings. Rows describing a removed API or a non-existent upstream feature map to the real functional equivalent or explicitly identify the non-gap; they cannot conceal an actual gap.

<!-- generated-crosswalk -->

The register contains **75 required implementation units**, **132 original report rows**, and the complete **102-method Herdr schema mapping**. 98 rows require gap closure; 33 preserve existing behavior; one explicitly non-existent upstream feature is recorded as a non-gap. No row is unassigned. These counts include duplicate findings across reviewers, so they are coverage counts, not unique-feature parity scores.

### [/home/chris/git/collie/android/acceptance/capability-gap-analysis.md](/home/chris/git/collie/android/acceptance/capability-gap-analysis.md)

| Original line / finding | Disposition | Required implementation units |
| --- | --- | --- |
| 25: Structured tool-call and denial detail | required | C04, A17 |
| 26: Journal attachment images and tool-result images | required | C03, A05, H19 |
| 27: Pane/workspace Changes, diffs and last-commit inspection | required | C05, A01 |
| 28: Pane/workspace Files, text and image reads, path existence | required | C06, C07, C08, A02 |
| 29: Harness/command/shell launch targets and folder-based worktree planning | required | C09, C10, C11, C12, A03, A04, A06 |
| 30: Launcher recipe add/remove/rename; folder stars | required | C10, C13, A06, A07 |
| 31: One-off command runs, preflight, recent-run history/removal | required | C14, C12, A07 |
| 32: Chat `sendQueuedNow` action | required | C02, A18 |
| 33: Persistent version/scope update dismissal | required | C22, A12 |
| 34: Mux topology-latency declaration and force-refresh | required | C01, H02, H03, A13 |
| 35: Machine/cache notification preference switches | required | C16, A08, A09 |
| 36: Machine resource summaries/history and alert thresholds | required | C19, C20, A08 |
| 37: Cache/model/git context, cache rules and per-pane watches | required | C17, C18, A09 |
| 38: Text/document attachments accepted by configured bridge upload policy | required | C15, A11 |
| 39: Retained offline chat tails | required | C24, A14 |
| 40: Larger non-Herdr terminal read windows | required | H04, A13 |
| 41: Local pins, handedness and keypad customization | required | C25, C26, A19 |
| 42: Per-device typeface/operator fonts | required | C25, A16 |
| 43: Native background notification delivery | required | C21, A15 |
| 44: Wider per-harness semantic presentation | required | A10, H12 |
| 45: Additional Herdr layout, movement, waits/process information and integration management | required | H01, H05, H06, H07, H08, H09, H10, H11, H12, H13, H14, H15, H16, H17, H18, H19, H20 |
| 68: Five-mux capability negotiation | required | H01, H04, H20 |
| 70: Windows Crew support closure | required | W01, W02 |
| 72: Journal inventory and truncation documentation drift | required | H20 |

### [/home/chris/git/collie/android/acceptance/capability-gaps-collie.md](/home/chris/git/collie/android/acceptance/capability-gaps-collie.md)

| Original line / finding | Disposition | Required implementation units |
| --- | --- | --- |
| 81: HTTPS origin, safe mount, bearer pairing, expiry/revoke | baseline | C01, A00, R02 |
| 82: Dashboard, Space/tab/pane, host/session/all-session selection | baseline | C01, A00 |
| 83: Pane/tab focus, rename, close; tab/Space create | baseline | H01, A00 |
| 84: Look now refresh | required | C01, H03, A13 |
| 85: Terminal mirror, ANSI, wrap/table pan, output Find, Zen | baseline | A13, A19 |
| 86: Semantic dialogs, quick replies, agent commands, named keys, direct typing | required | A10, H12 |
| 87: Operator key/reply/command catalogs | baseline | A19 |
| 88: Journal Chat/history, pagination, search, whole-turn updates | baseline | A13, R01 |
| 89: Queued-message display | baseline | C02, A18 |
| 90: Send queued messages now | required | C02, A18 |
| 91: Journal attachment/tool images | required | C03, A05 |
| 92: Structured tool-call/denial presentation | required | C04, A17 |
| 93: Changes list/diffs, staged+unstaged, nested repos, last commit | required | C05, A01 |
| 94: Files tree/source and ignored-file filtering | required | C06, A02 |
| 95: File images/JSON/Markdown/HTML/SVG previews | required | C07, A02 |
| 96: Agent-printed file links and source line navigation | required | C08, A02 |
| 97: Declared legacy command launchers | baseline | C09, A03 |
| 98: Discovered agent IDs, availability/reasons, shell choice | required | C09, A03, H12 |
| 99: Folder selection, favorites/recents, star | required | C10, A06 |
| 100: Existing workspace worktree list/create/open | baseline | C11, A04, H08 |
| 101: Plan worktree, choose base/ref and parent, start selected agent | required | C11, C12, A04, H08 |
| 102: Add/rename/remove launcher recipes or free lines | required | C13, A07 |
| 103: One-off command check/run/recent/remove/clear | required | C14, C12, A07 |
| 104: Request receipt / deliberate replay | required | C12, A03, A04, A07 |
| 105: Image attachment upload | baseline | C15, A11 |
| 106: Text/document attachments | required | C15, A11 |
| 107: Dictation/STT, hands-free option | baseline | C29, A00, A19 |
| 108: Notification kind preferences/snooze | required | C16, A08, A09 |
| 109: Cache countdown, model metadata, claim/catalog detail | required | C17, A09 |
| 110: Per-pane cache watch/list/forget | required | C18, A09 |
| 111: Machine CPU/memory/load/network/disks, 30-minute spark/24-hour history | required | C19, A08, W01 |
| 112: Machine alert threshold/duration and current firing rules | required | C20, C21, C23, A08, A15 |
| 113: PWA install/service worker/Web Push | required | C21, C24, C27, C28, A14, A15, A20, A21 |
| 114: Native background push | required | C21, A15 |
| 115: Update check/preflight/start/progress, peers retry, package/restart guidance | baseline | C22, A12 |
| 116: Version/scope-scoped durable update dismissal | required | C22, A12 |
| 117: Crew census/formation/member facts | baseline | C23, A00, H16, W01 |
| 118: Deputy takeover | required | C23, C20, C21, W01 |
| 119: Cold-start offline saved dashboard/pane/Chat and retention | required | C24, A14 |
| 120: Local appearance/ergonomics | required | C25, A16, A19 |
| 121: Local dashboard pinning and customizable keypad | required | C26, A19 |
| 122: External exact-target links/QR app entry | required | C27, A20 |
| 123: Redaction/configuration/provider/install/crew enrollment | required | C29, R02, A00, A11 |

### [/home/chris/git/collie/android/acceptance/capability-gaps-android.md](/home/chris/git/collie/android/acceptance/capability-gaps-android.md)

| Original line / finding | Disposition | Required implementation units |
| --- | --- | --- |
| 31: P1 — absent native surface | required | C05, A01 |
| 32: P1 — absent native surface | required | C06, C07, C08, A02 |
| 33: P1 — transport implemented, no UI caller | required | C09, C12, A03 |
| 34: P1 — transport implemented, legacy-only UI | required | C11, C12, A04, H08 |
| 35: P1 — decoded, discarded in rendering | required | C03, A05, H19 |
| 36: P2 — absent native surface | required | C10, A06 |
| 37: P2 — absent native surface | required | C13, C14, C12, A07 |
| 38: P2 — partial metadata only | required | C16, C19, C20, A08 |
| 39: P2 — decoded metadata, absent controls | required | C17, C18, A09 |
| 40: P2 — incomplete semantic parity, intentional conservative fallback | required | A10, H12 |
| 41: P2 — image-only native attachment UI | required | C15, A11 |
| 42: P2 — semantic mismatch | required | C22, A12 |
| 43: P2 — missing declared capability | required | H02, H03, A13 |
| 44: P2/P3 — PWA-only local retention | required | C24, A14 |
| 45: P3 — intentionally prepared, delivery absent | required | C21, A15 |
| 46: P3 — optional preference divergence | required | C25, A16 |
| 15: Connection and authorization | baseline | A00 |
| 16: Dashboard/scope navigation | baseline | A00 |
| 17: Spaces, tabs and pane administration | baseline | A00 |
| 18: Launch/worktrees, existing slice | baseline | A00 |
| 19: Transcript/history/search | baseline | A00 |
| 20: Terminal and agent controls | baseline | A00 |
| 21: Media | baseline | A00 |
| 22: Crew and settings | baseline | A00 |
| 23: Server updates | baseline | A00 |
| 52: Direct edit/save/stage/commit/revert from Files/Changes | non-gap | C05, C06, A01, A02 |
| 53: Native terminal emulator, direct Herdr socket, cross-host socket, arbitrary SSH/plugin/session/layout controls | required | H01, H05, H06, H07, H10, H13, H15, H16, H17, H18, H19 |
| 54: App Links/QR/browser session takeover | required | C27, A20 |
| 55: Proxy authentication browser action | required | R02, C29, A00 |
| 56: Infinite terminal scrollback/server-wide full-text journal search | required | H04, A13, R01 |
| 57: STT provider setup on phone / native local speech engine assumed available | baseline | C29, A11, A19 |
| 58: Server updater updates APK | required | C28, A21 |
| 62: Correct native four-adapter journal documentation drift | required | H20 |

### [/home/chris/git/collie/android/acceptance/capability-gaps-herdr.md](/home/chris/git/collie/android/acceptance/capability-gaps-herdr.md)

| Original line / finding | Disposition | Required implementation units |
| --- | --- | --- |
| 16: H-N1 / P2 | required | H02, A13 |
| 17: H-N2 / P2 | required | H03, C01, A13 |
| 18: H-N3 / P2 | required | H04, A13 |
| 28: Workspace/tab/pane census, agent lifecycle status, titles and labels | baseline | H01, A00 |
| 29: Pane rendered grid, ANSI styling, bounded scrollback | baseline | H04, A13 |
| 30: Agent session references and disk transcripts | baseline | H11, H12, A05, A17 |
| 31: Literal text, keys, guarded replies and direct typing | baseline | H01, H10, A10, A18 |
| 32: Pane focus across tab/workspace | baseline | H01, H05 |
| 33: Pane rename/close; tab create/rename/close; workspace create | baseline | H01, H07 |
| 34: Existing worktree list/create/open | baseline | H08, C11, A04 |
| 35: Richer branch/base/folder planning and create-at flow | required | H08, C11, C12, A04 |
| 36: Discover other local Herdr sessions | baseline | H17, A00 |
| 37: Herdr topology/agent event stream | required | H19 |
| 38: Pane split, resize, zoom, neighbor/edge/layout inspect, swap/move | required | H05 |
| 39: Portable layout export/apply/split ratios | required | H06 |
| 40: Workspace rename/close/group-close/reorder; tab move/reorder | required | H07 |
| 41: Worktree removal | required | H08 |
| 42: Process details and foreground cwd | required | H09 |
| 43: Atomic agent prompt, occupant-pinned lifecycle wait, output wait, named agent start/rename/explain | required | H10 |
| 44: Broader agent kinds and custom state/session/resume reporting | required | H11, H12, A10 |
| 45: Integration install/uninstall/list/status, manifests/reload | required | H13 |
| 46: Custom pane/workspace metadata and transient Herdr agent views | required | H14 |
| 47: Native Herdr notifications / plugin actions, panes, logs, enable/link | required | H15, H18, C21, A15 |
| 48: Saved-machine SSH connections, remote attach, persistence/restore, live handoff | required | H16, H17 |
| 49: Pane scroll/clear/copy/search/selection/link actions and client titles | required | H18 |
| 50: Terminal session observe/control, Kitty image output | required | H19, C03, C07, A02, A05 |
| 68: Five-mux declarations and outdated three-mux introduction | required | H01, H04, H20 |
| 72: Six upstream key limitations and supported alias | baseline | H01, H18, A10, A19, H20 |
| 73: Herdr zero revision requires content-bound guards | baseline | H01, H10, H18, A00, A10 |
| 74: Version-scoped truncation comment/documentation correction | required | H04, H20 |
| 75: Windows Crew refusal and bash-dependent actions | required | W01, W02, H15, H18 |
| 76: Native HTTP functional equivalence to host controls | required | H01, H16, H17, H18, H19 |

## Planning validation

The parent and the three requested GPT-6.1 Sol reviewers reconciled source/contract mismatches, prerequisite cycles, native authentication refusal provenance, proxy-token versus pairing lifetime, default-Off retention, font decoding, one shared entry router, push signing/size/provider ACL rules, gateway revocation and release sidecar ownership. The coverage generator verifies all 75 unit IDs, exact original source rows and the 102-method shipped-schema mapping. There are no unassigned original findings.

The documentation checker passes all 214 tracked/untracked non-ignored Markdown files. Planning input hashes verify all 403 native audit inputs, the four original capability reports and all 76 protected live binary/PWA assets unchanged (483 frozen inputs in total). No feature implementation, build, deployment, operator terminal write or live acceptance occurred in this planning turn. Required feature acceptance remains pending as recorded in the coverage register.

Retained planning checks, reviewed corrections, source hashes and the coverage generator are in `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10-capability-fix-plan/`. The original audit/Kotlin validation remains separate and unchanged.
