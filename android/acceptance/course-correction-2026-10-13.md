# Android completion scope and remaining work — 2026-10-13

Finish Collie for Android against released Collie functionality, preserve the existing work, and remove the requirement to develop Herdr. This is preparation for a goal, not goal activation or feature implementation.

Chris's latest instruction excludes **speech-to-text**. Voice input, transcription, speech engine/model setup and speech acceptance are not completion requirements. Existing speech source remains preserved. The English-only product decision also remains in effect.

## Fixed target and source of authority

The audited product source is main `7477ffd6ff7489bcd63ec2d5945114cc026cbc3e`. The two unfinished branches are `work/b33-operator-controls` at `6cc9335a` and `work/b34-session-observation` at `9f1f4b2a`. All three worktrees were clean when inspected. Their absolute locations are `/home/chris/git/collie`, `/home/chris/git/collie-unfinished/b33-operator-controls`, and `/home/chris/git/collie-unfinished/b34-session-observation`.

The parity target is official **Collie v1.21.0**, commit `b55b9d2f2aaa7426d7b97dbfdd2c6b196e58082f`, released October 13. Herdr compatibility is with **unmodified Herdr v0.9.4**, commit `7e26bad94044eba205033111a0ce8033ede7e24f`. Fix these released contracts for this completion effort; a subsequent upstream release does not automatically restart the project or expand the checklist.

