package com.lateapex.collie.ui

import com.lateapex.collie.ui.terminal.SemanticKind
import com.lateapex.collie.ui.terminal.SemanticSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneBodyModeTest {
    private val dialog = SemanticSurface(SemanticKind.PROMPT_SELECT, "q", regionSignature = "s", revision = 0, startLine = 0, endLine = 1)

    @Test fun rawWins() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.RAW), PaneBodyDecision.decide(dialog, true, rawTerminal = true, agent = "claude"))
    @Test fun dialogForcesMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.DIALOG), PaneBodyDecision.decide(dialog, true, false, "claude"))
    @Test fun shellHasNoJournal() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL), PaneBodyDecision.decide(null, true, false, "shell"))
    @Test fun unavailableHistoryIsMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL), PaneBodyDecision.decide(null, false, false, "codex"))
    @Test fun pendingIsMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.PENDING), PaneBodyDecision.decide(null, null, false, "pi"))
    @Test fun otherwiseTranscript() = assertEquals(PaneBodyMode(PaneBody.TRANSCRIPT, PaneBodyReason.NONE), PaneBodyDecision.decide(null, true, false, "claude"))
}
