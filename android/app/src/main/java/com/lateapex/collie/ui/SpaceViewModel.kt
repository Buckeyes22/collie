package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PollingBackoff
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.UpdateInfo
import com.lateapex.collie.network.WorkspaceSummary
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class SpaceTab(
    val tab: TabSummary,
    val panes: List<PaneSummary>,
)

internal data class SpaceContent(
    val workspace: WorkspaceSummary,
    val tabs: List<SpaceTab>,
    /** Sibling spaces shown in the route-level Spaces strip. */
    val workspaces: List<WorkspaceSummary>,
    /** Agent panes only, used for status/brand summaries. Shell panes remain in [SpaceTab.panes]. */
    val agents: List<PaneSummary>,
    /** Root update state used by the same route footer as the web Space screen. */
    val update: UpdateInfo? = null,
)

internal object SpaceModel {
    fun from(snapshot: SnapshotResponse, workspaceId: String, scope: Scope): SpaceContent? {
        val workspace = snapshot.workspaces.firstOrNull { item ->
            item.workspaceId == workspaceId && item.host == scope.host
        } ?: return null
        val scopedAgents = snapshot.agents.filter { pane ->
            pane.host == scope.host && (scope.session == null || pane.session == scope.session)
        }
        val shells = snapshot.shellPanes.filter { pane ->
            pane.workspaceId == workspaceId && pane.host == scope.host &&
                (scope.session == null || pane.session == scope.session)
        }
        val panes = scopedAgents.filter { it.workspaceId == workspaceId } + shells
        val tabs = snapshot.tabs
            .filter { tab -> tab.workspaceId == workspaceId && tab.host == scope.host }
            .sortedBy(TabSummary::number)
            .map { tab -> SpaceTab(tab, panes.filter { it.tabId == tab.tabId }) }
        val siblings = snapshot.workspaces
            .filter { item -> item.host == scope.host }
            .sortedBy(WorkspaceSummary::number)
        return SpaceContent(workspace, tabs, siblings, scopedAgents, snapshot.update)
    }
}

internal data class SpaceUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val content: SpaceContent? = null,
    val error: String? = null,
    val mux: MuxConfigResponse? = null,
    val paired: Boolean = false,
    val writeAuthorized: Boolean = false,
    val readSource: ReadSourceState = ReadSourceState.Unavailable(0),
    val refreshNotice: String? = null,
)

