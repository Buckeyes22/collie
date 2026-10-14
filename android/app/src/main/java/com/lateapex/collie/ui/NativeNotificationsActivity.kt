package com.lateapex.collie.ui

import android.content.Intent
import android.provider.Settings
import android.widget.ScrollView
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.CollieApplication
import kotlinx.coroutines.launch

/** Existing server notification preferences/snooze remain in Settings; this owns native enrollment. */
class NativeNotificationsActivity : AppCompatActivity() {
    private val runtime get() = (application as CollieApplication).container.nativePush
    private lateinit var status: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else status.text = getString(R.string.native_notifications_permission)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        title = getString(R.string.native_notifications_title)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        status = TextView(this).apply { text = runtime.status(); textSize = 18f }
        content.addView(status)
        content.addView(TextView(this).apply { text = getString(R.string.native_notifications_setup) })
        content.addView(MaterialButton(this).apply { text = getString(R.string.native_notifications_enable); setOnClickListener {
            if (ContextCompat.checkSelfPermission(this@NativeNotificationsActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) enable()
            else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } })
        content.addView(MaterialButton(this).apply { text = getString(R.string.native_notifications_disable); setOnClickListener {
            lifecycleScope.launch { runtime.disable(); status.text = runtime.status() }
        } })
        content.addView(MaterialButton(this).apply {
            text = getString(R.string.native_notifications_channel_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, com.lateapex.collie.notifications.NativePushRuntime.CHANNEL))
            }
        })
        val root = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(root)
        window.applySafeContentInsets(root)
        root.applyAppTypeface()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.statusChanges.collect { status.text = runtime.status() }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) status.text = runtime.status() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun enable() { lifecycleScope.launch { status.text = if (runtime.enable(this@NativeNotificationsActivity)) runtime.status() else getString(R.string.native_notifications_unavailable) } }
}
