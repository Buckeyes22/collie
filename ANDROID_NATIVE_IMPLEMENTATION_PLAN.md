# Collie Native Android Implementation Plan

**Status:** Full native route, control, and agent-grammar implementation present; the 2026-09-05 source-parity remediation has passed the combined build/test gate plus live S25 Ultra read and isolated write interaction passes; release signing, destructive/offline/accessibility cases, and external-camera visual comparison remain pending  
**Date:** 2026-09-05  
**Application ID:** `com.lateapex.collie`  
**Initial device:** Samsung Galaxy S25 Ultra (`SM-S938U1`)  
**Backend:** An operator-configured Collie HTTPS origin; initial default
`https://ed8.taile7b6b1.ts.net/`

## 1. Objective

Replace the generated Trusted Web Activity shell with a fully native Android application that
talks directly to Collie's existing HTTP API. The Android client must use Android UI, lifecycle,
storage, networking, and notification facilities; it must not render Collie's website in a WebView,
Custom Tab, Trusted Web Activity, or Capacitor container.

The implemented client lets an operator:

1. configure and validate a Collie HTTPS origin;
2. pair the phone using the existing short-lived pairing code flow;
3. monitor agent and shell panes from the native dashboard;
4. browse spaces, tabs, panes, launchers, worktrees, and bounded conversation history;
5. open a pane, render raw or agent-adapted content safely, and refresh it without transferring
   unchanged text;
6. send prompt-bound replies, semantic agent actions, quick replies, named terminal keys, selected
   images, and recorded speech through the existing STT route;
7. create, rename, focus, and close supported structural resources with capability gates and
   confirmations;
8. inspect and change notification preferences, paired devices, pack state, and Collie updates;
9. use the English-only product surface; and
10. distinguish live, stale, disconnected, unauthorised, unpaired, and bridge-restart states.

Native background notifications remain deliberately outside the implemented HTTP-client boundary
until an Android delivery provider and bridge registration contract are selected and reviewed.

## 2. Architecture decision

The Android application is an independent Kotlin client of the existing Collie bridge.

- UI is built from Android-native views and resources. The initial implementation uses a
  single-purpose Activity/ViewModel structure and View Binding, following the working patterns in
  PhantomLane. A browser rendering engine is not part of the application.
- OkHttp performs HTTPS requests and Kotlin serialization parses the bridge's JSON.
- A repository is the only layer allowed to combine API calls, credentials, scope, polling, and
  cached pane bodies.
- ViewModels own screen state and lifecycle-aware coroutines. Activities render immutable state and
  forward named user actions.
- The pairing bearer is encrypted with an AES/GCM key held by Android Keystore. Backups are disabled,
  and neither the token nor terminal output is written to logs, saved-state bundles, screenshots,
  analytics, or crash-reporting SDKs.
- The existing PWA remains supported as a separate client. Native Android does not require CORS,
  a JavaScript bridge, or API origin changes.

This decision supersedes ADR 0035. A new ADR records why direct native REST calls are safe and what
must replace browser Web Push.

## 3. Reference applications

### 3.1 PhantomLane

Use these patterns:

- Kotlin DSL Gradle project with exact plugin/dependency versions and dependency locking.
- Java 17 bytecode, min SDK 26, AndroidX, View Binding, lifecycle-aware ViewModels, coroutines,
  OkHttp, Kotlin serialization, MockWebServer, Robolectric, and instrumented device tests.
- `AndroidKeystoreCipher`-style non-exportable AES/GCM key for encrypted preferences.
- Release-only HTTPS authority, debug fixture isolation, `allowBackup=false`, explicit exported
  components, and cleartext disabled.
- Small presenters/repositories with deterministic unit tests instead of business logic in an
  Activity.

Do not copy PhantomLane product identifiers, endpoints, credentials, signing material, permissions,
telemetry services, widgets, or Wear OS surfaces.

### 3.2 StormLens

Use its Android operational patterns where applicable:

- committed Gradle wrapper and repeatable shell entry points;
- explicit debug network-security configuration only when a fixture actually needs it;
- manifest verification, emulator/device smoke scripts, and packaged-origin tests;
- physical-device acceptance evidence instead of treating compilation as UI acceptance.

Do not copy StormLens's Capacitor/WebView foundation. Collie's Android client is native.

## 4. Compatibility and toolchain

Initial pinned baseline:

| Component | Decision |
| --- | --- |
| Android package | `versionName=1.5.1`, monotonic `versionCode=2` |
| Compile/target SDK | 36 |
| Minimum SDK | 26 |
| JDK/JVM target | JDK 21 toolchain, Java/Kotlin 17 bytecode |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin plugins | 2.0.21 |
| HTTP | OkHttp 4.12.0 |
| JSON | kotlinx-serialization-json 1.7.3 |
| Async/state | kotlinx-coroutines 1.10.2, lifecycle 2.8.7 |
| UI | AndroidX AppCompat/Material, View Binding, RecyclerView |
| Unit tests | JUnit 4, coroutines-test, MockWebServer, Robolectric |
| Device tests | AndroidX Test, Espresso |

