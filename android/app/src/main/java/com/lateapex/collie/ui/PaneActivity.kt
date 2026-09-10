package com.lateapex.collie.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.VelocityTracker
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.URLSpan
import android.text.method.LinkMovementMethod
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.children
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivityPaneBinding
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreatedPane
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.ui.terminal.AnsiParser
import com.lateapex.collie.ui.terminal.AgentSemanticParser
import com.lateapex.collie.ui.terminal.ClaudeChromeFilter
import com.lateapex.collie.ui.terminal.NativeSemanticModel
import com.lateapex.collie.ui.terminal.NoteState
import com.lateapex.collie.ui.terminal.SemanticKind
import com.lateapex.collie.ui.terminal.SemanticIntent
import com.lateapex.collie.ui.terminal.SemanticSurface
import com.lateapex.collie.ui.terminal.SemanticTerminalProjection
import com.lateapex.collie.ui.terminal.StableTerminalDraft
import com.lateapex.collie.ui.terminal.TerminalComposerSemantics
import com.lateapex.collie.ui.terminal.TerminalColourSpace
import com.lateapex.collie.ui.terminal.TerminalDraft
import com.lateapex.collie.ui.terminal.TerminalLinks
import com.lateapex.collie.ui.terminal.TerminalRenderWindow
import com.lateapex.collie.ui.terminal.TerminalTableRuns
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaneActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPaneBinding
    private lateinit var viewModel: PaneViewModel
    private lateinit var address: PaneAddress
    private var isClaudePane = false
    private val keysViewportListener = ViewTreeObserver.OnPreDrawListener { !constrainKeysViewport() }
    private var agentName = ""
    private var semanticSurface: SemanticSurface? = null
    private var noEchoPrompt: String? = null
    private var analyzedPaneRevision: Long? = null
    private var analyzedSemanticSurface: SemanticSurface? = null
    private var analyzedNoEchoPrompt: String? = null
    private var analyzedTerminalDraft: TerminalDraft? = null
    private var dismissedNoEchoSignature: String? = null
    private var stableTerminalDraft: TerminalDraft? = null
    private var takenOverTerminalDraft: TerminalDraft? = null
    private val terminalDraftTracker = StableTerminalDraft(SystemClock::uptimeMillis)
    private var renderedRevision: Long? = null
    private var lastRawText = ""
    private var latestRawText = ""
    private var latestRevision: Long? = null
    private var followingOutput = true
    private var suppressScrollTracking = false
    private var terminalUserScrolling = false
    private val clearTerminalUserScrolling = Runnable { terminalUserScrolling = false }
    private var drawer = ComposerDrawer.NONE
    private var directTyping = false
    private var currentState = PaneUiState()
    private var renderedTerminalText: CharSequence = ""
    private var terminalRenderJob: Job? = null
    private var terminalRenderGeneration = 0L
    private var pendingTerminalRevision: Long? = null
    // Herdr 0.7.x reports revision 0 for every pane (HERDR_API.md), so revision alone can never
    // tell a changed grid from a repeated one. The rendered/pending TEXT is the change key; the
    // revision rides along for the semantic guards that already compare both.
    private var pendingTerminalText: String? = null
    private var pendingKeyboardFocus: View? = null
    private var findMatches = emptyList<OutputFind.Match>()
    private var findCursor = -1
    private var renderedTerminalBlocks = emptyList<RenderedTerminalBlock>()
    private var zenMode = false
    private var paneActionInFlight = false
    private val confirmHandler = Handler(Looper.getMainLooper())
    private val sendConfirmation = TimedConfirmation<String>(now = SystemClock::uptimeMillis)
    private var pendingDestructiveSend: PendingDestructiveSend? = null
    private var paneDraftKey = ""
    private var restoringDraft = false
    private var pendingQuickReply: String? = null
    private var pendingReplyAttempt: PendingReplyAttempt? = null
    private var pendingSent: PendingSent? = null
    private var quickReplyObservedSending = false
    private var quickSuccessVisible = false
    private val paneKeyQueue = PaneKeyQueue()
    private val keyQueueDiscardConfirmation = TimedConfirmation<String>(now = SystemClock::uptimeMillis)
    private val keyButtons = linkedMapOf<View, List<String>>()
    private val keyModifierButtons = linkedMapOf<PaneKeyModifier, MaterialButton>()
    private var keyQueueSend: MaterialButton? = null
    private var keyQueueContainer: View? = null
    private var keyQueueChips: LinearLayout? = null
    private var keyBaseInput: EditText? = null
    private var keyPresetSection: View? = null
    private var keyFunctionSection: View? = null
    private var keyPresetToggle: MaterialButton? = null
    private var keyFunctionToggle: MaterialButton? = null
    private var keysSegment = PaneKeysSegment.KEYS
    private var pendingDangerPreset: String? = null
    private var pendingKeyEcho: PendingKeyEcho? = null
    private var keyEchoReset: Runnable? = null
    private var renderedKeyQueue: Pair<List<String>, List<PaneKeyModifier>>? = null
    private var renderedKeyPresets: List<ComposerKeyPreset>? = null
    private var newOutputAvailable = false
    private var terminalTapBlocked = false
    private var bufferAffordance = PaneBufferAffordance(PaneBufferAction.NONE)
    private var pendingScrollbackTarget: Int? = null
    private var pendingScrollbackAnchor: PaneScrollAnchor? = null
    private val clearDestructiveConfirm = Runnable {
        sendConfirmation.reset()
        pendingDestructiveSend = null
        renderStatus(currentState)
    }
    private val clearKeyQueueDiscardConfirm = Runnable {
        keyQueueDiscardConfirmation.reset()
        renderStatus(currentState)
    }
    private val clearPendingSent = Runnable {
        pendingSent = null
        renderPendingSent()
    }
    private val displayPreferences by lazy { getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE) }
    private val nativePreferences by lazy { NativePreferences(this) }
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var composerMediaActions: ComposerMediaActions
    private lateinit var composerMediaText: ComposerMediaText
    private lateinit var speechRecorder: ComposerSpeechRecorder
    private var speechAvailability: SpeechAvailability = SpeechAvailability.Hidden
    private var composerMediaBusy = false
    private var composerMediaMessage: String? = null
    private val launchDuplicateLock = LaunchDuplicateLock()
    private var tabCreateInFlight = false
    private var renderedTabStrip: PaneTabStrip? = null
    private var keyboardStripFold: Boolean? = null
    private lateinit var panePresenceTracker: PanePresenceTracker
    private var closedPaneRedirected = false
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::handleSelectedImage)
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeechRecording() else showComposerMediaMessage(getString(R.string.pane_microphone_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivityPaneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        window.applySafeContentInsets(binding.root)
        moveFindIntoHeaderSlot()

        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf(String::isNotBlank) ?: run {
            finish()
            return
        }
        address = PaneAddress(
            Scope(
                host = intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank),
                session = intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank),
            ),
            paneId,
        )
        panePresenceTracker = PanePresenceTracker(
            fresh = intent.getBooleanExtra(EXTRA_FRESH_PANE, false),
            seen = savedInstanceState?.getBoolean(STATE_FRESH_PANE_SEEN) == true,
        )
        val origin = (application as? CollieApplication)?.container?.repository?.connection?.value?.origin?.value.orEmpty()
        paneDraftKey = listOf(origin, address.scope.host.orEmpty(), address.scope.session.orEmpty(), paneId)
            .joinToString("|")
        viewModel = ViewModelProvider(
            this,
            PaneViewModel.Factory(application, address, intent.getStringExtra(EXTRA_AGENT)),
        )[PaneViewModel::class.java]
        composerMediaText = AndroidComposerMediaText(this)
        composerMediaActions = ComposerMediaActions(
            config = repository::config,
            upload = { repository.upload(address, it) },
            transcribe = repository::transcribe,
            handsFreeEnabled = { nativePreferences.handsFreeEnabled },
            text = composerMediaText,
        )
        speechRecorder = ComposerSpeechRecorder(this) {
            runOnUiThread { if (speechRecorder.isRecording) stopSpeechRecording() }
        }

        bindIdentity(paneId)
        bindActions()
        bindExpandedKeys(ComposerActions.keyPresets(intent.getStringExtra(EXTRA_AGENT), emptyList()))
        binding.root.applyPreferredTypeface(nativePreferences)
        bindBackNavigation()
        bindFind()
        binding.hideTabsButton.setOnClickListener {
            if (keyboardStripFold != null) keyboardStripFold = true
            else nativePreferences.paneStripsVisible = false
            renderedTabStrip = null
            renderPaneNavigation(currentState)
        }
        binding.showTabsButton.setOnClickListener {
            if (keyboardStripFold != null) keyboardStripFold = false
            else nativePreferences.paneStripsVisible = true
            renderedTabStrip = null
            renderPaneNavigation(currentState)
        }
        binding.newTabButton.setOnClickListener { createCurrentWorkspaceTab() }
        binding.replyInput.onDirectKeys = viewModel::sendDirectKeys
        binding.replyInput.onSendShortcut = ::sendReplyDraft
        restoringDraft = true
        binding.replyInput.setText(nativePreferences.paneDraft(paneDraftKey))
        binding.replyInput.setSelection(binding.replyInput.text?.length ?: 0)
        restoringDraft = false
        binding.replyInput.addTextChangedListener(simpleTextWatcher { draft ->
            if (pendingDestructiveSend?.draft != draft) resetDestructiveConfirm()
            if (!restoringDraft && !directTyping && noEchoPrompt == null) {
                nativePreferences.savePaneDraft(paneDraftKey, draft)
            }
            binding.draftPersistenceWarning.isVisible =
                draft.isNotEmpty() && !nativePreferences.fitsPaneDraftStore(draft)
            renderComposerMediaControls()
        })
        binding.replyInput.setOnFocusChangeListener { _, focused ->
            binding.switcherHandle.isVisible = !focused && !directTyping && currentState.panes.isNotEmpty()
            if (focused) {
                keyboardStripFold = true
                renderedTabStrip = null
                renderPaneNavigation(currentState)
            }
        }
        bindDisplayPreferences()
        binding.root.viewTreeObserver.addOnPreDrawListener(keysViewportListener)
        showDrawer(ComposerDrawer.NONE)
        binding.newOutputButton.setText(R.string.pane_new_output)
        binding.newOutputButton.contentDescription = getString(R.string.pane_new_output_description)
        binding.newOutputButton.setOnClickListener {
            if (zenMode) {
                setZenMode(false)
            } else {
                resumeLatestOutput()
            }
        }
        binding.paneBufferAction.setOnClickListener {
            when (bufferAffordance.action) {
                PaneBufferAction.SHOW_HISTORY -> openHistory()
                PaneBufferAction.LOAD_OLDER -> loadOlderScrollback()
                PaneBufferAction.NONE -> Unit
            }
        }
        binding.terminalScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (suppressScrollTracking || renderedRevision == null) return@setOnScrollChangeListener
            if (terminalIsAtBottom()) {
                followingOutput = true
                if (latestRevision != renderedRevision || latestRawText != lastRawText) resumeLatestOutput()
                else setNewOutputAvailable(false)
            } else if (!binding.findBar.isVisible && terminalUserScrolling) {
                followingOutput = false
            }
        }
        binding.terminalScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    confirmHandler.removeCallbacks(clearTerminalUserScrolling)
                    terminalUserScrolling = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    confirmHandler.removeCallbacks(clearTerminalUserScrolling)
                    confirmHandler.postDelayed(clearTerminalUserScrolling, USER_SCROLL_SETTLE_MS)
                }
            }
            false
        }
        refreshSpeechAvailability()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::panePresenceTracker.isInitialized) {
            outState.putBoolean(STATE_FRESH_PANE_SEEN, panePresenceTracker.wasSeen)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        applyTerminalPreferences()
        viewModel.startPolling()
    }

    override fun onStop() {
        resetDestructiveConfirm(render = false)
        if (!directTyping && noEchoPrompt == null) {
            nativePreferences.savePaneDraft(paneDraftKey, binding.replyInput.text?.toString().orEmpty())
        }
        viewModel.endDirectTyping()
        setDirectTyping(false, announce = false)
        if (zenMode) setZenMode(false)
        if (::speechRecorder.isInitialized && speechRecorder.isRecording) {
            speechRecorder.cancel()
            showComposerMediaMessage(null)
        }
        viewModel.stopPolling()
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.viewTreeObserver.removeOnPreDrawListener(keysViewportListener)
        terminalRenderJob?.cancel()
        confirmHandler.removeCallbacksAndMessages(null)
        if (::speechRecorder.isInitialized) speechRecorder.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_ESCAPE && zenMode) {
            setZenMode(false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun bindBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (zenMode) {
                    setZenMode(false)
                    return
                }
                if (binding.findBar.isVisible) {
                    closeFind()
                    return
                }
                if (drawer != ComposerDrawer.NONE) {
                    showDrawer(ComposerDrawer.NONE)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun bindIdentity(paneId: String) {
        val title = intent.nonBlankExtra(EXTRA_TITLE) ?: paneId
        val cwd = intent.nonBlankExtra(EXTRA_CWD) ?: intent.nonBlankExtra(EXTRA_META) ?: paneId
        val tabLabel = intent.nonBlankExtra(EXTRA_TAB_LABEL) ?: title
        val status = intent.nonBlankExtra(EXTRA_STATUS)?.lowercase(Locale.ROOT) ?: STATUS_UNKNOWN
        agentName = intent.nonBlankExtra(EXTRA_AGENT).orEmpty()
        isClaudePane = agentName.equals("claude", ignoreCase = true)
        binding.paneAgentIcon.setImageResource(agentIcon(agentName))
        binding.paneAgentIcon.contentDescription = getString(R.string.pane_agent_icon_description, agentName)
        binding.paneTitle.text = title
        binding.paneCwd.text = DisplayText.abbreviateHome(cwd)
        binding.tabStrip.contentDescription = getString(R.string.pane_tab_strip_description, tabLabel, status)
        binding.paneStatus.text = if (agentName.equals("shell", ignoreCase = true)) {
            getString(R.string.status_shell).uppercase(Locale.getDefault())
        } else {
            status.uppercase(Locale.ROOT)
        }
        binding.paneStatus.setTextColor(statusColour(status))
        binding.paneStatusDot.isVisible = !agentName.equals("shell", ignoreCase = true)
        binding.paneStatusDot.backgroundTintList = ColorStateList.valueOf(statusColour(status))
    }

    /** Find is a full header-row takeover on the web route, not an extra row above the mirror. */
    private fun moveFindIntoHeaderSlot() {
        val parent = binding.paneHeader.parent as? ViewGroup ?: return
        val headerIndex = parent.indexOfChild(binding.paneHeader)
        (binding.findBar.parent as? ViewGroup)?.removeView(binding.findBar)
        parent.addView(binding.findBar, headerIndex)
    }

    private fun renderIdentity(state: PaneUiState) {
        val pane = currentPaneSummary() ?: return
        val tabPaneCount = state.panes.count {
            it.host == pane.host && it.session == pane.session &&
                it.workspaceId == pane.workspaceId && it.tabId == pane.tabId
        }
        val presentation = PaneHeaderPresenter.present(pane, tabPaneCount)
        agentName = presentation.agent
        isClaudePane = agentName.equals("claude", ignoreCase = true)
        binding.paneAgentIcon.setImageResource(agentIcon(agentName))
        binding.paneAgentIcon.contentDescription = getString(R.string.pane_agent_icon_description, agentName)
        binding.paneTitle.text = presentation.name
        binding.paneDiscriminator.text = presentation.discriminator.orEmpty()
        binding.paneDiscriminator.isVisible = presentation.discriminator != null
        binding.paneCwd.text = presentation.cwd.orEmpty()
        binding.paneCwd.isVisible = presentation.cwd != null
        binding.paneStatusDot.isVisible = !presentation.shell
        binding.paneStatusDot.backgroundTintList = ColorStateList.valueOf(statusColour(presentation.status))
        binding.paneStatus.text = if (presentation.shell) {
            getString(R.string.status_shell).uppercase(Locale.getDefault())
        } else {
            presentation.status.uppercase(Locale.ROOT)
        }
        binding.paneStatus.setTextColor(statusColour(presentation.status))
        binding.tabStrip.contentDescription = getString(
            R.string.pane_tab_strip_description,
            pane.tabLabel.orEmpty(),
            presentation.status,
        )
    }

    private fun bindActions() = with(binding) {
        backButton.setOnClickListener {
            if (drawer != ComposerDrawer.NONE) showDrawer(ComposerDrawer.NONE) else finish()
        }
        refreshButton.setOnClickListener(::showPaneActions)
        sendButton.setOnClickListener {
            if (directTyping) {
                setDirectTyping(false)
                return@setOnClickListener
            }
            sendReplyDraft()
        }
        terminalDraftTakeOver.setOnClickListener { takeOverTerminalDraft() }
        noEchoType.setOnClickListener {
            if (noEchoPrompt == null || semanticSurface?.ownsKeyboard == true) return@setOnClickListener
            replyInput.text?.clear()
            nativePreferences.savePaneDraft(paneDraftKey, "")
            if (!showDrawer(ComposerDrawer.NONE)) return@setOnClickListener
            setDirectTyping(true)
        }
        noEchoDismiss.setOnClickListener {
            dismissedNoEchoSignature = noEchoSignature(currentState.pane, noEchoPrompt)
            noEchoPrompt = null
            render(currentState)
            replyInput.requestFocus()
        }
        attachImageButton.setOnClickListener {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        speechButton.setOnClickListener { toggleSpeechRecording() }
        keysModeButton.setOnClickListener { toggleDrawer(ComposerDrawer.KEYS) }
        quickModeButton.setOnClickListener { toggleDrawer(ComposerDrawer.QUICK) }
        composerSettingsButton.setOnClickListener { toggleDrawer(ComposerDrawer.DISPLAY) }
        agentModeButton.setOnClickListener { showAgentPalette() }
        typeModeButton.setOnClickListener {
            if (directTyping) {
                setDirectTyping(false)
            } else if (replyInput.text?.isNotEmpty() == true) {
                showTransientMessage(getString(R.string.pane_direct_draft_refusal))
            } else {
                if (!showDrawer(ComposerDrawer.NONE)) return@setOnClickListener
                setDirectTyping(true)
            }
        }
        composerDockClose.setOnClickListener { showDrawer(ComposerDrawer.NONE) }
        switcherHandle.setOnClickListener { showPaneSwitcher() }
        bindSwitcherPull()
        bindTerminalTap(terminalText)
        mapOf(
            escapeButton to "Escape",
            tabButton to "Tab",
            upButton to "Up",
            downButton to "Down",
            leftButton to "Left",
            rightButton to "Right",
            ctrlCButton to "ctrl+c",
            enterButton to "Enter",
        ).forEach { (button, key) ->
            keyButtons[button] = listOf(key)
            button.setOnClickListener { pressPaneKeys(listOf(key), button) }
            if (key in REPEATABLE_KEYS) bindKeyRepeat(button, key)
        }
    }

    private fun refreshSpeechAvailability() {
        lifecycleScope.launch {
            speechAvailability = composerMediaActions.speechAvailability()
            if (speechAvailability is SpeechAvailability.Unavailable) {
                showComposerMediaMessage((speechAvailability as SpeechAvailability.Unavailable).reason)
            } else {
                renderComposerMediaControls()
            }
        }
    }

    private fun handleSelectedImage(uri: android.net.Uri) {
        if (composerMediaBusy || directTyping || terminalWriteBlock() != null || semanticSurface?.ownsKeyboard == true ||
            noEchoPrompt != null) return
        lifecycleScope.launch {
            setComposerMediaBusy(true)
            val selection = ComposerMedia.imageUpload(contentResolver, uri, composerMediaText)
            val result = when (selection) {
                is ImageSelectionResult.Ready -> composerMediaActions.uploadImage(selection.upload)
                is ImageSelectionResult.Rejected -> ComposerMediaResult.Failure(selection.reason)
            }
            applyComposerMediaResult(result)
            setComposerMediaBusy(false)
        }
    }

    private fun toggleSpeechRecording() {
        if (speechRecorder.isRecording) {
            stopSpeechRecording()
            return
        }
        val unavailable = speechAvailability as? SpeechAvailability.Unavailable
        if (unavailable != null) {
            showComposerMediaMessage(unavailable.reason)
            return
        }
        if (speechAvailability !is SpeechAvailability.Available || composerMediaBusy || directTyping ||
            terminalWriteBlock() != null || semanticSurface?.ownsKeyboard == true || noEchoPrompt != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSpeechRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechRecording() {
        if (speechAvailability !is SpeechAvailability.Available || composerMediaBusy || speechRecorder.isRecording ||
            terminalWriteBlock() != null
        ) return
        speechRecorder.start().fold(
            onSuccess = {
                showComposerMediaMessage(getString(R.string.pane_recording))
                renderComposerMediaControls()
            },
            onFailure = {
                showComposerMediaMessage(
                    getString(
                        R.string.pane_recording_start_failed,
                        getString(R.string.pane_microphone_unavailable),
                    ),
                )
            },
        )
    }

    private fun stopSpeechRecording() {
        if (!speechRecorder.isRecording || composerMediaBusy) return
        val audio = speechRecorder.stop().getOrElse {
            showComposerMediaMessage(getString(R.string.pane_recording_unreadable))
            renderComposerMediaControls()
            return
        }
        lifecycleScope.launch {
            setComposerMediaBusy(true)
            applyComposerMediaResult(
                composerMediaActions.transcribe(audio),
            )
            setComposerMediaBusy(false)
        }
    }

    private fun applyComposerMediaResult(result: ComposerMediaResult) {
        val guarded = ComposerMedia.enforceHandsFreeGuard(
            result,
            HandsFreeGuard(
                draftEmpty = binding.replyInput.text?.toString().orEmpty().trim().isEmpty(),
                composerReady = currentState.composerReady,
                canWrite = terminalWriteBlock() == null,
                sending = currentState.sending,
                directTyping = directTyping,
                overlayOpen = drawer != ComposerDrawer.NONE || binding.findBar.isVisible || paneActionInFlight ||
                    semanticSurface?.ownsKeyboard == true || noEchoPrompt != null,
                activityHasFocus = hasWindowFocus(),
            ),
            composerMediaText,
        )
        when (guarded) {
            is ComposerMediaResult.Draft -> {
                val current = binding.replyInput.text?.toString().orEmpty()
                val insertion = if (guarded.atCaret) {
                    ComposerMedia.insertAtCaret(
                        current,
                        binding.replyInput.selectionStart,
                        binding.replyInput.selectionEnd,
                        guarded.insertion,
                    )
                } else {
                    val draft = ComposerMedia.appendToDraft(current, guarded.insertion)
                    DraftInsertion(draft, draft.length)
                }
                binding.replyInput.setText(insertion.text)
                binding.replyInput.setSelection(insertion.caret)
                showComposerMediaMessage(guarded.message)
            }
            is ComposerMediaResult.AutoSend -> {
                binding.replyInput.setText(guarded.transcript)
                binding.replyInput.setSelection(guarded.transcript.length)
                showComposerMediaMessage(getString(R.string.pane_sending_transcript))
                sendReplyWithConfirmation(guarded.transcript)
            }
            is ComposerMediaResult.Failure -> showComposerMediaMessage(guarded.message)
        }
    }

    private fun setComposerMediaBusy(busy: Boolean) {
        composerMediaBusy = busy
        renderComposerMediaControls()
    }

    private fun showComposerMediaMessage(message: String?) {
        composerMediaMessage = message
        binding.composerMediaStatus.text = message.orEmpty()
        binding.composerMediaStatus.isVisible = !message.isNullOrBlank()
        renderComposerMediaControls()
    }

    private fun renderComposerMediaControls() = with(binding) {
        if (!::speechRecorder.isInitialized) return@with
        val hasPane = currentState.pane != null
        val terminalWritable = terminalWriteBlock() == null
        val dialogPresent = semanticSurface?.ownsKeyboard == true
        val noEcho = noEchoPrompt != null
        val micPrimary = speechAvailability !is SpeechAvailability.Hidden && !directTyping &&
            replyInput.text?.toString().orEmpty().trim().isEmpty()
        attachImageButton.isEnabled = terminalWritable && hasPane && !currentState.sending && !composerMediaBusy &&
            !directTyping && !dialogPresent && !noEcho
        speechButton.isVisible = micPrimary
        sendButton.isVisible = !micPrimary
        speechButton.isEnabled = speechRecorder.isRecording ||
            (speechAvailability is SpeechAvailability.Available && terminalWritable && hasPane &&
                !currentState.sending && !composerMediaBusy && !directTyping && !dialogPresent && !noEcho)
        speechButton.contentDescription = getString(
            if (speechRecorder.isRecording) R.string.pane_stop_recording else R.string.pane_record_speech,
        )
        speechButton.setBackgroundResource(
            if (speechRecorder.isRecording) R.drawable.bg_collie_control_on else R.drawable.bg_pane_send,
        )
        speechButton.imageTintList = ColorStateList.valueOf(
            getColor(if (speechRecorder.isRecording) R.color.collie_on_control_on else R.color.collie_on_primary),
        )
        val mediaStatus = if (directTyping) getString(R.string.pane_direct_armed) else composerMediaMessage
        composerMediaStatus.text = mediaStatus.orEmpty()
        composerMediaStatus.isVisible = !mediaStatus.isNullOrBlank()
    }

    private fun bindDisplayPreferences() = with(binding) {
        wrapLinesSwitch.isChecked = displayPreferences.getBoolean(PREF_WRAP, true)
        tapToTypeSwitch.isChecked = displayPreferences.getBoolean(PREF_TAP_TO_TYPE, true)
        rawTerminalSwitch.isChecked = displayPreferences.getBoolean(PREF_RAW, false)
        applyTerminalPreferences()
        wrapLinesSwitch.setOnCheckedChangeListener { _, checked ->
            displayPreferences.edit().putBoolean(PREF_WRAP, checked).apply()
            applyTerminalPreferences()
        }
        tapToTypeSwitch.setOnCheckedChangeListener { _, checked ->
            displayPreferences.edit().putBoolean(PREF_TAP_TO_TYPE, checked).apply()
        }
        rawTerminalSwitch.setOnCheckedChangeListener { _, checked ->
            displayPreferences.edit().putBoolean(PREF_RAW, checked).apply()
            renderSemanticSurface(semanticSurface, currentState)
            renderTerminal(lastRawText.ifEmpty { latestRawText })
        }
        fontSmallerButton.setOnClickListener { stepFontSize(-1) }
        fontLargerButton.setOnClickListener { stepFontSize(1) }
    }

    private fun bindExpandedKeys(presets: List<ComposerKeyPreset>) {
        val container = binding.keyPadContainer
        val primaryButtons = with(binding) {
            listOf(escapeButton, ctrlCButton, upButton, enterButton, tabButton, leftButton, downButton, rightButton)
        }
        keyButtons.keys.filterNot { it in primaryButtons }.forEach(keyButtons::remove)
        keyModifierButtons.clear()
        container.removeAllViews()
        renderedKeyQueue = null
        keyQueueSend = null
        keyQueueContainer = null
        keyQueueChips = null
        keyBaseInput = null
        keyPresetSection = null
        keyFunctionSection = null
        keyPresetToggle = null
        keyFunctionToggle = null
        renderedKeyPresets = presets

        fun rowParams(button: View) = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }.also { button.layoutParams = it }

        fun gridParams(button: View, row: Int, column: Int, height: Int = 44) =
            GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(column, 1f)).apply {
                width = 0
                this.height = dp(height)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }.also { button.layoutParams = it }

        fun keyButton(label: String, keys: List<String>): MaterialButton = outlinedButton(label).apply {
            minWidth = 0
            contentDescription = label
            keyButtons[this] = keys
            setOnClickListener { pressPaneKeys(keys, this) }
        }

        val segment = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val keysTab = outlinedButton(getString(R.string.pane_keys_segment_keys)).apply {
            id = R.id.pane_keys_segment_keys
            contentDescription = getString(R.string.pane_keys_segment_keys)
            setOnClickListener {
                resetKeyQueueDiscardConfirm()
                keysSegment = PaneKeysSegment.KEYS
                renderKeysSegment()
            }
        }
        val digitsTab = outlinedButton(getString(R.string.pane_keys_segment_digits)).apply {
            id = R.id.pane_keys_segment_digits
            contentDescription = getString(R.string.pane_keys_segment_digits)
            setOnClickListener {
                resetKeyQueueDiscardConfirm()
                keysSegment = PaneKeysSegment.DIGITS
                renderKeysSegment()
            }
        }
        segment.addView(keysTab, rowParams(keysTab))
        segment.addView(digitsTab, rowParams(digitsTab))
        container.addView(segment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val queuePanel = LinearLayout(this).apply {
            id = R.id.pane_keys_queue
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        keyQueueContainer = queuePanel
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        keyQueueChips = LinearLayout(this).apply {
            id = R.id.pane_keys_queue_chips
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        chipScroll.addView(
            keyQueueChips,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
        )
        queuePanel.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val queueActions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        keyQueueSend = outlinedButton(getString(R.string.pane_key_send_staged)).apply {
            id = R.id.pane_keys_queue_send
            isVisible = false
            contentDescription = getString(R.string.pane_key_send_staged_description)
            setOnClickListener {
                resetKeyQueueDiscardConfirm()
                val keys = paneKeyQueue.take()
                renderKeyQueue()
                if (keys.isNotEmpty()) viewModel.sendKeySequence(keys)
            }
            // Sized by the label, never by a fixed dp: at the S25 Ultra's density "Send keys" wrapped
            // to two rows and "Clear" clipped to "Cl" inside fixed 92/68 dp boxes.
            isSingleLine = true
            minimumWidth = dp(92)
            queueActions.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(4) },
            )
        }
        outlinedButton(getString(R.string.pane_key_clear)).apply {
            id = R.id.pane_keys_queue_clear
            contentDescription = getString(R.string.pane_key_clear_description)
            setOnClickListener {
                resetKeyQueueDiscardConfirm()
                paneKeyQueue.clear()
                renderKeyQueue()
            }
            tag = KEY_QUEUE_CLEAR_TAG
            isSingleLine = true
            minimumWidth = dp(68)
            queueActions.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        queuePanel.addView(queueActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        container.addView(
            queuePanel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val keysContent = LinearLayout(this).apply {
            id = R.id.pane_keys_primary
            orientation = LinearLayout.VERTICAL
        }
        val primaryGrid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 2
        }
        primaryButtons.forEachIndexed { index, button ->
            (button.parent as? ViewGroup)?.removeView(button)
            button.minWidth = 0
            primaryGrid.addView(button, gridParams(button, index / 4, index % 4))
        }
        keysContent.addView(primaryGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))

        val space = keyButton(getString(R.string.pane_key_space), listOf("Space"))
        keysContent.addView(space, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(2), 0, dp(2), dp(2))
        })

        val modifiers = LinearLayout(this).apply {
            id = R.id.pane_keys_modifiers
            orientation = LinearLayout.HORIZONTAL
        }
        PaneKeyModifier.entries.forEach { modifier ->
            val label = getString(modifier.labelRes)
            val button = outlinedButton(label).apply {
                minWidth = 0
                contentDescription = getString(R.string.pane_key_modifier_description, label)
                tag = modifier
                setOnClickListener {
                    resetKeyQueueDiscardConfirm()
                    paneKeyQueue.cycle(modifier)
                    renderKeyQueue()
                    if (paneKeyQueue.composing) binding.keyRow.smoothScrollTo(0, 0)
                }
            }
            keyModifierButtons[modifier] = button
            modifiers.addView(button, rowParams(button))
        }
        keysContent.addView(modifiers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        fun sectionToggle(label: String, id: Int, section: View): MaterialButton =
            outlinedButton(label).apply {
                this.id = id
                text = "$label  ⌄"
                isAllCaps = false
                minHeight = dp(44)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                contentDescription = getString(R.string.pane_keys_expand_section, label)
                setOnClickListener {
                    val expanded = !section.isVisible
                    section.isVisible = expanded
                    text = "$label  ${if (expanded) "⌃" else "⌄"}"
                    contentDescription = getString(
                        if (expanded) R.string.pane_keys_collapse_section else R.string.pane_keys_expand_section,
                        label,
                    )
                }
            }

        val presetGrid = GridLayout(this).apply {
            id = R.id.pane_keys_presets
            columnCount = 3
            rowCount = (presets.size + 2) / 3
            isVisible = false
        }
        presets.forEachIndexed { index, preset ->
            val button = keyButton(preset.label, preset.keys).apply {
                contentDescription = getString(R.string.pane_key_preset_description, preset.label)
                setOnClickListener { pressPreset(this, preset) }
            }
            presetGrid.addView(button, gridParams(button, index / 3, index % 3))
        }
        keyPresetSection = presetGrid
        keyPresetToggle = sectionToggle(
            getString(R.string.pane_keys_presets),
            R.id.pane_keys_presets_toggle,
            presetGrid,
        )
        keysContent.addView(keyPresetToggle)
        keysContent.addView(
            presetGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48) * presetGrid.rowCount),
        )

        val functionGrid = GridLayout(this).apply {
            id = R.id.pane_keys_functions
            columnCount = 4
            rowCount = 3
            isVisible = false
        }
        (1..12).forEach { number ->
            val button = keyButton(getString(R.string.pane_key_function, number), listOf("F$number"))
            functionGrid.addView(button, gridParams(button, (number - 1) / 4, (number - 1) % 4))
        }
        keyFunctionSection = functionGrid
        keyFunctionToggle = sectionToggle(
            getString(R.string.pane_keys_function_keys),
            R.id.pane_keys_functions_toggle,
            functionGrid,
        )
        keysContent.addView(keyFunctionToggle)
        keysContent.addView(
            functionGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48) * functionGrid.rowCount),
        )
        container.addView(
            keysContent,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val digits = GridLayout(this).apply {
            id = R.id.pane_keys_digits
            columnCount = 3
            rowCount = 3
        }
        (1..9).forEach { digit ->
            val button = keyButton(getString(R.string.pane_key_digit, digit), listOf(digit.toString())).apply {
                textSize = 18f
            }
            digits.addView(button, gridParams(button, (digit - 1) / 3, (digit - 1) % 3, height = 48))
        }
        container.addView(digits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156)))
        renderKeysSegment()
        renderKeyQueue()
    }

    private fun constrainKeysViewport(): Boolean {
        if (drawer != ComposerDrawer.KEYS) return false
        val column = binding.composerChrome.parent as? ViewGroup ?: return false
        if (column.height == 0) return false
        fun occupiedHeight(view: View): Int {
            if (!view.isVisible) return 0
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            return view.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        }
        val aboveComposer = column.children.filter {
            it !== binding.composerChrome && ((it.layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0f) == 0f
        }.sumOf(::occupiedHeight)
        val outsideDock = binding.composerChrome.children.filter { it !== binding.composerDock }.sumOf(::occupiedHeight)
        val dockHeader = occupiedHeight(binding.composerDock.getChildAt(0))
        val available = (column.height - column.paddingTop - column.paddingBottom -
            aboveComposer - outsideDock - dockHeader).coerceAtLeast(dp(48))
        val content = binding.keyPadContainer.height + binding.keyRow.paddingTop + binding.keyRow.paddingBottom
        if (content == 0) return false
        val height = minOf(content, available)
        // wrap_content measured the entire expanded pad, leaving no scroll range even when
        // its lower rows were beyond the window. Constrain the viewport, not its content.
        if (binding.keyRow.layoutParams.height != height) {
            binding.keyRow.layoutParams = binding.keyRow.layoutParams.apply { this.height = height }
            // Do not present intermediate geometry while the next layout is pending.
            return true
        }
        return false
    }

    private fun renderKeysSegment() = with(binding) {
        val keysSelected = keysSegment == PaneKeysSegment.KEYS
        root.findViewById<View>(R.id.pane_keys_primary)?.isVisible = keysSelected
        root.findViewById<View>(R.id.pane_keys_digits)?.isVisible = !keysSelected
        root.findViewById<View>(R.id.pane_keys_segment_keys)?.let { setControlState(it, keysSelected) }
        root.findViewById<View>(R.id.pane_keys_segment_digits)?.let { setControlState(it, !keysSelected) }
    }

    private fun resetKeysTrayPresentation() {
        keysSegment = PaneKeysSegment.KEYS
        keyPresetSection?.isVisible = false
        keyFunctionSection?.isVisible = false
        keyPresetToggle?.apply {
            text = "${getString(R.string.pane_keys_presets)}  ⌄"
            contentDescription = getString(R.string.pane_keys_expand_section, getString(R.string.pane_keys_presets))
        }
        keyFunctionToggle?.apply {
            text = "${getString(R.string.pane_keys_function_keys)}  ⌄"
            contentDescription = getString(
                R.string.pane_keys_expand_section,
                getString(R.string.pane_keys_function_keys),
            )
        }
        renderKeysSegment()
    }

    private fun pressPaneKeys(keys: List<String>, echoButton: MaterialButton? = null) {
        resetKeyQueueDiscardConfirm()
        val immediate = paneKeyQueue.press(keys)
        renderKeyQueue()
        if (immediate == null) binding.keyRow.smoothScrollTo(0, 0)
        if (immediate != null) {
            echoButton?.let { beginKeyEcho(it, immediate) }
            viewModel.sendKeySequence(immediate)
        }
    }

    private fun pressPreset(button: MaterialButton, preset: ComposerKeyPreset) {
        if (paneKeyQueue.composing) {
            pendingDangerPreset = null
            pressPaneKeys(preset.keys)
            return
        }
        if (preset.dangerous && pendingDangerPreset != preset.label) {
            pendingDangerPreset = preset.label
            button.setText(R.string.pane_key_confirm)
            confirmHandler.postDelayed({
                if (pendingDangerPreset == preset.label) {
                    pendingDangerPreset = null
                    button.text = preset.label
                }
            }, CONFIRM_TIMEOUT_MS)
            return
        }
        pendingDangerPreset = null
        button.text = preset.label
        pressPaneKeys(preset.keys, button)
    }

    private fun beginKeyEcho(button: MaterialButton, keys: List<String>) {
        clearKeyEcho()
        val echo = PendingKeyEcho(
            button = button,
            label = button.text,
            contentDescription = button.contentDescription,
            keys = keys,
        )
        pendingKeyEcho = echo
        button.text = getString(R.string.pane_key_pending, echo.label)
        keyEchoReset = Runnable {
            if (pendingKeyEcho === echo) {
                pendingKeyEcho = null
                restoreKeyEcho(echo)
            }
        }.also { confirmHandler.postDelayed(it, KEY_ECHO_TIMEOUT_MS) }
    }

    private fun renderKeyEcho(state: PaneUiState) {
        val echo = pendingKeyEcho ?: return
        val accepted = state.status == getString(R.string.pane_keys_sent, echo.keys.joinToString(" "))
        if (!accepted && state.mutationError == null && state.error == null) return
        if (accepted && echo.accepted) return
        keyEchoReset?.let(confirmHandler::removeCallbacks)
        keyEchoReset = null
        if (!accepted) {
            pendingKeyEcho = null
            restoreKeyEcho(echo)
            return
        }
        echo.accepted = true
        echo.button.setText(R.string.check_mark)
        echo.button.contentDescription = getString(R.string.pane_key_accepted, echo.label)
        keyEchoReset = Runnable {
            if (pendingKeyEcho === echo) {
                pendingKeyEcho = null
                restoreKeyEcho(echo)
            }
            keyEchoReset = null
        }.also { confirmHandler.postDelayed(it, KEY_ECHO_DONE_MS) }
    }

    private fun clearKeyEcho() {
        keyEchoReset?.let(confirmHandler::removeCallbacks)
        keyEchoReset = null
        pendingKeyEcho?.let(::restoreKeyEcho)
        pendingKeyEcho = null
    }

    private fun restoreKeyEcho(echo: PendingKeyEcho) {
        echo.button.text = echo.label
        echo.button.contentDescription = echo.contentDescription
    }

    private fun renderKeyQueue(restoreBaseFocus: Boolean = false) {
        val queuePanel = keyQueueContainer ?: return
        val chips = keyQueueChips ?: return
        val hadBaseFocus = restoreBaseFocus || keyBaseInput?.hasFocus() == true
        val staged = paneKeyQueue.staged
        val activeModifiers = paneKeyQueue.activeModifiers
        val composing = paneKeyQueue.composing
        queuePanel.isVisible = composing
        val contents = staged to activeModifiers
        // Polling changes availability, not the queue. Detaching the focused EditText here
        // invalidates the IME connection even if its replacement immediately requests focus.
        if (renderedKeyQueue != contents) {
            renderedKeyQueue = contents
            chips.removeAllViews()
            keyBaseInput = null

            staged.forEachIndexed { index, key ->
                val label = keyChipLabel(key)
                val chip = outlinedButton("$label  ×").apply {
                    minWidth = dp(44)
                    contentDescription = getString(R.string.pane_key_remove_staged, label)
                    if (isDangerKey(key)) setTextColor(getColor(R.color.collie_destructive))
                    setOnClickListener {
                        resetKeyQueueDiscardConfirm()
                        paneKeyQueue.removeAt(index)
                        renderKeyQueue()
                    }
                }
                chips.addView(
                    chip,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(4) },
                )
            }

            if (activeModifiers.isNotEmpty()) {
                val modifiers = activeModifiers.joinToString(" ") { modifierChipLabel(it) }
                chips.addView(TextView(this).apply {
                    text = "$modifiers + …"
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setTextColor(getColor(R.color.collie_muted))
                    setPadding(dp(10), 0, dp(10), 0)
                    setBackgroundResource(R.drawable.bg_collie_control_off)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(4) })
                var handlingInput = false
                val input = EditText(this).apply {
                    id = R.id.pane_keys_base_input
                    hint = getString(R.string.pane_key_base_placeholder)
                    contentDescription = getString(R.string.pane_key_base_description)
                    isSingleLine = true
                    minHeight = dp(44)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setTextColor(getColor(R.color.collie_foreground))
                    setHintTextColor(getColor(R.color.collie_muted))
                    setPadding(dp(8), 0, dp(8), 0)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(value: android.text.Editable?) {
                            if (handlingInput || value.isNullOrEmpty()) return
                            val raw = value.toString()
                            handlingInput = true
                            value.clear()
                            handlingInput = false
                            if (paneKeyQueue.pushBase(raw)) renderKeyQueue(restoreBaseFocus = true)
                        }
                    })
                }
                keyBaseInput = input
                chips.addView(input, LinearLayout.LayoutParams(dp(64), dp(44)))
            }
        }

        val terminalWritable = terminalWriteBlock() == null
        val danger = staged.any(::isDangerKey)
        keyQueueSend?.apply {
            isVisible = staged.isNotEmpty()
            isEnabled = staged.isNotEmpty() && terminalWritable && !currentState.sending
            strokeColor = ColorStateList.valueOf(getColor(if (danger) R.color.collie_destructive else R.color.collie_border))
            setTextColor(getColor(if (danger) R.color.collie_destructive else R.color.collie_foreground))
        }
        keyBaseInput?.isEnabled = terminalWritable && currentState.pane != null && !currentState.sending
        keyModifierButtons.forEach { (modifier, child) ->
            val mode = paneKeyQueue.mode(modifier)
            child.text = when (mode) {
                PaneModifierMode.OFF -> getString(modifier.labelRes)
                PaneModifierMode.ONCE -> getString(R.string.pane_key_modifier_once, getString(modifier.labelRes))
                PaneModifierMode.LOCKED -> getString(R.string.pane_key_modifier_lock, getString(modifier.labelRes))
            }
            setControlState(child, mode != PaneModifierMode.OFF)
            child.isEnabled = terminalWritable && currentState.pane != null && !currentState.sending
        }
        if (hadBaseFocus) keyBaseInput?.requestFocus()
    }

    private fun modifierChipLabel(modifier: PaneKeyModifier): String = when (modifier) {
        PaneKeyModifier.CTRL -> getString(R.string.pane_key_ctrl)
        PaneKeyModifier.ALT -> getString(R.string.pane_key_alt)
        PaneKeyModifier.SHIFT -> "⇧"
    }

    private fun keyChipLabel(key: String): String {
        val parts = key.split('+')
        val labels = mutableListOf<String>()
        var index = 0
        while (index < parts.size) {
            val modifier = when (parts[index].lowercase(Locale.ROOT)) {
                "ctrl" -> getString(R.string.pane_key_ctrl)
                "alt" -> getString(R.string.pane_key_alt)
                "shift" -> "⇧"
                "cmd" -> "Cmd"
                "super" -> "Super"
                else -> null
            } ?: break
            labels += modifier
            index += 1
        }
        val base = parts.drop(index).joinToString("+")
        if (base.isNotEmpty() || labels.isEmpty()) {
            labels += when {
                base.equals("Escape", ignoreCase = true) -> getString(R.string.pane_key_escape)
                base.equals("Enter", ignoreCase = true) -> "⏎"
                base.length == 1 -> base.uppercase(Locale.ROOT)
                else -> base
            }
        }
        return labels.joinToString(" ")
    }

    private fun isDangerKey(key: String): Boolean = key.lowercase(Locale.ROOT) in DANGER_KEYS

    private fun ViewGroup.children(): Sequence<View> = sequence {
        repeat(childCount) { yield(getChildAt(it)) }
    }

    private fun bindKeyRepeat(button: MaterialButton, key: String) {
        var engaged = false
        var repeats = 0
        var tick: Runnable? = null
        val engage = Runnable {
            engaged = true
            repeats = 1
            button.text = getString(R.string.pane_key_repeat_count, repeats)
            tick = object : Runnable {
                override fun run() {
                    if (!engaged || repeats >= MAX_REPEAT_KEYS) return
                    repeats += 1
                    button.text = getString(R.string.pane_key_repeat_count, repeats)
                    confirmHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                }
            }
            tick?.let { confirmHandler.postDelayed(it, REPEAT_INTERVAL_MS) }
        }
        button.setOnTouchListener { view, event ->
            if (paneKeyQueue.composing) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    engaged = false
                    repeats = 0
                    confirmHandler.postDelayed(engage, REPEAT_HOLD_MS)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    confirmHandler.removeCallbacks(engage)
                    tick?.let(confirmHandler::removeCallbacks)
                    view.isPressed = false
                    val commitRepeat = shouldCommitRepeatKeys(engaged, event.actionMasked)
                    if (engaged) {
                        val count = repeats
                        engaged = false
                        button.text = keyDisplayLabel(key)
                        if (count > 0 && commitRepeat) {
                            viewModel.sendKeySequence(List(count) { key })
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun render(state: PaneUiState) = with(binding) {
        currentState = state
        if (recoverFromClosedPane(state)) return@with
        pendingSent?.let { sent ->
            state.pane?.let { pane ->
                if (pane.revision != sent.revision || pane.text != sent.paneText) clearPendingSent.run()
            }
        }
        if (state.mutationError != null || state.error != null) pendingReplyAttempt = null
        renderIdentity(state)
        paneProgress.isVisible = state.loading && state.pane == null
        val hasPane = state.pane != null
        val writeBlock = PaneWriteGate.block(address, state)
        val terminalWritable = writeBlock == null
        val analysisPane = state.pane
        if (analysisPane == null) {
            analyzedPaneRevision = null
            analyzedSemanticSurface = null
            analyzedNoEchoPrompt = null
            analyzedTerminalDraft = null
        } else if (analysisPane.revision != analyzedPaneRevision) {
            analyzedPaneRevision = analysisPane.revision
            analyzedSemanticSurface = AgentSemanticParser.detect(agentName, analysisPane.text, analysisPane.revision)
            analyzedNoEchoPrompt = TerminalComposerSemantics.noEchoPrompt(analysisPane.text)
            analyzedTerminalDraft = if (analyzedSemanticSurface?.ownsKeyboard != true && analyzedNoEchoPrompt == null) {
                TerminalComposerSemantics.terminalDraft(agentName, analysisPane.text)
            } else {
                null
            }
        }
        val detectedSurface = analyzedSemanticSurface
        val detectedNoEchoPrompt = analyzedNoEchoPrompt
        val detectedNoEchoSignature = noEchoSignature(state.pane, detectedNoEchoPrompt)
        if (detectedNoEchoPrompt == null) dismissedNoEchoSignature = null
        val effectiveNoEchoPrompt = detectedNoEchoPrompt.takeUnless {
            detectedNoEchoSignature != null && detectedNoEchoSignature == dismissedNoEchoSignature
        }
        if (effectiveNoEchoPrompt != null && noEchoPrompt == null) {
            // The local field remains available for an explicit Type handoff, but a value refused
            // at a no-echo prompt must never survive activity/process recreation on disk.
            nativePreferences.savePaneDraft(paneDraftKey, "")
        }
        noEchoPrompt = effectiveNoEchoPrompt
        val observedTerminalDraft = analyzedTerminalDraft.takeIf {
            detectedSurface?.ownsKeyboard != true && noEchoPrompt == null
        }
        takenOverTerminalDraft?.let { taken ->
            if (observedTerminalDraft == null ||
                StableTerminalDraft.normalize(observedTerminalDraft.text) != StableTerminalDraft.normalize(taken.text)
            ) {
                takenOverTerminalDraft = null
            }
        }
        stableTerminalDraft = terminalDraftTracker.observe(
            observedTerminalDraft,
        )
        if (detectedSurface != semanticSurface) {
            semanticSurface = detectedSurface
            renderSemanticSurface(detectedSurface, state)
        } else {
            updateSemanticActionState(state)
        }
        val dialogPresent = detectedSurface?.ownsKeyboard == true
        val ordinaryInputBlocked = dialogPresent || noEchoPrompt != null
        if (dialogPresent && directTyping) setDirectTyping(false, announce = false)
        if (dialogPresent && drawer == ComposerDrawer.QUICK) showDrawer(ComposerDrawer.NONE)
        sendButton.isEnabled = if (directTyping) {
            true
        } else {
            terminalWritable && (state.composerReady || takenOverTerminalDraft != null) &&
                !state.sending && hasPane && !ordinaryInputBlocked
        }
        replyInput.isEnabled = terminalWritable && (directTyping || !state.sending && !ordinaryInputBlocked)
        composerChrome.isVisible = !zenMode && (hasPane || !state.loading)
        keysModeButton.isEnabled = terminalWritable && !state.sending && hasPane
        typeModeButton.isEnabled = terminalWritable && hasPane && (!state.sending || directTyping) && !dialogPresent
        quickModeButton.isEnabled = terminalWritable && !state.sending && hasPane && !ordinaryInputBlocked
        agentModeButton.isVisible = state.commands.isNotEmpty()
        agentModeButton.isEnabled = terminalWritable && !state.sending && hasPane && !ordinaryInputBlocked
        composerSettingsButton.isEnabled = true
        renderComposerMediaControls()
        renderComposerNotices(state)
        renderPaneNavigation(state)
        if (renderedKeyPresets != state.keyPresets) bindExpandedKeys(state.keyPresets)
        switcherHandle.isVisible = !replyInput.hasFocus() && !directTyping && state.panes.isNotEmpty()
        val unsupportedKeys = state.unsupportedKeys.map { it.lowercase(Locale.ROOT) }.toSet()
        keyButtons.forEach { (button, keys) ->
            button.isEnabled = terminalWritable && !state.sending && hasPane &&
                keys.all { key -> key.substringAfterLast('+').lowercase(Locale.ROOT) !in unsupportedKeys }
        }
        renderKeyQueue()
        renderKeyEcho(state)
        renderStatus(state)
        updateQuickReplyState(state)
        if (directTyping && (state.mutationError != null || !terminalWritable || !hasPane)) {
            setDirectTyping(false, announce = false)
        }
        if (state.clearReplyDraft) {
            pendingReplyAttempt?.let { attempt ->
                pendingSent = PendingSent(
                    text = attempt.text,
                    revision = state.pane?.revision ?: attempt.revision,
                    paneText = state.pane?.text ?: attempt.paneText,
                )
                confirmHandler.removeCallbacks(clearPendingSent)
                confirmHandler.postDelayed(clearPendingSent, PENDING_SENT_TIMEOUT_MS)
            }
            pendingReplyAttempt = null
            takenOverTerminalDraft = null
            replyInput.text?.clear()
            nativePreferences.savePaneDraft(paneDraftKey, "")
            viewModel.onReplyDraftCleared()
        }
        renderPendingSent()
        renderBufferAffordance(state)
        if (drawer == ComposerDrawer.QUICK) populateQuickActions(state.quickReplies)
        state.pane?.let { pane ->
            latestRawText = pane.text
            latestRevision = pane.revision
            val renderedOrPending =
                (renderedRevision == pane.revision && lastRawText == pane.text) ||
                    (pendingTerminalRevision == pane.revision && pendingTerminalText == pane.text)
            val changed = !renderedOrPending
            val grownTarget = pendingScrollbackTarget
            if (grownTarget != null && state.loadedLines >= grownTarget) {
                pendingScrollbackTarget = null
                showTerminalRevision(
                    pane.text,
                    pane.revision,
                    scrollToBottom = false,
                    afterRender = {
                        restoreScrollbackAnchor()
                        setNewOutputAvailable(false)
                    },
                )
            } else if (grownTarget != null && !state.loadingOlder && state.requestedLines < grownTarget) {
                pendingScrollbackTarget = null
                pendingScrollbackAnchor = null
            } else if (changed && renderedRevision == null) {
                showTerminalRevision(pane.text, pane.revision, scrollToBottom = true)
            } else if (changed && !findBar.isVisible && followingOutput) {
                showTerminalRevision(pane.text, pane.revision, scrollToBottom = true)
            } else if (changed) {
                // Freeze the actual visible snapshot. Restoring only scrollY lets inserted/reflowed
                // rows move underneath the reader and is not the web route's freeze contract.
                setNewOutputAvailable(true)
            }
        }
    }

    private fun renderBufferAffordance(state: PaneUiState) = with(binding) {
        // The web affordance is part of the rendered buffer, so it stays absent until that buffer
        // exists; the overflow History action below is independently available from topology data.
        val paneSummary = currentPaneSummary(state).takeIf { !state.pane?.text.isNullOrEmpty() }
        // Keep the action in-place as a disabled Loading row until the grown response lands. The
        // requested target advances immediately, but the screen still represents the loaded window.
        val representedLines = state.loadedLines.takeIf { state.loadingOlder && it > 0 } ?: state.requestedLines
        bufferAffordance = PaneBufferPolicy.resolve(paneSummary, state.mux, representedLines)
        paneBufferAction.isVisible = bufferAffordance.action != PaneBufferAction.NONE
        paneBufferAction.isEnabled = bufferAffordance.action != PaneBufferAction.LOAD_OLDER || !state.loadingOlder
        when (bufferAffordance.action) {
            PaneBufferAction.SHOW_HISTORY -> {
                paneBufferAction.setText(R.string.pane_scrollback_show_history)
                paneBufferAction.setIconResource(R.drawable.ic_history_transcript)
            }
            PaneBufferAction.LOAD_OLDER -> {
                paneBufferAction.setText(
                    if (state.loadingOlder) R.string.pane_scrollback_loading else R.string.pane_scrollback_load_older,
                )
                paneBufferAction.setIconResource(R.drawable.ic_history_load_older)
            }
            PaneBufferAction.NONE -> paneBufferAction.icon = null
        }
        val explanation = bufferAffordance.muxExplanation ?: bufferAffordance.missingSessionAgent?.let {
            getString(R.string.pane_scrollback_no_session, it)
        }
        paneBufferExplanation.text = explanation.orEmpty()
        paneBufferExplanation.isVisible = explanation != null
    }

    private fun loadOlderScrollback() {
        val child = binding.terminalScroll.getChildAt(0)
        val anchor = child?.let { PaneScrollAnchor(it.height, binding.terminalScroll.scrollY) }
        val target = viewModel.loadOlderScrollback() ?: return
        pendingScrollbackAnchor = anchor
        pendingScrollbackTarget = target
        followingOutput = false
        binding.paneBufferAction.isEnabled = false
        binding.paneBufferAction.setText(R.string.pane_scrollback_loading)
    }

    private fun restoreScrollbackAnchor() {
        val anchor = pendingScrollbackAnchor.also { pendingScrollbackAnchor = null } ?: return
        suppressScrollTracking = true
        binding.terminalScroll.post {
            val height = binding.terminalScroll.getChildAt(0)?.height ?: anchor.height
            binding.terminalScroll.scrollTo(
                0,
                PaneScrollbackWindow.restoreTop(anchor.height, anchor.top, height),
            )
            followingOutput = false
            binding.terminalScroll.post { suppressScrollTracking = false }
        }
    }

    private fun showTerminalRevision(
        text: String,
        revision: Long,
        scrollToBottom: Boolean,
        afterRender: (() -> Unit)? = null,
    ) {
        if (pendingTerminalRevision == revision && pendingTerminalText == text) return
        pendingTerminalRevision = revision
        pendingTerminalText = text
        val generation = ++terminalRenderGeneration
        val lightTheme = isLightTheme()
        val rawMode = displayPreferences.getBoolean(PREF_RAW, false)
        val claudePane = isClaudePane
        val displayAgent = agentName
        val omittedMarker = getString(R.string.pane_terminal_output_omitted)
        if (Build.FINGERPRINT == "robolectric") {
            commitTerminalRender(
                text,
                revision,
                prepareTerminalText(text, revision, lightTheme, rawMode, claudePane, displayAgent, omittedMarker),
                scrollToBottom,
                afterRender,
            )
            return
        }
        val textMetrics = TextViewCompat.getTextMetricsParams(binding.terminalText)
        terminalRenderJob?.cancel()
        terminalRenderJob = lifecycleScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(
                    prepareTerminalText(
                        text,
                        revision,
                        lightTheme,
                        rawMode,
                        claudePane,
                        displayAgent,
                        omittedMarker,
                    ),
                    textMetrics,
                )
            }
            if (generation != terminalRenderGeneration || pendingTerminalRevision != revision ||
                pendingTerminalText != text
            ) {
                return@launch
            }
            commitTerminalRender(text, revision, prepared, scrollToBottom, afterRender)
        }
    }

    private fun commitTerminalRender(
        text: String,
        revision: Long,
        prepared: CharSequence,
        scrollToBottom: Boolean,
        afterRender: (() -> Unit)?,
    ) {
        pendingTerminalRevision = null
        pendingTerminalText = null
        lastRawText = text
        renderedRevision = revision
        renderedTerminalText = prepared
        applyFindHighlights(resetCursor = false)
        afterRender?.invoke()
        if (!scrollToBottom) return
        suppressScrollTracking = true
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
            followingOutput = true
            setNewOutputAvailable(false)
            binding.terminalScroll.post { suppressScrollTracking = false }
        }
    }

    private fun resumeLatestOutput() {
        val revision = latestRevision
        if (revision != null) showTerminalRevision(latestRawText, revision, scrollToBottom = true)
        else {
            followingOutput = true
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
            setNewOutputAvailable(false)
        }
    }

    private fun terminalIsAtBottom(): Boolean {
        val child = binding.terminalScroll.getChildAt(0) ?: return true
        val remaining = child.height - binding.terminalScroll.height - binding.terminalScroll.scrollY
        return remaining <= dp(24)
    }

    private fun currentPaneSummary(state: PaneUiState): PaneSummary? = state.panes.firstOrNull {
        it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
    }

    private fun updateQuickReplyState(state: PaneUiState) {
        if (pendingQuickReply == null) return
        if (state.sending) {
            quickReplyObservedSending = true
            return
        }
        if (!quickReplyObservedSending) return
        if (state.mutationError != null || state.error != null) {
            pendingQuickReply = null
            quickReplyObservedSending = false
            quickSuccessVisible = false
            if (drawer == ComposerDrawer.QUICK) populateQuickActions(state.quickReplies)
            return
        }
        if (!quickSuccessVisible) {
            quickSuccessVisible = true
            if (drawer == ComposerDrawer.QUICK) populateQuickActions(state.quickReplies)
            confirmHandler.postDelayed({
                pendingQuickReply = null
                quickReplyObservedSending = false
                quickSuccessVisible = false
                if (drawer == ComposerDrawer.QUICK) showDrawer(ComposerDrawer.NONE)
            }, QUICK_SUCCESS_MS)
        }
    }

    private fun prepareTerminalText(
        text: String,
        revision: Long,
        lightTheme: Boolean,
        rawMode: Boolean,
        claudePane: Boolean,
        displayAgent: String,
        omittedMarker: String,
    ): CharSequence {
        val boundedText = TerminalRenderWindow.limit(text, omittedMarker)
        val parsed = AnsiParser().parse(
            boundedText,
            colourTransform = if (lightTheme) TerminalColourSpace::invertHue else { colour -> colour },
        )
        // A safety-only claim (currently Grok's unprobed checkbox grammar) still owns the keyboard,
        // but has no complete native representation. Keep that region verbatim in the transcript;
        // removing it in favour of an empty card would destroy context rather than fail closed.
        val projected = SemanticTerminalProjection.project(
            parsed,
            AgentSemanticParser.detect(displayAgent, boundedText, revision)?.takeIf(::hasNativeSemanticPresentation),
            rawMode,
        )
        val projectedChrome = if (claudePane && !rawMode) {
            ClaudeChromeFilter().filter(projected)
        } else {
            projected
        }
        return TerminalLinks.apply(projectedChrome)
    }

    /** Synchronous one-shot path for display toggles and deterministic view tests. */
    private fun renderTerminal(text: String) {
        renderedTerminalText = prepareTerminalText(
            text,
            renderedRevision ?: latestRevision ?: 0L,
            isLightTheme(),
            displayPreferences.getBoolean(PREF_RAW, false),
            isClaudePane,
            agentName,
            getString(R.string.pane_terminal_output_omitted),
        )
        applyFindHighlights(resetCursor = false)
    }

    private fun renderSemanticSurface(surface: SemanticSurface?, state: PaneUiState) = with(binding) {
        semanticPanel.isVisible = surface != null && hasNativeSemanticPresentation(surface) && !zenMode &&
            !displayPreferences.getBoolean(PREF_RAW, false)
        semanticActions.removeAllViews()
        if (surface == null) return@with
        semanticKind.text = when (surface.kind) {
            SemanticKind.PROMPT_SELECT -> getString(R.string.pane_semantic_prompt)
            SemanticKind.WIZARD -> getString(R.string.pane_semantic_wizard)
            SemanticKind.PREVIEW_SELECT -> getString(R.string.pane_semantic_preview)
            SemanticKind.MULTI_SELECT -> getString(R.string.pane_semantic_multi)
            SemanticKind.MENU -> getString(R.string.pane_semantic_menu)
            SemanticKind.AUTOCOMPLETE -> getString(R.string.pane_semantic_suggestions)
        }
        val model = surface.nativeModel
        if (model == null || !hasNativeSemanticPresentation(surface)) {
            // A claimed region without a typed projection is not safe to operate. Raw mode remains
            // available and shows the original terminal bytes.
            semanticPanel.isVisible = false
            return@with
        }
        semanticTitle.text = model.title
        semanticDetail.isVisible = false
        when (model) {
            is NativeSemanticModel.Prompt -> renderNativePrompt(surface, model)
            is NativeSemanticModel.Wizard -> renderNativeWizard(surface, model)
            is NativeSemanticModel.Preview -> renderNativePreview(surface, model)
            is NativeSemanticModel.Multi -> renderNativeMulti(surface, model)
            is NativeSemanticModel.Menu -> renderNativeMenu(surface, model)
            is NativeSemanticModel.Autocomplete -> renderNativeAutocomplete(model)
        }
        updateSemanticActionState(state)
    }

    private fun hasNativeSemanticPresentation(surface: SemanticSurface): Boolean = when (val model = surface.nativeModel) {
        is NativeSemanticModel.Prompt -> model.options.isNotEmpty()
        is NativeSemanticModel.Wizard -> model.review || model.options.isNotEmpty()
        is NativeSemanticModel.Preview -> model.options.isNotEmpty()
        is NativeSemanticModel.Multi -> model.review || model.options.isNotEmpty()
        is NativeSemanticModel.Menu -> model.rawLines.isNotEmpty()
        is NativeSemanticModel.Autocomplete -> model.entries.isNotEmpty()
        null -> false
    }

    private fun renderNativePrompt(surface: SemanticSurface, model: NativeSemanticModel.Prompt) {
        binding.semanticDetail.text = model.family
        binding.semanticDetail.isVisible = true
        model.options.forEach { option ->
            option.actionId?.let { id ->
                surface.actions.firstOrNull { it.id == id }?.let { action ->
                    addSemanticOption(option.numberedLabel(), option.description, option.pointed, option.chosen) {
                        viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action))
                    }
                }
            }
        }
        model.feedback?.let { feedback ->
            when {
                feedback.focused -> binding.semanticActions.addView(messageText(
                    getString(R.string.semantic_feedback_focused) +
                        feedback.text.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty(),
                ))
                feedback.text.isNotBlank() -> binding.semanticActions.addView(messageText(feedback.text))
                feedback.offered -> addSemanticEditorOffer(
                    getString(R.string.semantic_feedback_offer),
                    getString(R.string.semantic_feedback_title),
                    "",
                    240,
                    getString(R.string.semantic_send_feedback),
                ) { viewModel.sendSemanticIntent(surface, SemanticIntent.PromptFeedback(feedback.key, it)) }
            }
        }
        if (surface.interactionLocked && model.feedback == null) {
            binding.semanticActions.addView(messageText(getString(R.string.semantic_controls_locked)))
        }
    }

    private fun renderNativeWizard(surface: SemanticSurface, model: NativeSemanticModel.Wizard) {
        addSemanticStepper(model.steps, surface)
        if (model.review) {
            binding.semanticActions.addView(messageText(getString(R.string.semantic_review_answers)))
            model.answers.forEach { answer ->
                binding.semanticActions.addView(messageText("${answer.question}\n${answer.answer}"))
            }
            if (model.incomplete) binding.semanticActions.addView(messageText(getString(R.string.semantic_incomplete)))
            addSemanticStaticAction(surface, "submit", getString(R.string.semantic_submit_answers))
            addSemanticStaticAction(surface, "cancel", getString(R.string.semantic_cancel))
            return
        }
        model.options.forEach { option ->
            val action = option.actionId?.let { id -> surface.actions.firstOrNull { it.id == id } } ?: return@forEach
            val escape = option.label.startsWith("Chat about this", true)
            addSemanticOption(
                if (escape) "${option.numberedLabel()} · ${getString(R.string.semantic_ends_questions)}" else option.numberedLabel(),
                option.description,
                option.pointed,
                option.chosen,
            ) { viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action)) }
        }
    }

    private fun renderNativePreview(surface: SemanticSurface, model: NativeSemanticModel.Preview) {
        addSemanticStepper(model.steps, surface)
        model.options.forEach { option ->
            addSemanticOption(option.numberedLabel(), option.description, option.pointed, option.chosen) {
                option.number?.let { viewModel.sendSemanticIntent(surface, SemanticIntent.PreviewOption(it)) }
            }
        }
        if (model.preview.isNotEmpty()) {
            val pointed = model.options.firstOrNull { it.pointed }?.label.orEmpty()
            binding.semanticActions.addView(messageText(
                "${getString(R.string.semantic_current_preview, pointed)}\n${model.preview.joinToString("\n")}",
            ).apply { typeface = Typeface.MONOSPACE })
        }
        when (model.note.state) {
            NoteState.EDITING -> binding.semanticActions.addView(messageText(
                getString(R.string.semantic_note_focused) +
                    model.note.text.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty(),
            ))
            NoteState.ATTACHED -> {
                binding.semanticActions.addView(messageText(model.note.text))
                addSemanticEditorOffer(
                    getString(R.string.semantic_edit_note), getString(R.string.semantic_note_title), model.note.text,
                    300, getString(R.string.semantic_save),
                ) { viewModel.sendSemanticIntent(surface, SemanticIntent.PreviewNote(it, replacing = true)) }
                addSemanticButton(getString(R.string.semantic_remove_note)) {
                    viewModel.sendSemanticIntent(surface, SemanticIntent.PreviewNote("", replacing = true))
                }
            }
            NoteState.NONE -> addSemanticEditorOffer(
                getString(R.string.semantic_add_note), getString(R.string.semantic_note_title), "",
                300, getString(R.string.semantic_save),
            ) { viewModel.sendSemanticIntent(surface, SemanticIntent.PreviewNote(it, replacing = false)) }
        }
    }

    private fun renderNativeMulti(surface: SemanticSurface, model: NativeSemanticModel.Multi) {
        addSemanticStepper(model.steps, surface)
        if (surface.interactionLocked) {
            binding.semanticActions.addView(messageText(getString(R.string.semantic_controls_locked)))
        }
        if (model.review) {
            if (model.incomplete) binding.semanticActions.addView(messageText(getString(R.string.semantic_incomplete)))
            addSemanticStaticAction(surface, "submit", getString(R.string.semantic_submit_answers))
            addSemanticStaticAction(surface, "cancel", getString(R.string.semantic_cancel))
            return
        }
        model.options.forEach { option ->
            val action = option.actionId?.let { id -> surface.actions.firstOrNull { it.id == id } } ?: return@forEach
            val mark = if (option.checked == true) "☑" else "☐"
            addSemanticOption("$mark  ${option.numberedLabel()}", option.description, option.pointed, false) {
                viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action))
            }
        }
        model.advanceLabel?.let { label ->
            addSemanticButton(label, emphasized = true) {
                viewModel.sendSemanticIntent(surface, SemanticIntent.MultiAdvance)
            }
        }
        model.escape?.let { escape ->
            surface.actions.firstOrNull { it.id == "escape" }?.let { action ->
                addSemanticButton("${escape.label} · ${getString(R.string.semantic_ends_questions)}") {
                    viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action))
                }
            }
        }
    }

    private fun renderNativeMenu(surface: SemanticSurface, model: NativeSemanticModel.Menu) {
        binding.semanticActions.addView(messageText(model.rawLines.joinToString("\n")).apply {
            typeface = Typeface.MONOSPACE
        })
        if (model.upDown) {
            addSemanticStaticAction(surface, "up", getString(R.string.semantic_move_up))
            addSemanticStaticAction(surface, "down", getString(R.string.semantic_move_down))
        }
        if (model.leftRight) {
            model.currentValue?.let { binding.semanticActions.addView(messageText(it)) }
            addSemanticStaticAction(surface, "left", getString(R.string.semantic_previous_value))
            addSemanticStaticAction(surface, "right", getString(R.string.semantic_next_value))
        }
        model.actions.filterNot { it.id in setOf("up", "down", "left", "right") }.forEach { action ->
            addSemanticButton(action.label) { viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action)) }
        }
    }

    private fun renderNativeAutocomplete(model: NativeSemanticModel.Autocomplete) {
        model.entries.forEach { entry ->
            addSemanticOption(entry.name, entry.description.takeIf(String::isNotBlank), false, false, null)
        }
    }

    private fun addSemanticStepper(steps: List<com.lateapex.collie.ui.terminal.Step>, surface: SemanticSurface) {
        if (steps.isEmpty()) return
        val previous = surface.actions.firstOrNull { it.id == "previous" }
        val next = surface.actions.firstOrNull { it.id == "next" }
        previous?.let { addSemanticButton("← ${getString(R.string.semantic_previous)}") {
            viewModel.sendSemanticIntent(surface, SemanticIntent.Static(it))
        } }
        binding.semanticActions.addView(messageText(steps.joinToString("  ") {
            "${if (it.answered) "☑" else "☐"} ${it.label}"
        }))
        next?.let { addSemanticButton("${getString(R.string.semantic_next)} →") {
            viewModel.sendSemanticIntent(surface, SemanticIntent.Static(it))
        } }
    }

    private fun addSemanticStaticAction(surface: SemanticSurface, id: String, label: String) {
        surface.actions.firstOrNull { it.id == id }?.let { action ->
            addSemanticButton(label) { viewModel.sendSemanticIntent(surface, SemanticIntent.Static(action)) }
        }
    }

    private fun addSemanticOption(
        label: String,
        description: String?,
        pointed: Boolean,
        chosen: Boolean,
        action: (() -> Unit)?,
    ) {
        val prefix = buildString {
            if (pointed) append("› ")
            if (chosen) append("✓ ")
        }
        addSemanticButton(prefix + label + description?.let { "\n$it" }.orEmpty(), onClick = action)
    }

    private fun com.lateapex.collie.ui.terminal.Option.numberedLabel(): String =
        number?.let { "$it. $label" } ?: label

    private fun addSemanticButton(label: String, emphasized: Boolean = false, onClick: (() -> Unit)?): MaterialButton {
        val button = outlinedButton(label).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isEnabled = onClick != null
            if (emphasized) setControlState(this, true)
            onClick?.let { handler ->
                tag = SEMANTIC_CONTROL_TAG
                setOnClickListener { handler() }
            }
        }
        binding.semanticActions.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        return button
    }

    private fun addSemanticEditorOffer(
        offer: String,
        title: String,
        initial: String,
        maxLength: Int,
        submitLabel: String,
        onSubmit: (String) -> Unit,
    ) {
        lateinit var offerButton: MaterialButton
        offerButton = addSemanticButton(offer) {
            offerButton.isVisible = false
            val editor = EditText(this).apply {
                hint = title
                setText(initial)
                setSelection(text.length)
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
                minLines = 2
                maxLines = 5
            }
            binding.semanticActions.addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            lateinit var controls: LinearLayout
            controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(outlinedButton(getString(R.string.semantic_cancel)).apply {
                tag = SEMANTIC_CONTROL_TAG
                setOnClickListener {
                    binding.semanticActions.removeView(editor)
                    binding.semanticActions.removeView(controls)
                    offerButton.isVisible = true
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            controls.addView(outlinedButton(submitLabel).apply {
                tag = SEMANTIC_CONTROL_TAG
                setOnClickListener {
                    val value = editor.text?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty()) onSubmit(value)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            binding.semanticActions.addView(controls)
            editor.requestFocus()
        }
    }

    private fun renderComposerNotices(state: PaneUiState) = with(binding) {
        val draft = stableTerminalDraft
        terminalDraftNotice.isVisible = draft != null && noEchoPrompt == null && semanticSurface?.ownsKeyboard != true
        terminalDraftText.text = draft?.let { getString(R.string.pane_terminal_draft, it.text) }.orEmpty()
        terminalDraftTakeOver.isVisible = draft != null && !draft.opaque
        terminalDraftTakeOver.isEnabled = draft != null && !draft.opaque

        val prompt = noEchoPrompt
        noEchoNotice.isVisible = prompt != null && !directTyping
        noEchoText.text = prompt?.let { getString(R.string.pane_no_echo_prompt, it) }.orEmpty()
        val canType = PaneWriteGate.block(address, state) == null && state.pane != null && !state.sending
        noEchoType.isEnabled = prompt != null && canType
        noEchoDismiss.isEnabled = prompt != null
    }

    private fun takeOverTerminalDraft() {
        val draft = stableTerminalDraft?.takeUnless(TerminalDraft::opaque) ?: return
        if (directTyping) setDirectTyping(false, announce = false)
        val local = binding.replyInput.text?.toString().orEmpty()
        val merged = if (local.trim().isEmpty()) draft.text else "${local.trimEnd()}\n${draft.text}"
        binding.replyInput.setText(merged)
        binding.replyInput.setSelection(merged.length)
        takenOverTerminalDraft = draft
        terminalDraftTracker.markHandled(draft)
        stableTerminalDraft = null
        render(currentState)
        binding.replyInput.requestFocus()
    }

    private fun updateSemanticActionState(state: PaneUiState) {
        val surface = semanticSurface ?: return
        val enabled = PaneWriteGate.block(address, state) == null && state.pane != null &&
            !state.sending && !surface.interactionLocked
        fun update(view: View) {
            if (view.tag == SEMANTIC_CONTROL_TAG) view.isEnabled = enabled
            if (view is ViewGroup) view.children().forEach(::update)
        }
        update(binding.semanticActions)
    }

    private fun sendReplyWithConfirmation(draft: String) {
        if (semanticSurface?.ownsKeyboard == true) {
            showTransientMessage(getString(R.string.pane_dialog_owns_input))
            return
        }
        if (noEchoPrompt != null) {
            showTransientMessage(getString(R.string.pane_no_echo_prompt, noEchoPrompt))
            return
        }
        val reason = DestructiveInput.reason(draft)
        if (reason == null) {
            resetDestructiveConfirm(render = false)
            beginReplySend(draft)
            return
        }
        if (sendConfirmation.confirm(draft)) {
            resetDestructiveConfirm(render = false)
            beginReplySend(draft)
            return
        }
        pendingDestructiveSend = PendingDestructiveSend(draft, reason)
        confirmHandler.removeCallbacks(clearDestructiveConfirm)
        confirmHandler.postDelayed(clearDestructiveConfirm, CONFIRM_TIMEOUT_MS)
        renderStatus(currentState)
    }

    private fun sendReplyDraft() {
        val draft = binding.replyInput.text?.toString().orEmpty()
        if (draft.isNotBlank()) sendReplyWithConfirmation(draft)
    }

    private fun beginReplySend(text: String) {
        val pane = currentState.pane
        pendingReplyAttempt = PendingReplyAttempt(text, pane?.revision, pane?.text)
        viewModel.sendReply(text, takenOverTerminalDraft)
    }

    private fun renderPendingSent() {
        if (!::binding.isInitialized) return
        binding.pendingSendNotice.text = pendingSent?.text?.let { getString(R.string.pane_pending_sent, it) }.orEmpty()
        binding.pendingSendNotice.isVisible = pendingSent != null && !directTyping
    }

    private fun resetDestructiveConfirm(render: Boolean = true) {
        confirmHandler.removeCallbacks(clearDestructiveConfirm)
        sendConfirmation.reset()
        pendingDestructiveSend = null
        if (render && ::binding.isInitialized) renderStatus(currentState)
    }

    private fun renderStatus(state: PaneUiState) = with(binding.errorText) {
        val pending = pendingDestructiveSend?.takeIf { sendConfirmation.isPending(it.draft) }
        if (pending != null) {
            text = getString(R.string.pane_destructive_confirm, pending.reason)
            isVisible = true
            setTextColor(getColor(R.color.collie_destructive))
            return@with
        }
        if (keyQueueDiscardConfirmation.isPending(KEY_QUEUE_DISCARD_CONFIRMATION)) {
            text = getString(R.string.pane_agent_confirm, getString(R.string.pane_key_clear_description))
            isVisible = true
            setTextColor(getColor(R.color.collie_muted))
            return@with
        }
        text = state.mutationError ?: state.error ?: state.status.orEmpty()
        isVisible = state.mutationError != null || state.error != null || state.status != null
        setTextColor(getColor(if (state.mutationError != null || state.error != null) R.color.collie_error else R.color.collie_muted))
    }

    private fun toggleDrawer(target: ComposerDrawer) {
        if (directTyping) {
            setDirectTyping(false)
        }
        showDrawer(if (drawer == target) ComposerDrawer.NONE else target)
    }

    private fun showDrawer(target: ComposerDrawer): Boolean {
        if (drawer == ComposerDrawer.KEYS && target != ComposerDrawer.KEYS && paneKeyQueue.staged.isNotEmpty()) {
            if (!keyQueueDiscardConfirmation.confirm(KEY_QUEUE_DISCARD_CONFIRMATION)) {
                confirmHandler.removeCallbacks(clearKeyQueueDiscardConfirm)
                confirmHandler.postDelayed(clearKeyQueueDiscardConfirm, CONFIRM_TIMEOUT_MS)
                renderStatus(currentState)
                return false
            }
        }
        resetKeyQueueDiscardConfirm()
        if (drawer == ComposerDrawer.KEYS && target != ComposerDrawer.KEYS) {
            paneKeyQueue.clear()
            renderKeyQueue()
            resetKeysTrayPresentation()
        }
        if (drawer != ComposerDrawer.KEYS && target == ComposerDrawer.KEYS) resetKeysTrayPresentation()
        with(binding) {
            drawer = target
            composerDock.isVisible = target != ComposerDrawer.NONE
            keyRow.isVisible = target == ComposerDrawer.KEYS
            quickActionsContainer.isVisible = target == ComposerDrawer.QUICK
            displayPrefsContainer.isVisible = target == ComposerDrawer.DISPLAY
            composerDockTitle.text = when (target) {
                ComposerDrawer.KEYS -> getString(R.string.pane_mode_keys)
                ComposerDrawer.QUICK -> getString(R.string.pane_mode_quick)
                ComposerDrawer.DISPLAY -> getString(R.string.pane_mode_settings)
                ComposerDrawer.NONE -> ""
            }
            setControlState(keysModeButton, target == ComposerDrawer.KEYS)
            setControlState(quickModeButton, target == ComposerDrawer.QUICK)
            setControlState(composerSettingsButton, target == ComposerDrawer.DISPLAY)
            if (target == ComposerDrawer.QUICK) populateQuickActions(currentState.quickReplies)
        }
        return true
    }

    private fun resetKeyQueueDiscardConfirm(render: Boolean = true) {
        confirmHandler.removeCallbacks(clearKeyQueueDiscardConfirm)
        keyQueueDiscardConfirmation.reset()
        if (render && ::binding.isInitialized) renderStatus(currentState)
    }

    private fun setDirectTyping(active: Boolean, announce: Boolean = true) {
        with(binding) {
            if (directTyping == active) return
            resetDestructiveConfirm(render = false)
            if (active) {
                if (semanticSurface?.ownsKeyboard == true) {
                    showTransientMessage(getString(R.string.pane_dialog_owns_input))
                    return
                }
                if (!viewModel.beginDirectTyping()) {
                    showTransientMessage(getString(R.string.pane_direct_wait))
                    return
                }
                // Ordinary input is deliberately disabled for no-echo prompts. Explicit Type
                // mode owns this field, so re-enable it before arming the terminal IME bridge.
                replyInput.isEnabled = true
                replyInput.beginDirectTyping()
            } else {
                viewModel.endDirectTyping()
                replyInput.endDirectTyping()
            }
            directTyping = active
            setControlState(typeModeButton, active)
            replyInput.hint = getString(if (active) R.string.pane_direct_hint else R.string.pane_reply_hint)
            sendButton.isEnabled = active || terminalWriteBlock() == null &&
                (currentState.composerReady || takenOverTerminalDraft != null) && !currentState.sending
            sendButton.contentDescription = getString(if (active) R.string.pane_direct_stop else R.string.pane_send)
            if (active) {
                replyInput.requestFocus()
                replyInput.post {
                    if (directTyping && !isFinishing && !isDestroyed) {
                        replyInput.requestFocus()
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                if (announce) showTransientMessage(getString(R.string.pane_direct_armed))
            } else {
                val terminalWritable = terminalWriteBlock() == null
                typeModeButton.isEnabled = terminalWritable && currentState.pane != null && !currentState.sending
                replyInput.isEnabled = terminalWritable && !currentState.sending
                replyInput.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(replyInput.windowToken, 0)
            }
            renderComposerNotices(currentState)
            renderComposerMediaControls()
            renderPendingSent()
        }
    }

    private fun setControlState(button: View, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_collie_control_on else R.drawable.bg_collie_control_off)
        if (button is TextView) button.setTextColor(getColor(if (selected) R.color.collie_on_control_on else R.color.collie_muted))
    }

    private fun populateQuickActions(groups: List<QuickReplyGroup>): Unit = with(binding.quickActionsContainer) {
        removeAllViews()
        if (!currentState.composerReady) {
            addView(messageText(getString(R.string.pane_reply_unavailable)))
        }
        groups.forEach { group ->
            addView(TextView(this@PaneActivity).apply {
                text = group.title
                setTextColor(getColor(R.color.collie_muted))
                textSize = 10f
                setPadding(0, dp(8), 0, dp(4))
            })
            group.items.chunked(2).forEach { pair ->
                val row = LinearLayout(this@PaneActivity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { reply ->
                    val label = when {
                        pendingQuickReply != reply -> reply
                        quickSuccessVisible -> getString(R.string.pane_quick_sent, reply)
                        else -> getString(R.string.pane_quick_sending)
                    }
                    row.addView(outlinedButton(label).apply {
                        isEnabled = terminalWriteBlock() == null && currentState.composerReady && !currentState.sending
                            && pendingQuickReply == null
                        setOnClickListener {
                            pendingQuickReply = reply
                            quickReplyObservedSending = false
                            quickSuccessVisible = false
                            populateQuickActions(groups)
                            beginReplySend(reply)
                        }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
                }
                addView(row)
            }
        }
        if (groups.isEmpty()) addView(messageText(getString(R.string.pane_quick_unavailable)))
    }

    private fun showAgentPalette() {
        if (directTyping) {
            setDirectTyping(false)
        }
        if (!showDrawer(ComposerDrawer.NONE)) return
        val commands = currentState.commands
        if (commands.isEmpty()) return
        val dialog = CollieBottomSheetDialog(this, getString(R.string.pane_agent_commands))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = getString(R.string.pane_agent_search, commands.size)
            isSingleLine = true
        }
        root.addView(search, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val paletteConfirmation = TimedConfirmation<String>(now = SystemClock::uptimeMillis)
        var queryValue = ""
        var expiry: Runnable? = null
        fun rebuild(query: String) {
            queryValue = query
            list.removeAllViews()
            val commonCount = commands.count(AgentCommand::common)
            if (query.isBlank() && commands.size > commonCount) {
                list.addView(messageText(getString(R.string.pane_agent_common_hint, commands.size)))
            }
            filterAgentCommands(commands, query)
                .forEach { command ->
                    val armed = command.dangerous && paletteConfirmation.isPending(command.command)
                    val label = if (armed) {
                        getString(R.string.pane_agent_confirm, command.command)
                    } else {
                        "${command.command}${command.argumentHint.takeIf(String::isNotBlank)?.let { "  $it" }.orEmpty()}\n${command.description}"
                    }
                    list.addView(outlinedButton(label).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        typeface = Typeface.MONOSPACE
                        minHeight = dp(56)
                        isEnabled = terminalWriteBlock() == null && !currentState.sending &&
                            (command.takesArgument || currentState.composerReady)
                        setOnClickListener {
                            if (command.takesArgument) {
                                insertReply("${command.command} ")
                                dialog.dismiss()
                            } else if (command.dangerous && !paletteConfirmation.confirm(command.command)) {
                                expiry?.let(confirmHandler::removeCallbacks)
                                rebuild(queryValue)
                                expiry = Runnable {
                                    paletteConfirmation.reset()
                                    rebuild(queryValue)
                                }.also { confirmHandler.postDelayed(it, CONFIRM_TIMEOUT_MS) }
                            } else {
                                paletteConfirmation.reset()
                                beginReplySend(command.command)
                                dialog.dismiss()
                            }
                        }
                    }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
        }
        search.addTextChangedListener(simpleTextWatcher {
            paletteConfirmation.reset()
            expiry?.let(confirmHandler::removeCallbacks)
            rebuild(it)
        })
        rebuild("")
        dialog.setSheetContent(root)
        dialog.setOnDismissListener {
            val pendingExpiry = expiry
            if (pendingExpiry != null) confirmHandler.removeCallbacks(pendingExpiry)
            restorePaneFocus()
        }
        dialog.show()
    }

    private fun insertReply(value: String) = with(binding.replyInput) {
        if (directTyping) setDirectTyping(false)
        val updated = ComposerActions.appendArgumentCommand(text?.toString().orEmpty(), value.trimEnd())
        setText(updated)
        requestFocus()
        setSelection(updated.length)
    }

    private fun renderPaneNavigation(state: PaneUiState) = with(binding) {
        val model = PaneTabStripModel.build(
            current = address,
            allPanes = state.panes,
            allTabs = state.tabs,
            visiblePreference = keyboardStripFold?.not() ?: nativePreferences.paneStripsVisible,
            canWrite = state.canWrite,
            mux = state.mux,
            servers = state.servers,
        )
        if (model == renderedTabStrip) {
            newTabButton.isEnabled = model.canCreateTab && !tabCreateInFlight
            return@with
        }
        renderedTabStrip = model
        val hasStrips = model.tabs.isNotEmpty() || model.panes.size > 1
        tabStrip.isVisible = !zenMode && model.visible
        showTabsButton.isVisible = !zenMode && hasStrips && !model.visible
        currentPaneSummary()?.let { pane ->
            showTabsButton.text = listOfNotNull(
                pane.workspaceLabel,
                pane.tabLabel?.takeIf(String::isNotBlank),
            ).joinToString(" › ")
        }
        newTabButton.isVisible = model.canCreateTab
        newTabButton.isEnabled = model.canCreateTab && !tabCreateInFlight
        tabItems.removeAllViews()
        model.tabs.forEach { item ->
            val label = item.tab.label.ifBlank { "${item.tab.number}" }
            tabItems.addView(outlinedButton(label).apply {
                applySelectedState(item.active)
                setIconResource(R.drawable.bg_pane_status_dot)
                iconTint = ColorStateList.valueOf(triageColour(item.bucket))
                iconSize = dp(8)
                iconPadding = dp(7)
                contentDescription = getString(
                    if (item.active) R.string.pane_tab_current_description else R.string.pane_tab_description,
                    label,
                )
                setOnLongClickListener {
                    showStripTabActions(item)
                    true
                }
                setOnClickListener {
                    if (item.active) showStripTabActions(item)
                    else item.target?.let(::openPane)
                }
            }, stripButtonParams())
        }
        paneScroll.isVisible = model.panes.size > 1
        paneItems.removeAllViews()
        model.panes.forEach { item ->
            val name = displayAgentTitle(item.pane.agent, item.pane.paneLabel ?: item.pane.sessionName)
                ?: item.pane.agent.ifBlank { item.pane.paneId }
            val tag = item.pane.paneId.substringAfterLast(':')
            val label = "$name  $tag"
            paneItems.addView(outlinedButton(label).apply {
                applySelectedState(item.active)
                if (item.pane.kind == "shell" || item.pane.agent.equals("shell", ignoreCase = true)) {
                    setIconResource(agentIcon(item.pane.agent))
                    iconTint = null
                    iconSize = dp(14)
                } else {
                    setIconResource(R.drawable.bg_pane_status_dot)
                    iconTint = ColorStateList.valueOf(statusColour(item.pane.status.name.lowercase(Locale.ROOT)))
                    iconSize = dp(8)
                }
                iconPadding = dp(7)
                contentDescription = getString(
                    if (item.active) R.string.pane_strip_current_description else R.string.pane_strip_description,
                    label,
                )
                setOnLongClickListener {
                    showStripPaneActions(item)
                    true
                }
                setOnClickListener {
                    if (item.active) showStripPaneActions(item) else openPane(item.pane)
                }
            }, stripButtonParams())
        }
    }

    private fun stripButtonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply { marginEnd = dp(4) }

    private fun triageColour(bucket: TriageBucket): Int = statusColour(
        when (bucket) {
            TriageBucket.NEEDS_YOU -> "blocked"
            TriageBucket.READY_UNSEEN -> "done"
            TriageBucket.WORKING -> "working"
            TriageBucket.RECENT -> "idle"
        },
    )

    private fun keyDisplayLabel(key: String): String {
        val label = when (key) {
            "Up" -> R.string.pane_key_up
            "Down" -> R.string.pane_key_down
            "Left" -> R.string.pane_key_left
            "Right" -> R.string.pane_key_right
            else -> return key
        }
        return getString(label)
    }

    private fun showStripTabActions(item: PaneTabItem) {
        val rename = structuralGate("renameTab", address)
        val close = structuralGate("closeTab", address)
        val title = item.tab.label.ifBlank { getString(R.string.tab_default, item.tab.number) }
        if (!rename.visibleAndEnabled && !close.visibleAndEnabled) {
            showUnavailableStructuralActions(title, listOf(rename, close), R.string.tab_changes_unsupported)
            return
        }
        val dialog = CollieBottomSheetDialog(this, title)
        lateinit var renderActions: () -> Unit
        renderActions = {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (rename.visibleAndEnabled) {
                content.addView(sheetActionButton(getString(R.string.tab_action_rename)) {
                    showRenameStripTab(dialog, item, renderActions)
                })
            }
            if (close.visibleAndEnabled) {
                content.addView(armedCloseButton(
                    dialog = dialog,
                    label = getString(R.string.tab_action_close),
                    warning = tabCloseConfirmLabel(item.tab),
                    onConfirmed = { closeStripTab(item) },
                ))
            }
            content.addView(sheetActionButton(getString(R.string.pane_action_cancel)) { dialog.dismiss() })
            dialog.setSheetContent(content)
        }
        renderActions()
        dialog.setOnDismissListener { restorePaneFocus() }
        dialog.show()
    }

    private fun showStripPaneActions(item: PaneStripItem) {
        val target = item.pane.address()
        val rename = structuralGate("renamePane", target)
        val close = structuralGate("closePane", target)
        val title = displayAgentTitle(item.pane.agent, item.pane.paneLabel ?: item.pane.sessionName)
            ?: item.pane.agent.ifBlank { item.pane.paneId }
        if (!rename.visibleAndEnabled && !close.visibleAndEnabled) {
            showUnavailableStructuralActions(title, listOf(rename, close), R.string.pane_changes_unsupported)
            return
        }
        val dialog = CollieBottomSheetDialog(this, title)
        lateinit var renderActions: () -> Unit
        renderActions = {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (rename.visibleAndEnabled) {
                content.addView(sheetActionButton(getString(R.string.pane_action_rename)) {
                    showRenameStripPane(dialog, item.pane, renderActions)
                })
            }
            if (close.visibleAndEnabled) {
                content.addView(armedCloseButton(
                    dialog = dialog,
                    label = getString(R.string.pane_action_close),
                    warning = getString(R.string.pane_close_again),
                    onConfirmed = { closeStripPane(item) },
                ))
            }
            content.addView(sheetActionButton(getString(R.string.pane_action_cancel)) { dialog.dismiss() })
            dialog.setSheetContent(content)
        }
        renderActions()
        dialog.setOnDismissListener { restorePaneFocus() }
        dialog.show()
    }

    private val StructuralActionGate.visibleAndEnabled: Boolean
        get() = visible && enabled

    private fun showUnavailableStructuralActions(
        title: String,
        gates: List<StructuralActionGate>,
        @androidx.annotation.StringRes unsupported: Int,
    ) {
        val message = gates.firstNotNullOfOrNull { it.refusal }?.let(::hostRefusalText)
            ?: getString(
                when {
                    !currentState.canWrite -> R.string.pane_action_read_only
                    paneActionInFlight -> R.string.pane_action_in_progress
                    else -> unsupported
                },
            )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(messageText(message))
        }
        CollieBottomSheetDialog(this, title).apply {
            content.addView(sheetActionButton(getString(R.string.pane_action_cancel)) { dismiss() })
            setSheetContent(content)
            setOnDismissListener { restorePaneFocus() }
            show()
        }
    }

    private fun showRenameStripTab(
        dialog: CollieBottomSheetDialog,
        item: PaneTabItem,
        onBack: () -> Unit,
    ) {
        if (!canMutatePane("renameTab")) return
        val field = EditText(this).apply {
            hint = getString(R.string.tab_label_hint)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_PANE_LABEL_CHARS))
            setText(item.tab.label)
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
            addView(sheetActionButton(getString(R.string.tab_action_rename)) {
                val label = field.text?.toString()?.trim().orEmpty()
                if (label.isNotEmpty()) {
                    dialog.dismiss()
                    mutateStructural(
                        "renameTab",
                        address,
                        getString(R.string.tab_action_rename),
                        { repository.renameTab(address.scope, item.tab.tabId, label) },
                    ) {
                        renderedTabStrip = null
                        viewModel.refresh()
                    }
                }
            })
            addView(sheetActionButton(getString(R.string.pane_action_cancel)) {
                returnToSheetActions(dialog, field, onBack)
            })
        }
        dialog.setSheetContent(content)
        field.post { field.requestFocus() }
    }

    private fun closeStripTab(item: PaneTabItem) {
        val fallback = if (item.active) {
            renderedTabStrip?.let { TabCloseNavigation.fallback(it.tabs, item.tab.tabId) }
        } else {
            null
        }
        mutateStructural(
            "closeTab",
            address,
            getString(R.string.tab_action_close),
            { repository.closeTab(address.scope, item.tab.tabId) },
        ) {
            when {
                !item.active -> {
                    renderedTabStrip = null
                    viewModel.refresh()
                }
                fallback != null -> openPane(fallback)
                else -> finish()
            }
        }
    }

    private fun showRenameStripPane(
        dialog: CollieBottomSheetDialog,
        pane: PaneSummary,
        onBack: () -> Unit,
    ) {
        val target = pane.address()
        if (!canMutatePane("renamePane", target)) return
        val field = EditText(this).apply {
            hint = getString(R.string.pane_rename_hint)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_PANE_LABEL_CHARS))
            setText(pane.paneLabel.orEmpty())
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
            addView(sheetActionButton(getString(R.string.pane_rename_save)) {
                val label = field.text?.toString()?.trim().orEmpty()
                dialog.dismiss()
                mutateStructural(
                    "renamePane",
                    target,
                    getString(R.string.pane_action_rename),
                    { repository.renamePane(target, label) },
                ) {
                    if (target == address) {
                        binding.paneTitle.text = label.ifEmpty {
                            displayAgentTitle(pane.agent, pane.sessionName)
                                ?: pane.tabLabel?.takeIf(String::isNotBlank)
                                ?: pane.workspaceLabel.ifBlank { pane.paneId }
                        }
                    }
                    renderedTabStrip = null
                    viewModel.refresh()
                }
            })
            addView(sheetActionButton(getString(R.string.pane_action_cancel)) {
                returnToSheetActions(dialog, field, onBack)
            })
        }
        dialog.setSheetContent(content)
        field.post { field.requestFocus() }
    }

    private fun closeStripPane(item: PaneStripItem) {
        val target = item.pane.address()
        mutateStructural(
            "closePane",
            target,
            getString(R.string.pane_action_close),
            { repository.closePane(target) },
        ) {
            if (item.active) finish() else {
                renderedTabStrip = null
                viewModel.refresh()
            }
        }
    }

    private fun PaneSummary.address(): PaneAddress = PaneAddress(Scope(host, session), paneId)

    private fun structuralGate(capability: String, target: PaneAddress): StructuralActionGate =
        PaneStructuralGate.action(
            capability,
            target,
            currentState.canWrite,
            currentState.mux,
            currentState.servers,
            paneActionInFlight,
        )

    private fun createCurrentWorkspaceTab() {
        if (!showDrawer(ComposerDrawer.NONE)) return
        val pane = currentPaneSummary() ?: return
        val model = renderedTabStrip ?: return
        if (!model.canCreateTab || tabCreateInFlight) return
        tabCreateInFlight = true
        binding.newTabButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = repository.createTab(address.scope, pane.workspaceId)) {
                is ApiResult.Success -> if (result.value.ok) {
                    result.value.pane?.let(::openCreatedPane)
                        ?: showTransientMessage(getString(R.string.pane_new_tab_failed))
                } else {
                    showTransientMessage(result.value.error ?: getString(R.string.pane_new_tab_failed))
                }
                is ApiResult.Failure -> showTransientMessage(MainViewModel.describe(resources, result.error))
                is ApiResult.NotModified -> showTransientMessage(getString(R.string.pane_new_tab_failed))
            }
            tabCreateInFlight = false
            renderedTabStrip = null
            renderPaneNavigation(currentState)
        }
    }

    private fun showPaneSwitcher() {
        if (!showDrawer(ComposerDrawer.NONE)) return
        val panes = currentState.panes
        if (panes.isEmpty()) return
        val dialog = CollieBottomSheetDialog(this, getString(R.string.pane_switcher_title))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var launchers = emptyList<Launcher>()
        var launcherHome = ""
        fun rebuild() {
            list.removeAllViews()
            val sections = paneSwitcherSections(
                launchers = launchers,
                launcherHome = launcherHome,
                launching = launchDuplicateLock.snapshot(),
            )
            sections.forEach { section ->
                when (section) {
                    is PaneSwitcherSection.Agents -> {
                        val title = switcherBucketLabel(section.bucket)
                        if (section.bucket == TriageBucket.RECENT) {
                            list.addView(switcherFoldHeader(title, section.open) {
                                nativePreferences.dashboardRecentOpen = !section.open
                                rebuild()
                            })
                        } else {
                            list.addView(switcherSectionLabel(title))
                        }
                        if (section.open) section.rows.forEach { addSwitcherPaneRow(list, dialog, it) }
                    }
                    is PaneSwitcherSection.Shells -> {
                        val title = getString(R.string.pane_switcher_shells)
                        list.addView(switcherFoldHeader(title, section.open) {
                            nativePreferences.dashboardShellsOpen = !section.open
                            rebuild()
                        })
                        if (section.open) section.rows.forEach { addSwitcherPaneRow(list, dialog, it) }
                    }
                    is PaneSwitcherSection.Launch -> {
                        val title = getString(R.string.pane_switcher_launch)
                        list.addView(switcherFoldHeader(title, section.open) {
                            nativePreferences.dashboardLaunchOpen = !section.open
                            rebuild()
                        })
                        section.refusal?.let { list.addView(messageText(hostRefusalText(it))) }
                        if (section.open) section.rows.forEach { launcher ->
                            val cwd = launcher.cwd?.takeIf(String::isNotBlank)?.let {
                                PaneSwitcherModel.shortenHome(it, section.home)
                            }
                            val label = listOfNotNull(launcher.label, cwd).joinToString("\n")
                            list.addView(outlinedButton(label).apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                minHeight = dp(52)
                                isEnabled = section.refusal == null && launcher.command !in section.launching
                                setOnClickListener {
                                    if (!launchDuplicateLock.tryStart(launcher.command)) return@setOnClickListener
                                    rebuild()
                                    lifecycleScope.launch {
                                        try {
                                            when (val result = repository.launch(launcher.command, address.scope, address.paneId)) {
                                                is ApiResult.Success -> if (result.value.ok) {
                                                    result.value.pane?.let {
                                                        dialog.dismiss()
                                                        openCreatedPane(it)
                                                    } ?: showTransientMessage(getString(R.string.pane_launch_failed))
                                                } else {
                                                    showTransientMessage(result.value.error ?: getString(R.string.pane_launch_failed))
                                                }
                                                is ApiResult.Failure -> showTransientMessage(
                                                    MainViewModel.describe(resources, result.error),
                                                )
                                                is ApiResult.NotModified -> showTransientMessage(
                                                    getString(R.string.pane_launch_failed),
                                                )
                                            }
                                        } finally {
                                            launchDuplicateLock.finish(launcher.command)
                                            if (!isFinishing) rebuild()
                                        }
                                    }
                                }
                            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    }
                }
            }
        }
        rebuild()
        root.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setSheetContent(root)
        dialog.setOnDismissListener { restorePaneFocus() }
        dialog.show()
        lifecycleScope.launch {
            when (val result = repository.launchers(address.scope)) {
                is ApiResult.Success -> {
                    launchers = result.value.launchers
                    launcherHome = result.value.home
                    if (dialog.isShowing) rebuild()
                }
                is ApiResult.Failure, is ApiResult.NotModified -> Unit
            }
        }
        if (nativePreferences.hapticsEnabled) {
            binding.switcherHandle.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun paneSwitcherSections(
        launchers: List<Launcher> = emptyList(),
        launcherHome: String = "",
        launching: Set<String> = emptySet(),
    ) = PaneSwitcherModel.sections(
        panes = currentState.panes,
        current = address,
        recentOpen = nativePreferences.dashboardRecentOpen,
        recentNewest = nativePreferences.dashboardRecentNewest,
        shellsPreference = nativePreferences.dashboardShellsOpen,
        launchers = launchers,
        launcherHome = launcherHome,
        launchPreference = nativePreferences.dashboardLaunchOpen,
        writesAuthorized = currentState.canWrite,
        mux = currentState.mux,
        servers = currentState.servers,
        launching = launching,
    )

    private fun addSwitcherPaneRow(list: LinearLayout, dialog: CollieBottomSheetDialog, row: SwitcherPaneRow) {
        val label = switcherPaneLabel(row)
        list.addView(outlinedButton(label).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            applySelectedState(row.active)
            contentDescription = getString(
                if (row.active) R.string.pane_switcher_current_description
                else R.string.pane_switcher_pane_description,
                label,
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(agentIcon(row.pane.agent), 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener {
                dialog.dismiss()
                if (!row.active) openPane(row.pane)
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun switcherPaneLabel(row: SwitcherPaneRow): String {
        val title = displayAgentTitle(row.pane.agent, row.pane.paneLabel ?: row.pane.sessionName)
            ?: row.pane.agent.ifBlank { row.pane.paneId }
        val secondary = displayAgentTitle(row.pane.agent, row.secondary)
        return buildString {
            append(row.project)
            row.tab?.let { append(" · ").append(it) }
            append("\n").append(title)
            secondary?.takeUnless { it == title }?.let { append(" · ").append(it) }
        }
    }

    /** Paints the real switcher snapshot for the drag preview without making it interactive/modal. */
    private fun populateSwitcherPeek() = with(binding.switcherPeekContent) {
        removeAllViews()
        paneSwitcherSections().forEach { section ->
            when (section) {
                is PaneSwitcherSection.Agents -> {
                    val title = switcherBucketLabel(section.bucket)
                    addView(switcherPreviewSection(title, section.bucket == TriageBucket.RECENT, section.open))
                    if (section.open) section.rows.forEach { addView(switcherPreviewPaneRow(it)) }
                }
                is PaneSwitcherSection.Shells -> {
                    addView(switcherPreviewSection(getString(R.string.pane_switcher_shells), true, section.open))
                    if (section.open) section.rows.forEach { addView(switcherPreviewPaneRow(it)) }
                }
                is PaneSwitcherSection.Launch -> {
                    addView(switcherPreviewSection(getString(R.string.pane_switcher_launch), true, section.open))
                }
            }
        }
    }

    private fun switcherPreviewSection(label: String, fold: Boolean, open: Boolean): View =
        if (fold) {
            outlinedButton(
                getString(
                    R.string.pane_switcher_fold_label,
                    getString(if (open) R.string.pane_fold_open_symbol else R.string.pane_fold_closed_symbol),
                    label,
                ),
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                isClickable = false
                isFocusable = false
            }
        } else {
            switcherSectionLabel(label)
        }

    private fun switcherPreviewPaneRow(row: SwitcherPaneRow) = outlinedButton(switcherPaneLabel(row)).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        applySelectedState(row.active)
        setCompoundDrawablesRelativeWithIntrinsicBounds(agentIcon(row.pane.agent), 0, 0, 0)
        compoundDrawablePadding = dp(10)
        isClickable = false
        isFocusable = false
    }

    private fun switcherSectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.collie_muted))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(16), dp(4), dp(5))
    }

    private fun switcherFoldHeader(label: String, open: Boolean, onClick: () -> Unit) =
        outlinedButton(
            getString(
                R.string.pane_switcher_fold_label,
                getString(if (open) R.string.pane_fold_open_symbol else R.string.pane_fold_closed_symbol),
                label,
            ),
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            contentDescription = getString(
                if (open) R.string.pane_switcher_collapse else R.string.pane_switcher_expand,
                label,
            )
            setOnClickListener { onClick() }
        }

    private fun switcherBucketLabel(bucket: TriageBucket): String = getString(
        when (bucket) {
            TriageBucket.NEEDS_YOU -> R.string.pane_switcher_needs
            TriageBucket.READY_UNSEEN -> R.string.pane_switcher_ready
            TriageBucket.WORKING -> R.string.pane_switcher_working
            TriageBucket.RECENT -> R.string.pane_switcher_recent
        },
    )

    private fun hostRefusalText(refusal: HostWriteRefusal): String = when (refusal) {
        is HostWriteRefusal.Incompatible -> refusal.detail?.let {
            getString(R.string.pane_host_incompatible_detail, refusal.serverName, it)
        } ?: getString(R.string.pane_host_incompatible, refusal.serverName)
        is HostWriteRefusal.Unreachable -> getString(R.string.pane_host_unreachable, refusal.serverName)
    }

    private fun bindSwitcherPull() {
        var startY = 0f
        var velocityTracker: VelocityTracker? = null
        val density = resources.displayMetrics.density
        val touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        binding.switcherHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    populateSwitcherPeek()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val pullPx = (startY - event.rawY).coerceIn(0f, dp(SWITCHER_PEEK_DP).toFloat())
                    binding.composerChrome.translationY = -pullPx
                    binding.switcherPeek.isVisible = pullPx > touchSlopPx
                    binding.switcherPeek.translationY = (dp(SWITCHER_PREVIEW_HEIGHT_DP) - pullPx)
                        .coerceAtLeast(0f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1_000)
                    val pullDp = (startY - event.rawY) / resources.displayMetrics.density
                    val upwardVelocityDpPerSecond = -(velocityTracker?.yVelocity ?: 0f) / density
                    binding.composerChrome.animate().translationY(0f).setDuration(SWITCHER_SETTLE_MS).start()
                    settleSwitcherPeek()
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    velocityTracker?.recycle()
                    velocityTracker = null
                    when (PaneSwitcherPullDecision.decide(
                        travelDp = pullDp,
                        touchSlopDp = touchSlopPx / density,
                        upwardVelocityDpPerSecond = upwardVelocityDpPerSecond,
                    )) {
                        PaneSwitcherRelease.TAP -> view.performClick()
                        PaneSwitcherRelease.OPEN -> showPaneSwitcher()
                        PaneSwitcherRelease.CANCEL -> Unit
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    binding.composerChrome.animate().translationY(0f).setDuration(SWITCHER_SETTLE_MS).start()
                    settleSwitcherPeek()
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }
                else -> true
            }
        }
    }

    private fun settleSwitcherPeek() {
        binding.switcherPeek.animate()
            .translationY(dp(SWITCHER_PREVIEW_HEIGHT_DP).toFloat())
            .setDuration(SWITCHER_SETTLE_MS)
            .withEndAction { binding.switcherPeek.isVisible = false }
            .start()
    }

    private fun openPane(pane: PaneSummary) {
        if (!showDrawer(ComposerDrawer.NONE)) return
        val fallback = listOf(pane.workspaceLabel, pane.tabLabel).filterNotNull().filter(String::isNotBlank).joinToString(" › ")
        val title = displayAgentTitle(pane.agent, pane.paneLabel ?: pane.sessionName) ?: fallback.ifBlank { pane.paneId }
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(EXTRA_PANE_ID, pane.paneId)
            putExtra(EXTRA_HOST, pane.host)
            putExtra(EXTRA_SESSION, pane.session)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_META, getString(R.string.pane_agent_workspace_meta, pane.agent, pane.workspaceLabel))
            putExtra(EXTRA_AGENT, pane.agent)
            putExtra(EXTRA_CWD, pane.cwd)
            putExtra(EXTRA_TAB_LABEL, pane.tabLabel)
            putExtra(EXTRA_STATUS, pane.status.name.lowercase(Locale.ROOT))
        })
        finish()
    }

    private fun openCreatedPane(pane: CreatedPane) {
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(EXTRA_PANE_ID, pane.paneId)
            putExtra(EXTRA_HOST, address.scope.host)
            putExtra(EXTRA_SESSION, address.scope.session)
            putExtra(EXTRA_TITLE, pane.workspaceLabel)
            putExtra(EXTRA_META, getString(R.string.pane_created_shell_meta, pane.workspaceLabel))
            putExtra(EXTRA_AGENT, "shell")
            putExtra(EXTRA_CWD, pane.cwd)
            putExtra(EXTRA_TAB_LABEL, pane.tabId)
            putExtra(EXTRA_STATUS, STATUS_UNKNOWN)
            putExtra(EXTRA_FRESH_PANE, true)
        })
        finish()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showPaneActions(anchor: View) {
        val paneSummary = currentPaneSummary(currentState)
        val historyAvailable = PaneBufferPolicy.resolve(
            paneSummary,
            currentState.mux,
            currentState.requestedLines,
        ).action == PaneBufferAction.SHOW_HISTORY
        val dialog = CollieBottomSheetDialog(this, getString(R.string.pane_actions))
        lateinit var renderActions: () -> Unit
        renderActions = {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            fun closingRow(label: String, enabled: Boolean = true, action: () -> Unit) {
                content.addView(sheetActionButton(label) {
                    dialog.dismiss()
                    action()
                }.apply { isEnabled = enabled })
            }

            closingRow(getString(R.string.pane_action_find), action = ::openFind)
            if (historyAvailable) {
                closingRow(getString(R.string.pane_action_history), action = ::openHistory)
            }
            if (nativePreferences.zenAvailable) {
                val zenLabel = if (zenMode) {
                    "✓ ${getString(R.string.pane_action_zen)}"
                } else {
                    getString(R.string.pane_action_zen)
                }
                closingRow(zenLabel) { setZenMode(!zenMode) }
            }
            if (currentState.canWrite) {
                val focus = PaneStructuralGate.action(
                    "setFocus", address, true, currentState.mux, currentState.servers, paneActionInFlight,
                )
                val rename = PaneStructuralGate.action(
                    "renamePane", address, true, currentState.mux, currentState.servers, paneActionInFlight,
                )
                val close = PaneStructuralGate.action(
                    "closePane", address, true, currentState.mux, currentState.servers, paneActionInFlight,
                )
                if (focus.visible) {
                    closingRow(getString(R.string.pane_action_focus), focus.enabled, ::focusPane)
                }
                if (rename.visible) {
                    content.addView(sheetActionButton(getString(R.string.pane_action_rename)) {
                        showRenameCurrentPane(dialog, renderActions)
                    }.apply { isEnabled = rename.enabled })
                }
                if (close.visible) {
                    content.addView(
                        armedCloseButton(
                            dialog = dialog,
                            label = getString(R.string.pane_action_close),
                            warning = getString(R.string.pane_close_again),
                            onConfirmed = ::closeCurrentPane,
                        ).apply { isEnabled = close.enabled },
                    )
                }
                listOfNotNull(focus.refusal, rename.refusal, close.refusal).firstOrNull()?.let { refusal ->
                    content.addView(sheetActionButton(hostRefusalText(refusal)) {}.apply { isEnabled = false })
                }
            } else {
                content.addView(
                    sheetActionButton(getString(R.string.pane_action_read_only)) {}.apply { isEnabled = false },
                )
            }
            dialog.setSheetContent(content)
        }
        renderActions()
        dialog.setOnDismissListener { restorePaneFocus() }
        dialog.show()
    }

    private fun bindFind() = with(binding) {
        findQuery.addTextChangedListener(simpleTextWatcher { applyFindHighlights(resetCursor = true) })
        findPrevious.setOnClickListener { stepFind(-1) }
        findNext.setOnClickListener { stepFind(1) }
        findClose.setOnClickListener { closeFind() }
        findQuery.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) {
                false
            } else {
                stepFind(if (event?.isShiftPressed == true) -1 else 1)
                true
            }
        }
        findQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeFind()
                true
            } else {
                false
            }
        }
    }

    private fun openFind() = with(binding) {
        if (directTyping) setDirectTyping(false)
        if (!showDrawer(ComposerDrawer.NONE)) return@with
        followingOutput = false
        paneHeader.isVisible = false
        findBar.isVisible = true
        applyFindHighlights(resetCursor = true)
        focusWithKeyboard(findQuery)
    }

    /**
     * Focus a field and raise the keyboard once this window can take it. "Find in output" is
     * chosen on the pane-actions sheet, whose window still holds focus while it dismisses, so a
     * direct showSoftInput there left the field unfocused and the keyboard closed (S25 Ultra,
     * 2026-09-10). The request waits for the activity window to regain focus when it must.
     */
    private fun focusWithKeyboard(view: View) {
        pendingKeyboardFocus = null
        view.requestFocus()
        if (hasWindowFocus()) {
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        } else {
            pendingKeyboardFocus = view
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val view = pendingKeyboardFocus ?: return
        pendingKeyboardFocus = null
        view.requestFocus()
        WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeFind() = with(binding) {
        findBar.isVisible = false
        paneHeader.isVisible = !zenMode
        findQuery.text?.clear()
        findMatches = emptyList()
        findCursor = -1
        renderTerminalContent(renderedTerminalText)
        restorePaneFocus()
    }

    private fun stepFind(delta: Int) {
        findCursor = OutputFind.step(findMatches.size, findCursor, delta)
        applyFindHighlights(resetCursor = false)
    }

    private fun applyFindHighlights(resetCursor: Boolean) = with(binding) {
        val query = findQuery.text?.toString().orEmpty()
        val matches = OutputFind.matches(renderedTerminalText, query)
        if (resetCursor || matches != findMatches) findCursor = if (matches.isEmpty()) -1 else 0
        findMatches = matches
        if (findCursor !in matches.indices) findCursor = if (matches.isEmpty()) -1 else 0
        findCount.text = if (findCursor < 0) {
            getString(R.string.pane_find_count_empty)
        } else {
            getString(R.string.pane_find_count, findCursor + 1, matches.size)
        }
        findPrevious.isEnabled = matches.isNotEmpty()
        findNext.isEnabled = matches.isNotEmpty()
        if (query.isEmpty()) {
            renderTerminalContent(renderedTerminalText)
            return@with
        }
        val highlighted = SpannableString(renderedTerminalText)
        matches.forEach { match ->
            highlighted.setSpan(
                BackgroundColorSpan(getColor(R.color.collie_find_match)),
                match.start,
                match.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        matches.getOrNull(findCursor)?.let { match ->
            highlighted.setSpan(
                BackgroundColorSpan(getColor(R.color.collie_find_current)),
                match.start,
                match.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        renderTerminalContent(highlighted)
        val selected = matches.getOrNull(findCursor) ?: return@with
        terminalText.post { scrollToCurrentFindMatch(selected) }
    }

    private fun renderTerminalContent(highlighted: CharSequence) = with(binding) {
        val wrap = displayPreferences.getBoolean(PREF_WRAP, true)
        // The newest tables retain independent horizontal panning. Bounding the split prevents a
        // large coding transcript from recreating dozens of nested scroll containers every poll.
        val runs = if (wrap) TerminalTableRuns.find(highlighted).takeLast(MAX_PANNABLE_TABLE_RUNS) else emptyList()
        terminalHorizontalScroll.isVisible = runs.isEmpty()
        terminalBlockContent.isVisible = runs.isNotEmpty()
        val previousPan = renderedTerminalBlocks.mapNotNull { block ->
            (block.host as? android.widget.HorizontalScrollView)?.let { block.text.text.toString() to it.scrollX }
        }.toMap()
        terminalBlockContent.removeAllViews()
        renderedTerminalBlocks = emptyList()
        if (runs.isEmpty()) {
            if (highlighted is PrecomputedTextCompat) {
                TextViewCompat.setPrecomputedText(terminalText, highlighted)
            } else {
                terminalText.text = highlighted
            }
            return@with
        }
        // Visible split blocks own accessibility in this mode; avoid measuring the same full text
        // a second time in the hidden flat view.
        terminalText.text = ""

        val blocks = mutableListOf<RenderedTerminalBlock>()
        var cursor = 0
        fun addBlock(start: Int, end: Int, pans: Boolean) {
            if (start >= end) return
            val text = highlighted.subSequence(start, end)
            val output = terminalBlockText(text, pans)
            val host: View = if (pans) {
                android.widget.HorizontalScrollView(this@PaneActivity).apply {
                    isFillViewport = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(output)
                    previousPan[text.toString()]?.let { offset -> post { scrollTo(offset, 0) } }
                }
            } else {
                output
            }
            terminalBlockContent.addView(
                host,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            blocks += RenderedTerminalBlock(start, end, host, output)
        }
        runs.forEach { run ->
            addBlock(cursor, run.start, pans = false)
            addBlock(run.start, run.endExclusive, pans = true)
            cursor = run.endExclusive
        }
        addBlock(cursor, highlighted.length, pans = false)
        renderedTerminalBlocks = blocks
    }

    private fun terminalBlockText(text: CharSequence, pans: Boolean) = TextView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            if (pans) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        includeFontPadding = false
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setLineSpacing(0f, 1.25f)
        setHorizontallyScrolling(pans)
        typeface = Typeface.MONOSPACE
        textSize = nativePreferences.terminalFontSize.toFloat()
        setTextColor(if (isLightTheme()) LIGHT_MIRROR_FOREGROUND else DARK_MIRROR_FOREGROUND)
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
        this.text = text
        bindTerminalTap(this)
    }

    private fun bindTerminalTap(view: TextView) {
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val text = view.text
                val offset = view.getOffsetForPosition(event.x, event.y).coerceIn(0, text.length)
                val onLink = (text as? Spanned)?.getSpans(offset, offset, URLSpan::class.java)?.isNotEmpty() == true
                terminalTapBlocked = onLink || view.selectionStart != view.selectionEnd
            }
            false
        }
        view.setOnClickListener {
            if (terminalTapBlocked || view.selectionStart != view.selectionEnd) {
                terminalTapBlocked = false
                return@setOnClickListener
            }
            if (displayPreferences.getBoolean(PREF_TAP_TO_TYPE, true) && terminalWriteBlock() == null &&
                semanticSurface?.ownsKeyboard != true && noEchoPrompt == null) {
                binding.replyInput.requestFocus()
                WindowCompat.getInsetsController(window, binding.replyInput)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun terminalWriteBlock(): PaneWriteBlock? =
        if (::address.isInitialized) PaneWriteGate.block(address, currentState) else PaneWriteBlock.ReadOnly

    private fun noEchoSignature(pane: com.lateapex.collie.network.PaneReadResponse?, prompt: String?): String? =
        if (pane == null || prompt == null) null else "${pane.revision}\u0000$prompt"

    private fun scrollToCurrentFindMatch(match: OutputFind.Match? = findMatches.getOrNull(findCursor)) {
        val selected = match ?: return
        renderedTerminalBlocks.firstOrNull { selected.start in it.start until it.endExclusive }?.let { block ->
            val layout = block.text.layout ?: return@let
            val line = layout.getLineForOffset(selected.start - block.start)
            binding.terminalScroll.smoothScrollTo(
                0,
                binding.terminalContent.top + binding.terminalBlockContent.paddingTop +
                    block.host.top + layout.getLineTop(line),
            )
            return
        }
        val layout = binding.terminalText.layout ?: return
        val line = layout.getLineForOffset(selected.start)
        binding.terminalScroll.smoothScrollTo(
            0,
            binding.terminalContent.top + layout.getLineTop(line).coerceAtLeast(0),
        )
    }

    private fun openHistory() {
        val historyIntent = HistoryActivity.intent(
            this,
            address,
            binding.paneTitle.text?.toString(),
            intent.nonBlankExtra(EXTRA_AGENT),
        )
        startActivity(historyIntent)
    }

    private fun setZenMode(active: Boolean) = with(binding) {
        zenMode = active
        if (active && findBar.isVisible) closeFind()
        paneHeader.isVisible = !active
        semanticPanel.isVisible = !active && semanticSurface != null
        renderedTabStrip = null
        renderPaneNavigation(currentState)
        composerChrome.isVisible = !active && (currentState.pane != null || !currentState.loading)
        newOutputButton.text = if (active) "× ${getString(R.string.pane_action_zen)}" else getString(R.string.pane_new_output)
        newOutputButton.contentDescription = if (active) {
            "${getString(R.string.navigate_back)}: ${getString(R.string.pane_action_zen)}"
        } else {
            getString(R.string.pane_new_output_description)
        }
        newOutputButton.isVisible = active || newOutputAvailable
    }

    private fun setNewOutputAvailable(available: Boolean) {
        newOutputAvailable = available
        if (!zenMode) binding.newOutputButton.isVisible = available
    }

    private fun focusPane() {
        mutatePane("setFocus", getString(R.string.pane_action_focus), { repository.focusPane(address) }) {
            showTransientMessage(getString(R.string.pane_focus_done))
            viewModel.refresh()
        }
    }

    private fun showRenameCurrentPane(
        dialog: CollieBottomSheetDialog,
        onBack: () -> Unit,
    ) {
        if (!canMutatePane("renamePane")) return
        val field = EditText(this).apply {
            hint = getString(R.string.pane_rename_hint)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_PANE_LABEL_CHARS))
            setText(currentPaneSummary()?.paneLabel.orEmpty())
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
            addView(sheetActionButton(getString(R.string.pane_rename_save)) {
                val label = field.text?.toString()?.trim().orEmpty()
                val pane = currentPaneSummary()
                val clearedTitle = if (pane == null) {
                    address.paneId
                } else {
                    displayAgentTitle(pane.agent, pane.sessionName)
                        ?: pane.tabLabel?.takeIf(String::isNotBlank)
                        ?: pane.workspaceLabel.takeIf(String::isNotBlank)
                        ?: address.paneId
                }
                dialog.dismiss()
                mutatePane(
                    "renamePane",
                    getString(R.string.pane_action_rename),
                    { repository.renamePane(address, label) },
                ) {
                    binding.paneTitle.text = label.ifEmpty { clearedTitle }
                    showTransientMessage(
                        getString(if (label.isEmpty()) R.string.pane_rename_cleared else R.string.pane_rename_done),
                    )
                    viewModel.refresh()
                }
            })
            addView(sheetActionButton(getString(R.string.pane_action_cancel)) {
                returnToSheetActions(dialog, field, onBack)
            })
        }
        dialog.setSheetContent(content)
        field.post { field.requestFocus() }
    }

    private fun closeCurrentPane() = mutatePane(
        "closePane",
        getString(R.string.pane_action_close),
        { repository.closePane(address) },
    ) { finish() }

    private fun currentPaneSummary(): PaneSummary? = currentState.panes.firstOrNull {
        it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
    }

    private fun canMutatePane(capability: String, target: PaneAddress = address): Boolean {
        val gate = structuralGate(capability, target)
        if (gate.enabled) return true
        showTransientMessage(
            gate.refusal?.let(::hostRefusalText)
                ?: getString(if (currentState.canWrite) R.string.pane_action_failed else R.string.pane_action_read_only),
        )
        return false
    }

    private fun mutatePane(
        capability: String,
        action: String,
        request: suspend () -> ApiResult<ActionResponse>,
        onSuccess: () -> Unit,
    ) = mutateStructural(capability, address, action, request, onSuccess)

    private fun mutateStructural(
        capability: String,
        target: PaneAddress,
        action: String,
        request: suspend () -> ApiResult<ActionResponse>,
        onSuccess: () -> Unit,
    ) {
        if (!canMutatePane(capability, target)) return
        paneActionInFlight = true
        lifecycleScope.launch {
            when (val result = request()) {
                is ApiResult.Success -> if (result.value.ok) {
                    onSuccess()
                } else {
                    showTransientMessage(result.value.error ?: getString(R.string.pane_action_failed))
                }
                is ApiResult.NotModified -> showTransientMessage(getString(R.string.pane_action_failed))
                is ApiResult.Failure -> {
                    if (result.error is ApiFailure.Http && result.error.message == "device not paired") {
                        repository.demoteToReadOnly()
                    }
                    val uncertain = result.error is ApiFailure.Timeout || result.error is ApiFailure.Network ||
                        result.error is ApiFailure.Protocol
                    showTransientMessage(
                        if (uncertain) getString(R.string.pane_action_uncertain)
                        else getString(R.string.action_error, action, MainViewModel.describe(resources, result.error)),
                    )
                }
            }
            paneActionInFlight = false
        }
    }

    private fun applyTerminalPreferences() {
        val wrap = displayPreferences.getBoolean(PREF_WRAP, true)
        if (!displayPreferences.getBoolean(PREF_FONT_MIGRATED, false)) {
            if (displayPreferences.contains(PREF_FONT_SIZE)) {
                nativePreferences.terminalFontSize = displayPreferences.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)
            }
            displayPreferences.edit().putBoolean(PREF_FONT_MIGRATED, true).remove(PREF_FONT_SIZE).apply()
        }
        val size = nativePreferences.terminalFontSize
        binding.terminalHorizontalScroll.horizontalPanEnabled = !wrap
        binding.terminalHorizontalScroll.isFillViewport = wrap
        binding.terminalHorizontalScroll.isHorizontalScrollBarEnabled = !wrap
        binding.terminalHorizontalScroll.overScrollMode =
            if (wrap) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_IF_CONTENT_SCROLLS
        binding.terminalText.layoutParams = binding.terminalText.layoutParams.apply {
            width = if (wrap) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.terminalText.setHorizontallyScrolling(!wrap)
        binding.terminalText.movementMethod = LinkMovementMethod.getInstance()
        binding.terminalText.highlightColor = Color.TRANSPARENT
        binding.terminalSurface.setBackgroundColor(if (isLightTheme()) LIGHT_MIRROR_BACKGROUND else DARK_MIRROR_BACKGROUND)
        binding.terminalText.setTextColor(if (isLightTheme()) LIGHT_MIRROR_FOREGROUND else DARK_MIRROR_FOREGROUND)
        binding.terminalText.typeface = Typeface.MONOSPACE
        binding.terminalText.textSize = size.toFloat()
        binding.replyInput.textSize = nativePreferences.draftFontSize.toFloat()
        binding.fontSizeValue.text = getString(R.string.pane_display_size_value, size)
        binding.fontSmallerButton.isEnabled = size > MIN_FONT_SIZE
        binding.fontLargerButton.isEnabled = size < MAX_FONT_SIZE
        if (renderedTerminalText.isNotEmpty()) applyFindHighlights(resetCursor = false)
    }

    private fun isLightTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private fun stepFontSize(delta: Int) {
        val value = (nativePreferences.terminalFontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        nativePreferences.terminalFontSize = value
        applyTerminalPreferences()
    }

    private fun showTransientMessage(message: String) {
        binding.errorText.text = message
        binding.errorText.setTextColor(getColor(R.color.collie_muted))
        binding.errorText.isVisible = true
    }

    private fun recoverFromClosedPane(state: PaneUiState): Boolean {
        if (closedPaneRedirected) return true
        val present = state.panes.any {
            it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
        }
        if (!panePresenceTracker.shouldEvict(state.topologyKnown, present)) return false
        closedPaneRedirected = true
        Toast.makeText(this, R.string.pane_closed, Toast.LENGTH_SHORT).show()
        repository.selectScope(address.scope)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
        return true
    }

    private fun restorePaneFocus() {
        binding.root.requestFocus()
        keyboardStripFold = null
        renderedTabStrip = null
        renderPaneNavigation(currentState)
        binding.switcherHandle.isVisible = !directTyping && currentState.panes.isNotEmpty()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun outlinedButton(label: String) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        minHeight = dp(44)
        setTextColor(getColor(R.color.collie_foreground))
    }

    private fun sheetActionButton(label: String, action: () -> Unit) = outlinedButton(label).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
    }

    /** A destructive sheet row arms in place; only the second tap crosses the guarded write path. */
    private fun armedCloseButton(
        dialog: CollieBottomSheetDialog,
        label: String,
        warning: String,
        onConfirmed: () -> Unit,
    ): MaterialButton {
        var armed = false
        lateinit var button: MaterialButton
        button = sheetActionButton(label) {
            if (!armed) {
                armed = true
                button.text = warning
                button.contentDescription = warning
                button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.collie_destructive))
                button.setTextColor(getColor(R.color.collie_on_destructive))
            } else {
                dialog.dismiss()
                onConfirmed()
            }
        }
        return button
    }

    private fun tabCloseConfirmLabel(tab: TabSummary): String = if (tab.paneCount > 0) {
        resources.getQuantityString(R.plurals.tab_close_again, tab.paneCount, tab.paneCount)
    } else {
        getString(R.string.tab_close_again_plain)
    }

    private fun returnToSheetActions(
        dialog: CollieBottomSheetDialog,
        field: EditText,
        renderActions: () -> Unit,
    ) {
        field.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(field.windowToken, 0)
        renderActions()
        dialog.findViewById<View>(R.id.collie_sheet_content)?.requestFocus()
    }

    private fun MaterialButton.applySelectedState(selected: Boolean) {
        isSelected = selected
        if (selected) {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.collie_primary))
            setTextColor(getColor(R.color.collie_on_primary))
        }
    }

    private fun messageText(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.collie_muted))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun simpleTextWatcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = onChange(value?.toString().orEmpty())
        override fun afterTextChanged(value: android.text.Editable?) = Unit
    }

    private fun statusColour(status: String): Int = getColor(
        when (status) {
            "blocked" -> R.color.collie_blocked
            "working" -> R.color.collie_working
            "done" -> R.color.collie_done
            "idle" -> R.color.collie_idle
            else -> R.color.collie_unknown
        },
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Intent.nonBlankExtra(name: String): String? = getStringExtra(name)?.trim()?.takeIf(String::isNotEmpty)

    private enum class ComposerDrawer { NONE, KEYS, QUICK, DISPLAY }

    private data class PendingDestructiveSend(val draft: String, val reason: String)
    private data class PendingReplyAttempt(val text: String, val revision: Long?, val paneText: String?)
    private data class PendingSent(val text: String, val revision: Long?, val paneText: String?)
    private data class PendingKeyEcho(
        val button: MaterialButton,
        val label: CharSequence,
        val contentDescription: CharSequence?,
        val keys: List<String>,
        var accepted: Boolean = false,
    )
    private data class PaneScrollAnchor(val height: Int, val top: Int)
    private data class RenderedTerminalBlock(
        val start: Int,
        val endExclusive: Int,
        val host: View,
        val text: TextView,
    )

    companion object {
        private const val MAX_PANNABLE_TABLE_RUNS = 1
        private const val STATUS_UNKNOWN = "unknown"
        private const val DISPLAY_PREFERENCES = "collie_pane_display"
        private const val PREF_WRAP = "wrap_lines"
        private const val PREF_TAP_TO_TYPE = "tap_to_type"
        private const val PREF_RAW = "raw_terminal"
        private const val PREF_FONT_SIZE = "terminal_font_size"
        private const val PREF_FONT_MIGRATED = "terminal_font_migrated_to_native"
        private const val MIN_FONT_SIZE = 9
        private const val MAX_FONT_SIZE = 16
        private const val DEFAULT_FONT_SIZE = 10
        private const val MAX_PANE_LABEL_CHARS = 120
        private const val SWITCHER_PEEK_DP = 120
        private const val SWITCHER_PREVIEW_HEIGHT_DP = 320
        private const val SWITCHER_SETTLE_MS = 140L
        private const val USER_SCROLL_SETTLE_MS = 650L
        private const val CONFIRM_TIMEOUT_MS = 3_000L
        private const val KEY_QUEUE_DISCARD_CONFIRMATION = "discard"
        private const val SEMANTIC_CONTROL_TAG = "semantic_control"
        private const val QUICK_SUCCESS_MS = 650L
        private const val PENDING_SENT_TIMEOUT_MS = 6_000L
        private const val REPEAT_HOLD_MS = 350L
        private const val REPEAT_INTERVAL_MS = 90L
        private const val MAX_REPEAT_KEYS = 25
        private const val KEY_ECHO_TIMEOUT_MS = 3_000L
        private const val KEY_ECHO_DONE_MS = 650L
        private const val KEY_QUEUE_CLEAR_TAG = "key_queue_clear"
        private val DARK_MIRROR_BACKGROUND = Color.rgb(10, 10, 10)
        private val DARK_MIRROR_FOREGROUND = Color.rgb(250, 250, 250)
        private val LIGHT_MIRROR_BACKGROUND = Color.rgb(245, 245, 245)
        private val LIGHT_MIRROR_FOREGROUND = Color.rgb(5, 5, 5)
        private val REPEATABLE_KEYS = setOf("Up", "Down", "Left", "Right")
        private val DANGER_KEYS = setOf("ctrl+c", "ctrl+d", "ctrl+z")
        const val EXTRA_PANE_ID = "pane_id"
        const val EXTRA_HOST = "host"
        const val EXTRA_SESSION = "session"
        const val EXTRA_TITLE = "title"
        const val EXTRA_META = "meta"
        const val EXTRA_AGENT = "agent"
        const val EXTRA_CWD = "cwd"
        const val EXTRA_TAB_LABEL = "tab_label"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FRESH_PANE = "fresh_pane"
        private const val STATE_FRESH_PANE_SEEN = "fresh_pane_seen"

        internal fun filterAgentCommands(commands: List<AgentCommand>, query: String): List<AgentCommand> {
            val normalized = query.trim()
            return if (normalized.isEmpty()) {
                commands.filter(AgentCommand::common)
            } else {
                commands.filter {
                    it.command.contains(normalized, ignoreCase = true) ||
                        it.description.contains(normalized, ignoreCase = true)
                }
            }
        }

        internal fun shouldCommitRepeatKeys(engaged: Boolean, actionMasked: Int): Boolean =
            engaged && actionMasked == MotionEvent.ACTION_UP
    }
}

