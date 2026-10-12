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
import com.lateapex.collie.network.AgentCapabilitiesResponse
import com.lateapex.collie.network.NotificationState
import com.lateapex.collie.network.NotifyPreferences
import com.lateapex.collie.network.NotifyPreferencesPatch
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.CrewStatusResponse
import com.lateapex.collie.network.isValid
import com.lateapex.collie.network.isValidMachineId
import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.network.CacheRulesResponse
import com.lateapex.collie.network.CacheWatchListResponse
import com.lateapex.collie.network.CacheWatchResponse
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
import com.lateapex.collie.network.AgentIdentityResponse
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
import com.lateapex.collie.network.WorktreeRemovePlanResponse
import com.lateapex.collie.network.WorktreeRemoveRequest
import com.lateapex.collie.network.knownRefusalFor
import com.lateapex.collie.network.WorktreeRemoveResponse
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
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesExistAnswer
import com.lateapex.collie.network.FilesImageBlob
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.RelativeFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.ControlOperationResponse
import com.lateapex.collie.network.OrganizationAction
import com.lateapex.collie.network.OrganizationPlanResponse
import com.lateapex.collie.network.OrganizationOperationResponse
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

/** No credential/error text: removal failed, so encrypted custody is retained and locally inert. */
enum class CredentialRetirementFailure { UNSUPPORTED, PERSISTENCE_FAILED }

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
    @Volatile private var authorizationIncarnation: String? = null
    val deviceWriteAuthorization: StateFlow<DeviceWriteAuthorization> =
        mutableDeviceWriteAuthorization.asStateFlow()

    fun writesAllowed(scope: Scope? = null, subjectIdentity: String? = null): Boolean = synchronized(readAdmissionLock) {
        if (pendingAuthorityTransitions != 0) return@synchronized false
        val targetScope = scope ?: mutableScope.value
        val targetSubject = subjectIdentity ?: activeSubject
        val configured = connection.value ?: return@synchronized false
        if (configured === retiredCredentialConnection) return@synchronized false
        val live = mutableReadSource.value as? ReadSourceState.Live ?: return@synchronized false
        val committed = connectionStore.captureCommitted()
        if (committed != null && (committed.incarnationId == null || committed.connection !== configured ||
            committed.incarnationId != authorizationIncarnation || committed.incarnationId != generationIncarnation)) return@synchronized false
        configured.isPaired && authorizationConnection === configured &&
            mutableDeviceWriteAuthorization.value.permitsWrites &&
            live.key.origin == configured.origin.value && live.key.connectionGeneration == generation.get() &&
            live.key.leadEpoch == leadEpoch && live.key.host == targetScope.host && live.key.session == targetScope.session &&
            (activeSubject == null || live.key.subjectIdentity == activeSubject) &&
            (targetSubject == null || activeSubject == null || targetSubject == activeSubject)
    }

    private val mutableScope = MutableStateFlow(Scope())
    val selectedScope: StateFlow<Scope> = mutableScope.asStateFlow()

    private val generation = AtomicLong(0)
    private val navigationGeneration = AtomicLong(0)
    @Volatile private var generationConnection: Connection? = connection.value
    @Volatile private var generationIncarnation: String? = connectionStore.captureCommitted()?.incarnationId
    @Volatile private var leadEpoch: Long = 0
    @Volatile private var observedLeadAuthority: String? = null
    @Volatile var accessFailure: ApiFailure? = null
        private set
    @Volatile private var activeSubject: String? = null
    @Volatile private var retiredCredentialConnection: Connection? = null
    @Volatile var credentialRetirementFailure: CredentialRetirementFailure? = null
        private set
    private val scopeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val readAdmissionLock = Any()
    // Same-incarnation admission stays closed until its cancellation/custody ACK completes.
    // A successor never inherits a predecessor's pending cleanup fence. All access is under admission.
    private val pendingByGeneration = mutableMapOf<Long, Int>()
    private val pendingAuthorityTransitions: Int get() = pendingByGeneration[generation.get()] ?: 0
    /** Final result seams run outside all locks; no-op in production. */
    internal var beforeSnapshotPublicationForTesting: () -> Unit = {}
    internal var beforeConnectionResultPublicationForTesting: () -> Unit = {}
    /** Suspending seam for deterministic admission-race tests; production remains a no-op. */
    internal var beforeReadAdmissionForTesting: suspend () -> Unit = {}
    /** Runs before target/generation capture, outside locks; deterministic shared-caller tests only. */
    internal var beforeReadContextCaptureForTesting: () -> Unit = {}
    private val authorityInvalidations = java.util.concurrent.CopyOnWriteArrayList<(AuthorityRetirement) -> Unit>()
    private val readContextInvalidations = java.util.concurrent.CopyOnWriteArrayList<(AuthorityRetirement) -> Unit>()
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

    /** Shared original actors (Files, Pack, Machines and transport callers) retire on the same
     * committed witness. Initial/replayed callbacks never invalidate an already admitted B. */
    private val committedObservation = connectionStore.observeCommitted {
        val committed = connectionStore.captureCommitted() ?: return@observeCommitted
        invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED, change = {
            val latest = connectionStore.captureCommitted()
            if (latest == null || latest.connection !== committed.connection || latest.incarnationId != committed.incarnationId ||
                (generationConnection === latest.connection && generationIncarnation == latest.incarnationId)) false
            else {
                generationConnection = latest.connection
                generationIncarnation = latest.incarnationId
                generation.incrementAndGet()
                observedLeadAuthority = null
                true
            }
        })
    }

    fun selectScope(scope: Scope) {
        invalidateAuthority(AuthorityInvalidation.SCOPE_CHANGED, change = {
            if (pendingAuthorityTransitions > 1 || mutableScope.value == scope) false else {
                mutableScope.value = scope
                activeSubject = null
                true
            }
        })
    }

    /** Publish target and generation together; callbacks cannot admit an intermediate actor. */
    fun selectSubject(scope: Scope, subjectIdentity: String?) {
        invalidateAuthority(AuthorityInvalidation.SCOPE_CHANGED, change = {
            if (pendingAuthorityTransitions > 1 || (mutableScope.value == scope && activeSubject == subjectIdentity)) false else {
                mutableScope.value = scope
                activeSubject = subjectIdentity
                true
            }
        })
    }

    /** Sensitive feature custody retires synchronously; callbacks receive no credential. */
    fun onAuthorityInvalidated(listener: (AuthorityInvalidation) -> Unit): AutoCloseable {
        return onAuthorityRetired { listener(it.reason) }
    }

    /** Immutable authority-retirement events only; ordinary failed reads do not invalidate scope. */
    fun onReadContextInvalidated(listener: (AuthorityInvalidation) -> Unit): AutoCloseable {
        return onReadContextRetired { listener(it.reason) }
    }

    /** Ordered original-owner callbacks share registration order with the compatibility API. */
    fun onAuthorityRetired(listener: (AuthorityRetirement) -> Unit): AutoCloseable {
        authorityInvalidations.add(listener)
        return AutoCloseable { authorityInvalidations.remove(listener) }
    }

    fun onReadContextRetired(listener: (AuthorityRetirement) -> Unit): AutoCloseable {
        readContextInvalidations.add(listener)
        return AutoCloseable { readContextInvalidations.remove(listener) }
    }

    /** Files page custody follows committed store changes even while the screen is stopped.
     * Flow values can be queued: check this original actor against the actual current store value.
     * Temporary pending admission is not retirement. Callbacks/GUI ACKs run outside all locks. */
    fun onFilesActorRetired(context: ScopedReadContext, listener: (AuthorityInvalidation) -> Unit): AutoCloseable {
        val actor = java.util.concurrent.atomic.AtomicReference<ScopedReadContext?>(context)
        fun retire(reason: AuthorityInvalidation) {
            val original = actor.get() ?: return
            if (isActorRetired(original) && actor.compareAndSet(original, null)) listener(reason)
        }
        val authority = onReadContextInvalidated(::retire)
        val observer = onCommittedConnectionChanged { retire(AuthorityInvalidation.CONNECTION_CHANGED) }
        return AutoCloseable { actor.set(null); authority.close(); observer.close() }
    }

    /** Production observes every committed witness, even when an ABA returns the same reference.
     * Legacy test stores retain their existing flow; no supported production store falls back. */
    internal fun onCommittedConnectionChanged(listener: () -> Unit): AutoCloseable {
        if (connectionStore.captureCommitted() != null) return connectionStore.observeCommitted { listener() }
        val observer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        observer.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { connection.collect { listener() } }
        return AutoCloseable { observer.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    /** Null scope means the selected target, resolved with its navigation generation under one lock. */
    fun readContext(scope: Scope? = null): ScopedReadContext? = captureReadTarget(scope)?.context

    /** Explicit null subject remains distinct from an implicit selected subject. */
    fun readContext(scope: Scope, subjectIdentity: String?): ScopedReadContext? =
        captureReadTarget(scope, subjectIdentity, useSelectedSubject = false)?.context

    private data class ReadTarget(val scope: Scope, val context: ScopedReadContext, val subjectIdentity: String?)

    private fun captureReadTarget(
        scope: Scope?,
        subjectIdentity: String? = null,
        useSelectedSubject: Boolean = true,
    ): ReadTarget? {
        val committed = connectionStore.captureCommitted()
        val configured = (if (committed != null) committed.connection else connection.value) ?: return null
        // Unsupported stores cannot prove an independent successor incarnation. Preserve their
        // conservative pending contract; production adoption below distinguishes committed B first.
        if (committed == null && synchronized(readAdmissionLock) { pendingByGeneration.isNotEmpty() }) return null
        // A supported legacy record never gains a minted actor merely by reading it.
        if (committed != null && committed.incarnationId == null) return null
        if (configured === retiredCredentialConnection) return null
        fun sameCommit(): Boolean = if (committed == null) connectionStore.captureCommitted() == null && connection.value === configured
            else connectionStore.captureCommitted()?.let { it.connection === configured && it.incarnationId == committed.incarnationId } == true
        invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED, change = {
            if ((generationConnection === configured && generationIncarnation == committed?.incarnationId) || !sameCommit()) false
            else {
                generationConnection = configured
                generationIncarnation = committed?.incarnationId
                generation.incrementAndGet()
                observedLeadAuthority = null
                true
            }
        })
        beforeReadContextCaptureForTesting()
        return synchronized(readAdmissionLock) {
            // Held actors remain allocated during temporary fences, but no new actor is minted.
            if (pendingAuthorityTransitions != 0 || (committed == null && pendingByGeneration.isNotEmpty())) return@synchronized null
            if (!sameCommit() || generationConnection !== configured ||
                generationIncarnation != committed?.incarnationId) return@synchronized null
            if (configured === retiredCredentialConnection) return@synchronized null
            val targetScope = scope ?: mutableScope.value
            val targetSubject = if (useSelectedSubject) activeSubject else subjectIdentity
            val context = ScopedReadContext(ScopedRequestKey(configured.origin.value, generation.get(), credentialIdentity(configured, committed?.incarnationId),
                leadEpoch, targetScope.host, targetScope.session, targetSubject), configured, navigationGeneration.get(), targetScope,
                connectionIncarnation = committed?.incarnationId, committedStore = committed != null, committedSnapshot = committed)
            ReadTarget(targetScope, context, targetSubject)
        }
    }

    private fun readTarget(
        scope: Scope?,
        subjectIdentity: String? = null,
        expectedContext: ScopedReadContext? = null,
        useSelectedSubject: Boolean = true,
    ): ReadTarget? = if (expectedContext != null) {
        synchronized(readAdmissionLock) {
            ReadTarget(
                scope ?: expectedContext.requestScope, expectedContext,
                // An expected actor already resolved its subject, including an explicit null.
                if (useSelectedSubject) expectedContext.key.subjectIdentity else subjectIdentity,
            )
        }
    } else captureReadTarget(scope, subjectIdentity, useSelectedSubject)

    /** Local custody identity only: stable across recreation and different for replacement credentials. */
    private fun credentialIdentity(configured: Connection, incarnationId: String? = null): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("collie.native.custody.v1\u0000".toByteArray(Charsets.UTF_8))
        for (value in listOf(configured.origin.value, configured.token.orEmpty()) + listOfNotNull(incarnationId)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Non-authorizing witness for local acknowledged-input custody; never allocates an actor. */
    internal fun committedIncarnationFor(original: Connection): String? =
        connectionStore.captureCommitted()?.takeIf { it.connection === original }?.incarnationId

    private fun matchesCommitted(context: ScopedReadContext): Boolean {
        val actual = connectionStore.captureCommitted()
        return if (context.committedStore) actual != null && context.connectionIncarnation != null &&
            actual.incarnationId == context.connectionIncarnation && actual.connection === context.connection
        else actual == null && connection.value === context.connection
    }

    private fun matchesActorLocked(context: ScopedReadContext): Boolean =
        matchesCommitted(context) && connection.value === context.connection && context.connection !== retiredCredentialConnection &&
            generation.get() == context.key.connectionGeneration && navigationGeneration.get() == context.navigationGeneration &&
            leadEpoch == context.key.leadEpoch &&
            (activeSubject == null || context.key.subjectIdentity == null || activeSubject == context.key.subjectIdentity)

    fun isCurrent(context: ScopedReadContext): Boolean = synchronized(readAdmissionLock) {
        pendingAuthorityTransitions == 0 && matchesActorLocked(context)
    }

    /** A temporary admission fence is not permanent actor retirement or permission to dispatch. */
    fun isActorRetired(context: ScopedReadContext): Boolean = synchronized(readAdmissionLock) {
        !matchesActorLocked(context)
    }

    /** Serialize a bounded local encrypted-custody ACK against authority retirement. No network,
     * coroutine cancellation or invalidation listener may run inside this block. */
    internal fun acknowledgeLocalCustody(context: ScopedReadContext, acknowledgement: () -> Boolean): Boolean =
        synchronized(readAdmissionLock) { isCurrent(context) && acknowledgement() }

    fun setLeadEpoch(epoch: Long) {
        invalidateAuthority(AuthorityInvalidation.LEAD_CHANGED, change = {
            if (leadEpoch == epoch) false else { leadEpoch = epoch; true }
        })
    }

    /** Identity login does not authorize terminal writes; demand a fresh owning snapshot. */
    fun proxyIdentityChanged() = invalidateAuthority(AuthorityInvalidation.ACCESS_FROZEN)

    fun showSavedCopy(capturedAt: Long) {
        invalidateAuthority(AuthorityInvalidation.SAVED_COPY, settled = {
            mutableReadSource.value = ReadSourceState.SavedCopy(capturedAt, generation.get())
        })
    }

    private data class AuthorityTransition(
        val token: Long,
        val pendingGeneration: Long,
        val jobs: List<Job>,
        val retiresReadActor: Boolean,
        val retiredConnection: Connection?,
        val retiredIncarnation: String?,
        val retiredNavigation: Long,
        val owner: ScopedCustodyOwner?,
        val readListeners: List<(AuthorityRetirement) -> Unit>,
        val authorityListeners: List<(AuthorityRetirement) -> Unit>,
        val refusal: AuthorityRefusal?,
    )

    private fun finishPendingLocked(affectedGeneration: Long) {
        val count = checkNotNull(pendingByGeneration[affectedGeneration])
        if (count == 1) pendingByGeneration.remove(affectedGeneration)
        else pendingByGeneration[affectedGeneration] = count - 1
    }

    /** Called only under readAdmissionLock. The pending fence precedes every state change. */
    private fun prepareAuthorityTransitionLocked(
        retiresReadActor: Boolean = true,
        refusal: AuthorityRefusal? = null,
        change: () -> Boolean = { true },
    ): AuthorityTransition? {
        val affectedGeneration = generation.get()
        val retiredConnection = generationConnection
        val retiredIncarnation = generationIncarnation
        val retiredNavigation = navigationGeneration.get()
        val owner = retiredConnection?.let { ScopedCustodyOwner(it.origin.value, it.origin.value, credentialIdentity(it, retiredIncarnation)) }
        pendingByGeneration[affectedGeneration] = (pendingByGeneration[affectedGeneration] ?: 0) + 1
        val changed = try { change() } catch (failure: Throwable) {
            finishPendingLocked(affectedGeneration)
            throw failure
        }
        if (!changed) { finishPendingLocked(affectedGeneration); return null }
        val token = if (retiresReadActor) navigationGeneration.incrementAndGet() else navigationGeneration.get()
        mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.UNVERIFIED
        mutableReadSource.value = ReadSourceState.Unavailable(generation.get())
        return AuthorityTransition(token, affectedGeneration, if (retiresReadActor) scopeJobs.toList() else emptyList(),
            retiresReadActor, retiredConnection, retiredIncarnation, retiredNavigation, owner,
            readContextInvalidations.toList(), authorityInvalidations.toList(), refusal)
    }

    /** No repository/admission/private-custody lock crosses cancellation or main-thread ACK. */
    private fun completeAuthorityTransition(
        reason: AuthorityInvalidation,
        transition: AuthorityTransition,
        settled: () -> Unit = {},
    ) {
        val event = AuthorityRetirement(reason, transition.owner, transition.retiresReadActor,
            transition.retiredConnection, transition.retiredIncarnation, transition.pendingGeneration, transition.retiredNavigation, refusal = transition.refusal)
        try {
            transition.jobs.forEach { it.cancel() }
            if (transition.retiresReadActor) {
                transition.readListeners.forEach { listener -> runCatching { listener(event) } }
            }
            if (reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) {
                synchronized(cacheLock) {
                    paneCache.keys.removeAll { it.connection === transition.retiredConnection && it.incarnationId == transition.retiredIncarnation }
                }
            }
            if (reason in setOf(AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) {
                transition.owner?.let { owner -> runCatching { launchIntentStore?.retireOwned(owner, transition.pendingGeneration.takeIf { transition.retiredIncarnation == null }) } }
            }
            transition.authorityListeners.forEach { listener -> runCatching { listener(event) } }
        } finally {
            synchronized(readAdmissionLock) {
                if (navigationGeneration.get() == transition.token) settled()
                finishPendingLocked(transition.pendingGeneration)
            }
        }
    }

    private fun invalidateAuthority(
        reason: AuthorityInvalidation,
        change: () -> Boolean = { true },
        settled: () -> Unit = {},
    ) {
        val transition = synchronized(readAdmissionLock) {
            prepareAuthorityTransitionLocked {
                val changed = change()
                if (changed && reason == AuthorityInvalidation.CONNECTION_CHANGED) {
                    val committed = connectionStore.captureCommitted()
                    val configured = committed?.connection ?: connection.value
                    if (generationConnection !== configured || generationIncarnation != committed?.incarnationId) {
                        generationConnection = configured
                        generationIncarnation = committed?.incarnationId
                        generation.incrementAndGet()
                        observedLeadAuthority = null
                    }
                    accessFailure = null
                    authorizationConnection = null
                    authorizationIncarnation = null
                    retiredCredentialConnection = null
                    credentialRetirementFailure = null
                }
                changed
            }
        } ?: return
        completeAuthorityTransition(reason, transition, settled)
    }

    fun connectReadOnly(origin: String, label: String): Connection {
        val cleanLabel = DeviceLabel.normalize(label)
        require(cleanLabel.isNotEmpty() && cleanLabel.length <= DeviceLabel.MAX_CHARS)
        val connection = Connection(originValidator.requireValid(origin), cleanLabel, null)
        invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED, change = {
            connectionStore.save(connection)
            true
        })
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
                    invalidateAuthority(AuthorityInvalidation.CONNECTION_CHANGED, change = {
                        connectionStore.save(Connection(normalized, paired.label, paired.token))
                        true
                    })
                }
                result
            }
            is ApiResult.NotModified -> result
            is ApiResult.Failure -> result
        }
    }

    suspend fun snapshot(
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<SnapshotResponse> {
        val target = readTarget(scope, expectedContext = expectedContext) ?: return notConnected()
        val context = target.context
        val requestScope = target.scope
        val result = withConnectionOf(
            context.connection, requestScope, context.key.subjectIdentity, expectedContext = context,
        ) { configured -> api.snapshot(configured, requestScope) }
        if (result !is ApiResult.Success) return result
        if (!isCurrent(context)) return staleScope()
        beforeSnapshotPublicationForTesting()
        val authorityTransition = synchronized(readAdmissionLock) {
            if (!isCurrent(context)) return staleScope()
            val authority = result.value.controlAuthority?.let { "${it.leadId}/${it.epoch}" }
                ?: result.value.servers?.firstOrNull { it.isLead }?.id
            if (authority != null && observedLeadAuthority != null && observedLeadAuthority != authority) {
                prepareAuthorityTransitionLocked {
                    observedLeadAuthority = authority
                    leadEpoch += 1
                    true
                }
            } else {
                if (authority != null) observedLeadAuthority = authority
                accessFailure = null
                mutableDeviceWriteAuthorization.value = DeviceWriteAuthorization.from(result.value.device)
                authorizationConnection = context.connection
                authorizationIncarnation = context.connectionIncarnation
                mutableReadSource.value = ReadSourceState.Live(context.key, result.value.ts)
                null
            }
        }
        if (authorityTransition != null) {
            completeAuthorityTransition(AuthorityInvalidation.LEAD_CHANGED, authorityTransition)
            return staleScope()
        }
        return if (!requestScope.viewAll && requestScope.session != null) {
            result.copy(value = result.value.copy(
                agents = result.value.agents.map { it.copy(session = it.session ?: requestScope.session) },
                shellPanes = result.value.shellPanes.map { it.copy(session = it.session ?: requestScope.session) },
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

    suspend fun config(scope: Scope? = null, expectedContext: ScopedReadContext? = null): ApiResult<BridgeConfigResponse> =
        withResolvedConnection(scope, expectedContext) { configured, requestScope -> api.config(configured, requestScope) }

    private fun <T> configFailure(result: ApiResult<BridgeConfigResponse>): ApiResult<T> = when (result) {
        is ApiResult.Failure -> result
        is ApiResult.NotModified -> result
        is ApiResult.Success -> ApiResult.Failure(ApiFailure.Protocol("Unexpected config result"))
    }

    suspend fun refresh(
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<ActionResponse> {
        val target = readTarget(scope, expectedContext = expectedContext) ?: return notConnected()
        val context = target.context
        val requestScope = target.scope
        if (context.key.host != requestScope.host || context.key.session != requestScope.session || !isCurrent(context)) {
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
            val result = withConnectionOf(context.connection, requestScope, context.key.subjectIdentity, expectedContext = context) { api.refresh(it, requestScope) }
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
        val subject = (if (expectedContext != null) expectedContext.key.subjectIdentity else activeSubject)
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


    suspend fun planPaneLayout(address: PaneAddress, action: kotlinx.serialization.json.JsonObject, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutPlanResponse> {
        val subject = (if (expectedContext != null) expectedContext.key.subjectIdentity else activeSubject)
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

    suspend fun planTabTransfer(scope: Scope, tabId: String, body: kotlinx.serialization.json.JsonObject, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneTransferPlanResponse> {
        val subject = expectedContext?.key?.subjectIdentity ?: activeSubject
        val context = expectedContext ?: readContext(scope, subject) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != scope.host || context.key.session != scope.session || context.key.subjectIdentity != subject) return staleScope()
        val config = config(scope, context)
        if (!isCurrent(context)) return staleScope()
        if (config !is ApiResult.Success) return configFailure(config)
        val mux = config.value.mux ?: return ApiResult.Failure(ApiFailure.Http(404, "Whole-tab transfer is unsupported on this server", "capability.unsupported"))
        if (config.value.control?.supportsNew("controlOperations") != true || mux.supportsNew("tabTransfer") != true ||
            mux.supportsNew("paneLayout") != true || mux.supportsNew("paneMove") != true) return ApiResult.Failure(ApiFailure.Http(404, "Whole-tab transfer is unsupported on this server", "capability.unsupported"))
        return withScopedLayoutRead(scope, context) { api.planTabTransfer(it, scope, tabId, body) }
    }

    suspend fun applyPaneLayout(address: PaneAddress, requestId: String, planToken: String, action: kotlinx.serialization.json.JsonObject, expectedContext: ScopedReadContext? = null): ApiResult<com.lateapex.collie.network.PaneLayoutOperationResponse> {
        val subject = (if (expectedContext != null) expectedContext.key.subjectIdentity else activeSubject)
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
        val target = readTarget(null) ?: return notConnected()
        val result = withWritableConnectionTarget(target) { api.revokeDevice(it, label) }
        if (result is ApiResult.Success && result.value.currentExpired) {
            demoteToReadOnly(target.context)
        }
        return result
    }

    suspend fun crew(): ApiResult<CrewStatusResponse> = withConnection(block = api::crew)

    /** Machine telemetry is a lead-owned census; capture a deliberately unscoped, subject-free actor. */
    fun machineReadContext(): ScopedReadContext? = readContext(Scope(), null)

    suspend fun machines(
        expectedContext: ScopedReadContext,
        spark: Int = com.lateapex.collie.network.MAX_MACHINE_SPARK_MINUTES,
    ): ApiResult<com.lateapex.collie.network.MachinesResponse> {
        if (spark !in 1..com.lateapex.collie.network.MAX_MACHINE_SPARK_MINUTES) {
            return ApiResult.Failure(ApiFailure.Protocol("Machine spark window is out of range"))
        }
        if (expectedContext.key.host != null || expectedContext.key.session != null || expectedContext.key.subjectIdentity != null || !isCurrent(expectedContext)) return staleScope()
        val raw = withConnectionOf(expectedContext.connection, Scope(), null, expectedContext = expectedContext) {
            api.machines(it, spark)
        }
        if (raw !is ApiResult.Success) return raw
        return if (raw.status == 200 && raw.value.isValid()) raw
        else ApiResult.Failure(ApiFailure.Protocol("Malformed machine resources response"))
    }

    suspend fun machineHistory(
        expectedContext: ScopedReadContext,
        machineId: String,
        since: Long? = null,
    ): ApiResult<com.lateapex.collie.network.MachineHistoryResponse> {
        if (!isValidMachineId(machineId) || since?.let { it !in 0..9_007_199_254_740_991L } == true) {
            return ApiResult.Failure(ApiFailure.Protocol("Machine history request is malformed"))
        }
        if (expectedContext.key.host != null || expectedContext.key.session != null || expectedContext.key.subjectIdentity != null || !isCurrent(expectedContext)) return staleScope()
        val raw = withConnectionOf(expectedContext.connection, Scope(), null, expectedContext = expectedContext) {
            api.machineHistory(it, machineId, since)
        }
        if (raw !is ApiResult.Success) return raw
        return if (raw.status == 200 && raw.value.isValid()) raw
        else ApiResult.Failure(ApiFailure.Protocol("Malformed machine history response"))
    }

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

    /** Files reads retain the ordinary scope plus the device-read factor enforced by the bridge. */
    suspend fun files(
        target: FilesTarget,
        scope: Scope,
        path: RelativeFilePath? = null,
        dir: RelativeFilePath? = null,
        ignored: Boolean = false,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<FilesAnswer> = withConnection(scope, target.id, expectedContext) { api.files(it, target, scope, path, dir, ignored) }

    suspend fun filesExist(
        target: FilesTarget,
        scope: Scope,
        paths: List<RelativeFilePath>,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<FilesExistAnswer> = withConnection(scope, target.id, expectedContext) { api.filesExist(it, target, scope, paths) }

    suspend fun fileImage(
        context: ScopedReadContext,
        target: FilesTarget,
        path: RelativeFilePath,
    ): ApiResult<FilesImageBlob> {
        if (!isCurrent(context) || context.key.subjectIdentity != target.id) return staleScope()
        val scope = Scope(context.key.host, context.key.session)
        return withConnectionOf(context.connection, scope, target.id, expectedContext = context) {
            api.fileImage(it, target, scope, path)
        }
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

    suspend fun paneProcesses(address: PaneAddress, expectedContext: ScopedReadContext? = null): ApiResult<PaneProcessesResponse> = withConnection(address.scope, address.paneId, expectedContext) { configured ->
        when (val config = api.config(configured, address.scope)) {
            is ApiResult.Failure -> config
            is ApiResult.NotModified -> ApiResult.Failure(ApiFailure.Protocol("Process capability was not verified"))
            is ApiResult.Success -> if (config.value.mux?.supportsNew("paneProcesses") != true) {
                ApiResult.Failure(ApiFailure.Http(501, "Process inspection is unsupported on this runtime", "capability.unsupported"))
            } else api.paneProcesses(configured, address)
        }
    }
    suspend fun paneAgentIdentity(address: PaneAddress, expectedContext: ScopedReadContext? = null): ApiResult<AgentIdentityResponse> = withConnection(address.scope, address.paneId, expectedContext) { configured ->
        when (val config = api.config(configured, address.scope)) {
            is ApiResult.Failure -> config
            is ApiResult.NotModified -> ApiResult.Failure(ApiFailure.Protocol("Agent identity capability was not verified"))
            is ApiResult.Success -> if (config.value.mux?.supportsNew("agentIdentity") != true) {
                ApiResult.Failure(ApiFailure.Http(501, "Agent identity diagnostics are unsupported on this runtime", "capability.unsupported"))
            } else api.paneAgentIdentity(configured, address)
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
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<CreateResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope, context).isNotEmpty()) {
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
        if (!isCurrent(context)) return staleScope()
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
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
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
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launch.run",
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
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
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<CreateResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (command.isBlank() || requestId.isBlank() || !writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope).isNotEmpty()) return unresolvedIntentFailure()
        val body = intentJson.encodeToString(
            StartLaunchRequest.serializer(),
            StartLaunchRequest(requestId, cwd, command = command),
        )
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launch",
            receiptMode = LaunchIntentReceiptMode.legacyInitialOnly,
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchLaunchIntent(context, reserved.record)
    }

    fun unresolvedLaunchIntents(scope: Scope? = null, expectedContext: ScopedReadContext? = null): List<StoredLaunchIntent> {
        return try {
            val context = expectedContext ?: readContext(scope) ?: return emptyList()
            if (!isCurrent(context)) return emptyList()
            launchIntentStore?.all().orEmpty().filter { record ->
                record.state == LaunchIntentState.unknown && intentMatches(record.intent, context, record.intent.operation)
            }.takeIf { isCurrent(context) }.orEmpty()
        } catch (_: Exception) { emptyList() }
    }

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

    suspend fun planWorktree(scope: Scope, cwd: String, branch: String? = null, parent: String? = null, expectedContext: ScopedReadContext? = null): ApiResult<WorktreePlanResponse> = withConnection(scope, expectedContext = expectedContext) {
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
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<WorktreeOpenResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (!writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        if (unresolvedLaunchIntents(scope, context).isNotEmpty()) {
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
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.createAt",
            confirmedNoPrompts = confirmedNoPrompts, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
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
        if (unresolvedLaunchIntents(scope, context).isNotEmpty()) {
            return ApiResult.Failure(ApiFailure.Http(409, "Resolve the earlier uncertain launch before starting another", "launch.unresolved_intent"))
        }
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val body = intentJson.encodeToString(BranchRequest.serializer(), BranchRequest(branch, requestId))
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.create", targetIdentity = workspaceId,
            confirmedNoPrompts = false, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) {
            return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "launch.request_mismatch"))
        }
        return dispatchWorktreeIntent(context, reserved.record)
    }

    suspend fun retryWorktreeIntent(scope: Scope, requestId: String, expectedContext: ScopedReadContext? = null): ApiResult<WorktreeOpenResponse> {
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (record.state != LaunchIntentState.unknown || record.intent.receiptMode != LaunchIntentReceiptMode.ownerBound || record.intent.operation !in setOf("worktree.createAt", "worktree.create")) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved worktree request is not retryable in this scope", "launch.request_mismatch"))
        }
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
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
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<WorktreeOpenResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (context.requestScope != scope || !isCurrent(context)) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (workspaceId.isBlank() || branch.isBlank() || !writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        val body = intentJson.encodeToString(BranchRequest.serializer(), BranchRequest(branch, requestId))
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.create", targetIdentity = workspaceId,
            receiptMode = LaunchIntentReceiptMode.legacyInitialOnly,
            confirmedNoPrompts = false, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
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
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "launcher.add", targetIdentity = "added-launcher-row",
            confirmedNoPrompts = policyNoPrompts && confirmedNoPrompts, canonicalBody = canonicalBody,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
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

    suspend fun planOrganization(scope: Scope, action: OrganizationAction, expectedContext: ScopedReadContext? = null): ApiResult<OrganizationPlanResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.key.scope != scope || !context.connection.isPaired) return staleScope()
        val result = withConnectionOf(context.connection, scope, context.key.subjectIdentity, expectedContext = context) {
            api.planOrganization(it, scope, action)
        }
        return if (isCurrent(context)) result else staleScope()
    }

    suspend fun applyOrganization(scope: Scope, action: OrganizationAction, requestId: String, planToken: String, expectedContext: ScopedReadContext? = null): ApiResult<OrganizationOperationResponse> =
        withWritableConnection(scope, expectedContext = expectedContext) { api.applyOrganization(it, scope, requestId, planToken, action) }

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
        scope: Scope? = null,
        label: String? = null,
        cwd: String? = null,
    ): ApiResult<CreateResponse> = withResolvedWritableConnection(scope) { configured, requestScope ->
        api.createWorkspace(configured, requestScope, label, cwd)
    }

    suspend fun launchers(scope: Scope? = null, expectedContext: ScopedReadContext? = null): ApiResult<LaunchersResponse> =
        withResolvedConnection(scope, expectedContext) { configured, requestScope -> api.launchers(configured, requestScope) }

    suspend fun folders(scope: Scope? = null, expectedContext: ScopedReadContext? = null): ApiResult<FoldersResponse> =
        withResolvedConnection(scope, expectedContext) { configured, requestScope -> api.folders(configured, requestScope) }

    suspend fun starFolder(scope: Scope? = null, folder: String, starred: Boolean, expectedContext: ScopedReadContext? = null): ApiResult<FoldersResponse> =
        withResolvedWritableConnection(scope, expectedContext) { configured, requestScope -> api.starFolder(configured, requestScope, folder, starred) }

    /** Read-only per-harness implementation inventory from the exact owning host/session. */
    suspend fun agentCapabilities(scope: Scope = selectedScope.value, expectedContext: ScopedReadContext? = null): ApiResult<AgentCapabilitiesResponse> =
        withConnection(scope, expectedContext = expectedContext) { api.agentCapabilities(it, scope) }

    /** `command` must be a value returned by [launchers], never caller-authored shell text. */
    suspend fun launch(
        command: String,
        scope: Scope? = null,
        besidePaneId: String? = null,
    ): ApiResult<CreateResponse> = withResolvedWritableConnection(scope) { configured, requestScope ->
        require(command.isNotBlank())
        api.launch(configured, requestScope, command, besidePaneId)
    }

    suspend fun listWorktrees(
        workspaceId: String,
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<WorktreeListResponse> = withResolvedConnection(scope, expectedContext) { configured, requestScope ->
        require(workspaceId.isNotBlank())
        api.listWorktrees(configured, requestScope, workspaceId)
    }

    suspend fun createWorktree(
        workspaceId: String,
        branch: String,
        scope: Scope? = null,
    ): ApiResult<WorktreeOpenResponse> = withResolvedWritableConnection(scope) { configured, requestScope ->
        require(workspaceId.isNotBlank() && branch.isNotBlank())
        api.createWorktree(configured, requestScope, workspaceId, branch)
    }

    suspend fun openWorktree(
        workspaceId: String,
        path: String,
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<WorktreeOpenResponse> = withResolvedWritableConnection(scope, expectedContext) { configured, requestScope ->
        require(workspaceId.isNotBlank() && path.isNotBlank())
        api.openWorktree(configured, requestScope, workspaceId, path)
    }

    suspend fun planWorktreeRemoval(workspaceId: String, force: Boolean = false, scope: Scope? = null, expectedContext: ScopedReadContext? = null): ApiResult<WorktreeRemovePlanResponse> = withResolvedConnection(scope, expectedContext) { configured, requestScope ->
        val config = api.config(configured, requestScope)
        if (config is ApiResult.Failure) return@withResolvedConnection config
        if (config !is ApiResult.Success || config.value.control?.supportsNew("worktreeRemove") != true || config.value.mux?.supportsNew("removeWorktree") != true) {
            return@withResolvedConnection ApiResult.Failure(ApiFailure.Http(501, "Safe worktree removal is unavailable on this runtime", "worktree.remove_unsupported"))
        }
        api.planWorktreeRemoval(configured, requestScope, workspaceId, force)
    }

    /** Reserve the exact confirmation and request ID in the encrypted owner-bound intent journal before dispatch. */
    suspend fun removeWorktreeIntent(workspaceId: String, requestId: String, planToken: String, force: Boolean, scope: Scope, expectedContext: ScopedReadContext? = null, plan: WorktreeRemovePlanResponse? = null): ApiResult<WorktreeRemoveResponse> {
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
        if (plan != null && (!plan.validFor(workspaceId, force) || plan.planToken != planToken)) return staleScope()
        val store = launchIntentStore ?: return intentUnavailable()
        if (workspaceId.isBlank() || requestId.isBlank() || planToken.isBlank() || !writesAllowed(scope) || !context.connection.isPaired) return notConnected()
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        val request = WorktreeRemoveRequest(requestId, planToken, force)
        val body = intentJson.encodeToString(WorktreeRemoveRequest.serializer(), request)
        if (!isCurrent(context)) return staleScope()
        val intent = FrozenLaunchIntent(
            requestId = requestId, origin = context.key.origin, mount = context.key.origin,
            connectionGeneration = context.key.connectionGeneration, pairedDeviceIdentity = context.key.pairedDeviceIdentity,
            leadEpoch = context.key.leadEpoch, host = context.key.host, session = context.key.session,
            subjectIdentity = context.key.subjectIdentity, operation = "worktree.remove", targetIdentity = workspaceId,
            plannedWorktreePath = plan?.path, plannedWorktreeBranch = plan?.branch,
            confirmedNoPrompts = force, canonicalBody = body,
        )
        val reserved = try { store.reserve(intent) { isCurrent(context) } } catch (_: Exception) { return intentUnavailable() }
        if (reserved is ReserveIntentResult.Retired) return staleScope()
        if (reserved is ReserveIntentResult.Unresolved) return unresolvedIntentFailure()
        if (reserved !is ReserveIntentResult.Reserved) return ApiResult.Failure(ApiFailure.Http(409, "This request ID is already bound to a saved intent", "operation.request_mismatch"))
        return dispatchWorktreeRemoval(context, reserved.record, workspaceId)
    }

    /** Deliberate same-ID replay; the owner returns its durable receipt without dispatching twice. */
    suspend fun retryWorktreeRemovalIntent(scope: Scope, requestId: String, expectedContext: ScopedReadContext? = null): ApiResult<WorktreeRemoveResponse> {
        val store = launchIntentStore ?: return intentUnavailable()
        val record = runCatching { store.get(requestId) }.getOrNull() ?: return intentUnavailable()
        if (record.state != LaunchIntentState.unknown || record.intent.operation != "worktree.remove" || record.intent.targetIdentity.isNullOrBlank()) {
            return ApiResult.Failure(ApiFailure.Http(409, "The saved removal request is not unresolved in this scope", "operation.request_mismatch"))
        }
        val context = expectedContext ?: readContext(scope) ?: return notConnected()
        if (!isCurrent(context) || context.requestScope != scope) return staleScope()
        if (!intentMatches(record.intent, context, "worktree.remove")) return ApiResult.Failure(ApiFailure.Http(409, "The saved removal belongs to a different owner or scope", "operation.request_mismatch"))
        when (val readiness = intentReceiptReadiness(context, scope)) {
            is ApiResult.Failure -> return readiness
            is ApiResult.NotModified -> return intentUnavailable()
            is ApiResult.Success -> if (!readiness.value) return intentUnavailable()
        }
        return dispatchWorktreeRemoval(context, record, record.intent.targetIdentity!!)
    }

    private suspend fun dispatchWorktreeRemoval(context: ScopedReadContext, record: StoredLaunchIntent, workspaceId: String): ApiResult<WorktreeRemoveResponse> {
        if (!isCurrent(context) || !writesAllowed(context.key.scope, context.key.subjectIdentity) || !context.connection.isPaired) {
            persistIntentState(record, LaunchIntentState.unknown)
            return staleScope()
        }
        val frozen = runCatching { intentJson.decodeFromString(WorktreeRemoveRequest.serializer(), record.intent.canonicalBody) }.getOrNull()
            ?: return intentUnknown()
        if (frozen.requestId != record.intent.requestId) return intentUnknown()
        return try {
            val result = withConnectionOf(context.connection, context.key.scope, context.key.subjectIdentity, mutation = true, expectedContext = context) {
                api.removeWorktree(it, context.key.scope, workspaceId, frozen)
            }
            if (!isCurrent(context)) {
                persistIntentState(record, LaunchIntentState.unknown)
                return staleScope()
            }
            when (result) {
                is ApiResult.Success -> if (result.value.knownRefusalFor(workspaceId, frozen.requestId)) {
                    if (record.state == LaunchIntentState.pending && !persistIntentState(record, LaunchIntentState.refused)) intentUnknown() else result
                } else if (result.value.validFor(workspaceId, frozen.force, frozen.requestId) &&
                    (record.intent.plannedWorktreePath == null || result.value.path == record.intent.plannedWorktreePath && result.value.branch == record.intent.plannedWorktreeBranch)) {
                    val reference = "control-op:${frozen.requestId}"
                    if (launchIntentStore?.transition(frozen.requestId, record.intent.bodySha256, LaunchIntentState.succeeded, reference) == null) intentUnknown() else result
                } else {
                    persistIntentState(record, LaunchIntentState.unknown); intentUnknown()
                }
                is ApiResult.Failure -> { persistIntentState(record, LaunchIntentState.unknown); result }
                is ApiResult.NotModified -> { persistIntentState(record, LaunchIntentState.unknown); intentUnknown() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            persistIntentState(record, LaunchIntentState.unknown); throw cancelled
        } catch (_: Exception) {
            persistIntentState(record, LaunchIntentState.unknown); intentUnknown()
        }
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

    /** The catalog is always owned by the connected lead; never label it as a peer's provenance. */
    suspend fun cacheRules(scope: Scope = selectedScope.value, subjectIdentity: String? = activeSubject, etag: String? = null, expectedContext: ScopedReadContext? = null): ApiResult<CacheRulesResponse> {
        if (scope.host != null) return ApiResult.Failure(ApiFailure.Protocol("The peer owns its cache rule catalog"))
        return withConnection(scope, subjectIdentity, expectedContext) { api.cacheRules(it, etag) }
    }

    suspend fun cacheWatch(address: PaneAddress, expectedContext: ScopedReadContext? = null): ApiResult<CacheWatchResponse> =
        withConnection(address.scope, address.paneId, expectedContext) { api.cacheWatch(it, address) }

    /** This server preference route is paired-read gated; it does not change a terminal. */
    suspend fun setCacheWatch(address: PaneAddress, on: Boolean, expectedContext: ScopedReadContext? = null): ApiResult<CacheWatchResponse> =
        withConnection(address.scope, address.paneId, expectedContext) { api.setCacheWatch(it, address, on) }

    suspend fun cacheWatchList(): ApiResult<CacheWatchListResponse> =
        withConnection(block = api::cacheWatchList)

    suspend fun forgetCacheWatch(id: String): ApiResult<CacheWatchListResponse> =
        withConnection { api.forgetCacheWatch(it, id) }

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
        conditional: Boolean = true,
        expectedContext: ScopedReadContext? = null,
    ): ApiResult<PaneContent> {
        val context = expectedContext ?: readContext(address.scope, address.paneId) ?: return notConnected()
        if (!isCurrent(context) || context.key.host != address.scope.host ||
            context.key.session != address.scope.session || context.key.subjectIdentity != address.paneId
        ) return staleScope()
        val configured = context.connection
        val cacheKey = PaneCacheKey(configured, address, context.connectionIncarnation)
        // A pane body is conditional only for the exact window that produced it. Reusing the
        // 600-line ETag for a later 1,000-line request can otherwise turn a valid grow into a 304
        // with the shorter cached body when a bridge keys ETags to pane revision rather than size.
        // Credential identity is part of the key so reconnecting cannot reuse another device's body.
        // A new consumer must accept its own fresh body before it may conditionally reuse one.
        // In unconditional mode an unsolicited304 has no selected cache and refuses below.
        val cached = if (conditional) synchronized(cacheLock) { paneCache[cacheKey] }?.takeIf { it.lines == lines } else null
        return when (val result = withConnectionOf(configured, address.scope, address.paneId, expectedContext = context) { api.pane(it, address, lines, cached?.etag, markSeen) }) {
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
                    if (!isCurrent(context)) return ApiResult.Failure(ApiFailure.Protocol("Connection changed; refresh before trying again"))
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
        val rawConfig = com.lateapex.collie.network.RequestActorCustody.withActor(actor) {
            kotlinx.coroutines.withContext(OriginalReadActor { isCurrent(actor) }) { api.config(connection, address.scope) }
        }
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
        val result = com.lateapex.collie.network.RequestActorCustody.withActor(actor) {
            kotlinx.coroutines.withContext(OriginalReadActor {
                isCurrent(actor) && writesAllowed(address.scope, actor.key.subjectIdentity)
            }) { api.queuedSendNow(connection, address, keys, expectedPrompt, guard) }
        }
        if (!isCurrent(actor)) return staleScope()
        return processConnectionResult(actor, result)
    }

    suspend fun disconnect() {
        invalidateAuthority(AuthorityInvalidation.DISCONNECTED, change = {
            connectionStore.clear()
            mutableScope.value = Scope()
            activeSubject = null
            true
        })
    }

    fun demoteToReadOnly() {
        val current = readContext() ?: return
        demoteToReadOnly(current)
    }

    private fun demoteToReadOnly(expected: ScopedReadContext) {
        invalidateAuthority(AuthorityInvalidation.PAIRING_RETIRED, change = {
            retireCredentialLocked(expected)
        })
    }

    /** Called under admission: durable failure must never restore authority for a revoked owner. */
    private fun retireCredentialLocked(original: ScopedReadContext): Boolean {
        if (!matchesActorLocked(original)) return false
        val expected = original.connection
        val committed = connectionStore.captureCommitted()
        if (original.committedStore && (committed == null || committed.incarnationId != original.connectionIncarnation ||
            committed.connection !== expected)) return false
        val replacement = Connection(expected.origin, expected.label, null)
        var failure: CredentialRetirementFailure? = null
        val replaced = try {
            if (original.committedStore) connectionStore.compareAndSet(committed!!, replacement)
            else connectionStore.compareAndSet(expected, replacement)
        } catch (_: Exception) {
            failure = CredentialRetirementFailure.PERSISTENCE_FAILED
            false
        }
        // An external save is independent of the repository lock. Never publish A's freeze onto B.
        if (!(replaced && connection.value === replacement) && !matchesCommitted(original)) return false
        if (replaced) {
            credentialRetirementFailure = null
            retiredCredentialConnection = null
        } else {
            retiredCredentialConnection = expected
            credentialRetirementFailure = failure ?: CredentialRetirementFailure.UNSUPPORTED
        }
        return true
    }

    private suspend fun <T> withConnection(
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withConnectionTarget(readTarget(scope, expectedContext = expectedContext), block)

    private suspend fun <T> withConnection(
        scope: Scope,
        subjectIdentity: String?,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withConnectionTarget(
        readTarget(scope, subjectIdentity, expectedContext, useSelectedSubject = false), block,
    )

    private suspend fun <T> withConnectionTarget(
        target: ReadTarget?,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        target ?: return notConnected()
        val context = target.context
        if (!isCurrent(context) || context.key.host != target.scope.host || context.key.session != target.scope.session ||
            context.key.subjectIdentity != target.subjectIdentity) return staleScope()
        return withConnectionOf(context.connection, target.scope, context.key.subjectIdentity, expectedContext = context, block = block)
    }

    private suspend fun <T> withResolvedConnection(
        scope: Scope?,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection, Scope) -> ApiResult<T>,
    ): ApiResult<T> {
        val target = readTarget(scope, expectedContext = expectedContext) ?: return notConnected()
        return withConnection(target.scope, target.subjectIdentity, target.context) {
            block(it, target.scope)
        }
    }

    private suspend fun <T> withResolvedWritableConnection(
        scope: Scope?,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection, Scope) -> ApiResult<T>,
    ): ApiResult<T> {
        val target = readTarget(scope, expectedContext = expectedContext) ?: return notConnected()
        return withWritableConnection(target.scope, target.context.key.subjectIdentity, target.context) {
            block(it, target.scope)
        }
    }

    private suspend fun <T> withScopedLayoutRead(
        scope: Scope,
        expectedContext: ScopedReadContext?,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        val subject = (if (expectedContext != null) expectedContext.key.subjectIdentity else activeSubject)
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
        scope: Scope? = null,
        mutation: Boolean = false,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withConnectionOfTarget(
        configured, readTarget(scope, expectedContext = expectedContext), mutation, expectedContext != null, block,
    )

    private suspend fun <T> withConnectionOf(
        configured: Connection,
        scope: Scope,
        subjectIdentity: String?,
        mutation: Boolean = false,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withConnectionOfTarget(
        configured, readTarget(scope, subjectIdentity, expectedContext, useSelectedSubject = false),
        mutation, expectedContext != null, block,
    )

    /** Child jobs cancel reads; writes discard stale replies without claiming dispatch failed. */
    private suspend fun <T> withConnectionOfTarget(
        configured: Connection,
        target: ReadTarget?,
        mutation: Boolean,
        checkWriteAuthority: Boolean,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = try { coroutineScope {
        target ?: return@coroutineScope notConnected()
        val context = target.context
        val requestScope = target.scope
        val requestSubject = target.subjectIdentity
        // `viewAll` only widens the snapshot payload; ScopedRequestKey intentionally records the
        // authenticated host/session dimensions and reconstructs a non-wide Scope. Keep the
        // authority comparison on those identity dimensions while navigationGeneration fences
        // actual scope changes.
        if (context.connection !== configured || context.key.host != requestScope.host ||
            context.key.session != requestScope.session || context.key.subjectIdentity != requestSubject
        ) return@coroutineScope staleScope()
        if (!isCurrent(context)) return@coroutineScope staleScope()
        // Frozen writes spent time in readiness/consent reads before reaching this canonical
        // mutation gate. Recheck the current access fence at the last point before transport.
        if (mutation && checkWriteAuthority && !writesAllowed(requestScope, requestSubject)) {
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
            val result = com.lateapex.collie.network.RequestActorCustody.withActor(context) {
                kotlinx.coroutines.withContext(OriginalReadActor {
                    // Both the immutable request actor and declared operation survive every wait.
                    isCurrent(context) && (!mutation || if (checkWriteAuthority) writesAllowed(requestScope, requestSubject) else deviceOwnedAdmission())
                }) { block(configured) }
            }
            if (!isCurrent(context)) return@coroutineScope staleScope()
            processConnectionResult(context, result, job)
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
        requestJob: Job? = null,
    ): ApiResult<T> {
        if (!isCurrent(context)) return staleScope()
        beforeConnectionResultPublicationForTesting()
        var transition: AuthorityTransition? = null
        var reason = AuthorityInvalidation.ACCESS_FROZEN
        val publishedResult: ApiResult<T> = synchronized(readAdmissionLock) {
            if (!isCurrent(context)) return staleScope()
            val responseAuthority = when (result) {
                is ApiResult.Success -> result.controlAuthority
                is ApiResult.NotModified -> result.controlAuthority
                is ApiResult.Failure -> null
            }
            val authority = responseAuthority?.let { "${it.leadId}/${it.epoch}" }
            if (authority != null && observedLeadAuthority != null && observedLeadAuthority != authority) {
                if (requestJob != null) scopeJobs.remove(requestJob)
                reason = AuthorityInvalidation.LEAD_CHANGED
                transition = prepareAuthorityTransitionLocked {
                    observedLeadAuthority = authority
                    leadEpoch += 1
                    true
                }
                staleScope()
            } else if (result is ApiResult.Failure) {
                val http = result.error as? ApiFailure.Http
                val pairingRetired = http?.retiresPairing() == true
                val accessFrozen = http?.status in setOf(401, 403)
                if (pairingRetired || accessFrozen) {
                    if (requestJob != null) scopeJobs.remove(requestJob)
                }
                reason = if (pairingRetired) AuthorityInvalidation.PAIRING_RETIRED else AuthorityInvalidation.ACCESS_FROZEN
                transition = prepareAuthorityTransitionLocked(
                    refusal = AuthorityRefusal.from(result.error),
                    change = {
                        if (pairingRetired) {
                            // Only the failed incarnation may be removed, before callbacks/ACK.
                            // Store CAS also fences direct store.save outside the repository lock.
                            if (!retireCredentialLocked(context)) return@prepareAuthorityTransitionLocked false
                        } else if (!matchesCommitted(context)) return@prepareAuthorityTransitionLocked false
                        accessFailure = result.error
                        true
                    },
                    // Ordinary transport/unsupported failures settle visibly on the same read actor.
                    // Access refusals retire all previously admitted reads before a fresh refresh.
                    retiresReadActor = pairingRetired || accessFrozen,
                )
                if (transition == null) staleScope() else result
            } else {
                if (authority != null) observedLeadAuthority = authority
                result
            }
        }
        transition?.let { completeAuthorityTransition(reason, it) }
        return publishedResult
    }

    private fun deviceOwnedAdmission(): Boolean = readSource.value !is ReadSourceState.SavedCopy &&
        (accessFailure as? ApiFailure.Http)?.status !in setOf(401, 403)

    /** Device-owned preferences keep read admission; saved/offline authority cannot dispatch writes. */
    private suspend fun <T> withDeviceOwnedConnection(block: suspend (Connection) -> ApiResult<T>): ApiResult<T> {
        if (!deviceOwnedAdmission()) return staleScope()
        val configured = connection.value ?: return notConnected()
        return withConnectionOf(configured, mutation = true, block = block)
    }

    private suspend fun <T> withWritableConnection(
        scope: Scope? = null,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withWritableConnectionTarget(readTarget(scope, expectedContext = expectedContext), block)

    private suspend fun <T> withWritableConnection(
        scope: Scope,
        subjectIdentity: String?,
        expectedContext: ScopedReadContext? = null,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> = withWritableConnectionTarget(
        readTarget(scope, subjectIdentity, expectedContext, useSelectedSubject = false), block,
    )

    private suspend fun <T> withWritableConnectionTarget(
        target: ReadTarget?,
        block: suspend (Connection) -> ApiResult<T>,
    ): ApiResult<T> {
        target ?: return notConnected()
        val context = target.context
        val requestScope = target.scope
        val requestSubject = target.subjectIdentity
        if (!isCurrent(context) || context.key.scope != requestScope || context.key.subjectIdentity != requestSubject) return staleScope()
        val configured = connection.value ?: return notConnected()
        if (configured !== context.connection) return staleScope()
        if (!configured.isPaired) {
            return ApiResult.Failure(ApiFailure.Protocol("Pair this device before controlling a terminal"))
        }
        if (!writesAllowed(requestScope, requestSubject)) {
            return ApiResult.Failure(ApiFailure.Http(403, "device not authorised", "device.not_authorised"))
        }
        return withConnectionOf(configured, requestScope, requestSubject, mutation = true, expectedContext = context, block = block)
    }

    private fun <T> notConnected(): ApiResult<T> {
        val committed = connectionStore.captureCommitted()
        val configured = if (committed != null) committed.connection else connection.value
        val detail = when {
            configured == null -> "No Collie connection is configured"
            committed != null && committed.incarnationId == null -> "Reconnect to establish current encrypted connection custody"
            else -> "Connection authority is unavailable; refresh after the current transition"
        }
        return ApiResult.Failure(ApiFailure.Protocol(detail))
    }

    private data class CachedPane(
        val pane: PaneReadResponse,
        val etag: String?,
        val lines: Int,
    )

    private data class PaneCacheKey(
        val connection: Connection,
        val address: PaneAddress,
        val incarnationId: String?,
    )

}
