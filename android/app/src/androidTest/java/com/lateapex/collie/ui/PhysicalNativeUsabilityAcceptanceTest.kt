package com.lateapex.collie.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButtonToggleGroup
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical checks for native theme, IME, large-text, and TalkBack behavior on a paired stock pane. */
@RunWith(AndroidJUnit4::class)
class PhysicalNativeUsabilityAcceptanceTest {
    private data class Live(
        val app: CollieApplication,
        val connection: Connection,
        val actor: ScopedReadContext,
        val address: PaneAddress,
    )

    @Test
    fun nativeThemeChoicesAndLargeTextKeepComposerUsableAboveThePhysicalIme() {
        val live = live()
        val preferences = NativePreferences(live.app)
        val originalTheme = preferences.themeMode
        try {
            selectTheme(live.app, preferences, NativePreferences.ThemeMode.LIGHT)
            assertSettingsTheme(live.app, NativePreferences.ThemeMode.LIGHT)
            assertPaneChromeAndKeyboard(live, NativePreferences.ThemeMode.LIGHT)

            selectTheme(live.app, preferences, NativePreferences.ThemeMode.DARK)
            assertSettingsTheme(live.app, NativePreferences.ThemeMode.DARK)
            assertPaneChromeAndKeyboard(live, NativePreferences.ThemeMode.DARK)
        } finally {
            selectTheme(live.app, preferences, originalTheme)
        }
        assertTrue("Physical usability checks must preserve the original paired connection", live.app.container.connectionStore.connection.value === live.connection)
    }

    @Test
    fun enabledTalkBackCanOpenSettingsAndReturnFromTheLivePane() {
        val live = live()
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val serviceInfo = automation.serviceInfo
        val previousFlags = serviceInfo.flags
        val manager = live.app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assertTrue(
            "Physical TalkBack acceptance requires the real spoken accessibility service",
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .any { it.resolveInfo.serviceInfo.packageName == TALKBACK_PACKAGE },
        )
        assertTrue("Physical TalkBack acceptance requires touch exploration", manager.isTouchExplorationEnabled)
        serviceInfo.flags = previousFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = serviceInfo
        try {
            ActivityScenario.launch(MainActivity::class.java).use { main ->
                activateNode(
                    automation,
                    live.app.resources.getResourceName(R.id.settings_button),
                    R.string.connection_settings,
                )
                assertNodeVisible("TalkBack activation must navigate to Settings", awaitNode(
                    automation,
                    live.app.resources.getResourceName(R.id.settings_header_title),
                ))
                activateNode(
                    automation,
                    live.app.resources.getResourceName(R.id.settings_back_button),
                    R.string.settings_back,
                )
                assertNodeVisible("TalkBack Settings Back must return to the dashboard", awaitNode(
                    automation,
                    live.app.resources.getResourceName(R.id.settings_button),
                ))
                main.onActivity { activity -> activity.startActivity(paneIntent(live)) }
                assertNodeVisible("The controlled pane must appear above the retained dashboard", awaitNode(
                    automation,
                    live.app.resources.getResourceName(R.id.pane_title),
                ))
                assertCurrentLivePane(live)
                activateNode(
                    automation,
                    live.app.resources.getResourceName(R.id.back_button),
                    R.string.pane_back,
                )
                assertNodeVisible("TalkBack pane Back must return to the dashboard", awaitNode(
                    automation,
                    live.app.resources.getResourceName(R.id.settings_button),
                ))
            }
        } finally {
            serviceInfo.flags = previousFlags
            automation.serviceInfo = serviceInfo
        }
        assertTrue("TalkBack interaction must preserve the original paired connection", live.app.container.connectionStore.connection.value === live.connection)
    }