internal enum class PaneKeyModifier(@androidx.annotation.StringRes val labelRes: Int, val wire: String) {
    SHIFT(R.string.pane_key_shift, "shift"),
    CTRL(R.string.pane_key_ctrl, "ctrl"),
    ALT(R.string.pane_key_alt, "alt"),
}

internal enum class PaneModifierMode { OFF, ONCE, LOCKED }

internal enum class PaneKeysSegment { KEYS, DIGITS }

/** Tracks the web fresh-pane bootstrap: unseen creates may lag one snapshot, seen panes may not. */
internal class PanePresenceTracker(
    private val fresh: Boolean,
    seen: Boolean = false,
) {
    var wasSeen: Boolean = seen
        private set

    fun shouldEvict(topologyKnown: Boolean, present: Boolean): Boolean {
        if (!topologyKnown) return false
        if (present) {
            wasSeen = true
            return false
        }
        return !fresh || wasSeen
    }
}

/** Local review queue; only [PaneViewModel.sendKeySequence] crosses the guarded write boundary. */
internal class PaneKeyQueue {
    private val queue = mutableListOf<String>()
    private val modifiers = PaneKeyModifier.entries.associateWith { PaneModifierMode.OFF }.toMutableMap()

    val staged: List<String> get() = queue.toList()
    val activeModifiers: List<PaneKeyModifier>
        get() = CANONICAL_MODIFIER_ORDER.filter { mode(it) != PaneModifierMode.OFF }
    val composing: Boolean get() = queue.isNotEmpty() || activeModifiers.isNotEmpty()

