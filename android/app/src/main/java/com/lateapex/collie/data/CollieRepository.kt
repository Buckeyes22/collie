package com.lateapex.collie.data

import com.lateapex.collie.domain.DeviceLabel
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.MuxKeyGrammar
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AudioUpload
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.ImageUpload
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.NotificationState
import com.lateapex.collie.network.NotifyPreferences
import com.lateapex.collie.network.NotifyPreferencesPatch
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.CrewStatusResponse
import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.StartTarget
import com.lateapex.collie.network.WorktreeBase
import com.lateapex.collie.network.WorktreeFolder
import com.lateapex.collie.network.WorktreePlanResponse
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneHistoryResponse
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.StandbyUpdateResponse
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdateInfo
import com.lateapex.collie.network.UpdateStartResponse
import com.lateapex.collie.network.UploadResponse
import com.lateapex.collie.network.WorktreeListResponse
import com.lateapex.collie.network.WorktreeOpenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PaneContent(
    val pane: PaneReadResponse,
    val notModified: Boolean,
    val etag: String?,
)

/**
 * Write authority learned from the most recent successful snapshot for the current connection.
 * A missing device field is a positive legacy-server result; it is distinct from never having
 * verified this connection, which stays fail-closed.
 */
enum class DeviceWriteAuthorization(val permitsWrites: Boolean) {
    UNVERIFIED(false),
    LEGACY_OR_NOT_ENFORCED(true),
    AUTHORIZED(true),
    DENIED(false),
    ;

    companion object {
        fun from(device: DeviceAuthorization?): DeviceWriteAuthorization = when {
            device == null || !device.enforced -> LEGACY_OR_NOT_ENFORCED
            device.authorized -> AUTHORIZED
            else -> DENIED
        }
    }
}

/**
 * The only layer that combines connection state, scope, API calls, and pane-body caching.
 * Control methods invoke the transport exactly once. UI callers must confirm destructive actions
 * before entering this layer because an interrupted response never proves that a write did not land.
 */
