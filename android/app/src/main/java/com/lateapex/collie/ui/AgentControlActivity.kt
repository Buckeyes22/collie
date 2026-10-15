package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.NAMED_AGENT_KINDS
import com.lateapex.collie.network.NamedAgentAction
import kotlinx.coroutines.launch

class AgentControlActivity : AgentAutomationActivity() {
    override val waitsOnly = false
    companion object { fun intent(context: Context, address: PaneAddress): Intent = agentAutomationIntent(context, address, AgentControlActivity::class.java) }
}

/** Programmatic native forms; private text never receives saved hierarchy or link authority. */
abstract class AgentAutomationActivity : AppCompatActivity() {
    protected abstract val waitsOnly: Boolean
    private lateinit var address: PaneAddress
    private lateinit var content: LinearLayout
    private lateinit var model: AgentAutomationViewModel
    private var copiedActor: ScopedReadContext? = null
    private var registration: AutoCloseable? = null
    private var dialog: AlertDialog? = null
    private var restored: SavedAgentWait? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.prepareEdgeToEdgeContent()
        val pane = intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) } ?: run { finish(); return }
        address = PaneAddress(Scope(intent.getStringExtra(PaneActivity.EXTRA_HOST), intent.getStringExtra(PaneActivity.EXTRA_SESSION)), pane)
        restored = savedInstanceState?.let { state ->
            val id = state.getString("agent.wait.id") ?: return@let null; val owner = state.getString("agent.wait.owner") ?: return@let null
            val incarnation = state.getString("agent.wait.incarnation") ?: return@let null; val witness = state.getString("agent.wait.witness") ?: return@let null
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !owner.matches(Regex("[a-f0-9]{64}")) || !witness.matches(Regex("[a-f0-9]{64}")) || incarnation.length !in 1..128) null else SavedAgentWait(id, owner, incarnation, witness)
        }
        model = ViewModelProvider(this, AgentAutomationViewModel.Factory(application, address)).get(AgentAutomationViewModel::class.java)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); isSaveFromParentEnabled = false }
        val scroller = ScrollView(this).apply { addView(content); isSaveEnabled = false }
        setContentView(scroller); window.applySafeContentInsets(scroller)
        registration = (application as CollieApplication).container.repository.onReadContextRetired { event ->
            if (event.reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN && !event.retiresReadActor) return@onReadContextRetired
            withRetirementOnMain(event, { copiedActor }) { clearCopies(); render(model.state.value) }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) { model.resumed(restored); restored = null } }
    override fun onStop() { if (::model.isInitialized) model.stopped(); withMainCustodyAck { clearCopies() }; super.onStop() }
    override fun onDestroy() { registration?.close(); registration = null; dialog?.dismiss(); dialog = null; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::model.isInitialized) model.savedWait()?.let { saved -> outState.putString("agent.wait.id", saved.id); outState.putString("agent.wait.owner", saved.owner); outState.putString("agent.wait.incarnation", saved.incarnation); outState.putString("agent.wait.witness", saved.witness) }
        super.onSaveInstanceState(outState)
    }
    private fun clearCopies() { dialog?.dismiss(); dialog = null; if (::content.isInitialized) content.removeAllViews(); copiedActor = null }
    private fun render(state: AgentAutomationUiState) {
        if (!model.current(state)) return
        clearCopies(); copiedActor = model.originalActor(state)
        text(getString(if (waitsOnly) R.string.agent_waits_title else R.string.agent_control_title), 22f, bold = true)
        text(getString(R.string.agent_control_scope, address.scope.host ?: getString(R.string.agent_control_this_host), address.scope.session ?: getString(R.string.agent_control_default_session), address.paneId))
        text(notice(state.notice)).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        (state.problemCode ?: state.wait?.code)?.takeIf { it.matches(Regex("agent\\.[a-z_]{1,64}")) }?.let { text(getString(R.string.agent_control_problem_code, it)) }
        val control = state.control
        if (control != null) {
            text(getString(R.string.agent_control_identity, control.info.name ?: getString(R.string.agent_control_unnamed), control.info.agent ?: getString(R.string.agent_control_shell), control.info.status))
            if (!waitsOnly) {
                button(R.string.agent_control_explain, R.id.agent_control_explain, !state.busy) { model.explain(state) }
                button(R.string.agent_control_census, R.id.agent_control_census, !state.busy) { model.census(state) }
                state.namedAgents?.forEach { info -> text(getString(R.string.agent_control_identity, info.name ?: getString(R.string.agent_control_unnamed), info.agent ?: getString(R.string.agent_control_shell), info.status)) }
                state.screen?.let { text(it).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) } }
                state.explain?.explain?.let { explain -> text(listOfNotNull(explain.manifestSource, explain.manifestVersion, explain.fallbackReason, explain.warning).joinToString("\n")) }
                if (state.pending != null && state.pending.state == "pending") {
                    // Original dispatch is still running. Keep its custody, but expose result
                    // reconciliation only after an uncertain result actually exists.
                } else if (state.pending != null) {
                    text(getString(R.string.agent_control_uncertain))
                    button(R.string.agent_control_reconcile, R.id.agent_control_reconcile, !state.busy) { model.reconcile(state) }
                    button(R.string.agent_control_abandon, R.id.agent_control_abandon, !state.busy) {
                        if (!model.current(state)) return@button
                        dialog = AlertDialog.Builder(this).setTitle(R.string.agent_control_abandon).setMessage(R.string.agent_control_abandon_warning)
                            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.agent_control_abandon) { _, _ -> if (model.current(state)) model.abandon(state) }.create().also { it.show() }
                    }
                } else if (!state.busy && state.writable) {
                    if (control.info.agent == null) renderStart(state) else renderAgent(state)
                }
                button(R.string.agent_control_clear_history, R.id.agent_control_clear_history, !state.busy && state.pending == null) { model.clearHistory(state) }
                button(R.string.agent_waits_title, R.id.agent_control_open_waits, !state.busy) { if (model.current(state)) startActivity(WaitsActivity.intent(this, address)) }
            }
        }
        if (waitsOnly) renderWaits(state, model, content, address)
        button(R.string.agent_control_refresh, R.id.agent_control_refresh, !state.busy) { model.refresh() }
    }
    private fun renderStart(state: AgentAutomationUiState) {
        val name = editor(R.string.agent_control_name, R.id.agent_control_name, 32)
        val kinds = Spinner(this).apply { id = R.id.agent_control_kind; adapter = ArrayAdapter(this@AgentAutomationActivity, android.R.layout.simple_spinner_dropdown_item, NAMED_AGENT_KINDS); contentDescription = getString(R.string.agent_control_kind); isSaveEnabled = false }
        content.addView(kinds)
        val args = editor(R.string.agent_control_args, R.id.agent_control_args, 16_384, multiline = true)
        text(getString(R.string.agent_control_args_help))
        button(R.string.agent_control_start, R.id.agent_control_start, true) { model.act(state, NamedAgentAction.Start(name.text.toString(), kinds.selectedItem.toString(), args.text.toString().lines().filter { it.isNotEmpty() })) }
    }
    private fun renderAgent(state: AgentAutomationUiState) {
        val name = editor(R.string.agent_control_name, R.id.agent_control_name, 32)
        button(R.string.agent_control_rename, R.id.agent_control_rename, true) { model.act(state, NamedAgentAction.Rename(name.text.toString().ifBlank { null })) }
        button(R.string.agent_control_focus, R.id.agent_control_focus, true) { model.act(state, NamedAgentAction.Focus) }
        val keys = editor(R.string.agent_control_keys, R.id.agent_control_keys, 1024)
        button(R.string.agent_control_send_keys, R.id.agent_control_send_keys, state.screen != null) { model.act(state, NamedAgentAction.Keys(keys.text.toString().split(',').map(String::trim).filter(String::isNotEmpty))) }
        val prompt = editor(R.string.agent_control_prompt_text, R.id.agent_control_prompt_text, 16_384, multiline = true)
        button(R.string.agent_control_input, R.id.agent_control_input, state.screen != null) { model.act(state, NamedAgentAction.Input(prompt.text.toString(), keys.text.toString().split(',').map(String::trim).filter(String::isNotEmpty))) }
        val wait = CheckBox(this).apply { text = getString(R.string.agent_control_prompt_wait); id = R.id.agent_control_prompt_wait; isSaveEnabled = false }
        content.addView(wait)
        button(R.string.agent_control_prompt, R.id.agent_control_prompt, state.screen != null && state.control?.info?.let { it.interactiveReady && !it.launchPending } == true) {
            model.act(state, NamedAgentAction.Prompt(prompt.text.toString(), if (wait.isChecked) listOf("idle", "done") else null))
        }
    }
    private fun editor(label: Int, field: Int, maximum: Int, multiline: Boolean = false): EditText = EditText(this).apply {
        id = field; hint = getString(label); contentDescription = getString(label); isSaveEnabled = false; setSingleLine(!multiline)
        filters = arrayOf(android.text.InputFilter.LengthFilter(maximum)); minHeight = dp(48); content.addView(this)
    }
    private fun button(label: Int, field: Int, enabled: Boolean, action: () -> Unit) { content.addView(Button(this).apply { id = field; text = getString(label); isAllCaps = false; minHeight = dp(48); isEnabled = enabled; setOnClickListener { action() } }) }
    private fun text(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply { text = value; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); isSaveEnabled = false; content.addView(this) }
    private fun notice(value: String): String = getString(when (value) {
        "loading" -> R.string.agent_control_loading
        "dispatching" -> R.string.agent_control_dispatching
        "succeeded" -> R.string.agent_control_submitted
        "settled" -> R.string.agent_control_settled
        "refused" -> R.string.agent_control_refused
        "unknown", "reconciling" -> R.string.agent_control_uncertain
        "storage-unavailable" -> R.string.agent_control_storage_unavailable
        "retired" -> R.string.agent_control_retired
        "unavailable" -> R.string.agent_control_unavailable
        "invalid" -> R.string.agent_control_invalid
        "abandoned" -> R.string.agent_control_abandoned
        "observing", "pending" -> R.string.agent_waits_observing
        "matched" -> R.string.agent_waits_matched
        "cancelled" -> R.string.agent_waits_cancelled
        "timed-out" -> R.string.agent_waits_timeout
        "interrupted" -> R.string.agent_waits_interrupted
        else -> R.string.agent_control_ready
    })
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun agentAutomationIntent(context: Context, address: PaneAddress, target: Class<out AgentAutomationActivity>): Intent = Intent(context, target).apply {
    putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId); putExtra(PaneActivity.EXTRA_HOST, address.scope.host); putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
}
