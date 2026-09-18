# Collie for Android

This directory contains Collie's native Kotlin Android client. It connects directly to an
operator-configured Collie HTTPS origin through the existing bridge API. It does not load the web
application in a WebView, Custom Tab, Trusted Web Activity, or Capacitor container.

The Android package is `com.lateapex.collie`. The native client implements Collie's dashboard,
Space/tab/pane navigation, semantic and raw pane views, safe terminal controls, history, launchers,
worktrees, media/STT, Settings, devices, Pack, and Updates routes. See the
[native implementation plan](../ANDROID_NATIVE_IMPLEMENTATION_PLAN.md)
for the complete contract and [ADR 0036](../.adr/0036-the-android-app-is-a-native-rest-client.md)
for the architecture decision. The former TWA [specification](../ANDROID_TWA_SPEC.md) and
[implementation plan](../ANDROID_TWA_IMPLEMENTATION_PLAN.md) are retained only as superseded
history.

## Architecture and security boundary

- Native screens render immutable ViewModel state with View Binding. The bridge's rendered pane
  grid is displayed as inert styled text; the app is not a terminal emulator.
- One repository owns HTTP calls, pairing scope, lifecycle-aware polling, the in-memory pane cache,
  and retained device-write authorization. OkHttp never follows API redirects.
- Mutations send an `Origin` header that matches the configured HTTPS origin. Paired calls send the
  bearer credential through `Authorization`; no Android-specific CORS exception is needed.
- The pairing bearer is encrypted with an AES/GCM key held by Android Keystore. It must never enter
  logs, saved-state bundles, screenshots, clipboard data, backups, or build artifacts.
- Backups and cleartext traffic are disabled. The source manifest requests network access plus the
  narrowly scoped runtime microphone permission used by speech transcription, and exports only its
  launcher Activity.
- There is no browser-helper, WebView, JavaScript bridge, Firebase, analytics, or crash-reporting
  dependency.

Browser Web Push belongs to the PWA and is not available to native code. The native client provides
foreground polling and server notification preference/snooze controls, but not native background
delivery. Background notifications remain pending an independently reviewed provider, delivery,
registration, deduplication, and deep-link contract.

## Pinned toolchain

| Component | Version |
| --- | --- |
| JDK toolchain | 21 |
| Java/Kotlin bytecode | 17 |
| Compile and target SDK | 36 |
| Minimum SDK | 26 |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin and serialization plugins | 2.0.21 |

The committed Gradle wrapper is the only supported build entry point. A global Gradle install is
not required. Android dependencies stay under this directory; the root Bun install and Collie
bridge build do not require an Android SDK.

## Validate and build

Point Gradle at an installed Android SDK without creating a tracked `local.properties` file:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

Run the repository-owned configuration checker from the repository root:

```bash
bun run check:android
```

Then run the native gates from this directory:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
```

Current worktree status (2026-09-04): English resource checks, strict repository lint, root and web
TypeScript typechecks, version consistency, and the Android native static checker pass. The final
serialized Gradle gate also passes the complete JVM/Robolectric suite with zero failures/errors/skips,
Android lint, debug APK assembly, and minified unsigned release APK assembly.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Build outputs,
`local.properties`, IDE state, keystores, and local credentials are ignored and must stay
untracked.

When dependencies intentionally change, update and review the committed lock state rather than
allowing CI to resolve an unreviewed graph:

```bash
./gradlew --no-daemon :app:dependencies --write-locks
```

## Connect and pair

On first launch, enter the root of a reachable Collie deployment, such as
`https://ed8.taile7b6b1.ts.net/`. The app rejects HTTP, credentials embedded in a URL, query strings,
fragments, and non-root paths; it never disables platform certificate or hostname validation.

Generate a short-lived pairing code on the Collie host using the normal `collie pair` flow. Enter
that code and a device label in the app. A read-only connection can inspect a deployment whose read
policy permits it, but writes still require a valid paired bearer. An expired or rejected code does
not weaken the connection policy.

Terminal writes are deliberately conservative. Tokenless connections disable and hard-block all
write controls. A token alone is insufficient: a successful snapshot must also establish that device
enforcement is absent or that this device is authorized; unknown status fails closed and the last
successful decision survives transient poll failures. Named keys are re-read and bound to the exact
visible screen before they are sent. One-shot free-text input is bound to the exact visible composer
for agent families with a native grammar. Agent families that have no web harness adapter use
Collie's existing visible-tail fallback; known adapted families are never silently routed through
that fallback. Existing drafts, modals, and changed screens are refused when the active grammar
cannot prove that an ordinary composer is visible.

Disconnect removes the encrypted local connection record after confirmation. It does not revoke
the server-side device. Settings lists paired devices and provides a separately named, confirmed
server revocation action.

## Implemented native surface

- Dashboard triage mirrors the web hierarchy and adds Recent/Spaces collapse and sorting, Space
  filtering, server-approved launchers, workspace creation, and worktree list/create/open flows.
