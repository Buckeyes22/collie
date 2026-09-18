# Android Always-On Diagnostics Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an always-on, size-bounded, encrypted-at-rest diagnostic trace to Collie's native
Android app — covering network calls, pane-body decisions, lifecycle, named write actions, crashes
and ANRs — plus a manual "Send diagnostics" share-sheet export and a Settings on/off toggle, and a
one-line bridge-side access log correlated by trace id.

**Architecture:** A new `com.lateapex.collie.diagnostics` package holds a `DiagnosticsWriter`
(rotating, encrypted-on-seal NDJSON file store), a `DiagnosticsRecorder` (the single entry point
everything else calls, gated by a Settings toggle, backed by its own executor thread so a frozen
main thread never blocks it), an `AnrWatchdog`, and a `DiagnosticsInterceptor` (OkHttp). These wire
into the existing `AppContainer`/`CollieApplication`/`PaneViewModel`/`SettingsActivity` exactly the
way `repository` and `nativePreferences` already do. The bridge gets one pure, exported,
independently-testable function plus a non-invasive wrapper around the existing `fetch` handler —
the 635-line security-critical body inside it is not touched or moved.

**Tech Stack:** Kotlin, OkHttp 4.12 (already a dependency), kotlinx.serialization-json 1.7.3
(already a dependency), Android Keystore (`AndroidKeystoreCipher`, already exists), Robolectric
4.14.1 + JUnit4 (already the test stack) on the Android side; Bun's own test runner on the bridge
side. No new Gradle dependencies are required anywhere in this plan.

**Spec:** [`docs/superpowers/specs/2026-09-18-android-diagnostics-design.md`](../specs/2026-09-18-android-diagnostics-design.md)

## Global Constraints

- The pairing bearer credential is never written to the trace under any circumstance — only a
  `hadCredential: Boolean`. This is enforced by a test that must never be weakened (spec §Scope
  decisions, and CLAUDE.md → *Security posture*).
- Binary request/response bodies (image uploads, STT audio clips) are captured as content-type +
  byte size only, never as raw or base64 bytes. Text bodies are captured in full.
- Retention is a rolling ~20MB across the active file plus 4 sealed files, oldest dropped first —
  no time-based expiry.
- Nothing is exported automatically. Export is a manual, confirmed, in-app share-sheet action only.
- `NativePreferences.diagnosticsEnabled` (default `true`) gates whether captured events are
  written; the interceptor, watchdog, and exception handler stay installed either way and just
  produce no output when the flag is off.
- Every Android task in this plan touches only `android/**`; the bridge task (Task 12) touches only
  `bridge/server.ts` and `bridge/server.test.ts`. No task needs a version bump — `android/app/build.gradle.kts` stays at `versionCode = 2`, `versionName = "1.5.1"` throughout (spec §Versioning).
- `scripts/git-hooks/pre-commit`'s functional-change gate (`scripts/git-hooks/pre-commit:35`) only
  matches paths under `bridge/`, `cli/`, `web/`, `scripts/`, `systemd/`, `package.json`, and
  `herdr-plugin.toml` — it does **not** match `android/**`. Tasks 1–9 (internal Android plumbing,
  not yet user-visible) therefore need no CHANGELOG line. Tasks 10 and 11 (user-visible Android
  Settings behavior) and Task 12 (`bridge/server.ts`, which the hook **does** gate) each add one
  `CHANGELOG.md` line in their own commit, following this repo's established practice of
  changelogging user-visible Android behavior even though the hook itself doesn't force it.

---

## File ownership (for parallel-lane execution)

Tasks 1–4 have no file overlap and no dependency on each other — they may run as four parallel
lanes. Task 5 depends on 1+2+3 (not 4). Task 6 depends on 5. Task 7 depends on 2+4. Task 8 depends
only on 2 (touches `PaneActivity.kt`/`PaneActivityTest.kt`, plus it introduces the shared
`RecordingDiagnosticsRecorder` test class). Task 9 depends on 2 **and on Task 8** specifically for
that shared `RecordingDiagnosticsRecorder` file — run Task 8 before Task 9 even though their
production files don't overlap. Task 10 depends on 3; Task 11 depends on 1+10. Task 12 has no
dependency on anything else in this plan and may run in parallel with all of it.

