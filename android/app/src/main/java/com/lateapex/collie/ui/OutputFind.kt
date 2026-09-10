package com.lateapex.collie.ui

/** Pure literal search used by the pane mirror. It never interprets terminal text as commands. */
internal object OutputFind {
    data class Match(val start: Int, val endExclusive: Int)

    fun matches(text: CharSequence, query: String, limit: Int = MAX_MATCHES): List<Match> {
        if (query.isBlank() || text.isEmpty() || limit <= 0) return emptyList()
        val haystack = text.toString()
        val found = mutableListOf<Match>()
        var from = 0
        while (from <= haystack.length - query.length && found.size < limit) {
            val at = haystack.indexOf(query, startIndex = from, ignoreCase = true)
            if (at < 0) break
            found += Match(at, at + query.length)
            from = at + query.length.coerceAtLeast(1)
        }
        return found
    }

    fun step(size: Int, current: Int, delta: Int): Int {
        if (size <= 0) return -1
        val base = if (current in 0 until size) current else if (delta < 0) 0 else -1
        return Math.floorMod(base + delta, size)
    }

    private const val MAX_MATCHES = 5_000
}
