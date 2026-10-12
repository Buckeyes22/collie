# Collie for Android

This directory contains Collie's native Kotlin Android client. It connects directly to an
operator-configured Collie HTTPS origin through the existing bridge API. It does not load the web
application in a WebView, Custom Tab, Trusted Web Activity, or Capacitor container.

The Android package is `com.lateapex.collie`. The native client implements Collie's dashboard,
Space/tab/pane navigation, semantic and raw pane views, safe terminal controls, history and read-only Changes browsing, launchers,
worktrees, media/STT, Settings, devices, Crew, and Updates routes. See the
[native implementation plan](../ANDROID_NATIVE_IMPLEMENTATION_PLAN.md)
for the complete contract and [ADR 0036](../.adr/0036-the-android-app-is-a-native-rest-client.md)
for the architecture decision. The former TWA [specification](../ANDROID_TWA_SPEC.md) and
[implementation plan](../ANDROID_TWA_IMPLEMENTATION_PLAN.md) are retained only as superseded
history.

Pane details expose the model, Git head and cache state supplied by the owning bridge. Cache expiry
uses the bridge snapshot clock and preserves confidence and rule provenance; a peer carries its
sourced cache-rule label while the lead's catalog stays unavailable as peer provenance. Cache timing
is not a billing estimate. Cache watches and machine/cache
alert preferences are stored on the lead and affect subscribed bridge notification clients. Native
background delivery is not available in this app, so these controls do not promise a phone alert.

The read-only Changes screen lists bridge-discovered repositories and their working-tree diffs.
After opening a listed current diff, **Open current source in Files** opens its current root-relative
file through Files device-read admission. This shows current contents, not a committed revision.
Deleted current paths and historical commit diffs remain diff-only, with an explanation. The source
handoff expires after 30 seconds and is tied to its original server, device, scope and target;
retirement or process restart requires reopening current Changes rather than reusing an old action.

Its commit history is delivered in bounded first-parent pages pinned to the first page's HEAD;
selecting a row loads that commit and only its listed file diffs. Older peers that do not return
the history contract are shown as unsupported.
History stops at 10,000 commits. A terminal cap page says when older commits were omitted;
a real end stays distinct. Display subjects are capped at 500 characters and author names at
1,024 characters. Each bridge history page is bounded below 1 MiB; native transport keeps its
5 MiB streaming ceiling and refuses malformed page offsets or truncation metadata.

The dashboard's **Organize** action lists the selected connection's workspaces and tabs. It offers
only operations positively declared by the owner: rename, explicit focus, pre-removal ordering and
close. Reordering a block preserves its selected order and requires an anchor outside the block.
`tab.move` only reorders within its current workspace. The separate **Transfer entire tab** flow
reviews the complete owner-bound roster and a deterministic destination reflow, then moves one
existing process per explicit confirmation. Each move uses a fresh plan, opaque process witness and
durable receipt; an unknown result blocks every later step until the exact outcome is reconciled.
The workflow does not start replacement shells or claim atomicity, and source-tab close/focus effects
are disclosed by the plan. Closing a linked-worktree group first names the affected workspaces and
panes; closing any workspace leaves its repository checkout on disk. Old servers and
multiplexers without the additive organization capabilities remain available for ordinary browsing
and report these controls as unsupported. A timed-out action is never repeated automatically; the
screen keeps an outcome check and disables further changes until the operator reconciles it.

## Architecture and security boundary

- Native screens render immutable ViewModel state with View Binding. The bridge's rendered pane
  grid is displayed as inert styled text; the app is not a terminal emulator.
- One repository owns HTTP calls, pairing scope, lifecycle-aware polling, the in-memory pane cache,
  and retained device-write authorization. OkHttp never follows API redirects.
- Mutations send an `Origin` header that matches the configured HTTPS origin. Paired calls send the
  bearer credential through `Authorization`; no Android-specific CORS exception is needed.
- The pairing bearer is encrypted with an AES/GCM key held by Android Keystore. It must never enter
  logs, saved-state bundles, screenshots, clipboard data, backups, or build artifacts.
- Public builds start with an empty server field. Diagnostic capture is opt-in for new installs,
  and pairing request and response bodies are excluded from capture.
