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
import com.lateapex.collie.data.MachineRuleAttempt
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.MachineHistoryPoint
import com.lateapex.collie.network.MachineHistoryResponse
import com.lateapex.collie.network.MachineAlerts
import com.lateapex.collie.network.MachineRow
import com.lateapex.collie.network.MachinesResponse
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.MAX_MACHINE_HISTORY_POINTS
import com.lateapex.collie.network.MACHINE_MINUTE_MS
import com.lateapex.collie.network.isValid
import com.lateapex.collie.network.isValidSuccess
import com.lateapex.collie.network.isValidConflict
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal enum class MachineScreenError {
    NOT_CONNECTED, NOT_LEAD, UNKNOWN_MACHINE, PAIRING_REQUIRED, GATEWAY_IDENTITY, DEVICE_AUTHORIZATION,
    ACCESS_CHECK_FAILED, MALFORMED, UNAVAILABLE,
}

internal enum class MachineRuleWriteState { SAVING, SAVED, CONFLICT, UNSUPPORTED, REFUSED, UNKNOWN, UNKNOWN_RETIRED, FAILED }

internal data class MachinesScreenState(
    val loading: Boolean = false,
    val response: MachinesResponse? = null,
    val selectedMachineId: String? = null,
    val history: MachineHistoryResponse? = null,
    val error: MachineScreenError? = null,
    val historyError: MachineScreenError? = null,
    val ruleWriteState: MachineRuleWriteState? = null,
    val ruleWriteMachineId: String? = null,
    val ruleAttempts: List<MachineRuleAttempt> = emptyList(),
    val ruleCustodyAvailable: Boolean = false,
    val ruleReviewReady: Boolean = false,
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
    @Volatile private var activeRuleAttemptId: String? = null
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
        val pendingRuleWrite = mutableState.value.ruleWriteState == MachineRuleWriteState.SAVING
        activeRuleAttemptId?.let { repository.markMachineRuleAttemptUnknown(original, it) }
        activeRuleAttemptId = null
        mutableState.value = MachinesScreenState(
            error = failure,
            ruleWriteState = if (pendingRuleWrite) MachineRuleWriteState.UNKNOWN_RETIRED else null,
            ruleWriteMachineId = if (pendingRuleWrite) mutableState.value.ruleWriteMachineId else null,
            ruleCustodyAvailable = false,
            ruleReviewReady = false,
            actor = actor,
        )
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
        mutableState.value = mutableState.value.copy(loading = true, error = null, ruleReviewReady = false)
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
                        mutableState.value = mutableState.value.copy(loading = false, response = null, error = MachineScreenError.MALFORMED, ruleReviewReady = false)
                        return@launch
                    }
                    val selected = mutableState.value.selectedMachineId
                    val attempts = repository.machineRuleAttempts(context)
                    if (selected != null && response.machines.none { it.id == selected }) {
                        mutableState.value = MachinesScreenState(
                            response = response, error = null, actor = context,
                            ruleAttempts = attempts.orEmpty(), ruleCustodyAvailable = attempts != null,
                            ruleReviewReady = attempts != null,
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(
                            loading = false, response = response, error = null, actor = context,
                            ruleAttempts = attempts.orEmpty(), ruleCustodyAvailable = attempts != null,
                            ruleReviewReady = attempts != null,
                        )
                        if (selected != null && System.currentTimeMillis() - lastHistoryRequestAt >= HISTORY_POLL_INTERVAL_MS) {
                            loadHistory(context, selected, request)
                        }
                    }
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.error.toMachineScreenError(), ruleReviewReady = false)
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = MachineScreenError.MALFORMED, ruleReviewReady = false)
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

    fun canEditRules(row: MachineRow): Boolean {
        val context = actor ?: return false
        return row.rulesRevision != null && row.machineIncarnation != null &&
            mutableState.value.ruleCustodyAvailable && mutableState.value.ruleAttempts.none { it.machineId == row.id } &&
            repository.isCurrent(context) && repository.writesAllowed(Scope(), null)
    }

    fun saveRules(
        machineId: String,
        expectedRevision: String,
        expectedIncarnation: String,
        expectedExtensions: JsonObject?,
        alerts: MachineAlerts,
    ) {
        val context = actor ?: return
        val row = mutableState.value.response?.machines?.firstOrNull { it.id == machineId } ?: return
        val revision = row.rulesRevision ?: return setRuleWriteState(machineId, MachineRuleWriteState.UNSUPPORTED)
        val incarnation = row.machineIncarnation ?: return setRuleWriteState(machineId, MachineRuleWriteState.UNSUPPORTED)
        if (revision != expectedRevision || incarnation != expectedIncarnation || row.extensions != expectedExtensions) {
            setRuleWriteState(machineId, MachineRuleWriteState.CONFLICT)
            return
        }
        if (!canEditRules(row)) return setRuleWriteState(machineId, MachineRuleWriteState.REFUSED)
        val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
        val localAttemptId = java.util.UUID.randomUUID().toString().replace("-", "")
        activeRuleAttemptId = localAttemptId
        setRuleWriteState(machineId, MachineRuleWriteState.SAVING)
        viewModelScope.launch {
            val result = repository.replaceMachineAlerts(context, machineId, alerts, expectedRevision, expectedIncarnation, requestId, localAttemptId, expectedExtensions)
            if (!repository.isCurrent(context)) return@launch
            activeRuleAttemptId = null
            val attempts = repository.machineRuleAttempts(context)
            val stillUnresolved = attempts?.any { it.attemptId == localAttemptId } == true
            val outcomeUnknown = attempts == null || stillUnresolved
            mutableState.value = mutableState.value.copy(
                ruleAttempts = attempts.orEmpty(),
                ruleCustodyAvailable = attempts != null,
                ruleReviewReady = attempts != null && !stillUnresolved,
            )
            when (result) {
                is ApiResult.Success -> {
                    val response = result.value
                    val currentRow = mutableState.value.response?.machines?.firstOrNull { it.id == machineId }
                    val stillEditingSnapshot = currentRow?.rulesRevision == expectedRevision &&
                        currentRow.machineIncarnation == expectedIncarnation && currentRow.extensions == expectedExtensions
                    if (!stillEditingSnapshot) {
                        setRuleWriteState(machineId, if (outcomeUnknown) MachineRuleWriteState.UNKNOWN else MachineRuleWriteState.CONFLICT)
                    } else if (result.status == 200 && response.isValidSuccess() &&
                        response.machineIncarnation == expectedIncarnation && response.extensions == expectedExtensions
                    ) {
                        updateMachine(machineId) { current -> current.copy(
                            alerts = checkNotNull(response.alerts),
                            rulesRevision = response.rulesRevision,
                            machineIncarnation = response.machineIncarnation,
                            extensions = response.extensions,
                        ) }
                        setRuleWriteState(machineId, if (attempts == null || attempts.any { it.attemptId == localAttemptId })
                            MachineRuleWriteState.UNKNOWN else MachineRuleWriteState.SAVED)
                    } else if (result.status == 409 && response.isValidConflict()) {
                        val current = checkNotNull(response.current)
                        updateMachine(machineId) { rowNow -> rowNow.copy(
                            alerts = current.alerts,
                            rulesRevision = current.rulesRevision,
                            machineIncarnation = current.machineIncarnation,
                            extensions = current.extensions,
                        ) }
                        setRuleWriteState(machineId, MachineRuleWriteState.CONFLICT)
                    } else setRuleWriteState(machineId, if (outcomeUnknown) MachineRuleWriteState.UNKNOWN else MachineRuleWriteState.FAILED)
                }
                is ApiResult.Failure -> setRuleWriteState(machineId, if (outcomeUnknown) MachineRuleWriteState.UNKNOWN else result.error.toRuleWriteState())
                is ApiResult.NotModified -> setRuleWriteState(machineId, if (outcomeUnknown) MachineRuleWriteState.UNKNOWN else MachineRuleWriteState.FAILED)
            }
        }
    }

    private fun updateMachine(id: String, transform: (MachineRow) -> MachineRow) {
        val response = mutableState.value.response ?: return
        if (response.machines.none { it.id == id }) return
        mutableState.value = mutableState.value.copy(response = response.copy(
            machines = response.machines.map { if (it.id == id) transform(it) else it },
        ))
    }

    private fun setRuleWriteState(id: String, value: MachineRuleWriteState?) {
        mutableState.value = mutableState.value.copy(ruleWriteState = value, ruleWriteMachineId = id)
    }

    fun acknowledgeRuleAttempt(machineId: String, attemptId: String): Boolean {
        val context = actor ?: return false
        val response = mutableState.value.response ?: return false
        if (response.machines.none { it.id == machineId } || !mutableState.value.ruleReviewReady || !repository.isCurrent(context)) return false
        val attempt = mutableState.value.ruleAttempts.singleOrNull { it.attemptId == attemptId && it.machineId == machineId }
            ?: return false
        if (!repository.acknowledgeMachineRuleAttempt(context, attempt)) return false
        val remaining = repository.machineRuleAttempts(context) ?: return false
        mutableState.value = mutableState.value.copy(ruleAttempts = remaining, ruleCustodyAvailable = true, ruleReviewReady = true)
        setRuleWriteState(machineId, null)
        return true
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

private fun ApiFailure.toRuleWriteState(): MachineRuleWriteState = when (this) {
    is ApiFailure.Http -> when {
        code == "machine.rules_unsupported" -> MachineRuleWriteState.UNSUPPORTED
        status in 400..499 -> MachineRuleWriteState.REFUSED
        else -> MachineRuleWriteState.UNKNOWN
    }
    is ApiFailure.Network, ApiFailure.Timeout, is ApiFailure.Redirect -> MachineRuleWriteState.UNKNOWN
    is ApiFailure.Protocol -> MachineRuleWriteState.FAILED
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
