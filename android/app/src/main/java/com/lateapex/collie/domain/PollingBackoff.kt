package com.lateapex.collie.domain

/** Bounded exponential delay for foreground reads after repeated failures. */
internal class PollingBackoff(
    private val maxFailures: Int = 4,
    private val maxDelayMs: Long = 60_000L,
) {
    private var failures = 0

    init {
        require(maxFailures in 0..4)
        require(maxDelayMs > 0)
    }

    fun failed() {
        failures = (failures + 1).coerceAtMost(maxFailures)
    }

    fun succeeded() {
        failures = 0
    }

    fun delayAfter(baseDelayMs: Long): Long {
        val base = baseDelayMs.coerceAtLeast(1L)
        val multiplier = 1L shl failures.coerceIn(0, 4)
        if (base >= maxDelayMs || base > maxDelayMs / multiplier) return maxDelayMs
        return base * multiplier
    }
}
