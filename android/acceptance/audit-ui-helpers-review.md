# Native Android UI helpers review

Date: 2026-10-10. This bounded review covers the remaining top-level files under
`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui`, after subtracting the
initial non-pane screen unit and the files owned by the pane/composer reviewer. It is source review
plus existing tests; no production files or tests were changed or executed for this unit.

## Reference set

| Reference | Pin and sections actually applied |
| --- | --- |
| `/home/chris/git/collie/CLAUDE.md` | Current repository working rules; Android fork scope and 48dp native interactive-control floor. The Android rule is normative; web-only visual rules below are used comparatively through the native plan. |
| `.project-docs/collie-fork/ANDROID_NATIVE_IMPLEMENTATION_PLAN.md` | Pinned at Collie fork `8cc67d40d6195b5a5189d038ff2ec8409e0692de` (upstream foundation `632a2bf784045b1e2576da112d89ed21fecc9eeb`). §§5.5, 6.2, 6.3, 11–12: lifecycle-bound polling, dashboard triage/rows/host state, pane navigation and history/scrollback capability decisions, window clamp and scroll anchor; evidence remains emulator/screenshot scoped. Source URL: `https://github.com/Buckeyes22/collie/blob/8cc67d40d6195b5a5189d038ff2ec8409e0692de/ANDROID_NATIVE_IMPLEMENTATION_PLAN.md`. |
| `.project-docs/collie-fork/DESIGN.md` | Same fork revision. §§3–6, 8, 12 were compared for surface hierarchy, host color as identity (not health), typography separation, tap floors and navigation. It explicitly scopes itself to `web/src/`; therefore it is a parity reference where the native implementation plan says to mirror it, not an independent native contract. Source URL: `https://github.com/Buckeyes22/collie/blob/8cc67d40d6195b5a5189d038ff2ec8409e0692de/DESIGN.md`. |
| `.project-docs/collie-fork/.adr/0036-the-android-app-is-a-native-rest-client.md` and `CLAUDE.md` Android fork scope | Same fork revision. These establish the native HTTP-client boundary; helpers consume Collie wire models and do not implement Herdr sockets or agent protocols. |
| Collie source contract: `/home/chris/git/collie/bridge/journal/registry.ts` and `/home/chris/git/collie/bridge/types.ts` | Reviewed at the same 1.19.0 baseline. `buildJournalRegistry` lines 55–65 registers seven journal adapters; lines 68–80 defines `omp -> pi`; `toPaneWire` lines 241–248 derives `hasSession` from the adapter and addressability/discovery. This source is the authoritative explanation for the UI's `PaneBufferPolicy`, not current agent documentation. |
| `.project-docs/android-official/INDEX.md` and `/home/chris/git/collie/.project-docs/manifest.json` | Android official pages fetched 2026-10-10; official-web pages have no single source revision. Relevant pages: Views accessibility (`developer.android.com/guide/topics/ui/accessibility/apps`), RecyclerView (`developer.android.com/guide/topics/ui/layout/recyclerview`), dialog guidance (`developer.android.com/develop/ui/views/components/dialogs`), custom Views (`developer.android.com/develop/ui/views/layout/custom-views/custom-drawing`), configuration changes (`developer.android.com/guide/topics/resources/runtime-changes`), and accessibility/View references. Applied requirements: actionable rows and headings have meaningful semantics, input/layout code handles runtime configuration, custom measurement preserves the viewport contract, and dialogs remain operable with accessible controls. Each page URL, fetch time, and checksum is in the manifest. |
| `.project-docs/material-android/INDEX.md` | Material Components Android 1.12.0, revision `88acfcdb63281714d64e863d2e7da3b53b8da9ce`; buttons, dialogs, text fields, typography, and cards were consulted as API usage guidance. |
| `.project-docs/kotlin-coroutines/INDEX.md` | kotlinx.coroutines 1.10.2, revision `5f8900478a8e20c073145b1608fbc71fe3d7378b`; `CHANGES_UP_TO_1.7.md` records that cancellable continuation cancellation is established before the supplied block executes (line 578). Applied to the suspending launcher confirmation lifetime. |
| `.project-docs/claude-code-official/INDEX.md`, `.project-docs/codex-official/INDEX.md`, `.project-docs/opencode/INDEX.md`, `.project-docs/herdr-official/INDEX.md` | Partially applicable to the shipped help catalog and identity labels. These collections are mutable/versioned agent documentation and do not make native help a capability-negotiation contract. The exact UI catalog parity is tested against the current web catalog; native code does not invoke those tools directly. |

