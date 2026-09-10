package com.lateapex.collie.ui

import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary

/** The bounded live-pane window supported by Herdr's pane.read implementation. */
internal object PaneScrollbackWindow {
    const val INITIAL_LINES = 600
    const val STEP_LINES = 600
    const val MAX_LINES = 1_000

    fun grow(lines: Int): Int = (lines + STEP_LINES).coerceAtMost(MAX_LINES)

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
    private val journalAgents = setOf("claude", "codex", "grok", "opencode", "pi")

    fun resolve(
        pane: PaneSummary?,
        mux: MuxConfigResponse?,
        requestedLines: Int,
    ): PaneBufferAffordance {
        if (pane == null) return PaneBufferAffordance(PaneBufferAction.NONE)

        val sessionLogCapable = mux?.supports("agentSessionRef") != false
        val scrollbackCapable = mux?.supports("gridScrollback") != false
        val historyAvailable = pane.hasSession == true && sessionLogCapable
        val moreScrollback = scrollbackCapable &&
            pane.readableLines?.let { requestedLines < it } == true &&
            requestedLines < PaneScrollbackWindow.MAX_LINES
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
