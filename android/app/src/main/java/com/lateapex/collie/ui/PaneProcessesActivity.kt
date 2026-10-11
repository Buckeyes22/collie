package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PaneProcessesResponse
import kotlinx.coroutines.launch

/** Read-only, non-retained process diagnostics for one scoped pane. */
class PaneProcessesActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var address: PaneAddress
    private lateinit var content: LinearLayout
    private var authorityRegistration: AutoCloseable? = null
    private var retired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        val paneId = intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.trim()?.takeIf(String::isNotEmpty) ?: run {
            finish()
            return
        }
        address = PaneAddress(
            Scope(
                host = intent.getStringExtra(PaneActivity.EXTRA_HOST)?.trim()?.takeIf(String::isNotEmpty),
                session = intent.getStringExtra(PaneActivity.EXTRA_SESSION)?.trim()?.takeIf(String::isNotEmpty),
            ),
            paneId,
        )
        repository.selectSubject(address.scope, address.paneId)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        setContentView(scroller)
        window.applySafeContentInsets(scroller)
        authorityRegistration = repository.onReadContextInvalidated { _ ->
            retired = true
            runOnUiThread { renderMessage(getString(R.string.pane_processes_unavailable)) }
        }
        load()
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        authorityRegistration = null
        super.onDestroy()
    }

    private fun load() {
        renderMessage(getString(R.string.pane_processes_loading))
        lifecycleScope.launch {
            val processes = repository.paneProcesses(address)
            if (isFinishing || retired) return@launch
            content.removeAllViews()
            addText(getString(R.string.pane_processes_title), 20f, bold = true)
            when (processes) {
                is ApiResult.Success -> {
                    processes.value.foregroundCwd?.takeIf(String::isNotBlank)?.let {
                        addText(getString(R.string.pane_processes_foreground_cwd, it), 16f)
                    }
                    processes.value.restoreError?.takeIf(String::isNotBlank)?.let {
                        addText(getString(R.string.pane_processes_restore_error, it), 16f)
                    }
                    renderProcesses(processes.value)
                }
                is ApiResult.NotModified -> renderMessage(getString(R.string.pane_processes_unavailable))
                is ApiResult.Failure -> {
                    val http = processes.error as? ApiFailure.Http
                    val message = if (http?.code == "capability.unsupported" || http?.status == 501 || (http?.status == 404 && http?.code == null)) {
                        getString(R.string.pane_processes_unsupported)
                    } else getString(R.string.pane_processes_unavailable)
                    addText(message, 16f)
                }
            }
        }
    }

    private fun renderProcesses(response: PaneProcessesResponse) {
        if (!response.ok || response.paneId != address.paneId) {
            addText(getString(R.string.pane_processes_unavailable), 16f)
            return
        }
        response.shellPid?.let { addText(getString(R.string.pane_processes_pid, it), 15f) }
        response.foregroundProcessGroupId?.let {
            addText(getString(R.string.pane_processes_foreground_group, it), 15f)
        }
        if (response.truncated) addText(getString(R.string.pane_processes_truncated), 14f)
        if (response.processes.isEmpty()) addText(getString(R.string.pane_processes_empty), 16f)
        response.processes.forEach { process ->
            addText("${process.name} · ${getString(R.string.pane_processes_pid, process.pid)}", 17f, bold = true)
            val args = process.argv?.takeIf { it.isNotEmpty() }?.joinToString(" ")
                ?: process.cmdline?.takeIf(String::isNotBlank)
                ?: process.argv0
            args?.let { addText(getString(R.string.pane_processes_arguments, it), 14f) }
            process.cwd?.let { addText(getString(R.string.pane_processes_directory, it), 14f) }
        }
    }

    private fun renderMessage(message: String) {
        content.removeAllViews()
        addText(getString(R.string.pane_processes_title), 20f, bold = true)
        addText(message, 16f)
    }

    private fun addText(value: String, sizeSp: Float, bold: Boolean = false) {
        content.addView(TextView(this).apply {
            text = value
            textSize = sizeSp
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(10))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context, address: PaneAddress): Intent = Intent(context, PaneProcessesActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        }
    }
}
