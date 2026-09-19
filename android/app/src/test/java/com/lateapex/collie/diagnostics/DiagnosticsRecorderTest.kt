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

    @Test
    fun aFailingWriterNeverEscapesTheRecorderThread() {
        // An exception escaping an executor task reaches the default handler, which on Android
        // kills the whole app — a full disk must not do that.
        val escaped = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped.add(e) } }
        }
        val failing = object : DiagnosticsAppendable {
            override fun appendLine(line: String) = throw java.io.IOException("No space left on device")
        }
        val recorder = DiagnosticsRecorder(failing, { true }, executor = executor)
        recorder.record("network")
        recorder.recordNow("crash")
        executor.shutdown()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(escaped.isEmpty())
    }

    @Test
    fun recordNowIsOnDiskBeforeItReturnsAndAfterEarlierRecords() {
        val lines = mutableListOf<String>()
        val recorder = DiagnosticsRecorder(FakeWriter(lines), { true })
        recorder.record("lifecycle")
        recorder.recordNow("crash", mapOf("exception" to "IllegalStateException"))

        assertEquals(2, synchronized(lines) { lines.size })
        assertTrue(lines[0].contains("\"category\":\"lifecycle\""))
        assertTrue(lines[1].contains("\"category\":\"crash\""))
    }

    private class FakeWriter(private val sink: MutableList<String>) : DiagnosticsAppendable {
        override fun appendLine(line: String) {
            synchronized(sink) { sink.add(line) }
        }
    }
}
