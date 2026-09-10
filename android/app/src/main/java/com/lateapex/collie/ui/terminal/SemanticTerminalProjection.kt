package com.lateapex.collie.ui.terminal

import android.text.SpannableStringBuilder

/** Removes only the exact detected line region while retaining ANSI-derived spans in raw output. */
object SemanticTerminalProjection {
    fun project(parsed: CharSequence, surface: SemanticSurface?, rawMode: Boolean): CharSequence {
        if (rawMode || surface == null) return parsed
        val starts = mutableListOf(0)
        parsed.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
        val start = starts.getOrNull(surface.startLine) ?: return parsed
        val end = starts.getOrNull(surface.endLine + 1) ?: parsed.length
        if (start > end) return parsed
        return SpannableStringBuilder(parsed).delete(start, end)
    }
}
