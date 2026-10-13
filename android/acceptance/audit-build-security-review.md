# Android build and security configuration review

Read-only unit. I did not edit production/config files, run builds, inspect credentials, or access external services. This report is a source/config review; parent owns build and artifact validation.

## Summary

- **AGP/API compatibility:** the current official Android compatibility table lists AGP 8.9.1 as the minimum for API 36. The project pins AGP 8.9.1 and compiles/targets API 36, so this pair is supported. The archived 8.9.0 base release note's API 35 ceiling does not apply to the later 8.9.1 patch.
- **Unresolved compatibility question:** Kotlin Android/serialization plugins are 2.0.21; locked app compile/runtime classpaths resolve kotlin-stdlib 2.1.0 while compiler classpath remains 2.0.21. Serialization 1.7.3 documentation expects matching plugin versions, which are aligned at 2.0.21. Coroutines 1.10.2 README samples Kotlin 2.0.0, while its changelog records an update to 2.1.0. I found no documented incompatibility or demonstrated failure, so this is not a confirmed defect.
- **Optional supply-chain hardening gap:** dependency versions are locked but no Gradle verification-metadata.xml exists, so artifact bytes are not verified. Gradle documents this as an available supply-chain defense, not a mandatory requirement. No compromised artifact is evidenced.
- No manifest, backup, FileProvider or release-signing defect found in source. App components are non-exported except launcher; cleartext and backup are disabled; FileProvider grants read access only to diagnostics cache output. Release signing follows a separate job and validates source, lock and APK identity plus certificate signature.

## Applicable documentation

- Android official docs manifest/index: .project-docs/android-official/INDEX.md, fetched 2026-10-10T08:01:42Z; AGP note fetched 08:08:51Z. HTML snapshots are unpinned. Also checked the current official AGP compatibility table at https://developer.android.com/build/releases/about-agp?hl=en (fetched 2026-10-10; page updated 2026-10-08): its minimum-versions table, “API level 36,” gives AGP 8.9.1. This patch-specific entry resolves the apparent conflict with the archived AGP 8.9.0 base release note at https://developer.android.com/build/releases/agp-8-9-0-release-notes, whose generic API-35 maximum is for the base release. Reviewed Android build compileSdk/targetSdk (https://developer.android.com/studio/build), Android 16 behavior changes, manifest/application component docs, network security configuration (https://developer.android.com/privacy-and-security/security-config), backup best practices (https://developer.android.com/privacy-and-security/risks/backup-best-practices), app signing (https://developer.android.com/studio/publish/app-signing), and versioning (https://developer.android.com/studio/publish/versioning). Requirements: supported compile API; distinguish compile/target/min API; protect app-private data; limit exported components and shared-file URI scope; preserve signing identity and verify release signatures.
- Gradle Published docs: .project-docs/gradle-published/INDEX.md, exact 8.11.1 docs. Reviewed userguide/gradle_wrapper.html.md (wrapper checksum and jar verification), dependency_locking.html.md, dependency_verification.html.md (checksums/signatures and opt-in), toolchains.html.md, and build_environment.html.md. Upstream URLs use https://docs.gradle.org/8.11.1/userguide/. Gradle locks choose versions; verification metadata authenticates downloaded bytes.
- Android build reference .project-docs/android-build/INDEX.md and AGENTS.md, source revision a49ed253d75e61a2b6ab80a8da677b57437b08eb: Now in Android is Compose/Hilt/multimodule and not Collie’s Views app or authoritative Gradle specification. Partially applicable for generic build/test workflow only; architecture-specific directions excluded.
- Kotlin docs .project-docs/kotlin/INDEX.md, revision 38512ffcb9be58019fdb5d9e6b3c821061445028: broad Kotlin site, used only for stdlib/compiler pairing context, not an exact KGP 2.0.21 compatibility matrix.
- kotlinx.coroutines .project-docs/kotlin-coroutines/INDEX.md, revision 5f8900478a8e20c073145b1608fbc71fe3d7378b, version 1.10.2: reviewed pinned README and CHANGES. README badge/sample says Kotlin 2.0.0; CHANGES says Kotlin updated to 2.1.0. Does not say consumers must use Kotlin 2.1.x.
- kotlinx.serialization .project-docs/kotlin-serialization/INDEX.md, revision d4d066d72a9f92f06c640be5a36a22f75d0d7659, version v1.7.3: reviewed README Gradle/version pairing. Compiler plugin and runtime pins are 2.0.21 and 1.7.3, aligned.
- AndroidX Test .project-docs/android-test/INDEX.md, revision 675b8197a3f970b1b3c0ed52baa0a304ee7ae873: applicable only to configured test runner/libs; no mismatch derived. Material Android .project-docs/material-android/INDEX.md, revision 88acfcdb63281714d64e863d2e7da3b53b8da9ce: app pins Material 1.12.0; source snapshot is not exact 1.12 API docs, no security/build requirement depended on it.
- .project-docs/gradle/INDEX.md is Gradle source tree revision 481cb05a490e0ef9f8620f7873b83bd8a72e7c39, duplicate/non-versioned for the pinned wrapper; exact Gradle 8.11.1 docs take precedence. Robolectric source/guides are test framework references, not production build/security contracts. OkHttp and Okio source snapshots are not exact 4.12.0 APIs; transport behavior is owned by the HTTP/security review. AndroidX transitive releases map locked libraries but are not build-tool specs. Other web/agent/shell/mux/platform collections do not define these Gradle/manifest files.

