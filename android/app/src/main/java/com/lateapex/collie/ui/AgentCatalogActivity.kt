package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentCapabilitiesResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.AgentCapabilityRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.launch

/** Read-only capability evidence for the current owning bridge; unmeasured is shown explicitly. */
class AgentCatalogActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var content: LinearLayout
    private lateinit var refresh: MaterialButton
    private var authorityRegistration: AutoCloseable? = null
    @Volatile private var retired = false
    private var custodyActor: com.lateapex.collie.data.ScopedReadContext? = null
    private var inFlight = false
    private lateinit var scope: Scope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        scope = Scope(
            host = intent.getStringExtra(EXTRA_HOST)?.trim()?.takeIf(String::isNotEmpty),
            session = intent.getStringExtra(EXTRA_SESSION)?.trim()?.takeIf(String::isNotEmpty),
        )
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        setContentView(scroller)
        window.applySafeContentInsets(scroller)
        content.addView(text(getString(R.string.agent_catalog_title), 22f, bold = true))
        content.addView(text(getString(R.string.agent_catalog_explanation), 15f))
        refresh = MaterialButton(this).apply {
            text = getString(R.string.agent_catalog_refresh)
            setOnClickListener { load() }
        }
        content.addView(refresh)
        authorityRegistration = repository.onReadContextRetired { event ->
            withRetirementOnMain(event, { custodyActor }) {
                retired = true
                runOnUiThread { showMessage(getString(R.string.agent_catalog_unavailable)) }
            }
        }
        load()
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        authorityRegistration = null
        super.onDestroy()
    }

    private fun load() {
        if (inFlight || retired) return
        inFlight = true
        refresh.isEnabled = false
        showMessage(getString(R.string.agent_catalog_loading))
        lifecycleScope.launch {
            val captured = repository.readContext(scope)
            if (captured == null) {
                showMessage(getString(R.string.agent_catalog_unavailable))
                finishLoad()
                return@launch
            }
            custodyActor = captured
            val result = repository.agentCapabilities(scope, captured)
            if (isFinishing || retired || !repository.isCurrent(captured)) {
                finishLoad()
                return@launch
            }
            when (result) {
                is ApiResult.Success -> render(result.value)
                is ApiResult.NotModified -> showMessage(getString(R.string.agent_catalog_unavailable))
                is ApiResult.Failure -> {
                    val failure = result.error as? ApiFailure.Http
                    val unsupported = (result.error as? ApiFailure.Protocol)?.message == "Unsupported agent capability schema" ||
                        failure?.code == "capability.unsupported" || failure?.status == 501 ||
                        (failure?.status == 404 && failure.code == null)
                    showMessage(getString(if (unsupported) R.string.agent_catalog_unsupported else R.string.agent_catalog_unavailable))
                }
            }
            finishLoad()
        }
    }

    private fun render(response: AgentCapabilitiesResponse) {
        content.removeAllViews()
        content.addView(text(getString(R.string.agent_catalog_title), 22f, bold = true))
        content.addView(text(getString(R.string.agent_catalog_explanation), 15f))
        content.addView(refresh)
        content.addView(text(getString(R.string.agent_catalog_backend, response.mux), 14f, bold = true))
        content.addView(text(response.note, 14f))
        response.muxCapabilities?.let { capabilities ->
            content.addView(text(getString(
                R.string.agent_catalog_mux_features,
                stateLabel(if (capabilities.agentDetection) "implemented" else "unsupported"),
                stateLabel(if (capabilities.agentSessionRef) "implemented" else "unsupported"),
            ), 14f))
        }
        content.addView(text(getString(R.string.agent_catalog_columns), 13f, bold = true))
        response.rows.forEach { row -> renderRow(row) }
    }

    private fun renderRow(row: AgentCapabilityRow) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_pane_card)
        }
        card.addView(text(row.label, 17f, bold = true))
        val executable = when (row.executableFound) {
            true -> getString(R.string.agent_catalog_executable_found)
            false -> getString(R.string.agent_catalog_executable_missing)
            null -> getString(R.string.agent_catalog_not_probed)
        }
        val version = row.version ?: getString(R.string.agent_catalog_not_probed)
        card.addView(text(getString(R.string.agent_catalog_launch_line, stateLabel(row.launch), executable, version), 13f))
        card.addView(text(getString(
            R.string.agent_catalog_dimensions,
            stateLabel(row.detection), stateLabel(row.sessionReference), stateLabel(row.journal),
            stateLabel(row.webSemanticDialog), stateLabel(row.webComposer), stateLabel(row.media),
        ), 13f))
        if (row.observedPanes != null || row.observedSessionReferences != null) {
            card.addView(text(getString(
                R.string.agent_catalog_observed,
                row.observedPanes ?: 0,
                row.observedSessionReferences ?: 0,
            ), 12f))
        }
        val evidenceLines = listOf("journal", "semantic", "media").mapNotNull { key ->
            (row.evidence?.get(key) as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                ?.let { "$key: $it" }
        }
        if (evidenceLines.isNotEmpty()) card.addView(text(evidenceLines.joinToString("\n"), 12f))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun stateLabel(value: String): String = getString(when (value) {
        "implemented" -> R.string.agent_catalog_implemented
        "unsupported" -> R.string.agent_catalog_unsupported_cell
        "interface-ready" -> R.string.agent_catalog_interface_ready
        else -> R.string.agent_catalog_unverified
    })

    private fun showMessage(message: String) {
        if (!::content.isInitialized) return
        val header = content.getChildAt(0)
        val explanation = content.getChildAt(1)
        content.removeAllViews()
        header?.let(content::addView)
        explanation?.let(content::addView)
        if (::refresh.isInitialized && refresh.parent == null) content.addView(refresh)
        content.addView(text(message, 15f))
    }

    private fun finishLoad() {
        inFlight = false
        if (::refresh.isInitialized) refresh.isEnabled = !retired
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        setTextColor(ContextCompat.getColor(this@AgentCatalogActivity, R.color.collie_foreground))
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"

        fun intent(context: Context, scope: Scope = Scope()): Intent = Intent(context, AgentCatalogActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host)
            putExtra(EXTRA_SESSION, scope.session)
        }
    }
}
