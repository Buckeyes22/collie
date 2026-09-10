package com.lateapex.collie.ui

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary

/** Every condition that locks the web composer's terminal-write surface. */
internal sealed interface PaneWriteBlock {
    data object ReadOnly : PaneWriteBlock
    data object Gone : PaneWriteBlock
    data class Host(val refusal: HostWriteRefusal) : PaneWriteBlock
    data class MissingCapability(val capability: String, val note: String?) : PaneWriteBlock
}

/** A single fail-closed projection shared by PaneActivity affordances and mutation entry points. */
internal object PaneWriteGate {
    private val requiredCapabilities = listOf("typeText", "sendKeys")

    fun block(address: PaneAddress, state: PaneUiState): PaneWriteBlock? = block(
        address = address,
        canWrite = state.canWrite,
        topologyKnown = state.topologyKnown,
        panes = state.panes,
        mux = state.mux,
        servers = state.servers,
    )

    fun block(
        address: PaneAddress,
        canWrite: Boolean,
        topologyKnown: Boolean,
        panes: List<PaneSummary>,
        mux: MuxConfigResponse?,
        servers: List<ServerSummary>?,
    ): PaneWriteBlock? {
        if (!canWrite) return PaneWriteBlock.ReadOnly
        if (topologyKnown && panes.none { it.matches(address) }) return PaneWriteBlock.Gone
        PaneSwitcherModel.hostWriteRefusal(address.scope.host, servers)?.let {
            return PaneWriteBlock.Host(it)
        }
        requiredCapabilities.firstOrNull { mux?.supports(it) == false }?.let { capability ->
            return PaneWriteBlock.MissingCapability(capability, mux?.notes?.get(capability))
        }
        return null
    }

    private fun PaneSummary.matches(address: PaneAddress): Boolean =
        paneId == address.paneId && host == address.scope.host && session == address.scope.session
}
