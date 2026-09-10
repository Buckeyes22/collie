package com.lateapex.collie.ui

import androidx.annotation.ColorRes
import com.lateapex.collie.R

/** The ten identity-only host hues defined by the canonical web palette. */
object HostPalette {
    private val colours = intArrayOf(
        R.color.collie_host_0,
        R.color.collie_host_1,
        R.color.collie_host_2,
        R.color.collie_host_3,
        R.color.collie_host_4,
        R.color.collie_host_5,
        R.color.collie_host_6,
        R.color.collie_host_7,
        R.color.collie_host_8,
        R.color.collie_host_9,
    )

    const val SIZE = 10

    @ColorRes
    fun resource(slot: Int): Int = colours[Math.floorMod(slot, colours.size)]

    /** Stable collision-resolving slot assignment shared by every native host identity surface. */
    fun slot(servers: List<com.lateapex.collie.network.ServerSummary>, id: String): Int? {
        if (servers.none { it.id == id }) return null
        val taken = mutableSetOf<Int>()
        servers.map { it.id }.sorted().forEach { serverId ->
            var slot = (fnv1a(serverId) % SIZE.toUInt()).toInt()
            for (probe in 0 until SIZE) {
                if (slot !in taken) break
                slot = (slot + 1) % SIZE
            }
            if (serverId == id) return slot
            taken += slot
        }
        return null
    }

    private fun fnv1a(value: String): UInt {
        var hash = 0x811c9dc5u
        value.forEach { char -> hash = (hash xor char.code.toUInt()) * 0x01000193u }
        return hash
    }
}
