package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.PaneLayoutIntent
import com.lateapex.collie.data.PaneLayoutIntentOwner
import com.lateapex.collie.data.PaneLayoutReservation
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PortableLayout
import com.lateapex.collie.network.PortableLayoutNode
import com.lateapex.collie.network.valid
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/** Native portable layout share/import surface. Every apply is previewed and durably reserved first. */
class LayoutRecipesActivity : AppCompatActivity() {
    private val app get() = application as CollieApplication
    private val repository get() = app.container.repository
    private lateinit var address: PaneAddress
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView
    private lateinit var importButton: MaterialButton
    private lateinit var ratioButton: MaterialButton
    private var actor: ScopedReadContext? = null
    private var pending: PaneLayoutIntent? = null
    private val store get() = app.container.paneLayoutIntentStore
    private val json get() = app.container.json
    private var authorityRegistration: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) }
        if (paneId == null) { finish(); return }
        val scope = Scope(intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank), intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank))
        address = PaneAddress(scope, paneId)
        actor = repository.readContext(scope, paneId)
        title = getString(com.lateapex.collie.R.string.portable_layout_title)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        scroll.addView(content)
        setContentView(scroll)
        notice = text(getString(com.lateapex.collie.R.string.portable_layout_intro))
        content.addView(notice, params())
        button(getString(com.lateapex.collie.R.string.portable_layout_export)) { export() }
        importButton = button(getString(com.lateapex.collie.R.string.portable_layout_import)) { importDialog() }
        ratioButton = button(getString(com.lateapex.collie.R.string.portable_layout_ratio)) { ratioDialog() }
        importButton.isEnabled = false
        ratioButton.isEnabled = false
        authorityRegistration = repository.onAuthorityRetired { event ->
            if (event.affects(actor)) runOnUiThread { content.removeAllViews(); content.addView(text(getString(com.lateapex.collie.R.string.portable_layout_retired)), params()) }
        }
        loadMutationSupport()
        recoverPending()
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        authorityRegistration = null
        super.onDestroy()
    }

    private fun export() = lifecycleScope.launch {
        val captured = actor ?: return@launch
        if (!repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        val snapshot = repository.snapshot(address.scope, captured)
        if (snapshot !is ApiResult.Success || !repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        val pane = (snapshot.value.agents + snapshot.value.shellPanes).firstOrNull { it.paneId == address.paneId }
            ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        when (val result = repository.exportPortableLayout(address.scope, pane.tabId, captured)) {
            is ApiResult.Success -> {
                if (!repository.isCurrent(captured) || !result.value.layout.valid()) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
                val exported = json.encodeToString(result.value.layout)
                val share = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, exported)
                startActivity(Intent.createChooser(share, getString(com.lateapex.collie.R.string.portable_layout_share)))
            }
            else -> show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        }
    }

    private fun loadMutationSupport() = lifecycleScope.launch {
        val captured = actor ?: return@launch
        val config = repository.config(address.scope, captured)
        val available = config is ApiResult.Success && repository.isCurrent(captured) &&
            config.value.control?.supportsNew("controlOperations") == true && config.value.mux?.supportsNew("portableLayouts") == true
        importButton.isEnabled = available
        ratioButton.isEnabled = available
        if (!available && repository.isCurrent(captured)) notice.text = getString(com.lateapex.collie.R.string.portable_layout_read_only)
    }

    private fun importDialog() {
        val editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8; maxLines = 16; setTextIsSelectable(true); hint = getString(com.lateapex.collie.R.string.portable_layout_json_hint)
        }
        AlertDialog.Builder(this).setTitle(com.lateapex.collie.R.string.portable_layout_import)
            .setMessage(com.lateapex.collie.R.string.portable_layout_import_message).setView(editor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.lateapex.collie.R.string.portable_layout_preview) { _, _ -> planImport(editor.text.toString()) }.show()
    }

    private fun planImport(source: String) = lifecycleScope.launch {
        val captured = actor ?: return@launch
        if (source.length > 64 * 1024 || source.toByteArray().size > 64 * 1024) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val jsonValue = runCatching { json.parseToJsonElement(source) }.getOrNull()
        if (!closedPortableDocument(jsonValue)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val layout = runCatching { json.decodeFromString<PortableLayout>(source) }.getOrNull()
            ?.takeIf { it.valid() } ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        if (!repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        val snapshot = repository.snapshot(address.scope, captured)
        if (snapshot !is ApiResult.Success || !repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        val pane = (snapshot.value.agents + snapshot.value.shellPanes).firstOrNull { it.paneId == address.paneId }
            ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        val root = nodeJson(layout.root) ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val planBody = buildJsonObject { put("root", root); put("workspaceId", pane.workspaceId); put("tabId", pane.tabId); put("tabLabel", null as String?) }
        when (val planned = repository.planPortableLayout(address.scope, planBody, captured)) {
            is ApiResult.Success -> {
                if (!repository.isCurrent(captured) || !planned.value.valid()) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
                val frozen = buildJsonObject { put("root", root); put("workspaceId", pane.workspaceId); put("tabId", pane.tabId); put("tabLabel", null as String?) }
                showApplyConfirmation(captured, planned.value.planToken, frozen, planned.value.effects, pane.tabId, pane.workspaceId)
            }
            else -> show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        }
    }

    private fun showApplyConfirmation(captured: ScopedReadContext, token: String, body: JsonObject, effects: JsonObject, tabId: String, workspaceId: String) {
        val requestId = UUID.randomUUID().toString()
        val owner = PaneLayoutIntentOwner.from(captured, address.paneId)
        val frozen = json.encodeToString(JsonObject.serializer(), body)
        val intent = PaneLayoutIntent(owner, captured.key.connectionGeneration, captured.key.leadEpoch, requestId, token, frozen, tabId, workspaceId)
        AlertDialog.Builder(this).setTitle(com.lateapex.collie.R.string.portable_layout_confirm_title)
            .setMessage(getString(com.lateapex.collie.R.string.portable_layout_effects, effects.toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.lateapex.collie.R.string.portable_layout_apply) { _, _ -> reserveThenApply(captured, intent, body) }.show()
    }

    private fun reserveThenApply(captured: ScopedReadContext, intent: PaneLayoutIntent, planBody: JsonObject) = lifecycleScope.launch {
        if (!repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        when (store.reserve(intent)) {
            PaneLayoutReservation.Reserved -> { pending = intent; dispatchApply(captured, intent, planBody) }
            is PaneLayoutReservation.Existing -> { pending = intent.copy(state = "unknown"); recoverPending() }
            else -> show(getString(com.lateapex.collie.R.string.portable_layout_pending))
        }
    }

    private suspend fun dispatchApply(captured: ScopedReadContext, intent: PaneLayoutIntent, original: JsonObject) {
        val body = buildJsonObject {
            put("requestId", intent.requestId); put("planToken", intent.planToken)
            original.forEach { (key, value) -> put(key, value) }
        }
        when (val result = repository.applyPortableLayout(address.scope, body, captured)) {
            is ApiResult.Success -> {
                if (!repository.isCurrent(captured)) { store.markUnknown(intent.owner, intent.requestId); return show(getString(com.lateapex.collie.R.string.portable_layout_unknown)) }
                if (result.value.operation.state == "succeeded" && result.value.operation.result?.kind == "portable-layout" && store.resolve(intent.owner, intent.requestId)) {
                    pending = null; show(getString(com.lateapex.collie.R.string.portable_layout_applied))
                } else { store.markUnknown(intent.owner, intent.requestId); recoverPending() }
            }
            is ApiResult.NotModified -> { store.markUnknown(intent.owner, intent.requestId); recoverPending() }
            is ApiResult.Failure -> {
                val status = (result.error as? ApiFailure.Http)?.status
                if (status in setOf(400, 403, 404, 409, 501) && store.resolve(intent.owner, intent.requestId)) {
                    pending = null
                    show(getString(com.lateapex.collie.R.string.portable_layout_refused))
                } else {
                    store.markUnknown(intent.owner, intent.requestId)
                    show(getString(com.lateapex.collie.R.string.portable_layout_unknown))
                }
            }
        }
    }

    private fun recoverPending() = lifecycleScope.launch {
        val captured = actor ?: return@launch
        val owner = PaneLayoutIntentOwner.from(captured, address.paneId)
        val row = store.unresolved(owner) ?: return@launch
        pending = row
        show(getString(com.lateapex.collie.R.string.portable_layout_pending))
        if (!repository.isCurrent(captured)) return@launch
        when (val result = repository.controlOperation(address.scope, row.requestId, captured)) {
            is ApiResult.Success -> {
                if (result.value.operation.state == "succeeded" && result.value.operation.result?.kind in setOf("portable-layout", "split-ratio")) {
                    if (store.resolve(owner, row.requestId)) { pending = null; show(getString(com.lateapex.collie.R.string.portable_layout_applied)) }
                } else if (result.value.operation.state == "refused") {
                    if (store.resolve(owner, row.requestId)) { pending = null; show(getString(com.lateapex.collie.R.string.portable_layout_refused)) }
                }
                else show(getString(com.lateapex.collie.R.string.portable_layout_unknown))
            }
            else -> show(getString(com.lateapex.collie.R.string.portable_layout_unknown))
        }
    }

    private fun ratioDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val path = EditText(this).apply { hint = getString(com.lateapex.collie.R.string.portable_layout_path_hint); inputType = InputType.TYPE_CLASS_TEXT }
        val ratio = EditText(this).apply { hint = "0.5"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        fields.addView(path, params()); fields.addView(ratio, params())
        AlertDialog.Builder(this).setTitle(com.lateapex.collie.R.string.portable_layout_ratio)
            .setView(fields).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.lateapex.collie.R.string.portable_layout_preview) { _, _ -> planRatio(path.text.toString(), ratio.text.toString()) }.show()
    }

    private fun planRatio(pathText: String, ratioText: String) = lifecycleScope.launch {
        if (pathText.length > 63) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val bits = pathText.trim().map { char -> when (char) { '0' -> false; '1' -> true; else -> return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid)) } }
        val amount = ratioText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
            ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val captured = actor ?: return@launch
        val snapshot = repository.snapshot(address.scope, captured)
        if (snapshot !is ApiResult.Success || !repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        val pane = (snapshot.value.agents + snapshot.value.shellPanes).firstOrNull { it.paneId == address.paneId } ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_stale))
        val plan = repository.exportPortableLayout(address.scope, pane.tabId, captured)
        if (plan !is ApiResult.Success) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        var selected = plan.value.layout.root
        for (bit in bits) {
            if (selected.type != "split") return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
            selected = if (bit) selected.second ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
                else selected.first ?: return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        }
        if (selected.type != "split") return@launch show(getString(com.lateapex.collie.R.string.portable_layout_invalid))
        val action = buildJsonObject { put("kind", "split-ratio"); put("tabId", pane.tabId); put("paneId", null as String?); putJsonArray("path") { bits.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }; put("ratio", amount) }
        val preview = repository.planPortableLayout(address.scope, action, captured)
        if (preview !is ApiResult.Success || !repository.isCurrent(captured)) return@launch show(getString(com.lateapex.collie.R.string.portable_layout_unavailable))
        val requestId = UUID.randomUUID().toString()
        val intent = PaneLayoutIntent(PaneLayoutIntentOwner.from(captured, address.paneId), captured.key.connectionGeneration, captured.key.leadEpoch,
            requestId, preview.value.planToken, json.encodeToString(JsonObject.serializer(), action), pane.tabId, pane.workspaceId)
        AlertDialog.Builder(this@LayoutRecipesActivity).setTitle(com.lateapex.collie.R.string.portable_layout_ratio_confirm)
            .setMessage(getString(com.lateapex.collie.R.string.portable_layout_ratio_effects, bits.joinToString(""), amount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.lateapex.collie.R.string.portable_layout_apply) { _, _ -> lifecycleScope.launch { applyRatio(captured, intent, pane.tabId, bits, amount) } }.show()
    }

    private suspend fun applyRatio(captured: ScopedReadContext, intent: PaneLayoutIntent, tabId: String, path: List<Boolean>, ratio: Double) {
        if (store.reserve(intent) != PaneLayoutReservation.Reserved) return show(getString(com.lateapex.collie.R.string.portable_layout_pending))
        pending = intent
        val body = buildJsonObject { put("requestId", intent.requestId); put("planToken", intent.planToken); put("path", kotlinx.serialization.json.JsonArray(path.map { kotlinx.serialization.json.JsonPrimitive(it) })); put("ratio", ratio) }
        when (val result = repository.setSplitRatio(address.scope, tabId, body, captured)) {
            is ApiResult.Success -> if (result.value.operation.state == "succeeded" && result.value.operation.result?.kind == "split-ratio" && store.resolve(intent.owner, intent.requestId)) { pending = null; show(getString(com.lateapex.collie.R.string.portable_layout_ratio_applied)) }
                else { store.markUnknown(intent.owner, intent.requestId); recoverPending() }
            is ApiResult.Failure -> {
                val status = (result.error as? ApiFailure.Http)?.status
                if (status in setOf(400, 403, 404, 409, 501) && store.resolve(intent.owner, intent.requestId)) {
                    pending = null
                    show(getString(com.lateapex.collie.R.string.portable_layout_refused))
                } else { store.markUnknown(intent.owner, intent.requestId); recoverPending() }
            }
            is ApiResult.NotModified -> { store.markUnknown(intent.owner, intent.requestId); recoverPending() }
        }
    }

    private fun nodeJson(node: PortableLayoutNode): JsonObject? = when (node.type) {
        "pane" -> buildJsonObject {
            put("type", "pane"); node.label?.let { put("label", it) }; node.cwd?.let { put("cwd", it) }
            node.command?.let { values -> putJsonArray("command") { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            if (node.env.isNotEmpty()) putJsonObject("env") { node.env.forEach { (key, value) -> put(key, value) } }
        }
        "split" -> {
            val first = node.first?.let(::nodeJson) ?: return null
            val second = node.second?.let(::nodeJson) ?: return null
            buildJsonObject { put("type", "split"); put("direction", node.direction!!); put("ratio", node.ratio!!); put("first", first); put("second", second) }
        }
        else -> null
    }

    private fun closedPortableDocument(value: kotlinx.serialization.json.JsonElement?): Boolean {
        val document = value as? JsonObject ?: return false
        if (document.keys != setOf("workspaceId", "tabId", "focusedPaneId", "zoomed", "root")) return false
        var count = 0
        fun node(element: kotlinx.serialization.json.JsonElement, depth: Int): Boolean {
            if (depth > 12 || ++count > 64) return false
            val row = element as? JsonObject ?: return false
            return when (row["type"]?.jsonPrimitive?.contentOrNull) {
                "pane" -> row.keys.all { it in setOf("type", "label", "cwd", "paneId", "command", "env") }
                "split" -> row.keys == setOf("type", "direction", "ratio", "first", "second") &&
                    row["first"]?.let { node(it, depth + 1) } == true && row["second"]?.let { node(it, depth + 1) } == true
                else -> false
            }
        }
        return node(document["root"] ?: return false, 0)
    }

    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        setOnClickListener { action() }
        content.addView(this, params())
    }
    private fun text(value: String) = TextView(this).apply { text = value; textSize = 16f; setPadding(0, dp(8), 0, dp(12)) }
    private fun params() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun show(value: String) { notice.text = value; Toast.makeText(this, value, Toast.LENGTH_LONG).show() }

    companion object { const val EXTRA_PANE_ID = "pane_id"; const val EXTRA_HOST = "host"; const val EXTRA_SESSION = "session" }
}
