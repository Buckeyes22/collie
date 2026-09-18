# Android always-on diagnostics capture — design

**Status:** Approved by Chris, 2026-09-18. Ready for `writing-plans`.

## Problem

The Android app works about 85% of the time; the rest of the time it freezes (ANR-style
unresponsiveness), gets stuck in a wrong UI/state, or silently drops an action (a reply appears
sent on the phone but never reaches the terminal/agent). There is currently no logging, crash
handler, or diagnostics capability anywhere in the Android app (`grep`-confirmed: zero
`android.util.Log` calls, no `UncaughtExceptionHandler`, no export mechanism). Reproducing these
bugs on demand isn't reliable, and there is no way today to arm verbose logging ahead of time
without knowing when a failure will happen.

## Goal

Capture enough detail, continuously, during ordinary use that when a failure is *noticed after the
fact*, a trace covering the run-up to it already exists on the device and can be handed over for
diagnosis — without Chris ever needing to pre-arm a verbose mode or reproduce the bug live.

## Symptom classes this must help diagnose

Confirmed with Chris: UI freezes/ANRs, stuck/wrong UI state, and silent drops (an action appears to
succeed on the phone but the terminal/agent never sees it). Crashes were not reported as observed,
but the design covers them anyway since a crash handler is nearly free once the rest exists.

## Scope decisions (confirmed with Chris)

- **Content capture: full.** Request/response bodies and pane/terminal content are captured in
  full, not redacted or metadata-only. Chris explicitly chose this over the lower-capture options,
  understanding the resulting trace is as sensitive as a live terminal session. "Full" applies to
  text bodies; binary bodies (image uploads, STT audio clips) are captured as content-type + size
  only — dumping raw/base64 media into a text trace would blow past the retention cap in a handful
  of requests and isn't "terminal content" in the sense this decision was about.
