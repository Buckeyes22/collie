package com.lateapex.collie.ui

import com.lateapex.collie.network.MuxConfigResponse

/** Display the adapter's declared topology promise separately from the client's snapshot age. */
internal object TopologyStatus {
    fun describe(mux: MuxConfigResponse?, snapshotReadAt: Long?, now: Long): String {
        val topology = when {
            mux?.topologyLatency?.isPush() == true -> "topology updates: push"
            mux?.topologyLatency?.displayBoundMs() != null ->
                "topology census: up to ${mux.topologyLatency.displayBoundMs()} ms"
            else -> "topology timing: not declared"
        }
        val age = snapshotReadAt?.let { (now - it).coerceAtLeast(0) / 1_000 }
        val snapshot = when {
            age == null -> "snapshot: not read"
            age < 60 -> "snapshot read: ${age}s ago"
            else -> "snapshot read: ${age / 60}m ago"
        }
        return "$topology · $snapshot"
    }
}
