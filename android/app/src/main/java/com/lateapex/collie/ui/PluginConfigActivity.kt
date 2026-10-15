package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.HostPluginConfigApplyRequest
import com.lateapex.collie.network.HostPluginConfigFileRequest
import com.lateapex.collie.network.HostPluginConfigPrepareRequest
import com.lateapex.collie.network.mutationOutcomeUnknown
import java.util.UUID
import kotlinx.coroutines.launch

/** Bounded JSON editor. Directory creation and edits each require a fresh disclosed plan. */
class PluginConfigActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var content: LinearLayout
    private lateinit var scope: Scope
    private lateinit var session: PluginActivitySession
    private lateinit var pluginId: String
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        scope = Scope(host = intent.getStringExtra(EXTRA_HOST))
        session = restorePluginActivitySession(lastCustomNonConfigurationInstance, savedInstanceState, repository, scope, PluginReceiptStore(applicationContext))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        root.addView(MaterialButton(this).apply { setText(R.string.plugin_back); setOnClickListener { finish() } })
        root.addView(TextView(this).apply { text = getString(R.string.plugin_config_title, pluginId); textSize = 20f; setPadding(0, dp(12), 0, dp(12)) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.applyAppTypeface(); window.applySafeContentInsets(root)
        loadFiles()
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(outState: Bundle) { session.save(outState); super.onSaveInstanceState(outState) }

    private fun loadFiles() {
        if (busy) return
        if (!isCurrent()) { stale(); return }
        busy = true; content.removeAllViews(); message(getString(R.string.plugin_config_reading))
        lifecycleScope.launch {
            val actor = session.actor ?: return@launch stale()
            val result = repository.hostPluginConfigFiles(pluginId, scope, actor)
            if (!isCurrent()) return@launch stale()
            content.removeAllViews()
            when (result) {
                is ApiResult.Success -> {
                    message(getString(R.string.plugin_config_files_description))
                    result.value.files.forEach { file -> button("${file.id} · ${file.bytes} bytes") { editFile(file.id) } }
                    if (result.value.files.isEmpty()) message(getString(R.string.plugin_config_files_empty))
                }
                is ApiResult.Failure -> {
                    message(result.error.pluginMessage ?: "Plugin config is not available.")
                    if (result.error.pluginCode == "plugin_config_prepare_required" || result.error.pluginCode == "host.capability_unsupported") {
                        button(getString(R.string.plugin_config_prepare_dirs)) { planPreparation() }
                    }
                }
                is ApiResult.NotModified -> message(getString(R.string.plugin_config_listing_unchanged))
            }
            renderPendingReceipts()
            busy = false
        }
    }

    private fun planPreparation() {
        if (busy || !isCurrent()) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.planHostPluginConfigPrepare(pluginId, scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val plan = result.value.plan
                    AlertDialog.Builder(this@PluginConfigActivity).setTitle(R.string.plugin_config_prepare_title)
                        .setMessage(plan.effect).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.plugin_prepare) { _, _ -> if (isCurrent() && repository.isCurrent(actor)) confirmPreparation(plan.token, actor) else stale() }.show()
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.plugin_config_plan_failed))
                is ApiResult.NotModified -> message(getString(R.string.plugin_config_prepare_unchanged))
            }
            busy = false
        }
    }

    private fun confirmPreparation(token: String, actor: ScopedReadContext) {
        if (!isCurrent()) { stale(); return }
        val requestId = "config-prepare-${UUID.randomUUID()}"
        if (!session.track(requestId, PluginReceiptKind.CONTROL_OPERATION)) { message(getString(R.string.plugin_receipt_retain_failed, getString(R.string.plugin_preparation))); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.applyHostPluginConfigPrepare(pluginId, HostPluginConfigPrepareRequest(requestId, token), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val op = result.value.operation
                    if (op.state == "succeeded") { session.reconcile(requestId); busy = false; loadFiles() }
                    else offerReceipt(requestId, getString(R.string.plugin_operation_state, getString(R.string.plugin_preparation), op.state, op.code?.let { getString(R.string.plugin_code_suffix, it) }.orEmpty()))
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) offerReceipt(requestId, getString(R.string.plugin_preparation_uncertain)) else {
                    session.reconcile(requestId)
                    message(result.error.pluginMessage ?: getString(R.string.plugin_preparation_failed))
                }
                is ApiResult.NotModified -> offerReceipt(requestId, getString(R.string.plugin_preparation_not_returned))
            }
            busy = false
        }
    }

    private fun editFile(fileId: String) {
        if (busy || !isCurrent()) { stale(); return }
        val actor = session.actor ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.hostPluginConfig(pluginId, fileId, scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> showEditor(fileId, result.value.file.content, actor)
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.plugin_config_file_read_failed))
                is ApiResult.NotModified -> message(getString(R.string.plugin_config_file_unchanged))
            }
            busy = false
        }
    }

    private fun showEditor(fileId: String, original: String, actor: ScopedReadContext) {
        val editor = EditText(this).apply {
            setText(original); minLines = 12; maxLines = 24; gravity = Gravity.TOP; setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); addView(editor) }
        AlertDialog.Builder(this).setTitle(fileId).setView(box).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugin_review_save) { _, _ -> planEdit(fileId, editor.text.toString(), actor) }.show()
    }

    private fun planEdit(fileId: String, text: String, actor: ScopedReadContext) {
        if (text.toByteArray(Charsets.UTF_8).size > 65_536) { message(getString(R.string.plugin_config_too_large)); return }
        if (!isCurrent()) { stale(); return }
        val requestId = "config-edit-${UUID.randomUUID()}"
        busy = true
        lifecycleScope.launch {
            val result = repository.planHostPluginConfigEdit(pluginId, HostPluginConfigFileRequest(requestId, fileId, text), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val plan = result.value.plan
                    AlertDialog.Builder(this@PluginConfigActivity).setTitle(R.string.plugin_config_apply_title)
                        .setMessage(getString(R.string.plugin_config_apply_message, plan.effect, fileId))
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.plugin_apply) { _, _ -> if (isCurrent() && repository.isCurrent(actor)) applyEdit(fileId, text, plan.token, actor) else stale() }.show()
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: getString(R.string.plugin_config_edit_plan_failed))
                is ApiResult.NotModified -> message(getString(R.string.plugin_config_edit_plan_unchanged))
            }
            busy = false
        }
    }

    private fun applyEdit(fileId: String, text: String, token: String, actor: ScopedReadContext) {
        if (!isCurrent()) { stale(); return }
        val requestId = "config-edit-${UUID.randomUUID()}"
        if (!session.track(requestId, PluginReceiptKind.CONTROL_OPERATION)) { message(getString(R.string.plugin_receipt_retain_failed, getString(R.string.plugin_config_edit))); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.applyHostPluginConfigEdit(pluginId, HostPluginConfigApplyRequest(requestId, token, fileId, text), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val op = result.value.operation
                    if (op.state == "succeeded") { session.reconcile(requestId); busy = false; loadFiles() }
                    else offerReceipt(requestId, getString(R.string.plugin_operation_state, getString(R.string.plugin_config_edit), op.state, op.code?.let { getString(R.string.plugin_code_suffix, it) }.orEmpty()))
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) offerReceipt(requestId, getString(R.string.plugin_config_edit_uncertain)) else {
                    session.reconcile(requestId)
                    message(result.error.pluginMessage ?: getString(R.string.plugin_config_edit_failed))
                }
                is ApiResult.NotModified -> offerReceipt(requestId, getString(R.string.plugin_config_edit_not_returned))
            }
            busy = false
        }
    }

    private fun offerReceipt(requestId: String, label: String) {
        busy = false
        content.removeAllViews()
        message(getString(R.string.plugin_receipt_do_not_repeat, label))
        button(getString(R.string.plugin_check_operation_receipt, requestId)) { checkReceipt(requestId) }
    }

    private fun checkReceipt(requestId: String) {
        if (busy) return
        val actor = session.receiptContext(repository) ?: return stale()
        busy = true
        lifecycleScope.launch {
            val result = repository.readPluginReceipt(requestId, scope, actor, session.kind(requestId) ?: PluginReceiptKind.LEGACY_UNKNOWN)
            if (session.receiptContext(repository) !== actor) { busy = false; return@launch stale() }
            when (result) {
                is PluginReceiptRead.Success -> {
                    val operation = result
                    if (operation.state == "succeeded" || operation.state == "refused") session.reconcile(requestId)
                    content.removeAllViews()
                    message(if (operation.code == null) getString(R.string.plugin_operation_receipt, operation.state) else getString(R.string.plugin_operation_receipt_code, operation.state, operation.code))
                    if (operation.state == "pending" || operation.state == "running" || operation.state == "unknown") {
                        button(getString(R.string.plugin_check_operation_receipt, requestId)) { checkReceipt(requestId) }
                    } else renderPendingReceipts()
                    if (operation.state == "succeeded") button(getString(R.string.plugin_refresh_config)) { loadFiles() }
                }
                is PluginReceiptRead.Failure -> offerReceipt(requestId, result.message ?: getString(R.string.plugin_operation_receipt_unavailable))
                PluginReceiptRead.NotModified -> offerReceipt(requestId, getString(R.string.plugin_operation_receipt_unchanged))
            }
            busy = false
        }
    }

    private fun isCurrent(): Boolean = session.isWriteCurrent(repository)
    private fun stale() {
        busy = false; content.removeAllViews()
        message(getString(R.string.plugin_original_connection_stale))
        renderPendingReceipts()
    }
    private fun renderPendingReceipts() {
        if (session.receiptContext(repository) != null) {
            session.pendingReceipts.keys.forEach { id -> button(getString(R.string.plugin_check_operation_receipt, id)) { checkReceipt(id) } }
        } else if (session.pendingReceipts.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.plugin_receipt_profile_unavailable, session.pendingReceipts.keys.joinToString())
                textSize = 15f; setPadding(0, dp(12), 0, dp(12))
            })
        }
    }
    private fun message(value: String) { content.removeAllViews(); content.addView(TextView(this).apply { text = value; textSize = 15f; setPadding(0, dp(12), 0, dp(12)) }) }
    private fun button(label: String, action: () -> Unit) { content.addView(MaterialButton(this).apply { text = label; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        fun intent(context: Context, scope: Scope, pluginId: String) = Intent(context, PluginConfigActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host); putExtra(EXTRA_PLUGIN_ID, pluginId)
        }
    }
}
