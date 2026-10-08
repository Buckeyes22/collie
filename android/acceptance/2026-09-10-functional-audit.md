# Native Android interaction audit — 2026-09-10

Target: Chris's Samsung Galaxy S25 Ultra using Gboard. At the time of this 2026-09-10 audit,
`adb devices -l` exposed a Pixel and an emulator, not the S25 Ultra, and `adb mdns services` found
no wireless debugging endpoint. Neither the Pixel nor any live terminal was modified during this
audit. The next day's physical-device evidence is recorded below and in the
[2026-09-11 UX walk](2026-09-11-ux-walk.md).

## Reproduced defects

| Interaction | Before | Change | Regression |
| --- | --- | --- | --- |
| Type mode, Ctrl+A | No key reached the transport | Preserve canonical modifiers and named keys in hardware and IME events | Actual Android key-event injection plus hardware/IME unit cases |
| Keys, Ctrl, focus the base-key input, wait for a poll | The active editor was detached every two seconds | Update availability without rebuilding unchanged queue content | Keep the editor attached/focused across a real poll, then type and send |
| Expand function keys on a 360 × 640 dp screen | F5 and later rows were off-screen with no usable scroll range | Constrain the Keys scroll viewport to available window height before drawing; let collapsed content shrink | Scroll to and send every F1–F12 key; collapse and switch to the smaller digit pad |
| Tap terminal output to type | Composer focused but Gboard stayed closed | Explicitly show IME through the window insets controller | Tap rendered terminal text and wait for focused composer plus visible IME |

The initial `showSoftInput` implementation passed individually but failed in the longer
keyboard sequence. It was replaced with `WindowCompat.getInsetsController(...).show(ime())`.
Android documents why window/editor focus timing matters in its
[keyboard visibility guidance](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility).

## Evidence model

`NativeInteractionTest` uses Espresso/Android input dispatch, real Activities, ViewModels,
repositories, scheduled polling, layouts and Gboard. The only replacement is the API transport
and its in-memory connection store. Assertions check resulting requests and user-visible state,
not whether a view has a listener. Instrumentation replacement is confined to test code; no test
endpoint, authentication relaxation, screenshot-protection change or production credential is
introduced.

Gboard's on-screen key test taps Q, W, Space, Backspace and Enter using the actual keyboard row
bounds and asserts the complete ordered sequence after bounded asynchronous delivery. Touch timestamps
advance in real time. The probe clears cached accessibility bounds and waits for Gboard
rows to appear and remain stable before injecting any key taps. It assumes Gboard's English QWERTY layout;
it is distinct from the injected hardware-key checks. The emulator uses Gboard
15.1.08.726012951-preload-x86_64 and API 36. Test configurations are 1080 × 2400 at
420 dpi and 720 × 1280 at 320 dpi (360 × 640 dp). Neither emulates Samsung One UI.

One expanded run lost its emulator before completing. That run is incomplete, not a pass.
Subsequent runs use isolated read-only instances of `stormlens_api36` on ports 5582 and 5584.
The runner enables soft input with the emulator hardware keyboard and restores that setting
on exit. Screenshots remain protected by `FLAG_SECURE`; the black capture is not visual evidence.

## Interaction coverage

| Surface | Device-event checks |
| --- | --- |
| Setup | Pairing form, invalid-origin error, transition to dashboard; disconnect cancellation and confirmation |
| Dashboard | Settings and return; expand Spaces; Space → Pane → Back → dashboard; new Space label/directory; launcher selection and resulting pane; host/session selection; worktree creation and confirmed open |
| Space | Settings and return; new Tab |
| Pane navigation | Switcher upward drag, tap, close and Back; rename and visible title; explicit Show in terminal |
| Composer | Typed reply and exactly-once send; tap-to-type keyboard; rejected reply retains draft; read-only controls |
| Type mode | Characters, Space, Backspace, Enter; Ctrl+A; actual Gboard key taps |
| Keys | Arrow send; modifier input across polling; staged send; two-tap discard; empty queue on reopen; primary keys, digits 1–9 and F1–F12 |
| Quick and Agent | Quick reply; command search, selection and exactly-once request; Codex bound reply and Claude bound choice |
| Display and terminal | Display drawer open/close; wide-table horizontal pan without panning surrounding mirror |
| Search and History | Pane Find and Back; History search, next/previous and close |
| Settings | Typeface selection; terminal size increase/decrease; notification toggle; snooze and resume; light/dark recreation; draft size, Zen and hands-free preferences; pairing form; device revocation cancellation/confirmation; actual Updates/Pack navigation and Back |
| Pack | Formation-node touch, details sheet and close |
| Updates | Check, start confirmation, cancellation without a write, confirmed request |
| Media | Actual system photo picker opens and cancels without losing draft; actual microphone recording produces a draft through fixture STT |
| Lifecycle | Background polling stops, foreground resumes, previous reply is not replayed; network failure retains mirror and recovers |

