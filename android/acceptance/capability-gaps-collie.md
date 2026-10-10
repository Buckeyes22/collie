# Collie platform versus native Android: capability gaps

Read-only source review, 2026-10-10. This report identifies opportunities; it implements none.
The audited working tree is based on commit 8cc67d40d6195b5a5189d038ff2ec8409e0692de and contains
existing uncommitted audit changes. Evidence below describes those current files, not a claim that
every line is committed or deployed. Server/PWA foundation: Collie 1.19.0, upstream commit
632a2bf784045b1e2576da112d89ed21fecc9eeb, as recorded in E01.

The core native monitoring/reply workflow is implemented. The largest opportunities are native
Files/Changes, the richer New workflow, machine metrics, and cache visibility. Smaller gaps already
have usable contracts: journal images, Claude queue submission, extra notification switches, and
version-scoped update dismissal. Browser Web Push is implemented for the PWA; native background
delivery is absent by accepted design and needs a separate integration project.

## Evidence and limits

This matrix compares the public Collie HTTP dispatcher and PWA routes/API client against Android's
complete CollieApi interface, repository methods, declared Activities, wire models, and relevant
screen callers. An optional JSON field or a repository method is not screen support. “Implemented”
means code exists in this tree; it does not establish acceptance on a physical Android device or on
every multiplexer, crew peer version, operating system, or front door. No live terminal writes,
deployment probes, original server/PWA builds, or device acceptance were performed for this report.

The documentation layer was consulted first. Its resolve answer was withheld as unresolved
applicability (“platform=its HTTP”), with outcomes index_lag and not_found_in_checked_sources.
The parent lookup cited wiki/collie.md@cad2899a337a16aa0d65045d9aa80f6d3be5559e, recorded
2026-10-10; resolve's indexed revision was 69e22e31, two commits behind the brain Git revision
cad2899a, last sync 2026-10-10T17:52:45Z. Collie archived source documents were fetched
2026-10-10T07:59:58Z from repository baseline 8cc67d40. It also flagged unrelated Android
AppCompat/RecyclerView/SwipeRefreshLayout delivery-budget sync errors. Context7 evidence was
withheld, and public documentation was skipped for this local question. The flags are retrieval
limitations, not evidence that a product capability is unsupported. Current local files below
take precedence. One retrieval-defect gap was recorded: gap-6cd57263df239e51873a; the project-owned
Android compatibility document was attached for enrichment.

All evidence identifiers below resolve to full absolute paths and source lines. References describe
the current working tree inspected on 2026-10-10. The upstream introduction, architecture, guides,
ADRs, dispatcher and actual callers were used together so aspirational text does not become a
claim of implementation.