The wrapper is the only supported Gradle entry point. Android dependencies are isolated under
`android/`; Collie's root Bun install and production bridge build must not require an Android SDK.

## 5. Native client boundaries

### 5.1 Origin configuration

The connection screen accepts an origin, not an arbitrary API URL. Validation must:

- require `https`;
- reject user-info, query, fragment, and non-root paths;
- require a non-empty host;
- normalize a trailing slash consistently;
- never disable TLS certificate or hostname validation; and
- never silently fall back to HTTP.

The default origin is a convenience, not a hard security trust grant. The operator may replace it
with another private Collie deployment.

### 5.2 Request contract

Every API request sends:

- `Accept: application/json`;
- `X-Requested-With: XMLHttpRequest`, matching the browser client and fronting identity proxies;
- `Authorization: Bearer <token>` when a token exists; and
- bounded connect/read/write/call timeouts.

Every mutating request additionally sends `Origin: <configured-origin>`. Collie's write gate requires
an origin, and the value must match the request host. Native code can set this header directly; no
CORS change is needed because CORS is a browser enforcement mechanism.

Pane and history reads send `X-Collie-Seen: 1` only when the screen is actually viewed. Polling the
dashboard must not clear unseen completion state.

Redirects are not followed for API calls. A 3xx from an identity proxy becomes an explicit sign-in
state; it must not carry the bearer token to another origin.

### 5.3 Credential storage

Persist one connection record:

- normalized origin;
- display label chosen by the operator;
- encrypted bearer token, when paired; and
- non-sensitive preferences such as selected theme or polling interval.

The token is encrypted with AES/GCM using a non-exportable Android Keystore key. Encryption uses a
fresh random IV. Corrupt or undecryptable ciphertext is deleted and treated as unpaired. The
credential must never be placed in `Intent` extras, `Bundle`, `SavedStateHandle`, logs, clipboard,
screenshots, or backup data.

### 5.4 API parsing

Models mirror the bridge's wire names and use `ignoreUnknownKeys=true` so a newer bridge may add
optional fields. Required identity and control fields remain non-null; malformed successful bodies
surface as protocol errors rather than being filled with invented values.

The implemented API surface is:

| Method | Route | Native use |
| --- | --- | --- |
| GET | `/api/health` | connection/build probe |
| GET | `/api/snapshot` | dashboard and scope inventory |
| GET | `/api/pane/:id?lines=<bounded>` | terminal grid and live scrollback growth |
| POST | `/api/refresh` | foreground refresh hint |
| POST | `/api/pair` | exchange short-lived code for bearer |
| GET | `/api/devices` | show whether pairing is enforced/current |
| POST | `/api/devices/revoke` | revoke a named paired device |
| GET | `/api/config` | multiplexer capabilities, launchers, keys, and agent commands |
| GET | `/api/pack` | pack identity, members, health, and versions |
| GET | `/api/pane/:id/history` | bounded conversation history pagination |
| POST | `/api/pane/:id/focus\|rename\|close` | capability-gated pane actions |
| POST | `/api/pane/:id/reply` | type text and optionally submit |
| POST | `/api/pane/:id/keys` | explicit terminal key sequence |
| POST | `/api/pane/:id/upload` | bounded system-picker image upload |
| POST | `/api/tab`, `/api/workspace` | create tabs and workspaces |
| POST | `/api/tab/:id/rename\|close` | confirmed tab actions |
| GET/POST | `/api/workspace/:id/worktrees`, `/worktree`, `/worktree/open` | list, create, and open worktrees |
| GET/POST | `/api/launchers`, `/api/launch` | list server-approved launchers and invoke an exact returned command |
| POST | `/api/stt` | bounded recorded-audio transcription |
| GET/POST | `/api/notifications/prefs`, `/api/notifications/snooze` | notification preferences and snooze state |
| GET/POST | `/api/update/check`, `/api/update`, `/api/update/snooze` | preflight, start, progress, and reminder state |
| GET | `/standby/update` | retain update progress while the front bridge restarts |

These update routes manage the connected Collie server, not the Android package. Native UI must
call them server updates, show the server version separately from the Android app build, and never
imply that a successful bridge update downloaded or installed an APK.

All scoped routes preserve Collie's `host` then `session` query order. Pane identity is the tuple
`(host, session, paneId)`, never `paneId` alone; conditional body reuse additionally includes the
requested line window.

### 5.5 Polling and caching

- Poll only while the owning screen is at least STARTED.
- Dashboard default cadence is 3 seconds while agents need attention, 5 seconds while work is
  active, and 12 seconds when idle.
