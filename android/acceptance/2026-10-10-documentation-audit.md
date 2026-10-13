# Native Android documentation audit — 2026-10-10

The work here audits and corrects the native app. The reference archive is supporting evidence;
collecting more documentation is not the objective. This is source review plus regression and
emulator evidence, not a claim that every phone, terminal version, or live server scenario is certified.

## Reference boundary

- Android supports API 26–36; Kotlin 2.0.21, coroutines 1.10.2, serialization 1.7.3,
  OkHttp 4.12.0, Material 1.12.0, AGP 8.9.1, and Gradle 8.11.1 remain pinned.
- Collie is reviewed against the merged 1.19.0 foundation at
  `8cc67d40d6195b5a5189d038ff2ec8409e0692de`, upstream
  `632a2bf784045b1e2576da112d89ed21fecc9eeb`. The exact HTTP contract is the route and wire
  implementation under `/home/chris/git/collie/bridge`, with the repo's architecture and protocol docs.
  Android calls this HTTP API; Herdr's socket and Codex's app-server are indirect integrations owned
  by the bridge, rather than protocols the Android client should implement independently.
- Codex reference: shallow upstream checkout at
  `c3d3b142d10f4316b46e35aad7e5317e7e506cb7`, under
  `/tmp/collie-codex-upstream-20261010`, plus current official CLI documentation.
- The archived Markdown sources and their URL, revision, fetch date, and checksums are in
  `/home/chris/git/collie/.project-docs/manifest.json`. Public documentation was fetched on
  2026-10-10; a current documentation page does not establish the version installed on a remote host.
- OkHttp ownership and timeout semantics were additionally checked in Square's published
  [4.12.0 source artifact](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0-sources.jar),
  SHA-256 `d91a769a4140e542cddbac4e67fcf279299614e8bfd53bd23b85e60c2861341c`,
  specifically `ResponseBody.kt` and `OkHttpClient.kt`. Old Square documentation URLs returned 404;
  the GitHub reference redirected to another owner, so the published versioned artifact supplies
  the independent check.

## Corrections

| Finding | Correction and regression evidence |
| --- | --- |
| HTTP response bodies resumed into UI coroutines before being read. Headers alone do not mean the body is buffered. | Consume, decode, and close on the OkHttp callback worker. Tests assert a different thread and closure before return. |
| A body truncated after headers could throw past API error handling. Cancellation could race response ownership. | Cover the complete read with typed network/timeout handling; cancellation remains attached through body consumption. HTTPS tests truncate read/write/pair responses and cancel a delayed body, checking closure and no replay. |
| Long transcription, upload, and worktree call budgets still inherited shorter socket timeouts. | Apply each endpoint's budget to socket reads and writes as well as the total call. Tests inspect the transcription timeout configuration. |
| A late response from a previous connection could populate cache or affect the current device. Same-origin re-pairing reused ETags. | Check exact connection identity before and after suspension and before cache insertion; key pane cache by credential-bearing connection identity. Deferred-response tests cover disconnect and re-pair. |
| Write permission learned from one pairing was not explicitly bound to that pairing. | Require the exact connection that supplied the successful authorization snapshot. Test a new token on the same server: no write before a fresh snapshot. |
| UI code repeated repository revocation and could recognize expiry without requiring HTTP 403. | Centralize demotion in the repository; only matching 403 expiry/unpaired responses revoke authority. Preserve unrelated forbidden errors. |
| Multi-step terminal submissions could continue during a connection change. | Cancel pending mutations, disarm direct typing, reset busy state, and retain the draft. A Codex test switches connections after typing and proves no submit follows. |
| Dashboard, Pane, Space, and retained history could show data belonging to a previous connection. | Reset connection-dependent screen state; close old history when its connection changes and invalidate retained history across credentials. Keep stale content during ordinary outages on the same connection. |
| Space joins used an unnormalized local lead identity. | Use the scoped pane snapshot normalization for Space as well as Pane. Narrow session and Crew identities retain the existing bridge semantics. |
| Internal links checked the host while missing the configured port and mount. | Match scheme, host, effective port, and complete mount; reject URL userinfo and prefix lookalikes. Test nested HTTPS mounts and nondefault ports. |
| Pairing diagnostics excluded only a root-mounted URL. | Exclude request/response bodies for mounted pairing endpoints too. Tests cover three mount shapes and successful/refused pairing with secret canaries. |
| Settings pairing codes could enter saved view state or autofill. | Disable saved state and autofill on that field. Search fields also decline autofill. Existing main setup pairing protections remain. |
| Thumbnail sampling could decode a huge panorama because its short edge was small. | Bound the longest decoded edge, recycle the intermediate bitmap, and reject unreasonable thumbnail requests. Test panoramas, transposes, and extreme dimensions. |
| An attachment disappeared as soon as Send was tapped, including failed sends. | Retain until confirmed success; restore the path across recreation only for its originating server. Device tests send an attached draft through a rejected reply and verify retention. |
| An empty draft with an attachment could show the microphone instead of Send. | Allow image-only replies and make the attachment count as composer content. Removing the chip restores the appropriate primary action. |
| Direct keyboard input discarded forward deletion and could allocate from an unbounded IME deletion request. | Translate both before/after deletion, preserve local composition, and reject invalid/oversized requests. Tests cover both InputConnection deletion methods. |
| Unsupported multiplexer chords were compared only with bare keys. | Check the full chord and bare-key refusal independently in the UI and ViewModel. A refused `ctrl+Enter` leaves plain `Enter` usable. |
| Shared Android controls and programmatic actions used a 44dp floor. | Raise interactive controls to 48dp; wrap primary/modifier key rows before shrinking buttons on narrow phones. Geometry tests cover multiple widths, overlap, and reachability. |
| Device-label input allowed 80 characters while the bridge accepts 48 UTF-16 code units. | Share the server's limit and label normalization across setup/repository validation; bound both pairing fields and reject invalid response labels. Test the boundary and supplementary characters. |
| The suggested pairing label used a suffix of Android ID. | Use a private install-local random suffix, preserving already paired labels. A preference test verifies stability without reading a device identifier. |
| Claude `/review` and `/fork` help described older behavior. | Correct current-diff/target review and background-session copying with an optional prompt in both native and mirrored web catalogs. Retain version/configuration caveats. |
| The first terminal tap invoked its action directly rather than dispatching the registered click. | Route the touch listener through the same click action used by accessibility activation. |
| Native onboarding and history documentation named older contracts. | Document the 1.19.0 baseline, the current journal registry, and Android's 48dp floor. |

