# Android UI and accessibility hardening review

Review date: 2026-10-07
Machine: z2 isolated emulator lab
Device/emulator profiles: API 26 phone, API 36 Pixel 7 phone, API 36 foldable
Build/APK identity: Android 1.5.1 debug candidate; hashes below
Device interaction: all three emulator profiles passed on 2026-10-07

## Findings and dispositions

### Adaptive layouts — PASS ON API 26 PHONE, API 36 PHONE AND API 36 FOLDABLE

The API 26 and API 36 phone runs passed the real orientation-change composer-draft test. On the
API 36 phone, the full 51-case `NativeInteractionTest` suite passed, including Gboard with the
installed IME. Eight selected interaction flows also passed at 200% font scale and 540 dpi, which
produced a 320 dp-wide viewport.

The API 36 foldable run passed its three applicable UI tests and verified device-state transitions
from state 2 to 0 and back to 2. The reported display geometry changed from 2208 × 1840 to
1080 × 2092 and returned to 2208 × 1840. Folded and unfolded 200% captures showed the setup fields
and buttons fitting on screen. These emulator results do not substitute for a physical foldable or
operator-server acceptance.

Evidence: `/home/chris/collie-android-lab/hardening-1.5.1-debug-26-phone-0K8vsN/`;
`/home/chris/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3/`;
`/home/chris/collie-android-lab/hardening-1.5.1-debug-36-foldable-WkuvUJ/`;
`/tmp/collie-verified-folded-200.png`;
`/tmp/collie-verified-unfolded-200.png`;
`/tmp/collie-z2-verified-26-and-fold.log`;
`/tmp/collie-z2-verified-36-phone.log`.

### TalkBack labels, focus and control state — PLATFORM FOCUS AND ACTIVATION PASS; SPOKEN CHECK OPEN

TalkBack was enabled and connected, and touch exploration was active. `TalkBackDeviceTest` used
`UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` to keep the service running. It verified
the complete Settings gear label, moved platform accessibility focus to the gear, activated it
through the accessibility node's click action, and confirmed the Settings screen became visible.
The large-text pack test also verified the full lead member description, virtual-node bounds,
drawing, accessibility click activation, and independent touch selection.

These checks establish platform node focus and activation. They do not verify spoken output, focus
order across the whole app, announcements, or TalkBack traversal on other screens; those remain
manual acceptance checks. No TTS output was synthesized or asserted.

Evidence: `/home/chris/git/collie/android/app/src/androidTest/java/com/lateapex/collie/ui/TalkBackDeviceTest.kt`;
`/home/chris/git/collie/android/app/src/androidTest/java/com/lateapex/collie/ui/HardeningUiDeviceTest.kt`;
`/home/chris/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3/`.

### Text/display scaling — PASS ON API 26 AND API 36 PHONE; FOLDABLE CAPTURE PASS

Most layout text is declared with `sp`. The pane composer allows up to five lines, and existing
layout tests check its scaled size calculation and growth. `PackFormationView` sizes canvas labels
with Android's SP dimension conversion, truncates long visible names at measured width, and places
each name below its circle using active font metrics. Its measured height grows for expanded name
descenders and role/count badges; row spacing expands when overlapping name and badge extents would
collide. The top inset keeps expanded badges inside the view. Drawing, touch hit-testing and
accessibility bounds use the same adjusted node positions. Empty formations retain zero height, and
full member names remain available through accessibility nodes.

Robolectric coverage checks 200% font scaling at narrow width, lead/deputy with six blocked peers,
name-to-circle clearance, adjacent row separation and measured bottom bounds. Device tests passed
at 200% system font scale on both phone profiles. On the API 36 phone, the 540 dpi override yielded
320 dp width; the setup CTA test verified complete resource labels, zero ellipsized characters,
button height covering all text lines and padding, a 48 dp minimum touch target, and scroll-to-visible
reachability without triggering a connection or pairing action. Eight additional interaction flows
passed at this narrow large-text setting. The API 36 foldable's folded and unfolded 200% captures
showed the setup fields and buttons fitting on screen.

The layout change replaces fixed 48 dp heights on the setup actions with wrap-content height while
retaining the 48 dp minimum and vertical padding. A negative check against the prior fixed-height APK
failed the intended button-height assertion; the current candidate passed it. This covers the
observed setup-label clipping defect and the tested surfaces, not every screen at Android's maximum
font scaling.

