package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiResult
import kotlinx.coroutines.delay

/** Type once, observe the complete Codex draft, submit once, then observe consumption. */
internal object CodexReplyGuard {
    private const val ATTEMPTS = 10
    private const val POLL_MS = 150L
    private val pasteToken = Regex("^\\[Pasted Content ([1-9]\\d*) chars](?: #(?:[2-9]|[1-9]\\d+))?$")

    suspend fun send(
        text: String,
        expectedPrompt: String,
        unconfirmed: String,
        reply: suspend (String, Boolean, String) -> ApiResult<ActionResponse>,
        read: suspend () -> String?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): ApiResult<ActionResponse> {
        val typed = reply(text, false, expectedPrompt)
        if (typed !is ApiResult.Success || !typed.value.ok) return typed

        var draftBinding: String? = null
        for (attempt in 0 until ATTEMPTS) {
            // Waiting also lets Codex flush paste bursts before receiving the submit key.
            sleep(POLL_MS)
            val fresh = read() ?: continue
            val composer = CodexComposer.locate(fresh) ?: continue
            if (!carries(text, composer.draft())) continue
            val region = composer.region()
            if (region.length in 1..8_192 && PromptBinding.verifyNearTail(fresh, region)) {
                draftBinding = region
                break
            }
        }
        if (draftBinding == null) return uncertain(unconfirmed)

        val submitted = reply("", true, draftBinding)
        if (submitted !is ApiResult.Success || !submitted.value.ok) return submitted
        for (attempt in 0 until ATTEMPTS) {
            sleep(POLL_MS)
            val fresh = read() ?: continue
            if (PromptBinding.composerRegion("codex", fresh) != null) return submitted
        }
        // An RPC acknowledgement proves delivery of a key, not that Codex consumed the message.
        // Preserve the phone draft and never repeat text or Enter after an ambiguous result.
        return uncertain(unconfirmed)
    }

    internal fun carries(sent: String, draft: String?): Boolean {
        if (draft == null) return false
        val count = pasteToken.matchEntire(draft.trim())?.groupValues?.get(1)?.toIntOrNull()
        if (count != null) return count > 1_000 && count == sent.codePointCount(0, sent.length)
        // A soft wrap introduces one ASCII space even inside a word or CJK text. Match the whole
        // message, allowing only that seam to have unknown width; other rendered gaps stay exact.
        val pattern = Regex("\\s+|\\S+").findAll(draft.trim()).joinToString("") { token ->
            if (token.value == " ") "\\s*" else Regex.escape(token.value)
        }
        return pattern.isNotEmpty() && Regex(pattern).matches(sent.trim())
    }

    private fun uncertain(message: String): ApiResult<ActionResponse> = ApiResult.Success(
        ActionResponse(ok = false, error = message, code = "codex_unconfirmed", textDelivered = true),
        200,
    )
}
