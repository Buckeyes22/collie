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
    val secondary: String?,
)

internal data class SpacePaneText(val primary: String, val secondary: String?)

/**
 * A.1: the operator's name leads when there is one; otherwise the row leads with where the
 * pane lives (`space › tab`) and the agent's auto-title is demoted to the second line.
 * The same helper serves the dashboard rows and the pane switcher.
 */
internal fun dashboardPaneText(pane: PaneSummary): DashboardPaneText {
    val project = pane.workspaceLabel.ifBlank { pane.workspaceId }
    val tab = pane.tabLabel?.takeIf(String::isNotBlank)
    val stale = pane.terminalTitle != null && pane.terminalTitleStale == true
    val own = pane.sessionName?.takeIf(String::isNotBlank)
        ?: pane.terminalTitle?.takeIf { it.isNotBlank() && !stale }
    val secondary = displayAgentTitle(pane.agent, own)
    val label = displayAgentTitle(pane.agent, pane.paneLabel?.takeIf(String::isNotBlank))
    return if (label != null) {
        DashboardPaneText(label, secondary)
    } else {
        DashboardPaneText(if (tab != null) "$project › $tab" else project, secondary)
    }
}

internal fun spacePaneText(pane: PaneSummary): SpacePaneText {
    val primary = paneDisplayName(pane)
    // The card's metadata line carries where the pane sits (tab) and what it last showed
    // (the final non-blank mirror line the wire reports as terminalTitle), unless that is
    // already the title above it (S25 Ultra, 2026-09-10: "3 · User preference question").
    val shown = pane.terminalTitle?.takeIf { it.isNotBlank() && it != primary && displayAgentTitle(pane.agent, it) != primary }
    return SpacePaneText(
        primary = primary,
        secondary = listOfNotNull(pane.tabLabel?.takeIf(String::isNotBlank), shown).joinToString(" · ").ifEmpty { null },
    )
}