- Backups and cleartext traffic are disabled. The source manifest requests network access plus the
  narrowly scoped runtime microphone permission used by speech transcription, and exports its
  launcher and the narrowly validated native link/callback dispatcher.
- There is no WebView, JavaScript bridge, Firebase, analytics, or crash-reporting dependency.
  Optional identity sign-in opens the system external browser with ACTION_VIEW; it does not embed
  the web application or import browser cookies.

Pane Details can show foreground process, foreground directory, and restore-error diagnostics when
the owning runtime positively declares process inspection. These fields can contain credentials:
the route requires `device-read`, the bridge and Crew lead redact before clipping, and Android caps
the streamed JSON body before decoding. Unsupported runtimes explain that the data is unavailable.

Pane actions also expose an Agent identity screen for the owning runtime's reported status,
interactive readiness, sequence and factual session reference. It labels state authority as unknown
when the runtime does not publish it. Herdr 0.9.3 has no atomic current-occupant condition on its
report/session mutations, so state, session, resume and release controls stay unavailable; opening
the screen does not focus the terminal or mark its transcript seen.

The native New flow also opens launch recipe management and one-off Run on the selected machine and
session. The phone uses only the existing launcher, validation, run and recent-history routes. It
requires the host to declare each action, labels syntax checks as non-PATH checks, confirms
no-prompts commands, and stores run/add requests in the encrypted owner-bound intent journal before
dispatch. Recent commands fill the editor for review and never run automatically.

Browser Web Push belongs to the PWA and is not available to native code. The native client provides
foreground polling and server notification preference/snooze controls, but not native background
delivery. Background notifications remain pending an independently reviewed provider, delivery,
registration, deduplication, and deep-link contract.

Update notices offer separate actions to dismiss that version or request a reminder in the next
digest. Dismissals persist on the server and apply independently to an update offer and a settled
Crew failure notice. New versions and active progress remain visible; Settings shows the server's
current acknowledgements. An uncertain dismissal refreshes state without automatically retrying.

## Optional native identity sign-in

Direct native Tailscale access remains the default. A configured native DPoP gateway requires the
operator's public OIDC profile and an existing paired-device incarnation explicitly mapped to the
issuer subject and this installation's public proof key. Pair through an already supported route
before enrollment: first pairing through the gateway-only mount is not supported. Candidate health
and pair probes reach that exact origin once and report its refusal without borrowing another
saved connection's credentials. See the [gateway setup](../docs/native-identity-gateway.md).

Review the profile for the selected HTTPS origin in the native sign-in screen, confirm import,
and open the external browser. Only the fixed `com.lateapex.collie:/oauth2redirect` callback may
complete its encrypted expiring, process- and credential-bound one-shot transaction. Issuer requests
never receive Collie pairing. Identity tokens and enrollment retirement custody use separate
Keystore encryption; the proof key's public thumbprint can be copied explicitly.

Leaving the screen clears the profile editor and modal copy and cancels pending UI work. A
legitimate external-browser callback can resume on a started screen while stopped dialogs and
retired requests remain inert. Same-identity refresh or expiry preserves the exact enrollment
witness and pairing. Authenticated exact enrollment retirement emits a durable event whose signed
B22 consumer must commit before ACK. A current snapshot still gates terminal writes after sign-in;
identity login does not enable an unsupported or read-only pane.

Source fixtures do not establish genuine issuer, front-door, Keystore/external-browser,
Windows, Crew, TalkBack or signed-push acceptance. Those runtime checks and the B18/B22/B23 shared
joins remain required before the optional workflow can be considered complete.

## Offline saved copies and drafts

Settings offers **Off**, **Keep Chat for 1 day** and **Keep Chat for 7 days**. Read retention starts
Off; Dashboard and terminal mirrors expire after at most 24 hours even with seven-day Chat enabled.
Changing the retention choice clears previous read copies. Settings shows encrypted storage usage
and **Clear saved reads now** erases the cache and copied screen state. The combined pane/Chat limit
is 256 KiB, with a 10 MiB total encrypted read cache and bounded LRU pruning. Saved rosters and tails can be trimmed to fit; they are not a complete live inventory.

