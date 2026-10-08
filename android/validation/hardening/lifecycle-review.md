# Android lifecycle and network hardening review

Status for this review: current local Android unit-test, lint, and APK build gates passed on
2026-10-07. Selected API 36 phone-emulator instrumentation passed, including lifecycle, synthetic
network recovery, touch/accessibility layout, and connected TalkBack focus/activation checks. These
results do not establish real-radio or physical-device acceptance or battery measurements. No
physical battery or network-power measurements are claimed.

## Findings and changes

Dashboard, Space, and Pane activities start their foreground polling from `onStart()` and stop it
from `onStop()`. The API client uses cancellable OkHttp calls, applies bounded call timeouts, disables
automatic connection retries, and refuses redirects. OkHttp can still replay a repeatable POST body
for an HTTP 503 with `Retry-After: 0`, even with connection retries disabled. API POST bodies are
now marked one-shot so that follow-up cannot replay a user write. Repository terminal writes cross
the API once; an ambiguous response is not retried because the server may already have received the
input.

The dashboard already backed off repeated snapshot failures. Space and Pane used fixed five-second
and two-second intervals, so an offline server caused frequent reconnect attempts for as long as the
screen stayed visible. They now use the same bounded exponential backoff: a successful read or 304
resets it, failures double the next interval, and polling is capped at 60 seconds. Returning to the
screen starts a fresh read immediately. Network restoration while the screen remains visible is
noticed on the next scheduled attempt, within the 60-second cap; there is no connectivity callback.

Transient read failures keep the last successful dashboard, Space, or Pane data in memory while the
error is shown. The encrypted connection survives process death, but these screen snapshots are not
persisted; a cold start must fetch current data from the server. Writes still make one attempt and
require the operator to inspect the pane before resending after an ambiguous failure.

## Automated coverage

`HardeningLifecyclePollingBackoffTest` covers the backoff sequence, cap/reset, and large-input overflow
handling. `HardeningLifecycleOneShotRequestBodyTest` checks that the wrapper preserves content type,
length, one-shot status, and serialized bytes without starting a server. `HardeningLifecyclePollingTest`
covers preserved Pane and Space data during an offline failure followed by recovery, and ViewModel
cancellation of an in-flight Pane read when foreground polling stops. `HardeningLifecycleRepositoryTest`
covers ETag isolation across server origins. `HardeningLifecycleApiClientTest` exercises HTTP 503
Retry-After: 0 behavior with MockWebServer.
Existing repository and API-client tests cover one-attempt terminal writes, disabled OkHttp
connection retries, and same-origin ETag reuse. The API client's `Call.cancel()` hook is source
reviewed; the lifecycle test verifies cancellation at the ViewModel fake-API boundary. MockWebServer
verifies the HTTP 503 `Retry-After: 0` request is sent once. Transport cancellation during a live
MockWebServer call and physical server/network transitions were not exercised.

On 2026-10-07, the full `:app:testDebugUnitTest` task passed **536 tests, 0 failures, 0 errors, and
0 skipped**. This includes all five `HardeningLifecycle*` suites: 8 tests total (API client 1,
backoff 2, polling 3, repository 1, one-shot body 1), each passing according to its JUnit XML under
`android/app/build/test-results/testDebugUnitTest/`. The same current Gradle
run passed `lintDebug`, `lintRelease`, `assembleDebug`, `assembleDebugAndroidTest`, and
`assembleRelease` (`BUILD SUCCESSFUL`; 135 actionable tasks, 15 executed and 120 up-to-date, recorded
in `/tmp/collie-native-clipping-fix-gradle-retry.log`). The release APK from this task is unsigned.
An earlier attempt under a more restrictive execution profile failed before Gradle initialization
on the read-only wrapper lock; that limitation was resolved for the passing run and is not the
current test status.

The final API 36 phone-emulator run at
`$HOME/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3`, recorded in
`/tmp/collie-lab-host-verified-36-phone.log` (2026-10-07 23:19:49–23:23:04 UTC), passed 69 selected
instrumentation tests: five baseline tests, all 51 `NativeInteractionTest` cases, four UI-device
cases at 200% font and 320 dp width, eight focused layout cases, and one connected TalkBack platform
test. The interaction suite passed background polling stop/resume, fixture network failure and
recovery, and harmless fake-transport writes exactly once. The layout checks exercise the clipped
button-height regression: it fails on the earlier APK and passes on the current APK. The TalkBack
test verifies the platform gear label, accessibility focus, and activation into Settings; it does
not measure spoken output or full screen-reader navigation. The fixture network route is synthetic:
this run did not test Android radio/VPN changes or a live bridge/server restart. The emulator run is
not physical-device or battery evidence.

