package com.lateapex.collie.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDeltaTest {
    private var now = 0L
    private val delta = ResponseDelta(clock = { now })

    @Test
    fun anAnswerThatOnlyMovedItsClocksIsNotRecorded() {
        assertNotNull(delta.of("/api/snapshot", 200, snapshot(ts = 1, seen = 1, status = "working")))
        assertNull(delta.of("/api/snapshot", 200, snapshot(ts = 2, seen = 2, status = "working")))
    }

    @Test
    fun aRealChangeIsRecordedAsAPatchOfJustThatField() {
        val first = snapshot(ts = 1, seen = 1, status = "working")
        val second = snapshot(ts = 2, seen = 2, status = "blocked")
        delta.of("/api/snapshot", 200, first)
        val recorded = delta.of("/api/snapshot", 200, second)!!

        assertNull(recorded.body)
        val patch = recorded.patch!!
        assertEquals(1, patch.size)
        assertEquals("/agents/0/status", patch[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(clockless(second), apply(clockless(first), patch))
    }

    @Test
    fun aScreenWhoseSpinnerTickedRecordsOnlyThatLine() {
        val before = screen((0 until 40).map { "line $it" } + "✢ Simmering… (1m 10s)" + footer)
        val after = screen((0 until 40).map { "line $it" } + "✽ Simmering… (1m 12s)" + footer)
        delta.of("/api/pane/w1", 200, before)
        val patch = delta.of("/api/pane/w1", 200, after)!!.patch!!

        val op = patch.single().jsonObject
        assertEquals("lines", op["op"]!!.jsonPrimitive.content)
        assertEquals(listOf("✽ Simmering… (1m 12s)"), op["insert"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(parse(after), apply(parse(before), patch))
    }

    @Test
    fun aScreenThatScrolledRecordsOnlyTheNewLines() {
        val before = screen((0 until 40).map { "output $it" } + footer)
        val after = screen((3 until 43).map { "output $it" } + footer)
        delta.of("/api/pane/w1", 200, before)
        val op = delta.of("/api/pane/w1", 200, after)!!.patch!!.single().jsonObject

        assertEquals(3, op["drop"]!!.jsonPrimitive.int)
        assertEquals(listOf("output 40", "output 41", "output 42"), op["insert"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(parse(after), apply(parse(before), JsonArray(listOf(op))))
    }

    @Test
    fun aFullCopyIsRecordedEveryFiveMinutesSoAPatchNeverOutlivesItsBase() {
        delta.of("/api/snapshot", 200, snapshot(1, 1, "working"))
        now = 4 * 60_000L
        assertNull(delta.of("/api/snapshot", 200, snapshot(2, 2, "blocked"))!!.body)
        now = 5 * 60_000L
        assertNotNull(delta.of("/api/snapshot", 200, snapshot(3, 3, "working"))!!.body)
    }

    @Test
    fun aStatusChangeRecordsTheFullAnswer() {
        delta.of("/api/snapshot", 200, snapshot(1, 1, "working"))
        assertNotNull(delta.of("/api/snapshot", 503, snapshot(2, 2, "blocked"))!!.body)
    }

    @Test
    fun eachUrlIsComparedOnlyWithItself() {
        delta.of("/api/pane/w1", 200, screen(listOf("same")))
        assertNotNull(delta.of("/api/pane/w2", 200, screen(listOf("same")))!!.body)
    }

    private val footer = listOf("─".repeat(20), "❯ ", "─".repeat(20), "  Opus 5 · 12%")

    private fun snapshot(ts: Int, seen: Int, status: String) =
        """{"ts":$ts,"agents":[{"paneId":"w1","status":"$status","lastSeenAt":$seen},{"paneId":"w2","status":"idle","lastSeenAt":$seen}]}"""

    private fun screen(lines: List<String>) =
        Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("paneId" to JsonPrimitive("w1"), "text" to JsonPrimitive(lines.joinToString("\n")))))

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private fun clockless(text: String): JsonElement {
        fun strip(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "ts" && it != "lastSeenAt" }.mapValues { strip(it.value) })
            is JsonArray -> JsonArray(e.map(::strip))
            else -> e
        }
        return strip(parse(text))
    }

    /** Applies a recorded patch, as whoever reads the trace would. */
    private fun apply(base: JsonElement, patch: JsonArray): JsonElement = patch.fold(base) { doc, op ->
        val o = op.jsonObject
        val path = o["path"]!!.jsonPrimitive.content.split('/').drop(1).map { it.replace("~1", "/").replace("~0", "~") }
        when (o["op"]!!.jsonPrimitive.content) {
            "set" -> at(doc, path) { o["value"] }
            "remove" -> at(doc, path) { null }
            else -> at(doc, path) { old ->
                val lines = old!!.jsonPrimitive.content.split('\n').drop(o["drop"]!!.jsonPrimitive.int)
                val keep = o["keep"]!!.jsonPrimitive.int
                val keepEnd = o["keepEnd"]!!.jsonPrimitive.int
                JsonPrimitive(
                    (lines.take(keep) + o["insert"]!!.jsonArray.map { it.jsonPrimitive.content } + lines.takeLast(keepEnd))
                        .joinToString("\n"),
                )
            }
        }
    }

    private fun at(doc: JsonElement, path: List<String>, change: (JsonElement?) -> JsonElement?): JsonElement {
        if (path.isEmpty()) return change(doc)!!
        val key = path.first()
        return when (doc) {
            is JsonObject -> {
                val updated = doc.toMutableMap()
                val next = if (path.size == 1) change(doc[key]) else at(doc[key]!!, path.drop(1), change)
                if (next == null) updated.remove(key) else updated[key] = next
                JsonObject(updated)
            }
            is JsonArray -> {
                val index = key.toInt()
                JsonArray(doc.mapIndexed { i, e -> if (i != index) e else if (path.size == 1) change(e)!! else at(e, path.drop(1), change) })
            }
            else -> error("no path $path in $doc")
        }
    }
}