Primary references: [Collie release](https://github.com/AltanS/collie/releases/tag/v1.21.0), [released Collie docs](https://github.com/AltanS/collie/tree/v1.21.0/docs), [released HTTP client contracts](https://github.com/AltanS/collie/blob/v1.21.0/web/src/lib/api.ts), [Herdr release](https://github.com/herdrdev/herdr/releases/tag/v0.9.4).

The scope authority is this review plus the 41-row crosswalk in `/home/chris/git/collie/android/acceptance/course-correction-2026-10-13.json`. The earlier all41/75/132/102 requirement is superseded as an execution condition. Older reports, briefs, test records and implementation remain available as history and useful evidence; neither their stale status labels nor their expanded requirements override this review and Chris's latest instructions.

The original native REST-client decision remains applicable: `/home/chris/git/collie/.adr/0036-the-android-app-is-a-native-rest-client.md`. Preserve Kotlin native screens, HTTPS, pairing, origin/mount checks, encrypted credentials, lifecycle behavior and guarded terminal writes. The phone consumes Collie HTTP; it never talks directly to the Herdr socket or uses SSH for product operations.

The earlier doclayer answer used `wiki/collie.md@8753eae8` and flagged index lag/partial retrieval. It is historical context, not evidence of the released target or current runtime. The current comparison uses local source and the fixed official source copies, with URL, revision and content hashes recorded in `/home/chris/git/collie/.project-docs/course-correction-20261013/upstream-v1.21.0/reference-manifest.json` and `/home/chris/git/collie/.project-docs/course-correction-20261013/herdr-v0.9.4/reference-manifest.json`.

## Findings that change the remaining work

1. **The foundation is behind the target.** The initial analysis targeted Collie 1.19.0. The released v1.21 source adds durable-agent conversations, ask cards, send/abort controls, starters, reply context and harness/journal fixes. Reconcile these actual upstream changes before certifying native parity. Do not wholesale overwrite the Android fork or restore its deliberately removed language surface.
2. **Durable-agent support is missing from the native boundary.** Current `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt` lack the upstream `asks`, `turnId`, starter and `/answer`, `/send`, `/abort` workflows. These are real target gaps, not Herdr enhancements. They require native models, repository dispatch and native Chat controls.
3. **Native push is still a contract, not delivery.** `/home/chris/git/collie/bridge/native-push-contract.ts` explicitly says no provider, registration route or delivery service is enabled. The native manifest has no implemented push receiver. Implement the previously selected B22 UnifiedPush/self-hosted ntfy baseline; no provider selection is newly pending. A decoder test is not a background notification.
4. **Several features depend on private Herdr contracts.** Main's `/home/chris/git/collie/bridge/mux/herdr/client.ts` negotiates `agent_original_input_v1`, `agent_observation_cursor_v1`, `pane_original_metadata_v1`, `agent_view_read_v1`, `plugin_user_dirs_v1` and `plugin_owner_operations_v1`. B31's preserved producer contract explicitly describes custom-only support; B32 and B33 likewise require private producer extensions. Remove these as required app dependencies. Do not fake positive capabilities, replace them with unchecked writes, or modify Herdr to satisfy them.
5. **B33 cannot be merged wholesale for this goal.** Its 39-file branch adds private `pane.*.original_v1` methods and host UI actions. Extract useful native scroll/copy/find/link behavior using existing admitted content and Android facilities. Host scrollback editing, private popups and terminal UI administration are not native Collie release requirements.
6. **B34 cannot be merged wholesale for this goal.** Its 92-file branch includes a persistent rendered recorder, observer workflow and owner-media producer system. Extract fixes needed for official Chat, History and authenticated media. Do not build journals/media/control recipes for every Herdr-detected harness merely to fill the former 24×7 grid.
7. **The execution ledger is stale.** It still marks B31/B32 and several others as todo despite code now present on main; many statuses describe deleted intermediate worktrees and older integration revisions. Current source presence, reviewed source and actual runtime acceptance are different facts. Reconcile those facts in the existing ledger during execution instead of repeating implementation based on stale todo labels.
8. **Source approval does not establish app completion.** The old ledger records unresolved lifecycle, APK-update lint and integration/device checks. Reproduce material findings on the consolidated current source before fixing or clearing them. Neither old failures nor previously green component tests establish the present APK's behavior.
9. **The release remains independent of the server/PWA.** Finish the native update/distribution equivalent and the existing signed APK release process. Do not create a new signing/distribution service, change the production signer, or publish server releases merely to complete Android.
10. **Not every upstream difference is a new requirement.** Official v1.21 still has translated UI and voice features. English-only is an accepted fork decision, and speech-to-text is now explicitly excluded. Preserve arbitrary Unicode content. Full Windows host administration, Herdr plugins/layouts/backups/integration management and new universal auth infrastructure are not prerequisites for native Collie parity.

The additional-RPC lists in the reference directory are exploratory comparisons with the upstream Collie adapter. An exact string absent from schema fragments does not prove that a stock Herdr method is absent; some names are generated. Private producer contracts and explicit custom capability negotiation supply the decisive evidence above.

## Prioritized implementation work

These eight work packages replace the old nine-wave expansion. Reuse existing code and tests. A source path below is an existing anchor; newly needed Kotlin models/components are implementation work, not files claimed to exist already.

### 1. Reconcile the released foundation and remove mandatory private dependencies

Compare v1.19-to-v1.21 official changes with current main. Integrate the upstream bridge/contracts/journal fixes needed for the fixed target while preserving the Android fork and its security/language decisions. Update `/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md` to identify the actual supported release and differences. Keep versioning/changelog rules and required Crew wire decisions.

Trace private dependencies from `/home/chris/git/collie/bridge/mux/herdr/client.ts`, `/home/chris/git/collie/bridge/mux/herdr/agent-control.ts`, `/home/chris/git/collie/bridge/host-control/agent-views.ts` and `/home/chris/git/collie/bridge/host-control/herdr.ts` through capability publication, Crew forwarding, HTTP routes and native menu entry points. Required app flows must use official Collie interfaces. Optional unfinished custom controls must not appear as usable features or block those flows. Preserve their committed source/history.

Keep honest capability limits and safe unknown outcomes. Existing rechecks are not vendor atomic compare-and-set. Where an extra guarantee exists only because the expanded plan invented it, correct the requirement rather than patch Herdr or weaken a shipped write guard.

Acceptance: unmodified released Herdr supports ordinary paired native reads, replies, keys, launch/worktrees, Files/Changes and updates without any private capability flag. Supported backend differences stay explicit. Passive native navigation must not steal host focus.

### 2. Add durable-agent native parity

Use the released `PaneWire.asks`, `turnId`, `ControlTarget`, `ControlResult` and launcher starter contracts. Implement native typed models, API calls and repository admission in `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/WireModels.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/CollieRepository.kt`.

Expose Chat-only panes, supported question cards, reply context, new-message/steer/abort and program starters through `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NewActivity.kt`. A screenless pane must not offer terminal keys/mirror operations. Respect requestId, ask revision and turn targeting, and handle stale/refused/unknown/maybe-applied without invented retries.

Acceptance: use the official disposable durable example through Collie; launch a starter, read Chat/History, answer a harmless ask, send/steer/abort, then interrupt/restart and reconcile without duplicate actions. Do not deploy a new paid durable service for this test. References: [official control ADR](https://github.com/AltanS/collie/blob/v1.21.0/.adr/0101-a-control-on-a-screenless-pane-names-the-turn-it-saw.md), [starters ADR](https://github.com/AltanS/collie/blob/v1.21.0/.adr/0102-starters-are-launcher-rows.md).

### 3. Finish and verify the ordinary app workflows

Reuse main's native screens and APIs for dashboard/space/session navigation, pane output and supported harness interactions, Chat/History/images/tool details, Files/Changes, New/folders/worktrees/recipes/runs, attachments, cache context/watch, updates, settings, masks and diagnostics. The 41-row crosswalk supplies each retained requirement's existing paths, action and Pixel check.

Reconcile grammar/journal behavior against the actual v1.21 registries, including OpenClaw history and documented recent question/context fixes. Preserve safe raw fallback for agents without a supported reader. Do not force every detected agent to have a journal, resumable session or media transport that upstream does not provide.

Reproduce the materially relevant old findings on current code, especially retained offline screen lifecycle, update/install behavior and request ownership after screen changes. Fix observed failures; do not repeat the dozens of old source-freeze/review cycles by default.

Acceptance: the user can perform each retained workflow on the current APK. Cancellation, disconnect, server restart, process death and wrong-host/session responses cannot target another pane or authorize writes from cached state. Documentation: [Configure](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md), [Files/Changes](https://github.com/AltanS/collie/blob/v1.21.0/docs/changes.md), [Multiplexers](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### 4. Deliver native notifications

Use the existing B22 provider decision: the UnifiedPush Android connector and operator-configured self-hosted ntfy. Verify the connector's current supported API and lock a compatible released dependency under the repository age policy. Do not use the deprecated connector UI helper or add another provider/FCM requirement.

Finish paired installation registration, endpoint allowlisting, delivery, replacement/revocation and authenticated bounded payload lifecycle on the Collie side. Add the Android receiver, connector enrollment, installation custody, permission/channel UI, replay/expiry handling and exact-pane navigation. Reuse existing Collie event/preferences/cache/machine alert producers. Provider endpoints and credentials remain private; native registration remains distinct from terminal write authorization.

Acceptance: actual provider delivery reaches the closed/background app, honors notification preferences/snooze, handles blocked/done/update/clear, permissions, doze/reboot, duplicate/out-of-order messages and retirement. Notification taps require current pairing and never grant mutation authority. References: [official UnifiedPush connector](https://unifiedpush.org/kdoc/connector/), [ntfy distributor documentation](https://docs.ntfy.sh/subscribe/phone/), [Android permission guidance](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

### 5. Finish Crew and native deployment equivalents

Verify existing machine resources/charts/alerts, correct host/session selection, supported proxy-auth deployments, and the official deputy/standby takeover workflow through configured HTTPS. Reuse `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt`, `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MachinesActivity.kt` and the current authentication modules.

Keep the ordinary paired HTTPS path usable without a newly deployed general-purpose issuer or DPoP gateway. Do not build Windows host services or Herdr machine administration. Use declared official capability differences; missing external environments receive honest limitations rather than invented fixture-based live acceptance.

Acceptance: isolated lead/member scenarios cover host changes, unavailable members, authorized read/write separation and operator-confirmed deputy takeover. Inspect configured ingress expiration/refusal using existing access. Reference: [Crews](https://github.com/AltanS/collie/blob/v1.21.0/docs/crew.md), [Deployment](https://github.com/AltanS/collie/blob/v1.21.0/docs/deployment.md).

### 6. Extract useful B33/B34 work and consolidate safely

Inspect each branch against updated main and select only required, stock-compatible app improvements. B33 selection centers on native scroll/copy/find/links; B34 selection centers on official conversation/history/media corrections. Reuse current native `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OutputFind.kt` and `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PrintedPathLinks.kt` before introducing new components.

Do not wholesale merge the private B33 protocol or broad B34 recorder. Preserve the full existing branch commits before changing or removing worktrees. When selected source is integrated and tested, account explicitly for the preserved out-of-scope remainder. A branch's mere existence does not make all of its work a release requirement. Reuse the current compact recovery stores; no full checkout archives or large copies.

Acceptance: needed fixes are on main, no uncommitted useful implementation is abandoned, and required native workflows do not depend on these excluded systems. Keep only worktrees actually needed for unfinished in-scope work.

### 7. Validate the final integrated app on the Pixel

Use the current API37 project toolchain and the owned physical Pixel accessed through dell-7680. Use the existing recorded runner/access before asking Chris for setup. Freshly identify device/API and build the current APK; old S25/emulator/API26/36 records are historical. Do not uninstall or clear a personal app namespace. Use disposable synthetic terminal content and an isolated test application/server.

Run appropriate native unit/lint/build gates and required bridge/web checks for changed shared code. Apply meaningful current integrated checks; successful compilation, decoder fixtures or previous private-component reviews do not prove runtime parity. Native browser embedding remains prohibited. Check primary workflows plus process death, reconnect/server restart, large text/TalkBack/IME/insets and both themes. Exclude every speech test from this acceptance matrix.

Each retained crosswalk requirement needs a recorded disposition. Required workflows cannot be marked complete by safe refusal where upstream actually supports the action. Unsupported upstream behavior may be honestly unavailable; required behavior blocked by credentials/environment remains open. Continue all independent work rather than declaring the whole effort blocked.

This preparation performed no new build or device acceptance. SSH to dell-7680 returned success, but adb was not found through its noninteractive PATH or four common SDK locations, and no device/API response was obtained. This is not proof the Pixel is offline. Runtime, provider and production-signing availability must be checked through the existing documented routes during execution.

### 8. Produce and publish the Android release

Follow `/home/chris/git/collie/android/RELEASING.md`, `/home/chris/git/collie/scripts/android-build-manifest.sh`, `/home/chris/git/collie/scripts/android-release.sh` and `/home/chris/git/collie/.github/workflows/android-release.yml`. Reuse the production package identity and trusted existing signing key. Increase versionCode, choose the next Android version, update native checker pins/changelog, run required CI/release checks and verify the exact signed APK.

Finish the native Android release discovery/download/install equivalent using the Android release channel and Android-confirmed installation. Preserve pairing/preferences across same-signer upgrades. Server/Crew update controls remain distinct from updating the APK. Never manufacture a signer, silently substitute a debug/unsigned APK, or publish a server/PWA release to satisfy the Android goal.

Review the draft release's source/version/checksum/signer/notices/scan and artifact smoke result, then publish through existing authorized release access when execution is approved. Play Store/F-Droid and unrelated infrastructure are outside this completion effort.

## Completion condition

The retained native Collie capabilities, excluding speech-to-text and retaining English-only UI, work against the fixed official contracts and unmodified Herdr. Current integrated tests and the required Pixel workflows pass; real native notification delivery and same-signer upgrade are demonstrated. Necessary source is committed and consolidated on main. The reviewed signed Android APK and Android release are delivered. Out-of-scope source is preserved and honestly accounted for, not counted as failed parity or silently declared complete.

There is no remaining product-scope choice needed to set this goal. Implementation feasibility, runtime access, provider configuration and signing access remain engineering/environment checks; they are not authorization to expand the product. A genuinely missing credential or unavailable device may require narrowly scoped user help after existing routes are checked, while other work continues.

## Disposition of every original backlog item

Required means retained native functionality. Retain subset means the action below defines the requirement; the expanded remainder is preserved but does not gate release. Exclude expansion means preserve source and remove it as a prerequisite, not claim it is implemented. None of these rows claims fresh device acceptance.

### B01 — Establish scoped contracts, admission and Crew dispatch

Disposition: **required**. Keep paired read/write gates, origin/mount and host/session targeting. Reconcile upstream v1.21 contracts without adding stronger vendor guarantees that stock Herdr does not offer.

Current code: `/home/chris/git/collie/bridge/server.ts`; `/home/chris/git/collie/bridge/control/contracts.ts`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt`.

Acceptance: Wrong-host responses, revoked device, server restart and cancelled requests never admit a write. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/security.md).

### B02 — Persist launch intents and reconcile uncertain outcomes

Disposition: **required**. Reconcile existing launch receipts against upstream requestId semantics. Resolve uncertainty without duplicate launches; prove process-death behavior.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NewActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/data/LaunchIntentStore.kt`; `/home/chris/git/collie/bridge/launch-receipts.ts`.

Acceptance: Launch once, interrupt response, restart app and reconcile the same request without a second pane. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B03 — Make refresh, freshness and read depth truthful

Disposition: **required**. Check truthful refresh and read-depth limits against stock backends; preserve viewport during reads and show stale/unavailable state.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneScrollback.kt`; `/home/chris/git/collie/bridge/mux/herdr/adapter.ts`.

Acceptance: Refresh after host-side changes and page through output without losing position or moving host focus. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B04 — Show authenticated journal images

Disposition: **required**. Validate current authenticated image projection, decode bounds and original host/session after integration.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AuthenticatedImageView.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryTurnRenderer.kt`.

Acceptance: Open real journal images; switch hosts and revoke pairing while an image loads. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B05 — Render complete tool, question and denial details

Disposition: **required**. Bring tool, question and denial cards to v1.21 behavior, including answered question context and new data-based asks.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/StructuredToolPresentation.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryTurnRenderer.kt`.

Acceptance: View tools/denials/questions and answer harmless prompts; stale cards cannot act on a new question. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B06 — Expose safe Send queued now

Disposition: **required**. Verify Send queued now only when the current supported Codex workflow offers it; preserve unknown outcome handling.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CodexAcceptedReplies.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt`.

Acceptance: Queue and send one harmless message, including disconnect during submission. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B07 — Persist version-specific update dismissal

Disposition: **required**. Verify version-specific offer/crew dismissal, snooze and restart persistence with current official update responses.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt`.

Acceptance: Dismiss a version, reopen app, then show a genuinely newer offer. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/upgrading.md).

### B08 — Inspect foreground processes, directories and restore failures

Disposition: **retain subset**. Keep documented pane cwd/agent/session/restore information. Do not require a comprehensive Herdr process-inspection product for parity.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneHeaderPresentation.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneProcessesActivity.kt`.

Acceptance: Pane headers, restored panes and missing session state remain accurate on stock Herdr. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B09 — Close all harness registry and semantic gaps

Disposition: **retain subset**. Match the v1.21 shipped harness and journal support, including OpenClaw journal changes. Remove the requirement to invent journals/actions/media for every Herdr-detected kind. Preserve truthful raw fallback.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/terminal/AgentSemanticParser.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ShippedAgentCommands.kt`; `/home/chris/git/collie/bridge/journal/registry.ts`.

Acceptance: Exercise supported semantic interactions and journal readers with harmless captures/current available vendors; unknown harness remains usable safely as documented. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/claude-code-on-your-phone.md).

### B10 — Add Files, source navigation and inert previews

Disposition: **required**. Verify Files tree, previews/images, nested roots and source navigation; reuse integrated code.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/FilesActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/FilePreviewRenderer.kt`; `/home/chris/git/collie/bridge/files-view.ts`.

Acceptance: Navigate folders/files/images on solo and Crew; reject traversal and keep the selected original host. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/changes.md).

### B11 — Add Changes, diffs and last-commit inspection

Disposition: **required**. Verify diff, changed/untracked files and last-commit behavior against the official Files/Changes page.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ChangesActivity.kt`; `/home/chris/git/collie/bridge/changes.ts`.

Acceptance: Inspect changed files, diff and last commit without modifying the repository. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/changes.md).

### B12 — Control pane splits, resize, zoom and movement

Disposition: **retain subset**. Retain documented create-beside and explicit Show in terminal behavior. Extra splits/resize/zoom/movement are not release requirements solely because Herdr has them; keep stock-compatible existing extras only after bounded review.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LayoutActivity.kt`; `/home/chris/git/collie/bridge/control/layout.ts`.

Acceptance: Create beside and Show in terminal work; passive navigation does not steal terminal focus. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B13 — Organize workspaces and tabs

Disposition: **retain subset**. Require the official create/rename/close spaces/tabs flows. Reordering/moving extra host structures does not become mandatory by inclusion in this backlog.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OrganizeSpacesActivity.kt`; `/home/chris/git/collie/bridge/control/organization.ts`.

Acceptance: Create, rename and close disposable tabs/spaces with capability-aware confirmations. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B14 — Complete New, folders, favorites and recents

Disposition: **required**. Verify launcher rows, agent choices, favorite/recent folders and folder previews. Add v1.21 program starter rows with the upstream restrictions.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/NewActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/FolderPickerView.kt`.

Acceptance: Choose folders, favorite/recent entries and launch a supported starter exactly once. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B15 — Complete worktree planning, creation and safe removal

Disposition: **required**. Verify official worktree plan/create/open/remove workflow. Keep path containment, current target checks and uncertainty; do not claim stock vendor compare-and-set.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/WorktreePlanPresentation.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/WorktreeUiLease.kt`; `/home/chris/git/collie/bridge/worktree-folder.ts`.

Acceptance: Create/open/remove disposable worktree; cancel plan and interrupt create response without replay. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B16 — Manage recipes and one-off runs

Disposition: **required**. Verify added recipes and rename/remove, allowed one-off runs, preflight, recent run removal and clear.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LaunchRecipeActivity.kt`; `/home/chris/git/collie/bridge/launcher-recipes.ts`.

Acceptance: Save/use/rename/remove a recipe and run one harmless allowed command exactly once. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B17 — Upload bounded documents and text attachments

Disposition: **required**. Verify supported image/document/text attachment MIME, size, authorization and cancellation. Preserve terminal semantics rather than exposing a raw local source path. Speech/audio transcription is excluded.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/DocumentAttachment.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt`; `/home/chris/git/collie/bridge/uploads.ts`.

Acceptance: Attach a supported document/image and cancel/switch pane before upload completion. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B18 — Expose authoritative agent identity, sessions and resume state

Disposition: **retain subset**. Require official pane identity, session selection and available history. Stop requiring stronger original-terminal identity/CAS proofs beyond upstream guarantees as a new runtime dependency.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentIdentityActivity.kt`; `/home/chris/git/collie/bridge/mux/herdr/agent-control.ts`.

Acceptance: Switch sessions/panes and verify the displayed agent/history; identity uncertainty never enables a guessed action. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B19 — Implement named agent actions, atomic prompts and waits

Disposition: **retain subset**. Remove private agent_original_input_v1 and agent_observation_cursor_v1 requirements from mandatory app flows. Use existing official Collie reply/keys and v1.21 durable send/abort/answer; retire unavailable automation entry points without deleting preserved source.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentControlActivity.kt`; `/home/chris/git/collie/bridge/mux/herdr/agent-control.ts`; `/home/chris/git/collie/bridge/mux/herdr/client.ts`.

Acceptance: Prompt/reply/keys and durable controls work with an unmodified backend; no private handshake flag is required. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B20 — Expose cache/model/git context and cache-watch lifecycle

Disposition: **required**. Verify cache/model/git context, watch registration/removal and user-visible cache countdown against current docs.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CacheContextPresentation.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CacheWatchActivity.kt`.

Acceptance: Start/stop a cache watch and compare context/countdown with the current host state. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B21 — Search the whole owning-session journal within explicit bounds

Disposition: **retain subset**. Match official bounded Chat/History search and navigation. Whole-session indexing outside upstream capabilities is not a prerequisite. Verify local search/copy behavior without server-side text mutation.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryHighlight.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OutputFind.kt`.

Acceptance: Search visible/history content, load older pages and reopen the owning session without changing scope. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B22 — Deliver real background push and all five notification preferences

Disposition: **required**. Implement real native delivery using the already specified UnifiedPush/self-hosted ntfy baseline. Add Collie registration/delivery and Android connector/receiver/channel/permission lifecycle. Use documented Web Push encryption/connector contracts; reuse prepared payload models where compatible. No FCM/provider expansion.

Current code: `/home/chris/git/collie/bridge/native-push-contract.ts`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/notifications/NativePushPayload.kt`; `/home/chris/git/collie/android/app/src/main/AndroidManifest.xml`.

Acceptance: Receive blocked/done/update notifications while app is closed; test permission denial, doze, replacement, revoke, clear/dedupe and exact-pane tap without granting write authority. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/voice-and-push.md).

### B23 — Add safe links, share import, QR pairing and configured-host App Links

Disposition: **required**. Verify pane/history links, sharing/import and pairing QR as native equivalents. App Links only for operator-controlled configured hosts with verified associations.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieDeepLink.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OpenLinkActivity.kt`.

Acceptance: Import a link/share and QR; reject malformed/foreign origins and require pairing before protected reads. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/security.md).

### B24 — Implement identity-aware native proxy access

Disposition: **retain subset**. Support documented operator-configured front doors using the existing native authentication work where applicable. Do not make a new universal issuer/DPoP gateway deployment a prerequisite for ordinary paired HTTPS users. Preserve credentials and refusal boundaries.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ProxySignInActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/auth/ProxySignInCoordinator.kt`.

Acceptance: Pair through supported configured ingress; fail safely on auth expiry/foreign callbacks without leaking the bearer. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/deployment.md).

### B25 — Restore opt-in encrypted offline dashboard, panes and Chat

Disposition: **required**. Verify opt-in encrypted saved dashboard/pane/Chat reads and lifecycle. Recheck the historical retained-screen failure against the current consolidated source; old failure/review labels are not current test results.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OfflineContentUi.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/RetentionSettingsSection.kt`.

Acceptance: Enable saved reads, go offline/restart/reconnect; cached data never authorizes writes and opt-out removes owned saved data. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B26 — Show machine resources and history

Disposition: **required**. Verify current machine cards and historical resources, metrics availability and Crew scope.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MachinesActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ResourceChartView.kt`; `/home/chris/git/collie/bridge/machines.ts`.

Acceptance: Read solo/Crew resource charts and handle a member going unavailable. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/crew.md).

### B27 — Implement machine alert rules and durable monitoring checkpoints

Disposition: **retain subset**. Retain official machine alert settings and event delivery. New durable monitoring/checkpoint guarantees beyond official functionality are not independent release gates. Join required notification events with B22.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/MachinesActivity.kt`; `/home/chris/git/collie/bridge/crew/monitoring-checkpoint.ts`.

Acceptance: Set/change/remove an alert rule; show a harmless controlled alert through the configured native provider. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/crew.md).

### B28 — Complete independent APK discovery, download and installation

Disposition: **required**. Finish the native app update equivalent: independent Android release discovery/link, download checks and Android-confirmed install with matching package/signer and increasing versionCode. Keep server/Crew updates separate. No new generic distribution/signing service.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt`; `/home/chris/git/collie/scripts/android-release.sh`; `/home/chris/git/collie/.github/workflows/android-release.yml`.

Acceptance: Install the signed candidate and a same-signer higher-version upgrade while preserving pairing/preferences. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/upgrading.md).

