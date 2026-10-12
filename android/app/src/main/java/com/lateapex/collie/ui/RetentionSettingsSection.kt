package com.lateapex.collie.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.OfflineContentStore
import com.lateapex.collie.data.custodyOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local consent, independent of terminal and device policy. No HTTP request is made here. */
internal class RetentionSettingsSection(
    private val activity: AppCompatActivity,
    private val preferences: NativePreferences,
    private val repository: CollieRepository,
) {
    private val store get() = preferences.offlineContentStore
    private lateinit var usage: TextView
    private lateinit var legacy: TextView
    private lateinit var status: TextView
    private lateinit var drafts: MaterialSwitch
    private val choices = mutableListOf<Pair<OfflineContentStore.Retention, MaterialButton>>()
    private var updating = false

    fun bind(parent: LinearLayout) {
        val panel = LinearLayout(activity).apply { id = R.id.settings_offline_retention; orientation = LinearLayout.VERTICAL }
        panel.addView(label(R.string.offline_retention_title).also { ViewCompat.setAccessibilityHeading(it, true) })
        panel.addView(label(R.string.offline_retention_description))
        for ((value, title) in listOf(OfflineContentStore.Retention.OFF to R.string.offline_retention_off,
            OfflineContentStore.Retention.ONE_DAY to R.string.offline_retention_one_day,
            OfflineContentStore.Retention.SEVEN_DAYS to R.string.offline_retention_seven_days)) {
            val button = button(title) { perform { store.chooseRetention(value) } }
            button.id = when (value) {
                OfflineContentStore.Retention.OFF -> R.id.settings_offline_retention_off
                OfflineContentStore.Retention.ONE_DAY -> R.id.settings_offline_retention_one_day
                OfflineContentStore.Retention.SEVEN_DAYS -> R.id.settings_offline_retention_seven_days
            }
            choices += value to button; panel.addView(button)
        }
        usage = label(R.string.offline_usage); panel.addView(usage)
        panel.addView(button(R.string.offline_clear_reads) { perform(store::clearReads) })
        panel.addView(label(R.string.offline_draft_description))
        drafts = MaterialSwitch(activity).apply {
            id = R.id.settings_saved_drafts_switch
            setText(R.string.offline_draft_opt_in)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, enabled -> if (!updating) perform {
                if (enabled) store.chooseDrafts(true) else preferences.clearRetainedDrafts() && store.chooseDrafts(false)
            } }
        }
        panel.addView(drafts)
        panel.addView(button(R.string.offline_clear_drafts) { perform(preferences::clearRetainedDrafts) })
        legacy = label(R.string.offline_legacy_drafts); panel.addView(legacy)
        panel.addView(button(R.string.offline_migrate_drafts) {
            val original = repository.readContext()
            val scope = original?.let(repository::retentionScope)?.takeIf { it.subject != null && !it.viewAll }
            if (original == null || scope == null || !store.draftsEnabled) { status.setText(R.string.offline_migration_owner_required); return@button }
            perform { preferences.migrateLegacyDrafts(original.custodyOwner, scope) { repository.isCurrent(original) } }
        })
        panel.addView(button(R.string.offline_discard_legacy) {
            val sheet = CollieBottomSheetDialog(activity, activity.getString(R.string.offline_discard_legacy), initiallyExpanded = true)
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(label(R.string.offline_discard_legacy_message))
            content.addView(button(R.string.offline_discard_legacy) { sheet.dismiss(); perform(preferences::discardLegacyDrafts) })
            sheet.setSheetContent(content); sheet.show()
        })
        status = TextView(activity).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(status)
        parent.addView(panel)
        refresh()
    }

    private fun perform(action: () -> Boolean) {
        activity.lifecycleScope.launch {
            val successful = withContext(Dispatchers.IO) { action() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            status.text = if (successful) "" else activity.getString(R.string.offline_storage_unavailable)
            refresh()
        }
    }
    private fun refresh() {
        updating = true
        choices.forEach { (value, button) -> button.isSelected = value == store.retention }
        drafts.isChecked = store.draftsEnabled
        val (bytes, count) = store.storageUsage()
        usage.text = activity.getString(R.string.offline_usage, bytes, count)
        legacy.text = activity.getString(R.string.offline_legacy_drafts, preferences.legacyDraftCount)
        if (!store.available) status.setText(R.string.offline_storage_unavailable)
        updating = false
    }
    private fun label(id: Int): TextView = TextView(activity).apply { setText(id); textSize = 14f }
    private fun button(id: Int, click: () -> Unit): MaterialButton = MaterialButton(activity).apply {
        setText(id); isAllCaps = false
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
