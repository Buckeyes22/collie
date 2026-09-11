package com.lateapex.collie.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.DeviceRecord
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.NotifyPreferences
import com.lateapex.collie.network.NotifyPreferencesPatch
import com.lateapex.collie.network.PackStatusResponse
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.UpdateCheckResponse
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class SettingsConnectionPresentation(
    val bridge: String,
    val connected: Boolean,
    val deviceAccess: String?,
    val serverBuild: String?,
)

internal object SettingsConnectionModel {
    fun bridgeConnected(snapshotBridge: String?): Boolean = snapshotBridge == "connected"
}

/** One global in-flight gate for the server-mutating controls on Settings. The remembered value is
 * each control's legitimate baseline, so leaving busy never enables a capability-gated control. */
internal class SettingsMutationControlGate {
    private val controls = WeakHashMap<View, Boolean>()
    var busy: Boolean = false
        private set

    fun <T : View> register(view: T, enabled: Boolean): T {
        controls[view] = enabled
        view.isEnabled = enabled && !busy
        return view
    }

    fun setBusy(value: Boolean) {
        busy = value
        controls.entries.forEach { (view, enabled) -> view.isEnabled = enabled && !value }
    }
}

/** A server switch is controlled state: a tap dispatches the proposed value but paints the last
 * server value until refresh confirms it. */
internal object SettingsControlledSwitch {
    fun handleChange(
        button: CompoundButton,
        renderedValue: Boolean,
        mutationsBusy: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val proposed = button.isChecked
        if (proposed == renderedValue) return
        button.isChecked = renderedValue
        if (!mutationsBusy && button.isEnabled) onChange(proposed)
    }
}

