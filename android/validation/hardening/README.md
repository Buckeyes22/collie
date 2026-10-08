# Android hardening bundle — 2026-10-07

The current Android source builds and its JVM, HTTP regression and resource checks pass.
The isolated emulator lab is operational. Device observations and follow-up coverage
are separated below. Expanded physical-device validation is follow-up work for the initial release;
this record does not claim those measurements have been performed.

## Dispositions

Each result applies to the recorded candidate and test scope.

| Unit | Disposition | Evidence and remaining scope |
| --- | --- | --- |
| Rotation and adaptive layouts | Passed within emulator scope | Local composer draft survived actual orientation changes on API 36. Fold/unfold changed and restored active display geometry; 320 dp/200% font navigation and foreground folded/unfolded setup captures passed. |
| Lifecycle and network transitions | Fixed and regression-tested | Pane/Space bounded read backoff, origin-scoped pane cache and one-shot POST bodies pass 8 focused JVM/HTTP regressions. API 36 fixture outage/recovery preserves the mirror without writes; physical radio/VPN and live-server transitions remain outside that fixture coverage. |
| Battery | Physical measurement not executed; follow-up | Foreground polling cancellation and background/resume behavior are tested. Repeated CPU, network and wakeup measurements require a physical Android device; the emulator cannot establish battery consumption. |
| Accessibility | Platform checks passed; spoken review not executed | Platform node descriptions, bounds, accessibility activation and touch selection passed. A connected TalkBack service with touch exploration passed focus/click activation. Audible announcements and full screen-reader traversal remain unreviewed. |
| Large text and display size | Fixed and retested on three profiles | Settings, custom diagrams and setup labels passed at 200% system font. Setup buttons now grow for wrapped text; a new regression fails the previous APK and passes the current candidate. Eight additional flows passed at 320 dp. |
| Light/dark contrast | Fixed and resource-tested | Actual resource pairs pass the Robolectric test; destructive text measures 6.21:1 dark and 4.57:1 light. This covers the tested palette, not every rendered/translucent surface. |
| Reduced motion | Targeted device checks passed | The diagram is static. Device interaction runs disable and verify all three system animation scales; navigation remains operable in the tested flows. |
| Reachable Git history and assets | Reviewed with specific privacy/provenance findings | Union of 1,581 commits inspected across local history and a full public-fork mirror; indicator and maintained secret scans, 103 raster blobs and 6 SVGs reviewed. Historical terminal captures and unresolved icon provenance have specific review findings; those require a sharing/asset disposition rather than a blanket device-validation gate. |

Detailed records: [UI/accessibility](ui-review.md), [lifecycle/network/battery](lifecycle-review.md),
and [history/assets](history-assets-review.md). The retained [evidence manifest](evidence.json)
records candidate/source digests, counts, UTC ranges, screenshot hashes and scoped limitations.

## Current build and dependency evidence

These checks ran after the hardening source changes.

JDK 21 and SDK 36 produced **536 JVM/Robolectric tests with zero failures, errors or skips**
across 82 XML result files. Debug/release lint, debug APK, instrumentation APK and minified
unsigned release APK builds passed. The current resource tests include the light/dark contrast
case and three custom-diagram text-layout cases; the notification decoder's three tests also pass.
These final artifacts include the setup-button layout fix and its instrumentation regression.
All 350 Android source/configuration inputs in the transferred archive match the current checkout.

The final unsigned release APK SHA-256 is
`e97724ee57c763e669e91a2546387023f7fa3ae7a9de42dcd44dff665199da59`.
Its CycloneDX 1.6 SBOM identifies 80 locked release runtime modules and this APK hash.
Grype 0.116.1 found **zero matches**, using valid schema 6.1.10 data built
`2026-10-07T06:31:48Z`. This inventory describes build inputs; it does not claim all R8 contents,
asset rights or dependency edges are inventoried. The complete updated artwork notice was also
verified byte-for-byte inside the minified APK.

The current release APK was signed with a disposable audit key to exercise the helper again.
Signature, pinned fixture certificate, checksums, version/source metadata, SBOM hash and packaged
notice verification passed. The audit key was deleted; this is not production signing.

