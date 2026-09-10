package com.lateapex.collie.ui

import android.content.res.Resources
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal data class HistoryTurn(
    val uuid: String,
    val role: String,
    val roleLabel: String,
    val day: String,
    val time: String,
    val parts: List<HistoryPart>,
)

internal sealed interface HistoryPart {
    data class Prose(
        val text: String,
        val thinking: Boolean,
        val truncated: Boolean,
        val markdown: List<HistoryMarkdownBlock>,
    ) : HistoryPart

    data class Tool(
        val name: String,
        val summary: String,
        val result: String?,
        val resultIsError: Boolean,
        val resultTruncated: Boolean,
    ) : HistoryPart
}

internal sealed interface HistoryMarkdownBlock {
    data class Heading(val level: Int, val spans: List<HistoryInline>) : HistoryMarkdownBlock
    data class Paragraph(val spans: List<HistoryInline>) : HistoryMarkdownBlock
    data class Code(val language: String, val text: String) : HistoryMarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<List<HistoryInline>>) : HistoryMarkdownBlock
    data class Quote(val spans: List<HistoryInline>) : HistoryMarkdownBlock
    data object Rule : HistoryMarkdownBlock
    data class Table(
        val alignments: List<HistoryTableAlignment>,
        val header: List<List<HistoryInline>>,
        val rows: List<List<List<HistoryInline>>>,
    ) : HistoryMarkdownBlock
}

internal enum class HistoryTableAlignment { LEFT, CENTER, RIGHT }

internal sealed interface HistoryInline {
    data class Text(val text: String) : HistoryInline
    data class Code(val text: String) : HistoryInline
    data class Bold(val spans: List<HistoryInline>) : HistoryInline
    data class Italic(val spans: List<HistoryInline>) : HistoryInline
    data class Link(val href: String, val spans: List<HistoryInline>) : HistoryInline
}

internal enum class HistorySearchRegion { PROSE, TOOL_HEADER, TOOL_RESULT }
internal data class HistorySearchKey(
    val entryUuid: String,
    val partIndex: Int,
    val region: HistorySearchRegion,
    val blockIndex: Int = -1,
    val segmentIndex: Int = -1,
)
internal data class HistorySearchHit(val key: HistorySearchKey, val match: OutputFind.Match)

/** Safe native projection of the transcript wire model and the web view's Markdown subset. */
internal object HistoryPresentation {
    fun turn(entry: TranscriptEntry, agent: String?, resources: Resources): HistoryTurn = HistoryTurn(
        uuid = entry.uuid,
        role = entry.role,
        roleLabel = when (entry.role) {
            "user" -> resources.getString(R.string.history_role_you)
            "assistant" -> agent?.trim()?.takeIf(String::isNotEmpty)
                ?: resources.getString(R.string.history_role_agent)
            "summary" -> resources.getString(R.string.history_role_summary)
            "note" -> resources.getString(R.string.history_role_system)
            else -> entry.role.ifBlank { resources.getString(R.string.history_role_transcript) }
        },
        day = date(entry.ts),
        time = clock(entry.ts),
        parts = entry.parts.map { part(it, resources) },
    )

    fun mergeOlder(current: List<TranscriptEntry>, older: List<TranscriptEntry>): List<TranscriptEntry> {
        val seen = HashSet<String>(current.size + older.size)
        return (older + current).filter { seen.add(it.uuid) }
    }

    /** The history route pages newest-anchored; merging keeps the older entries the client holds. */
    fun mergeNewer(current: List<TranscriptEntry>, newer: List<TranscriptEntry>): List<TranscriptEntry> {
        val seen = HashSet<String>(current.size + newer.size)
        return (current + newer).filter { seen.add(it.uuid) }
    }

    fun stepUserTurn(entries: List<TranscriptEntry>, focusedUuid: String?, delta: Int): String? {
        val stops = entries.indices.filter { entries[it].role == "user" }
        if (stops.isEmpty()) return null
        val current = entries.indexOfFirst { it.uuid == focusedUuid }
        val target = if (delta < 0) {
            stops.lastOrNull { it < current } ?: stops.last()
        } else {
            stops.firstOrNull { it > current } ?: stops.first()
        }
        return entries[target].uuid
    }

