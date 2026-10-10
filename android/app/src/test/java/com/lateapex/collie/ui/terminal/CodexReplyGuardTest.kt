package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReplyGuardTest {
    private val status = "\n\n  model · /repo · Context 50% left"
    private fun composer(draft: String = "") = "› $draft$status"
    private val success = ApiResult.Success(ActionResponse(ok = true), 200)

    @Test
    fun waitsForCompleteTextThenSubmitsOnceAndConfirmsAnEmptyComposer() = runTest {
        val observations = ArrayDeque(listOf(composer("hel"), composer("hello"), composer("hello"), composer()))
        val writes = mutableListOf<Triple<String, Boolean, String>>()
        val result = CodexReplyGuard.send("hello", "›", "unconfirmed", reply = { text, submit, binding ->
            writes += Triple(text, submit, binding)
            success
        }, read = { observations.removeFirst() }, sleep = {})
        assertTrue((result as ApiResult.Success).value.ok)
        assertEquals(listOf(Triple("hello", false, "›"), Triple("", true, "› hello")), writes)
    }

    @Test
    fun acknowledgedEnterThatLeavesTextInTheTerminalPreservesThePhoneDraft() = runTest {
        val writes = mutableListOf<Pair<String, Boolean>>()
        val result = CodexReplyGuard.send("hello", "›", "unconfirmed", reply = { text, submit, _ ->
            writes += text to submit
            success
        }, read = { composer("hello") }, sleep = {}) as ApiResult.Success
        assertFalse(result.value.ok)
        assertTrue(result.value.textDelivered)
        assertEquals("codex_unconfirmed", result.value.code)
        assertEquals(listOf("hello" to false, "" to true), writes)
    }

    @Test
    fun modalOrDifferentDraftAfterTypingNeverReceivesEnter() = runTest {
        listOf("Press enter to confirm or esc to cancel", composer("someone else's draft"), null).forEach { screen ->
            var writes = 0
            val result = CodexReplyGuard.send("hello", "›", "unconfirmed", reply = { _, _, _ ->
                writes++
                success
            }, read = { screen }, sleep = {}) as ApiResult.Success
            assertFalse(result.value.ok)
            assertEquals(1, writes)
        }
    }

    @Test
    fun uncertainSubmissionIsNeverRetried() = runTest {
        var writes = 0
        val failure = ApiResult.Failure(ApiFailure.Timeout)
        val result = CodexReplyGuard.send("hello", "›", "unconfirmed", reply = { _, submit, _ ->
            writes++
            if (submit) failure else success
        }, read = { composer("hello") }, sleep = {})
        assertEquals(failure, result)
        assertEquals(2, writes)
    }

    @Test
    fun verifiesUnicodePasteCountsAndRejectsPartialOrUnrelatedDrafts() {
        val pasted = "🐕".repeat(1_001)
        assertTrue(CodexReplyGuard.carries(pasted, "[Pasted Content 1001 chars]"))
        assertFalse(CodexReplyGuard.carries(pasted, "[Pasted Content 2002 chars]"))
        assertFalse(CodexReplyGuard.carries("hello", "hell"))
        assertFalse(CodexReplyGuard.carries("hello", "previous hello"))
        assertTrue(CodexReplyGuard.carries("first\nsecond", "first second"))
        assertTrue(CodexReplyGuard.carries("日本語のメッセージ", "日本語の メッセージ"))
        assertTrue(CodexReplyGuard.carries("averylongword", "avery longword"))
        assertFalse(CodexReplyGuard.carries("delete file", "delete　file"))
    }
}
