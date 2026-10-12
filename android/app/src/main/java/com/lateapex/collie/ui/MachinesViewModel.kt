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
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.MachineHistoryPoint
import com.lateapex.collie.network.MachineHistoryResponse
import com.lateapex.collie.network.MachineRow
import com.lateapex.collie.network.MachinesResponse
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.MAX_MACHINE_HISTORY_POINTS
import com.lateapex.collie.network.MACHINE_MINUTE_MS
import com.lateapex.collie.network.isValid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class MachineScreenError {
    NOT_CONNECTED, NOT_LEAD, UNKNOWN_MACHINE, PAIRING_REQUIRED, GATEWAY_IDENTITY, DEVICE_AUTHORIZATION,
    ACCESS_CHECK_FAILED, MALFORMED, UNAVAILABLE,
}

internal data class MachinesScreenState(
    val loading: Boolean = false,
    val response: MachinesResponse? = null,
    val selectedMachineId: String? = null,
    val history: MachineHistoryResponse? = null,
    val error: MachineScreenError? = null,
    val historyError: MachineScreenError? = null,
    val actor: ScopedReadContext? = null,
)

internal class MachinesViewModel(
    application: Application,
    private val repository: CollieRepository,
) : AndroidViewModel(application) {
    private val actor = repository.machineReadContext()
    private val mutableState = MutableStateFlow(MachinesScreenState(loading = true, actor = actor))
    val state: StateFlow<MachinesScreenState> = mutableState.asStateFlow()
    private var polling: Job? = null
    private var readJob: Job? = null
    private var lastHistoryRequestAt = 0L
    @Volatile private var generation = 0L
    private val invalidation = repository.onReadContextRetired { event ->
        val original = actor ?: return@onReadContextRetired
        if (!event.retiresReadActor || !event.affects(original)) return@onReadContextRetired
        generation++
        readJob?.cancel()
        val refusal = event.refusal
        val failure = refusal?.let {
            ApiFailure.Http(it.status, null, it.code, source = it.source, factor = it.factor).toMachineScreenError()
        } ?: when (event.reason) {
            AuthorityInvalidation.PAIRING_RETIRED -> MachineScreenError.PAIRING_REQUIRED
            AuthorityInvalidation.ACCESS_FROZEN -> MachineScreenError.ACCESS_CHECK_FAILED
            else -> MachineScreenError.UNAVAILABLE
        }
        mutableState.value = MachinesScreenState(error = failure, actor = actor)
    }

    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
        readJob?.cancel()
        readJob = null
    }

    fun refresh() {
        val context = actor
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, response = null, error = MachineScreenError.NOT_CONNECTED)
            return
        }
        if (!repository.isCurrent(context)) {
            if (repository.isActorRetired(context)) {
                mutableState.value = MachinesScreenState(error = mutableState.value.error ?: MachineScreenError.UNAVAILABLE, actor = context)
            }
            return
        }
        val request = ++generation
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        readJob?.cancel()
        readJob = viewModelScope.launch {
            val result = repository.machines(context, spark = 30)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) {
                if (repository.isActorRetired(context)) {
                    mutableState.value = MachinesScreenState(error = MachineScreenError.UNAVAILABLE, actor = context)
                }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> {
                    val response = result.value
                    if (result.status != 200 || !response.isValid()) {
                        mutableState.value = mutableState.value.copy(loading = false, response = null, error = MachineScreenError.MALFORMED)
                        return@launch
                    }
                    val selected = mutableState.value.selectedMachineId
                    if (selected != null && response.machines.none { it.id == selected }) {
                        mutableState.value = MachinesScreenState(response = response, error = null, actor = context)
                    } else {
                        mutableState.value = mutableState.value.copy(loading = false, response = response, error = null, actor = context)
                        if (selected != null && System.currentTimeMillis() - lastHistoryRequestAt >= HISTORY_POLL_INTERVAL_MS) {
                            loadHistory(context, selected, request)
                        }
                    }
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.error.toMachineScreenError())
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = MachineScreenError.MALFORMED)
            }
        }
    }

    fun selectMachine(id: String) {
        val row = mutableState.value.response?.machines?.firstOrNull { it.id == id } ?: return
        val context = actor ?: return
        if (!repository.isCurrent(context)) return
        val request = ++generation
        readJob?.cancel()
        lastHistoryRequestAt = System.currentTimeMillis()
        mutableState.value = mutableState.value.copy(selectedMachineId = row.id, history = null, historyError = null, loading = true)
        readJob = viewModelScope.launch { loadHistory(context, row.id, request) }
    }

    fun showMachines() {
        generation++
        readJob?.cancel()
        readJob = null
        mutableState.value = mutableState.value.copy(selectedMachineId = null, history = null, historyError = null, loading = false)
    }

    private suspend fun loadHistory(context: ScopedReadContext, id: String, request: Long) {
        val old = mutableState.value.history?.takeIf { mutableState.value.selectedMachineId == id }
        val since = old?.points?.lastOrNull()?.let { it.t + MACHINE_MINUTE_MS }
        val result = repository.machineHistory(context, id, since)
        if (request != generation) return
        if (!repository.isCurrent(context)) {
            if (repository.isActorRetired(context)) {
                mutableState.value = MachinesScreenState(error = MachineScreenError.UNAVAILABLE, actor = context)
            }
            return
        }
        when (result) {
            is ApiResult.Success -> {
                val incoming = result.value
                if (result.status != 200 || !incoming.isValid()) {
                    mutableState.value = mutableState.value.copy(loading = false, historyError = MachineScreenError.MALFORMED)
                } else {
                    val merged = mergeMachineHistory(old, incoming)
                    lastHistoryRequestAt = System.currentTimeMillis()
                    mutableState.value = mutableState.value.copy(loading = false, history = merged, historyError = null)
                }
            }
            is ApiResult.Failure -> {
                val error = result.error.toMachineScreenError()
                if (error == MachineScreenError.UNKNOWN_MACHINE) {
                    val census = mutableState.value.response?.machines.orEmpty().filterNot { it.id == id }
                    mutableState.value = mutableState.value.copy(
                        loading = false, response = mutableState.value.response?.copy(machines = census),
                        selectedMachineId = null, history = null, error = null, historyError = error,
                    )
                } else mutableState.value = mutableState.value.copy(loading = false, historyError = error)
            }
            is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, historyError = MachineScreenError.MALFORMED)
        }
    }

    override fun onCleared() {
        stopPolling()
        invalidation.close()
        super.onCleared()
    }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val HISTORY_POLL_INTERVAL_MS = 60_000L
    }

    class Factory(private val application: Application, private val repository: CollieRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MachinesViewModel::class.java))
            return MachinesViewModel(application, repository) as T
        }
    }
}

