package com.lateapex.collie.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import com.lateapex.collie.network.RelativeFilePath
import java.util.Locale

internal data class PrintedFileReference(val path: RelativeFilePath, val line: Int?, val start: Int, val end: Int)

/** Scans only displayed text; absolute names need a root returned by the selected Files endpoint. */
internal object PrintedPathLinks {
    internal const val MAX_SOURCE_CHARS = 250_000
    private const val MAX_CANDIDATES = 64
    private val ENDING = Regex("(?:\\.[A-Za-z0-9]{1,12}|/)(?::([1-9][0-9]{0,8}))?(?=$|[\\s,;)\\]}])")
    private val DIRECTORY_ENDING = Regex("(?<=\\S)(?=$|[\\s,;)\\]}])")
    private val ABSOLUTE_START = Regex("(?<![\\p{L}\\p{N}_])(?:[A-Za-z]:[\\\\/]|/)")
    private val ABSOLUTE_WITH_EXTENSION = Regex("(?<![\\p{L}\\p{N}_])((?:[A-Za-z]:[\\\\/]|/)[^\\r\\n<>\\\"|]{1,4096}?\\.(?:[A-Za-z0-9]{1,12}|/))(?::([1-9][0-9]{0,8}))?(?=$|[\\s,;)\\]}])")
    private val LINE_SUFFIX = Regex("(?::([1-9][0-9]{0,8}))$")

