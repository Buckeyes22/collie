package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.FileEntry
import com.lateapex.collie.network.FileEntryKind
import com.lateapex.collie.network.RelativeFilePath
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class FilesActivity : AppCompatActivity() {
    private lateinit var target: FilesTarget
    private lateinit var scope: Scope
    private lateinit var viewModel: FilesViewModel
    private lateinit var list: RecyclerView
    private lateinit var sourceScroll: ScrollView
    private lateinit var sourceContent: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fileTitle: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var listingControls: LinearLayout
    private lateinit var sourceControls: LinearLayout
    private lateinit var sourceButton: Button
    private lateinit var previewButton: Button
    private lateinit var ignoredCheck: CheckBox
    private lateinit var sourceText: TextView
    private var showPreview = false
    private var lastFindOffset = 0
    private var currentSource: FilesAnswer.Source? = null
    @Volatile private var acceptedPageContext: com.lateapex.collie.data.ScopedReadContext? = null
    private var acceptedListing: FilesAnswer.Listing? = null
    private var invalidationSubscription: AutoCloseable? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var currentLine = 0
    private var currentFind = ""
    private var pendingAnchor: String? = null
    private var externalDialog: AlertDialog? = null
    private var adapter = FileListAdapter(::onEntry)
    private val repository get() = (application as CollieApplication).container.repository
    private val imageLoader get() = (application as CollieApplication).container.imageLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_TARGET_ID)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: run { finish(); return }
        target = when (intent.getStringExtra(EXTRA_TARGET_KIND)) {
            "workspace" -> FilesTarget.Workspace(id)
            "pane" -> FilesTarget.Pane(id)
            else -> { finish(); return }
        }
        scope = Scope(intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank), intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank))
        buildContent()
        val directory = if (savedInstanceState != null) savedInstanceState.getString(STATE_DIRECTORY).orEmpty() else intent.getStringExtra(EXTRA_DIRECTORY).orEmpty()
        val path = if (savedInstanceState != null) savedInstanceState.getString(STATE_PATH) else intent.getStringExtra(EXTRA_PATH)
        val openReference = if (savedInstanceState == null) intent.getStringExtra(EXTRA_REFERENCE) else null
        val initialLine = savedInstanceState?.getInt(STATE_LINE) ?: intent.getIntExtra(EXTRA_LINE, 0)
        val ignored = savedInstanceState?.getBoolean(STATE_IGNORED) ?: intent.getBooleanExtra(EXTRA_IGNORED, false)
        currentLine = initialLine.coerceIn(0, 999_999_999)
        currentFind = savedInstanceState?.getString(STATE_FIND)?.take(256).orEmpty()
        pendingAnchor = (if (savedInstanceState != null) savedInstanceState.getString(STATE_ANCHOR) else intent.getStringExtra(EXTRA_ANCHOR))?.take(256)
        showPreview = savedInstanceState?.getBoolean(STATE_PREVIEW) ?: (pendingAnchor != null)
        viewModel = ViewModelProvider(this, FilesViewModel.Factory(application, target, scope, directory, path, ignored, openReference))[FilesViewModel::class.java]
        invalidationSubscription = repository.onReadContextInvalidated {
            // Revoke the Activity's own accepted page synchronously. Invalidation can arrive from
            // a repository worker, so wait for the view wipe before returning to that caller.
            acceptedPageContext = null
            acceptedListing = null
            previewGeneration++
            previewJob?.cancel()
            val clear = Runnable {
                currentSource = null
                adapter.clear()
                sourceContent.removeAllViews()
                if (::sourceText.isInitialized) sourceText.text = ""
                sourceScroll.isVisible = false
                list.isVisible = false
                fileTitle.text = target.id
                breadcrumb.text = "/"
                dismissOldPreviewDialog()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                clear.run()
            } else {
                val completed = java.util.concurrent.CountDownLatch(1)
                if (Handler(Looper.getMainLooper()).post { try { clear.run() } finally { completed.countDown() } }) {
                    completed.await()
                }
            }
        }
        ignoredCheck.isChecked = ignored
        if (currentFind.isNotEmpty()) findViewById<EditText>(R.id.files_find).setText(currentFind)
        if (initialLine > 0) findViewById<EditText>(R.id.files_line).setText(initialLine.toString())
        findViewById<View>(R.id.files_back).setOnClickListener {
            if (currentSource != null) { showPreview = false; viewModel.backToDirectory() }
            else if (viewModel.state.value.directory.isNotEmpty()) viewModel.backToDirectory() else finish()
        }
        findViewById<Button>(R.id.files_retry).setOnClickListener { viewModel.refresh() }
        ignoredCheck.setOnCheckedChangeListener { _, checked -> viewModel.setIgnored(checked) }
        sourceButton.setOnClickListener { showPreview = false; render(viewModel.state.value) }
        previewButton.setOnClickListener { showPreview = true; render(viewModel.state.value) }
        findViewById<Button>(R.id.files_go_line).setOnClickListener { scrollToLine(findViewById<EditText>(R.id.files_line).text.toString().toIntOrNull() ?: 1) }
        findViewById<Button>(R.id.files_find_next).setOnClickListener { findInSource() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSource != null) { showPreview = false; viewModel.backToDirectory() }
                else if (viewModel.state.value.directory.isNotEmpty()) viewModel.backToDirectory()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::viewModel.isInitialized) {
            val state = viewModel.state.value
            outState.putString(STATE_DIRECTORY, state.directory.take(4096))
            outState.putString(STATE_PATH, state.path?.take(4096))
            outState.putBoolean(STATE_IGNORED, state.ignored)
            outState.putBoolean(STATE_PREVIEW, showPreview)
            outState.putInt(STATE_LINE, currentLine.coerceIn(0, 999_999_999))
            outState.putString(STATE_FIND, findViewById<EditText>(R.id.files_find).text.toString().take(256))
            outState.putString(STATE_ANCHOR, pendingAnchor?.take(256))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        invalidationSubscription?.close()
        invalidationSubscription = null
        previewJob?.cancel()
        super.onDestroy()
    }

    private fun buildContent() {
        setContentView(R.layout.activity_files)
        window.prepareEdgeToEdgeContent()
        window.applySafeContentInsets(findViewById(R.id.files_list))
        fileTitle = findViewById(R.id.files_title)
        breadcrumb = findViewById(R.id.files_breadcrumb)
        listingControls = findViewById(R.id.files_listing_controls)
        sourceControls = findViewById(R.id.files_source_controls)
        status = findViewById(R.id.files_status)
        progress = findViewById(R.id.files_progress)
        list = findViewById(R.id.files_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        sourceScroll = findViewById(R.id.files_source_scroll)
        sourceContent = findViewById(R.id.files_source_content)
        sourceButton = findViewById(R.id.files_source)
        previewButton = findViewById(R.id.files_preview)
        ignoredCheck = findViewById(R.id.files_ignored)
    }

    private fun render(state: FilesScreenState) {
        progress.isVisible = state.loading
        status.isVisible = state.error != null
        status.text = state.error?.let(::message).orEmpty()
        val context = state.acceptedContext?.takeIf(repository::isCurrent)
        acceptedPageContext = context
        val source = (state.answer as? FilesAnswer.Source).takeIf { context != null }
        currentSource = source.takeIf { context != null }
        val listing = (state.answer as? FilesAnswer.Listing).takeIf { context != null }
        acceptedListing = listing
        if (context == null) dismissOldPreviewDialog()
        list.isVisible = listing != null
        sourceScroll.isVisible = source != null && context != null
        findViewById<Button>(R.id.files_retry).isVisible = state.error != null && !state.loading
        sourceButton.isVisible = source != null && !source.binary
        previewButton.isVisible = false
        listingControls.isVisible = listing != null
        sourceControls.isVisible = source != null && context != null && !source.binary && !showPreview
        fileTitle.text = source?.path?.substringAfterLast('/') ?: target.id
        val shownDirectory = source?.path?.substringBeforeLast('/', "") ?: state.directory
        breadcrumb.isVisible = context != null
        if (context != null && state.answer != null) setBreadcrumb(shownDirectory, context, state.answer) else breadcrumb.text = ""
        ignoredCheck.isChecked = state.ignored
        if (listing != null) {
            val entries = listing.entries.filter { state.ignored || !it.ignored }
            adapter.submit(entries, listing.directory, listing.truncated, context!!, listing)
            findViewById<TextView>(R.id.files_listing_status).text = when {
                entries.isEmpty() -> getString(R.string.files_no_entries)
                listing.truncated -> getString(R.string.files_truncated)
                else -> resources.getQuantityString(R.plurals.files_entry_count, entries.size, entries.size)
            }
        } else adapter.clear()
        if (source != null && context != null) {
            val raster = source.binary
            previewButton.isVisible = !source.binary && source.path.substringAfterLast('.', "").lowercase() in PREVIEW_EXTENSIONS
            sourceText = sourceTextView(source)
            sourceContent.removeAllViews()
            if (source.binary) {
                if (raster && !showPreview && context != null) {
                    sourceContent.addView(AuthenticatedImageView(
                        this, imageLoader, context, null,
                        getString(R.string.files_image_alt, source.path.substringAfterLast('/').ifBlank { getString(R.string.files_image_default_alt) }),
                        null, target, RelativeFilePath.parse(source.path), source.size, source.mtimeMs,
                    ), layoutParams())
                } else {
                    addNotice(getString(if (raster) R.string.files_not_image else R.string.files_binary))
                }
            } else if (showPreview) {
                renderPreview(source)
            } else {
                if (source.truncated) addNotice(getString(R.string.files_truncated))
                sourceContent.addView(sourceText, layoutParams())
                val jump = currentLine
                if (jump > 0) sourceScroll.post {
                    if (currentSource === source && acceptedPageContext === context && repository.isCurrent(context)) scrollToLine(jump)
                }
            }
            pendingAnchor?.takeIf { showPreview }?.let { anchor ->
                sourceContent.doOnPreDraw {
                    if (currentSource === source && acceptedPageContext === context && repository.isCurrent(context) &&
                        pendingAnchor == anchor && showPreview && scrollToHeading(anchor)
                    ) pendingAnchor = null
                }
            }
        } else {
            sourceContent.removeAllViews()
            // Loading/retirement destroys content custody, not the lightweight navigation mode.
        }
    }

    private fun renderPreview(source: FilesAnswer.Source) {
        val context = acceptedPageContext?.takeIf(repository::isCurrent) ?: return
        val generation = ++previewGeneration
        val preview = FilePreviewRenderer.project(source.path, source.text)
        addNotice(getString(R.string.files_preview_notice, getString(preview.format.labelResourceId)))
        preview.unsupported.forEach { addNotice(getString(it.resourceId)) }
        preview.svg?.let { drawing ->
            val view = FilePreviewRenderer.createSvgView(this, drawing).apply { contentDescription = getString(R.string.files_svg_preview); minimumHeight = dp(260) }
            sourceContent.addView(view, layoutParams())
        }
        if (preview.jsonRows.isNotEmpty()) addJsonTree(preview.jsonRows)
        if (preview.markdownBlocks.isNotEmpty()) addMarkdownBlocks(source, preview.markdownBlocks)
        if (preview.links.isNotEmpty()) addFileLinks(source, preview.links, generation, context)
        if (preview.text.isNotEmpty() && preview.jsonRows.isEmpty() && preview.markdownBlocks.isEmpty()) {
            val view = sourceTextView(source, preview.text).apply { typeface = Typeface.DEFAULT; textSize = 15f; setTextIsSelectable(false) }
            addExternalLinkSpans(view, context)
            sourceContent.addView(view, layoutParams())
        }
        if (preview.images.isNotEmpty()) addPreviewImages(source, preview.images, generation, context)
    }

    private fun addJsonTree(rows: List<JsonPreviewRow>) {
        val rowViews = ArrayList<TextView>(rows.size)
        val expanded = BooleanArray(rows.size) { true }
        fun label(row: JsonPreviewRow) = row.label.ifBlank { getString(R.string.files_json_root) }
        fun value(row: JsonPreviewRow) = when (row.container) {
            JsonContainerKind.OBJECT -> resources.getQuantityString(R.plurals.files_json_object_count, row.childCount, row.childCount)
            JsonContainerKind.ARRAY -> resources.getQuantityString(R.plurals.files_json_array_count, row.childCount, row.childCount)
            null -> row.value
        }
        fun rowText(row: JsonPreviewRow, isExpanded: Boolean) = "  ".repeat(row.depth.coerceAtMost(32)) +
            (if (row.childCount > 0) if (isExpanded) "▾ " else "▸ " else "  ") + label(row) + ": " + value(row)
        rows.forEachIndexed { index, row ->
            val view = TextView(this).apply {
                text = rowText(row, expanded[index])
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minHeight = dp(48)
                setTextIsSelectable(false)
                isFocusable = row.childCount > 0
                isClickable = row.childCount > 0
                contentDescription = getString(if (row.childCount > 0) R.string.files_json_collapse else R.string.files_json_value, label(row), value(row))
                if (row.childCount > 0) setOnClickListener {
                    expanded[index] = !expanded[index]
                    text = rowText(row, expanded[index])
                    contentDescription = getString(if (expanded[index]) R.string.files_json_collapse else R.string.files_json_expand, label(row), value(row))
                    for (child in index + 1 until rows.size) {
                        var parent = rows[child].parent
                        var visible = true
                        while (parent != null) {
                            if (!expanded[parent]) visible = false
                            parent = rows[parent].parent
                        }
                        rowViews[child].isVisible = visible
                    }
                }
            }
            rowViews += view
            sourceContent.addView(view, layoutParams())
        }
    }

    private fun addMarkdownBlocks(source: FilesAnswer.Source, blocks: List<HistoryMarkdownBlock>) {
        fun inline(values: List<HistoryInline>): String = buildString {
            values.forEach { item -> when (item) {
                is HistoryInline.Text -> append(item.text)
                is HistoryInline.Code -> append(item.text)
                is HistoryInline.Bold -> append(inline(item.spans))
                is HistoryInline.Italic -> append(inline(item.spans))
                is HistoryInline.Link -> append(inline(item.spans)).append(" (").append(item.href).append(')')
            } }
        }
        blocks.take(2_000).forEach { block ->
            when (block) {
                is HistoryMarkdownBlock.Heading -> {
                    val view = sourceTextView(source, inline(block.spans)).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = (22 - block.level).coerceAtLeast(16).toFloat()
                        setTextIsSelectable(false)
                        contentDescription = getString(R.string.files_heading_level, block.level, text)
                        tag = HEADING_TAG
                    }
                    addExternalLinkSpans(view, acceptedPageContext ?: return)
                    sourceContent.addView(view, layoutParams())
                }
                is HistoryMarkdownBlock.Paragraph -> {
                    val view = sourceTextView(source, inline(block.spans)).apply { typeface = Typeface.DEFAULT; textSize = 16f; setTextIsSelectable(false) }
                    acceptedPageContext?.let { addExternalLinkSpans(view, it) }
                    sourceContent.addView(view, layoutParams())
                }
                is HistoryMarkdownBlock.Code -> sourceContent.addView(sourceTextView(source, block.text).apply { textSize = 14f; setTextIsSelectable(true); contentDescription = getString(R.string.files_code_block, block.language.ifBlank { getString(R.string.files_code) }) }, layoutParams())
                is HistoryMarkdownBlock.Quote -> sourceContent.addView(sourceTextView(source, "“${inline(block.spans)}”").apply { typeface = Typeface.DEFAULT; textSize = 16f; setPadding(dp(16), dp(8), dp(8), dp(8)); setTextIsSelectable(false) }, layoutParams())
                is HistoryMarkdownBlock.ListBlock -> block.items.take(500).forEachIndexed { index, item ->
                    sourceContent.addView(sourceTextView(source, (if (block.ordered) "${index + 1}. " else "• ") + inline(item)).apply { typeface = Typeface.DEFAULT; textSize = 16f; setTextIsSelectable(false) }, layoutParams())
                }
                is HistoryMarkdownBlock.Table -> {
                    val horizontal = android.widget.HorizontalScrollView(this).apply { isFillViewport = false; contentDescription = getString(R.string.files_scrollable_table) }
                    val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    val allRows = listOf(block.header) + block.rows.take(200)
                    allRows.forEachIndexed { rowIndex, cells ->
                        val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        cells.take(32).forEach { cell ->
                            val cellView = TextView(this@FilesActivity).apply {
                                text = inline(cell)
                                textSize = 14f
                                minWidth = dp(96)
                                minHeight = dp(48)
                                setPadding(dp(8), dp(8), dp(8), dp(8))
                                setTextColor(getColor(R.color.collie_foreground))
                                if (rowIndex == 0) setTypeface(typeface, Typeface.BOLD)
                                setTextIsSelectable(true)
                            }
                            rowView.addView(cellView, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                        table.addView(rowView)
                    }
                    horizontal.addView(table)
                    sourceContent.addView(horizontal, layoutParams())
                }
                HistoryMarkdownBlock.Rule -> sourceContent.addView(TextView(this).apply { text = "────────"; textSize = 18f; contentDescription = getString(R.string.files_section_divider) }, layoutParams())
            }
        }
    }

    private fun addPreviewImages(source: FilesAnswer.Source, refs: List<MarkdownImageRef>, generation: Long, context: com.lateapex.collie.data.ScopedReadContext) {
        if (refs.isEmpty()) return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            if (!repository.isCurrent(context) || context !== acceptedPageContext) return@launch
            when (val result = repository.filesExist(target, scope, refs.map { it.path }, context)) {
                is ApiResult.Success -> {
                    if (!repository.isCurrent(context) || currentSource != source || !showPreview || generation != previewGeneration) return@launch
                    refs.filter { it.path.value in result.value.exists }.forEach { image ->
                        val card = AuthenticatedImageView(
                            this@FilesActivity, imageLoader, context, null, image.alt, null,
                            target, image.path, null, null,
                        )
                        card.contentDescription = getString(R.string.files_image_alt, image.alt.ifBlank { getString(R.string.files_image_default_alt) })
                        sourceContent.addView(card, layoutParams())
                    }
                }
                else -> Unit // Unconfirmed references remain inert text.
            }
        }
    }

    private fun addFileLinks(source: FilesAnswer.Source, links: List<FileLinkRef>, generation: Long, context: com.lateapex.collie.data.ScopedReadContext) {
        val bounded = links.take(64)
        if (bounded.isEmpty()) return
        lifecycleScope.launch {
            val paths = bounded.map { it.path }.distinct()
            val answer = repository.filesExist(target, scope, paths, context)
            val existing = (answer as? ApiResult.Success)?.value?.exists ?: return@launch
            if (!repository.isCurrent(context) || context !== acceptedPageContext || currentSource != source || !showPreview || generation != previewGeneration) return@launch
            bounded.filter { it.path.value in existing }.forEach { link ->
                val button = Button(this@FilesActivity).apply {
                    text = getString(R.string.files_open_link, link.label)
                    contentDescription = getString(R.string.files_open_link_accessibility, link.path.value)
                    setOnClickListener {
                        if (currentSource == source && acceptedPageContext === context && repository.isCurrent(context) && generation == previewGeneration) {
                            lifecycleScope.launch {
                                val current = repository.filesExist(target, scope, listOf(link.path), context)
                                if ((current as? ApiResult.Success)?.value?.exists?.contains(link.path.value) != true ||
                                    currentSource != source || acceptedPageContext !== context || !repository.isCurrent(context) || generation != previewGeneration
                                ) return@launch
                                if (link.anchor != null && link.path.value == source.path) scrollToHeading(link.anchor)
                                else startActivity(FilesActivity.intent(this@FilesActivity, target, scope, path = link.path.value, openReference = true, anchor = link.anchor))
                            }
                        }
                    }
                }
                sourceContent.addView(button, layoutParams())
            }
        }
    }

    private fun sourceTextView(source: FilesAnswer.Source, text: String = source.text): TextView = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextIsSelectable(true)
        setTextColor(getColor(R.color.collie_foreground))
        this.text = text
        contentDescription = getString(R.string.files_source_accessibility, source.path)
        if (source.truncated) append("\n\n[${getString(R.string.files_truncated_inline)}]")
    }

    private fun addNotice(text: String) {
        sourceContent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.collie_muted))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            contentDescription = text
        }, layoutParams())
    }

    private fun addExternalLinkSpans(view: TextView, actor: com.lateapex.collie.data.ScopedReadContext) {
        val raw = view.text.toString()
        val pattern = Regex("(?:https?://|mailto:)[^\\s<>\\\"]{1,2048}", RegexOption.IGNORE_CASE)
        val styled = SpannableString(raw)
        pattern.findAll(raw).forEach { match ->
            val link = match.value.trimEnd('.', ',', ')', ']', ';')
            val safe = HistoryPresentation.safeHref(link) ?: return@forEach
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (actor !== acceptedPageContext || !repository.isCurrent(actor)) return
                    externalDialog = AlertDialog.Builder(this@FilesActivity)
                        .setTitle(R.string.files_external_link_title)
                        .setMessage(R.string.files_external_link_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.files_external_link_open) { _, _ ->
                            if (actor === acceptedPageContext && repository.isCurrent(actor)) {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
                                    .onFailure { Toast.makeText(this@FilesActivity, R.string.files_read_error, Toast.LENGTH_SHORT).show() }
                            }
                        }.show()
                }
            }, match.range.first, match.range.first + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = styled
        view.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun findInSource() {
        val source = currentSource ?: return
        val actor = acceptedPageContext?.takeIf(repository::isCurrent) ?: return
        if (!::sourceText.isInitialized) return
        val query = findViewById<EditText>(R.id.files_find).text.toString()
        if (query.isBlank()) return
        val text = sourceText.text.toString()
        val match = text.indexOf(query, lastFindOffset.coerceAtMost(text.length), ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(query, 0, ignoreCase = true)
        if (match >= 0) sourceScroll.post {
            if (currentSource !== source || acceptedPageContext !== actor || !repository.isCurrent(actor)) return@post
            val layout = sourceText.layout ?: return@post
            sourceScroll.scrollTo(0, layout.getLineTop(layout.getLineForOffset(match)))
            lastFindOffset = match + query.length
        }
    }

    private fun scrollToLine(line: Int) {
        val source = currentSource ?: return
        val actor = acceptedPageContext?.takeIf(repository::isCurrent) ?: return
        if (!::sourceText.isInitialized) return
        currentLine = line.coerceIn(0, 999_999_999)
        val layout = sourceText.layout ?: return
        if (sourceText.lineCount <= 0) return
        var offset = 0
        repeat((line - 1).coerceIn(0, sourceText.lineCount - 1)) {
            val next = sourceText.text.indexOf('\n', offset)
            if (next < 0) return@repeat
            offset = next + 1
        }
        sourceScroll.post {
            if (currentSource === source && acceptedPageContext === actor && repository.isCurrent(actor)) {
                sourceScroll.scrollTo(0, layout.getLineTop(layout.getLineForOffset(offset)))
            }
        }
    }

    private fun scrollToHeading(anchor: String): Boolean {
        val target = anchor.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s-]"), "").trim().replace(Regex("\\s+"), "-")
        fun find(group: ViewGroup): TextView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView && child.tag == HEADING_TAG) {
                    val heading = child.text.toString().lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s-]"), "").trim().replace(Regex("\\s+"), "-")
                    if (heading == target) return child
                }
                if (child is ViewGroup) find(child)?.let { return it }
            }
            return null
        }
        val heading = find(sourceContent) ?: return false
        sourceScroll.smoothScrollTo(0, heading.top)
        return true
    }

    private fun onEntry(entry: FileEntry, directory: String, context: com.lateapex.collie.data.ScopedReadContext?, listing: FilesAnswer.Listing?) {
        if (context == null || listing == null || context !== acceptedPageContext || listing !== acceptedListing || !repository.isCurrent(context)) return
        val path = listOf(directory, entry.name).filter(String::isNotEmpty).joinToString("/")
        if (entry.kind == FileEntryKind.DIRECTORY) viewModel.openDirectory(path) else viewModel.openFile(path)
    }

    private fun setBreadcrumb(path: String, context: com.lateapex.collie.data.ScopedReadContext, page: FilesAnswer) {
        fun ownsPage(): Boolean = context === acceptedPageContext && repository.isCurrent(context) && when (page) {
            is FilesAnswer.Listing -> page === acceptedListing
            is FilesAnswer.Source -> page === currentSource
            is FilesAnswer.Unavailable -> false
        }
        val label = if (path.isBlank()) "/" else "/$path"
        val styled = SpannableString(label)
        styled.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { if (ownsPage()) viewModel.openDirectory("") }
        }, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        var start = 1
        val parts = path.split('/').filter(String::isNotEmpty)
        parts.forEachIndexed { index, part ->
            val end = start + part.length
            val destination = parts.take(index + 1).joinToString("/")
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { if (ownsPage()) viewModel.openDirectory(destination) }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end + 1
        }
        breadcrumb.text = styled
        breadcrumb.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun message(value: FilesMessage): String = when (value) {
        FilesMessage.NOT_CONNECTED -> getString(R.string.files_read_error)
        FilesMessage.UNSUPPORTED -> getString(R.string.files_unsupported)
        FilesMessage.UNAUTHORIZED -> getString(R.string.files_unauthorized)
        FilesMessage.PROXY_REQUIRED -> getString(R.string.files_proxy_required)
        FilesMessage.NOT_FOUND -> getString(R.string.files_not_found)
        FilesMessage.NO_FOLDER -> getString(R.string.files_no_folder)
        FilesMessage.TOO_LARGE -> getString(R.string.files_too_large)
        FilesMessage.NOT_IMAGE -> getString(R.string.files_not_image)
        FilesMessage.UNAVAILABLE -> getString(R.string.files_unavailable)
        FilesMessage.MALFORMED -> getString(R.string.files_malformed)
    }

    private fun dismissOldPreviewDialog() { externalDialog?.dismiss(); externalDialog = null }

    private fun layoutParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TARGET_KIND = "files_target_kind"
        private const val EXTRA_TARGET_ID = "files_target_id"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_DIRECTORY = "files_directory"
        private const val EXTRA_PATH = "files_path"
        private const val EXTRA_IGNORED = "files_ignored"
        private const val EXTRA_LINE = "files_line"
        private const val EXTRA_REFERENCE = "files_reference"
        private const val EXTRA_ANCHOR = "files_anchor"
        private const val STATE_DIRECTORY = "files.state.directory"
        private const val STATE_PATH = "files.state.path"
        private const val STATE_IGNORED = "files.state.ignored"
        private const val STATE_PREVIEW = "files.state.preview"
        private const val STATE_LINE = "files.state.line"
        private const val STATE_FIND = "files.state.find"
        private const val STATE_ANCHOR = "files.state.anchor"
        private const val HEADING_TAG = "files.heading"
        private val PREVIEW_EXTENSIONS = setOf("md", "markdown", "mdown", "json", "jsonc", "html", "htm", "csv", "tsv", "svg")

        fun intent(context: Context, target: FilesTarget, scope: Scope, directory: String = "", path: String? = null, ignored: Boolean = false, line: Int? = null, openReference: Boolean = false, anchor: String? = null): Intent =
            Intent(context, FilesActivity::class.java).apply {
                putExtra(EXTRA_TARGET_KIND, if (target is FilesTarget.Pane) "pane" else "workspace")
                putExtra(EXTRA_TARGET_ID, target.id)
                putExtra(EXTRA_HOST, scope.host)
                putExtra(EXTRA_SESSION, scope.session)
                putExtra(EXTRA_DIRECTORY, directory)
                putExtra(EXTRA_PATH, path)
                if (openReference && path != null) putExtra(EXTRA_REFERENCE, path)
                anchor?.take(256)?.let { putExtra(EXTRA_ANCHOR, it) }
                putExtra(EXTRA_IGNORED, ignored)
                if (line != null) putExtra(EXTRA_LINE, line)
            }
    }
}

