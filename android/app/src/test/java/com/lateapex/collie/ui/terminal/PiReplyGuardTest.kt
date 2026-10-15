package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PromptColumns
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PiReplyGuardTest {
    private fun fixture(name: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "web/src/fixtures/panes") }.first { it.isDirectory }
        return File(root, name).readText()
    }
    private val success = ApiResult.Success(ActionResponse(ok = true), 200)
    private val rule = "─".repeat(40)
    private fun composer(draft: String = "") = "\u001B[38;2;100;120;140m$rule\u001B[0m\n$draft\n\u001B[38;2;100;120;140m$rule\u001B[0m\n\u001B[2mmodel · /repo\u001B[0m"

    @Test fun officialStockAndOmoEditorsAreRecognizedWithoutInferringFromAgentNames() {
        listOf("pi--v0851-idle.txt", "pi--v0871-idle.txt", "pi--v110-stock-idle.txt", "pi--v110-idle-default-footer.txt", "pi--omo-v5130-idle.txt").forEach { name ->
            val source = fixture(name)
            assertNotNull(name, PiComposer.locate(source))
            assertNotNull(name, PromptBinding.composerRegion("pi", source))
            assertNull(name, TerminalComposerSemantics.terminalDraft("pi", source))
        }
        listOf("pi--v0851-draft.txt", "pi--v0871-draft.txt", "pi--v110-stock-draft.txt", "pi--omo-v5130-draft.txt", "pi--v110-draft-multiline.txt").forEach { name ->
            val source = fixture(name)
            assertNotNull(name, PiComposer.locate(source)?.draft())
            assertNull(name, PromptBinding.composerRegion("pi", source))
            assertNotNull(name, TerminalComposerSemantics.terminalDraft("pi", source))
        }
    }

    @Test fun questionsInlineEditorsPickersAndUnpaintedQuotedFramesNeverBecomeComposers() {
        listOf("pi--omo-v5130-panel-question.txt", "pi--omo-v5130-plain-question.txt", "pi--omo-v5130-multi.txt",
            "pi--omo-v5130-answer-editor.txt", "pi--v110-core-selector.txt", "pi--omo-v5130-picker-tree.txt", "pi--v110-stock-picker-tree.txt").forEach { name ->
            val source = fixture(name)
            assertNull(name, PiComposer.locate(source))
            assertNull(name, PromptBinding.composerRegion("pi", source))
            assertNull(name, TerminalComposerSemantics.terminalDraft("pi", source))
        }
        assertNull(PiComposer.locate("$rule\n\n$rule\nmodel · /repo"))
        assertNull(PiComposer.locate(composer().replace("model · /repo", "model\nplain\nmore prose")))
    }

    @Test fun panelClockAndPathDoNotEnterTheDraftOrBinding() {
        val idle = fixture("pi--omo-v5130-panel-idle.txt")
        val draft = fixture("pi--omo-v5130-panel-draft.txt")
        val observed = PiComposer.locate(draft)!!
        assertEquals(PromptColumns(0, 91), observed.columns)
        assertEquals("hello collie draft", observed.draft())
        assertFalse(observed.region().contains("MEMORY"))
        assertNotNull(PromptBinding.composerRegion("pi", idle))
        val phantom = fixture("pi--omo-v5130-panel-phantom.txt")
        assertNull(PiComposer.locate(phantom)!!.draft())
        assertNotNull(PromptBinding.composerRegion("pi", phantom))
        assertTrue(PromptBinding.verifyNearTail(draft.replace("4 queued", "5 queued"), observed.region(), expectedColumns = observed.columns))
    }

    @Test fun typingReadsBackTheEntireUnicodeDraftThenSubmitsExactlyOnce() = runTest {
        val sent = "漢字 e\u0301 👨‍💻"
        val observations = ArrayDeque(listOf(composer("漢字"), composer(sent), composer()))
        val writes = mutableListOf<Pair<String, Boolean>>()
        val result = PiReplyGuard.send(sent, PiComposer.locate(composer())!!, "unconfirmed",
            reply = { message, submit, binding, columns ->
                writes += message to submit; assertNull(columns)
                assertTrue(binding.startsWith(rule)); success
            }, read = { observations.removeFirst() }, sleep = {}) as ApiResult.Success
        assertTrue(result.value.ok)
        assertEquals(listOf(sent to false, "" to true), writes)
        assertTrue(PiReplyGuard.carries("日本語のメッセージ", "日本語の メッセージ"))
        assertFalse(PiReplyGuard.carries("delete file", "delete　file"))
        assertFalse(PiReplyGuard.carries("hello", "earlier hello"))
        assertFalse(PiReplyGuard.carries("hello", "hell"))
        assertTrue(PiReplyGuard.carries("inspect example-token", "inspect •••••••••••••"))
        assertFalse(PiReplyGuard.carries("inspect example-token", "inspect ••••••••••••"))
        assertFalse(PiReplyGuard.carries("漢字", "••"))
        assertFalse(PiReplyGuard.carries("e\u0301", "é"))
        assertFalse(PiReplyGuard.carries("🐕".repeat(1001), "[Pasted Content 1001 chars]"))
    }

    @Test fun changedQuestionPartialDraftAndAcknowledgedButUnconsumedTextPreservePhoneDraft() = runTest {
        listOf(fixture("pi--omo-v5130-answer-editor.txt"), composer("hell"), composer("another draft"), null).forEach { observation ->
            var writes = 0
            val result = PiReplyGuard.send("hello", PiComposer.locate(composer())!!, "unconfirmed",
                reply = { _, _, _, _ -> writes++; success }, read = { observation }, sleep = {}) as ApiResult.Success
            assertEquals(1, writes); assertFalse(result.value.ok); assertTrue(result.value.textDelivered)
        }
        var writes = 0
        val result = PiReplyGuard.send("hello", PiComposer.locate(composer())!!, "unconfirmed",
            reply = { _, _, _, _ -> writes++; success }, read = { composer("hello") }, sleep = {}) as ApiResult.Success
        assertEquals(2, writes); assertFalse(result.value.ok); assertEquals("pi_unconfirmed", result.value.code)
    }

    @Test fun rangeChangesPreventEnterAndAmbiguousSubmissionIsNeverReplayed() = runTest {
        val panelIdle = PiComposer.locate(fixture("pi--omo-v5130-panel-phantom.txt"))!!
        var writes = 0
        PiReplyGuard.send("hello", panelIdle, "unconfirmed", reply = { _, _, _, columns ->
            writes++; assertEquals(PromptColumns(0, 91), columns); success
        }, read = { composer("hello") }, sleep = {})
        assertEquals(1, writes)
        val failure = ApiResult.Failure(ApiFailure.Timeout)
        writes = 0
        val result = PiReplyGuard.send("hello", PiComposer.locate(composer())!!, "unconfirmed",
            reply = { _, submit, _, _ -> writes++; if (submit) failure else success }, read = { composer("hello") }, sleep = {})
        assertEquals(failure, result); assertEquals(2, writes)
    }
}