### B29 — Export and apply portable layouts and recipes

Disposition: **exclude expansion**. Portable Herdr layout administration is outside native Collie parity. Preserve reviewed source and any safe working extra; do not require new runtime protocols or host layout import/export for release.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/LayoutRecipesActivity.kt`.

Acceptance: Only verify it if retained visibly; it must not obstruct required Collie workflows. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B30 — Manage integrations and manifest reloads

Disposition: **exclude expansion**. Full Herdr integration/manifest administration is outside the app completion goal. Keep ordinary Collie backend/harness capability reporting; hide unsupported optional administrative entry points.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/IntegrationsActivity.kt`; `/home/chris/git/collie/bridge/host-control/integrations.ts`.

Acceptance: Required agent launch and conversation flows function with stock integration configuration. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B31 — Expose bounded metadata and declarative agent views

Disposition: **exclude expansion**. Remove pane_original_metadata_v1 and agent_view_read_v1 as app prerequisites. Keep documented display metadata; preserve custom authoring source as history and remove/hide unavailable custom editor entry points.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentMetadataActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AgentViewActivity.kt`; `/home/chris/git/collie/bridge/host-control/agent-views.ts`.

Acceptance: Normal pane metadata and conversations work without agent.view.get or custom metadata RPCs. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/multiplexers.md).

### B32 — Manage plugins and their runtime controls

Disposition: **exclude expansion**. Remove plugin_user_dirs_v1 and plugin_owner_operations_v1 dependencies from required app paths. Keep documented Collie update/restart actions; full plugin marketplace/config editing is outside parity.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PluginsActivity.kt`; `/home/chris/git/collie/bridge/host-control/herdr.ts`.

