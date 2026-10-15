package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import java.util.UUID

/** Fixed Collie v1.21 ask/control contract (ADR 0100/0101). IDs are opaque. */
@Serializable
data class DurableAskOption(val id: String, val label: String, val effect: String)

@Serializable
data class DurableAskTool(val name: String, val summary: String, val output: String? = null)

@Serializable
data class DurableAsk(
    val id: String, val kind: String, val toolCallId: String, val revision: Int,
    val title: String, val itemId: String, val options: List<DurableAskOption>,
    val reason: String? = null, val tool: DurableAskTool? = null, val cleaned: Boolean = false,
) {
    fun answerable(option: DurableAskOption): Boolean = kind == "permission" && revision >= 1 &&
        id.isNotBlank() && options.size in 1..6 && options.map { it.id }.distinct().size == options.size &&
        option in options && option.id.isNotBlank() && option.effect in setOf("allow", "allow-always", "deny", "cancel") &&
        !(cleaned && option.effect == "allow-always")
}

@Serializable
data class DurableAnswerRequest(
    val requestId: String, val askId: String, val revision: Int, val optionId: String,
) {
    fun valid(): Boolean = durableRequestId(requestId) && askId.isNotBlank() && revision >= 1 && optionId.isNotBlank()
}

@Serializable
data class DurableControlRequest(
    val requestId: String, val turnId: String? = null, val askId: String? = null,
    val text: String? = null, val steer: Boolean? = null,
) {
    fun valid(send: Boolean): Boolean = durableRequestId(requestId) &&
        listOf(turnId, askId).count { it != null } == 1 &&
        turnId?.isNotBlank() != false && askId?.isNotBlank() != false &&
        if (send) text != null && text.toByteArray(Charsets.UTF_8).size in 1..32768
        else text == null && steer == null
}

@Serializable
data class DurableOutcome(
    val ok: Boolean, val outcome: String, val replayed: Boolean = false, val older: Boolean = false,
    val ask: DurableAsk? = null, val turnId: String? = null, val askIds: List<String> = emptyList(),
    val reason: String? = null, val detail: String? = null,
) {
    fun valid(answer: Boolean, status: Int): Boolean = when (outcome) {
        "taken" -> answer && ok && status == 200
        "ok" -> !answer && ok && status == 200
        "too-late" -> answer && !ok && status == 200
        "maybe-applied" -> !ok && status == 200
        "stale" -> !ok && (status == 409 || status == 404 && older)
        "refused" -> !ok && status == 409
        "unknown" -> !ok && status == 504
        else -> false
    }
}

private fun durableRequestId(value: String): Boolean = try {
    UUID.fromString(value).toString() == value.lowercase(java.util.Locale.ROOT)
} catch (_: IllegalArgumentException) { false }
