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
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
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
    fun diagnosticsSwitchDefaultsToOffAndTogglesThePreference() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        val diagnosticsSwitch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.settings_diagnostics_switch,
        )
        assertFalse(diagnosticsSwitch.isChecked)
        diagnosticsSwitch.performClick()
        assertTrue(NativePreferences(context).diagnosticsEnabled)
    }

    @Test
    fun sendDiagnosticsButtonOpensAConfirmSheetNamingSensitiveContent() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        val button = activity.findViewById<View>(R.id.settings_diagnostics_send_button)
        assertNotNull(button)
        button.performClick()

        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
    }

    @Test
    fun completeLocalAndServerSettingsSurfacesAreReachable() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        assertNotNull(activity.findViewById<android.view.View>(R.id.settings_hands_free_switch))
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
                R.id.settings_pairing_host,
                R.id.settings_theme_card,
                R.id.settings_local_preferences,
                R.id.settings_haptics_card,
                R.id.settings_behavior_preferences,
                R.id.settings_server_controls,
                R.id.settings_connection_card,
                R.id.settings_agent_catalog_button,
                R.id.settings_licenses_button,
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
        assertEquals(
            listOf(R.id.settings_parity_hands_free_card, R.id.settings_diagnostics_card, R.id.settings_offline_retention),
            (0 until behavior.childCount).map { behavior.getChildAt(it).id },
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings_parity_hands_free_card).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settings_parity_pack_card).visibility)
        assertEquals(null, activity.findViewById<View>(R.id.settings_server_diagnostics))
        assertTrue(activity.findViewById<View>(R.id.settings_updates_button) is LinearLayout)
        assertTrue(activity.findViewById<View>(R.id.settings_pack_button) is LinearLayout)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settings_agent_catalog_button).visibility)
        assertTrue(activity.findViewById<View>(R.id.settings_offline_retention) is LinearLayout)
        assertFalse(activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settings_saved_drafts_switch).isChecked)
        assertEquals(com.lateapex.collie.data.OfflineContentStore.Retention.OFF, NativePreferences(activity).offlineContentStore.retention)
        assertFalse(NativePreferences(activity).offlineContentStore.draftsEnabled)
    }

    @Test
    fun actualRetentionChoicesAndDraftSwitchRemainIndependentAndDefaultOff() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        val store = NativePreferences(activity).offlineContentStore
        val drafts = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settings_saved_drafts_switch)
        fun awaitChoice(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) {
                shadowOf(activity.mainLooper).idle()
                if (!predicate()) Thread.sleep(5)
            }
            shadowOf(activity.mainLooper).idle()
            assertTrue(message, predicate())
        }
        try {
            assertEquals(com.lateapex.collie.data.OfflineContentStore.Retention.OFF, store.retention)
            assertFalse(drafts.isChecked); assertFalse(store.draftsEnabled)
            activity.findViewById<View>(R.id.settings_offline_retention_one_day).performClick()
            awaitChoice("actual read choice commits without enabling saved drafts") {
                store.retention == com.lateapex.collie.data.OfflineContentStore.Retention.ONE_DAY &&
                    activity.findViewById<View>(R.id.settings_offline_retention_one_day).isSelected
            }
            assertFalse(drafts.isChecked); assertFalse(store.draftsEnabled)
            drafts.isChecked = true
            awaitChoice("actual independent draft consent commits") { store.draftsEnabled && drafts.isChecked }
            assertEquals(com.lateapex.collie.data.OfflineContentStore.Retention.ONE_DAY, store.retention)
            activity.findViewById<View>(R.id.settings_offline_retention_off).performClick()
            awaitChoice("turning reads Off keeps the independent draft choice") {
                store.retention == com.lateapex.collie.data.OfflineContentStore.Retention.OFF &&
                    activity.findViewById<View>(R.id.settings_offline_retention_off).isSelected
            }
            assertTrue(drafts.isChecked); assertTrue(store.draftsEnabled)
            drafts.isChecked = false
            awaitChoice("the actual draft switch returns only its own policy to Off") { !store.draftsEnabled && !drafts.isChecked }
            assertEquals(com.lateapex.collie.data.OfflineContentStore.Retention.OFF, store.retention)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun settingsCardsCarryWebStyleIdentityIconsAndCanonicalHeaderCopy() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_header_title).text)
        assertEquals("Back", activity.findViewById<View>(R.id.settings_back_button).contentDescription)
        assertEquals("Updates", activity.getString(R.string.updates_title))
        assertEquals("Crew overview", activity.getString(R.string.settings_pack_title))
        assertEquals("Diagnostics for this device.", activity.getString(R.string.settings_connection_description))

        listOf(
            R.id.settings_theme_card,
            R.id.settings_parity_terminal_font_card,
            R.id.settings_haptics_card,
            R.id.settings_parity_hands_free_card,
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
    fun settingsHeaderAndInteractiveRowsPreserveTheFortyEightDpTapFloor() {
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
        ).forEach { viewId ->
            val view = activity.findViewById<View>(viewId)
            assertTrue("view $viewId should retain a 48dp height", view.layoutParams.height >= floor)
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
    fun freshInstallHasNoPrivateOriginAndIgnoresUnconfiguredDeepLinks() {
        val app = context.applicationContext as CollieApplication
        val deadline = System.nanoTime() + 1_000_000_000L
        while (app.container.connectionStore.connection.value != null && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield()
        }
        assertNull(app.container.connectionStore.connection.value)
        val ordinary = Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals("", ordinary.get().findViewById<android.widget.EditText>(R.id.origin_input).text.toString())
        ordinary.destroy()
        val before = app.container.connectionStore.captureCommitted()
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("https://collie.example.com/pane/w1:p1/history")),
        ).create()
        val activity = controller.get()
        assertNull(activity.intent.data)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
        assertEquals("An unpaired protected link must not configure a private server", before, app.container.connectionStore.captureCommitted())
        controller.destroy()
    }

    @Test
    fun settingsDeepLinkRejectsAnotherServerAfterConnectionIsConfigured() {
        seedConnection(paired = false)
        val app = context.applicationContext as CollieApplication
        val original = requireNotNull(app.container.connectionStore.captureCommitted()?.connection)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (app.container.connectionStore.connection.value !== original && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield()
        }
        assertTrue(app.container.connectionStore.connection.value === original)
        val before = app.container.connectionStore.captureCommitted()
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("https://other.example.com/settings?pair=ABCD-EF23")),
        ).create()
        val activity = controller.get()
        assertNull(activity.intent.data)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
        assertEquals("A foreign pairing link must preserve the existing configured owner", before, app.container.connectionStore.captureCommitted())
        controller.destroy()
    }

    @Test
    fun settingsDeepLinkPassesExplicitPairingEntryExtras() {
        seedConnection(paired = false)
        val app = context.applicationContext as CollieApplication
        val original = requireNotNull(app.container.connectionStore.captureCommitted()?.connection)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (app.container.connectionStore.connection.value !== original && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield()
        }
        assertTrue(app.container.connectionStore.connection.value === original)
        val before = app.container.connectionStore.captureCommitted()
        val uri = Uri.parse("https://collie.example.com/").buildUpon()
            .path("/settings")
            .appendQueryParameter("pair", "abcd-ef23")
            .fragment("paired-devices")
            .build()
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).setData(uri),
        ).create()
        val activity = controller.get()
        assertNull(activity.intent.data)
        val previewIntent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(LinkImportActivity::class.java.name, previewIntent.component?.className)
        assertNull(previewIntent.data)
        assertEquals(setOf(LinkImportHandoff.EXTRA_ID), previewIntent.extras!!.keySet())
        val preview = Robolectric.buildActivity(LinkImportActivity::class.java, previewIntent).create().start().resume()
        assertFalse(preview.get().findViewById<android.widget.TextView>(R.id.link_import_status).text.contains("ABCD-EF23"))
        assertNull(shadowOf(preview.get()).nextStartedActivity)
        assertTrue(preview.get().findViewById<android.view.View>(R.id.link_import_continue).performClick())
        val confirmedIntent = requireNotNull(shadowOf(preview.get()).nextStartedActivity)
        assertNull(confirmedIntent.data)
        assertEquals(setOf(LinkImportHandoff.EXTRA_ID), confirmedIntent.extras!!.keySet())
        val confirmedMain = Robolectric.buildActivity(MainActivity::class.java, confirmedIntent).create()
        val settings = requireNotNull(shadowOf(confirmedMain.get()).nextStartedActivity)
        assertEquals(SettingsActivity::class.java.name, settings.component?.className)
        assertNull(settings.data)
        assertFalse(settings.hasExtra(SettingsActivity.EXTRA_PAIR_CODE))
        assertTrue(settings.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_DEVICES, false))
        val imported = requireNotNull(LinkImportHandoff.consumeConfirmed(settings, app))
        assertEquals("ABCD-EF23", (imported.candidate as LinkImportCoordinator.Candidate.Pairing).code)
        assertNull(LinkImportHandoff.consumeConfirmed(settings, app))
        assertEquals("Preview and Continue only fill a guarded pairing form; neither changes credentials", before, app.container.connectionStore.captureCommitted())
        confirmedMain.destroy(); preview.pause().stop().destroy(); controller.destroy()
    }

    @Test
    fun unpairedSettingsLeadsWithThePairingCard() {
        val activity = launchSettings(paired = false)
        // A.9: the pairing card is the first card on the screen, above Appearance.
        val host = activity.findViewById<LinearLayout>(R.id.settings_pairing_host)
        assertEquals(R.id.settings_parity_pairing_card, host.getChildAt(0).id)
        val page = host.parent as LinearLayout
        assertEquals(0, page.indexOfChild(host))
        assertEquals("S25U-native", activity.findViewById<android.widget.EditText>(R.id.settings_pair_label).text.toString())
        assertNull(activity.findViewById<View?>(R.id.settings_parity_push_card))
    }

    @Test
    fun pairingCardMatchesTheSetupScreenAndKeepsTheCardGap() {
        // S25 Ultra, 2026-09-10: the card sat flush on Appearance and its primary action was an
        // outlined pill where the setup screen uses the filled button for the same action.
        val activity = launchSettings(paired = false)
        val submit = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.settings_pair_button)
        assertEquals(
            androidx.core.content.ContextCompat.getColor(activity, R.color.collie_primary),
            submit.backgroundTintList!!.defaultColor,
        )
        assertEquals(
            (12 * activity.resources.displayMetrics.density).toInt(),
            (submit.layoutParams as ViewGroup.MarginLayoutParams).topMargin,
        )
        val card = activity.findViewById<View>(R.id.settings_parity_pairing_card)
        assertEquals(
            activity.resources.getDimensionPixelSize(R.dimen.collie_card_gap),
            (card.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin,
        )
    }

    @Test
    fun pairingFormsAreHiddenFromAutofill() {
        // Bitwarden offered "Save password?" for the one-time pairing code; its system dialog held
        // focus and blocked 36 device tests on the S25 Ultra (2026-09-11). A single-use code is not
        // a credential to store, so neither pairing form takes part in autofill.
        val settings = launchSettings(paired = false)
        assertEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            settings.findViewById<View>(R.id.settings_parity_pairing_card).importantForAutofill,
        )
        val setup = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            setup.findViewById<View>(R.id.setup_panel).importantForAutofill,
        )
        // Bitwarden ignores the flag and keys on a password-type field, so the one-time code is
        // plain capitals on both forms, never a password variant.
        val passwordVariations = setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        listOf(
            setup.findViewById<android.widget.EditText>(R.id.pairing_code_input),
            settings.findViewById<android.widget.EditText>(R.id.settings_pair_code),
        ).forEach { field ->
            assertFalse(field.inputType and android.text.InputType.TYPE_MASK_VARIATION in passwordVariations)
        }
    }

    @Test
    fun pairedSettingsHasNoPairingCardAndNoZenCard() {
        val activity = launchSettings(paired = true)
        assertNull(activity.findViewById<View?>(R.id.settings_parity_pairing_card))
        assertNull(activity.findViewById<View?>(R.id.settings_parity_zen_card))
    }

    @Test
    fun disconnectIsARedFilledButton() {
        val activity = launchSettings(paired = true)
        val button = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.disconnect_button)
        assertEquals(
            androidx.core.content.ContextCompat.getColor(activity, R.color.collie_destructive),
            button.backgroundTintList!!.defaultColor,
        )
    }

    @Test
    fun ownDeviceRowSaysUnpair() {
        val activity = launchSettings(paired = true, devices = listOf(device("S25U-native", current = true)))
        val row = activity.findViewById<View>(R.id.settings_device_current_action) as TextView
        assertEquals(activity.getString(R.string.settings_unpair), row.text.toString())
    }

    @Test
    fun pairingFieldIsOutlined() {
        val activity = launchSettings(paired = false)
        assertNotNull(activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.settings_pair_input_layout))
    }

    @Test
    fun aTokenTheBridgeNoLongerRecognisesStillOffersThePairingCard() {
        // After `collie devices revoke` the phone still holds its old token. The bridge's device
        // list has no current device, and the pre-plan Settings offered the form then; the
        // pairing-first rework keyed only on the local token (NativeInteractionTest caught it).
        val activity = launchSettings(paired = true, devices = listOf(device("s24u", current = false)))
        val card = activity.findViewById<View>(R.id.settings_parity_pairing_card)
        assertNotNull(card)
        assertEquals(View.VISIBLE, card.visibility)
        val host = activity.findViewById<LinearLayout>(R.id.settings_pairing_host)
        assertEquals(R.id.settings_parity_pairing_card, host.getChildAt(0).id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun operatorRowsAreReadmittedAfterActorRetirementWithoutConnectionOrScopeChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = Connection(CollieOrigin("https://collie.example.com/"), "phone", "fixture-pairing")
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(original)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        var rows = listOf(OperatorFontRow("Original Face", "original-face.woff2", "400"))
        var configs = 0
        var timestamp = 1L
        val api = object : CollieApi by com.lateapex.collie.network.CollieApiClient(
            okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        ) {
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
                configs++
                return ApiResult.Success(BridgeConfigResponse(operatorFonts = rows), 200)
            }
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
                SnapshotResponse("connected", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = timestamp++), 200,
            )
            override suspend fun health(connection: Connection) = ApiResult.Success(HealthResponse(true, "1.21.0", false, "solo"), 200)
            override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun notificationPreferences(connection: Connection): ApiResult<NotifyPreferences> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun cacheWatchList(connection: Connection): ApiResult<CacheWatchListResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun updateState(connection: Connection): ApiResult<UpdateCheckResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
        }
        val repository = CollieRepository(api, store, OriginValidator())
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).also { it.get().repositoryForTests = repository }
        fun choices(): List<String> {
            controller.get().findViewById<View>(R.id.settings_ui_typeface_value).performClick()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            fun text(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                (if (view is ViewGroup) (0 until view.childCount).flatMap { text(view.getChildAt(it)) } else emptyList())
            return text(dialog.window!!.decorView).also { dialog.dismiss() }
        }
        try {
            controller.create().start().resume()
            runCurrent()
            assertTrue(choices().contains("Original Face"))
            val admitted = requireNotNull(repository.readContext())
            // A real shipped-font preference does not retire its actor or remove admitted rows.
            controller.get().findViewById<View>(R.id.settings_ui_typeface_value).performClick()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog())
            fun choose(view: View): Boolean {
                val label = controller.get().getString(R.string.settings_ui_typeface_aldrich)
                if (view is TextView && view.text.toString() in setOf(label, controller.get().getString(R.string.settings_parity_selected_choice, label))) return view.performClick()
                return view is ViewGroup && (0 until view.childCount).any { choose(view.getChildAt(it)) }
            }
            assertTrue(choose(sheet.window!!.decorView)); runCurrent()
            assertTrue(repository.isCurrent(admitted))
            assertTrue(choices().contains("Original Face"))
            val before = configs
            rows = listOf(OperatorFontRow("Fresh Face", "fresh-face.woff2", "400"))
            repository.proxyIdentityChanged()
            runCurrent()
            assertFalse(repository.isCurrent(admitted))
            assertFalse(choices().contains("Original Face"))
            // A newly admitted live read changes readSource without changing connection/scope.
            repository.snapshot(); runCurrent()
            assertTrue(configs > before)
            assertTrue(choices().contains("Fresh Face"))
            assertFalse(choices().contains("Original Face"))
            val readmitted = configs
            repository.snapshot(); runCurrent()
            assertEquals("Ordinary same-actor snapshot ticks do not refetch the font catalog", readmitted, configs)
            assertTrue(store.connection.value === original)
            assertEquals(Scope(), repository.selectedScope.value)
        } finally {
            if (controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) controller.pause().stop()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anInitialUnadmittedFontCatalogRetriesAfterSettingsReadsSettleOnTheSameActor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = Connection(CollieOrigin("https://collie.example.com/"), "phone", "fixture-pairing")
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(original)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val health = kotlinx.coroutines.CompletableDeferred<Unit>()
        var admitConfig = false
        var configs = 0
        val api = object : CollieApi by CollieApiClient(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) {
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
                configs++
                return if (admitConfig) ApiResult.Success(BridgeConfigResponse(operatorFonts = listOf(OperatorFontRow("Fresh Face", "fresh-face.woff2", "400"))), 200)
                else ApiResult.Failure(ApiFailure.Protocol("Initial admission is temporarily unavailable"))
            }
            override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun health(connection: Connection): ApiResult<HealthResponse> {
                health.await()
                return ApiResult.Success(HealthResponse(true, "1.21.0", false, "solo"), 200)
            }
            override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun notificationPreferences(connection: Connection): ApiResult<NotifyPreferences> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun cacheWatchList(connection: Connection): ApiResult<CacheWatchListResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun updateState(connection: Connection): ApiResult<UpdateCheckResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
        }
        val repository = CollieRepository(api, store, OriginValidator())
        val actor = requireNotNull(repository.readContext())
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).also { it.get().repositoryForTests = repository }
        fun offered(): Boolean {
            controller.get().findViewById<View>(R.id.settings_ui_typeface_value).performClick()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            fun contains(view: View): Boolean = view is TextView && view.text.toString() == "Fresh Face" ||
                view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
            return contains(dialog.window!!.decorView).also { dialog.dismiss() }
        }
        try {
            controller.create().start().resume(); runCurrent()
            assertTrue(configs > 0)
            assertFalse(offered())
            assertTrue(repository.isCurrent(actor))
            assertTrue(repository.readSource.value is ReadSourceState.Unavailable)
            val initialAttempts = configs
            admitConfig = true
            health.complete(Unit); runCurrent()
            assertTrue("A completed Settings refresh must perform a new config read", configs > initialAttempts)
            assertTrue("Only the newly admitted config may offer the real operator row", offered())
            assertTrue(repository.isCurrent(actor))
            assertTrue(repository.readSource.value is ReadSourceState.Unavailable)
            assertTrue(store.connection.value === original)
        } finally {
            if (controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) controller.pause().stop()
            controller.destroy(); Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aHeldRetryCannotPublishAnOperatorRowAfterSettingsStops() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = Connection(CollieOrigin("https://collie.example.com/"), "phone", "fixture-pairing")
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(original)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val health = kotlinx.coroutines.CompletableDeferred<Unit>()
        val retryResponse = kotlinx.coroutines.CompletableDeferred<Unit>()
        var admitConfig = false
        var configs = 0
        val api = object : CollieApi by CollieApiClient(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) {
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
                configs++
                if (!admitConfig) return ApiResult.Failure(ApiFailure.Protocol("Initial admission is temporarily unavailable"))
                // A held external read deliberately ignores cancellation until its response arrives.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { retryResponse.await() }
                return ApiResult.Success(BridgeConfigResponse(operatorFonts = listOf(OperatorFontRow("Fresh Face", "fresh-face.woff2", "400"))), 200)
            }
            override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun health(connection: Connection): ApiResult<HealthResponse> {
                health.await()
                return ApiResult.Success(HealthResponse(true, "1.21.0", false, "solo"), 200)
            }
            override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun notificationPreferences(connection: Connection): ApiResult<NotifyPreferences> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun cacheWatchList(connection: Connection): ApiResult<CacheWatchListResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun updateState(connection: Connection): ApiResult<UpdateCheckResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
            override suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> = ApiResult.Failure(ApiFailure.Protocol("Not declared"))
        }
        val repository = CollieRepository(api, store, OriginValidator())
        val actor = requireNotNull(repository.readContext())
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).also { it.get().repositoryForTests = repository }
        fun offered(): Boolean {
            controller.get().findViewById<View>(R.id.settings_ui_typeface_value).performClick()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            fun contains(view: View): Boolean = view is TextView && view.text.toString() == "Fresh Face" ||
                view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
            return contains(dialog.window!!.decorView).also { dialog.dismiss() }
        }
        try {
            controller.create().start().resume(); runCurrent()
            assertTrue(configs > 0)
            assertFalse(offered())
            assertTrue(repository.isCurrent(actor))
            assertTrue(repository.readSource.value is ReadSourceState.Unavailable)
            val initialAttempts = configs
            admitConfig = true
            health.complete(Unit); runCurrent()
            assertTrue("The retry must actually dispatch before stop", configs > initialAttempts)
            assertFalse("The held reply must not yet offer a row", offered())
            controller.pause().stop()
            retryResponse.complete(Unit); runCurrent()
            assertTrue("Stopping Settings does not retire the profile's original actor", repository.isCurrent(actor))
            admitConfig = false
            controller.start().resume()
            // Inspect the real picker before the restarted collector can admit another config.
            assertFalse("A response received while stopped must not leak its row on resume", offered())
            runCurrent()
            assertFalse(offered())
            assertTrue(store.connection.value === original)
        } finally {
            if (controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) controller.pause().stop()
            controller.destroy()
            health.complete(Unit); retryResponse.complete(Unit); runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun launchSettings(paired: Boolean, devices: List<com.lateapex.collie.network.DeviceRecord> = emptyList()): SettingsActivity {
        seedConnection(paired)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        if (devices.isNotEmpty()) {
            activity.renderDevicesForTest(
                com.lateapex.collie.network.ApiResult.Success(
                    com.lateapex.collie.network.DevicesResponse(
                        enforced = true,
                        current = devices.firstOrNull { it.current }?.label,
                        devices = devices,
                    ),
                    status = 200,
                ),
            )
        }
        return activity
    }

    private fun device(label: String, current: Boolean) = com.lateapex.collie.network.DeviceRecord(
        label = label,
        createdAt = 0,
        lastSeenAt = 0,
        current = current,
    )

    private fun seedConnection(paired: Boolean) {
        saveFixtureConnection(context.applicationContext as CollieApplication, Connection(
            CollieOrigin("https://collie.example.com/"),
            "S25U-native",
            token = if (paired) "pairing-token" else null,
        ))
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
