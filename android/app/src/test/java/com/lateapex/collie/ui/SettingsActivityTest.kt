package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.EncryptedConnectionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        (context.applicationContext as CollieApplication).container.connectionStore.clear()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun resetTheme() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun settingsIsSecureScrollableAndShowsHonestDisconnectedState() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
        )
        assertTrue(activity.findViewById<ScrollView>(R.id.settings_scroll).isVerticalScrollBarEnabled)
        assertEquals(
            activity.getString(R.string.settings_not_connected),
            activity.findViewById<android.widget.TextView>(R.id.connection_access_value).text,
        )
        assertEquals(activity.getString(R.string.settings_parity_unknown), activity.findViewById<android.widget.TextView>(R.id.server_version_value).text)
        assertEquals("Server version", activity.getString(R.string.settings_parity_server_build))
        assertEquals(
            "Android app build ${BuildConfig.VERSION_NAME}",
            activity.findViewById<android.widget.TextView>(R.id.settings_build_stamp).text,
        )
        assertEquals("Update Collie", activity.getString(R.string.updates_native_card_title))
        assertFalse(activity.findViewById<android.view.View>(R.id.disconnect_button).isEnabled)
    }

    @Test
    fun controlsReflectAndPersistDevicePreferences() {
        NativePreferences(context).themeMode = NativePreferences.ThemeMode.DARK
        // The preference listeners are bound during create; forcing Robolectric's synthetic window
        // layout here loops in API 35 ImageView night-mode layout and does not exercise this contract.
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        val themes = activity.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.theme_toggle,
        )
        assertEquals(R.id.theme_dark_button, themes.checkedButtonId)

        val haptics = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.haptics_switch,
        )
        assertTrue(haptics.isChecked)
        haptics.performClick()
        assertFalse(NativePreferences(context).hapticsEnabled)
    }

    @Test
    fun completeLocalAndServerSettingsSurfacesAreReachable() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_hands_free_switch))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_zen_switch))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_notify_blocked_switch))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_snooze_30))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_devices_list))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_updates_button))
        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_pack_button))
    }

    @Test
    fun cardsFollowCanonicalSettingsOrderAndCapabilityGatesStartClosed() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val content = activity.findViewById<ScrollView>(R.id.settings_scroll).getChildAt(0) as LinearLayout

        assertEquals(
            listOf(
                R.id.settings_theme_card,
                R.id.settings_local_preferences,
                R.id.settings_haptics_card,
                R.id.settings_behavior_preferences,
                R.id.settings_server_controls,
                R.id.settings_connection_card,
                R.id.settings_build_stamp,
            ),
            (0 until content.childCount).map { content.getChildAt(it).id },
        )
        val local = activity.findViewById<LinearLayout>(R.id.settings_local_preferences)
        assertEquals(
            listOf(R.id.settings_parity_terminal_font_card),
            (0 until local.childCount).map { local.getChildAt(it).id },
        )
        val behavior = activity.findViewById<LinearLayout>(R.id.settings_behavior_preferences)
        assertEquals(listOf(R.id.settings_parity_hands_free_card, R.id.settings_parity_zen_card), (0 until behavior.childCount).map { behavior.getChildAt(it).id })
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings_parity_hands_free_card).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings_parity_pack_card).visibility)
        assertEquals(null, activity.findViewById<View>(R.id.settings_server_diagnostics))
        assertTrue(activity.findViewById<View>(R.id.settings_updates_button) is LinearLayout)
        assertTrue(activity.findViewById<View>(R.id.settings_pack_button) is LinearLayout)
    }

    @Test
    fun settingsCardsCarryWebStyleIdentityIconsAndCanonicalHeaderCopy() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_header_title).text)
        assertEquals("Back", activity.findViewById<View>(R.id.settings_back_button).contentDescription)
        assertEquals("Updates", activity.getString(R.string.updates_title))
        assertEquals("Pack overview", activity.getString(R.string.settings_pack_title))
        assertEquals("Diagnostics for this device.", activity.getString(R.string.settings_connection_description))

        listOf(
            R.id.settings_theme_card,
            R.id.settings_parity_terminal_font_card,
            R.id.settings_haptics_card,
            R.id.settings_parity_hands_free_card,
            R.id.settings_parity_zen_card,
            R.id.settings_parity_push_card,
            R.id.settings_parity_notify_card,
            R.id.settings_parity_snooze_card,
            R.id.settings_parity_updates_card,
            R.id.settings_parity_devices_card,
            R.id.settings_parity_pack_card,
            R.id.settings_connection_card,
        ).forEach { cardId ->
            val icon = firstImage(activity.findViewById(cardId))
            assertNotNull("card $cardId should expose a leading identity icon", icon)
            assertNotNull("card $cardId icon should have a native drawable", icon?.drawable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon?.importantForAccessibility)
        }
    }

    @Test
    fun settingsHeaderAndInteractiveRowsPreserveTheFortyFourDpTapFloor() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)
        val back = activity.findViewById<View>(R.id.settings_back_button)

        assertEquals(floor, back.layoutParams.width)
        assertEquals(floor, back.layoutParams.height)
        listOf(
            R.id.theme_system_button,
            R.id.theme_light_button,
            R.id.theme_dark_button,
            R.id.haptics_switch,
            R.id.settings_hands_free_switch,
            R.id.settings_zen_switch,
        ).forEach { viewId ->
            val view = activity.findViewById<View>(viewId)
            assertTrue("view $viewId should retain a 44dp height", view.layoutParams.height >= floor)
        }
        assertTrue(activity.findViewById<View>(R.id.settings_updates_button).minimumHeight >= floor)
        assertTrue(activity.findViewById<View>(R.id.settings_pack_button).minimumHeight >= floor)
    }

    @Test
    fun dashboardGearNavigatesToSettingsActivity() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        activity.findViewById<android.view.View>(R.id.settings_button).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun setupGearAlsoNavigatesToSettingsActivity() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        activity.findViewById<android.view.View>(R.id.setup_settings_button).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun settingsEntryExtrasAreNormalizedAndConsumedOnlyOnce() {
        val intent = SettingsActivity.intent(context, pairCode = " ab12-cd34 ", focusDevices = true)

        assertEquals(SettingsEntryRequest("AB12-CD34", focusDevices = true), SettingsEntryRequest.consume(intent))
        assertFalse(intent.hasExtra(SettingsActivity.EXTRA_PAIR_CODE))
        assertFalse(intent.hasExtra(SettingsActivity.EXTRA_FOCUS_DEVICES))
        assertEquals(SettingsEntryRequest(null, focusDevices = false), SettingsEntryRequest.consume(intent))
    }

    @Test
    fun settingsDeepLinkPassesExplicitPairingEntryExtras() {
        val uri = Uri.parse(BuildConfig.DEFAULT_ORIGIN).buildUpon()
            .path("/settings")
            .appendQueryParameter("pair", "ab12-cd34")
            .fragment("paired-devices")
            .build()
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).setData(uri),
        ).create().get()

        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, settings.component?.className)
        assertEquals("AB12-CD34", settings.getStringExtra(SettingsActivity.EXTRA_PAIR_CODE))
        assertTrue(settings.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_DEVICES, false))
    }

    private fun firstImage(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                firstImage(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