    fun search(
        entries: List<TranscriptEntry>,
        query: String,
        limit: Int = 5_000,
        resources: Resources? = null,
    ): List<HistorySearchHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return buildList {
            outer@ for (entry in entries) {
                for ((partIndex, part) in entry.parts.withIndex()) {
                    fun addMatches(region: HistorySearchRegion, source: String, blockIndex: Int = -1) {
                        if (size >= limit || source.isEmpty()) return
                        val key = HistorySearchKey(entry.uuid, partIndex, region, blockIndex)
                        OutputFind.matches(source, query, limit - size).forEach { match ->
                            add(HistorySearchHit(key, match))
                        }
                    }
                    if (part.kind == "tool") {
                        addMatches(HistorySearchRegion.TOOL_HEADER, toolHeader(part, resources))
                    } else {
                        parseMarkdown(part.text.orEmpty()).forEachIndexed { blockIndex, block ->
                            if (block is HistoryMarkdownBlock.Table) {
                                (listOf(block.header) + block.rows).flatten().forEachIndexed { segmentIndex, cell ->
                                    val source = inlinePlain(cell)
                                    if (size < limit && source.isNotEmpty()) {
                                        val key = HistorySearchKey(
                                            entry.uuid,
                                            partIndex,
                                            HistorySearchRegion.PROSE,
                                            blockIndex,
                                            segmentIndex,
                                        )
                                        OutputFind.matches(source, query, limit - size).forEach { match ->
                                            add(HistorySearchHit(key, match))
                                        }
                                    }
                                }
                            } else {
                                addMatches(HistorySearchRegion.PROSE, markdownPlain(block), blockIndex)
                            }
                        }
                    }
                    if (size >= limit) break@outer
                }
            }
        }
    }

    fun toolHeader(part: TranscriptPart, resources: Resources?): String {
        val name = part.name?.trim()?.takeIf(String::isNotEmpty)
            ?: resources?.getString(R.string.history_tool)
            ?: "Tool"
        return listOf(name, part.summary.orEmpty()).filter(String::isNotEmpty).joinToString(" · ")
    }

    fun safeHref(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return try {
            val scheme = URI(value).scheme?.lowercase(Locale.ROOT)
            value.takeIf { scheme == "http" || scheme == "https" || scheme == "mailto" }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun parseMarkdown(source: String): List<HistoryMarkdownBlock> {
        val lines = source.split('\n')
        val blocks = mutableListOf<HistoryMarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val body = mutableListOf<String>()
                index++
                while (index < lines.size && FENCE.matchEntire(lines[index]) == null) body += lines[index++]
                if (index < lines.size) index++
                blocks += HistoryMarkdownBlock.Code(fence.groupValues.getOrElse(1) { "" }, body.joinToString("\n"))
                continue
            }
            if (RULE.matches(line)) {
                blocks += HistoryMarkdownBlock.Rule
                index++
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                blocks += HistoryMarkdownBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
                index++
                continue
            }
            if (QUOTE.matches(line)) {
                val quoted = mutableListOf<String>()
                while (index < lines.size) {
                    val match = QUOTE.matchEntire(lines[index]) ?: break
                    quoted += match.groupValues[1]
                    index++
                }
                blocks += HistoryMarkdownBlock.Quote(parseInline(quoted.joinToString(" ").trim()))
                continue
            }
            val firstItem = listItem(line)
            if (firstItem != null) {
                val ordered = firstItem.first
                val items = mutableListOf<List<HistoryInline>>()
                while (index < lines.size) {
                    val item = listItem(lines[index]) ?: break
                    if (item.first != ordered) break
                    items += parseInline(item.second)
                    index++
                }
                blocks += HistoryMarkdownBlock.ListBlock(ordered, items)
                continue
            }
            if (startsTable(lines, index)) {
                val header = splitTableRow(line).map(::parseInline)
                val alignments = splitTableRow(lines[index + 1]).map { cell ->
                    when {
                        cell.startsWith(":") && cell.endsWith(":") -> HistoryTableAlignment.CENTER
                        cell.endsWith(":") -> HistoryTableAlignment.RIGHT
                        else -> HistoryTableAlignment.LEFT
                    }
                }
                index += 2
                val rows = mutableListOf<List<List<HistoryInline>>>()
                while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
                    val cells = splitTableRow(lines[index]).take(header.size).map(::parseInline).toMutableList()
                    while (cells.size < header.size) cells.add(emptyList())
                    rows += cells
                    index++
                }
                blocks += HistoryMarkdownBlock.Table(alignments, header, rows)
                continue
            }
            val paragraph = mutableListOf<String>()
            while (index < lines.size && !opensBlock(lines, index)) paragraph += lines[index++].trim()
            blocks += HistoryMarkdownBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
        }
        return blocks
    }

    fun parseInline(text: String, depth: Int = 0): List<HistoryInline> {
        if (depth >= 6) return listOf(HistoryInline.Text(text))
        val spans = mutableListOf<HistoryInline>()
        var cursor = 0
        INLINE.findAll(text).forEach { match ->
            if (match.range.first > cursor) spans += HistoryInline.Text(text.substring(cursor, match.range.first))
            when {
                match.groups[2] != null -> spans += HistoryInline.Code(match.groups[2]!!.value)
                match.groups[3] != null -> spans += HistoryInline.Bold(parseInline(match.groups[3]!!.value, depth + 1))
                match.groups[4] != null -> spans += HistoryInline.Italic(parseInline(match.groups[4]!!.value, depth + 1))
                match.groups[5] != null && match.groups[6] != null -> {
                    val href = safeHref(match.groups[6]!!.value)
                    spans += if (href == null) {
                        HistoryInline.Text(match.value)
                    } else {
                        HistoryInline.Link(href, parseInline(match.groups[5]!!.value.ifEmpty { href }, depth + 1))
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) spans += HistoryInline.Text(text.substring(cursor))
        return spans.filterNot { it is HistoryInline.Text && it.text.isEmpty() }
    }

    fun markdownPlain(block: HistoryMarkdownBlock): String = when (block) {
        is HistoryMarkdownBlock.Heading -> inlinePlain(block.spans)
        is HistoryMarkdownBlock.Paragraph -> inlinePlain(block.spans)
        is HistoryMarkdownBlock.Code -> block.text
        is HistoryMarkdownBlock.ListBlock -> block.items.mapIndexed { index, item ->
            "${if (block.ordered) "${index + 1}." else "•"} ${inlinePlain(item)}"
        }.joinToString("\n")
        is HistoryMarkdownBlock.Quote -> inlinePlain(block.spans)
        HistoryMarkdownBlock.Rule -> ""
        is HistoryMarkdownBlock.Table -> buildList {
            add(block.header.joinToString(" | ") { inlinePlain(it) })
            block.rows.forEach { row -> add(row.joinToString(" | ") { inlinePlain(it) }) }
        }.joinToString("\n")
    }

    fun inlinePlain(spans: List<HistoryInline>): String = buildString {
        spans.forEach { span ->
            append(
                when (span) {
                    is HistoryInline.Text -> span.text
                    is HistoryInline.Code -> span.text
                    is HistoryInline.Bold -> inlinePlain(span.spans)
                    is HistoryInline.Italic -> inlinePlain(span.spans)
                    is HistoryInline.Link -> inlinePlain(span.spans)
                },
            )
        }
    }

    private fun part(part: TranscriptPart, resources: Resources): HistoryPart = if (part.kind == "tool") {
        HistoryPart.Tool(
            name = part.name?.trim()?.takeIf(String::isNotEmpty) ?: resources.getString(R.string.history_tool),
            summary = part.summary.orEmpty(),
            result = part.result?.text,
            resultIsError = part.result?.isError == true,
            resultTruncated = part.result?.truncated == true,
        )
    } else {
        val text = part.text.orEmpty()
        HistoryPart.Prose(text, part.kind == "thinking", part.truncated, parseMarkdown(text))
    }

    private fun date(value: String): String = format(value, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    private fun clock(value: String): String = format(value, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    private fun format(value: String, formatter: DateTimeFormatter): String = try {
        formatter.withLocale(Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    } catch (_: RuntimeException) {
        ""
    }

    private fun listItem(line: String): Pair<Boolean, String>? =
        ORDERED.matchEntire(line)?.let { true to it.groupValues[1] }
            ?: UNORDERED.matchEntire(line)?.let { false to it.groupValues[1] }

    private fun splitTableRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        line.forEach { char ->
            when {
                escaped -> { current.append(char); escaped = false }
                char == '\\' -> escaped = true
                char == '|' -> { cells += current.toString(); current.clear() }
                else -> current.append(char)
            }
        }
        cells += current.toString()
        if (cells.size > 1 && cells.first().isBlank()) cells.removeAt(0)
        if (cells.size > 1 && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
        return cells.map(String::trim)
    }

    private fun startsTable(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && '|' in lines[index] && TABLE_DELIMITER.matches(lines[index + 1]) &&
            splitTableRow(lines[index]).size == splitTableRow(lines[index + 1]).size

    private fun opensBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        return FENCE.matches(line) || RULE.matches(line) || HEADING.matches(line) || QUOTE.matches(line) ||
            listItem(line) != null || startsTable(lines, index)
    }

    private val FENCE = Regex("""\s*(?:```|~~~)\s*(\S*)""")
    private val RULE = Regex("""\s*(?:-{3,}|\*{3,}|_{3,})\s*""")
    private val HEADING = Regex("""(#{1,6})\s+(.*)""")
    private val QUOTE = Regex("""\s*>\s?(.*)""")
    private val UNORDERED = Regex("""\s*[-*+]\s+(.*)""")
    private val ORDERED = Regex("""\s*\d+[.)]\s+(.*)""")
    private val TABLE_DELIMITER = Regex("""\s*\|?(?:\s*:?-+:?\s*\|)+\s*(?::?-+:?\s*\|?)?\s*""")
    private val INLINE = Regex(
        """(`+)([^`]+?)\1|\*\*(\S(?:[^\n]*?\S)?)\*\*|\*(\S(?:[^\n*]*?\S)?)\*|\[([^\]\n]*)\]\(([^)\s]+)\)""",
    )
}
