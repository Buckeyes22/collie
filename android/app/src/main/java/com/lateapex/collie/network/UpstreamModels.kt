@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

@Serializable
data class UploadCapability(val maxBytes: Long, val imageTypes: List<String>, val textTypes: List<String>)

@Serializable
data class HarnessInfo(val id: String, val label: String, val found: Boolean)

@Serializable
data class StartTarget(
    val harness: String? = null,
    val command: String? = null,
    val shell: Boolean? = null,
) {
    init {
        require(listOf(harness, command, shell?.takeIf { it }).count { it != null } == 1)
        require(harness?.isNotBlank() != false && command?.isNotBlank() != false)
    }
}

@Serializable
data class LauncherItem(
    val key: String,
    val start: StartTarget,
    val group: String,
    val label: String,
    val source: String,
    val noPrompts: Boolean,
    val branch: Boolean,
    val available: Boolean,
    val harness: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val reason: String? = null,
    val id: String? = null,
    val addedBy: String? = null,
    val addedAt: Long? = null,
    val addedAs: String? = null,
)

@Serializable
data class RecentRun(
    val line: String,
    val cwd: String?,
    val at: Long,
    val noPrompts: Boolean,
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
internal data class StartLaunchRequest(
    val requestId: String,
    val cwd: String? = null,
    val harness: String? = null,
    val command: String? = null,
    val shell: Boolean? = null,
)

@Serializable
data class WorktreeBase(@EncodeDefault val kind: String = "default", val ref: String? = null) {
    init {
        require(kind in setOf("ref", "default"))
        require(kind != "ref" || !ref.isNullOrBlank())
        require(kind != "default" || ref == null)
    }
}

@Serializable
data class WorktreeFolder(@EncodeDefault val kind: String = "default", val parent: String? = null) {
    init {
        require(kind in setOf("default", "parent"))
        require(kind != "parent" || !parent.isNullOrBlank())
        require(kind != "default" || parent == null)
    }
}

@Serializable
internal data class WorktreeAtRequest(
    val cwd: String,
    val branch: String,
    val base: WorktreeBase,
    val folder: WorktreeFolder,
    val requestId: String,
    val harness: String? = null,
    val command: String? = null,
    val shell: Boolean? = null,
)

@Serializable
data class WorktreePlanResponse(
    val ok: Boolean,
    val repoRoot: String? = null,
    val defaultBranch: String? = null,
    val currentBranch: String? = null,
    val remembered: JsonObject? = null,
    val branchValid: Boolean? = null,
    val defaultTarget: JsonObject? = null,
    val parentTarget: JsonObject? = null,
    val error: String? = null,
    val code: String? = null,
)

/** Cursors address one live generation or one older turn; never send both. */
data class ChatAfter(val gen: Long, val rev: Long)
data class ChatBefore(val seq: Long, val uuid: String)

@Serializable
data class PaneChatResponse(
    val paneId: String,
    val available: Boolean,
    val reason: String? = null,
    val page: String? = null,
    val gen: Long? = null,
    val rev: Long? = null,
    val head: Long? = null,
    val oldest: Long? = null,
    val hasOlder: Boolean = false,
    val upserts: List<TranscriptEntry> = emptyList(),
    val queued: List<String> = emptyList(),
    val sendQueuedNow: List<String>? = null,
    /** Opaque, server-derived pane occupant identity; absent when guarded sends are unsupported. */
    val queuedSendOccupant: String? = null,
    /** Digest of the ordered queue, computed by the owner; no queued text is repeated on writes. */
    val queuedDigest: String? = null,
)

/** Server-issued binding for one guarded queue action; deliberately contains no queued message text. */
private val QUEUED_SEND_IDENTITY = Regex("^[0-9a-f]{64}$")

internal fun isCanonicalQueuedSendIdentity(value: String?): Boolean =
    value != null && QUEUED_SEND_IDENTITY.matches(value)

@Serializable
data class QueuedSendGuard(
    val generation: Long,
    val revision: Long,
    @SerialName("queue_digest") val queueDigest: String,
    val occupant: String,
)