internal fun ApiFailure.toMachineScreenError(): MachineScreenError = when (this) {
    is ApiFailure.Http -> when {
        code == "crew.not_lead" -> MachineScreenError.NOT_LEAD
        code == "host.unknown" -> MachineScreenError.UNKNOWN_MACHINE
        status in setOf(401, 403) && source == com.lateapex.collie.network.FailureSource.GATEWAY &&
            factor == com.lateapex.collie.network.FailureFactor.IDENTITY -> MachineScreenError.GATEWAY_IDENTITY
        status in setOf(401, 403) && source == com.lateapex.collie.network.FailureSource.GATEWAY &&
            factor == com.lateapex.collie.network.FailureFactor.DEVICE -> MachineScreenError.DEVICE_AUTHORIZATION
        retiresPairing() -> MachineScreenError.PAIRING_REQUIRED
        status in setOf(401, 403) -> MachineScreenError.ACCESS_CHECK_FAILED
        else -> MachineScreenError.UNAVAILABLE
    }
    is ApiFailure.Protocol -> if (message.startsWith("Malformed")) MachineScreenError.MALFORMED else MachineScreenError.UNAVAILABLE
    is ApiFailure.Redirect, is ApiFailure.Network, ApiFailure.Timeout -> MachineScreenError.UNAVAILABLE
}

internal fun mergeMachineHistory(old: MachineHistoryResponse?, incoming: MachineHistoryResponse): MachineHistoryResponse {
    val points = (old?.points.orEmpty() + incoming.points)
        .filter { it.t <= incoming.ts && incoming.ts - it.t < 86_400_000L }
        .associateBy(MachineHistoryPoint::t).values.sortedBy(MachineHistoryPoint::t)
        .takeLast(MAX_MACHINE_HISTORY_POINTS)
    return incoming.copy(points = points)
}

internal fun MachineRow.resourceSummary(): String {
    val sample = sample ?: return "Resources unavailable"
    val memory = if (sample.memTotal > 0) "${sample.memUsed} / ${sample.memTotal} bytes memory" else "Memory unavailable"
    val load = sample.load1?.let { " · load ${"%.2f".format(java.util.Locale.ROOT, it)}" } ?: " · load unavailable"
    val received = sample.rxBps?.let { "${it.toLong()} bytes/s" } ?: "unavailable"
    val sent = sample.txBps?.let { "${it.toLong()} bytes/s" } ?: "unavailable"
    val network = " · network received $received / sent $sent"
    return "CPU ${"%.0f".format(java.util.Locale.ROOT, sample.cpu * 100)}% · $memory$load$network"
}
