# Android release preparation

Status for the local preparation work on 2026-10-07: the Android release path is implemented,
and the implementation/build hardening pass is complete. Publishing the first APK still needs
release signing and the publication steps below. Expanded device validation is follow-up work.

## Prepared

The fork now presents and distributes the native Android app independently of the upstream PWA.

- Android-first README, onboarding, contributor guide, app changelog and release runbook.
- Explicit attribution to AltanS/collie, preserved upstream README and MIT notice.
- Empty public-build server field and configured-server-only internal deep-link handling.
- Diagnostic capture off by default for new installs; explicit upgrade preferences preserved.
- Pairing-body exclusion so codes and returned credentials do not enter diagnostic capture.
- APK-bundled MIT, font and artwork notices.
- Independent `android-vX.Y.Z` workflow with native gates, signing, pinned certificate verification,
  checksums, source/version metadata and a draft GitHub Release.
- Android issue/PR templates, privacy policy and private vulnerability reporting policy.
- Server release automation restricted to upstream; paid model triage requires explicit opt-in.

## Validation evidence

The following rows preserve results from the initial preparation, before the follow-up contract and
broader hardening changes. They remain historical and do not establish a current-source build or
physical-device acceptance.

| Check | Result |
| --- | --- |
| Native JVM/Robolectric tests | 521 tests passed, zero failures/errors/skips on the initial preparation build |
| Android debug lint | Passed |
| Debug APK and instrumentation APK build | Passed |
| Minified unsigned release APK build | Passed |
| Android release lint | Passed |
| Root and web TypeScript checks | Passed |
| Full-tree oxlint | Passed |
| Signing helper with disposable key | Passed actual signing, signature and checksum verification |
| Signing failure controls | Missing credential, wrong tag, wrong certificate and existing output all refused |
| Signing metadata and cleanup | Versions, source, dirty state and signer recorded; failed staging removed |
| Pack integration retry | 61 tests passed after an initial suite-hook failure |
| Root bridge/CLI/script suites | 3,972 tests passed plus all CLI/shim/tag shell checks on retry |
| Web suite | 183 files passed; 5,063 tests passed, 30 existing todo cases on reduced-worker retry |
| Documentation and workflow checks | 94 Markdown files passed link checks; actionlint, shellcheck and version consistency passed |
| APK notices | MIT, font and artwork notice resources verified inside the earlier minified APK; provenance findings below still require disposition |
| Working-tree credential scan | No selected high-confidence private-key/GitHub-token/AWS-key signatures found |

The credential scan covered tracked working-tree files below 2 MB. It is not a complete
git-history, dependency or screenshot audit. Historical acceptance images and terminal fixtures
remain development evidence; use reviewed synthetic captures for public marketing or store assets.

The initial full web run failed with `ENOSPC` and a playground timeout while the workstation disk
was nearly full. The reduced-worker retry passed all 183 files. The initial root run had one
suite-hook failure; an isolated Pack retry and the complete root retry passed. No unrelated files
were deleted to make room.

### Current Android source and emulator evidence (2026-10-07)

The final current-source Gradle run passed 536 JVM/Robolectric tests with zero failures, errors, or
skips across 82 JUnit XML suites, plus `lintDebug`, `lintRelease`, and debug, instrumentation-test,
and minified unsigned-release APK builds. The run is recorded in
`/tmp/collie-native-clipping-fix-gradle-retry.log`. The unsigned release APK SHA-256 is
`e97724ee57c763e669e91a2546387023f7fa3ae7a9de42dcd44dff665199da59`. This is not production
signing or release acceptance.

The current hardening source was exercised by all five `HardeningLifecycle*` suites: 8 tests passed,
including HTTP 503 `Retry-After: 0` exactly-once behavior, polling backoff and cancellation, stale-read
recovery, cross-origin ETag isolation, and one-shot body delegation. All 82 JUnit XML suites show 536
tests, zero failures/errors/skips. The final full run above passed `lintDebug`, `lintRelease`, and
debug, instrumentation, and minified unsigned release APK builds.

Earlier lab runs completed clean full-source builds and five baseline instrumentation tests on
the API 26 and API 36 phone profiles; those historical run records remain at
`$HOME/collie-android-lab/run-26-phone-dui0R0` and
`$HOME/collie-android-lab/run-36-phone-g5nbSo`. For the final current candidate, the debug and
test APK pair was built by the final workstation Gradle run above, transferred to lab-host, and verified
against the 350 Android source/config inputs in its archive. The tested APK SHA-256 values are
`6c8918c234a6924dadddd1c28640fd033b1cb6b094a92978b99e94e53186d04c` and
`ceb6944b493d32ece7baf1194fc51df285dbe32f5eb7aeb1a18eeb89b68bdc38`. The API 36 phone run
`$HOME/collie-android-lab/hardening-1.5.1-debug-36-phone-pIokH3` passed 69 cases (five
baseline, 51 interaction, four 200%-font/320-dp UI, eight focused layout, one connected TalkBack
platform test; 2026-10-07 23:19:49–23:23:04 UTC). It covered background polling stop/resume,
synthetic fixture network loss/recovery, and harmless fixture writes exactly once. The final
button-height clipping regression fails on the earlier APK and passes on this build. The TalkBack
test verified the platform gear label, accessibility focus and activation into Settings; spoken
output and full navigation were not assessed.

