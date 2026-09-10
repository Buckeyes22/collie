package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse

/** A pack member's presented freshness, measured only on the lead's snapshot clock. */
internal enum class DashboardHostState {
    LIVE,
    STALE,
    UNKNOWN,
}

/**
 * Host presentation deliberately keeps freshness and writeability independent. An old receipt can
 * still be writable, while a recent receipt can already have a failed reachability probe.
 */
internal data class DashboardHostHealth(
    val state: DashboardHostState,
    val writable: Boolean,
    val incompatible: Boolean,
    val protocolDetail: String?,
    val lastSeenAt: Long,
)

internal object DashboardHostHealthModel {
    const val PRESENTED_STALE_MAX_MS = 15_000L

    /** The nominal cadence used by MainViewModel after a successful dashboard snapshot. */
    fun pollMs(snapshot: SnapshotResponse): Long = when {
        snapshot.agents.any { it.status == AgentStatus.BLOCKED } -> 3_000L
        snapshot.agents.any { it.status == AgentStatus.WORKING } -> 5_000L
        else -> 12_000L
    }

    fun staleThresholdMs(pollMs: Long): Long = minOf(
        3L * pollMs.coerceAtLeast(0L),
        PRESENTED_STALE_MAX_MS,
    )

    /** Down and incompatible members stay discoverable in the machine sheet. */
    fun shouldShowSwitcher(servers: List<ServerSummary>, selectedHost: String?): Boolean =
        servers.size > 1 || selectedHost != null

    fun from(server: ServerSummary, snapshot: SnapshotResponse): DashboardHostHealth =
        from(server, at = snapshot.ts, pollMs = pollMs(snapshot))

    fun from(server: ServerSummary, at: Long, pollMs: Long): DashboardHostHealth {
        val incompatible = server.protocol == "incompatible"
        val base = DashboardHostHealth(
            state = DashboardHostState.LIVE,
            writable = server.reachable,
            incompatible = incompatible,
            protocolDetail = server.protocolDetail,
            lastSeenAt = server.lastSeenAt,
        )
        // Phone-to-lead health owns the lead's state. A snapshot cannot diagnose its own source.
        if (server.isLead) return base.copy(writable = true)

        val neverSeen = server.lastSeenAt <= 0L
        if (incompatible) {
            return base.copy(
                state = if (neverSeen) DashboardHostState.UNKNOWN else DashboardHostState.STALE,
                writable = false,
            )
        }

        val withinTolerance = when {
            neverSeen -> false
            at <= 0L -> server.reachable
            else -> (at - server.lastSeenAt).coerceAtLeast(0L) <= staleThresholdMs(pollMs)
        }
        return base.copy(
            state = when {
                withinTolerance -> DashboardHostState.LIVE
                neverSeen -> DashboardHostState.UNKNOWN
                else -> DashboardHostState.STALE
            },
        )
    }
}
