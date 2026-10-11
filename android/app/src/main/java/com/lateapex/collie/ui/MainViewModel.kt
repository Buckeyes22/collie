package com.lateapex.collie.ui

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.StoredLaunchIntent
import com.lateapex.collie.domain.DeviceLabel
import com.lateapex.collie.domain.PollingBackoff
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.AddedLauncherResponse
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorktreeListResponse
import com.lateapex.collie.network.WorktreeOpenResponse
import com.lateapex.collie.network.WorktreePlanResponse
import com.lateapex.collie.network.StartTarget
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class MainUiState(
    val configured: Boolean = false,
    val origin: String? = null,
    val label: String? = null,
    val paired: Boolean = false,
    val writeAuthorized: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val snapshot: SnapshotResponse? = null,
    val error: String? = null,
    val lastSuccessAt: Long? = null,
    val snapshotFailed: Boolean = false,
    val authError: Boolean = false,
    val requestStartedAt: Long? = null,
    val requestSettled: Long = 0,
    val pairingRequired: Boolean = false,
    val muxName: String? = null,
    val mux: MuxConfigResponse? = null,
    val launchers: LaunchersResponse? = null,
    val refreshNotice: String? = null,
    val receiptContextBound: Boolean? = null,
    val unknownLaunchIntents: List<StoredLaunchIntent> = emptyList(),
) {
    val panes: List<PaneSummary>
        get() = snapshot?.let { it.agents + it.shellPanes }.orEmpty()
}

