# Kotlin toolchain alignment — 2026-10-10

This follow-up implements the requested version alignment. It supersedes the current-state conclusion in `/home/chris/git/collie/android/acceptance/audit-kotlin-compatibility-resolution.md`, which documents the earlier 2.0.21/2.1.0 investigation as historical evidence.

## Implemented configuration

| Component | Before | Current |
|---|---|---|
| Kotlin Android/compiler plugin | 2.0.21 | 2.2.21 |
| Kotlin serialization compiler plugin | 2.0.21 | 2.2.21 |
| Application stdlib/common | 2.1.0 | 2.2.21 |
| Application stdlib JDK wrappers | 1.9.10 | 2.2.21 |
| Kotlin BOM | Transitive 1.8.22 | Explicit 2.2.21 platform |
| Android Gradle Plugin | 8.9.1 | 8.10.1 |
| Bundled R8 | 8.9.32 | 8.10.24 |
| Gradle wrapper | 8.11.1 | 8.11.1 |
| Java toolchain / bytecode | 21 / 17 | 21 / 17 |

Kotlin 2.1.0 would align the original compiler/runtime versions but leave the existing Gradle wrapper outside that plugin family's fully supported range. The [Kotlin Gradle compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin) lists KGP 2.2.20–2.2.21 with Gradle 7.6.3–8.14 and AGP 7.3.1–8.11.1, covering the final pins. Android documents [R8 8.10.21 for Kotlin 2.2](https://developer.android.com/build/kotlin-support). Inspecting the actual cached AGP builder JARs establishes the R8 versions above; this is not inferred from a successful build. [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes#compatibility) supports API 36 and requires the existing Gradle 8.11.1 wrapper and JDK 17 or later. Official sources were checked 2026-10-10. Independent read-only re-review confirmed the final pins fall within these ranges and found no concrete defect in the configuration/lock/checker changes.

The original application source can compile with a newer compatible standard library. This alignment additionally removes the version difference and uses a supported compiler/shrinker combination; it is not a claim that the old metadata difference alone caused a demonstrated runtime failure.

## Exact changes and lock scope

- `/home/chris/git/collie/android/build.gradle.kts`: pin both Kotlin plugins to 2.2.21 and AGP to 8.10.1.
- `/home/chris/git/collie/android/app/build.gradle.kts`: declare the Kotlin 2.2.21 BOM platform to align application Kotlin libraries. Existing native configuration and prior user edits are preserved.
- `/home/chris/git/collie/android/app/gradle.lockfile`: targeted Gradle lock refresh for Kotlin and AGP tooling. The lock remains committed input to all configurations, rather than permitting fresh unreviewed resolution on every build.
- `/home/chris/git/collie/scripts/check-android-native.ts`: update both plugin pins and require the matching application BOM declaration.
- `/home/chris/git/collie/scripts/check-android-native.test.ts`: update fixtures and add a regression rejecting an old runtime BOM with the new compiler pin.
- `/home/chris/git/collie/android/app/src/main/res/raw/dependency_notices.txt`: regenerate exact dependency versions from the refreshed lock.
- `/home/chris/git/collie/android/README.md`: update toolchain pins.
- `/home/chris/git/collie/ANDROID_CHANGELOG.md`: record the functional build change under Unreleased. Application/server release versions are unchanged.

The final lock contains 307 module/version records and 80 release runtime modules. Application compile/runtime/test configurations change only Kotlin libraries and AGP-generated View Binding, whose runtime moves with AGP to 8.10.1. Unrelated application dependency versions, including coroutines 1.10.2 and serialization runtime 1.7.3, are unchanged. Build-only changes include compiler support libraries, AGP tools/protocol artifacts and Kotlin ABI tooling; exact additions/removals/configurations are retained in the lock delta. Older Kotlin libraries private to Android build tooling are not application runtime dependencies and were not globally forced to 2.2.21.

## Validation

Final execution results and APK identities are recorded in `/home/chris/git/collie/android/acceptance/audit-validation.json` under `kotlin_alignment`. The complete native gate passed: 607 JVM tests in 90 suites, debug/release lint with zero errors and 179 warnings each, and debug, instrumentation and unsigned release APK builds. The final APKs passed all 51 interaction tests plus 10 baseline emulator checks at 200% font scale. All 25 targeted tooling tests passed, as did the complete root suite (5,406 bridge, 1,956 CLI and 294 script passes), TypeScript checks, oxlint, policy checks, generated notices and isolated CLI/PWA builds. The refreshed 80-module locked-runtime SBOM produced zero Grype matches with a valid current database; all five license/notice texts match the minified APK byte-for-byte. Two intermediate builds were cancelled before completion while review corrected the shrinker requirement and the precise Kotlin/AGP patch-version range; neither is final validation evidence.

Evidence is retained under `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10-kotlin-alignment/`, including pre-change input files, the exact lock delta, logs, current input hashes, APKs and scan results. No original server/PWA assets are rebuilt, and no real operator session is written to.

The first current-toolchain interaction attempt passed 50/51: the typed field contained an extra initial character. The same case and the complete unchanged 51-case suite passed on rerun. Both results are retained; the cause is unproven and test input/timing variability remains a limitation. Incorrect initial baseline class arguments were corrected before the passing 10-test run. Physical/live agent, audible TalkBack and real provider delivery limits remain as recorded in the comprehensive report.
