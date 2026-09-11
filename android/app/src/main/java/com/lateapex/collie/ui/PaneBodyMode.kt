package com.lateapex.collie.ui

import com.lateapex.collie.ui.terminal.SemanticSurface

enum class PaneBody { TRANSCRIPT, MIRROR }
enum class PaneBodyReason { NONE, DIALOG, OVERLAY, NO_JOURNAL, RAW, PENDING }

data class PaneBodyMode(val body: PaneBody, val reason: PaneBodyReason)

/** §4 item 2: the transcript is the body unless something on screen needs the grid itself. */
object PaneBodyDecision {
    fun decide(
        surface: SemanticSurface?,
        transcriptAvailable: Boolean?,
        rawTerminal: Boolean,
        agent: String?,
        /**
         * The agent's own input box has been missing from the screen for more than one poll with
         * no recognised dialog: a panel such as /cost or /status is up, Send is refused, and only
         * the grid shows why (S25 Ultra walk, 2026-09-11).
         */
        composerMissing: Boolean = false,
    ): PaneBodyMode = when {
        rawTerminal -> PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.RAW)
        surface != null -> PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.DIALOG)
        agent.isNullOrBlank() || agent.equals("shell", ignoreCase = true) ->
            PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL)
        composerMissing -> PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.OVERLAY)
        transcriptAvailable == false -> PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL)
        transcriptAvailable == null -> PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.PENDING)
        else -> PaneBodyMode(PaneBody.TRANSCRIPT, PaneBodyReason.NONE)
    }
}
