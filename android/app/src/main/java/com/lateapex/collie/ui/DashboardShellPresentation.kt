package com.lateapex.collie.ui

import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.UpdateInfo

internal enum class DashboardConnectionTone { SILENT, AMBER, RED, GREEN, AUTH }
internal enum class DashboardConnectionCause { COLLIE_UNREACHABLE, HERDR_DOWN }

internal data class DashboardConnectionInput(
    val configured: Boolean,
    val snapshot: SnapshotResponse?,
    val snapshotFailed: Boolean,
    val authError: Boolean,
    val requestStartedAt: Long?,
)

internal data class DashboardConnectionView(
    val tone: DashboardConnectionTone,
    val cause: DashboardConnectionCause = DashboardConnectionCause.COLLIE_UNREACHABLE,
    val nextUpdateAt: Long? = null,
)

/** Shared wall-clock escalation and recovery state, matching the web shell's 4s/15s/1.8s beats. */
internal class DashboardConnectionHealth(now: Long) {
    private var lastLiveAt = now
    private var lastWakeAt = now
    private var lostLatched = false
    private var visibleTrouble = false
    private var greenUntil: Long? = null

    fun markWake(now: Long) {
        lastWakeAt = now
    }

    fun markLive(now: Long) {
        lastLiveAt = now
        lostLatched = false
        greenUntil = if (visibleTrouble) now + GREEN_MS else null
        visibleTrouble = false
    }

    fun view(input: DashboardConnectionInput, now: Long): DashboardConnectionView {
        if (!input.configured) return DashboardConnectionView(DashboardConnectionTone.SILENT)
        if (input.authError) return DashboardConnectionView(DashboardConnectionTone.AUTH)
        val stalled = input.requestStartedAt?.let { now - it >= STALL_MS } == true
        val connecting = input.snapshotFailed || input.snapshot == null ||
            input.snapshot.bridge == "disconnected" || stalled
        if (!connecting) {
            val until = greenUntil
            return if (until != null && now < until) {
                DashboardConnectionView(DashboardConnectionTone.GREEN, nextUpdateAt = until)
            } else {
                greenUntil = null
                DashboardConnectionView(
                    DashboardConnectionTone.SILENT,
                    nextUpdateAt = input.requestStartedAt?.plus(STALL_MS)?.takeIf { it > now },
                )
            }
        }
        greenUntil = null
        val anchor = if (lostLatched) lastLiveAt else maxOf(lastLiveAt, lastWakeAt)
        val elapsed = (now - anchor).coerceAtLeast(0)
        val cause = if (input.snapshot?.bridge == "disconnected") {
            DashboardConnectionCause.HERDR_DOWN
        } else {
            DashboardConnectionCause.COLLIE_UNREACHABLE
        }
        return when {
            elapsed >= LOST_MS -> {
                lostLatched = true
                visibleTrouble = true
                DashboardConnectionView(DashboardConnectionTone.RED, cause)
            }
            elapsed >= TROUBLE_MS -> {
                visibleTrouble = true
                DashboardConnectionView(DashboardConnectionTone.AMBER, cause, anchor + LOST_MS)
            }
            else -> DashboardConnectionView(DashboardConnectionTone.SILENT, cause, anchor + TROUBLE_MS)
        }
    }

    companion object {
        const val STALL_MS = 2_500L
        const val TROUBLE_MS = 4_000L
        const val LOST_MS = 15_000L
        const val GREEN_MS = 1_800L
    }
}
