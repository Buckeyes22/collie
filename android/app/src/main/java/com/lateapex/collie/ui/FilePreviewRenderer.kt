package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Xml
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.lateapex.collie.R
import com.lateapex.collie.network.RelativeFilePath
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.parser.Parser
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.xmlpull.v1.XmlPullParser
import com.caverock.androidsvg.SVG

internal data class FilePreview(
    val format: PreviewFormat,
    val text: String,
    val unsupported: List<PreviewNotice> = emptyList(),
    val svg: SvgDrawing? = null,
    val images: List<MarkdownImageRef> = emptyList(),
    val jsonRows: List<JsonPreviewRow> = emptyList(),
    val markdownBlocks: List<HistoryMarkdownBlock> = emptyList(),
    val links: List<FileLinkRef> = emptyList(),
)
internal enum class PreviewFormat(val labelResourceId: Int) {
    SOURCE(R.string.files_format_source), MARKDOWN(R.string.files_format_markdown), JSON(R.string.files_format_json),
    HTML(R.string.files_format_html), CSV(R.string.files_format_csv), TSV(R.string.files_format_tsv), SVG(R.string.files_format_svg)
}
internal enum class PreviewNotice(val resourceId: Int) {
    SOURCE_LIMIT(R.string.files_notice_source_limit), SOURCE_UNSUPPORTED(R.string.files_notice_source_unsupported),
    MARKDOWN_LIMIT(R.string.files_notice_markdown_limit), JSON_LIMIT(R.string.files_notice_json_limit),
    JSON_MALFORMED(R.string.files_notice_json_malformed), JSON_BOUNDED(R.string.files_notice_json_bounded),
    HTML_LIMIT(R.string.files_notice_html_limit), HTML_OMITTED(R.string.files_notice_html_omitted),
    HTML_ACTIVE_REMOVED(R.string.files_notice_html_active_removed), CSV_MALFORMED(R.string.files_notice_csv_malformed),
    TABLE_BOUNDED(R.string.files_notice_table_bounded), SVG_LIMIT(R.string.files_notice_svg_limit),
    SVG_REFUSED(R.string.files_notice_svg_refused)
}
internal enum class JsonContainerKind { OBJECT, ARRAY }
internal data class JsonPreviewRow(val label: String, val value: String, val depth: Int, val childCount: Int, val parent: Int?, val container: JsonContainerKind? = null)
internal data class SvgDrawing(val width: Float, val height: Float, val svg: SVG)
internal data class MarkdownImageRef(val alt: String, val path: RelativeFilePath)
internal data class FileLinkRef(val label: String, val path: RelativeFilePath, val anchor: String? = null)

/** Bounded native projections. This class never creates a browser, opens a URL, or performs I/O. */
internal object FilePreviewRenderer {
    private const val MAX_SOURCE_CHARS = 1_100_000
    private const val MAX_JSON_NODES = 5_000
    private const val MAX_JSON_DEPTH = 64
    private const val MAX_CSV_ROWS = 200
    private const val MAX_CSV_COLUMNS = 32
    private const val MAX_CELL_CHARS = 4_096
    private const val MAX_HTML_NODES = 5_000
    private const val MAX_SVG_NODES = 10_000
    private const val MAX_SVG_DEPTH = 64
    private const val MAX_SVG_PATH_SEGMENTS = 20_000
    private const val MAX_SVG_TRANSFORM_FUNCTIONS = 64
    private val SVG_EXTERNAL_REFERENCE = Regex("(?i)(data:|https?:|file:|javascript:|url\\s*\\()")
    private val SVG_TRANSFORM = Regex("(?i)(matrix|translate|scale|rotate|skewx|skewy)\\s*\\(([^)]*)\\)")
    private val SVG_NUMBER = Regex("[-+]?(?:\\d*\\.)?\\d+(?:[eE][-+]?\\d+)?")
    private val SVG_NUMERIC_LIST = Regex("[-+0-9.eE,\\s]+(?:px)?")
    private val SVG_POINTS_LIST = Regex("[-+0-9.eE,\\s]+")
    private val SVG_PATH = Regex("(?i)[MmZzLlHhVvCcSsQqTtAa0-9eE+.,\\s-]+")
    private val SVG_PATH_SEPARATORS = Regex("(?i)[MmZzLlHhVvCcSsQqTtAa,\\s-]")
    private val SVG_TRANSFORM_NUMERIC_REMAINDER = Regex("[-+0-9.eE,\\s]+")
    private const val MAX_SVG_TRANSFORMED_COORDINATE = 10_000_000.0
    private data class SvgAffine(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
        /** AndroidSVG 1.4's parser calls Matrix.preConcat for each function: current * function. */
        fun then(next: SvgAffine) = SvgAffine(
            a * next.a + c * next.b, b * next.a + d * next.b,
            a * next.c + c * next.d, b * next.c + d * next.d,
            a * next.e + c * next.f + e, b * next.e + d * next.f + f,
        )
        fun envelope(radius: Double): Double {
            val x = kotlin.math.abs(a) * radius + kotlin.math.abs(c) * radius + kotlin.math.abs(e)
            val y = kotlin.math.abs(b) * radius + kotlin.math.abs(d) * radius + kotlin.math.abs(f)
            return maxOf(x, y)
        }
        fun bounded(): Boolean = listOf(a, b, c, d, e, f).all { it.isFinite() && kotlin.math.abs(it) <= MAX_SVG_TRANSFORMED_COORDINATE }
        fun asFloatSafe() = bounded() && listOf(a, b, c, d, e, f).all { it.toFloat().isFinite() }
    }

