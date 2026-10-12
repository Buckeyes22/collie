package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AgentAutomationCustody
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.StoredAgentAction
import com.lateapex.collie.data.custodyVersion
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.AgentAutomationResponse
import com.lateapex.collie.network.AgentExplainResponse
import com.lateapex.collie.network.AgentWaitRecord
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.NamedAgentAction
import com.lateapex.collie.network.NamedAgentWait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

internal data class SavedAgentWait(val id: String, val owner: String, val incarnation: String, val witness: String)
internal data class AgentAutomationUiState(
    val generation: Long = 0, val busy: Boolean = false, val control: AgentAutomationResponse? = null,
    val screen: String? = null, val problemCode: String? = null, val writable: Boolean = false, val explain: AgentExplainResponse? = null,
    val namedAgents: List<com.lateapex.collie.network.AgentAutomationInfo>? = null, val pending: StoredAgentAction? = null, val wait: AgentWaitRecord? = null, val notice: String = "ready",
)

/** The Activity retains observations, not permission to replay a mutation. Original actors stay
 * attached through configuration, storage, transport and publication; stopped copies are erased. */
internal class AgentAutomationViewModel(
    application: Application, private val address: PaneAddress,
    private val repository: CollieRepository = (application as CollieApplication).container.repository,
    private val custody: AgentAutomationCustody = (application as CollieApplication).container.agentAutomationCustody,
) : AndroidViewModel(application) {
    private val lock = Any(); private var context: ScopedReadContext? = null; private var generation = 0L
    private var visible = false; private var request: Job? = null; private var waitJob: Job? = null
    private var savedWait: SavedAgentWait? = null
    private val mutableState = MutableStateFlow(AgentAutomationUiState())
    val state: StateFlow<AgentAutomationUiState> = mutableState.asStateFlow()
    private val registration = repository.onReadContextRetired { event -> retire(event) }

    fun originalActor(state: AgentAutomationUiState): ScopedReadContext? = synchronized(lock) { context.takeIf { mutableState.value === state } }
    fun current(state: AgentAutomationUiState): Boolean {
        val actor = synchronized(lock) { if (!visible || mutableState.value !== state || state.generation != generation) return false; context }
        return actor == null || repository.isCurrent(actor)
    }
    private fun publish(actor: ScopedReadContext, epoch: Long, next: AgentAutomationUiState): Boolean {
        if (!repository.isCurrent(actor)) return false
        return synchronized(lock) { if (!visible || context !== actor || generation != epoch) false else { mutableState.value = next.copy(generation = epoch); true } }
    }
    fun resumed(restored: SavedAgentWait? = null) {
        synchronized(lock) { visible = true; if (savedWait == null) savedWait = restored }
        refresh()
    }
    fun refresh() {
        val old = synchronized(lock) { generation++; val previous = request; request = null; context = null; mutableState.value = AgentAutomationUiState(generation, busy = true, notice = "loading"); previous }
        old?.cancel()
        repository.selectSubject(address.scope, address.paneId)
        val startingEpoch = synchronized(lock) { generation }
        val captured = repository.readContext(address.scope, address.paneId)
        if (captured == null || !repository.isCurrent(captured)) { synchronized(lock) { mutableState.value = AgentAutomationUiState(generation, notice = "retired") }; return }
        val epoch = synchronized(lock) { if (!visible || generation != startingEpoch) return; context = captured; generation }
        request = viewModelScope.launch {
            try {
                repository.paneSnapshot(address.scope, captured)
                if (!repository.isCurrent(captured)) return@launch
                val control = repository.namedAgentControl(address, captured)
                if (control !is ApiResult.Success || !repository.isCurrent(captured)) {
                    val restore = synchronized(lock) { savedWait }
                    if (restore != null && restore.owner == captured.custodyOwner.storageIdentity && restore.incarnation == captured.custodyVersion.connectionIncarnation && repository.isCurrent(captured)) {
                        if (publish(captured, epoch, AgentAutomationUiState(busy = true, notice = "pending"))) pollWait(captured, epoch, restore.id, null, restore.witness)
                    } else publish(captured, epoch, AgentAutomationUiState(notice = "unavailable"))
                    return@launch
                }
                val configuration = repository.config(address.scope, captured)
                if (!repository.isCurrent(captured)) return@launch
                val screen = if (control.value.info.agent != null) repository.namedAgentRead(address, captured) else null
                if (!repository.isCurrent(captured)) return@launch
                val records = withContext(Dispatchers.IO) { custody.owned(captured, address) }
                val pending = records.firstOrNull { it.state in setOf("pending", "unknown") }
                val next = AgentAutomationUiState(control = control.value, screen = (screen as? ApiResult.Success)?.value?.text,
                    writable = configuration is ApiResult.Success && repository.writesAllowed(address.scope, address.paneId), pending = pending, notice = if (pending != null) "unknown" else "ready")
                if (!publish(captured, epoch, next)) return@launch
                val restore = synchronized(lock) { savedWait }
                if (restore != null) {
                    if (restore.owner == captured.custodyOwner.storageIdentity && restore.incarnation == captured.custodyVersion.connectionIncarnation && restore.witness == control.value.witness) pollWait(captured, epoch, restore.id, control.value)
                    else { synchronized(lock) { savedWait = null }; publish(captured, epoch, next.copy(notice = "retired")) }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { publish(captured, epoch, AgentAutomationUiState(notice = "storage-unavailable")) }
        }
    }

    fun act(state: AgentAutomationUiState, supplied: NamedAgentAction) {
        val captured = originalActor(state) ?: return; val control = state.control ?: return
        if (!current(state) || state.busy || !state.writable || state.pending != null) return
        val action = when (supplied) { is NamedAgentAction.Start -> supplied.copy(args = supplied.args.toList()); is NamedAgentAction.Prompt -> supplied.copy(until = supplied.until?.toList()); is NamedAgentAction.Rename -> supplied; NamedAgentAction.Focus -> supplied; is NamedAgentAction.Keys -> supplied.copy(keys = supplied.keys.toList()); is NamedAgentAction.Input -> supplied.copy(keys = supplied.keys.toList()) }
        if (!action.valid()) { publish(captured, state.generation, state.copy(notice = "invalid")); return }
        val expectedText = if (action is NamedAgentAction.Prompt || action is NamedAgentAction.Keys || action is NamedAgentAction.Input) state.screen ?: return else null
        val id = UUID.randomUUID().toString(); val canonical = action.body().toString()
        val record = StoredAgentAction(id, captured.custodyVersion, address.scope.host, address.scope.session, address.paneId, control.info.terminalId, control.info.runtimeIdentity, control.witness, sha256(control.witness + "\u0000" + canonical + "\u0000" + expectedText.orEmpty()), action.verb, "pending")
        if (!publish(captured, state.generation, state.copy(busy = true, pending = record, notice = "dispatching"))) return
        val epoch = state.generation
        request = viewModelScope.launch {
            var outcome = "unknown"
            try {
                val reserved = withContext(Dispatchers.IO) { custody.track(captured) && custody.reserve(record) }
                if (!reserved || !repository.isCurrent(captured)) { outcome = "refused"; publish(captured, epoch, state.copy(busy = false, notice = "storage-unavailable")); return@launch }
                val result = repository.namedAgentAction(address, captured, id, control.witness, action, expectedText)
                if (result is ApiResult.Success) outcome = result.value.operation.state.takeIf { it in setOf("succeeded", "refused", "unknown") } ?: "unknown"
                val saved = withContext(Dispatchers.IO) { custody.finish(record, outcome) }
                if (!saved || !repository.isCurrent(captured)) return@launch
                val completed = record.copy(state = outcome)
                publish(captured, epoch, state.copy(busy = false, problemCode = (result as? ApiResult.Success)?.value?.operation?.code, control = control.takeIf { outcome == "unknown" }, screen = null, pending = completed.takeIf { outcome == "unknown" }, notice = if (result is ApiResult.Success && result.value.progress.settled) "settled" else outcome))
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { publish(captured, epoch, state.copy(busy = false, screen = null, pending = record.copy(state = "unknown"), notice = "unknown")) }
            finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { custody.finish(record, outcome) } } }
        }
    }
    fun reconcile(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; val original = state.pending ?: return; val info = state.control?.info ?: return
        if (!current(state) || state.busy || original.terminalId != info.terminalId || original.runtimeIdentity != info.runtimeIdentity) return
        publish(captured, state.generation, state.copy(busy = true, notice = "reconciling"))
        request = viewModelScope.launch {
            val response = repository.controlOperation(address.scope, original.id, captured)
            if (!repository.isCurrent(captured)) return@launch
            val operation = (response as? ApiResult.Success)?.value?.operation
            val proven = operation != null && operation.targetIdentity == original.witness && operation.capability == "agentAutomation"
            val outcome = if (proven && operation != null) operation.state.takeIf { it in setOf("succeeded", "refused") } ?: "unknown" else "unknown"
            val fresh = repository.namedAgentControl(address, captured)
            if (!repository.isCurrent(captured)) return@launch
            val sameTerminal = fresh is ApiResult.Success && fresh.value.info.terminalId == original.terminalId && fresh.value.info.runtimeIdentity == original.runtimeIdentity
            val settled = if (sameTerminal) outcome else "unknown"
            val saved = withContext(Dispatchers.IO) { custody.finish(original, settled) }
            if (saved) publish(captured, state.generation, state.copy(busy = false, pending = original.copy(state = settled).takeIf { settled == "unknown" }, notice = settled))
        }
    }
    fun abandon(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; val original = state.pending ?: return
        if (!current(state) || state.busy) return
        viewModelScope.launch { if (withContext(Dispatchers.IO) { custody.finish(original, "abandoned") }) publish(captured, state.generation, state.copy(pending = null, screen = null, notice = "abandoned")) }
    }
    fun census(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; if (!current(state) || state.busy) return
        request = viewModelScope.launch {
            val response = repository.namedAgentList(address.scope, captured)
            if (response is ApiResult.Success) publish(captured, state.generation, state.copy(namedAgents = response.value.agents))
            else publish(captured, state.generation, state.copy(notice = "unavailable"))
        }
    }
    fun clearHistory(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; if (!current(state) || state.busy || state.pending != null) return
        request = viewModelScope.launch { if (withContext(Dispatchers.IO) { custody.clearSettled(captured, address) }) publish(captured, state.generation, state.copy(notice = "ready")) }
    }
    fun explain(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; if (!current(state) || state.busy) return
        request = viewModelScope.launch { val response = repository.namedAgentExplain(address, captured); if (response is ApiResult.Success) publish(captured, state.generation, state.copy(explain = response.value)) else publish(captured, state.generation, state.copy(notice = "unavailable")) }
    }
    fun wait(state: AgentAutomationUiState, condition: NamedAgentWait, timeoutMs: Int) {
        val captured = originalActor(state) ?: return; val control = state.control ?: return
        if (!current(state) || state.busy || !condition.valid() || timeoutMs !in 1..300_000 || savedWait != null) return
        val incarnation = captured.custodyVersion.connectionIncarnation ?: return
        val id = UUID.randomUUID().toString()
        synchronized(lock) { if (!visible || context !== captured || generation != state.generation || savedWait != null) return; savedWait = SavedAgentWait(id, captured.custodyOwner.storageIdentity, incarnation, control.witness) }
        publish(captured, state.generation, state.copy(busy = true, notice = "observing"))
        waitJob = viewModelScope.launch {
            val response = repository.startAgentWait(address, captured, id, control.witness, condition, timeoutMs)
            if (!repository.isCurrent(captured)) return@launch
            if (response is ApiResult.Success) {
                if (publish(captured, state.generation, state.copy(busy = response.value.wait.state == "pending", wait = response.value.wait, notice = response.value.wait.state))) pollWait(captured, state.generation, id, control)
            } else publish(captured, state.generation, state.copy(busy = false, notice = "interrupted"))
        }
    }
    private suspend fun pollWait(captured: ScopedReadContext, epoch: Long, id: String, control: AgentAutomationResponse?, witness: String = control?.witness.orEmpty()) {
        // Hard local poll budget; a peer-provided deadline cannot keep the native process polling.
        repeat(301) {
            if (!repository.isCurrent(captured) || !synchronized(lock) { visible && context === captured && generation == epoch }) return
            val result = repository.agentWait(address, captured, id)
            if (!repository.isCurrent(captured)) return
            if (result !is ApiResult.Success) { publish(captured, epoch, mutableState.value.copy(busy = false, wait = null, notice = "interrupted")); return }
            val row = result.value.wait
            if (row.state == "matched") {
                val transition = row.result?.transition
                if (transition != null) {
                    val previous = control?.info
                    val originalMatches = transition.witness == witness && transition.previousPaneId == address.paneId && (previous == null || transition.previousWorkspaceId == previous.workspaceId && transition.previousTabId == previous.tabId && transition.terminalId == previous.terminalId && (transition.after == null || transition.after.runtimeIdentity == previous.runtimeIdentity && transition.after.terminalId == previous.terminalId))
                    if (!originalMatches || !repository.isCurrent(captured)) { publish(captured, epoch, mutableState.value.copy(busy = false, wait = null, notice = "retired")); return }
                    if (!publish(captured, epoch, mutableState.value.copy(busy = false, control = null, writable = false, screen = null, wait = row, notice = row.state))) return
                    synchronized(lock) { if (context === captured && generation == epoch && savedWait?.id == id) savedWait = null }
                    return
                }
                val move = row.result?.move
                if (move != null) {
                    val previous = control?.info
                    val originalMatches = move.witness == witness && move.previousPaneId == address.paneId && (previous == null ||
                        move.previousWorkspaceId == previous.workspaceId && move.previousTabId == previous.tabId && move.info.terminalId == previous.terminalId &&
                        move.info.runtimeIdentity == previous.runtimeIdentity && move.info.agent == previous.agent && move.info.sessionIdentity == previous.sessionIdentity &&
                        move.info.processIdentity == previous.processIdentity && move.info.name == previous.name)
                    if (!originalMatches || !repository.isCurrent(captured)) { publish(captured, epoch, mutableState.value.copy(busy = false, wait = null, notice = "retired")); return }
                    // This is neutral original-observation metadata. It never becomes control,
                    // composer text, navigation, or a new mutation witness.
                    if (!publish(captured, epoch, mutableState.value.copy(busy = false, control = null, writable = false, screen = null, wait = row, notice = row.state))) return
                    synchronized(lock) { if (context === captured && generation == epoch && savedWait?.id == id) savedWait = null }
                    return
                }
                val fresh = repository.namedAgentControl(address, captured)
                if (fresh !is ApiResult.Success || control?.info?.sameOccupant(fresh.value.info) != true || fresh.value.witness != witness) { publish(captured, epoch, mutableState.value.copy(busy = false, wait = null, notice = "retired")); return }
            }
            if (!publish(captured, epoch, mutableState.value.copy(busy = row.state == "pending", wait = row, notice = row.state))) return
            if (row.state != "pending") { synchronized(lock) { if (context === captured && generation == epoch && savedWait?.id == id) savedWait = null }; return }
            delay(1000)
        }
        cancelWait(mutableState.value)
    }
    fun cancelWait(state: AgentAutomationUiState) {
        val captured = originalActor(state) ?: return; val original = synchronized(lock) { savedWait } ?: return
        if (!current(state)) return
        waitJob?.cancel(); waitJob = null
        request = viewModelScope.launch {
            val result = repository.agentWait(address, captured, original.id, cancel = true)
            if (result is ApiResult.Success) publish(captured, state.generation, state.copy(busy = false, wait = result.value.wait, notice = "cancelled"))
            else publish(captured, state.generation, state.copy(busy = false, wait = null, notice = "interrupted"))
            synchronized(lock) { if (savedWait == original) savedWait = null }
        }
    }
    fun savedWait(): SavedAgentWait? = synchronized(lock) { savedWait }
    fun stopped() = retire(null)
    private fun retire(event: AuthorityRetirement?) {
        if (event?.reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN && !event.retiresReadActor) return
        val jobs = synchronized(lock) {
            if (event != null && !event.affects(context)) return
            generation++; context = null; if (event == null) visible = false else savedWait = null
            val previous = listOfNotNull(request, waitJob); request = null; waitJob = null
            mutableState.value = AgentAutomationUiState(generation, notice = "retired"); previous
        }
        jobs.forEach { it.cancel() }
    }
    override fun onCleared() { retire(null); registration.close(); super.onCleared() }
    class Factory(private val application: Application, private val address: PaneAddress) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T { require(modelClass.isAssignableFrom(AgentAutomationViewModel::class.java)); return AgentAutomationViewModel(application, address) as T }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
