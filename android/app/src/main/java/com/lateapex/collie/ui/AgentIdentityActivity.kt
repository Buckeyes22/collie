package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentIdentityResponse
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch

/** Read-only, scoped agent/session provenance. State authority stays unknown unless the owner reports it. */
class AgentIdentityActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var address: PaneAddress
    private lateinit var content: LinearLayout
    private lateinit var viewModel: AgentIdentityViewModel
    private var authorityRegistration: AutoCloseable? = null

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
            ), paneId,
        )
        repository.selectSubject(address.scope, address.paneId)
        viewModel = androidx.lifecycle.ViewModelProvider(this, AgentIdentityViewModel.Factory(application, address))
            .get(AgentIdentityViewModel::class.java)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        setContentView(scroller)
        window.applySafeContentInsets(scroller)
        authorityRegistration = repository.onReadContextInvalidated { _: AuthorityInvalidation ->
            clearContentAndAcknowledge(renderCurrentState = true)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
        viewModel.start()
    }

    override fun onStop() {
        // Do not leave private identity text displayed in a stopped window/snapshot.
        if (::viewModel.isInitialized) viewModel.screenStopped()
        clearContentAndAcknowledge()
        super.onStop()
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        authorityRegistration = null
        super.onDestroy()
    }

    private fun clearContentAndAcknowledge(renderCurrentState: Boolean = false) {
        if (!::content.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            content.removeAllViews()
            if (renderCurrentState && ::viewModel.isInitialized) renderState(viewModel.state.value)
            return
        }
        val complete = CountDownLatch(1)
        val posted = Handler(Looper.getMainLooper()).post {
            if (::content.isInitialized) {
                content.removeAllViews()
                if (renderCurrentState && ::viewModel.isInitialized) renderState(viewModel.state.value)
            }
            complete.countDown()
        }
        if (!posted) return
        var interrupted = false
        try {
            while (true) {
                try {
                    complete.await()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun renderState(state: AgentIdentityUiState) {
        if (!viewModel.isCurrentPresentation(state)) return
        content.removeAllViews()
        addText(getString(R.string.agent_identity_title), 20f, bold = true)
        when {
            state.loading -> addText(getString(R.string.agent_identity_loading), 16f)
            state.identity != null -> renderIdentity(state.identity)
            else -> addText(problemText(state.problem), 16f)
        }
        content.addView(Button(this).apply {
            text = getString(R.string.agent_identity_refresh)
            contentDescription = getString(R.string.agent_identity_refresh_description)
            isEnabled = !state.loading
            setOnClickListener {
                viewModel.refresh()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun problemText(problem: AgentIdentityProblem?): String = when (problem) {
        AgentIdentityProblem.AUTH -> getString(R.string.agent_identity_auth_refused)
        AgentIdentityProblem.NOT_FOUND -> getString(R.string.agent_identity_pane_missing)
        AgentIdentityProblem.UNSUPPORTED -> getString(R.string.agent_identity_unsupported)
        AgentIdentityProblem.RETIRED -> getString(R.string.agent_identity_retired)
        AgentIdentityProblem.STORAGE_RETAINED -> getString(R.string.pairing_storage_retained)
        AgentIdentityProblem.TEMPORARY, null -> getString(R.string.agent_identity_unavailable)
    }

    private fun renderIdentity(response: AgentIdentityResponse) {
        if (!response.ok || response.paneId != address.paneId || response.stateAuthority.observed || response.stateAuthority.source != null) {
            addText(getString(R.string.agent_identity_unavailable), 16f)
            return
        }
        addText(getString(R.string.agent_identity_agent, response.agent ?: getString(R.string.agent_identity_none)), 16f)
        addText(getString(R.string.agent_identity_status, response.status.name.lowercase()), 16f)
        addText(getString(R.string.agent_identity_authority_unknown), 16f)
        addText(getString(R.string.agent_identity_ready, response.interactiveReady?.let(::yesNo) ?: getString(R.string.agent_identity_unknown)), 16f)
        addText(getString(R.string.agent_identity_launch_pending, response.launchPending?.let(::yesNo) ?: getString(R.string.agent_identity_unknown)), 16f)
        addText(getString(R.string.agent_identity_state_sequence, response.stateChangeSeq ?: getString(R.string.agent_identity_unknown)), 16f)
        addText(getString(R.string.agent_identity_completion_sequence, response.completionSeq ?: getString(R.string.agent_identity_unknown)), 16f)
        val session = response.session
        if (session == null) {
            addText(getString(R.string.agent_identity_session_none), 16f)
        } else {
            addText(getString(R.string.agent_identity_session_source, session.source), 16f)
            addText(getString(R.string.agent_identity_session_agent, session.agent), 16f)
            addText(getString(R.string.agent_identity_session_kind, session.kind), 16f)
            addText(getString(R.string.agent_identity_session_value, session.value), 14f)
        }
        addText(getString(R.string.agent_identity_mutations_unavailable), 14f)
    }

    private fun yesNo(value: Boolean): String = getString(if (value) R.string.common_yes else R.string.common_no)

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
        fun intent(context: Context, address: PaneAddress): Intent = Intent(context, AgentIdentityActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        }
    }
}
