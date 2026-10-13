package com.lateapex.collie.ui

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentViewRequest
import com.lateapex.collie.network.AgentViewSchema
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.mutationOutcomeUnknown
import com.lateapex.collie.network.PaneSummary
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/** Separates a local preview predicate from the explicit Herdr terminal-view projection. */
class AgentViewActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var address: PaneAddress
    private lateinit var status: TextView
    private lateinit var filterInput: EditText
    private lateinit var sortInput: EditText
    private lateinit var labelInput: EditText
    private lateinit var localResults: LinearLayout
    private var currentAgent: String? = null
    private var paneFacts: PaneSummary? = null
    private var paneRows: List<PaneSummary> = emptyList()
    private var requestContext: com.lateapex.collie.data.ScopedReadContext? = null
    private var reconciliationRequired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reconciliationRequired = savedInstanceState?.getBoolean(STATE_RECONCILIATION_REQUIRED) ?: false
        window.prepareEdgeToEdgeContent()
        val pane = intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.takeIf(String::isNotBlank) ?: run { finish(); return }
        val scope = Scope(intent.getStringExtra(PaneActivity.EXTRA_HOST)?.takeIf(String::isNotBlank), intent.getStringExtra(PaneActivity.EXTRA_SESSION)?.takeIf(String::isNotBlank))
        address = PaneAddress(scope, pane)
        repository.selectSubject(scope, pane)
        requestContext = repository.readContext(scope, pane)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        column.addView(TextView(this).apply { text = getString(R.string.agent_view_title); textSize = 22f })
        column.addView(TextView(this).apply { text = getString(R.string.agent_view_explainer); textSize = 15f })
        status = TextView(this).also(column::addView)
        filterInput = EditText(this).apply {
            hint = getString(R.string.agent_view_filter_hint)
            setText("""{"op":"eq","field":"agent","value":"codex"}""")
            minLines = 4; maxLines = 10; gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }.also(column::addView)
        sortInput = EditText(this).apply {
            hint = getString(R.string.agent_view_sort_hint)
            setText("[]")
            minLines = 2; maxLines = 5; gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }.also(column::addView)
        labelInput = EditText(this).apply { hint = getString(R.string.agent_view_label_hint); maxLines = 1 }.also(column::addView)
        column.addView(button(getString(R.string.agent_view_preview_local)) { previewLocally() })
        localResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        column.addView(localResults)
        column.addView(button(getString(R.string.agent_view_apply_terminal)) { applyTerminalView() })
        column.addView(button(getString(R.string.agent_view_clear_terminal)) { clearTerminalView() })
        column.addView(button(getString(R.string.agent_view_refresh)) { refresh() })
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RECONCILIATION_REQUIRED, reconciliationRequired)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() = lifecycleScope.launch {
        status.text = getString(R.string.agent_view_loading)
        if (requestContext == null) { status.text = getString(R.string.agent_view_unavailable); return@launch }
        currentAgent = (repository.paneAgentIdentity(address, requestContext) as? ApiResult.Success)?.value?.takeIf { it.ok && it.paneId == address.paneId }?.agent
        if (currentAgent == null) { status.text = getString(R.string.agent_view_identity_unavailable); return@launch }
        val snapshot = (repository.snapshot(address.scope, requestContext) as? ApiResult.Success)?.value
        paneRows = snapshot?.let { it.agents + it.shellPanes }?.filter {
            it.host == address.scope.host && it.session == address.scope.session
        }.orEmpty()
        paneFacts = paneRows.firstOrNull { it.paneId == address.paneId && it.agent == currentAgent }
        if (address.scope.host != null) { status.text = getString(R.string.agent_view_lead_only); return@launch }
        when (val result = repository.agentView(requestContext)) {
            is ApiResult.Success -> {
                if (result.value.authoritative && !result.value.reconcileRequired) reconciliationRequired = false
                status.text = if (reconciliationRequired) getString(R.string.agent_view_unknown) else if (result.value.view == null) getString(R.string.agent_view_none) else getString(R.string.agent_view_receipt, result.value.view.toString())
            }
            is ApiResult.Failure -> status.text = problem(result.error)
            is ApiResult.NotModified -> status.text = getString(R.string.agent_view_unavailable)
        }
    }

    /** Apply the same closed declarative predicate to a local snapshot list. */
    private fun previewLocally() {
        val draft = parseDraft() ?: run { status.text = getString(R.string.agent_view_invalid); return }
        if (paneFacts == null) { status.text = getString(R.string.agent_view_unavailable); return }
        val evaluated = paneRows.map { pane -> pane to (draft.filter?.let { evaluate(it, pane) } ?: true) }
        val matches = evaluated.filter { it.second == true }.map { it.first }
        val unknown = evaluated.count { it.second == null }
        status.text = getString(R.string.agent_view_local_count, matches.size, unknown)
        localResults.removeAllViews()
        matches.take(64).forEach { pane ->
            localResults.addView(TextView(this).apply {
                text = getString(R.string.agent_view_local_row, pane.paneLabel?.takeIf(String::isNotBlank) ?: pane.paneId, pane.agent, pane.status.name.lowercase())
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
        }
    }

    private fun localFieldValue(pane: PaneSummary, field: String): JsonPrimitive? = when (field) {
        "agent" -> JsonPrimitive(pane.agent)
        "status" -> JsonPrimitive(pane.status.name.lowercase())
        "workspace_id" -> JsonPrimitive(pane.workspaceId)
        "tab_id" -> JsonPrimitive(pane.tabId)
        "pane_id" -> JsonPrimitive(pane.paneId)
        else -> null
    }

    private fun parseDraft(): AgentViewRequest? {
        val filterText = filterInput.text.toString().trim()
        val filter = if (filterText.isEmpty()) null else try {
            Json.parseToJsonElement(filterText) as? JsonObject ?: return null
        } catch (_: Exception) { return null }
        val sortText = sortInput.text.toString().trim()
        val sort = try {
            val parsed = Json.parseToJsonElement(sortText.ifEmpty { "[]" }) as? JsonArray ?: return null
            parsed.map { it as? JsonObject ?: return null }
        } catch (_: Exception) { return null }
        val draft = AgentViewRequest("preview", labelInput.text.toString().trim().takeIf(String::isNotEmpty), filter, sort)
        return draft.takeIf(AgentViewSchema::valid)
    }

    /** null means the local snapshot lacks enough data to claim a match. */
    private fun evaluate(node: JsonElement, pane: PaneSummary): Boolean? {
        val row = node as? JsonObject ?: return null
        val op = (row["op"] as? JsonPrimitive)?.contentOrNull ?: return null
        fun localField(): Pair<Boolean, JsonPrimitive?> {
            val name = (row["field"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: return false to null // Token fields have no local token projection.
            if (name !in setOf("status", "workspace_id", "tab_id", "pane_id", "agent", "seen", "state_change_seq")) return false to null
            if (name in setOf("seen", "state_change_seq")) return false to null
            return true to localFieldValue(pane, name)
        }
        fun comparable(element: JsonElement?): JsonPrimitive? = element as? JsonPrimitive
        return when (op) {
            "all", "any" -> {
                val values = (row["filters"] as? JsonArray)?.map { evaluate(it, pane) } ?: return null
                if (op == "all") when {
                    values.any { it == false } -> false
                    values.any { it == null } -> null
                    else -> true
                } else when {
                    values.any { it == true } -> true
                    values.any { it == null } -> null
                    else -> false
                }
            }
            "not" -> (row["filter"] as? JsonObject)?.let { evaluate(it, pane)?.not() }
            "eq" -> {
                val (supported, actual) = localField()
                if (!supported) null else {
                    val expected = comparable(row["value"])
                    if (expected == null) null else actual == expected
                }
            }
            "in" -> {
                val (supported, actual) = localField()
                if (!supported) null else {
                    val values = (row["values"] as? JsonArray)?.map { comparable(it)?.let { expected -> actual == expected } }
                        ?: return null
                    when {
                        values.any { it == true } -> true
                        values.any { it == null } -> null
                        else -> false
                    }
                }
            }
            "exists" -> {
                val (supported, actual) = localField()
                if (supported) actual != null else null
            }
            else -> null
        }
    }

    private fun applyTerminalView() = lifecycleScope.launch {
        if (reconciliationRequired) { status.text = getString(R.string.agent_view_unknown); return@launch }
        if (requestContext == null) { status.text = getString(R.string.agent_view_unavailable); return@launch }
        if (address.scope.host != null) { status.text = getString(R.string.agent_view_lead_only); return@launch }
        if (currentAgent == null || paneFacts == null) { status.text = getString(R.string.agent_view_identity_unavailable); return@launch }
        val draft = parseDraft()
        val label = labelInput.text.toString().trim().takeIf(String::isNotEmpty)
        if (draft == null) {
            status.text = getString(R.string.agent_view_invalid)
            return@launch
        }
        status.text = getString(R.string.agent_view_sending)
        when (val result = repository.setAgentView(draft.copy(requestId = requestId(), label = label), requestContext)) {
            is ApiResult.Success -> if (result.value.ok) status.text = getString(R.string.agent_view_applied) else { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown); refresh() }
            is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown) } else status.text = problem(result.error)
            is ApiResult.NotModified -> { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown) }
        }
    }

    private fun clearTerminalView() = lifecycleScope.launch {
        if (reconciliationRequired) { status.text = getString(R.string.agent_view_unknown); return@launch }
        if (requestContext == null) { status.text = getString(R.string.agent_view_unavailable); return@launch }
        if (address.scope.host != null) { status.text = getString(R.string.agent_view_lead_only); return@launch }
        status.text = getString(R.string.agent_view_sending)
        when (val result = repository.clearAgentView(requestId(), requestContext)) {
            is ApiResult.Success -> if (result.value.ok) status.text = getString(R.string.agent_view_cleared) else { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown); refresh() }
            is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown) } else status.text = problem(result.error)
            is ApiResult.NotModified -> { reconciliationRequired = true; status.text = getString(R.string.agent_view_unknown) }
        }
    }

    private fun requestId() = UUID.randomUUID().toString().replace('-', '_')
    private fun problem(error: com.lateapex.collie.network.ApiFailure): String = when (error) {
        is com.lateapex.collie.network.ApiFailure.Http -> when (error.code) {
            "capability.unsupported" -> getString(R.string.agent_view_unsupported)
            "invalid_agent_view", "plugin_disabled", "plugin_not_found" -> getString(R.string.agent_view_refused, error.code)
            else -> getString(R.string.agent_view_unavailable)
        }
        else -> getString(R.string.agent_view_unavailable)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_RECONCILIATION_REQUIRED = "agent_view_reconciliation_required"
        fun intent(context: Context, address: PaneAddress): Intent = Intent(context, AgentViewActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        }
    }
}