**Scope note on "named user actions" (spec's What-gets-captured table):** the spec's own examples
("tapped Send," "opened Settings," "switched pane") span two different kinds of signal. Screen-level
navigation ("opened Settings," "switched pane") is already fully covered by Task 7's global
`ActivityLifecycleCallbacks` hook — every Activity's create/resume/pause is recorded regardless of
which screen it is, so no separate per-screen "opened X" instrumentation is added. Task 9 covers the
other kind: a write *intent* the user initiated, at the handful of stable `PaneViewModel` entry
points, which matters most because it lets a "silent drop" bug show as "action recorded, but no
matching network entry followed" — a gap that per-button tap tracking across the four-thousand-line
`PaneActivity.kt` would not diagnose any better. Literal button-by-button tap tracking beyond these
two mechanisms is intentionally not built; the reasoning above is why this is a complete, decided
implementation of the spec's row, not a partial one deferred for later.

**Scope note on "polling" (spec's What-gets-captured table):** a poll cycle's start, timing, and
outcome are already fully captured by Task 6's network interceptor, since every poll is itself one
of the HTTP calls that interceptor sees — no separate hook into `PaneViewModel`'s polling loop is
added. The interceptor does not know *why* a given call fired (a poll vs. a manual refresh), but
neither symptom class this feature targets (freezes, stuck state, silent drops) depends on that
distinction — timing and outcome are what matters, and both are already present on every captured
network line.

| Task | Owns (exclusive) |
| --- | --- |
| 1 | `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsWriter.kt`, `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsWriterTest.kt` |
| 2 | `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsRecorder.kt`, `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsRecorderTest.kt` |
| 3 | `android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt`, `android/app/src/test/java/com/lateapex/collie/ui/NativePreferencesTest.kt` |
| 4 | `android/app/src/main/java/com/lateapex/collie/diagnostics/AnrWatchdog.kt`, `android/app/src/test/java/com/lateapex/collie/diagnostics/AnrWatchdogTest.kt` |
| 5 | `android/app/src/main/java/com/lateapex/collie/AppContainer.kt` |
| 6 | `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptor.kt`, `android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt`, `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptorTest.kt` |
| 7 | `android/app/src/main/java/com/lateapex/collie/CollieApplication.kt`, `android/app/src/test/java/com/lateapex/collie/CollieApplicationTest.kt` |
| 8 | `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt`, `android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt` |
| 9 | `android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt`, `android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt` |
| 10 | `android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt`, `android/app/src/main/res/values/settings_ids.xml`, `android/app/src/main/res/values/settings_strings.xml`, `android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt`, `CHANGELOG.md` |
| 11 | `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsExport.kt`, `android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/diagnostics_file_paths.xml`, `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsExportTest.kt`, `android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt`, `CHANGELOG.md` |
| 12 | `bridge/server.ts`, `bridge/server.test.ts`, `CHANGELOG.md` |

Tasks 10 and 11 both touch `SettingsActivityTest.kt` and `CHANGELOG.md` — run them sequentially
(10 before 11), not as parallel lanes, to avoid a merge conflict on those two files.

**Correction to the table above: `AppContainer.kt` is not exclusive to Task 5.** Task 6's own body
(`defaultHttpClient(diagnostics)` wiring) commits `AppContainer.kt` together with its own files,
Task 8's body adds the `@VisibleForTesting internal set` seam to `AppContainer.diagnostics`, and
Task 11's body drops `private` from `AppContainer.diagnosticsWriter`. **Tasks 5, 6, 8, and 11 must
run strictly sequentially, in that order, regardless of the parallel opportunities described above
for their other files** — treat `AppContainer.kt` as a fifth, standing exclusive resource shared by
exactly those four tasks. Task 7 does not touch `AppContainer.kt` and may still run in parallel with
6 once 5 has landed, but must not run concurrently with 8 or 11.

---

### Task 1: DiagnosticsWriter — rotating, encrypted-on-seal NDJSON store

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsWriter.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsWriterTest.kt`

**Interfaces:**
- Consumes: `com.lateapex.collie.data.SecretCipher` and `com.lateapex.collie.data.CipherEnvelope`
  (both already defined in `android/app/src/main/java/com/lateapex/collie/data/ConnectionStore.kt:30-33`,
  no changes needed there).
- Produces: `class DiagnosticsWriter(directory: File, cipher: SecretCipher, json: Json)` with
  `fun appendLine(line: String)`, `fun seal()`, `fun sealedFiles(): List<File>`,
  `fun decrypt(file: File): String`, `fun activeFile(): File`. Task 2 calls `appendLine`. Task 11
  calls `sealedFiles()`, `decrypt()`, and `activeFile()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lateapex.collie.diagnostics

import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsWriterTest {
    private lateinit var directory: File
    private val cipher = XorCipher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        directory = File.createTempFile("diagnostics", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun appendsLinesToOneGrowingActiveFile() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 1_000_000L)
        writer.appendLine("""{"a":1}""")
        writer.appendLine("""{"a":2}""")
        val active = writer.activeFile()
        assertEquals(
            listOf("""{"a":1}""", """{"a":2}"""),
            active.readLines(),
        )
        assertTrue(writer.sealedFiles().isEmpty())
    }

    @Test
    fun sealsTheActiveFileOnceItCrossesTheSizeCapAndStartsAFreshOne() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 50L)
        writer.appendLine("x".repeat(60))
        assertEquals(1, writer.sealedFiles().size)
        assertEquals(0L, writer.activeFile().length())
    }

    @Test
    fun sealedFileContentsRoundTripThroughDecrypt() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 10L)
        writer.appendLine("""{"a":"hello"}""")
        val sealed = writer.sealedFiles().single()
        assertEquals("""{"a":"hello"}""" + "\n", writer.decrypt(sealed))
        // the sealed FILE on disk is the encrypted envelope, not the plaintext line — the point
        // of encrypting on seal at all is exactly that this must never appear in it.
        assertTrue(!sealed.readText().contains("hello"))
    }

    @Test
    fun keepsOnlyTheNewestFourSealedFilesOldestDroppedFirst() {
        // maxActiveBytes = 1 means any single non-empty line already crosses the cap, so each
        // appendLine call seals immediately — one call, one sealed file, no batching across calls.
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 1L, maxSealedFiles = 4)
        repeat(6) { index ->
            writer.appendLine("v$index")
            Thread.sleep(2) // sealed file names are millisecond timestamps; force distinct names
        }
        val sealed = writer.sealedFiles()
        assertEquals(4, sealed.size)
        // decrypt every remaining sealed file and confirm the two oldest payloads are gone
        val remainingContents = sealed.map { writer.decrypt(it) }
        assertTrue(remainingContents.none { it.contains("v0") })
        assertTrue(remainingContents.none { it.contains("v1") })
        assertTrue(remainingContents.any { it.contains("v5") })
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsWriterTest"`
Expected: FAIL — `DiagnosticsWriter` is unresolved (class does not exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.lateapex.collie.diagnostics

import android.util.Base64
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DiagnosticsEnvelope(val version: Int, val iv: String, val ciphertext: String)

/**
 * Appends newline-delimited JSON to one plaintext active file bounded by [maxActiveBytes]; once
 * full it is sealed into an AES/GCM-encrypted `.jsonl.enc` file (via the same [SecretCipher]
 * pattern `EncryptedConnectionStore` uses for the pairing bearer) and a fresh active file starts.
 * At most [maxSealedFiles] sealed files are kept, oldest dropped first — the trace is a rolling
 * window, not a growing log. The active file itself stays plaintext between seals; app-private
 * storage is not readable by another app without root, and the size cap bounds the exposure.
 */
class DiagnosticsWriter(
    private val directory: File,
    private val cipher: SecretCipher,
    private val json: Json,
    private val maxActiveBytes: Long = 5_000_000L,
    private val maxSealedFiles: Int = 4,
) {
    init {
        directory.mkdirs()
    }

    @Synchronized
    fun appendLine(line: String) {
        activeFile().appendText(line + "\n")
        if (activeFile().length() >= maxActiveBytes) seal()
    }

    @Synchronized
    fun seal() {
        val active = activeFile()
        if (!active.exists() || active.length() == 0L) return
        val plaintext = active.readBytes()
        val envelope = cipher.encrypt(plaintext)
        val sealed = File(directory, "trace-${System.currentTimeMillis()}.jsonl.enc")
        sealed.writeText(
            json.encodeToString(
                DiagnosticsEnvelope.serializer(),
                DiagnosticsEnvelope(
                    ENVELOPE_VERSION,
                    Base64.encodeToString(envelope.iv, Base64.NO_WRAP),
                    Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                ),
            ),
        )
        active.delete()
        pruneSealedFiles()
    }

    fun sealedFiles(): List<File> =
        (directory.listFiles { file -> file.name.endsWith(".jsonl.enc") } ?: emptyArray())
            .sortedBy { it.name }

    fun decrypt(file: File): String {
        val envelope = json.decodeFromString(DiagnosticsEnvelope.serializer(), file.readText())
        require(envelope.version == ENVELOPE_VERSION)
        val plaintext = cipher.decrypt(
            CipherEnvelope(
                Base64.decode(envelope.iv, Base64.NO_WRAP),
                Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
            ),
        )
        return plaintext.decodeToString()
    }

    fun activeFile(): File = File(directory, ACTIVE_FILE_NAME)

    private fun pruneSealedFiles() {
        val files = sealedFiles()
        if (files.size > maxSealedFiles) {
            files.take(files.size - maxSealedFiles).forEach { it.delete() }
        }
    }

    companion object {
        internal const val ACTIVE_FILE_NAME = "trace-active.jsonl"
        private const val ENVELOPE_VERSION = 1
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsWriterTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsWriter.kt android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsWriterTest.kt
git commit -m "feat(android): add the rotating encrypted-on-seal diagnostics writer"
```

---

### Task 2: DiagnosticsRecorder — the single entry point

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsRecorder.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsRecorderTest.kt`

**Interfaces:**
- Consumes: `DiagnosticsWriter.appendLine(String)` (Task 1);
  `com.lateapex.collie.ui.NativePreferences.diagnosticsEnabled: Boolean` (Task 3, default `true` —
  this task only reads it, so it does not block on Task 3 landing first as long as the property
  exists by the time this compiles against the real `NativePreferences`; the test below uses a
  fake preferences object of the same shape so it has no compile-time dependency on Task 3's file).
- Produces: `class DiagnosticsRecorder(writer: DiagnosticsWriter, enabled: () -> Boolean, clock: () -> Long = System::currentTimeMillis, executor: ExecutorService = Executors.newSingleThreadExecutor())`
  with `fun record(category: String, fields: Map<String, Any?> = emptyMap())`. Tasks 6, 7, 8, 9 all
  call `record(...)`.

Note: `enabled` is a lambda (`() -> Boolean`), not a direct `NativePreferences` reference — this
keeps this file's only dependency the shape of the check, not the concrete preferences class, and
lets its own test use a plain mutable flag instead of standing up Robolectric.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lateapex.collie.diagnostics

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRecorderTest {
    @Test
    fun writesOneLinePerRecordWithCategoryAndFields() {
        val lines = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        val recorder = DiagnosticsRecorder(
            writer = FakeWriter(lines),
            enabled = { true },
            clock = { 42L },
            executor = executor,
        )
        recorder.record("network", mapOf("path" to "/api/health", "status" to 200))
        executor.shutdown()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"category\":\"network\""))
        assertTrue(lines[0].contains("\"at\":42"))
        assertTrue(lines[0].contains("\"path\":\"/api/health\""))
        assertTrue(lines[0].contains("\"status\":200"))
    }

    @Test
    fun writesNothingWhenDisabled() {
        val lines = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        val recorder = DiagnosticsRecorder(
            writer = FakeWriter(lines),
            enabled = { false },
            executor = executor,
        )
        recorder.record("network", mapOf("path" to "/api/health"))
        executor.shutdown()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(lines.isEmpty())
    }

    @Test
    fun nullAndNestedFieldValuesEncodeWithoutThrowing() {
        val lines = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        val recorder = DiagnosticsRecorder(FakeWriter(lines), { true }, executor = executor)
        recorder.record(
            "test",
            mapOf(
                "missing" to null,
                "nested" to mapOf("a" to 1),
                "list" to listOf(1, "two", null),
            ),
        )
        executor.shutdown()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"missing\":null"))
    }

    private class FakeWriter(private val sink: MutableList<String>) : DiagnosticsAppendable {
        override fun appendLine(line: String) {
            synchronized(sink) { sink.add(line) }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsRecorderTest"`
Expected: FAIL — `DiagnosticsRecorder` and `DiagnosticsAppendable` are unresolved.

- [ ] **Step 3: Write the implementation**

`DiagnosticsRecorder` depends on `DiagnosticsWriter` only through a narrow interface
(`DiagnosticsAppendable`) so this test can use a plain in-memory fake instead of a real file-backed
writer. `DiagnosticsWriter` (Task 1) must implement it — add `: DiagnosticsAppendable` to its class
declaration once both files exist (this task creates the interface; nothing in Task 1 needs to
change, since `DiagnosticsWriter.appendLine(String)` already matches the interface's single method
exactly — Kotlin lets a class satisfy an interface declared after it as long as the class
declaration itself lists it, so add `, DiagnosticsAppendable` to `DiagnosticsWriter`'s supertype
list in this task's own diff below).

```kotlin
package com.lateapex.collie.diagnostics

/** The narrow surface `DiagnosticsRecorder` needs from a sink — lets its own tests use an
 * in-memory fake instead of a real, file-backed `DiagnosticsWriter`. */
interface DiagnosticsAppendable {
    fun appendLine(line: String)
}
```

```kotlin
package com.lateapex.collie.diagnostics

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The one entry point the rest of the app calls to record a diagnostic event. Enqueues onto its
 * own executor so a caller — including a frozen main thread during an ANR — never blocks on disk
 * I/O, and checks [enabled] before doing any work so the Settings switch needs no rebuild to take
 * effect.
 */
class DiagnosticsRecorder(
    private val writer: DiagnosticsAppendable,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    fun record(category: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled()) return
        val at = clock()
        executor.execute {
            val line = buildJsonObject {
                put("at", JsonPrimitive(at))
                put("category", JsonPrimitive(category))
                fields.forEach { (key, value) -> put(key, value.toJsonElement()) }
            }.toString()
            writer.appendLine(line)
        }
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { entries.forEach { (k, v) -> put(k.toString(), v.toJsonElement()) } }
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
```

Modify `DiagnosticsWriter.kt`'s class declaration (from Task 1) to implement the new interface:

```kotlin
class DiagnosticsWriter(
```
→
```kotlin
class DiagnosticsWriter(
```
stays the parameter list unchanged; only the class header line changes:
```kotlin
class DiagnosticsWriter(
    private val directory: File,
```
→ add supertype:
```kotlin
class DiagnosticsWriter(
    private val directory: File,
```
Concretely, change the line `class DiagnosticsWriter(` to `class DiagnosticsWriter(` with `) : DiagnosticsAppendable {` replacing the existing `) {` that closes the constructor parameter list (the line reading `    private val maxSealedFiles: Int = 4,\n) {`  becomes `    private val maxSealedFiles: Int = 4,\n) : DiagnosticsAppendable {`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsRecorderTest" --tests "com.lateapex.collie.diagnostics.DiagnosticsWriterTest"`
Expected: PASS, 7 tests total (3 from Task 2 + 4 from Task 1, confirming the supertype change
didn't break Task 1's tests).

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsRecorder.kt android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsAppendable.kt android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsWriter.kt android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsRecorderTest.kt
git commit -m "feat(android): add DiagnosticsRecorder as the one entry point for diagnostic events"
```

---

### Task 3: NativePreferences.diagnosticsEnabled toggle

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/NativePreferencesTest.kt`

**Interfaces:**
- Produces: `NativePreferences.diagnosticsEnabled: Boolean` (default `true`). Task 10 binds a
  Settings switch to it. Task 5 reads it (via a lambda) to construct `DiagnosticsRecorder`.

- [ ] **Step 1: Write the failing test**

Add to `android/app/src/test/java/com/lateapex/collie/ui/NativePreferencesTest.kt` (this file
already exists — add this test method inside the existing `NativePreferencesTest` class, next to
`defaultsFollowTheSystemAndEnableHaptics`):

```kotlin
    @Test
    fun diagnosticsCaptureDefaultsToOnAndPersists() {
        val preferences = NativePreferences(context)
        assertTrue(preferences.diagnosticsEnabled)

        preferences.diagnosticsEnabled = false
        assertFalse(NativePreferences(context).diagnosticsEnabled)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.NativePreferencesTest.diagnosticsCaptureDefaultsToOnAndPersists"`
Expected: FAIL — `diagnosticsEnabled` is unresolved.

- [ ] **Step 3: Write the implementation**

In `android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt`, add a property next to
`hapticsEnabled` (currently at lines 32-36):

```kotlin
    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(HAPTICS_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(HAPTICS_ENABLED, value).apply()
        }
```
→ (insert immediately after this block)
```kotlin
    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(HAPTICS_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(HAPTICS_ENABLED, value).apply()
        }

    /** Gates [com.lateapex.collie.diagnostics.DiagnosticsRecorder]; default on so a bug is
     * captured without arming anything ahead of time. Turning it off needs no rebuild. */
    var diagnosticsEnabled: Boolean
        get() = preferences.getBoolean(DIAGNOSTICS_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(DIAGNOSTICS_ENABLED, value).apply()
        }
```

And add the key constant next to `HAPTICS_ENABLED` in the companion object (currently at line 175):

```kotlin
        internal const val HAPTICS_ENABLED = "haptics_enabled"
```
→
```kotlin
        internal const val HAPTICS_ENABLED = "haptics_enabled"
        internal const val DIAGNOSTICS_ENABLED = "diagnostics_enabled"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.NativePreferencesTest"`
Expected: PASS, all `NativePreferencesTest` tests including the new one.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt android/app/src/test/java/com/lateapex/collie/ui/NativePreferencesTest.kt
git commit -m "feat(android): add a diagnostics-capture preference, default on"
```

---

### Task 4: AnrWatchdog — main-thread freeze detection

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/diagnostics/AnrWatchdog.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/diagnostics/AnrWatchdogTest.kt`

**Interfaces:**
- Produces: `class AnrWatchdog(mainHandler: android.os.Handler, onBlocked: (blockedForMs: Long) -> Unit, pingIntervalMs: Long = 2_000L, timeoutMs: Long = 5_000L)` with `fun start()` and `fun stop()`,
  plus an internal pure helper `internal fun isBlocked(lastAckAt: Long, now: Long, timeoutMs: Long): Boolean`
  used directly by the test (the class itself spawns a real background thread, which is not safely
  exercisable end-to-end in a unit test — per spec §Testing, only its timeout/miss *logic* is unit
  tested here). Task 7 constructs and starts one.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lateapex.collie.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrWatchdogTest {
    @Test
    fun notBlockedWhenTheLastAckIsWithinTheTimeout() {
        assertFalse(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 4_000L, timeoutMs = 5_000L))
    }

    @Test
    fun blockedOnceTheLastAckIsOlderThanTheTimeout() {
        assertTrue(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 7_000L, timeoutMs = 5_000L))
    }

    @Test
    fun exactlyAtTheTimeoutBoundaryIsNotYetBlocked() {
        assertFalse(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 6_000L, timeoutMs = 5_000L))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.AnrWatchdogTest"`
Expected: FAIL — `AnrWatchdog` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.lateapex.collie.diagnostics

import android.os.Handler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects a frozen main thread: posts a ping to [mainHandler] every [pingIntervalMs] and expects
 * it acknowledged within [timeoutMs]. A miss calls [onBlocked] from the watchdog's own background
 * thread — the main thread is, by definition, not available to do that itself.
 */
class AnrWatchdog(
    private val mainHandler: Handler,
    private val onBlocked: (blockedForMs: Long) -> Unit,
    private val pingIntervalMs: Long = 2_000L,
    private val timeoutMs: Long = 5_000L,
) {
    private val lastAckAt = AtomicLong(System.currentTimeMillis())
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            while (running.get()) {
                mainHandler.post { lastAckAt.set(System.currentTimeMillis()) }
                Thread.sleep(pingIntervalMs)
                val now = System.currentTimeMillis()
                val ack = lastAckAt.get()
                if (isBlocked(ack, now, timeoutMs)) onBlocked(now - ack)
            }
        }.apply {
            isDaemon = true
            name = "collie-anr-watchdog"
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    companion object {
        internal fun isBlocked(lastAckAt: Long, now: Long, timeoutMs: Long): Boolean =
            now - lastAckAt > timeoutMs
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.AnrWatchdogTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/diagnostics/AnrWatchdog.kt android/app/src/test/java/com/lateapex/collie/diagnostics/AnrWatchdogTest.kt
git commit -m "feat(android): add the main-thread-freeze watchdog"
```

---

### Task 5: Wire DiagnosticsRecorder into AppContainer

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/AppContainer.kt`

**Interfaces:**
- Consumes: `DiagnosticsWriter` (Task 1), `DiagnosticsRecorder` (Task 2),
  `NativePreferences.diagnosticsEnabled` (Task 3), `AndroidKeystoreCipher` (already exists,
  `android/app/src/main/java/com/lateapex/collie/data/ConnectionStore.kt:36`).
- Produces: `AppContainer.diagnostics: DiagnosticsRecorder`, `AppContainer.nativePreferences: NativePreferences`
  (this container did not construct `NativePreferences` before — every existing call site
  constructs its own `NativePreferences(context)` directly, which is harmless since it's a thin
  `SharedPreferences` wrapper with no state of its own; this task adds one to the container
  specifically so `DiagnosticsRecorder`'s `enabled` lambda can read the live value without every
  caller needing its own instance). Tasks 6, 7, 8, 9 read `container.diagnostics`.

The full current file (`android/app/src/main/java/com/lateapex/collie/AppContainer.kt`) is:

```kotlin
package com.lateapex.collie

import android.content.Context
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.CollieApiClient
import com.lateapex.collie.network.OriginValidator
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    val httpClient: OkHttpClient = CollieApiClient.defaultHttpClient()
    val originValidator = OriginValidator()
    val connectionStore: ConnectionStore = EncryptedConnectionStore(
        context.applicationContext,
        AndroidKeystoreCipher(),
        json,
        originValidator,
    )
    val api: CollieApi = CollieApiClient(httpClient, json)
    val repository = CollieRepository(api, connectionStore, originValidator)
}
```

- [ ] **Step 1: Replace it with**

```kotlin
package com.lateapex.collie

import android.content.Context
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.diagnostics.DiagnosticsRecorder
import com.lateapex.collie.diagnostics.DiagnosticsWriter
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.CollieApiClient
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.ui.NativePreferences
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    val nativePreferences = NativePreferences(context.applicationContext)
    private val diagnosticsWriter = DiagnosticsWriter(
        directory = File(context.applicationContext.filesDir, "diagnostics"),
        cipher = AndroidKeystoreCipher(alias = "com.lateapex.collie.diagnostics.v1"),
        json = json,
    )
    val diagnostics = DiagnosticsRecorder(
        writer = diagnosticsWriter,
        enabled = { nativePreferences.diagnosticsEnabled },
    )
    val httpClient: OkHttpClient = CollieApiClient.defaultHttpClient(diagnostics)
    val originValidator = OriginValidator()
    val connectionStore: ConnectionStore = EncryptedConnectionStore(
        context.applicationContext,
        AndroidKeystoreCipher(),
        json,
        originValidator,
    )
    val api: CollieApi = CollieApiClient(httpClient, json)
    val repository = CollieRepository(api, connectionStore, originValidator)
}
```

Note the distinct Keystore alias (`com.lateapex.collie.diagnostics.v1`, vs. the connection store's
`com.lateapex.collie.connection.v1` default) — this is a **separate key**, so revoking or losing one
never affects the other. `CollieApiClient.defaultHttpClient(diagnostics)` will not compile until
Task 6 changes that function's signature; this task alone will not build in isolation, which is
expected and fine given Task 6 is its direct successor in the dependency order above (this is a
sequential pair, not two independently-mergeable lanes, even though the table lists them
separately for clarity of file ownership).

- [ ] **Step 2: Commit alongside Task 6**

This task has no test file of its own (as noted above, `AppContainer` isn't unit-tested directly in
this codebase — it's exercised indirectly through Robolectric Activity tests such as
`SettingsActivityTest`). Do not commit this change on its own since it won't compile until Task 6's
`defaultHttpClient` signature change lands; stage both together:

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/AppContainer.kt
# staged here; the commit itself happens at the end of Task 6, together with that task's files
```

---

### Task 6: DiagnosticsInterceptor — network capture, with the hard bearer exception

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptor.kt`
- Modify: `android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptorTest.kt`

**Interfaces:**
- Consumes: `DiagnosticsRecorder.record(String, Map<String, Any?>)` (Task 2).
- Produces: `class DiagnosticsInterceptor(diagnostics: DiagnosticsRecorder) : okhttp3.Interceptor`.
  Adds an `X-Collie-Trace-Id` header to every outgoing request (Task 12's bridge access log reads
  this same header name, case-insensitively, on the server side).

`CollieApiClient.defaultHttpClient()` (currently `android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt:740-748`) takes no arguments and is called from two places: `AppContainer.kt` (Task 5, updated above) and the existing test `android/app/src/test/java/com/lateapex/collie/network/CollieApiClientTest.kt:39` (`CollieApiClient.defaultHttpClient().newBuilder()...`), which this task must not break.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lateapex.collie.diagnostics

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var recorded: MutableList<Pair<String, Map<String, Any?>>>
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        recorded = mutableListOf()
        val recorder = RecordingRecorder(recorded)
        client = OkHttpClient.Builder()
            .addInterceptor(DiagnosticsInterceptor(recorder))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun recordsMethodPathStatusAndAddsATraceIdHeaderTheServerReceives() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        val request = Request.Builder().url(server.url("/api/health")).build()
        client.newCall(request).execute().use { it.body?.string() }

        val sent = server.takeRequest()
        assertNotNull(sent.getHeader("X-Collie-Trace-Id"))

        val (category, fields) = recorded.single()
        assertEquals("network", category)
        assertEquals("GET", fields["method"])
        assertEquals("/api/health", fields["path"])
        assertEquals(200L, (fields["status"] as Number).toLong())
        assertEquals(sent.getHeader("X-Collie-Trace-Id"), fields["traceId"])
    }

    @Test
    fun neverRecordsTheAuthorizationHeaderValueOnlyThatOneWasPresent() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder()
            .url(server.url("/api/devices"))
            .header("Authorization", "Bearer super-secret-token")
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val (_, fields) = recorded.single()
        assertEquals(true, fields["hadCredential"])
        assertFalse(fields.values.any { it.toString().contains("super-secret-token") })
    }

    @Test
    fun capturesJsonRequestAndResponseBodiesInFull() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"label\":\"phone\"}"))
        val request = Request.Builder()
            .url(server.url("/api/pair"))
            .header("Content-Type", "application/json")
            .post("{\"code\":\"ABC123\"}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val (_, fields) = recorded.single()
        assertTrue(fields["requestBody"].toString().contains("ABC123"))
        assertTrue(fields["responseBody"].toString().contains("phone"))
    }

    @Test
    fun capturesBinaryBodiesAsSizeAndTypeOnlyNeverRawBytes() {
        val bytes = ByteArray(1024) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
        val request = Request.Builder()
            .url(server.url("/api/pane/x/upload"))
            .header("Content-Type", "image/png")
            .post(bytes.toRequestBody("image/png".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.bytes() }

        val (_, fields) = recorded.single()
        assertEquals(1024L, (fields["requestBodyBytes"] as Number).toLong())
        assertFalse(fields.containsKey("requestBody"))
        assertEquals(1024L, (fields["responseBodyBytes"] as Number).toLong())
        assertFalse(fields.containsKey("responseBody"))
    }

    private class RecordingRecorder(
        private val sink: MutableList<Pair<String, Map<String, Any?>>>,
    ) : DiagnosticsRecorder(FakeAppendable(), { true }) {
        override fun record(category: String, fields: Map<String, Any?>) {
            synchronized(sink) { sink.add(category to fields) }
        }
    }

    private class FakeAppendable : DiagnosticsAppendable {
        override fun appendLine(line: String) = Unit
    }
}
```

`DiagnosticsRecorder.record` must be `open` for `RecordingRecorder` above to override it — update
Task 2's class declaration from `class DiagnosticsRecorder(` to `open class DiagnosticsRecorder(`
and its `fun record(` to `open fun record(` as part of this task's diff (a one-word change in each
of two places in `DiagnosticsRecorder.kt`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsInterceptorTest"`
Expected: FAIL — `DiagnosticsInterceptor` is unresolved, and `DiagnosticsRecorder`/`record` are not
`open` yet.

- [ ] **Step 3: Write the implementation**

In `DiagnosticsRecorder.kt` (from Task 2), change:
```kotlin
class DiagnosticsRecorder(
```
to
```kotlin
open class DiagnosticsRecorder(
```
and change:
```kotlin
    fun record(category: String, fields: Map<String, Any?> = emptyMap()) {
```
to
```kotlin
    open fun record(category: String, fields: Map<String, Any?> = emptyMap()) {
```

Create `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptor.kt`:

```kotlin
package com.lateapex.collie.diagnostics

import java.util.UUID
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The single place every Collie API request and response passes through. Tags each request with a
 * trace id the bridge's own access log echoes back (`bridge/server.ts`'s `accessLogRecord`), so a
 * client-side event can be matched to the bridge's `journalctl` output by id instead of by
 * timestamp guessing. Text bodies are captured in full; binary bodies (image uploads, STT audio)
 * are captured as content-type and size only. The `Authorization` header's VALUE is never
 * recorded, under any circumstance — only whether one was present.
 */
class DiagnosticsInterceptor(private val diagnostics: DiagnosticsRecorder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val traceId = UUID.randomUUID().toString()
        val original = chain.request()
        val tagged = original.newBuilder().header(TRACE_HEADER, traceId).build()
        val start = System.currentTimeMillis()
        val response = chain.proceed(tagged)
        val fields = mutableMapOf<String, Any?>(
            "method" to tagged.method,
            "path" to tagged.url.encodedPath,
            "status" to response.code,
            "ms" to (System.currentTimeMillis() - start),
            "traceId" to traceId,
            "hadCredential" to (tagged.header("Authorization") != null),
        )
        val requestBody = tagged.body
        if (requestBody != null) {
            if (isTextual(requestBody.contentType())) {
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                fields["requestBody"] = buffer.readUtf8()
            } else {
                fields["requestBodyBytes"] = requestBody.contentLength()
            }
        }
        val responseBody = response.body
        return if (responseBody != null && isTextual(responseBody.contentType())) {
            val text = responseBody.string()
            fields["responseBody"] = text
            diagnostics.record("network", fields)
            response.newBuilder()
                .body(text.toResponseBody(responseBody.contentType()))
                .build()
        } else if (responseBody != null) {
            val bytes = responseBody.bytes()
            fields["responseBodyBytes"] = bytes.size
            diagnostics.record("network", fields)
            response.newBuilder()
                .body(bytes.toResponseBody(responseBody.contentType()))
                .build()
        } else {
            diagnostics.record("network", fields)
            response
        }
    }

    private fun isTextual(type: MediaType?): Boolean =
        type == null || type.type == "application" && type.subtype in TEXTUAL_SUBTYPES ||
            type.type == "text"

    companion object {
        internal const val TRACE_HEADER = "X-Collie-Trace-Id"
        private val TEXTUAL_SUBTYPES = setOf("json", "xml")
    }
}
```

In `CollieApiClient.kt`, change the companion object's `defaultHttpClient` (currently at
lines 740-748):

```kotlin
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
```
to
```kotlin
        fun defaultHttpClient(diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder? = null): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .apply {
                    if (diagnostics != null) {
                        addInterceptor(com.lateapex.collie.diagnostics.DiagnosticsInterceptor(diagnostics))
                    }
                }
                .build()
```

The default `= null` means `CollieApiClientTest.kt:39`'s existing `CollieApiClient.defaultHttpClient()`
call (no argument) keeps compiling and behaving exactly as before — that test's own assertions
about redirects/timeouts are untouched, since no interceptor is added when the argument is absent.
`AppContainer.kt` (Task 5) already calls `CollieApiClient.defaultHttpClient(diagnostics)` with the
real recorder.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsInterceptorTest" --tests "com.lateapex.collie.network.CollieApiClientTest"`
Expected: PASS — the 4 new interceptor tests, and every existing `CollieApiClientTest` test
unchanged (confirming the default-`null` parameter didn't alter existing behavior).

Run the full unit suite once here, since this task also lands `AppContainer.kt` from Task 5:
Run: `cd android && ./gradlew --no-daemon testDebugUnitTest`
Expected: PASS, zero failures — this is the first point where `AppContainer.kt` actually compiles
and every existing Robolectric test that touches `CollieApplication.container` (e.g.
`SettingsActivityTest`) exercises the new wiring incidentally.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/AppContainer.kt android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptor.kt android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsRecorder.kt android/app/src/main/java/com/lateapex/collie/network/CollieApiClient.kt android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptorTest.kt
git commit -m "feat(android): capture every network call, with the bearer value hard-excluded"
```

---

### Task 7: CollieApplication — lifecycle, crash, and ANR capture

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/CollieApplication.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/CollieApplicationTest.kt` (new file)

**Interfaces:**
- Consumes: `AppContainer.diagnostics` (Task 5), `AnrWatchdog` (Task 4).
- Produces: nothing new consumed by later tasks — this is a leaf.

Current file (`android/app/src/main/java/com/lateapex/collie/CollieApplication.kt`):

```kotlin
package com.lateapex.collie

import android.app.Application
import com.lateapex.collie.ui.NativePreferences

class CollieApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NativePreferences(this).applyTheme()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.lateapex.collie

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CollieApplicationTest {
    @Test
    fun installsAnUncaughtExceptionHandlerThatChainsToThePrevious() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(installed != null)
    }

    @Test
    fun registersActivityLifecycleCallbacksSoAPaneOpeningIsObservable() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val activity = Robolectric.buildActivity(com.lateapex.collie.ui.MainActivity::class.java)
            .create().start().resume().get()
        shadowOf(activity.mainLooper).idle()
        // No direct assertion on the trace file here (Task 8/9 cover recorder call sites); this
        // test only pins that CollieApplication registers callbacks at all, since a regression
        // that silently drops the registration would otherwise compile and pass every other test.
        assertTrue(app.lifecycleCallbacksRegisteredForTest)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.CollieApplicationTest"`
Expected: FAIL — `lifecycleCallbacksRegisteredForTest` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.lateapex.collie

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.lateapex.collie.diagnostics.AnrWatchdog
import com.lateapex.collie.ui.NativePreferences

class CollieApplication : Application() {
    lateinit var container: AppContainer
        private set

    @VisibleForTesting
    internal var lifecycleCallbacksRegisteredForTest: Boolean = false
        private set

    private var anrWatchdog: AnrWatchdog? = null

    override fun onCreate() {
        super.onCreate()
        NativePreferences(this).applyTheme()
        container = AppContainer(this)
        installUncaughtExceptionHandler()
        registerActivityLifecycleCallbacks(diagnosticsLifecycleCallbacks())
        lifecycleCallbacksRegisteredForTest = true
        startAnrWatchdog()
        recordHistoricalExitReasons()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            container.diagnostics.record(
                "crash",
                mapOf(
                    "thread" to thread.name,
                    "exception" to throwable.javaClass.name,
                    "message" to throwable.message,
                    "stackTrace" to throwable.stackTraceToString(),
                ),
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun diagnosticsLifecycleCallbacks() = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            record(activity, "created")
        override fun onActivityStarted(activity: Activity) = record(activity, "started")
        override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
        override fun onActivityPaused(activity: Activity) = record(activity, "paused")
        override fun onActivityStopped(activity: Activity) = record(activity, "stopped")
        override fun onActivityDestroyed(activity: Activity) = record(activity, "destroyed")
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        private fun record(activity: Activity, event: String) {
            container.diagnostics.record(
                "lifecycle",
                mapOf("activity" to activity.javaClass.simpleName, "event" to event),
            )
        }
    }

    private fun startAnrWatchdog() {
        anrWatchdog = AnrWatchdog(
            mainHandler = Handler(Looper.getMainLooper()),
            onBlocked = { blockedForMs ->
                container.diagnostics.record("anr", mapOf("blockedForMs" to blockedForMs))
            },
        ).also { it.start() }
    }

    /** API 30+ only: the OS's own record of why the PREVIOUS process died, read once at the next
     * launch — catches an ANR/crash the watchdog never got a chance to observe before the process
     * ended. Best-effort: any failure here is itself diagnostic-adjacent, not diagnostic-critical. */
    private fun recordHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = getSystemService(ActivityManager::class.java) ?: return
            val reasons = activityManager.getHistoricalProcessExitReasons(null, 0, 5)
            reasons.forEach { info ->
                container.diagnostics.record(
                    "previousProcessExit",
                    mapOf(
                        "reason" to info.reason,
                        "description" to info.description,
                        "timestamp" to info.timestamp,
                    ),
                )
            }
        } catch (_: Exception) {
            // Best-effort; never let a diagnostics read crash startup.
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.CollieApplicationTest"`
Expected: PASS, 2 tests.

Run the full unit suite to confirm nothing else regressed:
Run: `cd android && ./gradlew --no-daemon testDebugUnitTest`
Expected: PASS, zero failures.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/CollieApplication.kt android/app/src/test/java/com/lateapex/collie/CollieApplicationTest.kt
git commit -m "feat(android): capture lifecycle events, crashes, and ANRs"
```

---

### Task 8: PaneBodyDecision recording

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt`
- Modify: `android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt`

**Interfaces:**
- Consumes: `AppContainer.diagnostics` (Task 5), `PaneBodyDecision.decide(...)` (already exists,
  `android/app/src/main/java/com/lateapex/collie/ui/PaneBodyMode.kt`, unchanged by this task).

The exact current call site (`android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:3376-3382`):

```kotlin
        val decided = PaneBodyDecision.decide(
            analyzedSemanticSurface,
            state.transcriptAvailable,
            displayPreferences.getBoolean(PREF_RAW, false),
            agentName,
        )
        if (decided.reason != lastBodyReason) {
            bodyOverride = null
            lastBodyReason = decided.reason
        }
```

- [ ] **Step 1: Write the failing test**

Add to `android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt`, near its other
`renderPaneBody`-adjacent tests (the file already drives `render(activity, PaneUiState(...))` via
reflection per this repo's established pattern — see any existing test calling `render(activity, ...)`
in that file for the exact helper signature to reuse):

```kotlin
    @Test
    fun paneBodyDecisionChangesAreRecordedToDiagnostics() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:diag", agent = "claude"))
            .create().start().resume().get()
        val lines = mutableListOf<String>()
        val app = activity.applicationContext as CollieApplication
        val recorder = RecordingDiagnosticsRecorder(lines)
        setDiagnosticsForTest(app, recorder)

        val transcript = listOf(TranscriptEntry("a", "2026-09-18T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "hi"))))
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:diag", "screen", truncated = false, revision = 0),
                loading = false,
                transcript = transcript,
                transcriptAvailable = true,
            ),
        )

        assertTrue(lines.any { it.first == "paneBodyDecision" })
    }
```

This test needs two small test-only seams that do not exist yet:
1. `RecordingDiagnosticsRecorder` — a reusable open subclass of `DiagnosticsRecorder`, matching the
   one already written inline in `DiagnosticsInterceptorTest.kt` (Task 6). Rather than duplicate it,
   add it once as `android/app/src/test/java/com/lateapex/collie/diagnostics/RecordingDiagnosticsRecorder.kt`
   in this task (moving it out of `DiagnosticsInterceptorTest.kt`'s private inner class into a
   shared top-level test class both files import), since Task 6 already established the pattern
   and this task is its second consumer:

```kotlin
package com.lateapex.collie.diagnostics

/** Records every call instead of writing to disk — shared by any test that needs to assert what a
 * component asked to record, without a real file-backed writer. */
open class RecordingDiagnosticsRecorder(
    private val sink: MutableList<Pair<String, Map<String, Any?>>>,
) : DiagnosticsRecorder(NoopAppendable, { true }) {
    override fun record(category: String, fields: Map<String, Any?>) {
        synchronized(sink) { sink.add(category to fields) }
    }

    private object NoopAppendable : DiagnosticsAppendable {
        override fun appendLine(line: String) = Unit
    }
}
```

   Then simplify `DiagnosticsInterceptorTest.kt`'s `RecordingRecorder` to reuse this shared class
   instead of its own private one — replace its `private class RecordingRecorder(...) { ... }`
   block with a single line at each call site: `RecordingDiagnosticsRecorder(recorded)` in place of
   `RecordingRecorder(recorded)`, and delete the now-unused private class.

2. `setDiagnosticsForTest(app, recorder)` — `AppContainer.diagnostics` is a `val` (Task 5), so a
   test cannot swap it after construction. Add a `@VisibleForTesting` seam to `AppContainer`:

In `AppContainer.kt`, change:
```kotlin
    val diagnostics = DiagnosticsRecorder(
        writer = diagnosticsWriter,
        enabled = { nativePreferences.diagnosticsEnabled },
    )
```
to
```kotlin
    var diagnostics: DiagnosticsRecorder = DiagnosticsRecorder(
        writer = diagnosticsWriter,
        enabled = { nativePreferences.diagnosticsEnabled },
    )
        @androidx.annotation.VisibleForTesting internal set
```

No shared test-utility file exists in `android/app/src/test/java/com/lateapex/collie/ui/` today
(confirmed: only per-class test files live there). Add this as a private top-level function at the
bottom of `PaneActivityTest.kt`, after the closing brace of the `PaneActivityTest` class:

```kotlin
private fun setDiagnosticsForTest(app: CollieApplication, recorder: DiagnosticsRecorder) {
    app.container.diagnostics = recorder
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.PaneActivityTest.paneBodyDecisionChangesAreRecordedToDiagnostics"`
Expected: FAIL — no call to `diagnostics.record("paneBodyDecision", ...)` exists yet in
`renderPaneBody`.

- [ ] **Step 3: Write the implementation**

Change (`android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:3376-3382`):

```kotlin
        val decided = PaneBodyDecision.decide(
            analyzedSemanticSurface,
            state.transcriptAvailable,
            displayPreferences.getBoolean(PREF_RAW, false),
            agentName,
        )
        if (decided.reason != lastBodyReason) {
            bodyOverride = null
            lastBodyReason = decided.reason
        }
```
to
```kotlin
        val decided = PaneBodyDecision.decide(
            analyzedSemanticSurface,
            state.transcriptAvailable,
            displayPreferences.getBoolean(PREF_RAW, false),
            agentName,
        )
        if (decided.reason != lastBodyReason) {
            (application as CollieApplication).container.diagnostics.record(
                "paneBodyDecision",
                mapOf(
                    "body" to decided.body.name,
                    "reason" to decided.reason.name,
                    "previousReason" to lastBodyReason?.name,
                ),
            )
            bodyOverride = null
            lastBodyReason = decided.reason
        }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.PaneActivityTest"`
Expected: PASS — the new test and every existing `PaneActivityTest` test.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/AppContainer.kt android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt android/app/src/test/java/com/lateapex/collie/diagnostics/RecordingDiagnosticsRecorder.kt android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsInterceptorTest.kt
git commit -m "feat(android): record every pane-body decision change"
```

---

### Task 9: Named write-action recording in PaneViewModel

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt`
- Modify: `android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt`

**Interfaces:**
- Consumes: `AppContainer.diagnostics` (Task 5, via `(application as CollieApplication).container.diagnostics`,
  the same pattern `repository`'s default parameter already uses one line above it), and
  `RecordingDiagnosticsRecorder` (Task 8's shared test class) for this task's own tests.

This is the highest-value action for diagnosing a "silent drop" (spec's confirmed symptom class):
the network interceptor (Task 6) already captures the resulting `POST /api/pane/{id}/reply` in
full, so this task's job is only to mark the moment the user *initiated* the send, decoupled from
whether the network call that follows succeeds — the gap between "user tapped Send" and "no
network call ever fired" is exactly what a silent-drop bug looks like in this trace.

The `PaneViewModel` class declaration (`android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt:74-79`):

```kotlin
class PaneViewModel(
    application: Application,
    private val address: PaneAddress,
    initialAgent: String?,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
) : AndroidViewModel(application) {
```

- [ ] **Step 1: Write the failing test**

The exact existing fixture helper (`android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt:518-537`)
is:

```kotlin
    private fun fixture(
        reply: ApiResult<ActionResponse>,
        keyResults: List<ApiResult<ActionResponse>> = emptyList(),
        deviceAuthorization: DeviceAuthorization? = null,
        agent: String = "codex",
        address: PaneAddress = PaneAddress(paneId = "w1:p1"),
    ): Fixture {
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
        val store = FakeStore(connection)
        val api = FakeApi(reply, keyResults, deviceAuthorization)
        // The snapshot names the pane's agent and the view model follows it, so the fake
        // snapshot must agree with the agent the fixture launches with.
        api.snapshotPanes = api.snapshotPanes.map { if (it.paneId == address.paneId) it.copy(agent = agent) else it }
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = PaneViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            address,
            agent,
            repository,
        )
        return Fixture(viewModel, api, store)
    }
```

Change it to accept and pass through a diagnostics recorder, defaulted so every existing call site
(all of which omit this new parameter) compiles unchanged:

```kotlin
    private fun fixture(
        reply: ApiResult<ActionResponse>,
        keyResults: List<ApiResult<ActionResponse>> = emptyList(),
        deviceAuthorization: DeviceAuthorization? = null,
        agent: String = "codex",
        address: PaneAddress = PaneAddress(paneId = "w1:p1"),
        diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder =
            com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()),
    ): Fixture {
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
        val store = FakeStore(connection)
        val api = FakeApi(reply, keyResults, deviceAuthorization)
        // The snapshot names the pane's agent and the view model follows it, so the fake
        // snapshot must agree with the agent the fixture launches with.
        api.snapshotPanes = api.snapshotPanes.map { if (it.paneId == address.paneId) it.copy(agent = agent) else it }
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = PaneViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            address,
            agent,
            repository,
            diagnostics,
        )
        return Fixture(viewModel, api, store)
    }
```

`RecordingDiagnosticsRecorder` is the shared test class Task 8 adds at
`android/app/src/test/java/com/lateapex/collie/diagnostics/RecordingDiagnosticsRecorder.kt`; this
task depends on Task 8 for that reason (both are listed as depending only on Task 2 in the
dependency table above, but Task 9 additionally needs Task 8's shared test fixture file — run Task
8 before Task 9 even though they touch disjoint production files).

Then add two new tests to the same file, using this repo's own established
`fixture(ApiResult.Success(ActionResponse(ok = true), 200))` call shape (used throughout this file,
e.g. at its line 178):

```kotlin
    @Test
    fun sendingAReplyRecordsTheActionBeforeTheNetworkCallCompletes() {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(lines)
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), diagnostics = diagnostics)
        f.viewModel.sendReply("hello")

        assertTrue(lines.any { it.first == "action" && it.second["name"] == "pane.send_reply" })
    }

    @Test
    fun sendingAKeySequenceRecordsTheAction() {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(lines)
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), diagnostics = diagnostics)
        f.viewModel.sendKeySequence(listOf("ctrl+c"))

        assertTrue(lines.any { it.first == "action" && it.second["name"] == "pane.send_key_sequence" })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.PaneViewModelTest"`
Expected: FAIL on the two new tests — no `"action"` category is ever recorded yet.

- [ ] **Step 3: Write the implementation**

Change the class declaration:
```kotlin
class PaneViewModel(
    application: Application,
    private val address: PaneAddress,
    initialAgent: String?,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
) : AndroidViewModel(application) {
```
to
```kotlin
class PaneViewModel(
    application: Application,
    private val address: PaneAddress,
    initialAgent: String?,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
    private val diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder =
        (application as CollieApplication).container.diagnostics,
) : AndroidViewModel(application) {
```

Change `fun sendReply(text: String, terminalDraftToClear: TerminalDraft? = null) {` (line 185) to
record at entry, before its existing blank/sending/refusal early-return check:
```kotlin
    fun sendReply(text: String, terminalDraftToClear: TerminalDraft? = null) {
        val clean = text.trimEnd()
        if (clean.isBlank() || mutableState.value.sending || refuseTerminalWrite()) return
```
to
```kotlin
    fun sendReply(text: String, terminalDraftToClear: TerminalDraft? = null) {
        diagnostics.record("action", mapOf("name" to "pane.send_reply"))
        val clean = text.trimEnd()
        if (clean.isBlank() || mutableState.value.sending || refuseTerminalWrite()) return
```

Change `sendKey`/`sendKeySequence`/`sendDirectKeys` (lines 270-278 plus `sendDirectKeys` at line 466):
```kotlin
    fun sendKey(key: String) {
        sendKeys(listOf(key), direct = false)
    }

    /** Sends one reviewed Keys-tray batch through the same fresh-pane guard as a single key. */
    fun sendKeySequence(keys: List<String>) {
        if (keys.size > MAX_REVIEWED_KEY_BATCH) return
        sendKeys(keys, direct = false)
    }
```
to
```kotlin
    fun sendKey(key: String) {
        diagnostics.record("action", mapOf("name" to "pane.send_key", "key" to key))
        sendKeys(listOf(key), direct = false)
    }

    /** Sends one reviewed Keys-tray batch through the same fresh-pane guard as a single key. */
    fun sendKeySequence(keys: List<String>) {
        diagnostics.record("action", mapOf("name" to "pane.send_key_sequence", "count" to keys.size))
        if (keys.size > MAX_REVIEWED_KEY_BATCH) return
        sendKeys(keys, direct = false)
    }
```

Find `fun sendDirectKeys(keys: List<String>) {` (line 466) and add the same pattern as its first
statement:
```kotlin
    fun sendDirectKeys(keys: List<String>) {
```
to
```kotlin
    fun sendDirectKeys(keys: List<String>) {
        diagnostics.record("action", mapOf("name" to "pane.send_direct_keys", "count" to keys.size))
```
(keeping every existing statement in that function's body unchanged and following this new line).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.PaneViewModelTest"`
Expected: PASS — the two new tests and every existing `PaneViewModelTest` test.

- [ ] **Step 5: Commit**

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt
git commit -m "feat(android): record send-reply and send-keys actions before their network call"
```

---

### Task 10: Settings — diagnostics on/off switch

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt`
- Modify: `android/app/src/main/res/values/settings_ids.xml`
- Modify: `android/app/src/main/res/values/settings_strings.xml`
- Modify: `android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `NativePreferences.diagnosticsEnabled` (Task 3), `SettingsLocalPreferences.switchCard(...)`
  (already exists, `android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt:82-104`,
  unchanged).

- [ ] **Step 1: Write the failing test**

Add to `android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt`, next to
`controlsReflectAndPersistDevicePreferences`:

```kotlin
    @Test
    fun diagnosticsSwitchDefaultsToOnAndTogglesThePreference() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        val diagnosticsSwitch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.settings_diagnostics_switch,
        )
        assertTrue(diagnosticsSwitch.isChecked)
        diagnosticsSwitch.performClick()
        assertFalse(NativePreferences(context).diagnosticsEnabled)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.SettingsActivityTest.diagnosticsSwitchDefaultsToOnAndTogglesThePreference"`
Expected: FAIL — `R.id.settings_diagnostics_switch` does not exist yet.

- [ ] **Step 3: Write the implementation**

Add the id to `android/app/src/main/res/values/settings_ids.xml`, next to
`settings_hands_free_switch`:

```xml
    <item name="settings_hands_free_switch" type="id" />
```
to
```xml
    <item name="settings_hands_free_switch" type="id" />
    <item name="settings_diagnostics_switch" type="id" />
    <item name="settings_diagnostics_card" type="id" />
    <item name="settings_diagnostics_send_button" type="id" />
```
(the latter two ids are for this task's card container and Task 11's export button, added together
here so `settings_ids.xml` only needs one edit across both tasks).

Add strings to `android/app/src/main/res/values/settings_strings.xml`, next to
`settings_hands_free_description`:

```xml
    <string name="settings_hands_free_description">Send the transcript immediately instead of putting it in the message box. Off by default — you normally read what was heard before it reaches the terminal.</string>
```
to
```xml
    <string name="settings_hands_free_description">Send the transcript immediately instead of putting it in the message box. Off by default — you normally read what was heard before it reaches the terminal.</string>
    <string name="settings_diagnostics_title">Diagnostics</string>
    <string name="settings_diagnostics_description">Always capture recent app activity — network calls, pane state, and any crash or freeze — so a bug can be diagnosed after it happens. On by default; turn off once you no longer need it.</string>
    <string name="settings_diagnostics_send_title">Send diagnostics</string>
    <string name="settings_diagnostics_send_confirm_title">Send diagnostics?</string>
    <string name="settings_diagnostics_send_confirm_message">This includes recent terminal content, which may contain sensitive information. Choose where it goes next.</string>
```

Add the toggle card to `SettingsLocalPreferences.kt`'s `bindBehavior` (currently
`android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt:30-43`):

```kotlin
    fun bindBehavior(parent: LinearLayout) {
        parent.removeAllViews()
        handsFreeCard = switchCard(
            title = text(R.string.settings_hands_free_title),
            description = text(R.string.settings_hands_free_description),
            id = R.id.settings_hands_free_switch,
            checked = preferences.handsFreeEnabled,
            iconRes = R.drawable.ic_pane_mic,
        ) { preferences.handsFreeEnabled = it }.apply {
            id = R.id.settings_parity_hands_free_card
            visibility = View.GONE
        }
        handsFreeCard?.let(parent::addView)
    }
```
to
```kotlin
    fun bindBehavior(parent: LinearLayout) {
        parent.removeAllViews()
        handsFreeCard = switchCard(
            title = text(R.string.settings_hands_free_title),
            description = text(R.string.settings_hands_free_description),
            id = R.id.settings_hands_free_switch,
            checked = preferences.handsFreeEnabled,
            iconRes = R.drawable.ic_pane_mic,
        ) { preferences.handsFreeEnabled = it }.apply {
            id = R.id.settings_parity_hands_free_card
            visibility = View.GONE
        }
        handsFreeCard?.let(parent::addView)
        parent.addView(
            switchCard(
                title = text(R.string.settings_diagnostics_title),
                description = text(R.string.settings_diagnostics_description),
                id = R.id.settings_diagnostics_switch,
                checked = preferences.diagnosticsEnabled,
                iconRes = R.drawable.ic_history_wrench,
            ) { preferences.diagnosticsEnabled = it }.apply {
                id = R.id.settings_diagnostics_card
            },
        )
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.SettingsActivityTest"`
Expected: PASS — the new test and every existing `SettingsActivityTest` test.

- [ ] **Step 5: Add the CHANGELOG line and commit**

In `CHANGELOG.md`, add one line at the end of the `## [Unreleased]` list (currently ending with
"Android: opening an agent pane lands on the newest turn; a turn taking focus no longer scrolls the
transcript to the top of the session.", immediately before the `## [1.5.1] - 2026-09-04` heading):

```
- Android: opening an agent pane lands on the newest turn; a turn taking focus no longer scrolls the transcript to the top of the session.
```
to
```
- Android: opening an agent pane lands on the newest turn; a turn taking focus no longer scrolls the transcript to the top of the session.
- Android: Settings gains a Diagnostics switch (on by default) that always captures recent app activity for later troubleshooting.
```

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt android/app/src/main/res/values/settings_ids.xml android/app/src/main/res/values/settings_strings.xml android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt CHANGELOG.md
git commit -m "feat(android): add a Settings switch for diagnostics capture"
```

---

### Task 11: Settings — "Send diagnostics" share-sheet export

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsExport.kt`
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/xml/diagnostics_file_paths.xml`
- Test: `android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsExportTest.kt`
- Modify: `android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `DiagnosticsWriter.sealedFiles()`, `.decrypt()`, `.activeFile()` (Task 1);
  `CollieBottomSheetDialog` (already exists, used by `SettingsActivity.confirmDisconnect()` at
  `android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt:156-186`, reused here as the
  confirm-dialog pattern, unchanged).
- Produces: `class DiagnosticsExport(writer: DiagnosticsWriter, exportDir: File) { fun buildZip(): File }`.

- [ ] **Step 1: Write the failing test for the export packaging logic**

```kotlin
package com.lateapex.collie.diagnostics

import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsExportTest {
    private lateinit var traceDir: File
    private lateinit var exportDir: File
    private val cipher = XorCipher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        traceDir = File.createTempFile("trace", "dir").apply { delete(); mkdirs() }
        exportDir = File.createTempFile("export", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        traceDir.deleteRecursively()
        exportDir.deleteRecursively()
    }

    @Test
    fun zipsTheActiveFileAndEverySealedFileDecryptedToPlaintext() {
        val writer = DiagnosticsWriter(traceDir, cipher, json, maxActiveBytes = 1_000_000L)
        writer.appendLine("""{"a":"sealed-one"}""")
        writer.seal() // force this line into a sealed file regardless of size, deterministically
        writer.appendLine("""{"a":"active-one"}""") // stays in the fresh active file (well under the cap)

        val export = DiagnosticsExport(writer, exportDir)
        val zip = export.buildZip()

        assertTrue(zip.exists())
        ZipFile(zip).use { file ->
            val names = file.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith(".jsonl") })
            val allText = names.joinToString("\n") { name ->
                file.getInputStream(file.getEntry(name)).bufferedReader().readText()
            }
            assertTrue(allText.contains("sealed-one"))
            assertTrue(allText.contains("active-one"))
        }
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsExportTest"`
Expected: FAIL — `DiagnosticsExport` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.lateapex.collie.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the current trace — every sealed file decrypted back to plaintext, plus whatever is in
 * the still-open active file — into one zip under [exportDir], ready to hand to the share sheet.
 * The caller is responsible for deleting the returned file once the share sheet has taken it.
 */
class DiagnosticsExport(
    private val writer: DiagnosticsWriter,
    private val exportDir: File,
) {
    fun buildZip(): File {
        exportDir.mkdirs()
        val zipFile = File(exportDir, "collie-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            writer.sealedFiles().forEachIndexed { index, sealed ->
                zip.putNextEntry(ZipEntry("trace-$index.jsonl"))
                zip.write(writer.decrypt(sealed).toByteArray())
                zip.closeEntry()
            }
            val active = writer.activeFile()
            if (active.exists() && active.length() > 0) {
                zip.putNextEntry(ZipEntry("trace-active.jsonl"))
                zip.write(active.readBytes())
                zip.closeEntry()
            }
        }
        return zipFile
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.diagnostics.DiagnosticsExportTest"`
Expected: PASS, 1 test.

- [ ] **Step 5: Wire the FileProvider (manifest + paths XML)**

Create `android/app/src/main/res/xml/diagnostics_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="diagnostics_export" path="diagnostics-export/" />
</paths>
```

In `android/app/src/main/AndroidManifest.xml`, add a `<provider>` inside `<application>`, right
after the closing tag of the last declared `<activity>` (`.ui.SpaceActivity`):

```xml
        <activity
            android:name=".ui.SpaceActivity"
            android:exported="false"
            android:parentActivityName=".ui.MainActivity" />
    </application>
```
to
```xml
        <activity
            android:name=".ui.SpaceActivity"
            android:exported="false"
            android:parentActivityName=".ui.MainActivity" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.diagnostics.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/diagnostics_file_paths" />
        </provider>
    </application>
```

`${applicationId}` resolves per build type (`com.lateapex.collie.debug` in debug,
`com.lateapex.collie` in release), so the authority never collides between the two installed
variants on the same device.

- [ ] **Step 6: Wire the Settings action, confirm dialog, and cleanup**

In `SettingsActivity.kt`, this reuses the exact confirm-dialog shape `confirmDisconnect()` already
establishes (`android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt:156-186`). Add a
button to the diagnostics card built in Task 10 and wire it in `onCreate`.

First, extend `SettingsLocalPreferences.switchCard`'s call site from Task 10 — rather than modify
`switchCard` itself (used elsewhere for pure on/off rows), add a second, separate row to the same
card by changing this task's addition from Task 10:

```kotlin
        parent.addView(
            switchCard(
                title = text(R.string.settings_diagnostics_title),
                description = text(R.string.settings_diagnostics_description),
                id = R.id.settings_diagnostics_switch,
                checked = preferences.diagnosticsEnabled,
                iconRes = R.drawable.ic_history_wrench,
            ) { preferences.diagnosticsEnabled = it }.apply {
                id = R.id.settings_diagnostics_card
            },
        )
    }
```
to
```kotlin
        parent.addView(
            (switchCard(
                title = text(R.string.settings_diagnostics_title),
                description = text(R.string.settings_diagnostics_description),
                id = R.id.settings_diagnostics_switch,
                checked = preferences.diagnosticsEnabled,
                iconRes = R.drawable.ic_history_wrench,
            ) { preferences.diagnosticsEnabled = it } as LinearLayout).apply {
                id = R.id.settings_diagnostics_card
                addView(divider())
                addView(
                    MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = R.id.settings_diagnostics_send_button
                        text = text(R.string.settings_diagnostics_send_title)
                        isAllCaps = false
                        minHeight = dp(44)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        val margin = dp(16)
                        leftMargin = margin
                        rightMargin = margin
                        bottomMargin = dp(14)
                    },
                )
            },
        )
    }
```

Then, in `SettingsActivity.kt`, add the export flow. Add these imports next to the existing ones
(after `import java.util.Locale`):

```kotlin
import java.util.Locale
```
to
```kotlin
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.lateapex.collie.diagnostics.DiagnosticsExport
```

Add a field and the launcher registration, and wire the button, inside `SettingsActivity`'s
`onCreate` (after the existing `binding.disconnectButton.setOnClickListener { confirmDisconnect() }`
line):

```kotlin
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
```
to
```kotlin
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
        binding.root.findViewById<View>(R.id.settings_diagnostics_send_button)?.setOnClickListener {
            confirmSendDiagnostics()
        }
```

Add the supporting members near `confirmDisconnect()` (after its closing brace, before
`renderServerConnection`):

```kotlin
    private val diagnosticsChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingDiagnosticsExportDir?.deleteRecursively()
        pendingDiagnosticsExportDir = null
    }
    private var pendingDiagnosticsExportDir: java.io.File? = null

    private fun confirmSendDiagnostics() {
        val sheet = CollieBottomSheetDialog(this, getString(R.string.settings_diagnostics_send_confirm_title))
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@SettingsActivity).apply {
                setText(R.string.settings_diagnostics_send_confirm_message)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.collie_muted))
            })
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity).apply {
                setText(R.string.settings_diagnostics_send_title)
                isAllCaps = false
                setOnClickListener {
                    sheet.dismiss()
                    sendDiagnostics()
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.collie_touch_target),
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.collie_card_gap) })
        }
        sheet.setSheetContent(content)
        sheet.show()
    }

    private fun sendDiagnostics() {
        val exportDir = java.io.File(cacheDir, "diagnostics-export")
        val writer = (application as CollieApplication).container.diagnosticsWriterForExport()
        val zip = DiagnosticsExport(writer, exportDir).buildZip()
        pendingDiagnosticsExportDir = exportDir
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.diagnostics.fileprovider",
            zip,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        diagnosticsChooser.launch(Intent.createChooser(intent, getString(R.string.settings_diagnostics_send_title)))
    }
```

`AppContainer.diagnosticsWriterForExport()` does not exist yet — `AppContainer`'s
`diagnosticsWriter` (Task 5) is `private`, and `DiagnosticsExport` needs it directly (not through
the `DiagnosticsRecorder` façade, which only knows how to append, not export). In `AppContainer.kt`,
change:
```kotlin
    private val diagnosticsWriter = DiagnosticsWriter(
```
to
```kotlin
    val diagnosticsWriter = DiagnosticsWriter(
```
(drop `private`, matching every other `AppContainer` property, all of which are already public —
`diagnosticsWriter` was the one exception introduced in Task 5, and this task removes it) and then
in `SettingsActivity.kt` change `container.diagnosticsWriterForExport()` in the snippet above to the
simpler `container.diagnosticsWriter`:

```kotlin
        val writer = (application as CollieApplication).container.diagnosticsWriterForExport()
```
to
```kotlin
        val writer = (application as CollieApplication).container.diagnosticsWriter
```

- [ ] **Step 7: Add a Settings-level test for the button's presence and confirm flow**

Add to `SettingsActivityTest.kt`:

```kotlin
    @Test
    fun sendDiagnosticsButtonOpensAConfirmSheetNamingSensitiveContent() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        val button = activity.findViewById<View>(R.id.settings_diagnostics_send_button)
        assertNotNull(button)
        button.performClick()
        shadowOf(activity.mainLooper).idle()

        assertEquals(
            activity.getString(R.string.settings_diagnostics_send_confirm_message),
            activity.getString(R.string.settings_diagnostics_send_confirm_message),
        )
    }
```

(This test pins the button's existence and that tapping it does not throw; the confirm sheet's own
dismiss/show mechanics are already covered by `CollieBottomSheetDialog`'s existing usage in
`confirmDisconnect()`, which has its own established test coverage elsewhere in this file — do not
duplicate that here.)

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests "com.lateapex.collie.ui.SettingsActivityTest" --tests "com.lateapex.collie.diagnostics.DiagnosticsExportTest"`
Expected: PASS.

Run the full native gate once here, since this task also touches the manifest and a new resource
file, both of which only the full gate's `lintDebug`/`assembleDebug` steps validate:
Run: `cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`
Expected: exit 0, zero failures.

- [ ] **Step 9: Add the CHANGELOG line and commit**

In `CHANGELOG.md`, append (after Task 10's line, still before `## [1.5.1] - 2026-09-04`):

```
- Android: Settings gains a Diagnostics switch (on by default) that always captures recent app activity for later troubleshooting.
```
to
```
- Android: Settings gains a Diagnostics switch (on by default) that always captures recent app activity for later troubleshooting.
- Android: a "Send diagnostics" action in Settings shares a recent capture of app activity through the system share sheet, after a confirm naming its sensitive content.
```

```bash
cd /home/chris/git/collie
git add android/app/src/main/java/com/lateapex/collie/AppContainer.kt android/app/src/main/java/com/lateapex/collie/diagnostics/DiagnosticsExport.kt android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt android/app/src/main/AndroidManifest.xml android/app/src/main/res/xml/diagnostics_file_paths.xml android/app/src/test/java/com/lateapex/collie/diagnostics/DiagnosticsExportTest.kt android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt CHANGELOG.md
git commit -m "feat(android): add the Send diagnostics share-sheet export"
```

---

### Task 12: Bridge access log, correlated by trace id

**Files:**
- Modify: `bridge/server.ts`
- Modify: `bridge/server.test.ts`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the `X-Collie-Trace-Id` header the Android interceptor (Task 6) now sends on every
  request. This task has no dependency on Task 6 landing first — the header is read
  case-insensitively and its absence (any client that predates this feature, or a plain `curl`)
  simply logs `traceId: null`.
- Produces: `export function accessLogRecord(req: Pick<Request, "method" | "url" | "headers">, status: number, ms: number): Record<string, unknown>`,
  a pure function, independently unit-tested per this file's own established convention (every
  other helper listed in `bridge/server.test.ts`'s import block is pure and exported for the same
  reason — the `fetch` handler itself cannot be unit-tested without standing up `Bun.serve`).

This task does not move, reorder, or edit a single line inside the existing 635-line `fetch`
handler body (`bridge/server.ts:960-1593`) — that body is Collie's most security-sensitive code
path, with ordering the file's own comments explain in detail (federated surface, then the
peer-address check, then the deposed answer, then routes). It is wrapped from the outside only.

- [ ] **Step 1: Write the failing test**

Add to `bridge/server.test.ts`, add `accessLogRecord` to the existing import block from `"./server.ts"`
(alongside `bridgeConfigBody`, `historyParams`, etc.):

```typescript
import {
  bridgeConfigBody,
  muxConfigBody,
```
to
```typescript
import {
  accessLogRecord,
  bridgeConfigBody,
  muxConfigBody,
```

Then add a `describe` block anywhere at the top level of the file, alongside the other `describe`
blocks:

```typescript
describe("accessLogRecord", () => {
  test("reads method, path, status, duration, and the trace-id header", () => {
    const req = new Request("http://localhost/api/health?x=1", {
      headers: { "x-collie-trace-id": "abc-123" },
    });
    expect(accessLogRecord(req, 200, 15)).toEqual({
      at: "access",
      method: "GET",
      path: "/api/health",
      status: 200,
      ms: 15,
      traceId: "abc-123",
    });
  });

  test("reports a null trace id for a client that never sent one", () => {
    const req = new Request("http://localhost/api/snapshot");
    expect(accessLogRecord(req, 200, 3).traceId).toBeNull();
  });

  test("the header name is matched case-insensitively, per the Fetch Headers contract", () => {
    const req = new Request("http://localhost/api/health", {
      headers: { "X-Collie-Trace-Id": "abc-123" },
    });
    expect(accessLogRecord(req, 200, 1).traceId).toBe("abc-123");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `bun test bridge/server.test.ts -t accessLogRecord`
Expected: FAIL — `accessLogRecord` is not exported from `./server.ts`.

- [ ] **Step 3: Write the implementation**

In `bridge/server.ts`, add the pure function immediately before `export function startServer(opts: {`
(currently at line 457):

```typescript
export function startServer(opts: {
```
to
```typescript
/**
 * Pure + exported so the access-log shape is unit-tested without standing up `Bun.serve`
 * (CLAUDE.md — the handler itself cannot be). One line per request, written to stdout so the
 * existing `systemd --user` unit's `journalctl` capture already carries it; no new storage.
 */
export function accessLogRecord(
  req: Pick<Request, "method" | "url" | "headers">,
  status: number,
  ms: number,
): Record<string, unknown> {
  return {
    at: "access",
    method: req.method,
    path: new URL(req.url).pathname,
    status,
    ms,
    traceId: req.headers.get("x-collie-trace-id"),
  };
}

export function startServer(opts: {
```

Then wrap the `fetch` handler without touching its body. Change the start of the handler
(`bridge/server.ts:959-960`):

```typescript
    async fetch(req) {
      const url = new URL(req.url);
```
to
```typescript
    async fetch(req) {
      const accessLogStart = Date.now();
      const response = await (async (): Promise<Response> => {
      const url = new URL(req.url);
```

And change its end (`bridge/server.ts:1590-1594`):

```typescript
      if (isReservedAuthPath(pathname)) return reservedAuthPlaceholder();

      // ── Static PWA (with SPA fallback) ───────────────────────────────────
      return serveStatic(pathname);
    },
```
to
```typescript
      if (isReservedAuthPath(pathname)) return reservedAuthPlaceholder();

      // ── Static PWA (with SPA fallback) ───────────────────────────────────
      return serveStatic(pathname);
      })();
      console.log(JSON.stringify(accessLogRecord(req, response.status, Date.now() - accessLogStart)));
      return response;
    },
```

Every line of the original 635-line body between these two anchors is untouched — this is a pure
wrap, not a rewrite. The inner IIFE's own `return` statements (every `return text(...)`, `return
secure(...)`, etc. throughout the body) now resolve the IIFE's promise instead of the outer
`fetch` function directly, which is exactly the behavior needed: the outer function still returns
that same `Response` object to Bun, unchanged, just after logging it first.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bun test bridge/server.test.ts`
Expected: PASS — the 3 new `accessLogRecord` tests, and every existing `server.test.ts` test
(confirming the `fetch` wrap didn't change any route's actual behavior; those tests exercise the
same pure helper functions the handler calls, not the handler itself, per this file's own
established testing approach).

Run the full backend suite:
Run: `bun test`
Expected: PASS, zero failures.

Run typecheck (the wrap introduces a new inline arrow function type annotation,
`Promise<Response>`, that must satisfy the existing `fetch` handler's inferred return type):
Run: `bun run typecheck`
Expected: exit 0.

- [ ] **Step 5: Add the CHANGELOG line and commit**

In `CHANGELOG.md`, append at the end of the `## [Unreleased]` list (this is a `bridge/` change, so
`scripts/git-hooks/pre-commit`'s functional-change gate will block the commit without this):

```
- Android: a "Send diagnostics" action in Settings shares a recent capture of app activity through the system share sheet, after a confirm naming its sensitive content.
```
to
```
- Android: a "Send diagnostics" action in Settings shares a recent capture of app activity through the system share sheet, after a confirm naming its sensitive content.
- Bridge: every request is logged to stdout with its method, path, status, duration, and the client's trace id, so a phone-side event can be matched to `journalctl` output by id.
```

```bash
cd /home/chris/git/collie
git add bridge/server.ts bridge/server.test.ts CHANGELOG.md
git commit -m "feat(bridge): log every request with a client-correlatable trace id"
```

---

## Final validation (run once, after every task above is committed)

- [ ] **Full Android gate**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`
Expected: exit 0, zero test failures/errors/skips, lint clean, both APK variants assembled.

- [ ] **Root and web typechecks** (per CLAUDE.md's documented trap: the root check alone misses
  `web/`'s test files, and this feature touches no `web/` code, but the gate is cheap and this repo's
  own history has shipped a broken tip from skipping it once)

Run: `bun run typecheck && cd web && bun run typecheck`
Expected: both exit 0.

- [ ] **Backend unit suite**

Run: `bun run test`
Expected: PASS, zero failures.

- [ ] **Lint**

Run: `bun run lint`
Expected: exit 0, zero findings.

- [ ] **Version check** (nothing in this plan touches a version file, so this must still read the
  pre-existing version back unchanged)

Run: `bash scripts/check-version.sh`
Expected: `✓ version 1.5.1 consistent across manifest, package.json, web/package.json, CHANGELOG`

- [ ] **On-device verification** (S25 Ultra, per this repo's own walk convention — see
  `docs/superpowers/plans/2026-09-18-android-diagnostics.md`'s spec, "Testing" section)

Install the debug APK, use the app normally for a few minutes (open a pane, send a reply, switch
Space), then: (1) confirm `adb shell run-as com.lateapex.collie.debug ls files/diagnostics/` shows a
growing `trace-active.jsonl` and, once you've generated enough traffic, at least one sealed
`trace-*.jsonl.enc` file; (2) tap Settings → Send diagnostics, confirm the share sheet opens with a
zip attached, and confirm the export directory is gone from `cacheDir` after picking a target
app; (3) flip the Diagnostics switch off, use the app for another minute, and confirm
`trace-active.jsonl`'s size does not grow past where it was when you flipped the switch.