    private data class SvgStrokeGeometry(
        val enabled: Boolean = false, val width: Double = 1.0,
        val cap: String = "butt", val join: String = "miter", val miterLimit: Double = 4.0,
    ) {
        fun radius(): Double = if (!enabled) 0.0 else width / 2.0 * maxOf(
            if (cap == "square") kotlin.math.sqrt(2.0) else 1.0,
            if (join == "miter") miterLimit else 1.0,
        )
    }

    private fun svgViewBoxTransform(width: Double, height: Double, values: List<Double>): SvgAffine {
        val (x, y, viewWidth, viewHeight) = values
        val scale = minOf(width / viewWidth, height / viewHeight)
        val dx = (width - viewWidth * scale) / 2.0 - x * scale
        val dy = (height - viewHeight * scale) / 2.0 - y * scale
        return SvgAffine(a = scale, d = scale, e = dx, f = dy)
    }

    private fun svgTransformList(value: String): SvgAffine {
        var current = SvgAffine()
        val matches = SVG_TRANSFORM.findAll(value).toList()
        if (matches.isEmpty() || value.replace(SVG_TRANSFORM, "").any { !it.isWhitespace() && it != ',' }) error("invalid SVG transform")
        if (matches.size > MAX_SVG_TRANSFORM_FUNCTIONS) error("SVG transform function budget")
        matches.forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val raw = match.groupValues[2]
            if (!raw.matches(SVG_TRANSFORM_NUMERIC_REMAINDER)) error("invalid SVG transform parameters")
            val values = SVG_NUMBER.findAll(raw).map { it.value.toDoubleOrNull() ?: error("invalid SVG scalar") }.toList()
            if (values.any { !it.isFinite() || kotlin.math.abs(it) > 100.0 }) error("SVG transform scalar limit")
            val fn = when (name) {
                "matrix" -> {
                    if (values.size != 6) error("invalid SVG matrix")
                    SvgAffine(values[0], values[1], values[2], values[3], values[4], values[5])
                }
                "translate" -> {
                    if (values.size !in 1..2) error("invalid SVG translate")
                    SvgAffine(e = values[0], f = values.getOrElse(1) { 0.0 })
                }
                "scale" -> {
                    if (values.size !in 1..2) error("invalid SVG scale")
                    SvgAffine(a = values[0], d = values.getOrElse(1) { values[0] })
                }
                "rotate" -> {
                    if (values.size != 1 && values.size != 3) error("invalid SVG rotate")
                    val radians = Math.toRadians(values[0]); val cos = kotlin.math.cos(radians); val sin = kotlin.math.sin(radians)
                    val rotation = SvgAffine(cos, sin, -sin, cos)
                    if (values.size == 1) rotation else SvgAffine(e = values[1], f = values[2]).then(rotation).then(SvgAffine(e = -values[1], f = -values[2]))
                }
                "skewx" -> {
                    if (values.size != 1 || kotlin.math.abs(values[0]) > 80.0) error("SVG skew angle limit")
                    SvgAffine(c = kotlin.math.tan(Math.toRadians(values[0])))
                }
                "skewy" -> {
                    if (values.size != 1 || kotlin.math.abs(values[0]) > 80.0) error("SVG skew angle limit")
                    SvgAffine(b = kotlin.math.tan(Math.toRadians(values[0])))
                }
                else -> error("unsupported SVG transform")
            }
            current = current.then(fn)
            if (!current.bounded()) error("SVG accumulated transform bound")
        }
        return current
    }
    private val SVG_NUMERIC_ATTRIBUTES = setOf(
        "width", "height", "viewbox", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry",
        "stroke-width", "stroke-dasharray", "stroke-dashoffset", "stroke-opacity", "opacity", "fill-opacity", "font-size",
    )

    fun project(path: String, source: String): FilePreview {
        if (source.length > MAX_SOURCE_CHARS) return FilePreview(PreviewFormat.SOURCE, source.take(MAX_SOURCE_CHARS), listOf(PreviewNotice.SOURCE_LIMIT))
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "md", "markdown", "mdown" -> markdown(source, RelativeFilePath.parse(path))
            "json", "jsonc" -> json(source)
            "html", "htm" -> html(source, RelativeFilePath.parse(path))
            "csv", "tsv" -> csv(source, path.endsWith(".tsv", true))
            "svg" -> svg(source)
            else -> FilePreview(PreviewFormat.SOURCE, source, listOf(PreviewNotice.SOURCE_UNSUPPORTED))
        }
    }

    /** Only root-contained relative image paths are eligible; remote/data/absolute references stay text. */
    fun markdownImages(source: String, documentPath: RelativeFilePath): List<MarkdownImageRef> {
        val refs = linkedMapOf<String, MarkdownImageRef>()
        val regex = Regex("!\\[([^]\\r\\n]{0,300})]\\(([^)\\r\\n]{1,4096})\\)")
        regex.findAll(source).take(21).forEach { match ->
            if (refs.size >= 20) return@forEach
            val raw = match.groupValues[2].trim()
            val path = resolveRelativeRaster(documentPath, raw) ?: return@forEach
            refs.putIfAbsent(path.value, MarkdownImageRef(match.groupValues[1].take(120), path))
        }
        return refs.values.toList()
    }

    fun markdown(source: String, documentPath: RelativeFilePath? = null): FilePreview {
        if (source.count { it == '\n' } + 1 > 2_000) {
            return FilePreview(PreviewFormat.MARKDOWN, source.take(MAX_SOURCE_CHARS), listOf(PreviewNotice.MARKDOWN_LIMIT))
        }
        val blocks = HistoryPresentation.parseMarkdown(source)
        val output = StringBuilder(minOf(source.length + 64, MAX_SOURCE_CHARS))
        fun inline(items: List<HistoryInline>): String = buildString {
            items.forEach { item -> when (item) {
                is HistoryInline.Text -> append(item.text)
                is HistoryInline.Code -> append(item.text)
                is HistoryInline.Bold -> append(inline(item.spans))
                is HistoryInline.Italic -> append(inline(item.spans))
                is HistoryInline.Link -> append(inline(item.spans)).append(" (" ).append(item.href).append(')')
            } }
        }
        blocks.take(2_000).forEach { block ->
            if (output.length >= MAX_SOURCE_CHARS) return@forEach
            when (block) {
                is HistoryMarkdownBlock.Heading -> output.append("#".repeat(block.level)).append(' ').append(inline(block.spans)).append("\n\n")
                is HistoryMarkdownBlock.Paragraph -> output.append(inline(block.spans)).append("\n\n")
                is HistoryMarkdownBlock.Code -> output.append(block.text.take(MAX_SOURCE_CHARS - output.length)).append("\n\n")
                is HistoryMarkdownBlock.Quote -> output.append("“").append(inline(block.spans)).append("”\n\n")
                is HistoryMarkdownBlock.ListBlock -> {
                    block.items.take(500).forEachIndexed { i, item -> output.append(if (block.ordered) "${i + 1}. " else "• ").append(inline(item)).append('\n') }
                    output.append('\n')
                }
                is HistoryMarkdownBlock.Table -> {
                    val rows = listOf(block.header) + block.rows.take(200)
                    rows.forEach { row -> output.append(row.joinToString("  |  ") { inline(it) }).append('\n') }
                    output.append('\n')
                }
                HistoryMarkdownBlock.Rule -> output.append("────────\n\n")
            }
        }
        val images = documentPath?.let { markdownImages(source, it) }.orEmpty()
        val links = documentPath?.let { markdownLinks(source, it) }.orEmpty()
        return FilePreview(PreviewFormat.MARKDOWN, output.toString().take(MAX_SOURCE_CHARS), images = images, markdownBlocks = blocks, links = links)
    }

    /** Iterative preorder projection: no recursive serializer and no unbounded intermediate tree. */
    fun json(source: String): FilePreview {
        if (source.length > MAX_SOURCE_CHARS || !com.lateapex.collie.network.FilesJsonBounds.accepts(source, MAX_JSON_DEPTH, MAX_JSON_NODES)) {
            return FilePreview(PreviewFormat.JSON, source.take(MAX_SOURCE_CHARS), listOf(PreviewNotice.JSON_LIMIT))
        }
        val root = try { Json.parseToJsonElement(source) } catch (_: Exception) {
            return FilePreview(PreviewFormat.JSON, source, listOf(PreviewNotice.JSON_MALFORMED))
        }
        data class Node(val value: JsonElement, val depth: Int, val label: String, val parent: Int?)
        val stack = ArrayDeque<Node>().apply { addLast(Node(root, 0, "", null)) }
        val rows = mutableListOf<JsonPreviewRow>()
        val out = StringBuilder()
        var stopped = false
        while (stack.isNotEmpty()) {
            if (rows.size >= MAX_JSON_NODES || out.length >= MAX_SOURCE_CHARS - 2_048) { stopped = true; break }
            val node = stack.removeLast()
            val value = node.value
            val children: List<Pair<String, JsonElement>> = when (value) {
                is JsonObject -> value.entries.map { it.key to it.value }
                is JsonArray -> value.mapIndexed { index, element -> "[$index]" to element }
                else -> emptyList()
            }
            val container = when (value) { is JsonObject -> JsonContainerKind.OBJECT; is JsonArray -> JsonContainerKind.ARRAY; else -> null }
            val display = if (value is JsonPrimitive) value.content.take(1_000) else ""
            val rowIndex = rows.size
            rows += JsonPreviewRow(node.label, display, node.depth, children.size, node.parent, container)
            out.append("  ".repeat(node.depth)).append(node.label.take(512)).append(": ").append(display.take(1_000)).append('\n')
            children.asReversed().forEach { (label, child) -> stack.addLast(Node(child, node.depth + 1, label, rowIndex)) }
        }
        return FilePreview(PreviewFormat.JSON, out.toString().take(MAX_SOURCE_CHARS), if (stopped) listOf(PreviewNotice.JSON_BOUNDED) else emptyList(), jsonRows = rows)
    }

    private fun parseHtmlFragment(source: String, parser: Parser): org.jsoup.nodes.Document {
        val document = org.jsoup.nodes.Document.createShell("")
        val nodes = parser.parseFragmentInput(source, document.body(), "")
        document.body().insertChildren(0, nodes)
        return document
    }

    fun html(source: String, documentPath: RelativeFilePath? = null): FilePreview {
        if (source.count { it == '<' } > MAX_HTML_NODES) {
            return FilePreview(PreviewFormat.HTML, source.take(MAX_SOURCE_CHARS), listOf(PreviewNotice.HTML_LIMIT))
        }
        val images = if (documentPath == null) emptyList() else htmlImages(source, documentPath)
        val safe = Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "code", "pre", "blockquote", "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
        val parser = Parser.htmlParser().setMaxDepth(64)
        val dirty = parseHtmlFragment(source, parser)
        // jsoup's Cleaner removes relative href values when its base URI is empty. Convert only
        // validated local references to safe sentinels, clean, then restore them for scoped native
        // existence admission. This never turns a document link into a network request.
        val localHrefSentinels = linkedMapOf<String, String>()
        if (documentPath != null) {
            dirty.select("a[href]").take(100).forEachIndexed { index, element ->
                val raw = element.attr("href").take(4096)
                if (raw.startsWith("https://collie.invalid/local-link-")) {
                    element.removeAttr("href")
                    return@forEachIndexed
                }
                if (HistoryPresentation.safeHref(raw) != null) return@forEachIndexed
                if (resolveRelativeLink(documentPath, raw) != null) {
                    val token = "https://collie.invalid/local-link-$index"
                    localHrefSentinels[token] = raw
                    element.attr("href", token)
                } else element.removeAttr("href")
            }
        }
        val cleaned = Cleaner(safe).clean(dirty)
        if (cleaned.allElements.size > MAX_HTML_NODES) return FilePreview(PreviewFormat.HTML, source.take(MAX_SOURCE_CHARS), listOf(PreviewNotice.HTML_LIMIT), images = images)
        val document = cleaned
        if (localHrefSentinels.isNotEmpty()) {
            document.select("a[href]").forEach { element ->
                localHrefSentinels[element.attr("href")]?.let { element.attr("href", it) }
            }
        }
        val out = StringBuilder()
        val stack = ArrayDeque<org.jsoup.nodes.Node>()
        document.body().childNodes().asReversed().forEach(stack::addLast)
        var nodes = 0
        var unsupported = false
        while (stack.isNotEmpty() && nodes < MAX_HTML_NODES && out.length < MAX_SOURCE_CHARS) {
            val node = stack.removeLast()
            nodes++
            when (node) {
                is org.jsoup.nodes.TextNode -> out.append(node.wholeText)
                is org.jsoup.nodes.Element -> {
                    val tag = node.normalName()
                    if (tag == "a") {
                        val href = HistoryPresentation.safeHref(node.attr("href"))
                        if (href != null) node.appendText(" ($href)")
                    }
                    if (tag in SAFE_BLOCKS) out.append('\n')
                    else if (tag !in SAFE_INLINE) unsupported = true
                    node.childNodes().asReversed().forEach(stack::addLast)
                    if (tag in SAFE_BLOCKS) out.append('\n')
                }
            }
        }
        val warnings = buildList {
            if (unsupported || stack.isNotEmpty()) add(PreviewNotice.HTML_OMITTED)
            if (Regex("(?is)<\\s*(script|style|iframe|form|object|embed|svg|img|video|audio)\\b").containsMatchIn(source)) add(PreviewNotice.HTML_ACTIVE_REMOVED)
        }
        val blocks = htmlBlocks(document.body())
        val links = documentPath?.let { htmlLinks(document.body(), it) }.orEmpty()
        return FilePreview(PreviewFormat.HTML, out.toString().replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_SOURCE_CHARS), warnings, images = images, markdownBlocks = blocks, links = links)
    }

    /** Converts only the cleaned, bounded DOM to native block models. */
    private fun htmlBlocks(body: org.jsoup.nodes.Element): List<HistoryMarkdownBlock> {
        val blocks = mutableListOf<HistoryMarkdownBlock>()
        fun spans(value: String) = listOf(HistoryInline.Text(value.take(MAX_SOURCE_CHARS)))
        fun text(element: org.jsoup.nodes.Element) = element.text().take(MAX_SOURCE_CHARS)
        fun rich(element: org.jsoup.nodes.Element): List<HistoryInline> {
            val result = mutableListOf<HistoryInline>()
            val stack = ArrayDeque<org.jsoup.nodes.Node>()
            element.childNodes().asReversed().forEach(stack::addLast)
            var visited = 0
            var chars = 0
            while (stack.isNotEmpty() && visited++ < MAX_HTML_NODES && chars < MAX_SOURCE_CHARS) {
                when (val node = stack.removeLast()) {
                    is org.jsoup.nodes.TextNode -> {
                        val part = node.wholeText.take(MAX_SOURCE_CHARS - chars)
                        if (part.isNotEmpty()) { result += HistoryInline.Text(part); chars += part.length }
                    }
                    is org.jsoup.nodes.Element -> if (node.normalName() == "a") {
                        val label = node.text().take(minOf(120, MAX_SOURCE_CHARS - chars))
                        val href = node.attr("href").take(4096)
                        if (label.isNotEmpty()) {
                            result += if (href.isNotEmpty()) HistoryInline.Link(href, listOf(HistoryInline.Text(label))) else HistoryInline.Text(label)
                            chars += label.length
                        }
                    } else node.childNodes().asReversed().forEach(stack::addLast)
                }
            }
            return result
        }
        body.children().take(500).forEach { element ->
            if (blocks.size >= 500) return@forEach
            val value = text(element)
            when (element.normalName()) {
                in setOf("h1", "h2", "h3", "h4", "h5", "h6") ->
                    blocks += HistoryMarkdownBlock.Heading(element.normalName().drop(1).toIntOrNull() ?: 1, rich(element))
                "p" -> if (value.isNotBlank()) blocks += HistoryMarkdownBlock.Paragraph(rich(element))
                "pre" -> blocks += HistoryMarkdownBlock.Code("", element.wholeText().take(MAX_SOURCE_CHARS))
                "blockquote" -> blocks += HistoryMarkdownBlock.Quote(rich(element))
                "ul", "ol" -> {
                    val items = element.children().filter { it.normalName() == "li" }.take(500).map(::rich)
                    if (items.isNotEmpty()) blocks += HistoryMarkdownBlock.ListBlock(element.normalName() == "ol", items)
                }
                "table" -> {
                    val rows = element.select("tr").take(201).map { row ->
                        row.children().filter { it.normalName() in setOf("th", "td") }.take(32).map(::rich)
                    }.filter { it.isNotEmpty() }
                    if (rows.isNotEmpty()) blocks += HistoryMarkdownBlock.Table(emptyList(), rows.first(), rows.drop(1))
                }
                "hr" -> blocks += HistoryMarkdownBlock.Rule
                else -> if (value.isNotBlank()) blocks += HistoryMarkdownBlock.Paragraph(spans(value))
            }
        }
        body.textNodes().take(500).map { it.wholeText.trim() }.filter(String::isNotBlank).take((500 - blocks.size).coerceAtLeast(0)).forEach {
            blocks += HistoryMarkdownBlock.Paragraph(spans(it))
        }
        return blocks
    }

    private fun markdownLinks(source: String, documentPath: RelativeFilePath): List<FileLinkRef> {
        val refs = linkedMapOf<String, FileLinkRef>()
        Regex("\\[([^]\\r\\n]{1,300})]\\(([^)\\r\\n]{1,4096})\\)").findAll(source).take(100).forEach { match ->
            if (match.range.first > 0 && source[match.range.first - 1] == '!') return@forEach
            val resolved = resolveRelativeLink(documentPath, match.groupValues[2]) ?: return@forEach
            refs.putIfAbsent("${resolved.first.value}#${resolved.second.orEmpty()}", FileLinkRef(match.groupValues[1].ifBlank { resolved.first.value }.take(120), resolved.first, resolved.second))
        }
        return refs.values.toList()
    }

    private fun htmlLinks(body: org.jsoup.nodes.Element, documentPath: RelativeFilePath): List<FileLinkRef> {
        val refs = linkedMapOf<String, FileLinkRef>()
        body.select("a[href]").take(100).forEach { element ->
            val raw = element.attr("href").takeIf(String::isNotBlank) ?: return@forEach
            val resolved = resolveRelativeLink(documentPath, raw) ?: return@forEach
            refs.putIfAbsent("${resolved.first.value}#${resolved.second.orEmpty()}", FileLinkRef(element.text().take(120).ifBlank { resolved.first.value }, resolved.first, resolved.second))
        }
        return refs.values.toList()
    }

    private fun htmlImages(source: String, documentPath: RelativeFilePath): List<MarkdownImageRef> {
        val refs = linkedMapOf<String, MarkdownImageRef>()
        val dirty = parseHtmlFragment(source, Parser.htmlParser().setMaxDepth(64))
        dirty.select("img[src]").take(21).forEach { element ->
            if (refs.size >= 20) return@forEach
            val raw = element.attr("src").takeIf(String::isNotBlank) ?: return@forEach
            val path = resolveRelativeRaster(documentPath, raw) ?: return@forEach
            refs.putIfAbsent(path.value, MarkdownImageRef(element.attr("alt").take(120), path))
        }
        return refs.values.toList()
    }

    private fun resolveRelativeRaster(documentPath: RelativeFilePath, raw: String): RelativeFilePath? {
        // The authenticated, bounded Files/image route sniffs response bytes. The source filename
        // is not a MIME authority and may be extensionless or misleading.
        return resolveRelative(documentPath, raw)
    }

    private fun resolveRelativeLink(documentPath: RelativeFilePath, raw: String): Pair<RelativeFilePath, String?>? {
        val value = raw.trim()
        val uri = try { java.net.URI(value.replace(" ", "%20")) } catch (_: Exception) { return null }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawUserInfo != null) return null
        val anchor = uri.fragment?.takeIf { it.length in 1..256 && it.matches(Regex("[\\p{L}\\p{N}_.:-]+")) }
        if (uri.rawFragment != null && anchor == null) return null
        val path = if (uri.path.isNullOrEmpty()) documentPath else resolveRelative(documentPath, uri.path) ?: return null
        return path to anchor
    }

    private fun resolveRelative(documentPath: RelativeFilePath, raw: String): RelativeFilePath? {
        val value = raw.trim()
        // Spaces are legal file-name characters (and common in local image names), so encode them
        // before URI parsing while leaving scheme/authority/query/fragment checks explicit below.
        val uri = try { java.net.URI(value.replace(" ", "%20")) } catch (_: Exception) { return null }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val rel = uri.path ?: return null
        if (rel.startsWith('/') || rel.startsWith('\\') || '\\' in rel || "%2f" in value.lowercase() || "%5c" in value.lowercase() || '\u0000' in rel) return null
        val parent = documentPath.value.substringBeforeLast('/', "")
        val segments = (parent.split('/').filter(String::isNotEmpty) + rel.split('/')).toMutableList()
        var escaped = false
        val normalized = mutableListOf<String>()
        segments.forEach { segment -> when (segment) {
            "", "." -> Unit
            ".." -> if (normalized.isEmpty()) escaped = true else normalized.removeAt(normalized.lastIndex)
            else -> normalized += segment
        } }
        if (escaped) return null
        val path = RelativeFilePath.parse(normalized.joinToString("/")) ?: return null
        return path
    }

    fun csv(source: String, tabs: Boolean = false): FilePreview {
        val delimiter = if (tabs) '\t' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var malformed = false
        var truncated = false
        var droppingRow = false
        var index = 0
        fun finishField() {
            if (row.size < MAX_CSV_COLUMNS) row.add(field.toString().take(MAX_CELL_CHARS)) else truncated = true
            field.setLength(0)
            afterQuote = false
        }
        fun finishRow() {
            if (!droppingRow && rows.size < MAX_CSV_ROWS) {
                finishField()
                rows.add(row.toList())
            } else truncated = true
            row = mutableListOf()
            droppingRow = false
        }
        while (index < source.length && rows.size < MAX_CSV_ROWS) {
            val c = source[index++]
            when {
                quoted && c == '"' && index < source.length && source[index] == '"' -> { field.append('"'); index++ }
                quoted && c == '"' -> { quoted = false; afterQuote = true }
                c == '"' && !quoted && !afterQuote && field.isEmpty() -> quoted = true
                c == delimiter && !quoted -> finishField()
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && index < source.length && source[index] == '\n') index++
                    finishRow()
                }
                afterQuote && c.isWhitespace() -> Unit
                afterQuote -> { malformed = true; if (field.length < MAX_CELL_CHARS) field.append(c) }
                c == '"' -> { malformed = true; if (field.length < MAX_CELL_CHARS) field.append(c) }
                else -> {
                    if (row.size >= MAX_CSV_COLUMNS) droppingRow = true
                    else if (field.length < MAX_CELL_CHARS) field.append(c) else truncated = true
                }
            }
            if (row.size >= MAX_CSV_COLUMNS && c != delimiter && c != '\n' && c != '\r') droppingRow = true
        }
        if (quoted) malformed = true
        if (row.isNotEmpty() || field.isNotEmpty() || afterQuote || source.endsWith(delimiter)) finishRow()
        if (index < source.length || rows.size >= MAX_CSV_ROWS) truncated = true
        val width = rows.maxOfOrNull { it.size }?.coerceAtMost(MAX_CSV_COLUMNS) ?: 0
        val out = rows.joinToString("\n") { cells ->
            (0 until width).joinToString("  |  ") { cells.getOrNull(it).orEmpty().replace('\n', ' ').replace('\r', ' ') }
        }
        return FilePreview(if (tabs) PreviewFormat.TSV else PreviewFormat.CSV, out, buildList {
            if (malformed) add(PreviewNotice.CSV_MALFORMED)
            if (truncated) add(PreviewNotice.TABLE_BOUNDED)
        }, markdownBlocks = rows.takeIf { it.isNotEmpty() }?.let { tableRows ->
            listOf(HistoryMarkdownBlock.Table(
                alignments = emptyList(),
                header = tableRows.first().map { listOf(HistoryInline.Text(it)) },
                rows = tableRows.drop(1).map { row -> row.map { listOf(HistoryInline.Text(it)) } },
            ))
        }.orEmpty())
    }

    fun svg(source: String): FilePreview {
        if (source.length > 1_000_000) return FilePreview(PreviewFormat.SVG, "", listOf(PreviewNotice.SVG_LIMIT))
        return try {
            require(!Regex("(?is)<!\\s*(DOCTYPE|ENTITY)|<\\?").containsMatchIn(source)) { "declarations and processing instructions are refused" }
            require(!Regex("(?is)<\\s*(script|foreignObject|image|use|style)\\b|(?:href|style)\\s*=|url\\s*\\(").containsMatchIn(source)) { "active, external, CSS, or reference content is refused" }
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(java.io.StringReader(source))
            }
            var depth = 0
            var nodes = 0
            var transformDepth = 0
            var transformFunctionCount = 0
            var rootSeen = false
            var width = 300f
            var height = 200f
            val transformAtDepth = Array(MAX_SVG_DEPTH + 1) { SvgAffine() }
            val hasTransformAtDepth = BooleanArray(MAX_SVG_DEPTH + 1)
            val strokeAtDepth = Array(MAX_SVG_DEPTH + 1) { SvgStrokeGeometry() }
            // Text layout needs inherited font metrics and glyph advances to establish geometry
            // bounds. Until those are bounded, reject text before the renderer parses it.
            val allowedTags = setOf("svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "title", "desc", "defs")
            val allowedAttributes = setOf(
                "xmlns", "width", "height", "viewbox", "preserveaspectratio", "x", "y", "x1", "x2", "y1", "y2",
                "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "fill", "fill-rule", "fill-opacity",
                "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset",
                "stroke-opacity", "stroke-miterlimit", "opacity", "font-size", "font-family", "font-weight", "text-anchor", "id",
            )
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF, XmlPullParser.PROCESSING_INSTRUCTION -> error("declarations refused")
                    XmlPullParser.START_TAG -> {
                        if (++depth > MAX_SVG_DEPTH || ++nodes > MAX_SVG_NODES || parser.attributeCount > 32) error("SVG structural budget")
                        val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                        if (tag !in allowedTags) error("unsupported SVG element")
                        var localTransform = SvgAffine()
                        var viewBoxTransform = SvgAffine()
                        hasTransformAtDepth[depth] = false
                        repeat(parser.attributeCount) { index ->
                            val name = parser.getAttributeName(index).substringAfter(':').lowercase(Locale.ROOT)
                            val value = parser.getAttributeValue(index)
                            if (name !in allowedAttributes || value.length > 100_000) error("unsupported SVG attribute")
                            if (name == "xmlns") {
                                if (value != "http://www.w3.org/2000/svg") error("unsupported SVG namespace")
                            } else if (SVG_EXTERNAL_REFERENCE.containsMatchIn(value)) error("external SVG reference")
                            if (name == "transform") {
                                transformDepth++
                                hasTransformAtDepth[depth] = true
                                // Each element owns one transform attribute regardless of functions.
                                if (transformDepth > 12) error("SVG transform nesting limit")
                                val matches = SVG_TRANSFORM.findAll(value).toList()
                                if (matches.size > MAX_SVG_TRANSFORM_FUNCTIONS - transformFunctionCount) error("SVG transform function budget")
                                transformFunctionCount += matches.size
                                localTransform = svgTransformList(value)
                            }
                            if (name in SVG_NUMERIC_ATTRIBUTES) {
                                if (!value.matches(SVG_NUMERIC_LIST)) error("invalid SVG numeric attribute")
                                val values = SVG_NUMBER.findAll(value).map { it.value.toDoubleOrNull() ?: error("invalid SVG scalar") }.toList()
                                if (values.isEmpty() || values.any { !it.isFinite() || kotlin.math.abs(it) > 1_000_000.0 }) error("SVG scalar limit")
                            }
                            if (name == "points") {
                                if (!value.matches(SVG_POINTS_LIST) || value.isBlank()) error("invalid SVG points")
                                val values = SVG_NUMBER.findAll(value).map { it.value.toDoubleOrNull() ?: error("invalid SVG point") }.toList()
                                if (values.isEmpty() || values.any { !it.isFinite() || kotlin.math.abs(it) > 1_000_000.0 }) error("SVG point scalar limit")
                            }
                            if (name == "d") {
                                if (!value.matches(SVG_PATH) || value.count { it.isLetter() } > MAX_SVG_PATH_SEGMENTS) error("SVG path limit")
                                val values = SVG_NUMBER.findAll(value).map { it.value.toDoubleOrNull() ?: error("invalid SVG path scalar") }.toList()
                                val remainder = SVG_PATH_SEPARATORS.replace(SVG_NUMBER.replace(value, ""), "")
                                if (values.any { !it.isFinite() || kotlin.math.abs(it) > 1_000_000.0 } || remainder.isNotEmpty()) error("SVG path scalar limit")
                            }
                        }
                        if (tag == "svg") {
                            if (rootSeen) error("nested SVG viewports are unsupported")
                            rootSeen = true
                            fun rootDimension(name: String, default: Float): Float {
                                val raw = parser.getAttributeValue(null, name) ?: return default
                                val numeric = raw.removeSuffix("px").toFloatOrNull() ?: error("unsupported SVG dimension")
                                if (!numeric.isFinite() || numeric <= 0f || numeric > 8192f) error("SVG dimension limit")
                                return numeric
                            }
                            width = rootDimension("width", 300f)
                            height = rootDimension("height", 200f)
                            if (width * height > 16_000_000f) error("SVG area limit")
                            parser.getAttributeValue(null, "viewBox")?.let { value ->
                                val values = SVG_NUMBER.findAll(value).map { it.value.toDouble() }.toList()
                                if (values.size != 4 || values.any { !it.isFinite() || kotlin.math.abs(it) > 1_000_000.0 } || values[2] <= 0.0 || values[3] <= 0.0) error("invalid viewBox")
                                viewBoxTransform = svgViewBoxTransform(width.toDouble(), height.toDouble(), values)
                            }
                            parser.getAttributeValue(null, "preserveAspectRatio")?.let { value ->
                                if (value.trim() !in setOf("xMidYMid meet", "xMidYMid", "defer xMidYMid meet")) error("unsupported SVG preserveAspectRatio")
                            }
                        }
                        val parent = if (depth == 1) SvgAffine() else transformAtDepth[depth - 1]
                        val world = if (depth == 1) viewBoxTransform.then(localTransform) else parent.then(localTransform)
                        if (!world.bounded() || !world.asFloatSafe()) error("SVG accumulated matrix bound")
                        transformAtDepth[depth] = world
                        // AndroidSVG1.4 inherits style and stroke Paint from its parent, including
                        // width/cap/join/miter. Bound the actual effective paint before parsing.
                        var stroke = if (depth == 1) SvgStrokeGeometry() else strokeAtDepth[depth - 1]
                        parser.getAttributeValue(null, "stroke")?.takeIf { it != "inherit" }?.let { value ->
                            stroke = stroke.copy(enabled = value.trim() != "none")
                        }
                        fun strokeNumber(name: String): Double? = parser.getAttributeValue(null, name)?.let { value ->
                            val number = value.trim().removeSuffix("px").toDoubleOrNull() ?: error("invalid SVG stroke scalar")
                            if (!number.isFinite() || number < 0.0 || number > 1_000_000.0) error("SVG stroke scalar bound")
                            number
                        }
                        strokeNumber("stroke-width")?.let { stroke = stroke.copy(width = it) }
                        parser.getAttributeValue(null, "stroke-linecap")?.trim()?.takeIf { it != "inherit" }?.let {
                            if (it !in setOf("butt", "round", "square")) error("unsupported SVG stroke cap")
                            stroke = stroke.copy(cap = it)
                        }
                        parser.getAttributeValue(null, "stroke-linejoin")?.trim()?.takeIf { it != "inherit" }?.let {
                            if (it !in setOf("miter", "round", "bevel")) error("unsupported SVG stroke join")
                            stroke = stroke.copy(join = it)
                        }
                        strokeNumber("stroke-miterlimit")?.let {
                            if (it < 1.0) error("SVG stroke miter bound")
                            stroke = stroke.copy(miterLimit = it)
                        }
                        strokeAtDepth[depth] = stroke
                        val coordinates = (0 until parser.attributeCount).sumOf { index ->
                            val attr = parser.getAttributeName(index).lowercase(Locale.ROOT)
                            if (attr in setOf("d", "points", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "width", "height", "stroke-width")) {
                                SVG_NUMBER.findAll(parser.getAttributeValue(index)).sumOf { it.value.toDoubleOrNull()?.let { value -> kotlin.math.abs(value) } ?: 0.0 }
                            } else 0.0
                        }
                        val paintRadius = if (tag in setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")) stroke.radius() else 0.0
                        val renderedRadius = coordinates + paintRadius
                        if (coordinates > 1_000_000_000.0 || !renderedRadius.isFinite() ||
                            world.envelope(renderedRadius) > MAX_SVG_TRANSFORMED_COORDINATE) error("SVG transformed geometry bound")
                    }
                    XmlPullParser.END_TAG -> {
                        if (hasTransformAtDepth[depth]) transformDepth--
                        depth--
                    }
                    XmlPullParser.TEXT -> if (parser.text.length > 100_000) error("SVG text limit")
                }
            }
            if (!rootSeen || depth != 0) error("invalid SVG document")
            SVG.setInternalEntitiesEnabled(false)
            val parsed = SVG.getFromString(source)
            FilePreview(PreviewFormat.SVG, "", emptyList(), SvgDrawing(width, height, parsed))
        } catch (_: Exception) {
            FilePreview(PreviewFormat.SVG, "", listOf(PreviewNotice.SVG_REFUSED))
        }
    }

    fun createSvgView(context: Context, drawing: SvgDrawing): View = SvgView(context, drawing)

    private class SvgView(context: Context, private val drawing: SvgDrawing) : View(context) {
        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private val detector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(0.5f, 8f)
                invalidate()
                return true
            }
        })
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fit = minOf(width / drawing.width.coerceAtLeast(1f), height / drawing.height.coerceAtLeast(1f))
            val s = fit * scale
            canvas.save()
            canvas.translate((width - drawing.width * s) / 2f + offsetX, (height - drawing.height * s) / 2f + offsetY)
            canvas.scale(s, s)
            drawing.svg.renderToCanvas(canvas, RectF(0f, 0f, drawing.width, drawing.height))
            canvas.restore()
        }
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                android.view.MotionEvent.ACTION_MOVE -> if (!detector.isInProgress && event.pointerCount == 1) {
                    offsetX = (offsetX + event.x - lastX).coerceIn(-width.toFloat(), width.toFloat())
                    offsetY = (offsetY + event.y - lastY).coerceIn(-height.toFloat(), height.toFloat())
                    lastX = event.x; lastY = event.y; invalidate()
                }
            }
            return true
        }
    }

    private val SAFE_BLOCKS = setOf("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "li", "tr")
    private val SAFE_INLINE = setOf("body", "html", "strong", "b", "em", "i", "code", "a", "ul", "ol", "table", "thead", "tbody", "th", "td")
    private val FORBIDDEN_SVG_ATTRS = setOf("style", "filter", "mask", "clip-path", "class")
}
