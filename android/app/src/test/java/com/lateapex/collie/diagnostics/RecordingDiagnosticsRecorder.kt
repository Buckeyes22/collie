package com.lateapex.collie.diagnostics

/** Records every call instead of writing to disk — shared by any test that needs to assert what a
 * component asked to record, without a real file-backed writer. */
open class RecordingDiagnosticsRecorder(
    private val sink: MutableList<Pair<String, Map<String, Any?>>>,
) : DiagnosticsRecorder(NoopAppendable, { true }) {
    override fun record(category: String, fields: Map<String, Any?>) {
        synchronized(sink) { sink.add(category to fields) }
    }

    override fun recordNow(category: String, fields: Map<String, Any?>) = record(category, fields)

    private object NoopAppendable : DiagnosticsAppendable {
        override fun appendLine(line: String) = Unit
    }
}