| ID | Evidence |
| --- | --- |
| E01 | [/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md:3](/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md), especially the existing-call matrix at line 16 and explicit absent native surfaces at line 40. |
| E02 | Native boundary: [/home/chris/git/collie/.adr/0036-the-android-app-is-a-native-rest-client.md:26](/home/chris/git/collie/.adr/0036-the-android-app-is-a-native-rest-client.md); native implemented surface and safety: [/home/chris/git/collie/android/README.md:87](/home/chris/git/collie/android/README.md). |
| E03 | Complete PWA route inventory: [/home/chris/git/collie/web/src/router.tsx:74](/home/chris/git/collie/web/src/router.tsx); complete native API interface: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:60](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); complete Activity inventory: [/home/chris/git/collie/android/app/src/main/AndroidManifest.xml:16](/home/chris/git/collie/android/app/src/main/AndroidManifest.xml). |
| E04 | Pairing/read/write/device-read gates: [/home/chris/git/collie/bridge/server.ts:5969](/home/chris/git/collie/bridge/server.ts), deny-by-default API front gate at line 6195; documented gate distinctions: [/home/chris/git/collie/docs/security.md:15](/home/chris/git/collie/docs/security.md). |
| E05 | Native scoped config/snapshot/pane/refresh/pair transport: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:196](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); authorization from successful snapshots and lead/session normalization: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt:152](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt). |
| E06 | Native headers and mount construction: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:739](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); redirects and retries disabled at line 797; one-shot repository sends: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt:380](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt). |
| E07 | Refresh is a read that actually asks the mux to refresh: [/home/chris/git/collie/bridge/server.ts:1435](/home/chris/git/collie/bridge/server.ts); native Space refresh caller: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceViewModel.kt:132](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceViewModel.kt). |
| E08 | Native history/chat calls and cursor contracts: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:294](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); native live-window presentation: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt:796](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt). |
| E09 | Native queue action is only decoded: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/UpstreamModels.kt:115](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/UpstreamModels.kt); native live window retains queued text but no send-now recipe: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LiveChatWindow.kt:17](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LiveChatWindow.kt); queue UI draws text only: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/TranscriptBodyView.kt:146](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/TranscriptBodyView.kt); guarded PWA action: [/home/chris/git/collie/web/src/components/agent-chat.tsx:1448](/home/chris/git/collie/web/src/components/agent-chat.tsx); documented Claude behavior: [/home/chris/git/collie/docs/configure.md:807](/home/chris/git/collie/docs/configure.md). |
| E10 | Journal image and structured-tool contract: [/home/chris/git/collie/bridge/journal/types.ts:31](/home/chris/git/collie/bridge/journal/types.ts); PWA image renderer: [/home/chris/git/collie/web/src/components/transcript-view.tsx:146](/home/chris/git/collie/web/src/components/transcript-view.tsx); Android fields decoded: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt:225](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt); Android projection loses image/structured-call fields: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryPresentation.kt:317](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryPresentation.kt). |
| E11 | Changes/Files routes and gates: [/home/chris/git/collie/bridge/server.ts:1541](/home/chris/git/collie/bridge/server.ts), pane dispatch at line 1696; PWA read methods: [/home/chris/git/collie/web/src/lib/api.ts:848](/home/chris/git/collie/web/src/lib/api.ts); detailed implemented product behavior: [/home/chris/git/collie/docs/changes.md:1](/home/chris/git/collie/docs/changes.md). |
| E12 | File path existence, raw image reads and containment: [/home/chris/git/collie/bridge/server.ts:3500](/home/chris/git/collie/bridge/server.ts); PWA bytes/existence APIs: [/home/chris/git/collie/web/src/lib/api.ts:1020](/home/chris/git/collie/web/src/lib/api.ts); document previews, limits and path links: [/home/chris/git/collie/docs/changes.md:154](/home/chris/git/collie/docs/changes.md). |
| E13 | Native structural writes/launcher/worktree repository: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt:208](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt); current native worktree UI uses listed workspace roots and legacy create: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt:949](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt). |
| E14 | Native new-launch and folder-worktree transports: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:343](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); richer models: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/UpstreamModels.kt:12](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/UpstreamModels.kt); decoded rich launcher list: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt:347](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt). |
| E15 | Server launch/added/recent/folder dispatch: [/home/chris/git/collie/bridge/server.ts:1466](/home/chris/git/collie/bridge/server.ts); added and recent routes at lines 5128 and 5171; run/check/folders at lines 5204 and 5261; one-off launch dispatch at line 5632. PWA methods: [/home/chris/git/collie/web/src/lib/api.ts:1280](/home/chris/git/collie/web/src/lib/api.ts), additions/folders at line 1465. |
| E16 | Launch-by-ID, discovery and receipt semantics: [/home/chris/git/collie/.adr/0091-launch-by-id-with-a-request-id.md:32](/home/chris/git/collie/.adr/0091-launch-by-id-with-a-request-id.md); operator add/free-text/run controls: [/home/chris/git/collie/docs/configure.md:407](/home/chris/git/collie/docs/configure.md); worktree-folder rules: [/home/chris/git/collie/.adr/0093-a-branch-folder-is-named-by-its-parent.md:33](/home/chris/git/collie/.adr/0093-a-branch-folder-is-named-by-its-parent.md). |
| E17 | Native image-only picker and 10 MiB bound: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:529](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt); bounded image selection logic: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt:189](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt); 10 MiB bound: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt:299](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt); server text/image attachments: [/home/chris/git/collie/docs/configure.md:642](/home/chris/git/collie/docs/configure.md). |
| E18 | Native multipart upload/raw STT calls: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:478](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); native hands-free behavior: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt:117](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt); provider setup and isolation: [/home/chris/git/collie/docs/voice-and-push.md:6](/home/chris/git/collie/docs/voice-and-push.md). |
| E19 | Server notification preference and cache-watch routes: [/home/chris/git/collie/bridge/server.ts:2377](/home/chris/git/collie/bridge/server.ts); PWA five switches: [/home/chris/git/collie/web/src/components/notify-prefs-control.tsx:13](/home/chris/git/collie/web/src/components/notify-prefs-control.tsx); Android three switches: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt:296](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt); native additive fields: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt:398](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt). |
| E20 | Cache metadata decoded without typed presentation: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt:104](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt); native header lacks cache/model fields: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneHeaderPresentation.kt:6](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneHeaderPresentation.kt); current cache chip semantics: [/home/chris/git/collie/docs/configure.md:599](/home/chris/git/collie/docs/configure.md); catalog route: [/home/chris/git/collie/bridge/server.ts:5409](/home/chris/git/collie/bridge/server.ts). |
| E21 | Machine routes and rule-write gate: [/home/chris/git/collie/bridge/server.ts:5335](/home/chris/git/collie/bridge/server.ts); history points and spark contract: [/home/chris/git/collie/bridge/types.ts:589](/home/chris/git/collie/bridge/types.ts); product metrics/alerts/screens: [/home/chris/git/collie/docs/crew.md:251](/home/chris/git/collie/docs/crew.md); native current host freshness is different data: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DashboardHostHealth.kt:7](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DashboardHostHealth.kt). |
| E22 | PWA subscription endpoint: [/home/chris/git/collie/web/src/lib/api.ts:1550](/home/chris/git/collie/web/src/lib/api.ts); native push is only decoders/seam: [/home/chris/git/collie/android/NATIVE_PUSH_CONTRACT.md:3](/home/chris/git/collie/android/NATIVE_PUSH_CONTRACT.md); missing integration obligations at line 86; accepted intentional exclusion in E02. |
| E23 | Native update check/state/start/standby/snooze transport: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:552](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt); durable server dismissal differs from snooze: [/home/chris/git/collie/bridge/server.ts:2489](/home/chris/git/collie/bridge/server.ts); native local dismissal: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt:568](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt). |
| E24 | Native Crew presentation/lifecycle: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt:35](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt); Crew census is deliberately not an administration console: [/home/chris/git/collie/web/src/routes/crew.tsx:27](/home/chris/git/collie/web/src/routes/crew.tsx); standby and operator quorum: [/home/chris/git/collie/ARCHITECTURE.md:92](/home/chris/git/collie/ARCHITECTURE.md). |
| E25 | PWA persisted offline behavior/retention: [/home/chris/git/collie/docs/configure.md:844](/home/chris/git/collie/docs/configure.md); bounds, password/unpair cleanup: [/home/chris/git/collie/docs/security.md:124](/home/chris/git/collie/docs/security.md); Android cache and connection clearing: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt:338](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt), lines 398 and 417. |
| E26 | PWA Appearance/Device/Alerts settings: [/home/chris/git/collie/web/src/routes/settings-sections.tsx:66](/home/chris/git/collie/web/src/routes/settings-sections.tsx); native limited preference set: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt:30](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt); fixed native Aldrich is deliberate: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PreferredTypeface.kt:11](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PreferredTypeface.kt). |
| E27 | PWA local key-board customization and no server endpoint: [/home/chris/git/collie/.adr/0092-the-keys-pad-is-a-board-you-arrange.md:33](/home/chris/git/collie/.adr/0092-the-keys-pad-is-a-board-you-arrange.md); native fixed keypad/operator presets: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:759](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt), More sheet at line 969; PWA pinning: [/home/chris/git/collie/web/src/routes/home.tsx:178](/home/chris/git/collie/web/src/routes/home.tsx). |
| E28 | Native route parser supports old pack and selected internal targets: [/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieDeepLink.kt:9](/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieDeepLink.kt); manifest exports launcher only and has no VIEW link filter (E03). |