- Space browsing exposes tabs and panes plus capability-gated tab/workspace creation, rename, and
  close actions. Pane switching retains the active host/session scope.
- Pane viewing supports ANSI-styled inert text, semantic dialogs/actions, raw-terminal display,
  history pagination/search, quick replies, agent-command search, neutral named-key grammar,
  direct typing, and prompt-bound replies. Codex, Claude, Grok, OMP, Agy, and Antigravity use exact
  composer recognition; OpenCode, shell, and otherwise unadapted agents retain the bounded
  visible-tail disposition used by the web client.
- The pane body switches between a rendered transcript (one card per turn, grown upward by
  "Load older") and the inert mirror of the underlying terminal grid. Transcript eligibility is
  decided by whether the pane's agent has a journal adapter (`bridge/journal/registry.ts`
  registers claude, codex, pi, and opencode), not by agent name. The mirror takes over when Raw
  is on, an AskUserQuestion-style dialog is up, no journal exists for this pane yet, or the
  operator manually taps "Show terminal" in the body row; an unrecognized surface such as
  Claude's `/cost` is not auto-detected.
- The Keys drawer is one compact primary row plus a modifiers row and a More sheet that holds
  presets and function keys; with the drawer open at most three rows sit above the composer.
- The worktree sheet lists a repository's linked worktrees that have no open space; selecting one
  opens it only after a separate "Open worktree?" confirmation dialog, rather than branching
  again from the same base.
- The system image picker uploads bounded images without storage permission. Operator-initiated
  audio recording requests microphone permission at runtime and sends a bounded clip to `/api/stt`;
  the resulting transcript returns to the draft for review.
- Settings covers connection/reset, theme, terminal/draft sizing, notification
  switches and snooze, paired-device revocation, connection state, Pack state, and Updates. Update start
  requires preflight and device-write authorization; `/standby/update` retains the freshest visible
  run while the bridge restarts.
- The dashboard banner and Server updates screen manage the connected Collie server through its
  update API. They do not download, install, or replace the Android APK. Settings reports the
  server version separately from the Android app build.
- The interface uses English base resources only. Static tests reject new visible hardcoded strings.

## Emulator and physical-device acceptance

The [2026-09-10 functional audit](acceptance/2026-09-10-functional-audit.md) records the newer
Gboard/device-event regression, reproduced defects, and explicit remaining S25 Ultra acceptance.
Run it from the repository root with `scripts/android-interaction-test.sh emulator-PORT`.

