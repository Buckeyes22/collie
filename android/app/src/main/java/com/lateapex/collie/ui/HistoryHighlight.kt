package com.lateapex.collie.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan

/** Adds search paint without discarding styling already present on transcript text. */
internal object HistoryHighlight {
    fun render(
        source: CharSequence,
        matches: List<OutputFind.Match>,
        current: Int,
        matchColour: Int,
        currentColour: Int,
    ): SpannableString = SpannableString(source).apply {
        matches.forEach { match ->
            setSpan(
                BackgroundColorSpan(matchColour),
                match.start,
                match.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        matches.getOrNull(current)?.let { match ->
            setSpan(
                BackgroundColorSpan(currentColour),
                match.start,
                match.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