`android-build` is an example app guide, not a product contract. Android transitive-library
collections and unrelated harness/multiplexer documentation are unrelated to these helper APIs.
The dashboard/pane requirements and server fields are reviewed against the pinned Collie plan,
wire types, and bridge implementation. Physical layout, OEM accessibility differences, remote
agent versions, and live journal reads remain runtime limitations; this unit did not exercise a
live service.

## Findings

### F1 — missing-session guidance omits registered journal harnesses

**Disposition: confirmed source mismatch; correction/test proposed for the parent integrator.**

`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneScrollback.kt:35-64`
hard-codes `journalAgents` to `claude`, `codex`, `grok`, `opencode`, and `pi` (line 36). When
`hasSession` is false, only members of that set receive `missingSessionAgent` (lines 61–63); the
pane then renders `pane_scrollback_no_session` at
`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:1522-1526`,
whose text says the agent has not reported a session to Herdr. The pinned bridge registers Hermes
as a journal adapter (`bridge/journal/registry.ts:55-65`) and registers OMP as an alias of Pi
(`bridge/journal/registry.ts:68-80`). Therefore Hermes and OMP panes with `hasSession=false` lose
the actionable missing-session explanation even though the server can serve their histories once
their session is addressable. The existing
`/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/PaneScrollbackTest.kt:59-65`
only exercises OpenCode in that branch.

Proposed correction: include `hermes` and `omp` in the UI's recognized journal-capable set, retaining
the current action gate on the wire's `hasSession` field. Do not add Muse to the Herdr-install
message: the bridge's registry comments say Muse discovers its own session (`registry.ts:115-123`),
and `toPaneWire` accounts for that. Add tests asserting Hermes/OMP receive missing-session guidance
when appropriate, and Muse does not receive an inaccurate Herdr-integration instruction.

### F2 — cancellation may dismiss before the confirmation dialog is shown

**Disposition: unresolved lifecycle risk; no runtime reproduction in this unit.**

`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LauncherConfirmation.kt:16-36`
creates a dialog inside `suspendCancellableCoroutine`, registers a cancellation handler that calls
`dialog.dismiss()` at line 35, then unconditionally calls `dialog.show()` at line 36. The pinned
coroutines cancellation behavior allows the continuation to already be cancelled before the block
runs; in that case the newly installed handler can dismiss a not-yet-shown dialog, after which the
unconditional `show()` can display a dialog whose caller has already been cancelled. The existing
`LauncherConfirmationTest` covers user dismissal after the dialog is visible and confirmation by
button, but not a pre-cancelled caller or cancellation between dismissal and `show()`.

Proposed verification: add a Robolectric regression using an already-cancelled parent Job and assert
that the confirmation is not left showing. If reproduced, ensure cancellation cannot race past the
show step (including a post-show activity-state check), then rerun the launcher and native
interaction tests. This remains a proposed risk until the owning integrator verifies the continuation
behavior and test.

No additional confirmed findings were identified in this unit.

## File-by-file disposition and evidence