Compilation is not acceptance. Install the debug APK on an API 26+ emulator or the target device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
```

A partial physical-device pass was recorded on 2026-09-04 against an upgrade-installed debug APK on
the paired Samsung Galaxy S25 Ultra (`SM-S938U1`, Android/API 36). Native launch/live reads, one
exactly-once harmless disposable write, Escape, Keys/Quick drawers, tab actions, the pull-up pane
switcher, draft/process-death persistence, multiline input, image-picker cancellation, pane/history
search, Settings/Updates, rotation, status/navigation/IME containment, and the packaged permission
boundary passed. The serialized Gradle gate also passed the complete JVM/Robolectric suite, lint, debug
assembly, and minified unsigned release assembly.

A full `connectedDebugAndroidTest` run was executed on 2026-09-11 against the same paired Samsung
Galaxy S25 Ultra (`SM-S938U1`, Android/API 36) and passed 58/58. That instrumented suite covers, on
the device, the Gboard row-count test that detects 3- or 4-row Gboard layouts dynamically from the
letter-to-bottom-row height ratio (`NativeInteractionTest.kt`). The transcript-vs-mirror body
switching for journalled agents (claude, codex, pi, opencode), the one-row-plus-More-sheet Keys
drawer, the unopened-worktree listing with its separate confirm dialog, and "Load older" pagination
are the same pane-UX overhaul, covered separately by the JVM/Robolectric unit suite
(`testDebugUnitTest`) rather than by this instrumented run. The newest-turn landing fix
(`revealOnFocusHint = false` on turn views, the Load older button, and the mirror's
`terminal_text`, pinned by `TranscriptBodyViewTest.kt`) and the pairing-code autofill fix
(Bitwarden popup suppressed by switching `pairing_code_input` to
`textCapCharacters|textNoSuggestions`, pinned by `SettingsActivityTest.kt`) are also unit-tested,
not part of the instrumented suite; the newest-turn fix was additionally confirmed live on the S25
Ultra by cold-opening a busy agent pane and observing it land on the newest turn. The ledger is at
[acceptance/2026-09-11-ux-walk.md](acceptance/2026-09-11-ux-walk.md); run the instrumented suite
from this directory with `./gradlew --no-daemon connectedDebugAndroidTest`. Running it uninstalls
and reinstalls the debug APK, which wipes the phone's encrypted pairing/connection state — re-pair
(`bin/collie devices revoke <old-label>` then `bin/collie pair`, then enter the code in the app)
before drawing acceptance conclusions from the run.

The final parity regression ran only on the API 36 `stormlens_api36` emulator. It verified live
read-only dashboard data and cold-relaunch connection persistence; safe status/navigation bounds;
the update ribbon; OpenCode naming/artwork; a large live pane without ANR; canonical pane/tab
pull-up sheets; header-takeover Find; per-table horizontal panning; in-flow Display controls;
History opening at 60 entries; canonical Settings order and live server-build id; dark-theme
recreation; Updates preflight behavior; and Space/tab selection and Back behavior. Four safe device
tests covering agent artwork, launcher/pane insets, and composer wiring also passed. Neither the S25
Ultra nor the connected Pixel was addressed during that final pass.

Earlier passes ran with `FLAG_SECURE` set, so their emulator screenshots were black and they used
accessibility/view bounds, direct interaction, and logcat instead. The flag was removed on
2026-09-10; `adb exec-out screencap -p` now captures every screen.

Before a distribution claim, finish the unchecked portions of this baseline and record the result
separately:

1. A clean reinstall and new short-lived pairing succeed without browser UI. The recorded pass used
   an upgrade install to preserve the operator's encrypted connection.
2. Polling stops in the background, resumes on return, and does not duplicate a write.
3. Network loss keeps the last successful state visibly stale, then recovers when connectivity
   returns.
4. Process death preserves the encrypted connection without exposing the token in logs,
   extras, saved state, screenshots, or the recent-app preview.
5. Space/tab/pane switches, agent modes, and a harmless STT draft behave correctly without duplicate
   writes when the live bridge advertises STT.
6. Device revocation, notification preferences, Pack, and Updates reflect the live server; an
   update test preserves progress through the bridge restart via the standby route.
7. Insets work in both gesture and three-button navigation, and TalkBack/font scaling retain usable
   44 dp controls.
8. Compare fixed web/native screens from `adb` screenshots. Automated layout assertions are not a
   substitute for that pixel comparison.

Keep the APK identity, signer, checksum, source commit, device/OS build, and acceptance timestamp in
an operator-controlled record outside Git. Never include bearer tokens, pairing codes, terminal
content, passwords, or private signing material in that record.

## Release status

Release signing and distribution remain pending. The repository contains no release keystore and
CI publishes no release artifact. The minified, non-debuggable release variant exists for local
verification; keep any future release key and passwords outside the worktree and repeat the
physical-device acceptance matrix before distributing an APK or AAB.

The current package is `versionName=1.5.1`, `versionCode=2`. Android `versionCode` is monotonic and
independent of the Collie server release mechanism; every APK distributed as an update must use a
higher code than the installed APK.

Changing the application ID or signing key breaks in-place upgrade continuity. Treat either as an
explicit migration, not a routine recovery step.

## Bundled artwork and typeface

The Android UI bundles Aldrich Regular 1.002 from the
[official Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/aldrich). Aldrich
is Copyright © 2011 Matthew Desmond and is distributed under the SIL Open Font License 1.1; the
complete license is packaged at `app/src/main/res/raw/license_aldrich.txt`. Aldrich has one real
weight, 400, so Android UI styles must not request or synthesize heavier variants.

`app/src/main/res/drawable-nodpi/collie_mark.png` is derived from Collie's generated PWA artwork at
`web/public/web-app-manifest-192x192.png`; only its fixed dark background is made transparent for
use in app chrome. Keep the two in sync rather than redrawing or tracing the brand mark
independently.

The native dashboard and pane header reuse the agent marks documented in
`web/src/components/agent-icon-data.ts`: Claude and Codex/OpenAI through Simple Icons, OpenCode from
the project's current `favicon-v3.svg`, pi from pi.dev, OMP from omp.sh, and Antigravity from
Google's product mark. Their Android vectors retain the same path geometry and brand colors where
Android's vector format supports them; OMP's small native tile uses the official gradient's
midpoint color.

Additional native agent tiles cover the rest of Herdr's detected integrations and locally installed
agent CLIs. GitHub Copilot uses the 24×24 path distributed by
[Simple Icons](https://github.com/simple-icons/simple-icons) (Copilot's source icon is MIT). Kimi
and Qwen use locally authored letter tiles rather than redistributing vendor artwork whose reuse
terms are not explicit.
Grok uses the monochrome vector maintained by [Lobe Icons](https://github.com/lobehub/lobe-icons)
(MIT), derived from xAI's published brand asset. Hermes uses Font Awesome Free's Staff Snake glyph
(CC BY 4.0, Copyright Fonticons, Inc.) to render the symbol used by Hermes Agent's official favicon.
Goose uses the silhouette from the project's
[Apache-2.0 desktop icon](https://github.com/block/goose/blob/main/ui/desktop/src/images/icon.svg).
The applicable copyright notices, license terms, source links, and modification notices are
packaged in `app/src/main/res/raw/third_party_notices.txt` so they travel inside every APK.
All product names and marks remain trademarks of their respective owners; inclusion identifies the
agent reported by Herdr and does not imply endorsement.
