# Kotlin compatibility finding — resolved 2026-10-10

Historical investigation of the pre-alignment Kotlin 2.0.21/stdlib 2.1.0 configuration: **reviewed with no mismatch found in that configuration**. The previous unresolved disposition was premature. This investigation changed no build pins or locks. The subsequent requested implementation in `/home/chris/git/collie/android/acceptance/audit-kotlin-alignment.md` supersedes all current-state statements below: compiler/plugins/application Kotlin now use 2.2.21 and AGP uses 8.10.1.

## What the version difference means

`/home/chris/git/collie/android/build.gradle.kts` pins the Android and serialization compiler plugins to 2.0.21. `/home/chris/git/collie/android/app/gradle.lockfile` resolves the application standard library to 2.1.0. Compiler plugin versions need to match the compiler; the application's runtime standard library does not have to be identical to that version. Kotlin explicitly allows a different declared standard-library version in its [Gradle configuration documentation](https://kotlinlang.org/docs/gradle-configure-project.html#dependency-on-the-standard-library).

The earlier dependency review used `--single-path` and incorrectly concluded that only the lock requested 2.1.0. A complete offline dependency insight shows that `kotlinx-coroutines-android:1.10.2` and `kotlinx-coroutines-core-jvm:1.10.2` request it. The [coroutines 1.10.2 build properties](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/gradle.properties#L4) also identify Kotlin 2.1.0. Downgrading the standard library solely to match the compiler would contradict the resolved dependency requirements.

## Versioned compiler contract and actual artifacts

The [Kotlin 2.0.21 metadata reader](https://github.com/JetBrains/kotlin/blob/v2.0.21/core/metadata.jvm/src/org/jetbrains/kotlin/metadata/jvm/deserialization/JvmMetadataVersion.kt#L17) accepts ordinary JVM metadata up to the next language version: its current version is 2.0.0 and its next version is 2.1.0. Strict metadata uses the current-version limit. The [general evolution policy](https://kotlinlang.org/docs/kotlin-evolution-principles.html#evolving-the-binary-format) qualifies forward compatibility; it is not a guarantee about every imaginable future library feature. The audit therefore checks the concrete pinned artifacts and current application rather than asking for an unlimited guarantee.

A temporary Gradle init task resolved the actual `releaseCompileClasspath` and `releaseRuntimeClasspath` without changing locks. Their union contains 67 JAR/AAR artifact files. An ASM probe visits all class files, including nested AAR JARs, and invokes `JvmMetadataVersion.isCompatible` from the **actual cached compiler 2.0.21 JAR**, using language version 2.0 and each class's actual metadata version/strict flag.

- 6,970 classes inspected; 3,304 carry Kotlin metadata.
- 1,730 metadata-bearing classes use version 2.1.0; all use ordinary semantics.
- Zero incompatible metadata versions; zero prerelease metadata flags.
- Probe controls confirm ordinary 2.1 is accepted, ordinary 2.2 is rejected and strict 2.1 is rejected.
- No metadata-version or prerelease-check bypass is configured in the application's build scripts/properties.

The existing final audit gates already compile, minify and exercise this same pinned combination: 607 JVM tests and 61 final emulator checks passed. An additional forced, offline compilation of both debug and release Kotlin confirms current source compiles against the resolved classpaths. These checks establish compatibility of the present application within its tested scope, not exhaustive behavioral verification of every library API.

## Evidence and corrected conclusion

Retained evidence is under `/home/chris/git/collie/.project-docs/audit-evidence/2026-10-10-kotlin-compatibility/`: complete dependency insight, resolved classpath paths and artifact hashes, the temporary init script and probe source, compiler/stdlib hashes, probe output and forced-compilation log. `/home/chris/git/collie/android/acceptance/audit-validation.json` records execution results and evidence identities.

The source and collection ledgers now close this finding. It does not require a compiler upgrade or a standard-library downgrade. If a later dependency introduces metadata beyond the supported limit, strict incompatible metadata or source features the compiler cannot handle, investigate that concrete change and upgrade the toolchain coherently then.