## Bounded feature matrix

The matrix covers public route families plus significant PWA-only client behaviors. Internal crew
RPC, the crew-only launcher forget-device route, host provisioning, and developer playgrounds are
not Android API opportunities. Adapter-specific semantic prompt coverage is a separate audit unit;
this matrix does not assume every native parser matches every PWA grammar.

| Capability | Platform/PWA today | Android today | Disposition and user impact | Evidence |
| --- | --- | --- | --- | --- |
| HTTPS origin, safe mount, bearer pairing, expiry/revoke | Open health/pair; paired reads and writes; optional identity/header gates | Implemented; encrypted token, scoped/mounted calls, exact pairing refusal clears authority | Core supported. A paired token still cannot satisfy a separate proxy identity requirement. | E02, E04–E06 |
| Dashboard, Space/tab/pane, host/session/all-session selection | Snapshot topology and triage, status/freshness | Implemented core; scoped identities and host freshness | Preserve current scopes when adding any feature. | E01, E05, E21, E24 |
| Pane/tab focus, rename, close; tab/Space create | Gated mutation routes | Implemented with write gates and confirmations | Supported subject to mux capability and reachable peer. | E01, E13 |
| Look now refresh | POST /api/refresh is read-level and invokes mux refresh | Transport/repository exist; Space refresh currently reloads snapshot only | Partial wiring. A Space pull may not accelerate bounded out-of-band topology visibility. | E05, E07 |
| Terminal mirror, ANSI, wrap/table pan, output Find, Zen | Rendered inert grid, client presentation | Implemented native controls | Core supported; no terminal emulator or shared PTY resizing is warranted. | E02, E03 |
| Semantic dialogs, quick replies, agent commands, named keys, direct typing | Client-side harness adapters and guarded terminal input | Implemented native parsers/control paths; exact subset assessed separately | Keep unsupported dialogs readable and fail closed; API availability is not safe semantic parity. | E02, E06 |
| Operator key/reply/command catalogs | Scoped /api/config | Implemented, native catalog/grammar | Supported; operator text remains operator-authored. | E01, E05, E27 |
| Journal Chat/history, pagination, search, whole-turn updates | Live generation/revision cursors and older pages; logs required | Implemented, fallback to history on legacy chat 404 | Supported where mux reports session and log exists; missing integration is not an Android defect. | E01, E08; /home/chris/git/collie/docs/configure.md:816 |
| Queued-message display | Chat queued array | Implemented as selectable queued text | Supported display only. | E09 |
| Send queued messages now | Server-declared keys; Claude Ctrl+Enter; guarded PWA button | Field decoded, omitted from live window/UI action | Worthwhile small gap. User otherwise waits or must manually find the correct keys. | E09 |
| Journal attachment/tool images | Image parts and imageUrl; authed /api/blobs bytes, scoped to owner | Fields decode, native text projection drops image values; no blob fetch API | Worthwhile content gap. Image-only turns/tools can look empty and omit agent evidence. | E03, E10; /home/chris/git/collie/bridge/server.ts:1512 |
| Structured tool-call/denial presentation | Additive call metadata and denial versus error semantics | Models decode fields; text tool card uses name/summary/result text | Partial presentation. Keep text fallback; enrich native cards without implying new tool execution. | E10 |
| Changes list/diffs, staged+unstaged, nested repos, last commit | Read-only pane/workspace Changes API and screen | No native API methods/screen | High-value gap: cannot inspect what the agent changed or just committed from native app. | E01, E03, E11 |
| Files tree/source and ignored-file filtering | Workspace-root bounded read, needs device-read; no git required | No native API methods/screen | High-value gap: cannot inspect output/config/source files outside the terminal transcript. | E01, E03, E11 |
| File images/JSON/Markdown/HTML/SVG previews | Raw raster-image API; PWA inert/sandboxed preview policies | Absent with Files; no WebView allowed | Native text/JSON/Markdown/raster are feasible. Browser HTML/SVG preview is a design boundary, not copyable UI. | E02, E12 |
| Agent-printed file links and source line navigation | Batched existence check, bounded workspace-relative links | linkText decodes, but no file existence/browser consumer | Worthwhile follow-on to Files; no guessing paths or fetching arbitrary URLs. | E05, E12 |
| Declared legacy command launchers | Host-scoped /api/launchers and /api/launch | Implemented, consumes legacy launchers list and confirms noPrompts | Supported; does not imply richer New parity. | E13, E14 |
| Discovered agent IDs, availability/reasons, shell choice | Rich /api/launchers items; POST /api/launch harness/command/shell | Models and startLaunch repository/transport exist; current screens use legacy rows | Transport-ready gap. Users cannot start discovered installed agents without an operator launcher row. | E01, E14–E16 |
| Folder selection, favorites/recents, star | Machine-scoped /api/folders and /api/folders/star | No methods/screens | Worthwhile New workflow gap; fewer path re-entry errors on a phone. Lists must belong to selected machine. | E03, E15; /home/chris/git/collie/docs/configure.md:687 |
| Existing workspace worktree list/create/open | Legacy supported routes; capability gated | Implemented current UI with branch entry and existing unopened trees | Supported subset, not arbitrary repository/folder worktrees. | E13 |
| Plan worktree, choose base/ref and parent, start selected agent | /api/worktree/plan and /api/worktree; server contains/validates parent | Native transport/repository/models exist; no new plan/base/parent/launcher screen | Transport-ready opportunity; reduces opaque base choice and combines branch+agent startup. | E01, E13, E14, E16 |
| Add/rename/remove launcher recipes or free lines | /api/launchers/added family; host switches and per-device attribution | adding metadata decodes; no mutation methods/screens | Native gap. Recipe mode can precede free-text mode; disabled operator switches must stay disabled. | E03, E14–E16 |
| One-off command check/run/recent/remove/clear | Check is read; run is explicit paired terminal write; per-machine history, secret suppression | Recent runs decode; no run/check/recent-write methods or UI | Native gap with larger write-risk surface; do after native New gates and intent identity are established. | E03, E14–E16 |
| Request receipt / deliberate replay | Same request ID returns same start/worktree outcome; automatic resend forbidden | IDs on legacy calls; caller-held ID methods for new launches/worktrees | Partial UX readiness; retain intent across operator-confirmed retry, never mint a new ID for ambiguous old intent. | E01, E14, E16 |
| Image attachment upload | Multipart /api/pane/:id/upload, config limits/types | Implemented image-only Photo Picker, 10 MiB native bound | Supported subset; do not inflate memory limit merely because server allows larger uploads. | E17, E18 |
| Text/document attachments | Server accepts configured text types through same upload route | No document picker or generic file upload abstraction | Worthwhile bounded extension: attach a brief/log/spec without pasting its contents. | E03, E17 |
| Dictation/STT, hands-free option | Operator-configured provider seam; setup stays CLI | Implemented recording, transcription insertion and guarded hands-free | Supported; absence of server STT is intentional configuration, not native gap. | E18, E26 |
| Notification kind preferences/snooze | Bridge-wide blocked/done/updates/machines/cache and global snooze | Implemented three switches and snooze; machine/cache fields preserved but hidden | Small settings gap affects all subscribed browser devices; clarify bridge-wide scope. | E19 |
| Cache countdown, model metadata, claim/catalog detail | Snapshot cache/model; measured versus claimed windows; /api/cache-rules | Cache/git JSON and model decode; header omits them, no catalog call | Worthwhile visibility gap; show confidence/state, not a promised billing saving. | E20 |
| Per-pane cache watch/list/forget | Preferences on lead, host/session/pane identifies subject; not forwarded | No native methods/UI | Native gap even without native push: controls existing browser alerts; must state delivery limitation. | E03, E19 |
| Machine CPU/memory/load/network/disks, 30-minute spark/24-hour history | /api/machines, /:id/history; optional platform readings, gaps kept empty | Crew topology/freshness exists, but no machine metric calls/models/screens | High-value monitoring gap. Host health is not resource telemetry or a load chart. | E01, E03, E21 |
| Machine alert threshold/duration and current firing rules | POST /api/machines/:id/alerts replaces whole rule object, device-write gate | No native rule editor/API | Worthwhile after machine read screen; preserve untouched rules when replacing whole object. Browser push handles delivery today. | E03, E19, E21 |
| PWA install/service worker/Web Push | Browser subscription, SW push/tap routing | Intentionally separate/absent | Browser-specific functionality, not a native REST feature that can be enabled by one API call. | E02, E22 |
| Native background push | Payload/provider types are preparation only | Pure decoder, no provider/registration/permission/receiver/channels | Deferred design project; no background notifications available now. | E02, E22 |
| Update check/preflight/start/progress, peers retry, package/restart guidance | Existing server updater and standby record | Implemented current native workflow; packaged installs don't use source updater | Supported, subject to documented source/packaged distinctions and standby ingress. Does not update the APK. | E01, E23 |
| Version/scope-scoped durable update dismissal | /api/update/dismiss persists offer or crew dismissal; newer release reappears | Local dismissed Boolean plus /api/update/snooze only | Small consistency gap: “dismiss” does not persist the same notice decision across Activity recreation/devices. | E23 |
| Crew census/formation/member facts | /api/crew report; join/leave/promote/rotate remain CLI | Implemented native report with legacy pack fallback | Supported. Missing fleet administration buttons are intentional exclusions shared by PWA. | E01, E24 |
| Deputy takeover | Separate standby listener, verified warrant, operator pairing credential | Update standby read only; no native takeover implementation | Separate architecture/access flow; current Crew screen is not proof of Android failover support. | E03, E24 |
| Cold-start offline saved dashboard/pane/Chat and retention | Bounded IndexedDB store; expiry, password/unpair cleanup; controls disabled | In-memory scoped pane cache and ViewModel last-success state; no persistent offline store/retention control | Native opportunity, not current parity. Needs explicit content storage/cleanup design, encrypted storage and stale read-only labeling. | E25 |
| Local appearance/ergonomics | Theme/typeface/fonts, left-hand layout, belt scale, pane ordering, tool/compaction switches | Theme/text size/haptics/Zen and local drafts exist; native Aldrich fixed; many PWA controls absent | Mostly native client work, not missing server APIs. Prioritize accessibility/left-hand layout; fixed font is currently a stated native choice. | E26 |
| Local dashboard pinning and customizable keypad | Pins and per-browser board editor/import/export; no server endpoint | Dashboard collapse/sort and fixed keypad/presets; no pin/board model/UI | Useful local customization; separate from operator key catalog, never move config into server. | E26, E27 |
| External exact-target links/QR app entry | PWA route URLs and pairing QR, browser routes current | Internal parser, launcher-only exported Activity; parser knows pack but not current crew/new/files/machines | Partial internal routing, no general Android App Link handling. Keep origin/mount binding; an exported link flow is separate work. | E03, E28 |
| Redaction/configuration/provider/install/crew enrollment | Server masking/capabilities; host CLI owns sensitive setup and provisioning | Config redact/upload/STT fields decode; native consumes masked results | Server-side control, not missing native config editing. Optional “masking off”/upload-limit UX can use config; don't add credential/setup forms. | E01, E04, E17, E18, E24 |

