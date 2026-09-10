package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneSummary
import java.util.Locale

internal data class PaneHeaderPresentation(
    val name: String,
    val discriminator: String?,
    val cwd: String?,
    val agent: String,
    val shell: Boolean,
    val status: String,
)

internal object PaneHeaderPresenter {
    fun present(pane: PaneSummary, tabPaneCount: Int): PaneHeaderPresentation {
        val ownName = pane.paneLabel ?: pane.sessionName
        val fallback = buildString {
            append(pane.workspaceLabel.ifBlank { pane.workspaceId })
            pane.tabLabel?.takeIf(String::isNotBlank)?.let { append(" › ").append(it) }
        }
        val name = ownName?.let { displayAgentTitle(pane.agent, it) ?: it } ?: fallback
        val discriminator = if (ownName == null && tabPaneCount > 1) {
            pane.paneId.substringAfterLast(':')
        } else {
            null
        }
        return PaneHeaderPresentation(
            name = name,
            discriminator = discriminator,
            cwd = cwdBeyondName(pane.cwd, name),
            agent = pane.agent,
            shell = pane.kind == "shell" || pane.agent.equals("shell", ignoreCase = true),
            status = pane.status.name.lowercase(Locale.ROOT),
        )
    }

    internal fun cwdBeyondName(cwd: String, name: String): String? {
        if (cwd.isBlank()) return null
        val short = shortCwd(cwd)
        val shown = name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9._-]+")).filter(String::isNotBlank).toSet()
        val segments = short.split('/').filter { it.isNotBlank() && it != "~" && it != "…" }
        return short.takeUnless { segments.isEmpty() || segments.all { it.lowercase(Locale.ROOT) in shown } }
    }

    internal fun shortCwd(cwd: String, max: Int = 32): String {
        val path = cwd
            .replace(Regex("^/(?:var/)?home/[^/]+"), "~")
            .replace(Regex("^/Users/[^/]+"), "~")
        if (path.length <= max) return path
        val segments = path.split('/').filter(String::isNotBlank).toMutableList()
        val last = segments.removeLastOrNull() ?: return path
        val kept = mutableListOf(last)
        var length = last.length + 2
        for (segment in segments.asReversed()) {
            if (length + segment.length + 1 > max) break
            kept.add(0, segment)
            length += segment.length + 1
        }
        return "…/${kept.joinToString("/")}"
    }
}
