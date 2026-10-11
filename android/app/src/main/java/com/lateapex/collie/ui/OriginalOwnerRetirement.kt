package com.lateapex.collie.ui

import android.os.Handler
import android.os.Looper
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.ScopedReadContext
import java.util.concurrent.CountDownLatch

/** UI copies and their owner are changed on Main. The original event is checked at execution,
 * after any queued successor refresh; no store/admission/private-custody lock crosses this ACK. */
internal fun withRetirementOnMain(
    event: AuthorityRetirement,
    copiedActor: () -> ScopedReadContext?,
    retire: () -> Unit,
) {
    withMainCustodyAck { if (event.affects(copiedActor())) retire() }
}

/** Serialize a bounded selective custody mutation on Main, without locks across its ACK. */
internal fun withMainCustodyAck(apply: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) { apply(); return }
    val complete = CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    check(Handler(Looper.getMainLooper()).post {
        try { apply() } catch (error: Throwable) { failure.set(error) } finally { complete.countDown() }
    }) { "Main-thread original-owner retirement post rejected" }
    var interrupted = false
    try {
        while (true) try { complete.await(); break } catch (_: InterruptedException) { interrupted = true }
    } finally { if (interrupted) Thread.currentThread().interrupt() }
    failure.get()?.let { throw IllegalStateException("Main-thread original-owner retirement failed", it) }
}