    private fun live(): Live {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; physical native usability is opt-in", originInput != null)
        val originText = requireNotNull(originInput)
        assertTrue("A supplied liveOrigin must not be blank", originText.isNotBlank())
        val expectedOrigin = OriginValidator().requireValid(REQUIRED_ORIGIN)
        assertEquals("Only the fixed completion HTTPS mount is accepted", expectedOrigin, OriginValidator().requireValid(originText))

        val paneId = args.getString("livePaneId")?.trim()
        assertEquals("Physical usability acceptance requires the controlled stock pane", CONTROLLED_PANE_ID, paneId)
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Physical usability acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing completion pairing must be installed", connection?.isPaired == true)
        val paired = requireNotNull(connection)
        assertEquals("The existing pairing must match the exact completion mount", expectedOrigin, paired.origin)

        val repository = app.container.repository
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val address = PaneAddress(scope, CONTROLLED_PANE_ID)
        val actor = repository.readContext(scope, address.paneId)
        assertNotNull("The installed pair must admit the exact stock pane read", actor)
        val captured = requireNotNull(actor)
        val snapshot = runBlocking { repository.snapshot(scope, captured) }
        assertTrue("The controlled stock pane must be readable from the paired host", snapshot is ApiResult.Success)
        assertTrue("The original pane read actor must remain current", repository.isCurrent(captured))
        val data = (snapshot as ApiResult.Success).value
        assertTrue(
            "The exact controlled pane must be present as an existing stock shell",
            (data.shellPanes + data.agents).any { it.paneId == CONTROLLED_PANE_ID && it.host == scope.host && it.session == scope.session && (it.agent == "shell" || it.kind == "shell") },
        )
        assertTrue("Large-font acceptance requires the parent-configured 2x system font scale", app.resources.configuration.fontScale >= 1.8f)
        return Live(app, paired, captured, address)
    }