- Pane default cadence is 2 seconds while visible.
- Stop polling when backgrounded; on resume issue best-effort `/api/refresh`, then read immediately.
- Exponential network retry is capped and reset by a successful response.
- Retain the last successful state during transient failure and mark it stale with elapsed time.
- Store a pane's ETag only with the successfully parsed body and requested line window it represents.
  A 304 may reuse only that exact `(host, session, paneId, requestedLines)` cache entry. Keep at most
  20 pane entries in memory and persist none.
- Cancel superseded calls when scope or pane changes.

## 6. Native user experience

### 6.1 Connection and pairing

On first launch, show:

- origin field prefilled with the known private deployment;
- device label field defaulted from the Android model, editable and length-bounded;
- pairing code field;
- **Connect read-only** and **Pair and connect** actions; and
- precise HTTPS, identity-proxy, unreachable, invalid-code, expired-code, duplicate-label, and TLS
  errors.

A read-only connection is valid because Collie gates reads and writes separately. When a write is
refused with `device not paired`, return to pairing without discarding the last readable dashboard.

### 6.2 Dashboard

The dashboard must reproduce the web application's information architecture and density rather
than reinterpret it as a generic Material card feed. The web implementation and `DESIGN.md` are the
visual source of truth. Render panes in Collie's client-derived triage order:

- blocked/needs-you;
- done/unseen (ready);
- working; and
- recent.

Blocked and ready items use individual attention cards. Working and recent items use flat rows in
one shared, thin-bordered group per section. Spaces use the same grouped-row language and expose a
compact filter when needed. Each row shows the best available name, agent, workspace, optional
tab/host/session, status, hint, time, and stale/unreachable state without redundantly spelling a
status already communicated by its section and dot.

The native screen mirrors the web header: 60 dp high below system insets, dog mark and stacked
`COLLIE`/host wordmark at the left, and a 44 dp settings target at the right. Content has a 16 dp
gutter, 20 dp section separation, one aligned left edge, 2 dp corner radii, thin rule hierarchy, and
no large floating cards. Pull-to-refresh, retry, connection settings, all-clear, loading, empty,
stale, and disconnected states must preserve the same structure without layout shifts.

### 6.3 Pane screen

Render pane output as text spans, never HTML. The ANSI parser supports the SGR subset used by Collie:
reset, bold/dim/italic/underline, standard/bright foreground and background colours, and 256/RGB
colours. Unsupported control sequences are stripped; control characters are never executed.

The output uses a monospace font on a fixed dark terminal surface and scrolls vertically. Do not
implement a terminal emulator or resize/control the shared PTY. The bridge already returns a
rendered grid.

The pane chrome also follows the web implementation: the shared compact header, centered
workspace/pane title and cwd metadata, overflow action, read-only or stale strip when applicable,
thin tab strip, terminal consuming the remaining height, and a compact bottom composer. Terminal
text is 10 sp with web-equivalent line spacing and wrapping; its ANSI base palette matches the web
terminal exactly. Named input modes (`Keys`, `Type`, `Quick`, and `Agent`) are short controls rather
than full-width Material buttons, and the text field/send affordance remains one compact row above
the safe navigation inset.

Composer behavior:

- sending text is an explicit tap;
- blank text is refused locally;
- one request is in flight at a time;
- a partial failure with `textDelivered=true` must not auto-retry;
- a 409 `prompt_changed` is shown as a stale-dialog refusal;
- keys are limited to visible named controls such as Escape, Enter, Tab, arrows, and Ctrl-C;
- no arbitrary raw escape bytes are accepted from UI text.

At the top of a live pane buffer, an agent pane with a reported session and a capable multiplexer
offers **Show entire history**. Otherwise, a pane with declared grid scrollback and a known deeper
`readableLines` value offers **Load older**. Loading grows the request from 600 lines to Herdr's
current 1,000-line clamp, freezes tail-following, adopts only the matching grown response, and
restores the reader's position by the prepended-height delta. Transcript and grid-scrollback
capabilities remain independent, so a multiplexer limitation may be explained beneath a working
**Load older** action.

Unsent pane drafts are scoped by origin, host, session, and pane, expire after 48 hours, and persist
only up to the bounded local size; larger drafts remain memory-only with a warning. Hardware named
keys in direct-typing mode use the same key grammar as on-screen controls, while Ctrl-Enter and
Command-Enter enter the guarded reply path without generating a duplicate Enter on key-up.

One-shot free-text input re-reads the same scoped pane immediately before the write and binds the
write to the exact visible prompt region. Known web-adapted agent families require a positively
recognized ordinary composer from their matching native grammar; existing drafts and modal or torn
screens fail closed. Agent families without a web harness adapter retain the web client's verified
visible-tail fallback. Named keys remain explicit operator actions and are separately bound to
freshly verified visible-tail evidence.