The API 26 phone run `$HOME/collie-android-lab/hardening-1.5.1-debug-26-phone-0K8vsN` passed
nine cases (five baseline and four UI; 2026-10-07 23:25:21–23:25:50 UTC). The API 36 foldable run
`$HOME/collie-android-lab/hardening-1.5.1-debug-36-foldable-WkuvUJ` passed eight cases (five
baseline and three UI; 2026-10-07 23:25:53–23:26:58 UTC) and completed the 2208×1840 → 1080×2092 →
2208×1840 fold-state cycle. Foreground folded/unfolded captures were visually reviewed at
`/tmp/collie-verified-folded-200.png` and `/tmp/collie-verified-unfolded-200.png`. These emulator
results do not establish physical-device acceptance, battery use, real radio/VPN transitions, or a
live paired-server restart.

## Notification contract and dependency hardening

The follow-up pass reconciled historical plans and acceptance records, prepared the
[native notification contract](android/NATIVE_PUSH_CONTRACT.md), and added release dependency tooling.
Provider selection and all live notification integration remain deferred.

The Android release workflow packages a CycloneDX 1.6 SBOM with 80 locked release runtime modules
into signed release artifacts. It records build inputs and the APK hash; R8 contents, dependency
edges and bundled assets are outside its scope. The workflow requires a current Grype database and
blocks signing on high or critical findings, retaining dependency evidence even when a scan fails.

| Follow-up check | Result |
| --- | --- |
| Notification contract and SBOM Bun tests | 53 passed, including 48 shared notification fixtures |
| Native decoder tests | 3 JUnit tests passed using the cached Kotlin 2.0.21 compiler directly |
| Complete script suite | 72 tests passed |
| Typechecks, lint, native configuration, workflow, shell and documentation checks | Passed |
| Disposable audit-key signing with SBOM (current preparation candidate) | Signature, certificate, checksums, SBOM, notices and metadata checks passed under `/tmp/collie-verified-signing-3u5qaqn8/artifacts`; disposable key was deleted. This is not production signing or public release acceptance. |
| SBOM reader interoperability | Syft parsed all 80 Maven package URLs plus the application component |
| Current final APK SBOM | CycloneDX 1.6 links the unsigned release APK SHA-256 above and inventories 80 locked release-runtime modules in `/tmp/collie-native-verified-sbom.cdx.json` |
| Fresh Grype dependency scan | Grype 0.116.1 reported zero matches using database v6.1.10 (built 2026-10-07T06:31:48Z); report at `/tmp/collie-native-verified-grype.json` |
| Prior notice-only rebuild (intermediate candidate) | Debug/release lint and APK builds passed; MIT/font/art notices were byte-identical in minified resource `iG.txt`. The current disposable-key artifact's packaged notices were checked separately above. |
| Current local Gradle tests, lint and APK builds | Passed; see current Android source evidence above |
| API 26/API 36 phone and API 36 foldable emulators | Selected suites passed: 9 API 26 phone, 69 API 36 phone, and 8 API 36 foldable cases above. These are emulator results; physical-device acceptance and battery evidence remain open. |

The disposable-key signing helper check is not production signing and does not establish a
production-signed candidate. The current SBOM and Grype evidence apply to the built candidate
identified by the retained artifacts. Production signing and publication remain separate steps;
physical-device measurements are additional validation rather than first-release prerequisites.

## First public APK checklist

Complete the publishing steps and a short smoke check of the signed artifact.

The [broader hardening record](android/validation/hardening/README.md) preserves 536 passing native
tests and 86 passing emulator checks. Missing exhaustive physical-device or accessibility evidence
does not by itself block the initial release. Release notes should describe the tested scope and
known limitations accurately.

- [ ] Choose and back up the release signing key outside Git; verify its certificate fingerprint.
- [ ] Choose the Android version, increment versionCode as needed, and prepare a clean release commit.
- [ ] Build and sign that commit; pass the existing native and dependency-scan workflow checks.
- [ ] Smoke-check installation, launch and basic connection behavior on the signed APK; verify its
  version, signer and checksums. An emulator is suitable for this initial check.
- [ ] Retain required upstream/font/artwork license notices. Record a maintainer disposition for the
  specific unresolved icon provenance findings; do not claim that unverified vendor permissions
  have been established. A generic replacement is an available option.
- [ ] Use reviewed public screenshots and release assets. Assess privacy findings if making retained
  repository evidence public; personal host/path metadata is not automatically a credential leak.
- [ ] Enable the chosen GitHub private vulnerability reporting route to match the security policy.
- [ ] Configure the `android-release` environment and secrets if using Actions; local signing is
  also supported. Review the draft assets and notes, then publish when ready.

The artwork findings need a concrete disposition for the affected assets, not a blanket requirement
for vendor approval of every mark. No usable credential was identified by the recorded scans.
Historical screenshots and deployment details remain identified for review when sharing those files.

## Follow-up validation

Track these improvements after the initial release without presenting them as mandatory gates.

- Physical-device battery/CPU/network/wakeup measurements.
- Spoken TalkBack output, complete focus traversal and dialog/sheet focus restoration.
- Real radio/VPN handoff, live-server restart and expanded interrupted-response observations.
- A wider visual sweep across themes, split window, IMEs and maximum font/display settings.
- Additional real-device, clean-pairing, upgrade and integration coverage as hardware becomes available.

A concrete finding such as leaked credentials, repeated terminal writes or a broken install would
need a fix before shipping. A test that has not been performed is recorded as a coverage limit;
it does not automatically mean the release is blocked.

No production key was generated, no physical device was reset, and no commit, tag, push or release
publication was performed during this preparation. GitHub settings were inspected read-only;
private vulnerability reporting needs an external setting change. Play Store and F-Droid distribution
are not configured.

## Maintainer references

Use the runbook to complete the external configuration and release steps.

- [Android release runbook](android/RELEASING.md)
- [Privacy policy](PRIVACY.md)
- [Security reporting](SECURITY.md)
- [Android developer and historical acceptance guide](android/README.md)