Local raw evidence is retained at `/tmp/collie-native-clipping-fix-gradle-retry.log`,
`/tmp/collie-native-verified-sbom.cdx.json`, `/tmp/collie-native-verified-grype.json`, and
`/tmp/collie-verified-signing-3u5qaqn8/artifacts`. Temporary paths are execution artifacts;
the committed manifest preserves summarized identities and results without APKs, keys or transcripts.

The notification/SBOM/native-checker regression run passed 70 Bun tests. Three history scanner
end-to-end tests verify redaction, removed historical candidates, other reachable refs,
untracked files and the shallow-history boundary. Root/web typechecks, full-tree lint, Android
configuration, ShellCheck, workflow lint, version consistency and documentation links are also checked.

## Lab host access and isolated lab

Live SSH and KVM checks supersede the earlier restricted-session socket failures.

The working route is an SSH alias for the lab host in the operator's user SSH configuration:

```bash
ssh lab-host 'hostname; whoami'
```

It returned the lab host name and the operator's account. Live checks found JDK 21.0.12.1, usable
KVM, 16 threads, 61 GiB RAM and ample root filesystem space. The lab does not use `/mnt/data`.
Lab capacity: live checks on 2026-10-07.

SDK, Gradle state, Android user state and fresh AVDs live under
`$HOME/collie-android-lab` on lab-host. Installed tools include emulator 37.2.12, platform-tools
37.0.1, platform 36 revision 2, build tools 36.0.0, and Google APIs x86_64 images for API 26
revision 16 and API 36 revision 7. The phone/foldable profiles are Pixel 2, Pixel 7 and Pixel Fold.
A dedicated ADB server on port 5038 and an account-wide lock serialize owned emulator ports
5660/5662/5664. Only these disposable lab AVDs are wiped; process-group cleanup stops the launched
emulator and its children. Existing Android installations, apps and physical devices are untouched.

The reusable source runners are `scripts/android-emulator-lab.sh` and
`scripts/android-device-hardening.sh`. Transferred copies are under
`$HOME/collie-android-lab/runners` on lab-host. The build runner copies Android and shared parity
fixture inputs, verifies source hashes before/after building and records APK/tool identities.
The device runner verifies a strict two-APK manifest, copies the pair into run-specific evidence,
checks the API and checks instrumentation's reported result rather than ADB's exit code alone.

Clean remote builds and baseline instrumentation passed on API 26 and API 36 phones: five cases
each cover native launch, system insets, agent artwork and encrypted Keystore round-trip/wipe.
Run directories are `$HOME/collie-android-lab/run-26-phone-dui0R0` and
`$HOME/collie-android-lab/run-36-phone-g5nbSo`. An early API 26 runner cleanup/lock inheritance
failure was fixed; its instrumentation passed, and the corrected API 36 runner exited successfully.

## Final emulator results

All three profiles used the same verified APK pair and passed **86 instrumentation cases**.

| Profile | Passed cases | UTC range on 2026-10-07 | Retained lab-host run directory |
| --- | --- | --- | --- |
| API 36 Pixel 7 phone | 69: baseline 5, full interaction 51, UI 4, narrow-font flows 8, TalkBack platform 1 | 23:19:49–23:23:04 | `$HOME/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3` |
| API 26 Pixel 2 phone | 9: baseline 5 and UI 4 | 23:25:21–23:25:50 | `$HOME/collie-android-lab/hardening-1.5.1-debug-26-phone-0K8vsN` |
| API 36 Pixel Fold | 8: baseline 5 and UI 3; additional posture/display capture checks | 23:25:53–23:26:58 | `$HOME/collie-android-lab/hardening-1.5.1-debug-36-foldable-WkuvUJ` |

The debug APK SHA-256 is
`6c8918c234a6924dadddd1c28640fd033b1cb6b094a92978b99e94e53186d04c`;
the instrumentation APK SHA-256 is
`ceb6944b493d32ece7baf1194fc51df285dbe32f5eb7aeb1a18eeb89b68bdc38`.
Both hashes were verified before installation and after each run.

The phone suite covers actual orientation/draft preservation, background/resume, write gates,
fixture outage/recovery, real Gboard touch input and native navigation. API 36 narrow runs use
200% system font and 540 dpi, producing 320 dp width. All three system animation scales were
verified as zero. TalkBack was connected with touch exploration enabled during a platform
accessibility-focus/click test; it does not establish audible speech or complete traversal.

