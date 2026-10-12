package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray

internal const val AGENT_AUTOMATION_BODY_BYTES = 32 * 1024
internal const val AGENT_AUTOMATION_RESPONSE_BYTES = 64 * 1024
val NAMED_AGENT_KINDS: List<String> = listOf("pi", "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode", "opencode", "copilot", "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli", "qwen", "letta", "maki", "muse")
val AGENT_WAIT_EVENTS: List<String> = listOf("workspace-created", "workspace-updated", "workspace-closed", "workspace-renamed", "workspace-moved", "workspace-focused", "tab-created", "tab-closed", "tab-renamed", "tab-moved", "tab-focused", "pane-created", "pane-closed", "pane-focused", "pane-moved", "pane-output-changed", "pane-exited", "agent-detected", "agent-status-changed")
private val AGENT_STATUSES = setOf("idle", "working", "blocked", "done", "unknown")
private val AGENT_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
private val AGENT_WITNESS = Regex("[a-f0-9]{64}")

@Serializable
data class AgentAutomationInfo(
    val paneId: String, val terminalId: String, val workspaceId: String, val tabId: String,
    val runtimeIdentity: String, val agent: String?, val sessionIdentity: String?, val processIdentity: String?,
    val name: String?, val status: String, val focused: Boolean, val interactiveReady: Boolean, val launchPending: Boolean,
    val stateChangeSeq: String?, val completionSeq: String?,
) {
    fun validFor(pane: String): Boolean = paneId == pane && listOf(paneId, terminalId, workspaceId, tabId).all { AGENT_ID.matches(it) } &&
        runtimeIdentity.length in 1..128 && runtimeIdentity.none { it.code < 32 } &&
        (agent == null || AGENT_ID.matches(agent)) && (name == null || Regex("[a-z][a-z0-9_-]{0,31}").matches(name)) &&
        listOf(sessionIdentity, processIdentity).all { it == null || AGENT_WITNESS.matches(it) } && status in AGENT_STATUSES &&
        listOf(stateChangeSeq, completionSeq).all { it == null || it.matches(Regex("0|[1-9][0-9]{0,19}")) && it.toULongOrNull() != null }
    fun sameOccupant(other: AgentAutomationInfo): Boolean = paneId == other.paneId && terminalId == other.terminalId && workspaceId == other.workspaceId && tabId == other.tabId && runtimeIdentity == other.runtimeIdentity && agent == other.agent && sessionIdentity == other.sessionIdentity && processIdentity == other.processIdentity && name == other.name
}
@Serializable data class AgentAutomationResponse(val ok: Boolean, val schema: Int, val info: AgentAutomationInfo, val witness: String)
@Serializable data class NamedAgentReadResponse(val ok: Boolean, val schema: Int, val paneId: String, val terminalId: String, val text: String, val truncated: Boolean)
@Serializable data class NamedAgentListResponse(val ok: Boolean, val schema: Int, val agents: List<AgentAutomationInfo>)
@Serializable data class AgentActionProgress(val submitted: Boolean, val activityObserved: Boolean, val settled: Boolean)
@Serializable data class AgentActionResponse(val ok: Boolean, val schema: Int, val operation: ControlOperation, val progress: AgentActionProgress, val result: AgentAutomationInfo?)
@Serializable data class AgentExplainDetails(val paneId: String, val terminalId: String, val agent: String, val status: String, val manifestSource: String?, val manifestVersion: String?, val fallbackReason: String?, val warning: String?, val visibleIdle: Boolean, val visibleWorking: Boolean, val visibleBlocked: Boolean, val detectionSkipped: Boolean)
@Serializable data class AgentExplainResponse(val ok: Boolean, val schema: Int, val explain: AgentExplainDetails)
@Serializable data class AgentWaitResponse(val ok: Boolean, val wait: AgentWaitRecord)
@Serializable data class AgentWaitRecord(val schema: Int, val id: String, val paneId: String, val state: String, val code: String?, val deadline: Long, val result: AgentWaitResult?)
@Serializable data class AgentWaitResult(val kind: String, val info: AgentAutomationInfo? = null, val paneId: String? = null, val text: String? = null, val matchedLine: String? = null, val truncated: Boolean? = null, val event: String? = null, val targetId: String? = null, val status: String? = null, val move: AgentObservedMove? = null, val transition: AgentObservedTransition? = null)
@Serializable data class AgentObservedTransition(val event: String, val previousPaneId: String, val previousWorkspaceId: String, val previousTabId: String, val terminalId: String, val after: AgentAutomationInfo?, val witness: String)
@Serializable data class AgentObservedMove(val previousPaneId: String, val previousWorkspaceId: String, val previousTabId: String, val info: AgentAutomationInfo, val witness: String)

