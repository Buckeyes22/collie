package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivityHistoryBinding
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PaneHistoryResponse
import com.lateapex.collie.network.TranscriptEntry
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/** Read-only transcript reader. It intentionally never joins the live-pane polling loop. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var address: PaneAddress
    private var entries = emptyList<TranscriptEntry>()
    private var hasMore = false
    private var fileTruncated = false
    private var total = 0
    private var renderCount = INITIAL_RENDER
    private var loading = false
    private var loadedOnce = false
    private var initialScrollComplete = false
    private var findOpen = false
    private var findHits = emptyList<HistorySearchHit>()
    private var findStops = emptyList<String>()
    private var findCursor = -1
    private var focusedUuid: String? = null
    private var userTurnIds = emptyList<String>()
    private val turnViews = linkedMapOf<String, View>()
    private val historyTextTargets = linkedMapOf<HistorySearchKey, HistoryTextTarget>()
    private val expandedTools = mutableSetOf<Pair<String, Int>>()
    private val renderer by lazy {
        HistoryTurnRenderer(
            this,
            intent.getStringExtra(EXTRA_AGENT).orEmpty(),
            expandedTools,
            ::toggleTool,
            historyTextTargets::put,
        )
    }

    private fun toggleTool(uuid: String, partIndex: Int) {
        val key = uuid to partIndex
        if (key in expandedTools) expandedTools -= key else expandedTools += key
        renderEntries()
    }
    private lateinit var retainedReader: HistoryReaderViewModel
    private var restoringFindState = false
    private var pendingRestoreAnchor: HistoryReadAnchor? = null
    private var pendingRestoredRenderCount: Int? = null
    private val repository get() = (application as CollieApplication).container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.applySafeContentInsets(binding.root)

        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.trim()?.takeIf(String::isNotEmpty) ?: run {
            finish()
            return
        }
        address = PaneAddress(
            Scope(
                host = intent.getStringExtra(EXTRA_HOST)?.trim()?.takeIf(String::isNotEmpty),
                session = intent.getStringExtra(EXTRA_SESSION)?.trim()?.takeIf(String::isNotEmpty),
            ),
            paneId,
        )
        retainedReader = ViewModelProvider(this)[HistoryReaderViewModel::class.java]
        restoreReaderState(savedInstanceState)
        binding.historySubtitle.text = intent.getStringExtra(EXTRA_TITLE)?.trim()?.takeIf(String::isNotEmpty) ?: paneId
        binding.historyBackButton.setOnClickListener { finish() }
        binding.historyClose.setOnClickListener { finish() }
        binding.historyFindOpen.setOnClickListener(::openFind)
        binding.historyFindClose.setOnClickListener { closeFind() }
        binding.historyLoadOlder.setOnClickListener { growUpward() }
        restoringFindState = true
        binding.historyFindQuery.setText(retainedReader.findQuery)
        restoringFindState = false
        binding.historyFindQuery.addTextChangedListener(simpleTextWatcher {
            retainedReader.findQuery = it
            if (!restoringFindState) updateFind(resetCursor = true)
        })
        binding.historyFindPrevious.setOnClickListener { stepFind(-1) }
        binding.historyFindNext.setOnClickListener { stepFind(1) }
        binding.historyFindQuery.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && !enter) {
                return@setOnEditorActionListener false
            }
            stepFind(if (event?.isShiftPressed == true) -1 else 1)
            true
        }
        binding.historyFindQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP && keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                closeFind()
                true
            } else {
                false
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findOpen) {
                    closeFind()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        binding.historyPreviousUser.setOnClickListener { stepUserTurn(-1) }
        binding.historyNextUser.setOnClickListener { stepUserTurn(1) }
        binding.historyScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (initialScrollComplete && !loading && scrollY < dp(GROW_THRESHOLD_DP)) {
                growUpward()
            }
        }
        binding.root.applyAppTypeface()
        if (entries.isNotEmpty()) {
            renderEntries()
            restoreFindChrome()
        } else {
            renderState(getString(R.string.history_loading))
            restoreFindChrome()
            loadPage(older = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val anchor = captureReadAnchor()
        retainedReader.state = HistoryRetainedState(
            entries = entries,
            hasMore = hasMore,
            fileTruncated = fileTruncated,
            total = total,
            renderCount = renderCount,
            loadedOnce = loadedOnce,
            findOpen = findOpen,
            findQuery = binding.historyFindQuery.text?.toString().orEmpty(),
            findCursor = findCursor,
            focusedUuid = focusedUuid,
            expandedTools = expandedTools.toSet(),
            anchor = anchor,
        )
        outState.putInt(STATE_RENDER_COUNT, renderCount)
        outState.putBoolean(STATE_FIND_OPEN, findOpen)
        outState.putString(STATE_FIND_QUERY, binding.historyFindQuery.text?.toString().orEmpty())
        outState.putInt(STATE_FIND_CURSOR, findCursor)
        outState.putString(STATE_FOCUSED_UUID, focusedUuid)
        if (anchor != null) {
            outState.putString(STATE_ANCHOR_UUID, anchor.uuid)
            outState.putInt(STATE_ANCHOR_OFFSET, anchor.offset)
            outState.putInt(STATE_ANCHOR_TOP, anchor.absoluteTop)
        }
        outState.putStringArrayList(STATE_EXPANDED_UUIDS, ArrayList(expandedTools.map { it.first }))
        outState.putIntegerArrayList(STATE_EXPANDED_INDICES, ArrayList(expandedTools.map { it.second }))
        super.onSaveInstanceState(outState)
    }

    private fun restoreReaderState(savedInstanceState: Bundle?) {
        retainedReader.state?.let { state ->
            entries = state.entries
            hasMore = state.hasMore
            fileTruncated = state.fileTruncated
            total = state.total
            renderCount = state.renderCount
            loadedOnce = state.loadedOnce
            findOpen = state.findOpen
            findCursor = state.findCursor
            focusedUuid = state.focusedUuid
            expandedTools += state.expandedTools
            pendingRestoreAnchor = state.anchor
            retainedReader.findQuery = state.findQuery
            loading = false
            return
        }
        if (savedInstanceState == null) return
        pendingRestoredRenderCount = savedInstanceState.getInt(STATE_RENDER_COUNT, INITIAL_RENDER)
        renderCount = pendingRestoredRenderCount ?: INITIAL_RENDER
        findOpen = savedInstanceState.getBoolean(STATE_FIND_OPEN)
        findCursor = savedInstanceState.getInt(STATE_FIND_CURSOR, -1)
        focusedUuid = savedInstanceState.getString(STATE_FOCUSED_UUID)
        retainedReader.findQuery = savedInstanceState.getString(STATE_FIND_QUERY).orEmpty()
        val uuids = savedInstanceState.getStringArrayList(STATE_EXPANDED_UUIDS).orEmpty()
        val indices = savedInstanceState.getIntegerArrayList(STATE_EXPANDED_INDICES).orEmpty()
        expandedTools += uuids.zip(indices)
        if (savedInstanceState.containsKey(STATE_ANCHOR_TOP)) {
            pendingRestoreAnchor = HistoryReadAnchor(
                uuid = savedInstanceState.getString(STATE_ANCHOR_UUID),
                offset = savedInstanceState.getInt(STATE_ANCHOR_OFFSET),
                absoluteTop = savedInstanceState.getInt(STATE_ANCHOR_TOP),
            )
        }
    }

    private fun restoreFindChrome() {
        binding.historyHeaderNormal.isVisible = !findOpen
        binding.historyHeaderFind.isVisible = findOpen
        binding.historyUserJumps.isVisible = userTurnIds.size > 1 && !findOpen
        if (findOpen) {
            binding.historyFindQuery.requestFocus()
            binding.historyFindQuery.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(binding.historyFindQuery, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun openFind(@Suppress("UNUSED_PARAMETER") ignored: View) {
        findOpen = true
        binding.historyHeaderNormal.isVisible = false
        binding.historyHeaderFind.isVisible = true
        binding.historyUserJumps.isVisible = false
        binding.historyFindQuery.requestFocus()
        binding.historyFindQuery.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.historyFindQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeFind() {
        findOpen = false
        binding.historyFindQuery.text?.clear()
        binding.historyFindQuery.clearFocus()
        binding.historyHeaderFind.isVisible = false
        binding.historyHeaderNormal.isVisible = true
        binding.historyUserJumps.isVisible = userTurnIds.size > 1
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.historyRoot.windowToken, 0)
    }

    private fun loadPage(older: Boolean) {
        if (loading || older && !hasMore) return
        val oldest = entries.firstOrNull()?.uuid
        if (older && oldest == null) return
        val anchor = if (older) captureAnchor() else null
        loading = true
        binding.historyProgress.isVisible = !loadedOnce
        binding.historyLoadOlder.isEnabled = false
        if (loadedOnce) binding.historyLoadOlder.setText(R.string.history_loading)
        lifecycleScope.launch {
            when (val result = repository.history(address, limit = HISTORY_PAGE_SIZE, before = oldest.takeIf { older })) {
                is ApiResult.Success -> applyPage(result.value, older, anchor)
                is ApiResult.Failure -> renderFailure(MainViewModel.describe(resources, result.error))
                is ApiResult.NotModified -> renderFailure(getString(R.string.history_load_failed))
            }
            loading = false
            loadedOnce = true
            binding.historyProgress.isVisible = false
            binding.historyLoadOlder.setText(R.string.history_load_older)
            binding.historyLoadOlder.isEnabled = hasMore || renderCount < entries.size
        }
    }

    private fun applyPage(page: PaneHistoryResponse, older: Boolean, anchor: ScrollAnchor?) {
        if (!page.available) {
            entries = emptyList()
            hasMore = false
            total = 0
            renderEntries()
            renderState(unavailableMessage(page.reason))
            return
        }
        if (older) {
            entries = HistoryPresentation.mergeOlder(entries, page.entries)
            renderCount = min(Int.MAX_VALUE - page.entries.size, renderCount) + page.entries.size
        } else {
            entries = page.entries
            renderCount = pendingRestoredRenderCount?.coerceAtLeast(INITIAL_RENDER) ?: INITIAL_RENDER
            pendingRestoredRenderCount = null
        }
        hasMore = page.hasMore
        fileTruncated = page.fileTruncated
        total = max(max(total, page.total), entries.size)
        renderEntries(anchor = anchor, scrollBottom = !older && pendingRestoreAnchor == null)
    }

    private fun growUpward() {
        if (loading || entries.isEmpty()) return
        if (renderCount < entries.size) {
            val anchor = captureAnchor()
            renderCount = min(renderCount + RENDER_STEP, entries.size)
            renderEntries(anchor = anchor)
        } else if (hasMore) {
            loadPage(older = true)
        }
    }

    private fun captureAnchor(): ScrollAnchor = ScrollAnchor(
        height = binding.historyScroll.getChildAt(0)?.height ?: 0,
        top = binding.historyScroll.scrollY,
    )

    private fun captureReadAnchor(): HistoryReadAnchor? {
        if (entries.isEmpty()) return null
        val top = binding.historyScroll.scrollY
        val visible = turnViews.entries.firstOrNull { (_, view) ->
            val bounds = Rect(0, 0, view.width, view.height)
            binding.historyScroll.offsetDescendantRectToMyCoords(view, bounds)
            bounds.bottom > top
        }
        if (visible == null) return HistoryReadAnchor(null, 0, top)
        val bounds = Rect(0, 0, visible.value.width, visible.value.height)
        binding.historyScroll.offsetDescendantRectToMyCoords(visible.value, bounds)
        return HistoryReadAnchor(visible.key, top - bounds.top, top)
    }

    private fun renderFailure(detail: String) {
        renderState(detail.ifBlank { getString(R.string.history_load_failed) })
        binding.historyLoadOlder.isVisible = hasMore || renderCount < entries.size
    }

    @Suppress("unused") // Kept as a zero-argument entry point for focused rendering tests.
    private fun renderEntries() = renderEntries(anchor = null, scrollBottom = false)

    private fun renderEntries(anchor: ScrollAnchor?, scrollBottom: Boolean = false) = with(binding) {
        historyEntries.removeAllViews()
        turnViews.clear()
        historyTextTargets.clear()
        val start = (entries.size - renderCount).coerceAtLeast(0)
        val shown = entries.subList(start, entries.size)
        historyCount.text = if (total > 0) getString(R.string.history_shown_count, shown.size, total) else ""
        val allRendered = start == 0
        historyLoadOlder.isVisible = entries.isNotEmpty() && (!allRendered || hasMore)
        renderState(
            when {
                entries.isEmpty() -> getString(R.string.history_empty)
                !allRendered || hasMore -> ""
                fileTruncated -> getString(R.string.history_start_clipped)
                else -> getString(R.string.history_start)
            },
        )

        var lastDay = ""
        var lastRole = ""
        shown.forEach { entry ->
            val turn = HistoryPresentation.turn(entry, intent.getStringExtra(EXTRA_AGENT), resources)
            val newDay = turn.day.isNotEmpty() && turn.day != lastDay
            if (newDay) {
                historyEntries.addView(dayDivider(turn.day))
                lastDay = turn.day
            }
            val view = renderer.turnView(turn, showHeader = newDay || turn.role != lastRole)
            turnViews[turn.uuid] = view
            historyEntries.addView(view)
            lastRole = turn.role
        }
        userTurnIds = HistoryNavigation.userTurns(entries)
        historyUserJumps.isVisible = userTurnIds.size > 1 && !findOpen
        historyPreviousUser.isEnabled = userTurnIds.size > 1
        historyNextUser.isEnabled = userTurnIds.size > 1
        updateFind(resetCursor = false)
        updateFocusedTurn()

        val recreationAnchor = pendingRestoreAnchor
        historyScroll.post {
            when {
                anchor != null -> {
                    val nextHeight = historyScroll.getChildAt(0)?.height ?: anchor.height
                    historyScroll.scrollTo(0, anchor.top + (nextHeight - anchor.height).coerceAtLeast(0))
                }
                recreationAnchor != null -> {
                    val restored = recreationAnchor.uuid?.let(turnViews::get)
                    if (restored != null) {
                        val bounds = Rect(0, 0, restored.width, restored.height)
                        historyScroll.offsetDescendantRectToMyCoords(restored, bounds)
                        historyScroll.scrollTo(0, (bounds.top + recreationAnchor.offset).coerceAtLeast(0))
                    } else {
                        historyScroll.scrollTo(0, recreationAnchor.absoluteTop.coerceAtLeast(0))
                    }
                    pendingRestoreAnchor = null
                    retainedReader.state = retainedReader.state?.copy(anchor = null)
                    initialScrollComplete = true
                }
                scrollBottom -> {
                    // A fresh render begins at y=0. Keep auto-growth disabled through the initial
                    // fullScroll callbacks and one following frame, otherwise that transient top
                    // position eagerly consumes every 120-turn window before the bottom settles.
                    initialScrollComplete = false
                    historyScroll.fullScroll(View.FOCUS_DOWN)
                    historyScroll.postOnAnimation {
                        historyScroll.fullScroll(View.FOCUS_DOWN)
                        historyScroll.postOnAnimation { initialScrollComplete = true }
                    }
                }
            }
        }
    }

    private fun dayDivider(day: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(4))
        addView(rule(), LinearLayout.LayoutParams(0, dp(1), 1f))
        addView(TextView(this@HistoryActivity).apply {
            text = day
            textSize = 11f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(8), 0, dp(8), 0)
        })
        addView(rule(), LinearLayout.LayoutParams(0, dp(1), 1f))
    }

    private fun updateFind(resetCursor: Boolean) {
        val query = binding.historyFindQuery.text?.toString().orEmpty()
        findHits = HistoryPresentation.search(entries, query, resources = resources)
        findStops = findHits.map { it.key.entryUuid }.distinct()
        if (resetCursor) {
            findCursor = -1
            focusedUuid = null
            updateFocusedTurn()
        } else {
            findCursor = focusedUuid?.let(findStops::indexOf)?.takeIf { it >= 0 }
                ?: findCursor.takeIf { it in findStops.indices }
                ?: -1
        }
        binding.historyFindCount.text = when {
            query.isBlank() -> ""
            findStops.isEmpty() -> "0/0"
            // Nothing is current until the reader steps; claiming 1/N made the first Next look
            // inert (S25 Ultra walk, 2026-09-11). The web History reads the same.
            findCursor < 0 -> "0/${findStops.size}"
            else -> "${findCursor + 1}/${findStops.size}"
        }
        binding.historyFindPrevious.isEnabled = findStops.isNotEmpty()
        binding.historyFindNext.isEnabled = findStops.isNotEmpty()
        paintFindHits()
    }

    private fun paintFindHits() {
        historyTextTargets.forEach { (key, target) ->
            val matches = findHits.filter { it.key == key }.map(HistorySearchHit::match)
            target.view.text = HistoryHighlight.render(
                target.source,
                matches,
                -1,
                color(R.color.collie_find_match),
                color(R.color.collie_find_current),
            )
        }
    }

    private fun stepFind(delta: Int) {
        if (findStops.isEmpty()) return
        findCursor = HistoryNavigation.step(findStops.size, findCursor, delta)
        val uuid = findStops[findCursor]
        focusedUuid = uuid
        revealEntry(uuid)
        updateFind(resetCursor = false)
        updateFocusedTurn()
        findHits.firstNotNullOfOrNull { hit ->
            historyTextTargets[hit.key]?.takeIf { hit.key.entryUuid == uuid }
        }?.reveal()
        turnViews[uuid]?.let(::scrollToView)
    }

    private fun stepUserTurn(delta: Int) {
        if (userTurnIds.size < 2) return
        val uuid = HistoryPresentation.stepUserTurn(entries, focusedUuid, delta) ?: return
        focusedUuid = uuid
        revealEntry(uuid)
        updateFocusedTurn()
        turnViews[uuid]?.let(::scrollToView)
    }

    private fun revealEntry(uuid: String) {
        val index = entries.indexOfFirst { it.uuid == uuid }
        if (index < 0) return
        val needed = min(entries.size - index + 10, entries.size)
        if (renderCount < needed) {
            renderCount = needed
            renderEntries()
        }
    }

    private fun updateFocusedTurn() {
        turnViews.forEach { (uuid, view) ->
            view.setBackgroundResource(
                if (uuid == focusedUuid) R.drawable.bg_history_focus else R.drawable.bg_history_transparent,
            )
        }
    }

    private fun scrollToView(view: View) {
        binding.historyScroll.post {
            val bounds = Rect(0, 0, view.width, view.height)
            binding.historyScroll.offsetDescendantRectToMyCoords(view, bounds)
            val target = bounds.top - (binding.historyScroll.height - view.height).coerceAtLeast(0) / 2
            binding.historyScroll.smoothScrollTo(0, target.coerceAtLeast(0))
        }
    }

    private fun renderState(message: String) = with(binding.historyState) {
        text = message
        isVisible = message.isNotEmpty()
    }

    private fun unavailableMessage(reason: String?): String = getString(
        when (reason) {
            "disabled" -> R.string.history_unavailable_disabled
            "no-session" -> R.string.history_unavailable_no_session
            "no-log" -> R.string.history_unavailable_no_log
            else -> R.string.history_unavailable
        },
    )

    private fun rule() = View(this).apply { setBackgroundColor(color(R.color.collie_border)) }
    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun simpleTextWatcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) =
            onChange(value?.toString().orEmpty())
        override fun afterTextChanged(value: android.text.Editable?) = Unit
    }

    companion object {
        internal const val INITIAL_RENDER = 60
        internal const val RENDER_STEP = 120
        internal const val HISTORY_PAGE_SIZE = 5_000
        internal const val GROW_THRESHOLD_DP = 800
        private const val EXTRA_PANE_ID = "pane_id"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_AGENT = "agent"
        private const val STATE_RENDER_COUNT = "history_render_count"
        private const val STATE_FIND_OPEN = "history_find_open"
        private const val STATE_FIND_QUERY = "history_find_query"
        private const val STATE_FIND_CURSOR = "history_find_cursor"
        private const val STATE_FOCUSED_UUID = "history_focused_uuid"
        private const val STATE_ANCHOR_UUID = "history_anchor_uuid"
        private const val STATE_ANCHOR_OFFSET = "history_anchor_offset"
        private const val STATE_ANCHOR_TOP = "history_anchor_top"
        private const val STATE_EXPANDED_UUIDS = "history_expanded_uuids"
        private const val STATE_EXPANDED_INDICES = "history_expanded_indices"

        fun intent(context: Context, address: PaneAddress, title: String?, agent: String?): Intent =
            Intent(context, HistoryActivity::class.java).apply {
                putExtra(EXTRA_PANE_ID, address.paneId)
                putExtra(EXTRA_HOST, address.scope.host)
                putExtra(EXTRA_SESSION, address.scope.session)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AGENT, agent)
            }
    }

    private data class ScrollAnchor(val height: Int, val top: Int)
}

internal class HistoryReaderViewModel : ViewModel() {
    var state: HistoryRetainedState? = null
    var findQuery: String = ""
}

internal data class HistoryRetainedState(
    val entries: List<TranscriptEntry>,
    val hasMore: Boolean,
    val fileTruncated: Boolean,
    val total: Int,
    val renderCount: Int,
    val loadedOnce: Boolean,
    val findOpen: Boolean,
    val findQuery: String,
    val findCursor: Int,
    val focusedUuid: String?,
    val expandedTools: Set<Pair<String, Int>>,
    val anchor: HistoryReadAnchor?,
)

internal data class HistoryReadAnchor(
    val uuid: String?,
    val offset: Int,
    val absoluteTop: Int,
)

/** Keeps the history reader and its floating landmarks inside the web route's 768dp column. */
class HistoryMaxWidthFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = resources.getDimensionPixelSize(R.dimen.collie_wide_content_max_width)
        val size = min(View.MeasureSpec.getSize(widthMeasureSpec), cap)
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.getMode(widthMeasureSpec)),
            heightMeasureSpec,
        )
    }
}
