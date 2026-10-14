package com.lateapex.collie.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native import surfaces only. Every phase is opt-in and remains read-only until explicit Continue. */
@RunWith(AndroidJUnit4::class)
class PhysicalLinkImportAcceptanceTest {
    private var pickerTileEvidenceRecorded = false
    private var pickerTileTouchAttempted = false
    @Test
    fun pasteShareOrQrNavigationRequiresContinueAndReadsTheExactPane() {
        val args = InstrumentationRegistry.getArguments()
        val phase = required(args, "phase")
        assertTrue("Import phase must be paste, share, qr, or pairing", phase in ALL_PHASES)
        assumeTrue("Pairing phase is exercised by the separate native pairing test", phase in NAVIGATION_PHASES)
        val (app, original, origin) = pairedFixture(args)
        val paneId = required(args, "livePaneId").also(::boundedId)
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val useHistory = args.getString("useHistory")?.let { raw ->
            raw.toBooleanStrictOrNull() ?: error("useHistory must be exactly true or false when supplied")
        } ?: false
        args.getString("operatorPair")?.let { raw ->
            check(raw.toBooleanStrictOrNull() != null) { "operatorPair must be exactly true or false when supplied" }
        }
        if (phase == "qr") boundedQrName(required(args, "qrFileName"))
        val repository = app.container.repository
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val url = navigationUrl(origin, paneId, scope, useHistory)
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val originalActor = requireNotNull(repository.readContext(scope, paneId))
        assertTrue("The exact original profile actor is current before import", repository.isCurrent(originalActor))
        try {
            when (phase) {
                "share" -> {
                    assertTrue("The original owner is current before the external share receiver", owner.current(app))
                    app.startActivity(Intent(Intent.ACTION_SEND).apply {
                        setClass(app, OpenLinkActivity::class.java)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                else -> {
                    openNativeSettingsImport()
                    when (phase) {
                        "paste" -> pasteAndReview(url)
                        "qr" -> chooseAndReviewQr(required(args, "qrFileName"))
                    }
                }
            }

            val preview = awaitResumed<LinkImportActivity>("native link preview")
            awaitPreviewReady(preview)
            assertSame("The same installed connection remains in place before Continue", original,
                app.container.connectionStore.connection.value)
            assertTrue("The captured original pairing owner remains current before Continue", owner.current(app))
            assertTrue("The original read actor remains current before Continue", repository.isCurrent(originalActor))
            assertEquals("The preview is the only foreground destination before explicit Continue",
                LinkImportActivity::class.java, resumedActivity()!!::class.java)

            onView(withId(R.id.link_import_continue)).check(matches(isDisplayed())).perform(click())
            val destination = if (useHistory) awaitResumed<HistoryActivity>("native imported history")
                else awaitResumed<PaneActivity>("native imported pane")
            assertSame("Navigation import preserves the original paired profile", original,
                app.container.connectionStore.connection.value)
            assertEquals("The installed profile retains the exact imported HTTPS origin", origin,
                app.container.connectionStore.connection.value?.origin)

            val expectedActor = requireNotNull(repository.readContext(scope, paneId))
            assertTrue("The destination gets a fresh current actor for the exact imported pane", repository.isCurrent(expectedActor))
            val snapshot = runBlocking { repository.snapshot(scope, expectedActor) }
            assertTrue("The destination validates through a genuine mounted snapshot read", snapshot is ApiResult.Success)
            assertTrue("The current pane actor survives the genuine snapshot read", repository.isCurrent(expectedActor))
            val liveSnapshot = (snapshot as ApiResult.Success).value
            val panes = liveSnapshot.agents + liveSnapshot.shellPanes
            assertNotNull("The exact imported pane is present in the server snapshot",
                panes.singleOrNull { it.paneId == paneId && it.host == scope.host && it.session == scope.session })
            assertEquals("Native navigation retains the exact pane ID", paneId,
                destination.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
            assertEquals("Native navigation retains the exact host scope", scope.host,
                destination.intent.getStringExtra(PaneActivity.EXTRA_HOST))
            assertEquals("Native navigation retains the exact session scope", scope.session,
                destination.intent.getStringExtra(PaneActivity.EXTRA_SESSION))
            assertNull("The destination receives no raw imported URL", destination.intent.data)
            assertFalse("The destination intent contains no imported URL text",
                destination.intent.extras?.toString()?.contains(url) == true)

            if (useHistory) {
                val history = destination as HistoryActivity
                awaitHistoryRendered(history)
                val result = runBlocking { repository.history(PaneAddress(scope, paneId), expectedContext = expectedActor) }
                assertTrue("History destination is backed by a genuine same-actor history read", result is ApiResult.Success)
                assertTrue("The imported history actor remains current", repository.isCurrent(expectedActor))
            } else {
                val pane = destination as PaneActivity
                awaitImportedPaneRendered(pane, repository, scope, paneId)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                repository.selectSubject(priorScope, priorSubject)
                resumedActivity()?.takeIf { it is PaneActivity || it is HistoryActivity }?.finish()
            }
        }
        assertSame("Read/navigation import never writes the connection profile", original,
            app.container.connectionStore.connection.value)
        assertTrue("The actual pasted/shared/decoded target remained under the captured origin", owner.connection?.origin == origin)
    }

    @Test
    fun qrPairingImportOnlyPairsAfterExplicitOperatorAction() {
        val args = InstrumentationRegistry.getArguments()
        val phase = required(args, "phase")
        assertTrue("Import phase must be paste, share, qr, or pairing", phase in ALL_PHASES)
        assumeTrue("This test covers only the pairing phase", phase == "pairing")
        val originText = required(args, "liveOrigin")
        val origin = OriginValidator().requireValid(originText)
        assertEquals("Pairing QR acceptance is pinned to the controlled completion mount", REQUIRED_ORIGIN, origin.value)
        val code = required(args, "pairingCode")
        val qrFileName = boundedQrName(required(args, "qrFileName"))
        args.getString("useHistory")?.let { raw ->
            check(raw.toBooleanStrictOrNull() != null) { "useHistory must be exactly true or false when supplied" }
        }
        val operatorPair = args.getString("operatorPair")?.let { raw ->
            raw.toBooleanStrictOrNull() ?: error("operatorPair must be exactly true or false when supplied")
        } ?: false
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Pairing acceptance uses the isolated disposable app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val before = app.container.connectionStore.connection.value
        assertNull("Pairing QR acceptance requires an empty task-owned profile; an existing unpaired profile follows a different Settings route", before)
        val priorCommitted = app.container.connectionStore.captureCommitted()
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val repository = app.container.repository

        openNativeSettingsImport()
        chooseAndReviewQr(qrFileName)
        val preview = awaitResumed<LinkImportActivity>("safe QR pairing preview")
        awaitPreviewReady(preview)
        assertTrue("Immediately before Continue, the original owner remains current", owner.current(app))
        assertSame("Immediately before Continue, the empty namespace remains unchanged", before,
            app.container.connectionStore.connection.value)
        assertEquals("Immediately before Continue, encrypted profile custody remains unchanged", priorCommitted,
            app.container.connectionStore.captureCommitted())
        assertSame("Reading the QR never configures or replaces the current profile", before,
            app.container.connectionStore.connection.value)
        assertEquals("Reading the QR never changes committed profile custody", priorCommitted,
            app.container.connectionStore.captureCommitted())
        assertTrue("The QR preview remains bound to the original pairing owner", owner.current(app))
        assertSame("Pairing QR preview is the foreground screen and has not navigated", preview, resumedActivity())
        assertFalse("Pairing QR preview does not expose the one-time code",
            preview.findViewById<TextView>(R.id.link_import_status).text.toString().contains(code))
        onView(withId(R.id.link_import_continue)).perform(click())

        val main = awaitResumed<MainActivity>("confirmed native pairing prefill")
        assertSame("Continue alone does not configure a pairing", before, app.container.connectionStore.connection.value)
        assertEquals("The safe pairing preview is bound to the supplied origin", origin.value,
            main.findViewById<EditText>(R.id.origin_input).text.toString())
        val codeField = main.findViewById<EditText>(R.id.pairing_code_input)
        assertTrue("Continue places the expected one-time code only in the native pairing field",
            codeField.text.toString() == code)
        assertFalse("Main intent does not retain the private one-time code",
            main.intent.extras?.toString()?.contains(code) == true)
        assertFalse("Imported one-time pairing code is excluded from Android state saving", codeField.isSaveEnabled)
        val saved = Bundle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(main, saved) }
        assertFalse("Saved activity state does not contain the private pairing code", saved.toString().contains(code))
        assertTrue("The imported value is waiting for an explicit native Pair action",
            app.container.connectionStore.connection.value?.isPaired != true)
        assertEquals("The prefill leaves durable profile custody exactly unchanged", priorCommitted,
            app.container.connectionStore.captureCommitted())
        assertSame("Main remains the foreground destination until the operator chooses Pair",
            main, resumedActivity())

        if (operatorPair) {
            assertTrue("Immediately before Pair, the original pairing owner is still current", owner.current(app))
            assertSame("Immediately before Pair, the empty profile is unchanged", before,
                app.container.connectionStore.connection.value)
            assertEquals("Immediately before Pair, the exact imported origin remains bound in the field", origin.value,
                main.findViewById<EditText>(R.id.origin_input).text.toString())
            assertTrue("Immediately before Pair, the exact one-time code remains bound only in memory",
                codeField.text.toString() == code)
            assertEquals("Pairing credentials remain uncommitted until the explicit Pair control",
                priorCommitted, app.container.connectionStore.captureCommitted())
            onView(withId(R.id.pair_button)).perform(scrollTo(), click())
            val deadline = SystemClock.uptimeMillis() + 30_000L
            while (SystemClock.uptimeMillis() < deadline) {
                val active = app.container.connectionStore.connection.value
                if (active?.isPaired == true && active.origin == origin) break
                SystemClock.sleep(100)
            }
            val paired = app.container.connectionStore.connection.value
            assertTrue("The explicit native Pair action completes against the exact supplied origin",
                paired?.isPaired == true && paired.origin == origin)
        } else {
            assertSame("Without operatorPair=true the test never submits the imported code", before,
                app.container.connectionStore.connection.value)
        }
    }

    private fun pairedFixture(args: android.os.Bundle): Triple<CollieApplication, Connection, com.lateapex.collie.domain.CollieOrigin> {
        val originText = required(args, "liveOrigin")
        val expectedOrigin = OriginValidator().requireValid(originText)
        assertEquals("Navigation imports are pinned to the controlled completion mount", REQUIRED_ORIGIN, expectedOrigin.value)
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Acceptance requires the isolated disposable app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val active = app.container.connectionStore.connection.value
        assertTrue("Navigation acceptance requires the already-paired disposable profile", active?.isPaired == true)
        val original = requireNotNull(active)
        assertEquals("The existing profile must match the exact supplied HTTPS origin", expectedOrigin, original.origin)
        return Triple(app, original, expectedOrigin)
    }

    private fun openNativeSettingsImport() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        app.startActivity(Intent(app, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitResumed<SettingsActivity>("native Settings screen")
        onView(withId(R.id.settings_import_link)).perform(scrollTo(), click())
        awaitResumed<LinkImportActivity>("native Settings import screen")
    }

    private fun pasteAndReview(url: String) {
        onView(withId(R.id.link_import_input)).perform(replaceText(url), closeSoftKeyboard())
        onView(withId(R.id.link_import_review)).perform(click())
    }

    private fun chooseAndReviewQr(fileName: String) {
        boundedQrName(fileName)
        onView(withId(R.id.link_import_qr)).perform(click())
        check(openTaskDownloadsInSystemPicker()) { "Actual DocumentsUI did not expose the task-owned Downloads location" }
        check(clickExactTextInSystemPicker(fileName)) {
            "The exact supplied QR file was not selected through the native Downloads picker"
        }
    }

    private fun openTaskDownloadsInSystemPicker(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000L
        var openedRoots = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() !in DOCUMENT_PACKAGES) {
                        SystemClock.sleep(100)
                        continue
                    }
                    if (openedRoots && clickVisibleDocumentText(root, "Downloads")) return true
                    if (!openedRoots && clickVisibleDocumentText(root, "Downloads")) return true
                    if (!openedRoots && clickVisibleDocumentText(root, "Show roots")) openedRoots = true
                    else if (!openedRoots && clickVisibleDocumentText(root, "Open navigation drawer")) openedRoots = true
                } finally { root.recycle() }
            }
            SystemClock.sleep(100)
        }
        return false
    }