class CollieRepository(
    private val api: CollieApi,
    private val connectionStore: ConnectionStore,
    private val originValidator: OriginValidator,
    private val maxPaneCacheEntries: Int = 20,
) {
    val connection: StateFlow<Connection?> = connectionStore.connection

    private val mutableDeviceWriteAuthorization = MutableStateFlow(DeviceWriteAuthorization.UNVERIFIED)
    @Volatile private var authorizationConnection: Connection? = null
    val deviceWriteAuthorization: StateFlow<DeviceWriteAuthorization> =
        mutableDeviceWriteAuthorization.asStateFlow()

    fun writesAllowed(): Boolean =
        connection.value?.let { configured ->
            configured.isPaired && authorizationConnection === configured &&
                mutableDeviceWriteAuthorization.value.permitsWrites
        } == true

    private val mutableScope = MutableStateFlow(Scope())
    val selectedScope: StateFlow<Scope> = mutableScope.asStateFlow()

    private val cacheMutex = Mutex()
    private val paneCache = object : LinkedHashMap<PaneCacheKey, CachedPane>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PaneCacheKey, CachedPane>?): Boolean =
            size > maxPaneCacheEntries
    }

    init {
        require(maxPaneCacheEntries > 0)
    }

    fun selectScope(scope: Scope) {
        mutableScope.value = scope
    }

    fun connectReadOnly(origin: String, label: String): Connection {
        val cleanLabel = DeviceLabel.normalize(label)
        require(cleanLabel.isNotEmpty() && cleanLabel.length <= DeviceLabel.MAX_CHARS)
        val connection = Connection(originValidator.requireValid(origin), cleanLabel, null)
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        connectionStore.save(connection)
        return connection
    }

    suspend fun probe(origin: String, label: String = "Android"): ApiResult<HealthResponse> {
        val candidate = Connection(originValidator.requireValid(origin), label, null)
        return api.health(candidate)
    }

    suspend fun pair(origin: String, label: String, code: String): ApiResult<PairResult> {
        val normalized = originValidator.requireValid(origin)
        val cleanLabel = DeviceLabel.normalize(label)
        require(cleanLabel.isNotEmpty() && cleanLabel.length <= DeviceLabel.MAX_CHARS)
        require(code.isNotBlank())
        return when (val result = api.pair(normalized, code.trim(), cleanLabel)) {
            is ApiResult.Success -> {
                val paired = result.value
                if (paired is PairResult.Paired) {
                    mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
                    connectionStore.save(Connection(normalized, paired.label, paired.token))
                }
                result
            }
            is ApiResult.NotModified -> result
            is ApiResult.Failure -> result
        }
    }

    suspend fun snapshot(scope: Scope = selectedScope.value): ApiResult<SnapshotResponse> =
        withConnection { configured ->
            val result = api.snapshot(configured, scope)
            if (result is ApiResult.Success && connection.value == configured) {
                mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.from(result.value.device)
                authorizationConnection = configured
            }
            if (result is ApiResult.Success && !scope.viewAll && scope.session != null) {
                // A narrow snapshot omits its session tags; they are supplied by the request.
                result.copy(value = result.value.copy(
                    agents = result.value.agents.map { it.copy(session = it.session ?: scope.session) },
                    shellPanes = result.value.shellPanes.map { it.copy(session = it.session ?: scope.session) },
                ))
            } else result
        }

    /** Resolve the implicit local host for pane joins, while keeping other crew identities intact. */
    suspend fun paneSnapshot(scope: Scope): ApiResult<SnapshotResponse> {
        val result = snapshot(scope)
        if (result !is ApiResult.Success || scope.host != null) return result
        val lead = result.value.servers?.firstOrNull { it.isLead }?.id ?: return result
        return result.copy(value = result.value.copy(
            agents = result.value.agents.map { if (it.host == lead) it.copy(host = null) else it },
            shellPanes = result.value.shellPanes.map { if (it.host == lead) it.copy(host = null) else it },
            tabs = result.value.tabs.map { if (it.host == lead) it.copy(host = null) else it },
            workspaces = result.value.workspaces.map { if (it.host == lead) it.copy(host = null) else it },
        ))
    }

    suspend fun config(scope: Scope = selectedScope.value): ApiResult<BridgeConfigResponse> = withConnection { api.config(it, scope) }

    suspend fun refresh(scope: Scope = selectedScope.value): ApiResult<ActionResponse> =
        withConnection { api.refresh(it, scope) }

    suspend fun devices(): ApiResult<DevicesResponse> = withConnection(api::devices)

    suspend fun revokeDevice(label: String): ApiResult<DevicesResponse> = withWritableConnection {
        require(label.isNotBlank())
        api.revokeDevice(it, label)
    }

    suspend fun crew(): ApiResult<CrewStatusResponse> = withConnection(api::crew)

    suspend fun history(
        address: PaneAddress,
        limit: Int = 200,
        before: String? = null,
        etag: String? = null,
    ): ApiResult<PaneHistoryResponse> = withConnection {
        api.history(it, address, limit, before, etag)
    }

    suspend fun chat(address: PaneAddress, limit: Int = 200, after: ChatAfter? = null, before: ChatBefore? = null, etag: String? = null): ApiResult<PaneChatResponse> = withConnection {
        api.chat(it, address, limit, after, before, etag)
    }

    suspend fun startLaunch(scope: Scope, target: StartTarget, requestId: String, cwd: String? = null): ApiResult<CreateResponse> = withWritableConnection {
        api.startLaunch(it, scope, target, requestId, cwd)
    }

    suspend fun planWorktree(scope: Scope, cwd: String, branch: String? = null, parent: String? = null): ApiResult<WorktreePlanResponse> = withConnection {
        api.planWorktree(it, scope, cwd, branch, parent)
    }

    suspend fun createWorktreeAt(scope: Scope, cwd: String, branch: String, target: StartTarget, requestId: String, base: WorktreeBase = WorktreeBase(), folder: WorktreeFolder = WorktreeFolder()): ApiResult<WorktreeOpenResponse> = withWritableConnection {
        api.createWorktreeAt(it, scope, cwd, branch, target, requestId, base, folder)
    }

    suspend fun closePane(address: PaneAddress): ApiResult<ActionResponse> = withWritableConnection {
        api.closePane(it, address)
    }

    suspend fun focusPane(address: PaneAddress): ApiResult<ActionResponse> = withWritableConnection {
        api.focusPane(it, address)
    }

    suspend fun renamePane(address: PaneAddress, label: String): ApiResult<ActionResponse> =
        withWritableConnection { api.renamePane(it, address, label) }

    suspend fun renameTab(scope: Scope, tabId: String, label: String): ApiResult<ActionResponse> =
        withWritableConnection {
            require(tabId.isNotBlank() && label.isNotBlank())
            api.renameTab(it, scope, tabId, label)
        }

    suspend fun closeTab(scope: Scope, tabId: String): ApiResult<ActionResponse> = withWritableConnection {
        require(tabId.isNotBlank())
        api.closeTab(it, scope, tabId)
    }

    suspend fun createTab(
        scope: Scope,
        workspaceId: String,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection {
        require(workspaceId.isNotBlank())
        api.createTab(it, scope, workspaceId, label, cwd)
    }

    suspend fun createWorkspace(
        scope: Scope = selectedScope.value,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection {
        api.createWorkspace(it, scope, label, cwd)
    }

    suspend fun launchers(scope: Scope = selectedScope.value): ApiResult<LaunchersResponse> =
        withConnection { api.launchers(it, scope) }

    /** `command` must be a value returned by [launchers], never caller-authored shell text. */
    suspend fun launch(
        command: String,
        scope: Scope = selectedScope.value,
        besidePaneId: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection {
        require(command.isNotBlank())
        api.launch(it, scope, command, besidePaneId)
    }

    suspend fun listWorktrees(
        workspaceId: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeListResponse> = withConnection {
        require(workspaceId.isNotBlank())
        api.listWorktrees(it, scope, workspaceId)
    }

    suspend fun createWorktree(
        workspaceId: String,
        branch: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeOpenResponse> = withWritableConnection {
        require(workspaceId.isNotBlank() && branch.isNotBlank())
        api.createWorktree(it, scope, workspaceId, branch)
    }

    suspend fun openWorktree(
        workspaceId: String,
        path: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeOpenResponse> = withWritableConnection {
        require(workspaceId.isNotBlank() && path.isNotBlank())
        api.openWorktree(it, scope, workspaceId, path)
    }

    suspend fun upload(address: PaneAddress, upload: ImageUpload): ApiResult<UploadResponse> =
        withWritableConnection { api.upload(it, address, upload) }

    suspend fun transcribe(audio: AudioUpload): ApiResult<SttResponse> =
        withWritableConnection { api.transcribe(it, audio) }

    suspend fun notificationPreferences(): ApiResult<NotifyPreferences> =
        withConnection(api::notificationPreferences)

    suspend fun setNotificationPreferences(
        patch: NotifyPreferencesPatch,
    ): ApiResult<NotifyPreferences> = withConnection {
        api.setNotificationPreferences(it, patch)
    }

    suspend fun setNotificationSnooze(snoozedUntil: Long?): ApiResult<NotificationState> =
        withConnection { api.setNotificationSnooze(it, snoozedUntil) }

    suspend fun checkForUpdates(): ApiResult<UpdateInfo> = withConnection(api::checkForUpdates)

    suspend fun updateState(): ApiResult<UpdateCheckResponse> = withConnection(api::updateState)

    suspend fun standbyUpdate(): ApiResult<StandbyUpdateResponse> = withConnection(api::standbyUpdate)

    suspend fun startUpdate(
        target: String,
        major: Boolean,
        peersOnly: Boolean = false,
    ): ApiResult<UpdateStartResponse> = withWritableConnection {
        require(target.isNotBlank())
        api.startUpdate(it, target, major, peersOnly)
    }

    suspend fun snoozeUpdate(): ApiResult<UpdateInfo> = withConnection(api::snoozeUpdate)

    suspend fun readPane(
        address: PaneAddress,
        lines: Int = 600,
        markSeen: Boolean = true,
    ): ApiResult<PaneContent> {
        val configured = connection.value ?: return notConnected()
        val cacheKey = PaneCacheKey(configured, address)
        // A pane body is conditional only for the exact window that produced it. Reusing the
        // 600-line ETag for a later 1,000-line request can otherwise turn a valid grow into a 304
        // with the shorter cached body when a bridge keys ETags to pane revision rather than size.
        // Credential identity is part of the key so reconnecting cannot reuse another device's body.
        val cached = cacheMutex.withLock { paneCache[cacheKey] }?.takeIf { it.lines == lines }
        return when (val result = withConnectionOf(configured) { api.pane(it, address, lines, cached?.etag, markSeen) }) {
            is ApiResult.Success -> {
                val entry = CachedPane(result.value, result.etag, lines)
                cacheMutex.withLock {
                    if (connection.value !== configured) return ApiResult.Failure(ApiFailure.Protocol("Connection changed; refresh before trying again"))
                    paneCache[cacheKey] = entry
                }
                ApiResult.Success(
                    PaneContent(entry.pane, notModified = false, entry.etag),
                    result.status,
                    result.etag,
                    result.buildId,
                )
            }
            is ApiResult.NotModified -> if (cached == null) {
                ApiResult.Failure(ApiFailure.Protocol("Pane returned 304 without a matching scoped cache entry"))
            } else {
                ApiResult.Success(
                    PaneContent(cached.pane, notModified = true, cached.etag),
                    status = 304,
                    etag = cached.etag,
                )
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun reply(
        address: PaneAddress,
        text: String,
        submit: Boolean = true,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse> = withWritableConnection {
        require(text.isNotBlank())
        // Exactly one client call. Ambiguous terminal writes are never automatically retried.
        api.reply(it, address, text, submit, expectedPrompt)
    }

    /** Submit already-observed text with the operator's server-configured submit keys. */
    suspend fun submitReply(address: PaneAddress, expectedPrompt: String): ApiResult<ActionResponse> =
        withWritableConnection { api.reply(it, address, "", true, expectedPrompt) }

    suspend fun keys(
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse> = withWritableConnection {
        require(keys.isNotEmpty() && keys.all(MuxKeyGrammar::isValid))
        // Exactly one client call. Ambiguous terminal writes are never automatically retried.
        api.keys(it, address, keys, expectedPrompt)
    }

    suspend fun disconnect() {
        connectionStore.clear()
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        cacheMutex.withLock { paneCache.clear() }
        mutableScope.value = Scope()
    }

    fun demoteToReadOnly() {
        val current = connection.value ?: return
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        connectionStore.save(Connection(current.origin, current.label, null))
    }

    private suspend fun <T> withConnection(block: suspend (Connection) -> ApiResult<T>): ApiResult<T> {
        val configured = connection.value ?: return notConnected()
        return withConnectionOf(configured, block)
    }

    /** Keep the cached read and its credential on the same server across suspending cache locks. */
    private suspend fun <T> withConnectionOf(configured: Connection, block: suspend (Connection) -> ApiResult<T>): ApiResult<T> {
        if (connection.value !== configured) return ApiResult.Failure(ApiFailure.Protocol("Connection changed; refresh before trying again"))
        val result = block(configured)
        if (connection.value !== configured) return ApiResult.Failure(ApiFailure.Protocol("Connection changed; refresh before trying again"))
        if (result is ApiResult.Failure && result.error is ApiFailure.Http && result.error.status == 403 &&
            result.error.message in setOf("device not paired", "device expired") && connection.value === configured
        ) {
            demoteToReadOnly()
            cacheMutex.withLock { paneCache.clear() }
        }
        return result
    }

    private suspend fun <T> withWritableConnection(
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val configured = connection.value ?: return notConnected()
        if (!configured.isPaired) {
            return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        }
        if (!writesAllowed()) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
        return withConnectionOf(configured, block)
    }

    private fun <T> notConnected(): ApiResult<T> =
        ApiResult.Failure(ApiFailure.Protocol("No Collie connection is configured"))

    private data class CachedPane(
        val pane: PaneReadResponse,
        val etag: String?,
        val lines: Int,
    )

    private data class PaneCacheKey(
        val connection: Connection,
        val address: PaneAddress,
    )

}
