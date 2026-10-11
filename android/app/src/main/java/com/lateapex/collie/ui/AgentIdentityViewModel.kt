package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.AgentIdentityResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal enum class AgentIdentityProblem { AUTH, NOT_FOUND, UNSUPPORTED, TEMPORARY, RETIRED, STORAGE_RETAINED }

internal data class AgentIdentityUiState(
    val presentationGeneration: Long = 0,
    val loading: Boolean = false,
    val identity: AgentIdentityResponse? = null,
    val problem: AgentIdentityProblem? = null,
)

/** Owns one captured read actor and a bounded, deliberate retry path for the identity screen. */
internal class AgentIdentityViewModel(
    application: Application,
    private val address: PaneAddress,
    private val repository: CollieRepository = (application as CollieApplication).container.repository,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AgentIdentityUiState())
    val state: StateFlow<AgentIdentityUiState> = mutableState.asStateFlow()
    private val custodyLock = Any()
    private var context: ScopedReadContext? = null
    private var requestJob: Job? = null
    private val requestGeneration = AtomicLong()
    private var started = false
    private val invalidation = repository.onReadContextInvalidated { reason ->
        // Read repository facts outside private custody; cancellation and UI ACK never hold this lock.
        val authFailure = repository.accessFailure as? ApiFailure.Http
        val problem = if (repository.credentialRetirementFailure != null) {
            AgentIdentityProblem.STORAGE_RETAINED
        } else if (reason == AuthorityInvalidation.ACCESS_FROZEN && authFailure != null && authFailure.status in setOf(401, 403)) {
            AgentIdentityProblem.AUTH
        } else AgentIdentityProblem.RETIRED
        retire(problem)
    }

    private fun retire(problem: AgentIdentityProblem) {
        val retiredJob = synchronized(custodyLock) {
            val generation = requestGeneration.incrementAndGet()
            context = null
            val previous = requestJob
            requestJob = null
            mutableState.value = AgentIdentityUiState(presentationGeneration = generation, problem = problem)
            previous
        }
        retiredJob?.cancel()
    }

    fun start() {
        val firstStart = synchronized(custodyLock) {
            if (started) false else { started = true; true }
        }
        if (firstStart) refresh()
    }

    fun refresh() {
        retire(AgentIdentityProblem.RETIRED)
        repository.selectSubject(address.scope, address.paneId)
        val captured = repository.readContext(address.scope, address.paneId)
        val admissionGeneration = requestGeneration.get()
        val actorCurrent = captured != null && repository.isCurrent(captured)
        val loading = synchronized(custodyLock) {
            // Retirement between the outside-lock repository check and publication always wins.
            if (admissionGeneration != requestGeneration.get()) return
            if (captured == null || !actorCurrent) {
                mutableState.value = AgentIdentityUiState(
                    presentationGeneration = admissionGeneration,
                    problem = if (repository.credentialRetirementFailure != null) AgentIdentityProblem.STORAGE_RETAINED else AgentIdentityProblem.TEMPORARY,
                )
                return
            }
            val generation = requestGeneration.incrementAndGet()
            context = captured
            AgentIdentityUiState(presentationGeneration = generation, loading = true).also { mutableState.value = it }
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = repository.paneAgentIdentity(address, captured!!)
            // Never hold private custody across a repository call, coroutine suspension or wipe ACK.
            if (!repository.isCurrent(captured)) return@launch
            val next = when (result) {
                is ApiResult.Success -> AgentIdentityUiState(presentationGeneration = loading.presentationGeneration, identity = result.value)
                is ApiResult.NotModified -> AgentIdentityUiState(
                    presentationGeneration = loading.presentationGeneration, problem = AgentIdentityProblem.TEMPORARY,
                )
                is ApiResult.Failure -> AgentIdentityUiState(
                    presentationGeneration = loading.presentationGeneration, problem = problemFor(result.error),
                )
            }
            commitIfCurrent(captured, loading, next)
        }
        val admitted = synchronized(custodyLock) {
            if (context === captured && mutableState.value === loading && requestGeneration.get() == loading.presentationGeneration) {
                requestJob = job
                true
            } else false
        }
        if (admitted) job.start() else job.cancel()
    }

    /** Atomic private commit: a retired generation or replaced loading object can never repopulate identity. */
    private fun commitIfCurrent(
        captured: ScopedReadContext,
        loading: AgentIdentityUiState,
        next: AgentIdentityUiState,
    ): Boolean = synchronized(custodyLock) {
        if (context !== captured || requestGeneration.get() != loading.presentationGeneration ||
            mutableState.value !== loading || next.presentationGeneration != loading.presentationGeneration) false
        else { mutableState.value = next; true }
    }

    fun isCurrentPresentation(state: AgentIdentityUiState): Boolean {
        val captured = synchronized(custodyLock) {
            if (state.presentationGeneration != requestGeneration.get()) return false
            context
        }
        if (captured != null && !repository.isCurrent(captured)) return false
        return synchronized(custodyLock) {
            state.presentationGeneration == requestGeneration.get() && context === captured
        }
    }

    fun screenStopped() { retire(AgentIdentityProblem.RETIRED) }

    private fun problemFor(error: ApiFailure): AgentIdentityProblem = when (error) {
        is ApiFailure.Http -> when {
            error.status == 401 || error.status == 403 -> AgentIdentityProblem.AUTH
            error.status == 404 -> AgentIdentityProblem.NOT_FOUND
            error.status == 501 || error.code == "capability.unsupported" -> AgentIdentityProblem.UNSUPPORTED
            else -> AgentIdentityProblem.TEMPORARY
        }
        else -> AgentIdentityProblem.TEMPORARY
    }

    override fun onCleared() {
        retire(AgentIdentityProblem.RETIRED)
        invalidation.close()
        super.onCleared()
    }

    class Factory(private val application: Application, private val address: PaneAddress) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentIdentityViewModel(application, address) as T
    }
}
