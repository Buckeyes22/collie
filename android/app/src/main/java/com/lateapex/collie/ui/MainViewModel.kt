package com.lateapex.collie.ui

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.PollingBackoff
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorktreeListResponse
import com.lateapex.collie.network.WorktreeOpenResponse
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
) {
    val panes: List<PaneSummary>
        get() = snapshot?.let { it.agents + it.shellPanes }.orEmpty()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    val currentScope: Scope get() = repository.selectedScope.value

    private var pollingJob: Job? = null
    private var configRequested = false
    private val requestInFlight = AtomicBoolean(false)
    private val pollingBackoff = PollingBackoff()
    private val scopeBackStack = ScopeBackStack()
    private val nativePreferences = NativePreferences(application)
    private var scopeValidationPending = true

    init {
        repository.selectScope(nativePreferences.dashboardScope)
        syncConnection()
        viewModelScope.launch {
            repository.connection.collectLatest { connection ->
                val previous = mutableState.value
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
        if (pollingJob != null || repository.connection.value == null) return
        pollingJob = viewModelScope.launch {
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
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(refreshing = true, error = null)
            try {
                repository.refresh()
                loadSnapshot(isManual = true)
            } finally {
                // A poll may already own the snapshot request. Pull-to-refresh must still stop its
                // spinner when that happens.
                mutableState.value = mutableState.value.copy(refreshing = false)
            }
        }
    }

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
            snapshot = null,
            launchers = null,
            error = null,
            snapshotFailed = false,
            authError = false,
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

    suspend fun listWorktrees(workspaceId: String, scope: Scope = currentScope): ApiResult<WorktreeListResponse> =
        repository.listWorktrees(workspaceId, scope)

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
            )
            val launchers = repository.launchers()
            if (launchers is ApiResult.Success) {
                mutableState.value = mutableState.value.copy(launchers = launchers.value)
            }
        }
    }

    private suspend fun loadSnapshot(isManual: Boolean) {
        if (!requestInFlight.compareAndSet(false, true)) return
        try {
            mutableState.value = mutableState.value.copy(
                loading = mutableState.value.snapshot == null,
                error = if (mutableState.value.snapshot == null) null else mutableState.value.error,
                requestStartedAt = System.currentTimeMillis(),
            )
            val requestedScope = repository.selectedScope.value
            var result = repository.snapshot(if (scopeValidationPending) Scope() else requestedScope)
            if (result is ApiResult.Success && scopeValidationPending) {
                val restore = DashboardScopePersistence.resolve(requestedScope, result.value)
                scopeValidationPending = false
                if (restore.scope != requestedScope) {
                    repository.selectScope(restore.scope)
                    nativePreferences.dashboardScope = restore.scope
                    configRequested = false
                    mutableState.value = mutableState.value.copy(launchers = null)
                }
                restore.readScope?.let { result = repository.snapshot(it) }
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
                    )
                }
                is ApiResult.Failure -> {
                    pollingBackoff.failed()
                    val pairingRequired = result.error is ApiFailure.Http &&
                        result.error.message == "device not paired"
                    if (pairingRequired) repository.demoteToReadOnly()
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
        label.trim().length > 80 -> getApplication<Application>().getString(R.string.device_label_too_long)
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
                failure.message == "device not paired" -> resources.getString(R.string.error_device_not_paired)
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