After an offline cold start, opted copies display their capture age, expiry, HTTPS origin, host and
session. **Saved copy** means read-only: queued-send, reply, keys, structural actions, uploads and
cached file/image references have no live actor. Tap the label or Refresh to retry. Only accepted
fresh live data restores authority; an unchanged response cannot turn a retired copy into a live
prompt. Gateway or untyped access refusals keep pairing and saved content; declared Collie pairing
retirement, disconnect or credential/origin replacement wipes the original owner's content.

**Keep saved drafts** is an independent default-Off switch. Off permits volatile editing without
new plaintext persistence or requiring a successful remote read. Local editing remains bound to its
original connection/incarnation and pane; it never authorizes a send. Delimiter-bearing target IDs
use an unambiguous local key, and both RAM and encrypted retrieval require the exact requested target. Opted drafts use Keystore encryption, the existing 8,192-character limit
and a 48-hour expiry. An accepted password mirror clears and blocks that exact pane's RAM and encrypted drafts, including
stopped editor copies. A newly accepted clean mirror can reopen ordinary editing; Chat cannot.
Password prompts exclude retained pane content and drafts. Clear saved drafts
is separate from clearing reads. Existing plaintext drafts from earlier versions are preserved
until explicit consented, owner- and target-matched encrypted migration or confirmed discard. Open the matching connected
pane before migrating its exact legacy draft; unknown-owner, hashed or unmatched keys are not
silently migrated or deleted by upgrading.

The [retention design](../.adr/0105-offline-content-is-encrypted-display-custody.md) records crash
retirement and bounds. Source implementation is pending independent review, current native gates,
physical Pixel lifecycle/large-font/TalkBack acceptance and the signed-provider retirement join.

## Pinned toolchain

| Component | Version |
| --- | --- |
| JDK toolchain | 21 |
| Java/Kotlin bytecode | 17 |
| Compile SDK | Stable 37.2 |
| Minimum and target SDK | 37 |
| Gradle wrapper | 9.8.0 |
| Android Gradle Plugin | 9.4.1, built-in Kotlin |
| Kotlin compiler, serialization plugin and runtime BOM | 2.4.20 |
| SDK Build Tools | 37.0.0 |
| Robolectric | 4.17, SDK 37 |

The current policy supports Android 17 / API 37 only. Stable tooling and dependencies must be
at least seven days old before adoption. See [the toolchain migration record](TOOLCHAIN.md)
for pinned versions, pending lock resolution and validation limits.

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

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Build outputs,
`local.properties`, IDE state, keystores, and local credentials are ignored and must stay
untracked.

When dependencies intentionally change, update and review the committed lock state rather than
allowing CI to resolve an unreviewed graph:

```bash
./gradlew --no-daemon :app:dependencies --write-locks
```

## Connect and pair

On first launch, enter the HTTPS URL of a reachable Collie deployment, such as
`https://collie.example.com/`. The app rejects HTTP, credentials embedded in a URL, query strings,
fragments, and ambiguous paths. Safe mount paths such as `/tools/collie/` are supported; it never disables platform certificate or hostname validation.

Generate a short-lived pairing code on the Collie host using the normal `collie pair` flow. Enter
that code and a device label in the app. Collie 1.19.0 requires a valid paired bearer for reads and writes. A tokenless connection can inspect only older deployments that permit reads. An expired device must pair again; unrelated server failures keep the credential. An expired or rejected pairing code does not weaken the connection policy.

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

Native pane attachments retain the Photo Picker for images and use Android's Storage Access
Framework document picker for generic text and raster files. The app reads selected bytes through
an owned, cancellable provider descriptor into a private cache spool, enforces the lower of the
server-advertised byte limit and the native 10 MiB ceiling, and removes previous-process spools at
process startup. Extension policy is operator-configured; provider MIME labels are advisory. The
upload uses the existing pane multipart endpoint and remains one-shot. Refused, cancelled, and
completed uploads do not persist the provider URI or its grant.

- Dashboard triage mirrors the web hierarchy and adds Recent/Spaces collapse and sorting, Space
  filtering, server-approved launchers, workspace creation, and worktree list/create/open flows.
- The New screen offers the selected machine's declared agent, shell and command rows. Its shared
  folder picker reads that machine's recent and favourite folders; folders and stars do not cross
  host boundaries, and unsupported older bridges leave manual remote-folder entry available.
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
- Transcript tool cards preserve structured file, command, search, task, fetch and question details;
  historic questions are read-only, and denied calls are labeled separately from execution failures.
