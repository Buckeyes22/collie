package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.PaneLayoutIntent
import com.lateapex.collie.data.PaneLayoutIntentOwner
import com.lateapex.collie.data.PaneTransferIntent
import com.lateapex.collie.data.PaneTransferLayout
import com.lateapex.collie.data.PaneTransferLayoutCell
import com.lateapex.collie.data.PaneTransferLayoutSplit
import com.lateapex.collie.data.PaneTransferMapping
import com.lateapex.collie.data.PaneTransferMember
import com.lateapex.collie.data.PaneTransferOwner
import com.lateapex.collie.data.PaneTransferRecipeStep
import com.lateapex.collie.data.PaneTransferStepResult
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PaneTransferDestinationGuardDto
import com.lateapex.collie.network.PaneTransferMemberDto
import com.lateapex.collie.network.PaneTransferPlanResponse
import com.lateapex.collie.network.PaneTransferRecipeStepDto
import com.lateapex.collie.network.validForPane
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One owner-bound, explicitly stepped whole-tab transfer; pending work is never resumed on launch. */
class TabTransferActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private val journal get() = (application as CollieApplication).container.paneLayoutIntentStore
    private lateinit var scope: Scope
    private lateinit var tabId: String
    private lateinit var sourceWorkspaceId: String
    private lateinit var rows: LinearLayout
    private lateinit var notice: TextView
    private lateinit var newTabButton: MaterialButton
    private lateinit var newWorkspaceButton: MaterialButton
    @Volatile private var actor: ScopedReadContext? = null
    @Volatile private var parent: PaneTransferIntent? = null
    @Volatile private var flowSupported = false
    @Volatile private var busy = false
    private var authorityRegistration: AutoCloseable? = null
    private var activeDialog: AlertDialog? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawTab = intent.getStringExtra(EXTRA_TAB_ID)?.takeIf(String::isNotBlank)
        val rawWorkspace = intent.getStringExtra(EXTRA_WORKSPACE_ID)?.takeIf(String::isNotBlank)
        if (rawTab == null || rawWorkspace == null) { finish(); return }
        tabId = rawTab
        sourceWorkspaceId = rawWorkspace
        scope = Scope(intent.getStringExtra(EXTRA_HOST), intent.getStringExtra(EXTRA_SESSION))
        window.prepareEdgeToEdgeContent()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)); applyAppTypeface() }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        content.addView(TextView(this).apply { text = getString(R.string.tab_transfer_title); textSize = 22f; contentDescription = text }, fullWidth())
        notice = TextView(this).apply { textSize = 14f; setTextColor(getColor(R.color.collie_muted)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(notice, fullWidth())
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        newTabButton = button(R.string.tab_transfer_new_tab) { chooseWorkspace() }
        newWorkspaceButton = button(R.string.tab_transfer_new_workspace) { promptNewWorkspace() }
        controls.addView(newTabButton, weightWidth())
        controls.addView(newWorkspaceButton, weightWidth())
        content.addView(controls, fullWidth())
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows, fullWidth())
        authorityRegistration = repository.onReadContextInvalidated {
            val currentParent = parent
            if (currentParent?.activeChild != null) journal.markTransferUnknown(currentParent.owner, currentParent.transferId, currentParent.activeChild.requestId)
            val acknowledgement = CountDownLatch(1)
            val wipe = Runnable {
                activeDialog?.dismiss()
                activeDialog = null
                actor = null
                parent = null
                flowSupported = false
                busy = false
                notice.text = getString(R.string.tab_transfer_scope_changed)
                rows.removeAllViews()
                acknowledgement.countDown()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) wipe.run() else {
                mainHandler.post(wipe)
                try { acknowledgement.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        refresh()
    }

    override fun onDestroy() { authorityRegistration?.close(); activeDialog?.dismiss(); activeDialog = null; actor = null; super.onDestroy() }

    private fun refresh() {
        if (busy) return
        busy = true
        flowSupported = false
        notice.text = getString(R.string.tab_transfer_loading)
        render()
        lifecycleScope.launch {
            val captured = repository.readContext(scope)
            if (captured == null) { actor = null; parent = null; busy = false; notice.text = getString(R.string.tab_transfer_unavailable); render(); return@launch }
            actor = captured
            val config = repository.config(scope, captured)
            if (!repository.isCurrent(captured)) { retireLocal(); return@launch }
            flowSupported = (config as? ApiResult.Success)?.value?.let { value ->
                val mux = value.mux
                value.control?.supportsNew("controlOperations") == true &&
                    mux != null && mux.supportsNew("tabTransfer") && mux.supportsNew("paneLayout") && mux.supportsNew("paneMove")
            } == true
            val owner = ownerFor(captured)
            val saved = journal.transfer(owner)
            if (!repository.isCurrent(captured)) { retireLocal(); return@launch }
            val snapshot = repository.paneSnapshot(scope, captured)
            if (!repository.isCurrent(captured)) { retireLocal(); return@launch }
            parent = saved
            when (snapshot) {
                is ApiResult.Success -> {
                    val source = snapshot.value.tabs.find { it.tabId == tabId && it.workspaceId == sourceWorkspaceId && it.host == scope.host }
                    notice.text = when {
                        saved?.activeChild != null -> getString(R.string.tab_transfer_unknown_notice)
                        saved?.state == "complete" -> getString(R.string.tab_transfer_complete)
                        !flowSupported -> getString(R.string.tab_transfer_unavailable)
                        source == null -> getString(R.string.tab_transfer_source_missing)
                        saved != null -> getString(R.string.tab_transfer_progress, saved.mappings.size, saved.sourceMembers.size)
                        else -> getString(R.string.tab_transfer_choose_destination)
                    }
                }
                else -> notice.text = getString(R.string.tab_transfer_unavailable)
            }
            busy = false
            render()
        }
    }

    private fun render() {
        rows.removeAllViews()
        val current = parent
        val canStart = !busy && current == null && flowSupported && actor?.let(repository::isCurrent) == true
        newTabButton.isEnabled = canStart
        newWorkspaceButton.isEnabled = canStart
        if (current == null) {
            if (flowSupported && actor?.let(repository::isCurrent) == true) {
                rows.addView(TextView(this).apply { text = getString(R.string.tab_transfer_description); contentDescription = text; textSize = 14f }, fullWidth())
            }
            return
        }
        rows.addView(TextView(this).apply {
            text = getString(R.string.tab_transfer_roster, current.sourceMembers.size, current.owner.sourceWorkspaceId, current.owner.sourceTabId)
            contentDescription = text; textSize = 15f
        }, fullWidth())
        current.sourceLayout?.let { layout ->
            rows.addView(TextView(this).apply {
                val cells = layout.cells.joinToString(separator = "\n") { cell ->
                    getString(R.string.tab_transfer_source_cell, cell.paneId, cell.x, cell.y, cell.width, cell.height,
                        if (cell.focused) getString(R.string.tab_transfer_source_focused) else "")
                }
                text = getString(R.string.tab_transfer_source_geometry, layout.x, layout.y, layout.width, layout.height,
                    if (layout.zoomed) getString(R.string.tab_transfer_source_zoomed) else "", cells)
                contentDescription = text; textSize = 13f
            }, fullWidth())
        }
        rows.addView(TextView(this).apply {
            text = getString(R.string.tab_transfer_reflow, current.recipe.joinToString { "${it.direction} ${(it.ratio * 100).toInt()}%" })
            contentDescription = text; textSize = 13f
        }, fullWidth())
        current.mappings.forEachIndexed { index, mapping ->
            rows.addView(TextView(this).apply { text = getString(R.string.tab_transfer_moved_row, index + 1, mapping.originalPaneId, mapping.currentPaneId); contentDescription = text; textSize = 13f }, fullWidth())
        }
        if (current.activeChild != null) {
            rows.addView(button(R.string.tab_transfer_check_outcome, disabled = busy || actor?.let(repository::isCurrent) != true) { checkOutcome() }, fullWidth())
            rows.addView(TextView(this).apply { text = getString(R.string.tab_transfer_no_replay); contentDescription = text; textSize = 12f }, fullWidth())
        } else if (current.state == "ready" && current.mappings.size < current.sourceMembers.size) {
            rows.addView(button(R.string.tab_transfer_move_next, disabled = busy || !flowSupported || actor?.let(repository::isCurrent) != true) { moveNext() }, fullWidth())
        }
    }

    private fun chooseWorkspace() {
        if (busy || parent != null) return
        lifecycleScope.launch {
            val captured = actor ?: return@launch
            val result = repository.paneSnapshot(scope, captured)
            if (!repository.isCurrent(captured) || result !is ApiResult.Success) return@launch
            val choices = result.value.workspaces.filter { it.host == scope.host && it.workspaceId != sourceWorkspaceId }
            if (choices.isEmpty()) { notice.text = getString(R.string.tab_transfer_no_other_workspace); render(); return@launch }
            val dialog = AlertDialog.Builder(this@TabTransferActivity).setTitle(R.string.tab_transfer_choose_workspace)
                .setItems(choices.map { it.label }.toTypedArray()) { _, index -> startPlan(buildJsonObject {
                    put("destination", buildJsonObject { put("type", JsonPrimitive("new_tab")); put("workspaceId", JsonPrimitive(choices[index].workspaceId)); put("label", JsonPrimitive(getString(R.string.tab_transfer_default_label))) })
                }) }.setNegativeButton(android.R.string.cancel, null).create()
            activeDialog = dialog
            dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
            dialog.show()
        }
    }

    private fun promptNewWorkspace() {
        if (busy || parent != null) return
        val input = EditText(this).apply { hint = getString(R.string.tab_transfer_workspace_name); contentDescription = hint }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.tab_transfer_new_workspace_title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) } ?: return@setPositiveButton
                startPlan(buildJsonObject { put("destination", buildJsonObject { put("type", JsonPrimitive("new_workspace")); put("label", JsonPrimitive(name)); put("tabLabel", JsonPrimitive(getString(R.string.tab_transfer_default_label))) }) })
            }.setNegativeButton(android.R.string.cancel, null).create()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun startPlan(destination: kotlinx.serialization.json.JsonObject) {
        val captured = actor ?: return
        if (!repository.isCurrent(captured) || busy) return
        busy = true
        notice.text = getString(R.string.tab_transfer_planning)
        render()
        lifecycleScope.launch {
            val snapshot = repository.paneSnapshot(scope, captured)
            if (!repository.isCurrent(captured) || snapshot !is ApiResult.Success) { busy = false; notice.text = getString(R.string.tab_transfer_unavailable); render(); return@launch }
            val sourcePanes = (snapshot.value.agents + snapshot.value.shellPanes).filter { it.tabId == tabId && it.workspaceId == sourceWorkspaceId && it.host == scope.host && it.session == scope.session }
            if (sourcePanes.isEmpty() || sourcePanes.size > 64) { busy = false; notice.text = getString(R.string.tab_transfer_source_missing); render(); return@launch }
            val firstAddress = PaneAddress(scope, sourcePanes.first().paneId)
            val layout = repository.paneLayout(firstAddress, captured)
            if (!repository.isCurrent(captured) || layout !is ApiResult.Success || !layout.value.result.validForPane(firstAddress.paneId, "layout")) { busy = false; notice.text = getString(R.string.tab_transfer_layout_unavailable); render(); return@launch }
            val recipe = balancedRecipe(layout.value.result.layout.area.width, layout.value.result.layout.area.height, sourcePanes.size - 1)
            if (recipe == null) { busy = false; notice.text = getString(R.string.tab_transfer_reflow_infeasible); render(); return@launch }
            val requestBody = buildJsonObject { put("destination", destination); put("recipe", buildJsonArray { recipe.forEach { step -> add(buildJsonObject { put("direction", JsonPrimitive(step.direction)); put("ratio", JsonPrimitive(step.ratio)) }) } }) }
            when (val plan = repository.planTabTransfer(scope, tabId, requestBody, captured)) {
                is ApiResult.Success -> {
                    val response = plan.value
                    val requestedType = (destination["type"] as? JsonPrimitive)?.contentOrNull
                    val requestedWorkspace = (destination["workspaceId"] as? JsonPrimitive)?.contentOrNull
                    val requestedLabel = (destination["label"] as? JsonPrimitive)?.contentOrNull
                    val requestedTabLabel = (destination["tabLabel"] as? JsonPrimitive)?.contentOrNull
                    val destinationMatches = response.destination.type == requestedType && response.destination.workspaceId == requestedWorkspace &&
                        response.destination.label == requestedLabel && response.destination.tabLabel == requestedTabLabel
                    if (!repository.isCurrent(captured) || !response.validForTab(tabId) || response.source.workspaceId != sourceWorkspaceId ||
                        response.source.layout != layout.value.result.layout || response.source.members.map { it.paneId }.toSet() != sourcePanes.map { it.paneId }.toSet() ||
                        response.recipe != recipe.map { PaneTransferRecipeStepDto(it.direction, it.ratio) } || !destinationMatches) {
                        busy = false; notice.text = getString(R.string.tab_transfer_source_changed); render(); return@launch
                    }
                    val parent = PaneTransferIntent(ownerFor(captured), captured.key.connectionGeneration, captured.key.leadEpoch, UUID.randomUUID().toString(),
                        response.destination.type, response.destination.workspaceId, response.destination.label, response.destination.tabLabel,
                        response.recipe.map { PaneTransferRecipeStep(it.direction, it.ratio) }, response.source.members.map { PaneTransferMember(it.paneId, it.witness) },
                        sourceLayout = PaneTransferLayout(response.source.layout.area.x, response.source.layout.area.y,
                            response.source.layout.area.width, response.source.layout.area.height, response.source.layout.zoomed,
                            response.source.layout.focusedPaneId,
                            response.source.layout.panes.map { PaneTransferLayoutCell(it.paneId, it.focused, it.rect.x, it.rect.y, it.rect.width, it.rect.height) },
                            response.source.layout.splits.map { PaneTransferLayoutSplit(it.direction, it.ratio, it.rect.x, it.rect.y, it.rect.width, it.rect.height) }))
                    if (!repository.isCurrent(captured) || !journal.beginTransfer(parent)) {
                        busy = false; notice.text = getString(R.string.tab_transfer_custody_unavailable); render(); return@launch
                    }
                    this@TabTransferActivity.parent = parent
                    notice.text = getString(R.string.tab_transfer_progress, 0, parent.sourceMembers.size)
                }
                else -> notice.text = getString(R.string.tab_transfer_plan_failed)
            }
            busy = false
            render()
        }
    }

    private fun moveNext() {
        val captured = actor ?: return
        val currentParent = parent ?: return
        if (!flowSupported || currentParent.state != "ready" || currentParent.activeChild != null || !repository.isCurrent(captured) || busy) return
        busy = true
        notice.text = getString(R.string.tab_transfer_planning)
        render()
        lifecycleScope.launch {
            val member = currentParent.sourceMembers.firstOrNull { row -> currentParent.mappings.none { it.originalPaneId == row.originalPaneId } }
            if (member == null) { busy = false; notice.text = getString(R.string.tab_transfer_complete); render(); return@launch }
            val remaining = currentParent.sourceMembers.filter { sourceMember -> currentParent.mappings.none { it.originalPaneId == sourceMember.originalPaneId } }
            val address = PaneAddress(scope, member.originalPaneId)
            val destination = if (currentParent.mappings.isEmpty()) {
                if (currentParent.destinationType == "new_tab") {
                    val workspaceId = currentParent.destinationWorkspaceId
                    if (workspaceId == null) { busy = false; notice.text = getString(R.string.tab_transfer_destination_changed); render(); return@launch }
                    buildJsonObject { put("type", JsonPrimitive("new_tab")); put("workspaceId", JsonPrimitive(workspaceId)); put("label", currentParent.destinationLabel?.let(::JsonPrimitive) ?: JsonNull) }
                }
                else buildJsonObject { put("type", JsonPrimitive("new_workspace")); put("label", currentParent.destinationLabel?.let(::JsonPrimitive) ?: JsonNull); put("tabLabel", currentParent.destinationTabLabel?.let(::JsonPrimitive) ?: JsonNull) }
            } else {
                val mapping = currentParent.mappings.first()
                val currentLayout = repository.paneLayout(PaneAddress(scope, mapping.currentPaneId), captured)
                if (!repository.isCurrent(captured) || currentLayout !is ApiResult.Success || !currentLayout.value.result.validForPane(mapping.currentPaneId, "layout") || currentLayout.value.result.layout.tabId != mapping.tabId || currentLayout.value.result.layout.workspaceId != mapping.workspaceId) {
                    busy = false; notice.text = getString(R.string.tab_transfer_destination_changed); render(); return@launch
                }
                val anchor = currentLayout.value.result.layout.panes.maxByOrNull { it.rect.width.toLong() * it.rect.height.toLong() } ?: run { busy = false; notice.text = getString(R.string.tab_transfer_destination_changed); render(); return@launch }
                val recipe = currentParent.recipe.getOrNull(currentParent.mappings.size - 1) ?: run { busy = false; notice.text = getString(R.string.tab_transfer_reflow_infeasible); render(); return@launch }
                buildJsonObject { put("type", JsonPrimitive("tab")); put("tabId", JsonPrimitive(mapping.tabId)); put("split", JsonPrimitive(recipe.direction)); put("targetPaneId", JsonPrimitive(anchor.paneId)); put("ratio", JsonPrimitive(recipe.ratio)) }
            }
            val guard = buildJsonObject {
                put("schema", JsonPrimitive(1)); put("transferId", JsonPrimitive(currentParent.transferId)); put("sourceWorkspaceId", JsonPrimitive(currentParent.owner.sourceWorkspaceId)); put("sourceTabId", JsonPrimitive(currentParent.owner.sourceTabId))
                put("remainingSource", buildJsonArray { remaining.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.originalPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
                if (currentParent.mappings.isEmpty()) put("destination", JsonNull) else {
                    val mapped = currentParent.mappings
                    val target = mapped.first()
                    put("destination", buildJsonObject {
                        put("workspaceId", JsonPrimitive(target.workspaceId)); put("tabId", JsonPrimitive(target.tabId))
                        put("members", buildJsonArray { mapped.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.currentPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
                    })
                }
            }
            val action = buildJsonObject { put("kind", JsonPrimitive("move")); put("destination", destination); put("transfer", guard) }
            val planResult = repository.planPaneLayout(address, action, captured)
            if (!repository.isCurrent(captured) || planResult !is ApiResult.Success || !planResult.value.validForPane(member.originalPaneId, action)) {
                busy = false; notice.text = getString(R.string.tab_transfer_plan_failed); render(); return@launch
            }
            confirmStep(captured, currentParent, member, address, action, planResult.value)
            return@launch
        }
    }

    private fun confirmStep(
        captured: ScopedReadContext,
        transfer: PaneTransferIntent,
        member: PaneTransferMember,
        address: PaneAddress,
        action: kotlinx.serialization.json.JsonObject,
        plan: com.lateapex.collie.network.PaneLayoutPlanResponse,
    ) {
        val target = action["destination"] as? kotlinx.serialization.json.JsonObject
        val targetType = (target?.get("type") as? JsonPrimitive)?.contentOrNull
        val targetId = when (targetType) {
            "new_tab" -> (target?.get("workspaceId") as? JsonPrimitive)?.contentOrNull
            "tab" -> (target?.get("tabId") as? JsonPrimitive)?.contentOrNull
            else -> (target?.get("label") as? JsonPrimitive)?.contentOrNull
        }.orEmpty()
        val destination = getString(when (targetType) {
            "new_tab" -> R.string.tab_transfer_target_new_tab
            "new_workspace" -> R.string.tab_transfer_target_new_workspace
            else -> R.string.tab_transfer_target_existing_tab
        }, targetId)
        // The first confirmation previews the deterministic reflow; subsequent
        // confirmations describe the split consumed by this post-container move.
        val recipe = transfer.recipe.getOrNull((transfer.mappings.size - 1).coerceAtLeast(0))
        val closesSource = (plan.effects["mayCloseSourceTab"] as? JsonPrimitive)?.booleanOrNull == true
        val sourceEffect = getString(if (closesSource) R.string.tab_transfer_effect_source_may_close else R.string.tab_transfer_effect_source_remains)
        val detail = getString(R.string.tab_transfer_confirm_message, transfer.mappings.size + 1, transfer.sourceMembers.size,
            member.originalPaneId, destination, recipe?.direction ?: getString(R.string.tab_transfer_reflow_unavailable),
            recipe?.let { (it.ratio * 100).toInt() } ?: 0, sourceEffect,
            getString(R.string.tab_transfer_effect_focus_fallback), getString(R.string.tab_transfer_effect_workspace_remains))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tab_transfer_confirm_title)
            .setMessage(detail)
            .setPositiveButton(R.string.tab_transfer_confirm_move) { _, _ ->
                activeDialog = null
                dispatchConfirmedStep(captured, transfer, member, address, action, plan)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                activeDialog = null
                busy = false
                render()
            }
            .create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
            if (busy && repository.isCurrent(captured) && parent?.transferId == transfer.transferId &&
                parent?.activeChild == null && actor === captured && !dialog.isShowing) {
                busy = false
                render()
            }
        }
        dialog.show()
    }

    private fun dispatchConfirmedStep(
        captured: ScopedReadContext,
        currentParent: PaneTransferIntent,
        member: PaneTransferMember,
        address: PaneAddress,
        action: kotlinx.serialization.json.JsonObject,
        plan: com.lateapex.collie.network.PaneLayoutPlanResponse,
    ) {
        val requestId = UUID.randomUUID().toString()
        if (plan.expiresAt <= System.currentTimeMillis() || !repository.isCurrent(captured) || parent != currentParent || actor !== captured) {
            busy = false
            notice.text = getString(R.string.tab_transfer_plan_expired)
            render()
            return
        }
        val frozen = PaneLayoutIntent(PaneLayoutIntentOwner.from(captured, member.originalPaneId), captured.key.connectionGeneration, captured.key.leadEpoch,
            requestId, plan.planToken, action.toString(), currentParent.owner.sourceTabId, currentParent.owner.sourceWorkspaceId)
        if (!repository.isCurrent(captured) || !journal.reserveTransferChild(currentParent.owner, currentParent.transferId, frozen)) {
            busy = false; notice.text = getString(R.string.tab_transfer_custody_unavailable); render(); return
        }
        parent = journal.transfer(currentParent.owner)
        if (!repository.isCurrent(captured)) {
            journal.markTransferUnknown(currentParent.owner, currentParent.transferId, requestId)
            busy = false; notice.text = getString(R.string.tab_transfer_unknown_notice); render(); return
        }
        lifecycleScope.launch {
            when (val result = repository.applyPaneLayout(address, requestId, plan.planToken, action, captured)) {
                is ApiResult.Success -> {
                    val operation = result.value.operation
                    val moveResult = operation?.result
                    val receipt = moveResult?.transfer
                    if (!repository.isCurrent(captured) || operation == null || moveResult == null || !operation.validFor(requestId) || operation.state != "succeeded" || receipt == null || !receipt.changed || receipt.processWitness != member.witness) {
                        journal.markTransferUnknown(currentParent.owner, currentParent.transferId, requestId)
                        notice.text = getString(R.string.tab_transfer_unknown_notice)
                    } else if (!journal.completeTransferChild(currentParent.owner, currentParent.transferId, requestId,
                            PaneTransferStepResult(receipt.changed, moveResult.paneId, receipt.targetWorkspaceId, receipt.targetTabId, receipt.processWitness,
                                receipt.createdTabId, receipt.createdWorkspaceId, receipt.closedTabId, receipt.closedWorkspaceId))) {
                        journal.markTransferUnknown(currentParent.owner, currentParent.transferId, requestId)
                        notice.text = getString(R.string.tab_transfer_custody_unavailable)
                    } else {
                        parent = journal.transfer(currentParent.owner)
                        notice.text = getString(R.string.tab_transfer_progress, parent?.mappings?.size ?: 0, currentParent.sourceMembers.size)
                    }
                }
                else -> {
                    journal.markTransferUnknown(currentParent.owner, currentParent.transferId, requestId)
                    notice.text = getString(R.string.tab_transfer_unknown_notice)
                }
            }
            busy = false
            render()
        }
    }

    private fun checkOutcome() {
        val captured = actor ?: return
        val currentParent = parent ?: return
        val child = currentParent.activeChild ?: return
        if (!repository.isCurrent(captured) || busy) return
        busy = true
        notice.text = getString(R.string.tab_transfer_checking)
        render()
        lifecycleScope.launch {
            when (val outcome = repository.controlOperation(scope, child.requestId, captured)) {
                is ApiResult.Success -> {
                    val operation = outcome.value.operation
                    if (!repository.isCurrent(captured) || !operation.validFor(child.requestId)) notice.text = getString(R.string.tab_transfer_unknown_notice)
                    else if (operation.state == "succeeded" && operation.result?.transfer?.let { it.changed && it.processWitness == currentParent.sourceMembers.firstOrNull { member -> member.originalPaneId == child.owner.paneId }?.witness } == true) {
                        val moveResult = operation.result ?: run { notice.text = getString(R.string.tab_transfer_unknown_notice); busy = false; render(); return@launch }
                        val transfer = moveResult.transfer ?: run { notice.text = getString(R.string.tab_transfer_unknown_notice); busy = false; render(); return@launch }
                        val saved = journal.completeTransferChild(currentParent.owner, currentParent.transferId, child.requestId,
                            PaneTransferStepResult(transfer.changed, moveResult.paneId, transfer.targetWorkspaceId, transfer.targetTabId, transfer.processWitness,
                                transfer.createdTabId, transfer.createdWorkspaceId, transfer.closedTabId, transfer.closedWorkspaceId))
                        parent = journal.transfer(currentParent.owner)
                        notice.text = if (saved) getString(R.string.tab_transfer_progress, parent?.mappings?.size ?: 0, currentParent.sourceMembers.size) else getString(R.string.tab_transfer_custody_unavailable)
                    } else if (operation.state == "refused") {
                        // A refusal is terminal, but no fresh ID is admitted until authoritative roster evidence proves no move happened.
                        if (proveNoEffect(captured, currentParent, child.owner.paneId)) {
                            journal.finishRefusedTransferChild(currentParent.owner, currentParent.transferId, child.requestId)
                            parent = journal.transfer(currentParent.owner)
                            notice.text = getString(R.string.tab_transfer_refused_replan)
                        } else notice.text = getString(R.string.tab_transfer_unknown_notice)
                    } else notice.text = getString(R.string.tab_transfer_unknown_notice)
                }
                else -> notice.text = getString(R.string.tab_transfer_unknown_notice)
            }
            busy = false
            render()
        }
    }

    private suspend fun proveNoEffect(captured: ScopedReadContext, transfer: PaneTransferIntent, paneId: String): Boolean {
        val snapshot = repository.paneSnapshot(scope, captured)
        if (!repository.isCurrent(captured) || snapshot !is ApiResult.Success) return false
        val panes = snapshot.value.agents + snapshot.value.shellPanes
        val remaining = transfer.sourceMembers.filter { member -> transfer.mappings.none { it.originalPaneId == member.originalPaneId } }
        val sourceRows = panes.filter { it.tabId == tabId && it.workspaceId == sourceWorkspaceId && it.host == scope.host && it.session == scope.session }
        if (sourceRows.map { it.paneId }.toSet() != remaining.map { it.originalPaneId }.toSet() || paneId !in sourceRows.map { it.paneId }) return false
        val sourceProof = freshTransferMembers(captured, tabId, sourceWorkspaceId) ?: return false
        if (sourceProof.map { it.paneId to it.witness }.toMap() != remaining.associate { it.originalPaneId to it.witness }) return false
        val mapped = transfer.mappings
        if (mapped.isEmpty()) return repository.isCurrent(captured)
        val target = mapped.first()
        if (mapped.any { it.workspaceId != target.workspaceId || it.tabId != target.tabId }) return false
        val destinationRows = panes.filter { it.tabId == target.tabId && it.workspaceId == target.workspaceId && it.host == scope.host && it.session == scope.session }
        if (destinationRows.map { it.paneId }.toSet() != mapped.map { it.currentPaneId }.toSet()) return false
        val destinationProof = freshTransferMembers(captured, target.tabId, target.workspaceId) ?: return false
        return repository.isCurrent(captured) && destinationProof.map { it.paneId to it.witness }.toMap() == mapped.associate { it.currentPaneId to it.witness }
    }

    /** A read-only planner response carries the current paired-process witnesses for this exact tab. */
    private suspend fun freshTransferMembers(captured: ScopedReadContext, exactTabId: String, exactWorkspaceId: String): List<PaneTransferMemberDto>? {
        if (!repository.isCurrent(captured)) return null
        val snapshot = repository.paneSnapshot(scope, captured)
        if (!repository.isCurrent(captured) || snapshot !is ApiResult.Success) return null
        val sourcePanes = (snapshot.value.agents + snapshot.value.shellPanes).filter {
            it.tabId == exactTabId && it.workspaceId == exactWorkspaceId && it.host == scope.host && it.session == scope.session
        }
        if (sourcePanes.isEmpty() || sourcePanes.size > 64) return null
        val address = PaneAddress(scope, sourcePanes.first().paneId)
        val layout = repository.paneLayout(address, captured)
        if (!repository.isCurrent(captured) || layout !is ApiResult.Success || !layout.value.result.validForPane(address.paneId, "layout")) return null
        val recipe = balancedRecipe(layout.value.result.layout.area.width, layout.value.result.layout.area.height, sourcePanes.size - 1) ?: return null
        val requestBody = buildJsonObject {
            put("destination", buildJsonObject {
                put("type", JsonPrimitive("new_workspace")); put("label", JsonPrimitive("Read-only transfer verification"));
                put("tabLabel", JsonPrimitive("Read-only transfer verification"))
            })
            put("recipe", buildJsonArray { recipe.forEach { step -> add(buildJsonObject { put("direction", JsonPrimitive(step.direction)); put("ratio", JsonPrimitive(step.ratio)) }) } })
        }
        val response = repository.planTabTransfer(scope, exactTabId, requestBody, captured)
        if (!repository.isCurrent(captured) || response !is ApiResult.Success || !response.value.validForTab(exactTabId) ||
            response.value.source.workspaceId != exactWorkspaceId || response.value.source.members.map { it.paneId }.toSet() != sourcePanes.map { it.paneId }.toSet()) return null
        return response.value.source.members
    }

    private fun ownerFor(context: ScopedReadContext) = PaneTransferOwner(context.key.origin, context.key.pairedDeviceIdentity, context.key.host, context.key.session, context.key.subjectIdentity, sourceWorkspaceId, tabId)

    private fun balancedRecipe(width: Int, height: Int, count: Int): List<PaneTransferRecipeStep>? {
        if (count !in 0..63 || width < 1 || height < 1) return null
        val leaves = mutableListOf(width to height)
        val recipe = mutableListOf<PaneTransferRecipeStep>()
        repeat(count) {
            val anchorIndex = leaves.indices.maxByOrNull { leaves[it].first.toLong() * leaves[it].second } ?: return null
            val (w, h) = leaves[anchorIndex]
            val direction = if (w >= h && w > 1) "right" else if (h > 1) "down" else if (w > 1) "right" else return null
            val extent = if (direction == "right") w else h
            val first = extent / 2
            val second = extent - first
            if (first < 1 || second < 1) return null
            leaves.removeAt(anchorIndex)
            if (direction == "right") { leaves.add(anchorIndex, second to h); leaves.add(anchorIndex, first to h) }
            else { leaves.add(anchorIndex, w to second); leaves.add(anchorIndex, w to first) }
            recipe += PaneTransferRecipeStep(direction, 0.5)
        }
        return recipe
    }

    private fun retireLocal() { actor = null; parent = null; flowSupported = false; busy = false; notice.text = getString(R.string.tab_transfer_scope_changed); render() }
    private fun button(label: Int, disabled: Boolean = false, action: () -> Unit) = MaterialButton(this).apply { text = getString(label); isEnabled = !disabled; minHeight = dp(48); setOnClickListener { action() } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun weightWidth() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        private const val EXTRA_HOST = "tab-transfer-host"
        private const val EXTRA_SESSION = "tab-transfer-session"
        private const val EXTRA_TAB_ID = "tab-transfer-tab"
        private const val EXTRA_WORKSPACE_ID = "tab-transfer-workspace"
        fun intent(context: Context, scope: Scope, tabId: String, workspaceId: String): Intent = Intent(context, TabTransferActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host); putExtra(EXTRA_SESSION, scope.session); putExtra(EXTRA_TAB_ID, tabId); putExtra(EXTRA_WORKSPACE_ID, workspaceId)
        }
    }
}
