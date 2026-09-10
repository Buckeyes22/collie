package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.lateapex.collie.R
import java.util.Locale

/**
 * Builds the transcript turn views shared by the history reader and the pane's transcript body.
 * Builders are output-identical to the ones lifted out of [HistoryActivity]; the expansion state
 * lives in the caller's [expandedTools] set so a re-render (or a pane re-bind) restores it.
 */
internal class HistoryTurnRenderer(
    private val context: Context,
    private val agent: String,
    private val expandedTools: MutableSet<Pair<String, Int>>,
    private val onToolToggle: (uuid: String, partIndex: Int) -> Unit,
    private val registerTextTarget: (HistorySearchKey, HistoryTextTarget) -> Unit = { _, _ -> },
) {
    fun turnView(turn: HistoryTurn, showHeader: Boolean): View {
        val outer = FrameLayout(context).apply {
            setPadding(dp(1), dp(1), dp(1), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(if (turn.role == "assistant") 4 else 12),
                dp(if (turn.role == "assistant") 4 else 8),
                dp(if (turn.role == "assistant") 4 else 12),
                dp(if (turn.role == "assistant") 4 else 8),
            )
            background = when (turn.role) {
                "user" -> ContextCompat.getDrawable(context, R.drawable.bg_history_user)
                "summary", "note" -> ContextCompat.getDrawable(context, R.drawable.bg_history_note)
                else -> null
            }
            if (showHeader || turn.role == "summary" || turn.role == "note") addView(roleHeader(turn))
            turn.parts.forEachIndexed { index, part -> addView(partView(turn.uuid, index, part)) }
        }
        outer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return outer
    }

    private fun roleHeader(turn: HistoryTurn) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(4))
        addView(ImageView(context).apply {
            setImageResource(
                when (turn.role) {
                    "user" -> R.drawable.ic_history_user
                    "summary", "note" -> R.drawable.ic_history_info
                    else -> agentIcon(agent)
                },
            )
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(if (turn.role == "assistant") 16 else 14), dp(if (turn.role == "assistant") 16 else 14)).apply {
            marginEnd = dp(6)
        })
        addView(TextView(context).apply {
            text = turn.roleLabel.uppercase(Locale.ENGLISH)
            textSize = 11f
            setTextColor(color(R.color.collie_muted))
            letterSpacing = 0.025f
        })
        if (turn.time.isNotEmpty()) addView(TextView(context).apply {
            text = turn.time
            textSize = 11f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun partView(uuid: String, partIndex: Int, part: HistoryPart): View = when (part) {
        is HistoryPart.Prose -> markdownView(uuid, partIndex, part)
        is HistoryPart.Tool -> toolView(uuid, partIndex, part)
    }

    private fun markdownView(uuid: String, partIndex: Int, part: HistoryPart.Prose) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        part.markdown.forEachIndexed { blockIndex, block ->
            val view = markdownBlockView(uuid, partIndex, blockIndex, block, part.thinking)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (blockIndex > 0) topMargin = dp(8)
            })
        }
        if (part.truncated) addView(contentText(context.getString(R.string.history_part_truncated), 12f).apply {
            setTextColor(color(R.color.collie_muted))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun markdownBlockView(
        uuid: String,
        partIndex: Int,
        blockIndex: Int,
        block: HistoryMarkdownBlock,
        thinking: Boolean,
    ): View {
        if (block == HistoryMarkdownBlock.Rule) return rule()
        if (block is HistoryMarkdownBlock.Table) return markdownTableView(uuid, partIndex, blockIndex, block, thinking)
        val key = HistorySearchKey(uuid, partIndex, HistorySearchRegion.PROSE, blockIndex)
        val source = when (block) {
            is HistoryMarkdownBlock.Heading -> inlineText(block.spans)
            is HistoryMarkdownBlock.Paragraph -> inlineText(block.spans)
            is HistoryMarkdownBlock.Code -> SpannableStringBuilder(block.text)
            is HistoryMarkdownBlock.ListBlock -> SpannableStringBuilder().apply {
                block.items.forEachIndexed { index, item ->
                    if (index > 0) append('\n')
                    append(if (block.ordered) "${index + 1}. " else "• ")
                    append(inlineText(item))
                }
            }
            is HistoryMarkdownBlock.Quote -> inlineText(block.spans)
            is HistoryMarkdownBlock.Table -> error("Tables use markdownTableView")
            HistoryMarkdownBlock.Rule -> SpannableStringBuilder()
        }
        if (thinking && source.isNotEmpty()) source.setSpan(StyleSpan(Typeface.ITALIC), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (block is HistoryMarkdownBlock.Heading && source.isNotEmpty()) {
            source.setSpan(StyleSpan(Typeface.BOLD), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val fixedWidth = block is HistoryMarkdownBlock.Code
        val text = contentText(source, when (block) {
            is HistoryMarkdownBlock.Heading -> when (block.level) { 1 -> 16f; 2 -> 15f; else -> 14f }
            is HistoryMarkdownBlock.Code -> 11f
            else -> 14f
        }, monospace = fixedWidth).apply {
            if (block is HistoryMarkdownBlock.Heading) {
                ViewCompat.setAccessibilityHeading(this, true)
            }
            if (block is HistoryMarkdownBlock.Code) {
                setBackgroundResource(R.drawable.bg_history_code)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            if (block is HistoryMarkdownBlock.Quote) {
                setBackgroundResource(R.drawable.bg_history_quote)
                setPadding(dp(10), 0, 0, 0)
                setTextColor(color(R.color.collie_muted))
            }
        }
        registerHistoryText(key, text, source)
        return if (block is HistoryMarkdownBlock.Code) {
            HorizontalScrollView(context).apply {
                isFillViewport = false
                isHorizontalScrollBarEnabled = false
                addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else text
    }

    private fun markdownTableView(
        uuid: String,
        partIndex: Int,
        blockIndex: Int,
        block: HistoryMarkdownBlock.Table,
        thinking: Boolean,
    ) = HorizontalScrollView(context).apply {
        isFillViewport = false
        isHorizontalScrollBarEnabled = false
        val table = TableLayout(context).apply {
            isShrinkAllColumns = false
            isStretchAllColumns = false
        }
        var segmentIndex = 0
        fun row(cells: List<List<HistoryInline>>, header: Boolean) = TableRow(context).apply {
            cells.forEachIndexed { column, spans ->
                val source = inlineText(spans)
                if (thinking && source.isNotEmpty()) {
                    source.setSpan(StyleSpan(Typeface.ITALIC), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (header && source.isNotEmpty()) {
                    source.setSpan(StyleSpan(Typeface.BOLD), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val cell = contentText(source, 12f).apply {
                    gravity = when (block.alignments.getOrNull(column) ?: HistoryTableAlignment.LEFT) {
                        HistoryTableAlignment.LEFT -> Gravity.START or Gravity.TOP
                        HistoryTableAlignment.CENTER -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
                        HistoryTableAlignment.RIGHT -> Gravity.END or Gravity.TOP
                    }
                    setBackgroundResource(R.drawable.bg_history_table_cell)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                registerHistoryText(
                    HistorySearchKey(
                        uuid,
                        partIndex,
                        HistorySearchRegion.PROSE,
                        blockIndex,
                        segmentIndex++,
                    ),
                    cell,
                    source,
                )
                addView(cell, TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        table.addView(row(block.header, header = true))
        block.rows.forEach { table.addView(row(it, header = false)) }
        addView(table, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toolView(uuid: String, partIndex: Int, part: HistoryPart.Tool): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_history_tool)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        val result = contentText(
            buildString {
                append(part.result.orEmpty())
                if (part.resultTruncated) append("\n").append(context.getString(R.string.history_output_truncated))
            },
            11f,
            monospace = true,
        ).apply {
            setTextColor(color(if (part.resultIsError) R.color.collie_error else R.color.collie_foreground))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(color(R.color.collie_background))
            isVisible = false
        }
        val chevron = ImageView(context).apply { setImageResource(R.drawable.ic_history_chevron_right) }
        val headerText = SpannableStringBuilder(part.name).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (part.summary.isNotBlank()) {
                val start = length
                append(" · ").append(part.summary)
                setSpan(ForegroundColorSpan(color(R.color.collie_muted)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val headerLabel = contentText(headerText, 12f, monospace = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(44)
            isClickable = part.result != null
            isFocusable = part.result != null
            foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            setPadding(dp(8), 0, dp(8), 0)
            addView(ImageView(context).apply {
                setImageResource(if (part.resultIsError) R.drawable.ic_history_warning else R.drawable.ic_history_wrench)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
            addView(headerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (part.result != null) addView(chevron, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(6) })
        }
        val expansionKey = uuid to partIndex
        val expanded = expansionKey in expandedTools
        header.contentDescription = context.getString(
            if (expanded) R.string.history_tool_expanded else R.string.history_tool_collapsed,
            headerText.toString(),
        )
        result.isVisible = expanded && part.result != null
        chevron.rotation = if (expanded) 90f else 0f
        header.setOnClickListener { if (part.result != null) onToolToggle(uuid, partIndex) }
        addView(header)
        addView(result)
        registerHistoryText(HistorySearchKey(uuid, partIndex, HistorySearchRegion.TOOL_HEADER), headerLabel, headerText)
        if (part.result != null) {
            registerHistoryText(
                HistorySearchKey(uuid, partIndex, HistorySearchRegion.TOOL_RESULT),
                result,
                result.text,
            ) {
                if (expansionKey !in expandedTools) onToolToggle(uuid, partIndex)
            }
        }
    }

    private fun inlineText(spans: List<HistoryInline>): SpannableStringBuilder = SpannableStringBuilder().apply {
        spans.forEach { span ->
            val start = length
            when (span) {
                is HistoryInline.Text -> append(span.text)
                is HistoryInline.Code -> append(span.text)
                is HistoryInline.Bold -> append(inlineText(span.spans))
                is HistoryInline.Italic -> append(inlineText(span.spans))
                is HistoryInline.Link -> append(inlineText(span.spans))
            }
            val end = length
            if (end <= start) return@forEach
            when (span) {
                is HistoryInline.Code -> {
                    setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(BackgroundColorSpan(color(R.color.collie_muted_surface)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is HistoryInline.Bold -> setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                is HistoryInline.Italic -> setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                is HistoryInline.Link -> {
                    setSpan(URLSpan(span.href), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(color(R.color.collie_primary)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is HistoryInline.Text -> Unit
            }
        }
    }

    private fun contentText(value: CharSequence, size: Float, monospace: Boolean = false) =
        (if (monospace) HistoryMonospaceTextView(context) else TextView(context)).apply {
        text = value
        textSize = size
        if (!monospace) typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(color(R.color.collie_foreground))
        setLineSpacing(0f, 1.3f)
        setTextIsSelectable(true)
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun registerHistoryText(
        key: HistorySearchKey,
        view: TextView,
        source: CharSequence,
        reveal: () -> Unit = {},
    ) {
        registerTextTarget(key, HistoryTextTarget(view, source, reveal))
    }

    private fun rule() = View(context).apply { setBackgroundColor(color(R.color.collie_border)) }
    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

internal data class HistoryTextTarget(
    val view: TextView,
    val source: CharSequence,
    val reveal: () -> Unit,
)

/** Fixed-width transcript surfaces must not inherit the user-selectable chrome face. */
internal class HistoryMonospaceTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var preserveMonospace = false

    init {
        preserveMonospace = true
        super.setTypeface(Typeface.MONOSPACE)
    }

    override fun setTypeface(value: Typeface?) {
        super.setTypeface(if (preserveMonospace) Typeface.MONOSPACE else value)
    }

    override fun setTypeface(value: Typeface?, style: Int) {
        if (preserveMonospace) super.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        else super.setTypeface(value, style)
    }
}
