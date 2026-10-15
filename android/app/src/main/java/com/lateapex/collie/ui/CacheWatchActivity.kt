package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.CacheRule
import com.lateapex.collie.network.PaneCacheReading
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/** Read-only cache/model/git facts and the lead-owned alert preference for one exact pane address. */
class CacheWatchActivity : AppCompatActivity() {
    private lateinit var viewModel: CacheWatchViewModel
    private lateinit var content: LinearLayout
    private lateinit var timing: TextView
    private lateinit var watchSwitch: MaterialSwitch
    private lateinit var watchHint: TextView
    private lateinit var watchThreshold: TextView
    private lateinit var modelFact: TextView
    private lateinit var gitFact: TextView
    private lateinit var sourceFact: TextView
    private lateinit var error: TextView
    private val renderGeneration = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retirementRegistration: AutoCloseable? = null
    private var stopped = false
    private var rendered: CacheWatchScreenState = CacheWatchScreenState()
    private var displayedSnapshotAt: Long? = null
    private var receivedElapsedAt: Long = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (viewModel.isCurrentPresentation(rendered)) renderTiming(rendered)
            content.postDelayed(this, 1_000L)
        }
    }
    private val refreshPoll = object : Runnable {
        override fun run() {
            viewModel.refresh()
            content.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        val paneId = intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.trim()?.takeIf(String::isNotEmpty) ?: run {
            finish()
            return
        }
        val address = PaneAddress(
            Scope(
                host = intent.getStringExtra(PaneActivity.EXTRA_HOST)?.trim()?.takeIf(String::isNotEmpty),
                session = intent.getStringExtra(PaneActivity.EXTRA_SESSION)?.trim()?.takeIf(String::isNotEmpty),
            ),
            paneId,
        )
        intent.putExtra(CacheWatchViewModel.KEY_PANE_ID, paneId)
        intent.putExtra(CacheWatchViewModel.KEY_HOST, address.scope.host)
        intent.putExtra(CacheWatchViewModel.KEY_SESSION, address.scope.session)
        viewModel = ViewModelProvider(
            this,
            SavedStateViewModelFactory(application, this, intent.extras),
        )[CacheWatchViewModel::class.java]
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        header.addView(actionText(getString(R.string.cache_back)) { finish() },
            LinearLayout.LayoutParams(dp(72), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.cache_title)
            textSize = 18f
            setTextColor(getColor(R.color.collie_foreground))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionText(getString(R.string.cache_refresh)) { viewModel.refresh() },
            LinearLayout.LayoutParams(dp(88), dp(48)))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.applyAppTypeface()
        window.applySafeContentInsets(root)
        content.addView(TextView(this).apply {
            text = address.paneId + listOfNotNull(address.scope.host, address.scope.session).joinToString(" · ", prefix = " · ")
            textSize = 13f
            setTextColor(getColor(R.color.collie_muted))
        }.withVerticalPadding())
        error = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.collie_error))
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(error)
        addSectionTitle(getString(R.string.cache_section_cache))
        timing = addFact(getString(R.string.cache_loading))
        addFact(getString(R.string.cache_no_billing_estimate))
        addSectionTitle(getString(R.string.cache_section_model_git))
        modelFact = addFact(getString(R.string.cache_model_unknown))
        gitFact = addFact(getString(R.string.cache_git_unknown))
        addSectionTitle(getString(R.string.cache_section_source))
        sourceFact = addFact(getString(R.string.cache_source_unknown))
        addSectionTitle(getString(R.string.cache_section_watch))
        watchSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.cache_watch_label)
            minHeight = dp(48)
        }
        content.addView(watchSwitch)
        watchHint = addFact(getString(R.string.cache_watch_loading))
        watchThreshold = addFact(getString(R.string.cache_watch_threshold_unknown))
        addFact(getString(R.string.cache_delivery_limit))
        retirementRegistration = viewModel.onRetired { retired ->
            renderGeneration.accumulateAndGet(retired.generation) { current, next -> maxOf(current, next) }
            wipeAcknowledged(retired)
        }
        viewModel.state.let { flow ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    flow.collect { state ->
                        // Always post: a StateFlow completion may resume its collector inline.
                        // Views/repository checks must run after the VM custody commit releases.
                        mainHandler.post {
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                state.generation >= renderGeneration.get() &&
                                (if (state.actor == null) viewModel.ownsFrame(state) else viewModel.isCurrentPresentation(state))) {
                                renderGeneration.accumulateAndGet(state.generation) { current, next -> maxOf(current, next) }
                                render(state)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (stopped) { stopped = false; viewModel.refresh() }
        content.post(ticker)
        content.postDelayed(refreshPoll, 30_000L)
    }

    override fun onStop() {
        stopped = true
        viewModel.stop()
        content.removeCallbacks(ticker)
        content.removeCallbacks(refreshPoll)
        super.onStop()
    }

    override fun onDestroy() {
        retirementRegistration?.close()
        retirementRegistration = null
        super.onDestroy()
    }

    /** The repository callback returns only after the copied on-screen facts are retired. */
    private fun wipeAcknowledged(retired: CacheWatchScreenState) {
        val wipe = Runnable {
            if (renderGeneration.get() == retired.generation) {
                displayedSnapshotAt = null
                receivedElapsedAt = 0
                render(retired)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) { wipe.run(); return }
        val done = CountDownLatch(1)
        check(mainHandler.post { try { wipe.run() } finally { done.countDown() } }) {
            "Cache details could not acknowledge retirement"
        }
        var interrupted = false
        while (true) {
            try { done.await(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun render(state: CacheWatchScreenState) {
        rendered = state
        state.snapshotAt?.let { at ->
            if (displayedSnapshotAt != at) {
                displayedSnapshotAt = at
                receivedElapsedAt = SystemClock.elapsedRealtime()
            }
        }
        error.text = state.error?.let(::messageFor).orEmpty()
        error.isVisible = state.error != null
        watchSwitch.isEnabled = CacheWatchControlState.switchEnabled(state)
        watchSwitch.setOnCheckedChangeListener(null)
        watchSwitch.isChecked = state.watch?.on == true
        watchSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isEnabled && rendered === state && !state.busy && state.watch?.on != checked) viewModel.setWatching(checked, state)
        }
        watchHint.text = when {
            state.loading -> getString(R.string.cache_watch_loading)
            state.watch == null -> getString(R.string.cache_watch_unavailable)
            !state.watch.watchable && state.watch.on -> getString(R.string.cache_watch_stale_remove)
            !state.watch.watchable -> getString(R.string.cache_watch_unavailable)
            state.watch.global -> getString(R.string.cache_watch_global)
            else -> getString(R.string.cache_watch_device_preference)
        }
        watchThreshold.isVisible = state.watch?.watchable == true
        watchThreshold.text = state.watch?.let { getString(R.string.cache_watch_threshold, duration(it.warnSeconds)) }
            ?: getString(R.string.cache_watch_threshold_unknown)
        modelFact.text = state.pane?.model?.takeIf(String::isNotBlank)
            ?.let { getString(R.string.cache_model_value, it) }
            ?: getString(R.string.cache_model_unknown)
        gitFact.text = state.pane?.gitHead?.let(CacheContextPresenter::gitFact)?.let { fact ->
            getString(if (fact.kind == "branch") R.string.cache_git_branch else R.string.cache_git_detached, fact.value)
        } ?: getString(R.string.cache_git_unknown)
        val sourceText = state.pane?.cache?.let { cache -> sourceDescription(cache, state.rule, state.catalogUnavailable) }
            ?: getString(R.string.cache_source_unknown)
        sourceFact.text = sourceText
        renderTiming(state)
    }

    private fun renderTiming(state: CacheWatchScreenState) {
        val cache = state.pane?.cache
        val serverAt = state.snapshotAt
        if (cache == null || serverAt == null) {
            timing.text = getString(if (state.loading) R.string.cache_loading else R.string.cache_unknown)
            return
        }
        val projected = CacheContextPresenter.timing(cache, serverAt)
        if (projected == null) {
            timing.text = getString(R.string.cache_unknown)
            return
        }
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - receivedElapsedAt).coerceAtLeast(0) / 1000L)
        val remaining = projected.remainingSeconds?.minus(elapsedSeconds)?.coerceAtLeast(0)
        val ttl = projected.ttlSeconds?.let { getString(R.string.cache_ttl_value, duration(it)) }
            ?: getString(R.string.cache_ttl_unknown)
        val countdown = remaining?.let { getString(R.string.cache_expires_value, duration(it)) }
            ?: when (projected.state) {
                "cold" -> getString(R.string.cache_expired_or_cold)
                else -> getString(R.string.cache_countdown_unknown)
            }
        val reason = cache.coldReason?.takeIf { it in setOf("observed", "expired", "reset") }
            ?.let { getString(R.string.cache_cold_reason, getString(when (it) {
                "observed" -> R.string.cache_cold_observed
                "expired" -> R.string.cache_cold_expired
                else -> R.string.cache_cold_reset
            })) }.orEmpty()
        val reset = cache.reset?.takeIf { it.label?.isNotBlank() == true }
            ?.let { getString(R.string.cache_reset_fact, it.label.orEmpty()) }.orEmpty()
        val observations = buildList {
            CacheContextPresenter.ageSeconds(cache.lastRequestAt?.toDouble(), serverAt)?.let {
                add(getString(R.string.cache_last_request_age, duration(it)))
            }
            CacheContextPresenter.ageSeconds(cache.measuredAt, serverAt)?.let {
                add(getString(R.string.cache_measured_age, duration(it)))
            }
        }
        timing.text = getString(
            R.string.cache_timing_value,
            getString(when (projected.state) {
                "warm" -> R.string.cache_state_warm
                "expiring" -> R.string.cache_state_expiring
                "cold" -> R.string.cache_state_cold
                else -> R.string.cache_state_unknown
            }),
            ttl,
            countdown,
            getString(when (projected.confidence) {
                "observed" -> R.string.cache_confidence_observed
                "reported" -> R.string.cache_confidence_reported
                "documented" -> R.string.cache_confidence_documented
                else -> R.string.cache_confidence_unknown
            }),
        ) + reason + reset + if (observations.isEmpty()) {
            "\n" + getString(R.string.cache_observation_time_unknown)
        } else "\n" + observations.joinToString(" · ")
    }

    private fun sourceDescription(cache: PaneCacheReading, rule: CacheRule?, unavailable: Boolean): String {
        if (viewModel.address.scope.host != null) {
            val peer = CacheContextPresenter.peerSource(cache)
            if (peer == null) return getString(R.string.cache_peer_catalog_unavailable, cache.ruleId.orEmpty(), confidenceLabel(cache.confidence), "")
            return getString(
                R.string.cache_peer_catalog_unavailable,
                peer.label,
                confidenceLabel(peer.confidence),
                if (peer.overridden) getString(R.string.cache_peer_override) else "",
            )
        }
        if (cache.ruleId.isNullOrBlank()) return getString(R.string.cache_source_unknown)
        if (unavailable || rule == null) return getString(R.string.cache_catalog_unavailable, cache.ruleId)
        val overridden = rule.overridden
        if (cache.overridden == true && overridden == null) {
            return getString(R.string.cache_override_source_unavailable, cache.ruleId)
        }
        val source = if (cache.overridden == true && overridden != null) {
            getString(
                R.string.cache_override_source,
                duration(overridden.ttlSeconds),
                overridden.sourceUrl,
                overridden.retrieved,
            )
        } else {
            getString(
                R.string.cache_rule_source,
                rule.sourceTitle,
                duration(rule.ttlSeconds),
                rule.sourceUrl,
                rule.retrievedAt,
            )
        }
        return getString(
            R.string.cache_rule_value,
            rule.label,
            confidenceLabel(cache.confidence),
            confidenceLabel(rule.confidence),
            source,
        ) +
            (rule.note?.takeIf(String::isNotBlank)?.let { "\n$it" } ?: "")
    }

    private fun messageFor(message: CacheWatchMessage): String = getString(when (message) {
        CacheWatchMessage.NOT_CONNECTED -> R.string.cache_not_connected
        CacheWatchMessage.UNSUPPORTED -> R.string.cache_unsupported
        CacheWatchMessage.NOT_FOUND -> R.string.cache_pane_not_found
        CacheWatchMessage.UNAVAILABLE -> R.string.cache_unavailable
        CacheWatchMessage.STALE -> R.string.cache_scope_changed
        CacheWatchMessage.MALFORMED -> R.string.cache_malformed
    })

    private fun confidenceLabel(value: String?): String = getString(when (value) {
        "observed" -> R.string.cache_confidence_observed
        "reported" -> R.string.cache_confidence_reported
        "documented" -> R.string.cache_confidence_documented
        else -> R.string.cache_confidence_unknown
    })

    private fun duration(seconds: Long): String = when {
        seconds >= 86_400 -> getString(R.string.cache_duration_days, seconds / 86_400, (seconds % 86_400) / 3600)
        seconds >= 3600 -> getString(R.string.cache_duration_hours, seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> getString(R.string.cache_duration_minutes, seconds / 60, seconds % 60)
        else -> getString(R.string.cache_duration_seconds, seconds)
    }

    private fun addSectionTitle(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(getColor(R.color.collie_foreground))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun addFact(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.collie_muted))
        setPadding(0, dp(8), 0, dp(8))
        content.addView(this)
    }

    private fun actionText(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        minHeight = dp(48)
        isFocusable = true
        isClickable = true
        contentDescription = label
        setTextColor(getColor(R.color.collie_primary))
        setOnClickListener { action() }
    }

    private fun TextView.withVerticalPadding(): TextView = apply { setPadding(0, dp(8), 0, dp(8)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context, address: PaneAddress): Intent = Intent(context, CacheWatchActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        }
    }
}