    fun mode(modifier: PaneKeyModifier): PaneModifierMode = modifiers.getValue(modifier)

    fun cycle(modifier: PaneKeyModifier) {
        modifiers[modifier] = when (mode(modifier)) {
            PaneModifierMode.OFF -> PaneModifierMode.ONCE
            PaneModifierMode.ONCE -> PaneModifierMode.LOCKED
            PaneModifierMode.LOCKED -> PaneModifierMode.OFF
        }
    }

    /** Returns keys to fire immediately, or null when they were staged for review. */
    fun press(keys: List<String>): List<String>? {
        if (!composing) return keys
        val active = activeModifiers.map(PaneKeyModifier::wire)
        queue += keys.map { key ->
            if (active.isEmpty() || '+' in key) key else "${active.joinToString("+")}+$key"
        }
        settleOnceModifiers()
        return null
    }

    fun take(): List<String> {
        val result = queue.toList()
        queue.clear()
        settleOnceModifiers()
        return result
    }

    fun removeAt(index: Int): Boolean {
        if (index !in queue.indices) return false
        queue.removeAt(index)
        return true
    }

    /** Stages the final printable non-space ASCII character, matching the web chord input. */
    fun pushBase(raw: String): Boolean {
        val base = raw.lastOrNull()?.lowercaseChar()?.takeIf { it.code in 0x21..0x7e } ?: return false
        val active = activeModifiers.map(PaneKeyModifier::wire)
        queue += if (active.isEmpty()) base.toString() else "${active.joinToString("+")}+$base"
        settleOnceModifiers()
        return true
    }

    fun clear() {
        queue.clear()
        PaneKeyModifier.entries.forEach { modifiers[it] = PaneModifierMode.OFF }
    }

    private fun settleOnceModifiers() {
        PaneKeyModifier.entries.forEach {
            if (mode(it) == PaneModifierMode.ONCE) modifiers[it] = PaneModifierMode.OFF
        }
    }

    private companion object {
        val CANONICAL_MODIFIER_ORDER = listOf(PaneKeyModifier.CTRL, PaneKeyModifier.ALT, PaneKeyModifier.SHIFT)
    }
}