Acceptance: Server update UI works by supported Collie contract; no private plugin plan/apply endpoints are required. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/upgrading.md).

### B33 — Complete operator scroll, copy, search, links and host UI actions

Disposition: **retain subset**. Select native copy/find/link/scroll UX pieces from work/b33-operator-controls only where needed. Use admitted Collie content and Android selection/scroll/link handling; do not merge pane.*.original_v1 or private popup/action protocols. Host terminal scroll/edit/UI controls are outside parity.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/OutputFind.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PrintedPathLinks.kt`.

Acceptance: Copy/find/open safe links and scroll the native mirror; host terminal focus/scroll state stays unchanged except explicit supported actions. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B34 — Add rendered observation and concrete history/media equivalents

Disposition: **retain subset**. Select stock-compatible fixes for official Chat/History/media only. A new persistent recorder, observer product, custom media producer and invented journals for all detected agents are outside parity. Preserve the branch until safe extraction is committed.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/AuthenticatedImageView.kt`.

Acceptance: History and supported journal images work for documented harnesses; absent journals remain honestly unavailable. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B35 — Manage saved machines through scoped host administration

Disposition: **retain subset**. Require official Crew membership/status and host navigation. Full Herdr saved-machine administration is outside parity; do not build it to close an old ledger row.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt`.

Acceptance: Select correct host, inspect Crew state and handle unavailable members. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/crew.md).

### B36 — Complete named sessions, persistence, backup/restore and handoff

Disposition: **retain subset**. Require documented multi-session selection/state. Herdr backup/restore/handoff administration is outside native parity; display upstream restore failures honestly.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneSwitcherModel.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SpaceViewModel.kt`; `/home/chris/git/collie/bridge/sessions.ts`.