The same final debug build passed selected API 26 phone and API 36 foldable suites. API 26 phone
evidence at `$HOME/collie-android-lab/hardening-1.5.1-debug-26-phone-0K8vsN` records nine
tests (five baseline and four UI cases). API 36 foldable evidence at
`$HOME/collie-android-lab/hardening-1.5.1-debug-36-foldable-WkuvUJ` records eight tests (five
baseline and three UI cases) and a 2208×1840 → 1080×2092 → 2208×1840 folded/unfolded/folded state
cycle. Foreground captures `/tmp/collie-verified-folded-200.png` and
`/tmp/collie-verified-unfolded-200.png` were visually reviewed. These emulator checks do not replace
acceptance on a physical foldable or target phone.

No live-server transition, production-signed candidate acceptance, or physical battery measurement
is marked as passed. The earlier 521-test result remains historical and is superseded by the 536-test
run above.

## Device and server follow-up

This extended matrix is follow-up coverage rather than an initial-release gate. Run it against the exact debug build under review on a dedicated device and disposable
Collie server. Do not use a personal terminal for write checks.

| Scenario | Expected observation |
| --- | --- |
| Cold launch while the network is unavailable | The saved server origin remains configured; the screen shows a connection error until the server responds, with no stale screen snapshot claimed across process death. |
| Network loss with a loaded dashboard, Space, and Pane | Last successful rows and pane text remain visible with an error; no terminal write is repeated. **Synthetic API 36 emulator Pane coverage passed** for mirror retention and no-write behavior; real radio/VPN loss and all-screen hardware coverage remain untested. |
| Network restored while each screen remains visible | The read recovers by the next scheduled attempt, no later than 60 seconds after the capped interval is reached. **Synthetic API 36 emulator Pane recovery passed**; real connectivity restoration remains untested. |
| App backgrounds during an in-flight read | The request is canceled when the activity stops; no further polling occurs until the activity starts again. **API 36 emulator test passed** for activity backgrounding and fake-API poll stop; live transport cancellation remains untested. |
| App returns from background | A fresh read starts promptly and the UI replaces stale data when the server responds. **API 36 emulator test passed** for resume and fake-API refresh; a live-server resume remains untested. |
| Server restarts while a Pane is open | The existing pane remains visible during the outage; the error clears and the pane refreshes after the bridge is reachable. **Not yet tested against a live bridge/server restart.** |
| One harmless reply with response interrupted | Host audit evidence shows at most one write attempt; inspect the pane before manually deciding whether to resend. |

For each scenario, record device model and build, Android API, APK SHA-256, app version, server
version, start/end UTC, connectivity state, visible UI result, and server-side write count where
applicable. Keep request logs free of pairing codes, bearer tokens, and terminal contents.

## Repeatable battery and network measurement

Source inspection predicts that normal polling stops when the activity is no longer started. It
cannot establish radio wakeups, CPU time, battery drain, or the behavior of a particular Android
build. Measure those on physical hardware; do not infer them from emulator runs.

Use the same physical device, OS build, APK, server state, Wi-Fi/cellular state, screen brightness,
and battery range for each run. Repeat each phase at least three times and keep the Android Studio
Profiler or Battery Historian export with the run record.

1. Install the exact APK and start from a known app state with no other foreground workload.
2. Reset Android's battery statistics with `adb shell dumpsys batterystats --reset`.
3. Leave the Dashboard open and untouched for 30 minutes; capture battery statistics, app CPU time,
   network bytes, and wakeups.
4. Reset statistics and repeat with a Pane open for 30 minutes without input.
5. Reset again, open a Pane, background Collie for 30 minutes, and verify that no periodic API reads
   occur while it is stopped.
6. Repeat the foreground Pane phase with the network unavailable, observe backoff intervals, restore
   the network, and record time to the first successful read.
7. Compare repeated runs and retain profiler exports, battery-stat output, APK identity, and all test
   conditions with the follow-up validation record.

Battery percentage alone is too coarse for a short run. Report measured intervals, CPU/network
activity, wakeups, and the profiler evidence; only compare battery change when the device exposes a
resolution that supports the comparison.