    private fun clickVisibleDocumentText(root: AccessibilityNodeInfo, text: String): Boolean =
        clickVisibleDocumentNode(root, text)

    private fun clickExactTextInSystemPicker(fileName: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() in DOCUMENT_PACKAGES && clickVisibleDocumentNode(root, fileName)) return true
                } finally { root.recycle() }
            }
            SystemClock.sleep(150)
        }
        return false
    }

    private fun clickVisibleDocumentNode(node: AccessibilityNodeInfo, fileName: String): Boolean {
        if (node.packageName?.toString() in DOCUMENT_PACKAGES && node.isVisibleToUser && node.isEnabled &&
            (node.text?.toString() == fileName || node.contentDescription?.toString() == fileName ||
                node.contentDescription?.toString()?.startsWith("$fileName, ") == true)) {
            var candidate = AccessibilityNodeInfo.obtain(node)
            var clickOffered = false
            try {
                for (depth in 0 until 8) {
                    if (candidate.packageName != node.packageName) return false
                    val advertisedTileClick = depth == 0 && candidate.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_CLICK
                    }
                    val canClick = candidate.isClickable || advertisedTileClick
                    clickOffered = clickOffered || canClick
                    val clicked = candidate.isVisibleToUser && candidate.isEnabled && canClick &&
                        candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (depth == 0 && fileName.startsWith("cc13-") && !pickerTileEvidenceRecorded) {
                        pickerTileEvidenceRecorded = true
                        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                            putString("stream", "Task picker tile visible=true enabled=true clickable=${candidate.isClickable} " +
                                "advertisedClick=$advertisedTileClick clickSucceeded=$clicked\n")
                        })
                    }
                    if (clicked) return true
                    val parent = candidate.parent ?: break
                    candidate.recycle()
                    candidate = parent
                }
            } finally { candidate.recycle() }
            // Actual API37 Downloads grid tiles use the RecyclerView touch handler, without a
            // clickable ancestor. Keep the separate clickable Preview child out of this tap.
            if (!clickOffered && fileName.startsWith("cc13-") && tapExactTaskTile(node, fileName)) return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (clickVisibleDocumentNode(child, fileName)) return true }
            finally { child.recycle() }
        }
        return false
    }

    private fun tapExactTaskTile(node: AccessibilityNodeInfo, fileName: String): Boolean {
        if (pickerTileTouchAttempted || !node.refresh()) return false
        val packageName = node.packageName?.toString()
        if (packageName !in DOCUMENT_PACKAGES || !node.isVisibleToUser || !node.isEnabled ||
            !(node.text?.toString() == fileName || node.contentDescription?.toString() == fileName ||
                node.contentDescription?.toString()?.startsWith("$fileName, ") == true)) return false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != packageName || root.windowId != node.windowId) return false
            val locations = root.findAccessibilityNodeInfosByText("Downloads")
            val downloadsVisible = try {
                locations.any { it.packageName?.toString() == packageName && it.isVisibleToUser && it.text?.toString() == "Downloads" }
            } finally { locations.forEach { it.recycle() } }
            if (!downloadsVisible) return false
            val tile = Rect().also(node::getBoundsInScreen)
            val window = Rect().also(root::getBoundsInScreen)
            if (tile.isEmpty || !window.contains(tile)) return false
            val x = tile.centerX()
            val y = tile.top + tile.height() * 3 / 4
            fun coversInteractiveChild(parent: AccessibilityNodeInfo): Boolean {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChild(index) ?: continue
                    try {
                        val bounds = Rect().also(child::getBoundsInScreen)
                        if (child.isVisibleToUser && child.isEnabled && bounds.contains(x, y) &&
                            (child.isClickable || child.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return true
                        if (coversInteractiveChild(child)) return true
                    } finally { child.recycle() }
                }
                return false
            }
            if (!tile.contains(x, y) || coversInteractiveChild(node)) return false
            pickerTileTouchAttempted = true
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val downAccepted = try { automation.injectInputEvent(down, true) } finally { down.recycle() }
            SystemClock.sleep(50)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val upAccepted = try { automation.injectInputEvent(up, true) } finally { up.recycle() }
            assertTrue("The one exact visible task tile receives normal touchscreen down/up", downAccepted && upAccepted)
            return true
        } finally { root.recycle() }
    }

    private fun awaitImportedPaneRendered(
        activity: PaneActivity,
        repository: com.lateapex.collie.data.CollieRepository,
        scope: Scope,
        paneId: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val read = state.paneReadContext
                val view = activity.findViewById<TextView>(R.id.terminal_text)
                ready = activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == paneId &&
                    state.pane?.paneId == paneId && read?.key?.host == scope.host && read?.key?.session == scope.session &&
                    read?.key?.subjectIdentity == paneId &&
                    state.savedCopy == null && state.lastSuccessAt != null && read != null && repository.isCurrent(read) &&
                    view?.isShown == true && !view.text.isNullOrEmpty()
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Native pane did not render the exact live pane under its current read actor")
    }

    private fun awaitHistoryRendered(activity: HistoryActivity) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val entries = activity.findViewById<android.view.ViewGroup>(R.id.history_entries)
                val state = activity.findViewById<TextView>(R.id.history_state)
                ready = activity.findViewById<TextView>(R.id.history_subtitle)?.text?.isNotBlank() == true &&
                    entries?.isShown == true && entries.childCount > 0 &&
                    state?.text?.toString() != activity.getString(R.string.history_loading)
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Native History screen did not render actual journal entries")
    }

    private fun awaitPreviewReady(activity: LinkImportActivity) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var enabled = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                enabled = activity.findViewById<View>(R.id.link_import_continue)?.isEnabled == true
            }
            if (enabled) return
            SystemClock.sleep(100)
        }
        error("The native link preview did not admit an explicit Continue action")
    }

    private inline fun <reified T : Activity> awaitResumed(label: String): T {
        val deadline = SystemClock.uptimeMillis() + 25_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val current = resumedActivity()
            if (current is T) return current
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private fun resumedActivity(): Activity? {
        fun resolve(): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).lastOrNull()
        if (Looper.myLooper() == Looper.getMainLooper()) return resolve()
        var result: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = resolve()
        }
        return result
    }

    private fun navigationUrl(
        origin: com.lateapex.collie.domain.CollieOrigin,
        pane: String,
        scope: Scope,
        history: Boolean,
    ): String = buildString {
        append(origin.value.trimEnd('/'))
        append("/pane/")
        append(Uri.encode(pane))
        if (history) append("/history")
        val query = mutableListOf<String>()
        scope.host?.let { query += "h=${Uri.encode(it)}" }
        scope.session?.let { query += "s=${Uri.encode(it)}" }
        if (query.isNotEmpty()) append("?").append(query.joinToString("&"))
    }

    private fun required(args: android.os.Bundle, name: String): String =
        requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) { "Explicit $name is required for this physical phase" }

    private fun boundedId(value: String) {
        assertTrue("Supplied pane ID must be a bounded native identifier", value.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
    }

    private fun boundedQrName(value: String): String {
        assertTrue("Only a task-owned QR image can be opened from DocumentsUI",
            value.matches(Regex("cc13-[A-Za-z0-9_-]{1,48}\\.(png|jpg|jpeg)")))
        return value
    }

    private companion object {
        const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        val ALL_PHASES = setOf("paste", "share", "qr", "pairing")
        val NAVIGATION_PHASES = setOf("paste", "share", "qr")
        val DOCUMENT_PACKAGES = setOf("com.google.android.documentsui", "com.android.documentsui")
    }
}