- Journal images in history, expanded tool results, and the live transcript load from the
  authenticated mounted Collie origin. A card opens a native pan/zoom viewer; missing, unsupported,
  or retired-scope images show an explicit unavailable state. Image references are not arbitrary
  URLs: blob paths stay within the selected Collie connection, inline raster data is bounded, and
  decoded memory is shared across visible cards and viewers. After an access or navigation change,
  a fresh admitted journal read is required before old entries can load images again.
- A live transcript can show the bridge-declared **Send now** action for a pending queue. It is
  available only for a paired, write-authorized, working pane with supported declared keys and a
  freshly verified non-modal composer; it sends the keys once and never resends queued text.
  Older bridges and peers that do not declare the guarded action leave the control unavailable.
- Files opens the pane or workspace's server-selected root with bounded folder listings, ignored
  filtering, source Find/line jumps, native inert Markdown/JSON/HTML/CSV/SVG previews, and raster
  reads through the shared authenticated image loader. Printed relative source paths remain plain
  text unless a scoped Files/exist read confirms them. Parser bounds and SVG/HTML security policy are
  documented in [Files preview security](FILES_PREVIEW_SECURITY.md). Removing or replacing the
  encrypted connection clears accepted Files content, detached and pooled listing rows, retained earlier
  source/preview copies, parsed SVG graphs and image dialogs even while the screen is stopped. The shared
  image loader retires requests and versioned caches for the original committed actor without a new read;
  current replacement actors and temporary admission fences keep their own live custody. An explicit retry admits a fresh actor; a Changes handoff stays bound to
  its original actor and cannot acquire replacement credentials. Each committed encrypted connection
  has a durable opaque incarnation ID; clearing or replacing it permanently retires earlier actors,
  even when notification coalesces and the same Connection object returns. Re-saving the exact live
  instance preserves its ID. Older encrypted records without an ID require deliberate reconnect;
  reading an old record does not mint new authority.
- Machines reads the paired lead-owned census and per-machine 24-hour history. Short sparks occupy
  their actual most recent minutes within a fixed 30-minute plot, with missing minutes left blank.
  Gateway identity, gateway device and unclassified access failures retain their original explanation
  when the read actor retires; refreshing that retired screen cannot borrow a replacement actor. Resource samples,
  optional network/load/disk measurements, stale sample age, and missing minutes stay distinct from
  zero; CPU and memory charts have a textual table, and polling stops whenever the screen leaves
  the foreground. Long lead rosters remain complete and are shown in bounded pages. The fixed
  24-hour chart/table names its time range and lists leading, internal, and trailing gaps. Access
  refusals retain known pairing or gateway-factor meaning; unknown provenance is labeled neutrally.
  Current bridges also expose whole-rule CPU, memory and disk editing. Each save carries that row's
  rules revision and machine incarnation, preserves opaque server extensions and is sent once;
  conflicts show the server's current rules for review. An uncertain save keeps a bounded encrypted
  metadata barrier across restart until a fresh read and explicit acknowledgement; screen cancellation
  ends the exact original process-local attempt before best-effort durable UNKNOWN publication. If
  journal contention leaves the encrypted row PENDING, the same-process view projects it as UNKNOWN,
  discloses that durability state, and permits acknowledgement only after fresh review. Final journal
  updates recheck actor custody and journal version after encryption. The old request is never
  reconstructed or replayed. Background notification delivery remains a separate feature.
- The Keys drawer keeps primary keys and modifiers together, wrapping on narrow phones to retain
  48 dp touch targets. Its More sheet holds presets and function keys.
- The worktree sheet lists a repository's linked worktrees that have no open space; selecting one
  opens it only after a separate "Open worktree?" confirmation dialog, rather than branching
  again from the same base.
- The system image picker uploads bounded images without storage permission. Operator-initiated
  audio recording requests microphone permission at runtime and sends a bounded clip to `/api/stt`;
  the resulting transcript returns to the draft for review.
- History renders only exact mounted Collie blob references and supported inline raster data. Tap a
  card to open the accessible zoom viewer. After rotation or authority retirement, retained cards
  remain unavailable until **Refresh history images** completes a current read for their turns;
  text, disclosure state, search and the loaded page range stay in place while that read runs.
