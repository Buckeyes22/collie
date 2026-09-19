package com.lateapex.collie.diagnostics

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    open val isEnabled: Boolean
        get() = enabled()

    open fun record(category: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled()) return
        val at = clock()
        val snapshot = fields.toMap()
        executor.execute { write { encode(at, category, snapshot) } }
    }

    /**
     * Writes before returning, behind anything already queued. For the uncaught-exception
     * handler: the process dies the moment it hands off to Android's default handler, so an
     * enqueued record would never reach disk.
     */
    open fun recordNow(category: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled()) return
        val at = clock()
        val snapshot = fields.toMap()
        runCatching {
            executor.submit { write { encode(at, category, snapshot) } }.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    // An exception escaping an executor task reaches the default uncaught-exception handler, which
    // kills the process: a full disk or a Keystore failure must never take the app down with it.
    private fun write(line: () -> String) {
        runCatching { writer.appendLine(line()) }
    }

    private fun encode(at: Long, category: String, fields: Map<String, Any?>): String =
        buildJsonObject {
            put("at", JsonPrimitive(at))
            put("category", JsonPrimitive(category))
            fields.forEach { (key, value) -> put(key, value.toJsonElement()) }
        }.toString()

    private companion object {
        const val FLUSH_TIMEOUT_SECONDS = 2L
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