### 6.4 Settings, administration, and disconnect

Settings show the origin, pairing label, app/build version, connection status, theme, typeface,
terminal and draft sizing, notification preferences/snooze, paired devices, pack status,
and update status. Device revocation and local disconnect are separately named and confirmed.
Updates require a usable preflight plus retained, snapshot-derived device authorization; polling
falls through to `/standby/update` during a bridge restart without erasing visible progress.

## 7. Security requirements

- `INTERNET` and runtime `RECORD_AUDIO` for operator-initiated STT are the only requested
  permissions; image selection uses the system picker and requires no camera or broad media/storage
  access. Location, contacts, accessibility, overlay, VPN, and package-install permissions remain
  absent.
- `android:usesCleartextTraffic="false"`, `android:allowBackup="false"`, and data-extraction rules
  excluding all application data are mandatory.
- Only the launcher Activity is exported.
- Release builds are non-debuggable and minified; keep rules preserve only serialized wire models
  when necessary.
- No analytics, advertising, crash-reporting, Firebase, WebView, browser-helper, or JavaScript bridge
  dependency.
- OkHttp logging interceptors are prohibited in release and must never log request/response bodies.
- All API redirects are rejected before a credential could cross origins.
- TLS uses the platform trust store and hostname verification without bypasses or user-installed
  trust-all managers.
- `FLAG_SECURE` protects screens that display terminal content or pairing credentials from ordinary
  screenshots and recent-app previews. A user-visible setting may relax only terminal screenshots,
  never the pairing-code/token screen.
- Treat every terminal write as remote shell control. Structural mutations are capability-gated,
  require a paired bearer plus current or retained snapshot device authorization, never retry an
  ambiguous response, and require UI confirmation where destructive.

## 8. Native notifications

Browser Push subscriptions cannot be reused by native Android code. TWA delegation is removed with
the TWA, and a complete native push design requires a second delivery provider.

Native notifications are therefore a pending external integration, not a fake compatibility
checkbox. Before enabling
them, implement and review:

1. a bridge-side notification-provider abstraction retaining the existing Web Push provider;
2. a native device subscription schema distinct from browser PushSubscription;
3. Firebase Cloud Messaging or another Android-capable delivery service selected by the operator;
4. repository-external handling of provider credentials and `google-services.json`;
5. pairing-authenticated registration, replacement, and revocation;
6. server-side routing/deduplication so one event does not alert the same physical device twice;
7. notification channels for blocked, done, and updates;
8. deep-link identity `(host, session, paneId)`; and
9. tests proving a notification cannot authorize a terminal write.

Until Phase 3 is configured, the native app provides foreground live updates only and states that
limitation honestly in Settings.

## 9. Project layout

```text
android/
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── gradlew, gradlew.bat
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/lateapex/collie/
        │   │   ├── CollieApplication.kt
        │   │   ├── data/
        │   │   ├── network/
        │   │   ├── domain/
        │   │   └── ui/
        │   └── res/
        ├── test/
        └── androidTest/
```

Do not generate or track a Node package, Bubblewrap manifest, Digital Asset Links file, APK/AAB,
keystore, `local.properties`, `.gradle/`, or build outputs.

## 10. Implementation and remaining phases

### Phase 0 — reverse the TWA decision — implemented

- Add this implementation plan.
- Add ADR 0036 accepting a native REST client and mark ADR 0035 superseded.
- Replace the short TWA rule in `CLAUDE.md`.
- Mark the TWA specification and implementation plan superseded rather than rewriting history.
- Remove Bubblewrap, Android Browser Helper, Digital Asset Links checks, and TWA-only sources.

Exit: no tracked source or build dependency can launch a browser surface.

### Phase 1 — build the native client foundation — implemented

- Convert Gradle to Kotlin/Android native tooling with dependency locks.
- Add hardened manifest, Application container, encrypted connection store, origin validator,
  OkHttp API client, wire models, repository, dashboard, pane view, composer, and settings/reset.
- Add unit tests for origin validation, encryption envelope handling, request headers, redirects,
  parsing, scope encoding, ETag behavior, ANSI parsing, and ViewModel transitions.
- Update CI to run dependency verification/locking checks, unit tests, lint, and debug assemble.

Exit status: the native project, hardened boundary, test suites, and CI gates exist. Serialized
baseline and remediated combined gates were recorded passing on 2026-09-04 and 2026-09-05.
Release signing and the remaining acceptance items below are still required before distribution.

### Physical-device acceptance — partial pass recorded 2026-09-04

Completed on the paired Samsung Galaxy S25 Ultra (`SM-S938U1`, Android/API 36) with the final debug
artifact installed as an upgrade so the existing encrypted pairing state was retained:

