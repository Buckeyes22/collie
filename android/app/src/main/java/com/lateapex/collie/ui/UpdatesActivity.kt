package com.lateapex.collie.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.DeviceWriteAuthorization
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PreflightCheck
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdateRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Native `/settings/updates`, kept structurally equivalent to the web CheckControl + UpdateCard. */
class UpdatesActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var checkDescription: TextView
    private lateinit var checkResult: TextView
    private lateinit var checkButton: MaterialButton
    private lateinit var updateSymbol: AppCompatImageView
    private lateinit var status: TextView
    private lateinit var peers: LinearLayout
    private lateinit var runSection: LinearLayout
    private lateinit var checks: LinearLayout
    private lateinit var preflightToggle: MaterialButton
    private lateinit var preflightBody: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var standardActions: LinearLayout
    private lateinit var blockedReason: TextView
    private lateinit var confirmation: LinearLayout
    private lateinit var confirmationTitle: TextView
    private lateinit var confirmationBody: TextView
    private lateinit var confirmationConfirm: MaterialButton
    private lateinit var confirmationCancel: MaterialButton
    private lateinit var start: MaterialButton
    private lateinit var major: MaterialButton
    private lateinit var dismiss: MaterialButton
    private lateinit var error: TextView
    private var latestState: UpdateCheckResponse? = null
    private var busy = false
    private var checking = false
    private var checkFailed = false
    private var dismissed = false
    private var preflightOpen = false
    private var preflightInitialized = false
    private var logOpen = false
    private var standbyRun: UpdateRun? = null
    private var actionError: String? = null
    private var confirmationAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()

        val root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.collie_background))
        }
        val column = CollieMaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.collie_background))
        }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))
        column.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(checkControl())
        content.addView(updateCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })
        column.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        window.applySafeContentInsets(root)
        root.applyAppTypeface()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    load()
                    val active = UpdateNativePresentation.hasActiveRun(latestState) ||
                        standbyRun?.state in UpdateNativePresentation.activeStates
                    delay(if (active) RUN_POLL_MS else SETTLED_POLL_MS)
                }
            }
        }
    }

    private fun checkControl(): View = card().apply {
        addView(horizontalRow(dp(16), dp(16)).apply {
            gravity = Gravity.TOP
            addView(icon(R.drawable.ic_dashboard_reload), LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(12)
            })
            addView(LinearLayout(this@UpdatesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title(getString(R.string.updates_native_check_title)))
                checkDescription = body(getString(R.string.updates_native_check_prompt))
                addView(checkDescription)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        addDivider()
        addView(horizontalRow(dp(12), dp(12)).apply {
            gravity = Gravity.CENTER_VERTICAL
            checkButton = outlinedButton(R.id.updates_check_button, getString(R.string.updates_check_now)) { checkNow() }
            addView(checkButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            checkResult = metadata("").apply { setPadding(dp(12), 0, 0, 0) }
            addView(checkResult, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun updateCard(): View = card().apply {
        addView(horizontalRow(dp(16), dp(16)).apply {
            gravity = Gravity.TOP
            updateSymbol = icon(R.drawable.ic_dashboard_update)
            addView(updateSymbol, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(12)
            })
            addView(LinearLayout(this@UpdatesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title(getString(R.string.updates_native_card_title)))
                status = body(getString(R.string.loading)).apply { id = R.id.updates_native_summary }
                addView(status)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        runSection = section()
        addView(runSection)
        peers = section().apply { id = R.id.updates_native_peers }
        addView(peers)
        checks = section().apply {
            id = R.id.updates_checks
            preflightToggle = MaterialButton(
                this@UpdatesActivity,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle,
            ).apply {
                id = R.id.updates_native_preflight_toggle
                isAllCaps = false
                setOnClickListener {
                    preflightOpen = !preflightOpen
                    renderPreflight(latestState)
                }
            }
            addView(preflightToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            preflightBody = LinearLayout(this@UpdatesActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(preflightBody)
        }
        addView(checks)
        actions = section().apply {
            orientation = LinearLayout.VERTICAL
            standardActions = LinearLayout(this@UpdatesActivity).apply {
                id = R.id.updates_native_standard_actions
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@UpdatesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    start = primaryButton(R.id.updates_start_button, getString(R.string.updates_update)) { confirmRoutine() }
                    start.isEnabled = false
                    major = outlinedButton(R.id.updates_native_major_button, getString(R.string.updates_native_major_placeholder)) {
                        confirmMajor()
                    }
                    addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
                    addView(major, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(4) })
                })
                blockedReason = metadata("").apply {
                    id = R.id.updates_native_blocked_reason
                    setTextColor(color(R.color.collie_blocked))
                    setPadding(0, dp(6), 0, 0)
                    visibility = View.GONE
                }
                addView(blockedReason, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                dismiss = outlinedButton(R.id.updates_snooze_button, getString(R.string.updates_native_dismiss)) { snooze() }
                addView(dismiss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(4) })
            }
            addView(standardActions)
            confirmation = LinearLayout(this@UpdatesActivity).apply {
                id = R.id.updates_native_confirmation
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                confirmationTitle = title("").apply {
                    id = R.id.updates_native_confirmation_title
                    textSize = 14f
                }
                confirmationBody = body("").apply {
                    id = R.id.updates_native_confirmation_body
                    setPadding(0, dp(4), 0, 0)
                }
                addView(confirmationTitle)
                addView(confirmationBody)
                addView(LinearLayout(this@UpdatesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    confirmationConfirm = primaryButton(R.id.updates_native_confirmation_confirm, "") {
                        val action = confirmationAction ?: return@primaryButton
                        confirmationAction = null
                        confirmationConfirm.isEnabled = false
                        confirmationCancel.isEnabled = false
                        confirmationConfirm.setText(R.string.updates_native_starting)
                        action()
                    }
                    addView(confirmationConfirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                        marginEnd = dp(8)
                    })
                    confirmationCancel = borderlessButton(
                        R.id.updates_native_confirmation_cancel,
                        getString(R.string.settings_cancel),
                    ) {
                        confirmationAction = null
                        latestState?.let(::renderActions) ?: run {
                            standardActions.visibility = View.VISIBLE
                            confirmation.visibility = View.GONE
                        }
                    }
                    addView(confirmationCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            }
            addView(confirmation)
            error = metadata("").apply {
                id = R.id.updates_native_error
                setTextColor(color(R.color.collie_blocked))
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
            }
            addView(error)
        }
        addView(actions)
    }

    @androidx.annotation.VisibleForTesting
    internal fun renderForTest(value: UpdateCheckResponse) {
        render(value)
    }

    private suspend fun load() {
        if (busy || checking) return
        if (repository.deviceWriteAuthorization.value == DeviceWriteAuthorization.UNVERIFIED) repository.snapshot()
        when (val result = repository.updateState()) {
            is ApiResult.Success -> render(withRetainedRun(result.value))
            is ApiResult.Failure -> when (val standby = repository.standbyUpdate()) {
                is ApiResult.Success -> {
                    val standbyRun = standby.value.activeRunOrNull()
                    if (standbyRun != null) renderStandby(standbyRun)
                    else if (!UpdateNativePresentation.hasActiveRun(latestState)) showLoadError(result)
                }
                is ApiResult.Failure -> if (!UpdateNativePresentation.hasActiveRun(latestState)) showLoadError(result)
                is ApiResult.NotModified -> Unit
            }
            is ApiResult.NotModified -> Unit
        }
    }

    private fun showLoadError(result: ApiResult.Failure) {
        status.text = getString(
            R.string.error_subject,
            getString(R.string.unavailable),
            MainViewModel.describe(resources, result.error),
        )
        start.isEnabled = false
        major.isEnabled = false
    }

    private fun renderStandby(run: UpdateRun) {
        standbyRun = UpdateNativePresentation.freshestRun(standbyRun, run)
        val retained = latestState
        if (retained != null) {
            render(withRetainedRun(retained))
            start.isEnabled = false
            major.isEnabled = false
            return
        }
        status.text = runLine(run)
        renderRun(run)
        checks.visibility = View.GONE
        peers.visibility = View.GONE
        actions.visibility = View.GONE
    }

    private fun withRetainedRun(value: UpdateCheckResponse): UpdateCheckResponse {
        val current = UpdateNativePresentation.visibleRun(value)
        val retained = standbyRun
        val resolved = when {
            retained == null -> current
            current == null -> retained
            else -> UpdateNativePresentation.freshestRun(current, retained)
        }
        if (resolved != null) standbyRun = resolved
        return value.copy(run = resolved ?: value.run)
    }

    private fun render(value: UpdateCheckResponse) {
        latestState = value
        renderCheckControl(value)
        val upToDate = UpdateNativePresentation.isUpToDate(value)
        updateSymbol.setImageResource(
            if (upToDate) R.drawable.ic_collie_status_filled else R.drawable.ic_dashboard_update,
        )
        updateSymbol.imageTintList = ColorStateList.valueOf(
            color(if (upToDate) R.color.collie_done else R.color.collie_muted),
        )
        status.text = buildString {
            if (upToDate) append(getString(R.string.updates_native_nothing_to_do)).append('\n')
            append(getString(R.string.updates_native_running, value.current.ifBlank { getString(R.string.updates_native_unknown_version) }))
            value.latest?.let { append(getString(R.string.updates_native_newest_suffix, it)) }
            if (value.latest == null) append('\n').append(getString(R.string.updates_native_latest_unknown))
            val newer = value.newerVersions.orEmpty()
            if (newer.isNotEmpty()) {
                append('\n').append(resources.getQuantityString(R.plurals.updates_native_behind, newer.size, newer.size))
            }
            if (value.bridgeStale) append('\n').append(getString(R.string.updates_native_restart_required))
        }
        renderRun(UpdateNativePresentation.visibleRun(value))
        renderPeers(value)
        if (!preflightInitialized) {
            preflightOpen = UpdateNativePresentation.shouldOpenPreflight(value)
            preflightInitialized = true
        }
        renderPreflight(value)
        renderActions(value)
    }

    private fun renderCheckControl(value: UpdateCheckResponse) {
        checkDescription.text = if (value.checkedAt != null) {
            getString(R.string.updates_native_check_running_checked, value.current, timeAgo(value.checkedAt))
        } else {
            getString(R.string.updates_native_check_running, value.current)
        }
        checkButton.text = getString(if (checking) R.string.updates_checking else R.string.updates_check_now)
        checkButton.isEnabled = !checking
        checkResult.text = when {
            checking -> ""
            checkFailed -> getString(R.string.updates_native_check_error)
            !value.releaseAvailable && !value.bridgeStale && value.majorAvailable == null -> getString(R.string.updates_up_to_date)
            else -> ""
        }
        checkResult.setTextColor(color(if (checkFailed) R.color.collie_blocked else R.color.collie_muted))
    }

    private fun renderPeers(value: UpdateCheckResponse) {
        peers.removeAllViews()
        val rows = UpdateNativePresentation.peerRows(
            value.pack.orEmpty(),
            UpdateNativePresentation.visibleRun(value)?.peers.orEmpty(),
        )
        peers.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEach { row ->
            peers.addView(horizontalRow(0, dp(4)).apply {
                gravity = Gravity.TOP
                addView(statusDot(statusColor(row.state)), LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    topMargin = dp(5)
                    marginEnd = dp(8)
                })
                addView(LinearLayout(this@UpdatesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(metadata(buildString {
                        append(row.name).append(" · ")
                        append(row.version ?: getString(R.string.updates_native_unknown_version)).append(" · ")
                        append(peerState(row.state))
                        row.asOf?.let { append(" · ").append(getString(R.string.updates_native_as_of, timeAgo(it))) }
                    }).apply { setTextColor(color(R.color.collie_foreground)) })
                    row.reason?.let { reason ->
                        addView(metadata(reason).apply { setTextColor(color(R.color.collie_blocked)) })
                    }
                    if (row.reason == null && row.rank <= 2) {
                        addView(metadata(getString(R.string.updates_native_peer_unknown_reason)).apply {
                            setTextColor(color(R.color.collie_blocked))
                        })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun renderPreflight(value: UpdateCheckResponse?) {
        preflightBody.removeAllViews()
        val rows = value?.preflight?.checks.orEmpty()
        val running = value?.run?.state in UpdateNativePresentation.activeStates
        checks.visibility = if (running || rows.isEmpty()) View.GONE else View.VISIBLE
        if (rows.isEmpty()) return
        val summary = preflightSummary(rows)
        preflightToggle.text = getString(R.string.updates_native_details_summary, summary)
        preflightToggle.contentDescription = getString(
            if (preflightOpen) R.string.updates_native_details_hide_accessibility else R.string.updates_native_details_show_accessibility,
            summary,
        )
        preflightToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (preflightOpen) R.drawable.ic_history_chevron_up else R.drawable.ic_history_chevron_down,
            0,
        )
        preflightBody.visibility = if (preflightOpen) View.VISIBLE else View.GONE
        if (!preflightOpen) return
        val newer = value?.newerVersions.orEmpty()
        if (newer.size > 1) {
            preflightBody.addView(metadata(getString(R.string.updates_native_includes, newer.joinToString(", "))))
        }
        rows.forEach { check -> preflightBody.addView(preflightRow(check)) }
    }

    private fun preflightRow(check: PreflightCheck): View = horizontalRow(0, dp(4)).apply {
        gravity = Gravity.TOP
        addView(statusDot(statusColor(check.verdict)), LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            topMargin = dp(5)
            marginEnd = dp(8)
        })
        addView(LinearLayout(this@UpdatesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(metadata(getString(R.string.updates_native_preflight_line, check.id, check.reason)).apply {
                setTextColor(color(R.color.collie_foreground))
            })
            check.remedy?.let { remedy ->
                // Plain text, so the bridge's markdown code ticks would read literally.
                addView(metadata(getString(R.string.updates_native_fix_on_host, remedy.lineSequence().first().replace("`", ""))))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun renderRun(run: UpdateRun?) {
        runSection.removeAllViews()
        runSection.visibility = if (run == null) View.GONE else View.VISIBLE
        if (run == null) return
        val active = run.state in UpdateNativePresentation.activeStates
        runSection.addView(body(runLine(run)).apply {
            id = R.id.updates_native_run_status
            setTextColor(color(when {
                active -> R.color.collie_working
                run.state == "done" -> R.color.collie_done
                run.state == "rolled-back" || run.state == "stuck" || run.state == "interrupted" -> R.color.collie_blocked
                else -> R.color.collie_foreground
            }))
        })
        if (active) runSection.addView(metadata(getString(R.string.updates_native_progress_note)))
        run.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            runSection.addView(metadata(reason).apply { setTextColor(color(R.color.collie_blocked)) })
        }
        if (run.state == "stuck" && !run.recovery.isNullOrBlank()) {
            runSection.addView(code(run.recovery).apply {
                id = R.id.updates_native_recovery
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(color(R.color.collie_muted_surface))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        if (!run.logTail.isNullOrBlank()) {
            runSection.addView(borderlessButton(
                R.id.updates_native_log_toggle,
                getString(if (logOpen) R.string.updates_native_log_hide else R.string.updates_native_log_show),
            ) {
                logOpen = !logOpen
                renderRun(latestState?.let(UpdateNativePresentation::visibleRun) ?: standbyRun)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            if (logOpen) runSection.addView(ScrollView(this).apply {
                id = R.id.updates_native_log_tail
                addView(code(run.logTail).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setBackgroundColor(color(R.color.collie_muted_surface))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(192)))
        }
        if (run.state == "rolled-back" || run.state == "interrupted") {
            runSection.addView(outlinedButton(
                R.id.updates_native_retry_button,
                getString(R.string.updates_native_retry),
            ) { confirmRunRetry(run) }.apply {
                isEnabled = !busy && repository.writesAllowed()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun renderActions(value: UpdateCheckResponse) {
        val running = value.run?.state in UpdateNativePresentation.activeStates
        if (running) confirmationAction = null
        val confirming = confirmationAction != null
        val action = UpdateNativePresentation.action(value)
        val target = UpdateNativePresentation.actionTarget(value, action)
        actions.visibility = if (
            running || (!confirming && action == NativeUpdateAction.NONE && value.majorAvailable == null)
        ) View.GONE else View.VISIBLE
        standardActions.visibility = if (confirming) View.GONE else View.VISIBLE
        confirmation.visibility = if (confirming) View.VISIBLE else View.GONE
        if (confirming) {
            error.visibility = View.GONE
            return
        }
        start.visibility = if (action == NativeUpdateAction.NONE) View.GONE else View.VISIBLE
        start.text = when (action) {
            NativeUpdateAction.UPDATE -> getString(R.string.updates_native_action, target.orEmpty())
            NativeUpdateAction.UPDATE_PACK -> getString(R.string.updates_native_action_pack, target.orEmpty())
            NativeUpdateAction.RETRY_PACK -> getString(R.string.updates_native_retry_pack)
            NativeUpdateAction.NONE -> ""
        }
        start.isEnabled = UpdateNativePresentation.canStart(value, busy, repository.writesAllowed(), action)
        major.visibility = if (value.majorAvailable == null) View.GONE else View.VISIBLE
        major.text = value.majorAvailable?.let { getString(R.string.updates_native_major_action, it) }.orEmpty()
        major.isEnabled = UpdateNativePresentation.canStartMajor(value, busy, repository.writesAllowed())
        dismiss.visibility = if (!dismissed && (value.releaseAvailable || value.majorAvailable != null)) View.VISIBLE else View.GONE
        val redCount = value.preflight?.checks?.count { it.verdict == "red" } ?: 0
        val preflightUnavailable = getString(R.string.updates_preflight_unavailable).takeIf { value.preflight == null }
        val actionBlocked = (redCount > 0 || preflightUnavailable != null) && action != NativeUpdateAction.RETRY_PACK
        blockedReason.text = resources.getQuantityString(R.plurals.updates_native_blocked_reason, redCount, redCount)
        blockedReason.visibility = if (!running && !confirming && redCount > 0) View.VISIBLE else View.GONE
        error.text = listOfNotNull(
            actionError,
            preflightUnavailable?.takeIf { actionBlocked },
            getString(R.string.updates_native_dismissed).takeIf { dismissed },
            value.majorAvailable?.let { getString(R.string.updates_native_major_note, it) },
        ).joinToString("\n")
        error.setTextColor(color(if (actionBlocked) R.color.collie_blocked else R.color.collie_muted))
        error.visibility = if (error.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun checkNow() {
        if (checking) return
        checking = true
        checkFailed = false
        latestState?.let(::renderCheckControl)
        val prior = latestState?.checkedAt
        lifecycleScope.launch {
            when (val result = repository.checkForUpdates()) {
                is ApiResult.Success -> checkFailed = result.value.checkedAt == null || result.value.checkedAt == prior
                is ApiResult.Failure -> checkFailed = true
                is ApiResult.NotModified -> checkFailed = true
            }
            checking = false
            load()
            latestState?.let(::renderCheckControl)
        }
    }

    private fun snooze() {
        dismissed = true
        latestState?.let(::renderActions)
        lifecycleScope.launch { repository.snoozeUpdate() }
    }

    private fun confirmRoutine() {
        val value = latestState ?: return
        val action = UpdateNativePresentation.action(value)
        val target = UpdateNativePresentation.actionTarget(value, action) ?: return
        if (!UpdateNativePresentation.canStart(value, busy, repository.writesAllowed(), action)) return
        val title = when (action) {
            NativeUpdateAction.UPDATE_PACK -> getString(R.string.updates_native_pack_confirm_title, target)
            NativeUpdateAction.RETRY_PACK -> getString(R.string.updates_native_retry_pack_confirm_title)
            else -> getString(R.string.updates_native_confirm_title, target)
        }
        showConfirmation(
            title,
            getString(when (action) {
                NativeUpdateAction.UPDATE_PACK -> R.string.updates_native_pack_confirm_body
                NativeUpdateAction.RETRY_PACK -> R.string.updates_native_retry_pack_confirm_body
                else -> R.string.updates_native_confirm_body
            }),
            getString(when (action) {
                NativeUpdateAction.UPDATE_PACK -> R.string.updates_native_pack_confirm_action
                NativeUpdateAction.RETRY_PACK -> R.string.updates_native_retry_pack_confirm_action
                else -> R.string.updates_native_confirm_action
            }),
        ) { begin(target, major = false, peersOnly = action == NativeUpdateAction.RETRY_PACK) }
    }

    private fun confirmMajor() {
        val value = latestState ?: return
        val target = value.majorAvailable ?: return
        if (!UpdateNativePresentation.canStartMajor(value, busy, repository.writesAllowed())) return
        showConfirmation(
            getString(R.string.updates_native_major_confirm_title, target),
            getString(R.string.updates_native_major_confirm_body, target),
            getString(R.string.updates_native_major_confirm_action, target),
        ) { begin(target, major = true, peersOnly = false) }
    }

    private fun confirmRunRetry(run: UpdateRun) {
        val value = latestState ?: return
        if (busy || !repository.writesAllowed()) return
        val target = run.to ?: value.latest ?: value.current
        showConfirmation(
            getString(R.string.updates_native_confirm_title, target),
            getString(R.string.updates_native_confirm_body),
            getString(R.string.updates_native_confirm_action),
        ) { begin(target, major = false, peersOnly = false) }
    }

    internal fun showConfirmation(title: String, message: String, actionLabel: String, action: () -> Unit) {
        confirmationTitle.text = title
        confirmationBody.text = message
        confirmationConfirm.text = actionLabel
        confirmationConfirm.isEnabled = true
        confirmationCancel.isEnabled = true
        confirmationAction = action
        latestState?.let(::renderActions) ?: run {
            actions.visibility = View.VISIBLE
            standardActions.visibility = View.GONE
            confirmation.visibility = View.VISIBLE
        }
    }

    private fun begin(target: String, major: Boolean, peersOnly: Boolean) {
        if (busy) return
        confirmationAction = null
        busy = true
        actionError = null
        start.isEnabled = false
        this.major.isEnabled = false
        error.text = getString(R.string.updates_starting)
        error.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val result = repository.startUpdate(target, major, peersOnly)) {
                is ApiResult.Success -> {
                    actionError = null
                    result.value.run?.let(::renderStandby)
                }
                is ApiResult.Failure -> {
                    actionError = MainViewModel.describe(resources, result.error)
                    confirmation.visibility = View.GONE
                    standardActions.visibility = View.VISIBLE
                    error.text = actionError
                    error.setTextColor(color(R.color.collie_blocked))
                }
                is ApiResult.NotModified -> Unit
            }
            busy = false
            load()
        }
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(8), 0)
        setBackgroundResource(R.drawable.bg_pane_header)
        addView(ImageButton(this@UpdatesActivity).apply {
            id = R.id.updates_back_button
            setImageResource(R.drawable.ic_collie_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.updates_native_back)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                startActivity(Intent(this@UpdatesActivity, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(CollieHeadingTextView(this@UpdatesActivity).apply {
            setText(R.string.updates_title)
            textSize = 18f
            setTextColor(color(R.color.collie_foreground))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun runLine(run: UpdateRun): String {
        val resource = when (run.state) {
            "preflight" -> R.string.updates_native_state_preflight
            "staging" -> R.string.updates_native_state_staging
            "restarting" -> R.string.updates_native_state_restarting
            "verifying" -> R.string.updates_native_state_verifying
            "done" -> R.string.updates_native_state_done
            "rolled-back" -> R.string.updates_native_state_rolled_back
            "stuck" -> R.string.updates_native_state_stuck
            "interrupted" -> R.string.updates_native_state_interrupted
            else -> R.string.updates_native_state_idle
        }
        return when (run.state) {
            "staging", "done" -> getString(resource, run.to ?: run.from.orEmpty())
            "rolled-back" -> getString(resource, run.from.orEmpty())
            else -> getString(resource)
        }
    }

    private fun peerState(state: String): String = getString(when (state) {
        "green" -> R.string.updates_native_peer_green
        "amber" -> R.string.updates_native_peer_amber
        "red" -> R.string.updates_native_peer_red
        "waiting" -> R.string.updates_native_peer_waiting
        "updating" -> R.string.updates_native_peer_updating
        "unreachable" -> R.string.updates_native_peer_unreachable
        "preflight" -> R.string.updates_native_peer_preflight
        "staging" -> R.string.updates_native_peer_staging
        "restarting" -> R.string.updates_native_peer_restarting
        "verifying" -> R.string.updates_native_peer_verifying
        "done" -> R.string.updates_native_peer_done
        "rolled-back" -> R.string.updates_native_peer_rolled_back
        "stuck" -> R.string.updates_native_peer_stuck
        "interrupted" -> R.string.updates_native_peer_interrupted
        "idle" -> R.string.updates_native_peer_idle
        else -> R.string.updates_native_peer_unknown
    })

    private fun preflightSummary(rows: List<PreflightCheck>): String {
        val red = rows.count { it.verdict == "red" }
        val amber = rows.count { it.verdict == "amber" }
        return when {
            red > 0 && amber > 0 -> getString(R.string.updates_native_summary_red_amber, red, amber)
            red > 0 -> resources.getQuantityString(R.plurals.updates_native_summary_red, red, red)
            amber > 0 -> resources.getQuantityString(R.plurals.updates_native_summary_amber, amber, amber)
            else -> resources.getQuantityString(R.plurals.updates_native_summary_checks, rows.size, rows.size)
        }
    }

    private fun statusColor(value: String): Int = when (value) {
        "red", "rolled-back", "unreachable", "stuck", "interrupted" -> R.color.collie_blocked
        "amber", "waiting", "updating", "preflight", "staging", "restarting", "verifying" -> R.color.collie_working
        "green", "done" -> R.color.collie_done
        else -> R.color.collie_unknown
    }

    private fun timeAgo(then: Long): String = DateUtils.getRelativeTimeSpanString(
        then,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_collie_card)
    }

    private fun LinearLayout.addDivider() = addView(View(this@UpdatesActivity).apply {
        setBackgroundColor(color(R.color.collie_border))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

    private fun section() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundResource(R.drawable.bg_update_section)
        visibility = View.GONE
    }

    private fun horizontalRow(horizontal: Int, vertical: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun title(value: String) = CollieHeadingTextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(color(R.color.collie_foreground))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(color(R.color.collie_muted))
        setLineSpacing(0f, 1.08f)
    }

    private fun metadata(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(color(R.color.collie_muted))
        setLineSpacing(0f, 1.08f)
    }

    private fun code(value: String) = metadata(value).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun icon(drawable: Int) = AppCompatImageView(this).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
        setPadding(dp(2), dp(2), dp(2), dp(2))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun statusDot(colorId: Int) = View(this).apply {
        setBackgroundResource(R.drawable.bg_pane_status_dot)
        backgroundTintList = ColorStateList.valueOf(color(colorId))
    }

    private fun primaryButton(id: Int, label: String, action: () -> Unit) = MaterialButton(this).apply {
        this.id = id
        text = label
        isAllCaps = false
        minHeight = dp(44)
        // A refused update is drawn as refused: the themed black fill had no disabled state.
        val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled))
        backgroundTintList = android.content.res.ColorStateList(
            states,
            intArrayOf(getColor(R.color.collie_foreground), getColor(R.color.collie_muted_surface)),
        )
        setTextColor(android.content.res.ColorStateList(states, intArrayOf(getColor(R.color.collie_surface), getColor(R.color.collie_muted))))
        setOnClickListener { action() }
    }

    private fun outlinedButton(id: Int, label: String, action: () -> Unit) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        this.id = id
        text = label
        isAllCaps = false
        minHeight = dp(44)
        setOnClickListener { action() }
    }

    private fun borderlessButton(id: Int, label: String, action: () -> Unit) = MaterialButton(
        this,
        null,
        android.R.attr.borderlessButtonStyle,
    ).apply {
        this.id = id
        text = label
        isAllCaps = false
        minHeight = dp(44)
        setOnClickListener { action() }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RUN_POLL_MS = 2_000L
        const val SETTLED_POLL_MS = 6_000L
    }
}
