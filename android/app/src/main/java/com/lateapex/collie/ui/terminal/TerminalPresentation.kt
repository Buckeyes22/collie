package com.lateapex.collie.ui.terminal

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import kotlin.math.roundToInt

internal data class TerminalLink(val start: Int, val endExclusive: Int, val href: String)

/** Explicit-scheme terminal links. No terminal bytes are interpreted as markup. */
internal object TerminalLinks {
    private val scan = Regex("https?://[^\\s<>\"'`\\\\{}|^\\[\\]]+", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', '?', '*', '_', '~', '\'', '"', '’', '”')
    private val closers = mapOf(')' to '(', ']' to '[', '}' to '{')
    private val host = Regex("^https?://[a-z0-9]", RegexOption.IGNORE_CASE)

    fun find(text: CharSequence): List<TerminalLink> = scan.findAll(text).mapNotNull { match ->
        var value = match.value.takeWhile { it.code >= 0x20 && it.code != 0x7f }
        while (value.isNotEmpty()) {
            val last = value.last()
            val opener = closers[last]
            val trim = last in trailingPunctuation ||
                (opener != null && value.count { it == opener } < value.count { it == last })
            if (!trim) break
            value = value.dropLast(1)
        }
        value.takeIf(host::containsMatchIn)?.let {
            TerminalLink(match.range.first, match.range.first + it.length, it)
        }
    }.toList()

    fun apply(source: CharSequence): CharSequence {
        val linked = SpannableString(source)
        find(source).forEach { link ->
            linked.setSpan(URLSpan(link.href), link.start, link.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            linked.setSpan(UnderlineSpan(), link.start, link.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return linked
    }
}

/**
 * Maps the terminal's fixed dark colour space through the web mirror's light-theme
 * `invert(1) hue-rotate(180deg)` transform while retaining every non-colour span.
 */
internal object TerminalColourSpace {
    fun invertHue(colour: Int): Int {
        val r = Color.red(colour) / 255f
        val g = Color.green(colour) / 255f
        val b = Color.blue(colour) / 255f
        // CSS filter matrices are applied in order: invert first, then a 180 degree hue rotation.
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        return Color.argb(
            Color.alpha(colour),
            channel(1f + 0.574f * r - 1.430f * g - 0.144f * b),
            channel(1f - 0.426f * r - 0.430f * g - 0.144f * b),
            channel(1f - 0.426f * r - 1.430f * g + 0.856f * b),
        )
    }
}