/** Typed named parameters only. No method string, command, executable or arbitrary RPC is accepted. */
sealed interface NamedAgentAction {
    val verb: String
    val timeoutMs: Int
    fun body(): JsonObject
    fun valid(): Boolean
    data class Start(val name: String, val agent: String, val args: List<String> = emptyList(), override val timeoutMs: Int = 30_000) : NamedAgentAction {
        override val verb = "start"
        override fun valid() = Regex("[a-z][a-z0-9_-]{0,31}").matches(name) && agent in NAMED_AGENT_KINDS && args.size <= 32 && args.all { it.toByteArray().size <= 512 && it.none { char -> char.code < 32 || char.code == 127 } } && timeoutMs in 3001..300_000
        override fun body() = buildJsonObject { put("name", name); put("agent", agent); put("args", JsonArray(args.map(::JsonPrimitive))); put("timeoutMs", timeoutMs) }
        override fun toString() = "NamedAgentStart(<private arguments>)"
    }
    data class Rename(val name: String?) : NamedAgentAction {
        override val verb = "rename"; override val timeoutMs = 30_000
        override fun valid() = name == null || Regex("[a-z][a-z0-9_-]{0,31}").matches(name)
        override fun body() = buildJsonObject { put("name", name?.let(::JsonPrimitive) ?: JsonNull) }
    }
    data class Prompt(val text: String, val until: List<String>? = null, override val timeoutMs: Int = 30_000) : NamedAgentAction {
        override val verb = "prompt"
        override fun valid() = text.isNotBlank() && validAgentText(text, 16_384) && (until == null || until.isNotEmpty() && until.size <= 5 && until.distinct().size == until.size && until.all { it in AGENT_STATUSES }) && timeoutMs in 1..300_000
        override fun body() = buildJsonObject { put("text", text); put("until", until?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull); put("timeoutMs", timeoutMs) }
        override fun toString() = "NamedAgentPrompt(<private text>)"
    }
    data object Focus : NamedAgentAction {
        override val verb = "focus"; override val timeoutMs = 30_000
        override fun valid() = true
        override fun body() = buildJsonObject { }
    }
    data class Keys(val keys: List<String>) : NamedAgentAction {
        override val verb = "keys"; override val timeoutMs = 30_000
        override fun valid() = validNamedKeys(keys)
        override fun body() = buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) }
    }
    data class Input(val text: String, val keys: List<String>) : NamedAgentAction {
        override val verb = "input"; override val timeoutMs = 30_000
        override fun valid() = validAgentText(text, 16_384) && validNamedKeys(keys, allowEmpty = true) && (text.isNotEmpty() || keys.isNotEmpty())
        override fun body() = buildJsonObject { put("text", text); put("keys", JsonArray(keys.map(::JsonPrimitive))) }
        override fun toString() = "NamedAgentInput(<private text>)"
    }

}
sealed interface NamedAgentWait {
    fun body(): JsonObject
    fun valid(): Boolean
    data class Lifecycle(val until: List<String>) : NamedAgentWait {
        override fun valid() = until.isNotEmpty() && until.size <= 5 && until.distinct().size == until.size && until.all { it in AGENT_STATUSES }
        override fun body() = buildJsonObject { put("kind", "lifecycle"); put("until", JsonArray(until.map(::JsonPrimitive))) }
    }
    data class Output(val source: String, val pattern: String, val regex: Boolean = false, val lines: Int = 200) : NamedAgentWait {
        override fun valid() = source in setOf("visible", "recent", "recent_unwrapped", "detection") && pattern.isNotEmpty() && '\u0000' !in pattern && validAgentText(pattern, if (regex) 256 else 1024) && lines in 1..200
        override fun body() = buildJsonObject { put("kind", "output"); put("source", source); put("lines", lines); put("match", buildJsonObject { put("kind", if (regex) "regex" else "substring"); put("value", pattern) }) }
        override fun toString() = "NamedAgentOutputWait(<private pattern>)"
    }
    data class Event(val event: String, val targetId: String?, val workspaceId: String? = null, val status: String? = null, val agent: String? = null, val label: String? = null) : NamedAgentWait {
        override fun valid() = event in AGENT_WAIT_EVENTS && (targetId == null || AGENT_ID.matches(targetId)) && (workspaceId == null || AGENT_ID.matches(workspaceId)) && (event.endsWith("created") || targetId != null) && (if (event == "agent-status-changed") status in AGENT_STATUSES else status == null) && (agent == null || event == "agent-detected" && AGENT_ID.matches(agent)) && (label == null || event in setOf("workspace-renamed", "tab-renamed") && label.toByteArray().size <= 128) && (workspaceId == null || event in setOf("tab-created", "pane-created"))
        override fun body() = buildJsonObject { put("kind", "event"); put("event", event); put("targetId", targetId?.let(::JsonPrimitive) ?: JsonNull); put("workspaceId", workspaceId?.let(::JsonPrimitive) ?: JsonNull); put("status", status?.let(::JsonPrimitive) ?: JsonNull); put("agent", agent?.let(::JsonPrimitive) ?: JsonNull); put("label", label?.let(::JsonPrimitive) ?: JsonNull) }
    }
}

