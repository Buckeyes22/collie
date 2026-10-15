package com.lateapex.collie.ui

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private lateinit var previousImageLoader: ScopedImageLoader
    private lateinit var fixtureJournal: LaunchIntentStore
    private lateinit var fixtureJournalPreferences: String

    @Before fun installTransport() {
        previousRepository = app.container.repository
        previousImageLoader = app.container.imageLoader
        fixtureJournalPreferences = "native_interaction_intents_${java.util.UUID.randomUUID()}"
        val journalContext = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(fixtureJournalPreferences, mode)
        }
        fixtureJournal = LaunchIntentStore(journalContext,
            AndroidKeystoreCipher(alias = "com.lateapex.collie.capability-fixture.launch-intents.v1"))
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
        val repository = CollieRepository(api, store, OriginValidator(), launchIntentStore = fixtureJournal)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            .set(app.container, repository)
        app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
            .set(app.container, ScopedImageLoader(repository))
    }

    @After fun restoreTransport() {
        app.container.imageLoader.retire()
        app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
            .set(app.container, previousImageLoader)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            .set(app.container, previousRepository)
        fixtureJournal.clear()
        app.deleteSharedPreferences(fixtureJournalPreferences)
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

    @Test fun visibleRunRecoveryReloadsWithoutReplayThenRetriesTheOriginalBody() {
        api.recipeRunsEnabled = true
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(app, Scope(), LaunchRecipeActivity.MODE_RUN)).use { scenario ->
            val exact = "printf '%s' \"fixture value\""
            onView(withHint(R.string.recipes_run_input)).perform(scrollTo(), typeText(exact), closeSoftKeyboard())
            onView(withHint(R.string.recipes_run_cwd)).perform(scrollTo(), typeText("/fixture space"), closeSoftKeyboard())
            awaitRecipeButton(scenario, R.string.recipes_run, true)
            onView(allOf(withText(R.string.recipes_run), isAssignableFrom(com.google.android.material.button.MaterialButton::class.java))).perform(scrollTo(), click())
            onView(withText(R.string.recipes_no_prompt_ack)).check(matches(isDisplayed()))
            onView(withId(android.R.id.message)).check(matches(withText(org.hamcrest.Matchers.containsString(exact))))
            assertTrue(api.recipeRunBodies.isEmpty())
            onView(withText(R.string.recipes_no_prompt_ack)).perform(click())
            awaitRecipeButton(scenario, R.string.recipes_retry_run, false)
            assertEquals(1, api.recipeRunBodies.size)
            val first = api.recipeRunBodies.single()
            val body = Json.parseToJsonElement(first).jsonObject
            assertEquals(exact, body.getValue("run").jsonPrimitive.content)
            assertEquals("/fixture space", body.getValue("cwd").jsonPrimitive.content)
            assertEquals(36, body.getValue("requestId").jsonPrimitive.content.length)
            onView(withText(R.string.recipes_retry_run)).perform(scrollTo()).check(matches(allOf(isDisplayed(), org.hamcrest.Matchers.not(isEnabled()))))
            awaitRecipeButton(scenario, R.string.recipes_reload, true)
            onView(withText(R.string.recipes_reload)).perform(scrollTo(), click())
            awaitRecipeButton(scenario, R.string.recipes_retry_run, true)
            assertEquals("Read-only reload must not replay", 1, api.recipeRunBodies.size)
            onView(withText(R.string.recipes_retry_run)).perform(scrollTo(), click())
            onView(withId(android.R.id.message)).check(matches(withText(org.hamcrest.Matchers.containsString(exact))))
            onView(allOf(withText(R.string.recipes_retry_run), withId(android.R.id.button1))).perform(click())
            awaitRecipeButton(scenario, R.string.recipes_retry_run, false)
            assertEquals(listOf(first, first), api.recipeRunBodies.toList())
        }
    }

    @Test fun retiredRunConfirmationCannotDispatchItsReviewedCommand() {
        api.recipeRunsEnabled = true
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(app, Scope(), LaunchRecipeActivity.MODE_RUN)).use { scenario ->
            onView(withHint(R.string.recipes_run_input)).perform(scrollTo(), typeText("printf retired-fixture"), closeSoftKeyboard())
            awaitRecipeButton(scenario, R.string.recipes_run, true)
            onView(allOf(withText(R.string.recipes_run), isAssignableFrom(com.google.android.material.button.MaterialButton::class.java))).perform(scrollTo(), click())
            onView(withText(R.string.recipes_no_prompt_ack)).check(matches(isDisplayed()))
            scenario.onActivity { runBlocking { app.container.repository.disconnect() } }
            onView(withText(R.string.recipes_no_prompt_ack)).perform(click())
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(api.recipeRunBodies.isEmpty())
            onView(withText(R.string.recipes_run)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
        }
    }

    private fun awaitRecipeButton(scenario: ActivityScenario<LaunchRecipeActivity>, label: Int, enabled: Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8000L
        do {
            var ready = false
            scenario.onActivity { activity ->
                fun walk(view: View): Boolean {
                    if (view is com.google.android.material.button.MaterialButton && view.text == activity.getString(label) && view.isShown && view.isEnabled == enabled) return true
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (walk(view.getChildAt(i))) return true
                    return false
                }
                ready = walk(activity.window.decorView)
            }
            if (ready) return
            android.os.SystemClock.sleep(100L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        fail("Visible recipe control must reach the expected enabled state")
    }

    @Test fun typedReplyAndSendReachTransportExactlyOnce() {
        launchPane().use { scenario ->
            awaitComposerAdmission(scenario)
            onView(withId(R.id.reply_input)).perform(click(), typeText("hello fixture"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            awaitReplyCompletion(scenario)
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
            // The actions sheet is its own window; its × closes it, not the composer drawer's.
            onView(withId(R.id.collie_sheet_close)).perform(click())
            onView(withId(R.id.pane_action_wrap)).check(doesNotExist())
        }
    }

    @Test fun directTypingSendsCharactersSpaceBackspaceAndEnterInOrder() {
        launchPane().use {
            onView(withId(R.id.type_mode_button)).perform(click())
            onView(withId(R.id.reply_input)).perform(typeText("ab c"), pressKey(KeyEvent.KEYCODE_DEL), pressKey(KeyEvent.KEYCODE_ENTER))
            // The terminal key queue drains through coroutines; Espresso's UI idle check alone
            // does not mean the final queued write has reached the transport.
            onView(isRoot()).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isRoot()
                override fun getDescription() = "Wait for the direct key sequence to reach the transport"
                override fun perform(controller: UiController, view: View) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 4_000L
                    while (api.keys.flatten().size < 6 && android.os.SystemClock.uptimeMillis() < deadline) {
                        controller.loopMainThreadForAtLeast(25)
                    }
                }
            })
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
        // Match the explicit return intent; a launcher MAIN/category intent stops matching
        // ActivityScenario after MainActivity.onNewIntent replaces the activity's intent.
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
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
            awaitDisplayed(R.id.dashboard_panel)
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
                onView(withContentDescription(app.resources.getQuantityString(R.plurals.space_accessibility_count, 1, "Fixture", 1))).perform(click())
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
            awaitComposerAdmission(scenario)
            onView(withId(R.id.reply_input)).perform(typeText("background fixture"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            awaitReplyCompletion(scenario)
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
        launchPane().use { scenario ->
            awaitComposerAdmission(scenario)
            onView(withId(R.id.quick_mode_button)).perform(click())
            onView(withText("Fixture quick reply")).perform(click())
            awaitReplyCompletion(scenario)
            assertEquals(listOf("Fixture quick reply"), api.replies.toList())
            onView(withId(R.id.composer_dock_close)).perform(click())
        }
    }

    @Test fun commandPaletteSearchSelectsAndSendsTheChosenCommand() {
        launchPane().use { scenario ->
            awaitComposerAdmission(scenario)
            onView(withId(R.id.agent_mode_button)).perform(click())
            onView(withHint(app.getString(R.string.pane_agent_search, 1))).perform(typeText("help"), closeSoftKeyboard())
            onView(withText("/help\nFixture help")).perform(click())
            awaitReplyCompletion(scenario)
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
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = info
            try {
                val rows = mutableListOf<android.graphics.Rect>()
                val imeNodes = mutableListOf<String>()
                val exposedKeys = mutableListOf<android.graphics.Rect>()
                var latinKeyboard: android.graphics.Rect? = null
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (node.className == "android.widget.LinearLayout") rows += bounds
                    // Only IME layout metadata; never capture text from another app.
                    imeNodes += "${node.className}:${node.viewIdResourceName}:$bounds"
                    if (node.className?.toString()?.endsWith(".Key") == true && !bounds.isEmpty) {
                        exposedKeys += android.graphics.Rect(bounds)
                    }
                    if (node.className == "android.view.View" &&
                        node.viewIdResourceName == "com.android.inputmethod.latin:id/keyboard_view" && !bounds.isEmpty) {
                        latinKeyboard = android.graphics.Rect(bounds)
                    }
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                val keyboardDeadline = android.os.SystemClock.uptimeMillis() + 5000
                var previousRows: List<android.graphics.Rect> = emptyList()
                var stableSince = android.os.SystemClock.uptimeMillis()
                do {
                    rows.clear()
                    imeNodes.clear()
                    exposedKeys.clear()
                    latinKeyboard = null
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .forEach { collect(it.root) }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (rows.isEmpty() || rows != previousRows) stableSince = now
                    previousRows = rows.toList()
                    if (rows.isNotEmpty() && now - stableSince >= 300) break
                    Thread.sleep(100)
                } while (android.os.SystemClock.uptimeMillis() < keyboardDeadline)
                val targets = if (exposedKeys.isNotEmpty()) {
                    // LatinIME exposes actual key bounds. Group those keys, excluding its toolbar and
                    // Android's navigation bar; a number row can precede the four QWERTY rows.
                    val keyRows = exposedKeys.distinct().groupBy { it.top }.toSortedMap().values
                        .map { row -> row.sortedBy { it.left } }
                    assertTrue("IME did not expose four QWERTY key rows: $imeNodes", keyRows.size >= 4)
                    val qwertyRows = keyRows.takeLast(4)
                    val letters = qwertyRows[0]
                    val bottom = qwertyRows[3]
                    assertEquals("English QWERTY first row", 10, letters.size)
                    assertTrue("English QWERTY middle rows", qwertyRows[1].size >= 9 && qwertyRows[2].size >= 9)
                    val space = bottom.maxByOrNull { it.width() } ?: error("IME space key unavailable")
                    assertTrue("IME space key must span several letter keys", space.width() > letters[0].width() * 2)
                    val backspace = qwertyRows[2].last()
                    val enter = bottom.last()
                    listOf(letters[0], letters[1], space, backspace, enter)
                        .map { it.exactCenterX() to it.exactCenterY() }
                } else if (latinKeyboard != null) {
                    // This LatinIME build exposes its actual key canvas, not individual keys.
                    // Its measured four-row English layout excludes the suggestion strip and
                    // Android navigation bar; retain exact delivered-key verification below.
                    val canvas = requireNotNull(latinKeyboard)
                    assertTrue("LatinIME canvas must occupy the keyboard width", canvas.width() > app.resources.displayMetrics.widthPixels * 0.8)
                    fun target(column: Float, row: Int) =
                        canvas.left + canvas.width() * column to canvas.top + canvas.height() * ((row + 0.5f) / 4f)
                    listOf(target(0.05f, 0), target(0.15f, 0), target(0.5f, 3), target(0.95f, 2), target(0.95f, 3))
                } else {
                    // Gboard exposes row containers on this fixture, preserving its verified path.
                    val density = app.resources.displayMetrics.density
                    val width = app.resources.displayMetrics.widthPixels
                    val letters = rows.distinct().filter { it.width() > width * 0.8 && it.height() > density * 100 }.minByOrNull { it.height() }
                        ?: error("Gboard letter rows unavailable: $rows density=$density width=$width")
                    val bottom = rows.distinct().filter { it.width() > width * 0.8 && it.height() in 1..(density * 100).toInt() && kotlin.math.abs(it.top - letters.bottom) < density * 4 }
                        .minByOrNull { kotlin.math.abs(it.top - letters.bottom) }
                        ?: error("Gboard bottom row unavailable: $rows letters=$letters")
                    val rowCount = kotlin.math.round(letters.height().toFloat() / bottom.height()).toInt().coerceIn(3, 4)
                    val rowHeight = letters.height() / rowCount.toFloat()
                    val firstLetterRow = rowCount - 3
                    fun rowCenter(row: Int) = letters.top + rowHeight * (firstLetterRow + row + 0.5f)
                    listOf(
                        letters.left + letters.width() * 0.05f to rowCenter(0),
                        letters.left + letters.width() * 0.15f to rowCenter(0),
                        bottom.exactCenterX() to bottom.exactCenterY(),
                        letters.left + letters.width() * 0.95f to rowCenter(2),
                        bottom.left + bottom.width() * 0.95f to bottom.exactCenterY(),
                    )
                }
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
                targets.forEach { (x, y) -> tap(x, y) }
                val deliveryDeadline = android.os.SystemClock.uptimeMillis() + 3000
                while (api.keys.flatten().size < 5 && android.os.SystemClock.uptimeMillis() < deliveryDeadline) Thread.sleep(50)
                var detail = ""
                scenario.onActivity {
                    val input = it.findViewById<DirectTypingEditText>(R.id.reply_input)
                    detail = "targets=$targets direct=${input.directTypingEnabled} draft=${input.text} imeNodes=$imeNodes"
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
        launchPane().use { scenario ->
            awaitComposerAdmission(scenario)
            scenario.onActivity { it.acceptUploadForTest("/fixture/upload.png") }
            onView(withId(R.id.reply_input)).perform(typeText("keep this draft"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            awaitReplyCompletion(scenario)
            onView(withId(R.id.reply_input)).check(matches(withText("keep this draft")))
            onView(withId(R.id.error_text)).check(matches(isDisplayed()))
            assertEquals(listOf("/fixture/upload.png\nkeep this draft"), api.replies.toList())
            scenario.onActivity {
                assertEquals("/fixture/upload.png\nkeep this draft", it.outgoingReplyForTest("keep this draft"))
            }
            onView(withId(R.id.reply_input)).perform(replaceText(""), closeSoftKeyboard())
        }
    }

    @Test fun newSpaceFormSubmitsItsLabelAndDirectory() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitDisplayed(R.id.new_space)
            onView(withId(R.id.new_space)).perform(click())
            onView(withHint(R.string.space_directory_optional)).perform(typeText("/fixture"), closeSoftKeyboard())
            onView(withHint(R.string.space_label_optional)).perform(typeText("New fixture"), closeSoftKeyboard())
            onView(withText(R.string.action_create)).check { view, failure ->
                if (failure != null) throw failure
                var current: View? = view
                while (current != null) {
                    val item = current
                    val rect = android.graphics.Rect()
                    val visible = item.getGlobalVisibleRect(rect)
                    android.util.Log.i("CollieFixtureSheet", "${item.javaClass.simpleName} id=${item.id} " +
                        "height=${item.height} measured=${item.measuredHeight} top=${item.top} scrollY=${item.scrollY} " +
                        "layoutHeight=${item.layoutParams?.height} visible=$visible rect=$rect")
                    if (item.id == com.google.android.material.R.id.design_bottom_sheet) {
                        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(item)
                        android.util.Log.i("CollieFixtureSheet", "state=${behavior.state} fitToContents=${behavior.isFitToContents} " +
                            "peekHeight=${behavior.peekHeight} expandedOffset=${behavior.expandedOffset} maxHeight=${behavior.maxHeight}")
                    }
                    current = item.parent as? View
                }
            }
            onView(withText(R.string.action_create)).perform(scrollTo(), click())
            awaitTransportMutation { api.createdSpaces.isNotEmpty() }
            assertEquals(listOf("New fixture" to "/fixture"), api.createdSpaces)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun newSpaceLongFolderListScrollsToFullyVisibleCreateAndSubmitsOnce() {
        api.folderRecent = (1..8).map { "/fixture/recent-$it" }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitDisplayed(R.id.new_space)
            onView(withId(R.id.new_space)).perform(click())
            awaitFolderRow("/fixture/recent-8")
            onView(withHint(R.string.space_directory_optional)).perform(scrollTo(), typeText("/fixture"), closeSoftKeyboard())
            onView(withHint(R.string.space_label_optional)).perform(scrollTo(), typeText("Long folder fixture"), closeSoftKeyboard())
            onView(withText(R.string.action_create)).perform(scrollTo()).check { view, failure ->
                if (failure != null) throw failure
                var ancestor = view.parent as? View
                while (ancestor != null && ancestor !is androidx.core.widget.NestedScrollView) ancestor = ancestor.parent as? View
                val viewport = ancestor as? androidx.core.widget.NestedScrollView
                    ?: throw AssertionError("Create must use the canonical scrolling viewport")
                assertTrue("Folder list must produce real overflow", viewport.getChildAt(0).height > viewport.height)
                assertTrue("Reaching Create must scroll the real viewport", viewport.scrollY > 0)
                val rect = android.graphics.Rect()
                assertTrue(view.getGlobalVisibleRect(rect))
                assertEquals("The entire Create target must be visible", view.height, rect.height())
                assertTrue("Create must retain a 48dp target", view.height >= (48 * view.resources.displayMetrics.density).toInt())
                android.util.Log.i("CollieFixtureSheet", "overflow viewport=${viewport.height} content=${viewport.getChildAt(0).height} scrollY=${viewport.scrollY} createHeight=${view.height} rect=$rect")
            }.perform(click())
            awaitTransportMutation { api.createdSpaces.isNotEmpty() }
            assertEquals(listOf("Long folder fixture" to "/fixture"), api.createdSpaces)
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

    /** One repository: the worktree sheet opens straight to the branch field (B.3). */
    private fun openWorktreeChoices() {
        onView(withId(R.id.new_space)).perform(click())
        onView(withText(R.string.space_mode_worktree)).perform(click())
    }

    @Test fun createWorktreeSendsTheEnteredBranch() {
        api.workspace = api.workspace.copy(repoRoot = "/fixture", isWorktree = false)
        ActivityScenario.launch(MainActivity::class.java).use {
            openWorktreeChoices()
            onView(withId(R.id.worktree_branch_input)).perform(typeText("fixture-branch"), closeSoftKeyboard())
            onView(withText(R.string.action_create)).perform(click())
            awaitTransportMutation { api.worktreeBranches.isNotEmpty() }
            assertEquals(listOf("fixture-branch"), api.worktreeBranches)
            onView(withId(R.id.composer_chrome)).check(matches(isDisplayed()))
        }
    }

    @Test fun openWorktreeWaitsForConfirmation() {
        api.workspace = api.workspace.copy(repoRoot = "/fixture", isWorktree = false)
        ActivityScenario.launch(MainActivity::class.java).use {
            openWorktreeChoices()
            // The branch field opens the IME; dismiss it before scrolling to the existing-worktree
            // action, exactly as a user does when choosing an existing branch instead of typing.
            onView(withId(R.id.worktree_branch_input)).perform(closeSoftKeyboard())
            onView(withText("existing-fixture")).perform(scrollTo(), click())
            assertTrue(api.openedWorktrees.isEmpty())
            onView(withText(R.string.action_open)).perform(click())
            assertEquals(listOf("/fixture/existing"), api.openedWorktrees)
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
        launchPane().use { scenario ->
            onView(withText("Fixture terminal\n$ ")).check(matches(isDisplayed()))
            val readsBeforeOutage = api.reads
            api.offline = true
            awaitNetworkUiState(
                scenario = scenario,
                expectedErrorVisible = true,
                minimumReadCount = readsBeforeOutage + 1,
                phase = "offline",
            )
            val readsDuringOutage = api.reads
            api.offline = false
            awaitNetworkUiState(
                scenario = scenario,
                expectedErrorVisible = false,
                minimumReadCount = readsDuringOutage + 1,
                phase = "recovery",
            )
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
            val pickerIntent = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                .createIntent(app, androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ))
            val pickerPackage = pickerIntent.resolveActivity(app.packageManager)?.packageName
            val photoPickerAvailable = androidx.activity.result.contract.ActivityResultContracts
                .PickVisualMedia.isPhotoPickerAvailable(app)
            assertNotNull("No system image-picker handler", pickerPackage)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var pickerVisible = false
            while (!pickerVisible && android.os.SystemClock.uptimeMillis() < deadline) {
                val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                // Modern picker provider UI can live in a different package from its resolved
                // entrypoint. Retain its existing provider-window check; fallback DocumentsUI
                // is checked against the actual contract-selected handler.
                pickerVisible = if (photoPickerAvailable) {
                    activePackage.contains("photopicker") || activePackage.contains("providers.media")
                } else activePackage == pickerPackage
                if (!pickerVisible) Thread.sleep(50)
            }
            assertTrue("System photo picker did not open", pickerVisible)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else {
                // API26 instrumentation cannot inject into DocumentsUI under the app identity.
                // UiAutomation uses the existing test shell identity for the real Back key.
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    assertTrue("System picker Back event was not accepted", instrumentation.uiAutomation
                        .injectInputEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0), true))
                }
            }
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
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            automation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        } else {
            // API 26 has no UiAutomation.grantRuntimePermission. Grant only this test application's
            // declared permission, using the same shell identity as the modern automation helper.
            check(app.packageName.matches(Regex("[A-Za-z0-9_.]+")))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "pm grant ${app.packageName} android.permission.RECORD_AUDIO",
            )).use { stream -> stream.copyTo(java.io.ByteArrayOutputStream()) }
        }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
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
                R.id.right_button to "Right", R.id.enter_button to "Enter", R.id.ctrl_c_button to "ctrl+c",
                R.id.space_button to "Space")
            val gestureLog = mutableListOf<String>()
            keys.forEach { (id, label) ->
                onView(withId(id)).perform(shortNamedKeyTap(label, gestureLog))
            }
            assertEquals("Short real touch gesture log: ${gestureLog.joinToString()}",
                keys.map { it.second }, api.keys.flatten())
        }
    }

    private fun shortNamedKeyTap(label: String, gestureLog: MutableList<String>) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = allOf(isDisplayed(), isEnabled())

        override fun getDescription(): String = "dispatch a 40 ms touch for $label"

        override fun perform(ui: UiController, view: View) {
            val root = view.rootView
            val viewPosition = IntArray(2)
            val rootPosition = IntArray(2)
            view.getLocationOnScreen(viewPosition)
            root.getLocationOnScreen(rootPosition)
            val x = viewPosition[0] - rootPosition[0] + view.width / 2f
            val y = viewPosition[1] - rootPosition[1] + view.height / 2f
            val downTime = android.os.SystemClock.uptimeMillis()
            val upTime = downTime + 40L
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            try {
                assertTrue("$label touch DOWN was not handled", root.dispatchTouchEvent(down))
                assertTrue("$label touch UP was not handled", root.dispatchTouchEvent(up))
                gestureLog += "$label durationMs=${up.eventTime - down.eventTime}"
            } finally {
                down.recycle()
                up.recycle()
            }
            // Allow the click listener and repository flow to complete after both events have
            // traversed the real view hierarchy; do not yield between DOWN and UP.
            ui.loopMainThreadForAtLeast(100L)
        }
    }

    @Test fun digitAndFunctionKeysCanBeReachedAndSent() {
        launchPane().use {
            onView(withId(R.id.keys_mode_button)).perform(click())
            onView(withId(R.id.pane_keys_segment_digits)).perform(click())
            (1..9).forEach { onView(withText(it.toString())).perform(click()) }
            onView(withId(R.id.pane_keys_segment_keys)).perform(click())
            onView(withText(R.string.pane_keys_more)).perform(click())
            (1..12).forEach { onView(withText("F$it")).perform(click()) }
            // F-keys live in the More sheet (A.4), which closes with its own ×; the drawer
            // underneath keeps its height, so there is no expanded pad left to collapse.
            onView(withId(R.id.collie_sheet_close)).perform(click())
            onView(withId(R.id.pane_keys_segment_digits)).perform(scrollTo(), click())
            assertEquals((1..9).map(Int::toString) + (1..12).map { "F$it" }, api.keys.flatten())
        }
    }

    @Test fun codexComposerSendsWithItsVisiblePromptBinding() {
        api.summary = api.summary.copy(agent = "codex", kind = null)
        api.paneText = "transcript\n\n› \n\n  gpt-5 · /repo · Context 50% left"
        launchPane().use { scenario ->
            awaitComposerAdmission(scenario)
            onView(withId(R.id.reply_input)).perform(typeText("Fixture codex reply"), closeSoftKeyboard())
            onView(withId(R.id.send_button)).perform(click())
            awaitReplyCompletion(scenario)
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

    /** Observe asynchronous structural dispatch before asserting its exact one-shot payload. */
    private fun awaitTransportMutation(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(20L)
        }
        assertTrue("Accepted structural mutation did not reach the fixture transport", condition())
    }

    /** Observe asynchronous initial admission/activity routing; keep the final visible assertion. */
    private fun awaitFolderRow(path: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        do {
            try {
                onView(withText(path)).check(matches(withText(path)))
                return
            } catch (_: androidx.test.espresso.NoMatchingViewException) {
                // The captured folder response may not have rendered its rows yet.
            }
            android.os.SystemClock.sleep(50L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        onView(withText(path)).check(matches(withText(path)))
    }

    /** Observe asynchronous initial admission/activity routing; keep the final visible assertion. */
    private fun awaitDisplayed(id: Int) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        do {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (_: androidx.test.espresso.NoMatchingViewException) {
                // A destination Activity may not have attached its root yet.
            } catch (_: AssertionError) {
                // The bound view may still be waiting for its initial admitted snapshot.
            }
            android.os.SystemClock.sleep(50L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        onView(withId(id)).check(matches(isDisplayed()))
    }

    /** Positive send fixtures wait for initial pane/config/snapshot admission before user input. */
    private fun awaitComposerAdmission(scenario: ActivityScenario<PaneActivity>) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        var admitted = false
        do {
            scenario.onActivity { activity ->
                val field = PaneActivity::class.java.getDeclaredField("currentState").apply { isAccessible = true }
                val state = field.get(activity) as PaneUiState
                admitted = state.canWrite && state.composerReady && !state.sending
            }
            if (admitted) return
            android.os.SystemClock.sleep(50L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertTrue("Positive fixture composer did not gain verified write admission", admitted)
    }

    /** Wait for the asynchronous reply actor, retaining the exact per-test transport assertions. */
    private fun awaitReplyCompletion(scenario: ActivityScenario<PaneActivity>) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        var completed = false
        do {
            scenario.onActivity { activity ->
                val field = PaneActivity::class.java.getDeclaredField("currentState").apply { isAccessible = true }
                val state = field.get(activity) as PaneUiState
                completed = api.replies.isNotEmpty() && !state.sending
            }
            if (completed) return
            android.os.SystemClock.sleep(50L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertTrue("Reply actor did not complete within the bounded fixture wait", completed)
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

    private fun awaitNetworkUiState(
        scenario: ActivityScenario<PaneActivity>,
        expectedErrorVisible: Boolean,
        minimumReadCount: Int,
        phase: String,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + 70_000L
        var errorVisible = false
        var terminalVisible = false
        var terminalText = ""
        var observedReadCount = api.reads
        do {
            scenario.onActivity { activity ->
                val error = activity.findViewById<View>(R.id.error_text)
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                errorVisible = error.isShown
                terminalVisible = terminal.isShown
                terminalText = terminal.text?.toString().orEmpty()
            }
            observedReadCount = api.reads
            assertTrue("Terminal mirror disappeared during $phase", terminalVisible)
            assertEquals("Stale terminal mirror changed during $phase", "Fixture terminal\n$ ", terminalText)
            assertTrue("A user write occurred during $phase", api.replies.isEmpty() && api.keys.isEmpty())
            if (errorVisible == expectedErrorVisible && observedReadCount >= minimumReadCount) return
            android.os.SystemClock.sleep(100L)
        } while (android.os.SystemClock.uptimeMillis() < deadline)

        assertEquals("Pane error visibility did not reach the expected $phase state", expectedErrorVisible, errorVisible)
        assertTrue("No pane read completed during $phase", observedReadCount >= minimumReadCount)
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
        var recipeRunsEnabled = false
        val recipeRunBodies = CopyOnWriteArrayList<String>()
        var folderRecent: List<String> = emptyList()
        var pairedDevice: String? = "Fixture"
        var speechAvailable = false
        var transcriptions = 0
        var servers: List<ServerSummary>? = null
        var sessions: List<SessionSummary> = emptyList()
        var workspace = WorkspaceSummary("w1", 1, "Fixture", false, "t1", 1, 1)
        @Volatile var offline = false
        val worktreeBranches = CopyOnWriteArrayList<String>()
        val openedWorktrees = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val createdSpaces = CopyOnWriteArrayList<Pair<String?, String?>>()
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
            receiptContextBound = recipeRunsEnabled,
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
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> {
            reads++
            return if (offline) ApiResult.Failure(ApiFailure.Network("Fixture offline")) else
                ok(PaneReadResponse(address.paneId, paneText, false, 1))
        }
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
        override suspend fun createWorktreeFrozen(connection: Connection, scope: Scope,
            workspaceId: String, canonicalBody: String): ApiResult<WorktreeOpenResponse> {
            val body = Json.parseToJsonElement(canonicalBody).jsonObject
            val branch = requireNotNull(body["branch"]).jsonPrimitive.content
            require(branch.isNotBlank())
            require(workspaceId == workspace.workspaceId)
            return createWorktree(connection, scope, workspaceId, branch)
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
        override suspend fun folders(connection: Connection, scope: Scope) =
            ok(FoldersResponse(recent = folderRecent, favourites = emptyList(), home = "/fixture"))
        override suspend fun launchers(connection: Connection, scope: Scope) =
            ok(LaunchersResponse(listOf(Launcher("fixture-command", "Fixture launcher")), "/fixture",
                adding = if (recipeRunsEnabled) LaunchersAdding(true, true, true, "fixture-only.toml", 0, 20, emptyList()) else null))
        override suspend fun checkRun(connection: Connection, scope: Scope, run: String) =
            ok(LaunchCheckResponse(ok = true, noPrompts = true))
        override suspend fun startRunFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
            check(recipeRunsEnabled)
            recipeRunBodies += canonicalBody
            return ApiResult.Failure(ApiFailure.Network("Owned fixture lost run response"))
        }
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
        override suspend fun crew(connection: Connection) = ok(CrewStatusResponse(
            CrewIdentity("fixture", "Fixture pack", 1, 1), CrewSelf("lead", "Fixture host", "1.5.1"), null,
            listOf(CrewMember("lead", "Fixture host", true, health = "reachable", lastSeenAt = 1,
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
        override suspend fun history(connection: Connection, address: PaneAddress, limit: Int, before: String?, etag: String?) =
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
