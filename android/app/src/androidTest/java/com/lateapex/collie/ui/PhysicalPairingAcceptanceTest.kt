package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pairs the disposable acceptance app through its native form; never prints the bootstrap code. */
@RunWith(AndroidJUnit4::class)
class PhysicalPairingAcceptanceTest {
    @Test fun disconnectDisposableAppThroughNativeSettings() {
        val args = InstrumentationRegistry.getArguments()
        val expectedOriginInput = args.getString("expectedOrigin")
        assumeTrue("No controlled native-disconnect requested", expectedOriginInput != null)
        val expectedOrigin = requireNotNull(expectedOriginInput)
        assertTrue("A supplied expectedOrigin must not be blank", expectedOrigin.isNotBlank())
        check(BuildConfig.APPLICATION_ID.endsWith(".completiontest")) {
            "Native disconnect acceptance requires isolated app identity"
        }
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val expected = app.container.originValidator.requireValid(expectedOrigin)
        val existing = app.container.connectionStore.connection.value
        assertTrue("Disconnect requires the already-paired task-owned profile at exactly $expected", existing?.isPaired == true && existing.origin == expected)

        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.disconnect_button)).perform(scrollTo(), click())
            onView(withText(R.string.settings_disconnect_confirm_title))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_disconnect))
                .inRoot(isDialog())
                .perform(click())

            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && app.container.connectionStore.connection.value != null) {
                Thread.sleep(100)
            }
            assertTrue("Native Settings disconnect did not clear the local paired profile", app.container.connectionStore.connection.value == null)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.setup_panel)).check(matches(isDisplayed()))
        }
    }

    @Test fun pairDisposableAppThroughNativeForm() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("pairingOrigin")
        assumeTrue("No controlled pairing requested", origin != null)
        check(BuildConfig.APPLICATION_ID.endsWith(".completiontest")) { "Pairing acceptance requires isolated app identity" }
        val code = args.getString("pairingCode") ?: error("Private bootstrap code required")
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        check(app.container.connectionStore.connection.value?.isPaired != true) { "Existing paired profile must be preserved" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val layoutDeadline = android.os.SystemClock.uptimeMillis() + 10_000
            var formReady = false
            while (!formReady && android.os.SystemClock.uptimeMillis() < layoutDeadline) {
                scenario.onActivity { activity ->
                    val input = activity.findViewById<EditText>(R.id.origin_input)
                    formReady = input.isShown && input.width > 0 && input.height > 0 && !input.isLayoutRequested
                }
                if (!formReady) android.os.SystemClock.sleep(50)
            }
            assertTrue("The normal native pairing form must finish layout before input", formReady)
            onView(withId(R.id.origin_input)).perform(replaceText(origin!!))
            onView(withId(R.id.device_label_input)).perform(replaceText("completion Pixel"))
            onView(withId(R.id.pairing_code_input)).perform(object : ViewAction {
                override fun getDescription() = "Enter private disposable pairing code"
                override fun getConstraints(): Matcher<View> = isAssignableFrom(EditText::class.java)
                override fun perform(controller: UiController, view: View) {
                    (view as EditText).setText(code)
                    controller.loopMainThreadUntilIdle()
                }
            }, closeSoftKeyboard())
            onView(withId(R.id.pair_button)).perform(click())
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline && app.container.connectionStore.connection.value?.isPaired != true) Thread.sleep(100)
            val connection = app.container.connectionStore.connection.value
            assertTrue("Native pairing did not complete for the configured controlled origin", connection?.isPaired == true && connection.origin == app.container.originValidator.requireValid(origin))
        }
    }
}