## Findings

### B1 — AGP 8.9.1 / API 36 is supported; earlier mismatch claim withdrawn

android/build.gradle.kts:2 pins AGP 8.9.1. android/app/build.gradle.kts:9,14 set compileSdk/targetSdk 36. .github/workflows/ci.yml:148–149 and .github/workflows/android-release.yml:30–31 install SDK 36 / Build Tools 36.0.0. The archived source .project-docs/android-official/developer.android.com/studio/releases/past-releases/agp-8-9-0-release-notes-b66fd978.md:52–57 is specifically the 8.9.0 base release note and says API 35 is its maximum. The current official compatibility table's API 36 row explicitly gives minimum AGP 8.9.1, so that base-version ceiling does not establish a violation by 8.9.1. The current AGP table also gives Gradle 8.11.1 as the minimum for AGP 8.9, matching this wrapper.

Disposition: **No defect.** The pair meets the current official API 36 minimum. Withdraw the prior upgrade recommendation; no toolchain or checker change is indicated by this evidence. This conclusion is based on the current compatibility table, not merely a successful build.

### B2 — runtime stdlib is newer than compiler/plugin

android/build.gradle.kts:3–4 pins Kotlin plugins 2.0.21. android/app/build.gradle.kts:85–86 pins coroutines 1.10.2 and serialization JSON 1.7.3. android/app/gradle.lockfile:212,224–225 resolves app compile/runtime stdlib 2.1.0 but plugin/compiler stdlib 2.0.21. Serialization README’s version pairing is satisfied; coroutines’ pinned release materials reflect both Kotlin 2.0.0 and 2.1.0.

Disposition: **Unresolved compatibility question, not confirmed mismatch.** Confirm exact published metadata/compiler compatibility or align versions only if required. Different version numbers alone do not prove failure. Parent build output supplies compilation evidence but not runtime compatibility.

### B3 — no Gradle dependency byte verification

android/app/build.gradle.kts:113–115 enables locking; android/app/gradle.lockfile records selected versions. No tracked android/gradle/verification-metadata.xml exists. Gradle 8.11.1 dependency verification docs §§“The verification metadata file,” “Scope,” and “Bootstrapping” describe checksum/signature verification and state it is not enabled by default.

