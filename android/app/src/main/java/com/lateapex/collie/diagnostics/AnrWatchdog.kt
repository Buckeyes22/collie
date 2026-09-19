package com.lateapex.collie.diagnostics

import android.os.Handler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects a frozen main thread: posts a ping to [mainHandler] every [pingIntervalMs] and expects
 * it acknowledged within [timeoutMs]. A miss calls [onBlocked] from the watchdog's own background
 * thread — the main thread is, by definition, not available to do that itself.
 */
class AnrWatchdog(
    private val mainHandler: Handler,
    private val onBlocked: (blockedForMs: Long) -> Unit,
    private val pingIntervalMs: Long = 2_000L,
    private val timeoutMs: Long = 5_000L,
) {
    private val lastAckAt = AtomicLong(System.currentTimeMillis())
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            while (running.get()) {
                mainHandler.post { lastAckAt.set(System.currentTimeMillis()) }
                // stop() interrupts the sleep; an escaping InterruptedException would reach the
                // default handler, which kills the process.
                try {
                    Thread.sleep(pingIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                val ack = lastAckAt.get()
                if (isBlocked(ack, now, timeoutMs)) onBlocked(now - ack)
            }
        }.apply {
            isDaemon = true
            name = "collie-anr-watchdog"
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    companion object {
        internal fun isBlocked(lastAckAt: Long, now: Long, timeoutMs: Long): Boolean =
            now - lastAckAt > timeoutMs
    }
}
