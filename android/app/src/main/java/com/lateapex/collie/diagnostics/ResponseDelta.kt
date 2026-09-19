package com.lateapex.collie.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What of a polled answer is new since the last answer from the same URL — so the trace holds each
 * fact once instead of the same page every 2 seconds (S25 Ultra, 2026-09-18: 23 MB in 20 minutes,
 * 80% of it one transcript page).
 *
 *  - A transcript page (`entries` keyed by `uuid`) keeps only entries not seen, or changed, since.
 *  - Any other JSON is compared without its clocks ([CLOCK_KEYS] move on every poll) and recorded
 *    as a patch of the fields that changed; a long multi-line string (the pane's screen text) is
 *    patched by lines, allowing for the screen having scrolled.
 *  - A full copy is recorded first, on a status change, when a patch would not be smaller, and
 *    every [keyframeEveryMs], so a patch never depends on a copy older than the trace keeps.
 *
 * Applying a patch: for `set`/`remove`, the JSON pointer [path]; for `lines`, take the old lines,
 * drop `drop` from the top, and the new text is its first `keep` lines + `insert` + its last
 * `keepEnd` lines.
 */
internal class ResponseDelta(
    private val clock: () -> Long = System::currentTimeMillis,
    private val keyframeEveryMs: Long = 5 * 60_000L,
) {
    class Recorded(val body: String? = null, val patch: JsonArray? = null, val entriesUnchanged: Int? = null)

    private class Seen(
        val status: Int,
        val json: JsonElement?,
        val digest: String?,
        val entries: Map<String, String>?,
        val keyframeAt: Long,
    )

    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?) = size > MAX_URLS
    }

    /** What to record for this answer, or null when nothing in it is new. */
    @Synchronized
    fun of(url: String, status: Int, text: String): Recorded? {
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        val previous = seen[url]
        val now = clock()
        val page = json as? JsonObject
        val entries = (page?.get("entries") as? JsonArray)
            ?.takeIf { list -> list.isNotEmpty() && list.all { (it as? JsonObject)?.get("uuid") is JsonPrimitive } }
        if (page != null && entries != null) {
            val current = entries.associate { uuidOf(it) to digest(it.toString()) }
            val rest = digest("$status\n" + JsonObject(page - "entries").toString())
            seen[url] = Seen(status, null, rest, current, now)
            val fresh = entries.filter { previous?.entries?.get(uuidOf(it)) != current[uuidOf(it)] }
            if (fresh.isEmpty() && previous?.digest == rest) return null
            return Recorded(
                body = JsonObject(page + ("entries" to JsonArray(fresh))).toString(),
                entriesUnchanged = entries.size - fresh.size,
            )
        }
        if (json == null) {
            val digest = digest("$status\n$text")
            seen[url] = Seen(status, null, digest, null, now)
            return if (previous?.digest == digest) null else Recorded(body = text)
        }
        val stripped = withoutClocks(json)
        if (previous?.json == stripped && previous.status == status) return null
        val due = previous?.json == null || previous.status != status || now - previous.keyframeAt >= keyframeEveryMs
        if (!due) {
            val patch = JsonArray(mutableListOf<JsonElement>().also { diff(previous!!.json!!, stripped, "", it) })
            if (patch.toString().length < text.length) {
                seen[url] = Seen(status, stripped, null, null, previous.keyframeAt)
                return Recorded(patch = patch)
            }
        }
        seen[url] = Seen(status, stripped, null, null, now)
        return Recorded(body = text)
    }

    private fun diff(old: JsonElement, new: JsonElement, path: String, out: MutableList<JsonElement>) {
        if (old == new) return
        when {
            old is JsonObject && new is JsonObject -> {
                (old.keys - new.keys).forEach { key ->
                    out += buildJsonObject { put("op", "remove"); put("path", "$path/${pointer(key)}") }
                }
                new.forEach { (key, value) ->
                    val before = old[key]
                    if (before == null) out += set("$path/${pointer(key)}", value) else diff(before, value, "$path/${pointer(key)}", out)
                }
            }
            old is JsonArray && new is JsonArray && old.size == new.size ->
                new.forEachIndexed { index, value -> diff(old[index], value, "$path/$index", out) }
            old is JsonPrimitive && new is JsonPrimitive && old.isString && new.isString &&
                new.content.count { it == '\n' } >= MIN_PATCHED_LINES -> out += lines(path, old.content, new.content)
            else -> out += set(path, new)
        }
    }

    /** The smallest change block, trying the screen as it was and as scrolled up by up to [MAX_SCROLL] lines. */
    private fun lines(path: String, oldText: String, newText: String): JsonObject {
        val old = oldText.split('\n')
        val new = newText.split('\n')
        var best: IntArray? = null
        for (drop in 0..minOf(MAX_SCROLL, old.size)) {
            if (drop > 0 && new.firstOrNull() != old.getOrNull(drop)) continue
            val shifted = old.subList(drop, old.size)
            var keep = 0
            while (keep < shifted.size && keep < new.size && shifted[keep] == new[keep]) keep++
            var keepEnd = 0
            while (keepEnd < shifted.size - keep && keepEnd < new.size - keep &&
                shifted[shifted.size - 1 - keepEnd] == new[new.size - 1 - keepEnd]
            ) keepEnd++
            val inserted = new.size - keep - keepEnd
            if (best == null || inserted < best[3]) best = intArrayOf(drop, keep, keepEnd, inserted)
        }
        val (drop, keep, keepEnd) = best!!
        return buildJsonObject {
            put("op", "lines")
            put("path", path)
            put("drop", drop)
            put("keep", keep)
            put("insert", JsonArray(new.subList(keep, new.size - keepEnd).map(::JsonPrimitive)))
            put("keepEnd", keepEnd)
        }
    }

    private fun set(path: String, value: JsonElement) = buildJsonObject {
        put("op", "set")
        put("path", path)
        put("value", value)
    }

    private fun withoutClocks(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it !in CLOCK_KEYS }.mapValues { withoutClocks(it.value) })
        is JsonArray -> JsonArray(element.map(::withoutClocks))
        else -> element
    }

    private fun uuidOf(entry: JsonElement) = (entry as JsonObject).getValue("uuid").jsonPrimitive.content

    private fun pointer(key: String) = key.replace("~", "~0").replace("/", "~1")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val CLOCK_KEYS = setOf("ts", "lastSeenAt")
        const val MAX_URLS = 16
        const val MIN_PATCHED_LINES = 8
        const val MAX_SCROLL = 60
    }
}
