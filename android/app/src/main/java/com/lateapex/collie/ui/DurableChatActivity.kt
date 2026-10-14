package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.DurableAnswerRequest
import com.lateapex.collie.network.DurableControlRequest
import com.lateapex.collie.network.DurableOutcome
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.mutationOutcomeUnknown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Native Chat surface for Collie's screenless durable-agent pane. It exposes no terminal controls. */
class DurableChatActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private val imageLoader get() = (application as CollieApplication).container.imageLoader
    private lateinit var address: PaneAddress
    private lateinit var paneTitle: String
    private lateinit var status: TextView
    private lateinit var turns: LinearLayout
    private lateinit var askCards: LinearLayout
    private lateinit var composer: EditText
    private lateinit var steer: CheckBox
    private lateinit var sendButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var scroll: ScrollView
    private var notice: String? = null

    @Volatile private var currentActor: ScopedReadContext? = null
    private var currentPane: PaneSummary? = null
    private val liveChat = LiveChatWindow()
    private lateinit var promptContext: ChatPromptContextView
    private var entries: List<TranscriptEntry> = emptyList()
    private var expandedTools = mutableSetOf<Pair<String, Int>>()
    private var renderedEntries: List<TranscriptEntry>? = null
    private var renderer: HistoryTurnRenderer? = null
    private var busy = false
    private var pendingControl: PendingControl? = null
    private var pendingAnswer: PendingAnswer? = null
    private var retirement: AutoCloseable? = null
    private var lastAskRender: List<Any?>? = null
    private var screenlessVerified = false
    private var liveReadReady = false
    private val journal by lazy { com.lateapex.collie.data.DurableIntentJournal(applicationContext) }
    private var recoveredIntent: com.lateapex.collie.data.DurableIntentRecord? = null
    private var journalUnreadable = false
    private fun owner(actor: ScopedReadContext) = com.lateapex.collie.data.DurableIntentOwner.from(actor.connection, address)
    private fun unsettled(): Boolean = pendingControl != null || pendingAnswer != null || recoveredIntent != null || journalUnreadable
    private fun recoverJournal(actor: ScopedReadContext) {
        if (busy || pendingControl != null || pendingAnswer != null) return
        when (val record = journal.read(owner(actor))) {
            com.lateapex.collie.data.DurableIntentJournal.Lookup.Empty -> { recoveredIntent = null; journalUnreadable = false }
            is com.lateapex.collie.data.DurableIntentJournal.Lookup.Pending -> {
                recoveredIntent = record.record
                journalUnreadable = false
                showNotice(getString(R.string.durable_chat_unknown))
            }
            com.lateapex.collie.data.DurableIntentJournal.Lookup.Unreadable -> { journalUnreadable = true; showNotice(getString(R.string.durable_chat_unknown)) }
        }
    }
    private fun settled(actor: ScopedReadContext, requestId: String) {
        journal.clear(owner(actor), requestId)
        if (recoveredIntent?.requestId == requestId) recoveredIntent = null
    }
    private lateinit var abandonButton: MaterialButton

    private data class PendingControl(
        val request: DurableControlRequest,
        val target: DurableTarget,
        val text: String,
        val steer: Boolean,
        val stop: Boolean,
        val actor: ScopedReadContext,
    )

    private data class PendingAnswer(
        val request: DurableAnswerRequest,
        val askId: String,
        val optionId: String,
        val actor: ScopedReadContext,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        address = PaneAddress(
            Scope(
                host = intent.getStringExtra(EXTRA_HOST)?.trim()?.takeIf(String::isNotEmpty),
                session = intent.getStringExtra(EXTRA_SESSION)?.trim()?.takeIf(String::isNotEmpty),
            ),
            intent.getStringExtra(EXTRA_PANE_ID)?.trim()?.takeIf(String::isNotEmpty) ?: run { finish(); return },
        )
        paneTitle = intent.getStringExtra(EXTRA_TITLE)?.trim()?.takeIf(String::isNotEmpty) ?: getString(R.string.durable_chat_title)
        setContentView(buildContent())
        window.applySafeContentInsets(findViewById(ROOT_ID))
        repository.selectSubject(address.scope, address.paneId)
        retirement = repository.onReadContextRetired { event ->
            val observed = currentActor
            if (event.affects(observed)) acknowledgeChatRetirement {
                if (observed != null && event.reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) journal.retire(owner(observed))
                recoveredIntent = null
                journalUnreadable = false
                busy = false
                liveReadReady = false
                currentActor = null
                currentPane = null
                liveChat.clear()
                promptContext.showPrompt(null)
                entries = emptyList()
                renderedEntries = null
                pendingAnswer = null
                pendingControl = null
                notice = null
                renderer = null
                lastAskRender = null
                askCards.removeAllViews()
                turns.removeAllViews()
                showNotice(getString(R.string.durable_chat_unavailable))
                updateControls()
                if (event.reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) finish()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::address.isInitialized) repository.selectSubject(address.scope, address.paneId)
    }

    override fun onStop() {
        liveReadReady = false
        updateControls()
        updateAskCards()
        super.onStop()
    }

    override fun onDestroy() {
        retirement?.close()
        retirement = null
        renderer = null
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            id = ROOT_ID
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@DurableChatActivity).apply {
                text = getString(R.string.navigate_back)
                setOnClickListener { finish() }
                contentDescription = getString(R.string.navigate_back)
            })
            addView(TextView(this@DurableChatActivity).apply {
                text = paneTitle
                textSize = 20f
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = getString(R.string.durable_chat_loading)
            textSize = 13f
            setTextColor(getColor(R.color.collie_muted))
            setPadding(0, dp(4), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        root.addView(MaterialButton(this).apply {
            setText(R.string.pane_action_history)
            setOnClickListener {
                val actor = currentActor ?: return@setOnClickListener
                if (liveReadReady && repository.isCurrent(actor)) startActivity(HistoryActivity.intent(this@DurableChatActivity, address, paneTitle, "durable"))
            }
        })
        askCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { isFillViewport = false }
        turns = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
        }
        promptContext = ChatPromptContextView(this)
        scroll.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(askCards)
            addView(promptContext)
            addView(turns)
        }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        composer = EditText(this).apply {
            hint = getString(R.string.durable_chat_send_hint)
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(getColor(R.color.collie_foreground))
        }
        root.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        steer = CheckBox(this).apply {
            text = getString(R.string.durable_chat_steer)
            isVisible = false
        }
        root.addView(steer)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sendButton = MaterialButton(this).apply { text = getString(R.string.durable_chat_send); setOnClickListener { submitControl(stop = false) } }
        stopButton = MaterialButton(this).apply { text = getString(R.string.durable_chat_stop); setOnClickListener { submitControl(stop = true) } }
        retryButton = MaterialButton(this).apply { text = getString(R.string.durable_chat_retry); isVisible = false; setOnClickListener { retryUnknown() } }
        controls.addView(sendButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        root.addView(retryButton)
        abandonButton = MaterialButton(this).apply {
            setText(R.string.durable_chat_resolve_unknown)
            isVisible = false
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@DurableChatActivity)
                    .setMessage(R.string.durable_chat_resolve_unknown_detail)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.durable_chat_resolve_unknown) { _, _ ->
                        if (busy || !liveReadReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@setPositiveButton
                        val actor = currentActor?.takeIf(repository::isCurrent) ?: return@setPositiveButton
                        if (journalUnreadable) {
                            if (!journal.discardUnreadable(owner(actor))) return@setPositiveButton
                            journalUnreadable = false
                        }
                        val requestId = pendingControl?.request?.requestId ?: pendingAnswer?.request?.requestId ?: recoveredIntent?.requestId
                        if (requestId != null && !journal.clear(owner(actor), requestId)) return@setPositiveButton
                        pendingControl = null
                        pendingAnswer = null
                        recoveredIntent = null
                        updateControls()
                        updateAskCards()
                    }.show()
            }
        }
        root.addView(abandonButton)
        composer.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateControls()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        steer.setOnCheckedChangeListener { _, _ -> updateControls() }
        updateControls()
        return root
    }

    private suspend fun refresh() {
        val actor = repository.readContext(address.scope, address.paneId) ?: return
        val snapshot = repository.snapshot(address.scope, actor)
        if (snapshot !is ApiResult.Success) {
            liveReadReady = false
            updateControls()
            updateAskCards()
            if (currentActor == null) showNotice(getString(R.string.durable_chat_unavailable))
            return
        }
        if (!repository.isCurrent(actor)) return
        val pane = snapshot.value.agents.firstOrNull { matchesAddress(it) }
        if (pane == null || pane.agent != "durable") {
            showNotice(getString(R.string.durable_chat_unavailable))
            currentActor = actor
            currentPane = null
            updateAskCards()
            updateControls()
            return
        }
        val configuration = repository.config(address.scope, actor)
        val muxName = (configuration as? ApiResult.Success)?.value?.mux?.name
        screenlessVerified = muxName == "durable"
        if (!screenlessVerified) {
            currentActor = actor
            currentPane = null
            showNotice(getString(R.string.durable_chat_not_durable))
            updateAskCards()
            updateControls()
            return
        }
        if (!repository.isCurrent(actor)) return
        currentActor = actor
        currentPane = pane
        liveReadReady = true
        recoverJournal(actor)
        if (notice == getString(R.string.durable_chat_unavailable) || notice == getString(R.string.durable_chat_not_durable)) notice = null
        pendingControl = pendingControl?.takeIf { repository.isCurrent(it.actor) }
        pendingAnswer = pendingAnswer?.takeIf { repository.isCurrent(it.actor) }
        updateAskCards()
        updateControls()
        when (val history = repository.chat(address, limit = HISTORY_LIMIT, after = liveChat.after, expectedContext = actor)) {
            is ApiResult.Success -> if (repository.isCurrent(actor)) {
                if (!history.value.available) {
                    showNotice(getString(R.string.durable_chat_history_unavailable, history.value.reason.orEmpty()))
                } else {
                    if (notice == null) status.text = pane.status.name.lowercase().replaceFirstChar(Char::uppercase)
                    if (liveChat.merge(history.value)) {
                        entries = liveChat.entries
                        promptContext.showPrompt(liveChat.lastPrompt)
                        renderEntries(actor)
                    } else showNotice(getString(R.string.durable_chat_history_unavailable, "unreadable chat window"))
                }
            }
            is ApiResult.Failure -> if (repository.isCurrent(actor)) {
                showNotice(getString(R.string.durable_chat_history_unavailable, history.error.displayText()))
            }
            is ApiResult.NotModified -> Unit
        }
    }

    private fun matchesAddress(pane: PaneSummary): Boolean = pane.paneId == address.paneId &&
        pane.host == address.scope.host && pane.session == address.scope.session

    private fun updateAskCards() {
        val pane = currentPane
        val actor = currentActor
        val asks = pane?.asks.orEmpty()
        val existingPending = pendingAnswer
        val renderKey = listOf(asks, existingPending?.request, pendingControl?.request, recoveredIntent, journalUnreadable, busy, liveReadReady, actor?.key, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        if (lastAskRender == renderKey) return
        lastAskRender = renderKey
        askCards.removeAllViews()
        if (asks.isEmpty()) return
        for (ask in asks) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_history_tool)
            }
            card.addView(TextView(this).apply { text = ask.title; textSize = 15f; setTextColor(getColor(R.color.collie_foreground)) })
            ask.reason?.takeIf(String::isNotBlank)?.let { reason -> card.addView(bodyText(reason)) }
            ask.tool?.let { tool ->
                card.addView(bodyText("${tool.name}: ${tool.summary}"))
                tool.output?.takeIf(String::isNotBlank)?.let { card.addView(bodyText(it)) }
            }
            if (ask.cleaned) card.addView(bodyText(getString(R.string.durable_chat_cleaned)))
            val options = DurableChatPolicy.answerable(ask)
            if (options.isEmpty()) {
                card.addView(bodyText(getString(R.string.durable_chat_not_ready)))
            } else {
                card.addView(TextView(this).apply { text = getString(R.string.durable_chat_ask); setTextColor(getColor(R.color.collie_muted)) })
                for (option in options) {
                    card.addView(MaterialButton(this).apply {
                        text = option.label
                        isEnabled = liveReadReady && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !busy && actor != null && repository.isCurrent(actor) && !unsettled()
                        setOnClickListener { answer(ask.id, ask.revision, option.id) }
                    })
                }
                if (existingPending?.askId == ask.id && existingPending.request.revision == ask.revision) {
                    card.addView(MaterialButton(this).apply {
                        text = getString(R.string.durable_chat_retry)
                        isEnabled = liveReadReady && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !busy && actor?.let { sameActor(it, existingPending.actor) } == true && repository.isCurrent(existingPending.actor)
                        setOnClickListener { retryAnswer(existingPending) }
                    })
                }
            }
            askCards.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderEntries(actor: ScopedReadContext) {
        if (renderedEntries == entries) return
        val wasAtBottom = scroll.getChildAt(0)?.let { scroll.scrollY + scroll.height >= it.height - dp(32) } ?: true
        turns.removeAllViews()
        val renderer = HistoryTurnRenderer(
            this,
            "durable",
            expandedTools,
            onToolToggle = { uuid, index -> toggleTool(uuid, index, actor) },
            imageLoader = imageLoader,
            readContext = actor,
            readContextForEntry = { uuid -> actor.takeIf { entries.any { entry -> entry.uuid == uuid } } },
        )
        this.renderer = renderer
        if (entries.isEmpty()) turns.addView(bodyText(getString(R.string.durable_chat_empty)))
        entries.forEach { entry ->
            val turn = HistoryPresentation.turn(entry, "durable", resources)
            turns.addView(renderer.turnView(turn, showHeader = true))
        }
        renderedEntries = entries.toList()
        if (wasAtBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toggleTool(uuid: String, partIndex: Int, actor: ScopedReadContext) {
        if (!repository.isCurrent(actor) || entries.none { it.uuid == uuid }) return
        val key = uuid to partIndex
        if (key in expandedTools) expandedTools -= key else expandedTools += key
        renderedEntries = null
        renderEntries(actor)
    }

    private fun submitControl(stop: Boolean) {
        val pane = currentPane ?: return
        val actor = currentActor ?: return
        if (!liveReadReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !screenlessVerified || busy || unsettled() || !repository.isCurrent(actor)) return
        val target = DurableChatPolicy.target(pane.turnId, pane.asks) ?: run { showNotice(getString(R.string.durable_chat_no_target)); return }
        val text = if (stop) "" else composer.text?.toString().orEmpty()
        val steering = !stop && steer.isChecked && DurableChatPolicy.canSteer(pane.status)
        if (!stop && text.toByteArray(Charsets.UTF_8).size !in 1..MAX_SEND_BYTES) return
        val request = DurableControlRequest(
            requestId = UUID.randomUUID().toString(),
            turnId = target.turnId,
            askId = target.askId,
            text = text.takeUnless { stop },
            steer = true.takeIf { steering },
        )
        if (!request.valid(send = !stop)) return
        performControl(PendingControl(request, target, text, steering, stop, actor))
    }

    private fun retryUnknown() {
        val pending = pendingControl ?: return
        val pane = currentPane ?: return
        val actor = currentActor ?: return
        val target = DurableChatPolicy.target(pane.turnId, pane.asks)
        val currentText = if (pending.stop) "" else composer.text?.toString().orEmpty()
        val currentSteer = !pending.stop && steer.isChecked
        if (!sameActor(actor, pending.actor) || !repository.isCurrent(pending.actor) ||
            !DurableChatPolicy.retryStillNamesIntent(pending.target, target, pending.text,
                currentText, pending.steer, currentSteer)
        ) {
            showNotice(getString(R.string.durable_chat_retry_changed))
            pendingControl = null
            retryButton.isVisible = false
            return
        }
        performControl(pending)
    }

    private fun performControl(intent: PendingControl) {
        if (!liveReadReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || busy || !repository.isCurrent(intent.actor)) return
        if (!journal.reserve(owner(intent.actor), if (intent.stop) "abort" else "send", intent.request.requestId,
            kotlinx.serialization.json.Json.encodeToString(DurableControlRequest.serializer(), intent.request))) {
            recoverJournal(intent.actor); updateControls(); return
        }
        busy = true
        updateControls()
        lifecycleScope.launch {
            val result = try {
                if (intent.stop) repository.durableAbort(address, intent.request, intent.actor)
                else repository.durableSend(address, intent.request, intent.actor)
            } catch (cancelled: CancellationException) {
                busy = false
                pendingControl = intent
                throw cancelled
            } catch (error: Exception) {
                ApiResult.Failure(ApiFailure.Network(error.message))
            }
            if (!repository.isCurrent(intent.actor)) return@launch
            busy = false
            val uncertain = when (result) {
                is ApiResult.Success -> result.value.outcome in setOf("unknown", "maybe-applied")
                is ApiResult.Failure -> result.error.mutationOutcomeUnknown()
                is ApiResult.NotModified -> true
            }
            if (!uncertain) settled(intent.actor, intent.request.requestId)
            var refreshAfter = false
            when (result) {
                is ApiResult.Success -> {
                    if (!result.value.valid(answer = false, status = result.status)) {
                        pendingControl = null
                        showNotice(getString(R.string.durable_chat_refused, "unreadable control outcome"))
                    } else {
                        handleControlOutcome(result.value, intent)
                        refreshAfter = !result.value.older && result.value.outcome in setOf("ok", "stale", "maybe-applied", "too-late")
                    }
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) {
                    pendingControl = intent
                    showNotice(getString(R.string.durable_chat_unknown))
                } else {
                    pendingControl = null
                    showNotice(getString(R.string.durable_chat_refused, result.error.displayText()))
                }
                is ApiResult.NotModified -> {
                    pendingControl = intent
                    showNotice(getString(R.string.durable_chat_refused, "unexpected response"))
                }
            }
            updateControls()
            if (refreshAfter) refresh()
        }
    }

    private fun handleControlOutcome(outcome: DurableOutcome, intent: PendingControl) {
        when (DurableChatPolicy.reading(outcome)) {
            DurableReading.COMPLETE -> {
                pendingControl = null
                if (!intent.stop) {
                    composer.text?.clear()
                    steer.isChecked = false
                }
                showNotice(getString(if (intent.stop) R.string.durable_chat_stopped else R.string.durable_chat_sent))
            }
            DurableReading.STALE -> {
                pendingControl = null
                showNotice(getString(if (outcome.older) R.string.durable_chat_older else R.string.durable_chat_moved))
            }
            DurableReading.REFUSED -> {
                pendingControl = null
                showNotice(getString(R.string.durable_chat_refused, outcome.detail ?: outcome.reason.orEmpty()))
            }
            DurableReading.UNKNOWN -> {
                pendingControl = intent
                showNotice(getString(R.string.durable_chat_unknown))
            }
            DurableReading.MAYBE_APPLIED -> {
                pendingControl = intent
                showNotice(getString(R.string.durable_chat_maybe))
            }
            DurableReading.TOO_LATE -> {
                pendingControl = null
                showNotice(getString(R.string.durable_chat_too_late))
            }
        }
    }

    private fun answer(askId: String, revision: Int, optionId: String) {
        val actor = currentActor ?: return
        val ask = currentPane?.asks?.firstOrNull { it.id == askId && it.revision == revision } ?: return
        val option = ask.options.firstOrNull { it.id == optionId }?.takeIf(ask::answerable) ?: return
        val request = DurableAnswerRequest(UUID.randomUUID().toString(), askId, revision, option.id)
        if (request.valid()) performAnswer(PendingAnswer(request, askId, optionId, actor))
    }

    private fun retryAnswer(pending: PendingAnswer) {
        val ask = currentPane?.asks?.firstOrNull { it.id == pending.askId && it.revision == pending.request.revision } ?: return
        val option = ask.options.firstOrNull { it.id == pending.optionId }?.takeIf(ask::answerable) ?: return
        val actor = currentActor
        if (actor == null || !sameActor(actor, pending.actor) || !repository.isCurrent(pending.actor) || option.id != pending.request.optionId) {
            showNotice(getString(R.string.durable_chat_retry_changed))
            pendingAnswer = null
            return
        }
        performAnswer(pending)
    }

    private fun performAnswer(intent: PendingAnswer) {
        if (!liveReadReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || busy || !repository.isCurrent(intent.actor)) return
        if (!journal.reserve(owner(intent.actor), "answer", intent.request.requestId,
            kotlinx.serialization.json.Json.encodeToString(DurableAnswerRequest.serializer(), intent.request))) {
            recoverJournal(intent.actor); updateAskCards(); updateControls(); return
        }
        busy = true
        updateAskCards()
        updateControls()
        lifecycleScope.launch {
            val result = try {
                repository.answerAsk(address, intent.request, intent.actor)
            } catch (cancelled: CancellationException) {
                busy = false
                pendingAnswer = intent
                throw cancelled
            } catch (error: Exception) {
                ApiResult.Failure(ApiFailure.Network(error.message))
            }
            if (!repository.isCurrent(intent.actor)) return@launch
            busy = false
            val uncertain = when (result) {
                is ApiResult.Success -> result.value.outcome in setOf("unknown", "maybe-applied")
                is ApiResult.Failure -> result.error.mutationOutcomeUnknown()
                is ApiResult.NotModified -> true
            }
            if (!uncertain) settled(intent.actor, intent.request.requestId)
            var refreshAfter = false
            when (result) {
                is ApiResult.Success -> {
                    val outcome = result.value
                    if (!outcome.valid(answer = true, status = result.status)) {
                        pendingAnswer = null
                        showNotice(getString(R.string.durable_chat_refused, "unreadable answer outcome"))
                    } else {
                        refreshAfter = !outcome.older && outcome.outcome in setOf("taken", "stale", "maybe-applied", "too-late")
                        when (DurableChatPolicy.reading(outcome)) {
                        DurableReading.COMPLETE -> {
                            pendingAnswer = null
                            showNotice(getString(R.string.durable_chat_answered))
                        }
                        DurableReading.UNKNOWN -> {
                            pendingAnswer = intent
                            showNotice(getString(R.string.durable_chat_unknown))
                        }
                        DurableReading.MAYBE_APPLIED -> {
                            pendingAnswer = intent
                            showNotice(getString(R.string.durable_chat_maybe))
                        }
                        DurableReading.TOO_LATE, DurableReading.STALE -> {
                            pendingAnswer = null
                            showNotice(getString(if (outcome.older) R.string.durable_chat_older else R.string.durable_chat_too_late))
                        }
                        DurableReading.REFUSED -> {
                            pendingAnswer = null
                            showNotice(getString(R.string.durable_chat_refused, outcome.detail ?: outcome.reason.orEmpty()))
                        }
                        }
                    }
                }
                is ApiResult.Failure -> if (result.error.mutationOutcomeUnknown()) {
                    pendingAnswer = intent
                    showNotice(getString(R.string.durable_chat_unknown))
                } else {
                    pendingAnswer = null
                    showNotice(getString(R.string.durable_chat_refused, result.error.displayText()))
                }
                is ApiResult.NotModified -> {
                    pendingAnswer = intent
                    showNotice(getString(R.string.durable_chat_refused, "unexpected response"))
                }
            }
            updateAskCards()
            updateControls()
            if (refreshAfter) refresh()
        }
    }

    private fun updateControls() {
        if (!::sendButton.isInitialized) return
        val pane = currentPane
        val actor = currentActor
        val target = pane?.let { DurableChatPolicy.target(it.turnId, it.asks) }
        val active = liveReadReady && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && screenlessVerified && pane != null && actor != null && repository.isCurrent(actor)
        steer.isVisible = active && DurableChatPolicy.canSteer(pane.status)
        if (!steer.isVisible) steer.isChecked = false
        sendButton.isEnabled = active && !unsettled() && !busy && composer.text?.toString()?.toByteArray(Charsets.UTF_8)?.size in 1..MAX_SEND_BYTES && target != null
        stopButton.isEnabled = active && !unsettled() && !busy && DurableChatPolicy.canStop(pane.status, target)
        if (::abandonButton.isInitialized) {
            abandonButton.isVisible = unsettled()
            abandonButton.isEnabled = active && !busy
        }
        val pending = pendingControl
        retryButton.isVisible = pending != null
        val retryText = if (pending?.stop == true) "" else composer.text?.toString().orEmpty()
        val retrySteer = pending?.stop != true && steer.isChecked
        retryButton.isEnabled = !busy && pending != null && active && sameActor(actor, pending.actor) &&
            DurableChatPolicy.retryStillNamesIntent(
                pending.target, target, pending.text, retryText, pending.steer, retrySteer,
            )
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.collie_foreground))
        setPadding(0, dp(5), 0, dp(5))
        setTextIsSelectable(true)
    }

    private fun sameActor(left: ScopedReadContext, right: ScopedReadContext): Boolean =
        left.key == right.key && left.navigationGeneration == right.navigationGeneration &&
            left.connectionIncarnation == right.connectionIncarnation

    private fun showNotice(value: String) {
        notice = value
        if (::status.isInitialized) status.text = value
    }

    private fun ApiFailure.displayText(): String = when (this) {
        is ApiFailure.Http -> message?.takeIf(String::isNotBlank) ?: "HTTP $status"
        is ApiFailure.Network -> message?.takeIf(String::isNotBlank) ?: "network unavailable"
        is ApiFailure.Protocol -> message
        is ApiFailure.Redirect -> "unexpected redirect ($status)"
        ApiFailure.Timeout -> "request timed out"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PANE_ID = "pane_id"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_TITLE = "title"
        private const val ROOT_ID = 0x43484154
        private const val POLL_MS = 2_500L
        private const val HISTORY_LIMIT = 200
        private const val MAX_SEND_BYTES = 32_768

        fun intent(context: Context, address: PaneAddress, title: String?): Intent =
            Intent(context, DurableChatActivity::class.java).apply {
                putExtra(EXTRA_PANE_ID, address.paneId)
                putExtra(EXTRA_HOST, address.scope.host)
                putExtra(EXTRA_SESSION, address.scope.session)
                putExtra(EXTRA_TITLE, title)
            }
    }
}

private fun acknowledgeChatRetirement(cleanup: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
    else {
        val done = java.util.concurrent.CountDownLatch(1)
        if (Handler(Looper.getMainLooper()).post { try { cleanup() } finally { done.countDown() } }) done.await()
    }
}
