package com.lateapex.collie.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.PackMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side layout checks use local fixtures only and never contact an operator's server. */
@RunWith(AndroidJUnit4::class)
class HardeningUiDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Test
    fun paneDraftSurvivesRealOrientationChange() {
        val paneId = "hardening-rotation-fixture"
        val connectionStore = app.container.connectionStore
        val previousConnection = connectionStore.connection.value
        val draftKey = listOf("", "", "", paneId).joinToString("|")
        val preferences = NativePreferences(app)
        val intent = Intent(app, PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
            .putExtra(PaneActivity.EXTRA_AGENT, "shell")
            .putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        var originalOrientation = app.resources.configuration.orientation

        try {
            connectionStore.clear()
            ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        originalOrientation = activity.resources.configuration.orientation
                        activity.findViewById<EditText>(R.id.reply_input).setText("rotation-safe local draft")
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                    val targetOrientation = if (originalOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        Configuration.ORIENTATION_PORTRAIT
                    } else {
                        Configuration.ORIENTATION_LANDSCAPE
                    }
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = requestedOrientation(targetOrientation)
                    }
                    awaitOrientation(scenario, targetOrientation)
                    scenario.onActivity { activity ->
                        assertEquals(
                            "rotation-safe local draft",
                            activity.findViewById<EditText>(R.id.reply_input).text.toString(),
                        )
                    }
                } finally {
                    scenario.onActivity { activity ->
                        activity.requestedOrientation = requestedOrientation(originalOrientation)
                    }
                    awaitOrientation(scenario, originalOrientation)
                }
            }
        } finally {
            preferences.savePaneDraft(draftKey, "")
            restoreConnection(previousConnection)
        }
    }

    @Test
    fun settingsDraftControlsRemainTouchReachableAtTwoHundredPercentSystemFont() {
        val fontScale = app.resources.configuration.fontScale
        assertTrue(
            "Run this case with the emulator font scale set to 200% (was $fontScale)",
            fontScale >= 1.9f,
        )

        val connectionStore = app.container.connectionStore
        val previousConnection = connectionStore.connection.value
        val preferences = NativePreferences(app)
        val originalDraftSize = preferences.draftFontSize
        val testDraftSize = 14
        try {
            connectionStore.clear()
            preferences.draftFontSize = testDraftSize
            ActivityScenario.launch(SettingsActivity::class.java).use {
                onView(withId(R.id.settings_draft_size_increase))
                    .perform(scrollTo())
                    .check(matches(isDisplayed()))
                    .perform(click())
                assertEquals(testDraftSize + 1, preferences.draftFontSize)
                onView(withId(R.id.settings_draft_size_decrease))
                    .perform(scrollTo())
                    .check(matches(isDisplayed()))
                    .perform(click())
                assertEquals(testDraftSize, preferences.draftFontSize)
            }
        } finally {
            preferences.draftFontSize = originalDraftSize
            restoreConnection(previousConnection)
        }
    }

    @Test
    fun setupActionsKeepFullLabelsVisibleAtTwoHundredPercentSystemFont() {
        val fontScale = app.resources.configuration.fontScale
        assertTrue(
            "Run this case with the emulator font scale set to 200% (was $fontScale)",
            fontScale >= 1.9f,
        )

        val connectionStore = app.container.connectionStore
        val previousConnection = connectionStore.connection.value
        try {
            connectionStore.clear()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val displayWidthDp = scenarioActivityWidthDp(scenario)
                onView(withId(R.id.pair_button)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.connect_button)).perform(scrollTo()).check(matches(isDisplayed()))

                scenario.onActivity { activity ->
                    val labels = listOf(
                        R.id.pair_button to R.string.pair_and_connect,
                        R.id.connect_button to R.string.connect_read_only,
                    )
                    labels.forEach { (buttonId, labelId) ->
                        val button = activity.findViewById<TextView>(buttonId)
                        val textLayout = requireNotNull(button.layout) { "button text must be laid out" }
                        val ellipsisCount = (0 until textLayout.lineCount).sumOf(textLayout::getEllipsisCount)
                        val expectedLabel = activity.getString(labelId)

                        assertEquals(
                            "the visible button text must remain complete",
                            expectedLabel,
                            button.text.toString(),
                        )
                        assertEquals("button label must not be ellipsized", 0, ellipsisCount)
                        assertTrue(
                            "button height must include all text lines and vertical padding at $displayWidthDp dp",
                            button.height >= textLayout.height + button.compoundPaddingTop +
                                button.compoundPaddingBottom,
                        )
                        assertTrue(
                            "the setup action keeps a 48 dp minimum touch target",
                            button.minimumHeight >= 48f * button.resources.displayMetrics.density,
                        )
                        assertTrue("setup action remains enabled and touchable", button.isEnabled && button.isClickable)
                        if (displayWidthDp <= 360f) {
                            assertTrue(
                                "narrow large-text layout should wrap the complete label",
                                textLayout.lineCount >= 2,
                            )
                        }

                        val visibleBounds = Rect()
                        assertTrue(
                            "scrolling should place the full control on screen",
                            button.getGlobalVisibleRect(visibleBounds),
                        )
                        assertTrue("visible button area must be non-empty", visibleBounds.height() >= button.height)
                    }
                }
            }
        } finally {
            restoreConnection(previousConnection)
        }
    }

    @Test
    fun scaledPackFormationDrawsAndUsesShiftedTouchAndAccessibilityBounds() {
        val connectionStore = app.container.connectionStore
        val previousConnection = connectionStore.connection.value
        connectionStore.clear()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val configuration = Configuration(activity.resources.configuration).apply { fontScale = 2f }
                    val context = activity.createConfigurationContext(configuration)
                    val members = listOf(member("lead", lead = true), member("deputy")) +
                        (1..6).map { member("peer-$it") }
                    val blocked = members.associate { it.id to 3 }
                    val view = PackFormationView(context)
                    var selected: PackMember? = null
                    view.submit(members, "deputy", blocked, onSelected = { selected = it })

                    val widthPx = (240f * context.resources.displayMetrics.density).toInt()
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    content.addView(
                        view,
                        ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)

                    val canonical = PackFormationLayout.nodes(members, "deputy").associateBy { it.member.id }
                    val laidOut = view.laidOutNodes.associateBy { it.member.id }
                    assertTrue(
                        "role/count badges at 200% should expand overlapping rows",
                        laidOut.getValue("deputy").y > canonical.getValue("deputy").y,
                    )
                    assertTrue("the adjusted formation must extend below canonical content", view.measuredHeight > 0)

                    val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))

                        val provider = view.accessibilityNodeProvider
                        assertNotNull("attached formation should expose a platform virtual-node provider", provider)
                        val nodeInfo = requireNotNull(provider).createAccessibilityNodeInfo(0)
                        assertNotNull("platform provider should resolve the lead virtual node", nodeInfo)
                        assertEquals(
                            activity.getString(
                                R.string.pack_node_accessibility,
                                "Fixture lead",
                                activity.getString(R.string.pack_role_lead),
                                activity.getString(R.string.health_reachable),
                            ),
                            requireNotNull(nodeInfo).contentDescription.toString(),
                        )
                        val bounds = Rect()
                        requireNotNull(nodeInfo).getBoundsInParent(bounds)
                        val canvasScale = widthPx / PackFormationLayout.VIEW_WIDTH
                        assertTrue(
                            "virtual node bounds should include the badge top inset",
                            bounds.exactCenterY() / canvasScale > laidOut.getValue("lead").y,
                        )
                        val leadPixel = bitmap.getPixel(bounds.centerX(), bounds.centerY())
                        assertTrue("drawn lead node should occupy the accessibility center", (leadPixel ushr 24) > 0)

                        assertTrue(
                            "the lead accessibility node should activate through its click action",
                            requireNotNull(provider).performAction(
                                0,
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                                null,
                            ),
                        )
                        assertEquals("accessibility click should select the lead", "lead", selected?.id)
                        selected = null

                        val event = MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            bounds.exactCenterX(),
                            bounds.exactCenterY(),
                            0,
                        )
                        try {
                            assertTrue(view.onTouchEvent(event))
                        } finally {
                            event.recycle()
                        }
                        assertEquals("lead", selected?.id)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            restoreConnection(previousConnection)
        }
    }

    private fun awaitOrientation(scenario: ActivityScenario<PaneActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var actual = Configuration.ORIENTATION_UNDEFINED
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { actual = it.resources.configuration.orientation }
            if (actual != expected) SystemClock.sleep(100)
        }
        assertEquals("activity did not reach requested orientation", expected, actual)
    }

    private fun scenarioActivityWidthDp(scenario: ActivityScenario<MainActivity>): Float {
        var widthDp = 0f
        scenario.onActivity { activity ->
            widthDp = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
        }
        return widthDp
    }

    private fun requestedOrientation(configurationOrientation: Int): Int =
        if (configurationOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    private fun restoreConnection(connection: Connection?) {
        app.container.connectionStore.clear()
        connection?.let(app.container.connectionStore::save)
    }

    private fun member(id: String, lead: Boolean = false) = PackMember(
        id = id,
        name = "Fixture $id",
        isLead = lead,
        health = "reachable",
        lastSeenAt = 1,
        secretBehind = false,
        provisional = false,
    )

}
