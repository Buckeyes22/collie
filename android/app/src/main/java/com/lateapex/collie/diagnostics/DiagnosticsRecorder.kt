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
open class DiagnosticsRecorder(
    private val writer: DiagnosticsAppendable,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    open fun record(category: String, fields: Map<String, Any?> = emptyMap()) {
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
