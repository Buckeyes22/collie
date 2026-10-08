package com.lateapex.collie.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.lateapex.collie.domain.Scope

/** Device-local presentation and interaction preferences for the native client. */
class NativePreferences(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class ThemeMode(val storedValue: String, val delegateMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    var themeMode: ThemeMode
        get() {
            val stored = preferences.getString(THEME_MODE, null)
            return ThemeMode.entries.firstOrNull { it.storedValue == stored } ?: ThemeMode.SYSTEM
        }
        set(value) {
            preferences.edit().putString(THEME_MODE, value.storedValue).apply()
        }

    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(HAPTICS_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(HAPTICS_ENABLED, value).apply()
        }

    /** Gates [com.lateapex.collie.diagnostics.DiagnosticsRecorder]; capture is opt-in because
     * network bodies include terminal content. Existing explicit preferences are preserved. */
    var diagnosticsEnabled: Boolean
        get() = preferences.getBoolean(DIAGNOSTICS_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(DIAGNOSTICS_ENABLED, value).apply()
        }

    var terminalFontSize: Int
        get() = preferences.getInt(TERMINAL_FONT_SIZE, 10).coerceIn(9, 16)
        set(value) {
            preferences.edit().putInt(TERMINAL_FONT_SIZE, value.coerceIn(9, 16)).apply()
        }

    var draftFontSize: Int
        get() = preferences.getInt(DRAFT_FONT_SIZE, 14).coerceIn(13, 16)
        set(value) {
            preferences.edit().putInt(DRAFT_FONT_SIZE, value.coerceIn(13, 16)).apply()
        }

    var handsFreeEnabled: Boolean
        get() = preferences.getBoolean(HANDS_FREE_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(HANDS_FREE_ENABLED, value).apply()
        }

    var dashboardRecentOpen: Boolean
        get() = preferences.getBoolean(DASHBOARD_RECENT_OPEN, true)
        set(value) { preferences.edit().putBoolean(DASHBOARD_RECENT_OPEN, value).apply() }

    var dashboardRecentNewest: Boolean
        get() = preferences.getString(DASHBOARD_RECENT_SORT, "newest") != "oldest"
        set(value) {
            preferences.edit().putString(DASHBOARD_RECENT_SORT, if (value) "newest" else "oldest").apply()
        }

    var dashboardSpacesOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_SPACES_OPEN)) {
            preferences.getBoolean(DASHBOARD_SPACES_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_SPACES_OPEN) else editor.putBoolean(DASHBOARD_SPACES_OPEN, value)
            editor.apply()
        }

    var dashboardLaunchOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_LAUNCH_OPEN)) {
            preferences.getBoolean(DASHBOARD_LAUNCH_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_LAUNCH_OPEN) else editor.putBoolean(DASHBOARD_LAUNCH_OPEN, value)
            editor.apply()
        }

    var dashboardShellsOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_SHELLS_OPEN)) {
            preferences.getBoolean(DASHBOARD_SHELLS_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_SHELLS_OPEN) else editor.putBoolean(DASHBOARD_SHELLS_OPEN, value)
            editor.apply()
        }

    var paneStripsVisible: Boolean
        get() = preferences.getBoolean(PANE_STRIPS_VISIBLE, true)
        set(value) {
            preferences.edit().putBoolean(PANE_STRIPS_VISIBLE, value).apply()
        }

    var dashboardScope: Scope
        get() {
            val viewAll = preferences.getBoolean(DASHBOARD_SCOPE_VIEW_ALL, false)
            return Scope(
                host = preferences.getString(DASHBOARD_SCOPE_HOST, null)?.takeIf(String::isNotBlank),
                session = preferences.getString(DASHBOARD_SCOPE_SESSION, null)
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { viewAll },
                viewAll = viewAll,
            )
        }
        set(value) {
            preferences.edit().apply {
                if (value.host == null) remove(DASHBOARD_SCOPE_HOST)
                else putString(DASHBOARD_SCOPE_HOST, value.host)
                if (value.session == null || value.viewAll) remove(DASHBOARD_SCOPE_SESSION)
                else putString(DASHBOARD_SCOPE_SESSION, value.session)
                putBoolean(DASHBOARD_SCOPE_VIEW_ALL, value.viewAll)
            }.apply()
        }

    fun paneDraft(key: String): String {
        volatileDrafts[key]?.let { return it }
        val valueKey = "$PANE_DRAFT_PREFIX$key"
        val savedKey = "$PANE_DRAFT_SAVED_PREFIX$key"
        val value = preferences.getString(valueKey, "").orEmpty()
        if (value.isEmpty()) return ""
        val savedAt = preferences.getLong(savedKey, 0L)
        if (savedAt == 0L) {
            preferences.edit().putLong(savedKey, now()).apply()
            return value
        }
        if (now() - savedAt <= PANE_DRAFT_TTL_MS) return value
        preferences.edit().remove(valueKey).remove(savedKey).apply()
        return ""
    }

    fun savePaneDraft(key: String, value: String) {
        val valueKey = "$PANE_DRAFT_PREFIX$key"
        val savedKey = "$PANE_DRAFT_SAVED_PREFIX$key"
        val editor = preferences.edit()
        when {
            value.isBlank() -> {
                volatileDrafts.remove(key)
                editor.remove(valueKey).remove(savedKey)
            }
            !fitsPaneDraftStore(value) -> {
                volatileDrafts[key] = value
                editor.remove(valueKey).remove(savedKey)
            }
            else -> {
                volatileDrafts.remove(key)
                editor.putString(valueKey, value).putLong(savedKey, now())
            }
        }
        editor.apply()
    }

    fun fitsPaneDraftStore(value: String): Boolean = value.length <= MAX_PERSISTED_DRAFT_CHARS

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode.delegateMode)
    }

    companion object {
        internal const val PREFERENCES = "collie_native_preferences"
        internal const val THEME_MODE = "theme_mode"
        internal const val HAPTICS_ENABLED = "haptics_enabled"
        internal const val DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        internal const val TERMINAL_FONT_SIZE = "terminal_font_size"
        internal const val DRAFT_FONT_SIZE = "draft_font_size"
        internal const val HANDS_FREE_ENABLED = "hands_free_enabled"
        internal const val PANE_DRAFT_PREFIX = "pane_draft:"
        internal const val PANE_DRAFT_SAVED_PREFIX = "pane_draft_saved:"
        internal const val MAX_PERSISTED_DRAFT_CHARS = 8_192
        internal const val PANE_DRAFT_TTL_MS = 48L * 60L * 60L * 1_000L
        internal const val DASHBOARD_RECENT_OPEN = "dashboard_recent_open"
        internal const val DASHBOARD_RECENT_SORT = "dashboard_recent_sort"
        internal const val DASHBOARD_SPACES_OPEN = "dashboard_spaces_open"
        internal const val DASHBOARD_LAUNCH_OPEN = "dashboard_launch_open"
        internal const val DASHBOARD_SHELLS_OPEN = "dashboard_shells_open"
        internal const val PANE_STRIPS_VISIBLE = "pane_strips_visible"
        internal const val DASHBOARD_SCOPE_HOST = "dashboard_scope_host"
        internal const val DASHBOARD_SCOPE_SESSION = "dashboard_scope_session"
        internal const val DASHBOARD_SCOPE_VIEW_ALL = "dashboard_scope_view_all"
        private val volatileDrafts = mutableMapOf<String, String>()
    }
}
