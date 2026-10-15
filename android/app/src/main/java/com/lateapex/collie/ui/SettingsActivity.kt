package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Vibrator
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivitySettingsBinding
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OperatorFontRow
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.lateapex.collie.diagnostics.DiagnosticsExport
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

internal data class SettingsEntryRequest(
    val pairCode: String?,
    val focusDevices: Boolean,
) {
    companion object {
        /** Read navigation once. Removing the extras keeps recreation or a later clear-top delivery
         * from restoring a pairing code whose field the operator has already edited or submitted. */
        fun consume(intent: Intent): SettingsEntryRequest {
            val pairCode = intent.getStringExtra(SettingsActivity.EXTRA_PAIR_CODE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.uppercase(Locale.ENGLISH)
            val focusDevices = intent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_DEVICES, false) || pairCode != null
            intent.removeExtra(SettingsActivity.EXTRA_PAIR_CODE)
            intent.removeExtra(SettingsActivity.EXTRA_FOCUS_DEVICES)
            return SettingsEntryRequest(pairCode, focusDevices)
        }
    }
}

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var nativePreferences: NativePreferences
    private lateinit var localPreferences: SettingsLocalPreferences
    private lateinit var serverControls: SettingsServerControls
    private var disconnecting = false
    private var disconnectSheet: CollieBottomSheetDialog? = null
    private var operatorFontActor: ScopedReadContext? = null
    private var operatorFontsObserver: AutoCloseable? = null
    private val operatorTypefaceGeneration = AtomicLong(0)
    private var operatorTypefaceJob: Job? = null
    private var operatorPendingTypeface: OperatorFontRow? = null
    private var operatorAdmittedFonts: List<OperatorFontRow> = emptyList()
    private var operatorFontCatalogRetryJob: Job? = null
    private var operatorFontCatalogLoading = false
    private var operatorFontCatalogAdmitted = false

    @androidx.annotation.VisibleForTesting internal var repositoryForTests: com.lateapex.collie.data.CollieRepository? = null
    private val repository
        get() = repositoryForTests ?: (application as CollieApplication).container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.applySafeContentInsets(binding.root)
        val legacyEntry = SettingsEntryRequest.consume(intent)
        val imported = LinkImportHandoff.consumeConfirmed(intent, application as CollieApplication)
        val pairing = imported?.candidate as? LinkImportCoordinator.Candidate.Pairing
        val entry = if (pairing != null && imported.owner.current(application as CollieApplication))
            SettingsEntryRequest(pairing.code, true) else legacyEntry

        nativePreferences = NativePreferences(this)
        binding.settingsBackButton.setOnClickListener { returnToDashboard() }
        bindAppearance()
        localPreferences = SettingsLocalPreferences(this, nativePreferences).also {
            it.bindDisplay(binding.settingsLocalPreferences)
            it.bindBehavior(binding.settingsBehaviorPreferences)
            it.setOnOperatorTypefaceSelected(::selectOperatorTypeface)
            it.setOnTypefaceSelectionInvalidated(::invalidateOperatorTypefaceSelection)
        }
        PreferredTypeface.observe(repository)
        operatorFontsObserver = repository.onReadContextRetired(::onOperatorFontActorRetired)
        RetentionSettingsSection(this, nativePreferences, repository).bind(binding.settingsBehaviorPreferences)
        serverControls = SettingsServerControls(
            this,
            repository,
            onConnection = { presentation ->
                renderServerConnection(presentation)
                retryOperatorFontCatalogAfterRefresh()
            },
            onSpeechCapability = localPreferences::setHandsFreeCapability,
            initialPairCode = entry.pairCode,
            importedPairing = imported?.takeIf { pairing != null },
            focusDevices = entry.focusDevices,
            onRevealDevices = ::revealDevices,
        ).also { it.bind(binding.settingsServerControls, binding.settingsPairingHost) }
        binding.settingsServerControls.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                id = R.id.settings_import_link
                setText(R.string.link_import_title)
                isAllCaps = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { startActivity(LinkImportActivity.intent(this@SettingsActivity)) }
            },
        )
        binding.settingsServerControls.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                setText(R.string.settings_manage_launchers)
                isAllCaps = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    startActivity(LaunchRecipeActivity.intent(this@SettingsActivity, repository.selectedScope.value, LaunchRecipeActivity.MODE_RECIPES))
                }
            },
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )


        binding.settingsServerControls.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                setText(R.string.android_updates_title)
                isAllCaps = false
                setOnClickListener { startActivity(Intent(this@SettingsActivity, AndroidAppUpdatesActivity::class.java)) }
            },
        )
        binding.settingsServerControls.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                setText(R.string.native_notifications_title)
                isAllCaps = false
                setOnClickListener { startActivity(Intent(this@SettingsActivity, NativeNotificationsActivity::class.java)) }
            },
        )
        binding.root.applyAppTypeface()
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        binding.settingsHapticsCard.visibility = if (vibrator?.hasVibrator() == true) View.VISIBLE else View.GONE
        binding.hapticsSwitch.isChecked = nativePreferences.hapticsEnabled
        binding.hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            nativePreferences.hapticsEnabled = checked
        }
        binding.serverVersionValue.setText(R.string.settings_parity_unknown)
        binding.settingsConnectionBridgeValue.setText(R.string.settings_parity_bridge_connecting)
        binding.settingsBuildStamp.text = getString(R.string.settings_parity_build_stamp, BuildConfig.VERSION_NAME)
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
        binding.settingsLicensesButton.setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        binding.root.findViewById<View>(R.id.settings_agent_catalog_button)?.setOnClickListener {
            startActivity(AgentCatalogActivity.intent(this))
        }
        binding.root.findViewById<View>(R.id.settings_diagnostics_send_button)?.setOnClickListener {
            confirmSendDiagnostics()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.connection.collect(::renderConnection)
                }
                launch {
                    combine(repository.connection, repository.selectedScope, repository.readSource) { _, scope, _ -> scope }
                        .map { scope -> repository.readContext(scope) }
                        .distinctUntilChanged { previous, current ->
                            previous?.key == current?.key && previous?.navigationGeneration == current?.navigationGeneration
                        }
                        .collectLatest { actor -> loadOperatorFontChoices(actor) }
                }
            }
        }
    }

    private suspend fun loadOperatorFontChoices(actor: ScopedReadContext?) {
        val previousActor = operatorFontActor
        if (previousActor != null && (actor == null || previousActor.key != actor.key ||
                previousActor.navigationGeneration != actor.navigationGeneration)) {
            invalidateOperatorTypefaceSelection()
            operatorAdmittedFonts = emptyList()
            PreferredTypeface.clearLoadedOperator()
            binding.root.applyAppTypeface()
        }
        operatorFontActor = actor
        operatorFontCatalogAdmitted = false
        if (actor == null) {
            operatorFontCatalogLoading = false
            localPreferences.updateOperatorFonts(emptyList(), null, loading = false)
            PreferredTypeface.clearLoadedOperator()
            binding.root.applyAppTypeface()
            return
        }
        val owner = actor.custodyOwner.storageIdentity
        localPreferences.updateOperatorFonts(emptyList(), owner, loading = true)
        operatorFontCatalogLoading = true
        try {
            val result = repository.config(actor.key.scope, actor)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            when (result) {
                is ApiResult.Success -> {
                    if (!repository.isCurrent(actor) || operatorFontActor !== actor) return
                    val rows = result.value.operatorFonts.filter(OperatorFontRow::valid)
                    operatorAdmittedFonts = rows
                    operatorFontCatalogAdmitted = true
                    operatorPendingTypeface?.let { pending ->
                        if (pending !in rows) invalidateOperatorTypefaceSelection()
                    }
                    localPreferences.updateOperatorFonts(rows, owner, loading = false)
                    val selected = nativePreferences.operatorTypefaceSelection
                    if (selected?.ownerFingerprint == owner) {
                        val row = rows.firstOrNull { it.family == selected.family && it.basename == selected.basename }
                        if (row != null) launchOperatorTypefaceLoad(row, actor, showLoading = false)
                        else {
                            nativePreferences.clearOperatorTypeface()
                            PreferredTypeface.clearLoadedOperator()
                            binding.root.applyAppTypeface()
                            localPreferences.operatorFontUnavailable()
                        }
                    } else {
                        PreferredTypeface.clearLoadedOperator()
                        binding.root.applyAppTypeface()
                    }
                }
                is ApiResult.Failure, is ApiResult.NotModified -> {
                    if (repository.isCurrent(actor) && operatorFontActor === actor) {
                        invalidateOperatorTypefaceSelection()
                        operatorAdmittedFonts = emptyList()
                        localPreferences.updateOperatorFonts(emptyList(), owner, loading = false)
                        PreferredTypeface.clearLoadedOperator()
                        binding.root.applyAppTypeface()
                        localPreferences.operatorFontUnavailable()
                    }
                }
            }
        } finally {
            if (operatorFontActor === actor) operatorFontCatalogLoading = false
        }
    }

    /** Optional parallel reads can briefly fence the first config without retiring its actor.
     * Retry an unadmitted catalog after the existing refresh has settled, under a fresh read actor. */
    private fun retryOperatorFontCatalogAfterRefresh() {
        if (operatorFontCatalogAdmitted || operatorFontCatalogLoading || operatorFontCatalogRetryJob?.isActive == true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        operatorFontCatalogRetryJob = lifecycleScope.launch {
            if (operatorFontCatalogAdmitted || operatorFontCatalogLoading ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            loadOperatorFontChoices(repository.readContext(repository.selectedScope.value))
        }
    }

    private fun selectOperatorTypeface(row: OperatorFontRow) {
        val actor = operatorFontActor ?: return
        if (row !in operatorAdmittedFonts) return
        launchOperatorTypefaceLoad(row, actor, showLoading = true)
    }

    private fun invalidateOperatorTypefaceSelection() {
        OperatorTypefaceRestorer.supersede()
        operatorTypefaceGeneration.incrementAndGet()
        operatorTypefaceJob?.cancel()
        operatorTypefaceJob = null
        operatorPendingTypeface = null
    }

    private fun launchOperatorTypefaceLoad(row: OperatorFontRow, actor: ScopedReadContext, showLoading: Boolean) {
        invalidateOperatorTypefaceSelection()
        val generation = operatorTypefaceGeneration.get()
        operatorPendingTypeface = row
        val isCurrentSelection = {
            generation == operatorTypefaceGeneration.get() && operatorFontActor === actor && row in operatorAdmittedFonts
        }
        if (showLoading) localPreferences.operatorFontLoading(row.family)
        operatorTypefaceJob = lifecycleScope.launch {
          when (val result = OperatorTypefaceLoader(this@SettingsActivity, repository).select(row, actor, isCurrentSelection)) {
            is OperatorTypefaceLoader.Result.Loaded -> {
                if (!isCurrentSelection() || operatorFontActor !== actor) return@launch
                operatorPendingTypeface = null
                binding.root.applyAppTypeface()
                localPreferences.operatorFontLoaded()
            }
            is OperatorTypefaceLoader.Result.Failed -> {
                if (isCurrentSelection() && operatorFontActor === actor) localPreferences.operatorFontFailed(result.detail)
            }
            OperatorTypefaceLoader.Result.Stale -> {
                if (isCurrentSelection() && operatorFontActor === actor) {
                    PreferredTypeface.clearLoadedOperator()
                    binding.root.applyAppTypeface()
                    localPreferences.updateOperatorFonts(emptyList(), null, loading = false)
                }
            }
          }
        }
    }

    private fun onOperatorFontActorRetired(event: AuthorityRetirement) {
        PreferredTypeface.onActorRetired(event)
        val actor = operatorFontActor ?: return
        if (!event.affects(actor)) return
        runOnUiThread {
            if (operatorFontActor === actor) {
                invalidateOperatorTypefaceSelection()
                operatorAdmittedFonts = emptyList()
                operatorFontActor = null
                operatorFontCatalogLoading = false
                operatorFontCatalogAdmitted = false
                localPreferences.updateOperatorFonts(emptyList(), null, loading = false)
                binding.root.applyAppTypeface()
            }
        }
    }

    override fun onStop() {
        operatorFontCatalogRetryJob?.cancel()
        operatorFontCatalogRetryJob = null
        super.onStop()
    }

    override fun onDestroy() {
        operatorFontsObserver?.close()
        operatorFontsObserver = null
        super.onDestroy()
    }

    private fun revealDevices(card: View, focusTarget: View?) {
        binding.settingsScroll.post {
            val rect = Rect()
            card.getDrawingRect(rect)
            binding.settingsScroll.offsetDescendantRectToMyCoords(card, rect)
            binding.settingsScroll.smoothScrollTo(0, rect.top)
            focusTarget?.requestFocus()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun renderDevicesForTest(result: com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.DevicesResponse>) {
        serverControls.renderDevices(result, repository.writesAllowed())
    }

    private fun returnToDashboard() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun bindAppearance() = with(binding) {
        themeToggle.check(
            when (nativePreferences.themeMode) {
                NativePreferences.ThemeMode.SYSTEM -> R.id.theme_system_button
                NativePreferences.ThemeMode.LIGHT -> R.id.theme_light_button
                NativePreferences.ThemeMode.DARK -> R.id.theme_dark_button
            },
        )
        themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val next = when (checkedId) {
                R.id.theme_light_button -> NativePreferences.ThemeMode.LIGHT
                R.id.theme_dark_button -> NativePreferences.ThemeMode.DARK
                else -> NativePreferences.ThemeMode.SYSTEM
            }
            if (nativePreferences.themeMode == next) return@addOnButtonCheckedListener
            nativePreferences.themeMode = next
            nativePreferences.applyTheme()
        }
    }

    private fun renderConnection(connection: Connection?) = with(binding) {
        connectionOriginValue.text = connection?.origin?.value ?: getString(R.string.settings_not_connected)
        connectionLabelValue.text = when {
            connection == null -> getString(R.string.settings_parity_unknown)
            connection.origin.value.startsWith("https://") -> getString(R.string.settings_parity_secure_yes)
            else -> getString(R.string.settings_parity_secure_no)
        }
        connectionAccessValue.text = when {
            connection == null -> getString(R.string.settings_not_connected)
            connection.isPaired -> getString(R.string.settings_access_paired)
            else -> getString(R.string.settings_access_read_only)
        }
        if (connection == null) settingsConnectionBridgeValue.setText(R.string.settings_parity_bridge_offline)
        disconnectButton.isEnabled = connection != null && !disconnecting
    }

    private fun confirmDisconnect() {
        if (repository.connection.value == null || disconnecting) return
        val sheet = CollieBottomSheetDialog(this, getString(R.string.settings_disconnect_confirm_title))
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@SettingsActivity).apply {
                setText(R.string.settings_disconnect_confirm_message)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.collie_muted))
            })
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity).apply {
                setText(R.string.settings_disconnect)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@SettingsActivity, R.color.collie_destructive),
                )
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.collie_on_destructive))
                isAllCaps = false
                setOnClickListener {
                    sheet.dismiss()
                    disconnectLocally()
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.collie_touch_target),
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.collie_card_gap) })
        }
        sheet.setSheetContent(content)
        sheet.setOnDismissListener { if (disconnectSheet === sheet) disconnectSheet = null }
        disconnectSheet = sheet
        sheet.show()
    }

    // Deletes by path, not through a field: a rotation while the chooser is open recreates this
    // activity, and the result arrives on the new instance. CollieApplication clears the same
    // directory at startup for a process that died with the chooser open.
    private val diagnosticsChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        DiagnosticsExport.exportDir(cacheDir).deleteRecursively()
    }

    private fun confirmSendDiagnostics() {
        val sheet = CollieBottomSheetDialog(this, getString(R.string.settings_diagnostics_send_confirm_title))
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@SettingsActivity).apply {
                setText(R.string.settings_diagnostics_send_confirm_message)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.collie_muted))
            })
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity).apply {
                setText(R.string.settings_diagnostics_send_title)
                isAllCaps = false
                setOnClickListener {
                    sheet.dismiss()
                    sendDiagnostics()
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.collie_touch_target),
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.collie_card_gap) })
        }
        sheet.setSheetContent(content)
        sheet.show()
    }

    private fun sendDiagnostics() {
        val writer = (application as CollieApplication).container.diagnosticsWriter
        lifecycleScope.launch {
            // Decrypting and zipping up to ~20 MB must not run on the main thread: the watchdog
            // would log the export itself as a freeze.
            val zip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DiagnosticsExport(writer, DiagnosticsExport.exportDir(cacheDir)).buildZip()
            }
            val uri = FileProvider.getUriForFile(
                this@SettingsActivity,
                "$packageName.diagnostics.fileprovider",
                zip,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            diagnosticsChooser.launch(Intent.createChooser(intent, getString(R.string.settings_diagnostics_send_title)))
        }
    }

    private fun renderServerConnection(value: SettingsConnectionPresentation) = with(binding) {
        settingsConnectionBridgeValue.text = value.bridge
        settingsConnectionBridgeValue.setTextColor(ContextCompat.getColor(
            this@SettingsActivity,
            if (value.connected) R.color.collie_done else R.color.collie_working,
        ))
        value.deviceAccess?.let { connectionAccessValue.text = it }
        serverVersionValue.text = value.serverBuild ?: getString(R.string.settings_parity_unknown)
    }

    private fun disconnectLocally() {
        if (disconnecting) return
        disconnecting = true
        binding.disconnectButton.isEnabled = false
        binding.settingsError.visibility = View.GONE
        lifecycleScope.launch {
            try {
                repository.disconnect()
                startActivity(
                    Intent(this@SettingsActivity, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
                finish()
            } catch (_: IllegalStateException) {
                disconnecting = false
                binding.settingsError.setText(R.string.settings_disconnect_failed)
                binding.settingsError.visibility = View.VISIBLE
                renderConnection(repository.connection.value)
            }
        }
    }

    companion object {
        const val EXTRA_PAIR_CODE = "com.lateapex.collie.extra.SETTINGS_PAIR_CODE"
        const val EXTRA_FOCUS_DEVICES = "com.lateapex.collie.extra.SETTINGS_FOCUS_DEVICES"

        fun intent(
            context: Context,
            pairCode: String? = null,
            focusDevices: Boolean = false,
        ): Intent = Intent(context, SettingsActivity::class.java).apply {
            pairCode?.trim()?.takeIf(String::isNotEmpty)?.let { putExtra(EXTRA_PAIR_CODE, it.uppercase(Locale.ENGLISH)) }
            if (focusDevices || pairCode != null) putExtra(EXTRA_FOCUS_DEVICES, true)
        }
    }
}
