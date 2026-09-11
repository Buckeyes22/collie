package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivitySpaceBinding
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreatedPane
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.WorkspaceSummary
import java.util.Locale
import kotlinx.coroutines.launch

internal enum class SpaceTabAction(@androidx.annotation.StringRes val labelRes: Int) {
    RENAME(R.string.tab_action_rename),
    CLOSE(R.string.tab_action_close),
}

internal object SpaceActionModel {
    fun canUse(writeAuthorized: Boolean, mux: MuxConfigResponse?, capability: String): Boolean =
        writeAuthorized && mux?.supports(capability) != false

    fun tabActions(writeAuthorized: Boolean, mux: MuxConfigResponse?): List<SpaceTabAction> =
        if (!writeAuthorized) {
            emptyList()
        } else {
            buildList {
                if (mux?.supports("renameTab") != false) add(SpaceTabAction.RENAME)
                if (mux?.supports("closeTab") != false) add(SpaceTabAction.CLOSE)
            }
        }

    @androidx.annotation.StringRes
    fun closeMessageResource(tab: TabSummary): Int =
        if (tab.paneCount == 1) R.string.tab_close_one_pane else R.string.tab_close_many_panes
}

class SpaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySpaceBinding
    private lateinit var viewModel: SpaceViewModel
    private lateinit var scope: Scope
    private lateinit var workspaceId: String
    private val adapter = SpaceAdapter(::openPane)
    private val footerAdapter = SpaceFooterAdapter()
    private var selectedTabId: String? = null
    private var creatingTab = false
    private var creatingSpace = false
    private var everRenderedSpace = false
    private var missingRedirected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivitySpaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyAppTypeface()
        window.applySafeContentInsets(binding.root)

        workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)?.takeIf(String::isNotBlank) ?: run {
            finish()
            return
        }
        scope = Scope(
            host = intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank),
            session = intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank),
        )
        selectedTabId = savedInstanceState?.getString(STATE_SELECTED_TAB)
        binding.backButton.setOnClickListener { returnToDashboard() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.spaceList.layoutManager = LinearLayoutManager(this)
        binding.spaceList.adapter = ConcatAdapter(adapter, footerAdapter)
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        viewModel = ViewModelProvider(
            this,
            SpaceViewModel.Factory(application, workspaceId, scope),
        )[SpaceViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedTabId?.let { outState.putString(STATE_SELECTED_TAB, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPolling()
    }

    override fun onStop() {
        viewModel.stopPolling()
        super.onStop()
    }

    private fun render(state: SpaceUiState) = with(binding) {
        if (state.error == getString(R.string.space_missing) && !missingRedirected) {
            missingRedirected = true
            Toast.makeText(
                this@SpaceActivity,
                if (everRenderedSpace) R.string.space_closed else R.string.space_not_found,
                Toast.LENGTH_SHORT,
            ).show()
            returnToDashboard()
            return@with
        }
        swipeRefresh.isRefreshing = state.refreshing
        progress.isVisible = state.loading
        errorText.text = state.error.orEmpty()
        errorText.isVisible = state.error != null

        val content = state.content
        if (content != null) everRenderedSpace = true
        spaceReadOnlyBanner.isVisible = !state.writeAuthorized && (content != null || !state.loading)
        if (spaceReadOnlyBanner.isVisible) {
            spaceReadOnlyBanner.setText(
                when {
                    !state.paired -> R.string.read_only_not_paired
                    content == null && state.error != null -> R.string.read_only_unreachable
                    else -> R.string.read_only_device_unauthorised
                },
            )
            spaceReadOnlyBanner.setOnClickListener(
                if (state.paired) null else View.OnClickListener {
                    startActivity(SettingsActivity.intent(this@SpaceActivity, focusDevices = true))
                },
            )
            spaceReadOnlyBanner.isClickable = !state.paired
        }
        selectedTabId = SpacePresentationModel.retainedSelection(content, selectedTabId)
        headerStatus.text = content?.workspace?.label?.takeIf(String::isNotBlank).orEmpty()
        if (content != null) {
            val tabs = resources.getQuantityString(R.plurals.space_tab_count, content.workspace.tabCount, content.workspace.tabCount)
            val panes = resources.getQuantityString(R.plurals.space_pane_count, content.workspace.paneCount, content.workspace.paneCount)
            spaceSubtitle.text = getString(R.string.space_header_counts, tabs, panes)
            spaceSubtitle.isVisible = true
            tabsStrip.isVisible = content.tabs.isNotEmpty()
            renderTabStrip(content, state)
        } else {
            spaceSubtitle.isVisible = false
            tabsStrip.isVisible = false
        }
        adapter.submitList(SpacePresentationModel.rows(content, selectedTabId))
    }

    private fun renderTabStrip(content: SpaceContent, state: SpaceUiState) {
        with(binding.tabChips) {
        removeAllViews()
        addView(
            tabChip(getString(R.string.space_tab_all), active = selectedTabId == null) {
                selectedTabId = null
                adapter.submitList(SpacePresentationModel.rows(content, null))
                renderTabStrip(content, state)
            },
            stripParams(4),
        )
        content.tabs.forEach { group ->
            val tab = group.tab
            val active = selectedTabId == tab.tabId
            val agentPanes = content.agents.filter {
                it.workspaceId == content.workspace.workspaceId && it.tabId == tab.tabId
            }
            val chip = tabChip(
                label = tab.label.ifBlank { getString(R.string.tab_default, tab.number) },
                active = active,
                focused = tab.focused,
                status = SpacePresentationModel.worstStatus(agentPanes),
                agent = SpacePresentationModel.soleAgent(agentPanes),
            ) {
                if (active) {
                    showTabActions(tab)
                } else {
                    selectedTabId = tab.tabId
                    adapter.submitList(SpacePresentationModel.rows(content, tab.tabId))
                    renderTabStrip(content, state)
                }
            }
            chip.setOnLongClickListener {
                showTabActions(tab)
                true
            }
            addView(chip, stripParams(4))
            if (active) {
                post { binding.tabsStrip.smoothScrollTo(chip.left - dp(24), 0) }
            }
        }
        if (SpaceActionModel.canUse(state.writeAuthorized, state.mux, "createTab")) {
            addView(addButton(R.string.new_tab, creatingTab, ::showNewTab), stripParams(4))
        }
        if (SpaceActionModel.canUse(state.writeAuthorized, state.mux, "createSpace")) {
            addView(addButton(R.string.new_space, creatingSpace, ::showNewWorkspace), stripParams(8))
        }
        }
    }

    private fun tabChip(
        label: String,
        active: Boolean,
        focused: Boolean = false,
        status: com.lateapex.collie.network.AgentStatus? = null,
        agent: String? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(44)
        isClickable = true
        isFocusable = true
        isSelected = active
        contentDescription = label
        background = chipBackground(active, focused, tab = true)
        foreground = selectableForeground()
        setPadding(dp(if (status == null && agent == null) 12 else 8), 0, dp(12), 0)
        status?.let { addStatusDot(this, it, active, tab = true) }
        agent?.let {
            addView(ImageView(this@SpaceActivity).apply {
                setImageResource(agentIcon(it))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
        }
        addView(chipLabel(label, false).apply {
            if (active) setTextColor(color(R.color.collie_foreground))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
    }

    private fun chipLabel(label: String, active: Boolean) = TextView(this).apply {
        text = label
        maxLines = 1
        textSize = 14f
        typeface = ResourcesCompat.getFont(this@SpaceActivity, R.font.collie_ui)
        setTextColor(color(if (active) R.color.collie_on_primary else R.color.collie_muted))
    }

    private fun addStatusDot(
        parent: LinearLayout,
        status: com.lateapex.collie.network.AgentStatus,
        active: Boolean,
        tab: Boolean,
    ) {
        parent.addView(View(this).apply {
            background = statusDotDrawable(
                this,
                status,
                if (active && !tab) R.color.collie_primary else if (tab && active) R.color.collie_background else R.color.collie_muted_surface,
            )
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
    }

    private fun chipBackground(active: Boolean, focused: Boolean, tab: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = if (tab) floatArrayOf(dp(2f), dp(2f), dp(2f), dp(2f), 0f, 0f, 0f, 0f) else FloatArray(8) { dp(2f) }
        setColor(color(if (active && !tab) R.color.collie_primary else if (active) R.color.collie_background else R.color.collie_muted_surface))
        setStroke(
            dp(1),
            color(if (active || focused) R.color.collie_rule else android.R.color.transparent),
            if (focused && !active) dp(3f) else 0f,
            if (focused && !active) dp(2f) else 0f,
        )
    }

    private fun addButton(
        @androidx.annotation.StringRes description: Int,
        busy: Boolean,
        action: () -> Unit,
    ): FrameLayout =
        FrameLayout(this).apply {
            isClickable = !busy
            isFocusable = !busy
            isEnabled = !busy
            contentDescription = getString(description)
            foreground = selectableForeground()
            if (busy) {
                addView(ProgressBar(this@SpaceActivity).apply {
                    isIndeterminate = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            } else {
                addView(ImageView(this@SpaceActivity).apply {
                    setImageResource(R.drawable.ic_collie_add)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(dp(1), color(R.color.collie_border), dp(3f), dp(2f))
                    }
                }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                setOnClickListener { action() }
            }
        }

    private fun showNewTab() {
        if (creatingTab) return
        creatingTab = true
        viewModel.state.value.content?.let { renderTabStrip(it, viewModel.state.value) }
        lifecycleScope.launch {
            try {
                handleCreate(viewModel.createTab(null, null))
            } finally {
                creatingTab = false
                viewModel.state.value.content?.let { renderTabStrip(it, viewModel.state.value) }
            }
        }
    }

    private fun showNewWorkspace() {
        if (creatingSpace) return
        val dialog = CollieBottomSheetDialog(this, getString(R.string.new_space))
        val body = sheetBody()
        val label = textField(R.string.space_label_optional)
        val cwd = textField(R.string.space_directory_optional)
        body.addView(label, matchRow(top = 4))
        body.addView(cwd, matchRow(top = 8))
        body.addView(sheetActionButton(getString(R.string.action_create)) {
            dialog.dismiss()
            creatingSpace = true
            viewModel.state.value.content?.let { renderTabStrip(it, viewModel.state.value) }
            lifecycleScope.launch {
                try {
                    handleCreate(viewModel.createWorkspace(label.valueOrNull(), cwd.valueOrNull()))
                } finally {
                    creatingSpace = false
                    viewModel.state.value.content?.let { renderTabStrip(it, viewModel.state.value) }
                }
            }
        }, matchRow(top = 12))
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun returnToDashboard() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun showTabActions(tab: TabSummary) {
        val state = viewModel.state.value
        val title = tab.label.ifBlank { getString(R.string.tab_default, tab.number) }
        val dialog = CollieBottomSheetDialog(this, title)
        val body = sheetBody()
        val choices = SpaceActionModel.tabActions(state.writeAuthorized, state.mux)
        if (!state.writeAuthorized) {
            body.addView(sheetMessage(R.string.tab_pair_required))
        } else if (choices.isEmpty()) {
            body.addView(sheetMessage(R.string.tab_changes_unsupported))
        } else {
            choices.forEach { choice ->
                when (choice) {
                    SpaceTabAction.RENAME -> body.addView(sheetActionButton(getString(choice.labelRes)) {
                        dialog.dismiss()
                        showRenameTab(tab)
                    }, matchRow(top = 4))
                    SpaceTabAction.CLOSE -> body.addView(closeTabButton(dialog, tab), matchRow(top = 4))
                }
            }
        }
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun showRenameTab(tab: TabSummary) {
        val dialog = CollieBottomSheetDialog(this, getString(R.string.tab_rename_title))
        val body = sheetBody()
        val label = textField(R.string.tab_label_hint).apply {
            setText(tab.label)
            setSelection(text.length)
        }
        val save = sheetActionButton(getString(R.string.tab_action_rename)) {
            val value = label.text.toString().trim()
            if (value.isNotEmpty()) {
                dialog.dismiss()
                lifecycleScope.launch { handleAction(viewModel.renameTab(tab.tabId, value)) }
            }
        }
        body.addView(label, matchRow(top = 4))
        body.addView(save, matchRow(top = 12))
        dialog.setSheetContent(body)
        dialog.show()
        label.post { label.requestFocus() }
    }

    private fun closeTabButton(dialog: CollieBottomSheetDialog, tab: TabSummary): MaterialButton {
        var armed = false
        return sheetActionButton(getString(R.string.tab_action_close)) {
            if (!armed) {
                armed = true
                text = getString(SpaceActionModel.closeMessageResource(tab), tab.paneCount)
                backgroundTintList = ColorStateList.valueOf(color(R.color.collie_destructive))
                setTextColor(color(R.color.collie_on_destructive))
            } else {
                dialog.dismiss()
                val closedId = tab.tabId
                if (selectedTabId == closedId) selectedTabId = null
                lifecycleScope.launch { handleAction(viewModel.closeTab(closedId)) }
            }
        }
    }

    private fun sheetBody() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun sheetActionButton(label: String, action: MaterialButton.() -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setOnClickListener { action() }
        }

    private fun sheetMessage(@androidx.annotation.StringRes text: Int) = TextView(this).apply {
        setText(text)
        textSize = 14f
        setTextColor(color(R.color.collie_muted))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun textField(@androidx.annotation.StringRes hintRes: Int) = EditText(this).apply {
        setHint(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(160))
        minHeight = dp(44)
        setTextColor(color(R.color.collie_foreground))
        setHintTextColor(color(R.color.collie_muted))
    }

    private fun EditText.valueOrNull(): String? = text.toString().trim().ifBlank { null }

    private fun handleCreate(result: ApiResult<com.lateapex.collie.network.CreateResponse>) {
        when (result) {
            is ApiResult.Success -> if (result.value.ok) {
                result.value.pane?.let(::openCreatedPane)
                    ?: viewModel.report(getString(R.string.created_pane_missing))
            } else {
                viewModel.report(result.value.error ?: getString(R.string.created_item_failed))
            }
            is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
            is ApiResult.NotModified -> Unit
        }
    }

    private fun handleAction(result: ApiResult<com.lateapex.collie.network.ActionResponse>) {
        when (result) {
            is ApiResult.Success -> if (result.value.ok) viewModel.refresh()
                else viewModel.report(result.value.error ?: getString(R.string.tab_change_failed))
            is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
            is ApiResult.NotModified -> Unit
        }
    }

    private fun openCreatedPane(pane: CreatedPane) {
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_TITLE, pane.workspaceLabel)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_shell_meta, pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, "shell")
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, pane.tabId)
            putExtra(PaneActivity.EXTRA_STATUS, "unknown")
            putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        })
    }

    private fun openPane(pane: PaneSummary) {
        val tabLabel = pane.tabLabel?.takeIf(String::isNotBlank)
        val defaultTitle = listOfNotNull(pane.workspaceLabel.takeIf(String::isNotBlank), tabLabel)
            .joinToString(" › ").ifBlank { pane.workspaceId }
        val displayTitle = listOf(pane.paneLabel, pane.sessionName, defaultTitle)
            .firstNotNullOfOrNull { displayAgentTitle(pane.agent, it) }
            ?: defaultTitle
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, pane.host)
            putExtra(PaneActivity.EXTRA_SESSION, pane.session)
            putExtra(PaneActivity.EXTRA_TITLE, displayTitle)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_meta, pane.agent, pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, pane.agent)
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, tabLabel)
            putExtra(PaneActivity.EXTRA_STATUS, pane.status.name.lowercase(Locale.ROOT))
        })
    }

    private fun stripParams(end: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        dp(44),
    ).apply { marginEnd = dp(end) }

    private fun matchRow(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun selectableForeground() = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        const val EXTRA_WORKSPACE_ID = "workspace_id"
        const val EXTRA_HOST = "host"
        const val EXTRA_SESSION = "session"
        const val EXTRA_TITLE = "title"
        private const val STATE_SELECTED_TAB = "selected_tab"

        fun intent(context: Context, workspace: WorkspaceSummary, session: String?): Intent =
            Intent(context, SpaceActivity::class.java).apply {
                putExtra(EXTRA_WORKSPACE_ID, workspace.workspaceId)
                putExtra(EXTRA_HOST, workspace.host)
                putExtra(EXTRA_SESSION, session)
                putExtra(EXTRA_TITLE, workspace.label)
            }
    }
}