- [x] Launch and every inspected route used native Android activities with no browser UI.
- [x] The dashboard listed the live origin's panes over Tailscale after process death and relaunch.
- [x] A harmless reply reached a disposable Herdr shell exactly once, and the named Escape key was
      delivered without submitting the draft.
- [x] Keys and Quick drawers opened, their controls responded, and Back closed the drawer before
      leaving the pane. Active-tab re-tap and long-press both opened the tab action sheet; the
      upward switcher gesture opened the grouped pane switcher.
- [x] An unsent draft survived process death, multiline insertion remained unsent, and the system
      image picker opened and cancelled back to the same pane without broad media permission.
- [x] Pane output search and bounded History search returned the expected match; Settings and
      Updates opened, scrolled, and showed live device authorization, diagnostics, and preflight.
- [x] Portrait content stayed between the 128 px status inset and 168 px navigation inset; the
      composer stayed above the IME. A landscape configuration change and return preserved the
      active native route.
- [x] `dumpsys package` showed API 26 minimum/API 36 target, only Internet and microphone app
      permissions, microphone still denied until explicit use, and only the launcher entry point
      in the launcher resolver.
- [x] APK identity, debug signer digest, checksum, source commit, device build, and timestamp were
      captured locally without credentials or terminal content.

Not claimed by this pass:

- A clean reinstall/new pairing was intentionally not performed because it would destroy the
  operator's retained connection; upgrade and process-death persistence were verified instead.
- Device-wide network disruption, destructive revocation/update execution, TalkBack/font-scale,
  alternate navigation mode, and real STT upload remain dedicated acceptance cases. The live
  bridge reported native background delivery and STT unavailable, so those controls were not
  represented as successful device tests.

### API 36 emulator parity pass — completed 2026-09-04

The final debug APK was installed and exercised only on `emulator-5554`
(`stormlens_api36`, 1080×2400, density 420). Neither connected physical phone was addressed during
this final pass.

- [x] A live read-only connection rendered the private Collie origin, survived force-stop/cold
      relaunch, and returned directly to the populated dashboard without another connection step.
- [x] Dashboard content remained within `[0,128]..[1080,2337]`, rendered the update ribbon and
      triage/Spaces hierarchy, used Claude/Codex/OpenCode marks, and omitted the old `OC |` prefix.
- [x] A large live OpenCode pane rendered and continued polling without the former light-theme ANR;
      Pane actions opened as the canonical bottom sheet, Display opened in-flow, and Find replaced
      the header at `y=128..265` while tabs remained immediately below it.
- [x] Fresh History opened at `60/670`, proving the initial-scroll expansion race no longer turns a
      fresh route into a 300-entry render.
- [x] Settings followed the canonical order and capability gates, contained no separate diagnostics
      or native-security cards, showed the response-header server build, and recreated successfully
      after switching to dark theme.
- [x] Updates rendered its two-card structure and six live preflight checks; read-only mode kept the
      install action disabled. Space selection reduced content to the chosen tab, Back restored All,
      and active-tab re-tap opened the canonical pull-up sheet.
- [x] Four non-destructive device tests passed on the emulator: agent artwork, both launcher/pane
      system-bar inset cases, and composer-door wiring. The credential-wiping instrumentation cases
      were intentionally excluded from this retained-session run.
- [x] Logcat contained no Collie fatal exception or ANR during the final route pass.

`FLAG_SECURE` correctly makes ordinary emulator screenshots black. Final visual evidence therefore
uses view/accessibility bounds and behavioral interaction, not a claim of pixel-identical captured
bitmaps. External-camera comparison on a physical device remains a distribution acceptance item.

### Source-parity remediation — implemented and integrated 2026-09-05

The repository now includes a second source-driven parity pass. The combined worktree completed a
fresh serialized JVM/lint/build gate and a non-destructive S25 Ultra instrumentation pass after all
concurrent slices settled.

- Live-pane history and scrollback now use the same separate capability decisions as the web app:
  session-backed agent panes open the entire transcript; panes with known deeper grid history load a
  bounded older window and retain the reader's anchored position. Missing-session and unsupported-
  multiplexer explanations no longer become dead controls.
- `/api/config` operator key rows are incorporated into the native Keys surface through the existing
  named-key grammar and guarded write path instead of being discarded in favor of built-ins only.
- Pane-scoped draft persistence now has a 48-hour TTL and bounded persisted size. Oversized drafts
  stay memory-only, no-echo content is excluded from persistence, and successful sends clear the
  matching stored draft.
- Hardware Escape, Tab, arrow, Backspace, and Enter input in direct-typing mode is translated to
  named keys; Ctrl-Enter and Command-Enter use the ordinary guarded send path with key-up suppression.
- Dashboard and Space source now include the web-order shared footer, pack/build/update projections,
  persisted section disclosure and Recent ordering, connection and idle overlays, and the current
  update ribbon dispositions.