    private fun selectTheme(
        app: CollieApplication,
        preferences: NativePreferences,
        mode: NativePreferences.ThemeMode,
    ) {
        val buttonId = when (mode) {
            NativePreferences.ThemeMode.SYSTEM -> R.id.theme_system_button
            NativePreferences.ThemeMode.LIGHT -> R.id.theme_light_button
            NativePreferences.ThemeMode.DARK -> R.id.theme_dark_button
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withId(buttonId)).perform(click())
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && preferences.themeMode != mode) SystemClock.sleep(100)
            assertEquals("The native Settings theme control must persist its selected mode", mode, preferences.themeMode)
            scenario.onActivity { activity ->
                assertEquals(buttonId, activity.findViewById<MaterialButtonToggleGroup>(R.id.theme_toggle).checkedButtonId)
            }
        }
    }

    private fun assertSettingsTheme(app: CollieApplication, mode: NativePreferences.ThemeMode) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<TextView>(R.id.settings_header_title)
                assertTrue("Settings title label must remain visible at 2x font scale", title.isShown && title.text.isNotBlank())
                val background = activity.getColor(R.color.collie_background)
                assertTrue(
                    "Settings title must retain readable contrast in $mode mode",
                    ColorUtils.calculateContrast(title.currentTextColor, background) >= 4.5,
                )
                val expectedNight = mode == NativePreferences.ThemeMode.DARK
                val isNight = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                assertEquals("Settings must apply $mode colors", expectedNight, isNight)
            }
        }
    }

    private fun assertPaneChromeAndKeyboard(live: Live, mode: NativePreferences.ThemeMode) {
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            awaitLivePaneInputReady(live, scenario)
            var originalDraft = ""
            scenario.onActivity { activity ->
                val back = activity.findViewById<View>(R.id.back_button)
                val title = activity.findViewById<TextView>(R.id.pane_title)
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val composer = activity.findViewById<EditText>(R.id.reply_input)
                val send = activity.findViewById<View>(R.id.send_button)
                originalDraft = composer.text.toString()
                assertTrue("Pane title label must be visible in $mode mode", title.isShown && title.text.isNotBlank())
                assertTrue("Pane title must receive the parent-configured large system font", title.textSize >= 16f * activity.resources.displayMetrics.density * 1.7f)
                val titleBounds = Rect()
                assertTrue(
                    "Pane title glyphs must remain inside fully visible 2x-font layout bounds",
                    title.lineCount > 0 &&
                        title.lineHeight * title.lineCount + title.paddingTop + title.paddingBottom <= title.height &&
                        title.getGlobalVisibleRect(titleBounds) && titleBounds.height() == title.height,
                )
                assertTrue(
                    "Pane title must retain readable contrast in $mode mode",
                    ColorUtils.calculateContrast(title.currentTextColor, activity.getColor(R.color.collie_background)) >= 4.5,
                )
                assertEquals(activity.getString(R.string.pane_back), back.contentDescription.toString())
                assertEquals(activity.getString(R.string.pane_send), send.contentDescription.toString())
                assertEquals("Live transcript content must remain on the bundled monospace face", ResourcesCompat.getFont(activity, R.font.collie_mono), terminal.typeface)
                assertTrue("Composer text must remain visually monospace in $mode mode", hasMonospaceMetrics(composer))
                assertTrue("Composer hint remains available to operators", composer.hint.isNotBlank())
                assertTrue("The live native composer must be available for keyboard input", composer.isShown && composer.isEnabled)
                composer.requestFocus()
                val inputMethod = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethod.showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
            }
            awaitImeAndComposer(scenario)
            scenario.onActivity { activity ->
                val composer = activity.findViewById<EditText>(R.id.reply_input)
                assertTrue("Usability acceptance must not change or transmit the existing draft", originalDraft == composer.text.toString())
                assertEquals("Transcript remains monospace while the physical IME is visible", ResourcesCompat.getFont(activity, R.font.collie_mono), activity.findViewById<TextView>(R.id.terminal_text).typeface)
                assertTrue("Composer remains visually monospace while the physical IME is visible", hasMonospaceMetrics(composer))
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(composer.windowToken, 0)
            }
        }
    }

    private fun awaitLivePaneInputReady(live: Live, scenario: ActivityScenario<PaneActivity>) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            assertTrue("The original completion pairing must remain installed during pane load", live.app.container.connectionStore.connection.value === live.connection)
            scenario.onActivity { activity ->
                val reply = activity.findViewById<EditText>(R.id.reply_input)
                ready = activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == live.address.paneId &&
                    activity.intent.getStringExtra(PaneActivity.EXTRA_HOST) == live.address.scope.host &&
                    activity.intent.getStringExtra(PaneActivity.EXTRA_SESSION) == live.address.scope.session &&
                    reply.isShown && reply.isEnabled
            }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("The real PaneActivity must finish live loading and enable its existing composer", ready)

        assertCurrentLivePane(live)
    }

    private fun assertCurrentLivePane(live: Live) {
        // PaneViewModel selects the pane subject during native navigation, advancing the
        // navigation generation. Capture the new read actor only after the actual pane is
        // visible; never reuse the pre-navigation actor for this confirmation read.
        val admittedActor = live.app.container.repository.readContext(live.address.scope, live.address.paneId)
        assertNotNull("The current native pane must admit a fresh exact-scope actor", admittedActor)
        val actor = requireNotNull(admittedActor)
        assertEquals("The fresh actor must remain scoped to the controlled pane host/session", live.address.scope, actor.key.scope)
        assertEquals("The fresh actor must be scoped to the controlled pane subject", live.address.paneId, actor.key.subjectIdentity)
        assertTrue("The fresh native pane actor must be current", live.app.container.repository.isCurrent(actor))
        val result = runBlocking { live.app.container.repository.snapshot(live.address.scope, actor) }
        assertTrue("The exact pane must remain readable under its fresh native actor", result is ApiResult.Success)
        assertTrue("The fresh native pane actor must remain current after the read", live.app.container.repository.isCurrent(actor))
        val snapshot = (result as ApiResult.Success).value
        assertTrue(
            "The exact controlled stock pane must still be present after native loading",
            (snapshot.shellPanes + snapshot.agents).any {
                it.paneId == live.address.paneId && it.host == live.address.scope.host &&
                    it.session == live.address.scope.session && (it.agent == "shell" || it.kind == "shell")
            },
        )
        assertTrue("The original completion pairing must remain installed after pane admission", live.app.container.connectionStore.connection.value === live.connection)
    }

    private fun awaitImeAndComposer(scenario: ActivityScenario<PaneActivity>) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var visible = false
        while (!visible && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val composer = activity.findViewById<EditText>(R.id.reply_input)
                val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val insets = ViewCompat.getRootWindowInsets(content)
                val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val location = IntArray(2).also(content::getLocationOnScreen)
                val usableBottom = location[1] + content.height - content.paddingBottom
                val composerRect = Rect()
                val send = activity.findViewById<View>(R.id.send_button)
                val sendRect = Rect()
                val sendLocation = IntArray(2).also(send::getLocationOnScreen)
                val sendCenterX = sendLocation[0] + send.width / 2
                val sendCenterY = sendLocation[1] + send.height / 2
                val decor = activity.window.decorView
                val decorLocation = IntArray(2).also(decor::getLocationOnScreen)
                val safeInsets = insets?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                val sendInsideSafeWindow = safeInsets != null &&
                    sendCenterX >= decorLocation[0] + safeInsets.left &&
                    sendCenterX <= decorLocation[0] + decor.width - safeInsets.right &&
                    sendCenterY >= decorLocation[1] + safeInsets.top &&
                    sendCenterY <= decorLocation[1] + decor.height - safeInsets.bottom
                visible = composer.hasFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && ime > 0 &&
                    content.paddingBottom >= ime &&
                    composer.getGlobalVisibleRect(composerRect) && composerRect.width() == composer.width && composerRect.height() == composer.height && composerRect.bottom <= usableBottom &&
                    send.getGlobalVisibleRect(sendRect) && sendRect.width() == send.width && sendRect.height() == send.height && sendRect.bottom <= usableBottom &&
                    send.width >= activity.resources.displayMetrics.density * 48f && send.height >= activity.resources.displayMetrics.density * 48f &&
                    sendInsideSafeWindow
            }
            if (!visible) SystemClock.sleep(100)
        }
        assertTrue("Physical IME must be visible and leave the focused composer inside the usable window", visible)
    }

    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.address.paneId)
        .putExtra(PaneActivity.EXTRA_HOST, live.address.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, live.address.scope.session)
        .putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_TITLE, "Controlled stock shell")

    private fun hasMonospaceMetrics(view: TextView): Boolean {
        val sample = "iiiiWWWW0123456789"
        val actual = Paint(view.paint).measureText(sample)
        val expected = Paint(view.paint).apply { typeface = Typeface.MONOSPACE }.measureText(sample)
        return abs(actual - expected) < 0.2f
    }

    private fun activateNode(automation: UiAutomation, viewId: String, labelResource: Int) {
        val label = ApplicationProvider.getApplicationContext<Context>().getString(labelResource)
        val expectedPackage = ApplicationProvider.getApplicationContext<Context>().packageName
        val node = awaitNode(automation, viewId)
        assertNotNull("TalkBack must expose $viewId", node)
        requireNotNull(node).let { target ->
            try {
                assertEquals("TalkBack must expose the full native label", label, target.contentDescription)
                assertEquals("TalkBack target must belong to the running app", expectedPackage, target.packageName.toString())
                assertTrue("TalkBack target must be enabled", target.isEnabled)
                assertTrue("TalkBack must focus the native control", target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            } finally {
                target.recycle()
            }
        }
        val focused = awaitFocus(automation)
        assertNotNull("TalkBack must report the native control as focused", focused)
        requireNotNull(focused).let { target ->
            try {
                assertEquals(viewId, target.viewIdResourceName)
                assertEquals(label, target.contentDescription)
                assertTrue(target.isAccessibilityFocused)
                assertTrue("Focused TalkBack target must remain visible", target.isVisibleToUser)
                assertTrue("Focused TalkBack target must remain enabled", target.isEnabled)
                assertEquals("Focused target must remain in the running app", expectedPackage, target.packageName.toString())
                assertTrue("TalkBack must activate the focused native control", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } finally {
                target.recycle()
            }
        }
    }

    private fun assertNodeVisible(message: String, node: AccessibilityNodeInfo?) {
        assertNotNull(message, node)
        requireNotNull(node).let {
            try {
                assertTrue("$message (node is not visible to the user)", it.isVisibleToUser)
            } finally {
                it.recycle()
            }
        }
    }

    private fun awaitNode(automation: UiAutomation, viewId: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
            val node = nodes.firstOrNull { it.isVisibleToUser }
            nodes.filter { it !== node }.forEach { it.recycle() }
            root?.recycle()
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private fun awaitFocus(automation: UiAutomation): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            root?.recycle()
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private companion object {
        const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        const val CONTROLLED_PANE_ID = "w1:p1"
        const val TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
        const val WAIT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
