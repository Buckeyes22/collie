package com.lateapex.collie.ui

import com.lateapex.collie.network.TranscriptPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/** Typed, inert projection of the journal's closed ToolCall union. */
internal sealed interface ToolCallPresentation {
    data class Edit(val path: String, val added: Int?, val removed: Int?, val created: Boolean?, val diff: List<String>) : ToolCallPresentation
    data class Execute(val command: String, val description: String?, val exitCode: Int?) : ToolCallPresentation
    data class Read(val path: String, val range: List<Int>?) : ToolCallPresentation
    data class Search(val query: String, val where: String?, val hits: Int?) : ToolCallPresentation
    data class Fetch(val url: String) : ToolCallPresentation
    data class Delete(val path: String) : ToolCallPresentation
    data class Move(val path: String, val to: String?) : ToolCallPresentation
    data class Task(val agent: String, val summary: String) : ToolCallPresentation
    data class Question(val name: String, val summary: String, val questions: List<QuestionItem>, val answers: List<List<String>>?) : ToolCallPresentation
    data class Other(
        val name: String,
        val summary: String,
        val inertJson: String?,
        val projectionNodes: Int = 0,
        val projectionTruncated: Boolean = false,
    ) : ToolCallPresentation
}

internal data class BoundedJsonProjection(val text: String, val nodesVisited: Int, val truncated: Boolean)

internal data class QuestionItem(
    val header: String?,
    val question: String,
    val multiple: Boolean,
    val options: List<QuestionOption>,
)

internal data class QuestionOption(val label: String, val description: String?)

internal enum class ToolResultStatus { NONE, COMPLETED, DENIED, FAILED }

internal object StructuredToolPresentation {
    internal const val MAX_TEXT = 4_000
    internal const val MAX_JSON = 4_000
    internal const val MAX_JSON_DEPTH = 8
    internal const val MAX_JSON_NODES = 128
    private const val MAX_DIFF_HUNKS = 12
    private const val MAX_DIFF_LINES = 80
    private const val MAX_DIFF_TEXT = 8_000
    private const val MAX_QUESTIONS = 9
    private const val MAX_OPTIONS = 20
    private const val MAX_QUESTION_CHARS = 500
    private const val MAX_HEADER_CHARS = 80
    private const val MAX_OPTION_CHARS = 200
    private const val MAX_DESCRIPTION_CHARS = 500

    fun call(part: TranscriptPart): ToolCallPresentation? {
        val raw = part.call ?: return null
        val kind = raw["kind"].string() ?: return fallback(part, raw)
        return try {
            when (kind) {
                "edit" -> ToolCallPresentation.Edit(
                    raw["path"].safeText().orEmpty(), raw["added"].integer(), raw["removed"].integer(),
                    raw["created"].boolean(), diff(raw["diff"]),
                )
                "execute" -> ToolCallPresentation.Execute(
                    raw["command"].safeText().orEmpty(), raw["description"].safeText(), raw["exitCode"].integer(),
                )
                "read" -> ToolCallPresentation.Read(raw["path"].safeText().orEmpty(), intPair(raw["range"]))
                "search" -> ToolCallPresentation.Search(
                    raw["query"].safeText().orEmpty(), raw["where"].safeText(), raw["hits"].integer(),
                )
                "fetch" -> ToolCallPresentation.Fetch(raw["url"].safeText().orEmpty())
                "delete" -> ToolCallPresentation.Delete(raw["path"].safeText().orEmpty())
                "move" -> ToolCallPresentation.Move(raw["path"].safeText().orEmpty(), raw["to"].safeText())
                "task" -> ToolCallPresentation.Task(
                    raw["agent"].safeText().orEmpty(), raw["summary"].safeText().orEmpty(),
                )
                "question" -> question(part, raw)
                "other" -> other(part, raw)
                else -> fallback(part, raw)
            }
        } catch (_: RuntimeException) {
            fallback(part, raw)
        }
    }

    fun resultStatus(part: TranscriptPart): ToolResultStatus = when {
        part.result == null -> ToolResultStatus.NONE
        part.result.denied -> ToolResultStatus.DENIED
        part.result.isError -> ToolResultStatus.FAILED
        else -> ToolResultStatus.COMPLETED
    }