- Settings source now exposes the canonical route/card hierarchy, local display preferences,
  connection identity, server-backed notification/device/pack/update controls, destructive-action
  separation, and leading card identity artwork.
- The Keys tray now mirrors the web interaction model: Keys/123 segments, primary and digit grids,
  modifier modes, removable staged-key chips, one-character chord input, collapsible presets and
  function keys, guarded discard, and result-attributed press feedback.
- Current-pane and strip actions use one pull-up sheet instance for action, rename, cancel, and
  two-tap close modes. Healthy snapshots evict panes closed elsewhere while preserving the
  optimistic bootstrap for a newly created pane until it first appears.
- History reader state and dashboard host/session scope survive recreation. Persisted scope is
  checked against a fresh unscoped roster before a filtered request is published.
- Multi-host pane rows expose host/session identity and health. New Space selects and validates an
  explicit writable machine and threads that scope through plain-space and worktree operations.
- Pairing links retain their code/device anchor, focus the Devices form, and server-backed Settings
  mutations share one visible busy gate instead of accepting unsent optimistic toggles.

The 2026-09-05 serialized gate passed 378 JVM/Robolectric tests with zero failures, Android lint,
debug APK assembly, instrumentation APK assembly, and minified release assembly. The English-only
and native-boundary contracts passed 21/21. The debug APK was upgrade-installed on the S25 Ultra,
and four non-destructive device tests passed (agent artwork, composer-door wiring, and both launcher
and pane system-bar inset cases). Once the handset became available, a read-only interactive pass
also verified the live dashboard and pane, Keys, Quick, Agent, and Display drawers, the grouped pane
switcher, Pane actions, History, and Settings. Pairing survived the upgrade install; live content
remained inside the status/navigation insets; no terminal key, quick reply, agent command,
structural action, update, revocation, or server mutation was invoked.

### Live-pane performance remediation — completed 2026-09-05

S25 Ultra profiling found that an unusually large, frequently changing coding-agent pane could
consume a full CPU core and force 100–300 ms UI frames. The native client now:

- performs ANSI/link preparation and text precomputation away from the frame-producing thread;
- caches pane grammar analysis by bridge revision and uses a lightweight control stripper for
  semantic/composer scans;
- bounds the live visual mirror to 16 KiB and 1 KiB per pathological row, with an explicit English
  omission marker, while prompt verification still uses the complete response and History remains
  the unabridged session reader; and
- limits independent table-pan containers to the newest table instead of rebuilding an unbounded
  nested view hierarchy every two-second poll.

On the same active S25 Ultra pane, the steady-state debug-build trace improved from 75% janky frames
with 117/150/300 ms 50th/90th/99th percentiles and roughly 100% CPU to 0% legacy jank with
9/11/13 ms percentiles and brief 14–26% refresh bursts. A follow-up scroll/drawer/switcher pass had
8/16/57 ms percentiles, no fatal exception, no new ANR, and no terminal write.

### Composer and wrapping regression remediation — completed 2026-09-05

A physical S25 Ultra pass then reproduced three release-blocking interaction failures and corrected
them against a disposable Herdr shell workspace:

- the normal Send action had rejected current Codex's painted `model · cwd · Main [default]`
  status row because native prompt binding still required the older `Context N% left` field;
- Type mode now forwards both committed IME text and physical printable key events, and explicitly
  opens the Samsung soft keyboard when armed; and
- wrapped output pins the full terminal mirror to horizontal offset zero and reports no horizontal
  scroll action, while the explicit Wrap-lines-off preference and bounded table scrollers retain
  their intentional pan behavior.

The disposable pane observed `COLLIE_NATIVE_REPLY_OK_0905` through normal Reply, then observed
`COLLIE_NATIVE_TYPE_OK_0905` arrive through Type and execute through the Keys drawer's Enter action.
The final installed build reported the IME shown after one Type tap, exposed the current live Codex
Send action as enabled, and exposed the wrapped live mirror as non-scrollable horizontally. The full
gate passed 389 JVM/Robolectric tests, Android lint, debug and instrumentation APK assembly, and all
21 English-only/native-boundary checks.

### Visual-parity acceptance — hierarchy and behavior pass; external comparison pending

- Capture the live web dashboard and pane at a fixed mobile CSS viewport before changing native UI.
- Capture the same live dashboard and pane from the S25 Ultra at its real density after each major
  layout pass; use an upgrade install so encrypted pairing state survives.
- Compare paired screenshots for hierarchy, visible row count, typography, gutters, dividers,
  colors, system-bar containment, terminal density, and composer height.
- Test the dashboard and pane at 360 dp and 390 dp widths, with three-button and gesture navigation,
  with the keyboard open, and after rotation/background-resume.
- Keep release screenshots protected by `FLAG_SECURE`; any debug-only screenshot exception used for
  local comparison must be removed before the final APK is installed.
