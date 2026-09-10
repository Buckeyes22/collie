package com.lateapex.collie.ui

/** One-shot, exact-value confirmation with a monotonic expiry. */
class TimedConfirmation<T>(
    private val timeoutMs: Long = 3_000L,
    private val now: () -> Long,
) {
    private var pending: T? = null
    private var expiresAt = 0L

    fun confirm(value: T): Boolean {
        val timestamp = now()
        if (pending == value && timestamp < expiresAt) {
            reset()
            return true
        }
        pending = value
        expiresAt = timestamp + timeoutMs
        return false
    }

    fun isPending(value: T): Boolean = pending == value && now() < expiresAt

    fun reset() {
        pending = null
        expiresAt = 0L
    }
}
