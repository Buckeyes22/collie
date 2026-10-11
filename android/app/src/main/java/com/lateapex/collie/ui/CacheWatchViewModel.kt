package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CacheRule
import com.lateapex.collie.network.CacheWatchResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CacheWatchScreenState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val pane: PaneSummary? = null,
    val snapshotAt: Long? = null,
    val watch: CacheWatchResponse? = null,
    val rule: CacheRule? = null,
    val catalogUnavailable: Boolean = false,
    val error: CacheWatchMessage? = null,
    val actor: ScopedReadContext? = null,
    val generation: Long = 0,
)

internal object CacheWatchControlState {
    fun switchEnabled(state: CacheWatchScreenState): Boolean =
        !state.loading && !state.busy && state.watch?.let { it.watchable || it.on } == true
}

internal enum class CacheWatchMessage { NOT_CONNECTED, UNSUPPORTED, NOT_FOUND, UNAVAILABLE, STALE, MALFORMED }

/** One pane's sourced cache facts and lead-owned watch preference; no transcript or secret is saved. */
internal class CacheWatchViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val repository: CollieRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application,
        savedState,
        (application as CollieApplication).container.repository,
    )

    val address = PaneAddress(
        Scope(
            host = savedState.get<String>(KEY_HOST)?.takeIf(String::isNotBlank),
            session = savedState.get<String>(KEY_SESSION)?.takeIf(String::isNotBlank),
        ),
        savedState.get<String>(KEY_PANE_ID)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing pane identity"),
    )
    private val stateFlow = MutableStateFlow(CacheWatchScreenState())
    val state: StateFlow<CacheWatchScreenState> = stateFlow.asStateFlow()
    // Repository admission/cancellation and acknowledged View callbacks NEVER run under this lock.
    // A retirement and every result/catalog publication share one atomic custody boundary.
    private val custodyLock = Any()
    private var requestGeneration = 0L
    private var admissionEpoch = 0L
    private var catalogEtag: String? = null
    private var cachedRules: Map<String, CacheRule>? = null
    private var context: ScopedReadContext? = null
    private var activeJob: Job? = null
    private var disposed = false
    private val retirementListeners = linkedSetOf<(CacheWatchScreenState) -> Unit>()
    private val authorityRegistration: AutoCloseable
    private val connectionObservation: Job

    init {
        repository.selectSubject(address.scope, address.paneId)
        authorityRegistration = repository.onReadContextInvalidated { reason ->
            retire(when (reason) {
                AuthorityInvalidation.ACCESS_FROZEN, AuthorityInvalidation.PAIRING_RETIRED -> CacheWatchMessage.UNAVAILABLE
                else -> CacheWatchMessage.STALE
            }, invalidateAdmission = reason != AuthorityInvalidation.CONNECTION_CHANGED)
        }
        // The repository notices credential replacement on its next readContext call.
        // Observe the real store flow so screen custody retires without an extra read.
        connectionObservation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.connection.collect { changed -> retireChangedConnection(changed) }
        }
        refresh()
    }

    fun onRetired(listener: (CacheWatchScreenState) -> Unit): AutoCloseable {
        synchronized(custodyLock) { retirementListeners.add(listener) }
        return AutoCloseable { synchronized(custodyLock) { retirementListeners.remove(listener) } }
    }

    fun stop() = retire(CacheWatchMessage.STALE)

    private data class Retirement(
        val job: Job?,
        val listeners: List<(CacheWatchScreenState) -> Unit>,
        val frame: CacheWatchScreenState,
    )

    /** Called only within custodyLock; performs no admission, cancellation or callback. */
    private fun retirementLocked(message: CacheWatchMessage, invalidateAdmission: Boolean = true): Retirement {
        requestGeneration++
        if (invalidateAdmission) admissionEpoch++
        context = null
        catalogEtag = null
        cachedRules = null
        val previous = activeJob
        activeJob = null
        val frame = CacheWatchScreenState(error = message, generation = requestGeneration)
        stateFlow.value = frame
        return Retirement(previous, retirementListeners.toList(), frame)
    }

    private fun finishRetirement(retirement: Retirement) {
        retirement.job?.cancel()
        retirement.listeners.forEach { it(retirement.frame) }
    }

    private fun retire(message: CacheWatchMessage, invalidateAdmission: Boolean = true) {
        val retirement = synchronized(custodyLock) { retirementLocked(message, invalidateAdmission) }
        finishRetirement(retirement)
    }

    private fun retireChangedConnection(changed: Connection?) {
        val retirement = synchronized(custodyLock) {
            val actor = context
            // StateFlow may deliver a queued notification after a fresh refresh. Match the
            // actual current connection and the actor still held by THIS custody generation.
            // This is a nonblocking identity read, never repository admission under this lock.
            if (disposed || changed !== repository.connection.value || actor == null || actor.connection === changed) null
            else retirementLocked(if (changed == null) CacheWatchMessage.NOT_CONNECTED else CacheWatchMessage.STALE)
        }
        retirement?.let(::finishRetirement)
    }

    fun ownsFrame(frame: CacheWatchScreenState): Boolean = synchronized(custodyLock) {
        !disposed && frame === stateFlow.value && frame.generation == requestGeneration
    }

    fun isCurrentPresentation(frame: CacheWatchScreenState): Boolean {
        val actor = frame.actor ?: return false
        if (!repository.isCurrent(actor)) return false
        return synchronized(custodyLock) {
            !disposed && frame === stateFlow.value && frame.generation == requestGeneration && actor === context
        }
    }

    /** A completion may commit only the exact admitted loading frame, never a replacement actor. */
    private fun publishIfCurrent(
        loading: CacheWatchScreenState,
        result: CacheWatchScreenState,
        rules: Map<String, CacheRule>? = null,
        etag: String? = null,
        updateCatalog: Boolean = false,
    ): Boolean {
        val actor = loading.actor ?: return false
        if (!repository.isCurrent(actor)) return false
        return synchronized(custodyLock) {
            if (disposed || context !== actor || requestGeneration != loading.generation || stateFlow.value !== loading ||
                repository.connection.value !== actor.connection) false
            else {
                if (updateCatalog) {
                    cachedRules = rules
                    catalogEtag = etag
                }
                stateFlow.value = result.copy(actor = actor, generation = loading.generation)
                activeJob = null
                true
            }
        }
    }

    private fun start(loading: CacheWatchScreenState, work: suspend () -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { work() }
        val admitted = synchronized(custodyLock) {
            if (!disposed && stateFlow.value === loading && context === loading.actor && requestGeneration == loading.generation) {
                activeJob = job
                true
            } else false
        }
        if (admitted) job.start() else job.cancel()
    }

    fun refresh() {
        repository.selectSubject(address.scope, address.paneId)
        val (admission, oldJob, oldActor) = synchronized(custodyLock) {
            if (disposed) return
            requestGeneration++
            admissionEpoch++
            val prior = activeJob
            activeJob = null
            Triple(admissionEpoch, prior, context)
        }
        oldJob?.cancel()
        val actor = oldActor?.takeIf(repository::isCurrent) ?: repository.readContext(address.scope, address.paneId)
        if (actor == null) {
            synchronized(custodyLock) {
                if (!disposed && admissionEpoch == admission) stateFlow.value = CacheWatchScreenState(error = CacheWatchMessage.NOT_CONNECTED, generation = requestGeneration)
            }
            return
        }
        if (!repository.isCurrent(actor)) return
        val loading = synchronized(custodyLock) {
            if (disposed || admission != admissionEpoch || repository.connection.value !== actor.connection) return
            // readContext can synchronously publish CONNECTION_CHANGED while acquiring a
            // replacement actor. That callback retires old custody, but this explicit fresh
            // admission may continue. Stop, other retirements and newer requests revoke its epoch.
            requestGeneration++
            val previous = if (context === actor) stateFlow.value else CacheWatchScreenState()
            if (context !== actor) { catalogEtag = null; cachedRules = null }
            context = actor
            previous.copy(loading = true, busy = false, error = null, actor = actor, generation = requestGeneration)
                .also { stateFlow.value = it }
        }
        val catalog = synchronized(custodyLock) { cachedRules to catalogEtag }
        start(loading) {
            val snapshot = repository.paneSnapshot(address.scope, actor)
            if (!isCurrentPresentation(loading)) return@start
            if (snapshot !is ApiResult.Success) {
                publishIfCurrent(loading, CacheWatchScreenState(error = message(snapshot)))
                return@start
            }
            val pane = findPane(snapshot.value)
            if (pane == null) {
                publishIfCurrent(loading, CacheWatchScreenState(error = CacheWatchMessage.NOT_FOUND))
                return@start
            }
            val watch = repository.cacheWatch(address, actor)
            if (!isCurrentPresentation(loading)) return@start
            if (watch !is ApiResult.Success) {
                publishIfCurrent(loading, CacheWatchScreenState(error = message(watch), pane = pane, snapshotAt = snapshot.value.ts))
                return@start
            }
            var rules = catalog.first
            var etag = catalog.second
            var updateCatalog = false
            var rule: CacheRule? = null
            var catalogUnavailable = false
            if (address.scope.host == null && pane.cache?.ruleId?.isNotBlank() == true) {
                when (val response = repository.cacheRules(address.scope, address.paneId, etag, actor)) {
                    is ApiResult.Success -> {
                        rules = response.value.rules.associateBy(CacheRule::id)
                        etag = response.etag
                        updateCatalog = true
                    }
                    is ApiResult.NotModified -> if (rules == null || etag == null) catalogUnavailable = true
                    is ApiResult.Failure -> catalogUnavailable = true
                }
                rule = if (catalogUnavailable) null else rules?.get(pane.cache?.ruleId)
                if (rule == null) catalogUnavailable = true
            } else if (address.scope.host != null && pane.cache?.ruleId?.isNotBlank() == true) {
                catalogUnavailable = true
            }
            publishIfCurrent(loading, CacheWatchScreenState(
                pane = pane, snapshotAt = snapshot.value.ts, watch = watch.value,
                rule = rule, catalogUnavailable = catalogUnavailable,
            ), rules, etag, updateCatalog)
        }
    }

    fun setWatching(on: Boolean, displayed: CacheWatchScreenState = stateFlow.value) {
        val watch = displayed.watch ?: return
        if (displayed.busy || displayed.loading || (on && !watch.watchable) || watch.on == on) return
        if (!isCurrentPresentation(displayed)) return
        val actor = displayed.actor ?: return
        val loading = synchronized(custodyLock) {
            if (disposed || stateFlow.value !== displayed || context !== actor || requestGeneration != displayed.generation ||
                repository.connection.value !== actor.connection) return
            requestGeneration++
            admissionEpoch++
            displayed.copy(busy = true, error = null, generation = requestGeneration).also { stateFlow.value = it }
        }
        start(loading) {
            val result = repository.setCacheWatch(address, on, actor)
            publishIfCurrent(loading, when (result) {
                is ApiResult.Success -> loading.copy(busy = false, watch = result.value)
                else -> loading.copy(busy = false, error = message(result))
            })
        }
    }

    private fun findPane(snapshot: SnapshotResponse): PaneSummary? =
        (snapshot.agents + snapshot.shellPanes).filter {
            it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
        }.singleOrNull()

    private fun message(result: ApiResult<*>): CacheWatchMessage = when (result) {
        is ApiResult.Failure -> {
            val failure = result.error as? ApiFailure.Http
            when {
                failure?.code == "cache.no_session" -> CacheWatchMessage.UNAVAILABLE
                failure?.code == "cache.pane_unknown" -> CacheWatchMessage.NOT_FOUND
                failure?.status == 404 || failure?.status == 501 -> CacheWatchMessage.UNSUPPORTED
                failure?.status == 409 -> CacheWatchMessage.STALE
                failure?.status == 400 -> CacheWatchMessage.MALFORMED
                else -> CacheWatchMessage.UNAVAILABLE
            }
        }
        is ApiResult.NotModified -> CacheWatchMessage.MALFORMED
        is ApiResult.Success<*> -> CacheWatchMessage.UNAVAILABLE
    }

    override fun onCleared() {
        synchronized(custodyLock) { disposed = true }
        connectionObservation.cancel()
        retire(CacheWatchMessage.STALE)
        authorityRegistration.close()
        super.onCleared()
    }

    companion object {
        const val KEY_PANE_ID = "cache_watch_pane_id"
        const val KEY_HOST = "cache_watch_host"
        const val KEY_SESSION = "cache_watch_session"
    }
}
