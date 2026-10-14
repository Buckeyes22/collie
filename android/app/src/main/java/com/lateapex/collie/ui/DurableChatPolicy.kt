package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.DurableAsk
import com.lateapex.collie.network.DurableOutcome

/** The name a screenless control carries from the live pane state it actually displayed. */
internal data class DurableTarget(val turnId: String? = null, val askId: String? = null) {
    init { require((turnId != null) xor (askId != null)) }
}

internal enum class DurableReading { COMPLETE, STALE, REFUSED, UNKNOWN, MAYBE_APPLIED, TOO_LATE }

internal object DurableChatPolicy {
    fun target(turnId: String?, asks: List<DurableAsk>?): DurableTarget? =
        turnId?.takeIf(String::isNotBlank)?.let { DurableTarget(turnId = it) }
            ?: asks?.firstOrNull { it.id.isNotBlank() }?.let { DurableTarget(askId = it.id) }

    fun canSteer(status: AgentStatus): Boolean = status == AgentStatus.WORKING

    fun canStop(status: AgentStatus, target: DurableTarget?): Boolean =
        target != null && status in setOf(AgentStatus.WORKING, AgentStatus.BLOCKED)

    fun answerable(ask: DurableAsk): List<com.lateapex.collie.network.DurableAskOption> =
        ask.options.filter(ask::answerable)

    fun reading(outcome: DurableOutcome): DurableReading = when (outcome.outcome) {
        "taken", "ok" -> DurableReading.COMPLETE
        "stale" -> DurableReading.STALE
        "refused" -> DurableReading.REFUSED
        "unknown" -> DurableReading.UNKNOWN
        "maybe-applied" -> DurableReading.MAYBE_APPLIED
        "too-late" -> DurableReading.TOO_LATE
        else -> DurableReading.REFUSED
    }

    /** An uncertain intent can be retried only with its original visible target and exact text. */
    fun retryStillNamesIntent(
        retryTarget: DurableTarget,
        currentTarget: DurableTarget?,
        retryText: String,
        currentText: String,
        retrySteer: Boolean,
        currentSteer: Boolean,
    ): Boolean = retryTarget == currentTarget && retryText == currentText && retrySteer == currentSteer
}
