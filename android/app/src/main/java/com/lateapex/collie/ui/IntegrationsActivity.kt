package com.lateapex.collie.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentManifestStatusResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.HostIntegration
import com.lateapex.collie.network.HostOperationIntent
import com.lateapex.collie.network.HostOperationKind
import com.lateapex.collie.network.HostOperationRequest
import com.lateapex.collie.network.HostManifestPresentation
import com.lateapex.collie.network.IntegrationAction
import java.util.UUID
import kotlinx.coroutines.launch

/** Native host administration. Every write is planned, visibly confirmed, and dispatched once. */
class IntegrationsActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var content: LinearLayout
    private var busy = false
    private var integrationDetailsAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        toolbar.addView(MaterialButton(this).apply {
            setText(R.string.host_integrations_back); contentDescription = getString(R.string.host_integrations_back); setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply { setText(R.string.host_integrations_title); textSize = 20f; setPadding(dp(12), 0, 0, 0) })
        root.addView(toolbar)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.applyAppTypeface()
        window.applySafeContentInsets(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (busy) return
        busy = true
        integrationDetailsAvailable = false
        val scope = hostScope()
        content.removeAllViews()
        text(R.string.host_integrations_loading)
        lifecycleScope.launch {
            val integrations = repository.hostIntegrations(scope)
            if (repository.selectedScope.value.let { Scope(host = it.host) } != scope) {
                busy = false
                showMessage(R.string.host_integrations_stale)
                return@launch
            }
            val details = if (integrations is ApiResult.Success) repository.hostIntegrationStatus(scope) else null
            if (!isCurrent(scope)) {
                busy = false
                showMessage(R.string.host_integrations_stale)
                return@launch
            }
            integrationDetailsAvailable = details is ApiResult.Success && details.value.versionStatus == "available"
            content.removeAllViews()
            when (integrations) {
                is ApiResult.Success -> {
                    val rows = if (details is ApiResult.Success) {
                        val byTarget = details.value.integrations.associateBy(HostIntegration::target)
                        integrations.value.integrations.map { byTarget[it.target] ?: it }
                    } else integrations.value.integrations
                    busy = false
                    if (rows.isEmpty()) text(R.string.host_integrations_empty)
                    if (details !is ApiResult.Success || details.value.versionStatus != "available") text(R.string.host_integration_status_restricted)
                    rows.forEach(::integrationRow)
                    text(R.string.host_manifest_codex_guidance)
                    text(R.string.host_letta_experimental_note)
                    button(R.string.host_letta_install) { confirm(HostOperationIntent(HostOperationKind.LETTA, IntegrationAction.INSTALL), scope) }
                    button(R.string.host_letta_uninstall) { confirm(HostOperationIntent(HostOperationKind.LETTA, IntegrationAction.UNINSTALL), scope) }
                    button(R.string.host_manifest_reload) { confirm(HostOperationIntent(HostOperationKind.MANIFEST_RELOAD), scope) }
                    button(R.string.host_manifest_refresh) { confirm(HostOperationIntent(HostOperationKind.MANIFEST_REFRESH), scope) }
                    loadManifests(scope)
                }
                is ApiResult.Failure -> showFailure(integrations.error)
                is ApiResult.NotModified -> text(R.string.host_integrations_stale)
            }
            busy = false
        }
    }

    private fun loadManifests(scope: Scope) {
        lifecycleScope.launch {
            when (val result = repository.agentManifestStatus(scope)) {
                is ApiResult.Success -> if (isCurrent(scope)) renderManifests(result.value)
                is ApiResult.Failure -> if (isCurrent(scope)) text(R.string.host_manifest_unavailable)
                is ApiResult.NotModified -> if (isCurrent(scope)) text(R.string.host_manifest_unavailable)
            }
        }
    }

    private fun integrationRow(item: HostIntegration) {
        val presentation = HostControlPresentation.integrationState(item.state)
        text("${item.label} · ${getString(presentation.statusLabel)}")
        if (!item.available) text(R.string.host_integration_executable_missing)
        text(getString(R.string.host_integration_versions, item.command, item.installedVersion ?: getString(R.string.host_version_unknown), item.requiredVersion ?: getString(R.string.host_version_unknown)))
        button(presentation.actionLabel) {
            confirm(HostOperationIntent(HostOperationKind.INTEGRATION, presentation.action, item.target), hostScope())
        }.also { it.isEnabled = item.available && integrationDetailsAvailable && !busy }
    }

    private fun confirm(intent: HostOperationIntent, scope: Scope) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val planned = repository.planHostOperation(intent, scope)
            if (!isCurrent(scope)) { busy = false; showMessage(R.string.host_integrations_stale); return@launch }
            if (planned !is ApiResult.Success || planned.value.plan == null) {
                busy = false
                showFailure((planned as? ApiResult.Failure)?.error)
                return@launch
            }
            val plan = planned.value.plan
            AlertDialog.Builder(this@IntegrationsActivity)
                .setTitle(R.string.host_operation_confirm_title)
                .setMessage(plan.effect)
                .setNegativeButton(android.R.string.cancel) { _, _ -> busy = false }
                .setPositiveButton(R.string.host_operation_confirm) { _, _ -> dispatch(intent, scope, plan.token) }
                .setOnCancelListener { busy = false }
                .show()
        }
    }

    private fun dispatch(intent: HostOperationIntent, scope: Scope, token: String) {
        if (!isCurrent(scope)) { busy = false; showMessage(R.string.host_integrations_stale); return }
        val requestId = UUID.randomUUID().toString()
        lifecycleScope.launch {
            val result = repository.applyHostOperation(intent, HostOperationRequest(requestId, token), scope)
            if (!isCurrent(scope)) {
                busy = false
                showMessage(R.string.host_integrations_stale)
                return@launch
            }
            when (result) {
                is ApiResult.Success -> {
                    val receipt = result.value.operation
                    if (receipt.state == "succeeded") showMessage(R.string.host_operation_succeeded)
                    else showMessage(R.string.host_operation_unknown)
                    busy = false
                    load()
                }
                is ApiResult.Failure -> {
                    busy = false
                    if (result.error is ApiFailure.Network || result.error is ApiFailure.Timeout) showMessage(R.string.host_operation_unknown)
                    else showFailure(result.error)
                }
                is ApiResult.NotModified -> { busy = false; showMessage(R.string.host_operation_unknown) }
            }
        }
    }

    private fun renderManifests(result: AgentManifestStatusResponse) {
        val display = HostManifestPresentation.display(result.status)
        text(getString(R.string.host_manifest_count, display.manifests.size))
        text(getString(R.string.host_manifest_last_check, display.lastCheckUnix ?: getString(R.string.host_version_unknown)))
        text(getString(R.string.host_manifest_last_result, display.lastResult ?: getString(R.string.host_version_unknown)))
        if (display.manifests.isEmpty()) text(R.string.host_manifest_empty)
        display.manifests.forEach { row ->
            text(row.agent)
            text(getString(R.string.host_manifest_active, row.activeVersion ?: getString(R.string.host_version_unknown)))
            text(getString(R.string.host_manifest_cached, row.cachedRemoteVersion ?: getString(R.string.host_version_unknown)))
            text(getString(R.string.host_manifest_source, row.source.ifBlank { getString(R.string.host_version_unknown) }, row.sourceKind.ifBlank { getString(R.string.host_version_unknown) }))
            text(if (row.localOverrideShadowingRemote) R.string.host_manifest_override else R.string.host_manifest_no_override)
            text(getString(R.string.host_manifest_remote_last_check, row.remoteLastCheckedUnix ?: getString(R.string.host_version_unknown)))
            text(getString(R.string.host_manifest_update_result, row.remoteUpdateResult ?: getString(R.string.host_version_unknown)))
            row.remoteUpdateError?.let { text(getString(R.string.host_manifest_update_error, it)) }
            row.warning?.let { text(getString(R.string.host_manifest_warning, it)) }
        }
    }

    private fun showFailure(error: ApiFailure?) {
        val message = when (error) {
            is ApiFailure.Http -> error.message ?: getString(R.string.host_integrations_unavailable)
            is ApiFailure.Protocol -> error.message
            is ApiFailure.Network -> getString(R.string.host_integrations_offline)
            is ApiFailure.Timeout -> getString(R.string.host_integrations_offline)
            is ApiFailure.Redirect -> getString(R.string.host_integrations_unavailable)
            null -> getString(R.string.host_integrations_unavailable)
        }
        text(message)
    }

    private fun showMessage(id: Int) { content.removeAllViews(); text(id) }
    private fun text(id: Int) = text(getString(id))
    private fun text(value: String) {
        content.addView(TextView(this).apply { text = value; textSize = 15f; setPadding(0, dp(8), 0, dp(8)); applyAppTypeface() })
    }
    private fun button(label: Int, action: () -> Unit): MaterialButton = MaterialButton(this).also { view ->
        view.setText(label); view.isAllCaps = false; view.minHeight = dp(48); view.isEnabled = !busy
        view.setOnClickListener { action() }
        content.addView(view, LinearLayout.LayoutParams(-1, -2))
    }
    private fun hostScope(): Scope = Scope(host = repository.selectedScope.value.host)
    private fun isCurrent(scope: Scope): Boolean = repository.selectedScope.value.host == scope.host
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
