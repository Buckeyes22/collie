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
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.data.QueuedSendAttemptStore
import com.lateapex.collie.data.UnavailableQueuedSendAttemptStore
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.MuxKeyGrammar
import com.lateapex.collie.domain.PollingBackoff
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.retiresPairing
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.QueuedSendGuard
import com.lateapex.collie.ui.terminal.PromptBinding
import com.lateapex.collie.ui.terminal.CodexReplyGuard
import com.lateapex.collie.ui.terminal.SemanticAction
import com.lateapex.collie.ui.terminal.SemanticActionGuard
import com.lateapex.collie.ui.terminal.SemanticInteractionEngine
import com.lateapex.collie.ui.terminal.SemanticInteractionResult
import com.lateapex.collie.ui.terminal.SemanticInteractionTransport
import com.lateapex.collie.ui.terminal.SemanticIntent
import com.lateapex.collie.ui.terminal.SemanticPane
import com.lateapex.collie.ui.terminal.SemanticSurface
import com.lateapex.collie.ui.terminal.SemanticWriteOutcome
import com.lateapex.collie.ui.terminal.StableTerminalDraft
import com.lateapex.collie.ui.terminal.TerminalComposerSemantics
import com.lateapex.collie.ui.terminal.TerminalDraft
import java.util.ArrayDeque
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PaneUiState(
    val pane: PaneReadResponse? = null,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
    val mutationError: String? = null,
    val status: String? = null,
    val lastSuccessAt: Long? = null,
    val canWrite: Boolean = false,
    val topologyKnown: Boolean = false,
    val composerReady: Boolean = false,
    val panes: List<PaneSummary> = emptyList(),
    val tabs: List<TabSummary> = emptyList(),
    val mux: MuxConfigResponse? = null,
    val servers: List<ServerSummary>? = null,
    val quickReplies: List<QuickReplyGroup> = emptyList(),
    val commands: List<AgentCommand> = emptyList(),
    val keyPresets: List<ComposerKeyPreset> = ComposerActions.keyPresets(null, emptyList()),
    val unsupportedKeys: Set<String> = emptySet(),
    val clearReplyDraft: Boolean = false,
    val requestedLines: Int = PaneScrollbackWindow.INITIAL_LINES,
    val desiredLines: Int = PaneScrollbackWindow.INITIAL_LINES,
    val loadedLines: Int = 0,
    val loadingOlder: Boolean = false,
    /** Actor for the exact accepted pane snapshot; redraws never create a replacement actor. */
    val paneReadContext: com.lateapex.collie.data.ScopedReadContext? = null,
    /** Positive declaration belongs to this captured config actor, never a replacement scope. */
    val filesConfigContext: ScopedReadContext? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val transcriptAvailable: Boolean? = null,
    val transcriptReason: String? = null,
    val transcriptHasMore: Boolean = false,
    val transcriptQueued: List<String> = emptyList(),
    /** Authority of the last accepted live transcript page; redraws cannot mint a new actor. */
    val transcriptReadContext: ScopedReadContext? = null,
    val transcriptSendNow: QueuedSendNow? = null,
    val transcriptSendPending: Boolean = false,
    val transcriptSendAttempt: QueuedSendNow? = null,
    val codexAcceptedReplies: List<String> = emptyList(),
)

