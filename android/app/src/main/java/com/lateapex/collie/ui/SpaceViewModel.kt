package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
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
)

internal class SpaceViewModel(
    application: Application,
    private val workspaceId: String,
    private val scope: Scope,
) : AndroidViewModel(application) {
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository
    private val mutableState = MutableStateFlow(SpaceUiState())
    val state: StateFlow<SpaceUiState> = mutableState.asStateFlow()
    private val requestInFlight = AtomicBoolean(false)
    private var pollingJob: Job? = null

    init {
        mutableState.value = mutableState.value.copy(
            paired = repository.connection.value?.isPaired == true,
            writeAuthorized = repository.writesAllowed(),
        )
        viewModelScope.launch {
            repository.connection.collectLatest { connection ->
                mutableState.value = mutableState.value.copy(
                    paired = connection?.isPaired == true,
                    writeAuthorized = repository.writesAllowed(),
                )
            }
        }
    }

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            loadConfig()
            while (true) {
                load(manual = false)
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh() {
        viewModelScope.launch { load(manual = true) }
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
        val result = repository.config()
        if (result is ApiResult.Success) {
            mutableState.value = mutableState.value.copy(mux = result.value.mux)
        }
    }

    private suspend fun load(manual: Boolean) {
        if (!requestInFlight.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(
            loading = mutableState.value.content == null,
            refreshing = manual,
            error = null,
        )
        try {
            when (val result = repository.snapshot(scope)) {
                is ApiResult.Success -> {
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
                            writeAuthorized = repository.writesAllowed(),
                            loading = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    if (result.error is ApiFailure.Http && result.error.message == "device not paired") {
                        repository.demoteToReadOnly()
                    }
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = MainViewModel.describe(getApplication<Application>().resources, result.error),
                        writeAuthorized = repository.writesAllowed(),
                    )
                }
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    refreshing = false,
                )
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

    private companion object {
        const val POLL_MS = 5_000L
    }
}
