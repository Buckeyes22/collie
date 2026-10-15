package com.lateapex.collie.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
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
import com.lateapex.collie.network.ChangeCommitHistoryState
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.RelativeFilePath
import com.lateapex.collie.ui.terminal.TerminalPlainText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import org.hamcrest.Matchers.any
import java.util.UUID

/** Opt-in real HTTPS/native UI acceptance. No fixture API, reflection, or direct mutation API. */
@RunWith(AndroidJUnit4::class)
class PhysicalStockWorkflowsAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private data class Live(val app: CollieApplication, val repository: CollieRepository,
        val address: PaneAddress, val actor: ScopedReadContext, val root: String)

    /**
     * Supply liveOrigin, livePaneId, liveRepoPath; optional liveHost/liveSession stay exact.
     * The shell must already be in the explicitly supplied disposable dated Git root.
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
        assertTrue("Only the disposable physical acceptance Git root is allowed", root.matches(PhysicalFixturePaths.datedRootPattern))
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

    @Test fun liveMirrorMasksHarmlessSyntheticPasswordWithoutChangingItsOwner() = withLive { live ->
        val committed = requireNotNull(live.app.container.connectionStore.captureCommitted())
        assertEquals("Masking uses the original admitted committed owner", committed, live.actor.committedSnapshot)
        assertNotNull("Masking requires the actual paired incarnation", committed.incarnationId)
        val journal = live.app.container.launchIntentStore.all()
        val marker = "COLLIE_MASK_${UUID.randomUUID().toString().take(8)}"
        val dummy = "COMPLETION_DUMMY_${UUID.randomUUID().toString().replace("-", "")}"
        val command = "printf '%s\\n' '$marker password=$dummy'"
        val maskedOutput = "$marker password=${"•".repeat(dummy.length)}"
        val target = ChangesTarget.Pane(live.address.paneId)
        val before = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("The owned fixture Git state must be readable", before is ApiResult.Success)
        fun owner() {
            assertTrue("The original exact shell actor must remain current", live.repository.isCurrent(live.actor))
            assertEquals("The original pairing incarnation must remain committed", committed,
                live.app.container.connectionStore.captureCommitted())
        }
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "actual owned shell mirror and native reply admission") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.panes.singleOrNull { it.paneId == live.address.paneId && it.host == live.address.scope.host &&
                    it.session == live.address.scope.session }?.agent == "shell" && state.paneReadContext?.key == live.actor.key &&
                    state.savedCopy == null && activity.findViewById<View>(R.id.send_button).isEnabled &&
                    activity.findViewById<TextView>(R.id.terminal_text).isShown
            }
            owner()
            onView(withId(R.id.reply_input)).perform(replaceText(command))
            owner()
            onView(withId(R.id.send_button)).perform(click())
            await(scenario, "real synthetic password is masked in the native mirror") { activity ->
                owner()
                val text = activity.findViewById<TextView>(R.id.terminal_text).text.toString()
                val normalized = text.replace("\r", "").replace("\n", "")
                maskedOutput in normalized && dummy !in normalized
            }
            owner()
            val response = runBlocking {
                live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
            }
            assertTrue("The real original-actor HTTPS response must succeed", response is ApiResult.Success)
            val text = (response as ApiResult.Success).value.pane.text
            val normalized = text.replace("\r", "").replace("\n", "")
            assertTrue("The actual server response preserves the marker and masks the complete dummy value",
                maskedOutput in normalized && dummy !in normalized)
        }
        owner()
        val after = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Masking acceptance must leave the owned Git state readable", after is ApiResult.Success)
        assertEquals("One harmless printf and masked reads must not alter repository contents",
            (before as ApiResult.Success).value.state, (after as ApiResult.Success).value.state)
        assertEquals("Masking must not add or change a launch intent", journal, live.app.container.launchIntentStore.all())
    }

    @Test fun liveChangesLastCommitReadsOwnedHeadAndItsCommittedFileDiff() = withLive { live ->
        val target = ChangesTarget.Pane(live.address.paneId)
        val lookup = ChangesLookup()
        val committedHeadSource = HEAD_SOURCE
        val listed = runBlocking { live.repository.changes(target, live.address.scope, lookup, live.actor) }
        assertTrue("Owned fixture Changes listing must succeed under its original actor", listed is ApiResult.Success)
        val before = (listed as ApiResult.Success).value
        val available = before.state as? ChangesListState.Available
        assertNotNull("Owned fixture must expose its actual Git repository", available)
        assertEquals("Last commit acceptance is scoped to the exact disposable Git root", live.root, available?.root)
        val repo = available!!.repos.singleOrNull { it.relPath == "." }
        assertNotNull("The fixture root must be the listed repository", repo)
        assertTrue("The committed fixture source must now be listed as an uncommitted modification",
            repo!!.files.any { it.path == TEXT })

        val historyResult = runBlocking {
            live.repository.changeCommitHistory(target, live.address.scope, lookup, ".", head = null,
                offset = 0, limit = 1, expectedContext = live.actor)
        }
        assertTrue("Read-only Git history must return the actual owned repository HEAD", historyResult is ApiResult.Success)
        val history = ((historyResult as ApiResult.Success).value.state as? ChangeCommitHistoryState.Available)?.page
        assertNotNull("The disposable fixture must have its committed baseline", history)
        assertEquals("The server's HEAD must equal its newest returned commit", history!!.headHash, history.commits.single().hash)
        val head = history.commits.single()

        var changesActivity: ChangesActivity? = null
        try {
            ActivityScenario.launch<ChangesActivity>(ChangesActivity.intentForPane(
                live.app, live.address.paneId, live.address.scope, "Disposable acceptance Git",
            )).use { scenario ->
                changesActivity = awaitChanges("native Changes repository and Last commit control") {
                    changeAction(it, live.app.getString(R.string.changes_last_commit)) != null
                }
                instrumentation.runOnMainSync {
                    val button = changeAction(requireNotNull(changesActivity), live.app.getString(R.string.changes_last_commit))
                    assertNotNull("Last commit must be reachable through the native Changes screen", button)
                    assertTrue(requireNotNull(button).performClick())
                }
                awaitChanges("native Last commit detail from the exact Git HEAD") { activity ->
                    visibleText(activity.findViewById(R.id.changes_content)).any {
                        head.subject in it && head.shortHash in it
                    }
                }
                awaitChanges("native HEAD detail lists its exact committed fixture source") { activity ->
                    changeButton(activity, committedHeadSource) != null
                }
                instrumentation.runOnMainSync {
                    val file = changeButton(requireNotNull(changesActivity), committedHeadSource)
                    assertNotNull("The actual HEAD detail must list its committed fixture source", file)
                    assertTrue(requireNotNull(file).performClick())
                }
                awaitChanges("native read-only diff for the exact actual HEAD source") { activity ->
                    val body = visibleText(activity.findViewById(R.id.changes_content)).joinToString("\n")
                    "+printf '%s\\n' 'COLLIE_NATIVE_LAUNCH_ACCEPTANCE'" in body &&
                        "+exec bash --noprofile --norc" in body && "+$CHANGE" !in body
                }
            }
        } finally { instrumentation.runOnMainSync { changesActivity?.finish() } }

        val after = runBlocking { live.repository.changes(target, live.address.scope, lookup, live.actor) }
        assertTrue("Read-only Last commit inspection must retain its original actor", live.repository.isCurrent(live.actor))
        assertTrue("Read-only Last commit inspection must leave Changes readable", after is ApiResult.Success)
        assertEquals("Opening HEAD and its committed diff must not alter the repository's live Changes state",
            before.state, (after as ApiResult.Success).value.state)
    }

    @Test fun liveMirrorCopiesSelectedTextAndOpensOnlyConfirmedFileReadOnly() = withLive { live ->
        // One alphanumeric word lets Android's native long-press select the exact full marker.
        val marker = "COLLIECOPY${UUID.randomUUID().toString().take(8)}"
        val printedPath = "${live.root}/$TEXT"
        val command = "printf '%s\\n' '$marker' 'Open $printedPath'; pwd"
        val target = ChangesTarget.Pane(live.address.paneId)
        val beforeChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Repository read before mirror interaction must succeed", beforeChanges is ApiResult.Success)
        var readOnlyBaseline: PaneReadResponse? = null

        var filesActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
                await(scenario, "native live mirror and reply admission") { activity ->
                    activity.findViewById<View>(R.id.send_button).isEnabled &&
                        activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                        activity.findViewById<TextView>(R.id.terminal_text).isShown
                }
                onView(withId(R.id.reply_input)).perform(replaceText(command))
                onView(withId(R.id.send_button)).perform(click())
                await(scenario, "actual mirror output and confirmed printed source link") { activity ->
                    val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                    mirror.text.toString().contains(marker) && mirror.text.toString().contains(printedPath) &&
                        clickableSpan(mirror, printedPath) != null
                }
                val beforeReadOnlyActions = runBlocking {
                    live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
                }
                assertTrue("The actual live output must be captured before exercising mirror read actions",
                    beforeReadOnlyActions is ApiResult.Success)
                readOnlyBaseline = (beforeReadOnlyActions as ApiResult.Success).value.pane

                onView(withId(R.id.terminal_text)).perform(longPressText(marker))
                scenario.onActivity { activity ->
                    val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                    val start = minOf(mirror.selectionStart, mirror.selectionEnd)
                    val end = maxOf(mirror.selectionStart, mirror.selectionEnd)
                    assertTrue("Native long-press selects the exact live output marker", start >= 0 && end > start)
                    assertEquals(marker, mirror.text.subSequence(start, end).toString())
                    assertTrue("Native Copy retains the exact original live pane actor", live.repository.isCurrent(live.actor))
                }
                copyExactSelectedMirrorThroughVisibleToolbar(scenario, live, marker)
                val clipboard = live.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertTrue("Native selectable mirror text must copy the selected live output",
                    clipboard.primaryClip?.getItemAt(0)?.coerceToText(live.app)?.toString()?.contains(marker) == true)

                // Close the existing IME without refocusing the composer; wait for native Copy's
                // selection dismissal and the original mirror layout before the actual link tap.
                onView(withId(R.id.terminal_text)).perform(closeSoftKeyboard())
                awaitVisibleConfirmedPrintedPath(scenario, live, printedPath)
                onView(withId(R.id.terminal_text)).perform(tapPrintedPath(printedPath))
                filesActivity = awaitFiles("confirmed printed path opens the native Files reader") { activity ->
                    activity.findViewById<TextView>(R.id.files_title).text.toString() == TEXT &&
                        visibleText(activity.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it }
                }
                instrumentation.runOnMainSync {
                    val content = requireNotNull(filesActivity).findViewById<View>(R.id.files_source_content)
                    assertTrue("Printed-path handoff must use the read-only Files presentation",
                        descendants(content).none { it is EditText })
                }
            }
        } finally { instrumentation.runOnMainSync { filesActivity?.finish() } }

        val pane = runBlocking {
            live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
        }
        assertTrue("Copy and confirmed-file navigation must retain the exact live pane actor", live.repository.isCurrent(live.actor))
        assertTrue("Copy and confirmed-file navigation must leave the actual shell readable", pane is ApiResult.Success)
        assertEquals("Copy and Files navigation must leave the real terminal output and revision unchanged",
            readOnlyBaseline,
            (pane as ApiResult.Success).value.pane)
        val afterChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Mirror and Files reads must leave the repository readable", afterChanges is ApiResult.Success)
        assertEquals("Mirror copying and read-only printed-path navigation must not change Git state",
            (beforeChanges as ApiResult.Success).value.state, (afterChanges as ApiResult.Success).value.state)
        val mirror = TerminalPlainText.strip((pane as ApiResult.Success).value.pane.text)
        assertTrue("The exact native shell's actual output must retain the copy marker", marker in mirror)
        assertTrue("The exact native shell's actual output must retain the printed file reference", printedPath in mirror)
    }

    /** Separate opt-in proof of the retained URLSpan path; confirmed Files links use another path. */
    @Test fun liveMirrorOpensActualHttpsUrlThroughTheNormalExternalBrowser() {
        val args = InstrumentationRegistry.getArguments()
        val optedIn = args.getString("liveExternalUrl")
        assumeTrue("Explicit liveExternalUrl=true is required for the normal external-browser handoff", optedIn != null)
        assertEquals("External URL acceptance opt-in must be exactly true", "true", optedIn)
        val browserPackage = requireNotNull(args.getString("liveBrowserPackage")) { "Supply the actual existing default browser package" }
        assertTrue("Pin one bounded existing browser package", browserPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
        withLive { live ->
            val url = "https://example.com/"
            val marker = "COLLIEURL${UUID.randomUUID().toString().take(8)}"
            val connection = requireNotNull(live.app.container.connectionStore.connection.value)
            val committed = requireNotNull(live.app.container.connectionStore.captureCommitted())
            val launchIds = live.app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
            val target = ChangesTarget.Pane(live.address.paneId)
            val beforeChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
            assertTrue("The owned repository is readable before this normal link exercise", beforeChanges is ApiResult.Success)
            assertFalse("External web-link acceptance must leave Collie's package", browserPackage == live.app.packageName)
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
                await(scenario, "native admission for one harmless URL printf") { activity ->
                    activity.findViewById<View>(R.id.send_button).isEnabled && activity.findViewById<EditText>(R.id.reply_input).isEnabled
                }
                onView(withId(R.id.reply_input)).perform(replaceText("printf '%s\\n' '$marker' '$url'"))
                onView(withId(R.id.send_button)).perform(click())
                await(scenario, "the actual fresh shell output contains a rendered exact HTTPS URLSpan") { activity ->
                    val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                    val text = mirror.text as? Spanned
                    text != null && text.toString().contains(marker) && text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                        .any { it.url == url && text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() == url }
                }
                onView(withId(R.id.terminal_text)).perform(closeSoftKeyboard())
                awaitVisibleConfirmedPrintedPath(scenario, live, url)
                val beforeRead = runBlocking {
                    live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
                }
                assertTrue("Capture the actual completed shell output before the read-only handoff", beforeRead is ApiResult.Success)
                val observed = java.util.concurrent.atomic.AtomicReference<Intent?>(null)
                val sends = java.util.concurrent.atomic.AtomicInteger()
                val callback = androidx.test.runner.intent.IntentCallback { sent ->
                    // Standard read-only AndroidX instrumentation observer. Never stub or block.
                    if (sent.action == Intent.ACTION_VIEW && sent.dataString == url) {
                        sends.incrementAndGet(); observed.compareAndSet(null, Intent(sent))
                    }
                }
                val monitor = androidx.test.runner.intent.IntentMonitorRegistry.getInstance()
                monitor.addIntentCallback(callback)
                try {
                    var x = -1
                    var y = -1
                    scenario.onActivity { activity ->
                        assertTrue("The original native Pane owns the actual URL tap", activity.hasWindowFocus() &&
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity) &&
                            live.repository.isCurrent(live.actor))
                        val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                        val text = mirror.text as Spanned
                        val span = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                            .filter { it.url == url }.maxByOrNull { text.getSpanStart(it) }
                        val actual = requireNotNull(span)
                        assertEquals(url, text.subSequence(text.getSpanStart(actual), text.getSpanEnd(actual)).toString())
                        val layout = requireNotNull(mirror.layout)
                        assertTrue("The actual URL layout must remain settled", !mirror.isLayoutRequested && layout.text.toString() == text.toString())
                        val offset = text.getSpanStart(actual) + url.length / 2
                        val line = layout.getLineForOffset(offset)
                        val position = IntArray(2).also(mirror::getLocationOnScreen)
                        x = (position[0] + mirror.compoundPaddingLeft + layout.getPrimaryHorizontal(offset) - mirror.scrollX).toInt()
                        y = (position[1] + mirror.compoundPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - mirror.scrollY).toInt()
                        val visible = Rect()
                        assertTrue("Only the actual visible URLSpan receives the normal touch", mirror.getGlobalVisibleRect(visible) && visible.contains(x, y))
                        val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                        assertFalse("No IME may cover the actual URL tap", insets.isVisible(WindowInsetsCompat.Type.ime()))
                        assertFalse("Native selection must have ended before the link tap", mirror.hasSelection())
                    }
                    // Worker-thread injection lets the normal external activity open without an
                    // Espresso in-process RESUMED-root wait after the actual ACTION_UP.
                    val automation = instrumentation.uiAutomation
                    val downTime = SystemClock.uptimeMillis()
                    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                    fun touch(action: Int) {
                        val coordinates = MotionEvent.PointerCoords().apply {
                            this.x = x.toFloat(); this.y = y.toFloat(); pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
                        }
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try { assertTrue("Android accepts the actual native URL touch", automation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(50); touch(MotionEvent.ACTION_UP)
                    val deadline = SystemClock.uptimeMillis() + 5_000L
                    var actualBrowser = false
                    while (!actualBrowser && SystemClock.uptimeMillis() < deadline) {
                        val root = automation.rootInActiveWindow
                        try { actualBrowser = root?.isVisibleToUser == true && root.packageName?.toString() == browserPackage }
                        finally { root?.recycle() }
                        if (!actualBrowser) SystemClock.sleep(50)
                    }
                    assertTrue("The actual existing external browser must become foreground", actualBrowser)
                    val sent = requireNotNull(observed.get()) { "No actual HTTPS ACTION_VIEW was observed from the native link touch" }
                    assertEquals("One actual native link tap sends exactly one browser intent", 1, sends.get())
                    assertEquals(url, sent.dataString)
                    assertNull("The external handoff contains no Collie data attachment", sent.clipData)
                    assertTrue("The normal URLSpan handoff must carry no headers or private Collie extras",
                        sent.extras?.keySet().orEmpty().all { it == "com.android.browser.application_id" })
                    sent.getStringExtra("com.android.browser.application_id")?.let { assertEquals(live.app.packageName, it) }
                    assertSame("External navigation preserves the original paired credential", connection, live.app.container.connectionStore.connection.value)
                    assertEquals("External navigation preserves the original committed pairing", committed, live.app.container.connectionStore.captureCommitted())
                    assertTrue("External navigation retains the original pane actor", live.repository.isCurrent(live.actor))
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putString("nativeExternalLink", "ACTUAL_HTTPS_ACTION_VIEW_AND_BROWSER_FOREGROUND")
                        putString("nativeExternalLinkPackage", browserPackage)
                        putString("nativeExternalLinkUrl", url)
                    })
                    // One ordinary Android Back, not an intent/relaunch shortcut back to the pane.
                    val backAt = SystemClock.uptimeMillis()
                    for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                        assertTrue("Normal Back returns from the browser", automation.injectInputEvent(
                            android.view.KeyEvent(backAt, SystemClock.uptimeMillis(), action, android.view.KeyEvent.KEYCODE_BACK, 0), true))
                    }
                    await(scenario, "normal Back resumes the original Pane after the browser handoff") { activity ->
                        activity.hasWindowFocus() && ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity) &&
                            live.repository.isCurrent(live.actor) && activity.findViewById<TextView>(R.id.terminal_text).text.toString().contains(marker)
                    }
                } finally { monitor.removeIntentCallback(callback) }
                val afterRead = runBlocking {
                    live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
                }
                assertTrue("The exact shell remains readable after the browser handoff", afterRead is ApiResult.Success)
                assertEquals("Opening the HTTPS URL must preserve real terminal output and revision",
                    (beforeRead as ApiResult.Success).value.pane, (afterRead as ApiResult.Success).value.pane)
                val afterChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
                assertTrue("The owned repository remains readable", afterChanges is ApiResult.Success)
                assertEquals("External URL navigation must not change the owned repository", (beforeChanges as ApiResult.Success).value.state,
                    (afterChanges as ApiResult.Success).value.state)
                assertTrue("External navigation creates no additional launch intent",
                    live.app.container.launchIntentStore.all().all { it.intent.requestId in launchIds })
            }
        }
    }

    @Test fun liveLoadOlderScrollbackPreservesTheVisibleMirrorLine() = withLive { live ->
        // A bounded harmless printf creates enough real terminal scrollback to expose the
        // production Load older action. It writes no files and uses no fixture/test API.
        val rowPrefix = "SCROLL_${UUID.randomUUID().toString().take(8)}_"
        val rowPattern = Regex("${Regex.escape(rowPrefix)}[0-9]{4}")
        val lastRow = rowPrefix + "0649"
        val command = buildString {
            append("printf '%s\\n'")
            repeat(650) { index -> append(" '").append(rowPrefix).append(index.toString().padStart(4, '0')).append("'") }
        }
        val target = ChangesTarget.Pane(live.address.paneId)
        val beforeChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Repository state before scrollback read must be available", beforeChanges is ApiResult.Success)
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "native scrollback reader admission") { activity ->
                activity.findViewById<View>(R.id.send_button).isEnabled &&
                    activity.findViewById<EditText>(R.id.reply_input).isEnabled
            }
            onView(withId(R.id.reply_input)).perform(replaceText(command))
            onView(withId(R.id.send_button)).perform(click())
            await(scenario, "actual terminal output reaches the bounded initial window") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val summary = state.panes.singleOrNull {
                    it.paneId == live.address.paneId && it.host == live.address.scope.host && it.session == live.address.scope.session
                }
                val reportedDepth = listOfNotNull(summary?.readableLines, state.pane?.readableLines).maxOrNull() ?: 0
                state.pane?.text?.contains(lastRow) == true && state.loadedLines >= 600 &&
                    state.mux != null && reportedDepth > state.requestedLines
            }
            // ViewModel acceptance precedes background text preparation, TextView layout and
            // the posted ScrollView restore. Observe actual geometry, not ViewModel readiness alone.
            fun settledGeometry(activity: PaneActivity): List<Int>? {
                assertTrue("Every geometry observation retains the original live pane actor", live.repository.isCurrent(live.actor))
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext ?: return null
                if (state.savedCopy != null || actor.key != live.actor.key ||
                    actor.navigationGeneration != live.actor.navigationGeneration ||
                    actor.connection !== live.actor.connection || !live.repository.isCurrent(actor)) return null
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.terminal_scroll)
                val child = scroll.getChildAt(0) ?: return null
                val layout = mirror.layout ?: return null
                val text = mirror.text.toString()
                if (!mirror.isShown || !scroll.isShown || mirror.isLayoutRequested || scroll.isLayoutRequested ||
                    child.isLayoutRequested || mirror.width <= 0 || mirror.height <= 0 || scroll.height <= 0 ||
                    layout.text.toString() != text || text.lineSequence().none { it.trim() == lastRow }) return null
                val location = IntArray(2).also(mirror::getLocationOnScreen)
                val scrollLocation = IntArray(2).also(scroll::getLocationOnScreen)
                return listOf(child.height, scroll.scrollY, mirror.height, mirror.width, layout.lineCount,
                    location[1], scrollLocation[1], scroll.height, mirror.lineHeight, mirror.scrollY)
            }
            var priorGeometry: List<Int>? = null
            var stableSamples = 0
            await(scenario, "the initial 600-line mirror has completed its actual layout") { activity ->
                val geometry = settledGeometry(activity)
                stableSamples = if (geometry != null && geometry == priorGeometry) stableSamples + 1 else 0
                priorGeometry = geometry
                geometry != null && stableSamples >= 2
            }
            val beforeReadOnlyGrowth = runBlocking {
                live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
            }
            assertTrue("The exact shell mirror must remain readable before loading older rows", beforeReadOnlyGrowth is ApiResult.Success)

            var anchor = ""
            var anchorY = 0
            var initialPrintedRows = 0
            scenario.onActivity { activity ->
                val viewModel = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java]
                val state = viewModel.state.value
                val summary = state.panes.singleOrNull {
                    it.paneId == live.address.paneId && it.host == live.address.scope.host && it.session == live.address.scope.session
                }
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.terminal_scroll)
                val reportedDepth = listOfNotNull(summary?.readableLines, state.pane?.readableLines).maxOrNull() ?: 0
                val backendSupportsOlder = reportedDepth > state.requestedLines &&
                    state.mux?.supports("gridScrollback") != false && PaneScrollbackWindow.maxLines(state.mux) > state.requestedLines
                assumeTrue("The supplied shell backend has no deeper scrollback window to accept", backendSupportsOlder)
                val button = activity.findViewById<View>(R.id.pane_buffer_action)
                assertTrue("A backend-reported deeper scrollback window must expose the native Load older control",
                    button.isShown && button.isEnabled)
                assertEquals("The visible control must load terminal rows, not open agent history",
                    activity.getString(R.string.pane_scrollback_load_older), (button as TextView).text.toString())
                scroll.scrollTo(0, 0)
                val layout = requireNotNull(mirror.layout)
                val text = mirror.text.toString()
                initialPrintedRows = text.lineSequence().count { it.trim().matches(rowPattern) }
                val location = IntArray(2).also(mirror::getLocationOnScreen)
                val scrollLocation = IntArray(2).also(scroll::getLocationOnScreen)
                for (line in 0 until layout.lineCount) {
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line).coerceAtMost(text.length)
                    val candidate = text.substring(start, end).trim()
                    if (candidate.matches(rowPattern)) {
                        val top = location[1] + mirror.paddingTop + layout.getLineTop(line) - mirror.scrollY
                        val bottom = location[1] + mirror.paddingTop + layout.getLineBottom(line) - mirror.scrollY
                        if (bottom > scrollLocation[1] && top < scrollLocation[1] + scroll.height) {
                            anchor = candidate
                            anchorY = top
                            break
                        }
                    }
                }
                assertTrue("Initial 600-line mirror window must contain a visible unique scrollback row", anchor.isNotEmpty())
                assertTrue("The native Load older control must be available for this readable pane", button.performClick())
            }
            priorGeometry = null
            stableSamples = 0
            await(scenario, "the expanded mirror layout and restored anchor settle within one line-height") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val geometry = settledGeometry(activity)
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val lines = mirror.text.toString().lineSequence().toList()
                val matches = lines.withIndex().filter { it.value.trim() == anchor }
                // The old 600-line TextView can remain settled while the new state prepares.
                // More actual owned rows proves this is the expanded render, not that old layout.
                val expanded = state.loadedLines > 600 && !state.loadingOlder && state.pane?.text?.contains(anchor) == true &&
                    lines.count { it.trim().matches(rowPattern) } > initialPrintedRows
                var restoredY: Int? = null
                if (expanded && geometry != null && matches.size == 1) {
                    val offset = lines.take(matches.single().index).sumOf { it.length + 1 }
                    val layout = requireNotNull(mirror.layout)
                    val lineIndex = layout.getLineForOffset(offset.coerceAtMost(mirror.text.length))
                    val location = IntArray(2).also(mirror::getLocationOnScreen)
                    restoredY = location[1] + mirror.paddingTop + layout.getLineTop(lineIndex) - mirror.scrollY
                }
                val anchored = restoredY != null && kotlin.math.abs(restoredY - anchorY) <= mirror.lineHeight
                stableSamples = if (anchored && geometry == priorGeometry) stableSamples + 1 else 0
                priorGeometry = geometry.takeIf { anchored }
                anchored && stableSamples >= 2
            }
            scenario.onActivity { activity ->
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val lines = mirror.text.toString().lineSequence().toList()
                val line = lines.indexOfFirst { it.trim() == anchor }
                assertTrue("The preserved scrollback anchor must remain in the expanded mirror", line >= 0)
                val layout = requireNotNull(mirror.layout)
                val offset = lines.take(line).sumOf { it.length + 1 }
                val lineIndex = layout.getLineForOffset(offset.coerceAtMost(mirror.text.length))
                val location = IntArray(2).also(mirror::getLocationOnScreen)
                val restoredY = location[1] + mirror.paddingTop + layout.getLineTop(lineIndex) - mirror.scrollY
                assertTrue("Loading older rows must preserve the visible mirror row within one line-height",
                    kotlin.math.abs(restoredY - anchorY) <= mirror.lineHeight)
                assertTrue("Scrollback expansion must retain the original live pane actor", live.repository.isCurrent(live.actor))
            }
            val afterReadOnlyGrowth = runBlocking {
                live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
            }
            assertTrue("The exact shell mirror must remain readable after loading older rows", afterReadOnlyGrowth is ApiResult.Success)
            assertEquals("Reading older scrollback must preserve terminal contents and revision",
                (beforeReadOnlyGrowth as ApiResult.Success).value.pane,
                (afterReadOnlyGrowth as ApiResult.Success).value.pane)
            val target = ChangesTarget.Pane(live.address.paneId)
            val repoAfter = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
            assertTrue("Scrollback reads must leave the fixture Git repository readable", repoAfter is ApiResult.Success)
            assertEquals("Loading deeper terminal rows must leave the repository's Git state unchanged",
                (beforeChanges as ApiResult.Success).value.state, (repoAfter as ApiResult.Success).value.state)
            assertTrue("Scrollback reads must retain the exact live pane actor", live.repository.isCurrent(live.actor))
        }
    }

    /** Public cross-window accessibility only; remote toolbar surfaces need not be Espresso views. */
    private fun copyExactSelectedMirrorThroughVisibleToolbar(
        scenario: ActivityScenario<PaneActivity>, live: Live, marker: String,
    ) {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val priorFlags = info.flags
        info.flags = priorFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        val copyLabel = live.app.getString(android.R.string.copy)
        fun originalPane(selectionRequired: Boolean = true): Rect {
            var bounds = Rect()
            scenario.onActivity { activity ->
                assertTrue("Copy requires the original resumed native Pane", activity.hasWindowFocus() &&
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity))
                assertTrue("Copy requires the original current pane actor", live.repository.isCurrent(live.actor))
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = requireNotNull(state.paneReadContext)
                assertTrue("Copy must retain original live custody without a saved copy", state.savedCopy == null &&
                    actor.key == live.actor.key && actor.navigationGeneration == live.actor.navigationGeneration &&
                    actor.connection === live.actor.connection && live.repository.isCurrent(actor))
                if (selectionRequired) {
                    val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                    val start = minOf(mirror.selectionStart, mirror.selectionEnd)
                    val end = maxOf(mirror.selectionStart, mirror.selectionEnd)
                    assertTrue("The actual native selection must remain available before Copy", start >= 0 && end > start)
                    assertEquals("Only the exact fresh marker may be copied", marker, mirror.text.subSequence(start, end).toString())
                }
                assertTrue("The original native Pane window must remain visible", activity.window.decorView.getGlobalVisibleRect(bounds))
            }
            return bounds
        }
        fun copyNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean =
            node.isVisibleToUser && node.isEnabled && (node.text?.toString() == copyLabel || node.contentDescription?.toString() == copyLabel)
        fun clickable(node: android.view.accessibility.AccessibilityNodeInfo): Boolean = node.isClickable ||
            node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }
        fun trustedPackage(name: String): Boolean = name == live.app.packageName || name == "android" || try {
            val flags = live.app.packageManager.getApplicationInfo(name, 0).flags
            flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        data class Target(val label: android.view.accessibility.AccessibilityNodeInfo,
            val touch: android.view.accessibility.AccessibilityNodeInfo, val bounds: Rect, val window: Int,
            val rootWindow: Int, val pkg: String) {
            fun key() = "$window/$rootWindow/$pkg/$bounds"
            fun close() { label.recycle(); touch.recycle() }
        }
        var lastWindows = ""
        var priorTarget: String? = null
        try {
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (SystemClock.uptimeMillis() < deadline) {
                val paneBounds = originalPane()
                val targets = mutableListOf<Target>()
                val windows = automation.windows
                val metadata = mutableListOf<String>()
                try {
                    for (window in windows.take(24)) {
                        val windowBounds = Rect().also(window::getBoundsInScreen)
                        val root = window.root
                        metadata += "id=${window.id},type=${window.type},layer=${window.layer},pkg=${root?.packageName},class=${root?.className},bounds=$windowBounds"
                        if (root == null) continue
                        try {
                            if (window.type !in setOf(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                                    android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) ||
                                !Rect.intersects(paneBounds, windowBounds)) continue
                            var visited = 0
                            fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                                if (++visited > 2_000 || depth > 32) return
                                if (copyNode(node)) {
                                    var touch = android.view.accessibility.AccessibilityNodeInfo.obtain(node)
                                    try {
                                        var ancestors = 0
                                        while (!clickable(touch) && ancestors++ < 3) {
                                            val parent = touch.parent ?: break
                                            touch.recycle(); touch = parent
                                        }
                                        val bounds = Rect().also(touch::getBoundsInScreen)
                                        val pkg = touch.packageName?.toString().orEmpty()
                                        val labelPkg = node.packageName?.toString().orEmpty()
                                        val trusted = trustedPackage(pkg) && trustedPackage(labelPkg)
                                        if (targets.size < 8) metadata += "copyPackage=$labelPkg,touchPackage=$pkg,class=${node.className},nodeWindow=${node.windowId},touchWindow=${touch.windowId},bounds=$bounds,clickable=${clickable(touch)},trusted=$trusted"
                                        if (touch.isVisibleToUser && touch.isEnabled && clickable(touch) && !bounds.isEmpty &&
                                            paneBounds.contains(bounds) && windowBounds.contains(bounds) && trusted) {
                                            targets += Target(android.view.accessibility.AccessibilityNodeInfo.obtain(node),
                                                android.view.accessibility.AccessibilityNodeInfo.obtain(touch), bounds, touch.windowId, window.id, pkg)
                                        }
                                    } finally { touch.recycle() }
                                }
                                for (index in 0 until node.childCount) {
                                    if (visited >= 2_000) break
                                    val child = node.getChild(index) ?: continue
                                    try { visit(child, depth + 1) } finally { child.recycle() }
                                }
                            }
                            visit(root, 0)
                        } finally { root.recycle() }
                    }
                    lastWindows = metadata.joinToString("; ")
                    val target = targets.distinctBy { it.key() }.singleOrNull()
                    if (target != null && priorTarget == target.key()) {
                        val before = Rect(target.bounds)
                        if (!target.label.refresh() || !target.touch.refresh() || !copyNode(target.label) ||
                            !target.touch.isVisibleToUser || !target.touch.isEnabled || !clickable(target.touch) ||
                            target.touch.packageName?.toString() != target.pkg || target.touch.windowId != target.window ||
                            Rect().also(target.touch::getBoundsInScreen) != before) continue
                        assertTrue("The exact Copy action remains inside the original visible Pane", originalPane().contains(before))
                        instrumentation.sendStatus(2, android.os.Bundle().apply {
                            putString("nativeCopyToolbar", "VISIBLE_COPY")
                            putString("nativeCopyPackage", target.pkg)
                            putString("nativeCopyWindow", target.window.toString())
                            putString("nativeCopyRootWindow", target.rootWindow.toString())
                            putString("nativeCopyBounds", before.toString())
                            putString("nativeCopyWindows", lastWindows)
                        })
                        val downTime = SystemClock.uptimeMillis()
                        val properties = android.view.MotionEvent.PointerProperties().apply {
                            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                        }
                        fun touch(action: Int) {
                            val coordinates = android.view.MotionEvent.PointerCoords().apply {
                                x = before.centerX().toFloat(); y = before.centerY().toFloat()
                                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
                            }
                            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                            try { assertTrue("Android accepts the normal touch on its actual visible Copy control", automation.injectInputEvent(event, true)) }
                            finally { event.recycle() }
                        }
                        touch(MotionEvent.ACTION_DOWN)
                        SystemClock.sleep(50)
                        touch(MotionEvent.ACTION_UP)
                        val clipboard = live.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copiedDeadline = SystemClock.uptimeMillis() + 2_000L
                        while (SystemClock.uptimeMillis() < copiedDeadline) {
                            originalPane(selectionRequired = false)
                            if (clipboard.primaryClip?.getItemAt(0)?.coerceToText(live.app)?.toString() == marker) return
                            SystemClock.sleep(25)
                        }
                        fail("Actual visible native Copy did not place the exact fresh selection on the clipboard")
                    }
                    priorTarget = target?.key()
                } finally { targets.forEach { it.close() }; windows.forEach { it.recycle() } }
                SystemClock.sleep(100)
            }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("nativeCopyToolbar", "NO_UNIQUE_VISIBLE_COPY_WITHIN_5S")
                putString("nativeCopyWindows", lastWindows)
            })
            fail("No unique visible native Copy control was exposed within five seconds; window metadata emitted without UI text")
        } finally {
            info.flags = priorFlags
            automation.serviceInfo = info
        }
    }

    /** Wait for the real post-Copy viewport before touching the confirmed native link. */
    private fun awaitVisibleConfirmedPrintedPath(scenario: ActivityScenario<PaneActivity>, live: Live, path: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var previous: List<Int>? = null
        var stable = 0
        var diagnostic = "not-observed"
        while (SystemClock.uptimeMillis() < deadline) {
            var geometry: List<Int>? = null
            scenario.onActivity { activity ->
                assertTrue("Printed-path readiness retains the original pane actor", live.repository.isCurrent(live.actor))
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.terminal_scroll)
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                val imeHidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                val selectionEnded = !mirror.hasSelection()
                val layout = mirror.layout
                val laidOut = layout != null && layout.text.toString() == mirror.text.toString() &&
                    !mirror.isLayoutRequested && !scroll.isLayoutRequested && mirror.isShown && mirror.width > 0 && mirror.height > 0
                val original = resumed.contains(activity) && activity.hasWindowFocus() && state.savedCopy == null &&
                    actor != null && actor.key == live.actor.key && actor.navigationGeneration == live.actor.navigationGeneration &&
                    actor.connection === live.actor.connection && live.repository.isCurrent(actor)
                diagnostic = "resumed=${resumed.map { it.javaClass.simpleName }},paneFocused=${activity.hasWindowFocus()},original=$original,imeHidden=$imeHidden,selectionEnded=$selectionEnded,laidOut=$laidOut"
                val occurrence = clickableSpanOccurrence(mirror, path)
                if (original && imeHidden && selectionEnded && laidOut && occurrence != null) {
                    val actualLayout = requireNotNull(layout)
                    val offset = occurrence.first + path.length / 2
                    val line = actualLayout.getLineForOffset(offset)
                    val localX = mirror.compoundPaddingLeft + actualLayout.getPrimaryHorizontal(offset) - mirror.scrollX
                    val localY = mirror.compoundPaddingTop + (actualLayout.getLineTop(line) + actualLayout.getLineBottom(line)) / 2f - mirror.scrollY
                    val position = IntArray(2).also(mirror::getLocationOnScreen)
                    val x = (position[0] + localX).toInt()
                    val y = (position[1] + localY).toInt()
                    val visible = Rect()
                    val decor = activity.window.decorView
                    val decorPosition = IntArray(2).also(decor::getLocationOnScreen)
                    val system = requireNotNull(insets).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    val safe = Rect(decorPosition[0] + system.left, decorPosition[1] + system.top,
                        decorPosition[0] + decor.width - system.right, decorPosition[1] + decor.height - system.bottom)
                    val pointVisible = localX >= 0f && localX < mirror.width && localY >= 0f && localY < mirror.height &&
                        mirror.getGlobalVisibleRect(visible) && visible.contains(x, y) && safe.contains(x, y)
                    diagnostic += ",confirmed=true,pointVisible=$pointVisible"
                    if (pointVisible) geometry = listOf(x, y, mirror.width, mirror.height, actualLayout.lineCount,
                        scroll.scrollY, scroll.height, visible.top, visible.bottom)
                }
            }
            stable = if (geometry != null && geometry == previous) stable + 1 else 0
            previous = geometry
            if (geometry != null && stable >= 2) return
            SystemClock.sleep(100)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        try {
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("nativePrintedPathReadiness", diagnostic)
                putString("nativePrintedPathActivePackage", root?.packageName?.toString())
                putString("nativePrintedPathActiveClass", root?.className?.toString())
                putString("nativePrintedPathActiveWindow", root?.windowId?.toString())
            })
        } finally { root?.recycle() }
        fail("Original resumed Pane and visible confirmed path did not settle after native Copy/IME close within five seconds")
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

    private fun awaitChanges(checkpoint: String, ready: (ChangesActivity) -> Boolean): ChangesActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched: ChangesActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ChangesActivity>().singleOrNull()
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
    private fun changeButton(activity: ChangesActivity, path: String = TEXT): TextView? = descendants(activity.findViewById(R.id.changes_content))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.text.toString().contains("  $path  ") }
    private fun changeAction(activity: ChangesActivity, label: String): TextView? = descendants(activity.findViewById(R.id.changes_content))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.text.toString() == label }
    private fun clickableSpan(view: TextView, value: String): ClickableSpan? = clickableSpanOccurrence(view, value)?.second

    private fun clickableSpanOccurrence(view: TextView, value: String): Pair<Int, ClickableSpan>? {
        val text = view.text.toString()
        val spanned = view.text as? Spanned ?: return null
        // The latest printed occurrence follows this run's fresh copy marker; older echoed
        // commands can carry the same confirmed path outside the current viewport.
        var start = text.lastIndexOf(value)
        while (start >= 0) {
            val span = spanned.getSpans(start, start + value.length, ClickableSpan::class.java)
                ?.firstOrNull { spanned.getSpanStart(it) <= start && spanned.getSpanEnd(it) >= start + value.length }
            if (span != null) return start to span
            start = if (start == 0) -1 else text.lastIndexOf(value, start - 1)
        }
        return null
    }

    private fun longPressText(token: String): ViewAction = textGesture(token, longPress = true)
    private fun tapPrintedPath(path: String): ViewAction = textGesture(path, longPress = false)

    /** Inject an ordinary touch at the requested text span so this remains a native UI exercise. */
    private fun textGesture(token: String, longPress: Boolean): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = any(View::class.java)
        override fun getDescription(): String = if (longPress) "Long press native mirror text $token" else "Tap confirmed native mirror path $token"
        override fun perform(uiController: UiController, view: View) {
            val textView = view as? TextView ?: throw AssertionError("Mirror gesture target must be a native TextView")
            val text = textView.text
            val occurrence = if (longPress) null else clickableSpanOccurrence(textView, token)
            // The terminal transcript may echo both an input command and its printed output.
            // Select the final marker occurrence, and tap only the occurrence actually spanned as a link.
            val start = if (longPress) text.toString().lastIndexOf(token) else occurrence?.first ?: -1
            if (start < 0) throw AssertionError("Requested native output text is absent or has no confirmed link span: $token")
            val layout = requireNotNull(textView.layout) { "Native mirror text must be laid out before the touch" }
            val offset = start + token.length / 2
            val line = layout.getLineForOffset(offset)
            val x = textView.compoundPaddingLeft + layout.getPrimaryHorizontal(offset) - textView.scrollX
            val y = textView.compoundPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - textView.scrollY
            if (x < 0f || x >= textView.width || y < 0f || y >= textView.height) {
                throw AssertionError("Requested native text point is outside the TextView; refusing to touch a different row: $token")
            }
            val viewPosition = IntArray(2).also(textView::getLocationOnScreen)
            val screenX = (viewPosition[0] + x).toInt()
            val screenY = (viewPosition[1] + y).toInt()
            val visible = Rect()
            if (!textView.getGlobalVisibleRect(visible) || !visible.contains(screenX, screenY)) {
                throw AssertionError("Requested native text point is not actually visible; refusing to touch a clipped row: $token")
            }
            val decor = textView.rootView
            val insets = ViewCompat.getRootWindowInsets(decor)
                ?: throw AssertionError("Native mirror gesture needs the live window's actual system insets")
            if (!longPress && (insets.isVisible(WindowInsetsCompat.Type.ime()) || textView.isLayoutRequested ||
                    layout.text.toString() != text.toString() || textView.hasSelection())) {
                throw AssertionError("Confirmed printed path must remain laid out with IME hidden and native selection ended immediately before touch")
            }
            val safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val decorPosition = IntArray(2).also(decor::getLocationOnScreen)
            val safe = Rect(decorPosition[0] + safeInsets.left, decorPosition[1] + safeInsets.top,
                decorPosition[0] + decor.width - safeInsets.right, decorPosition[1] + decor.height - safeInsets.bottom)
            if (!safe.contains(screenX, screenY)) {
                throw AssertionError("Requested native text point is outside the real system-safe content area: $token")
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screenX.toFloat(), screenY.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val downAccepted = try { automation.injectInputEvent(down, false) } finally { down.recycle() }
            if (!downAccepted) throw AssertionError("Android rejected the exact native text-touch down: $token")
            uiController.loopMainThreadForAtLeast(
                if (longPress) ViewConfiguration.getLongPressTimeout().toLong() + 100L else 50L,
            )
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, screenX.toFloat(), screenY.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val upAccepted = try { automation.injectInputEvent(up, false) } finally { up.recycle() }
            if (!upAccepted) throw AssertionError("Android rejected the exact native text-touch up: $token")
        }
    }

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
        const val HEAD_SOURCE = "completion-shell.sh"
        const val IMAGE = "acceptance.png"
        const val BASE = "COLLIE_FIXTURE_BASE 漢字 e\u0301 👨‍💻"
        const val CHANGE = "COLLIE_FIXTURE_CHANGE λ"
    }
}
