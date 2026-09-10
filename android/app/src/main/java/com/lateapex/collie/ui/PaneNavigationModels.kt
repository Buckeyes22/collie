package com.lateapex.collie.ui

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.TranscriptEntry

internal data class PaneTabItem(
    val tab: TabSummary,
    val target: PaneSummary?,
    val active: Boolean,
    val bucket: TriageBucket,
)

internal data class PaneStripItem(
    val pane: PaneSummary,
    val active: Boolean,
)

internal data class PaneTabStrip(
    val tabs: List<PaneTabItem>,
    val panes: List<PaneStripItem>,
    val visible: Boolean,
    val canCreateTab: Boolean,
    val writeRefusal: HostWriteRefusal?,
)

/** Pure navigation projection shared by the pane tab and pane rows. */
internal object PaneTabStripModel {
    fun build(
        current: PaneAddress,
        allPanes: List<PaneSummary>,
        allTabs: List<TabSummary>,
        visiblePreference: Boolean,
        canWrite: Boolean,
        mux: MuxConfigResponse?,
        servers: List<ServerSummary>?,
    ): PaneTabStrip {
        val currentPane = allPanes.firstOrNull { it.matches(current) }
        val workspaceId = currentPane?.workspaceId
        val scopedPanes = allPanes.filter { pane ->
            pane.workspaceId == workspaceId && pane.sameScope(current)
        }
        val tabs = allTabs
            .filter { tab -> tab.workspaceId == workspaceId && tab.sameHost(current.scope.host) }
            .sortedWith(compareBy<TabSummary> { it.number }.thenBy { it.label }.thenBy { it.tabId })
            .map { tab ->
                val panes = scopedPanes.filter { it.tabId == tab.tabId }
                PaneTabItem(
                    tab = tab,
                    target = panes.firstOrNull { it.focused } ?: panes.firstOrNull(),
                    active = tab.tabId == currentPane?.tabId,
                    bucket = panes.map(DashboardModel::bucketOf).minByOrNull { it.ordinal }
                        ?: TriageBucket.RECENT,
                )
            }
        val currentTabPanes = scopedPanes.filter { it.tabId == currentPane?.tabId }
        val refusal = PaneSwitcherModel.hostWriteRefusal(current.scope.host, servers)
        return PaneTabStrip(
            tabs = tabs,
            panes = currentTabPanes.sortedBy(PaneSummary::paneId).map { PaneStripItem(it, it.matches(current)) },
            visible = visiblePreference && (tabs.isNotEmpty() || currentTabPanes.size > 1),
            canCreateTab = canWrite && mux?.supports("createTab") != false && refusal == null && workspaceId != null,
            writeRefusal = refusal,
        )
    }

    private fun PaneSummary.matches(address: PaneAddress): Boolean =
        paneId == address.paneId && sameScope(address)

    private fun PaneSummary.sameScope(address: PaneAddress): Boolean =
        host == address.scope.host && session == address.scope.session

    private fun TabSummary.sameHost(currentHost: String?): Boolean =
        host == currentHost
}

/** Browser-style destination selection after the current tab closes. */
internal object TabCloseNavigation {
    fun fallback(tabs: List<PaneTabItem>, closingTabId: String): PaneSummary? {
        val closing = tabs.indexOfFirst { it.tab.tabId == closingTabId }
        if (closing < 0) return null
        val candidates = tabs.drop(closing + 1) + tabs.take(closing).asReversed()
        return candidates.firstNotNullOfOrNull { it.target }
    }
}

internal data class StructuralActionGate(
    val visible: Boolean,
    val enabled: Boolean,
    val refusal: HostWriteRefusal?,
)

/** Capability and reachability gate applied before any structural pane write is offered. */
internal object PaneStructuralGate {
    fun action(
        capability: String,
        current: PaneAddress,
        canWrite: Boolean,
        mux: MuxConfigResponse?,
        servers: List<ServerSummary>?,
        busy: Boolean,
    ): StructuralActionGate {
        if (mux?.supports(capability) == false) return StructuralActionGate(false, false, null)
        val refusal = PaneSwitcherModel.hostWriteRefusal(current.scope.host, servers)
        return StructuralActionGate(
            visible = true,
            enabled = canWrite && refusal == null && !busy,
            refusal = refusal,
        )
    }
}

internal data class HistorySearchMatch(val entryUuid: String, val partIndex: Int)

/** Search and user-turn landmarks over the read-only transcript wire model. */
internal object HistoryNavigation {
    fun matches(entries: List<TranscriptEntry>, query: String, limit: Int = 5_000): List<HistorySearchMatch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return buildList {
            outer@ for (entry in entries) {
                for ((partIndex, part) in entry.parts.withIndex()) {
                    val searchable = listOfNotNull(part.text, part.name, part.summary, part.result?.text)
                        .joinToString("\n")
                    repeat(OutputFind.matches(searchable, query, limit - size).size) {
                        add(HistorySearchMatch(entry.uuid, partIndex))
                    }
                    if (size >= limit) break@outer
                }
            }
        }
    }

    fun userTurns(entries: List<TranscriptEntry>): List<String> =
        entries.filter { it.role == "user" }.map(TranscriptEntry::uuid)

    fun step(size: Int, current: Int, delta: Int): Int = OutputFind.step(size, current, delta)
}