class PaneViewModel(
    application: Application,
    private val address: PaneAddress,
    initialAgent: String?,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
    private val diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder =
        (application as CollieApplication).container.diagnostics,
    private val queuedSendAttemptStore: QueuedSendAttemptStore =
        (application as? CollieApplication)?.container?.queuedSendAttemptStore
            ?: UnavailableQueuedSendAttemptStore,
) : AndroidViewModel(application) {
    /**
     * The agent whose grammar binds replies and dialogs. Seeded from the launch intent and then
     * followed from the snapshot: a shell pane that starts `claude` while open becomes a Claude
     * pane, and the activity already re-labels it from the same snapshot. Before 2026-09-10 this
     * was fixed at launch, so the activity drew Claude's buttons while the pre-flight verified as a
     * shell and refused every tap with "the dialog changed" (S25 Ultra).
     */
    private var agent: String? = initialAgent
    private var observedConnection = repository.connection.value
    private var connectionPaired = observedConnection?.isPaired == true
    private var deviceAuthorizationKnown = false
    private var deviceWriteAllowed = false
    private val mutableState = MutableStateFlow(
        PaneUiState(
            canWrite = false,
            status = text(if (connectionPaired) R.string.pane_status_checking_device else R.string.pane_status_not_paired),
        ),
    )
    val state: StateFlow<PaneUiState> = mutableState.asStateFlow()
    private var transientStatus: String? = null

    /** A confirmation that clears itself after [STATUS_NOTICE_MS]. */
    private fun confirmation(value: String): String {
        transientStatus = value
        return value
    }
    private var pollingJob: Job? = null
    private var mutationJob: Job? = null
    private val readInFlight = AtomicBoolean(false)
    private val pollingBackoff = PollingBackoff()
    private val writeInFlight = AtomicBoolean(false)
    // Credential scope stays in memory, with no generated toString or saved-state serialization.
    private class AcceptedScope(val connection: com.lateapex.collie.domain.Connection, val incarnation: String?, val replies: CodexAcceptedReplies) {
        val origin get() = connection.origin.value
        val token get() = connection.token
    }
    private val acceptedScopes = mutableListOf<AcceptedScope>()
    private fun acceptedReplies(): CodexAcceptedReplies? = repository.connection.value?.let { connection ->
        val incarnation = repository.committedIncarnationFor(connection)
        acceptedScopes.firstOrNull { it.origin == connection.origin.value && it.token == connection.token && it.incarnation == incarnation }?.replies
            ?: CodexAcceptedReplies().also { acceptedScopes += AcceptedScope(connection, incarnation, it) }
    }
    private val directKeyQueue = ArrayDeque<String>()
    private var directTypingActive = false
    private var directTypingGeneration = 0L
    private var supportPoll = 0
    private var paneRequestedLines = PaneScrollbackWindow.INITIAL_LINES
    private var paneDesiredLines = PaneScrollbackWindow.INITIAL_LINES

    private class DiscardedActor : kotlinx.coroutines.CancellationException()
    private suspend fun <T> withCurrentActor(block: suspend () -> ApiResult<T>): ApiResult<T> {
        val context = repository.readContext(address.scope, address.paneId) ?: throw DiscardedActor()
        adoptOriginalContext(context)
        val result = block()
        if (!repository.isCurrent(context)) throw DiscardedActor()
        return result
    }

    @Volatile internal var originalContext: ScopedReadContext? = null
        private set
    private fun adoptOriginalContext(context: ScopedReadContext) {
        if (originalContext?.custodyOwner?.let { it != context.custodyOwner } == true) {
            mutableState.value = PaneUiState(clearReplyDraft = true)
            chatWindow.clear(); chatEtag = null; transcriptEtag = null
        }
        originalContext = context
    }

    private val authorityRegistration = repository.onAuthorityRetired { event ->
        withMainCustodyAck {
            val reason = event.reason
            // Retire the old cache entry even after this screen has adopted B. A's event
            // cannot clear any B entry or depend on a callback-time repository read.
            if (reason in setOf(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED,
                com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED, com.lateapex.collie.data.AuthorityInvalidation.DISCONNECTED)) {
                acceptedScopes.removeAll { entry ->
                    if (entry.incarnation != null) event.affectsConnection(entry.connection, entry.incarnation)
                    else reason != com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED &&
                        event.affectsConnection(entry.connection, null)
                }
            }
            if (!event.affects(originalContext)) return@withMainCustodyAck
            if (reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN) {
                repository.accessFailure?.takeIf { it is ApiFailure.Http && it.status in setOf(401, 403) }?.let { failure ->
                    mutableState.value = mutableState.value.copy(loading = false, error = MainViewModel.describe(getApplication<Application>().resources, failure))
                }
            }
            disarmQueuedAction()
            deviceAuthorizationKnown = false
            deviceWriteAllowed = false
            endDirectTyping()
            // A failed optional/supporting read freezes writes without retiring an otherwise current
            // accepted pane body. Actual read-authority retirement clears its actor below.
            mutableState.value = mutableState.value.copy(canWrite = false, composerReady = false)
            if (reason in setOf(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED,
                com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED, com.lateapex.collie.data.AuthorityInvalidation.DISCONNECTED)) {
                chatWindow.clear()
                chatEtag = null
                transcriptEtag = null
                transcriptSkipPolls = 0
                legacyChat = false
                mutableState.value = PaneUiState(clearReplyDraft = true)
            }
        }
    }
    private val readContextRegistration = repository.onReadContextRetired { event ->
        withRetirementOnMain(event, { originalContext }) {
            chatEtag = null
            transcriptEtag = null
            mutableState.value = mutableState.value.copy(transcriptReadContext = null, paneReadContext = null)
        }
    }

    override fun onCleared() {
        authorityRegistration.close()
        readContextRegistration.close()
        super.onCleared()
    }

    init {
        repository.selectSubject(address.scope, address.paneId)
        // A confirmation ("Reply sent.", "Typed into terminal.") is a notice, not a state: it used to
        // sit over the mirror until the next mutation replaced it. Only confirmations expire; the
        // authorization statuses stay until the fact they report changes.
        viewModelScope.launch {
            state.map { it.status }.distinctUntilChanged().collectLatest { status ->
                if (status == null || status != transientStatus) return@collectLatest
                delay(STATUS_NOTICE_MS)
                if (mutableState.value.status == status) mutableState.value = mutableState.value.copy(status = null)
            }
        }
        viewModelScope.launch {
            repository.connection.collect { connection ->
                // A queued old flow value cannot replace an already adopted current pane.
                if (connection !== repository.connection.value) return@collect
                val adopted = originalContext?.takeIf { !repository.isActorRetired(it) }
                if (adopted != null && adopted.connection !== connection) return@collect
                if (connection != observedConnection && adopted?.connection !== connection) {
                    mutationJob?.cancel()
                    endDirectTyping()
                    observedConnection = connection
                    deviceAuthorizationKnown = false
                    deviceWriteAllowed = false
                    mutableState.value = mutableState.value.copy(
                        topologyKnown = false,
                        pane = null,
                        sending = false,
                        clearReplyDraft = false,
                        composerReady = false,
                        transcript = emptyList(),
                        transcriptQueued = emptyList(),
                        transcriptSendNow = null,
                        transcriptSendPending = false,
                        transcriptSendAttempt = null,
                        transcriptHasMore = false,
                        transcriptAvailable = null,
                        transcriptReason = null,
                        codexAcceptedReplies = acceptedReplies()?.texts.orEmpty(),
                        lastSuccessAt = null,
                        quickReplies = emptyList(),
                        commands = emptyList(),
                        mux = null,
                        panes = emptyList(),
                        tabs = emptyList(),
                        servers = null,
                    )
                } else if (connection !== observedConnection && adopted != null && repository.isCurrent(adopted)) {
                    // The current actor may already own its pane, including a legacy original
                    // connection returning. Publish only local input acknowledgements: keep the
                    // adopted pane/transcript and never restore drafts or replay terminal input.
                    observedConnection = connection
                    mutableState.value = mutableState.value.copy(codexAcceptedReplies = acceptedReplies()?.texts.orEmpty())
                }
                connectionPaired = connection?.isPaired == true
                applyWriteAccess()
            }
        }
    }

    fun startPolling() {
        repository.selectSubject(address.scope, address.paneId)
        if (pollingJob != null) return
        val activePoll = viewModelScope.launch {
            while (true) {
                try {
                    load()
                    if (supportPoll++ % 3 == 0) loadSupportingData()
                } catch (_: DiscardedActor) { /* A changed target owns the next iteration. */ }
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
        viewModelScope.launch {
            load()
            loadSupportingData()
        }
    }

    /** Grow the bounded live-pane window. Returns the target used to match the resulting read. */
    fun loadOlderScrollback(): Int? {
        val previous = mutableState.value
        val maximum = PaneScrollbackWindow.maxLines(previous.mux)
        if (previous.loadingOlder || paneDesiredLines >= PaneScrollbackWindow.MAX_DESIRED_LINES) return null
        val desired = PaneScrollbackWindow.growDesired(paneDesiredLines)
        val target = desired.coerceAtMost(maximum)
        if (target <= paneRequestedLines) {
            paneDesiredLines = desired
            mutableState.value = previous.copy(desiredLines = desired)
            return null
        }
        paneDesiredLines = desired
        paneRequestedLines = target
        mutableState.value = previous.copy(
            requestedLines = target,
            desiredLines = desired,
            loadingOlder = true,
            error = null,
        )
        viewModelScope.launch { load() }
        return target
    }

    fun sendReply(text: String, terminalDraftToClear: TerminalDraft? = null) {
        diagnostics.record("action", mapOf("name" to "pane.send_reply"))
        val clean = text.trimEnd()
        if (clean.isBlank() || mutableState.value.sending || refuseTerminalWrite()) return
        val displayed = currentPaneOrRefuse() ?: return
        val displayedPrompt = if (terminalDraftToClear == null) {
            PromptBinding.composerRegion(agent, displayed.text)
        } else {
            PromptBinding.tailRegion(displayed.text).takeIf {
                terminalDraftMatches(displayed.text, terminalDraftToClear)
            }
        } ?: run {
            mutableState.value = mutableState.value.copy(
                mutationError = text(
                    if (terminalDraftToClear == null) {
                        R.string.pane_error_no_composer
                    } else {
                        R.string.pane_error_terminal_draft_changed
                    },
                ),
            )
            return
        }
        if (!writeInFlight.compareAndSet(false, true)) return
        val accepted = acceptedReplies()
        val baseline = chatWindow.after?.let { cursor ->
            chatWindow.head?.let { CodexAcceptedReplies.Baseline(cursor.gen, cursor.rev, it) }
        }
        mutationJob = viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val expectedPrompt = if (terminalDraftToClear == null) {
                    refreshBinding(displayed, displayedPrompt, { PromptBinding.composerRegion(agent, it) }, wholePane = agent != "codex")
                } else {
                    clearTakenOverTerminalDraft(displayed, displayedPrompt, terminalDraftToClear)
                } ?: return@launch
                if (refuseTerminalWrite()) {
                    mutableState.value = mutableState.value.copy(sending = false)
                    return@launch
                }
                val result = if (agent == "codex") {
                    CodexReplyGuard.send(
                        clean,
                        expectedPrompt,
                        text(R.string.pane_error_codex_unconfirmed),
                        reply = { message, submit, binding ->
                            if (message.isEmpty()) withCurrentActor { repository.submitReply(address, binding) }
                            else withCurrentActor { repository.reply(address, message, submit, binding) }
                        },
                        read = {
                            when (val observed = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }) {
                                is ApiResult.Success -> observed.value.pane.let { pane ->
                                    mutableState.value = mutableState.value.copy(
                                        pane = pane,
                                        composerReady = PromptBinding.composerRegion(agent, pane.text) != null,
                                    )
                                    pane.text
                                }
                                else -> null
                            }
                        },
                    )
                } else withCurrentActor { repository.reply(address, clean, submit = true, expectedPrompt = expectedPrompt) }
                when (result) {
                is ApiResult.Success -> {
                    val response = result.value
                    if (response.ok && agent == "codex") {
                        accepted?.accept(clean, baseline)
                        // A concurrent poll can journal the turn while the submit guard is
                        // awaiting composer consumption. Its upsert will not arrive twice.
                        chatWindow.after?.let { cursor ->
                            accepted?.reconcile(cursor.gen, cursor.rev, chatWindow.entries)
                        }
                    }
                    mutableState.value = if (response.ok) {
                        mutableState.value.copy(
                            sending = false,
                            status = confirmation(text(if (agent == "codex") R.string.pane_codex_accepted else R.string.pane_reply_sent)),
                            codexAcceptedReplies = acceptedReplies()?.texts.orEmpty(),
                            clearReplyDraft = true,
                        )
                    } else if (response.textDelivered) {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = if (response.code == "codex_unconfirmed") response.error
                                else text(R.string.pane_error_text_delivered),
                        )
                    } else if (response.code == "prompt_changed") {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = text(R.string.pane_error_screen_changed_reply),
                        )
                    } else {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = response.error ?: text(R.string.pane_error_reply_refused),
                        )
                    }
                    load()
                }
                is ApiResult.Failure -> {
                    demoteIfRevoked(result.error)
                    mutableState.value = mutableState.value.copy(
                        sending = false,
                        mutationError = mutationFailure(result.error, R.string.pane_action_send),
                    )
                }
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_send_empty),
                )
                }
            } finally {
                mutableState.value = mutableState.value.copy(sending = false)
                writeInFlight.set(false)
            }
        }
    }

    fun sendKey(key: String) {
        diagnostics.record("action", mapOf("name" to "pane.send_key", "key" to key))
        sendKeys(listOf(key), direct = false)
    }

    /** Sends one reviewed Keys-tray batch through the same fresh-pane guard as a single key. */
    fun sendKeySequence(keys: List<String>) {
        diagnostics.record("action", mapOf("name" to "pane.send_key_sequence", "count" to keys.size))
        if (keys.size > MAX_REVIEWED_KEY_BATCH) return
        sendKeys(keys, direct = false)
    }

    /** Send the current server-declared live queue recipe once, after rechecking its owner and screen. */
    fun sendQueuedNow(displayed: QueuedSendNow) {
        diagnostics.record("action", mapOf("name" to "pane.send_queued_now", "count" to displayed.keys.size))
        val current = mutableState.value
        val occupant = current.panes.firstOrNull { it.matches(address) } ?: return refuseQueuedSend()
        val pane = currentPaneOrRefuse() ?: return
        val displayedPrompt = PromptBinding.tailRegion(pane.text)
        if (current.sending || current.transcriptSendPending || current.transcriptSendNow != displayed ||
            current.mux?.supportsNew("guardedQueuedSend") != true ||
            current.transcriptSendAttempt?.sameQueue(displayed) == true || !queuedKeysSupported(displayed.keys) ||
            displayedPrompt == null || queueScreenBlocked(agent, pane.text, pane.revision) ||
            occupant.status != AgentStatus.WORKING || refuseTerminalWrite()
        ) return refuseQueuedSend()
        if (!writeInFlight.compareAndSet(false, true)) return
        val queueIdentity = QueueOccupantIdentity.of(occupant)
        mutableState.value = mutableState.value.copy(
            sending = true,
            transcriptSendPending = true,
            error = null,
            mutationError = null,
            status = null,
        )
        mutationJob = viewModelScope.launch {
            try {
                val liveAtStart = freshQueuedAction(displayed) ?: return@launch
                if (!liveAtStart) return@launch

                val snapshotConnection = repository.connection.value
                val snapshot = withCurrentActor { repository.paneSnapshot(address.scope) }
                if (snapshot !is ApiResult.Success || repository.connection.value !== snapshotConnection) {
                    refuseQueuedSend()
                    return@launch
                }
                val panes = snapshot.value.agents + snapshot.value.shellPanes
                val freshOccupant = panes.firstOrNull { it.matches(address) }
                deviceAuthorizationKnown = true
                deviceWriteAllowed = snapshot.value.device?.let { !it.enforced || it.authorized } ?: true
                mutableState.value = mutableState.value.copy(
                    panes = panes,
                    servers = snapshot.value.servers,
                    topologyKnown = true,
                )
                applyWriteAccess()
                if (freshOccupant == null || freshOccupant.status != AgentStatus.WORKING ||
                    QueueOccupantIdentity.of(freshOccupant) != queueIdentity || refuseTerminalWrite()
                ) {
                    refuseQueuedSend()
                    return@launch
                }

                val freshPane = when (val result = withCurrentActor {
                    repository.readPane(address, paneRequestedLines, markSeen = true)
                }) {
                    is ApiResult.Success -> result.value.pane
                    else -> {
                        refuseQueuedSend()
                        return@launch
                    }
                }
                val freshPrompt = PromptBinding.tailRegion(freshPane.text)
                if (mutableState.value.pane?.let { it.revision == pane.revision && it.text == pane.text } != true ||
                    freshPrompt == null || freshPrompt != displayedPrompt ||
                    queueScreenBlocked(agent, freshPane.text, freshPane.revision) || refuseTerminalWrite()
                ) {
                    mutableState.value = mutableState.value.copy(
                        pane = freshPane,
                        mutationError = text(R.string.pane_error_screen_changed_key),
                    )
                    return@launch
                }

                // The last read binds this one-shot to the queue that is still pending now. No text
                // crosses the write route, and an uncertain response never triggers a retry.
                if (freshQueuedAction(displayed) != true || refuseTerminalWrite()) {
                    refuseQueuedSend()
                    return@launch
                }
                val paneIdentity = queuedPaneIdentity()
                val actionIdentity = queuedActionIdentity(displayed)
                val persisted = try {
                    queuedSendAttemptStore.recordBeforeDispatch(paneIdentity, actionIdentity)
                } catch (_: Exception) {
                    false
                }
                if (!persisted) {
                    refuseQueuedSend()
                    return@launch
                }
                // Busy state blocks repeated taps during preflight. The durable one-shot latch
                // starts only after reservation, so a proved pre-dispatch refusal stays recoverable.
                mutableState.value = mutableState.value.copy(transcriptSendAttempt = displayed)
                val writeActor = repository.readContext(address.scope, address.paneId)
                    ?: throw DiscardedActor()
                if (!repository.isCurrent(writeActor)) throw DiscardedActor()
                when (val result = repository.queuedSendNow(
                    address,
                    displayed.keys,
                    expectedPrompt = freshPrompt,
                    guard = QueuedSendGuard(
                        generation = displayed.generation,
                        revision = displayed.revision,
                        queueDigest = displayed.queuedDigest,
                        occupant = displayed.occupant,
                    ),
                    actor = writeActor,
                )) {
                    is ApiResult.Success -> if (result.value.ok) {
                        runCatching {
                            queuedSendAttemptStore.recordOutcome(
                                actionIdentity,
                                com.lateapex.collie.data.EncryptedQueuedSendAttemptStore.OUTCOME_ACCEPTED,
                            )
                        }
                        mutableState.value = mutableState.value.copy(
                            status = confirmation(text(R.string.pane_transcript_sending_now)),
                        )
                    } else {
                        val outcome = when (result.value.code) {
                            "queued_send.stale", "queued_send.already_attempted", "queued_send.unsupported_peer" ->
                                com.lateapex.collie.data.EncryptedQueuedSendAttemptStore.OUTCOME_PRE_DISPATCH_REFUSED
                            "queued_send.outcome_unknown" ->
                                com.lateapex.collie.data.EncryptedQueuedSendAttemptStore.OUTCOME_UNKNOWN
                            else -> com.lateapex.collie.data.EncryptedQueuedSendAttemptStore.OUTCOME_REFUSED
                        }
                        runCatching { queuedSendAttemptStore.recordOutcome(actionIdentity, outcome) }
                        mutableState.value = mutableState.value.copy(
                            mutationError = result.value.error ?: text(R.string.pane_error_key_refused),
                        )
                    }
                    is ApiResult.Failure -> {
                        demoteIfRevoked(result.error)
                        mutableState.value = mutableState.value.copy(
                            mutationError = mutationFailure(result.error, R.string.pane_action_key),
                        )
                    }
                    is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                        mutationError = text(R.string.pane_error_key_empty),
                    )
                }
                load()
            } catch (_: DiscardedActor) {
                // A replaced connection/subject cannot inherit a queue write or its response.
            } finally {
                mutableState.value = mutableState.value.copy(sending = false, transcriptSendPending = false)
                writeInFlight.set(false)
            }
        }
    }

    private fun queuedKeysSupported(keys: List<String>): Boolean =
        keys.isNotEmpty() && keys.size <= MAX_QUEUED_SEND_KEYS &&
            keys.all { MuxKeyGrammar.isSupported(it, mutableState.value.unsupportedKeys) }

    private fun queueScreenBlocked(agent: String?, screen: String, revision: Long): Boolean =
        com.lateapex.collie.ui.terminal.AgentSemanticParser.detect(agent, screen, revision) != null ||
            com.lateapex.collie.ui.terminal.TerminalComposerSemantics.noEchoPrompt(screen) != null

    private fun disarmQueuedAction() {
        chatWindow.disarmSendNow()
        mutableState.value = mutableState.value.copy(transcriptSendNow = null)
    }

    private fun refuseQueuedSend() {
        mutableState.value = mutableState.value.copy(
            mutationError = text(R.string.pane_error_screen_changed_key),
        )
    }

    private suspend fun freshQueuedAction(expected: QueuedSendNow): Boolean? {
        val verificationContext = repository.readContext(address.scope, address.paneId) ?: return false
        val result = withCurrentActor { repository.chat(address, limit = TRANSCRIPT_PAGE, expectedContext = verificationContext) }
        if (!repository.isCurrent(verificationContext)) return false
        return when (result) {
        is ApiResult.Success -> {
            val page = result.value
            val matches = page.available && page.page == "live" && page.gen == expected.generation &&
                page.rev == expected.revision && page.queued == expected.queued && page.sendQueuedNow == expected.keys &&
                page.queuedDigest == expected.queuedDigest && page.queuedSendOccupant == expected.occupant
            val merged = page.available && chatWindow.merge(page)
            if (merged) {
                val persistedAttempt = observeQueuedAction(chatWindow.sendNow)
                mutableState.value = mutableState.value.copy(
                    transcriptReadContext = verificationContext,
                    transcript = chatWindow.entries,
                    transcriptQueued = chatWindow.queued,
                    transcriptSendNow = chatWindow.sendNow,
                    transcriptSendAttempt = persistedAttempt ?: mutableState.value.transcriptSendAttempt
                        ?.takeIf { current -> chatWindow.sendNow?.let(current::sameQueue) == true },
                    transcriptHasMore = chatWindow.hasOlder,
                )
                if (matches) return persistedAttempt == null
            }
            if (!matches || !merged) {
                refuseQueuedSend()
                mutableState.value = mutableState.value.copy(
                    transcriptSendNow = chatWindow.sendNow,
                    transcriptSendPending = false,
                )
                return false
            }
            false
        }
        is ApiResult.Failure, is ApiResult.NotModified -> {
            disarmQueuedAction()
            refuseQueuedSend()
            false
        }
        }
    }

    private fun PaneSummary.matches(address: PaneAddress): Boolean =
        paneId == address.paneId && host == address.scope.host && session == address.scope.session

    private data class QueueOccupantIdentity(
        val paneId: String,
        val workspaceId: String,
        val tabId: String,
        val host: String?,
        val session: String?,
        val agent: String,
        val cwd: String,
        val sessionName: String?,
        val hasSession: Boolean?,
        val kind: String?,
    ) {
        companion object {
            fun of(pane: PaneSummary) = QueueOccupantIdentity(
                pane.paneId, pane.workspaceId, pane.tabId, pane.host, pane.session,
                pane.agent, pane.cwd, pane.sessionName, pane.hasSession, pane.kind,
            )
        }
    }

    private fun observeQueuedAction(action: QueuedSendNow?): QueuedSendNow? {
        action ?: return null
        return try {
            val paneIdentity = queuedPaneIdentity()
            val actionIdentity = queuedActionIdentity(action)
            if (!queuedSendAttemptStore.observe(paneIdentity, actionIdentity)) action
            else if (queuedSendAttemptStore.wasAttempted(actionIdentity)) action
            else null
        } catch (_: Exception) {
            // Missing/corrupt custody is fail-closed: do not make a potentially repeated action live.
            action
        }
    }

    private fun queuedPaneIdentity(): String {
        val connection = repository.connection.value
        return digestOf(
            listOf(
                connection?.origin?.value.orEmpty(),
                connection?.token.orEmpty(),
                address.scope.host.orEmpty(),
                address.scope.session.orEmpty(),
                address.paneId,
            ).joinToString("\u0000"),
        )
    }

    private fun queuedActionIdentity(action: QueuedSendNow): String = digestOf(
        listOf(
            action.generation.toString(),
            action.queuedDigest,
            action.occupant,
            action.keys.joinToString("\u0000"),
        ).joinToString("\u0001"),
    )

    private fun digestOf(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Executes one adapter-derived terminal action after a fresh exact-pane re-read. The semantic
     * signature and revision must still match and the selected action must still exist verbatim.
     * The resulting keys cross the repository boundary once and are never retried.
     */
    fun sendSemanticAction(displayedSurface: SemanticSurface, displayedAction: SemanticAction) {
        val displayedPane = currentPaneOrRefuse() ?: return
        if (mutableState.value.sending || refuseTerminalWrite() || displayedSurface.interactionLocked ||
            displayedPane.revision != displayedSurface.revision || displayedAction !in displayedSurface.actions ||
            displayedAction.keys.any { !MuxKeyGrammar.isSupported(it, mutableState.value.unsupportedKeys) }
        ) return
        if (!writeInFlight.compareAndSet(false, true)) return
        mutationJob = viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val fresh = when (val result = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }) {
                    is ApiResult.Success -> result.value.pane
                    is ApiResult.Failure -> {
                        mutableState.value = mutableState.value.copy(
                            sending = false,
                            mutationError = text(R.string.pane_dialog_verify_failed),
                        )
                        return@launch
                    }
                    is ApiResult.NotModified -> {
                        mutableState.value = mutableState.value.copy(
                            sending = false,
                            mutationError = text(R.string.pane_dialog_verify_failed),
                        )
                        return@launch
                    }
                }
                val stillDisplayed = mutableState.value.pane?.let {
                    it.revision == displayedPane.revision && it.text == displayedPane.text
                } == true
                val verified = if (stillDisplayed) {
                    SemanticActionGuard.verify(agent, displayedSurface, displayedAction, fresh.text, fresh.revision)
                } else {
                    null
                }
                if (verified == null) {
                    mutableState.value = mutableState.value.copy(
                        pane = fresh,
                        sending = false,
                        composerReady = PromptBinding.composerRegion(agent, fresh.text) != null,
                        mutationError = text(R.string.pane_dialog_changed),
                    )
                    return@launch
                }
                when (val result = withCurrentActor { repository.keys(address, verified.keys, expectedPrompt = displayedSurface.regionSignature) }) {
                    is ApiResult.Success -> mutableState.value = if (result.value.ok) {
                        mutableState.value.copy(
                            sending = false,
                            status = confirmation(text(R.string.pane_dialog_sent, verified.label)),
                        )
                    } else if (result.value.code == "prompt_changed") {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = text(R.string.pane_dialog_changed),
                        )
                    } else {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = result.value.error
                                ?: text(R.string.pane_dialog_refused),
                        )
                    }
                    is ApiResult.Failure -> {
                        demoteIfRevoked(result.error)
                        mutableState.value = mutableState.value.copy(
                            sending = false,
                            mutationError = mutationFailure(result.error, R.string.pane_dialog_action),
                        )
                    }
                    is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                        sending = false,
                        mutationError = text(R.string.pane_dialog_empty),
                    )
                }
                load()
            } finally {
                mutableState.value = mutableState.value.copy(sending = false)
                writeInFlight.set(false)
            }
        }
    }

    /** Runs the verified multi-step recipes used by plan feedback, preview dialogs, and multi-select. */
    fun sendSemanticIntent(displayedSurface: SemanticSurface, intent: SemanticIntent) {
        if (intent is SemanticIntent.Static) {
            sendSemanticAction(displayedSurface, intent.action)
            return
        }
        val displayedPane = currentPaneOrRefuse() ?: return
        if (mutableState.value.sending || refuseTerminalWrite() || displayedSurface.interactionLocked ||
            displayedPane.revision != displayedSurface.revision || displayedSurface.nativeModel == null
        ) return
        if (!writeInFlight.compareAndSet(false, true)) return
        mutationJob = viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val transport = object : SemanticInteractionTransport {
                    override suspend fun read(): SemanticPane? = when (
                        val result = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }
                    ) {
                        is ApiResult.Success -> SemanticPane(result.value.pane.text, result.value.pane.revision)
                        else -> null
                    }

                    override suspend fun keys(keys: List<String>, expectedPrompt: String?): SemanticWriteOutcome =
                        if (keys.any { !MuxKeyGrammar.isSupported(it, mutableState.value.unsupportedKeys) }) SemanticWriteOutcome.FAILED
                        else when (val result = withCurrentActor { repository.keys(address, keys, expectedPrompt) }) {
                            is ApiResult.Success -> when {
                                result.value.ok -> SemanticWriteOutcome.OK
                                result.value.code == "prompt_changed" -> SemanticWriteOutcome.CHANGED
                                else -> SemanticWriteOutcome.FAILED
                            }
                            else -> SemanticWriteOutcome.FAILED
                        }

                    override suspend fun reply(
                        text: String,
                        submit: Boolean,
                        expectedPrompt: String?,
                    ): SemanticWriteOutcome = when (val result = withCurrentActor { repository.reply(address, text, submit, expectedPrompt) }) {
                        is ApiResult.Success -> when {
                            result.value.ok -> SemanticWriteOutcome.OK
                            result.value.code == "prompt_changed" -> SemanticWriteOutcome.CHANGED
                            else -> SemanticWriteOutcome.FAILED
                        }
                        else -> SemanticWriteOutcome.FAILED
                    }
                }
                val outcome = SemanticInteractionEngine(transport).execute(agent, displayedSurface, intent)
                mutableState.value = when (outcome) {
                    SemanticInteractionResult.SENT -> mutableState.value.copy(
                        sending = false,
                        status = confirmation(text(R.string.pane_dialog_sent, semanticIntentLabel(intent))),
                    )
                    SemanticInteractionResult.CHANGED -> mutableState.value.copy(
                        sending = false,
                        mutationError = text(R.string.pane_dialog_changed),
                    )
                    SemanticInteractionResult.FAILED -> mutableState.value.copy(
                        sending = false,
                        mutationError = text(R.string.pane_dialog_refused),
                    )
                }
                load()
            } finally {
                mutableState.value = mutableState.value.copy(sending = false)
                writeInFlight.set(false)
            }
        }
    }

    private fun semanticIntentLabel(intent: SemanticIntent): String = when (intent) {
        is SemanticIntent.Static -> intent.action.label
        is SemanticIntent.PointerSelect -> intent.action.label
        is SemanticIntent.PromptFeedback -> "feedback"
        is SemanticIntent.MultiToggle -> intent.action.label
        is SemanticIntent.OmpMultiToggle -> intent.action.label
        is SemanticIntent.OmpNoteSubmit -> "submit note"
        is SemanticIntent.MultiReviewSelect -> intent.action.label
        is SemanticIntent.PreviewOption -> "option ${intent.number}"
        is SemanticIntent.PreviewNote -> "note"
        SemanticIntent.MultiAdvance -> "advance"
    }

    fun beginDirectTyping(): Boolean {
        if (writeInFlight.get() || refuseTerminalWrite()) return false
        directTypingGeneration += 1
        directKeyQueue.clear()
        directTypingActive = true
        return true
    }

    fun endDirectTyping() {
        directTypingActive = false
        directTypingGeneration += 1
        directKeyQueue.clear()
    }

    /** Explicit Type mode queues committed keyboard input without adding a trailing Enter. */
    fun sendDirectKeys(keys: List<String>) {
        diagnostics.record("action", mapOf("name" to "pane.send_direct_keys", "count" to keys.size))
        if (!directTypingActive || keys.isEmpty() || keys.any { !MuxKeyGrammar.isValid(it) }) return
        if (terminalWriteBlock() != null || mutableState.value.pane == null ||
            directKeyQueue.size + keys.size > MAX_DIRECT_KEYS_PENDING
        ) {
            mutableState.value = mutableState.value.copy(
                mutationError = if (directKeyQueue.size + keys.size > MAX_DIRECT_KEYS_PENDING) {
                    text(R.string.pane_error_direct_queue_full)
                } else {
                    text(R.string.pane_error_direct_unavailable)
                },
            )
            failDirectQueue(directTypingGeneration)
            return
        }
        directKeyQueue.addAll(keys)
        drainDirectKeys()
    }

    private fun drainDirectKeys() {
        if (!directTypingActive || directKeyQueue.isEmpty() || mutableState.value.sending ||
            terminalWriteBlock() != null || writeInFlight.get()
        ) return
        val batch = buildList {
            repeat(minOf(MAX_DIRECT_KEY_BATCH, directKeyQueue.size)) {
                add(directKeyQueue.removeFirst())
            }
        }
        sendKeys(batch, direct = true, directGeneration = directTypingGeneration)
    }

    private fun sendKeys(keys: List<String>, direct: Boolean, directGeneration: Long? = null) {
        if (keys.any { !MuxKeyGrammar.isSupported(it, mutableState.value.unsupportedKeys) }) {
            mutableState.value = mutableState.value.copy(mutationError = text(R.string.pane_error_key_refused))
            if (direct) failDirectQueue(directGeneration)
            return
        }
        if (keys.isEmpty() || mutableState.value.sending || refuseTerminalWrite()) {
            if (direct) failDirectQueue(directGeneration)
            return
        }
        val displayed = currentPaneOrRefuse() ?: run {
            if (direct) failDirectQueue(directGeneration)
            return
        }
        // Type mode sends unbound, as the web's direct typing does: every keystroke changes the
        // prompt line, so a tail-region binding refreshed between keystrokes raced Herdr's echo and
        // refused every second key as "screen changed", dropping it on the floor (S25 Ultra,
        // 2026-09-10). The tray and the bound prompt actions keep their binding.
        val displayedPrompt = if (direct) {
            null
        } else {
            PromptBinding.tailRegion(displayed.text) ?: return
        }
        if (!writeInFlight.compareAndSet(false, true)) {
            if (direct) batchBackToFront(keys)
            return
        }
        mutationJob = viewModelScope.launch {
            var directSucceeded = false
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val expectedPrompt = if (displayedPrompt == null) {
                    null
                } else {
                    refreshBinding(displayed, displayedPrompt, PromptBinding::tailRegion) ?: return@launch
                }
                if (refuseTerminalWrite()) {
                    mutableState.value = mutableState.value.copy(sending = false)
                    return@launch
                }
                when (val result = withCurrentActor { repository.keys(address, keys, expectedPrompt = expectedPrompt) }) {
                is ApiResult.Success -> mutableState.value = if (result.value.ok) {
                    directSucceeded = true
                    mutableState.value.copy(
                        sending = false,
                        status = confirmation(
                            if (direct) text(R.string.pane_typed) else text(R.string.pane_keys_sent, keys.joinToString(" ")),
                        ),
                    )
                } else if (result.value.code == "prompt_changed") {
                    mutableState.value.copy(
                        sending = false,
                        mutationError = text(R.string.pane_error_screen_changed_key),
                    )
                } else {
                    mutableState.value.copy(
                        sending = false,
                        mutationError = result.value.error ?: text(R.string.pane_error_key_refused),
                    )
                }
                is ApiResult.Failure -> {
                    demoteIfRevoked(result.error)
                    mutableState.value = mutableState.value.copy(
                        sending = false,
                        mutationError = mutationFailure(result.error, R.string.pane_action_key),
                    )
                }
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_key_empty),
                )
                }
                if (direct && directSucceeded) {
                    directSucceeded = refreshAfterDirectBatch()
                } else {
                    load()
                }
            } finally {
                mutableState.value = mutableState.value.copy(sending = false)
                writeInFlight.set(false)
                if (direct) {
                    if (!directSucceeded) failDirectQueue(directGeneration)
                    if (directSucceeded && directGeneration == directTypingGeneration) drainDirectKeys()
                }
            }
        }
    }

    private fun batchBackToFront(keys: List<String>) {
        keys.asReversed().forEach(directKeyQueue::addFirst)
    }

    private fun failDirectQueue(generation: Long?) {
        if (generation != null && generation != directTypingGeneration) return
        directKeyQueue.clear()
        directTypingActive = false
    }

    private suspend fun refreshAfterDirectBatch(): Boolean = when (
        val result = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }
    ) {
        is ApiResult.Success -> {
            mutableState.value = mutableState.value.copy(
                pane = result.value.pane,
                loading = false,
                sending = false,
                error = null,
                composerReady = PromptBinding.composerRegion(agent, result.value.pane.text) != null,
                lastSuccessAt = System.currentTimeMillis(),
            )
            true
        }
        is ApiResult.Failure -> {
            mutableState.value = mutableState.value.copy(
                sending = false,
                mutationError = text(R.string.pane_error_direct_verify),
            )
            false
        }
        is ApiResult.NotModified -> {
            mutableState.value = mutableState.value.copy(
                sending = false,
                mutationError = text(R.string.pane_error_direct_verify),
            )
            false
        }
    }

    private suspend fun load() {
        if (!readInFlight.compareAndSet(false, true)) return
        try {
            run {
                val lines = paneRequestedLines
                val readActor = repository.readContext(address.scope, address.paneId) ?: throw DiscardedActor()
                adoptOriginalContext(readActor)
                val acceptedActor = mutableState.value.paneReadContext
                val canUseConditionalBody = mutableState.value.pane != null && acceptedActor != null &&
                    repository.isCurrent(acceptedActor) && acceptedActor.key == readActor.key &&
                    acceptedActor.navigationGeneration == readActor.navigationGeneration
                when (val result = repository.readPane(address, lines, markSeen = true,
                    conditional = canUseConditionalBody, expectedContext = readActor)) {
                    is ApiResult.Success -> {
                        if (!repository.isCurrent(readActor)) throw DiscardedActor()
                        pollingBackoff.succeeded()
                        val newlyAcceptedBody = result.status == 200 && !result.value.notModified
                        mutableState.value = mutableState.value.copy(
                            pane = result.value.pane,
                            // A conditional 304 has no newly accepted body. Preserve an already
                            // current actor through a 304, but never revive a retired one.
                            paneReadContext = if (newlyAcceptedBody) readActor else mutableState.value.paneReadContext?.takeIf(repository::isCurrent),
                            loading = false,
                            error = null,
                            composerReady = PromptBinding.composerRegion(agent, result.value.pane.text) != null,
                            lastSuccessAt = System.currentTimeMillis(),
                            requestedLines = paneRequestedLines,
                            desiredLines = paneDesiredLines,
                            loadedLines = result.value.pane.servedLines?.coerceIn(0, lines)
                                ?: result.value.pane.text.lineSequence().count().coerceAtMost(lines),
                            loadingOlder = lines < paneRequestedLines,
                        )
                        loadTranscript()
                    }
                    is ApiResult.Failure -> {
                        if (!repository.isCurrent(readActor)) throw DiscardedActor()
                        pollingBackoff.failed()
                        // Keep the last successful window actionable so a failed grow can be retried.
                        if (mutableState.value.loadingOlder && mutableState.value.loadedLines > 0) {
                            paneRequestedLines = mutableState.value.loadedLines
                            paneDesiredLines = paneRequestedLines
                        }
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            error = MainViewModel.describe(getApplication<Application>().resources, result.error),
                            requestedLines = paneRequestedLines,
                            desiredLines = paneDesiredLines,
                            loadingOlder = false,
                        )
                        return
                    }
                    is ApiResult.NotModified -> {
                        pollingBackoff.succeeded()
                        mutableState.value = mutableState.value.copy(loadingOlder = false)
                        return
                    }
                }
            }
        } finally {
            readInFlight.set(false)
        }
    }

    /**
     * Polls to skip before asking for history again after a "no journal" answer. A Claude started
     * in an open pane has no session log until its first turn, so one answer cannot be final: the
     * pane stayed on the mirror for good (S25 Ultra, 2026-09-11). The pause keeps an agent that
     * genuinely has no journal from costing a history read on every poll.
     */
    private var transcriptSkipPolls = 0

    /** The last poll page's ETag: an unchanged page then costs a bodiless 304, not ~36 KB. */
    @Volatile private var transcriptEtag: String? = null
    @Volatile private var chatEtag: String? = null
    private var legacyChat = false
    private var transcriptConnection = repository.connection.value
    private val chatWindow = LiveChatWindow()

    private suspend fun loadTranscript() {
        if (transcriptConnection !== repository.connection.value) {
            transcriptConnection = repository.connection.value
            chatWindow.clear()
            chatEtag = null
            legacyChat = false
            transcriptEtag = null
            transcriptSkipPolls = 0
            mutableState.value = mutableState.value.copy(
                transcript = emptyList(), transcriptQueued = emptyList(), transcriptHasMore = false,
                transcriptSendNow = null, transcriptSendPending = false, transcriptSendAttempt = null,
                transcriptAvailable = null, transcriptReadContext = null,
            )
        }
        if (transcriptSkipPolls > 0) {
            transcriptSkipPolls--
            return
        }
        // Keep one immutable read actor across transport dispatch and result acceptance. A later
        // redraw may keep old journal text, but cannot grant those entries a new image authority.
        val transcriptContext = repository.readContext(address.scope, address.paneId) ?: return
        if (!legacyChat) {
            val chatConnection = repository.connection.value
            val chatResult = withCurrentActor {
                repository.chat(address, limit = TRANSCRIPT_PAGE, after = chatWindow.after, etag = chatEtag, expectedContext = transcriptContext)
            }
            if (repository.connection.value !== chatConnection || !repository.isCurrent(transcriptContext)) return
            when (val result = chatResult) {
                is ApiResult.Success -> {
                    val page = result.value
                    if (!page.available) {
                        chatWindow.clear()
                        chatEtag = null
                        transcriptSkipPolls = TRANSCRIPT_RETRY_POLLS
                    mutableState.value = mutableState.value.copy(
                        transcriptAvailable = false, transcriptReason = page.reason, transcript = emptyList(),
                        transcriptQueued = emptyList(), transcriptSendNow = null, transcriptSendPending = false,
                        transcriptSendAttempt = null, transcriptHasMore = false, transcriptReadContext = transcriptContext,
                    )
                    } else {
                        val previousSendNow = mutableState.value.transcriptSendNow
                        val merged = chatWindow.merge(page)
                        if (!merged && previousSendNow != null && chatWindow.sendNow == null) {
                            // A malformed generation/revision is rejected without replacing the
                            // retained transcript or cursor, but its disarm must reach the visible
                            // pane state even though the page was not admitted.
                            mutableState.value = mutableState.value.copy(transcriptSendNow = null)
                        }
                        if (merged) {
                            acceptedReplies()?.reconcile(page)
                            chatEtag = result.etag
                            val sendNow = chatWindow.sendNow
                            val persistedAttempt = observeQueuedAction(sendNow)
                            val priorAttempt = mutableState.value.transcriptSendAttempt
                                ?.takeIf { prior -> sendNow?.let(prior::sameQueue) == true }
                            mutableState.value = mutableState.value.copy(
                                transcriptReadContext = transcriptContext,
                                transcript = chatWindow.entries,
                                transcriptQueued = chatWindow.queued,
                                transcriptSendNow = sendNow,
                                transcriptSendAttempt = persistedAttempt ?: priorAttempt,
                                transcriptAvailable = true,
                                transcriptReason = null,
                                transcriptHasMore = chatWindow.hasOlder,
                                codexAcceptedReplies = acceptedReplies()?.texts.orEmpty(),
                            )
                        }
                    }
                    return
                }
                is ApiResult.NotModified -> {
                    val admitted = mutableState.value.transcriptReadContext
                    if (admitted != null && repository.isCurrent(admitted) &&
                        admitted.key == transcriptContext.key && admitted.navigationGeneration == transcriptContext.navigationGeneration) {
                        mutableState.value = mutableState.value.copy(transcriptReadContext = admitted)
                    }
                    return
                }
                is ApiResult.Failure -> {
                    disarmQueuedAction()
                    val error = result.error
                    if (error is ApiFailure.Http && error.status == 404 || error is ApiFailure.Protocol && error.message == "Live chat is unavailable") {
                        legacyChat = true
                    } else return
                }
            }
        }
        val firstLoad = mutableState.value.transcript.isEmpty()
        val limit = if (firstLoad) TRANSCRIPT_PAGE else TRANSCRIPT_POLL
        // The long first page and the short poll page are different reads with different ETags.
        val etag = if (firstLoad) null else transcriptEtag
        when (val result = withCurrentActor {
            repository.history(address, limit = limit, etag = etag, expectedContext = transcriptContext)
        }) {
            is ApiResult.Success -> {
                if (!repository.isCurrent(transcriptContext)) return
                val page = result.value
                transcriptEtag = if (firstLoad || !page.available) null else result.etag
                if (!page.available) {
                    transcriptSkipPolls = TRANSCRIPT_RETRY_POLLS
                    mutableState.value = mutableState.value.copy(
                        transcriptAvailable = false,
                        transcriptReason = page.reason,
                        transcript = emptyList(),
                        transcriptReadContext = transcriptContext,
                    )
                    return
                }
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeNewer(mutableState.value.transcript, page.entries),
                    transcriptAvailable = true,
                    transcriptReason = null,
                    transcriptReadContext = transcriptContext,
                    // A poll's short page cannot say what lies before the oldest entry loaded.
                    transcriptHasMore = if (firstLoad) page.hasMore else mutableState.value.transcriptHasMore,
                )
            }
            is ApiResult.Failure -> Unit
            is ApiResult.NotModified -> {
                val admitted = mutableState.value.transcriptReadContext
                if (admitted != null && repository.isCurrent(admitted) &&
                    admitted.key == transcriptContext.key && admitted.navigationGeneration == transcriptContext.navigationGeneration) {
                    mutableState.value = mutableState.value.copy(transcriptReadContext = admitted)
                }
            }
        }
    }

    fun loadOlderTranscript() {
        val oldest = mutableState.value.transcript.firstOrNull()?.uuid ?: return
        // Bind the request to the authority visible at the user's action. A queued coroutine
        // cannot silently adopt replacement credentials after a navigation retirement.
        val readContext = repository.readContext(address.scope, address.paneId) ?: return
        viewModelScope.launch {
            if (!legacyChat) {
                val before = chatWindow.before ?: return@launch
                if (!repository.isCurrent(readContext)) return@launch
                val result = repository.chat(address, limit = TRANSCRIPT_PAGE, before = before, expectedContext = readContext)
                if (!repository.isCurrent(readContext)) return@launch
                if (result is ApiResult.Failure || result is ApiResult.NotModified) disarmQueuedAction()
                if (result is ApiResult.Success) {
                    val previousSendNow = mutableState.value.transcriptSendNow
                    val merged = result.value.available && chatWindow.merge(result.value)
                    if (merged) {
                        mutableState.value = mutableState.value.copy(
                            transcript = chatWindow.entries, transcriptQueued = chatWindow.queued,
                            transcriptHasMore = chatWindow.hasOlder, transcriptAvailable = true,
                            transcriptReadContext = readContext,
                        )
                    } else if (previousSendNow != null && chatWindow.sendNow == null) {
                        mutableState.value = mutableState.value.copy(transcriptSendNow = null)
                    }
                }
                return@launch
            }
            if (!repository.isCurrent(readContext)) return@launch
            val result = repository.history(
                address,
                limit = TRANSCRIPT_PAGE,
                before = oldest,
                expectedContext = readContext,
            )
            if (!repository.isCurrent(readContext)) return@launch
            if (result is ApiResult.Success && result.value.available) {
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeOlder(mutableState.value.transcript, result.value.entries),
                    transcriptHasMore = result.value.hasMore,
                    transcriptAvailable = true,
                    transcriptReadContext = readContext,
                )
            }
        }
    }

    private suspend fun loadSupportingData() {
        val configActor = repository.readContext(address.scope, address.paneId) ?: throw DiscardedActor()
        adoptOriginalContext(configActor)
        val config = repository.config(address.scope, configActor)
        if (!repository.isCurrent(configActor)) throw DiscardedActor()
        mutableState.value = mutableState.value.copy(filesConfigContext =
            configActor.takeIf { config is ApiResult.Success && config.value.control?.supportsNew("filesDiscovery") == true })
        if (config is ApiResult.Success) {
            val isShell = agent.equals("shell", ignoreCase = true)
            mutableState.value = mutableState.value.copy(
                quickReplies = ComposerActions.quickReplies(
                    agent,
                    isShell,
                    config.value.operatorQuickReplies,
                ),
                commands = ComposerActions.commands(agent, config.value.operatorCommands),
                keyPresets = ComposerActions.keyPresets(agent, config.value.operatorKeys),
                unsupportedKeys = config.value.mux?.unsupportedKeys.orEmpty().toSet(),
                mux = config.value.mux,
            )
            if (terminalWriteBlock() != null) failDirectQueue(directTypingGeneration)
        }
        val snapshotConnection = repository.connection.value
        val snapshot = withCurrentActor { repository.paneSnapshot(address.scope) }
        if (snapshot is ApiResult.Success && snapshotConnection == observedConnection &&
            repository.connection.value == snapshotConnection
        ) {
            deviceAuthorizationKnown = true
            deviceWriteAllowed = snapshot.value.device?.let { !it.enforced || it.authorized } ?: true
            (snapshot.value.agents + snapshot.value.shellPanes).firstOrNull {
                it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
            }?.agent?.takeIf { it.isNotBlank() }?.let { agent = it }
            mutableState.value = mutableState.value.copy(
                panes = snapshot.value.agents + snapshot.value.shellPanes,
                tabs = snapshot.value.tabs,
                servers = snapshot.value.servers,
                topologyKnown = true,
            )
            applyWriteAccess()
            if (terminalWriteBlock() != null) failDirectQueue(directTypingGeneration)
        }
    }

    fun onReplyDraftCleared() {
        mutableState.value = mutableState.value.copy(clearReplyDraft = false)
    }

    fun dismissAcceptedCodexReplies() {
        acceptedReplies()?.dismiss()
        mutableState.value = mutableState.value.copy(codexAcceptedReplies = emptyList())
    }

    private fun currentPaneOrRefuse(): PaneReadResponse? {
        val pane = mutableState.value.pane
        if (pane == null) {
            mutableState.value = mutableState.value.copy(
                mutationError = text(R.string.pane_error_wait_content),
            )
        }
        return pane
    }

    private fun terminalWriteBlock(): PaneWriteBlock? = PaneWriteGate.block(address, mutableState.value)

    private fun refuseTerminalWrite(): Boolean {
        val block = terminalWriteBlock() ?: return false
        mutableState.value = mutableState.value.copy(mutationError = writeBlockText(block))
        return true
    }

    private fun writeBlockText(block: PaneWriteBlock): String = when (block) {
        PaneWriteBlock.ReadOnly -> text(R.string.pane_action_read_only)
        PaneWriteBlock.Gone -> text(R.string.pane_write_gone)
        is PaneWriteBlock.Host -> when (val refusal = block.refusal) {
            is HostWriteRefusal.Incompatible -> refusal.detail?.let {
                text(R.string.pane_host_incompatible_detail, refusal.serverName, it)
            } ?: text(R.string.pane_host_incompatible, refusal.serverName)
            is HostWriteRefusal.Unreachable -> text(R.string.pane_host_unreachable, refusal.serverName)
        }
        is PaneWriteBlock.MissingCapability -> block.note?.takeIf(String::isNotBlank)
            ?: text(R.string.pane_write_missing_capability, block.capability)
    }

    private fun terminalDraftMatches(text: String, expected: TerminalDraft): Boolean {
        val current = TerminalComposerSemantics.terminalDraft(agent, text) ?: return false
        return !current.opaque && StableTerminalDraft.normalize(current.text) == StableTerminalDraft.normalize(expected.text)
    }

    /**
     * Clears a host-owned input only after a fresh exact-tail check, then waits and positively sees
     * the empty composer before returning the binding used by the reply write.
     */
    private suspend fun clearTakenOverTerminalDraft(
        displayed: PaneReadResponse,
        displayedBinding: String,
        expectedDraft: TerminalDraft,
    ): String? {
        val freshBinding = refreshBinding(displayed, displayedBinding, PromptBinding::tailRegion) ?: return null
        val freshText = mutableState.value.pane?.text ?: displayed.text
        if (!terminalDraftMatches(freshText, expectedDraft) || terminalWriteBlock() != null) {
            mutableState.value = mutableState.value.copy(
                sending = false,
                mutationError = text(R.string.pane_error_terminal_draft_changed),
            )
            return null
        }
        val clearCount = expectedDraft.text.codePointCount(0, expectedDraft.text.length) + TAKEOVER_BACKSPACE_MARGIN
        val clearKeys = buildList(clearCount + 1) {
            add("ctrl+k")
            repeat(clearCount) { add("Backspace") }
        }
        when (val result = withCurrentActor { repository.keys(address, clearKeys, expectedPrompt = freshBinding) }) {
            is ApiResult.Success -> if (!result.value.ok) {
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = if (result.value.code == "prompt_changed") {
                        text(R.string.pane_error_terminal_draft_changed)
                    } else {
                        result.value.error ?: text(R.string.pane_error_terminal_draft_clear)
                    },
                )
                return null
            }
            is ApiResult.Failure -> {
                demoteIfRevoked(result.error)
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = mutationFailure(result.error, R.string.pane_action_key),
                )
                return null
            }
            is ApiResult.NotModified -> {
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_terminal_draft_clear),
                )
                return null
            }
        }
        delay(TAKEOVER_SETTLE_MS)
        val cleared = when (val result = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }) {
            is ApiResult.Success -> result.value.pane
            else -> {
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_verify),
                )
                return null
            }
        }
        val clearedBinding = PromptBinding.composerRegion(agent, cleared.text)
        mutableState.value = mutableState.value.copy(
            pane = cleared,
            composerReady = clearedBinding != null,
            lastSuccessAt = System.currentTimeMillis(),
        )
        if (clearedBinding == null) {
            mutableState.value = mutableState.value.copy(
                sending = false,
                mutationError = text(R.string.pane_error_terminal_draft_not_cleared),
            )
            return null
        }
        return clearedBinding
    }

    private suspend fun refreshBinding(
        displayed: PaneReadResponse,
        displayedBinding: String,
        derive: (String) -> String?,
        wholePane: Boolean = true,
    ): String? {
        val fresh = when (val result = withCurrentActor { repository.readPane(address, paneRequestedLines, markSeen = true) }) {
            is ApiResult.Success -> result.value.pane
            is ApiResult.Failure -> {
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_verify),
                )
                return null
            }
            is ApiResult.NotModified -> {
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    mutationError = text(R.string.pane_error_verify),
                )
                return null
            }
        }
        val freshBinding = derive(fresh.text)
        val stillDisplayed = mutableState.value.pane?.let {
            if (wholePane) it.revision == displayed.revision && it.text == displayed.text
            else derive(it.text) == displayedBinding
        } == true
        if (!stillDisplayed || freshBinding == null || freshBinding != displayedBinding) {
            mutableState.value = mutableState.value.copy(
                pane = fresh,
                sending = false,
                mutationError = text(R.string.pane_error_screen_changed_input),
            )
            return null
        }
        mutableState.value = mutableState.value.copy(
            pane = fresh,
            composerReady = PromptBinding.composerRegion(agent, fresh.text) != null,
            lastSuccessAt = System.currentTimeMillis(),
        )
        return freshBinding
    }

    private fun mutationFailure(failure: ApiFailure, @androidx.annotation.StringRes actionRes: Int): String = when {
        failure is ApiFailure.Timeout || failure is ApiFailure.Network || failure is ApiFailure.Protocol ->
            text(R.string.pane_error_outcome_uncertain, text(actionRes))
        failure is ApiFailure.Http && failure.textDelivered ->
            text(R.string.pane_error_submit_incomplete)
        else -> MainViewModel.describe(getApplication<Application>().resources, failure)
    }

    private fun demoteIfRevoked(failure: ApiFailure) {
        if (failure is ApiFailure.Http && failure.retiresPairing()) {
            connectionPaired = false
            applyWriteAccess()
        }
    }

    private fun applyWriteAccess() {
        val writable = connectionPaired && deviceWriteAllowed && repository.writesAllowed(address.scope, address.paneId)
        val previous = mutableState.value
        val accessStatus = when {
            !connectionPaired -> text(R.string.pane_status_not_paired)
            !deviceAuthorizationKnown -> text(R.string.pane_status_checking_device)
            !deviceWriteAllowed -> text(R.string.pane_status_unauthorised)
            previous.status == text(R.string.pane_status_not_paired) ||
                previous.status == text(R.string.pane_status_checking_device) ||
                previous.status == text(R.string.pane_status_unauthorised) -> null
            else -> previous.status
        }
        mutableState.value = previous.copy(canWrite = writable, status = accessStatus)
        if (!writable) failDirectQueue(directTypingGeneration)
    }

    class Factory(
        private val application: Application,
        private val address: PaneAddress,
        private val agent: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PaneViewModel(application, address, agent) as T
    }

    private fun text(@androidx.annotation.StringRes resource: Int, vararg args: Any): String =
        getApplication<Application>().getString(resource, *args)

    companion object {
        const val POLL_MS = 2_000L
        const val STATUS_NOTICE_MS = 4_000L
        /** First load and each "Load older": the bridge's own default page, about 40 minutes of a busy agent. */
        const val TRANSCRIPT_PAGE = 200

        /** Each poll only tops up the newest turns; they merge into what is already loaded. */
        const val TRANSCRIPT_POLL = 60
        const val TRANSCRIPT_RETRY_POLLS = 8
        const val MAX_DIRECT_KEY_BATCH = 64
        const val MAX_DIRECT_KEYS_PENDING = 8_192
        const val MAX_REVIEWED_KEY_BATCH = 128
        const val MAX_QUEUED_SEND_KEYS = 12
        const val TAKEOVER_SETTLE_MS = 350L
        const val TAKEOVER_BACKSPACE_MARGIN = 32
    }
}
