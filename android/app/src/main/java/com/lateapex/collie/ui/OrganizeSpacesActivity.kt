package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OrganizationAction
import com.lateapex.collie.network.OrganizationPlanResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.network.validFor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Native, owner-scoped workspace/tab organization; focus is only changed by an explicit Focus row. */
class OrganizeSpacesActivity : AppCompatActivity() {
    private val repository: CollieRepository get() = (application as CollieApplication).container.repository
    private lateinit var scope: Scope
    private lateinit var rows: LinearLayout
    private lateinit var notice: TextView
    private var context: ScopedReadContext? = null
    private var snapshot: SnapshotResponse? = null
    private var capabilities: Map<String, Boolean> = emptyMap()
    private var expandedWorkspaceId: String? = null
    private var busy = false
    private var uncertainRequestId: String? = null
    private var authorityRegistration: AutoCloseable? = null
    private val planActors = linkedMapOf<String, ScopedReadContext>()
    private val layoutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) refresh()
    }
    private val transferLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        scope = Scope(
            host = intent.getStringExtra(EXTRA_HOST),
            session = intent.getStringExtra(EXTRA_SESSION),
            viewAll = intent.getBooleanExtra(EXTRA_VIEW_ALL, false),
        )
        uncertainRequestId = savedInstanceState?.getString(STATE_UNCERTAIN_ID)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            applyAppTypeface()
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        root.addView(TextView(this).apply {
            text = getString(R.string.organization_title)
            textSize = 22f
            setTextColor(getColor(R.color.collie_on_background))
            contentDescription = getString(R.string.organization_title)
        }, fullWidth())
        notice = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.collie_muted))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(notice, fullWidth())
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(button(R.string.organization_refresh) { refresh() }, weightWidth())
        controls.addView(button(R.string.organization_reorder) { showReorderDialog() }, weightWidth())
        root.addView(controls, fullWidth())
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows, fullWidth())
        root.addView(TextView(this).apply {
            text = getString(R.string.organization_cross_workspace_tab_limit)
            textSize = 12f
            setTextColor(getColor(R.color.collie_muted))
            setPadding(0, dp(16), 0, 0)
        }, fullWidth())
        authorityRegistration = repository.onReadContextInvalidated {
            runOnUiThread {
                busy = false
                context = null
                snapshot = null
                capabilities = emptyMap()
                planActors.clear()
                notice.text = getString(R.string.organization_scope_changed)
                render()
            }
        }
        if (scope.viewAll) {
            notice.text = getString(R.string.organization_select_session)
            render()
        } else refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        uncertainRequestId?.let { outState.putString(STATE_UNCERTAIN_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        super.onDestroy()
    }

    private fun refresh() {
        if (scope.viewAll) { notice.text = getString(R.string.organization_select_session); render(); return }
        if (busy) return
        busy = true
        notice.text = getString(R.string.organization_loading)
        render()
        lifecycleScope.launch {
            val actor = repository.readContext(scope)
            if (actor == null) {
                busy = false
                context = null
                snapshot = null
                capabilities = emptyMap()
                notice.text = getString(R.string.organization_unavailable)
                render()
                return@launch
            }
            context = actor
            val response = repository.paneSnapshot(scope, actor)
            if (!repository.isCurrent(actor)) {
                context = null
                snapshot = null
                capabilities = emptyMap()
                busy = false
                notice.text = getString(R.string.organization_scope_changed)
                render()
                return@launch
            }
            when (response) {
                is ApiResult.Success -> {
                    snapshot = response.value
                    when (val config = repository.config(scope, actor)) {
                        is ApiResult.Success -> capabilities = config.value.mux?.capabilities.orEmpty()
                        else -> capabilities = emptyMap()
                    }
                    if (!repository.isCurrent(actor)) {
                        context = null
                        snapshot = null
                        capabilities = emptyMap()
                        busy = false
                        notice.text = getString(R.string.organization_scope_changed)
                        render()
                        return@launch
                    }
                    notice.text = if (uncertainRequestId == null) getString(R.string.organization_ready)
                    else getString(R.string.organization_unknown_notice)
                }
                is ApiResult.Failure -> { snapshot = null; capabilities = emptyMap(); notice.text = getString(R.string.organization_unavailable) }
                is ApiResult.NotModified -> { snapshot = null; capabilities = emptyMap(); notice.text = getString(R.string.organization_unavailable) }
            }
            busy = false
            render()
        }
    }

    private fun render() {
        rows.removeAllViews()
        renderStatusButton()
        val current = snapshot ?: return
        val workspaces = current.workspaces.filter { it.host == scope.host }.sortedBy(WorkspaceSummary::number)
        if (workspaces.isEmpty()) {
            rows.addView(TextView(this).apply { text = getString(R.string.organization_empty); textSize = 15f }, fullWidth())
            return
        }
        workspaces.forEachIndexed { index, workspace ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_pane_card)
            }
            card.addView(TextView(this).apply {
                text = getString(R.string.organization_workspace_summary, workspace.number, workspace.label, workspace.tabCount, workspace.paneCount)
                textSize = 16f
                setTextColor(getColor(R.color.collie_on_background))
                contentDescription = text
            }, fullWidth())
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button(R.string.organization_open_tabs) {
                expandedWorkspaceId = if (expandedWorkspaceId == workspace.workspaceId) null else workspace.workspaceId
                render()
            }, weightWidth())
            actions.addView(button(R.string.organization_rename, "workspaceRename") { promptRename(workspace.label) { label -> runAction(OrganizationAction.RenameWorkspace(workspace.workspaceId, label)) } }, weightWidth())
            actions.addView(button(R.string.organization_focus, "workspaceFocus") { runAction(OrganizationAction.FocusWorkspace(workspace.workspaceId)) }, weightWidth())
            actions.addView(button(R.string.organization_close, "workspaceClose") { planCloseWorkspace(workspace) }, weightWidth())
            card.addView(actions, fullWidth())
            val order = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            order.addView(button(R.string.organization_move_up, "workspaceMove", disabled = index == 0) { runAction(OrganizationAction.MoveWorkspace(workspace.workspaceId, (index - 1).coerceAtLeast(0))) }, weightWidth())
            order.addView(button(R.string.organization_move_down, "workspaceMove", disabled = index == workspaces.lastIndex) { runAction(OrganizationAction.MoveWorkspace(workspace.workspaceId, index + 2)) }, weightWidth())
            card.addView(order, fullWidth())
            if (expandedWorkspaceId == workspace.workspaceId) renderTabs(card, workspace, current)
            rows.addView(card, fullWidth().apply { topMargin = dp(8) })
        }
    }

    private fun renderTabs(card: LinearLayout, workspace: WorkspaceSummary, state: SnapshotResponse) {
        // Herdr preserves tab numbers while changing array order; render the wire order verbatim.
        val tabs = state.tabs.filter { it.workspaceId == workspace.workspaceId && it.host == scope.host }
        tabs.forEachIndexed { index, tab ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(4), dp(4)) }
            line.addView(TextView(this).apply {
                text = getString(R.string.organization_tab_summary, tab.number, tab.label, tab.paneCount)
                textSize = 14f
                setTextColor(getColor(R.color.collie_on_background))
            }, fullWidth())
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button(R.string.organization_rename, "renameTab") { promptRename(tab.label) { label -> runAction(OrganizationAction.RenameTab(tab.tabId, label)) } }, weightWidth())
            actions.addView(button(R.string.organization_focus, "tabFocus") { runAction(OrganizationAction.FocusTab(tab.tabId)) }, weightWidth())
            actions.addView(button(R.string.organization_close, "closeTab") { planCloseTab(tab.tabId, tab.label, tab.paneCount) }, weightWidth())
            line.addView(actions, fullWidth())
            if (capabilities["tabTransfer"] == true) {
                line.addView(button(R.string.tab_transfer_open, disabled = context?.let(repository::isCurrent) != true) {
                    val actor = context ?: return@button
                    if (!repository.isCurrent(actor)) return@button
                    transferLauncher.launch(TabTransferActivity.intent(this, scope, tab.tabId, workspace.workspaceId))
                }, fullWidth())
            }
            val move = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            move.addView(button(R.string.organization_move_up, "tabMove", disabled = index == 0) { runAction(OrganizationAction.MoveTab(tab.tabId, (index - 1).coerceAtLeast(0))) }, weightWidth())
            move.addView(button(R.string.organization_move_down, "tabMove", disabled = index == tabs.lastIndex) { runAction(OrganizationAction.MoveTab(tab.tabId, index + 2)) }, weightWidth())
            line.addView(move, fullWidth())
            val paneRows = (state.agents + state.shellPanes).filter {
                it.tabId == tab.tabId && it.workspaceId == workspace.workspaceId &&
                    it.host == scope.host && it.session == scope.session
            }
            if (paneRows.isNotEmpty()) {
                line.addView(TextView(this).apply {
                    text = getString(R.string.organization_transfer_pane_guidance)
                    textSize = 12f
                    setTextColor(getColor(R.color.collie_muted))
                    contentDescription = text
                }, fullWidth())
                paneRows.forEach { pane ->
                    val paneLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    paneLine.addView(TextView(this).apply {
                        text = getString(R.string.organization_pane_summary, pane.paneLabel ?: pane.paneId, pane.paneId, pane.status.name.lowercase())
                        textSize = 13f
                        contentDescription = text
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    paneLine.addView(button(R.string.organization_move_pane, disabled = capabilities["paneMove"] != true || context?.let(repository::isCurrent) != true) {
                        val actor = context ?: return@button
                        if (!repository.isCurrent(actor)) return@button
                        layoutLauncher.launch(android.content.Intent(this, LayoutActivity::class.java).apply {
                            putExtra(LayoutActivity.EXTRA_PANE_ID, pane.paneId)
                            putExtra(LayoutActivity.EXTRA_HOST, scope.host)
                            putExtra(LayoutActivity.EXTRA_SESSION, scope.session)
                        })
                    }, weightWidth())
                    line.addView(paneLine, fullWidth())
                }
            }
            card.addView(line, fullWidth())
        }
    }

    private fun showReorderDialog() {
        val items = snapshot?.workspaces?.filter { it.host == scope.host }?.sortedBy { it.number }.orEmpty()
        if (items.size < 2 || !supports("workspaceMoveBlock")) return
        val selected = BooleanArray(items.size)
        val labels = items.map { getString(R.string.organization_workspace_choice, it.number, it.label) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.organization_select_block)
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton(R.string.organization_choose_anchor) { _, _ ->
                val ids = items.filterIndexed { index, _ -> selected[index] }.map { it.workspaceId }
                if (ids.isEmpty()) return@setPositiveButton
                val remaining = items.filterNot { it.workspaceId in ids }
                val anchors = remaining.map { getString(R.string.organization_workspace_choice, it.number, it.label) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.organization_before_workspace)
                    .setItems(anchors + getString(R.string.organization_at_end)) { _, which ->
                        val before = if (which == remaining.size) null else remaining.getOrNull(which)?.workspaceId ?: return@setItems
                        runAction(OrganizationAction.ReorderWorkspaces(ids, before))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun planCloseWorkspace(workspace: WorkspaceSummary) {
        runPlan(OrganizationAction.CloseWorkspace(workspace.workspaceId, false)) { plan ->
            val required = (plan.effects["groupCloseRequired"] as? JsonPrimitive)?.booleanOrNull == true
            if (required) {
                val names = stringArray(plan.effects["groupPreviewIds"]).map { id ->
                    snapshot?.workspaces?.firstOrNull { it.workspaceId == id }?.label ?: id
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.organization_group_close_title)
                    .setMessage(getString(R.string.organization_group_close_message, names.joinToString("\n• "), paneEffectSummary(plan.effects)))
                    .setPositiveButton(R.string.organization_review_group_close) { _, _ ->
                        runPlan(OrganizationAction.CloseWorkspace(workspace.workspaceId, true)) { groupPlan -> confirmClose(groupPlan, workspace.label) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else confirmClose(plan, workspace.label)
        }
    }

    private fun planCloseTab(tabId: String, label: String, paneCount: Int) {
        runPlan(OrganizationAction.CloseTab(tabId)) { plan ->
            AlertDialog.Builder(this)
                .setTitle(R.string.organization_close_tab_title)
                .setMessage(getString(R.string.organization_close_tab_message, label, paneEffectSummary(plan.effects, paneCount)))
                .setPositiveButton(R.string.organization_close_confirm) { _, _ -> applyPlan(OrganizationAction.CloseTab(tabId), plan) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmClose(plan: OrganizationPlanResponse, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.organization_close_title)
            .setMessage(getString(R.string.organization_close_message, label, paneEffectSummary(plan.effects)))
            .setPositiveButton(R.string.organization_close_confirm) { _, _ ->
                val action = OrganizationAction.CloseWorkspace(
                    workspaceId = (plan.action["workspaceId"] as? JsonPrimitive)?.contentOrNull ?: return@setPositiveButton,
                    closeGroup = (plan.action["closeGroup"] as? JsonPrimitive)?.booleanOrNull == true,
                )
                applyPlan(action, plan)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAction(action: OrganizationAction) {
        runPlan(action) { plan -> applyPlan(action, plan) }
    }

    private fun runPlan(action: OrganizationAction, onReady: (OrganizationPlanResponse) -> Unit) {
        if (busy || uncertainRequestId != null || !supports(action.capability) || !repository.writesAllowed(scope)) return
        busy = true
        notice.text = getString(R.string.organization_loading)
        val actor = context ?: repository.readContext(scope)
        if (actor == null) { busy = false; notice.text = getString(R.string.organization_unavailable); render(); return }
        context = actor
        render()
        lifecycleScope.launch {
            val result = repository.planOrganization(scope, action, actor)
            if (!repository.isCurrent(actor)) { busy = false; notice.text = getString(R.string.organization_scope_changed); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> if (result.value.validFor(action)) {
                    planActors[result.value.planToken] = actor
                    while (planActors.size > 8) planActors.remove(planActors.keys.first())
                    onReady(result.value)
                } else notice.text = getString(R.string.organization_plan_invalid)
                is ApiResult.Failure -> notice.text = getString(R.string.organization_plan_failed)
                is ApiResult.NotModified -> notice.text = getString(R.string.organization_plan_failed)
            }
            render()
        }
    }

    private fun applyPlan(action: OrganizationAction, plan: OrganizationPlanResponse) {
        val actor = planActors.remove(plan.planToken) ?: return
        if (busy || uncertainRequestId != null || context !== actor || !repository.isCurrent(actor) || !repository.writesAllowed(scope)) {
            notice.text = getString(R.string.organization_scope_changed)
            return
        }
        val requestId = java.util.UUID.randomUUID().toString()
        busy = true
        notice.text = getString(R.string.organization_sending)
        render()
        lifecycleScope.launch {
            val result = repository.applyOrganization(scope, action, requestId, plan.planToken, actor)
            if (!repository.isCurrent(actor)) { uncertainRequestId = requestId; busy = false; notice.text = getString(R.string.organization_unknown_notice); render(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> {
                    if (result.value.validFor(requestId, action) && result.value.ok) {
                        notice.text = getString(R.string.organization_succeeded)
                        refreshAfterMutation(actor)
                    } else {
                        uncertainRequestId = requestId
                        notice.text = getString(R.string.organization_unknown_notice)
                        render()
                    }
                }
                is ApiResult.Failure -> {
                    if (result.error is ApiFailure.Http && result.error.source == com.lateapex.collie.network.FailureSource.COLLIE && result.error.factor == com.lateapex.collie.network.FailureFactor.OPERATION && result.error.status in 400..499) {
                        notice.text = getString(R.string.organization_refused)
                        refresh()
                    } else {
                        uncertainRequestId = requestId
                        notice.text = getString(R.string.organization_unknown_notice)
                        render()
                    }
                }
                is ApiResult.NotModified -> {
                    uncertainRequestId = requestId
                    notice.text = getString(R.string.organization_unknown_notice)
                    render()
                }
            }
        }
    }

    private suspend fun refreshAfterMutation(actor: ScopedReadContext) {
        if (!repository.isCurrent(actor)) return
        val result = repository.paneSnapshot(scope, actor)
        if (!repository.isCurrent(actor)) return
        if (result is ApiResult.Success) snapshot = result.value
        render()
    }

    private fun reconcileUnknown() {
        val requestId = uncertainRequestId ?: return
        if (busy) return
        val actor = context ?: repository.readContext(scope) ?: return
        if (!repository.isCurrent(actor)) return
        context = actor
        busy = true
        render()
        lifecycleScope.launch {
            val receipt = repository.controlOperation(scope, requestId, actor)
            if (context !== actor || !repository.isCurrent(actor)) {
                busy = false
                notice.text = getString(R.string.organization_scope_changed)
                render()
                return@launch
            }
            when (receipt) {
                is ApiResult.Success -> {
                    when (receipt.value.operation.state) {
                        "succeeded", "refused" -> {
                            val state = receipt.value.operation.state
                            val refreshed = repository.paneSnapshot(scope, actor)
                            if (context !== actor || !repository.isCurrent(actor)) {
                                busy = false
                                notice.text = getString(R.string.organization_scope_changed)
                                render()
                                return@launch
                            }
                            if (refreshed is ApiResult.Success) snapshot = refreshed.value
                            uncertainRequestId = null
                            notice.text = getString(if (state == "succeeded") R.string.organization_succeeded else R.string.organization_refused)
                        }
                        else -> notice.text = getString(R.string.organization_unknown_notice)
                    }
                }
                is ApiResult.Failure -> showReconcileAcknowledgement()
                is ApiResult.NotModified -> notice.text = getString(R.string.organization_unknown_notice)
            }
            busy = false
            render()
        }
    }

    private fun showReconcileAcknowledgement() {
        AlertDialog.Builder(this)
            .setTitle(R.string.organization_reconcile_title)
            .setMessage(R.string.organization_reconcile_message)
            .setPositiveButton(R.string.organization_reconcile_done) { _, _ ->
                uncertainRequestId = null
                notice.text = getString(R.string.organization_ready)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun paneEffectSummary(effects: JsonObject, fallbackCount: Int? = null): String {
        val panes = effects["panes"] as? JsonArray
        val labels = panes?.mapNotNull { (it as? JsonObject)?.get("label") as? JsonPrimitive }?.mapNotNull { it.contentOrNull }.orEmpty()
        val count = labels.size.takeIf { it > 0 } ?: fallbackCount ?: 0
        val names = labels.joinToString("\n• ")
        return if (names.isBlank()) getString(R.string.organization_process_count, count)
        else getString(R.string.organization_process_list, count, names)
    }

    private fun stringArray(value: kotlinx.serialization.json.JsonElement?): List<String> =
        (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun capabilityAvailable(name: String): Boolean = capabilities[name] == true &&
        // These are legacy mux verbs too; the reviewed flow also needs the new runtime identity
        // witness, represented by the schema-gated organization capability.
        (name !in setOf("renameTab", "closeTab") || capabilities["workspaceRename"] == true)
    private fun supports(name: String): Boolean = capabilityAvailable(name) && uncertainRequestId == null
    private fun button(label: Int, capability: String? = null, disabled: Boolean = false, allowUnknown: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = getString(label)
            textSize = 12f
            minHeight = dp(48)
            isAllCaps = false
            isEnabled = !busy && !disabled && (uncertainRequestId == null || allowUnknown) && (capability == null || capabilityAvailable(capability)) && (!allowUnknown || this@OrganizeSpacesActivity.context?.let(repository::isCurrent) == true)
            setOnClickListener { onClick() }
        }
    private fun promptRename(current: String, onSubmit: (String) -> Unit) {
        val actor = context
        val input = EditText(this).apply {
            setText(current)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(128))
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(this).setTitle(R.string.organization_rename_title).setView(input)
            .setPositiveButton(R.string.organization_save) { _, _ ->
                if (actor == null || context !== actor || !repository.isCurrent(actor)) {
                    notice.text = getString(R.string.organization_scope_changed)
                } else input.text.toString().takeIf { it.isNotBlank() }?.let(onSubmit)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun renderStatusButton() {
        if (uncertainRequestId == null) return
        val control = button(R.string.organization_check_outcome, allowUnknown = true, onClick = ::reconcileUnknown)
        rows.addView(control, fullWidth())
    }
    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun weightWidth() = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.setMarginEnd(dp(4)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_HOST = "organization_host"
        private const val EXTRA_SESSION = "organization_session"
        private const val EXTRA_VIEW_ALL = "organization_view_all"
        private const val STATE_UNCERTAIN_ID = "organization_uncertain_request"
        fun intent(context: Context, scope: Scope): Intent = Intent(context, OrganizeSpacesActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host)
            putExtra(EXTRA_SESSION, scope.session)
            putExtra(EXTRA_VIEW_ALL, scope.viewAll)
        }
    }
}