class MainViewModel @JvmOverloads constructor(
    application: Application,
    repositoryOverride: CollieRepository? = null,
) : AndroidViewModel(application) {
    private val repository: CollieRepository = repositoryOverride
        ?: (application as CollieApplication).container.repository
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    val currentScope: Scope get() = repository.selectedScope.value

    private var pollingJob: Job? = null
    private var configRequested = false
    private val requestInFlight = AtomicBoolean(false)
    private val manualRefreshPending = AtomicBoolean(false)
    private val pollingBackoff = PollingBackoff()
    private val scopeBackStack = ScopeBackStack()
    private val nativePreferences = NativePreferences(application)
    private var scopeValidationPending = true
    private var observedConnection = repository.connection.value

    private val authorityRegistration = repository.onAuthorityInvalidated { reason ->
        if (reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN) {
            repository.accessFailure?.takeIf { it is ApiFailure.Http && it.status in setOf(401, 403) }?.let { failure ->
                mutableState.value = mutableState.value.copy(loading = false, error = MainViewModel.describe(getApplication<Application>().resources, failure))
            }
        }
        mutableState.value = mutableState.value.copy(writeAuthorized = false)
        if (reason in setOf(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED,
            com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED, com.lateapex.collie.data.AuthorityInvalidation.DISCONNECTED)) {
            mutableState.value = MainUiState()
        } else if (reason == com.lateapex.collie.data.AuthorityInvalidation.LEAD_CHANGED) {
            mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
        }
    }

    override fun onCleared() {
        authorityRegistration.close()
        super.onCleared()
    }

    init {
        repository.selectScope(nativePreferences.dashboardScope)
        syncConnection()
        viewModelScope.launch {
            repository.connection.collectLatest { connection ->
                val changed = connection != observedConnection
                observedConnection = connection
                if (changed) {
                    configRequested = false
                    scopeValidationPending = true
                }
                val previous = if (changed) mutableState.value.copy(
                    snapshot = null, mux = null, muxName = null, launchers = null, lastSuccessAt = null,
                    refreshing = false, refreshNotice = null,
                ) else mutableState.value
                val revoked = previous.configured && previous.paired && connection?.isPaired == false
                mutableState.value = previous.copy(
                    configured = connection != null,
                    origin = connection?.origin?.value,
                    label = connection?.label,
                    paired = connection?.isPaired == true,
                    writeAuthorized = repository.writesAllowed(),
                    pairingRequired = revoked || (previous.pairingRequired && connection?.isPaired != true),
                )
            }
        }
    }

    fun startPolling(refreshBridge: Boolean = true) {
        repository.selectSubject(currentScope, null)
        if (pollingJob != null || repository.connection.value == null) return
        val activePoll = viewModelScope.launch {
            if (refreshBridge) repository.refresh()
            while (true) {
                loadSnapshot(isManual = false)
                if (!configRequested) {
                    configRequested = true
                    loadConfig()
                }
                delay(pollDelay(mutableState.value.snapshot))
            }
        }
        pollingJob = activePoll
        activePoll.invokeOnCompletion { if (pollingJob === activePoll) pollingJob = null }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun connectReadOnly(origin: String, label: String) {
        val labelError = validateLabel(label)
        if (labelError != null) {
            mutableState.value = mutableState.value.copy(error = labelError)
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val probe = try {
                repository.probe(origin, label.trim())
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(loading = false, error = originError(error))
                return@launch
            }
            when (probe) {
                is ApiResult.Success -> {
                    repository.connectReadOnly(origin, label.trim())
                    scopeValidationPending = true
                    syncConnection()
                    loadSnapshot(isManual = true)
                    startPolling()
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = describe(getApplication<Application>().resources, probe.error),
                )
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = getApplication<Application>().getString(R.string.health_empty),
                )
            }
        }
    }

    fun pair(origin: String, label: String, code: String) {
        val labelError = validateLabel(label)
        if (labelError != null) {
            mutableState.value = mutableState.value.copy(error = labelError)
            return
        }
        if (code.isBlank()) {
            mutableState.value = mutableState.value.copy(
                error = getApplication<Application>().getString(R.string.pairing_code_required),
            )
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = try {
                repository.pair(origin, label.trim(), code.trim())
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(loading = false, error = originError(error))
                return@launch
            }
            when (result) {
                is ApiResult.Success -> when (val pair = result.value) {
                    is PairResult.Paired -> {
                        scopeValidationPending = true
                        syncConnection()
                        loadSnapshot(isManual = true)
                        startPolling()
                    }
                    is PairResult.Refused -> mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = getApplication<Application>().getString(R.string.pairing_refused, pair.reason),
                    )
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = if (
                        result.error is ApiFailure.Timeout ||
                        result.error is ApiFailure.Network ||
                        result.error is ApiFailure.Protocol
                    ) {
                        getApplication<Application>().getString(R.string.pairing_uncertain)
                    } else {
                        describe(getApplication<Application>().resources, result.error)
                    },
                )
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = getApplication<Application>().getString(R.string.pairing_empty),
                )
            }
        }
    }

    fun refresh() {
        if (!manualRefreshPending.compareAndSet(false, true)) return
        val refreshScope = currentScope
        val readContext = repository.readContext(refreshScope, null)
        if (readContext == null) {
            manualRefreshPending.set(false)
            return
        }
        viewModelScope.launch {
            if (!isCurrentRefresh(readContext, refreshScope)) {
                manualRefreshPending.set(false)
                return@launch
            }
            mutableState.value = mutableState.value.copy(refreshing = true, error = null, refreshNotice = null)
            try {
                while (requestInFlight.get() && isCurrentRefresh(readContext, refreshScope)) delay(10)
                if (!isCurrentRefresh(readContext, refreshScope)) return@launch
                when (val refreshed = repository.refresh(refreshScope, readContext)) {
                    is ApiResult.Failure -> {
                        if (!isCurrentRefresh(readContext, refreshScope)) return@launch
                        mutableState.value = mutableState.value.copy(refreshNotice = getApplication<Application>().getString(
                            if (refreshed.error is ApiFailure.Http && refreshed.error.status == 404)
                                R.string.refresh_ordinary_reload else R.string.refresh_failed_fallback,
                        ))
                    }
                    is ApiResult.Success -> if (!refreshed.value.ok) {
                        if (!isCurrentRefresh(readContext, refreshScope)) return@launch
                        mutableState.value = mutableState.value.copy(
                            refreshNotice = getApplication<Application>().getString(R.string.refresh_failed_fallback),
                        )
                    }
                    is ApiResult.NotModified -> Unit
                }
                if (isCurrentRefresh(readContext, refreshScope)) loadSnapshot(
                    isManual = true, scopeOverride = refreshScope, expectedContext = readContext,
                )
            } finally {
                manualRefreshPending.set(false)
                if (isCurrentRefresh(readContext, refreshScope)) {
                    mutableState.value = mutableState.value.copy(refreshing = false)
                }
            }
        }
    }

    private fun isCurrentRefresh(context: ScopedReadContext, scope: Scope): Boolean =
        repository.selectedScope.value == scope && repository.isCurrent(context)

    /** Retry the read path only. The dashboard connection band never invokes a bridge mutation. */
    fun retryConnection() {
        viewModelScope.launch { loadSnapshot(isManual = true) }
    }

    fun selectScope(scope: Scope, remember: Boolean = true) {
        if (repository.selectedScope.value == scope) return
        if (remember) scopeBackStack.record(repository.selectedScope.value)
        applyScope(scope)
    }

    /** Mirrors browser history for dashboard host/session switches made in-place. */
    fun navigateBackScope(): Boolean {
        val previous = scopeBackStack.pop() ?: return false
        applyScope(previous)
        return true
    }

    private fun applyScope(scope: Scope) {
        stopPolling()
        repository.selectScope(scope)
        nativePreferences.dashboardScope = scope
        configRequested = false
        mutableState.value = mutableState.value.copy(
            loading = true,
            refreshing = false,
            snapshot = null,
            launchers = null,
            error = null,
            snapshotFailed = false,
            authError = false,
            refreshNotice = null,
        )
        startPolling()
    }

    fun disconnect() {
        stopPolling()
        scopeBackStack.clear()
        nativePreferences.dashboardScope = Scope()
        repository.selectScope(Scope())
        scopeValidationPending = true
        viewModelScope.launch {
            repository.disconnect()
            configRequested = false
            mutableState.value = MainUiState()
        }
    }

    suspend fun createWorkspace(label: String?, cwd: String?, scope: Scope = currentScope): ApiResult<CreateResponse> =
        repository.createWorkspace(scope = scope, label = label, cwd = cwd)

    suspend fun launch(command: String): ApiResult<CreateResponse> = repository.launch(command)

    suspend fun launchIntent(
        target: StartTarget,
        requestId: String,
        cwd: String?,
        confirmedNoPrompts: Boolean,
        scope: Scope = currentScope,
    ): ApiResult<CreateResponse> {
        val actor = repository.readContext(scope)
        val result = repository.startLaunchIntent(scope, target, requestId, cwd, confirmedNoPrompts)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun retryLaunchIntent(requestId: String, scope: Scope = currentScope): ApiResult<CreateResponse> {
        val actor = repository.readContext(scope)
        val result = repository.retryLaunchIntent(scope, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    fun abandonLaunchIntent(requestId: String, scope: Scope = currentScope): ApiResult<ActionResponse> {
        val actor = repository.readContext(scope)
        val result = repository.abandonLaunchIntent(scope, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun createWorktreeIntent(
        cwd: String,
        branch: String,
        target: StartTarget,
        requestId: String,
        base: com.lateapex.collie.network.WorktreeBase = com.lateapex.collie.network.WorktreeBase(),
        folder: com.lateapex.collie.network.WorktreeFolder = com.lateapex.collie.network.WorktreeFolder(),
        confirmedNoPrompts: Boolean,
        scope: Scope = currentScope,
    ): ApiResult<WorktreeOpenResponse> {
        val actor = repository.readContext(scope)
        val result = repository.createWorktreeAtIntent(scope, cwd, branch, target, requestId, base, folder, confirmedNoPrompts)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun retryWorktreeIntent(requestId: String, scope: Scope = currentScope): ApiResult<WorktreeOpenResponse> {
        val actor = repository.readContext(scope)
        val result = repository.retryWorktreeIntent(scope, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun addLauncherIntent(requestId: String, canonicalBody: String, scope: Scope = currentScope): ApiResult<AddedLauncherResponse> {
        val actor = repository.readContext(scope)
        val result = repository.addLauncherIntent(scope, requestId, canonicalBody)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun retryAddedLauncherIntent(requestId: String, scope: Scope = currentScope): ApiResult<AddedLauncherResponse> {
        val actor = repository.readContext(scope)
        val result = repository.retryAddedLauncherIntent(scope, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun createWorkspaceWorktreeIntent(
        workspaceId: String,
        branch: String,
        requestId: String,
        scope: Scope = currentScope,
    ): ApiResult<WorktreeOpenResponse> {
        val actor = repository.readContext(scope)
        val result = repository.createWorkspaceWorktreeIntent(scope, workspaceId, branch, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    suspend fun createWorkspaceWorktreeLegacyInitial(
        workspaceId: String,
        branch: String,
        requestId: String,
        scope: Scope = currentScope,
    ): ApiResult<WorktreeOpenResponse> {
        val actor = repository.readContext(scope)
        val result = repository.createWorkspaceWorktreeLegacyInitial(scope, workspaceId, branch, requestId)
        if (actor != null && !repository.isCurrent(actor)) return staleScopeResult()
        if (scope == currentScope) refreshLaunchIntentState(scope)
        return result
    }

    fun refreshLaunchIntentState(scope: Scope = currentScope) {
        if (scope == currentScope) {
            mutableState.value = mutableState.value.copy(unknownLaunchIntents = repository.unresolvedLaunchIntents(scope))
        }
    }

    private fun <T> staleScopeResult(): ApiResult<T> = ApiResult.Failure(
        ApiFailure.Protocol("Connection or scope changed; refresh before trying again"),
    )

    suspend fun listWorktrees(workspaceId: String, scope: Scope = currentScope): ApiResult<WorktreeListResponse> =
        repository.listWorktrees(workspaceId, scope)

    suspend fun planWorktree(cwd: String, branch: String?, parent: String?, scope: Scope = currentScope): ApiResult<WorktreePlanResponse> =
        repository.planWorktree(scope, cwd, branch, parent)

    suspend fun createWorktree(
        workspaceId: String,
        branch: String,
        scope: Scope = currentScope,
    ): ApiResult<WorktreeOpenResponse> = repository.createWorktree(workspaceId, branch, scope)

    suspend fun openWorktree(
        workspaceId: String,
        path: String,
        scope: Scope = currentScope,
    ): ApiResult<WorktreeOpenResponse> = repository.openWorktree(workspaceId, path, scope)

    fun report(error: String?) {
        mutableState.value = mutableState.value.copy(error = error, snapshotFailed = false, authError = false)
    }

    private suspend fun loadConfig() {
        val result = repository.config()
        if (result is ApiResult.Success) {
            mutableState.value = mutableState.value.copy(
                muxName = result.value.mux?.name?.trim()?.takeIf(String::isNotEmpty),
                mux = result.value.mux,
                receiptContextBound = result.value.receiptContextBound,
            )
            val launchers = repository.launchers()
            if (launchers is ApiResult.Success) {
                mutableState.value = mutableState.value.copy(launchers = launchers.value)
            }
            refreshLaunchIntentState()
        }
    }

    private suspend fun loadSnapshot(
        isManual: Boolean,
        scopeOverride: Scope? = null,
        expectedContext: ScopedReadContext? = null,
    ) {
        if (!isManual && manualRefreshPending.get()) return
        if (!requestInFlight.compareAndSet(false, true)) return
        try {
            mutableState.value = mutableState.value.copy(
                loading = mutableState.value.snapshot == null,
                error = if (mutableState.value.snapshot == null) null else mutableState.value.error,
                requestStartedAt = System.currentTimeMillis(),
            )
            val requestedScope = scopeOverride ?: repository.selectedScope.value
            val context = expectedContext ?: repository.readContext(requestedScope) ?: return
            val initialScope = if (scopeValidationPending) Scope() else requestedScope
            val initialContext = if (initialScope == requestedScope) context else {
                if (!repository.isCurrent(context)) return
                repository.readContext(initialScope) ?: return
            }
            var result = repository.snapshot(initialScope, initialContext)
            if (!repository.isCurrent(context)) return
            if (result is ApiResult.Success && scopeValidationPending) {
                val restore = DashboardScopePersistence.resolve(requestedScope, result.value)
                scopeValidationPending = false
                if (restore.scope != requestedScope) {
                    repository.selectScope(restore.scope)
                    nativePreferences.dashboardScope = restore.scope
                    configRequested = false
                    mutableState.value = mutableState.value.copy(launchers = null)
                }
                restore.readScope?.let { restoredScope ->
                    val restoredContext = repository.readContext(restoredScope) ?: return
                    result = repository.snapshot(restoredScope, restoredContext)
                    if (!repository.isCurrent(restoredContext)) return
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    pollingBackoff.succeeded()
                    val now = System.currentTimeMillis()
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                        snapshot = result.value,
                        writeAuthorized = repository.writesAllowed(),
                        error = null,
                        lastSuccessAt = now.takeIf { result.value.bridge != "disconnected" }
                            ?: mutableState.value.lastSuccessAt,
                        snapshotFailed = false,
                        authError = false,
                        refreshNotice = if (isManual) mutableState.value.refreshNotice else null,
                    )
                }
                is ApiResult.Failure -> {
                    pollingBackoff.failed()
                    val pairingRequired = result.error is ApiFailure.Http && result.error.retiresPairing()
                    val authError = result.error is ApiFailure.Redirect ||
                        result.error is ApiFailure.Http && result.error.status in setOf(401, 403)
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = describe(getApplication<Application>().resources, result.error),
                        paired = if (pairingRequired) false else mutableState.value.paired,
                        writeAuthorized = if (pairingRequired) false else repository.writesAllowed(),
                        pairingRequired = pairingRequired,
                        snapshotFailed = !pairingRequired,
                        authError = authError && !pairingRequired,
                    )
                }
                is ApiResult.NotModified -> {
                    pollingBackoff.succeeded()
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        lastSuccessAt = System.currentTimeMillis().takeIf {
                            mutableState.value.snapshot?.bridge != "disconnected"
                        } ?: mutableState.value.lastSuccessAt,
                        snapshotFailed = false,
                        authError = false,
                        refreshNotice = if (isManual) mutableState.value.refreshNotice else null,
                    )
                }
            }
        } finally {
            requestInFlight.set(false)
            mutableState.value = mutableState.value.copy(
                refreshing = if (isManual) false else mutableState.value.refreshing,
                requestStartedAt = null,
                requestSettled = mutableState.value.requestSettled + 1,
            )
        }
    }

    private fun syncConnection() {
        val connection = repository.connection.value
        mutableState.value = mutableState.value.copy(
            configured = connection != null,
            origin = connection?.origin?.value,
            label = connection?.label,
            paired = connection?.isPaired == true,
            writeAuthorized = repository.writesAllowed(),
            pairingRequired = false,
            loading = false,
            snapshotFailed = false,
            authError = false,
            requestStartedAt = null,
        )
    }

    private fun pollDelay(snapshot: SnapshotResponse?): Long {
        val base = snapshot?.let(DashboardHostHealthModel::pollMs) ?: 5_000L
        return pollingBackoff.delayAfter(base)
    }

    private fun validateLabel(label: String): String? = when {
        label.isBlank() -> getApplication<Application>().getString(R.string.device_label_required)
        DeviceLabel.normalize(label).length > DeviceLabel.MAX_CHARS -> getApplication<Application>().getString(R.string.device_label_too_long)
        else -> null
    }

    private fun originError(error: IllegalArgumentException): String =
        error.message?.substringAfter("Invalid Collie origin: ", "")?.takeIf(String::isNotBlank)?.let {
            getApplication<Application>().getString(R.string.origin_invalid_detail, it)
        } ?: getApplication<Application>().getString(R.string.origin_invalid)

    companion object {
        fun describe(resources: Resources, failure: ApiFailure): String = when (failure) {
            is ApiFailure.Redirect -> resources.getString(R.string.error_proxy_sign_in, failure.status)
            is ApiFailure.Http -> when {
                failure.source == com.lateapex.collie.network.FailureSource.GATEWAY && failure.factor == com.lateapex.collie.network.FailureFactor.IDENTITY -> resources.getString(R.string.proxy_identity_required)
                failure.source == com.lateapex.collie.network.FailureSource.GATEWAY && failure.factor == com.lateapex.collie.network.FailureFactor.DEVICE -> resources.getString(R.string.proxy_identity_binding_required)
                failure.retiresPairing() -> resources.getString(R.string.error_device_not_paired)
                failure.message == "device not authorised" -> resources.getString(R.string.error_device_unauthorised)
                else -> failure.message ?: resources.getString(R.string.error_http, failure.status)
            }
            is ApiFailure.Network -> failure.message ?: resources.getString(R.string.error_network)
            is ApiFailure.Protocol -> failure.message
            ApiFailure.Timeout -> resources.getString(R.string.error_timeout)
        }
    }
}

/** Small, lifecycle-independent model so dashboard scope navigation survives Activity recreation. */
internal class ScopeBackStack {
    private val scopes = ArrayDeque<Scope>()

    fun record(scope: Scope) {
        if (scopes.lastOrNull() != scope) scopes.addLast(scope)
    }

    fun pop(): Scope? = scopes.removeLastOrNull()

    fun clear() = scopes.clear()
}

/** Validates durable navigation against an unscoped fresh roster before it drives the dashboard. */
internal object DashboardScopePersistence {
    /**
     * Resolves a durable selection from an unscoped roster. A non-null [Restore.readScope]
     * deliberately requires a second read: the validating roster must never masquerade as the
     * filtered dashboard response.
     */
    fun resolve(scope: Scope, snapshot: SnapshotResponse): Restore {
        val servers = snapshot.servers.orEmpty()
        val leadId = servers.firstOrNull { it.isLead }?.id
        val host = when {
            scope.host == null -> null
            servers.any { it.id == scope.host } -> scope.host
            else -> return Restore(Scope(), null)
        }
        if (scope.viewAll) return reread(Scope(host = host, viewAll = true))
        val session = scope.session ?: return reread(Scope(host = host))
        val hostKey = host ?: leadId
        val available = snapshot.sessions.any {
            it.name == session && it.reachable && (it.host == null || it.host == hostKey)
        }
        return reread(if (available) Scope(host = host, session = session) else Scope(host = host))
    }

    fun validate(scope: Scope, snapshot: SnapshotResponse): Scope = resolve(scope, snapshot).scope

    private fun reread(scope: Scope): Restore = Restore(scope, scope.takeUnless { it == Scope() })

    data class Restore(val scope: Scope, val readScope: Scope?)
}

/** A Space inherits an explicit ambient session; absence deliberately resolves to server primary. */
internal object DashboardNavigationModel {
    fun spaceSession(scope: Scope): String? = scope.session
}