    fun extract(displayedText: String, serverRoot: String? = null): List<PrintedFileReference> {
        if (displayedText.length > MAX_SOURCE_CHARS) return emptyList()
        // Protection and admission have separate budgets. If absolute evidence exceeds the
        // protected-range budget, leave the whole text inert rather than guessing a relative
        // suffix from an absolute token beyond the first batch.
        if (ABSOLUTE_START.findAll(displayedText).take(MAX_CANDIDATES + 1).count() > MAX_CANDIDATES) return emptyList()
        val result = linkedMapOf<String, PrintedFileReference>()
        // Absolute path evidence owns its entire source range. Do not rediscover a legal suffix
        // (for example Main.kt inside /srv/repo/src/Main.kt) as a separate relative candidate.
        val absolute = absoluteCandidates(displayedText) ?: return emptyList()
        val absoluteRanges = absolute.map { it.protectedRange }
        fun overlapsAbsolute(start: Int, endExclusive: Int): Boolean =
            absoluteRanges.any { start < it.last + 1 && it.first < endExclusive }
        if (!serverRoot.isNullOrBlank()) for (candidate in absolute) {
            if (result.size >= MAX_CANDIDATES) break
            val relative = stripServerRoot(candidate.path, serverRoot) ?: continue
            val path = RelativeFilePath.parse(relative.trimEnd('/')) ?: continue
            result.putIfAbsent("${path.value}:${candidate.line}:${candidate.pathRange.first}", PrintedFileReference(path, candidate.line, candidate.pathRange.first, candidate.pathRange.last + 1))
        }
        for (quoted in QUOTED_RELATIVE.findAll(displayedText).take(MAX_CANDIDATES)) {
            if (result.size >= MAX_CANDIDATES) break
            val raw = quoted.groupValues[2]
            if (overlapsAbsolute(quoted.range.first, quoted.range.last + 1)) continue
            val suffix = LINE_SUFFIX.find(raw)
            val pathText = if (suffix == null) raw else raw.substring(0, suffix.range.first)
            val path = RelativeFilePath.parse(pathText) ?: continue
            val line = suffix?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999_999_999 }
            val start = quoted.range.first + 1
            result.putIfAbsent("${path.value}:$line:$start", PrintedFileReference(path, line, start, start + pathText.length + if (line == null) 0 else suffix.value.length))
        }
        var candidateChecks = 0
        for (ending in ENDING.findAll(displayedText).take(256)) {
            if (result.size >= MAX_CANDIDATES || candidateChecks >= 4096) break
            val line = ending.groupValues[1].toIntOrNull()?.takeIf { it in 1..999_999_999 }
            val lineSuffixLength = if (line == null) 0 else ending.groupValues[1].length + 1
            val pathEnd = ending.range.last + 1 - lineSuffixLength
            val lowerBound = maxOf(0, pathEnd - 4096)
            val starts = buildList {
                for (start in lowerBound..pathEnd) {
                    if (start == 0 || displayedText[start - 1].isWhitespace() || displayedText[start - 1] in "`\"'([{<") add(start)
                }
            }
            starts.forEach { start ->
                if (candidateChecks++ >= 4096 || result.size >= MAX_CANDIDATES) return@forEach
                if (overlapsAbsolute(start, ending.range.last + 1)) return@forEach
                val raw = displayedText.substring(start, pathEnd).trim()
                    .trimStart('`', '"', '\'', '(', '[', '{', '<')
                    .trimEnd('.', ',', ';', ')', ']', '}', '>', '`', '"', '\'')
                if (raw.isBlank() || raw.length > 4096 || raw.startsWith('/') || raw.contains('\\') || raw.contains('%') || "://" in raw || Regex("\\s+/").containsMatchIn(raw)) return@forEach
                if (raw.substringBefore(' ').lowercase(Locale.ROOT) in PROSE_PREFIXES) return@forEach
                val path = RelativeFilePath.parse(raw.trimEnd('/')) ?: return@forEach
                val pathStart = displayedText.indexOf(raw, start).takeIf { it >= start } ?: return@forEach
                val end = ending.range.last + 1
                if (pathStart < end) {
                    val reference = PrintedFileReference(path, line, pathStart, end)
                    result.putIfAbsent("${path.value}:$line:$pathStart", reference)
                }
            }
        }
        for (ending in DIRECTORY_ENDING.findAll(displayedText).take(256)) {
            if (result.size >= MAX_CANDIDATES || candidateChecks >= 4096) break
            val pathEnd = ending.range.first
            val lowerBound = maxOf(0, pathEnd - 4096)
            for (start in lowerBound..pathEnd) {
                if (start != 0 && !displayedText[start - 1].isWhitespace() && displayedText[start - 1] !in "`\"'([{<") continue
                if (candidateChecks++ >= 4096 || result.size >= MAX_CANDIDATES) break
                if (overlapsAbsolute(start, pathEnd)) continue
                val raw = displayedText.substring(start, pathEnd).trim()
                    .trimStart('`', '"', '\'', '(', '[', '{', '<')
                    .trimEnd('.', ',', ';', ')', ']', '}', '>', '`', '"', '\'')
                if ('/' !in raw || raw.length > 4096 || raw.startsWith('/') || raw.contains('\\') || raw.contains('%') || "://" in raw || Regex("\\s+/").containsMatchIn(raw)) continue
                if (raw.substringBefore(' ').lowercase(Locale.ROOT) in PROSE_PREFIXES) continue
                val path = RelativeFilePath.parse(raw.trimEnd('/')) ?: continue
                val pathStart = displayedText.indexOf(raw, start).takeIf { it >= start } ?: continue
                val reference = PrintedFileReference(path, null, pathStart, pathEnd)
                result.putIfAbsent("${path.value}:null:$pathStart", reference)
            }
        }
        return result.values.toList()
    }

    fun hasAbsoluteCandidates(displayedText: String): Boolean = displayedText.length <= MAX_SOURCE_CHARS && ABSOLUTE_START.containsMatchIn(displayedText)

    private data class AbsoluteCandidate(val path: String, val line: Int?, val pathRange: IntRange, val protectedRange: IntRange)

    private val QUOTED_RELATIVE = Regex("([`\\\"'])([^`\\\"'\\r\\n]{1,4096})\\1")

    /** Absolute spans are recognized before relative candidates, including extensionless names. */
    private fun absoluteCandidates(text: String): List<AbsoluteCandidate>? {
        // If an absolute span cannot be protected in full, fail this entire bounded source
        // closed. Never feed its truncated suffix to the separate relative-name scanner.
        for (startMatch in ABSOLUTE_START.findAll(text)) {
            val start = startMatch.range.first
            val opening = text.getOrNull(start - 1)?.takeIf { it in "`\"'" }
            val end = if (opening != null) {
                val close = text.indexOf(opening, start)
                if (close < 0 || text.substring(start, close).any { it == '\n' || it == '\r' }) return null
                close
            } else {
                var next = start
                while (next < text.length && !text[next].isWhitespace() && text[next] !in ",;)]}>|`\"'") next++
                next
            }
            if (end - start > 4096) return null
            // The ordinary absolute regex also admits spaces up to an extension. Check that
            // complete extent independently so a short first word cannot hide an overlong path.
            val ending = ENDING.find(text, start + startMatch.value.length)
            if (ending != null && ending.range.last + 1 - start > 4096) {
                val between = text.substring(start, ending.range.last + 1)
                if (between.none { it in "\r\n<>\"|" }) return null
            }
        }
        val result = mutableListOf<AbsoluteCandidate>()
        for (match in ABSOLUTE_WITH_EXTENSION.findAll(text).take(MAX_CANDIDATES)) {
            val path = match.groupValues[1].trimEnd('.', ',', ';', ')', ']', '}')
            if (path.length > 4096) return null
            val line = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..999_999_999 }
            val pathStart = match.range.first
            result += AbsoluteCandidate(path, line, pathStart until pathStart + path.length, match.range)
        }
        for (startMatch in ABSOLUTE_START.findAll(text).take(MAX_CANDIDATES + 1)) {
            if (result.size >= MAX_CANDIDATES) break
            val prefix = startMatch.range.first
            val start = startMatch.range.first
            if (result.any { start in it.protectedRange }) continue
            val opening = if (start > 0 && text[start - 1] in setOf('`', '\"', '\'')) text[start - 1] else null
            val tokenEnd = if (opening != null) {
                val close = text.indexOf(opening, start).takeIf { it >= 0 && text.substring(start, it).none { ch -> ch == '\n' || ch == '\r' } } ?: continue
                close
            } else {
                var end = start
                while (end < text.length && !text[end].isWhitespace() && text[end] !in ",;)]}>|`\"'") end++
                end
            }
            if (tokenEnd <= start || tokenEnd - start > 4096) continue
            var raw = text.substring(start, tokenEnd).trimEnd('.', ',', ';', ')', ']', '}')
            val suffix = LINE_SUFFIX.find(raw)
            val line = suffix?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999_999_999 }
            if (suffix != null) raw = raw.substring(0, suffix.range.first)
            val protectedStart = if (opening != null) prefix else start
            val protectedEnd = if (opening != null) tokenEnd else tokenEnd
            result += AbsoluteCandidate(raw, line, start until start + raw.length, protectedStart until (protectedEnd + if (opening != null) 1 else 0))
        }
        return result
    }

    private fun stripServerRoot(absolute: String, serverRoot: String): String? {
        val root = serverRoot.trimEnd('/', '\\')
        if (root.isEmpty()) return null
        val win = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(root)
        val pathValue = if (win) absolute.replace('\\', '/') else absolute
        val rootValue = if (win) root.replace('\\', '/') else root
        val prefix = "$rootValue/"
        val matches = if (win) pathValue.startsWith(prefix, ignoreCase = true) else pathValue.startsWith(prefix)
        return if (matches) pathValue.substring(prefix.length) else null
    }

    private val PROSE_PREFIXES = setOf("open", "see", "edit", "read", "file", "path", "source", "line", "updated", "changed", "from", "into", "at", "in", "to")

    private abstract class ConfirmedPathSpan : ClickableSpan()

    /** Retire only our confirmation spans; preserve markdown links, styles and selectable text. */
    fun withoutConfirmedPaths(text: CharSequence): CharSequence = SpannableString(text).apply {
        getSpans(0, length, ConfirmedPathSpan::class.java).forEach(::removeSpan)
    }

    fun confirmedText(
        text: CharSequence,
        references: List<PrintedFileReference>,
        confirmed: Set<String>,
        onOpen: (PrintedFileReference) -> Unit,
    ): CharSequence {
        val output = SpannableString(withoutConfirmedPaths(text))
        if (confirmed.isEmpty()) return output
        val selected = references.filter { it.path.value in confirmed && it.start >= 0 && it.end <= output.length && it.start < it.end }
        // If two different existing paths explain overlapping prose, leave it plain text.
        // Choosing the longer guess could open a different legal filename than the author meant.
        val nonOverlapping = selected.filter { candidate ->
            selected.none { other ->
                other !== candidate && other.path != candidate.path && other.start < candidate.end && candidate.start < other.end
            }
        }.sortedBy { it.start }
        nonOverlapping.forEach { reference ->
                output.setSpan(object : ConfirmedPathSpan() {
                    override fun onClick(widget: View) = onOpen(reference)
                }, reference.start, reference.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        return output
    }
}