- **Hard exception, non-negotiable regardless of the above:** the pairing bearer credential itself
  is never written to the trace, only a boolean "request carried a credential." This is an existing
  repo-wide invariant (CLAUDE.md → *Security posture*: "a socket call can type into a real
  terminal — treat a collie as remote shell access"), not a diagnostic-content decision, and it is
  not up for revision by this feature.
- **Export: manual share sheet**, not auto-upload. Nothing leaves the device on its own. A
  "Send diagnostics" action in Settings packages the current trace and hands it to Android's share
  sheet; Chris decides where it goes each time.
- **Bridge correlation: yes.** The Android client tags each outgoing request with a trace-id and
  the bridge logs that id alongside its own request handling, so a client-side event can be matched
  to the bridge's `journalctl` output by id instead of by timestamp guessing. This is a small
  bridge-side change (`bridge/server.ts`), not purely an Android change.
- **Toggle: yes**, added after initial design review. A Settings switch
  (`NativePreferences.diagnosticsEnabled`, default `true`) gates whether captured events are
  actually written. Default-on satisfies "don't have to isolate a time or manually arm it"; the
  switch exists so the capture can be turned off later once the underlying bugs are found, without
  a rebuild or reinstall.

## Architecture

A new package, `android/app/src/main/java/com/lateapex/collie/diagnostics/`:

- **`DiagnosticsRecorder`** — the only entry point other code calls. Shape:
  `record(category: String, fields: Map<String, Any?>)`. Backed by a single-thread executor with an
  in-memory queue; checks `NativePreferences.diagnosticsEnabled` before doing any work. Runs on its
  own thread so a frozen main thread (an ANR) never blocks it and it never blocks a caller.
- **`DiagnosticsWriter`** — owns the on-disk state: appends newline-delimited JSON to a rotating set
  of files (`filesDir/diagnostics/trace-0.jsonl` … `trace-3.jsonl`, ~5MB each, oldest file dropped
  when the set is full). Runs on `DiagnosticsRecorder`'s background thread.
- **`AndroidKeystoreCipher`** — already exists (currently encrypts the pairing bearer in
  `EncryptedConnectionStore`); reused as-is to encrypt each rotated file at rest, matching the
  app's existing security bar for sensitive on-device data rather than inventing a second scheme.
- **An OkHttp `Interceptor`**, added to the `OkHttpClient` built in `AppContainer`/
  `CollieApiClient.defaultHttpClient()`. This is the single place that already sees every request
  and response, so network capture and trace-id tagging happen here once instead of at every call
  site.
- **A global `Thread.UncaughtExceptionHandler`**, installed in `CollieApplication.onCreate()`:
  records the throwable and stack trace, then chains to Android's previous default handler so the
  normal crash behavior (process restart) is unchanged.
- **An ANR watchdog**: a background thread posts a token to a `Handler` on the main `Looper` every
  2s and expects it processed within 5s; a miss is recorded as a "main thread blocked" event with
  however much extra context is available (last known screen, last recorded action). On API 30+
  (the S25 Ultra target device is API 36), `ActivityManager.getHistoricalProcessExitReasons()` is
  also read on the next app launch to pull the OS's own ANR/crash trace for the *previous* process
  — this catches the case where the watchdog itself never got a chance to observe the freeze before
  the process died, and costs nothing to add since the API already exists on the target OS version.
  On API 26–29 devices this historical-exit-reason read is skipped (API not available); the
  watchdog still applies to those devices.

## What gets captured

| Category | Fields |
| --- | --- |
| Network | method, path, status, duration, byte sizes, trace-id, `hadCredential: Boolean` (never the credential itself); text bodies (JSON/plain text) captured in full, binary bodies (image uploads, STT audio clips) captured as content-type + size only, never as raw/base64 bytes |
| Lifecycle | onCreate/onStart/onResume/onPause/onStop/onDestroy per Activity; process start marker |
| Pane body decisions | Every `PaneBodyDecision.decide()` result and reason, and when it changes |
| Named user actions | Identified by button/menu identity ("tapped Send," "opened Settings," "switched pane") — not raw touch coordinates |
| Polling | Cycle start/end, interval chosen, outcome |
| Crashes / ANRs | Uncaught exceptions with stack trace; watchdog misses; historical `ApplicationExitInfo` on next launch (API 30+) |

## Retention

Rolling cap of ~20MB across 4 rotating files, oldest dropped first. No time-based expiry — size is
the only bound. The exact file/size split may be adjusted during implementation once real event
volume under full-content capture is measured; this is a tuning detail, not a scope change.

## Export flow

Settings → **"Send diagnostics."** Tapping it shows a confirmation dialog stating plainly that the
export includes recent terminal content and may contain sensitive information. On confirm: decrypt
the current trace files into a temporary zip under a `FileProvider`-scoped cache directory, hand
that off via `ACTION_SEND` to Android's share sheet. The temporary plaintext zip is deleted the
moment the share sheet returns control to the app, and again on next app start as a backstop.

## Bridge correlation

The OkHttp interceptor generates a short trace-id (UUID) per outgoing request and sends it as a new
request header, `X-Collie-Trace-Id`. `bridge/server.ts`'s `fetch(req)` gains one structured
access-log line per request (method, path, status, duration, and that header's value) written to
stdout, which the existing `systemd --user` unit already sends to `journalctl --user -u collie`.
No new bridge storage, no new endpoint — this only adds a log line to a request path that already
runs.

## Toggle

`NativePreferences.diagnosticsEnabled: Boolean` (default `true`), same `SharedPreferences`-backed
boolean-property pattern as the existing `hapticsEnabled`. A Settings switch row controls it. When
off, `DiagnosticsRecorder.record()` returns immediately without queuing or writing anything; the
interceptor, watchdog, and exception handler stay installed regardless of the flag (cheap to leave
running) and simply produce no output when it's off. Flipping it needs no rebuild or reinstall.

## Testing

- Robolectric unit tests for `DiagnosticsRecorder`/`DiagnosticsWriter`: rotation at the size cap,
  encryption round-trip via `AndroidKeystoreCipher`, and the disabled-toggle no-op path.
- A unit test on the interceptor that fails loudly if the bearer credential value ever reaches a
  recorded field — this is the one test in the whole feature that must never be weakened or
  removed.
- A unit test on the ANR watchdog's timeout/miss logic (the watchdog itself can't be exercised
  end-to-end in Robolectric, since deliberately freezing the main thread isn't safe in a test
  harness).
- Manual on-device verification during the walk: confirm a real network call, a real pane-body
  decision change, and a real Settings toggle flip all show up correctly in an exported trace, and
  that turning diagnostics off actually stops new writes.

## Out of scope

- Auto-upload of traces to the bridge or any third-party service.
- Redaction/scrubbing of pane content (explicitly rejected in favor of full capture).
- Any change to what the bridge stores permanently — the bridge-side change here is one log line,
  not new persistence.
