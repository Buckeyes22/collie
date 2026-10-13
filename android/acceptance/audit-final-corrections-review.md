# Final review of media-send, accepted-message scope, and lifecycle corrections

Date: 2026-10-10  
Scope: read-only re-review of the current `PaneActivity.kt`, `PaneViewModel.kt`, and directly relevant tests after the F1/F2 corrections. Gradle was active, so no tests/builds were run.

## Disposition

The two previously reported findings are corrected in the current source, with focused regression tests. I found no confirmed regression in the key-repeat stop cleanup or delayed microphone-start guard. One non-blocking retention consideration remains for the per-connection accepted-message scopes; it does not invalidate the current leak fix.

## F1: Hands Free AutoSend busy ordering — corrected

`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:635-655` now awaits transcription inside `try/finally`, clears `composerMediaBusy` in the `finally`, and then calls `completeComposerMediaResult`. That helper also clears busy before applying the result. `applyComposerMediaResult` handles `AutoSend` at lines 700-705 and calls the normal `sendReplyWithConfirmation`; its busy refusal remains at lines 2090-2093. Therefore the completed transcription no longer enters that wrapper while the operation's busy flag is set, while a user send during an in-flight operation remains blocked.

`/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt:72-101` still verifies a pending upload blocks keyboard submission and preserves the draft. New coverage at lines 103-118 invokes the completed-result path and asserts the transcript is present and `pendingReplyAttempt` exists, which demonstrates that the send path began after clearing the gate. The existing pure policy tests in `ComposerMediaTest.kt:81-123` remain complementary. No duplicate-submission path was found in this re-review.

## F2: Codex accepted prompt scoping — corrected

`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt:119-125` now uses a private regular class, not a data class, keyed by origin and token. It has no generated `toString`; the source comment states that the scope is not serialized, and its only storage is a private in-memory list. `Connection` is also deliberately not a data class and redacts its token in `toString` (`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/domain/Connection.kt:15-26`). The connection-change collector continues clearing pane/transcript state and projects accepted replies from the currently selected scope (`PaneViewModel.kt:143-164`).

Updated test `/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt:595-630` verifies a different token at the same origin exposes no old prompt; changing only the label while retaining the token restores the same pairing's prompt; changing origin isolates it; returning to the original pairing restores its own pending prompt; and dismiss clears it. This directly covers the former same-origin gap. No token is passed into logs or persisted state in the reviewed implementation.

**Non-blocking retention consideration:** `acceptedScopes` retains each old token and its pending snippets until the `PaneViewModel` is destroyed. That is necessary for the current behavior of restoring pending confirmation text when returning to the same pairing, but the list has no explicit cap/eviction. I found no practical same-VM enumeration path in this bounded review that makes this a confirmed defect; a future change could bound scopes or discard entries on explicit credential revocation if lifecycle/retention requirements demand it.

## Held-key repeat lifecycle — corrected

`PaneActivity.onStop()` calls `cancelActiveKeyRepeat` and clears the callback field before other teardown (`PaneActivity.kt:379-394`). The closure created in `bindKeyRepeat` resets `active`, `engaged`, and repeat count, removes both delayed runnables, clears pressed state, and restores the key label (`PaneActivity.kt:1300-1337`). The release handler ignores a late up-event when the repeat was already canceled (`PaneActivity.kt:1342-1347`). Test `PaneActivityTest.kt:153-177` holds a key, stops the Activity, advances the main looper beyond the hold interval, verifies the UI reset, then dispatches a late release and verifies it cannot click. No regression found.

## Microphone start guard — adequate for the reviewed races

The button path checks availability, busy state, direct typing, write authorization, semantic-dialog ownership, and no-echo state before requesting permission (`PaneActivity.kt:595-611`). The permission-result callback calls `startSpeechRecording`, which rechecks availability, busy state, duplicate recording, `currentState.sending`, direct typing, semantic/no-echo state, and write authorization (`PaneActivity.kt:213-215,614-618`). This closes the delayed permission race where sending or direct typing begins after the prompt opens. `PaneActivityTest.kt:103-121` exercises a delayed start attempt while sending and while direct typing and confirms no recording begins.

The rendered microphone is also disabled unless a pane exists, writes are allowed, sending/media-busy/direct-typing are false, and no blocking dialog/no-echo prompt is open (`PaneActivity.kt:722-740`). `onStop()` cancels an active recorder (`PaneActivity.kt:389-392`). Hands Free submission rechecks composer readiness, write access, send state, direct typing, overlays, and window focus before AutoSend (`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/ComposerMedia.kt:44-55` and `PaneActivity.kt:658-671`). No unsafe submission from a backgrounded or stale composer was identified.

## Test and review limits

Source and test bodies were inspected only. Gradle was active, so no tests were executed. This report does not independently validate microphone permission UI behavior on a device or the full Activity lifecycle under process death. No production code was edited.
