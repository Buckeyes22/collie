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
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.ControlOperationResponse
import com.lateapex.collie.ui.ReadSourceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicLong
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

    fun writesAllowed(scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject): Boolean {
        val configured = connection.value ?: return false
        val live = mutableReadSource.value as? ReadSourceState.Live ?: return false
        return configured.isPaired && authorizationConnection === configured &&
            mutableDeviceWriteAuthorization.value.permitsWrites &&
            live.key.origin == configured.origin.value && live.key.connectionGeneration == generation.get() &&
            live.key.leadEpoch == leadEpoch && live.key.host == scope.host && live.key.session == scope.session &&
            (activeSubject == null || live.key.subjectIdentity == activeSubject) &&
            (subjectIdentity == null || activeSubject == null || subjectIdentity == activeSubject)
    }

    private val mutableScope = MutableStateFlow(Scope())
    val selectedScope: StateFlow<Scope> = mutableScope.asStateFlow()

    private val generation = AtomicLong(0)
    private val navigationGeneration = AtomicLong(0)
    @Volatile private var generationConnection: Connection? = connection.value
    @Volatile private var leadEpoch: Long = 0
    @Volatile private var observedLeadAuthority: String? = null
    @Volatile var accessFailure: ApiFailure? = null
        private set
    @Volatile private var activeSubject: String? = null
    private val scopeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val readAdmissionLock = Any()
    /** Suspending seam for deterministic admission-race tests; production remains a no-op. */
    internal var beforeReadAdmissionForTesting: suspend () -> Unit = {}
    private val authorityInvalidations = java.util.concurrent.CopyOnWriteArrayList<(AuthorityInvalidation) -> Unit>()
    private val mutableReadSource = MutableStateFlow<ReadSourceState>(ReadSourceState.Unavailable(0))
    val readSource: StateFlow<ReadSourceState> = mutableReadSource.asStateFlow()
    private val refreshMutex = Mutex()
    private val refreshes = mutableMapOf<Pair<ScopedRequestKey, Long>, kotlinx.coroutines.CompletableDeferred<ApiResult<ActionResponse>>>()
    private val cacheLock = Any()
    private val paneCache = object : LinkedHashMap<PaneCacheKey, CachedPane>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PaneCacheKey, CachedPane>?): Boolean =
            size > maxPaneCacheEntries
    }

    init {
        require(maxPaneCacheEntries > 0)
    }

    fun selectScope(scope: Scope) {
        if (mutableScope.value == scope) return
        invalidateAuthority(AuthorityInvalidation.SCOPE_CHANGED)
        mutableScope.value = scope
        activeSubject = null
    }

    /** Screen owners select one lightweight target; replacement invalidates all old actors. */
    fun selectSubject(scope: Scope, subjectIdentity: String?) {
        if (selectedScope.value == scope && activeSubject == subjectIdentity) return
        invalidateAuthority(AuthorityInvalidation.SCOPE_CHANGED)
        mutableScope.value = scope
        activeSubject = subjectIdentity
    }

    /** Sensitive feature custody retires synchronously; callbacks receive no credential. */
    fun onAuthorityInvalidated(listener: (AuthorityInvalidation) -> Unit): AutoCloseable {
        authorityInvalidations.add(listener)
        return AutoCloseable { authorityInvalidations.remove(listener) }
    }

    @Synchronized
    fun readContext(scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject): ScopedReadContext? {
        val configured = connection.value ?: return null
        if (generationConnection !== configured) {
            generationConnection = configured
            generation.incrementAndGet()
            observedLeadAuthority = null
            invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED)
        }
        return ScopedReadContext(ScopedRequestKey(configured.origin.value, generation.get(), credentialIdentity(configured),
            leadEpoch, scope.host, scope.session, subjectIdentity), configured, navigationGeneration.get())
    }

    /** Local custody identity only: stable across recreation and different for replacement credentials. */
    private fun credentialIdentity(configured: Connection): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("collie.native.custody.v1\u0000".toByteArray(Charsets.UTF_8))
        for (value in listOf(configured.origin.value, configured.token.orEmpty())) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun isCurrent(context: ScopedReadContext): Boolean = connection.value === context.connection &&
        generation.get() == context.key.connectionGeneration && navigationGeneration.get() == context.navigationGeneration &&
        leadEpoch == context.key.leadEpoch &&
        (activeSubject == null || context.key.subjectIdentity == null || activeSubject == context.key.subjectIdentity)

    fun setLeadEpoch(epoch: Long) {
        if (leadEpoch == epoch) return
        leadEpoch = epoch
        invalidateAuthority(AuthorityInvalidation.LEAD_CHANGED)
    }

    fun showSavedCopy(capturedAt: Long) {
        invalidateAuthority(AuthorityInvalidation.SAVED_COPY)
        mutableReadSource.value = ReadSourceState.SavedCopy(capturedAt, generation.get())
    }

    private fun invalidateAuthority(reason: AuthorityInvalidation) {
        val jobs = synchronized(readAdmissionLock) {
            navigationGeneration.incrementAndGet()
            scopeJobs.toList()
        }
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        mutableReadSource.value = ReadSourceState.Unavailable(generation.get())
        jobs.forEach { it.cancel() }
        if (reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) {
            synchronized(cacheLock) { paneCache.clear() }
        }
        authorityInvalidations.forEach { listener -> runCatching { listener(reason) } }
    }

    fun connectReadOnly(origin: String, label: String): Connection {
        val cleanLabel = DeviceLabel.normalize(label)
        require(cleanLabel.isNotEmpty() && cleanLabel.length <= DeviceLabel.MAX_CHARS)
        val connection = Connection(originValidator.requireValid(origin), cleanLabel, null)
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED)
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
                    invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED)
                    connectionStore.save(Connection(normalized, paired.label, paired.token))
                }
                result
            }
            is ApiResult.NotModified -> result
            is ApiResult.Failure -> result
        }
    }

    suspend fun snapshot(scope: Scope = selectedScope.value, expectedContext: ScopedReadContext? = null): ApiResult<SnapshotResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (context.key.host != scope.host || context.key.session != scope.session || !isCurrent(context)) return staleScope()
        val result = withConnection(scope, context.key.subjectIdentity, context) { configured -> api.snapshot(configured, scope) }
        if (result !is ApiResult.Success) return result
        if (!isCurrent(context)) return staleScope()
        val authority = result.value.controlAuthority?.let { "${it.leadId}/${it.epoch}" }
            ?: result.value.servers?.firstOrNull { it.isLead }?.id
        if (authority != null) {
            val previous = observedLeadAuthority
            observedLeadAuthority = authority
            if (previous != null && previous != authority) {
                setLeadEpoch(leadEpoch + 1)
                return staleScope()
            }
        }
        accessFailure = null
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.from(result.value.device)
        authorizationConnection = context.connection
        mutableReadSource.value = ReadSourceState.Live(context.key, result.value.ts)
        return if (!scope.viewAll && scope.session != null) {
            result.copy(value = result.value.copy(
                agents = result.value.agents.map { it.copy(session = it.session ?: scope.session) },
                shellPanes = result.value.shellPanes.map { it.copy(session = it.session ?: scope.session) },
            ))
        } else result
    }

    /** Resolve the implicit local host for pane joins, while keeping other crew identities intact. */
    suspend fun paneSnapshot(scope: Scope, expectedContext: ScopedReadContext? = null): ApiResult<SnapshotResponse> {
        val result = snapshot(scope, expectedContext)
        if (result !is ApiResult.Success || scope.host != null) return result
        val lead = result.value.servers?.firstOrNull { it.isLead }?.id ?: return result
        return result.copy(value = result.value.copy(
            agents = result.value.agents.map { if (it.host == lead) it.copy(host = null) else it },
            shellPanes = result.value.shellPanes.map { if (it.host == lead) it.copy(host = null) else it },
            tabs = result.value.tabs.map { if (it.host == lead) it.copy(host = null) else it },
            workspaces = result.value.workspaces.map { if (it.host == lead) it.copy(host = null) else it },
        ))
    }

    suspend fun config(scope: Scope = selectedScope.value): ApiResult<BridgeConfigResponse> = withConnection(scope) { api.config(it, scope) }

    suspend fun refresh(
        scope: Scope = selectedScope.value,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<ActionResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (context.key.host != scope.host || context.key.session != scope.session || !isCurrent(context)) {
            return staleScope()
        }
        val refreshKey = context.key to context.navigationGeneration
        var owns = false
        val pending = refreshMutex.withLock {
            refreshes[refreshKey] ?: kotlinx.coroutines.CompletableDeferred<ApiResult<ActionResponse>>().also {
                refreshes[refreshKey] = it
                owns = true
            }
        }
        if (!owns) return pending.await().takeIf { isCurrent(context) } ?: staleScope()
        try {
            val result = withConnectionOf(context.connection, scope, context.key.subjectIdentity, expectedContext = context) { api.refresh(it, scope) }
            val admittedResult = if (isCurrent(context)) result else staleScope()
            pending.complete(admittedResult)
            return admittedResult
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                refreshMutex.withLock { refreshes.remove(refreshKey) }
            }
        }
    }

    suspend fun controlOperation(scope: Scope, requestId: String): ApiResult<ControlOperationResponse> = withConnection(scope) {
        val config = api.config(it, scope)
        if (config is ApiResult.Failure) return@withConnection config
        if (config !is ApiResult.Success || config.value.control?.supportsNew("controlOperations") != true) {
            ApiResult.Failure(ApiFailure.Http(404, "Control operations are unsupported on this server", "capability.unsupported"))
        } else api.controlOperation(it, scope, requestId)
    }

    suspend fun devices(): ApiResult<DevicesResponse> = withConnection(block = api::devices)

    suspend fun revokeDevice(label: String): ApiResult<DevicesResponse> = withWritableConnection {
        require(label.isNotBlank())
        api.revokeDevice(it, label)
    }

    suspend fun crew(): ApiResult<CrewStatusResponse> = withConnection(block = api::crew)

    suspend fun history(
        address: PaneAddress,
        limit: Int = 200,
        before: String? = null,
        etag: String? = null,
    ): ApiResult<PaneHistoryResponse> = withConnection(address.scope, address.paneId) {
        api.history(it, address, limit, before, etag)
    }

    suspend fun chat(address: PaneAddress, limit: Int = 200, after: ChatAfter? = null, before: ChatBefore? = null, etag: String? = null): ApiResult<PaneChatResponse> = withConnection(address.scope, address.paneId) {
        api.chat(it, address, limit, after, before, etag)
    }

    suspend fun startLaunch(scope: Scope, target: StartTarget, requestId: String, cwd: String? = null): ApiResult<CreateResponse> = withWritableConnection(scope) {
        api.startLaunch(it, scope, target, requestId, cwd)
    }

    suspend fun planWorktree(scope: Scope, cwd: String, branch: String? = null, parent: String? = null): ApiResult<WorktreePlanResponse> = withConnection(scope) {
        api.planWorktree(it, scope, cwd, branch, parent)
    }

    suspend fun createWorktreeAt(scope: Scope, cwd: String, branch: String, target: StartTarget, requestId: String, base: WorktreeBase = WorktreeBase(), folder: WorktreeFolder = WorktreeFolder()): ApiResult<WorktreeOpenResponse> = withWritableConnection(scope) {
        api.createWorktreeAt(it, scope, cwd, branch, target, requestId, base, folder)
    }

    suspend fun closePane(address: PaneAddress): ApiResult<ActionResponse> = withWritableConnection(address.scope, address.paneId) {
        api.closePane(it, address)
    }

    suspend fun focusPane(address: PaneAddress): ApiResult<ActionResponse> = withWritableConnection(address.scope, address.paneId) {
        api.focusPane(it, address)
    }

    suspend fun renamePane(address: PaneAddress, label: String): ApiResult<ActionResponse> =
        withWritableConnection(address.scope, address.paneId) { api.renamePane(it, address, label) }

    suspend fun renameTab(scope: Scope, tabId: String, label: String): ApiResult<ActionResponse> =
        withWritableConnection(scope) {
            require(tabId.isNotBlank() && label.isNotBlank())
            api.renameTab(it, scope, tabId, label)
        }

    suspend fun closeTab(scope: Scope, tabId: String): ApiResult<ActionResponse> = withWritableConnection(scope) {
        require(tabId.isNotBlank())
        api.closeTab(it, scope, tabId)
    }

    suspend fun createTab(
        scope: Scope,
        workspaceId: String,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection(scope) {
        require(workspaceId.isNotBlank())
        api.createTab(it, scope, workspaceId, label, cwd)
    }

    suspend fun createWorkspace(
        scope: Scope = selectedScope.value,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection(scope) {
        api.createWorkspace(it, scope, label, cwd)
    }

    suspend fun launchers(scope: Scope = selectedScope.value): ApiResult<LaunchersResponse> =
        withConnection(scope) { api.launchers(it, scope) }

    /** `command` must be a value returned by [launchers], never caller-authored shell text. */
    suspend fun launch(
        command: String,
        scope: Scope = selectedScope.value,
        besidePaneId: String? = null,
    ): ApiResult<CreateResponse> = withWritableConnection(scope) {
        require(command.isNotBlank())
        api.launch(it, scope, command, besidePaneId)
    }

    suspend fun listWorktrees(
        workspaceId: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeListResponse> = withConnection(scope) {
        require(workspaceId.isNotBlank())
        api.listWorktrees(it, scope, workspaceId)
    }

    suspend fun createWorktree(
        workspaceId: String,
        branch: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeOpenResponse> = withWritableConnection(scope) {
        require(workspaceId.isNotBlank() && branch.isNotBlank())
        api.createWorktree(it, scope, workspaceId, branch)
    }

    suspend fun openWorktree(
        workspaceId: String,
        path: String,
        scope: Scope = selectedScope.value,
    ): ApiResult<WorktreeOpenResponse> = withWritableConnection(scope) {
        require(workspaceId.isNotBlank() && path.isNotBlank())
        api.openWorktree(it, scope, workspaceId, path)
    }

    suspend fun upload(address: PaneAddress, upload: ImageUpload, context: ScopedReadContext? = null): ApiResult<UploadResponse> =
        withWritableConnection(address.scope, address.paneId) {
            if (context != null && (!isCurrent(context) || it !== context.connection)) staleScope() else api.upload(it, address, upload)
        }

    suspend fun transcribe(audio: AudioUpload, context: ScopedReadContext? = null): ApiResult<SttResponse> =
        withWritableConnection {
            if (context != null && (!isCurrent(context) || it !== context.connection)) staleScope() else api.transcribe(it, audio)
        }

    suspend fun notificationPreferences(): ApiResult<NotifyPreferences> =
        withConnection(block = api::notificationPreferences)

    suspend fun setNotificationPreferences(
        patch: NotifyPreferencesPatch,
    ): ApiResult<NotifyPreferences> = withDeviceOwnedConnection {
        api.setNotificationPreferences(it, patch)
    }

    suspend fun setNotificationSnooze(snoozedUntil: Long?): ApiResult<NotificationState> =
        withDeviceOwnedConnection { api.setNotificationSnooze(it, snoozedUntil) }

    suspend fun checkForUpdates(): ApiResult<UpdateInfo> = withConnection(block = api::checkForUpdates)

    suspend fun updateState(): ApiResult<UpdateCheckResponse> = withConnection(block = api::updateState)

    suspend fun standbyUpdate(): ApiResult<StandbyUpdateResponse> = withConnection(block = api::standbyUpdate)

    suspend fun startUpdate(
        target: String,
        major: Boolean,
        peersOnly: Boolean = false,
    ): ApiResult<UpdateStartResponse> = withWritableConnection {
        require(target.isNotBlank())
        api.startUpdate(it, target, major, peersOnly)
    }

    suspend fun snoozeUpdate(): ApiResult<UpdateInfo> = withDeviceOwnedConnection(block = api::snoozeUpdate)

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
        val cached = synchronized(cacheLock) { paneCache[cacheKey] }?.takeIf { it.lines == lines }
        return when (val result = withConnectionOf(configured, address.scope, address.paneId) { api.pane(it, address, lines, cached?.etag, markSeen) }) {
            is ApiResult.Success -> {
                val pane = result.value
                val served = pane.servedLines
                if ((pane.requestedLines != null && pane.requestedLines != lines) ||
                    (served != null && (served !in 0..lines || served != pane.text.rowCount())) ||
                    (pane.readableLines != null && pane.readableLines !in 0..100_000) ||
                    (pane.limitReason != null && pane.limitReason !in setOf("adapter-ceiling", "server-ceiling", "available-depth"))
                ) return ApiResult.Failure(ApiFailure.Protocol("Pane read returned inconsistent bounded-read metadata"))
                val entry = CachedPane(result.value, result.etag, lines)
                synchronized(cacheLock) {
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

    private fun String.rowCount(): Int =
        if (isEmpty()) 0 else split('\n').let { rows -> rows.size - if (endsWith('\n')) 1 else 0 }

    suspend fun reply(
        address: PaneAddress,
        text: String,
        submit: Boolean = true,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse> = withWritableConnection(address.scope, address.paneId) {
        require(text.isNotBlank())
        // Exactly one client call. Ambiguous terminal writes are never automatically retried.
        api.reply(it, address, text, submit, expectedPrompt)
    }

    /** Submit already-observed text with the operator's server-configured submit keys. */
    suspend fun submitReply(address: PaneAddress, expectedPrompt: String): ApiResult<ActionResponse> =
        withWritableConnection(address.scope, address.paneId) { api.reply(it, address, "", true, expectedPrompt) }

    suspend fun keys(
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String? = null,
    ): ApiResult<ActionResponse> = withWritableConnection(address.scope, address.paneId) {
        require(keys.isNotEmpty() && keys.all(MuxKeyGrammar::isValid))
        // Exactly one client call. Ambiguous terminal writes are never automatically retried.
        api.keys(it, address, keys, expectedPrompt)
    }

    suspend fun disconnect() {
        invalidateAuthority(AuthorityInvalidation.DISCONNECTED)
        connectionStore.clear()
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        synchronized(cacheLock) { paneCache.clear() }
        mutableScope.value = Scope()
    }

    fun demoteToReadOnly() {
        val current = connection.value ?: return
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        connectionStore.save(Connection(current.origin, current.label, null))
    }

    private suspend fun <T> withConnection(scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject, expectedContext: ScopedReadContext? = null, block: suspend (Connection) -> ApiResult<T>): ApiResult<T> {
        val context = expectedContext ?: readContext(scope, subjectIdentity) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != scope.host || context.key.session != scope.session ||
            (subjectIdentity != null && context.key.subjectIdentity != subjectIdentity)) return staleScope()
        return withConnectionOf(context.connection, scope, context.key.subjectIdentity, expectedContext = context, block = block)
    }

    /** Child jobs cancel reads; writes discard stale replies without claiming dispatch failed. */
    private suspend fun <T> withConnectionOf(configured: Connection, scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject, mutation: Boolean = false, expectedContext: ScopedReadContext? = null, block: suspend (Connection) -> ApiResult<T>): ApiResult<T> = try { coroutineScope {
        val context = expectedContext ?: readContext(scope, subjectIdentity) ?: return@coroutineScope notConnected()
        if (!isCurrent(context) || context.connection !== configured || context.key.host != scope.host || context.key.session != scope.session ||
            (subjectIdentity != null && context.key.subjectIdentity != subjectIdentity)) return@coroutineScope staleScope()
        val coroutineContext = currentCoroutineContext()
        val job = coroutineContext[Job]!!
        if (!mutation) {
            beforeReadAdmissionForTesting()
            val admitted = synchronized(readAdmissionLock) {
                if (!isCurrent(context)) false else {
                    scopeJobs.add(job)
                    true
                }
            }
            if (!admitted) return@coroutineScope staleScope()
            coroutineContext.ensureActive()
        }
        try {
            val result = block(configured)
            if (!isCurrent(context)) return@coroutineScope staleScope()
            val responseAuthority = when (result) {
                is ApiResult.Success -> result.controlAuthority
                is ApiResult.NotModified -> result.controlAuthority
                is ApiResult.Failure -> null
            }
            if (responseAuthority != null) {
                val authority = "${responseAuthority.leadId}/${responseAuthority.epoch}"
                val previous = observedLeadAuthority
                observedLeadAuthority = authority
                if (previous != null && previous != authority) {
                    synchronized(readAdmissionLock) { scopeJobs.remove(job) }
                    setLeadEpoch(leadEpoch + 1)
                    return@coroutineScope staleScope()
                }
            }
            if (result is ApiResult.Failure) {
                mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
                mutableReadSource.value = ReadSourceState.Unavailable(context.key.connectionGeneration)
                accessFailure = result.error
                if (result.error is ApiFailure.Http && result.error.status in setOf(401, 403) && !result.error.retiresPairing()) {
                    synchronized(readAdmissionLock) { scopeJobs.remove(job) }
                    invalidateAuthority(AuthorityInvalidation.ACCESS_FROZEN)
                } else authorityInvalidations.forEach { listener -> runCatching { listener(AuthorityInvalidation.ACCESS_FROZEN) } }
                if (result.error is ApiFailure.Http && result.error.retiresPairing()) {
                    synchronized(readAdmissionLock) { scopeJobs.remove(job) }
                    invalidateAuthority(AuthorityInvalidation.PAIRING_RETIRED)
                    demoteToReadOnly()
                }
            }
            result
        } finally { synchronized(readAdmissionLock) { scopeJobs.remove(job) } }
    } } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // A cancelled request child must not kill its screen's poll loop. External screen/lifecycle
        // cancellation still propagates normally; only an authority invalidation becomes a discard.
        currentCoroutineContext().ensureActive()
        staleScope()
    }

    private fun <T> staleScope(): ApiResult<T> = ApiResult.Failure(ApiFailure.Protocol("Connection or scope changed; refresh before trying again"))

    /** Device-owned preferences keep read admission; saved/offline authority cannot dispatch writes. */
    private suspend fun <T> withDeviceOwnedConnection(block: suspend (Connection) -> ApiResult<T>): ApiResult<T> {
        if (readSource.value is ReadSourceState.SavedCopy ||
            (accessFailure as? ApiFailure.Http)?.status in setOf(401, 403)) return staleScope()
        val configured = connection.value ?: return notConnected()
        return withConnectionOf(configured, mutation = true, block = block)
    }

    private suspend fun <T> withWritableConnection(
        scope: Scope = selectedScope.value,
        subjectIdentity: String? = activeSubject,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val configured = connection.value ?: return notConnected()
        if (!configured.isPaired) {
            return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        }
        if (!writesAllowed(scope, subjectIdentity)) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
        return withConnectionOf(configured, scope, subjectIdentity, mutation = true, block = block)
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