Fold states changed **2 → 0 → 2** and display geometry changed
**2208×1840 → 1080×2092 → 2208×1840**. Foreground setup PNGs matched each active display's dimensions.
The primary visually reviewed `/tmp/collie-verified-folded-200.png` and
`/tmp/collie-verified-unfolded-200.png`: full labels, fields and setup controls fit at 200% font.
Phone rotation assertions are excluded from this large-window profile under Android 16 policy.

The new full-label regression failed the previous APK at 320 dp/200% font because fixed-height
buttons clipped wrapped labels. Wrap-content button heights with a 48 dp minimum and vertical
padding fixed it; every final profile passed. The expected negative result is retained in
`/tmp/collie-lab-host-clipping-negative.log`. An earlier duplicate-key observation came from a 482 ms
injected gesture exceeding the intentional 350 ms repeat threshold. A controlled 40 ms short tap
now passes the full suite with the exact key-count assertion intact; production repeat behavior
was unchanged. Recovery checks now wait for bounded observable state instead of a fixed sleep.

Final device logs are `/tmp/collie-lab-host-verified-36-phone.log` and
`/tmp/collie-lab-host-verified-26-and-fold.log`. These final results supersede interim device attempts.
No owned emulator processes remained after cleanup.

## Reproduce device checks

Use a reviewed build and one disposable lab profile at a time.

The APK directory contains `app-debug.apk`, `app-debug-androidTest.apk`, `apks.sha256` with exactly
two basename-only SHA-256 entries, and `interaction-methods.txt` selecting the reviewed
`NativeInteractionTest` cases. API 36 phone runs the selected suite and eight additional flows
at 200% font/540 dpi (320 dp on this AVD). The full suite includes a Gboard-specific case; installed,
English QWERTY Gboard is its prerequisite. The current API 36 image has Gboard and TalkBack installed.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash $HOME/collie-android-lab/runners/android-emulator-lab.sh validate \
  $HOME/collie-android-lab $HOME/collie-android-lab/checkout 26-phone
COLLIE_LAB_TALKBACK=1 bash $HOME/collie-android-lab/runners/android-device-hardening.sh \
  $HOME/collie-android-lab $HOME/collie-android-lab/reviewed-candidate 36-phone
```

Repeat device checks for `26-phone` and `36-foldable`. Fold evidence requires active state IDs
and display geometry to change and restore before naming screenshots folded/unfolded.
Android 16 large-window policy can ignore requested orientation, so foldable orientation is
validated through posture/display changes rather than the phone-only rotation assertion.
All interaction fixtures use synthetic credentials and local transports.

Tool selection used the [official SDK checksum page](https://developer.android.com/studio),
[emulator command-line guide](https://developer.android.com/studio/run/emulator-commandline)
and [Linux acceleration guide](https://developer.android.com/studio/run/emulator-acceleration),
checked 2026-10-07; installed revisions are retained in each run.

## Follow-up validation and publication review

The unexecuted device checks below are follow-up coverage, not mandatory initial-release gates.

| Area | Missing evidence |
| --- | --- |
| Physical lifecycle/network | Radio/VPN handoff, server restart, process death and recovery against a disposable paired real server. Fixture tests establish client behavior within their scope. |
| Real-server writes | Retain server-side evidence of at most one harmless input with an interrupted response/HTTP 503 retry hint. JVM HTTP and device fake-transport tests already cover automatic-replay regression. |
| TalkBack | Spoken navigation/announcements, sheet/dialog focus restoration and activation across all screens. Installed service and virtual-node tests do not establish a spoken review. |
| Battery | Repeated controlled physical-device foreground/background/offline CPU, network and wakeup measurements. No physical device was made available or reset for this run. |
| Extended visual sweep | Both themes, split window, keyboard and maximum display/font settings across all screens; retain reviewed synthetic captures. Retain the distinction between debug fixture observations and signed-artifact checks. |
| Assets | Do not republish historical terminal captures before privacy review; record a disposition for specific icon provenance findings and preserve required license notices. |

For each follow-up run retain device/OS/API, APK/source identity, server version, UTC range,
conditions and observations. Unperformed measurements remain coverage limits. They do not block
the initial release by themselves. Signing, a basic signed-artifact smoke check and publication
review are tracked separately in [release readiness](../../../RELEASE_READINESS.md).