Acceptance: Switch and reopen owning sessions; handle stale/gone session without redirecting a write. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B37 — Close Windows Crew, private-state, build and action gaps

Disposition: **retain subset**. Consume the documented Windows Collie contract and capability limits; do not develop new Windows Herdr/Crew/build/service features. Retain applicable existing private-state protections.

Current code: `/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md`; `/home/chris/git/collie/bridge/owner-only.ts`.

Acceptance: Document an unavailable Windows environment honestly; a fixture does not prove live Windows behavior. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/windows.md).

### B38 — Expose verified deputy ingress and native takeover

Disposition: **required**. Implement/verify the official deputy/standby takeover user workflow through trusted configured HTTPS. No new Herdr takeover APIs or general HA platform.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackActivity.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt`; `/home/chris/git/collie/bridge/crew/standby.ts`.

Acceptance: On an isolated Crew, test lead loss, user-confirmed takeover and lead return without duplicate terminal actions. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/crew.md).

### B39 — Complete local settings, operator fonts, pins and keypad editing

Disposition: **required**. Verify themes, Zen, fonts/operator fonts, pins, quick replies, commands/keypad, draft preferences and masks as native workflows. Keep explicit English-only decision.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PreferredTypeface.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/CollieKeyRow.kt`.

Acceptance: Change settings/restart, use operator-defined rows, and check both themes, large text, TalkBack and IME. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B40 — Complete configuration, masking, uploads and access diagnostics; exclude STT

Disposition: **required**. Verify config-driven controls, secret masking, upload limits and authorization/refusal diagnostics against official Collie behavior. Speech-to-text, voice input, speech model/engine setup and their acceptance checks are explicitly excluded; preserve existing speech source.

Current code: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt`; `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt`.

Acceptance: Verify masked output, configured upload limits and useful refusal diagnostics without recording or transcription. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/configure.md).

### B41 — Reconcile documentation and close the complete capability ledger

Disposition: **required**. Use this corrected scope, not all41/75/132/102 as immutable acceptance counts. Update existing docs/evidence to current integrated source and run release checks; no new evidence framework.

Current code: `/home/chris/git/collie/android/acceptance/capability-execution-ledger.json`; `/home/chris/git/collie/android/RELEASING.md`; `/home/chris/git/collie/android/UPSTREAM_API_COMPATIBILITY.md`.

Acceptance: Acceptance records name actual commit/APK/hash/device/backend and distinguish fixture, JVM and live checks. [Official reference](https://github.com/AltanS/collie/blob/v1.21.0/docs/security.md).
