package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneSummary
import java.util.Locale

private val openCodeTitlePrefix = Regex("^\\s*OC\\s*\\|\\s*", RegexOption.IGNORE_CASE)
private val linuxHomePrefix = Regex("^/(?:var/)?home/[^/]+")
private val macHomePrefix = Regex("^/Users/[^/]+")

/** Removes agent-owned title chrome while preserving user and bridge data unchanged. */
internal fun displayAgentTitle(agent: String, title: String?): String? {
    if (title.isNullOrBlank()) return null
    val key = agent.lowercase(Locale.ROOT).trim()
    if (!key.isAgentFamily("opencode")) return title
    return openCodeTitlePrefix.replaceFirst(title, "").trimStart().takeIf(String::isNotBlank)
}

/** The origin web UI's canonical pane name in a context where space and tab are already known. */
internal fun paneDisplayName(pane: PaneSummary): String {
    val raw = pane.paneLabel?.takeIf(String::isNotBlank)
        ?: pane.sessionName?.takeIf(String::isNotBlank)
        ?: pane.terminalTitle?.takeIf { it.isNotBlank() && pane.terminalTitleStale != true }
    return displayAgentTitle(pane.agent, raw)
        ?: if (pane.kind == "shell") "shell" else pane.agent
}

/** Collapse a conventional home prefix and retain whole trailing path segments within [max]. */
internal fun shortCwd(cwd: String, max: Int = 32): String {
    val path = cwd.replaceFirst(linuxHomePrefix, "~").replaceFirst(macHomePrefix, "~")
    if (path.length <= max) return path
    val segments = path.split('/').filter(String::isNotEmpty).toMutableList()
    val last = segments.removeLastOrNull() ?: return path
    val kept = ArrayDeque<String>().apply { addFirst(last) }
    var length = last.length + 1
    for (segment in segments.asReversed()) {
        if (length + segment.length + 1 > max) break
        kept.addFirst(segment)
        length += segment.length + 1
    }
    return "…/${kept.joinToString("/")}"
}

internal data class DashboardPaneText(
    val primary: String,
    val detailLead: String?,
    val detailTail: String?,
)

internal data class SpacePaneText(val primary: String, val secondary: String?)

/** Mirrors paneParts plus AgentCard's mobile promotion of the secondary text. */
internal fun dashboardPaneText(pane: PaneSummary): DashboardPaneText {
    val project = pane.workspaceLabel.ifBlank { pane.workspaceId }
    val stale = pane.terminalTitle != null && pane.terminalTitleStale == true
    val rawOwn = pane.paneLabel?.takeIf(String::isNotBlank)
        ?: pane.sessionName?.takeIf(String::isNotBlank)
        ?: pane.terminalTitle?.takeIf { it.isNotBlank() && !stale }
    val own = displayAgentTitle(pane.agent, rawOwn)
    val cwd = pane.cwd.takeIf(String::isNotBlank)?.let { path ->
        path.split('/').lastOrNull(String::isNotBlank)
            ?.takeUnless { it.equals(project.trim(), ignoreCase = true) }
            ?.let { shortCwd(path) }
    }
    val secondary = own ?: cwd ?: pane.terminalTitle?.takeIf { stale && it.isNotBlank() }
    val tab = pane.tabLabel
    return when {
        secondary != null -> DashboardPaneText(secondary, project, tab)
        tab != null -> DashboardPaneText(tab, project, null)
        else -> DashboardPaneText(project, null, null)
    }
}

internal fun spacePaneText(pane: PaneSummary): SpacePaneText = SpacePaneText(
    primary = paneDisplayName(pane),
    secondary = pane.cwd.takeIf(String::isNotBlank)?.let(::shortCwd),
)
