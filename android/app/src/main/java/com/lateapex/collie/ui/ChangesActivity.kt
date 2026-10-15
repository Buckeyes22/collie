package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivityChangesBinding
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ChangedFile
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesTarget
import kotlinx.coroutines.launch

/** Read-only view of bridge-listed Git changes. Paths are submitted only as opaque query values. */
class ChangesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChangesBinding
    private lateinit var viewModel: ChangesViewModel
    private lateinit var target: ChangesTarget
    private var filePage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePage = savedInstanceState?.getInt(STATE_FILE_PAGE, 0)?.coerceAtLeast(0) ?: 0
        window.prepareEdgeToEdgeContent()
        binding = ActivityChangesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyAppTypeface()
        window.applySafeContentInsets(binding.root)

        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf(String::isNotBlank)
        val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)?.takeIf(String::isNotBlank)
        target = when {
            paneId != null && workspaceId == null -> ChangesTarget.Pane(paneId)
            workspaceId != null && paneId == null -> ChangesTarget.Workspace(workspaceId)
            else -> { finish(); return }
        }
        val scope = Scope(
            host = intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank),
            session = intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank),
        )
        val label = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)
            ?: if (target is ChangesTarget.Pane) paneId.orEmpty() else workspaceId.orEmpty()
        binding.changesSubtitle.text = safeDisplay(label)
        binding.changesBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.changesRefresh.setOnClickListener { viewModel.refresh() }
        viewModel = ViewModelProvider(this, ChangesViewModel.Factory(application, target, scope))
            .get(ChangesViewModel::class.java)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = viewModel.state.value
                when {
                    current.detail != null && current.history != null -> viewModel.backToHistory()
                    current.detail != null -> viewModel.backToList()
                    current.history != null -> viewModel.backToList()
                    else -> finish()
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_FILE_PAGE, filePage)
        super.onSaveInstanceState(outState)
    }

    private fun render(state: ChangesScreenState) {
        binding.changesProgress.isVisible = state.loading
        binding.changesError.isVisible = state.error != null
        binding.changesError.text = state.error?.let(::messageFor).orEmpty()
        binding.changesContent.removeAllViews()
        val detail = state.detail
        if (detail != null) {
            renderDetail(state, detail)
        } else if (state.history != null) {
            renderHistory(state)
        } else {
            addLookupControls(state.lookup.depth, state.lookup.nested)
            renderList(state.list)
        }
    }

    private fun renderList(answer: com.lateapex.collie.network.ChangesListAnswer?) {
        val state = answer?.state
        if (answer == null || state == null) {
            if (!binding.changesProgress.isVisible && binding.changesError.visibility != View.VISIBLE) {
                addMessage(getString(R.string.changes_loading))
            }
            return
        }
        when (state) {
            is ChangesListState.Unavailable -> addMessage(unavailableFor(state.reason))
            is ChangesListState.Available -> {
                if (state.truncated) addNotice(getString(R.string.changes_list_truncated))
                if (state.depthLimited) addNotice(getString(R.string.changes_depth_limited))
                if (state.repos.isEmpty() && state.clean.isEmpty()) addMessage(getString(R.string.changes_no_repositories))
                val fileCount = state.repos.sumOf { it.files.size }
                val pageCount = ((fileCount + FILES_PER_PAGE - 1) / FILES_PER_PAGE).coerceAtLeast(1)
                filePage = filePage.coerceIn(0, pageCount - 1)
                val firstFile = filePage * FILES_PER_PAGE
                val visibleFiles = mutableListOf<Pair<String, ChangedFile>>()
                var fileIndex = 0
                state.repos.forEach { repo ->
                    repo.files.forEach { file ->
                        if (fileIndex in firstFile until firstFile + FILES_PER_PAGE) {
                            visibleFiles += repo.relPath to file
                        }
                        fileIndex++
                    }
                }
                val visibleByRepo = visibleFiles.groupBy({ it.first }, { it.second })
                state.repos.forEach { repo ->
                    val pageFiles = visibleByRepo[repo.relPath].orEmpty()
                    if (pageFiles.isEmpty() && repo.files.isNotEmpty()) return@forEach
                    val paneMarker = if (repo.relPath == answer.paneRepo) " · ${getString(R.string.changes_this_pane)}" else ""
                    addHeading(repo.name + " · " + repo.relPath + paneMarker)
                    addButton(getString(R.string.changes_last_commit)) {
                        filePage = 0
                        viewModel.openLastCommit(repo.relPath)
                    }
                    addButton(getString(R.string.changes_commit_history)) {
                        filePage = 0
                        viewModel.openHistory(repo.relPath)
                    }
                    if (repo.files.isEmpty()) addMuted(getString(R.string.changes_repo_no_files))
                    pageFiles.forEach { file -> addFile(repo.relPath, file, onCommit = false) }
                }
                state.clean.forEach { repo ->
                    addHeading(repo.name + " · " + repo.relPath)
                    addMuted(getString(R.string.changes_clean_repository))
                    addButton(getString(R.string.changes_last_commit)) {
                        filePage = 0
                        viewModel.openLastCommit(repo.relPath)
                    }
                    addButton(getString(R.string.changes_commit_history)) {
                        filePage = 0
                        viewModel.openHistory(repo.relPath)
                    }
                }
                if (fileCount > FILES_PER_PAGE) addFilePager(fileCount, pageCount)
            }
        }
    }

    private fun renderHistory(state: ChangesScreenState) {
        val history = state.history ?: return
        val page = history.page
        addButton(getString(R.string.changes_back_to_list)) { viewModel.backToList() }
        addHeading(page.name + " · " + page.repo)
        addMuted(getString(R.string.changes_history_page, page.offset + 1, page.offset + page.commits.size, page.headHash.take(12)))
        if (page.truncated) addNotice(getString(R.string.changes_history_truncated))
        page.commits.forEach { commit ->
            val label = buildString {
                append(commit.shortHash).append("  ").append(safeDisplay(commit.subject))
                append("\n").append(safeDisplay(commit.author)).append(" · ").append(commit.timeSeconds)
            }
            addButton(label) { viewModel.openHistoryCommit(history.repo, commit) }
        }
        if (history.pageIndex > 0) addButton(getString(R.string.changes_previous_commits)) { viewModel.previousHistoryPage() }
        if (page.nextOffset != null) addButton(getString(R.string.changes_older_commits)) { viewModel.nextHistoryPage() }
        else if (!page.truncated) addMuted(getString(R.string.changes_history_end))
    }

    private fun renderDetail(state: ChangesScreenState, detail: ChangeDetail) {
        if (state.history != null) addButton(getString(R.string.changes_back_to_history)) { viewModel.backToHistory() }
        else addButton(getString(R.string.changes_back_to_list)) { viewModel.backToList() }
        addHeading(detail.title)
        if (detail.subtitle.isNotBlank()) addMuted(detail.subtitle)
        detail.currentSource?.let { source ->
            addButton(getString(R.string.changes_open_current_source)) {
                val handoff = viewModel.sourceHandoff(source) ?: return@addButton
                startActivity(FilesActivity.intentForAcceptedSource(this, source.target, source.scope, source.path, handoff))
            }
        }
        detail.sourceNotice?.let { notice ->
            addNotice(getString(when (notice) {
                ChangeSourceNotice.DELETED -> R.string.changes_deleted_source_notice
                ChangeSourceNotice.HISTORICAL -> R.string.changes_historical_source_notice
            }))
        }
        val isCommitDetail = state.selection?.commit == true
        if (isCommitDetail && detail.commitFilesTruncated) {
            addNotice(getString(R.string.changes_commit_files_truncated))
        }
        val body = if (isCommitDetail && detail.commitFiles.isNotEmpty()) {
            addSection(getString(R.string.changes_commit_files))
            val pageCount = ((detail.commitFiles.size + FILES_PER_PAGE - 1) / FILES_PER_PAGE).coerceAtLeast(1)
            filePage = filePage.coerceIn(0, pageCount - 1)
            detail.commitFiles.drop(filePage * FILES_PER_PAGE).take(FILES_PER_PAGE)
                .forEach { file -> addFile(state.selection.repo, file, onCommit = true) }
            if (detail.commitFiles.size > FILES_PER_PAGE) addFilePager(detail.commitFiles.size, pageCount)
            detail.body
        } else detail.body
        if (detail.binary) {
            addNotice(body)
        } else if (detail.isDiff) {
            addDiff(body)
        } else {
            addSelectableText(body)
        }
    }

    private fun addFile(repo: String, file: ChangedFile, onCommit: Boolean) {
        val label = buildString {
            append(file.status.wire).append("  ").append(safeDisplay(file.path))
            if (file.oldPath != null) append("  ← ").append(safeDisplay(file.oldPath))
            append("  +").append(file.added).append(" −").append(file.removed)
            if (file.binary) append("  ").append(getString(R.string.changes_binary_badge))
        }
        addButton(label) {
            if (onCommit) viewModel.openCommitFile(repo, file) else viewModel.openFile(repo, file)
        }
    }

    private fun addLookupControls(depth: Int, nested: Boolean) {
        addButton(getString(R.string.changes_depth_label, depth)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.changes_depth_title)
                .setItems(arrayOf("1", "2", "3", "4")) { _, index -> viewModel.setDepth(index + 1) }
                .setNegativeButton(R.string.pane_action_cancel, null)
                .show()
        }
        val nestedSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.changes_nested)
            contentDescription = getString(R.string.changes_nested_accessibility)
            isChecked = nested
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            setOnCheckedChangeListener { _, checked -> viewModel.setNested(checked) }
        }
        binding.changesContent.addView(nestedSwitch, matchRow())
    }

    private fun addDiff(diff: String) {
        val numbered = diff.lineSequence().mapIndexed { index, line ->
            String.format(java.util.Locale.ROOT, "%4d │ %s", index + 1, safeDisplay(line))
        }.joinToString("\n")
        val text = textView(numbered, 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val horizontal = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = true
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        binding.changesContent.addView(horizontal, matchRow(8))
    }

    private fun addSelectableText(value: String) {
        binding.changesContent.addView(textView(safeDisplay(value), 14f).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }, matchRow())
    }

    private fun addHeading(value: String) {
        binding.changesContent.addView(textView(safeDisplay(value), 17f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }, matchRow())
    }

    private fun addSection(value: String) {
        binding.changesContent.addView(textView(safeDisplay(value), 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }, matchRow())
    }

    private fun addMessage(value: String) {
        binding.changesContent.addView(textView(value, 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(32), dp(20), dp(32))
            contentDescription = value
        }, matchRow())
    }

    private fun addNotice(value: String) {
        binding.changesContent.addView(textView(value, 13f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_dashboard_shell_working)
        }, matchRow(8))
    }

    private fun addMuted(value: String) {
        binding.changesContent.addView(textView(value, 13f).apply {
            setTextColor(getColor(R.color.collie_muted))
            setPadding(0, dp(4), 0, dp(8))
        }, matchRow())
    }

    private fun addButton(value: String, action: () -> Unit) {
        val button = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(getColor(R.color.collie_on_background))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener { action() }
        }
        binding.changesContent.addView(button, matchRow(4))
    }

    private fun addFilePager(fileCount: Int, pageCount: Int) {
        val first = filePage * FILES_PER_PAGE + 1
        val last = minOf(fileCount, (filePage + 1) * FILES_PER_PAGE)
        addMuted(getString(R.string.changes_file_page, first, last, fileCount, filePage + 1, pageCount))
        if (filePage > 0) {
            addButton(getString(R.string.changes_previous_files)) {
                filePage--
                render(viewModel.state.value)
            }
        }
        if (filePage + 1 < pageCount) {
            addButton(getString(R.string.changes_next_files)) {
                filePage++
                render(viewModel.state.value)
            }
        }
    }

    private fun textView(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.collie_on_background))
        includeFontPadding = true
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        if (value.resourceId != 0) androidx.core.content.ContextCompat.getDrawable(this, value.resourceId) else null
    }

    private fun matchRow(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun messageFor(message: ChangesMessage): String = when (message) {
        ChangesMessage.NOT_CONNECTED -> getString(R.string.changes_not_connected)
        ChangesMessage.UNSUPPORTED -> getString(R.string.changes_unsupported)
        ChangesMessage.UNAUTHORIZED -> getString(R.string.changes_unauthorized)
        ChangesMessage.UNKNOWN_REPO -> getString(R.string.changes_unknown_repo)
        ChangesMessage.UNKNOWN_PATH -> getString(R.string.changes_unknown_path)
        ChangesMessage.NO_COMMIT -> getString(R.string.changes_no_commit)
        ChangesMessage.UNAVAILABLE -> getString(R.string.changes_unavailable)
        ChangesMessage.MALFORMED -> getString(R.string.changes_malformed)
    }

    private fun unavailableFor(reason: String): String = when (reason) {
        "no-pane" -> getString(R.string.changes_no_pane)
        "no-workspace" -> getString(R.string.changes_no_workspace)
        "no-folder" -> getString(R.string.changes_no_folder)
        "no-git" -> getString(R.string.changes_no_git)
        else -> getString(R.string.changes_unavailable)
    }

    private fun safeDisplay(value: String): String = buildString(value.length) {
        value.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt() && type != Character.SURROGATE.toInt()) {
                appendCodePoint(cp)
            } else if (cp == '\n'.code || cp == '\t'.code || cp == '\r'.code) {
                appendCodePoint(cp)
            } else append('�')
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FILES_PER_PAGE = 100
        private const val STATE_FILE_PAGE = "changes_file_page"
        private const val EXTRA_PANE_ID = "pane_id"
        private const val EXTRA_WORKSPACE_ID = "workspace_id"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_TITLE = "title"

        fun intentForPane(context: Context, paneId: String, scope: Scope, title: String?): Intent =
            Intent(context, ChangesActivity::class.java).apply {
                putExtra(EXTRA_PANE_ID, paneId)
                putExtra(EXTRA_HOST, scope.host)
                putExtra(EXTRA_SESSION, scope.session)
                putExtra(EXTRA_TITLE, title)
            }

        fun intentForWorkspace(context: Context, workspaceId: String, scope: Scope, title: String?): Intent =
            Intent(context, ChangesActivity::class.java).apply {
                putExtra(EXTRA_WORKSPACE_ID, workspaceId)
                putExtra(EXTRA_HOST, scope.host)
                putExtra(EXTRA_SESSION, scope.session)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