The threading and cancellation corrections follow
[Android coroutine guidance](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) and
[Kotlin's cancellable continuation contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html).
The media and keyboard corrections follow
[bitmap loading guidance](https://developer.android.com/topic/performance/graphics/load-bitmap) and
[InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection).
The control and identifier changes follow
[Android accessibility guidance for Views](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views) and
[Android identifier guidance](https://developer.android.com/identity/user-data-ids).
Claude command help follows [current official commands](https://code.claude.com/docs/en/commands),
which explicitly document older `/review` and `/fork` behavior as version-dependent.

## Native coverage

| Area walked | Evidence and disposition |
| --- | --- |
| Entry points, HTTPS origins, deep links, pairing, encrypted credentials | Manifest and configuration checks, origin/deep-link/store tests, real emulator Keystore and setup interactions. No cleartext fallback or trust-all TLS. |
| HTTP routes, scoping, schema decoding, caching, transport ownership, failures | API/repository suites; TLS MockWebServer tests, scoped/narrow snapshots and credential changes. Newer fields stay optional where the server contract permits. |
| Dashboard, Space, Pane, history, Settings, Updates, Pack, licenses | Screen/model/layout tests and fixture-backed emulator navigation/actions. Destructive actions retain native confirmation and write gates. |
| Codex typing, pasted composers, prompt identity, send and submit | Current Rust composer source and native Codex guard tests; emulator Codex prompt-binding test. Type once, verify the displayed composer, submit once, and verify before clearing the phone draft. |
| Claude and other harness menus, semantic actions, terminal text | Native parser fixtures, semantic interaction tests, unknown/modal surface refusal, neutral key grammar, named/digit/function-key device tests. Undocumented harness surfaces rely on fixtures rather than an invented official contract. |
| Live chat, legacy journal fallback, scrollback, search and selection | Generation/revision merge and queued-turn tests, history/navigation/ANSI/wrapping fixtures, emulator search and horizontal table pan. Server journal registration is authoritative. |
| Launchers, workspace/tab/pane actions, worktrees and Crew | Scoped wire/model tests and isolated device actions. Native confirms launchers that skip prompts. Fixture writes never contact the operator's server. |
| Photo selection, attachments, microphone and STT | Bounded decoding/upload tests; image picker cancellation and recording-to-draft emulator cases. Recorder release/cancellation and private-cache cleanup reviewed. |
| Lifecycle, drafts, outage recovery, insets, accessibility | Foreground polling/cancellation tests, connection isolation, rotation, edge-to-edge and large-font device cases; TalkBack disposition below. |
| Diagnostics, export, backup, permissions, dependencies and packaging | Mounted pairing exclusions, redaction/export tests, merged manifest, backup exclusions, opt-in capture, narrow FileProvider grants, native/no-browser policy, notices and locked runtime SBOM checks. Native background push remains intentionally absent. |

Codex's [official slash-command list](https://learn.chatgpt.com/docs/cli/slash-commands) includes
commands whose availability depends on mode or model. The static Android catalog is help, not
capability negotiation. No commands were removed solely because one Rust enum did not contain them.
Agent version variation and terminal redraw races still require real installation evidence.

## Validation and limits

- **595 JVM unit tests passed**, with zero failures, errors, or skips. The complete suite runs in
  bounded worker batches to avoid accumulated Robolectric heap pressure; no tests were excluded.
- **Debug and release lint passed with zero errors and 178 warnings each.** Debug, instrumentation,
  and unsigned release APKs built successfully. The final combined Gradle gate is retained as
  `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10/collie-doc-audit-final-android-5.log`.
- **24 native policy, contract, notices, and runtime dependency checks passed**; **41 shared command
  catalog tests passed**. The merged release manifest was checked for API bounds, exported components,
  permissions, verified TLS configuration, and backup exclusions.
- **57 Android 16/API-36 emulator tests passed** against the final implementation, including real
  Android Keystore, launch, insets, composer behavior, navigation, and interaction coverage.
- The **51-test interaction suite also passed at 200% system text size**. **Four further cases passed
  at 320dp screen width and 200% text size**, covering wrapped setup labels, Settings controls, draft
  rotation, and Pack touch/accessibility geometry. These are repeat/scenario runs, not 55 additional
  distinct tests beyond the base suite.
- **The connected TalkBack test passed** with the installed spoken-feedback service and touch
  exploration enabled. It focused, checked the label of, and activated the setup Settings action.
  This establishes that interaction, not a whole-app manual screen-reader certification.

These runs used an isolated, read-only emulator and fixture servers. They did not pair an emulator
to the operator's service or send terminal writes there. Runtime coverage was API 36; minimum-API
compatibility was checked by lint/source review, not an API-26 device run.

Logs, per-suite unit totals, and hashes of 265 native production/configuration/lock inputs are saved
under `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10`. The manifest
`/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10/build-identity.json` binds the results
to the base commit plus local audit changes and the following artifacts:

| Artifact | SHA-256 |
| --- | --- |
| `/home/chris/git/collie/android/app/build/outputs/apk/debug/app-debug.apk` | `019ac945da860794a0332b7266288c079eafa7e0eaf9591c3746cafecaa692bc` |
| `/home/chris/git/collie/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `667ce6ee67376ce00b5660676e82e7602e4b98e6192b94bb4ab53ebed863c72e` |
| `/home/chris/git/collie/android/app/build/outputs/apk/release/app-release-unsigned.apk` | `8b48e5a739be9aed64382d61e8654a1675d783af9c81196a1ed9d1a7eab89413` |

The debug APK has the separate `com.lateapex.collie.debug` application ID. It is a testing build;
the unsigned release APK requires the established release-signing route before installation.

Lint warnings are reviewed separately from build success. They include optional newer dependency
versions, unused resources, Kotlin convenience APIs, rendering/style suggestions, compact text,
and static accessibility checks whose labels or click actions are set in code. The API-33 launcher
resource already supplies a monochrome icon; its API-26 fallback warning does not establish a missing
themed icon on Android 13+. Dependency upgrades and a whole-app visual redesign are not prerequisites
for matching the APIs of the pinned libraries. Final warning counts and reports are retained with evidence.

The existing local SDK/JDK route was obtained through doclayer from
`wiki/ed8-android-sdk-and-jdk-installation.md@f17b1c41` and then verified live on 2026-10-10.
No physical device was attached to local ADB. Physical-phone/live-host scenarios remain **unverified**:
real affected Codex threads, actual permission/trust prompts, fresh pairing and process death on the
phone, OEM keyboard differences, real network loss, live STT providers, remote Crew operations, and
update progress through a real service restart. The isolated emulator cannot establish those outcomes.
The live server and its paired-device registry were not changed by this audit.

The Android version remains 1.5.2/versionCode 3. Changes are local and unreleased; the build output
is not proof that a corrected APK is installed on the user's phone.

## Non-pane UI lifecycle and behavior review

This unit reviewed the non-pane screens and helpers assigned for the 2026-10-10 audit. The working
tree already contained the audit changes listed above; this review preserved them. It found no
additional confirmed mismatch in this unit and made no code changes. The dispositions below mean
source review plus the referenced automated/emulator evidence; they do not mean a physical phone or
live operator service was exercised.

### References used for this unit

| Collection and pin | Applicable sections and requirements used |
| --- | --- |
| `android-official` (`.project-docs/android-official/INDEX.md`; official pages fetched 2026-10-10) | Activity lifecycle; process lifecycle; tasks/back stack; Intents and intent filters; configuration changes; accessibility in Views; secure file sharing; string/dimension resources; API 26–36 behavior. Requirements used: stop screen polling when stopped, collect screen state only while visible, preserve/consume navigation state deliberately through recreation, ensure controls have accessible names/actions, and use narrow URI grants for shared files. This official-web collection has no single source revision; page URLs and fetch timestamps are recorded in `.project-docs/manifest.json`. |
| `collie-fork` at `8cc67d40d6195b5a5189d038ff2ec8409e0692de`, upstream `632a2bf784045b1e2576da112d89ed21fecc9eeb` | `CLAUDE.md` Android fork scope; ADR 0036/native implementation plan; Android-specific README and history contract. Requirement used: screens remain native and consume the existing Collie HTTP surface; do not implement bridge-owned Herdr/Codex protocols in these activities. Relevant public source is `https://github.com/Buckeyes22/collie/tree/8cc67d40d6195b5a5189d038ff2ec8409e0692de`. |
| `material-android` 1.12.0, revision `88acfcdb63281714d64e863d2e7da3b53b8da9ce` | Buttons, cards, switches, dialogs, text fields, typography, canonical layouts and predictive back in `.project-docs/material-android/INDEX.md`. These are component usage references, not independent product behavior requirements. |
| `kotlin-coroutines` 1.10.2, revision `5f8900478a8e20c073145b1608fbc71fe3d7378b` | Cancellation and structured coroutine lifetime where screen polling and lifecycle collectors are used. |
| `font-aldrich` revision `bd8f81ddb5c74d5c8897b36ad88b440266245103` | Font asset provenance only; `PreferredTypeface` intentionally applies the single shipped face to native chrome while exempting terminal-bound fields. |

The other archived collections are not behavioral authorities for these screens: `android-build` is
an example app's architecture guide; `android-test` is test-library material; Android transitive
collections describe libraries not directly called by this UI; and agent-specific collections
(Herdr, Codex, Claude Code, OpenCode) apply to bridge-side integrations or command catalogs, not
these screen implementations. Their relevant effects reach this unit only through Collie's HTTP
wire models and repository contracts, covered by the API/repository owners. This classification is
limited to this unit and does not replace the audit-wide collection inventory.

### File dispositions

| Production path | Disposition and evidence |
| --- | --- |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt` | Reviewed. Visible-state collection is in `repeatOnLifecycle(STARTED)`; polling starts/stops in `onStart`/`onStop`; screen actions delegate through `MainViewModel`. Dashboard shell/navigation and lifecycle evidence: `DashboardShellActivityTest`, `DashboardNavigationTest`, `DashboardModelTest`, `NativeInteractionTest`, `HardeningUiDeviceTest`. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MainViewModel.kt` | Reviewed. State is screen-owned in `StateFlow`; connection identity changes clear connection-specific snapshots/config; scope restoration validates against a fresh unscoped roster; polling is cancellable and backs off on failures. Evidence: `DashboardNavigationTest`, `HardeningLifecyclePollingTest`, `DashboardShellActivityTest`, `CollieRepositoryTest`. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceActivity.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceViewModel.kt` | Reviewed. Polling follows activity start/stop; state is collected only while started; workspace, session, host, tab, and pane projection is scoped to the selected Space and snapshot. Evidence: `NewSpaceHostModelTest`, `DashboardNavigationTest`, `HardeningLifecyclePollingTest`, `NativeInteractionTest`; actual Space screen is included in the recorded emulator walk. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt` | Reviewed. Connection observation is STARTED-scoped; disconnect requires a confirmation and is local; pairing entry extras are consumed once; server mutations follow repository authorization and confirmed server state; diagnostics export is explicit, confirmed, and shared with a read-only FileProvider grant. Evidence: `SettingsActivityTest`, `SettingsServerControlsTest`, `NativePreferencesTest`, `NativeInteractionTest`; settings paired/unpaired screenshots and emulator navigation are retained. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt` | Reviewed. Pack polling is canceled at stop; display is read-only; member nodes expose role/health to accessibility and use a text-aware formation layout. Evidence: `PackActivityParityTest`, `PackFormationLayoutTest`, `PackFormationTextLayoutTest`, plus the recorded narrow-screen/large-text device cases. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdateNativePresentation.kt` | Reviewed. Polling is STARTED-scoped; active runs poll more often than settled state; destructive update actions require an explicit confirmation and repository authorization; presentation derives current run/preflight/peer state from wire data. Evidence: `UpdateNativePresentationTest` plus the recorded Updates emulator screen. Live update/restart integration is explicitly unverified in the audit-wide limits. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryHighlight.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryPresentation.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryTurnRenderer.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/TranscriptBodyView.kt` | Reviewed. History is tied to the exact connection and closes on connection replacement; paging/deduplication and render windows preserve transcript order; Markdown links allow only HTTP(S)/mailto; find highlighting uses source offsets; expanded tool results and user-turn navigation remain separately addressable. Evidence: `HistoryActivityTest`, `HistoryPresentationTest`, `HistoryTurnRendererTest`, emulator transcript/find/pan scenarios. Live journal backend remains outside this UI unit and is owned by the API/history review. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieDeepLink.kt` | Reviewed. Link origin checks scheme, host, effective port, credentials and complete configured mount before route parsing; only known paths are accepted. Evidence: `CollieDeepLinkTest` covers canonical routes, mount prefixes, authority mismatches and unknown paths; `SettingsActivityTest` covers configured-server restrictions and one-shot pair entry. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PreferredTypeface.kt` | Reviewed. Device-local settings have bounded values; diagnostics defaults off; pairing-label entropy is install-local and stable; pane drafts expire and over-limit values stay memory-only; typeface listener is removed on detach and terminal-bound text is exempt. Evidence: `NativePreferencesTest`, `SettingsActivityTest`, `VisualFoundationsTest`. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LicensesActivity.kt` | Reviewed. Displays packaged notices as visible, selectable monospace content with an accessible heading; reachable from Settings. Evidence: `LicensesActivityTest`. |
| `/home/chris/git/collie/android/app/src/main/res/layout/activity_main.xml`, `/home/chris/git/collie/android/app/src/main/res/layout/activity_space.xml`, `/home/chris/git/collie/android/app/src/main/res/layout/activity_settings.xml`, `/home/chris/git/collie/android/app/src/main/res/layout/activity_history.xml`, `/home/chris/git/collie/android/app/src/main/res/layout/activity_licenses.xml` | Reviewed as corresponding screen resources. Touch target, scroll, labels, and semantic rendering checks are covered by the UI tests cited above and `AccessibilityContrastTest`, `VisualFoundationsTest`, `SystemBarInsetsDeviceTest`, and `TalkBackDeviceTest`. Pack and Updates construct their layouts in their Activity files. |
| `/home/chris/git/collie/android/app/src/main/res/values/strings.xml`, `/home/chris/git/collie/android/app/src/main/res/values/settings_strings.xml`, `/home/chris/git/collie/android/app/src/main/res/values/pane_strings.xml`, `/home/chris/git/collie/android/app/src/main/res/values/dimens.xml`, `/home/chris/git/collie/android/app/src/main/res/font/collie_ui.xml` | Reviewed as copy, touch-size and typography resources used by this unit. Localization/resource syntax is covered by `AndroidLocalizationTest`; minimum 48dp interactive sizing is asserted in Settings, dashboard shell, and History tests. |

The source review and existing tests do not establish OEM-specific keyboard/screen-reader behavior,
API-26 runtime behavior, live service update completion, or physical-device layout. Those remain
unverified, as already recorded under Validation and limits. There are no unit-specific unresolved
findings or proposed code fixes.
