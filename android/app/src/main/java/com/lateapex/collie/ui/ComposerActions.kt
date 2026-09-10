package com.lateapex.collie.ui

import com.lateapex.collie.network.OperatorCommand
import com.lateapex.collie.network.OperatorKeyRow
import com.lateapex.collie.network.OperatorQuickReplyRow

data class QuickReplyGroup(val title: String, val items: List<String>)

data class AgentCommand(
    val command: String,
    val description: String,
    val takesArgument: Boolean = false,
    val argumentHint: String = "",
    val common: Boolean = true,
    val dangerous: Boolean = false,
)

data class ComposerKeyPreset(
    val label: String,
    val keys: List<String>,
    val dangerous: Boolean = false,
)

/** Native mirrors of the web client's shipped, first-screen composer actions. */
object ComposerActions {
    private val shippedKeyPresets = listOf(
        ComposerKeyPreset("Ctrl C", listOf("ctrl+c")),
        ComposerKeyPreset("Ctrl D", listOf("ctrl+d"), dangerous = true),
        ComposerKeyPreset("Ctrl U", listOf("ctrl+u")),
        ComposerKeyPreset("Ctrl R", listOf("ctrl+r")),
        ComposerKeyPreset("Ctrl L", listOf("ctrl+l")),
        ComposerKeyPreset("Ctrl Z", listOf("ctrl+z"), dangerous = true),
    )
    private val agentQuickReplies = listOf(
        QuickReplyGroup("CONFIRM", listOf("yes", "no")),
        QuickReplyGroup("COMMON", listOf("continue", "commit and push", "retry", "skip")),
    )
    private val shellQuickReplies = listOf(QuickReplyGroup("CONFIRM", listOf("y", "n")))

    fun quickReplies(
        agent: String?,
        isShell: Boolean,
        operatorRows: List<OperatorQuickReplyRow>,
    ): List<QuickReplyGroup> {
        val aimed = rowsFor(operatorRows, if (isShell) "shell" else agent, { it.agent }, { it.title })
        if (aimed.isNotEmpty()) return aimed.map { QuickReplyGroup(it.title, it.items) }
        return if (isShell) shellQuickReplies else agentQuickReplies
    }

    fun commands(agent: String?, operatorRows: List<OperatorCommand>): List<AgentCommand> {
        val aimed = rowsFor(operatorRows, agent, { it.agent }, { it.command })
        val shipped = ShippedAgentCommands.forAgent(canonical(agent))
        if (aimed.isEmpty()) return shipped
        val dangerous = shipped.filter(AgentCommand::dangerous).map(AgentCommand::command).toSet()
        return aimed.map {
            AgentCommand(
                command = it.command,
                description = it.description,
                takesArgument = it.takesArg,
                argumentHint = it.argHint,
                common = it.common,
                dangerous = it.confirm || it.command in dangerous,
            )
        }
    }

    /** Operator keys replace the shipped preset catalog for the panes their scope reaches. */
    fun keyPresets(agent: String?, operatorRows: List<OperatorKeyRow>): List<ComposerKeyPreset> {
        val aimed = rowsFor(operatorRows, agent, { it.agent }, { it.label })
        if (aimed.isEmpty()) return shippedKeyPresets
        return aimed.map { ComposerKeyPreset(it.label, it.keys, it.danger) }
    }

    fun appendArgumentCommand(draft: String, command: String): String =
        "$draft${if (draft.isEmpty()) "" else " "}$command "

    private fun canonical(agent: String?): String = when {
        agent == null -> ""
        agent.trim().lowercase().isAgentFamily("claude") -> "claude"
        agent.trim().lowercase().isAgentFamily("codex") -> "codex"
        agent.trim().lowercase().isAgentFamily("opencode") -> "opencode"
        agent.trim().lowercase().isAgentFamily("omp") -> "omp"
        agent.trim().lowercase().isAgentFamily("pi") -> "pi"
        agent.trim().lowercase().isAgentFamily("grok") -> "grok"
        agent.trim().lowercase().isAgentFamily("antigravity") -> "agy"
        agent.trim().lowercase().isAgentFamily("agy") -> "agy"
        else -> agent.trim().lowercase()
    }

    private fun scopeFamily(agent: String): String = when {
        agent.isAgentFamily("antigravity") -> "antigravity"
        else -> canonical(agent)
    }

    private fun <T> rowsFor(
        rows: List<T>,
        agent: String?,
        scopeOf: (T) -> String?,
        keyOf: (T) -> String,
    ): List<T> {
        val paneKey = agent?.trim()?.lowercase().orEmpty()
        val paneFamily = scopeFamily(paneKey)
        val families = setOf("claude", "codex", "pi", "opencode", "omp", "grok", "agy", "antigravity")
        val aimed = linkedMapOf<String, Pair<Int, T>>()
        rows.forEach { row ->
            val scope = scopeOf(row)?.trim()?.lowercase()
            val specificity = when {
                scope == null -> 1
                paneKey.isEmpty() -> 0
                scope == paneKey -> 3
                scope in families && scope == paneFamily -> 2
                else -> 0
            }
            if (specificity == 0) return@forEach
            val key = keyOf(row)
            val previous = aimed[key]
            if (previous == null || specificity >= previous.first) aimed[key] = specificity to row
        }
        return aimed.values.map(Pair<Int, T>::second)
    }
}
