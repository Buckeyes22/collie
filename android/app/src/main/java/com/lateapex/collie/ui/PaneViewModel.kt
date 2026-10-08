package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.MuxKeyGrammar
import com.lateapex.collie.domain.PollingBackoff
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.ui.terminal.PromptBinding
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
    val loadedLines: Int = 0,
    val loadingOlder: Boolean = false,
    val transcript: List<TranscriptEntry> = emptyList(),
    val transcriptAvailable: Boolean? = null,
    val transcriptReason: String? = null,
    val transcriptHasMore: Boolean = false,
)

class PaneViewModel(
    application: Application,
    private val address: PaneAddress,
    initialAgent: String?,
    private val repository: CollieRepository =
        (application as CollieApplication).container.repository,
    private val diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder =
        (application as CollieApplication).container.diagnostics,
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
    private val readInFlight = AtomicBoolean(false)
    private val pollingBackoff = PollingBackoff()
    private val writeInFlight = AtomicBoolean(false)
    private val directKeyQueue = ArrayDeque<String>()
    private var directTypingActive = false
    private var directTypingGeneration = 0L
    private var supportPoll = 0
    private var paneRequestedLines = PaneScrollbackWindow.INITIAL_LINES

    init {
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
                if (connection != observedConnection) {
                    observedConnection = connection
                    deviceAuthorizationKnown = false
                    deviceWriteAllowed = false
                    mutableState.value = mutableState.value.copy(
                        topologyKnown = false,
                        panes = emptyList(),
                        tabs = emptyList(),
                        servers = null,
                    )
                }
                connectionPaired = connection?.isPaired == true
                applyWriteAccess()
            }
        }
    }

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (true) {
                load()
                if (supportPoll++ % 3 == 0) loadSupportingData()
                delay(pollingBackoff.delayAfter(POLL_MS))
            }
        }
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
        if (previous.loadingOlder || paneRequestedLines >= PaneScrollbackWindow.MAX_LINES) return null
        val target = PaneScrollbackWindow.grow(paneRequestedLines)
        paneRequestedLines = target
        mutableState.value = previous.copy(
            requestedLines = target,
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
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val expectedPrompt = if (terminalDraftToClear == null) {
                    refreshBinding(displayed, displayedPrompt) { PromptBinding.composerRegion(agent, it) }
                } else {
                    clearTakenOverTerminalDraft(displayed, displayedPrompt, terminalDraftToClear)
                } ?: return@launch
                if (refuseTerminalWrite()) {
                    mutableState.value = mutableState.value.copy(sending = false)
                    return@launch
                }
                when (val result = repository.reply(address, clean, submit = true, expectedPrompt = expectedPrompt)) {
                is ApiResult.Success -> {
                    val response = result.value
                    mutableState.value = if (response.ok) {
                        mutableState.value.copy(
                            sending = false,
                            status = confirmation(text(R.string.pane_reply_sent)),
                            clearReplyDraft = true,
                        )
                    } else if (response.textDelivered) {
                        mutableState.value.copy(
                            sending = false,
                            mutationError = text(R.string.pane_error_text_delivered),
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

    /**
     * Executes one adapter-derived terminal action after a fresh exact-pane re-read. The semantic
     * signature and revision must still match and the selected action must still exist verbatim.
     * The resulting keys cross the repository boundary once and are never retried.
     */
    fun sendSemanticAction(displayedSurface: SemanticSurface, displayedAction: SemanticAction) {
        val displayedPane = currentPaneOrRefuse() ?: return
        if (mutableState.value.sending || refuseTerminalWrite() || displayedSurface.interactionLocked ||
            displayedPane.revision != displayedSurface.revision || displayedAction !in displayedSurface.actions ||
            displayedAction.keys.any { !MuxKeyGrammar.isValid(it) }
        ) return
        if (!writeInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val fresh = when (val result = repository.readPane(address, paneRequestedLines, markSeen = true)) {
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
                when (val result = repository.keys(address, verified.keys, expectedPrompt = displayedSurface.regionSignature)) {
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
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    sending = true,
                    error = null,
                    mutationError = null,
                    status = null,
                )
                val transport = object : SemanticInteractionTransport {
                    override suspend fun read(): SemanticPane? = when (
                        val result = repository.readPane(address, paneRequestedLines, markSeen = true)
                    ) {
                        is ApiResult.Success -> SemanticPane(result.value.pane.text, result.value.pane.revision)
                        else -> null
                    }

                    override suspend fun keys(keys: List<String>, expectedPrompt: String?): SemanticWriteOutcome =
                        when (val result = repository.keys(address, keys, expectedPrompt)) {
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
                    ): SemanticWriteOutcome = when (val result = repository.reply(address, text, submit, expectedPrompt)) {
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
                writeInFlight.set(false)
            }
        }
    }

    private fun semanticIntentLabel(intent: SemanticIntent): String = when (intent) {
        is SemanticIntent.Static -> intent.action.label
        is SemanticIntent.PromptFeedback -> "feedback"
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
        if (keys.isEmpty() || keys.any { !MuxKeyGrammar.isValid(it) } || mutableState.value.sending || refuseTerminalWrite()) {
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
        viewModelScope.launch {
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
                when (val result = repository.keys(address, keys, expectedPrompt = expectedPrompt)) {
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
        val result = repository.readPane(address, paneRequestedLines, markSeen = true)
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
            do {
                val lines = paneRequestedLines
                when (val result = repository.readPane(address, lines, markSeen = true)) {
                    is ApiResult.Success -> {
                        pollingBackoff.succeeded()
                        mutableState.value = mutableState.value.copy(
                            pane = result.value.pane,
                            loading = false,
                            error = null,
                            composerReady = PromptBinding.composerRegion(agent, result.value.pane.text) != null,
                            lastSuccessAt = System.currentTimeMillis(),
                            requestedLines = paneRequestedLines,
                            loadedLines = lines,
                            loadingOlder = lines < paneRequestedLines,
                        )
                        loadTranscript()
                    }
                    is ApiResult.Failure -> {
                        pollingBackoff.failed()
                        // Keep the last successful window actionable so a failed grow can be retried.
                        if (mutableState.value.loadingOlder && mutableState.value.loadedLines > 0) {
                            paneRequestedLines = mutableState.value.loadedLines
                        }
                        mutableState.value = mutableState.value.copy(
                            loading = false,
                            error = MainViewModel.describe(getApplication<Application>().resources, result.error),
                            requestedLines = paneRequestedLines,
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
            } while (mutableState.value.loadedLines < paneRequestedLines)
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
    private var transcriptEtag: String? = null

    private suspend fun loadTranscript() {
        if (transcriptSkipPolls > 0) {
            transcriptSkipPolls--
            return
        }
        val firstLoad = mutableState.value.transcript.isEmpty()
        val limit = if (firstLoad) TRANSCRIPT_PAGE else TRANSCRIPT_POLL
        // The long first page and the short poll page are different reads with different ETags.
        val etag = if (firstLoad) null else transcriptEtag
        when (val result = repository.history(address, limit = limit, etag = etag)) {
            is ApiResult.Success -> {
                val page = result.value
                transcriptEtag = if (firstLoad || !page.available) null else result.etag
                if (!page.available) {
                    transcriptSkipPolls = TRANSCRIPT_RETRY_POLLS
                    mutableState.value = mutableState.value.copy(
                        transcriptAvailable = false,
                        transcriptReason = page.reason,
                        transcript = emptyList(),
                    )
                    return
                }
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeNewer(mutableState.value.transcript, page.entries),
                    transcriptAvailable = true,
                    transcriptReason = null,
                    // A poll's short page cannot say what lies before the oldest entry loaded.
                    transcriptHasMore = if (firstLoad) page.hasMore else mutableState.value.transcriptHasMore,
                )
            }
            is ApiResult.Failure, is ApiResult.NotModified -> Unit
        }
    }

    fun loadOlderTranscript() {
        val oldest = mutableState.value.transcript.firstOrNull()?.uuid ?: return
        viewModelScope.launch {
            val result = repository.history(address, limit = TRANSCRIPT_PAGE, before = oldest)
            if (result is ApiResult.Success && result.value.available) {
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeOlder(mutableState.value.transcript, result.value.entries),
                    transcriptHasMore = result.value.hasMore,
                )
            }
        }
    }

    private suspend fun loadSupportingData() {
        val config = repository.config()
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
        val snapshot = repository.snapshot(address.scope)
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
        when (val result = repository.keys(address, clearKeys, expectedPrompt = freshBinding)) {
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
        val cleared = when (val result = repository.readPane(address, paneRequestedLines, markSeen = true)) {
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
    ): String? {
        val fresh = when (val result = repository.readPane(address, paneRequestedLines, markSeen = true)) {
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
            it.revision == displayed.revision && it.text == displayed.text
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
        if (failure is ApiFailure.Http && failure.message == "device not paired") {
            repository.demoteToReadOnly()
            connectionPaired = false
            applyWriteAccess()
        }
    }

    private fun applyWriteAccess() {
        val writable = connectionPaired && deviceWriteAllowed
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
        const val TAKEOVER_SETTLE_MS = 350L
        const val TAKEOVER_BACKSPACE_MARGIN = 32
    }
}
