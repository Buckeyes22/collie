package com.lateapex.collie.data

import com.lateapex.collie.domain.DeviceLabel
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.MuxKeyGrammar
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AddedLauncherResponse
import com.lateapex.collie.network.AudioUpload
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.FoldersResponse
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.ImageUpload
import com.lateapex.collie.network.UploadSource
import com.lateapex.collie.network.ImageBlob
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.NotificationState
import com.lateapex.collie.network.NotifyPreferences
import com.lateapex.collie.network.NotifyPreferencesPatch
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.CrewStatusResponse
import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.QueuedSendGuard
import com.lateapex.collie.network.isCanonicalQueuedSendIdentity
import com.lateapex.collie.network.StartTarget
import com.lateapex.collie.network.WorktreeBase
import com.lateapex.collie.network.WorktreeFolder
import com.lateapex.collie.network.WorktreePlanResponse
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneHistoryResponse
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneProcessesResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.StandbyUpdateResponse
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdateDismissalScope
import com.lateapex.collie.network.UpdateInfo
import com.lateapex.collie.network.UpdateStartResponse
import com.lateapex.collie.network.UploadResponse
import com.lateapex.collie.network.WorktreeListResponse
import com.lateapex.collie.network.WorktreeOpenResponse
import com.lateapex.collie.network.WorktreeAtRequest
import com.lateapex.collie.network.BranchRequest
import com.lateapex.collie.network.StartLaunchRequest
import com.lateapex.collie.network.StartRunRequest
import com.lateapex.collie.network.LaunchCheckResponse
import com.lateapex.collie.network.RecentRunsResponse
import com.lateapex.collie.network.AddedLauncherMutationResponse
import com.lateapex.collie.network.RecipeWire
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.ControlOperationResponse
import com.lateapex.collie.network.ChangeCommitAnswer
import com.lateapex.collie.network.ChangeCommitDiffAnswer
import com.lateapex.collie.network.ChangeCommitHistoryAnswer
import com.lateapex.collie.network.ChangeDiffAnswer
import com.lateapex.collie.network.ChangesListAnswer
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
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
    private val launchIntentStore: LaunchIntentStore? = null,
    private val intentJson: Json = Json { explicitNulls = false; encodeDefaults = false },
) {
    private companion object {
        const val OUTCOME_UNKNOWN_CODE = "launch.outcome_unknown"
        val PRE_DISPATCH_REFUSAL_CODES = setOf(
            "launch.request_mismatch",
            "launch.receipts_unavailable",
            "launch.not_allowlisted",
            "launch.unknown_harness",
            "launch.bad_folder",
            "launch.folder_missing",
            "launch.no_device",
            "launch.run_off",
            "launch.bad_line",
            "launch.run_no_branch",
            "launcher.adds_off",
            "launcher.free_text_off",
            "launcher.bad_recipe",
            "launcher.duplicate",
            "launcher.added_full",
            "launcher.store_unreadable",
            "launcher.no_device",
            "worktree.invalid_branch",
            "worktree.invalid_base",
            "worktree.not_a_repo",
            "worktree.folder_invalid",
            "worktree.folder_missing",
            "worktree.folder_link",
            "worktree.folder_outside_home",
            "worktree.folder_hidden",
            "worktree.folder_in_repo",
            "worktree.target_exists",
            "worktree.branch_required",
        )
    }

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
    private val readContextInvalidations = java.util.concurrent.CopyOnWriteArrayList<(AuthorityInvalidation) -> Unit>()
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

    /** Immutable authority-retirement events only; ordinary failed reads do not invalidate scope. */
    fun onReadContextInvalidated(listener: (AuthorityInvalidation) -> Unit): AutoCloseable {
        readContextInvalidations.add(listener)
        return AutoCloseable { readContextInvalidations.remove(listener) }
    }

    @Synchronized
    fun readContext(scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject): ScopedReadContext? {
        val configured = connection.value ?: return null
        val changed = synchronized(readAdmissionLock) {
            if (generationConnection === configured) false else {
                generationConnection = configured
                generation.incrementAndGet()
                observedLeadAuthority = null
                true
            }
        }
        // Invalidation listeners may acknowledge a main-thread custody wipe. Never invoke them
        // while holding the admission lock or a connection monitor.
        if (changed) invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED)
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

    /** Serialize a bounded local encrypted-custody ACK against authority retirement. No network,
     * coroutine cancellation or invalidation listener may run inside this block. */
    internal fun acknowledgeLocalCustody(context: ScopedReadContext, acknowledgement: () -> Boolean): Boolean =
        synchronized(readAdmissionLock) { isCurrent(context) && acknowledgement() }

    fun setLeadEpoch(epoch: Long) {
        val changed = synchronized(readAdmissionLock) {
            if (leadEpoch == epoch) false else { leadEpoch = epoch; true }
        }
        if (changed) invalidateAuthority(AuthorityInvalidation.LEAD_CHANGED)
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
        readContextInvalidations.forEach { listener -> runCatching { listener(reason) } }
        if (reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) {
            synchronized(cacheLock) { paneCache.clear() }
        }
        if (reason in setOf(AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) {
            runCatching { launchIntentStore?.clear() }
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

    suspend fun snapshot(
        scope: Scope = selectedScope.value,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<SnapshotResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        val result = withConnectionOf(
            context.connection, scope, context.key.subjectIdentity, expectedContext = context,
        ) { configured -> api.snapshot(configured, scope) }
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

    suspend fun config(scope: Scope = selectedScope.value, expectedContext: ScopedReadContext? = null): ApiResult<BridgeConfigResponse> = withConnection(scope, expectedContext?.key?.subjectIdentity ?: activeSubject, expectedContext) { api.config(it, scope) }

    private fun <T> configFailure(result: ApiResult<BridgeConfigResponse>): ApiResult<T> = when (result) {
        is ApiResult.Failure -> result
        is ApiResult.NotModified -> result
        is ApiResult.Success -> ApiResult.Failure(ApiFailure.Protocol("Unexpected config result"))
    }

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

    suspend fun controlOperation(scope: Scope, requestId: String, expectedContext: ScopedReadContext? = null): ApiResult<ControlOperationResponse> {
        val subject = expectedContext?.key?.subjectIdentity ?: activeSubject
        val context = expectedContext ?: readContext(scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != scope.host || context.key.session != scope.session || context.key.subjectIdentity != subject) return staleScope()
        val capability = config(scope, context)
        if (!isCurrent(context)) return staleScope()
        if (capability !is ApiResult.Success) return configFailure(capability)
        if (capability.value.control?.supportsNew("controlOperations") != true) return ApiResult.Failure(ApiFailure.Http(404, "Control operations are unsupported on this server", "capability.unsupported"))
        return withScopedLayoutRead(scope, context) { api.controlOperation(it, scope, requestId) }
    }

    suspend fun paneLayout(address: PaneAddress, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutReadResponse> {
        val context = expectedContext ?: readContext(address.scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != address.scope.host || context.key.session != address.scope.session) return staleScope()
        val config = config(address.scope, context)
        if (!isCurrent(context)) return staleScope()
        if (config !is ApiResult.Success) return configFailure(config)
        if (config.value.mux?.supportsNew("paneLayout") != true) return ApiResult.Failure(ApiFailure.Http(404, "Pane layout is unsupported on this server", "capability.unsupported"))
        return withScopedLayoutRead(address.scope, context) { api.paneLayout(it, address) }
    }

    suspend fun paneLayoutRead(address: PaneAddress, kind: String, direction: String? = null, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutReadResponse> {
        val context = expectedContext ?: readContext(address.scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != address.scope.host || context.key.session != address.scope.session) return staleScope()
        val config = config(address.scope, context)
        if (!isCurrent(context)) return staleScope()
        if (config !is ApiResult.Success) return configFailure(config)
        if (config.value.mux?.supportsNew("paneLayout") != true) return ApiResult.Failure(ApiFailure.Http(404, "Pane layout inspection is unsupported on this server", "capability.unsupported"))
        return withScopedLayoutRead(address.scope, context) { api.paneLayoutRead(it, address, kind, direction) }
    }

    suspend fun folders(scope: Scope = selectedScope.value, expectedContext: ScopedReadContext? = null): ApiResult<FoldersResponse> =
        withScopedLayoutRead(scope, expectedContext) { api.folders(it, scope) }

    suspend fun planPaneLayout(address: PaneAddress, action: kotlinx.serialization.json.JsonObject, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutPlanResponse> {
        val subject = expectedContext?.key?.subjectIdentity ?: activeSubject
        val context = expectedContext ?: readContext(address.scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != address.scope.host || context.key.session != address.scope.session || context.key.subjectIdentity != subject) return staleScope()
        val config = config(address.scope, context)
        if (!isCurrent(context)) return staleScope()
        if (config !is ApiResult.Success) return configFailure(config)
        val muxCapabilities = config.value.mux
        val kind = action["kind"]?.toString()?.trim('"').orEmpty()
        val capability = when (kind) {
            "split" -> "paneSplit"; "resize" -> "paneResize"; "zoom" -> "paneZoom"; "swap" -> "paneSwap"; "move" -> "paneMove"; "focus-direction" -> "paneDirectionalFocus"; else -> ""
        }
        if (capability.isEmpty() || config.value.control?.supportsNew("controlOperations") != true || muxCapabilities?.supportsNew("paneLayout") != true || muxCapabilities?.supportsNew(capability) != true) return ApiResult.Failure(ApiFailure.Http(404, "This pane layout action is unsupported on this server", "capability.unsupported"))
        return withScopedLayoutRead(address.scope, context) { api.planPaneLayout(it, address, action) }
    }

    suspend fun applyPaneLayout(address: PaneAddress, requestId: String, planToken: String, action: kotlinx.serialization.json.JsonObject, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutOperationResponse> {
        val subject = expectedContext?.key?.subjectIdentity ?: activeSubject
        val context = expectedContext ?: readContext(address.scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != address.scope.host || context.key.session != address.scope.session || context.key.subjectIdentity != subject) return staleScope()
        val config = config(address.scope, context)
        if (!isCurrent(context)) return staleScope()
        if (config !is ApiResult.Success) return configFailure(config)
        if (!writesAllowed(address.scope, subject)) return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        if (config.value.control?.supportsNew("controlOperations") != true || config.value.mux?.supportsNew("paneLayout") != true) return ApiResult.Failure(ApiFailure.Http(404, "Durable layout operations are unsupported on this server", "capability.unsupported"))
        return withScopedLayoutWrite(address.scope, subject, context) { api.applyPaneLayout(it, address, requestId, planToken, action) }
    }

    suspend fun devices(): ApiResult<DevicesResponse> = withConnection(block = api::devices)

    suspend fun revokeDevice(label: String): ApiResult<DevicesResponse> {
        require(label.isNotBlank())
        val configured = connection.value
        val result = withWritableConnection { api.revokeDevice(it, label) }
        if (result is ApiResult.Success && result.value.currentExpired && configured != null && connection.value === configured) {
            invalidateAuthority(AuthorityInvalidation.PAIRING_RETIRED)
            demoteToReadOnly()
        }
        return result
    }

    suspend fun crew(): ApiResult<CrewStatusResponse> = withConnection(block = api::crew)

    suspend fun history(
        address: PaneAddress,
        limit: Int = 200,
        before: String? = null,
        etag: String? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<PaneHistoryResponse> = withConnection(address.scope, address.paneId, expectedContext) {
        api.history(it, address, limit, before, etag)
    }

    /** Read one same-owner journal image under the exact authority captured by its renderer. */
    suspend fun imageBlob(context: ScopedReadContext, sha256: String): ApiResult<ImageBlob> {
        if (!sha256.matches(Regex("[0-9a-fA-F]{64}")) || !isCurrent(context)) return staleScope()
        val scope = Scope(context.key.host, context.key.session)
        return withConnectionOf(
            context.connection,
            scope,
            context.key.subjectIdentity,
            expectedContext = context,
        ) { api.imageBlob(it, scope, sha256) }
    }

    suspend fun chat(
        address: PaneAddress,
        limit: Int = 200,
        after: ChatAfter? = null,
        before: ChatBefore? = null,
        etag: String? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<PaneChatResponse> = withConnection(address.scope, address.paneId, expectedContext) {
        api.chat(it, address, limit, after, before, etag)
    }

    suspend fun paneProcesses(address: PaneAddress): ApiResult<PaneProcessesResponse> = withConnection(address.scope, address.paneId) { configured ->
        when (val config = api.config(configured, address.scope)) {
            is ApiResult.Failure -> config
            is ApiResult.NotModified -> ApiResult.Failure(ApiFailure.Protocol("Process capability was not verified"))
            is ApiResult.Success -> if (config.value.mux?.supportsNew("paneProcesses") != true) {
                ApiResult.Failure(ApiFailure.Http(501, "Process inspection is unsupported on this runtime", "capability.unsupported"))
            } else api.paneProcesses(configured, address)
        }
    }
    /** Ordinary paired reads. Change paths remain opaque; the bridge validates repository membership. */
    suspend fun changes(target: ChangesTarget, scope: Scope, lookup: ChangesLookup, expectedContext: ScopedReadContext? = null): ApiResult<ChangesListAnswer> =
        withConnection(scope, target.id, expectedContext) { api.changes(it, target, scope, lookup) }

    suspend fun changeDiff(target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String, expectedContext: ScopedReadContext? = null): ApiResult<ChangeDiffAnswer> =
        withConnection(scope, target.id, expectedContext) { api.changeDiff(it, target, scope, lookup, repo, path) }

    suspend fun changeCommitHistory(target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, head: String?, offset: Int, limit: Int, expectedContext: ScopedReadContext? = null): ApiResult<ChangeCommitHistoryAnswer> =
        withConnection(scope, target.id, expectedContext) { api.changeCommitHistory(it, target, scope, lookup, repo, head, offset, limit) }

    suspend fun changeCommit(target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String? = null, expectedContext: ScopedReadContext? = null): ApiResult<ChangeCommitAnswer> =
        withConnection(scope, target.id, expectedContext) { api.changeCommit(it, target, scope, lookup, repo, commitHash) }

    suspend fun changeCommitDiff(target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String, commitHash: String? = null, expectedContext: ScopedReadContext? = null): ApiResult<ChangeCommitDiffAnswer> =
        withConnection(scope, target.id, expectedContext) { api.changeCommitDiff(it, target, scope, lookup, repo, path, commitHash) }

    suspend fun startLaunch(scope: Scope, target: StartTarget, requestId: String, cwd: String? = null): ApiResult<CreateResponse> = withWritableConnection(scope) {
        api.startLaunch(it, scope, target, requestId, cwd)
    }

    /** Reserve and persist an exact launch intent before making its one permitted network attempt. */
    suspend fun startLaunchIntent(
        scope: Scope,
        target: StartTarget,
        requestId: String,
        cwd: String? = null,
        confirmedNoPrompts: Boolean,
    ): ApiResult<CreateResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) {
            return ApiResult.Failure(ApiFailure.Http(409, "Resolve the earlier uncertain launch before starting another", "launch.unresolved_intent"))
        }
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val body = intentJson.encodeToString(
            StartLaunchRequest.serializer(),
            StartLaunchRequest(requestId, cwd, target.harness, target.command, target.shell),
        )
        val intent = FrozenLaunchIntent(
            requestId = requestId,
            origin = context.key.origin,
            mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration,
            pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch,
            host = context.key.host,
            session = context.key.session,
            subjectIdentity = context.key.subjectIdentity,
            operation = "launch",
            confirmedNoPrompts = confirmedNoPrompts,
            canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchLaunchIntent(context, reserved.record)
    }

    /** A one-off run is the same owner-bound launch receipt contract with a distinct frozen body. */
    suspend fun startRunIntent(
        scope: Scope,
        run: String,
        requestId: String,
        cwd: String? = null,
        confirmedNoPrompts: Boolean,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<CreateResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (run.isBlank() || run.length > 200 || (cwd?.length ?: 0) > 4096 || requestId.isBlank() ||
            !writesAllowed(scope, context.key.subjectIdentity) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) return unresolvedIntentFailure()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        when (val check = withConnection(scope, context.key.subjectIdentity, context) { api.checkRun(it, scope, run) }) {
            is ApiResult.Success -> {
                if (check.value.problem != null) {
                    return ApiResult.Failure(ApiFailure.Http(400, "The command does not pass the host's syntax check", "launch.bad_line"))
                }
                if (check.value.noPrompts && !confirmedNoPrompts) {
                    return ApiResult.Failure(ApiFailure.Http(403, "Explicit confirmation is required for no-prompts commands", "launch.no_prompts_confirmation_required"))
                }
            }
            is ApiResult.Failure -> return check
            is ApiResult.NotModified -> return intentUnavailable()
        }
        when (val discovery = withConnection(scope, context.key.subjectIdentity, context) { api.launchers(it, scope) }) {
            is ApiResult.Success -> if (discovery.value.adding?.run != true) {
                return ApiResult.Failure(ApiFailure.Http(403, "This host does not declare one-off runs", "launch.run_off"))
            }
            is ApiResult.Failure -> return discovery
            is ApiResult.NotModified -> return intentUnavailable()
        }
        val body = intentJson.encodeToString(StartRunRequest.serializer(), StartRunRequest(requestId, cwd, run))
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launch.run",
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchLaunchIntent(context, reserved.record)
    }

    suspend fun checkRun(scope: Scope, run: String, expectedContext: ScopedReadContext? = null): ApiResult<LaunchCheckResponse> =
        withConnection(scope, expectedContext = expectedContext) { api.checkRun(it, scope, run) }

    suspend fun renameAddedLauncher(scope: Scope, id: String, label: String, expectedContext: ScopedReadContext? = null): ApiResult<AddedLauncherResponse> =
        withWritableConnection(scope, expectedContext = expectedContext) { api.renameAddedLauncher(it, scope, id, label) }

    suspend fun removeAddedLauncher(scope: Scope, id: String, expectedContext: ScopedReadContext? = null): ApiResult<AddedLauncherMutationResponse> =
        withWritableConnection(scope, expectedContext = expectedContext) { api.removeAddedLauncher(it, scope, id) }

    suspend fun removeRecentRun(scope: Scope, line: String, expectedContext: ScopedReadContext? = null): ApiResult<RecentRunsResponse> =
        withWritableConnection(scope, expectedContext = expectedContext) { api.removeRecentRun(it, scope, line) }

    suspend fun clearRecentRuns(scope: Scope, expectedContext: ScopedReadContext? = null): ApiResult<RecentRunsResponse> =
        withWritableConnection(scope, expectedContext = expectedContext) { api.clearRecentRuns(it, scope) }

    /** One local durable attempt for an older declared launcher endpoint without owner receipts. */
    suspend fun startLegacyLaunchInitial(
        scope: Scope,
        command: String,
        requestId: String,
        cwd: String? = null,
        confirmedNoPrompts: Boolean,
    ): ApiResult<CreateResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        if (command.isBlank() || requestId.isBlank() || !writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) return unresolvedIntentFailure()
        val body = intentJson.encodeToString(
            StartLaunchRequest.serializer(),
            StartLaunchRequest(requestId, cwd, command = command),
        )
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launch",
            receiptMode = LaunchIntentReceiptMode.legacyInitialOnly,
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchLaunchIntent(context, reserved.record)
    }

    fun unresolvedLaunchIntents(scope: Scope = selectedScope.value): List<StoredLaunchIntent> = try {
        val context = readContext(scope) ?: return emptyList()
        launchIntentStore?.all().orEmpty().filter { record ->
            record.state == LaunchIntentState.unknown && intentMatches(record.intent, context, record.intent.operation)
        }
    } catch (_: Exception) { emptyList() }

    /** An explicit retry replays the exact persisted body and ID; startup never calls this method. */
    suspend fun retryLaunchIntent(scope: Scope, requestId: String, expectedContext: ScopedReadContext? = null): ApiResult<CreateResponse> {
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (record.state != LaunchIntentState.unknown || record.intent.receiptMode != LaunchIntentReceiptMode.ownerBound ||
            record.intent.operation !in setOf("launch", "launch.run")) {
            return ApiResult.Failure(ApiFailure.Http(409, "Only an unresolved launch can be retried", "launch.request_mismatch"))
        }
        // Reconcile the owning topology before the user's deliberate same-ID replay. This is a read;
        // it never infers success from a similar label and never dispatches the saved write.
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope) return staleScope()
        val refreshed = snapshot(scope, context)
        if (!isCurrent(context)) return staleScope()
        if (refreshed !is ApiResult.Success) {
            return (refreshed as? ApiResult.Failure)?.let { it } ?: intentUnavailable()
        }
        if (record.state != LaunchIntentState.unknown || !intentMatches(record.intent, context, record.intent.operation)) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved launch is not retryable in this scope", "launch.request_mismatch"))
        }
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        return dispatchLaunchIntent(context, record)
    }

    fun abandonLaunchIntent(scope: Scope, requestId: String): ApiResult<ActionResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (!isCurrent(context) || record.state != LaunchIntentState.unknown ||
            !intentMatches(record.intent, context, record.intent.operation)) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved request is not abandonable in this scope", "launch.request_mismatch"))
        }
        return try {
            if (!store.abandonUnknown(requestId, record.intent.bodySha256)) {
                ApiResult.Failure(ApiFailure.Http(409, "The saved request changed before it could be abandoned", "launch.request_mismatch"))
            } else ApiResult.Success(ActionResponse(ok = true), 200)
        } catch (_: Exception) {
            intentUnavailable()
        }
    }

    private suspend fun dispatchLaunchIntent(
        context: ScopedReadContext,
        record: StoredLaunchIntent,
    ): ApiResult<CreateResponse> {
        try {
        // Authority can change after the readiness read and before this transport call. Preserve
        // the reserved intent as uncertain without sending it under a stale scope.
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        if (!writesAllowed(context.key.scope, context.key.subjectIdentity) || !context.connection.isPaired) {
            return intentDispatchRefused(record)
        }
        val result = withConnectionOf(
            context.connection,
            context.key.scope,
            context.key.subjectIdentity,
            mutation = true,
            expectedContext = context,
        ) {
            if (record.intent.operation == "launch.run") {
                if (record.intent.receiptMode != LaunchIntentReceiptMode.ownerBound) return@withConnectionOf intentUnknown()
                api.startRunFrozen(it, context.key.scope, record.intent.canonicalBody)
            } else if (record.intent.receiptMode == LaunchIntentReceiptMode.legacyInitialOnly) {
                if (record.state != LaunchIntentState.pending) return@withConnectionOf intentUnknown()
                val frozen = intentJson.decodeFromString(StartLaunchRequest.serializer(), record.intent.canonicalBody)
                if (frozen.requestId != record.intent.requestId || frozen.command.isNullOrBlank()) return@withConnectionOf intentUnknown()
                api.launch(it, context.key.scope, frozen.command)
            } else {
                api.startLaunchFrozen(it, context.key.scope, record.intent.canonicalBody)
            }
        }
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        val state = if (record.intent.receiptMode == LaunchIntentReceiptMode.legacyInitialOnly &&
            result is ApiResult.Success && result.value.ok && result.value.pane == null
        ) LaunchIntentState.unknown else launchDisposition(result)
        // A refusal from a replay cannot prove that the original uncertain attempt had no effect.
        // Keep the durable unknown row until a success receipt or explicit user abandonment.
        if (record.state == LaunchIntentState.unknown && state == LaunchIntentState.refused) return result
        if (!persistIntentState(record, state)) return intentUnknown()
        return result
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // The request may already have reached the bridge. Commit uncertainty synchronously before
        // propagating lifecycle cancellation so a recreated screen can offer the same saved intent.
        persistIntentState(record, LaunchIntentState.unknown)
        throw cancelled
    } catch (_: Exception) {
        // Transport implementations are expected to return ApiResult, but an unexpected throw after
        // reservation is still ambiguous. Keep the durable ID/body and expose deliberate recovery.
        persistIntentState(record, LaunchIntentState.unknown)
        return intentUnknown()
        }
    }

    private fun persistIntentState(record: StoredLaunchIntent, state: LaunchIntentState): Boolean = try {
        val store = launchIntentStore ?: return false
        val current = store.get(record.intent.requestId) ?: return false
        current.state == state || store.transition(record.intent.requestId, record.intent.bodySha256, state) != null
    } catch (_: Exception) { false }

    private fun intentMatches(intent: FrozenLaunchIntent, context: ScopedReadContext, operation: String): Boolean =
        intent.origin == context.key.origin && intent.mount == context.key.origin &&
            // These counters fence live actors but reset with the process and change on same-owner
            // lead takeover. Stable owner/scope fields below govern deliberate crash recovery.
            intent.pairedDeviceIdentity == context.key.pairedDeviceIdentity &&
            intent.host == context.key.host && intent.session == context.key.session &&
            intent.subjectIdentity == context.key.subjectIdentity && intent.operation == operation

    private suspend fun intentReceiptReadiness(context: ScopedReadContext, scope: Scope): ApiResult<Boolean> {
        if (!isCurrent(context)) return staleScope()
        val config = withConnectionOf(
            context.connection, scope, context.key.subjectIdentity, expectedContext = context,
        ) { api.config(it, scope) }
        if (!isCurrent(context)) return staleScope()
        return when (config) {
            is ApiResult.Success -> ApiResult.Success(
                config.value.receiptContextBound == true,
                config.status,
                config.etag,
                config.buildId,
                config.controlAuthority,
            )
            is ApiResult.Failure -> config
            is ApiResult.NotModified -> config
        }
    }

    private fun launchDisposition(result: ApiResult<*>): LaunchIntentState = when (result) {
        is ApiResult.Success<*> -> {
            val (ok, code) = when (val value = result.value) {
                is CreateResponse -> value.ok to value.code
                is WorktreeOpenResponse -> value.ok to value.code
                is AddedLauncherResponse -> value.ok to value.code
                else -> false to null
            }
            when {
                ok -> LaunchIntentState.succeeded
                code == OUTCOME_UNKNOWN_CODE -> LaunchIntentState.unknown
                code in PRE_DISPATCH_REFUSAL_CODES -> LaunchIntentState.refused
                else -> LaunchIntentState.unknown
            }
        }
        is ApiResult.Failure -> {
            val error = result.error as? ApiFailure.Http
            if (error != null && error.status in 400..499 && error.source == com.lateapex.collie.network.FailureSource.COLLIE && error.code in PRE_DISPATCH_REFUSAL_CODES) {
                LaunchIntentState.refused
            } else LaunchIntentState.unknown
        }
        is ApiResult.NotModified -> LaunchIntentState.unknown
    }

    private fun intentUnavailable() = ApiResult.Failure(
        ApiFailure.Http(503, "Durable owner-bound launch receipts are unavailable", "launch.receipts_unavailable"),
    )

    private fun unresolvedIntentFailure() = ApiResult.Failure(
        ApiFailure.Http(409, "Resolve the earlier uncertain launch before starting another", "launch.unresolved_intent"),
    )

    /** A post-readiness access freeze is a proven pre-dispatch refusal, never a network attempt. */
    private fun <T> intentDispatchRefused(record: StoredLaunchIntent): ApiResult<T> {
        // This local refusal applies only to the current attempt. If it is a retry, the earlier
        // request may already have taken effect, so preserve that original uncertainty.
        if (record.state == LaunchIntentState.pending && !persistIntentState(record, LaunchIntentState.refused)) {
            return intentUnknown()
        }
        val failure = accessFailure ?: ApiFailure.Http(403, "device not authorised", "device.not_authorised")
        return ApiResult.Failure(failure)
    }

    private fun intentUnknown() = ApiResult.Failure(
        ApiFailure.Protocol("Launch outcome is unknown; the saved request remains available for deliberate reconciliation"),
    )

    suspend fun planWorktree(scope: Scope, cwd: String, branch: String? = null, parent: String? = null): ApiResult<WorktreePlanResponse> = withConnection(scope) {
        api.planWorktree(it, scope, cwd, branch, parent)
    }

    suspend fun createWorktreeAt(scope: Scope, cwd: String, branch: String, target: StartTarget, requestId: String, base: WorktreeBase = WorktreeBase(), folder: WorktreeFolder = WorktreeFolder()): ApiResult<WorktreeOpenResponse> = withWritableConnection(scope) {
        api.createWorktreeAt(it, scope, cwd, branch, target, requestId, base, folder)
    }

    suspend fun createWorktreeAtIntent(
        scope: Scope,
        cwd: String,
        branch: String,
        target: StartTarget,
        requestId: String,
        base: WorktreeBase = WorktreeBase(),
        folder: WorktreeFolder = WorktreeFolder(),
        confirmedNoPrompts: Boolean,
    ): ApiResult<WorktreeOpenResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) {
            return ApiResult.Failure(ApiFailure.Http(409, "Resolve the earlier uncertain launch before starting another", "launch.unresolved_intent"))
        }
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val body = intentJson.encodeToString(
            WorktreeAtRequest.serializer(),
            WorktreeAtRequest(cwd, branch, base, folder, requestId, target.harness, target.command, target.shell),
        )
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.createAt",
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchWorktreeIntent(context, reserved.record)
    }

    /** Persist the existing workspace/branch POST with a caller-owned ID before its first attempt. */
    suspend fun createWorkspaceWorktreeIntent(
        scope: Scope,
        workspaceId: String,
        branch: String,
        requestId: String,
    ): ApiResult<WorktreeOpenResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!workspaceId.isNotBlank() || !branch.isNotBlank() || !writesAllowed(scope) || !context.connection.isPaired) {
            return notConnected()
        }
        if (unresolvedLaunchIntents(scope).isNotEmpty()) {
            return ApiResult.Failure(ApiFailure.Http(409, "Resolve the earlier uncertain launch before starting another", "launch.unresolved_intent"))
        }
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val body = intentJson.encodeToString(BranchRequest.serializer(), BranchRequest(branch, requestId))
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.create", targetIdentity = workspaceId,
            confirmedNoPrompts = false, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchWorktreeIntent(context, reserved.record)
    }

    suspend fun retryWorktreeIntent(scope: Scope, requestId: String): ApiResult<WorktreeOpenResponse> {
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (record.state != LaunchIntentState.unknown || record.intent.receiptMode != LaunchIntentReceiptMode.ownerBound || record.intent.operation !in setOf("worktree.createAt", "worktree.create")) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved worktree request is not retryable in this scope", "launch.request_mismatch"))
        }
        val context = readContext(scope) ?: return notConnected()
        val refreshed = snapshot(scope, context)
        if (!isCurrent(context)) return staleScope()
        if (refreshed !is ApiResult.Success) return (refreshed as? ApiResult.Failure) ?: intentUnavailable()
        if (!intentMatches(record.intent, context, record.intent.operation)) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved worktree request belongs to a different authority", "launch.request_mismatch"))
        }
        if (record.intent.operation == "worktree.create" && record.intent.targetIdentity.isNullOrBlank()) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved worktree target is incomplete", "launch.request_mismatch"))
        }
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        return dispatchWorktreeIntent(context, record)
    }

    /** Existing one-shot initial create for a writable bridge without owner-bound receipt support. */
    suspend fun createWorkspaceWorktreeLegacyInitial(
        scope: Scope,
        workspaceId: String,
        branch: String,
        requestId: String,
    ): ApiResult<WorktreeOpenResponse> {
        val context = readContext(scope) ?: return notConnected()
        val store = launchIntentStore ?: return intentUnavailable()
        if (workspaceId.isBlank() || branch.isBlank() || !writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        val body = intentJson.encodeToString(BranchRequest.serializer(), BranchRequest(branch, requestId))
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.create", targetIdentity = workspaceId,
            receiptMode = LaunchIntentReceiptMode.legacyInitialOnly,
            confirmedNoPrompts = false, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchWorktreeIntent(context, reserved.record)
    }

    /** Owner-bound launcher.add journal seam for the launcher-management UI. The body is frozen by the caller. */
    suspend fun addLauncherIntent(
        scope: Scope,
        requestId: String,
        canonicalBody: String,
        expectedContext: ScopedReadContext? = null,
        confirmedNoPrompts: Boolean = false,
        expectedRecipe: RecipeWire? = null,
    ): ApiResult<AddedLauncherResponse> {
        if (requestId.isBlank() || canonicalBody.isBlank() || canonicalBody.length > 65_536 || bodyRequestId(canonicalBody) != requestId) {
            return ApiResult.Failure(ApiFailure.Http(400, "Malformed frozen launcher request", "launch.request_mismatch"))
        }
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) return unresolvedIntentFailure()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val request = runCatching { intentJson.parseToJsonElement(canonicalBody).jsonObject }.getOrNull()
            ?: return ApiResult.Failure(ApiFailure.Http(400, "Malformed launcher request", "launcher.bad_request"))
        val isRecipe = request["recipe"] is kotlinx.serialization.json.JsonObject
        val hasText = request.containsKey("text")
        if (isRecipe == hasText || request.containsKey("recipe") && !isRecipe) {
            return ApiResult.Failure(ApiFailure.Http(400, "Launcher request must select exactly one recipe or text target", "launcher.bad_request"))
        }
        val text = (request["text"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = (request["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val requestedHarness = (request["harness"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (!isRecipe && (text.isNullOrBlank() || text.length > 4096 || kind !in setOf("agent", "command"))) {
            return ApiResult.Failure(ApiFailure.Http(400, "Malformed launcher request", "launcher.bad_request"))
        }
        val currentCatalog = when (val discovery = withConnection(scope, context.key.subjectIdentity, context) { api.launchers(it, scope) }) {
            is ApiResult.Success -> discovery.value
            is ApiResult.Failure -> return discovery
            is ApiResult.NotModified -> return intentUnavailable()
        }
        val adding = currentCatalog.adding ?: return ApiResult.Failure(ApiFailure.Http(409, "This host does not declare launcher additions", "launcher.adds_off"))
        if (!adding.adds || adding.max !in 1..20 || adding.count !in 0 until adding.max) {
            return ApiResult.Failure(ApiFailure.Http(409, "The host has disabled launcher additions or reached its limit", "launcher.adds_off"))
        }
        val noPromptsField = request["noPrompts"]
        val noPromptsPrimitive = noPromptsField as? kotlinx.serialization.json.JsonPrimitive
        if (noPromptsField != null && noPromptsPrimitive?.booleanOrNull == null) {
            return ApiResult.Failure(ApiFailure.Http(400, "Malformed no-prompts choice", "launcher.bad_request"))
        }
        var policyNoPrompts = noPromptsPrimitive?.booleanOrNull == true
        if (isRecipe) {
            val picked = request["recipe"]!!.jsonObject
            val harness = (picked["harness"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val optionIds = picked["options"]?.let { value ->
                runCatching { (value as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } }.getOrNull()
            } ?: return ApiResult.Failure(ApiFailure.Http(400, "Malformed recipe selection", "launcher.bad_recipe"))
            val recipe = adding.recipes.firstOrNull { it.harness == harness }
                ?: return ApiResult.Failure(ApiFailure.Http(409, "The selected recipe is no longer available", "launcher.bad_recipe"))
            if (expectedRecipe != null && recipe != expectedRecipe) {
                return ApiResult.Failure(ApiFailure.Protocol("The recipe changed after it was reviewed; reload and confirm the current recipe"))
            }
            if (optionIds.size != optionIds.toSet().size || optionIds.any { id -> recipe.options.none { it.id == id } } ||
                recipe.options.filter { it.id in optionIds }.mapNotNull { it.group }.let { it.size != it.toSet().size }) {
                return ApiResult.Failure(ApiFailure.Http(400, "The recipe options are invalid or mutually exclusive", "launcher.bad_recipe"))
            }
            policyNoPrompts = recipe.options.any { it.id in optionIds && it.noPrompts }
        } else if (!adding.freeText) {
            return ApiResult.Failure(ApiFailure.Http(409, "This host has disabled free-text launchers", "launcher.free_text_off"))
        } else if (kind == "agent" && (requestedHarness.isNullOrBlank() || adding.recipes.none { it.harness == requestedHarness })) {
            return ApiResult.Failure(ApiFailure.Http(409, "The selected agent harness is no longer declared", "launcher.unknown_harness"))
        }
        if (!isRecipe) {
            when (val check = withConnection(scope, context.key.subjectIdentity, context) { api.checkRun(it, scope, text!!) }) {
                is ApiResult.Success -> {
                    if (check.value.problem != null) {
                        return ApiResult.Failure(ApiFailure.Http(400, "The launcher text does not pass the host's syntax check", "launch.bad_line"))
                    }
                    policyNoPrompts = policyNoPrompts || check.value.noPrompts
                }
                is ApiResult.Failure -> return check
                is ApiResult.NotModified -> return intentUnavailable()
            }
        }
        if (policyNoPrompts && !confirmedNoPrompts) {
            return ApiResult.Failure(ApiFailure.Http(403, "Explicit confirmation is required for no-prompts launchers", "launcher.no_prompts_confirmation_required"))
        }
        if (policyNoPrompts && isRecipe && expectedRecipe == null) {
            return ApiResult.Failure(ApiFailure.Protocol("The no-prompts recipe must be compared with the reviewed catalog before dispatch"))
        }
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launcher.add", targetIdentity = "added-launcher-row",
            confirmedNoPrompts = policyNoPrompts && confirmedNoPrompts, canonicalBody = canonicalBody,
        )
        val reserved = try { store.reserve(intent) } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchAddedLauncherIntent(context, reserved.record)
    }

    suspend fun retryAddedLauncherIntent(scope: Scope, requestId: String, expectedContext: ScopedReadContext? = null): ApiResult<AddedLauncherResponse> {
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (record.state != LaunchIntentState.unknown || record.intent.receiptMode != LaunchIntentReceiptMode.ownerBound || record.intent.operation != "launcher.add") {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved launcher request is not retryable in this scope", "launch.request_mismatch"))
        }
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope) return staleScope()
        val refreshed = snapshot(scope, context)
        if (!isCurrent(context)) return staleScope()
        if (refreshed !is ApiResult.Success) return (refreshed as? ApiResult.Failure) ?: intentUnavailable()
        if (!intentMatches(record.intent, context, "launcher.add")) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved launcher request belongs to a different authority", "launch.request_mismatch"))
        }
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        return dispatchAddedLauncherIntent(context, record)
    }

    private suspend fun dispatchAddedLauncherIntent(
        context: ScopedReadContext,
        record: StoredLaunchIntent,
    ): ApiResult<AddedLauncherResponse> {
      return try {
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        if (!writesAllowed(context.key.scope, context.key.subjectIdentity) || !context.connection.isPaired) return intentDispatchRefused(record)
        val result = withConnectionOf(
            context.connection, context.key.scope, context.key.subjectIdentity,
            mutation = true, expectedContext = context,
        ) { api.addLauncherFrozen(it, context.key.scope, record.intent.canonicalBody) }
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        val state = launchDisposition(result)
        if (record.state == LaunchIntentState.unknown && state == LaunchIntentState.refused) return result
        if (!persistIntentState(record, state)) return intentUnknown()
        result
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        persistIntentState(record, LaunchIntentState.unknown)
        throw cancelled
    } catch (_: Exception) {
        persistIntentState(record, LaunchIntentState.unknown)
        intentUnknown()
    }
    }

    private fun bodyRequestId(body: String): String? = runCatching {
        intentJson.parseToJsonElement(body).jsonObject["requestId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private suspend fun dispatchWorktreeIntent(context: ScopedReadContext, record: StoredLaunchIntent): ApiResult<WorktreeOpenResponse> {
        try {
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        if (!writesAllowed(context.key.scope, context.key.subjectIdentity) || !context.connection.isPaired) {
            return intentDispatchRefused(record)
        }
        val result = when (record.intent.operation) {
            "worktree.createAt" -> withConnectionOf(
                context.connection,
                context.key.scope,
                context.key.subjectIdentity,
                mutation = true,
                expectedContext = context,
            ) { api.createWorktreeAtFrozen(it, context.key.scope, record.intent.canonicalBody) }
            "worktree.create" -> record.intent.targetIdentity?.let {
                val workspaceId = it
                withConnectionOf(
                    context.connection,
                    context.key.scope,
                    context.key.subjectIdentity,
                    mutation = true,
                    expectedContext = context,
                ) {
                    if (record.intent.receiptMode == LaunchIntentReceiptMode.legacyInitialOnly && record.state != LaunchIntentState.pending) {
                        intentUnknown()
                    } else api.createWorktreeFrozen(it, context.key.scope, workspaceId, record.intent.canonicalBody)
                }
            } ?: return intentUnknown()
            else -> return intentUnavailable()
        }
        if (!isCurrent(context)) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        val state = launchDisposition(result)
        if (record.state == LaunchIntentState.unknown && state == LaunchIntentState.refused) return result
        if (!persistIntentState(record, state)) return intentUnknown()
        return result
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        persistIntentState(record, LaunchIntentState.unknown)
        throw cancelled
    } catch (_: Exception) {
        persistIntentState(record, LaunchIntentState.unknown)
        return intentUnknown()
        }
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

    suspend fun launchers(scope: Scope = selectedScope.value, expectedContext: ScopedReadContext? = null): ApiResult<LaunchersResponse> =
        withConnection(scope, expectedContext = expectedContext) { api.launchers(it, scope) }

    suspend fun starFolder(scope: Scope = selectedScope.value, folder: String, starred: Boolean): ApiResult<FoldersResponse> =
        withWritableConnection(scope) { api.starFolder(it, scope, folder, starred) }

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

    suspend fun upload(address: PaneAddress, upload: UploadSource, context: ScopedReadContext? = null): ApiResult<UploadResponse> = try {
        withWritableConnection(address.scope, address.paneId) {
            if (context != null && (!isCurrent(context) || it !== context.connection)) staleScope() else api.upload(it, address, upload)
        }
    } finally {
        upload.dispose()
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

    suspend fun dismissUpdate(version: String, scope: UpdateDismissalScope): ApiResult<UpdateInfo> =
        withDeviceOwnedConnection {
            require(version.isNotBlank())
            if (!it.isPaired) return@withDeviceOwnedConnection ApiResult.Failure(
                ApiFailure.Protocol("Pair this device before dismissing an update notice"),
            )
            api.dismissUpdate(it, version, scope)
        }

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

    /** A new guarded action: positive capability negotiation precedes its single scoped keys POST. */
    suspend fun queuedSendNow(
        address: PaneAddress,
        keys: List<String>,
        expectedPrompt: String,
        guard: QueuedSendGuard,
        actor: ScopedReadContext,
    ): ApiResult<ActionResponse> {
        if (keys.isEmpty() || keys.size > 12 || !keys.all(MuxKeyGrammar::isValid) ||
            guard.generation < 0L || guard.revision < 0L ||
            !isCanonicalQueuedSendIdentity(guard.queueDigest) || !isCanonicalQueuedSendIdentity(guard.occupant)
        ) return ApiResult.Failure(ApiFailure.Protocol("Queued send action is malformed"))
        if (actor.key.scope != address.scope || actor.key.subjectIdentity != address.paneId || !isCurrent(actor)) {
            return staleScope()
        }
        val connection = actor.connection
        if (!connection.isPaired) return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        if (!writesAllowed(address.scope, actor.key.subjectIdentity)) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }

        // Capability negotiation is part of this one captured actor. Process its authority headers
        // through the same repository path as every other response before considering a write.
        val rawConfig = api.config(connection, address.scope)
        if (!isCurrent(actor)) return staleScope()
        val config = processConnectionResult(actor, rawConfig)
        if (config !is ApiResult.Success) return when (config) {
            is ApiResult.Failure -> config
            is ApiResult.NotModified -> ApiResult.Failure(ApiFailure.Protocol("Guarded queued send capability could not be verified"))
            is ApiResult.Success -> error("unreachable")
        }
        if (config.value.mux?.supportsNew("guardedQueuedSend") != true) {
            return ApiResult.Failure(ApiFailure.Http(404, "Guarded queued send is unsupported on this server", "capability.unsupported"))
        }

        // This is the last check before the only POST. The connection object, scoped actor and
        // current device write admission must still be the ones that began negotiation.
        if (!isCurrent(actor) || this.connection.value !== actor.connection) return staleScope()
        if (!writesAllowed(address.scope, actor.key.subjectIdentity)) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
        val result = api.queuedSendNow(connection, address, keys, expectedPrompt, guard)
        if (!isCurrent(actor)) return staleScope()
        return processConnectionResult(actor, result)
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

    private suspend fun <T> withScopedLayoutRead(
        scope: Scope,
        expectedContext: ScopedReadContext?,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val subject = expectedContext?.key?.subjectIdentity ?: activeSubject
        val context = expectedContext ?: readContext(scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != scope.host || context.key.session != scope.session || context.key.subjectIdentity != subject) return staleScope()
        return withConnection(scope, subject, context, block)
    }

    private suspend fun <T> withScopedLayoutWrite(
        scope: Scope,
        subject: String?,
        expectedContext: ScopedReadContext?,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val context = expectedContext ?: readContext(scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != scope.host || context.key.session != scope.session || context.key.subjectIdentity != subject) return staleScope()
        if (!context.connection.isPaired) return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        if (!writesAllowed(scope, subject)) return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        return withConnectionOf(context.connection, scope, subject, mutation = true, expectedContext = context, block = block)
    }

    /** Child jobs cancel reads; writes discard stale replies without claiming dispatch failed. */
    private suspend fun <T> withConnectionOf(
        configured: Connection,
        scope: Scope = selectedScope.value,
        subjectIdentity: String? = activeSubject,
        mutation: Boolean = false,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = try { coroutineScope {
        val context = expectedContext ?: readContext(scope, subjectIdentity) ?: return@coroutineScope notConnected()
        // `viewAll` only widens the snapshot payload; ScopedRequestKey intentionally records the
        // authenticated host/session dimensions and reconstructs a non-wide Scope. Keep the
        // authority comparison on those identity dimensions while navigationGeneration fences
        // actual scope changes.
        if (context.connection !== configured || context.key.host != scope.host ||
            context.key.session != scope.session || context.key.subjectIdentity != subjectIdentity
        ) return@coroutineScope staleScope()
        if (!isCurrent(context)) return@coroutineScope staleScope()
        // Frozen writes spent time in readiness/consent reads before reaching this canonical
        // mutation gate. Recheck the current access fence at the last point before transport.
        if (mutation && expectedContext != null && !writesAllowed(scope, subjectIdentity)) {
            return@coroutineScope accessFailure?.let { ApiResult.Failure(it) }
                ?: ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
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
            processConnectionResult(context, result) { synchronized(readAdmissionLock) { scopeJobs.remove(job) } }
        } finally { synchronized(readAdmissionLock) { scopeJobs.remove(job) } }
    } } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // A cancelled request child must not kill its screen's poll loop. External screen/lifecycle
        // cancellation still propagates normally; only an authority invalidation becomes a discard.
        currentCoroutineContext().ensureActive()
        staleScope()
    }

    private fun <T> staleScope(): ApiResult<T> = ApiResult.Failure(ApiFailure.Protocol("Connection or scope changed; refresh before trying again"))

    /** Canonical authority/refusal processing shared by ordinary calls and queued-send negotiation. */
    private fun <T> processConnectionResult(
        context: ScopedReadContext,
        result: ApiResult<T>,
        beforeInvalidation: () -> Unit = {},
    ): ApiResult<T> {
        if (!isCurrent(context)) return staleScope()
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
                beforeInvalidation()
                setLeadEpoch(leadEpoch + 1)
                return staleScope()
            }
        }
        if (result is ApiResult.Failure) {
            mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
            mutableReadSource.value = ReadSourceState.Unavailable(context.key.connectionGeneration)
            accessFailure = result.error
            if (result.error is ApiFailure.Http && result.error.status in setOf(401, 403) && !result.error.retiresPairing()) {
                beforeInvalidation()
                invalidateAuthority(AuthorityInvalidation.ACCESS_FROZEN)
            } else authorityInvalidations.forEach { listener -> runCatching { listener(AuthorityInvalidation.ACCESS_FROZEN) } }
            if (result.error is ApiFailure.Http && result.error.retiresPairing()) {
                beforeInvalidation()
                invalidateAuthority(AuthorityInvalidation.PAIRING_RETIRED)
                demoteToReadOnly()
            }
        }
        return result
    }

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
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val context = expectedContext ?: readContext(scope, subjectIdentity) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope || context.key.subjectIdentity != subjectIdentity) return staleScope()
        val configured = connection.value ?: return notConnected()
        if (configured !== context.connection) return staleScope()
        if (!configured.isPaired) {
            return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        }
        if (!writesAllowed(scope, subjectIdentity)) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
        return withConnectionOf(configured, scope, subjectIdentity, mutation = true, expectedContext = context, block = block)
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