## Recommended sequence

These are recommendations within the existing native boundary, not authorized implementation work.
Priority reflects user benefit and contract readiness; it is not a claim that missing parity is a
release blocker.

1. **Restore content users currently lose.** Add journal image/tool-image handling with an explicit
   unsupported/loading/error state and bounded authenticated bytes, then structured tool-call and
   denial presentation. E10 proves decoding alone loses the visual content. Allow only validated
   journal blob references or tightly bounded supported inline images, carry the pane host/session,
   send the bearer through the existing client, reject redirects, and cancel on scope/connection
   change. An image URL from a transcript must never become an arbitrary credentialed network fetch.

2. **Complete small existing contracts.** Expose machine/cache notification switches using partial
   preference patches; implement durable update dismissal with the actual version and offer/crew
   scope; wire Space “look now” through the existing refresh method. Add Claude's queued-message
   action only when the current chat answer declares keys. Re-read/revalidate the pane and retain
   live, pairing/device-write, no-saved-copy, no-dialog/no-password gates; use declared keys through
   the guarded key path, never resend queued message text. An unbound Ctrl+Enter during a semantic
   dialog can answer that dialog. No automatic mutation retry is acceptable (E06, E09, E19, E23).

3. **Add native read-only Files/Changes.** Start with Changes list/diff/last-commit and Files tree/source,
   then JSON/Markdown/raster previews and printed-path links. Reuse server discovery/containment;
   the app supplies a scoped pane/workspace and relative path, never an arbitrary filesystem root.
   Files requires device-read authority beyond ordinary paired reads. Respect truncation, forbidden,
   stale-scope and unknown-path outcomes. Keep HTML as inert source or a future separately designed
   native interpretation; embedding its PWA frame would violate the accepted no-WebView boundary.
   No git stage/commit/edit/pull/push endpoints are provided by this view (E02, E04, E11, E12).

