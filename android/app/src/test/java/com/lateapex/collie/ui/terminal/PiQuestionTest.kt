package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.PromptColumns
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PiQuestionTest {
    private fun fixture(name: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "web/src/fixtures/panes") }.first { it.isDirectory }
        return File(root, name).readText()
    }
    private fun panel() = fixture("pi--omo-v5130-panel-question.txt")

    @Test fun officialPanelQuestionNamesOnlyItsObservedColumnsAndFooterKeys() {
        val surface = AgentSemanticParser.detect("pi", panel(), 10)!!
        assertEquals(PromptColumns(0, 91), surface.expectedColumns)
        assertEquals("Pick a color?", surface.title)
        assertEquals(listOf("1"), surface.actions.single { it.label == "Red" }.keys)
        assertFalse(surface.regionSignature.contains("23.9K/200K"))
        assertNull(AgentSemanticParser.detect("omp", panel(), 10))
        val plain = AgentSemanticParser.detect("pi", fixture("pi--omo-v5130-plain-question.txt"), 11)!!
        assertNull(plain.expectedColumns)
    }

    @Test fun panelTicksPreserveQuestionIdentityButQuestionChangesDoNot() {
        val shown = AgentSemanticParser.detect("pi", panel(), 1)!!
        val ticked = AgentSemanticParser.detect("pi", panel().replace("23.9K/200K", "24.1K/200K").replace("5 queued", "6 queued"), 2)!!
        assertEquals(shown.regionSignature, ticked.regionSignature)
        assertEquals(shown.expectedColumns, ticked.expectedColumns)
        val action = shown.actions.single { it.label == "Red" }
        assertEquals(action, SemanticActionGuard.verify("pi", shown, action, panel().replace("5 queued", "6 queued"), 2))
        assertNull(SemanticActionGuard.verify("pi", shown.copy(expectedColumns = PromptColumns(0, 90)), action, panel(), 2))
        assertNull(SemanticActionGuard.verify("pi", shown, action, panel().replace("Pick a color?", "Pick a fruit?"), 2))
        assertTrue(PromptBinding.verifyNearTail(panel(), shown.regionSignature, expectedColumns = shown.expectedColumns))
        assertFalse(PromptBinding.verifyNearTail(panel().replace("Pick a color?", "Pick a fruit?"), shown.regionSignature, expectedColumns = shown.expectedColumns))
        assertNull(AgentSemanticParser.detect("pi", TerminalPlainText.strip(panel()), 1))
        assertNull(AgentSemanticParser.detect("pi", panel().replace("space\u001B[0m\u001B[38;2;160;160;160m select", "space\u001B[0m\u001B[38;2;160;160;160m toggle"), 1))
        assertNull(AgentSemanticParser.detect("pi", fixture("pi--omo-v5130-multi.txt"), 1))
        assertNull(AgentSemanticParser.detect("pi", fixture("pi--omo-v5130-answer-editor.txt"), 1))
    }

    @Test fun displayColumnsPreserveUnicodeClustersAtTheRangeBoundary() {
        assertEquals("漢e\u0301", PromptColumnText.crop("漢e\u0301Z", PromptColumns(0, 3)))
        assertEquals("", PromptColumnText.crop("漢", PromptColumns(0, 1)))
        assertEquals("👨‍💻", PromptColumnText.crop("👨‍💻Z", PromptColumns(0, 2)))
    }

    @Test fun semanticWriteCarriesTheRangeAndAWholeRowOnlyTransportRefuses() = runTest {
        val shown = AgentSemanticParser.detect("pi", panel(), 1)!!
        val actor = object : SemanticInteractionTransport {
            var sent: PromptColumns? = null
            override suspend fun read() = SemanticPane(panel().replace("5 queued", "6 queued"), 2)
            override suspend fun keys(keys: List<String>, expectedPrompt: String?) = SemanticWriteOutcome.OK
            override suspend fun reply(text: String, submit: Boolean, expectedPrompt: String?) = SemanticWriteOutcome.OK
            override suspend fun keys(keys: List<String>, expectedPrompt: String?, expectedColumns: PromptColumns?): SemanticWriteOutcome {
                assertEquals(listOf("1"), keys); assertEquals(shown.regionSignature, expectedPrompt)
                sent = expectedColumns; return SemanticWriteOutcome.OK
            }
        }
        assertEquals(SemanticInteractionResult.SENT, SemanticInteractionEngine(actor).execute("pi", shown, SemanticIntent.Static(shown.actions.single { it.label == "Red" })))
        assertEquals(PromptColumns(0, 91), actor.sent)
        val legacy = object : SemanticInteractionTransport {
            override suspend fun read() = SemanticPane(panel(), 1)
            override suspend fun keys(keys: List<String>, expectedPrompt: String?): SemanticWriteOutcome = error("must not discard range")
            override suspend fun reply(text: String, submit: Boolean, expectedPrompt: String?): SemanticWriteOutcome = error("must not discard range")
        }
        assertEquals(SemanticInteractionResult.CHANGED, SemanticInteractionEngine(legacy).execute("pi", shown, SemanticIntent.Static(shown.actions.single { it.label == "Red" })))
    }
}