| Production file | Source evidence reviewed | Existing relevant evidence and disposition |
| --- | --- | --- |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentIcons.kt` | Lines 7–31 map normalized agent family aliases to bundled drawables; unknown agents use generic icon. | `AgentIconsTest` covers aliases, vector inflation/drawing and local authorship; `AgentIconsDeviceTest` covers device rendering. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentPresentation.kt` | Lines 6–25 remove OpenCode title chrome only for OpenCode; lines 28–42 abbreviate conventional home paths; lines 56–84 prioritize operator/workspace/tab identity and suppress stale/repeated terminal titles. | `DashboardModelTest`, `PaneSwitcherModelTest`, and `SpaceModelTest` cover title, path, and scope projections. Reviewed, no additional mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieBottomSheetDialog.kt` | Lines 17–60 establish the shared sheet shell, semantic title, explicit close action, safe bottom inset, max height, and app typeface; lines 63–73 replace content safely. | `VisualFoundationsTest` checks heading semantics, content ownership and 48dp close target; pane/settings/dashboard Robolectric tests and `NativeInteractionTest` exercise callers. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieHeadingTextView.kt` | Lines 8–16 mark the widget as an accessibility heading on supported APIs. | `VisualFoundationsTest`, `LicensesActivityTest`, and accessibility tests cover heading rendering/semantics. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DashboardHostHealth.kt` | Lines 26–39 derive declared poll/stale windows; lines 41–79 keep lead health, reachability/writeability, incompatibility and freshness distinct. | `DashboardHostHealthTest` covers cadences, thresholds, lead behavior, unseen/incompatible peers, and independent health/writeability. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DashboardShellPresentation.kt` | Lines 23–88 implement 2.5s stalled, 4s trouble, 15s lost, 1.8s recovery timing and distinguish auth/unreachable/Herdr-down. | `DashboardShellPresentationTest` exercises each boundary and recovery case. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DisplayText.kt` | Lines 3–9 abbreviate only the configured home root and require a path boundary. | `DisplayTextTest` covers exact root, child, unknown root and false-prefix cases. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HostPalette.kt` | Lines 6–45 hash stable IDs, resolve collisions in sorted roster order, return no slot for unknown IDs, and wrap only the ten defined colors. | `VisualFoundationsTest`, `DashboardModelTest`, and `NewSpaceHostModelTest` cover palette shape and use. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LauncherConfirmation.kt` | Lines 13–37 require named confirmation only for `noPrompts` launchers, show the exact machine/command/cwd, refuse dismissal, and bind dismissal to cancellation. | `LauncherConfirmationTest` covers visible dismissal and affirmative action. F2 remains unresolved because no pre-show cancellation case is tested. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MaxWidthViews.kt` | Lines 10–55 cap route widths at resource-defined bounds while respecting measure mode. | `VisualFoundationsTest` checks 640dp and 768dp caps at phone/wide widths; `SettingsRoutesTest`, `PaneLayoutTest`, and `HistoryActivityTest` verify route widget selection. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NewSpaceHostModel.kt` | Lines 16–85 derive peer choices from snapshot; selected writes are validated against the current roster/reachability, and host-local worktrees are filtered. | `NewSpaceHostModelTest` covers ambient/lead defaults, read-only peers, fresh-roster revalidation, and host-scoped repositories. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OutputFind.kt` | Lines 3–28 perform bounded literal case-insensitive, nonoverlapping match search and wrap navigation. | `OutputFindTest` covers case, overlap, blanks, limits, and bidirectional wrap. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneAdapter.kt` | Lines 42–375 define triage/space/launcher/footer projection; lines 377–832 bind rows, toggles, filters, progress, host/session labels, status semantics, touch actions, and stable item diffing. | `DashboardModelTest`, `DashboardShellActivityTest`, `VisualFoundationsTest`, `AccessibilityContrastTest`, and `NativeInteractionTest` cover model ordering, row projection, controls and device geometry. Reviewed, no additional mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneHeaderPresentation.kt` | Lines 14–62 prioritize names, stable pane discriminator, and cwd distinct from displayed identity. | `PaneHeaderPresentationTest` covers sole-tab names, multi-pane discriminator, hand names and cwd duplication. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneNavigationModels.kt` | Lines 26–69 keep tab/pane rows within the `(host, session, workspace)` scope; lines 72–93 choose close fallback; lines 95–140 gate structural writes and derive transcript search/user turns. | `PaneNavigationModelsTest` covers scope, capability/authorization gates, close fallback and transcript match traversal. Reviewed, no additional mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneScrollback.kt` | Lines 5–24 define the 600-to-1000-line window and scroll anchor; lines 35–64 independently gate transcript history and grid scrollback and produce missing-session guidance. | `PaneScrollbackTest` covers cap/action/anchor behavior. F1 is confirmed: its hand-maintained harness list misses registered Hermes and OMP. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneStatusVocabulary.kt` | Lines 7–28 centralize spoken labels and status colors; shell gets explicit language. | `PaneActivityTest`, `AccessibilityContrastTest`, and status rendering in `DashboardModelTest` provide adjacent coverage. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneSwitcherModel.kt` | Lines 30–83 group agents by dashboard triage, separate shells, and gate launch actions; lines 85–101 preserve host refusal visibility; lines 103–147 prioritize nonstale titles, prevent duplicate launch commands, and expose refusal data. | `PaneSwitcherModelTest` covers section order, folding, shell rows, capabilities, host refusals, title/path and duplicate locks. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneSwitcherPullDecision.kt` | Lines 3–25 map touch slop, upward distance and fling velocity to tap/open/cancel. | `PaneSwitcherPullDecisionTest` covers threshold, fling, short/downward drag and tap fallback. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ShippedAgentCommands.kt` | Lines 3–207 parse and hold the native static catalog by canonical agent name; catalog data is help and does not claim runtime capability negotiation. | `ShippedAgentCommandsTest` compares exact native and web catalog data. Official agent pages are version-dependent and do not establish the remote installation's current command set. Reviewed within that limit, no mismatched native/web rows found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceAdapter.kt` | Lines 23–63 project selected/all tab rows and empty states; lines 65–163 bind tab/pane rows and click behavior; lines 165–189 render status dots. | `SpaceModelTest`, `SpaceActivityTest`, `NativeInteractionTest`, and accessibility/device tests cover list selection and interaction. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SystemBarInsets.kt` | Lines 12–47 enable edge-to-edge, set theme-aware bar icons, and apply immutable-base safe/cutout/IME insets without cumulative padding. | `SystemBarInsetsTest` verifies repeated inset dispatch; `SystemBarInsetsDeviceTest` exercises API-36 bars/keyboard on emulator. Reviewed, no mismatch found. |
| `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/TerminalHorizontalScrollView.kt` | Lines 10–79 measure to viewport when wrapped, allow natural-width measurement/touch only when pan is enabled, reset offset on disable and hide scroll semantics while disabled. | `TerminalHorizontalScrollViewTest` and `PaneLayoutTest` cover wrap/pan measurement and layout wiring. Reviewed, no mismatch found. |

## Coverage and limits

The derived helper inventory contains 23 top-level production Kotlin files; all 23 received a
file-specific source disposition above. One confirmed mismatch (F1) and one unresolved cancellation
race (F2) were recorded. No production correction or regression test was authored in this bounded
read-only unit. Existing JVM and emulator pass counts elsewhere in
`/home/chris/git/collie/android/acceptance/2026-10-10-documentation-audit.md` are pre-existing
audit evidence, not new runs by this reviewer. The screenshot/emulator evidence does not exercise
Hermes/OMP missing-session feedback, cancellation before launcher dialog display, every OEM inset
implementation, or physical-device/TalkBack behavior across supported Android APIs.

The initially assigned screens (`MainActivity`, `SpaceActivity`, Settings, Pack, Updates, History,
deep links, preferences, licenses and their resources) are documented in
`/home/chris/git/collie/android/acceptance/audit-ui-review.md`. Pane/composer activities, models,
and terminal-specific classes named in the parent handoff are excluded from this helper unit and
remain owned by their assigned reviewers; this report does not claim their file disposition.