4. **Surface the transport-ready New workflow.** Select the machine first, read its discovered
   harnesses and rich launcher items, offer a shell/agent/declared row, and use caller-held intent
   IDs. Add favorites/recents and worktree plan/base/parent choices. Preserve missing-field behavior
   for older peers, availability/reason/noPrompts, operator switches, fixed row folders and mux
   capabilities. Treat an unknown start outcome as unknown, retaining the ID for an explicit
   operator retry; never follow it by a fresh start. Worktree plan is advisory and the server
   rechecks the parent/create constraints (E13–E16).

5. **Add monitoring without waiting for native push.** Machine status/history and cache/countdown
   details are useful while foreground polling is active. Typed cache fields should retain unknown
   and claimed-versus-measured states. Metric views must retain missing-minute gaps and stale ages,
   omit unreported platform counters, and request only visible-screen data. Machine rule writes
   replace the whole object, so keep all current untouched metrics. Cache-watch preferences live
   on the lead even when their pane is on a peer; cache rule catalog is this host's catalog and is
   deliberately not forwarded for peer claims (E19–E21).

6. **Then expand creation and local ergonomics.** Recipe management before arbitrary free-text
   launchers/runs; bounded document attachments before larger upload limits; left-hand layout,
   pins and keypad customization before ornamental parity. Free-text add and one-off run have
   different operator switches, preflight/noPrompts requirements, attribution and persistence rules.
   Keep command history's secret suppression and remove/clear controls. Local pad layout is device
   data and does not require a server endpoint (E15–E17, E26, E27).

