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
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.mutationOutcomeUnknown
import com.lateapex.collie.network.PaneMetadataRequest
import com.lateapex.collie.network.WorkspaceMetadataRequest
import kotlinx.coroutines.launch
import java.util.UUID

/** Bounded, per-device pane annotations. These values never assert lifecycle state. */
class AgentMetadataActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private var address: PaneAddress? = null
    private var workspaceId: String? = null
    private lateinit var scope: Scope
    private lateinit var status: TextView
    private lateinit var titleInput: EditText
    private lateinit var displayInput: EditText
    private lateinit var tokenNameInput: EditText
    private lateinit var tokenValueInput: EditText
    private lateinit var ttlInput: EditText
    private var currentAgent: String? = null
    private var requestContext: com.lateapex.collie.data.ScopedReadContext? = null
    private var reconciliationRequired = false
    private var pendingRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reconciliationRequired = savedInstanceState?.getBoolean(STATE_RECONCILIATION_REQUIRED) ?: false
        pendingRequestId = savedInstanceState?.getString(STATE_PENDING_REQUEST_ID)
        window.prepareEdgeToEdgeContent()
        val pane = intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.takeIf(String::isNotBlank)
        workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)?.takeIf(String::isNotBlank)
        if ((pane == null) == (workspaceId == null)) { finish(); return }
        scope = Scope(
            intent.getStringExtra(PaneActivity.EXTRA_HOST)?.takeIf(String::isNotBlank),
            intent.getStringExtra(PaneActivity.EXTRA_SESSION)?.takeIf(String::isNotBlank),
        )
        address = pane?.let { PaneAddress(scope, it) }
        repository.selectSubject(scope, pane ?: workspaceId)
        requestContext = repository.readContext(scope, pane ?: workspaceId)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        column.addView(TextView(this).apply { text = getString(if (pane == null) R.string.workspace_metadata_title else R.string.agent_metadata_title); textSize = 22f })
        column.addView(TextView(this).apply { text = getString(if (pane == null) R.string.workspace_metadata_explainer else R.string.agent_metadata_explainer); textSize = 15f })
        status = TextView(this)
        column.addView(status)
        titleInput = input(getString(R.string.agent_metadata_title_hint)).also(column::addView)
        displayInput = input(getString(R.string.agent_metadata_display_hint)).also(column::addView)
        if (pane == null) { titleInput.visibility = android.view.View.GONE; displayInput.visibility = android.view.View.GONE }
        tokenNameInput = input(getString(R.string.agent_metadata_token_name_hint)).also(column::addView)
        tokenValueInput = input(getString(R.string.agent_metadata_token_value_hint)).also(column::addView)
        ttlInput = input(getString(R.string.agent_metadata_ttl_hint), "86400").also(column::addView)
        column.addView(button(getString(R.string.agent_metadata_refresh)) { refresh() })
        column.addView(button(getString(R.string.agent_metadata_save)) { write(clear = false) })
        column.addView(button(getString(R.string.agent_metadata_clear)) { write(clear = true) })
        refresh()
    }

    override fun onStop() {
        if (::titleInput.isInitialized) { titleInput.text.clear(); displayInput.text.clear(); tokenValueInput.text.clear() }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RECONCILIATION_REQUIRED, reconciliationRequired)
        pendingRequestId?.let { outState.putString(STATE_PENDING_REQUEST_ID, it) }
        super.onSaveInstanceState(outState)
    }

    private fun refresh() = lifecycleScope.launch {
        status.text = getString(R.string.agent_metadata_loading)
        if (requestContext == null) { status.text = getString(R.string.agent_metadata_unavailable); return@launch }
        val target = address
        if (target != null) {
            val identity = repository.paneAgentIdentity(target, requestContext)
            currentAgent = (identity as? ApiResult.Success)?.value?.takeIf { it.ok && it.paneId == target.paneId }?.agent
        }
        if (target != null && currentAgent == null) {
            status.text = getString(R.string.agent_metadata_identity_unavailable)
            return@launch
        }
        val result = if (target != null) repository.paneMetadata(target, requestContext, pendingRequestId) else repository.workspaceMetadata(scope, workspaceId!!, requestContext, pendingRequestId)
        when (result) {
            is ApiResult.Success -> {
                val operation = result.value.operation
                if (operation != null && operation.requestId == pendingRequestId && operation.state in setOf("succeeded", "refused")) {
                    reconciliationRequired = false
                    pendingRequestId = null
                }
                status.text = if (reconciliationRequired) getString(R.string.agent_metadata_unknown) else getString(R.string.agent_metadata_loaded, result.value.contributions.size)
            }
            is ApiResult.Failure -> status.text = problem(result.error)
            is ApiResult.NotModified -> status.text = getString(R.string.agent_metadata_unavailable)
        }
    }

    private fun write(clear: Boolean) = lifecycleScope.launch {
        if (reconciliationRequired) { status.text = getString(R.string.agent_metadata_unknown); return@launch }
        if (requestContext == null) { status.text = getString(R.string.agent_metadata_unavailable); return@launch }
        val target = address
        val owner = currentAgent
        if (target != null && owner == null) { status.text = getString(R.string.agent_metadata_identity_unavailable); return@launch }
        val title = titleInput.text.toString().trim().takeIf(String::isNotEmpty)
        val display = displayInput.text.toString().trim().takeIf(String::isNotEmpty)
        val tokenKey = tokenNameInput.text.toString().trim()
        val tokenValue = tokenValueInput.text.toString().takeIf { tokenKey.isNotEmpty() }
        val ttlSeconds = ttlInput.text.toString().toLongOrNull()
        if ((!clear && (ttlSeconds == null || ttlSeconds !in 1L..86400L)) ||
            tokenKey.isNotEmpty() && !tokenKey.matches(Regex("[A-Za-z0-9_-]{1,32}")) ||
            (tokenValue?.length ?: 0) > 80 || listOf(title, display).any { it != null && it.length > 80 }) {
            status.text = getString(R.string.agent_metadata_invalid)
            return@launch
        }
        status.text = getString(R.string.agent_metadata_sending)
        val requestId = UUID.randomUUID().toString().replace('-', '_')
        pendingRequestId = requestId
        val result = if (target != null) {
            repository.writePaneMetadata(target, PaneMetadataRequest(
                requestId = requestId, agent = owner!!, ttlMs = if (clear) 1 else ttlSeconds!! * 1000,
                tokens = if (clear) tokenKey.takeIf(String::isNotBlank)?.let { mapOf(it to null) } ?: emptyMap() else tokenKey.takeIf(String::isNotBlank)?.let { mapOf(it to tokenValue) } ?: emptyMap(),
                title = if (clear) null else title, displayAgent = if (clear) null else display,
                clearTitle = clear, clearDisplayAgent = clear, clearStateLabels = clear,
            ), requestContext)
        } else {
            repository.writeWorkspaceMetadata(scope, workspaceId!!, WorkspaceMetadataRequest(
                requestId = requestId, ttlMs = if (clear) 1 else ttlSeconds!! * 1000,
                tokens = if (clear) tokenKey.takeIf(String::isNotBlank)?.let { mapOf(it to null) } ?: emptyMap() else tokenKey.takeIf(String::isNotBlank)?.let { mapOf(it to tokenValue) } ?: emptyMap(),
            ), requestContext)
        }
        when (result) {
            is ApiResult.Success -> if (result.value.ok) {
                pendingRequestId = null
                status.text = getString(R.string.agent_metadata_saved); titleInput.text.clear(); displayInput.text.clear(); tokenValueInput.text.clear()
            } else {
                reconciliationRequired = true
                status.text = getString(R.string.agent_metadata_unknown)
                refresh()
            }
            is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) {
                reconciliationRequired = true
                status.text = getString(R.string.agent_metadata_unknown)
            } else { pendingRequestId = null; status.text = problem(result.error) }
            is ApiResult.NotModified -> {
                reconciliationRequired = true
                status.text = getString(R.string.agent_metadata_unknown)
            }
        }
    }

    private fun problem(error: com.lateapex.collie.network.ApiFailure): String = when (error) {
        is com.lateapex.collie.network.ApiFailure.Http -> when (error.code) {
            "capability.unsupported", "metadata.token_occupant_guard_unavailable" -> getString(R.string.agent_metadata_unsupported)
            "metadata.agent_mismatch", "metadata.target_changed", "metadata.target_gone" -> getString(R.string.agent_metadata_wrong_pane)
            else -> getString(R.string.agent_metadata_refused, error.code ?: error.status.toString())
        }
        else -> getString(R.string.agent_metadata_unavailable)
    }

    private fun input(hintText: String, value: String = ""): EditText = EditText(this).apply { hint = hintText; setText(value); maxLines = 1; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_RECONCILIATION_REQUIRED = "metadata_reconciliation_required"
        private const val STATE_PENDING_REQUEST_ID = "metadata_pending_request_id"
        const val EXTRA_WORKSPACE_ID = "workspace_id"
        fun intent(context: Context, address: PaneAddress): Intent = Intent(context, AgentMetadataActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        }
        fun intentForWorkspace(context: Context, workspaceId: String, scope: Scope): Intent = Intent(context, AgentMetadataActivity::class.java).apply {
            putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
        }
    }
}
