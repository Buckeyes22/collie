package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.HostPlugin
import com.lateapex.collie.network.HostPluginAction
import com.lateapex.collie.network.HostPluginPaneRequest
import com.lateapex.collie.network.HostPluginUninstallRequest
import java.util.UUID
import kotlinx.coroutines.launch

/** Manifest-derived action and pane controls for one installed plugin. */
class PluginDetailsActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private val pluginId get() = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
    private val scope get() = Scope(host = intent.getStringExtra(EXTRA_HOST))
    private lateinit var content: LinearLayout
    private lateinit var session: PluginActivitySession
    private var busy = false
    private var plugin: HostPlugin? = null
    private val openedPanes = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = restorePluginActivitySession(lastCustomNonConfigurationInstance, savedInstanceState, repository, scope, PluginReceiptStore(applicationContext))
        window.prepareEdgeToEdgeContent()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(MaterialButton(this).apply { setText(R.string.host_plugins_back); setOnClickListener { finish() } })
        bar.addView(TextView(this).apply { setText(R.string.host_plugin_details_title); textSize = 20f; setPadding(dp(12), 0, 0, 0) })
        root.addView(bar)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.applyAppTypeface(); window.applySafeContentInsets(root)
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(outState: Bundle) { session.save(outState); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); load() }

    private fun load() {
        if (busy) return
        if (!isCurrent()) { stale(); return }
        busy = true
        content.removeAllViews(); message(getString(R.string.host_plugins_loading))
        lifecycleScope.launch {
            val actor = session.actor ?: return@launch stale()
            val inventory = repository.hostPlugins(session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            if (inventory !is ApiResult.Success) { busy = false; content.removeAllViews(); message(getString(R.string.host_plugins_unavailable)); return@launch }
            val selected = inventory.value.plugins.firstOrNull { it.id == pluginId }
            if (selected == null) { busy = false; content.removeAllViews(); message(getString(R.string.host_plugin_missing)); return@launch }
            plugin = selected
            val actions = repository.hostPluginActions(pluginId, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            val logs = repository.hostPluginLogs(pluginId, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            content.removeAllViews()
            busy = false
            message("${selected.name} · ${selected.version}")
            if (!selected.enabled) message(getString(R.string.host_plugins_disabled))
            selected.description?.takeIf(String::isNotBlank)?.let(::message)
            selected.source.owner?.let { owner -> message(getString(R.string.host_plugin_source, owner, selected.source.repo.orEmpty(), selected.source.requestedRef.orEmpty())) }
            selected.warnings.forEach { message(getString(R.string.host_plugin_warning, it)) }
            button(if (selected.enabled) R.string.host_plugin_disable else R.string.host_plugin_enable) { confirmEnabled(!selected.enabled) }
            button(R.string.host_plugin_configuration) { if (isCurrent()) startActivity(PluginConfigActivity.intent(this@PluginDetailsActivity, session.scope, pluginId)) else stale() }
            button(R.string.plugin_unlink) { confirmUnlink() }
            button(R.string.plugin_uninstall) { confirmUninstall() }
            when (actions) {
                is ApiResult.Success -> {
                    message(getString(R.string.host_plugin_actions_heading))
                    if (actions.value.actions.isEmpty()) message(getString(R.string.host_plugin_no_actions))
                    actions.value.actions.forEach { action -> actionRow(action) }
                }
                is ApiResult.Failure -> message(getString(R.string.host_plugin_actions_unavailable))
                is ApiResult.NotModified -> message(getString(R.string.host_plugin_actions_unavailable))
            }
            message(getString(R.string.host_plugin_panes_heading))
            if (selected.panes.isEmpty()) message(getString(R.string.host_plugin_no_panes))
            selected.panes.forEach { pane ->
                message("${pane.title} · ${pane.placement}${pane.description?.let { " · $it" }.orEmpty()}")
                button(getString(R.string.plugin_open_pane, pane.title)) { openPane(pane.id, pane.placement) }
            }
            openedPanes.toMap().forEach { (entrypoint, paneId) ->
                message(getString(R.string.plugin_pane_opened, paneId))
                button(getString(R.string.plugin_focus_pane, paneId)) { navigatePane(entrypoint, paneId, close = false) }
                button(getString(R.string.plugin_close_pane, paneId)) { navigatePane(entrypoint, paneId, close = true) }
            }
            message(getString(R.string.host_plugin_logs_heading))
            renderPendingReceipts()
            when (logs) {
                is ApiResult.Success -> if (logs.value.logs.isEmpty()) message(getString(R.string.host_plugin_no_logs)) else logs.value.logs.forEach { row ->
                    message("${row.status.uppercase()} · ${row.actionId ?: row.event ?: row.id}")
                    row.stdout?.takeIf(String::isNotBlank)?.let(::message)
                    row.stderr?.takeIf(String::isNotBlank)?.let(::message)
                    row.error?.takeIf(String::isNotBlank)?.let(::message)
                }
                is ApiResult.Failure -> message(getString(R.string.host_plugin_logs_unavailable))
                is ApiResult.NotModified -> message(getString(R.string.host_plugin_logs_unavailable))
            }
        }
    }

    private fun actionRow(action: HostPluginAction) {
        val reasons = buildList {
            if (!action.enabled) add(getString(R.string.host_plugins_disabled))
            if (!action.platformSupported) add(getString(R.string.host_plugin_platform_unsupported))
        }
        if (action.description != null) message("${action.title}: ${action.description}") else message(action.title)
        button(R.string.host_plugin_run_action) {
            AlertDialog.Builder(this).setTitle(action.title)
                .setMessage(action.description ?: getString(R.string.host_plugin_action_confirm))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.host_plugin_action_run) { _, _ -> runAction(action) }
                .show()
        }.also { it.isEnabled = reasons.isEmpty() && !busy }
        reasons.forEach(::message)
    }

    private fun runAction(action: HostPluginAction) {
        if (!isCurrent() || busy) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.invokeHostPluginAction(pluginId, action.actionId, UUID.randomUUID().toString(), session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> { message(getString(R.string.host_plugin_action_started, result.value.action.status)); load() }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.host_plugin_action_failed))
                is ApiResult.NotModified -> message(getString(R.string.host_plugins_stale))
            }
        }
    }

    private fun openPane(entrypoint: String, placement: String) {
        if (!isCurrent() || busy) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.openHostPluginPane(pluginId, HostPluginPaneRequest(entrypoint, placement), session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> {
                    val opened = result.value.pane
                    opened.paneId?.let { openedPanes[entrypoint] = it }
                    message(if (opened.paneId == null) "${opened.placement} plugin pane opened; the runtime did not return a pane identity." else "Opened plugin pane ${opened.paneId}.")
                    load()
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: "Could not open plugin pane.")
                is ApiResult.NotModified -> stale()
            }
        }
    }

    private fun navigatePane(entrypoint: String, paneId: String, close: Boolean) {
        if (!isCurrent() || busy) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = if (close) repository.closeHostPluginPane(pluginId, paneId, session.scope, actor) else repository.focusHostPluginPane(pluginId, paneId, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> { if (close) openedPanes.remove(entrypoint); load() }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: "Could not ${if (close) "close" else "focus"} plugin pane.")
                is ApiResult.NotModified -> stale()
            }
        }
    }

    private fun confirmEnabled(enabled: Boolean) {
        if (busy || !isCurrent()) { stale(); return }
        val actor = session.actor ?: return stale()
        AlertDialog.Builder(this).setTitle(if (enabled) R.string.host_plugin_enable else R.string.host_plugin_disable)
            .setMessage(if (enabled) R.string.host_plugin_enable_effect else R.string.host_plugin_disable_effect)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.host_plugin_confirm) { _, _ -> if (isCurrent() && repository.isCurrent(actor)) setEnabled(enabled, actor) else stale() }.show()
    }

    private fun confirmUninstall() {
        if (busy || !isCurrent()) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.planHostPluginUninstall(pluginId, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            if (result !is ApiResult.Success) { message((result as? ApiResult.Failure)?.error?.pluginMessage ?: "Plugin removal plan unavailable"); return@launch }
            val plan = result.value.plan
                    AlertDialog.Builder(this@PluginDetailsActivity).setTitle(R.string.plugin_review_removal)
                .setMessage(getString(R.string.plugin_removal_effect, plan.effect))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.plugin_remove) { _, _ -> if (isCurrent() && repository.isCurrent(actor)) uninstall(plan.token, actor) else stale() }.show()
        }
    }

    private fun uninstall(planToken: String, actor: ScopedReadContext) {
        if (!isCurrent() || busy) { stale(); return }
        busy = true
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            if (!session.track(requestId, PluginReceiptKind.OWNER_OPERATION)) { message(getString(R.string.plugin_receipt_retain_failed, getString(R.string.plugin_removal))); return@launch }
            val result = repository.applyHostPluginUninstall(pluginId, HostPluginUninstallRequest(requestId, planToken), session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            if (result is ApiResult.Success) {
                if (result.value.operation.state == "succeeded") { session.reconcile(requestId); finish() }
                else if (result.value.operation.state == "refused") { session.reconcile(requestId); message(getString(R.string.plugin_removal_refused, result.value.operation.code.orEmpty())) }
        else retainForReceipt(requestId, getString(R.string.plugin_removal_state, result.value.operation.state))
            } else {
                retainForReceipt(requestId, "Removal result is not confirmed.")
            }
        }
    }

    private fun setEnabled(enabled: Boolean, actor: ScopedReadContext) {
        if (!isCurrent()) { stale(); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.setHostPluginEnabled(pluginId, enabled, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> load()
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.host_plugins_unavailable))
                is ApiResult.NotModified -> stale()
            }
        }
    }

    private fun confirmUnlink() {
        if (busy || !isCurrent()) { stale(); return }
        val actor = session.actor ?: return stale()
        AlertDialog.Builder(this).setTitle(R.string.plugin_unlink)
            .setMessage(R.string.plugin_unlink_effect)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugin_unlink) { _, _ -> if (isCurrent() && repository.isCurrent(actor)) unlink(actor) else stale() }.show()
    }

    private fun unlink(actor: ScopedReadContext) {
        if (!isCurrent() || busy) { stale(); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.unlinkHostPlugin(pluginId, session.scope, actor)
            if (!isCurrent()) { stale(); return@launch }
            busy = false
            when (result) {
                is ApiResult.Success -> load()
                is ApiResult.Failure -> {
                    content.removeAllViews()
                    message(getString(R.string.plugin_unlink_unconfirmed))
                    button(R.string.plugin_refresh_inventory) { load() }
                }
                is ApiResult.NotModified -> stale()
            }
        }
    }

    private fun retainForReceipt(requestId: String, label: String) {
        busy = false
        content.removeAllViews(); message("$label Check the receipt before taking any further action.")
        button(getString(R.string.plugin_check_receipt, requestId)) { checkReceipt(requestId) }
    }

    private fun renderPendingReceipts() {
        if (session.receiptContext(repository) != null) {
            session.pendingReceipts.keys.forEach { requestId -> button(getString(R.string.plugin_check_receipt, requestId)) { checkReceipt(requestId) } }
        } else if (session.pendingReceipts.isNotEmpty()) {
            message(getString(R.string.plugin_receipt_profile_unavailable, session.pendingReceipts.keys.joinToString()))
        }
    }

    private fun checkReceipt(requestId: String) {
        val receiptActor = session.receiptContext(repository) ?: run { stale(); return }
        lifecycleScope.launch {
            val receipt = repository.readPluginReceipt(requestId, session.scope, receiptActor, session.kind(requestId) ?: PluginReceiptKind.LEGACY_UNKNOWN)
            if (session.receiptContext(repository) !== receiptActor) return@launch stale()
            when (receipt) {
                is PluginReceiptRead.Success -> {
                    val state = receipt
                    if (state.state == "succeeded" || state.state == "refused") session.reconcile(requestId)
                    content.removeAllViews(); message(if (state.code == null) getString(R.string.plugin_details_operation_receipt, state.state) else getString(R.string.plugin_details_operation_receipt_code, state.state, state.code))
                    if (state.state == "running" || state.state == "pending" || state.state == "unknown") button(getString(R.string.plugin_check_receipt, requestId)) { checkReceipt(requestId) }
                    button(R.string.plugin_refresh_plugin) { load() }
                    if (state.state == "succeeded" && state.removed) finish()
                }
                is PluginReceiptRead.Failure -> retainForReceipt(requestId, receipt.message ?: "Receipt remains unavailable.")
                PluginReceiptRead.NotModified -> retainForReceipt(requestId, "Receipt remains unavailable.")
            }
        }
    }

    private fun isCurrent() = session.isWriteCurrent(repository)
    private fun stale() {
        busy = false; content.removeAllViews(); message(getString(R.string.host_plugins_stale))
        renderPendingReceipts()
    }
    private fun message(value: String) { content.addView(TextView(this).apply { text = value; textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }) }
    private fun button(label: Int, action: () -> Unit): MaterialButton = MaterialButton(this).apply { setText(label); isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }.also(content::addView)
    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply { text = label; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }.also(content::addView)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        fun intent(context: Context, scope: Scope, pluginId: String) = Intent(context, PluginDetailsActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host); putExtra(EXTRA_PLUGIN_ID, pluginId)
        }
    }
}