/** Check actual JSON primitive kinds before serializers: quoted numbers/booleans never coerce. */
internal fun agentInfoJson(value: JsonElement?, paneId: String): Boolean {
    val row = value as? JsonObject ?: return false
    return row.keys == setOf("paneId", "terminalId", "workspaceId", "tabId", "runtimeIdentity", "agent", "sessionIdentity", "processIdentity", "name", "status", "focused", "interactiveReady", "launchPending", "stateChangeSeq", "completionSeq") &&
        row.string("paneId") == paneId && listOf("terminalId", "workspaceId", "tabId", "runtimeIdentity", "status").all { row.string(it) != null } &&
        listOf("agent", "sessionIdentity", "processIdentity", "name", "stateChangeSeq", "completionSeq").all { row[it] == JsonNull || row.string(it) != null } && listOf("focused", "interactiveReady", "launchPending").all { row.boolean(it) != null }
}
internal fun agentControlJson(row: JsonObject, paneId: String): Boolean = row.keys == setOf("ok", "schema", "info", "witness") && row.boolean("ok") == true && row.number("schema") == 1L && row.string("witness")?.let(AGENT_WITNESS::matches) == true && agentInfoJson(row["info"], paneId)
internal fun agentActionJson(row: JsonObject, paneId: String, id: String, witness: String): Boolean {
    val operation = row["operation"] as? JsonObject ?: return false; val progress = row["progress"] as? JsonObject ?: return false
    val originalAt = operation.number("at") ?: return false
    return row.keys == setOf("ok", "schema", "operation", "progress", "result") && row.boolean("ok") == true && row.number("schema") == 1L &&
        operation.keys == setOf("schema", "requestId", "state", "capability", "targetIdentity", "at", "updatedAt", "code") && operation.number("schema") == 1L && operation.string("requestId") == id && operation.string("capability") == "agentAutomation" && operation.string("targetIdentity") == witness && operation.string("state") in setOf("pending", "succeeded", "refused", "unknown") && operation.number("at")?.let { it >= 0 } == true && operation.number("updatedAt")?.let { it >= originalAt } == true && (operation["code"] == JsonNull || operation.string("code") != null) &&
        progress.keys == setOf("submitted", "activityObserved", "settled") && progress.keys.all { progress.boolean(it) != null } && (progress.boolean("activityObserved") != true || progress.boolean("submitted") == true) && (progress.boolean("settled") != true || progress.boolean("activityObserved") == true) && (row["result"] == JsonNull || operation.string("state") == "succeeded" && agentInfoJson(row["result"], paneId))
}
internal fun agentExplainJson(row: JsonObject, paneId: String): Boolean {
    val details = row["explain"] as? JsonObject ?: return false
    return row.keys == setOf("ok", "schema", "explain") && row.boolean("ok") == true && row.number("schema") == 1L && details.keys == setOf("paneId", "terminalId", "agent", "status", "manifestSource", "manifestVersion", "fallbackReason", "warning", "visibleIdle", "visibleWorking", "visibleBlocked", "detectionSkipped") && details.string("paneId") == paneId && listOf("terminalId", "agent", "status").all { details.string(it) != null } && listOf("manifestSource", "manifestVersion", "fallbackReason", "warning").all { details[it] == JsonNull || details.string(it)?.let { text -> text.toByteArray().size <= 1024 } == true } && listOf("visibleIdle", "visibleWorking", "visibleBlocked", "detectionSkipped").all { details.boolean(it) != null }
}
internal fun agentWaitJson(row: JsonObject, paneId: String, id: String): Boolean {
    val wait = row["wait"] as? JsonObject ?: return false
    if (row.keys != setOf("ok", "wait") || row.boolean("ok") != true || wait.keys != setOf("schema", "id", "paneId", "state", "code", "deadline", "result") || (wait["code"] != JsonNull && wait.string("code")?.matches(Regex("agent\\.[a-z_]{1,64}")) != true) || wait.number("schema") != 1L || wait.string("id") != id || wait.string("paneId") != paneId || wait.string("state") !in setOf("pending", "matched", "cancelled", "retired", "timed-out", "interrupted") || wait.number("deadline")?.let { it in 0..9_007_199_254_740_991L } != true) return false
    if (wait["result"] == JsonNull) return true
    if (wait.string("state") != "matched") return false
    val result = wait["result"] as? JsonObject ?: return false
    if (result.toString().toByteArray().size > 32 * 1024) return false
    return when (result.string("kind")) {
        "lifecycle" -> result.keys == setOf("kind", "info") && agentInfoJson(result["info"], paneId)
        "output" -> result.keys == setOf("kind", "paneId", "text", "matchedLine", "truncated") && result.string("paneId") == paneId && result.string("text") != null && (result["matchedLine"] == JsonNull || result.string("matchedLine") != null) && result.boolean("truncated") != null
        "event" -> {
            val move = result["move"] as? JsonObject
            val transition = result["transition"] as? JsonObject
            val transitioned = result.keys == setOf("kind", "event", "targetId", "status", "transition") && transition != null && transition.keys == setOf("event", "previousPaneId", "previousWorkspaceId", "previousTabId", "terminalId", "after", "witness") && transition.string("event") == result.string("event") && result.string("event") in setOf("pane-closed", "pane-exited", "tab-closed", "workspace-closed", "agent-detected") && transition.string("previousPaneId") == paneId && listOf("previousWorkspaceId", "previousTabId", "terminalId").all { transition.string(it)?.let(AGENT_ID::matches) == true } && transition.string("witness")?.let(AGENT_WITNESS::matches) == true &&
                (if (result.string("event") == "agent-detected") agentInfoJson(transition["after"], paneId) && (transition["after"] as? JsonObject)?.string("terminalId") == transition.string("terminalId") else transition["after"] == JsonNull)
            val ordinary = result.keys == setOf("kind", "event", "targetId", "status")
            val moved = result.keys == setOf("kind", "event", "targetId", "status", "move") && result.string("event") == "pane-moved" && move != null &&
                move.keys == setOf("previousPaneId", "previousWorkspaceId", "previousTabId", "info", "witness") && move.string("previousPaneId") == paneId &&
                listOf("previousWorkspaceId", "previousTabId").all { move.string(it)?.let(AGENT_ID::matches) == true } && move.string("witness")?.let(AGENT_WITNESS::matches) == true &&
                result.string("targetId")?.let { target -> agentInfoJson(move["info"], target) } == true
            (ordinary || moved || transitioned) && result.string("event") in AGENT_WAIT_EVENTS && (result["targetId"] == JsonNull || result.string("targetId")?.let(AGENT_ID::matches) == true) && (result["status"] == JsonNull || result.string("status") in AGENT_STATUSES)
        }
        else -> false
    }
}
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun validNamedKeys(keys: List<String>, allowEmpty: Boolean = false): Boolean = keys.size in (if (allowEmpty) 0 else 1)..16 && keys.all { it.length in 1..64 && it.none { char -> char.code < 32 || char.code == 127 } }
internal fun agentReadJson(row: JsonObject, paneId: String): Boolean = row.keys == setOf("ok", "schema", "paneId", "terminalId", "text", "truncated") && row.boolean("ok") == true && row.number("schema") == 1L && row.string("paneId") == paneId && row.string("terminalId")?.let(AGENT_ID::matches) == true && row.string("text")?.toByteArray()?.size?.let { it <= 32 * 1024 } == true && row.boolean("truncated") != null
internal fun agentListJson(row: JsonObject): Boolean {
    val agents = row["agents"] as? JsonArray ?: return false
    if (row.keys != setOf("ok", "schema", "agents") || row.boolean("ok") != true || row.number("schema") != 1L || agents.size > 64) return false
    val terminals = mutableSetOf<String>()
    return agents.all { value -> val info = value as? JsonObject ?: return@all false; val pane = info.string("paneId") ?: return@all false; val terminal = info.string("terminalId") ?: return@all false; agentInfoJson(info, pane) && terminals.add(terminal) }
}

private fun validAgentText(text: String, maxBytes: Int): Boolean {
    if ('\u0000' in text || text.toByteArray(Charsets.UTF_8).size > maxBytes) return false
    var at = 0
    while (at < text.length) { val char = text[at++]; if (char.isHighSurrogate()) { if (at == text.length || !text[at].isLowSurrogate()) return false; at++ } else if (char.isLowSurrogate()) return false }
    return true
}