Disposition: **Optional supply-chain hardening gap**, not a contract violation; no compromised artifact found. Consider reviewed SHA-256/signature metadata. SBOM/Grype detects inventory and known vulnerabilities, not byte substitution.

Other source checks:
- Wrapper is Gradle 8.11.1 (android/gradle/wrapper/gradle-wrapper.properties:5–8), with pinned distribution SHA-256 at :6, HTTPS, URL validation, and 10s timeout. This matches AGP 8.9’s Gradle minimum/default. Wrapper jar is tracked; local SHA-256 observed as 3dc39ad650d40f6c029bd8ff605c6d95865d657dbfdeacdb079db0ddfffedf9f. Gradle docs describe verifying jar separately; no independent jar checksum is tracked. Optional hardening only.
- android/settings.gradle.kts:1–18 uses Google/Maven Central/plugin portal, FAIL_ON_PROJECT_REPOS, and only :app. No ad hoc project repo or dynamic version.
- android/app/build.gradle.kts:11–20 has stable app ID, minSdk 26, target 36, empty DEFAULT_ORIGIN, test runner. Java/Kotlin bytecode target 17 and JDK toolchain 21 (:48–55,69–71) match CI Java 21; AGP’s min/default JDK is 17. Release minification/resource shrink and lint abort are enabled (:23–36,62–66). Profileinstaller exclusion (:73–75) does not alter TLS/trust.
- scripts/check-android-native.ts:8–13 and 106–126 pins AGP 8.9.1 and enforces compile/target 36; its tests pin the same combination. The combination is consistent with the current official compatibility table. The same checker audits prohibited browser/TWA and trust-all patterns (lines 16–19, 183 onward); this review did not rerun it.
- android/gradle.properties:9,14–16 contains daemon memory and AndroidX settings only. Lockfile includes build/test and runtime config resolutions; that is not itself a lockfile anomaly.
- android/app/proguard-rules.pro:1–12 conditionally retains serializer annotation/companion methods. Release CI assembles a minified APK but this unit did not test its runtime.
- android/app/src/main/AndroidManifest.xml:3–15 declares INTERNET and RECORD_AUDIO, disables backup, enables RTL and disallows cleartext. Lines 16–53 export only launcher; other activities are non-exported and explicit. Lines 55–63 declare app-ID-scoped FileProvider, exported=false, URI grants and dedicated paths.
- data_extraction_rules.xml:2–16 excludes root/files/database/sharedpref/external for cloud backup and device transfer. This is redundant with allowBackup=false/fullBackupContent=false but consistent. diagnostics_file_paths.xml:2–4 exposes only cache/diagnostics-export/. DiagnosticsExport.kt:42 uses this exact directory; SettingsActivity.kt:237–245 grants read URI permission on share. No broad cache root configured.
- .github/workflows/ci.yml:133–166 pins setup actions and runs checks, tests, lint, debug assemble and merged-manifest check. android-release.yml:7–12,15–75 uses read-only build permissions, pinned actions/tool versions, tests/lint/release assemble, SBOM and high-severity scan with no blanket ignore. Signing job :77–121 uses protected environment secrets, temp keystore with umask 077 and cleanup, unsets encoded secret, then calls helper. Draft release only :122–152. scripts/android-release.sh:12–55 validates secrets exist, clean tree/source/lock/APK hash, identity/version/tag, non-debuggable status, certificate fingerprint and apksigner output; :56–80 emits metadata/checksums. scripts/android-build-manifest.sh:1–16 refuses dirty tree and records commit/lock/APK digests. No credential values read.
- android/grype.yaml:1–7 requires fresh DB <=120h and empty ignores. android/dependency-licenses.json maps license/source notices; CI validates notices and bundled license. Those controls do not verify dependency artifact bytes.
- android/.gitignore:1–10 and root .gitignore:22–25,75–83 exclude local SDK, build output, keystore, generated .local and docs cache. android/local.properties is ignored machine-local SDK config, not checked-in release input. Generated android/.local/generated/, node_modules, caches and build reports excluded.

