package com.lateapex.collie.ui

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import org.hamcrest.Matcher
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android event dispatch, Activity, ViewModel and repository; only the transport is fake.
 * Never connects to, writes into, or changes the stored credentials of an operator's server.
 */
@RunWith(AndroidJUnit4::class)
class NativeInteractionTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()
    private val api = InteractionApi()
    private lateinit var fixtureStore: ConnectionStore
    private lateinit var previousRepository: CollieRepository

    @Before fun installTransport() {
        previousRepository = app.container.repository
        NativePreferences(app).savePaneDraft(
            listOf("https://interaction.invalid/", "", "", "fixture:p1").joinToString("|"), "",
        )
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(
                Connection(CollieOrigin("https://interaction.invalid/"), "Interaction fixture", "fixture-only"),
            )
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        fixtureStore = store
        // Confine replacement to instrumentation: no production test endpoint or auth exception.
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            .set(app.container, CollieRepository(api, store, OriginValidator()))
    }

    @After fun restoreTransport() {
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            .set(app.container, previousRepository)
    }

    private fun launchPane() = ActivityScenario.launch<PaneActivity>(
        Intent(app, PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, "fixture:p1")
            .putExtra(PaneActivity.EXTRA_AGENT, api.summary.agent),
    )

    @Test fun swipeUpOpensSwitcherAndCloseReturnsToPane() {
        launchPane().use {
            onView(withId(R.id.switcher_handle)).check(matches(isDisplayed())).perform(GeneralSwipeAction(
                Swipe.SLOW,
                GeneralLocation.CENTER,
                { view -> GeneralLocation.CENTER.calculateCoordinates(view).also {
                    it[1] -= 180f * view.resources.displayMetrics.density
                } },
                Press.FINGER,
            ))
            onView(withId(R.id.collie_sheet_title)).check(matches(isDisplayed()))
            onView(withId(R.id.collie_sheet_close)).perform(click())
            settleUi()
            onView(withId(R.id.switcher_handle)).check(matches(isDisplayed()))
        }
    }

    @Test fun tapOpensSwitcherAndBackDismisses() {
        launchPane().use {
            onView(withId(R.id.switcher_handle)).perform(click())
            onView(withId(R.id.collie_sheet_title)).check(matches(isDisplayed()))
            pressBack()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500) // Material sheet dismissal finishes on the animation clock.
            onView(withId(R.id.switcher_handle)).check(matches(isDisplayed()))
        }
    }

    @Test fun typedReplyAndSendReachTransportExactlyOnce() {
        launchPane().use {
            onView(withId(R.id.reply_input)).perform(click(), typeText("hello fixture"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            assertEquals(listOf("hello fixture"), api.replies.toList())
        }
    }

    @Test fun keysDrawerArrowReachesTransportExactlyOnce() {
        launchPane().use {
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withContentDescription(R.string.pane_key_up_description)).perform(click())
            assertEquals(listOf(listOf("Up")), api.keys.toList())
            onView(withId(R.id.composer_dock_close)).perform(click())
            onView(withId(R.id.composer_dock)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test fun paneActionsOpenAndCloseThroughTouch() {
        launchPane().use {
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withId(R.id.pane_action_wrap)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.pane_dock_close)).perform(click())
        }
    }

    @Test fun directTypingSendsCharactersSpaceBackspaceAndEnterInOrder() {
        launchPane().use {
            onView(withId(R.id.type_mode_button)).perform(click())
            onView(withId(R.id.reply_input)).perform(typeText("ab c"), pressKey(KeyEvent.KEYCODE_DEL), pressKey(KeyEvent.KEYCODE_ENTER))
            assertEquals(listOf("a", "b", "Space", "c", "Backspace", "Enter"), api.keys.flatten())
        }
    }

    @Test fun directTypingControlChordPreservesModifier() {
        launchPane().use {
            onView(withId(R.id.type_mode_button)).perform(click())
            onView(withId(R.id.reply_input)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isDisplayed()
                override fun getDescription() = "dispatch hardware Ctrl+A"
                override fun perform(ui: UiController, view: View) {
                    val now = android.os.SystemClock.uptimeMillis()
                    ui.injectKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
                    ui.injectKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
                    ui.loopMainThreadUntilIdle()
                }
            })
            assertEquals(listOf("ctrl+a"), api.keys.flatten())
        }
    }

    @Test fun modifierInputKeepsItsKeyboardConnectionAcrossPolling() {
        launchPane().use { scenario ->
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withContentDescription("Ctrl modifier")).perform(click())
            settleUi()
            onView(withId(R.id.pane_keys_base_input)).perform(click())
            lateinit var input: View
            scenario.onActivity { input = it.findViewById(R.id.pane_keys_base_input) }
            awaitKeyboard(scenario, R.id.pane_keys_base_input)
            Thread.sleep(2300) // Exercise a real scheduled pane refresh while the IME owns this field.
            scenario.onActivity {
                assertTrue("Polling detached the active keyboard input", input.isAttachedToWindow)
                assertTrue("Polling stole keyboard focus: ${it.currentFocus?.javaClass?.simpleName} id=${it.currentFocus?.id}", input.hasFocus())
            }
            onView(withId(R.id.pane_keys_base_input)).perform(typeText("a"))
            onView(isRoot()).perform(closeSoftKeyboard())
            onView(withId(R.id.pane_keys_queue_send)).perform(click())
            assertEquals(listOf("ctrl+a"), api.keys.flatten())
        }
    }

    @Test fun dashboardSettingsAndBackNavigateThroughVisibleButtons() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.dashboard_panel)).check(matches(isDisplayed()))
            onView(withId(R.id.settings_button)).perform(click())
            onView(withId(R.id.settings_header_title)).check(matches(isDisplayed()))
            onView(withId(R.id.settings_back_button)).perform(click())
            onView(withId(R.id.dashboard_panel)).check(matches(isDisplayed()))
        }
    }

    @Test fun settingsLinksOpenUpdatesAndPackAndTheirBackButtonsWork() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.settings_updates_button)).perform(scrollTo(), click())
            onView(withId(R.id.updates_check_button)).check(matches(isDisplayed()))
            onView(withId(R.id.updates_back_button)).perform(click())
            onView(withId(R.id.settings_header_title)).check(matches(isDisplayed()))
            onView(withId(R.id.settings_pack_button)).perform(scrollTo(), click())
            onView(withId(R.id.pack_back_button)).perform(click())
            onView(withId(R.id.dashboard_panel)).check(matches(isDisplayed()))
        }
    }

    @Test fun dashboardSpacePaneAndBackButtonsFormAWorkingRoute() {
        val preferences = NativePreferences(app)
        val original = preferences.dashboardSpacesOpen
        preferences.dashboardSpacesOpen = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withContentDescription(R.string.expand_spaces)).perform(click())
                onView(withContentDescription(app.getString(R.string.space_accessibility, "Fixture", 1))).perform(click())
                onView(withId(R.id.space_list)).check(matches(isDisplayed()))
                onView(withContentDescription(app.getString(R.string.pane_agent_accessibility, "Fixture shell", "shell"))).perform(click())
                onView(withId(R.id.reply_input)).check(matches(isDisplayed()))
                onView(withId(R.id.back_button)).perform(click())
                onView(withId(R.id.space_list)).check(matches(isDisplayed()))
                onView(withId(R.id.back_button)).perform(click())
                onView(withId(R.id.dashboard_panel)).check(matches(isDisplayed()))
            }
        } finally { preferences.dashboardSpacesOpen = original }
    }

    @Test fun settingsTerminalSizeButtonsChangeAndRestoreTheValue() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            var before = 0
            scenario.onActivity { before = NativePreferences(it).terminalFontSize }
            onView(withId(R.id.settings_terminal_size_increase)).perform(scrollTo(), click())
            scenario.onActivity { assertEquals(before + 1, NativePreferences(it).terminalFontSize) }
            onView(withId(R.id.settings_terminal_size_decrease)).perform(click())
            scenario.onActivity { assertEquals(before, NativePreferences(it).terminalFontSize) }
        }
    }

    @Test fun settingsBehaviorAndDraftControlsPersistTheirChoices() {
        api.speechAvailable = true
        val preferences = NativePreferences(app)
        val draftSize = preferences.draftFontSize
        val handsFree = preferences.handsFreeEnabled
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                onView(withId(R.id.settings_draft_size_increase)).perform(scrollTo(), click())
                assertEquals((draftSize + 1).coerceAtMost(16), preferences.draftFontSize)
                onView(withId(R.id.settings_draft_size_decrease)).perform(scrollTo(), click())
                onView(withId(R.id.settings_hands_free_switch)).perform(scrollTo(), click())
                assertEquals(!handsFree, preferences.handsFreeEnabled)
            }
        } finally {
            preferences.draftFontSize = draftSize
            preferences.handsFreeEnabled = handsFree
        }
    }

    @Test fun settingsPairFormUsesTheEnteredCodeAndLabel() {
        api.pairedDevice = null
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.settings_pair_code)).perform(scrollTo(), replaceText("ABCDEF"), closeSoftKeyboard())
            onView(withId(R.id.settings_pair_label)).perform(scrollTo(), replaceText("Fixture paired phone"), closeSoftKeyboard())
            onView(withId(R.id.settings_pair_button)).perform(scrollTo(), click())
            assertEquals("Fixture paired phone", fixtureStore.connection.value?.label)
        }
    }

    @Test fun notificationSwitchSendsItsNewValue() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.settings_notify_done_switch)).perform(scrollTo(), click())
            assertEquals(false, api.notifications.done)
        }
    }

    @Test fun historySearchNextPreviousAndCloseWork() {
        ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(app, PaneAddress(paneId = "fixture:p1"), "Fixture", "shell")).use {
            onView(withId(R.id.history_find_open)).perform(click())
            onView(withId(R.id.history_find_query)).perform(typeText("needle"), closeSoftKeyboard())
            onView(withId(R.id.history_find_next)).check(matches(isEnabled())).perform(click())
            onView(withId(R.id.history_find_previous)).check(matches(isEnabled())).perform(click())
            onView(withId(R.id.history_find_close)).perform(click())
            onView(withId(R.id.history_find_open)).check(matches(isDisplayed()))
        }
    }

    @Test fun paneFindSearchAndBackRestoreComposer() {
        launchPane().use {
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(R.string.pane_action_find)).perform(click())
            onView(withId(R.id.find_query)).perform(typeText("Fixture"), closeSoftKeyboard())
            pressBack()
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun tapToTypeOpensTheSoftKeyboard() {
        launchPane().use { scenario ->
            onView(withText("Fixture terminal\n$ ")).perform(click())
            awaitKeyboard(scenario)
        }
    }

    @Test fun pollingStopsInBackgroundAndResumesWithoutDuplicatingAReply() {
        launchPane().use { scenario ->
            onView(withId(R.id.reply_input)).perform(typeText("background fixture"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val before = api.reads
            Thread.sleep(2300)
            assertEquals(before, api.reads)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
            assertTrue(api.reads > before)
            assertEquals(listOf("background fixture"), api.replies.toList())
        }
    }

    @Test fun notificationSnoozeAndResumeReachTheServer() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.settings_snooze_30)).perform(scrollTo(), click())
            assertTrue(api.snoozedUntil!! > System.currentTimeMillis())
            onView(withId(R.id.settings_snooze_resume)).perform(scrollTo(), click())
            assertNull(api.snoozedUntil)
        }
    }

    @Test fun updatesCheckCancelAndConfirmHaveDistinctEffects() {
        ActivityScenario.launch(UpdatesActivity::class.java).use {
            onView(withId(R.id.updates_check_button)).perform(scrollTo(), click())
            assertEquals(1, api.updateChecks)
            onView(withId(R.id.updates_start_button)).perform(scrollTo(), click())
            assertEquals(0, api.updateStarts)
            onView(withId(R.id.updates_native_confirmation_cancel)).perform(scrollTo(), click())
            assertEquals(0, api.updateStarts)
            onView(withId(R.id.updates_start_button)).perform(scrollTo(), click())
            onView(withId(R.id.updates_native_confirmation_confirm)).perform(scrollTo(), click())
            assertEquals(1, api.updateStarts)
        }
    }

    @Test fun packNodeOpensDetailsAndCloseReturnsToFormation() {
        ActivityScenario.launch(PackActivity::class.java).use {
            onView(withId(R.id.pack_formation)).perform(androidx.test.espresso.action.GeneralClickAction(
                androidx.test.espresso.action.Tap.SINGLE,
                { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    floatArrayOf(location[0] + view.width / 2f, location[1] + view.width * 58f / 360f)
                }, Press.FINGER, 0, 0,
            ))
            onView(withId(R.id.collie_sheet_title)).check(matches(isDisplayed()))
            onView(withId(R.id.collie_sheet_close)).perform(click())
            settleUi()
            onView(withId(R.id.pack_formation)).check(matches(isDisplayed()))
        }
    }

    @Test fun spaceSettingsAndReturnAreReachable() {
        ActivityScenario.launch<SpaceActivity>(SpaceActivity.intent(app,
            WorkspaceSummary("w1", 1, "Fixture", false, "t1", 1, 1), null)).use {
            onView(withId(R.id.settings_button)).perform(click())
            onView(withId(R.id.settings_header_title)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.settings_button)).check(matches(isDisplayed()))
        }
    }

    @Test fun quickReplySendsExactlyOnce() {
        launchPane().use {
            onView(withId(R.id.quick_mode_button)).perform(click())
            onView(withText("Fixture quick reply")).perform(click())
            assertEquals(listOf("Fixture quick reply"), api.replies.toList())
            onView(withId(R.id.composer_dock_close)).perform(click())
        }
    }

    @Test fun commandPaletteSearchSelectsAndSendsTheChosenCommand() {
        launchPane().use {
            onView(withId(R.id.agent_mode_button)).perform(click())
            onView(withHint(app.getString(R.string.pane_agent_search, 1))).perform(typeText("help"), closeSoftKeyboard())
            onView(withText("/help\nFixture help")).perform(click())
            assertEquals(listOf("/help"), api.replies.toList())
        }
    }

    @Test fun stagedKeyQueueRequiresDiscardConfirmationAndNeverSurvivesReopen() {
        launchPane().use {
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withContentDescription("Ctrl modifier")).perform(click())
            settleUi()
            onView(withContentDescription(R.string.pane_key_up_description)).perform(click())
            assertTrue(api.keys.isEmpty())
            onView(withId(R.id.pane_keys_queue_send)).check(matches(isDisplayed()))
            onView(withId(R.id.composer_dock_close)).perform(click())
            onView(withId(R.id.pane_keys_queue_send)).check(matches(isDisplayed()))
            onView(withId(R.id.composer_dock_close)).perform(click())
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withId(R.id.pane_keys_queue_send)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            assertTrue(api.keys.isEmpty())
        }
    }

    @Test fun gboardKeysReachDirectTyping() {
        launchPane().use { scenario ->
            onView(withId(R.id.type_mode_button)).perform(click())
            awaitKeyboard(scenario)
            scenario.onActivity {
                assertTrue("Type mode was not armed", it.findViewById<DirectTypingEditText>(R.id.reply_input).directTypingEnabled)
            }
            Thread.sleep(300) // Let Gboard finish repositioning after the mode transition.
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val info = automation.serviceInfo
            val originalFlags = info.flags
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            try {
                val rows = mutableListOf<android.graphics.Rect>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (node.className == "android.widget.LinearLayout") rows += bounds
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                val keyboardDeadline = android.os.SystemClock.uptimeMillis() + 5000
                var previousRows: List<android.graphics.Rect> = emptyList()
                var stableSince = android.os.SystemClock.uptimeMillis()
                do {
                    rows.clear()
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .forEach { collect(it.root) }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (rows.isEmpty() || rows != previousRows) stableSince = now
                    previousRows = rows.toList()
                    if (rows.isNotEmpty() && now - stableSince >= 300) break
                    Thread.sleep(100)
                } while (android.os.SystemClock.uptimeMillis() < keyboardDeadline)
                // Gboard's English QWERTY keys are not accessibility children without TalkBack.
                // Use the actual IME row bounds, not app-screen coordinates or injected characters.
                val density = app.resources.displayMetrics.density
                val width = app.resources.displayMetrics.widthPixels
                val letters = rows.distinct().filter { it.width() > width * 0.8 && it.height() > density * 100 }.minByOrNull { it.height() }
                    ?: error("Gboard letter rows unavailable: $rows density=$density width=$width")
                val bottom = rows.distinct().filter { it.width() > width * 0.8 && it.height() in 1..(density * 100).toInt() && kotlin.math.abs(it.top - letters.bottom) < density * 4 }
                    .minByOrNull { kotlin.math.abs(it.top - letters.bottom) }
                    ?: error("Gboard bottom row unavailable: $rows letters=$letters")
                fun tap(x: Float, y: Float) {
                    val now = android.os.SystemClock.uptimeMillis()
                    listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                        if (action == android.view.MotionEvent.ACTION_UP) Thread.sleep(50)
                        val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue("IME touch injection was rejected", automation.injectInputEvent(event, true))
                        event.recycle()
                    }
                    instrumentation.waitForIdleSync()
                }
                tap(letters.left + letters.width() * 0.05f, letters.top + letters.height() / 6f)
                tap(letters.left + letters.width() * 0.15f, letters.top + letters.height() / 6f)
                tap(bottom.exactCenterX(), bottom.exactCenterY())
                tap(letters.left + letters.width() * 0.95f, letters.top + letters.height() * 5f / 6f)
                tap(bottom.left + bottom.width() * 0.95f, bottom.exactCenterY())
                val deliveryDeadline = android.os.SystemClock.uptimeMillis() + 3000
                while (api.keys.flatten().size < 5 && android.os.SystemClock.uptimeMillis() < deliveryDeadline) Thread.sleep(50)
                var detail = ""
                scenario.onActivity {
                    val input = it.findViewById<DirectTypingEditText>(R.id.reply_input)
                    detail = "letters=$letters bottom=$bottom direct=${input.directTypingEnabled} draft=${input.text}"
                }
                assertEquals(detail, listOf("q", "w", "Space", "Backspace", "Enter"), api.keys.flatten())
            } finally {
                info.flags = originalFlags
                automation.serviceInfo = info
            }
        }
    }

    @Test fun paneRenameUpdatesTheChosenPaneAndVisibleTitle() {
        launchPane().use {
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(R.string.pane_action_rename)).perform(click())
            onView(withHint(R.string.pane_rename_hint)).perform(replaceText("Renamed fixture"), closeSoftKeyboard())
            onView(withText(R.string.pane_rename_save)).perform(click())
            assertEquals("Renamed fixture", api.summary.paneLabel)
            onView(withId(R.id.pane_title)).check(matches(withText("Renamed fixture")))
        }
    }

    @Test fun showInTerminalOnlyRunsAfterItsExplicitTap() {
        launchPane().use {
            assertEquals(0, api.focusCalls)
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(R.string.pane_action_focus)).perform(click())
            assertEquals(1, api.focusCalls)
        }
    }

    @Test fun readOnlyPaneKeepsNavigationButDisablesTypingAndKeys() {
        api.authorized = false
        launchPane().use {
            onView(withId(R.id.reply_input)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withId(R.id.type_mode_button)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withId(R.id.keys_mode_button)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withId(R.id.switcher_handle)).perform(click())
            onView(withId(R.id.collie_sheet_close)).perform(click())
            settleUi()
            assertTrue(api.keys.isEmpty())
            assertTrue(api.replies.isEmpty())
        }
    }

    @Test fun rejectedReplyKeepsTheDraftAndShowsTheFailure() {
        api.rejectReply = true
        launchPane().use {
            onView(withId(R.id.reply_input)).perform(typeText("keep this draft"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            onView(withId(R.id.reply_input)).check(matches(withText("keep this draft")))
            onView(withId(R.id.error_text)).check(matches(isDisplayed()))
            assertEquals(listOf("keep this draft"), api.replies.toList())
            onView(withId(R.id.reply_input)).perform(replaceText(""), closeSoftKeyboard())
        }
    }

    @Test fun newSpaceFormSubmitsItsLabelAndDirectory() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.new_space)).perform(click())
            onView(withHint(R.string.space_directory_optional)).perform(typeText("/fixture"), closeSoftKeyboard())
            onView(withHint(R.string.space_label_optional)).perform(typeText("New fixture"), closeSoftKeyboard())
            onView(withText(R.string.action_create)).perform(click())
            assertEquals(listOf("New fixture" to "/fixture"), api.createdSpaces)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun newTabCreatesOnceAndOpensItsPane() {
        ActivityScenario.launch<SpaceActivity>(SpaceActivity.intent(app,
            WorkspaceSummary("w1", 1, "Fixture", false, "t1", 1, 1), null)).use {
            onView(withContentDescription(R.string.new_tab)).perform(click())
            assertEquals(1, api.createdTabs)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun launcherRowCreatesOnceAndOpensItsPane() {
        NativePreferences(app).dashboardLaunchOpen = true
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Launch Fixture launcher")).perform(click())
            assertEquals(listOf("fixture-command"), api.launched)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun pairingFormConnectsAndEnablesTheDashboard() {
        fixtureStore.clear()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.origin_input)).perform(replaceText("https://interaction.invalid/"), closeSoftKeyboard())
            onView(withId(R.id.device_label_input)).perform(replaceText("Fixture phone"), closeSoftKeyboard())
            onView(withId(R.id.pairing_code_input)).perform(typeText("123456"), closeSoftKeyboard())
            onView(withId(R.id.pair_button)).perform(click())
            onView(withId(R.id.dashboard_panel)).check(matches(isDisplayed()))
            assertTrue(fixtureStore.connection.value?.isPaired == true)
        }
    }

    @Test fun disconnectCanBeCancelledThenConfirmedWithoutTouchingStoredCredentials() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.disconnect_button)).perform(scrollTo(), click())
            pressBack()
            Thread.sleep(500) // Wait for the sheet dismissal animation before addressing Settings.
            assertNotNull(fixtureStore.connection.value)
            onView(withId(R.id.disconnect_button)).perform(scrollTo(), click())
            onView(org.hamcrest.Matchers.allOf(
                withText(R.string.settings_disconnect),
                org.hamcrest.Matchers.not(withId(R.id.disconnect_button)),
            )).perform(click())
            onView(withId(R.id.setup_panel)).check(matches(isDisplayed()))
            assertNull(fixtureStore.connection.value)
        }
    }

    @Test fun invalidOriginIsExplainedWithoutLeavingSetup() {
        fixtureStore.clear()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.origin_input)).perform(replaceText("http://interaction.invalid/"), closeSoftKeyboard())
            onView(withId(R.id.connect_button)).perform(click())
            onView(withId(R.id.error_text)).check(matches(isDisplayed()))
            onView(withId(R.id.setup_panel)).check(matches(isDisplayed()))
            assertNull(fixtureStore.connection.value)
        }
    }

    @Test fun wideTablePansHorizontallyInsideTheWrappingMirror() {
        api.paneText = "before\n| Name | Value |\n| --- | ---: |\n| alpha | ${"0123456789".repeat(15)} |\nafter\n$ "
        launchPane().use { scenario ->
            val table = org.hamcrest.Matchers.allOf(
                isAssignableFrom(android.widget.HorizontalScrollView::class.java),
                isDescendantOfA(withId(R.id.terminal_block_content)),
            )
            onView(table).perform(swipeLeft())
            onView(table).check { view, failure ->
                if (failure != null) throw failure
                assertTrue("Wide table did not pan", view.scrollX > 0)
            }
            scenario.onActivity {
                assertEquals(0, it.findViewById<View>(R.id.terminal_horizontal_scroll).scrollX)
            }
        }
    }

    private fun openWorktreeChoices() {
        onView(withId(R.id.new_space)).perform(click())
        onView(withText(R.string.space_mode_worktree)).perform(click())
        onView(withText("Fixture")).perform(click())
    }

    @Test fun createWorktreeSendsTheEnteredBranch() {
        api.workspace = api.workspace.copy(repoRoot = "/fixture", isWorktree = false)
        ActivityScenario.launch(MainActivity::class.java).use {
            openWorktreeChoices()
            onView(withId(R.id.worktree_branch_input)).perform(typeText("fixture-branch"), closeSoftKeyboard())
            onView(withText(R.string.action_create)).perform(click())
            assertEquals(listOf("fixture-branch"), api.worktreeBranches)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun revokeDeviceRequiresConfirmationAndTargetsTheChosenLabel() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withContentDescription(app.getString(R.string.settings_revoke_title, "Spare fixture"))).perform(scrollTo(), click())
            assertTrue(api.revoked.isEmpty())
            onView(withText(R.string.settings_cancel)).perform(scrollTo(), click())
            assertTrue(api.revoked.isEmpty())
            onView(withContentDescription(app.getString(R.string.settings_revoke_title, "Spare fixture"))).perform(click())
            onView(withText(R.string.settings_revoke)).perform(scrollTo(), click())
            assertEquals(listOf("Spare fixture"), api.revoked)
        }
    }

    @Test fun networkFailureKeepsTheMirrorAndRecoversWithoutWrites() {
        launchPane().use {
            onView(withText("Fixture terminal\n$ ")).check(matches(isDisplayed()))
            api.offline = true
            Thread.sleep(2300)
            onView(withText("Fixture terminal\n$ ")).check(matches(isDisplayed()))
            onView(withId(R.id.error_text)).check(matches(isDisplayed()))
            api.offline = false
            Thread.sleep(2300)
            onView(withId(R.id.error_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            assertTrue(api.replies.isEmpty())
            assertTrue(api.keys.isEmpty())
        }
    }

    @Test fun hostPickerChangesTheRequestedScope() {
        api.servers = listOf(
            ServerSummary("lead", "Lead fixture", true, true, "compatible", lastSeenAt = 1),
            ServerSummary("peer", "Peer fixture", false, true, "compatible", lastSeenAt = 1),
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.host_switcher)).perform(click())
            onView(withText("Peer fixture")).perform(click())
            assertEquals("peer", app.container.repository.selectedScope.value.host)
        }
    }

    @Test fun sessionPickerChangesTheRequestedScope() {
        api.sessions = listOf(SessionSummary("main", true, true, 0, 0, 0),
            SessionSummary("other", false, true, 0, 0, 0))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.session_switcher)).perform(click())
            onView(withText("other")).perform(click())
            assertEquals("other", app.container.repository.selectedScope.value.session)
        }
    }

    @Test fun photoPickerCancelReturnsWithoutLosingTheDraft() {
        launchPane().use {
            onView(withId(R.id.reply_input)).perform(typeText("photo fixture"), closeSoftKeyboard())
            onView(withId(R.id.attach_image_button)).perform(click())
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var pickerVisible = false
            while (!pickerVisible && android.os.SystemClock.uptimeMillis() < deadline) {
                val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                pickerVisible = activePackage.contains("photopicker") || activePackage.contains("providers.media")
                if (!pickerVisible) Thread.sleep(50)
            }
            assertTrue("System photo picker did not open", pickerVisible)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            onView(withId(R.id.reply_input)).check(matches(withText("photo fixture")))
            assertTrue(api.replies.isEmpty())
            onView(withId(R.id.reply_input)).perform(replaceText(""), closeSoftKeyboard())
        }
    }

    @Test fun speechRecordingReturnsATranscriptForReviewWithoutSending() {
        api.speechAvailable = true
        val preferences = NativePreferences(app)
        val handsFree = preferences.handsFreeEnabled
        preferences.handsFreeEnabled = false
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        try {
            launchPane().use {
                onView(withId(R.id.speech_button)).perform(click())
                Thread.sleep(1000) // Collect a bounded emulator audio clip through MediaRecorder.
                onView(withId(R.id.speech_button)).perform(click())
                onView(withId(R.id.reply_input)).check(matches(withText("Fixture speech")))
                assertEquals(1, api.transcriptions)
                assertTrue(api.replies.isEmpty())
                onView(withId(R.id.reply_input)).perform(replaceText(""), closeSoftKeyboard())
            }
        } finally {
            preferences.handsFreeEnabled = handsFree
        }
    }

    @Test fun everyPrimaryNamedKeyReachesTheTransport() {
        launchPane().use {
            onView(withId(R.id.keys_mode_button)).perform(click())
            val keys = listOf(R.id.escape_button to "Escape", R.id.tab_button to "Tab",
                R.id.up_button to "Up", R.id.down_button to "Down", R.id.left_button to "Left",
                R.id.right_button to "Right", R.id.enter_button to "Enter")
            keys.forEach { (id, _) -> onView(withId(id)).perform(click()) }
            onView(withText(R.string.pane_keys_more)).perform(click())
            onView(withText(R.string.pane_key_space)).perform(click())
            assertEquals(keys.map { it.second } + "Space", api.keys.flatten())
        }
    }

    @Test fun digitAndFunctionKeysCanBeReachedAndSent() {
        launchPane().use { scenario ->
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withId(R.id.pane_keys_segment_digits)).perform(click())
            (1..9).forEach { onView(withText(it.toString())).perform(click()) }
            onView(withId(R.id.pane_keys_segment_keys)).perform(click())
            onView(withText(R.string.pane_keys_more)).perform(click())
            (1..12).forEach { onView(withText("F$it")).perform(click()) }
            var expandedHeight = 0
            scenario.onActivity { expandedHeight = it.findViewById<View>(R.id.key_row).height }
            onView(withContentDescription(R.string.pane_dock_close)).perform(click())
            onView(withId(R.id.pane_keys_segment_digits)).perform(scrollTo(), click())
            scenario.onActivity { assertTrue("Collapsed pad retained empty expanded height", it.findViewById<View>(R.id.key_row).height < expandedHeight) }
            assertEquals((1..9).map(Int::toString) + (1..12).map { "F$it" }, api.keys.flatten())
        }
    }

    @Test fun codexComposerSendsWithItsVisiblePromptBinding() {
        api.summary = api.summary.copy(agent = "codex", kind = null)
        api.paneText = "transcript\n\n› \n\n  gpt-5 · /repo · Context 50% left"
        launchPane().use {
            onView(withId(R.id.reply_input)).perform(typeText("Fixture codex reply"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            assertEquals(listOf("Fixture codex reply"), api.replies.toList())
            assertFalse(api.replyBindings.single().isNullOrBlank())
        }
    }

    @Test fun claudePromptSelectionSendsItsBoundChoiceAndBlocksFreeTyping() {
        api.summary = api.summary.copy(agent = "claude", kind = null)
        api.paneText = "Which color?\n❯ 1. Red\n  2. Blue\nEnter to select · Esc to cancel"
        launchPane().use {
            onView(withId(R.id.reply_input)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText("› 1. Red")).perform(click())
            assertEquals(listOf("1", "Enter"), api.keys.flatten())
            assertTrue(api.replies.isEmpty())
        }
    }

    @Test fun themeButtonsRecreateTheScreenWithTheChosenTheme() {
        val preferences = NativePreferences(app)
        val original = preferences.themeMode
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                onView(withId(R.id.theme_light_button)).perform(click())
                scenario.onActivity {
                    assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO,
                        it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                }
                onView(withId(R.id.theme_dark_button)).perform(click())
                scenario.onActivity {
                    assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES,
                        it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                }
            }
        } finally {
            preferences.themeMode = original
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { preferences.applyTheme() }
        }
    }

    private fun settleUi() {
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Wait for the sheet/drawer transition to finish"
            override fun perform(controller: UiController, view: View) {
                controller.loopMainThreadForAtLeast(500)
            }
        })
    }

    private fun awaitKeyboard(scenario: ActivityScenario<PaneActivity>, inputId: Int = R.id.reply_input) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var visible = false
        while (!visible && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val input = activity.findViewById<View>(inputId)
                visible = input.hasFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(input)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            if (!visible) Thread.sleep(50)
        }
        assertTrue("Composer must focus and open the soft keyboard", visible)
    }

    private class InteractionApi : CollieApi {
        var pairedDevice: String? = "Fixture"
        var speechAvailable = false
        var transcriptions = 0
        var servers: List<ServerSummary>? = null
        var sessions: List<SessionSummary> = emptyList()
        var workspace = WorkspaceSummary("w1", 1, "Fixture", false, "t1", 1, 1)
        @Volatile var offline = false
        val worktreeBranches = mutableListOf<String>()
        val openedWorktrees = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val createdSpaces = mutableListOf<Pair<String?, String?>>()
        var createdTabs = 0
        val launched = mutableListOf<String>()
        var paneText = "Fixture terminal\n$ "
        var authorized = true
        var rejectReply = false
        var focusCalls = 0
        var updateChecks = 0
        var updateStarts = 0
        @Volatile var reads = 0
        var snoozedUntil: Long? = null
        var notifications = NotifyPreferences(true, true, true)
        val replyBindings = mutableListOf<String?>()
        val replies = CopyOnWriteArrayList<String>()
        val keys = CopyOnWriteArrayList<List<String>>()
        private fun <T> ok(value: T) = ApiResult.Success(value, 200)
        var summary = PaneSummary("fixture:p1", "w1", "Fixture", 1, "t1", "shell",
            AgentStatus.IDLE, "/fixture", false, kind = "shell", paneLabel = "Fixture shell")
        override suspend fun health(connection: Connection) = ok(HealthResponse(true, "1.5.1", false, "solo"))
        override suspend fun config(connection: Connection) = ok(BridgeConfigResponse(
            mux = MuxConfigResponse(name = "tmux"),
            stt = if (speechAvailable) SttCapability("fixture", true) else null,
            operatorCommands = listOf(OperatorCommand(agent = "shell", command = "/help", description = "Fixture help")),
            operatorQuickReplies = listOf(OperatorQuickReplyRow(agent = "shell", title = "Fixture", items = listOf("Fixture quick reply"))),
        ))
        override suspend fun snapshot(connection: Connection, scope: Scope) = if (offline) ApiResult.Failure(ApiFailure.Network("Fixture offline")) else ok(SnapshotResponse(
            bridge = "connected", device = DeviceAuthorization(true, "Fixture", authorized),
            agents = if (summary.kind == "shell") emptyList() else listOf(summary),
            shellPanes = if (summary.kind == "shell") listOf(summary) else emptyList(),
            workspaces = listOf(workspace),
            tabs = listOf(TabSummary("t1", "w1", 1, "Shell", false, 1)),
            notifications = NotificationState(snoozedUntil), servers = servers, sessions = sessions, ts = 1,
        ))
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean) =
            if (offline) ApiResult.Failure(ApiFailure.Network("Fixture offline")) else
                ok(PaneReadResponse(address.paneId, paneText, false, 1)).also { reads++ }
        override suspend fun refresh(connection: Connection, scope: Scope) = ok(ActionResponse(true))
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> =
            ok(PairResult.Paired("fixture-only", label))
        override suspend fun devices(connection: Connection) = ok(DevicesResponse(true, pairedDevice,
            if (revoked.isEmpty()) listOf(DeviceRecord("Spare fixture", 1, 1, false)) else emptyList()))
        override suspend fun revokeDevice(connection: Connection, label: String): ApiResult<DevicesResponse> {
            revoked += label
            return devices(connection)
        }
        override suspend fun listWorktrees(connection: Connection, scope: Scope, workspaceId: String) = ok(WorktreeListResponse(true,
            listOf(Worktree("/fixture/existing", "existing-fixture", null, true, false))))
        override suspend fun createWorktree(connection: Connection, scope: Scope, workspaceId: String, branch: String): ApiResult<WorktreeOpenResponse> {
            worktreeBranches += branch
            return ok(WorktreeOpenResponse(true, created))
        }
        override suspend fun openWorktree(connection: Connection, scope: Scope, workspaceId: String, path: String): ApiResult<WorktreeOpenResponse> {
            openedWorktrees += path
            return ok(WorktreeOpenResponse(true, created))
        }
        private val created = CreatedPane("fixture:p1", "w1", "Fixture", "t1", "/fixture")
        override suspend fun createWorkspace(connection: Connection, scope: Scope, label: String?, cwd: String?): ApiResult<CreateResponse> {
            createdSpaces += label to cwd
            return ok(CreateResponse(true, created))
        }
        override suspend fun createTab(connection: Connection, scope: Scope, workspaceId: String, label: String?, cwd: String?): ApiResult<CreateResponse> {
            createdTabs++
            return ok(CreateResponse(true, created))
        }
        override suspend fun launchers(connection: Connection, scope: Scope) =
            ok(LaunchersResponse(listOf(Launcher("fixture-command", "Fixture launcher")), "/fixture"))
        override suspend fun launch(connection: Connection, scope: Scope, command: String, besidePaneId: String?): ApiResult<CreateResponse> {
            launched += command
            return ok(CreateResponse(true, created))
        }
        override suspend fun renamePane(connection: Connection, address: PaneAddress, label: String): ApiResult<ActionResponse> {
            summary = summary.copy(paneLabel = label)
            return ok(ActionResponse(true))
        }
        override suspend fun focusPane(connection: Connection, address: PaneAddress): ApiResult<ActionResponse> {
            focusCalls++
            return ok(ActionResponse(true))
        }
        private val update = UpdateInfo("1.5.1", "1.5.2", null, true, null, null,
            bridgeStale = false, checkedAt = 1)
        override suspend fun updateState(connection: Connection) = ok(UpdateCheckResponse(
            "1.5.1", "1.5.2", null, true, null, null, bridgeStale = false, checkedAt = 1,
            preflight = PreflightReport(1, "pass", listOf(PreflightCheck("fixture", "pass", "Ready"))),
        ))
        override suspend fun checkForUpdates(connection: Connection): ApiResult<UpdateInfo> {
            updateChecks++
            return ok(update)
        }
        override suspend fun startUpdate(connection: Connection, target: String, major: Boolean, peersOnly: Boolean): ApiResult<UpdateStartResponse> {
            updateStarts++
            return ok(UpdateStartResponse(true, target, major))
        }
        override suspend fun pack(connection: Connection) = ok(PackStatusResponse(
            PackIdentity("fixture", "Fixture pack", 1, 1), PackSelf("lead", "Fixture host", "1.5.1"), null,
            listOf(PackMember("lead", "Fixture host", true, health = "reachable", lastSeenAt = 1,
                version = "1.5.1", secretBehind = false, provisional = false)), 1,
        ))
        override suspend fun transcribe(connection: Connection, audio: AudioUpload): ApiResult<SttResponse> {
            assertTrue("Recorder produced no audio data", audio.bytes.isNotEmpty())
            transcriptions++
            return ok(SttResponse(true, "Fixture speech"))
        }
        override suspend fun setNotificationSnooze(connection: Connection, snoozedUntil: Long?): ApiResult<NotificationState> {
            this.snoozedUntil = snoozedUntil
            return ok(NotificationState(snoozedUntil))
        }
        override suspend fun notificationPreferences(connection: Connection) = ok(notifications)
        override suspend fun setNotificationPreferences(connection: Connection, patch: NotifyPreferencesPatch): ApiResult<NotifyPreferences> {
            notifications = NotifyPreferences(patch.blocked ?: notifications.blocked, patch.done ?: notifications.done,
                patch.updates ?: notifications.updates)
            return ok(notifications)
        }
        override suspend fun history(connection: Connection, address: PaneAddress, limit: Int, before: String?) =
            ok(PaneHistoryResponse(address.paneId, true, entries = (1..4).map {
                TranscriptEntry("entry-$it", "2026-09-10T10:00:00Z", if (it % 2 == 0) "assistant" else "user",
                    listOf(TranscriptPart("text", text = "needle fixture message $it")))
            }, total = 4))
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?): ApiResult<ActionResponse> {
            replies += text
            replyBindings += expectedPrompt
            return if (rejectReply) ApiResult.Failure(ApiFailure.Http(409, "Fixture changed", null)) else ok(ActionResponse(true))
        }
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> {
            this.keys += keys
            return ok(ActionResponse(true))
        }
    }
}
