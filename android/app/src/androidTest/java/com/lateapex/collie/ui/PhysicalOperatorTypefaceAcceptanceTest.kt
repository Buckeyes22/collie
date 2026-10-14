package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OperatorFontRow
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises the paired production font route only when the exact opt-in origin is supplied. */
@RunWith(AndroidJUnit4::class)
class PhysicalOperatorTypefaceAcceptanceTest {
    @Test
    fun actualSettingsSelectionLoadsPersistsAndRestoresTheAdmittedOperatorFont() {
        val args = InstrumentationRegistry.getArguments()
        val suppliedOrigin = args.getString("liveOrigin")
        org.junit.Assume.assumeTrue("liveOrigin is required for physical operator-font acceptance", suppliedOrigin != null)
        val liveOrigin = requireNotNull(suppliedOrigin)
        assertTrue("A supplied liveOrigin must not be blank", liveOrigin.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        val paired = app.container.connectionStore.connection.value
        assertNotNull("Acceptance requires the already-paired production connection", paired)
        val connection = requireNotNull(paired)
        assertTrue("The installed pair must be paired", connection.isPaired)
        val expectedOrigin = OriginValidator().requireValid(REQUIRED_ORIGIN)
        assertEquals("The supplied origin must be the fixed completion mount", expectedOrigin, OriginValidator().requireValid(liveOrigin))
        assertEquals("The installed pairing must match the supplied exact HTTPS mount", expectedOrigin, connection.origin)

        val repository = app.container.repository
        val scope = repository.selectedScope.value
        val actor = repository.readContext(scope)
        assertNotNull("The original paired profile must admit a read actor", actor)
        val originalActor = requireNotNull(actor)
        assertTrue("Font acceptance keeps its original read actor current", repository.isCurrent(originalActor))
        val config = runBlocking { repository.config(scope, originalActor) }
        assertTrue("The real server config must be readable", config is ApiResult.Success)
        assertTrue("The captured read actor remains current after config", repository.isCurrent(originalActor))
        val configValue = (config as ApiResult.Success).value
        val row = requireNotNull(configValue.operatorFonts.firstOrNull {
            it.valid() && it.family == TARGET_FAMILY && it.basename == TARGET_BASENAME
        }) { "The paired server must advertise the expected Acceptance Space font row" }
        assertEquals("400", row.weight)
        val response = runBlocking { repository.operatorFont(row, originalActor) }
        assertTrue("The actual mounted WOFF2 font must be readable", response is ApiResult.Success)
        assertTrue("The captured read actor remains current after font fetch", repository.isCurrent(originalActor))
        val expectedFace = OperatorWoff2Codec.build(app, row, (response as ApiResult.Success).value)

        val preferences = NativePreferences(app)
        val originalUiChoice = preferences.uiTypeface
        val originalOperatorChoice = preferences.operatorTypefaceSelection
        val originalOwner = originalActor.custodyOwner.storageIdentity
        assertTrue(
            "A pre-existing operator selection must belong to this owner and remain advertised so the UI can restore it",
            originalOperatorChoice == null || (originalOperatorChoice.ownerFingerprint == originalOwner &&
                (configValue.operatorFonts.any { it.family == originalOperatorChoice.family && it.basename == originalOperatorChoice.basename })),
        )
        var settings: ActivityScenario<SettingsActivity>? = null
        var pane: ActivityScenario<PaneActivity>? = null
        try {
            val firstSettings = ActivityScenario.launch(SettingsActivity::class.java)
            settings = firstSettings
            awaitInitialOperatorChoice(firstSettings, app, originalActor, connection)
            // Establish a visibly different shipped baseline so the downloaded Space Grotesk
            // face cannot pass merely because the app was already using the same family.
            chooseUsingSettings(firstSettings, shippedLabel(app, NativePreferences.UiTypeface.ALDRICH))
            awaitRestored(firstSettings, preferences, originalOwner, null, NativePreferences.UiTypeface.ALDRICH)
            var aldrichDiffersFromTarget = false
            firstSettings.onActivity { activity ->
                aldrichDiffersFromTarget = !sameRenderedFace(
                    activity.findViewById(R.id.settings_ui_typeface_value),
                    expectedFace,
                )
            }
            assertTrue("The shipped Aldrich baseline must differ from the target Space Grotesk face", aldrichDiffersFromTarget)
            try { chooseUsingSettings(firstSettings, TARGET_FAMILY) }
            catch (failure: AssertionError) {
                throw AssertionError("Target disappeared after the shipped-font control; ${fontAdmissionDiagnostic(firstSettings, app, originalActor, connection)}", failure)
            }
            awaitPublishedFace(firstSettings, preferences, originalActor, row, expectedFace)
            assertTrue("The captured read actor remains current after UI selection", repository.isCurrent(originalActor))
            firstSettings.close()
            settings = null

            // A second real Settings instance proves the persisted choice and visible face survive
            // replacing Settings in the same process. Process-death/font-reload restoration is a
            // separate acceptance path because the process cache remains alive here.
            val reopenedSettings = ActivityScenario.launch(SettingsActivity::class.java)
            settings = reopenedSettings
            awaitPublishedFace(reopenedSettings, preferences, originalActor, row, expectedFace)
            assertTrue("The captured read actor remains current after restore", repository.isCurrent(originalActor))

            val optionalPaneId = args.getString("livePaneId")?.trim()?.takeIf(String::isNotEmpty)
            if (optionalPaneId != null) {
                reopenedSettings.close()
                settings = null
                val address = PaneAddress(
                    Scope(
                        host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
                        session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
                    ),
                    optionalPaneId,
                )
                val livePane = ActivityScenario.launch<PaneActivity>(Intent(app, PaneActivity::class.java).apply {
                    putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
                    putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
                    putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
                })
                pane = livePane
                awaitPaneTypography(livePane, expectedFace)
                livePane.close()
                pane = null
            }
        } finally {
            pane?.close()
            settings?.close()
            // Restore the original choice through the same native picker used by an operator.
            val restoringSettings = ActivityScenario.launch(SettingsActivity::class.java)
            settings = restoringSettings
            try {
                val restoreLabel = originalOperatorChoice?.family ?: shippedLabel(app, originalUiChoice)
                chooseUsingSettings(restoringSettings, restoreLabel)
                awaitRestored(restoringSettings, preferences, originalOwner, originalOperatorChoice, originalUiChoice)
            } finally {
                restoringSettings.close()
            }
            assertSame("Acceptance must preserve the original paired connection", connection, app.container.connectionStore.connection.value)
        }
    }

    /** Observe the actual admitted picker before any preference write; never select the operator row here. */
    private fun awaitInitialOperatorChoice(
        scenario: ActivityScenario<SettingsActivity>,
        app: CollieApplication,
        actor: ScopedReadContext,
        connection: com.lateapex.collie.domain.Connection,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var offered = false
        while (!offered && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { it.findViewById<View>(R.id.settings_ui_typeface_value).performClick() }
            try {
                onView(withText(containsString(TARGET_FAMILY))).inRoot(isDialog()).check(matches(isDisplayed()))
                offered = true
            } catch (_: NoMatchingViewException) {
                // The menu can load while its initial config read is in flight; reopen normally.
            } finally { Espresso.pressBack() }
            if (!offered) SystemClock.sleep(150)
        }
        assertTrue("Initial native picker must admit the real target before ALDRICH selection; ${fontAdmissionDiagnostic(scenario, app, actor, connection)}", offered)
    }

    private fun fontAdmissionDiagnostic(
        scenario: ActivityScenario<SettingsActivity>,
        app: CollieApplication,
        actor: ScopedReadContext,
        connection: com.lateapex.collie.domain.Connection,
    ): String {
        var label = "other"
        scenario.onActivity { activity ->
            val shown = activity.findViewById<TextView>(R.id.settings_ui_typeface_value).text.toString()
            val publicLabels = NativePreferences.UiTypeface.entries.map { shippedLabel(activity, it) } + TARGET_FAMILY
            label = shown.takeIf { it in publicLabels } ?: "other"
        }
        val repository = app.container.repository
        return "actorCurrent=${repository.isCurrent(actor)}, readSource=${repository.readSource.value.javaClass.simpleName}, " +
            "sameConnection=${app.container.connectionStore.connection.value === connection}, statusLabel=$label"
    }

    private fun chooseUsingSettings(scenario: ActivityScenario<SettingsActivity>, label: String) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var chosen = false
        while (!chosen && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.settings_ui_typeface_value).performClick()
            }
            try {
                Espresso.onView(withText(containsString(label))).inRoot(isDialog()).check(matches(isDisplayed()))
                Espresso.onView(withText(containsString(label))).inRoot(isDialog()).perform(click())
                chosen = true
            } catch (_: NoMatchingViewException) {
                Espresso.pressBack()
                SystemClock.sleep(150)
            }
        }
        assertTrue("The actual Settings font picker must offer '$label'", chosen)
    }

    private fun awaitPublishedFace(
        scenario: ActivityScenario<SettingsActivity>,
        preferences: NativePreferences,
        actor: ScopedReadContext,
        row: OperatorFontRow,
        expectedFace: Typeface,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val selected = preferences.operatorTypefaceSelection
                val chrome = activity.findViewById<TextView>(R.id.settings_ui_typeface_value)
                ready = selected?.family == row.family && selected?.basename == row.basename &&
                    selected?.ownerFingerprint == actor.custodyOwner.storageIdentity &&
                    chrome.isShown && chrome.text.toString() == row.family && sameRenderedFace(chrome, expectedFace)
            }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("The actual font row must publish to persisted owner state and visible Settings chrome", ready)
    }

    private fun awaitRestored(
        scenario: ActivityScenario<SettingsActivity>,
        preferences: NativePreferences,
        owner: String,
        originalOperatorChoice: NativePreferences.OperatorTypefaceSelection?,
        originalUiChoice: NativePreferences.UiTypeface,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var restored = false
        while (!restored && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val selected = preferences.operatorTypefaceSelection
                val expectedLabel = originalOperatorChoice?.family ?: shippedLabel(activity, originalUiChoice)
                restored = if (originalOperatorChoice == null) {
                    selected == null && preferences.uiTypeface == originalUiChoice &&
                        activity.findViewById<TextView>(R.id.settings_ui_typeface_value).text.toString() == expectedLabel
                } else {
                    selected == originalOperatorChoice && selected?.ownerFingerprint == owner &&
                        activity.findViewById<TextView>(R.id.settings_ui_typeface_value).text.toString() == expectedLabel
                }
            }
            if (!restored) SystemClock.sleep(100)
        }
        assertTrue("The original typeface choice must be restored through Settings", restored)
    }

    private fun awaitPaneTypography(scenario: ActivityScenario<PaneActivity>, operatorFace: Typeface) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var checked = false
        while (!checked && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val reply = activity.findViewById<TextView>(R.id.reply_input)
                if (terminal != null && reply != null) {
                    assertEquals("Terminal mirror remains on its shipped monospace face", androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.collie_mono), terminal.typeface)
                    val monospaced = hasMonospacedAscii(reply)
                    val referenceMatches = sameRenderedFace(reply, Typeface.MONOSPACE)
                    val fixedGlyphMetrics = replyGlyphMetrics(reply)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                        putBoolean("replyEqualGlyphAdvances", monospaced)
                        putBoolean("replyDefaultMonoWidthMatches", referenceMatches)
                        putString("stream", "Fixed public glyph metrics only: $fixedGlyphMetrics\n")
                    })
                    assertTrue("Reply input must have equal ASCII glyph advances; $fixedGlyphMetrics", monospaced)
                    assertTrue("Reply input default-monospace sample widths must match; $fixedGlyphMetrics", referenceMatches)
                    assertTrue("Operator font must not replace the reply input face", !sameRenderedFace(reply, operatorFace))
                    val tagged = descendants(activity.findViewById(android.R.id.content))
                        .filterIsInstance<TextView>().filter { it.getTag(R.id.collie_content_typeface) == true && it.text.isNotEmpty() }
                    assertTrue("Operator font must not leak into agent/transcript content", tagged.none { sameRenderedFace(it, operatorFace) })
                    checked = true
                }
            }
            if (!checked) SystemClock.sleep(100)
        }
        assertTrue("The optional real pane must render before its protected faces are checked", checked)
    }

    /** Measures public constant glyphs only; never reads or logs the user's composer text. */
    private fun replyGlyphMetrics(view: TextView): String {
        val paint = Paint(view.paint)
        val advances = listOf("i", "W", "M", "0", " ").map { paint.measureText(it) }
        val sample = "MWim 0123456789 Acceptance Space"
        val actual = paint.measureText(sample)
        val expected = Paint(paint).apply { typeface = Typeface.MONOSPACE }.measureText(sample)
        return "advances[i,W,M,0,space]=$advances, actualWidth=$actual, expectedWidth=$expected, " +
            "style=${view.typeface?.style ?: -1}, letterSpacing=${view.letterSpacing}, textScaleX=${view.textScaleX}"
    }

    private fun hasMonospacedAscii(view: TextView): Boolean {
        val paint = Paint(view.paint)
        val advances = listOf("i", "W", "M", "0", " ").map { paint.measureText(it) }
        return advances.max() - advances.min() < 0.2f
    }

    private fun sameRenderedFace(view: TextView, typeface: Typeface): Boolean {
        val sample = "MWim 0123456789 Acceptance Space"
        val actual = Paint(view.paint).measureText(sample)
        val expected = Paint(view.paint).apply { this.typeface = typeface }.measureText(sample)
        return abs(actual - expected) < 0.2f
    }

    private fun shippedLabel(context: Context, face: NativePreferences.UiTypeface): String = when (face) {
        NativePreferences.UiTypeface.SYSTEM -> context.getString(R.string.settings_ui_typeface_system)
        NativePreferences.UiTypeface.GROTESK -> context.getString(R.string.settings_ui_typeface_grotesk)
        NativePreferences.UiTypeface.ALDRICH -> context.getString(R.string.settings_ui_typeface_aldrich)
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    companion object {
        private const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        private const val TARGET_FAMILY = "Acceptance Space"
        private const val TARGET_BASENAME = "acceptance-space.woff2"
        private const val WAIT_MS = 45_000L
    }
}