internal class SpaceViewModel(
    application: Application,
    private val workspaceId: String,
    private val scope: Scope,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SpaceUiState())
    val state: StateFlow<SpaceUiState> = mutableState.asStateFlow()
    private val requestInFlight = AtomicBoolean(false)
    private val manualRefreshPending = AtomicBoolean(false)
    private val pollingBackoff = PollingBackoff()
    private var pollingJob: Job? = null
    private var observedConnection = repository.connection.value
    private var lastGoodCapturedAt: Long? = null

    private val authorityRegistration = repository.onAuthorityInvalidated { reason ->
        if (reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN) {
            repository.accessFailure?.takeIf { it is ApiFailure.Http && it.status in setOf(401, 403) }?.let { failure ->
                mutableState.value = mutableState.value.copy(loading = false, error = MainViewModel.describe(getApplication<Application>().resources, failure))
            }
        }
        mutableState.value = mutableState.value.copy(writeAuthorized = false)
        if (reason in setOf(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED,
            com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED,
            com.lateapex.collie.data.AuthorityInvalidation.DISCONNECTED,
            com.lateapex.collie.data.AuthorityInvalidation.SCOPE_CHANGED)) {
            lastGoodCapturedAt = null
            mutableState.value = SpaceUiState()
        } else if (reason == com.lateapex.collie.data.AuthorityInvalidation.LEAD_CHANGED) {
            mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
        }
    }

    override fun onCleared() {
        authorityRegistration.close()
        super.onCleared()
    }

    init {
        repository.selectSubject(scope, workspaceId)
        mutableState.value = mutableState.value.copy(
            paired = repository.connection.value?.isPaired == true,
            writeAuthorized = repository.writesAllowed(),
        )
        viewModelScope.launch {
            repository.readSource.collect { source ->
                mutableState.value = mutableState.value.copy(readSource = source, writeAuthorized = repository.writesAllowed(scope, workspaceId))
            }
        }
        viewModelScope.launch {
            repository.connection.collectLatest { connection ->
                if (connection !== observedConnection) {
                    observedConnection = connection
                    mutableState.value = SpaceUiState()
                    loadConfig()
                }
                mutableState.value = mutableState.value.copy(
                    paired = connection?.isPaired == true,
                    writeAuthorized = repository.writesAllowed(),
                )
            }
        }
    }

    fun startPolling() {
        repository.selectSubject(scope, workspaceId)
        if (pollingJob != null) return
        val activePoll = viewModelScope.launch {
            loadConfig()
            while (true) {
                load(manual = false)
                delay(pollingBackoff.delayAfter(POLL_MS))
            }
        }
        pollingJob = activePoll
        activePoll.invokeOnCompletion { if (pollingJob === activePoll) pollingJob = null }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh() {
        if (!manualRefreshPending.compareAndSet(false, true)) return
        val context = repository.readContext(scope, workspaceId)
        if (context == null) {
            manualRefreshPending.set(false)
            return
        }
        mutableState.value = mutableState.value.copy(refreshing = true, error = null)
        viewModelScope.launch {
            try {
                while (requestInFlight.get() && repository.isCurrent(context)) delay(10)
                if (repository.isCurrent(context)) load(manual = true, expectedContext = context)
            } finally {
                manualRefreshPending.set(false)
                if (repository.isCurrent(context)) {
                    mutableState.value = mutableState.value.copy(refreshing = false)
                }
            }
        }
    }

    suspend fun createTab(label: String?, cwd: String?): ApiResult<CreateResponse> =
        repository.createTab(scope, workspaceId, label, cwd)

    suspend fun renameTab(tabId: String, label: String): ApiResult<ActionResponse> =
        repository.renameTab(scope, tabId, label)

    suspend fun closeTab(tabId: String): ApiResult<ActionResponse> = repository.closeTab(scope, tabId)

    suspend fun createWorkspace(label: String?, cwd: String?): ApiResult<CreateResponse> =
        repository.createWorkspace(scope, label, cwd)

    fun report(message: String?) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    private suspend fun loadConfig() {
        val context = repository.readContext(scope, workspaceId) ?: return
        val result = repository.config(scope)
        if (!repository.isCurrent(context)) return
        if (result is ApiResult.Success) {
            mutableState.value = mutableState.value.copy(mux = result.value.mux)
        }
    }

    private suspend fun load(manual: Boolean, expectedContext: ScopedReadContext? = null) {
        if (!manual && manualRefreshPending.get()) return
        if (!requestInFlight.compareAndSet(false, true)) return
        val context = expectedContext ?: repository.readContext(scope, workspaceId)
            ?: run { requestInFlight.set(false); return }
        if (!repository.isCurrent(context)) {
            requestInFlight.set(false)
            return
        }
        mutableState.value = mutableState.value.copy(
            loading = mutableState.value.content == null,
            refreshing = manual,
            error = null,
        )
        try {
            var refreshNotice: String? = null
            if (manual) {
                val refresh = repository.refresh(scope, context)
                if (!repository.isCurrent(context)) return
                when (refresh) {
                    is ApiResult.Success -> if (!refresh.value.ok) {
                        mutableState.value = mutableState.value.copy(loading = false, refreshing = false,
                            error = refresh.value.error, writeAuthorized = false)
                        return
                    }
                    is ApiResult.Failure -> {
                        if (refresh.error is ApiFailure.Http && refresh.error.status == 404) {
                            refreshNotice = getApplication<Application>().getString(R.string.refresh_ordinary_reload)
                        } else {
                            val retainedTimestamp = if (mutableState.value.content != null) lastGoodCapturedAt else null
                            retainedTimestamp?.let(repository::showSavedCopy)
                            mutableState.value = mutableState.value.copy(loading = false, refreshing = false,
                                error = MainViewModel.describe(getApplication<Application>().resources, refresh.error),
                                readSource = repository.readSource.value,
                                writeAuthorized = false)
                            return
                        }
                    }
                    is ApiResult.NotModified -> refreshNotice = getApplication<Application>().getString(R.string.refresh_ordinary_reload)
                }
            }
            val result = repository.paneSnapshot(scope, context)
            if (!repository.isCurrent(context)) return
            when (result) {
                is ApiResult.Success -> {
                    pollingBackoff.succeeded()
                    lastGoodCapturedAt = result.value.ts
                    val content = SpaceModel.from(result.value, workspaceId, scope)
                    mutableState.value = if (content == null) {
                        mutableState.value.copy(
                            loading = false,
                            refreshing = false,
                            error = getApplication<Application>().getString(R.string.space_missing),
                            writeAuthorized = repository.writesAllowed(),
                        )
                    } else {
                        mutableState.value.copy(
                            content = content,
                            readSource = repository.readSource.value,
                            writeAuthorized = repository.writesAllowed(),
                            loading = false,
                            refreshing = false,
                            error = refreshNotice,
                            refreshNotice = refreshNotice,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    pollingBackoff.failed()
                    val retainedTimestamp = if (mutableState.value.content != null) lastGoodCapturedAt else null
                    retainedTimestamp?.let(repository::showSavedCopy)
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = MainViewModel.describe(getApplication<Application>().resources, result.error),
                        readSource = repository.readSource.value,
                        writeAuthorized = false,
                    )
                }
                is ApiResult.NotModified -> {
                    pollingBackoff.succeeded()
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                    )
                }
            }
        } finally {
            requestInFlight.set(false)
        }
    }

    class Factory(
        private val application: Application,
        private val workspaceId: String,
        private val scope: Scope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SpaceViewModel(application, workspaceId, scope) as T
    }

    companion object {
        const val POLL_MS = 5_000L
    }
}
