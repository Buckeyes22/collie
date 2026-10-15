package com.lateapex.collie.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.HostPluginInstallRequest
import com.lateapex.collie.network.HostPluginLinkRequest
import java.util.UUID
import kotlinx.coroutines.launch

/** Native, host-scoped plugin inventory. Every callback retains the entry actor. */
class PluginsActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var content: LinearLayout
    private lateinit var session: PluginActivitySession
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = Scope(host = repository.selectedScope.value.host)
        session = restorePluginActivitySession(lastCustomNonConfigurationInstance, savedInstanceState, repository, scope, PluginReceiptStore(applicationContext))
        window.prepareEdgeToEdgeContent()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(MaterialButton(this).apply { setText(R.string.host_plugins_back); setOnClickListener { finish() } })
        bar.addView(TextView(this).apply { setText(R.string.host_plugins_title); textSize = 20f; setPadding(dp(12), 0, 0, 0) })
        root.addView(bar)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.applyAppTypeface(); window.applySafeContentInsets(root)
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(outState: Bundle) { session.save(outState); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); load() }

    private fun isCurrent(): Boolean = session.isWriteCurrent(repository)

    private fun load() {
        if (loading) return
        if (!isCurrent()) { renderStale(); return }
        loading = true
        content.removeAllViews(); message(getString(R.string.host_plugins_loading))
        lifecycleScope.launch {
            val actor = session.actor ?: return@launch renderStale()
            val result = repository.hostPlugins(session.scope, actor)
            if (!isCurrent()) { loading = false; renderStale(); return@launch }
            content.removeAllViews()
            button(getString(R.string.plugin_install_from_github)) { promptInstall() }
            button(getString(R.string.plugin_link_managed)) { chooseLink() }
            when (result) {
                is ApiResult.Success -> {
                    if (result.value.plugins.isEmpty()) message(getString(R.string.host_plugins_empty))
                    result.value.plugins.forEach { plugin ->
                        button("${plugin.name} · ${plugin.version}${if (plugin.enabled) "" else " · ${getString(R.string.host_plugins_disabled)}"}") {
                            if (!isCurrent()) { renderStale(); return@button }
                            startActivity(PluginDetailsActivity.intent(this@PluginsActivity, session.scope, plugin.id))
                        }
                    }
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.host_plugins_unavailable))
                is ApiResult.NotModified -> message(getString(R.string.host_plugins_stale))
            }
            renderPendingReceipts()
            loading = false
        }
    }

    private fun promptInstall() {
        if (!isCurrent()) { renderStale(); return }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val owner = EditText(this).apply { hint = getString(R.string.plugin_github_owner); setSingleLine(true) }
        val repo = EditText(this).apply { hint = getString(R.string.plugin_repository); setSingleLine(true) }
        val subdir = EditText(this).apply { hint = getString(R.string.plugin_subdirectory_optional); setSingleLine(true) }
        val ref = EditText(this).apply { hint = getString(R.string.plugin_revision_optional); setSingleLine(true) }
        fields.addView(owner); fields.addView(repo); fields.addView(subdir); fields.addView(ref)
        AlertDialog.Builder(this).setTitle(R.string.plugin_plan_install).setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.plugin_review) { _, _ ->
                if (!isCurrent()) { renderStale(); return@setPositiveButton }
                lifecycleScope.launch {
                    val actor = session.actor ?: return@launch renderStale()
                    val planned = repository.planHostPluginInstall(owner.text.toString().trim(), repo.text.toString().trim(), subdir.text.toString().trim().ifEmpty { null }, ref.text.toString().trim().ifEmpty { null }, session.scope, actor)
                    if (!isCurrent()) { renderStale(); return@launch }
                    if (planned !is ApiResult.Success) { message((planned as? ApiResult.Failure)?.error?.pluginMessage ?: "Install plan unavailable"); return@launch }
                    val plan = planned.value.plan
                    val details = "${plan.plugin.name} ${plan.plugin.version}\n${plan.source.owner}/${plan.source.repo}${plan.source.subdir?.let { "/$it" }.orEmpty()}\nCommit ${plan.source.commit}\nSHA-256 ${plan.checksum}\n\n${plan.effects.joinToString("\n")}"
                    AlertDialog.Builder(this@PluginsActivity).setTitle(R.string.plugin_review_installation).setMessage(details)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.plugin_install) { _, _ ->
                            if (!isCurrent() || !repository.isCurrent(actor)) { renderStale(); return@setPositiveButton }
                            lifecycleScope.launch {
                                val requestId = UUID.randomUUID().toString()
                                if (!session.track(requestId, PluginReceiptKind.OWNER_OPERATION)) { message(getString(R.string.plugin_receipt_retain_failed, getString(R.string.plugin_install_operation))); return@launch }
                                renderPendingReceipts()
                                if (!isCurrent() || !repository.isCurrent(actor)) { renderStale(); return@launch }
                                val applied = repository.applyHostPluginInstall(HostPluginInstallRequest(requestId, plan.token, plan.checksum), session.scope, actor)
                                if (!isCurrent()) { renderStale(); return@launch }
                                if (applied is ApiResult.Success && applied.value.operation.state == "succeeded") {
                                    session.reconcile(requestId)
                                    message(getString(R.string.plugin_install_succeeded))
                                    load()
                                } else {
                                    retainForReceipt(requestId, if (applied is ApiResult.Success) "Install ${applied.value.operation.state}: ${applied.value.operation.code.orEmpty()}" else "Install result is not confirmed.")
                                }
                            }
                        }.show()
                }
            }.show()
    }

    private fun chooseLink() {
        if (!isCurrent()) { renderStale(); return }
        lifecycleScope.launch {
            val actor = session.actor ?: return@launch renderStale()
            val result = repository.hostPluginLinkSelections(session.scope, actor)
            if (!isCurrent()) { renderStale(); return@launch }
            if (result !is ApiResult.Success) { message((result as? ApiResult.Failure)?.error?.pluginMessage ?: getString(R.string.plugin_link_selections_unavailable)); return@launch }
            val rows = result.value.selections
            if (rows.isEmpty()) { message(getString(R.string.plugin_managed_folders_empty)); return@launch }
            AlertDialog.Builder(this@PluginsActivity).setTitle(R.string.plugin_choose_managed)
                .setItems(rows.map { "${it.name} ${it.version} · ${it.pluginId}" }.toTypedArray()) { _, index ->
                    if (!isCurrent()) { renderStale(); return@setItems }
                    val selected = rows[index]
                    lifecycleScope.launch {
                        val actorAtPlan = session.actor ?: return@launch renderStale()
                        val planned = repository.planHostPluginLink(selected.id, session.scope, actorAtPlan)
                        if (!isCurrent()) { renderStale(); return@launch }
                        if (planned !is ApiResult.Success) { message((planned as? ApiResult.Failure)?.error?.pluginMessage ?: "Link plan unavailable"); return@launch }
                        val plan = planned.value.plan
                        AlertDialog.Builder(this@PluginsActivity).setTitle(R.string.plugin_review_link)
                            .setMessage("${plan.name} ${plan.version}\n${plan.effect}")
                            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.plugin_link) { _, _ ->
                                    if (!isCurrent() || !repository.isCurrent(actorAtPlan)) { renderStale(); return@setPositiveButton }
                                lifecycleScope.launch {
                                    val requestId = UUID.randomUUID().toString()
                                    if (!session.track(requestId, PluginReceiptKind.OWNER_OPERATION)) { message(getString(R.string.plugin_receipt_retain_failed, getString(R.string.plugin_link_operation))); return@launch }
                                    if (!isCurrent() || !repository.isCurrent(actorAtPlan)) { renderStale(); return@launch }
                                        val applied = repository.applyHostPluginLink(HostPluginLinkRequest(requestId, plan.token), session.scope, actorAtPlan)
                                    if (!isCurrent()) { renderStale(); return@launch }
                                    if (applied is ApiResult.Success && applied.value.operation.state == "succeeded") {
                                        session.reconcile(requestId)
                                        message(getString(R.string.plugin_link_succeeded))
                                        load()
                                    } else retainForReceipt(requestId, if (applied is ApiResult.Success) "Link ${applied.value.operation.state}" else "Link result is not confirmed.")
                                }
                            }.show()
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun retainForReceipt(requestId: String, status: String) {
        content.removeAllViews(); message("$status Check this receipt before taking any further action.")
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
        val receiptActor = session.receiptContext(repository) ?: run { renderStale(); return }
        lifecycleScope.launch {
            val receipt = repository.readPluginReceipt(requestId, session.scope, receiptActor, session.kind(requestId) ?: PluginReceiptKind.LEGACY_UNKNOWN)
            if (session.receiptContext(repository) !== receiptActor) { renderStale(); return@launch }
            when (receipt) {
                is PluginReceiptRead.Success -> {
                    val status = receipt
                    if (status.state == "succeeded" || status.state == "refused") session.reconcile(requestId)
                    content.removeAllViews()
                    message(if (status.code == null) getString(R.string.plugin_operation_receipt, status.state) else getString(R.string.plugin_operation_receipt_code, status.state, status.code))
                    if (status.state == "running" || status.state == "pending" || status.state == "unknown") button(getString(R.string.plugin_check_receipt, requestId)) { checkReceipt(requestId) }
                    button(getString(R.string.plugin_refresh_plugins)) { load() }
                }
                is PluginReceiptRead.Failure -> retainForReceipt(requestId, receipt.message ?: "Receipt remains unavailable.")
                PluginReceiptRead.NotModified -> retainForReceipt(requestId, "Receipt remains unavailable.")
            }
        }
    }

    private fun renderStale() {
        loading = false
        content.removeAllViews()
        message(getString(R.string.host_plugins_stale))
        renderPendingReceipts()
    }

    private fun message(value: String) { content.addView(TextView(this).apply { text = value; textSize = 15f; setPadding(0, dp(12), 0, dp(12)) }) }
    private fun button(label: String, action: () -> Unit) { content.addView(MaterialButton(this).apply { text = label; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
