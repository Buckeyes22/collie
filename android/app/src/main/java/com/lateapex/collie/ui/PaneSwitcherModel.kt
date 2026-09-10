package com.lateapex.collie.ui

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary

internal data class SwitcherPaneRow(
    val pane: PaneSummary,
    val project: String,
    val tab: String?,
    val secondary: String?,
    val active: Boolean,
)

internal sealed interface PaneSwitcherSection {
    data class Agents(
        val bucket: TriageBucket,
        val rows: List<SwitcherPaneRow>,
        val open: Boolean = true,
    ) : PaneSwitcherSection
    data class Shells(val rows: List<SwitcherPaneRow>, val open: Boolean) : PaneSwitcherSection
    data class Launch(
        val rows: List<Launcher>,
        val home: String,
        val open: Boolean,
        val refusal: HostWriteRefusal?,
        val launching: Set<String>,
    ) : PaneSwitcherSection
}

internal object PaneSwitcherModel {
    const val COLLAPSE_THRESHOLD = 8

    fun sections(
        panes: List<PaneSummary>,
        current: PaneAddress,
        recentOpen: Boolean,
        recentNewest: Boolean,
        shellsPreference: Boolean?,
        launchers: List<Launcher>,
        launcherHome: String,
        launchPreference: Boolean?,
        writesAuthorized: Boolean,
        mux: MuxConfigResponse?,
        servers: List<ServerSummary>?,
        launching: Set<String>,
    ): List<PaneSwitcherSection> {
        val agents = panes.filterNot(::isShell)
        val shells = panes.filter(::isShell)
        val home = launcherHome.trimEnd('/')
        val hostRefusal = hostWriteRefusal(current.scope.host, servers)
        return buildList {
            val byBucket = agents.groupBy(DashboardModel::bucketOf)
            TriageBucket.entries.forEach { bucket ->
                val source = byBucket[bucket].orEmpty()
                val rows = (if (bucket == TriageBucket.RECENT && !recentNewest) {
                    source.sortedBy { it.lastSeenAt ?: 0 }
                } else {
                    source.sortedByDescending {
                        if (bucket == TriageBucket.RECENT) it.lastSeenAt ?: 0 else it.lastActiveAt ?: 0
                    }
                })
                    .map { row(it, current, home) }
                if (rows.isNotEmpty()) {
                    add(PaneSwitcherSection.Agents(bucket, rows, bucket != TriageBucket.RECENT || recentOpen))
                }
            }
            if (shells.isNotEmpty()) {
                add(PaneSwitcherSection.Shells(shells.map { row(it, current, home) }, openForCount(shellsPreference, shells.size)))
            }
            if (launchers.isNotEmpty() && writesAuthorized && mux?.supports("createTab") != false) {
                add(
                    PaneSwitcherSection.Launch(
                        rows = launchers,
                        home = home,
                        open = openForCount(launchPreference, launchers.size),
                        refusal = hostRefusal,
                        launching = launching,
                    ),
                )
            }
        }
    }

    fun hostWriteRefusal(host: String?, servers: List<ServerSummary>?): HostWriteRefusal? {
        if (host == null) return null
        val server = servers?.firstOrNull { it.id == host } ?: return null
        if (server.protocol == "incompatible") {
            return HostWriteRefusal.Incompatible(
                server.name,
                server.protocolDetail?.takeIf(String::isNotBlank),
            )
        }
        return if (!server.reachable) HostWriteRefusal.Unreachable(server.name) else null
    }

    fun openForCount(preference: Boolean?, count: Int): Boolean =
        preference ?: (count <= COLLAPSE_THRESHOLD)

    fun shortenHome(path: String, home: String): String = DisplayText.abbreviateHome(path, home)

    private fun row(pane: PaneSummary, current: PaneAddress, home: String): SwitcherPaneRow {
        val project = pane.workspaceLabel.ifBlank { pane.workspaceId }
        val staleTitle = pane.terminalTitleStale == true
        val own = listOf(
            pane.paneLabel,
            pane.sessionName,
            pane.terminalTitle.takeUnless { staleTitle },
        ).firstNotNullOfOrNull { it?.takeIf(String::isNotBlank) }
        val shortCwd = pane.cwd.takeIf(String::isNotBlank)?.let { shortenHome(it, home) }
        val informativeCwd = shortCwd?.takeUnless {
            pane.cwd.trimEnd('/').substringAfterLast('/').equals(project.trim(), ignoreCase = true)
        }
        val secondary = displayAgentTitle(pane.agent, own)
            ?: informativeCwd
            ?: displayAgentTitle(pane.agent, pane.terminalTitle.takeIf { staleTitle })
        return SwitcherPaneRow(
            pane = pane,
            project = project,
            tab = pane.tabLabel?.takeIf(String::isNotBlank),
            secondary = secondary,
            active = pane.paneId == current.paneId && pane.host == current.scope.host && pane.session == current.scope.session,
        )
    }

    private fun isShell(pane: PaneSummary): Boolean =
        pane.kind.equals("shell", ignoreCase = true) || pane.agent.equals("shell", ignoreCase = true)
}

internal sealed interface HostWriteRefusal {
    data class Incompatible(val serverName: String, val detail: String?) : HostWriteRefusal
    data class Unreachable(val serverName: String) : HostWriteRefusal
}

/** Per-command in-flight ownership; duplicate taps never start a second write. */
internal class LaunchDuplicateLock {
    private val commands = mutableSetOf<String>()

    fun tryStart(command: String): Boolean = command.isNotBlank() && commands.add(command)
    fun finish(command: String) {
        commands.remove(command)
    }
    fun snapshot(): Set<String> = commands.toSet()
}