## Reproduce

Use an API 36 emulator with Gboard in English QWERTY:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  scripts/android-interaction-test.sh emulator-5582
```

The script refuses physical-device serials and reports failure when instrumentation fails,
even if `adb` exits zero. It builds and installs the debug app and test APK on the emulator.
It does not publish, release, or update a live bridge.

## Acceptance still required at the time of this audit

These items remain in scope and must not be inferred from fixture success:

- **Not exercised in this audit — S25 Ultra unavailable through ADB on 2026-09-10:** the exact
  installed build, settings, touch gestures, keyboard transitions, font/display scale, rotation and
  accessibility behavior on Chris's phone. The 2026-09-11 UX walk later exercised many changed
  screens on that phone; see the dated follow-up below.
- Live terminal delivery, agent-specific prompts, authentication expiry and recovery against the
  actual bridge/multiplexer. The fixture checks API intent, not a live terminal's receipt.
- Actual attachment upload and real STT-provider results, including provider failures and denied
  permissions; native background push is not implemented by this client.
- Live-service outcomes for worktrees, host/session transitions, device revocation and Pack failure states. Their successful UI/request paths are fixture-tested.
- Actual update/restart progress and recovery, clean installation, process death and network loss.

Existing native unit/HTTP tests cover portions of these contracts, but do not establish their
complete touch-to-live-service acceptance. This audit is not a claim that every native surface
has passed on the S25 Ultra.

## Final run results

[Machine-readable execution record](2026-09-10-interaction-results.json), extracted from successful instrumentation output and native JUnit XML.

Two uninterrupted full runs passed: 50/50 scenarios at 720 × 1280 / 320 dpi and
50/50 at 1080 × 2400 / 420 dpi. The two subsequently added navigation checks passed 2/2 at each
configuration on the same debug APK. Total coverage: **52 distinct scenarios per configuration**, executed as
50-test full runs followed by 2-test navigation runs. No failed or interrupted run is counted
as a pass.

After the four production fixes, `testDebugUnitTest lintDebug assembleRelease` passed:
390 tests, zero failures/errors/skips, successful Android lint and minified release assembly.
Root and web typechecks, root lint, native configuration checks, version consistency and shell
syntax checks passed. For this audit, these builds remained local; nothing was installed on the S25
Ultra or released.

## Tested build identity

- Base checkout: `972facb`, with existing uncommitted work plus this audit's local changes.
- Debug package: `com.lateapex.collie.debug`, version `1.5.1-debug`, version code `2`.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `be1957dc5c38efb206e0ea1d5b08d45724b4aab3a0c331ac4c8cd952f21dd11f`.
- This is local validation evidence, not a new release or physical-device distribution claim.

## Later evidence and current release status

The 2026-09-10 S25 availability note above is a dated snapshot, not the current device state. On
2026-09-11, the [UX walk](2026-09-11-ux-walk.md) recorded 193 observed passes and zero failures on
the S25 Ultra across the changed screens, followed by additional device checks described at the end
of that record. A separate `connectedDebugAndroidTest` run passed 58/58 on the phone. This is
physical-device evidence for the builds and flows recorded there; it supersedes the unavailable-device
note for those later checks.

These results establish that the documented UI and interaction features were implemented and
observed on the tested builds. They do not establish complete live-service acceptance or acceptance
of the current signed release candidate. The 2026-10-07 local release preparation reports 521
native tests passed, native debug/release lint and builds passed, and signing tooling passed with a
disposable test key. Production signing setup and device acceptance against the exact signed
candidate remain outstanding; see [`RELEASE_READINESS.md`](../../RELEASE_READINESS.md). The
remaining acceptance items above should be completed and recorded against that candidate before a
distribution claim.