private class FileListAdapter(private val onClick: (FileEntry, String, com.lateapex.collie.data.ScopedReadContext?, FilesAnswer.Listing?) -> Unit) : RecyclerView.Adapter<FileListAdapter.Holder>() {
    private var entries: List<FileEntry> = emptyList()
    private var directory = ""
    private var truncated = false
    private var acceptedContext: com.lateapex.collie.data.ScopedReadContext? = null
    private var acceptedListing: FilesAnswer.Listing? = null
    fun submit(values: List<FileEntry>, current: String, isTruncated: Boolean, context: com.lateapex.collie.data.ScopedReadContext, listing: FilesAnswer.Listing) {
        entries = values.take(2_000); directory = current; truncated = isTruncated; acceptedContext = context; acceptedListing = listing; notifyDataSetChanged()
    }
    fun clear() { entries = emptyList(); directory = ""; truncated = false; acceptedContext = null; acceptedListing = null; notifyDataSetChanged() }
    override fun getItemCount(): Int = entries.size + if (truncated) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(TextView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        minHeight = (48 * resources.displayMetrics.density).toInt()
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12 * resources.displayMetrics.density).toInt(), 8, (12 * resources.displayMetrics.density).toInt(), 8)
        textSize = 16f
        isFocusable = true
        setTextColor(context.getColor(R.color.collie_foreground))
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position >= entries.size) {
            holder.text.text = holder.text.context.getString(R.string.files_narrow_folder_for_more)
            holder.text.contentDescription = holder.text.text
            holder.text.isClickable = false
            holder.text.setOnClickListener(null)
            return
        }
        val entry = entries[position]
        val prefix = when (entry.kind) { FileEntryKind.DIRECTORY -> "▸  "; FileEntryKind.FILE -> "   "; FileEntryKind.LINK -> "↗  " }
        val metadata = buildList { entry.size?.let { add(holder.text.context.getString(R.string.files_bytes, it)) }; if (entry.ignored) add(holder.text.context.getString(R.string.files_ignored)) }.joinToString(" · ")
        holder.text.text = listOf(prefix + entry.name, metadata).filter(String::isNotEmpty).joinToString("\n")
        holder.text.contentDescription = holder.text.context.getString(if (entry.kind == FileEntryKind.DIRECTORY) R.string.files_open_folder else R.string.files_open_file, entry.name)
        holder.text.isClickable = true
        val admittedContext = acceptedContext
        val admittedListing = acceptedListing
        val admittedDirectory = directory
        holder.text.setOnClickListener { onClick(entry, admittedDirectory, admittedContext, admittedListing) }
    }
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