    private fun question(part: TranscriptPart, raw: JsonObject): ToolCallPresentation {
        val questions = (raw["questions"] as? JsonArray).orEmpty().take(MAX_QUESTIONS).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val prompt = item["question"].safeText(MAX_QUESTION_CHARS)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val options = (item["options"] as? JsonArray).orEmpty().take(MAX_OPTIONS).mapNotNull { option ->
                val optionObject = option as? JsonObject
                val label = (optionObject?.get("label") ?: option).safeText(MAX_OPTION_CHARS)?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                QuestionOption(label, optionObject?.get("description").safeText(MAX_DESCRIPTION_CHARS))
            }
            QuestionItem(
                item["header"].safeText(MAX_HEADER_CHARS), prompt,
                item["multiple"].boolean() == true || item["multiSelect"].boolean() == true,
                options,
            )
        }
        if (questions.isEmpty()) return fallback(part, raw)
        val answers = (raw["answers"] as? JsonArray)?.take(MAX_QUESTIONS)?.map { answer ->
            (answer as? JsonArray).orEmpty().take(MAX_OPTIONS).mapNotNull { it.safeText() }
        }
        return ToolCallPresentation.Question(
            raw["name"].safeText() ?: part.name.safeText().orEmpty(),
            raw["summary"].safeText() ?: part.summary.safeText().orEmpty(), questions, answers,
        )
    }

    private fun diff(value: JsonElement?): List<String> {
        var remaining = MAX_DIFF_TEXT
        val projected = ArrayList<String>()
        val hunks = value as? JsonArray ?: return projected
        var hunkIndex = 0
        while (hunkIndex < hunks.size && hunkIndex < MAX_DIFF_HUNKS && remaining > 0) {
            val hunk = hunks[hunkIndex++] as? JsonObject ?: continue
            val header = hunk["header"].safeText()
            if (header != null) {
                projected += header.take(remaining)
                remaining -= projected.last().length
            }
            val lines = hunk["lines"] as? JsonArray ?: continue
            var lineIndex = 0
            while (lineIndex < lines.size && lineIndex < MAX_DIFF_LINES && remaining > 0) {
                val line = lines[lineIndex++].safeText() ?: continue
                projected += line.take(remaining)
                remaining -= projected.last().length
            }
        }
        return projected
    }

    private fun intPair(value: JsonElement?): List<Int>? = (value as? JsonArray)?.takeIf { it.size == 2 }
        ?.map { it.integer() ?: return null }

    private fun fallback(part: TranscriptPart, raw: JsonObject): ToolCallPresentation.Other = other(part, raw)

    private fun other(part: TranscriptPart, raw: JsonObject): ToolCallPresentation.Other {
        val projection = boundedJson(raw)
        return ToolCallPresentation.Other(
            raw["name"].safeText() ?: part.name.safeText().orEmpty(),
            raw["summary"].safeText() ?: part.summary.safeText().orEmpty(),
            projection.text,
            projection.nodesVisited,
            projection.truncated,
        )
    }

    /** Iterative JSON projection: the source tree is never recursively serialized or copied. */
    internal fun boundedJson(raw: JsonObject): BoundedJsonProjection {
        val output = StringBuilder(minOf(MAX_JSON, 256))
        val stack = ArrayDeque<JsonAction>()
        stack.addLast(JsonAction.Value(raw, 0))
        var nodes = 0
        var truncated = false

        fun appendBounded(value: String) {
            val available = MAX_JSON - output.length
            if (available <= 0) {
                truncated = true
                return
            }
            if (value.length > available) truncated = true
            output.append(value, 0, minOf(value.length, available))
        }

        fun quote(value: String) {
            appendBounded("\"")
            var index = 0
            while (index < value.length && output.length < MAX_JSON) {
                val char = value[index++]
                appendBounded(
                    when (char) {
                        '"' -> "\\\""
                        '\\' -> "\\\\"
                        '\b' -> "\\b"
                        '\u000C' -> "\\f"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> if (char < ' ') "\\u%04x".format(char.code) else char.toString()
                    },
                )
            }
            if (index < value.length) truncated = true else appendBounded("\"")
        }

        while (stack.isNotEmpty() && output.length < MAX_JSON && !truncated) {
            when (val action = stack.removeLast()) {
                is JsonAction.Raw -> appendBounded(action.text)
                is JsonAction.Quoted -> quote(action.text)
                is JsonAction.Value -> {
                    if (nodes >= MAX_JSON_NODES) {
                        appendBounded("\"[node limit]\"")
                        truncated = true
                        continue
                    }
                    nodes++
                    when (val element = action.element) {
                        is JsonObject -> {
                            if (action.depth >= MAX_JSON_DEPTH && element.isNotEmpty()) {
                                appendBounded("\"[depth limit]\"")
                                truncated = true
                            } else {
                                appendBounded("{")
                                stack.addLast(JsonAction.ObjectEntries(element.entries.iterator(), action.depth, first = true))
                            }
                        }
                        is JsonArray -> {
                            if (action.depth >= MAX_JSON_DEPTH && element.isNotEmpty()) {
                                appendBounded("\"[depth limit]\"")
                                truncated = true
                            } else {
                                appendBounded("[")
                                stack.addLast(JsonAction.ArrayEntries(element.iterator(), action.depth, first = true))
                            }
                        }
                        is JsonPrimitive -> if (element.isString) quote(element.content) else appendBounded(element.content)
                        else -> appendBounded("null")
                    }
                }
                is JsonAction.ObjectEntries -> {
                    if (action.entries.hasNext()) {
                        val entry = action.entries.next()
                        stack.addLast(action.copy(first = false))
                        stack.addLast(JsonAction.Value(entry.value, action.depth + 1))
                        stack.addLast(JsonAction.Raw(":"))
                        stack.addLast(JsonAction.Quoted(entry.key))
                        if (!action.first) stack.addLast(JsonAction.Raw(","))
                    } else appendBounded("}")
                }
                is JsonAction.ArrayEntries -> {
                    if (action.entries.hasNext()) {
                        val entry = action.entries.next()
                        stack.addLast(action.copy(first = false))
                        stack.addLast(JsonAction.Value(entry, action.depth + 1))
                        if (!action.first) stack.addLast(JsonAction.Raw(","))
                    } else appendBounded("]")
                }
            }
        }
        if (stack.isNotEmpty()) truncated = true
        return BoundedJsonProjection(output.toString(), nodes, truncated)
    }

    private sealed interface JsonAction {
        data class Value(val element: JsonElement, val depth: Int) : JsonAction
        data class ObjectEntries(val entries: Iterator<Map.Entry<String, JsonElement>>, val depth: Int, val first: Boolean) : JsonAction
        data class ArrayEntries(val entries: Iterator<JsonElement>, val depth: Int, val first: Boolean) : JsonAction
        data class Raw(val text: String) : JsonAction
        data class Quoted(val text: String) : JsonAction
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.safeText(max: Int = MAX_TEXT): String? = string()?.take(max)
    private fun JsonElement?.integer(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun String?.safeText(): String? = this?.take(MAX_TEXT)
}
