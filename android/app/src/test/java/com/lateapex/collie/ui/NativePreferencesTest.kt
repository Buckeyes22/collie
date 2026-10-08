package com.lateapex.collie.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.Scope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun resetTheme() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun defaultsFollowTheSystemAndEnableHaptics() {
        val preferences = NativePreferences(context)

        assertEquals(NativePreferences.ThemeMode.SYSTEM, preferences.themeMode)
        assertTrue(preferences.hapticsEnabled)
    }

    @Test
    fun diagnosticsCaptureDefaultsToOffAndPersists() {
        val preferences = NativePreferences(context)
        assertFalse(preferences.diagnosticsEnabled)

        preferences.diagnosticsEnabled = true
        assertTrue(NativePreferences(context).diagnosticsEnabled)
    }

    @Test
    fun themeAndHapticsSurviveANewPreferencesInstance() {
        NativePreferences(context).apply {
            themeMode = NativePreferences.ThemeMode.LIGHT
            hapticsEnabled = false
        }

        val restored = NativePreferences(context)
        assertEquals(NativePreferences.ThemeMode.LIGHT, restored.themeMode)
        assertFalse(restored.hapticsEnabled)

        restored.applyTheme()
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    @Test
    fun unknownStoredThemeFailsBackToTheSystem() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(NativePreferences.THEME_MODE, "future-mode")
            .commit()

        assertEquals(NativePreferences.ThemeMode.SYSTEM, NativePreferences(context).themeMode)
    }

    @Test
    fun completePresentationPreferencesPersistAndClamp() {
        NativePreferences(context).apply {
            terminalFontSize = 99
            draftFontSize = 1
            handsFreeEnabled = true
        }

        val restored = NativePreferences(context)
        assertEquals(16, restored.terminalFontSize)
        assertEquals(13, restored.draftFontSize)
        assertTrue(restored.handsFreeEnabled)

    }

    @Test
    fun dashboardDisclosureAndSortPreferencesPersistWithAnUnsetSpacesDefault() {
        val defaults = NativePreferences(context)
        assertTrue(defaults.dashboardRecentOpen)
        assertTrue(defaults.dashboardRecentNewest)
        assertEquals(null, defaults.dashboardSpacesOpen)
        assertEquals(null, defaults.dashboardLaunchOpen)
        assertEquals(null, defaults.dashboardShellsOpen)
        assertTrue(defaults.paneStripsVisible)

        defaults.dashboardRecentOpen = false
        defaults.dashboardRecentNewest = false
        defaults.dashboardSpacesOpen = false
        defaults.dashboardLaunchOpen = false
        defaults.dashboardShellsOpen = false
        defaults.paneStripsVisible = false

        val restored = NativePreferences(context)
        assertFalse(restored.dashboardRecentOpen)
        assertFalse(restored.dashboardRecentNewest)
        assertEquals(false, restored.dashboardSpacesOpen)
        assertEquals(false, restored.dashboardLaunchOpen)
        assertEquals(false, restored.dashboardShellsOpen)
        assertFalse(restored.paneStripsVisible)

        restored.dashboardSpacesOpen = null
        restored.dashboardLaunchOpen = null
        restored.dashboardShellsOpen = null
        assertEquals(null, NativePreferences(context).dashboardSpacesOpen)
        assertEquals(null, NativePreferences(context).dashboardLaunchOpen)
        assertEquals(null, NativePreferences(context).dashboardShellsOpen)
    }

    @Test
    fun dashboardScopeSurvivesANewPreferencesInstanceAndViewAllDropsAStaleSession() {
        val preferences = NativePreferences(context)
        assertEquals(Scope(), preferences.dashboardScope)

        preferences.dashboardScope = Scope(host = "workshop", session = "review")
        assertEquals(
            Scope(host = "workshop", session = "review"),
            NativePreferences(context).dashboardScope,
        )

        context.getSharedPreferences(NativePreferences.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NativePreferences.DASHBOARD_SCOPE_VIEW_ALL, true)
            .apply()
        assertEquals(
            Scope(host = "workshop", viewAll = true),
            NativePreferences(context).dashboardScope,
        )
    }

    @Test
    fun paneDraftsExpireAfterFortyEightHoursAndOversizedDraftsStayMemoryOnly() {
        var now = 1_000L
        val preferences = NativePreferences(context) { now }
        val smallKey = "small-${System.nanoTime()}"
        preferences.savePaneDraft(smallKey, "keep me")
        assertEquals("keep me", NativePreferences(context) { now }.paneDraft(smallKey))

        now += NativePreferences.PANE_DRAFT_TTL_MS + 1
        assertEquals("", NativePreferences(context) { now }.paneDraft(smallKey))

        val largeKey = "large-${System.nanoTime()}"
        val large = "x".repeat(NativePreferences.MAX_PERSISTED_DRAFT_CHARS + 1)
        preferences.savePaneDraft(largeKey, large)
        assertEquals(large, NativePreferences(context) { now }.paneDraft(largeKey))
        assertFalse(
            context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
                .contains("${NativePreferences.PANE_DRAFT_PREFIX}$largeKey"),
        )
    }
}