- Settings covers connection/reset, theme, terminal/draft sizing, notification
  switches and snooze, paired-device revocation, connection state, Crew state, a per-runtime Agent
  support inventory, and Updates. Agent support separates all 24 H12 names from launch IDs, shows
  bounded versions and current owner-snapshot identity/session counts, and keeps unobserved
  dimensions unverified. Image
  producers marked interface-ready still require the B34 owner ingestion and authorization path.
  Update start
  requires preflight and device-write authorization; `/standby/update` retains the freshest visible
  run while the bridge restarts.
- The dashboard banner and Server updates screen manage the connected Collie server through its
  update API. They do not download, install, or replace the Android APK. Settings reports the
  server version separately from the Android app build.
- The interface uses English base resources only. Static tests reject new visible hardcoded strings.

## Current physical-device acceptance and historical results

The [2026-09-10 functional audit](acceptance/2026-09-10-functional-audit.md) records the newer
Gboard/device-event regression and emulator coverage. Its initial note that the S25 Ultra was
unavailable describes that day's ADB state; the [2026-09-11 UX walk](acceptance/2026-09-11-ux-walk.md)
later records a 193-step physical-device walk and additional checks. The emulator interaction
script is historical tooling; it is not part of the current acceptance policy.

Compilation is not acceptance. Current acceptance uses the owned physical Pixel running Android 17
on dell-7680. Run against its explicitly selected serial; do not reinstall another app namespace:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
```

The following dated device and emulator results are historical. They do not establish current
Android 17 acceptance or require an older-platform matrix. Current functional, security,
lifecycle, large-font and TalkBack checks remain required on the physical Pixel.

A partial physical-device pass was recorded on 2026-09-04 against an upgrade-installed debug APK on
the paired Samsung Galaxy S25 Ultra (`SM-S938U1`, Android/API 36). Native launch/live reads, one
exactly-once harmless disposable write, Escape, Keys/Quick drawers, tab actions, the pull-up pane
switcher, draft/process-death persistence, multiline input, image-picker cancellation, pane/history
search, Settings/Updates, rotation, status/navigation/IME containment, and the packaged permission
boundary passed. The serialized Gradle gate also passed the complete JVM/Robolectric suite, lint, debug
assembly, and minified unsigned release assembly.

A full `connectedDebugAndroidTest` run was executed on 2026-09-11 against the paired Samsung Galaxy
S25 Ultra (`SM-S938U1`, Android/API 36) and passed 58/58. The separate UX walk on that device
recorded 193 observed steps with zero failures. These are dated results against the builds listed in
the UX-walk record, not acceptance of a later release candidate. The instrumented suite covers, on
the device, the Gboard row-count test that detects 3- or 4-row Gboard layouts dynamically from the
letter-to-bottom-row height ratio (`NativeInteractionTest.kt`). The transcript-vs-mirror body
switching for journalled agents (Claude, Codex, pi/OMP, opencode, Grok, Hermes, and Muse), the compact Keys drawer with a More sheet
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

Before publishing an APK, smoke-test the signed artifact and record its identity. Track the extended
current-device lifecycle, large-font and TalkBack checks as required acceptance. The local release preparation recorded on 2026-10-07 passed 521
native tests, native debug/release lint and builds, and signing-helper checks with a disposable test
key; it did not complete production signing setup or device acceptance. See
[`RELEASE_READINESS.md`](../RELEASE_READINESS.md) for the current preparation evidence and remaining
release requirements. The following device checks remain useful acceptance coverage:

1. A clean reinstall and new short-lived pairing succeed without browser UI. The recorded pass used
   an upgrade install to preserve the operator's encrypted connection.
2. Polling stops in the background, resumes on return, and does not duplicate a write.
3. Network loss keeps the last successful state visibly stale, then recovers when connectivity
   returns.
4. Process death preserves the encrypted connection without exposing the token in logs,
   extras, saved state, screenshots, or the recent-app preview.
5. Space/tab/pane switches, agent modes, and a harmless STT draft behave correctly without duplicate
   writes when the live bridge advertises STT.
6. Device revocation, notification preferences, Crew, and Updates reflect the live server; an
   update test preserves progress through the bridge restart via the standby route.
7. Insets work in both gesture and three-button navigation, and TalkBack/font scaling retain usable
   48 dp controls.
8. Compare fixed web/native screens from `adb` screenshots. Automated layout assertions are not a
   substitute for that pixel comparison.

Keep the APK identity, signer, checksum, source commit, device/OS build, and acceptance timestamp in
an operator-controlled record outside Git. Never include bearer tokens, pairing codes, terminal
content, passwords, or private signing material in that record.

## Release status

The Android release workflow builds, tests, signs and verifies an APK, then creates a draft
GitHub Release on an `android-vX.Y.Z` tag. Signing secrets and a pinned certificate fingerprint
must be configured before that workflow can complete. The signing helper also supports local
preparation. See [Releasing](RELEASING.md) for versioning, signing, acceptance and publication.

Unsigned and debug APKs are development artifacts. A public APK needs release signing, passing build checks and a basic artifact smoke check.
Current physical Pixel security, lifecycle, large-font and TalkBack acceptance remains required; disclose untested scope accurately.

The current package is `versionName=1.5.2`, `versionCode=3`. Android `versionCode` is monotonic and
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

Claude, Codex, pi, OMP and Antigravity use locally authored letter or glyph tiles on a neutral
square, like Kimi and Qwen. They identify the agent a pane runs and redistribute no vendor
artwork, so they carry no third-party notice. The PWA's `web/src/components/agent-icon-data.ts`
keeps upstream Collie's own tile selection; see ACKNOWLEDGMENTS.md. OpenCode's tile still adapts
the project's `favicon-v3.svg`.

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
`app/src/main/res/raw/third_party_notices.txt` packages the verified source, license, and
modification notices for the artwork it lists. The other built-in vendor marks named above
(Claude, Codex/OpenAI, pi, OMP, and Antigravity) do not yet have pinned source and redistribution
terms established for this Android bundle. Record a maintainer disposition for these specific
findings, retain required notices, and avoid claiming that unverified permissions are established.
The Simple Icons repository-wide license alone does not establish an individual mark's terms.
All product names and marks remain trademarks of their respective owners; inclusion identifies the
agent reported by Herdr and does not imply endorsement.


## Scoped control compatibility

New control support requires a positive declaration in `/api/config.control.capabilities`; legacy mux absent-field compatibility applies only to existing actions. Crew negotiates the peer's independent `hello.controlCapabilities` and `hello.hostControlCapabilities`. Host integrations use a separate typed provider: Herdr integration inventory/status/install/removal and manifest status/reload are available only after a matching 0.9.3+ protocol-22 handshake; experimental Letta stays on its fixed-argument CLI path. Other providers explicitly report unsupported. Install, removal, reload and network refresh are separately planned and confirmed, backed by owner-bound one-shot receipts.

Native request contexts bind the HTTPS origin including its mount, credential and leader generation, host/session and subject. Space Look now uses the owning session's read-gated POST refresh before snapshot; older 404 peers get a labeled ordinary reload. Captured media scope is checked before upload and before applying its result. Saved copies never authorize terminal or structural actions. Unknown/gateway 401/403 freezes actions while retaining pairing; only exact authoritative Collie pairing provenance retires it.

Control receipts use GET `/api/control/operations/<id>` and never return secret payload/evidence. Pending after restart is unknown, with no repeat dispatch. Existing launch/worktree/add receipts still use POST replay. The bridge health adapter uses its existing health seam with exact typed Herdr ping/pong validation; source/schema fixtures do not prove a live vendor handshake.

Launches, receipt-bound worktree creation and the launcher-add request seam keep an encrypted local copy of the exact request ID and body before sending. If the reply is uncertain, the app offers only a deliberate replay of that same request after refreshing the owning scope; the user may instead abandon it after a duplicate-risk warning. Workspace-branch requests also bind the workspace ID carried in the POST path. When the bridge does not positively advertise owner-bound receipts, the existing workspace-branch create remains a one-shot initial action and never gains an ambiguous retry.

See [scoped control authority decision](../.adr/0096-control-authority-is-scoped-and-positive.md). Joint B02/B03 and live vendor/device acceptance are tracked in the B01 handoff.

Document chips show the sanitized filename, detected MIME type, and actual byte count, including accessible text. Provider work has two process-wide ownership slots; blocked cancellation or close cannot stall the caller or create unlimited replacement workers. A cancelled import unlinks its registered private spool immediately, and its slot stays occupied until provider and asynchronous cleanup finish. No document upload is automatically replayed.

Pane layout controls are available from a pane's actions when the connected runtime positively declares layout support and durable operation receipts. The native screen shows the server-reported pane geometry and adjacency, then plans and confirms split, resize, zoom, swap, directional focus or movement. A timeout is shown as uncertain and is never sent again automatically. Same-ID recovery is offered only when the owner-bound receipt capability is present. On older peers, the operator can inspect the current layout and explicitly abandon the local recovery record after a duplicate-risk warning; this does not claim that the earlier change was undone.

Portable layouts add export/share and pasted-JSON import from the pane action sheet. The preview explains process replacement, shows redacted starts and stops, and requires a fresh plan plus explicit confirmation before apply. Ratio edits use the exact binary path from the exported tree. The request ID and exact body are reserved in the encrypted intent store before dispatch; an uncertain result is recovered by reading the same owner-bound operation receipt and is never automatically replayed. Only commands currently matching a host-declared launcher can be imported; plugin-backed recipe resolution awaits B32. Build and physical-device acceptance are recorded separately from this source implementation.
Cache watches preserve the exact host/session/pane address. Older ambiguous peer watches stay
in the list with Forget available and no pane link; add a new watch from a current exact pane.
Cache details clear live facts and catalog validators when their authority retires or the screen
stops. The existing Refresh action acquires fresh facts before the switch becomes usable again.

Native retained content and recovery records carry their original committed connection owner.
Delayed retirement compares that copied owner again on Main and removes only the predecessor's
pane drafts and encrypted launch intents. A fresh committed successor can refresh the same
screen while predecessor cleanup is held. Genuine same-generation or unsupported-store pending
acknowledgements refuse new actors while preserving temporarily fenced original custody.
Fresh canonical whole review, native compilation and actual device/Crew/format acceptance
remain required; historical failed runs remain preserved, and B18 H11/R1 remain open.

Worktree review custody permits one live flow per Activity and at most four attached private
dialog copies. Cancelling, invalid input, superseding a review or completing it releases its
subscription and copied text. Explicit form-to-plan, no-prompts and force-review handoffs retain
the original actor; STOP remains terminal. Each Open confirmation consumes its action once,
and a fresh deliberate review may open the current target again.

The bridge retains a checkout that it already created even if later persistence or launcher
readiness loses authority. It reports the launcher as not started and keeps the original request
ID; it never automatically recreates the checkout or repeats launcher commands. Original paired
caller, runtime and target checks reach the actual adapter before each text and Enter dispatch.

The encrypted launch journal serializes reservation with exact-owner retirement, including a
zero-row purge. Separate bounded encrypted metadata stores up to 4,096 retired owner hashes,
committed atomically with body removal; the existing 128-record, 512 KiB body limit and completion
headroom remain unchanged. Every production reservation also checks its immutable original actor,
so metadata eviction cannot grant a retired actor authority. Failed persistence preserves the raw
envelope and makes the live journal unavailable. Fresh committed incarnations and their UNKNOWN
barriers remain independent. Native compilation and actual device/Crew/vendor acceptance of these
corrections remain pending.

New retains a separate original owner for editable cwd/branch/base/parent and its single owned
saved form copy after a modal review ends. Genuine original retirement clears those copies;
a delayed predecessor event leaves an admitted successor’s form intact. Saved fields restore
only after the same durable owner/incarnation matches a current actor; no bearer-bearing
Connection enters Bundle state. Same-owner cancellation and rotation preserve manual inputs,
and a replacement requires a fresh target choice.

Herdr worktree create/open/remove and selected launcher text/Enter keep their original admission
through dial readiness and every managed write/drain continuation. Before any bytes are accepted,
retirement is a refusal; an accepted prefix or lost reply remains uncertain. There is no automatic
second request. A full Node transport queue has already accepted its one payload and cannot be
recalled as a proven zero-effect refusal. This local fence does not provide a vendor filesystem
or occupant CAS. Current user acceptance targets latest versions and the physical Pixel; old
version/device matrices remain historical evidence and are not current acceptance requirements.
