package com.lateapex.collie.ui

import androidx.annotation.DrawableRes
import com.lateapex.collie.R
import java.util.Locale

/** Matches Herdr's agent names and the web client's tolerant family aliases. */
@DrawableRes
internal fun agentIcon(agent: String): Int {
    val key = agent.lowercase(Locale.ROOT).trim()
    return when {
        key.isAgentFamily("claude") -> R.drawable.ic_agent_claude
        key.isAgentFamily("codex") -> R.drawable.ic_agent_codex
        key.isAgentFamily("opencode") -> R.drawable.ic_agent_opencode
        key.isAgentFamily("pi") -> R.drawable.ic_agent_pi
        key.isAgentFamily("omp") -> R.drawable.ic_agent_omp
        key.isAgentFamily("agy") || key.isAgentFamily("antigravity") ->
            R.drawable.ic_agent_antigravity
        key.isAgentFamily("grok") -> R.drawable.ic_agent_grok
        key.isAgentFamily("kimi") -> R.drawable.ic_agent_kimi
        key.isAgentFamily("qwen") -> R.drawable.ic_agent_qwen
        key.isAgentFamily("copilot") || key.isAgentFamily("github-copilot") ->
            R.drawable.ic_agent_copilot
        key.isAgentFamily("hermes") -> R.drawable.ic_agent_hermes
        key.isAgentFamily("goose") -> R.drawable.ic_agent_goose
        else -> R.drawable.ic_agent_generic
    }
}

internal fun String.isAgentFamily(family: String): Boolean =
    this == family || startsWith("$family-") || startsWith("$family.") || startsWith("${family}_")
