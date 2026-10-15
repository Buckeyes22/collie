# Current Android toolchain

Collie for Android supports Android 16 / API 36 and newer. Android 16 support was added at the user's request on October 15, 2026; the current compile SDK, target SDK and toolchain remain pinned.

## Stable pins

The candidate build uses the latest stable versions eligible under the repository's seven-day dependency rule, checked on October 12, 2026.

| Component | Pin | Primary release evidence |
| --- | --- | --- |
| Compile SDK | 37.2 | [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk); official stable repository platform 37.2 |
| Minimum / target API | 36 / 37 | Android 16 and newer; compile SDK remains 37.2 |
| SDK Build Tools | 37.0.0 | Official Android SDK repository stable channel |
| Android Gradle Plugin | 9.4.1 | [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes); Google artifact published September 18 |
| Gradle wrapper | 9.8.0 | [Gradle release](https://github.com/gradle/gradle/releases/tag/v9.8.0), September 24; verified distribution and wrapper JAR checksums |
| Kotlin compiler / serialization plugin / BOM | 2.4.20 | [Kotlin release](https://github.com/JetBrains/kotlin/releases/tag/v2.4.20), September 7 |
| JDK toolchain / bytecode | 21 / 17 | AGP supports JDK 17+; Java/Kotlin bytecode stays aligned |
| AndroidX Core / AppCompat / Lifecycle | 1.19.1 / 1.8.0 / 2.11.0 | Official Google Maven stable artifacts |
| Material | 1.14.0 | Official Google Maven stable artifact |
| Coroutines / serialization JSON | 1.11.0 / 1.11.0 | Official Maven Central stable artifacts |
| OkHttp / TLS / MockWebServer 3 | 5.5.0 | Official Maven Central stable artifacts |
| Robolectric / JUnit | 4.17 / 4.13.2 | [Robolectric setup](https://robolectric.org/getting-started/); JUnit 4 is its documented runner contract |
| AndroidX Test Core / Runner / JUnit extension | 1.7.0 / 1.7.0 / 1.3.0 | Official Google Maven stable artifacts |
| RecyclerView / SwipeRefreshLayout / Espresso | 1.4.0 / 1.2.0 / 3.7.0 | Latest stable artifacts unchanged |
| jsoup / AndroidSVG | 1.23.2 / 1.4 | Latest stable artifacts unchanged; existing inert parser bounds remain |

Kotlin 2.4.21 and Gradle 9.8.1 are stable but were released October 8 and October 7 respectively; they are not seven days old on this check date. No preview version or deprecated opt-out is used.

## Build migration and validation

AGP owns Kotlin integration. The build removes `org.jetbrains.kotlin.android` and `android.kotlinOptions`, pins the built-in compiler with the documented KGP classpath override, and uses typed `kotlin.compilerOptions`. JVM default mode preserves the previous `-Xjvm-default=all` behavior. See [AGP migration](https://developer.android.com/build/migrate-to-built-in-kotlin) and [Kotlin compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html).

AGP 9.4 supports API 37 and requires Gradle 9.6 or newer. JetBrains' fully tested Kotlin 2.4.20 range ends at Gradle 9.7 / AGP 9.3.1; its [compatibility guidance](https://kotlinlang.org/docs/gradle-configure-project.html) permits current releases with possible warnings or unavailable new features. The selected combination requires fresh native gates; metadata does not prove it builds.

ROOT's current Gradle configuration check passed. A separate dependency-graph-only resolution passed for 37 resolvable configurations / 235 unique modules, with no unresolved graph results. This does not establish artifact compilation. All 170 changed modules meet the seven-day age rule using their primary repository POM publication timestamps. Debug/release application runtime and compiler configurations select Kotlin 2.4.20; internally pinned tool helpers are retained, including the compiler's older reflection helper.

Generated dependency notices describe the 81 resolved release-runtime modules. An artifact-bound SBOM still requires a real release APK. The subsequent MockWebServer 3 artifact change requires another coordinated lock update; lock enforcement remains enabled. Fresh lint, APK builds, complete native regressions and independent review remain pending.

All behavioral assertions, deadlines, two-gigabyte test heaps, class isolation and security checks are retained. The main Robolectric suite uses SDK 37; setup/settings, typography, licenses and inset regressions also run on SDK 36; its documented JDK access flags apply only to test workers. Older test SDK selectors change platform selection, without deleting test methods or assertions. Actual Android acceptance includes API 36 in a separate local emulator and the authorized Samsung SM-S938U1 on API 37 over wireless ADB after its OS update, alongside prior Pixel API 37 evidence through dell-7680; JVM results do not establish device behavior, font/TalkBack usability, lifecycle or signed-upgrade success.

## Remaining cleanup and evidence

The two obsolete SDK branches are removed: current historical-exit reporting is unconditional, and raster decoding uses the current ImageDecoder path. Pixel, MIME, allocation, TLS and original-actor guards remain enforced. MockWebServer consumers use the current immutable response/request APIs. Recorded request targets preserve encoded mounted paths and query strings; the multipart fixture that intentionally reads a body twice retains a local consuming buffer. Half-body disconnect tests use the current socket effect, preserving their failure/no-replay assertions.

CI uses Google's [documented signed APT repository](https://developer.android.com/tools/agents/android-cli/download), pinned to Android CLI 1.0.16500706 (October 2). SDK commands use slash package IDs, explicit SDK roots and metrics opt-out. CI accepts SDK prompts through stdin, checks the CLI's exit status and requires its saved Android SDK license. No local installation or CI run is claimed by this source change. Current native gates must identify the provenance of any remaining Gradle configuration deprecation before it is attributed to project or plugin code.

Old migrations and historical raw evidence remain preserved. Current security, bounded parsing, TLS, origin/mount ownership, encrypted custody and all 41 functional requirements remain required.
