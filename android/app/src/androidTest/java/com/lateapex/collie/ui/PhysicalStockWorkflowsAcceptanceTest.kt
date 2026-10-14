package com.lateapex.collie.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.RelativeFilePath
import com.lateapex.collie.ui.terminal.TerminalPlainText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in real HTTPS/native UI acceptance. No fixture API, reflection, or direct mutation API. */
@RunWith(AndroidJUnit4::class)
class PhysicalStockWorkflowsAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private data class Live(val app: CollieApplication, val repository: CollieRepository,
        val address: PaneAddress, val actor: ScopedReadContext, val root: String)

    /**
     * Supply liveOrigin, livePaneId, liveRepoPath; optional liveHost/liveSession stay exact.
     * The shell must already be in a disposable /home/chris/collie-completion-YYYYMMDD Git root.
     * Commit acceptance.txt containing BASE, then append CHANGE; list a valid acceptance.png.
     */
    private fun withLive(test: (Live) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveOrigin")
        assumeTrue("liveOrigin argument was not supplied; this physical test is opt-in", originText != null)
        assertFalse("Supplied liveOrigin must not be blank", originText.isNullOrBlank())
        fun required(name: String): String {
            val value = args.getString(name)?.trim()
            assertTrue("Supplied liveOrigin requires an explicit $name", !value.isNullOrEmpty())
            return requireNotNull(value)
        }
        val pane = required("livePaneId")
        assertTrue("livePaneId must be a bounded explicit pane identifier", pane.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val root = required("liveRepoPath")
        assertTrue("Only the disposable physical acceptance Git root is allowed", root.matches(Regex("/home/chris/collie-completion-[0-9]{8}")))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val repository = app.container.repository
        val connection = app.container.connectionStore.connection.value
        assertTrue("Supplied liveOrigin requires its existing installed pairing", connection?.isPaired == true)
        assertEquals("Installed pairing must match the exact supplied origin and mount", OriginValidator().requireValid(requireNotNull(originText)), connection?.origin)
        val address = PaneAddress(Scope(args.getString("liveHost")?.takeIf(String::isNotBlank),
            args.getString("liveSession")?.takeIf(String::isNotBlank)), pane)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(address.scope, address.paneId) }
            val actor = repository.readContext(address.scope, address.paneId)
            assertNotNull("Existing pairing must admit the exact live read actor", actor)
            val captured = requireNotNull(actor)
            val result = runBlocking { repository.snapshot(address.scope, captured) }
            assertTrue("Live snapshot must succeed under its original actor", result is ApiResult.Success)
            assertTrue("Live snapshot actor must remain current", repository.isCurrent(captured))
            val snapshot = (result as ApiResult.Success).value
            val target = (snapshot.agents + snapshot.shellPanes).singleOrNull {
                it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
            }
            assertNotNull("Explicit disposable pane must be present in the live snapshot", target)
            assertEquals("Disposable shell must already be in the exact fixture Git root", root, target?.cwd)
            assertTrue("Only a shell pane may receive the harmless printf acceptance command", target?.agent == "shell" || target?.kind == "shell")
            test(Live(app, repository, address, captured, root))
        } finally {
            instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) }
            assertTrue("Acceptance must preserve the installed pairing", app.container.connectionStore.connection.value === connection)
        }
    }

    @Test fun liveShellNativeReplyPreservesUnicodeOutputAndFindMatches() = withLive { live ->
        val marker = "COLLIE_PHYSICAL_${UUID.randomUUID().toString().take(8)} 漢字 e\u0301 👨‍💻"
        val command = "printf '\\n%s\\n' '$marker'; pwd"
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "live native reply admission") { activity ->
                activity.findViewById<View>(R.id.send_button).isEnabled &&
                    activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                    activity.findViewById<TextView>(R.id.terminal_text).let { it.isShown && it.text.isNotEmpty() }
            }
            onView(withId(R.id.reply_input)).perform(replaceText(command))
            onView(withId(R.id.send_button)).perform(click())
            await(scenario, "standalone Unicode stdout and fixture working directory") { activity ->
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val lines = mirror.text.toString().lineSequence().map(String::trim).toList()
                mirror.isShown && marker in lines && live.root in lines
            }
            assertTrue("UI writes and verification must retain the original paired actor", live.repository.isCurrent(live.actor))
            val actual = runBlocking { live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor) }
            assertTrue("Original-actor production pane read must confirm stdout", actual is ApiResult.Success)
            assertTrue("Native Unicode stdout must also exist in the actual stock pane", TerminalPlainText.strip((actual as ApiResult.Success).value.pane.text).lineSequence().map(String::trim).any { it == marker })
            assertTrue("Original actor must still own the real pane after verification", live.repository.isCurrent(live.actor))
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(live.app.getString(R.string.pane_action_find))).perform(click())
            onView(withId(R.id.find_query)).perform(replaceText(marker))
            await(scenario, "native output Find result") { activity ->
                val count = activity.findViewById<TextView>(R.id.find_count).text.toString()
                count.matches(Regex("[1-9][0-9]*/[1-9][0-9]*"))
            }
            onView(withId(R.id.find_close)).perform(click())
        }
    }

    @Test fun liveFilesOpensListedUnicodeSourceAndLoadsTheActualRaster() = withLive { live ->
        val target = FilesTarget.Pane(live.address.paneId)
        val response = runBlocking { live.repository.files(target, live.address.scope, dir = requireNotNull(RelativeFilePath.parse("", allowRoot = true)), expectedContext = live.actor) }
        assertTrue("Live Files listing must succeed under the original actor", response is ApiResult.Success)
        val listing = (response as ApiResult.Success).value as? FilesAnswer.Listing
        assertNotNull("The live root must be a directory listing", listing)
        assertEquals(live.root, listing?.root)
        assertTrue("Fixture text and PNG must both be listed", listing!!.entries.map { it.name }.containsAll(listOf(TEXT, IMAGE)))
        assertTrue(live.repository.isCurrent(live.actor))
        var filesActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
                await(pane, "native Files discovery admission") { it.findViewById<View>(R.id.files_button).isEnabled }
                onView(withId(R.id.files_button)).perform(click())
                filesActivity = awaitFiles("native listed fixture source") { fileRow(it, TEXT) != null }
                instrumentation.runOnMainSync { assertTrue("Listed text row must invoke its native handler", fileRow(filesActivity!!, TEXT)!!.performClick()) }
                awaitFiles("byte-faithful Unicode Files source") { activity ->
                    visibleText(activity.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it }
                }
                instrumentation.runOnMainSync {
                    assertEquals(TEXT, filesActivity!!.findViewById<TextView>(R.id.files_title).text.toString())
                    assertTouchableInsideSystemInsets(filesActivity!!, R.id.files_back)
                }
                onView(withId(R.id.files_back)).perform(click())
                awaitFiles("returned native listing") { activity -> fileRow(activity, IMAGE) != null }
                instrumentation.runOnMainSync { assertTrue("Listed raster row must invoke its native handler", fileRow(filesActivity!!, IMAGE)!!.performClick()) }
                awaitFiles("actual authenticated PNG bitmap display") { activity ->
                    descendants(activity.findViewById(R.id.files_source_content)).filterIsInstance<ImageView>()
                        .any { it.isShown && it.drawable != null && it.drawable.intrinsicWidth > 0 && it.drawable.intrinsicHeight > 0 }
                }
                instrumentation.runOnMainSync { assertEquals(IMAGE, filesActivity!!.findViewById<TextView>(R.id.files_title).text.toString()) }
                assertTrue("Raster must remain owned by the exact paired read actor", live.repository.isCurrent(live.actor))
            }
        } finally { instrumentation.runOnMainSync { filesActivity?.finish() } }
    }

    @Test fun liveChangesOpensListedHarmlessDiffAndCurrentSourceHandoff() = withLive { live ->
        val response = runBlocking { live.repository.changes(ChangesTarget.Pane(live.address.paneId), live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Live Changes listing must succeed under the original actor", response is ApiResult.Success)
        val answer = (response as ApiResult.Success).value
        val available = answer.state as? ChangesListState.Available
        assertNotNull("Disposable Git fixture must report actual repository changes", available)
        assertEquals("Changes must retain the exact disposable repository root", live.root, available?.root)
        assertTrue("Harmless modified source must be Git-listed", available!!.repos.any { repo -> repo.files.any { it.path == TEXT } })
        assertTrue(live.repository.isCurrent(live.actor))
        var sourceActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<ChangesActivity>(ChangesActivity.intentForPane(live.app, live.address.paneId, live.address.scope, "Disposable acceptance Git")).use { scenario ->
                await(scenario, "native listed harmless Git change") { activity -> changeButton(activity) != null }
                scenario.onActivity { assertTrue("Git-listed file must invoke the native diff handler", changeButton(it)!!.performClick()) }
                await(scenario, "actual harmless Unicode diff addition") { activity ->
                    visibleText(activity.findViewById(R.id.changes_content)).any { "+$CHANGE" in it }
                }
                onView(withText(live.app.getString(R.string.changes_open_current_source))).perform(click())
                val deadline = SystemClock.uptimeMillis() + TIMEOUT
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        sourceActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<FilesActivity>().singleOrNull()
                        ready = sourceActivity?.let { visibleText(it.findViewById(R.id.files_source_content)).any { text -> BASE in text && CHANGE in text } } == true
                    }
                    if (ready) break
                    SystemClock.sleep(100)
                }
                assertNotNull("Current-source handoff must open the real native Files Activity", sourceActivity)
                instrumentation.runOnMainSync {
                    assertTrue("Current-source handoff must render the listed fixture bytes", visibleText(sourceActivity!!.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it })
                }
            }
        } finally { instrumentation.runOnMainSync { sourceActivity?.finish() } }
    }

    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_HOST, live.address.scope.host).putExtra(PaneActivity.EXTRA_SESSION, live.address.scope.session)
        .putExtra(PaneActivity.EXTRA_TITLE, "Disposable stock acceptance shell")

    private fun awaitFiles(checkpoint: String, ready: (FilesActivity) -> Boolean): FilesActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched: FilesActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FilesActivity>().singleOrNull()
                if (activity != null && ready(activity)) matched = activity
            }
            matched?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical native checkpoint timed out: $checkpoint")
    }

    private fun <T : Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        fail("Physical native checkpoint timed out: $checkpoint")
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }
    private fun visibleText(root: View) = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun fileRow(activity: FilesActivity, name: String): TextView? = descendants(activity.findViewById(R.id.files_list))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.contentDescription?.toString() == activity.getString(R.string.files_open_file, name) }
    private fun changeButton(activity: ChangesActivity): TextView? = descendants(activity.findViewById(R.id.changes_content))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.text.toString().contains("  $TEXT  ") }

    /** A handler invocation cannot prove the physical tap survives edge-to-edge system bars. */
    private fun assertTouchableInsideSystemInsets(activity: Activity, viewId: Int) {
        val button = activity.findViewById<View>(viewId)
        assertTrue("Native Back must be shown, enabled, and clickable", button.isShown && button.isEnabled && button.isClickable)
        val decor = activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)) { "Physical window must have actual system insets" }
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val windowPosition = IntArray(2).also(decor::getLocationOnScreen)
        val safe = Rect(windowPosition[0] + insets.left, windowPosition[1] + insets.top,
            windowPosition[0] + decor.width - insets.right, windowPosition[1] + decor.height - insets.bottom)
        val position = IntArray(2).also(button::getLocationOnScreen)
        val centerX = position[0] + button.width / 2
        val centerY = position[1] + button.height / 2
        val visible = Rect()
        assertTrue("Native Back tap must be visible in the physical window", button.getGlobalVisibleRect(visible) && visible.contains(centerX, centerY))
        assertTrue("Native Back tap must be inside actual system-bar/cutout insets", safe.contains(centerX, centerY))
    }

    private companion object {
        const val TIMEOUT = 30_000L
        const val TEXT = "acceptance.txt"
        const val IMAGE = "acceptance.png"
        const val BASE = "COLLIE_FIXTURE_BASE 漢字 e\u0301 👨‍💻"
        const val CHANGE = "COLLIE_FIXTURE_CHANGE λ"
    }
}