/** Server-backed Settings cards. Reads are parallel; every mutation has one visible busy owner. */
internal class SettingsServerControls(
    private val activity: SettingsActivity,
    private val repository: CollieRepository,
    private val onConnection: (SettingsConnectionPresentation) -> Unit,
    private val onSpeechCapability: (Boolean) -> Unit,
    initialPairCode: String? = null,
    focusDevices: Boolean = false,
    private val onRevealDevices: (card: View, focusTarget: View?) -> Unit = { _, _ -> },
) : DefaultLifecycleObserver {
    private lateinit var parent: LinearLayout
    private lateinit var devicesCard: LinearLayout
    private lateinit var devicesDescription: TextView
    private var pairingCard: LinearLayout? = null
    private lateinit var notifyCard: LinearLayout
    private lateinit var snoozeCard: LinearLayout
    private lateinit var updateSummary: TextView
    private lateinit var packCard: LinearLayout
    private lateinit var packSummary: TextView
    private lateinit var status: TextView
    private val mutationGate = SettingsMutationControlGate()
    private var pairCodeDraft = initialPairCode.orEmpty()
    private var pairLabelDraft = ""
    // The devices card is rebuilt on every refresh, and the form with it; a refused code's message
    // has to outlive that rebuild or the operator never sees it.
    private var pairOutcome: Pair<String, Boolean>? = null
    private var pairFormView: PairFormView? = null
    private var revokeConfirming: String? = null
    private var devicesRevealPending = focusDevices || initialPairCode != null
    private var pairNameFocusPending = initialPairCode != null
    private var refreshing = false
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            schedulePollIfConnected()
        }
    }

    /**
     * [pairingHost] sits above every other card: an unpaired phone's next step is the code
     * field, not a scroll past Appearance, Text size and Haptics (A.9; S25 Ultra 2026-09-10).
     */
    fun bind(parent: LinearLayout, pairingHost: LinearLayout = parent) {
        this.parent = parent
        parent.removeAllViews()
        if (pairingHost !== parent) pairingHost.removeAllViews()
        if (pairLabelDraft.isBlank()) pairLabelDraft = repository.connection.value?.label.orEmpty()
        var pairFormForEntry: PairFormView? = null
        if (unpaired()) {
            val form = pairForm().also { pairFormView = it; pairFormForEntry = it }
            pairingCard = card().also {
                it.id = R.id.settings_parity_pairing_card
                it.addView(header(
                    text(R.string.settings_pair_phone),
                    text(R.string.settings_phone_not_paired),
                    R.drawable.ic_history_user,
                ))
                it.addView(divider())
                it.addView(form.root)
            }
            pairingHost.addView(pairingCard)
            if (devicesRevealPending) {
                onRevealDevices(pairingCard!!, if (pairNameFocusPending) form.label else pairingCard!!)
                devicesRevealPending = false
                pairNameFocusPending = false
            }
        }
        notifyCard = card().also {
            it.id = R.id.settings_parity_notify_card
            it.addView(header(
                text(R.string.settings_notify_title),
                text(R.string.settings_notify_description),
                R.drawable.ic_composer_quick,
            ))
            addNotifyRows(it, null)
            parent.addView(it)
        }
        snoozeCard = card().also {
            it.id = R.id.settings_parity_snooze_card
            it.addView(header(
                text(R.string.settings_snooze_title),
                text(R.string.settings_snooze_description),
                R.drawable.ic_history_info,
            ))
            renderSnooze(it, null)
            parent.addView(it)
        }
        parent.addView(navigationCard(
            title = text(R.string.updates_title),
            description = text(R.string.settings_updates_description),
            buttonId = R.id.settings_updates_button,
            activityClass = UpdatesActivity::class.java,
        ).also {
            it.id = R.id.settings_parity_updates_card
            updateSummary = it.findViewWithTag(SUMMARY_TAG)
        })
        devicesCard = card().also {
            it.id = R.id.settings_parity_devices_card
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            val heading = header(
                text(R.string.settings_devices_title),
                text(R.string.settings_devices_description),
                R.drawable.ic_history_user,
            )
            devicesDescription = (heading.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            it.addView(heading)
            parent.addView(it)
        }
        if (devicesRevealPending) {
            // A QR arrival scrolls now but the pair form's name field only exists when the phone is
            // unpaired; a read-only-banner arrival focuses the devices card itself, matching the web
            // fragment route.
            onRevealDevices(devicesCard, if (pairNameFocusPending) pairFormForEntry?.label else devicesCard)
            devicesRevealPending = false
            pairNameFocusPending = false
        }
        packCard = navigationCard(
            title = text(R.string.settings_pack_title),
            description = text(R.string.settings_pack_description),
            buttonId = R.id.settings_pack_button,
            activityClass = PackActivity::class.java,
        ).also { packSummary = it.findViewWithTag(SUMMARY_TAG) }
        packCard.id = R.id.settings_parity_pack_card
        packCard.visibility = View.GONE
        parent.addView(packCard)
        status = TextView(activity).apply {
            textSize = 12f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(4), dp(8), dp(4), 0)
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        parent.addView(status)
        activity.lifecycle.addObserver(this)
    }

    fun refresh() {
        activity.lifecycleScope.launch { refreshNow() }
    }

    override fun onStart(owner: LifecycleOwner) {
        parent.removeCallbacks(poll)
        refresh()
        schedulePollIfConnected()
    }

    override fun onStop(owner: LifecycleOwner) {
        parent.removeCallbacks(poll)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        parent.removeCallbacks(poll)
        owner.lifecycle.removeObserver(this)
    }

    private suspend fun refreshNow() {
        if (refreshing) return
        refreshing = true
        try {
            showStatus(text(R.string.settings_refreshing))
            coroutineScope {
                val devices = async { repository.devices() }
                val prefs = async { repository.notificationPreferences() }
                val snapshot = async { repository.snapshot() }
                val update = async { repository.updateState() }
                val pack = async { repository.pack() }
                val config = async { repository.config() }
                val connection = repository.connection.value
                val health = async { connection?.let { repository.probe(it.origin.value, it.label) } }
                val snapshotResult = snapshot.await()
                renderDevices(devices.await(), repository.writesAllowed())
                renderNotify(prefs.await())
                val snoozedUntil = (snapshotResult as? ApiResult.Success)?.value?.notifications?.snoozedUntil
                renderSnooze(snoozeCard, snoozedUntil)
                renderUpdate(update.await())
                renderPack(pack.await())
                val configResult = config.await()
                val healthResult = health.await()
                val snapshotValue = (snapshotResult as? ApiResult.Success)?.value
                val device = snapshotValue?.device
                val access = device?.let {
                    when {
                        !it.enforced -> text(R.string.settings_parity_access_not_enforced)
                        it.authorized && it.device != null -> text(R.string.settings_parity_access_full_named, it.device)
                        it.authorized -> text(R.string.settings_parity_access_full_local)
                        it.device != null -> text(R.string.settings_parity_access_read_only_named, it.device)
                        else -> text(R.string.settings_parity_access_read_only)
                    }
                }
                val healthValue = (healthResult as? ApiResult.Success)?.value
                val bridgeConnected = SettingsConnectionModel.bridgeConnected(snapshotValue?.bridge)
                onConnection(SettingsConnectionPresentation(
                    bridge = when {
                        bridgeConnected -> text(R.string.settings_parity_bridge_connected)
                        repository.connection.value != null -> text(R.string.settings_parity_bridge_offline)
                        else -> text(R.string.settings_parity_bridge_connecting)
                    },
                    connected = bridgeConnected,
                    deviceAccess = access,
                    serverBuild = (snapshotResult as? ApiResult.Success)?.buildId
                        ?: (healthResult as? ApiResult.Success)?.buildId
                        ?: healthValue?.version,
                ))
                onSpeechCapability((configResult as? ApiResult.Success)?.value?.stt != null)
                showStatus(null)
            }
        } finally {
            refreshing = false
        }
    }

    private fun unpaired(): Boolean = repository.connection.value?.isPaired == false

    private fun addNotifyRows(card: LinearLayout, prefs: NotifyPreferences?) {
        while (card.childCount > 1) card.removeViewAt(card.childCount - 1)
        val rows = listOf(
            Triple(R.id.settings_notify_blocked_switch, text(R.string.settings_notify_needs_input), prefs?.blocked),
            Triple(R.id.settings_notify_done_switch, text(R.string.settings_notify_finished), prefs?.done),
            Triple(R.id.settings_notify_updates_switch, text(R.string.settings_notify_updates), prefs?.updates),
        )
        rows.forEachIndexed { index, (id, label, checked) ->
            if (index == 0) card.addView(divider())
            card.addView(switchRow(id, label, checked ?: false, prefs != null) { next ->
                val patch = when (id) {
                    R.id.settings_notify_blocked_switch -> NotifyPreferencesPatch(blocked = next)
                    R.id.settings_notify_done_switch -> NotifyPreferencesPatch(done = next)
                    else -> NotifyPreferencesPatch(updates = next)
                }
                mutate(text(R.string.settings_notify_updating)) { repository.setNotificationPreferences(patch) }
            })
            if (index < rows.lastIndex) card.addView(divider())
        }
    }

    private fun renderNotify(result: ApiResult<NotifyPreferences>) {
        val serverOff = result is ApiResult.Failure && result.error is ApiFailure.Http && result.error.status == 404
        notifyCard.isVisible = !serverOff
        snoozeCard.isVisible = !serverOff
        addNotifyRows(notifyCard, (result as? ApiResult.Success)?.value)
        if (result is ApiResult.Failure) {
            showStatus(errorText(text(R.string.settings_notify_subject), result.error), error = true)
        }
    }

    private fun renderSnooze(card: LinearLayout, until: Long?) {
        while (card.childCount > 1) card.removeViewAt(card.childCount - 1)
        card.addView(divider())
        val active = until != null && until > System.currentTimeMillis()
        card.addView(TextView(activity).apply {
            text = if (active) {
                text(R.string.settings_snoozed_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until!!)))
            } else {
                text(R.string.settings_notifications_active)
            }
            textSize = 12f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(16), dp(10), dp(16), dp(6))
        })
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        if (active) {
            actions.addView(actionButton(R.id.settings_snooze_resume, text(R.string.settings_snooze_resume)) {
                mutate(text(R.string.settings_snooze_resuming)) { repository.setNotificationSnooze(null) }
            })
        } else {
            listOf(
                Triple(R.id.settings_snooze_30, text(R.string.settings_snooze_30), 30L),
                Triple(R.id.settings_snooze_60, text(R.string.settings_snooze_60), 60L),
                Triple(R.id.settings_snooze_240, text(R.string.settings_snooze_240), 240L),
            ).forEach { (id, label, minutes) ->
                actions.addView(actionButton(id, label) {
                    mutate(text(R.string.settings_snoozing_notifications)) {
                        repository.setNotificationSnooze(System.currentTimeMillis() + minutes * 60_000)
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            }
        }
        card.addView(actions)
    }

    internal fun renderDevices(result: ApiResult<DevicesResponse>, writesAllowed: Boolean) {
        pairingCard?.isVisible = unpaired()
        while (devicesCard.childCount > 1) devicesCard.removeViewAt(devicesCard.childCount - 1)
        devicesCard.addView(divider())
        val list = LinearLayout(activity).apply {
            id = R.id.settings_devices_list
            orientation = LinearLayout.VERTICAL
        }
        if (result !is ApiResult.Success) {
            devicesCard.addView(message(text(R.string.settings_devices_load_failed)))
            devicesCard.addView(list)
            return
        }
        val data = result.value
        devicesDescription.text = text(
            if (data.enforced) R.string.settings_parity_devices_enforced else R.string.settings_parity_devices_open,
        )
        devicesCard.addView(message(if (data.enforced) {
            data.current?.let { text(R.string.settings_paired_as, it) } ?: text(R.string.settings_phone_not_paired)
        } else {
            text(R.string.settings_enforcement_off)
        }))
        data.devices.forEach { device ->
            list.addView(deviceRow(device, writesAllowed))
            list.addView(divider())
        }
        devicesCard.addView(list)
    }

    private data class PairFormView(val root: View, val code: EditText, val label: EditText)

    private fun pairForm(): PairFormView {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        val codeLayout = com.google.android.material.textfield.TextInputLayout(
            activity,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle,
        ).apply {
            id = R.id.settings_pair_input_layout
            hint = text(R.string.settings_pair_code)
            helperText = text(R.string.settings_pair_code_hint)
        }
        val code = com.google.android.material.textfield.TextInputEditText(codeLayout.context).apply {
            id = R.id.settings_pair_code
            isSingleLine = true
            maxEms = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps())
            setText(pairCodeDraft)
            doAfterTextChanged { pairCodeDraft = it?.toString().orEmpty() }
        }
        codeLayout.addView(code)
        val labelLayout = com.google.android.material.textfield.TextInputLayout(
            activity,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle,
        ).apply {
            setPadding(0, dp(8), 0, 0)
            hint = text(R.string.device_label_hint)
        }
        val label = com.google.android.material.textfield.TextInputEditText(labelLayout.context).apply {
            id = R.id.settings_pair_label
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(pairLabelDraft)
            doAfterTextChanged { pairLabelDraft = it?.toString().orEmpty() }
        }
        labelLayout.addView(label)
        // The outcome is reported under the form itself. The shared status line sits at the end
        // of the server controls, below the connection card, where a refused code went unseen.
        val outcome = TextView(activity).apply {
            id = R.id.settings_pair_status
            textSize = 12f
            setPadding(dp(4), dp(8), dp(4), 0)
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            pairOutcome?.let { (message, error) -> showStatus(message, error, target = this) }
        }
        fun report(message: String?, error: Boolean = false) {
            pairOutcome = message?.let { it to error }
            showStatus(message, error, target = outcome)
        }
        val submit = actionButton(R.id.settings_pair_button, text(R.string.pair_and_connect)) {
            val origin = repository.connection.value?.origin
            if (origin == null || code.text.isNullOrBlank() || label.text.isNullOrBlank()) {
                report(text(R.string.settings_pair_fields_required), error = true)
                return@actionButton
            }
            mutate(
                progress = text(R.string.settings_pairing_phone),
                target = outcome,
                onFailure = { report(it, error = true) },
                onSuccess = { result ->
                    when (result) {
                        is PairResult.Paired -> {
                            pairCodeDraft = ""
                            pairLabelDraft = ""
                            pairOutcome = null
                            refresh()
                        }
                        is PairResult.Refused -> report(text(R.string.pairing_refused, result.reason), error = true)
                    }
                },
            ) { repository.pair(origin.value, label.text.toString(), code.text.toString()) }
        }
        form.addView(codeLayout)
        form.addView(labelLayout)
        form.addView(submit)
        form.addView(outcome)
        return PairFormView(form, code, label)
    }

    private fun deviceRow(device: DeviceRecord, writesAllowed: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(10), dp(8))
        }
        val meta = TextView(activity).apply {
            text = buildString {
                append(device.label)
                if (device.current) append(text(R.string.settings_this_device_suffix))
                append('\n').append(text(
                    R.string.settings_parity_device_meta,
                    relativeTime(device.createdAt),
                    relativeTime(device.lastSeenAt),
                ))
            }
            textSize = 13f
            setTextColor(color(R.color.collie_foreground))
        }
        row.addView(AppCompatImageView(activity).apply {
            setImageResource(R.drawable.ic_history_user)
            imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        row.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun revoke() {
            mutate(
                progress = text(R.string.settings_revoking, device.label),
                onSuccess = {
                    if (device.current) {
                        repository.demoteToReadOnly()
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                        )
                        activity.finish()
                    } else {
                        refresh()
                    }
                },
            ) { repository.revokeDevice(device.label) }
        }
        // The card is rebuilt on every refresh, so the row's confirm state is remembered by label:
        // a rebuilt row comes back still asking, instead of snapping back to "Revoke" a second
        // after the tap (S25 Ultra, 2026-09-10).
        fun renderChoice(confirming: Boolean) {
            revokeConfirming = if (confirming) device.label else revokeConfirming?.takeIf { it != device.label }
            controls.removeAllViews()
            if (confirming) {
                controls.addView(actionButton(View.generateViewId(), text(R.string.settings_cancel)) { renderChoice(false) })
                controls.addView(actionButton(View.generateViewId(), text(
                    if (device.current) R.string.settings_unpair else R.string.settings_revoke,
                )) { revokeConfirming = null; revoke() }.apply { setTextColor(color(R.color.collie_destructive)) })
            } else {
                controls.addView(actionButton(
                    if (device.current) R.id.settings_device_current_action else View.generateViewId(),
                    text(if (device.current) R.string.settings_unpair else R.string.settings_revoke),
                    enabled = writesAllowed,
                ) { renderChoice(true) }.apply {
                    contentDescription = if (device.current) {
                        text(R.string.settings_unpair_title)
                    } else {
                        text(R.string.settings_revoke_title, device.label)
                    }
                })
            }
        }
        renderChoice(revokeConfirming == device.label)
        row.addView(controls)
        return row
    }

    private fun renderUpdate(result: ApiResult<UpdateCheckResponse>) {
        updateSummary.text = when (result) {
            is ApiResult.Success -> when {
                UpdateNativePresentation.hasActiveRun(result.value) -> text(R.string.settings_update_running)
                UpdateNativePresentation.peersBehind(result.value) > 0 -> {
                    val count = UpdateNativePresentation.peersBehind(result.value)
                    activity.resources.getQuantityString(R.plurals.settings_update_peers_behind, count, count)
                }
                result.value.bridgeStale -> text(R.string.settings_update_restart_needed)
                result.value.releaseAvailable -> text(R.string.settings_update_available_short, result.value.latest.orEmpty())
                result.value.majorAvailable != null -> text(R.string.settings_update_available_short, result.value.majorAvailable)
                else -> text(R.string.updates_up_to_date)
            }
            else -> text(R.string.settings_update_unavailable)
        }
    }

    private fun renderPack(result: ApiResult<PackStatusResponse>) {
        packCard.isVisible = result is ApiResult.Success
        packSummary.text = when (result) {
            is ApiResult.Success -> text(R.string.settings_pack_machines, result.value.pack.name, result.value.members.size)
            is ApiResult.Failure -> if (result.error is ApiFailure.Http && result.error.status == 404) {
                text(R.string.settings_pack_solo)
            } else {
                text(R.string.settings_pack_status_unavailable)
            }
            is ApiResult.NotModified -> text(R.string.settings_pack_unchanged)
        }
    }

    private fun navigationCard(
        title: String,
        description: String,
        @IdRes buttonId: Int,
        activityClass: Class<*>,
    ): LinearLayout = card().apply {
        val card = this
        val row = LinearLayout(activity).apply {
            id = buttonId
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_settings_navigation_press)
            isClickable = true
            isFocusable = true
            setOnClickListener { activity.startActivity(Intent(activity, activityClass)) }
        }
        row.addView(AppCompatImageView(activity).apply {
            setImageResource(
                if (activityClass == UpdatesActivity::class.java) R.drawable.ic_dashboard_update else R.drawable.ic_pack_network,
            )
            imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(color(R.color.collie_foreground))
            })
            addView(TextView(activity).apply {
                tag = SUMMARY_TAG
                text = description
                textSize = 14f
                setTextColor(color(R.color.collie_muted))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(AppCompatImageView(activity).apply {
            setImageResource(R.drawable.ic_history_chevron_right)
            imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        card.addView(row)
    }

    private fun <T> mutate(
        progress: String,
        target: TextView = status,
        onFailure: ((String) -> Unit)? = null,
        onSuccess: suspend (T) -> Unit = { refresh() },
        action: suspend () -> ApiResult<T>,
    ) {
        if (mutationGate.busy) return
        mutationGate.setBusy(true)
        showStatus(progress, target = target)
        activity.lifecycleScope.launch {
            val result = try {
                action()
            } finally {
                mutationGate.setBusy(false)
            }
            when (result) {
                is ApiResult.Success -> onSuccess(result.value)
                is ApiResult.Failure -> {
                    val message = errorText(text(R.string.settings_change), result.error)
                    if (onFailure != null) onFailure(message) else showStatus(message, error = true, target = target)
                }
                is ApiResult.NotModified -> refresh()
            }
        }
    }

    private fun switchRow(@IdRes id: Int, label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(6), dp(12), dp(6))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.collie_foreground))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(mutationGate.register(MaterialSwitch(activity).apply {
            this.id = id
            isChecked = checked
            contentDescription = label
            // Server preferences are controlled state. Keep the rendered value until the refresh
            // following a successful mutation; this prevents a rejected request looking applied.
            setOnCheckedChangeListener { button, _ ->
                SettingsControlledSwitch.handleChange(button, checked, mutationGate.busy, onChange)
            }
        }, enabled))
        return row
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_pane_card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
    }

    private fun header(title: String, description: String, iconRes: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(14), dp(16), dp(12))
        addView(AppCompatImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(CollieHeadingTextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(color(R.color.collie_foreground))
            })
            addView(TextView(activity).apply {
                text = description
                textSize = 14f
                setTextColor(color(R.color.collie_muted))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun message(value: String) = TextView(activity).apply {
        text = value
        textSize = 12f
        setTextColor(color(R.color.collie_muted))
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun actionButton(@IdRes id: Int, label: String, enabled: Boolean = true, action: () -> Unit) =
        mutationGate.register(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.id = id
            text = label
            isAllCaps = false
            minHeight = dp(44)
            setOnClickListener { if (!mutationGate.busy) action() }
        }, enabled)

    private fun divider() = View(activity).apply {
        setBackgroundColor(color(R.color.collie_border))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun showStatus(message: String?, error: Boolean = false, target: TextView = status) {
        target.text = message.orEmpty()
        target.isVisible = message != null
        target.setTextColor(color(if (error) R.color.collie_error else R.color.collie_muted))
    }

    private fun errorText(subject: String, failure: ApiFailure) = activity.getString(
        R.string.error_subject,
        subject,
        MainViewModel.describe(activity.resources, failure),
    )
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(@androidx.annotation.StringRes resource: Int, vararg args: Any): String =
        activity.getString(resource, *args)
    private fun relativeTime(value: Long): String = DateUtils.getRelativeTimeSpanString(
        value,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    private fun schedulePollIfConnected() {
        if (repository.connection.value != null) parent.postDelayed(poll, SETTINGS_POLL_MS)
    }

    private companion object {
        const val SUMMARY_TAG = "settings-summary"
        const val SETTINGS_POLL_MS = 5_000L
    }
}
