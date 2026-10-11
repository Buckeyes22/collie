@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lateapex.collie.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val deposed: Boolean,
    val mode: String,
)

@Serializable
data class BridgeConfigResponse(
    val mux: MuxConfigResponse? = null,
    val operatorCommands: List<OperatorCommand> = emptyList(),
    val operatorKeys: List<OperatorKeyRow> = emptyList(),
    val operatorQuickReplies: List<OperatorQuickReplyRow> = emptyList(),
    val stt: SttCapability? = null,
    val upload: UploadCapability? = null,
    val redact: Boolean? = null,
    val control: ControlCapabilityResponse? = null,
    val hostControl: HostControlResponse? = null,
    /** New durable launch retries require a positive declaration from this owning host. */
    val receiptContextBound: Boolean? = null,
)

@Serializable
data class SttCapability(
    val provider: String,
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
data class MuxConfigResponse(
    val name: String = "",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val spaces: String? = null,
    val unsupportedKeys: List<String> = emptyList(),
    val topologyLatency: MuxTopologyLatencyResponse? = null,
    val readLimits: MuxReadLimitsResponse? = null,
) {
    /** Missing capability data fails open, matching the web client during mixed-version upgrades. */
    fun supports(capability: String): Boolean = capabilities[capability] != false
    /** New operations require a positive declaration; legacy behavior stays limited to supports(). */
    fun supportsNew(capability: String): Boolean = capabilities[capability] == true
}

@Serializable
data class MuxTopologyLatencyResponse(val kind: String, val ms: JsonPrimitive? = null) {
    fun displayBoundMs(): Long? = ms?.takeIf { kind == "bounded" && !it.isString }?.content
        ?.toLongOrNull()?.takeIf { it > 0L && it <= 12_000L }
    fun isPush(): Boolean = kind == "push"
}

@Serializable
data class MuxReadLimitsResponse(val maxRequestedLines: JsonPrimitive? = null) {
    fun verifiedMaximum(): Int? = maxRequestedLines?.takeIf { !it.isString }?.content
        ?.toIntOrNull()?.takeIf { it in 1..10_000 }
}

@Serializable
data class OperatorCommand(
    val agent: String? = null,
    val command: String,
    val description: String = "",
    val takesArg: Boolean = false,
    val argHint: String = "",
    val common: Boolean = true,
    val confirm: Boolean = false,
)

@Serializable
data class OperatorQuickReplyRow(
    val agent: String? = null,
    val title: String,
    val items: List<String>,
)

@Serializable
data class OperatorKeyRow(
    val agent: String? = null,
    val label: String,
    val keys: List<String>,
    val danger: Boolean = false,
)

@Serializable(with = AgentStatusSerializer::class)
enum class AgentStatus {
    @SerialName("idle") IDLE,
    @SerialName("working") WORKING,
    @SerialName("blocked") BLOCKED,
    @SerialName("done") DONE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class PaneSummary(
    val paneId: String,
    val workspaceId: String,
    val workspaceLabel: String,
    val workspaceNumber: Int,
    val tabId: String,
    val agent: String,
    val status: AgentStatus,
    val cwd: String,
    val focused: Boolean,
    val kind: String? = null,
    val paneLabel: String? = null,
    val sessionName: String? = null,
    val hasSession: Boolean? = null,
    /** Mux-reported depth may lag a shrinking buffer and never proves agent history exhaustive. */
    val readableLines: Int? = null,
    val tabLabel: String? = null,
    val soleTabName: String? = null,
    val tabPosition: Int? = null,
    val terminalTitle: String? = null,
    val terminalTitleStale: Boolean? = null,
    val hint: String? = null,
    val lastActiveAt: Long? = null,
    val lastSeenAt: Long? = null,
    val gitHead: JsonObject? = null,
    val cache: JsonObject? = null,
    val model: String? = null,
    val hidden: Boolean = false,
    val host: String? = null,
    val session: String? = null,
)

@Serializable
data class PaneProcess(
    val pid: Long,
    val name: String,
    val argv: List<String>? = null,
    val argv0: String? = null,
    val cmdline: String? = null,
    val cwd: String? = null,
)

@Serializable
data class PaneProcessesResponse(
    val ok: Boolean,
    val paneId: String,
    val shellPid: Long? = null,
    val foregroundProcessGroupId: Long? = null,
    val tty: String? = null,
    val foregroundCwd: String? = null,
    val restoreError: String? = null,
    val processes: List<PaneProcess> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class WorkspaceSummary(
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val activeTabId: String,
    val tabCount: Int,
    val paneCount: Int,
    val repoRoot: String? = null,
    val folder: String? = null,
    val isWorktree: Boolean? = null,
    val host: String? = null,
)

@Serializable
data class TabSummary(
    val tabId: String,
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val paneCount: Int,
    val host: String? = null,
)

@Serializable
data class SessionSummary(
    val name: String,
    val isPrimary: Boolean,
    val reachable: Boolean,
    val agents: Int,
    val working: Int,
    val blocked: Int,
    val host: String? = null,
)

@Serializable
data class ServerSummary(
    val id: String,
    val name: String,
    val isLead: Boolean,
    val reachable: Boolean,
    val protocol: String,
    val protocolDetail: String? = null,
    val lastSeenAt: Long,
)

@Serializable
data class DeviceAuthorization(
    val enforced: Boolean,
    val device: String? = null,
    val authorized: Boolean,
)

@Serializable
data class ControlAuthority(val leadId: String, val epoch: String)

@Serializable
data class SnapshotResponse(
    val bridge: String,
    val controlAuthority: ControlAuthority? = null,
    val device: DeviceAuthorization? = null,
    val agents: List<PaneSummary>,
    val shellPanes: List<PaneSummary>,
    val workspaces: List<WorkspaceSummary>,
    val tabs: List<TabSummary>,
    val sessions: List<SessionSummary> = emptyList(),
    val servers: List<ServerSummary>? = null,
    val notifications: NotificationState? = null,
    val update: UpdateInfo? = null,
    val ts: Long,
)

@Serializable
data class NotificationState(val snoozedUntil: Long?, val preferences: NotifyPreferences? = null)

@Serializable
data class PaneReadResponse(
    val paneId: String,
    val text: String,
    val truncated: Boolean,
    val revision: Long,
    val linkText: String? = null,
    val requestedLines: Int? = null,
    val servedLines: Int? = null,
    val readableLines: Int? = null,
    val limitReason: String? = null,
)

@Serializable
data class ActionResponse(
    val ok: Boolean,
    val error: String? = null,
    val textDelivered: Boolean = false,
    val code: String? = null,
    val detail: JsonObject? = null,
    val reason: String? = null,
)

@Serializable
data class DeviceRecord(
    val label: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val current: Boolean,
    val expiresAt: Long? = null,
    val expired: Boolean = false,
)

@Serializable
data class DevicesResponse(
    val enforced: Boolean,
    val current: String? = null,
    val devices: List<DeviceRecord>,
    val currentExpired: Boolean = false,
)

@Serializable
data class TranscriptResult(
    val text: String? = null,
    val truncated: Boolean = false,
    val isError: Boolean = false,
    val denied: Boolean = false,
    val imageUrl: String? = null,
)

/** One transcript part. Fields not used by its `kind` remain absent on the wire. */
@Serializable
data class TranscriptPart(
    val kind: String,
    val text: String? = null,
    val truncated: Boolean = false,
    val name: String? = null,
    val summary: String? = null,
    val result: TranscriptResult? = null,
    val id: String? = null,
    val call: JsonObject? = null,
    val url: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class TranscriptEntry(
    val uuid: String,
    val ts: String,
    val role: String,
    val parts: List<TranscriptPart>,
    val seq: Long? = null,
    val abandoned: Boolean = false,
)

/** Bounded response body from the authenticated journal blob endpoint. */
data class ImageBlob(val contentType: String?, val bytes: ByteArray) {
    init { require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) }
    companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}

@Serializable
data class PaneHistoryResponse(
    val paneId: String,
    val available: Boolean,
    val reason: String? = null,
    val entries: List<TranscriptEntry> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
    val fileTruncated: Boolean = false,
)

@Serializable
data class UploadResponse(
    val ok: Boolean,
    val path: String? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

@Serializable
data class SttResponse(
    val ok: Boolean,
    val text: String? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

data class ImageUpload(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    init {
        require(fileName.isNotBlank())
        require(contentType.startsWith("image/"))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
    }
}

data class AudioUpload(
    val contentType: String,
    val bytes: ByteArray,
) {
    init {
        require(contentType.substringBefore(';').trim().lowercase() in ACCEPTED_CONTENT_TYPES)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val ACCEPTED_CONTENT_TYPES = setOf(
            "audio/webm",
            "audio/ogg",
            "application/ogg",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/mpeg",
            "audio/wav",
            "audio/x-wav",
        )
    }
}

@Serializable
data class CreatedPane(
    val paneId: String,
    val workspaceId: String,
    val workspaceLabel: String,
    val tabId: String,
    val cwd: String,
)

@Serializable
data class CreateResponse(
    val ok: Boolean,
    val pane: CreatedPane? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

@Serializable
data class Launcher(
    val command: String,
    val label: String,
    val cwd: String? = null,
    val harness: String? = null,
    val noPrompts: Boolean = false,
    /** Present for the richer, server-supplied target catalog; absent on legacy launcher rows. */
    val target: StartTarget? = null,
)

@Serializable
data class LaunchersResponse(
    val launchers: List<Launcher>,
    val home: String,
    val harnesses: List<HarnessInfo>? = null,
    val items: List<LauncherItem>? = null,
    val adding: JsonObject? = null,
    val recentRuns: List<RecentRun>? = null,
)

/** POST /api/launchers/added response; the saved intent keeps this wire payload encrypted locally. */
@Serializable
data class AddedLauncherResponse(
    val ok: Boolean,
    val row: JsonObject? = null,
    val replayed: Boolean? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

@Serializable
data class Worktree(
    val path: String,
    val branch: String?,
    val openWorkspaceId: String?,
    val linked: Boolean,
    val prunable: Boolean,
)

@Serializable
data class WorktreeListResponse(
    val ok: Boolean,
    val worktrees: List<Worktree> = emptyList(),
    val defaultBranch: String? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

@Serializable
data class WorktreeOpenResponse(
    val ok: Boolean,
    val pane: CreatedPane? = null,
    val alreadyOpen: Boolean = false,
    val launcherStarted: Boolean = false,
    val launcherError: String? = null,
    val replayed: Boolean = false,
    val error: String? = null,
    val code: String? = null,
    val detail: JsonObject? = null,
)

@Serializable
data class NotifyPreferences(
    val blocked: Boolean,
    val done: Boolean,
    val updates: Boolean,
    val cache: Boolean = false,
    val machines: Boolean = true,
)

@Serializable
data class NotifyPreferencesPatch(
    val blocked: Boolean? = null,
    val done: Boolean? = null,
    val updates: Boolean? = null,
    val cache: Boolean? = null,
    val machines: Boolean? = null,
)

@Serializable
data class UpdatePeerLeg(
    val name: String,
    val state: String,
    val version: String? = null,
    val reason: String? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class UpdateRun(
    val schema: Int,
    val state: String,
    val from: String?,
    val to: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val pid: Long,
    val attempt: Int,
    val reason: String? = null,
    val logTail: String? = null,
    val recovery: String? = null,
    val runId: String? = null,
    val peers: List<UpdatePeerLeg>? = null,
    val settledAt: Long? = null,
)

/** `/standby/update` also answers `{ "state": "idle" }` before any run exists. */
@Serializable
data class StandbyUpdateResponse(
    val state: String,
    val schema: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
    val pid: Long? = null,
    val attempt: Int? = null,
    val reason: String? = null,
    val logTail: String? = null,
    val recovery: String? = null,
    val runId: String? = null,
    val peers: List<UpdatePeerLeg>? = null,
) {
    fun activeRunOrNull(): UpdateRun? {
        if (state == "idle") return null
        return UpdateRun(
            schema = schema ?: return null,
            state = state,
            from = from,
            to = to,
            startedAt = startedAt ?: return null,
            updatedAt = updatedAt ?: return null,
            pid = pid ?: return null,
            attempt = attempt ?: return null,
            reason = reason,
            logTail = logTail,
            recovery = recovery,
            runId = runId,
            peers = peers,
        )
    }
}

@Serializable
data class UpdateInfo(
    val current: String,
    val latest: String?,
    val latestUrl: String?,
    val releaseAvailable: Boolean,
    val majorAvailable: String?,
    val majorUrl: String?,
    val installKind: String? = null,
    val packageCommand: String? = null,
    val restartNeeded: Boolean = false,
    val restartCommand: String? = null,
    val linkChange: JsonObject? = null,
    val urgent: JsonObject? = null,
    val peers: List<UpdatePeerLeg>? = null,
    val peersTo: String? = null,
    val settledAt: Long? = null,
    val bridgeStale: Boolean,
    val checkedAt: Long?,
    val newerVersions: List<String>? = null,
    val run: UpdateRun? = null,
)

@Serializable
data class PreflightCheck(
    val id: String,
    val verdict: String,
    val reason: String,
    val remedy: String? = null,
)

@Serializable
data class PreflightReport(
    val schema: Int,
    val verdict: String,
    val checks: List<PreflightCheck>,
)

@Serializable
data class UpdateCrewMember(
    val name: String,
    val version: String?,
    val verdict: String,
    val reasons: List<String>,
    val asOf: Long?,
    val health: String? = null,
    val installKind: String? = null,
)

@Serializable
data class UpdateCheckResponse(
    val current: String,
    val latest: String?,
    val latestUrl: String?,
    val releaseAvailable: Boolean,
    val majorAvailable: String?,
    val majorUrl: String?,
    val installKind: String? = null,
    val packageCommand: String? = null,
    val restartNeeded: Boolean = false,
    val restartCommand: String? = null,
    val linkChange: JsonObject? = null,
    val urgent: JsonObject? = null,
    val peers: List<UpdatePeerLeg>? = null,
    val peersTo: String? = null,
    val settledAt: Long? = null,
    val bridgeStale: Boolean,
    val checkedAt: Long?,
    val newerVersions: List<String>? = null,
    val run: UpdateRun? = null,
    val preflight: PreflightReport? = null,
    @JsonNames("pack") val crew: List<UpdateCrewMember>? = null,
)

@Serializable
data class UpdateStartResponse(
    val ok: Boolean,
    val to: String,
    val major: Boolean,
    val run: UpdateRun? = null,
)

@Serializable
data class CrewIdentity(
    val id: String,
    val name: String,
    val secretGeneration: Int,
    val rotatedAt: Long,
)

@Serializable
data class CrewSelf(
    val id: String,
    val name: String,
    val version: String,
)

@Serializable
data class CrewDeputy(
    val id: String,
    val warrantGeneration: Int? = null,
)

@Serializable
data class CrewConflict(
    val leadMemberId: String,
    val warrantGeneration: Int? = null,
)

@Serializable
data class CrewMember(
    val id: String,
    val name: String,
    val isLead: Boolean,
    val address: String? = null,
    val enrolledAt: Long? = null,
    val health: String,
    val reason: String? = null,
    val lastSeenAt: Long,
    val version: String? = null,
    val secretBehind: Boolean,
    val provisional: Boolean,
    val linkState: String? = null,
    val conflict: CrewConflict? = null,
)

@Serializable
data class CrewStatusResponse(
    @JsonNames("pack") val crew: CrewIdentity,
    val self: CrewSelf,
    val deputy: CrewDeputy?,
    val members: List<CrewMember>,
    val ts: Long,
)

sealed interface PairResult {
    data class Paired(val token: String, val label: String) : PairResult {
        override fun toString(): String = "Paired(token=<redacted>, label=$label)"
    }

    data class Refused(val reason: String) : PairResult
}

@Serializable
internal data class PairResponse(
    val token: String? = null,
    val label: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
internal data class PairRequest(val code: String, val label: String)

@Serializable
internal data class ReplyRequest(
    val text: String,
    val submit: Boolean,
    @SerialName("expected_prompt") val expectedPrompt: String? = null,
)

@Serializable
internal data class KeysRequest(
    val keys: List<String>,
    @SerialName("expected_prompt") val expectedPrompt: String? = null,
)

@Serializable
internal data class RenameRequest(val label: String)

@Serializable
internal data class CreateTabRequest(
    val workspaceId: String,
    val label: String? = null,
    val cwd: String? = null,
)

@Serializable
internal data class CreateWorkspaceRequest(
    val label: String? = null,
    val cwd: String? = null,
)

@Serializable
internal data class LaunchRequest(
    val command: String,
    val paneId: String? = null,
    val requestId: String = java.util.UUID.randomUUID().toString(),
)

@Serializable
internal data class BranchRequest(val branch: String, val requestId: String = java.util.UUID.randomUUID().toString())

@Serializable
internal data class WorktreePathRequest(val path: String)

@Serializable
internal data class SnoozeRequest(val snoozedUntil: Long?)

@Serializable
internal data class RevokeDeviceRequest(val label: String)

@Serializable
internal data class StartUpdateRequest(
    val confirm: Boolean,
    val target: String,
    val major: Boolean,
    val peersOnly: Boolean? = null,
)

@Serializable
internal data class ErrorResponse(
    val error: String? = null,
    val code: String? = null,
    val textDelivered: Boolean = false,
    val detail: JsonObject? = null,
)
