package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.AGENT_WAIT_EVENTS
import com.lateapex.collie.network.NamedAgentWait

class WaitsActivity : AgentAutomationActivity() {
    override val waitsOnly = true
    companion object { fun intent(context: Context, address: PaneAddress): Intent = agentAutomationIntent(context, address, WaitsActivity::class.java) }
}

internal fun renderWaits(state: AgentAutomationUiState, model: AgentAutomationViewModel, content: LinearLayout, address: PaneAddress) {
    val context = content.context; val info = state.control?.info
    fun text(value: String) { content.addView(TextView(context).apply { text = value; textSize = 16f; isSaveEnabled = false }) }
    fun spinner(items: List<String>, field: Int, label: Int): Spinner = Spinner(context).apply { id = field; adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items); contentDescription = context.getString(label); isSaveEnabled = false; content.addView(this) }
    fun edit(field: Int, label: Int, maximum: Int): EditText = EditText(context).apply { id = field; hint = context.getString(label); contentDescription = context.getString(label); isSaveEnabled = false; filters = arrayOf(InputFilter.LengthFilter(maximum)); content.addView(this) }
    state.wait?.result?.let { result ->
        when (result.kind) {
            "lifecycle" -> text(context.getString(R.string.agent_waits_status_result, result.info?.status.orEmpty()))
            "event" -> text(context.getString(R.string.agent_waits_event_result, result.event.orEmpty(), result.targetId.orEmpty()))
            "output" -> content.addView(TextView(context).apply { text = result.text.orEmpty(); typeface = Typeface.MONOSPACE; setTextIsSelectable(true); isSaveEnabled = false; if (result.truncated == true) contentDescription = context.getString(R.string.agent_waits_truncated) })
        }
    }
    if (model.savedWait() != null) {
        content.addView(Button(context).apply { id = R.id.agent_waits_cancel; text = context.getString(R.string.agent_waits_cancel); isAllCaps = false; setOnClickListener { model.cancelWait(state) } })
        text(context.getString(R.string.agent_waits_cancel_help)); return
    }
    if (state.busy || info == null) return
    text(context.getString(R.string.agent_waits_bound))
    val modes = spinner(listOf(context.getString(R.string.agent_waits_lifecycle), context.getString(R.string.agent_waits_output), context.getString(R.string.agent_waits_event)), R.id.agent_waits_mode, R.string.agent_waits_mode)
    val status = spinner(listOf("idle", "working", "blocked", "done", "unknown"), R.id.agent_waits_status, R.string.agent_waits_status)
    val source = spinner(listOf("visible", "recent", "recent_unwrapped", "detection"), R.id.agent_waits_source, R.string.agent_waits_source)
    val pattern = edit(R.id.agent_waits_pattern, R.string.agent_waits_pattern, 1024)
    val regex = CheckBox(context).apply { id = R.id.agent_waits_regex; text = context.getString(R.string.agent_waits_regex); isSaveEnabled = false; content.addView(this) }
    val event = spinner(AGENT_WAIT_EVENTS, R.id.agent_waits_event, R.string.agent_waits_event)
    val seconds = edit(R.id.agent_waits_seconds, R.string.agent_waits_seconds, 3).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("30") }
    content.addView(Button(context).apply {
        id = R.id.agent_waits_start; text = context.getString(R.string.agent_waits_start); isAllCaps = false
        setOnClickListener {
            if (!model.current(state)) return@setOnClickListener
            val selected = event.selectedItem.toString()
            val target = when { selected.endsWith("created") -> null; selected.startsWith("workspace-") -> info.workspaceId; selected.startsWith("tab-") -> info.tabId; else -> address.paneId }
            val condition = when (modes.selectedItemPosition) {
                0 -> NamedAgentWait.Lifecycle(listOf(status.selectedItem.toString()))
                1 -> NamedAgentWait.Output(source.selectedItem.toString(), pattern.text.toString(), regex.isChecked)
                else -> NamedAgentWait.Event(selected, target, workspaceId = info.workspaceId.takeIf { selected == "tab-created" || selected == "pane-created" }, status = status.selectedItem.toString().takeIf { selected == "agent-status-changed" })
            }
            val timeout = seconds.text.toString().toIntOrNull()?.takeIf { it in 1..300 }?.times(1000)
            if (timeout == null || !condition.valid()) Toast.makeText(context, R.string.agent_control_invalid, Toast.LENGTH_LONG).show()
            else model.wait(state, condition, timeout)
        }
    })
}