## File disposition ledger

| Exact path(s) | Disposition |
|---|---|
| /home/chris/git/collie/android/settings.gradle.kts | Reviewed; bounded repositories and project inclusion, no mismatch. |
| /home/chris/git/collie/android/build.gradle.kts | Reviewed; AGP 8.9.1 supports API 36 per current official table; B2 is unrelated. |
| /home/chris/git/collie/android/app/build.gradle.kts | Reviewed; API/AGP combination supported; B2 and other SDK/compiler/test/lint/locking config reviewed. |
| /home/chris/git/collie/android/gradle.properties | Reviewed; no secret or unsafe override. |
| /home/chris/git/collie/android/gradle/wrapper/gradle-wrapper.properties | Reviewed; wrapper pin and checksum align with Gradle 8.11.1 docs. |
| /home/chris/git/collie/android/gradle/wrapper/gradle-wrapper.jar; /home/chris/git/collie/android/gradlew; /home/chris/git/collie/android/gradlew.bat | Reviewed as wrapper integrity surface. Jar checksum independent of repo not present; optional hardening. |
| /home/chris/git/collie/android/app/gradle.lockfile | Reviewed all configurations; versions locked; B2/B3 apply. |
| /home/chris/git/collie/android/app/proguard-rules.pro | Reviewed; focused serialization retention; minified runtime is not proven here. |
| /home/chris/git/collie/android/app/src/main/AndroidManifest.xml | Reviewed exported surfaces, permissions, cleartext, backup and provider; no mismatch. |
| /home/chris/git/collie/android/app/src/main/res/xml/data_extraction_rules.xml | Reviewed all cloud/device-transfer domains; app data excluded. |
| /home/chris/git/collie/android/app/src/main/res/xml/diagnostics_file_paths.xml | Reviewed narrow cache path against exporter/share code. |
| /home/chris/git/collie/android/grype.yaml | Reviewed fresh DB and no ignore suppressions. |
| /home/chris/git/collie/android/dependency-licenses.json | Reviewed license/source coverage and notice workflow; no finding. |
| /home/chris/git/collie/android/.gitignore; /home/chris/git/collie/.gitignore | Reviewed Android local/signing/build exclusions; no finding. |
| /home/chris/git/collie/.github/workflows/ci.yml | Android job reviewed at lines 133–166. |
| /home/chris/git/collie/.github/workflows/android-release.yml | Full workflow reviewed at lines 1–152; release signing boundary above. |
| /home/chris/git/collie/scripts/android-build-manifest.sh | Full script reviewed; clean-tree and source/lock/APK identity. |
| /home/chris/git/collie/scripts/android-release.sh | Full script reviewed; credentials, staging cleanup, signature/fingerprint and metadata. |
| /home/chris/git/collie/scripts/android-sbom.ts | Full 79-line script reviewed; selects only locked releaseRuntimeClasspath modules, hashes APK/lockfile and marks missing dependency edges/assets/license data as incomplete. No mismatch found. |
| /home/chris/git/collie/scripts/android-dependency-notices.ts | Full 49-line script reviewed; maps every runtime module to the longest matching license prefix and fails when unmapped; --check does not regenerate. No mismatch found. |
| /home/chris/git/collie/scripts/check-android-native.ts; /home/chris/git/collie/scripts/check-android-native.test.ts | Checker and fixture references reviewed for toolchain, manifest and forbidden patterns. Accepted AGP/API constants match the official API-36 minimum. |
| /home/chris/git/collie/android/RELEASING.md | Release process reviewed as validation contract, including signed artifact smoke test; not executable config. |

## Validation and limits

No Gradle/check task was run here. Parent owns final gate. Source review cannot establish whether minified release runtime works or whether remote environment protection/secrets are configured as intended. No credentials, live server or operator session was accessed. No fix was made because this assignment is read-only.
