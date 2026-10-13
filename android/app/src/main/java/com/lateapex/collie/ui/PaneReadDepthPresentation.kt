package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneReadResponse

/** Text that reports the bounded window without implying whole-session or alt-screen coverage. */
internal object PaneReadDepthPresentation {
    fun explanation(
        read: PaneReadResponse?,
        desiredLines: Int,
        maximumLines: Int = 1_000,
        adapterLimitDeclared: Boolean = false,
    ): String? {
        if (read == null) return null
        val admitted = read.requestedLines ?: minOf(desiredLines, maximumLines)
        val served = read.servedLines ?: rowCount(read.text)
        return when {
            desiredLines > admitted && read.limitReason == "adapter-ceiling" ->
                "Adapter ceiling: requested $desiredLines rows; admitted $admitted; served $served."
            desiredLines > admitted && read.limitReason == "server-ceiling" ->
                "Bridge ceiling: requested $desiredLines rows; admitted $admitted; served $served."
            desiredLines > admitted && adapterLimitDeclared && admitted == maximumLines ->
                "Adapter ceiling: desired $desiredLines rows; admitted $admitted; served $served. Agent history is separate."
            desiredLines > admitted && admitted == maximumLines ->
                "Client compatibility limit: desired $desiredLines rows; admitted $admitted; served $served. Agent history is separate."
            read.limitReason == "available-depth" ->
                "The reported retained depth was reached ($served rows); separate agent history may still exist."
            served < admitted && read.readableLines != null && read.readableLines > served ->
                "The read returned $served rows; the mux-reported depth (${read.readableLines}) may predate buffer changes. Separate agent history may exist."
            served < desiredLines && read.readableLines == null ->
                "Served $served of $desiredLines requested rows; available depth is unknown."
            read.readableLines != null -> "Served $served rows; mux-reported depth is ${read.readableLines} rows."
            else -> null
        }
    }

    private fun rowCount(text: String): Int =
        if (text.isEmpty()) 0 else text.split('\n').let { it.size - if (text.endsWith('\n')) 1 else 0 }
}
