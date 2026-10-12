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
        root.addView(MaterialButton(this).apply { text = "Back"; setOnClickListener { finish() } })
        root.addView(TextView(this).apply { text = "Plugin configuration · $pluginId"; textSize = 20f; setPadding(0, dp(12), 0, dp(12)) })
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
        busy = true; content.removeAllViews(); message("Reading the owner-scoped config directory…")
        lifecycleScope.launch {
            val actor = session.actor ?: return@launch stale()
            val result = repository.hostPluginConfigFiles(pluginId, scope, actor)
            if (!isCurrent()) return@launch stale()
            content.removeAllViews()
            when (result) {
                is ApiResult.Success -> {
                    message("Only bounded JSON files are shown. Edits keep a private backup and require a current plan.")
                    result.value.files.forEach { file -> button("${file.id} · ${file.bytes} bytes") { editFile(file.id) } }
                    if (result.value.files.isEmpty()) message("No JSON config files exist yet.")
                }
                is ApiResult.Failure -> {
                    message(result.error.pluginMessage ?: "Plugin config is not available.")
                    if (result.error.pluginCode == "plugin_config_prepare_required" || result.error.pluginCode == "host.capability_unsupported") {
                        button("Prepare config and state directories") { planPreparation() }
                    }
                }
                is ApiResult.NotModified -> message("Config listing was not modified.")
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
                    AlertDialog.Builder(this@PluginConfigActivity).setTitle("Prepare plugin config")
                        .setMessage(plan.effect).setNegativeButton("Cancel", null)
                        .setPositiveButton("Prepare") { _, _ -> if (isCurrent() && repository.isCurrent(actor)) confirmPreparation(plan.token, actor) else stale() }.show()
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: "Could not prepare a config plan.")
                is ApiResult.NotModified -> message("Config preparation plan was not modified.")
            }
            busy = false
        }
    }

    private fun confirmPreparation(token: String, actor: ScopedReadContext) {
        if (!isCurrent()) { stale(); return }
        val requestId = "config-prepare-${UUID.randomUUID()}"
        if (!session.track(requestId, PluginReceiptKind.CONTROL_OPERATION)) { message("Could not durably retain an operation receipt ID. No preparation was sent."); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.applyHostPluginConfigPrepare(pluginId, HostPluginConfigPrepareRequest(requestId, token), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val op = result.value.operation
                    if (op.state == "succeeded") { session.reconcile(requestId); busy = false; loadFiles() }
                    else offerReceipt(requestId, "Preparation ${op.state}${op.code?.let { ": $it" } ?: ""}.")
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) offerReceipt(requestId, "Preparation outcome is uncertain.") else {
                    session.reconcile(requestId)
                    message(result.error.pluginMessage ?: "Preparation failed.")
                }
                is ApiResult.NotModified -> offerReceipt(requestId, "Preparation result was not returned.")
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
                is ApiResult.Failure -> message(result.error.pluginMessage ?: "Could not read config file.")
                is ApiResult.NotModified -> message("Config file was not modified.")
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
        AlertDialog.Builder(this).setTitle(fileId).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Review save") { _, _ -> planEdit(fileId, editor.text.toString(), actor) }.show()
    }

    private fun planEdit(fileId: String, text: String, actor: ScopedReadContext) {
        if (text.toByteArray(Charsets.UTF_8).size > 65_536) { message("Config must be no larger than 64 KiB."); return }
        if (!isCurrent()) { stale(); return }
        val requestId = "config-edit-${UUID.randomUUID()}"
        busy = true
        lifecycleScope.launch {
            val result = repository.planHostPluginConfigEdit(pluginId, HostPluginConfigFileRequest(requestId, fileId, text), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val plan = result.value.plan
                    AlertDialog.Builder(this@PluginConfigActivity).setTitle("Apply config edit")
                        .setMessage("${plan.effect}\n\n${fileId} will be replaced after JSON validation; the current file will be backed up.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ -> if (isCurrent() && repository.isCurrent(actor)) applyEdit(fileId, text, plan.token, actor) else stale() }.show()
                }
                is ApiResult.Failure -> message(result.error.pluginMessage ?: "Config edit plan failed. Check that the text is valid JSON.")
                is ApiResult.NotModified -> message("Config edit plan was not modified.")
            }
            busy = false
        }
    }

    private fun applyEdit(fileId: String, text: String, token: String, actor: ScopedReadContext) {
        if (!isCurrent()) { stale(); return }
        val requestId = "config-edit-${UUID.randomUUID()}"
        if (!session.track(requestId, PluginReceiptKind.CONTROL_OPERATION)) { message("Could not durably retain an operation receipt ID. No config edit was sent."); return }
        busy = true
        lifecycleScope.launch {
            val result = repository.applyHostPluginConfigEdit(pluginId, HostPluginConfigApplyRequest(requestId, token, fileId, text), scope, actor)
            if (!isCurrent()) { busy = false; return@launch stale() }
            when (result) {
                is ApiResult.Success -> {
                    val op = result.value.operation
                    if (op.state == "succeeded") { session.reconcile(requestId); busy = false; loadFiles() }
                    else offerReceipt(requestId, "Config edit ${op.state}${op.code?.let { ": $it" } ?: ""}.")
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) offerReceipt(requestId, "Config edit outcome is uncertain.") else {
                    session.reconcile(requestId)
                    message(result.error.pluginMessage ?: "Config edit failed.")
                }
                is ApiResult.NotModified -> offerReceipt(requestId, "Config edit result was not returned.")
            }
            busy = false
        }
    }

    private fun offerReceipt(requestId: String, label: String) {
        busy = false
        content.removeAllViews()
        message("$label Do not repeat the operation until its receipt is checked.")
        button("Check operation receipt $requestId") { checkReceipt(requestId) }
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
                    message("Operation receipt: ${operation.state}${operation.code?.let { " · $it" } ?: ""}")
                    if (operation.state == "pending" || operation.state == "running" || operation.state == "unknown") {
                        button("Check operation receipt $requestId") { checkReceipt(requestId) }
                    } else renderPendingReceipts()
                    if (operation.state == "succeeded") button("Refresh config") { loadFiles() }
                }
                is PluginReceiptRead.Failure -> offerReceipt(requestId, result.message ?: "Operation receipt is unavailable; keep the outcome unresolved.")
                PluginReceiptRead.NotModified -> offerReceipt(requestId, "Operation receipt was not modified; keep the outcome unresolved.")
            }
            busy = false
        }
    }

    private fun isCurrent(): Boolean = session.isWriteCurrent(repository)
    private fun stale() {
        busy = false; content.removeAllViews()
        message("The original host or paired connection is no longer current. No plugin write was replayed.")
        renderPendingReceipts()
    }
    private fun renderPendingReceipts() {
        if (session.receiptContext(repository) != null) {
            session.pendingReceipts.keys.forEach { id -> button("Check operation receipt $id") { checkReceipt(id) } }
        } else if (session.pendingReceipts.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "No matching current paired profile is available for receipt reads. Unresolved operation IDs: ${session.pendingReceipts.keys.joinToString()}. No plugin write was replayed."
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
