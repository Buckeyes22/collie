package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

sealed interface FilesTarget {
    val id: String
    data class Pane(override val id: String) : FilesTarget
    data class Workspace(override val id: String) : FilesTarget
}

/** A path is relative to the server-selected Files root; callers cannot construct a root. */
@JvmInline
value class RelativeFilePath private constructor(val value: String) {
    companion object {
        fun parse(value: String, allowRoot: Boolean = false): RelativeFilePath? {
            if (value.isEmpty()) return if (allowRoot) RelativeFilePath("") else null
            if (value.toByteArray(Charsets.UTF_8).size > 4096 || value.startsWith('/') || '\\' in value || '\u0000' in value) return null
            val segments = value.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || it.equals(".git", ignoreCase = true) }) return null
            return RelativeFilePath(segments.joinToString("/"))
        }
    }
}

enum class FileEntryKind { DIRECTORY, FILE, LINK }
data class FileEntry(val name: String, val kind: FileEntryKind, val size: Long?, val ignored: Boolean)

sealed interface FilesAnswer {
    data class Unavailable(val reason: String) : FilesAnswer
    data class Listing(val root: String, val directory: String, val entries: List<FileEntry>, val truncated: Boolean) : FilesAnswer
    data class Source(val root: String, val path: String, val size: Long, val mtimeMs: Double?, val binary: Boolean, val truncated: Boolean, val text: String) : FilesAnswer
}

data class FilesExistAnswer(val exists: Set<String>)
data class FilesImageBlob(val contentType: String?, val bytes: ByteArray, val size: Long?, val mtimeMs: Double?) {
    init { require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) }
    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}

@Serializable
data class FilesExistRequest(val paths: List<String>)

internal object FilesWire {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val MAX_TEXT_CHARS = 1_100_000
    private const val MAX_SIZE = 9_007_199_254_740_991L

    fun answer(element: JsonElement, target: FilesTarget, expectedPath: RelativeFilePath?, expectedDir: RelativeFilePath?): FilesAnswer? {
        val obj = element as? JsonObject ?: return null
        if (!identityMatches(obj, target)) return null
        val available = obj.bool("available") ?: return null
        if (!available) {
            val reason = obj.string("reason", 32)?.takeIf { it in setOf("no-pane", "no-workspace", "no-folder") } ?: return null
            return FilesAnswer.Unavailable(reason)
        }
        val root = obj.string("root", 4096)?.takeIf(String::isNotBlank) ?: return null
        if (expectedPath != null) {
            if (obj.string("path", 4096) != expectedPath.value) return null
            val size = obj.number("size")?.takeIf { it in 0..MAX_SIZE } ?: return null
            val mtime = obj.optionalDouble("mtimeMs")?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_SIZE.toDouble() }
            val binary = obj.bool("binary") ?: return null
            val truncated = obj.bool("truncated") ?: return null
            val text = obj.string("text", MAX_TEXT_CHARS) ?: return null
            if (size > MAX_FILE_BYTES && !binary && !truncated) return null
            if (binary && text.isNotEmpty()) return null
            if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES + 4) return null
            return FilesAnswer.Source(root, expectedPath.value, size, mtime, binary, truncated, text)
        }
        val dir = expectedDir ?: return null
        if (obj.string("dir", 4096) != dir.value) return null
        val raw = obj.array("entries", MAX_ENTRIES) ?: return null
        val entries = raw.map { parseEntry(it) ?: return null }
        if (entries.map { it.name }.toSet().size != entries.size) return null
        val truncated = obj.bool("truncated") ?: return null
        return FilesAnswer.Listing(root, dir.value, entries, truncated)
    }

    fun exists(element: JsonElement, requested: List<RelativeFilePath>): FilesExistAnswer? {
        val obj = element as? JsonObject ?: return null
        val values = obj.array("exists", requested.size) ?: return null
        val allowed = requested.map { it.value }.toSet()
        val found = values.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null }
        if (found.any { it !in allowed } || found.size != found.toSet().size) return null
        return FilesExistAnswer(found.toSet())
    }

    private fun identityMatches(obj: JsonObject, target: FilesTarget): Boolean = when (target) {
        is FilesTarget.Pane -> obj.string("paneId", 4096) == target.id
        is FilesTarget.Workspace -> obj.string("workspaceId", 4096) == target.id && "paneId" !in obj
    }

    private fun parseEntry(element: JsonElement): FileEntry? {
        val obj = element as? JsonObject ?: return null
        val name = obj.string("name", 4096)?.takeIf(String::isNotBlank) ?: return null
        if ('/' in name || '\\' in name || name == "." || name == ".." || name.equals(".git", true)) return null
        val kind = when (obj.string("kind", 8)) {
            "dir" -> FileEntryKind.DIRECTORY
            "file" -> FileEntryKind.FILE
            "link" -> FileEntryKind.LINK
            else -> return null
        }
        val size = obj.optionalNumber("size")
        if (size != null && (size !in 0..MAX_SIZE || kind != FileEntryKind.FILE)) return null
        val ignored = if ("ignored" !in obj) false else obj.bool("ignored") ?: return null
        if (ignored && kind == FileEntryKind.DIRECTORY) { /* valid: git can ignore directories */ }
        return FileEntry(name, kind, size, ignored)
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.optionalNumber(key: String): Long? = if (key !in this || this[key] == JsonNull) null else number(key)
    private fun JsonObject.optionalDouble(key: String): Double? = if (key !in this || this[key] == JsonNull) null else (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.string(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf { it.length <= max }
    private fun JsonObject.array(key: String, max: Int): List<JsonElement>? = (this[key] as? JsonArray)?.takeIf { it.size <= max }
    private fun Boolean?.orFalse() = this == true
}

/** Lexical preflight performed on bounded response text before kotlinx.serialization builds a tree. */
internal object FilesJsonBounds {
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 30_000

    fun accepts(text: String, maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): Boolean {
        var depth = 0
        var nodes = 0
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> { inString = false; nodes++ }
                    else -> Unit
                }
                if (nodes > maxNodes) return false
                index++
                continue
            }
            when (c) {
                '"' -> { inString = true; escaped = false }
                '{', '[' -> { depth++; nodes++; if (depth > maxDepth || nodes > maxNodes) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
                ',', ':' -> Unit
                ' ', '\t', '\r', '\n' -> Unit
                else -> if (index == 0 || text[index - 1].isWhitespace() || text[index - 1] in "[:,{") {
                    nodes++
                    if (nodes > maxNodes) return false
                }
            }
            index++
        }
        return !inString && depth == 0 && nodes <= maxNodes
    }
}
