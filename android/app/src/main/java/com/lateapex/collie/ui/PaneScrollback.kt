package com.lateapex.collie.ui

import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary

/** The bounded live-pane window supported by Herdr's pane.read implementation. */
internal object PaneScrollbackWindow {
    const val INITIAL_LINES = 600
    const val STEP_LINES = 600
    const val LEGACY_MAX_LINES = 1_000
    const val MAX_DESIRED_LINES = 10_000

    fun maxLines(mux: MuxConfigResponse?): Int = mux?.readLimits?.verifiedMaximum() ?: LEGACY_MAX_LINES

    fun grow(lines: Int, maximum: Int): Int = (lines + STEP_LINES).coerceAtMost(maximum)

    /** Keep the user's desired depth even when the next effective request is capped. */
    fun growDesired(lines: Int): Int = (lines + STEP_LINES).coerceAtMost(MAX_DESIRED_LINES)

    fun restoreTop(oldHeight: Int, oldTop: Int, newHeight: Int): Int =
        (oldTop + (newHeight - oldHeight)).coerceAtLeast(0)
}

internal enum class PaneBufferAction {
    NONE,
    SHOW_HISTORY,
    LOAD_OLDER,
}

/**
 * Transcript history and terminal scrollback are independent capabilities. The explanation is
 * therefore independent of the primary action: a mux may offer Load older while explaining that
 * it can never offer an agent transcript.
 */
internal data class PaneBufferAffordance(
    val action: PaneBufferAction,
    val muxExplanation: String? = null,
    val missingSessionAgent: String? = null,
)

internal object PaneBufferPolicy {
    // Muse discovers its own sessions; installing a Herdr hook cannot repair that adapter.
    private val journalAgents = setOf("claude", "codex", "grok", "hermes", "omp", "opencode", "pi")

    fun resolve(
        pane: PaneSummary?,
        mux: MuxConfigResponse?,
        requestedLines: Int,
        read: com.lateapex.collie.network.PaneReadResponse? = null,
    ): PaneBufferAffordance {
        if (pane == null) return PaneBufferAffordance(PaneBufferAction.NONE)

        val sessionLogCapable = mux?.supports("agentSessionRef") != false
        val scrollbackCapable = mux?.supports("gridScrollback") != false
        val historyAvailable = pane.hasSession == true && sessionLogCapable
        // This only ends growth of the current terminal mirror window: a short non-truncated read is
        // evidence that this request returned all rows its adapter currently offered, never proof
        // that agent-owned alternate-screen history or a journal is complete.
        val sameWindowReadHadNoMore = read?.requestedLines == requestedLines && !read.truncated &&
            (read.servedLines ?: read.text.lineSequence().count()) < requestedLines
        val moreScrollback = !sameWindowReadHadNoMore && scrollbackCapable &&
            pane.readableLines?.let { requestedLines < it } == true &&
            requestedLines < PaneScrollbackWindow.maxLines(mux)
        val action = when {
            historyAvailable -> PaneBufferAction.SHOW_HISTORY
            moreScrollback -> PaneBufferAction.LOAD_OLDER
            else -> PaneBufferAction.NONE
        }
        val muxExplanation = if (sessionLogCapable) {
            null
        } else {
            mux?.notes?.get("agentSessionRef")?.trim()?.takeIf(String::isNotEmpty)
        }
        val missingSessionAgent = pane.agent.takeIf {
            sessionLogCapable && pane.hasSession != true && it in journalAgents
        }
        return PaneBufferAffordance(action, muxExplanation, missingSessionAgent)
    }
}