- Compare the final secure build with an external camera when `FLAG_SECURE` prevents trustworthy
  on-device capture. That pixel comparison is pending external evidence and must not be inferred
  from layout/resource tests.

### Phase 2 — route, interaction, and grammar parity — implemented

- Agent-specific semantic adapters separate supported conversations, composers, dialogs, menus,
  drafts, and status regions while preserving an explicit raw-terminal disposition. Unsupported or
  ambiguous adapted-agent states fail closed; OpenCode and shell retain their verified fallback.
- Spaces, tabs, sessions, and pack host scope navigation.
- Conversation history with bounded pagination.
- Operator commands, key rows, and quick replies from `/api/config`.
- Launcher rows and allowed launch actions.
- Pane/tab rename, focus, close, and creation with confirmations.
- Image upload through Android's system picker with no broad storage permission.
- Native audio capture and existing `/api/stt` upload with runtime microphone permission handling.
- Device list/revocation, notification preferences, update status/action, worktrees, and pack report.

Exit status: the native routes and controls above have explicit implementations and test coverage,
including history/settings/pack/update surfaces, media/STT, device authorization, and standby
update progress. The combined 2026-09-05 serialized source/build gate and safe physical-device
instrumentation gate are recorded above.

### Phase 3 — background notifications — pending external design/provider

- Select and configure the Android delivery provider.
- Extend the bridge behind the existing notification-provider seam.
- Add registration/revocation and native notification/deep-link handling.
- Verify doze, force-stop, reboot, channel settings, duplicate suppression, and token rotation.

Exit: blocked/done/update alerts arrive with the app backgrounded and open the exact pane without
granting write authority.

### Phase 4 — release hardening

- Configure an external release keystore and recoverable backup without committing secrets.
- Retain R8/resource shrinking and inspect the release manifest/dependency graph.
- Produce SBOM/dependency audit evidence and resolve findings by upgrade or explicit disposition.
- Run Macrobenchmark/baseline-profile work only if measured startup/rendering data justifies it.
- Complete accessibility, font scaling, TalkBack, contrast, reduced-motion, rotation, foldable,
  battery, and network-transition testing.
- Decide private sideloading versus managed/private Play distribution.

Exit: a release-signed APK/AAB has reproducible provenance and completed physical-device acceptance.

## 11. Verification commands

From `android/`:

```bash
export ANDROID_HOME=/home/chris/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew --no-daemon :app:dependencies --write-locks
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

From the repository root:

```bash
bash scripts/check-version.sh
bun run typecheck
cd web && bun run typecheck
bun run test
cd web && bun run test
bun run lint
git diff --check
```

Physical-device build/install:

```bash
/home/chris/Android/Sdk/platform-tools/adb devices -l
/home/chris/Android/Sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
/home/chris/Android/Sdk/platform-tools/adb shell am start -n \
  com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
