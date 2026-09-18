package com.lateapex.collie.diagnostics

/** The narrow surface `DiagnosticsRecorder` needs from a sink — lets its own tests use an
 * in-memory fake instead of a real, file-backed `DiagnosticsWriter`. */
interface DiagnosticsAppendable {
    fun appendLine(line: String)
}
