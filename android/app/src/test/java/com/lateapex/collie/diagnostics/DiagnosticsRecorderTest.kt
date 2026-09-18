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
