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
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.lateapex.collie.diagnostics.DiagnosticsExport
import kotlinx.coroutines.launch

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

    private val repository
        get() = (application as CollieApplication).container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.applySafeContentInsets(binding.root)
        val entry = SettingsEntryRequest.consume(intent)

        nativePreferences = NativePreferences(this)
        binding.settingsBackButton.setOnClickListener { returnToDashboard() }
        bindAppearance()
        localPreferences = SettingsLocalPreferences(this, nativePreferences).also {
            it.bindDisplay(binding.settingsLocalPreferences)
            it.bindBehavior(binding.settingsBehaviorPreferences)
        }
        serverControls = SettingsServerControls(
            this,
            repository,
            onConnection = ::renderServerConnection,
            onSpeechCapability = localPreferences::setHandsFreeCapability,
            initialPairCode = entry.pairCode,
            focusDevices = entry.focusDevices,
            onRevealDevices = ::revealDevices,
        ).also { it.bind(binding.settingsServerControls, binding.settingsPairingHost) }
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
        binding.root.findViewById<View>(R.id.settings_diagnostics_send_button)?.setOnClickListener {
            confirmSendDiagnostics()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.connection.collect(::renderConnection)
            }
        }
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

    private val diagnosticsChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingDiagnosticsExportDir?.deleteRecursively()
        pendingDiagnosticsExportDir = null
    }
    private var pendingDiagnosticsExportDir: java.io.File? = null

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
        val exportDir = java.io.File(cacheDir, "diagnostics-export")
        val writer = (application as CollieApplication).container.diagnosticsWriter
        val zip = DiagnosticsExport(writer, exportDir).buildZip()
        pendingDiagnosticsExportDir = exportDir
        val uri = FileProvider.getUriForFile(
            this,
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