```

No command in this plan pushes a branch, opens a PR, publishes an artifact, deploys a backend,
creates a Firebase project, or changes a production service. Those require separately explicit
authorization.

## 12. Acceptance criteria and current verification

Implemented source requirements:

- [x] `android/` is Kotlin/native Android with no TWA, WebView, Custom Tab, Capacitor, or browser
      helper runtime.
- [x] HTTPS-only origin validation, redirect refusal, Android Keystore bearer encryption, backup
      exclusion, `FLAG_SECURE`, and a minimal exported/permission surface are encoded and checked.
- [x] Dashboard, Space, pane, history, Settings, Pack, and Updates routes have native dispositions.
- [x] Pane content supports semantic agent adapters plus an explicit raw-terminal disposition;
      prompt-bound text and key writes fail closed when required evidence changes.
- [x] Image picker upload, runtime microphone capture/STT, launchers, worktrees, structural actions,
      notification preferences, device revocation, pack state, and update controls are implemented.
- [x] Writes require the stored bearer and a successful current/retained snapshot authorization
      decision; unknown enforced status is fail-closed while older/non-enforced servers remain
      compatible after a successful snapshot.
- [x] `/standby/update` preserves the freshest visible update run across a front-door restart.
- [x] English base resources contain all native interface strings and plurals, with a hardcoded-copy
      guard and no localized overlays.
- [x] CI and repository checks cover native configuration, dependency locks, unit/Robolectric
      tests, instrumentation assembly, lint, debug assembly, and minified release assembly.

Verification status through 2026-09-05:

- The English-only resource guard, strict repository lint, root and web TypeScript typechecks,
  version check, native static checker, and its own assertion suite were recorded passing in the
  2026-09-04 baseline.
- Focused JVM/Robolectric tests cover the 2026-09-05 history/scrollback, operator-key, draft,
  hardware-shortcut, dashboard, multi-host creation, pairing-entry, Settings-busy, sheet, switcher,
  recreation, and closed-pane slices. A fresh serialized combined run passed 378 tests with zero
  failures, Android lint, debug and instrumentation APK assembly, and minified release assembly.
  No fixed JVM test count is a project invariant.
- The post-remediation performance gate passed 385 JVM/Robolectric tests, Android lint, debug APK
  assembly, and instrumentation APK assembly. It adds regression coverage for bounded terminal
  rendering, fast control stripping, long-scrollback composer analysis, and semantic projection
  offsets.
- The current debug APK was upgrade-installed on the S25 Ultra without uninstalling or clearing app
  data. Four safe device tests passed: official OpenCode artwork, composer-door wiring, and launcher
  plus pane system-bar inset checks. A current read-only interaction pass verified the populated
  dashboard, live pane, correct system-bar containment, all five composer doors, grouped pane
  switcher, canonical Pane actions sheet without a Refresh action, History, and the canonical
  Settings hierarchy through paired-device and connection diagnostics. Network disruption, new
  pairing, destructive actions, terminal writes, STT, accessibility/font scaling, and alternate
  navigation mode are not claimed by this pass.
- Screenshot/layout tests do not establish pixel identity. External-camera comparison of the final
  `FLAG_SECURE` build against fixed web references remains pending.
- The 2026-09-04 API 36 emulator pass above verified the live dashboard, OpenCode pane, header-takeover
  Find, canonical pull-up sheets, 60-entry History opening, Settings/Updates, Space tab behavior,
  cold-relaunch connection persistence, dark-theme recreation, and system-bar containment. It used
  accessibility hierarchy evidence because protected screenshots are black; it has not been rerun
  against the 2026-09-05 combined worktree.
- Native background push remains pending the provider and bridge registration design in Phase 3.
- No commit, push, PR, deployment, or backend mutation is implied by these local implementation
  and verification steps.

### Remaining external and wire-limited acceptance

- `PaneReadResponse` does not echo the requested or served line count and does not carry total
  scrollback depth. The native client keys completion to its local requested target and consults the
  optional snapshot `readableLines`; an older bridge without that field intentionally exposes no
  **Load older** action.
- The live-pane 1,000-line ceiling mirrors Herdr's currently observed `pane.read` clamp. It is not a
  negotiated multiplexer capability, so a future server change requires a coordinated client cap or
  a new wire field.
- `hasSession` folds agent-adapter availability and a reported session reference into one field, and
  the bridge does not publish its journal-agent registry. The client therefore maintains the same
  small English explanation list by hand; it never uses that list for pane identity or writes.
- Native background notifications still require a selected delivery provider, bridge registration
  contract, external credentials, and end-to-end doze/reboot/deep-link testing.
- Current unlocked-device interaction, external-camera visual comparison,
  destructive/network/accessibility cases, release signing, and a native APK/store update channel
  remain acceptance work. No result is implied until its evidence is recorded.

## 13. Risks and controls

| Risk | Control |
| --- | --- |
| Native behavior drifts from the web client | Mirror bridge wire models, API contract tests, tolerant additions/strict required fields |
| Credential leaks through logs/state/backups | Keystore envelope, no body logger, `FLAG_SECURE`, backup disabled, tests and manifest audit |
| Redirect sends bearer off-origin | Disable redirects at OkHttp client and classify 3xx before retry |
| Duplicate terminal text after timeout | No automatic mutation retry; honor `textDelivered`; explicit operator retry only |
| Host/session pane IDs collide or line windows reuse the wrong body | Scope every route by `(host, session, paneId)` and conditional pane bodies additionally by requested lines |
| Polling drains battery | Lifecycle stop, adaptive cadence, ETags, cancellation, bounded backoff |
| Native push adds secrets/vendor coupling | Separate reviewed phase; provider abstraction; external credentials |
| Large parity surface becomes unverifiable | Keep route, grammar, presenter, and wire behavior in focused test fixtures |
| Existing TWA docs/tools mislead maintainers | Superseding ADR, explicit migration cleanup, repository-wide TWA reference audit |

## 14. Deliverables

The native implementation produces:

- this comprehensive plan and a superseding ADR;
- a Kotlin native Android project under `android/`;
- secure origin/pairing storage and direct Collie API client;
- native dashboard/Space navigation, pane switcher and semantic/raw views, history, settings,
  pack/update administration, prompt-bound reply/key controls, media/STT, launchers, and worktrees;
- English-only resources with plural and hardcoded-string guards;
- unit/instrumented test foundations and Android CI;
- a native Android operator/build/acceptance guide; and
- recorded 2026-09-04 automated/API 36 emulator parity evidence and the earlier non-destructive
  S25 Ultra acceptance pass; and
- explicit remaining gates for destructive/network/accessibility device cases, external-camera
  visual comparison, release signing, and native background-notification acceptance.
