package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.DeviceLabel
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.MuxKeyGrammar
import com.lateapex.collie.domain.Scope
import java.io.IOException
import java.io.InterruptedIOException
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
        val status: Int,
        val etag: String? = null,
        val buildId: String? = null,
        val controlAuthority: ControlAuthority? = null,
    ) : ApiResult<T>

    data class NotModified(val etag: String?, val controlAuthority: ControlAuthority? = null) : ApiResult<Nothing>
    data class Failure(val error: ApiFailure) : ApiResult<Nothing>
}

sealed interface ApiFailure {
    data class Redirect(val status: Int) : ApiFailure
    data class Http(
        val status: Int,
        val message: String?,
        val code: String?,
        val textDelivered: Boolean = false,
        val source: FailureSource = FailureSource.UNKNOWN,
        val factor: FailureFactor = FailureFactor.UNKNOWN,
    ) : ApiFailure
    data class Protocol(val message: String) : ApiFailure
    data class Network(val message: String?) : ApiFailure
    data object Timeout : ApiFailure
}

interface CollieApi {
    suspend fun health(connection: Connection): ApiResult<HealthResponse>
    suspend fun config(connection: Connection): ApiResult<BridgeConfigResponse> =
        ApiResult.Failure(ApiFailure.Protocol("Config is unavailable"))
    suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> = config(connection)
    suspend fun snapshot(connection: Connection, scope: Scope = Scope()): ApiResult<SnapshotResponse>
    suspend fun pane(
        connection: Connection,
        address: PaneAddress,
        lines: Int = 600,
        etag: String? = null,
        markSeen: Boolean = true,
    ): ApiResult<PaneReadResponse>
    suspend fun paneProcesses(connection: Connection, address: PaneAddress): ApiResult<PaneProcessesResponse> = unavailable("Pane process details")
    suspend fun refresh(connection: Connection, scope: Scope = Scope()): ApiResult<ActionResponse>
    suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult>
    suspend fun controlOperation(connection: Connection, scope: Scope, requestId: String): ApiResult<ControlOperationResponse> = unavailable("Control operations")
    suspend fun devices(connection: Connection): ApiResult<DevicesResponse>
    suspend fun revokeDevice(connection: Connection, label: String): ApiResult<DevicesResponse> = unavailable("Device revoke")
    suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> = unavailable("Crew status")
    /** [etag] from the last answer to the same read: an unchanged page comes back as NotModified. */
    suspend fun history(
        connection: Connection,
        address: PaneAddress,
        limit: Int = 200,
        before: String? = null,
        etag: String? = null,
    ): ApiResult<PaneHistoryResponse> = unavailable("Pane history")
    suspend fun chat(
        connection: Connection,
        address: PaneAddress,
        limit: Int = 200,
        after: ChatAfter? = null,
        before: ChatBefore? = null,
        etag: String? = null,
    ): ApiResult<PaneChatResponse> = unavailable("Live chat")
    /** Authenticated bytes from Collie's content-addressed journal image route. */
    suspend fun imageBlob(connection: Connection, scope: Scope, sha256: String): ApiResult<ImageBlob> = unavailable("Journal image")
    suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup): ApiResult<ChangesListAnswer> = unavailable("Changes")
    suspend fun changeDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String): ApiResult<ChangeDiffAnswer> = unavailable("Changes diff")
    suspend fun changeCommit(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String): ApiResult<ChangeCommitAnswer> = unavailable("Changes commit")
    suspend fun changeCommitDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String): ApiResult<ChangeCommitDiffAnswer> = unavailable("Changes commit diff")
    suspend fun startLaunch(connection: Connection, scope: Scope, target: StartTarget, requestId: String, cwd: String? = null): ApiResult<CreateResponse> = unavailable("Launch by id")
    /** Sends the exact encrypted-journal body for a deliberate replay; never rebuilds an intent. */
    suspend fun startLaunchFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> = unavailable("Frozen launch retry")
    suspend fun addLauncherFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<AddedLauncherResponse> = unavailable("Frozen launcher add")
    suspend fun planWorktree(connection: Connection, scope: Scope, cwd: String, branch: String? = null, parent: String? = null): ApiResult<WorktreePlanResponse> = unavailable("Worktree plan")
    suspend fun createWorktreeAt(connection: Connection, scope: Scope, cwd: String, branch: String, target: StartTarget, requestId: String, base: WorktreeBase = WorktreeBase(), folder: WorktreeFolder = WorktreeFolder()): ApiResult<WorktreeOpenResponse> = unavailable("Worktree from folder")
    /** Sends the exact encrypted-journal body for a deliberate replay. */
    suspend fun createWorktreeAtFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<WorktreeOpenResponse> = unavailable("Frozen worktree retry")
    suspend fun closePane(connection: Connection, address: PaneAddress): ApiResult<ActionResponse> = unavailable("Pane close")
    suspend fun focusPane(connection: Connection, address: PaneAddress): ApiResult<ActionResponse> = unavailable("Pane focus")
    suspend fun renamePane(
        connection: Connection,
        address: PaneAddress,
        label: String,
    ): ApiResult<ActionResponse> = unavailable("Pane rename")
    suspend fun renameTab(
        connection: Connection,
        scope: Scope,
        tabId: String,
        label: String,
    ): ApiResult<ActionResponse> = unavailable("Tab rename")
    suspend fun closeTab(connection: Connection, scope: Scope, tabId: String): ApiResult<ActionResponse> = unavailable("Tab close")
    suspend fun createTab(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = unavailable("Tab create")
    suspend fun createWorkspace(
        connection: Connection,
        scope: Scope,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = unavailable("Workspace create")
    suspend fun launchers(connection: Connection, scope: Scope): ApiResult<LaunchersResponse> = unavailable("Launchers")
    suspend fun folders(connection: Connection, scope: Scope): ApiResult<FoldersResponse> = unavailable("Folders")
    suspend fun starFolder(connection: Connection, scope: Scope, folder: String, starred: Boolean): ApiResult<FoldersResponse> = unavailable("Folder favorites")
    suspend fun launch(
        connection: Connection,
        scope: Scope,
        command: String,
        besidePaneId: String? = null,
    ): ApiResult<CreateResponse> = unavailable("Launch")
    suspend fun listWorktrees(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
    ): ApiResult<WorktreeListResponse> = unavailable("Worktree list")
    suspend fun createWorktree(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        branch: String,
    ): ApiResult<WorktreeOpenResponse> = unavailable("Worktree create")
    suspend fun createWorktreeFrozen(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        canonicalBody: String,
    ): ApiResult<WorktreeOpenResponse> = unavailable("Frozen worktree create")
    suspend fun openWorktree(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        path: String,
    ): ApiResult<WorktreeOpenResponse> = unavailable("Worktree open")
    suspend fun upload(
        connection: Connection,
        address: PaneAddress,
        upload: ImageUpload,
    ): ApiResult<UploadResponse> = unavailable("Upload")
    suspend fun transcribe(connection: Connection, audio: AudioUpload): ApiResult<SttResponse> =
        unavailable("Speech to text")
    suspend fun notificationPreferences(connection: Connection): ApiResult<NotifyPreferences> = unavailable("Notification preferences")
    suspend fun setNotificationPreferences(
        connection: Connection,
        patch: NotifyPreferencesPatch,
    ): ApiResult<NotifyPreferences> = unavailable("Notification preferences update")
    suspend fun setNotificationSnooze(
        connection: Connection,
        snoozedUntil: Long?,
    ): ApiResult<NotificationState> = unavailable("Notification snooze")
    suspend fun checkForUpdates(connection: Connection): ApiResult<UpdateInfo> = unavailable("Update check")
    suspend fun updateState(connection: Connection): ApiResult<UpdateCheckResponse> = unavailable("Update state")
    suspend fun standbyUpdate(connection: Connection): ApiResult<StandbyUpdateResponse> = unavailable("Standby update state")
    suspend fun startUpdate(
        connection: Connection,
        target: String,
        major: Boolean,
        peersOnly: Boolean = false,
    ): ApiResult<UpdateStartResponse> = unavailable("Update start")
    suspend fun snoozeUpdate(connection: Connection): ApiResult<UpdateInfo> = unavailable("Update snooze")
    suspend fun dismissUpdate(
        connection: Connection,
        version: String,
        scope: UpdateDismissalScope,
    ): ApiResult<UpdateInfo> = unavailable("Update dismissal")
    suspend fun reply(
        connection: Connection,
        address: PaneAddress,
        text: String,
        submit: Boolean = true,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse>
    suspend fun keys(
        connection: Connection,
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse>
    suspend fun queuedSendNow(
        connection: Connection,
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String,
        guard: QueuedSendGuard,
    ): ApiResult<ActionResponse> = unavailable("Guarded queued send")

    private fun <T> unavailable(operation: String): ApiResult<T> =
        ApiResult.Failure(ApiFailure.Protocol("$operation is unavailable"))
}

class CollieApiClient(
    private val client: OkHttpClient,
    private val json: Json,
) : CollieApi {
    override suspend fun imageBlob(connection: Connection, scope: Scope, sha256: String): ApiResult<ImageBlob> {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        val imageAccept = "image/png,image/jpeg,image/gif,image/webp"
        val imageUrl = scopedUrl(connection.origin, path("blobs", sha256.lowercase()), scope)
        val request = requestBuilder(connection, imageUrl).header("Accept", imageAccept).get().build()
        return executeResponse(request, READ_TIMEOUT_SECONDS) { response ->
            if (response.code in REDIRECT_CODES) return@executeResponse ApiResult.Failure(ApiFailure.Redirect(response.code))
            if (!response.isSuccessful) {
                val body = response.body?.source()?.let { source ->
                    val limit = MAX_ERROR_MESSAGE_CHARS.toLong()
                    source.request(limit + 1)
                    source.readUtf8(minOf(source.buffer.size, limit))
                }.orEmpty()
                return@executeResponse failure(response, body)
            }
            val body = response.body ?: return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Image response was empty"))
            val declared = body.contentLength()
            if (declared > ImageBlob.MAX_BYTES) return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Image Content-Length exceeds the 10 MiB limit ($declared bytes)"))
            val bytes = try {
                body.source().use { source ->
                    val output = java.io.ByteArrayOutputStream(minOf(if (declared >= 0) declared.toInt() else 64 * 1024, ImageBlob.MAX_BYTES))
                    val chunk = okio.Buffer()
                    while (true) {
                        val read = source.read(chunk, 16 * 1024L)
                        if (read == -1L) break
                        val bytesRead = chunk.readByteArray()
                        val received = output.size().toLong() + bytesRead.size
                        if (received > ImageBlob.MAX_BYTES) return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Image stream exceeds the 10 MiB limit ($received bytes)"))
                        output.write(bytesRead)
                    }
                    output.toByteArray()
                }
            } catch (_: IOException) {
                return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Image response could not be read"))
            }
            if (bytes.isEmpty()) return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Image response was empty"))
            ApiResult.Success(
                ImageBlob(response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(), bytes),
                response.code,
                controlAuthority = responseAuthority(response),
            )
        }
    }
    override suspend fun health(connection: Connection): ApiResult<HealthResponse> =
        executeJson(request(connection, path("health")), HealthResponse.serializer(), READ_TIMEOUT_SECONDS)

    override suspend fun config(connection: Connection): ApiResult<BridgeConfigResponse> =
        executeJson(request(connection, path("config")), BridgeConfigResponse.serializer(), READ_TIMEOUT_SECONDS)

    override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> =
        executeJson(request(connection, scopedUrl(connection.origin, path("config"), scope)), BridgeConfigResponse.serializer(), READ_TIMEOUT_SECONDS)

    override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
        val url = scopedUrl(
            connection.origin,
            path("snapshot"),
            scope,
            if (scope.viewAll) listOf("sessions" to "all") else emptyList(),
        )
        return executeJson(request(connection, url), SnapshotResponse.serializer(), READ_TIMEOUT_SECONDS)
    }

    override suspend fun pane(
        connection: Connection,
        address: PaneAddress,
        lines: Int,
        etag: String?,
        markSeen: Boolean,
    ): ApiResult<PaneReadResponse> {
        require(lines in 1..10_000)
        val url = scopedUrl(
            connection.origin,
            path("pane", address.paneId),
            address.scope,
            listOf("lines" to lines.toString()),
        )
        val builder = requestBuilder(connection, url)
        if (etag != null) builder.header("If-None-Match", etag)
        if (markSeen) builder.header("X-Collie-Seen", "1")
        return executeJson(builder.build(), PaneReadResponse.serializer(), READ_TIMEOUT_SECONDS, allowNotModified = true)
    }

    override suspend fun paneProcesses(connection: Connection, address: PaneAddress): ApiResult<PaneProcessesResponse> {
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "processes"), address.scope)
        val builder = requestBuilder(connection, url).header("X-Collie-Seen", "1")
        return requireContract(
            executeJson(
                builder.get().build(),
                PaneProcessesResponse.serializer(),
                READ_TIMEOUT_SECONDS,
                maxResponseBytes = MAX_PROCESS_INFO_BODY_BYTES,
            ),
            "Malformed process details response",
        ) { response ->
            response.ok && response.paneId == address.paneId && response.processes.size <= 64 &&
                (response.shellPid == null || response.shellPid >= 0) &&
                (response.foregroundProcessGroupId == null || response.foregroundProcessGroupId >= 0) &&
                (response.foregroundCwd == null || response.foregroundCwd.length <= 4096) &&
                (response.restoreError == null || response.restoreError.length <= 4096) &&
                response.processes.all { process ->
                    process.pid >= 0 && process.name.length <= 256 &&
                        (process.argv == null || process.argv.size <= 64 && process.argv.all { it.length <= 2048 }) &&
                        (process.argv0 == null || process.argv0.length <= 2048) &&
                        (process.cmdline == null || process.cmdline.length <= 8192) &&
                        (process.cwd == null || process.cwd.length <= 4096)
                } &&
                ((response.tty?.length ?: 0) + (response.foregroundCwd?.length ?: 0) + (response.restoreError?.length ?: 0) +
                    response.processes.sumOf { process ->
                        process.name.length + (process.argv?.sumOf { it.length } ?: 0) + (process.argv0?.length ?: 0) +
                            (process.cmdline?.length ?: 0) + (process.cwd?.length ?: 0)
                    } <= MAX_PROCESS_INFO_TEXT_CHARS)
        }
    }

    override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> {
        val url = scopedUrl(connection.origin, path("refresh"), scope)
        return executeAction(post(connection, url, ""))
    }

    override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> {
        val bootstrap = Connection(origin, label, null)
        val request = post(bootstrap, url(origin, path("pair")), json.encodeToString(PairRequest(code, label)))
        return executeResponse(request, MUTATION_TIMEOUT_SECONDS) {
            if (it.code in REDIRECT_CODES) return@executeResponse ApiResult.Failure(ApiFailure.Redirect(it.code))
            val body = it.body?.string().orEmpty()
            val decoded = decode(PairResponse.serializer(), body)
            if (it.code == 400) {
                return@executeResponse if (decoded != null) {
                    ApiResult.Success(PairResult.Refused(decoded.error ?: "bad-request"), it.code)
                } else {
                    failure(it, body)
                }
            }
            if (!it.isSuccessful) return@executeResponse failure(it, body)
            if (decoded == null) return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Malformed pairing response"))
            val token = decoded.token
            val returnedLabel = decoded.label
            if (token.isNullOrBlank() || returnedLabel.isNullOrBlank() || returnedLabel.length > DeviceLabel.MAX_CHARS ||
                token.any { char -> char <= ' ' || char >= '\u007f' }
            ) {
                ApiResult.Failure(ApiFailure.Protocol("Pairing response omitted or invalidated required fields"))
            } else {
                ApiResult.Success(PairResult.Paired(token, returnedLabel), it.code)
            }
        }
    }

    override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> =
        executeJson(request(connection, url(connection.origin, path("devices"))), DevicesResponse.serializer(), READ_TIMEOUT_SECONDS)

    override suspend fun revokeDevice(connection: Connection, label: String): ApiResult<DevicesResponse> {
        require(label.isNotBlank())
        val body = json.encodeToString(RevokeDeviceRequest(label))
        return executeJson(
            post(connection, url(connection.origin, path("devices", "revoke")), body),
            DevicesResponse.serializer(),
            MUTATION_TIMEOUT_SECONDS,
        )
    }

    override suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> {
        val result = executeJson(
            request(connection, url(connection.origin, path("crew"))),
            CrewStatusResponse.serializer(),
            READ_TIMEOUT_SECONDS,
        )
        // A single legacy read fallback; a crew-role refusal must keep its meaning.
        if (result is ApiResult.Failure && result.error is ApiFailure.Http &&
            result.error.status == 404 && result.error.code != "crew.not_lead"
        ) return executeJson(request(connection, url(connection.origin, path("pack"))), CrewStatusResponse.serializer(), READ_TIMEOUT_SECONDS)
        return result
    }

    override suspend fun history(
        connection: Connection,
        address: PaneAddress,
        limit: Int,
        before: String?,
        etag: String?,
    ): ApiResult<PaneHistoryResponse> {
        require(limit in 1..MAX_HISTORY_LIMIT)
        require(before?.isNotBlank() != false)
        val leadingQuery = buildList {
            add("limit" to limit.toString())
            before?.let { add("before" to it) }
        }
        val url = scopedUrl(
            connection.origin,
            path("pane", address.paneId, "history"),
            address.scope,
            leadingQuery,
        )
        val builder = requestBuilder(connection, url).header("X-Collie-Seen", "1")
        if (etag != null) builder.header("If-None-Match", etag)
        return requireContract(
            executeJson(builder.get().build(), PaneHistoryResponse.serializer(), READ_TIMEOUT_SECONDS, allowNotModified = true),
            "Malformed pane history response",
        ) { response ->
            if (response.available) response.reason == null else !response.reason.isNullOrBlank()
        }
    }

    override suspend fun chat(connection: Connection, address: PaneAddress, limit: Int, after: ChatAfter?, before: ChatBefore?, etag: String?): ApiResult<PaneChatResponse> {
        require(limit in 1..MAX_HISTORY_LIMIT && (after == null || before == null))
        val query = buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to "${it.gen}:${it.rev}") }
            before?.let { require(it.uuid.isNotBlank()); add("before" to "${it.seq}:${it.uuid}") }
        }
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "chat"), address.scope, query)
        val builder = requestBuilder(connection, url).header("X-Collie-Seen", "1")
        if (before == null && after != null && etag != null) builder.header("If-None-Match", etag)
        return requireContract(
            executeJson(builder.get().build(), PaneChatResponse.serializer(), READ_TIMEOUT_SECONDS, allowNotModified = before == null && after != null),
            "Malformed live chat response",
        ) { page ->
            if (!page.available) !page.reason.isNullOrBlank()
            else page.gen != null && page.gen >= 0L && page.upserts.all { it.seq != null && it.seq >= 0L } &&
                if (before != null) page.page == "older" else page.page == "live" &&
                    (page.rev == null || page.rev >= 0L) && page.head != null && page.head >= 0L &&
                    page.oldest != null && page.oldest >= 0L
        }
    }

    override suspend fun changes(
        connection: Connection,
        target: ChangesTarget,
        scope: Scope,
        lookup: ChangesLookup,
    ): ApiResult<ChangesListAnswer> {
        validateChangesTarget(target)
        val query = listOf("depth" to lookup.depth.toString(), "nested" to if (lookup.nested) "1" else "0")
        val url = scopedUrl(connection.origin, changesPath(target), scope, query)
        return executeChanges(request(connection, url), "Malformed Changes response") { ChangesWire.list(it, target) }
    }

    override suspend fun changeDiff(
        connection: Connection,
        target: ChangesTarget,
        scope: Scope,
        lookup: ChangesLookup,
        repo: String,
        path: String,
    ): ApiResult<ChangeDiffAnswer> {
        validateChangesTarget(target)
        validateChangesReference(repo, path)
        val query = listOf(
            "depth" to lookup.depth.toString(), "nested" to if (lookup.nested) "1" else "0",
            "repo" to repo, "path" to path,
        )
        val url = scopedUrl(connection.origin, changesPath(target), scope, query)
        return executeChanges(request(connection, url), "Malformed Changes diff response") {
            ChangesWire.diff(it, target, repo, path, commit = false)
        }
    }

    override suspend fun changeCommit(
        connection: Connection,
        target: ChangesTarget,
        scope: Scope,
        lookup: ChangesLookup,
        repo: String,
    ): ApiResult<ChangeCommitAnswer> {
        validateChangesTarget(target)
        validateChangesReference(repo)
        val query = listOf(
            "depth" to lookup.depth.toString(), "nested" to if (lookup.nested) "1" else "0",
            "view" to "commit", "repo" to repo,
        )
        val url = scopedUrl(connection.origin, changesPath(target), scope, query)
        return executeChanges(request(connection, url), "Malformed Changes commit response") {
            ChangesWire.commit(it, target, repo)
        }
    }

    override suspend fun changeCommitDiff(
        connection: Connection,
        target: ChangesTarget,
        scope: Scope,
        lookup: ChangesLookup,
        repo: String,
        path: String,
    ): ApiResult<ChangeCommitDiffAnswer> {
        validateChangesTarget(target)
        validateChangesReference(repo, path)
        val query = listOf(
            "depth" to lookup.depth.toString(), "nested" to if (lookup.nested) "1" else "0",
            "repo" to repo, "path" to path, "view" to "commit",
        )
        val url = scopedUrl(connection.origin, changesPath(target), scope, query)
        return executeChanges(request(connection, url), "Malformed Changes commit diff response") { element ->
            ChangesWire.diff(element, target, repo, path, commit = true)?.let {
                ChangeCommitDiffAnswer(it.target, it.workspaceLabel, it.state)
            }
        }
    }

    private fun changesPath(target: ChangesTarget): List<String> = when (target) {
        is ChangesTarget.Pane -> path("pane", target.id, "changes")
        is ChangesTarget.Workspace -> path("workspace", target.id, "changes")
    }

    private fun validateChangesTarget(target: ChangesTarget) {
        require(target.id.isNotBlank() && target.id.length <= MAX_CHANGES_ID_CHARS && '\u0000' !in target.id)
    }

    private fun validateChangesReference(repo: String, path: String? = null) {
        require(repo.isNotBlank() && repo.length <= MAX_CHANGES_ID_CHARS && '\u0000' !in repo)
        require(path == null || (path.isNotBlank() && path.length <= MAX_CHANGES_ID_CHARS && '\u0000' !in path))
    }

    override suspend fun startLaunch(connection: Connection, scope: Scope, target: StartTarget, requestId: String, cwd: String?): ApiResult<CreateResponse> {
        require(requestId.isNotBlank())
        val body = StartLaunchRequest(requestId, cwd, target.harness, target.command, target.shell)
        return executeCreate(post(connection, scopedUrl(connection.origin, path("launch"), scope), json.encodeToString(body)))
    }

    override suspend fun startLaunchFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
        require(canonicalBody.isNotBlank() && canonicalBody.length <= 16_384)
        return executeCreate(post(connection, scopedUrl(connection.origin, path("launch"), scope), canonicalBody))
    }

    override suspend fun addLauncherFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<AddedLauncherResponse> {
        require(canonicalBody.isNotBlank() && canonicalBody.length <= 65_536)
        val expected = expectedAddedLauncher(canonicalBody)
            ?: return ApiResult.Failure(ApiFailure.Protocol("Malformed frozen launcher request"))
        return requireContract(
            executeJson(
                post(connection, scopedUrl(connection.origin, path("launchers", "added"), scope), canonicalBody),
                AddedLauncherResponse.serializer(),
                MUTATION_TIMEOUT_SECONDS,
            ),
            "Malformed launcher add response",
        ) { response ->
            if (response.ok) validAddedLauncherRow(response, expected)
            else !response.error.isNullOrBlank() || !response.code.isNullOrBlank()
        }
    }

    private data class ExpectedAddedLauncher(
        val requestId: String,
        val addedAs: String,
        val kind: String,
        val harness: String?,
        val command: String,
        val label: String,
        val noPrompts: Boolean,
    )

    private data class RecipeOptionContract(
        val id: String,
        val label: String,
        val args: String,
        val group: String? = null,
        val noPrompts: Boolean = false,
    )

    private data class RecipeContract(
        val binary: String,
        val label: String,
        val options: List<RecipeOptionContract> = emptyList(),
    )

    /** Mirror the versioned Collie recipe IDs and their actual executable names, never assume id == binary. */
    private val launcherRecipes = mapOf(
        "claude" to RecipeContract("claude", "Claude Code", listOf(
            RecipeOptionContract("skip", "Skip permission prompts", "--dangerously-skip-permissions", "permissions", true),
            RecipeOptionContract("plan", "Plan mode", "--permission-mode plan", "permissions"),
            RecipeOptionContract("opus", "Model: opus", "--model opus", "model"),
            RecipeOptionContract("sonnet", "Model: sonnet", "--model sonnet", "model"),
            RecipeOptionContract("continue", "Continue last", "--continue"),
        )),
        "codex" to RecipeContract("codex", "Codex", listOf(
            RecipeOptionContract("bypass", "Skip approvals and sandbox", "--dangerously-bypass-approvals-and-sandbox", "mode", true),
            RecipeOptionContract("never", "Never ask", "--ask-for-approval never", "mode", true),
            RecipeOptionContract("readonly", "Read-only sandbox", "--sandbox read-only", "mode"),
            RecipeOptionContract("search", "Web search", "--search"),
        )),
        "opencode" to RecipeContract("opencode", "opencode", listOf(
            RecipeOptionContract("auto", "Auto-approve", "--auto", noPrompts = true),
            RecipeOptionContract("plan", "Plan agent", "--agent plan"),
            RecipeOptionContract("continue", "Continue last", "--continue"),
        )),
        "pi" to RecipeContract("pi", "pi", listOf(
            RecipeOptionContract("continue", "Continue last", "--continue"),
            RecipeOptionContract("thinking-high", "Thinking: high", "--thinking high"),
        )),
        "omp" to RecipeContract("omp", "omp", listOf(
            RecipeOptionContract("continue", "Continue last", "--continue"),
            RecipeOptionContract("thinking-high", "Thinking: high", "--thinking=high"),
        )),
        "grok" to RecipeContract("grok", "Grok"),
        "hermes" to RecipeContract("hermes", "Hermes"),
        "muse" to RecipeContract("muse", "Muse"),
        "agy" to RecipeContract("agy", "Antigravity"),
    )
    private data class AddedLauncherRowContract(
        val requestId: String,
        val kind: String,
        val harness: String?,
        val command: String,
        val label: String,
        val noPrompts: Boolean,
        val addedBy: String,
        val addedAt: Long,
        val addedAs: String,
    )

    /** The response row is evidence for this exact frozen request, not an arbitrary `ok: true`. */
    private fun expectedAddedLauncher(body: String): ExpectedAddedLauncher? {
        return try {
            val request = json.parseToJsonElement(body).jsonObject
            val requestId = request["requestId"]?.stringValue() ?: return null
            if (!requestId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE))) return null
            val explicitLabel = request["label"]
            val requestedLabel = when {
                explicitLabel == null || explicitLabel == JsonNull -> null
                else -> {
                    val value = explicitLabel.stringValue() ?: return null
                    if (value == "") null else java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC)
                }
            }
            val recipe = request["recipe"] as? JsonObject
            val typed = request["text"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }
            when {
                recipe != null && "text" !in request -> {
                    val harness = recipe["harness"]?.stringValue()?.takeIf { it.isNotBlank() && it.length <= 48 } ?: return null
                    val contract = launcherRecipes[harness] ?: return null
                    val rawOptions = recipe["options"] as? kotlinx.serialization.json.JsonArray ?: return null
                    val optionIds = mutableListOf<String>()
                    for (element in rawOptions) {
                        val id = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                        optionIds += id
                    }
                    if (optionIds.size != optionIds.toSet().size) return null
                    val chosenIds = optionIds.toSet()
                    val chosen = contract.options.filter { it.id in chosenIds }
                    if (chosen.size != chosenIds.size) return null
                    val groups = chosen.mapNotNull { it.group }
                    if (groups.size != groups.toSet().size) return null
                    val command = (listOf(contract.binary) + chosen.map { it.args }).joinToString(" ")
                    val label = requestedLabel ?: if (chosen.isEmpty()) contract.label else
                        "${contract.label}, ${chosen.joinToString(", ") { it.label }}"
                    ExpectedAddedLauncher(
                        requestId, "recipe", "agent", harness, command, label, chosen.any { it.noPrompts },
                    )
                }
                recipe == null && request["recipe"] == null && typed != null -> {
                    val command = java.text.Normalizer.normalize(typed.content.trim(), java.text.Normalizer.Form.NFC)
                    val defaultLabel = command.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
                    val expectedLabel = requestedLabel ?: takeCodePoints(defaultLabel, 60)
                    val noPrompts = request["noPrompts"] == JsonPrimitive(true) || scanNoPromptsForReceipt(command)
                    when (request["kind"]?.stringValue()) {
                        "command" -> {
                            if ("harness" in request) return null
                            ExpectedAddedLauncher(requestId, "text", "command", null, command, expectedLabel, noPrompts)
                        }
                        "agent" -> {
                            val harness = request["harness"]?.stringValue()?.takeIf { launcherRecipes.containsKey(it) } ?: return null
                            ExpectedAddedLauncher(requestId, "text", "agent", harness, command, expectedLabel, noPrompts)
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    private fun scanNoPromptsForReceipt(command: String): Boolean {
        val words = command.trim().split(Regex("\\s+")).map { word ->
            if (word.length >= 2 && (word.first() == '\'' || word.first() == '\"') && word.last() == word.first()) {
                word.substring(1, word.length - 1)
            } else word
        }
        val flags = setOf(
            "--dangerously-skip-permissions", "--permission-mode=bypassPermissions",
            "--dangerously-bypass-approvals-and-sandbox", "--yolo", "--ask-for-approval=never",
        )
        if (words.any { it in flags }) return true
        if (words.zipWithNext().any { (flag, value) ->
                (flag == "--permission-mode" && value == "bypassPermissions") ||
                    (flag == "--ask-for-approval" && value == "never")
            }) return true
        val executable = words.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("\\.(?:exe|cmd|ps1)$", RegexOption.IGNORE_CASE), "")
        return executable == "codex" && words.zipWithNext().any { (flag, value) -> flag == "-a" && value == "never" } ||
            executable == "opencode" && "--auto" in words
    }

    private fun validAddedLauncherRow(response: AddedLauncherResponse, expected: ExpectedAddedLauncher): Boolean =
        response.replayed != false && parseAddedLauncherRow(response.row, expected) != null

    private fun parseAddedLauncherRow(row: JsonObject?, expected: ExpectedAddedLauncher): AddedLauncherRowContract? {
        row ?: return null
        val allowedFields = setOf("source", "id", "kind", "harness", "command", "label", "noPrompts", "addedBy", "addedAt", "addedAs")
        val expectedFieldCount = if (expected.harness == null) 9 else 10
        if (row.size != expectedFieldCount || row.keys.any { it !in allowedFields }) return null
        fun string(key: String): String? = row[key]?.let { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
        if (string("source") != "added") return null
        val requestId = string("id")?.takeIf { it == expected.requestId } ?: return null
        val kind = string("kind")?.takeIf { it == expected.kind } ?: return null
        val addedAs = string("addedAs")?.takeIf { it == expected.addedAs } ?: return null
        val harness = if (expected.harness == null) {
            if ("harness" in row) return null
            null
        } else string("harness")?.takeIf { it == expected.harness } ?: return null
        val command = string("command")?.takeIf { validLauncherText(it, 200) && it == expected.command } ?: return null
        val label = string("label")?.takeIf { validLauncherText(it, 60) && it == expected.label } ?: return null
        val addedBy = string("addedBy")?.takeIf {
            it.isNotBlank() && it.length <= 48 && it == it.trim() && it.none { character -> character.isISOControl() }
        } ?: return null
        val noPromptsPrimitive = row["noPrompts"] as? JsonPrimitive ?: return null
        if (noPromptsPrimitive.isString) return null
        val noPrompts = noPromptsPrimitive.booleanOrNull?.takeIf { it == expected.noPrompts } ?: return null
        val addedAtPrimitive = row["addedAt"] as? JsonPrimitive ?: return null
        if (addedAtPrimitive.isString) return null
        val addedAt = addedAtPrimitive.longOrNull?.takeIf { it in 0..9_007_199_254_740_991L } ?: return null
        return AddedLauncherRowContract(requestId, kind, harness, command, label, noPrompts, addedBy, addedAt, addedAs)
    }

    private fun validLauncherText(value: String, maxCodePoints: Int): Boolean {
        if (value.isBlank() || value != java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC)) return false
        if (value.codePointCount(0, value.length) > maxCodePoints) return false
        return value.codePoints().noneMatch { code ->
            code < 0x20 || code in 0x7f..0x9f || code == 0x2028 || code == 0x2029 ||
                code in 0x202a..0x202e || code in 0x2066..0x2069 || code == 0x200e || code == 0x200f || code == 0x061c
        }
    }

    private fun takeCodePoints(value: String, count: Int): String {
        val end = value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length)))
        return value.substring(0, end)
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    override suspend fun planWorktree(connection: Connection, scope: Scope, cwd: String, branch: String?, parent: String?): ApiResult<WorktreePlanResponse> {
        require(cwd.isNotBlank())
        val query = buildList { add("cwd" to cwd); branch?.let { add("branch" to it) }; parent?.let { add("parent" to it) } }
        return executeJson(request(connection, scopedUrl(connection.origin, path("worktree", "plan"), scope, query)), WorktreePlanResponse.serializer(), READ_TIMEOUT_SECONDS)
    }

    override suspend fun createWorktreeAt(connection: Connection, scope: Scope, cwd: String, branch: String, target: StartTarget, requestId: String, base: WorktreeBase, folder: WorktreeFolder): ApiResult<WorktreeOpenResponse> {
        require(cwd.isNotBlank() && branch.isNotBlank() && requestId.isNotBlank())
        val body = WorktreeAtRequest(cwd, branch, base, folder, requestId, target.harness, target.command, target.shell)
        return executeWorktreeOpen(post(connection, scopedUrl(connection.origin, path("worktree"), scope), json.encodeToString(body)))
    }

    override suspend fun createWorktreeAtFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<WorktreeOpenResponse> {
        require(canonicalBody.isNotBlank() && canonicalBody.length <= 16_384)
        return executeWorktreeOpen(post(connection, scopedUrl(connection.origin, path("worktree"), scope), canonicalBody))
    }

    override suspend fun closePane(connection: Connection, address: PaneAddress): ApiResult<ActionResponse> =
        paneAction(connection, address, "close")

    override suspend fun focusPane(connection: Connection, address: PaneAddress): ApiResult<ActionResponse> =
        paneAction(connection, address, "focus")

    override suspend fun renamePane(
        connection: Connection,
        address: PaneAddress,
        label: String,
    ): ApiResult<ActionResponse> {
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "rename"), address.scope)
        return executeAction(post(connection, url, json.encodeToString(RenameRequest(label))))
    }

    override suspend fun renameTab(
        connection: Connection,
        scope: Scope,
        tabId: String,
        label: String,
    ): ApiResult<ActionResponse> {
        require(tabId.isNotBlank() && label.isNotBlank())
        val url = scopedUrl(connection.origin, path("tab", tabId, "rename"), scope)
        return executeAction(post(connection, url, json.encodeToString(RenameRequest(label))))
    }

    override suspend fun closeTab(
        connection: Connection,
        scope: Scope,
        tabId: String,
    ): ApiResult<ActionResponse> {
        require(tabId.isNotBlank())
        val url = scopedUrl(connection.origin, path("tab", tabId, "close"), scope)
        return executeAction(post(connection, url, EMPTY_JSON))
    }

    override suspend fun createTab(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        label: String?,
        cwd: String?,
    ): ApiResult<CreateResponse> {
        require(workspaceId.isNotBlank())
        val body = json.encodeToString(CreateTabRequest(workspaceId, label, cwd))
        val url = scopedUrl(connection.origin, path("tab"), scope)
        return executeCreate(post(connection, url, body))
    }

    override suspend fun createWorkspace(
        connection: Connection,
        scope: Scope,
        label: String?,
        cwd: String?,
    ): ApiResult<CreateResponse> {
        val body = json.encodeToString(CreateWorkspaceRequest(label, cwd))
        val url = scopedUrl(connection.origin, path("workspace"), scope)
        return executeCreate(post(connection, url, body))
    }

    override suspend fun launchers(connection: Connection, scope: Scope): ApiResult<LaunchersResponse> =
        executeJson(
            request(connection, scopedUrl(connection.origin, path("launchers"), scope)),
            LaunchersResponse.serializer(),
            READ_TIMEOUT_SECONDS,
        )

    override suspend fun folders(connection: Connection, scope: Scope): ApiResult<FoldersResponse> =
        requireContract(
            executeJson(request(connection, scopedUrl(connection.origin, path("folders"), scope)), FoldersResponse.serializer(), READ_TIMEOUT_SECONDS),
            "Malformed folder list response",
        ) { response -> response.home.isNotBlank() && response.recent.size <= 8 && response.favourites.size <= 12 &&
            response.recent.all { it.isNotBlank() && it.length <= 4096 } &&
            response.favourites.all { it.isNotBlank() && it.length <= 4096 } && response.recent.toSet().intersect(response.favourites.toSet()).isEmpty()
        }

    override suspend fun starFolder(connection: Connection, scope: Scope, folder: String, starred: Boolean): ApiResult<FoldersResponse> {
        require(folder.isNotBlank() && folder.length <= 4096)
        val body = json.encodeToString(StarFolderRequest(folder, starred))
        return requireContract(
            executeJson(post(connection, scopedUrl(connection.origin, path("folders", "star"), scope), body), FoldersResponse.serializer(), MUTATION_TIMEOUT_SECONDS),
            "Malformed folder star response",
        ) { response -> response.home.isNotBlank() && response.recent.size <= 8 && response.favourites.size <= 12 &&
            response.recent.all { it.isNotBlank() && it.length <= 4096 } &&
            response.favourites.all { it.isNotBlank() && it.length <= 4096 } && response.recent.toSet().intersect(response.favourites.toSet()).isEmpty()
        }
    }

    override suspend fun launch(
        connection: Connection,
        scope: Scope,
        command: String,
        besidePaneId: String?,
    ): ApiResult<CreateResponse> {
        require(command.isNotBlank())
        require(besidePaneId?.isNotBlank() != false)
        val body = json.encodeToString(LaunchRequest(command, besidePaneId))
        val url = scopedUrl(connection.origin, path("launch"), scope)
        return executeCreate(post(connection, url, body))
    }

    override suspend fun listWorktrees(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
    ): ApiResult<WorktreeListResponse> {
        require(workspaceId.isNotBlank())
        val url = scopedUrl(connection.origin, path("workspace", workspaceId, "worktrees"), scope)
        return requireContract(
            executeJson(request(connection, url), WorktreeListResponse.serializer(), READ_TIMEOUT_SECONDS),
            "Malformed worktree list response",
        ) { response -> response.ok || !response.error.isNullOrBlank() }
    }

    override suspend fun createWorktree(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        branch: String,
    ): ApiResult<WorktreeOpenResponse> {
        require(workspaceId.isNotBlank() && branch.isNotBlank())
        val url = scopedUrl(connection.origin, path("workspace", workspaceId, "worktree"), scope)
        val body = json.encodeToString(BranchRequest(branch))
        return executeWorktreeOpen(post(connection, url, body))
    }

    override suspend fun createWorktreeFrozen(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        canonicalBody: String,
    ): ApiResult<WorktreeOpenResponse> {
        require(workspaceId.isNotBlank() && canonicalBody.isNotBlank())
        val url = scopedUrl(connection.origin, path("workspace", workspaceId, "worktree"), scope)
        return executeWorktreeOpen(post(connection, url, canonicalBody))
    }

    override suspend fun openWorktree(
        connection: Connection,
        scope: Scope,
        workspaceId: String,
        path: String,
    ): ApiResult<WorktreeOpenResponse> {
        require(workspaceId.isNotBlank() && path.isNotBlank())
        val url = scopedUrl(connection.origin, this.path("workspace", workspaceId, "worktree", "open"), scope)
        val body = json.encodeToString(WorktreePathRequest(path))
        return executeWorktreeOpen(post(connection, url, body))
    }

    override suspend fun upload(
        connection: Connection,
        address: PaneAddress,
        upload: ImageUpload,
    ): ApiResult<UploadResponse> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                upload.fileName,
                upload.bytes.toRequestBody(upload.contentType.toMediaTypeOrNull()),
            )
            .build()
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "upload"), address.scope)
        return requireContract(
            executeJson(post(connection, url, body), UploadResponse.serializer(), UPLOAD_TIMEOUT_SECONDS),
            "Malformed upload response",
        ) { response ->
            if (response.ok) !response.path.isNullOrBlank() else !response.error.isNullOrBlank()
        }
    }

    override suspend fun transcribe(
        connection: Connection,
        audio: AudioUpload,
    ): ApiResult<SttResponse> {
        val contentType = audio.contentType.toMediaTypeOrNull()
            ?: return ApiResult.Failure(ApiFailure.Protocol("Invalid audio content type"))
        return requireContract(
            executeJson(
                post(connection, url(connection.origin, path("stt")), audio.bytes.toRequestBody(contentType)),
                SttResponse.serializer(),
                sttTimeoutSeconds(audio.bytes.size),
            ),
            "Malformed speech-to-text response",
        ) { response ->
            if (response.ok) !response.text.isNullOrBlank() else !response.error.isNullOrBlank()
        }
    }

    override suspend fun notificationPreferences(connection: Connection): ApiResult<NotifyPreferences> =
        executeJson(
            request(connection, url(connection.origin, path("notifications", "prefs"))),
            NotifyPreferences.serializer(),
            READ_TIMEOUT_SECONDS,
        )

    override suspend fun setNotificationPreferences(
        connection: Connection,
        patch: NotifyPreferencesPatch,
    ): ApiResult<NotifyPreferences> = executeJson(
        post(
            connection,
            url(connection.origin, path("notifications", "prefs")),
            json.encodeToString(patch),
        ),
        NotifyPreferences.serializer(),
        MUTATION_TIMEOUT_SECONDS,
    )

    override suspend fun setNotificationSnooze(
        connection: Connection,
        snoozedUntil: Long?,
    ): ApiResult<NotificationState> = executeJson(
        post(
            connection,
            url(connection.origin, path("notifications", "snooze")),
            snoozedUntil?.let { json.encodeToString(SnoozeRequest(it)) }
                ?: "{\"snoozedUntil\":null}",
        ),
        NotificationState.serializer(),
        MUTATION_TIMEOUT_SECONDS,
    )

    override suspend fun checkForUpdates(connection: Connection): ApiResult<UpdateInfo> =
        executeJson(
            post(connection, url(connection.origin, path("update", "check")), EMPTY_JSON),
            UpdateInfo.serializer(),
            MUTATION_TIMEOUT_SECONDS,
        )

    override suspend fun updateState(connection: Connection): ApiResult<UpdateCheckResponse> =
        executeJson(
            request(connection, url(connection.origin, path("update", "check"))),
            UpdateCheckResponse.serializer(),
            READ_TIMEOUT_SECONDS,
        )

    override suspend fun standbyUpdate(connection: Connection): ApiResult<StandbyUpdateResponse> =
        requireContract(
            executeJson(
                requestBuilder(connection, url(connection.origin, listOf("standby", "update")))
                    .removeHeader("Authorization").get().build(),
                StandbyUpdateResponse.serializer(),
                READ_TIMEOUT_SECONDS,
            ),
            "Malformed standby update response",
        ) { response -> response.state == "idle" || response.activeRunOrNull() != null }

    override suspend fun startUpdate(
        connection: Connection,
        target: String,
        major: Boolean,
        peersOnly: Boolean,
    ): ApiResult<UpdateStartResponse> {
        require(target.isNotBlank())
        val body = json.encodeToString(
            StartUpdateRequest(
                confirm = true,
                target = target,
                major = major,
                peersOnly = true.takeIf { peersOnly },
            ),
        )
        return requireContract(
            executeJson(
                post(connection, url(connection.origin, path("update")), body),
                UpdateStartResponse.serializer(),
                MUTATION_TIMEOUT_SECONDS,
            ),
            "Malformed update start response",
        ) { response -> response.ok && response.to.isNotBlank() }
    }

    override suspend fun snoozeUpdate(connection: Connection): ApiResult<UpdateInfo> =
        executeJson(
            post(connection, url(connection.origin, path("update", "snooze")), EMPTY_JSON),
            UpdateInfo.serializer(),
            MUTATION_TIMEOUT_SECONDS,
        )

    override suspend fun dismissUpdate(
        connection: Connection,
        version: String,
        scope: UpdateDismissalScope,
    ): ApiResult<UpdateInfo> {
        require(version.isNotBlank())
        return executeJson(
            post(
                connection,
                url(connection.origin, path("update", "dismiss")),
                json.encodeToString(UpdateDismissRequest(version, scope)),
            ),
            UpdateInfo.serializer(),
            MUTATION_TIMEOUT_SECONDS,
        )
    }

    override suspend fun reply(
        connection: Connection,
        address: PaneAddress,
        text: String,
        submit: Boolean,
        expectedPrompt: String?,
    ): ApiResult<ActionResponse> {
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "reply"), address.scope)
        return executeAction(post(connection, url, json.encodeToString(ReplyRequest(text, submit, expectedPrompt))))
    }

    override suspend fun keys(
        connection: Connection,
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String?,
    ): ApiResult<ActionResponse> {
        require(keys.isNotEmpty() && keys.all(MuxKeyGrammar::isValid))
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "keys"), address.scope)
        return executeAction(post(connection, url, json.encodeToString(KeysRequest(keys, expectedPrompt))))
    }

    override suspend fun queuedSendNow(
        connection: Connection,
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String,
        guard: QueuedSendGuard,
    ): ApiResult<ActionResponse> {
        if (keys.isEmpty() || keys.size > MAX_QUEUED_SEND_KEYS || !keys.all(MuxKeyGrammar::isValid) ||
            guard.generation < 0L || guard.revision < 0L ||
            !isCanonicalQueuedSendIdentity(guard.queueDigest) || !isCanonicalQueuedSendIdentity(guard.occupant)
        ) return ApiResult.Failure(ApiFailure.Protocol("Queued send action is malformed"))
        val url = scopedUrl(connection.origin, path("pane", address.paneId, "keys"), address.scope)
        val body = GuardedQueuedSendRequest(keys.toList(), expectedPrompt, guard)
        return executeAction(post(connection, url, json.encodeToString(body)))
    }

    private suspend fun paneAction(
        connection: Connection,
        address: PaneAddress,
        action: String,
    ): ApiResult<ActionResponse> {
        val url = scopedUrl(connection.origin, path("pane", address.paneId, action), address.scope)
        return executeAction(post(connection, url, EMPTY_JSON))
    }

    private suspend fun executeCreate(request: Request): ApiResult<CreateResponse> = requireContract(
        executeJson(request, CreateResponse.serializer(), MUTATION_TIMEOUT_SECONDS),
        "Malformed create response",
    ) { response ->
        if (response.ok) response.pane != null else !response.error.isNullOrBlank()
    }

    private suspend fun executeWorktreeOpen(request: Request): ApiResult<WorktreeOpenResponse> = requireContract(
        executeJson(request, WorktreeOpenResponse.serializer(), WORKTREE_TIMEOUT_SECONDS),
        "Malformed worktree response",
    ) { response ->
        if (response.ok) response.pane != null else !response.error.isNullOrBlank()
    }

    private fun <T> requireContract(
        result: ApiResult<T>,
        message: String,
        valid: (T) -> Boolean,
    ): ApiResult<T> = when (result) {
        is ApiResult.Success -> if (valid(result.value)) result else ApiResult.Failure(ApiFailure.Protocol(message))
        is ApiResult.Failure -> result
        is ApiResult.NotModified -> result
    }

    override suspend fun controlOperation(connection: Connection, scope: Scope, requestId: String): ApiResult<ControlOperationResponse> {
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        val result = executeJson(request(connection, scopedUrl(connection.origin, listOf("api", "control", "operations", requestId), scope)), ControlOperationResponse.serializer(), READ_TIMEOUT_SECONDS)
        return requireContract(result, "Unknown or mismatched control operation") { it.ok && it.operation.validFor(requestId) }
    }

    private suspend fun executeAction(request: Request): ApiResult<ActionResponse> =
        executeResponse(request, MUTATION_TIMEOUT_SECONDS) {
            if (it.code in REDIRECT_CODES) return@executeResponse ApiResult.Failure(ApiFailure.Redirect(it.code))
            val body = it.body?.string().orEmpty()
            val action = decode(ActionResponse.serializer(), body)
            if (action != null && (action.ok || !action.error.isNullOrBlank()) && (it.isSuccessful || it.code == 409)) {
                return@executeResponse ApiResult.Success(action, it.code, buildId = it.header(BUILD_HEADER), controlAuthority = responseAuthority(it))
            }
            if (!it.isSuccessful) return@executeResponse failure(it, body)
            ApiResult.Failure(ApiFailure.Protocol("Malformed action response"))
        }

    private suspend fun <T> executeJson(
        request: Request,
        serializer: DeserializationStrategy<T>,
        timeoutSeconds: Long,
        allowNotModified: Boolean = false,
        maxResponseBytes: Int? = null,
    ): ApiResult<T> = executeResponse(request, timeoutSeconds) {
        if (allowNotModified && it.code == 304) return@executeResponse ApiResult.NotModified(it.header("ETag"), responseAuthority(it))
        if (it.code in REDIRECT_CODES) return@executeResponse ApiResult.Failure(ApiFailure.Redirect(it.code))
        val body = (if (maxResponseBytes == null) it.body?.string().orEmpty() else readBoundedBody(it, maxResponseBytes))
            ?: return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Process details response exceeded its size limit"))
        if (!it.isSuccessful) return@executeResponse failure(it, body)
        val value = decode(serializer, body)
            ?: return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Malformed successful response"))
        ApiResult.Success(value, it.code, it.header("ETag"), it.header(BUILD_HEADER), responseAuthority(it))
    }

    /** Process details are sensitive and bounded at the wire before a JSON String is materialized. */
    private fun readBoundedBody(response: Response, maxBytes: Int): String? {
        val body = response.body ?: return ""
        if (body.contentLength() > maxBytes) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private suspend fun <T> executeChanges(
        request: Request,
        malformedMessage: String,
        parse: (JsonElement) -> T?,
    ): ApiResult<T> = executeResponse(request, READ_TIMEOUT_SECONDS) { response ->
        if (response.code in REDIRECT_CODES) return@executeResponse ApiResult.Failure(ApiFailure.Redirect(response.code))
        val body = response.body
        if ((body?.contentLength() ?: 0L) > MAX_CHANGES_RESPONSE_BYTES) {
            return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Changes response exceeds the size limit"))
        }
        val buffer = Buffer()
        val source = body?.source()
        if (source != null) {
            while (buffer.size < MAX_CHANGES_RESPONSE_BYTES) {
                val remaining = MAX_CHANGES_RESPONSE_BYTES - buffer.size
                val read = source.read(buffer, minOf(remaining, 8_192L))
                if (read == -1L) break
            }
            if (buffer.size == MAX_CHANGES_RESPONSE_BYTES && source.read(Buffer(), 1L) != -1L) {
                return@executeResponse ApiResult.Failure(ApiFailure.Protocol("Changes response exceeds the size limit"))
            }
        }
        val text = buffer.readUtf8()
        if (!response.isSuccessful) return@executeResponse failure(response, text)
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val value = element?.let(parse)
            ?: return@executeResponse ApiResult.Failure(ApiFailure.Protocol(malformedMessage))
        ApiResult.Success(value, response.code, response.header("ETag"), response.header(BUILD_HEADER), responseAuthority(response))
    }

    /** Consume and close the body on OkHttp's worker, before handing a value to the caller. */
    private suspend fun <T> executeResponse(
        request: Request,
        timeoutSeconds: Long,
        read: (Response) -> ApiResult<T>,
    ): ApiResult<T> = try {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
            .newCall(request).await(timeoutSeconds, read)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: InterruptedIOException) {
        ApiResult.Failure(ApiFailure.Timeout)
    } catch (io: IOException) {
        ApiResult.Failure(ApiFailure.Network(io.message))
    }

    private fun failure(response: Response, body: String): ApiResult.Failure {
        val status = response.code
        val source = when (response.header("x-collie-error-source")) {
            "collie" -> FailureSource.COLLIE
            "gateway" -> FailureSource.GATEWAY
            else -> FailureSource.UNKNOWN
        }
        val factor = if (source == FailureSource.UNKNOWN) FailureFactor.UNKNOWN else when (response.header("x-collie-error-factor")) {
            "pairing" -> FailureFactor.PAIRING
            "identity" -> FailureFactor.IDENTITY
            "device" -> FailureFactor.DEVICE
            "scope" -> FailureFactor.SCOPE
            "capability" -> FailureFactor.CAPABILITY
            "operation" -> FailureFactor.OPERATION
            else -> FailureFactor.UNKNOWN
        }
        val parsed = decode(ErrorResponse.serializer(), body)
        val plainMessage = body.trim()
            .take(MAX_ERROR_MESSAGE_CHARS)
            .takeIf { it.isNotEmpty() && !it.startsWith("<") }
        return ApiResult.Failure(
            ApiFailure.Http(
                status = status,
                message = parsed?.error ?: plainMessage,
                code = response.header("x-collie-error-code") ?: parsed?.code,
                textDelivered = parsed?.textDelivered == true,
                source = source,
                factor = factor,
            ),
        )
    }

    private fun <T> decode(serializer: DeserializationStrategy<T>, body: String): T? =
        try {
            json.decodeFromString(serializer, body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun request(connection: Connection, relativePath: List<String>): Request =
        request(connection, url(connection.origin, relativePath))

    private fun request(connection: Connection, url: HttpUrl): Request =
        requestBuilder(connection, url).get().build()

    private fun requestBuilder(connection: Connection, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE.toString())
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                connection.token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }

    private fun post(connection: Connection, url: HttpUrl, body: String): Request =
        post(connection, url, body.toRequestBody(JSON_MEDIA_TYPE))

    private fun post(connection: Connection, url: HttpUrl, body: RequestBody): Request =
        requestBuilder(connection, url)
            .header("Origin", connection.origin.headerValue)
            .post(OneShotRequestBody(body))
            .build()

    private fun responseAuthority(response: okhttp3.Response): ControlAuthority? {
        val lead = response.header("X-Collie-Lead-Id") ?: return null
        val epoch = response.header("X-Collie-Lead-Epoch") ?: return null
        if (!lead.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !epoch.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        return ControlAuthority(lead, epoch)
    }

    private fun url(origin: CollieOrigin, relativePath: List<String>): HttpUrl =
        origin.value.toHttpUrl().newBuilder().apply {
            relativePath.forEach { addEncodedPathSegment(encodePathSegment(it)) }
        }.build()

    private fun scopedUrl(
        origin: CollieOrigin,
        relativePath: List<String>,
        scope: Scope,
        leadingQuery: List<Pair<String, String>> = emptyList(),
    ): HttpUrl = origin.value.toHttpUrl().newBuilder().apply {
        relativePath.forEach { addEncodedPathSegment(encodePathSegment(it)) }
        leadingQuery.forEach { (name, value) -> addQueryParameter(name, value) }
        scope.host?.let { addQueryParameter("host", it) }
        scope.session?.let { addQueryParameter("session", it) }
    }.build()

    private fun path(vararg segments: String): List<String> = listOf("api", *segments)

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val EMPTY_JSON = "{}"
        private const val BUILD_HEADER = "X-Collie-Build"
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val MUTATION_TIMEOUT_SECONDS = 20L
        private const val MAX_QUEUED_SEND_KEYS = 12
        private const val WORKTREE_TIMEOUT_SECONDS = 100L
        private const val UPLOAD_TIMEOUT_SECONDS = 60L
        private const val STT_PROVIDER_AND_OVERHEAD_SECONDS = 80L
        private const val STT_UPLINK_BITS_PER_SECOND = 256_000L
        private const val MAX_HISTORY_LIMIT = 5_000
        private const val MAX_PROCESS_INFO_TEXT_CHARS = 220 * 1024
        private const val MAX_PROCESS_INFO_BODY_BYTES = 1_000_000
        private const val MAX_CHANGES_ID_CHARS = 8_192
        private const val MAX_CHANGES_RESPONSE_BYTES = 5L * 1024L * 1024L
        private const val MAX_ERROR_MESSAGE_CHARS = 512
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        internal fun sttTimeoutSeconds(bytes: Int): Long =
            STT_PROVIDER_AND_OVERHEAD_SECONDS +
                ((bytes.coerceAtLeast(0).toLong() * 8) + STT_UPLINK_BITS_PER_SECOND - 1) /
                STT_UPLINK_BITS_PER_SECOND
        fun defaultHttpClient(diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder? = null): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .apply {
                    if (diagnostics != null) {
                        addInterceptor(com.lateapex.collie.diagnostics.DiagnosticsInterceptor(diagnostics))
                    }
                }
                .build()
    }
}

private suspend fun <T> Call.await(timeoutSeconds: Long, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        // Remains installed while the callback reads the body, so cancellation closes the socket.
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!continuation.isActive) return
                        read(it)
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