Evidence: `/home/chris/git/collie/android/app/src/main/res/layout/activity_main.xml`;
`/home/chris/git/collie/android/app/src/main/res/layout/activity_pane.xml`;
`/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt`;
`/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/PaneLayoutTest.kt`;
`/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/PackFormationTextLayoutTest.kt`;
`/home/chris/git/collie/android/app/src/androidTest/java/com/lateapex/collie/ui/HardeningUiDeviceTest.kt`;
`/tmp/collie-z2-clipping-negative.log`.

### Light/dark contrast — PASS IN SOURCE AND RESOURCE TEST

The dark destructive button used `#FAFAFA` text on `#FF6467`, measuring 2.77:1. The dark
`collie_on_destructive` token is now `#171717`, measuring 6.21:1 on that fill. The light pair
remains `#FAFAFA` on `#E7000B` at 4.57:1. The remaining measured foreground, muted, semantic-status,
primary-button and status-ring pairs met their 4.5:1 text or 3:1 non-text thresholds on the
reviewed card/background surfaces. These thresholds follow WCAG 2.2 Success Criteria 1.4.3 and
1.4.11 ([text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html),
[non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)); this is a
targeted palette check, not a full conformance evaluation.

The complete JVM suite passed the focused resource contrast test, which resolves both light and dark
resources and checks the actual token pairs. This covers the reviewed resource combinations; it is
not a full visual contrast audit across every composited screen state.

Evidence: `/home/chris/git/collie/android/app/src/main/res/values/colors.xml`;
`/home/chris/git/collie/android/app/src/main/res/values-night/colors.xml`;
`/home/chris/git/collie/android/app/src/main/res/values/settings_native_parity.xml`;
`/home/chris/git/collie/android/app/src/test/java/com/lateapex/collie/ui/AccessibilityContrastTest.kt`.

### Reduced motion — STATIC PACK VIEW; ANIMATION SCALES DISABLED DURING DEVICE RUNS

`PackFormation.kt` draws a static custom view and contains no animation or motion transition, so
source review found no reduced-motion defect there. The hardening runs disabled and verified the
window, transition and animator duration scales before interaction testing. This does not establish
a full-system reduced-motion experience across every screen or third-party component.

Evidence: `/home/chris/git/collie/android/app/src/main/java/com/lateapex/collie/ui/PackFormation.kt`;
`/home/chris/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3/`.

## Verification and device disposition

The final API 36 phone hardening run passed 69 tests: baseline 5, full `NativeInteractionTest` 51,
large-text UI 4, TalkBack platform focus/activation 1, and eight narrow-large-text interaction
cases. The full interaction suite included Gboard and used the actual installed IME. The API 26
phone run passed 9 tests (baseline 5 plus UI 4). The API 36 foldable run passed 8 tests (baseline 5
plus its three applicable UI cases) and verified fold/unfold state and display-size restoration.
All three system animation scales were verified as zero in the API 36 phone run.

The final candidate APK hashes are debug
`6c8918c234a6924dadddd1c28640fd033b1cb6b094a92978b99e94e53186d04c` and instrumentation
`ceb6944b493d32ece7baf1194fc51df285dbe32f5eb7aeb1a18eeb89b68bdc38`. Evidence directories and run
windows are:

- API 36 phone: `/home/chris/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3/`,
  2026-10-07T23:19:49Z–23:23:04Z; log `/tmp/collie-z2-verified-36-phone.log`.
- API 26 phone: `/home/chris/collie-android-lab/hardening-1.5.1-debug-26-phone-0K8vsN/`,
  2026-10-07T23:25:21Z–23:25:50Z.
- API 36 foldable: `/home/chris/collie-android-lab/hardening-1.5.1-debug-36-foldable-WkuvUJ/`,
  2026-10-07T23:25:53Z–23:26:58Z; combined log `/tmp/collie-z2-verified-26-and-fold.log`.

The complete JVM suite previously passed 536 tests, including three `PackFormationTextLayoutTest`
cases and the actual-resource contrast test. The current device candidate includes the setup CTA
height fix and passed the new fourth `HardeningUiDeviceTest` case on both phone profiles. This report
records emulator UI evidence for the debug candidate; it does not claim a signed-candidate visual
sweep, physical-device acceptance, or real-server acceptance.

Follow-up validation includes spoken TalkBack output and whole-app focus traversal, an expanded
visual sweep, and physical-device/real-server observations. These are not inferred from emulator
results and are not mandatory initial-release gates.