Native background push and persistent offline content are independent design projects. Push needs
a real provider choice, authenticated paired-device-owned registration/replacement/revocation,
durable sender sequences and receiver clear tombstones, Android permission/channels/receiver,
origin-bound exact-target navigation and end-to-end acceptance. The existing payload decoder grants
none of those. Persistent offline content needs bounds/retention, encrypted custody, password and
unpair/revoke/expiry cleanup, process-death behavior, and complete stale-view write suppression.
Neither project should weaken existing foreground polling or introduce a browser shell (E02,
E22, E25).

## Shared prerequisites and validation implications

Every feature should use the existing HTTPS/mount-normalizing repository and API client. Valid
pairing is required for reads as well as writes. Terminal/creation/rule writes must retain successful
snapshot-derived device authorization, Origin, exact host/session identity, current-screen evidence
where relevant, one-shot transport, and refusal handling. Files reads need the additional proxy
device factor. Notification preference mutations are read-level on the server because they do not
drive terminals, but affect all subscribed devices and therefore need clear scope copy. Standby
routes have separate admission and must not be treated as ordinary API exceptions (E04–E06).

A conforming reverse proxy/Tailscale front door and its optional identity requirements remain
deployment prerequisites. Native pairing does not log a device into an interactive identity proxy.
The client deliberately refuses redirects; solving an identity-proxy native sign-in flow must not
relax TLS, copy browser sessions, forge identity/device headers, add CORS exceptions, or silently
introduce a prohibited browser container (E02, E04, E06).

Feature absence was checked against E03's complete native interface/Activity inventory and actual
screen consumers, then cross-checked against E01's own declared parity limits. New launch/worktree,
queue and image fields are explicitly classified as partial where decoded or transported. No new
tests were run for this documentation-only review. Future implementation should verify mixed-version
servers/peers, unsupported mux capabilities, malformed/optional responses, scope changes,
background/resume/process death, authoritative revoke/expiry, redirect refusal and ambiguous writes,
then exercise a signed APK on a device against harmless controlled sessions. Existing audit/build
evidence elsewhere in the repository does not replace acceptance of these unimplemented features.
