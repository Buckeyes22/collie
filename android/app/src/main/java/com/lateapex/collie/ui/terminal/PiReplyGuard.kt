package com.lateapex.collie.ui.terminal

import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PromptColumns
import kotlinx.coroutines.delay

/** Pi types once, observes the complete draft, submits once, and positively sees consumption. */
internal object PiReplyGuard {
    suspend fun send(
        text: String,
        displayed: PiComposer.Composer,
        unconfirmed: String,
        reply: suspend (String, Boolean, String, PromptColumns?) -> ApiResult<ActionResponse>,
        read: suspend () -> String?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): ApiResult<ActionResponse> {
        if (displayed.draft() != null) return ApiResult.Success(ActionResponse(ok = false, code = "prompt_changed"), 200)
        val typed = reply(text, false, displayed.region(), displayed.columns)
        if (typed !is ApiResult.Success || !typed.value.ok) return typed
        var draft: PiComposer.Composer? = null
        for (attempt in 0 until 10) {
            sleep(150)
            val fresh = read() ?: continue
            val composer = PiComposer.locate(fresh) ?: continue
            if (composer.columns != displayed.columns || composer.omo != displayed.omo || !carries(text, composer.draft())) continue
            if (!PromptBinding.verifyNearTail(fresh, composer.region(), expectedColumns = composer.columns)) continue
            draft = composer
            break
        }
        val observed = draft ?: return uncertain(unconfirmed)
        val submitted = reply("", true, observed.region(), observed.columns)
        if (submitted !is ApiResult.Success) return submitted
        if (!submitted.value.ok) return submitted.copy(value = submitted.value.copy(textDelivered = true))
        for (attempt in 0 until 10) {
            sleep(150)
            val fresh = read() ?: continue
            val composer = PiComposer.locate(fresh) ?: continue
            if (composer.columns == displayed.columns && composer.omo == displayed.omo && composer.draft() == null) return submitted
        }
        return uncertain(unconfirmed)
    }

    internal fun carries(sent: String, draft: String?): Boolean {
        if (draft == null) return false
        // Match the entire message with only a folded ASCII row seam allowed to vary. Unlike Codex,
        // Pi has no captured paste-token contract; never treat a token/count as the delivered draft.
        val pattern = Regex("\\s+|\\S+").findAll(draft.trim()).joinToString("") { token ->
            if (token.value == " ") "\\s*" else token.value.split('•').joinToString("(?:•|[\\x21-\\x7e])") { Regex.escape(it) }
        }
        return pattern.isNotEmpty() && Regex(pattern).matches(sent.trim())
    }

    private fun uncertain(message: String): ApiResult<ActionResponse> = ApiResult.Success(
        ActionResponse(ok = false, error = message, code = "pi_unconfirmed", textDelivered = true), 200,
    )
}
