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
import androidx.test.core.app.ActivityScenario
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
import org.junit.After
import org.junit.runner.RunWith

/** Real native import surfaces only. Every phase is opt-in and remains read-only until explicit Continue. */
@RunWith(AndroidJUnit4::class)
class PhysicalLinkImportAcceptanceTest {
    private var settingsScenario: ActivityScenario<SettingsActivity>? = null

    @After fun closeOwnedSettingsScenario() {
        settingsScenario?.close()
        settingsScenario = null
    }

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
            onView(withId(R.id.device_label_input)).perform(replaceText("completion Pixel"), closeSoftKeyboard())
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
        settingsScenario = ActivityScenario.launch(SettingsActivity::class.java)
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
        val deadline = SystemClock.uptimeMillis() + 15_000L
        check(openTaskDownloadsInSystemPicker(deadline)) {
            "Actual DocumentsUI did not settle in the exact task-owned Downloads directory"
        }
        check(selectExactQrFileInSystemPicker(fileName, deadline)) {
            "The exact supplied QR file was not visible on the first Downloads page or could not be touched safely"
        }
    }

    /** Select Downloads only through the actual roots ListView, then await the closed drawer. */
    private fun openTaskDownloadsInSystemPicker(deadline: Long): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var rootsToggleTouched = false
        var downloadsRootTouched = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                val packageName = root.packageName?.toString()
                if (packageName !in DOCUMENT_PACKAGES) {
                    SystemClock.sleep(100)
                    continue
                }
                if (isDownloadsDirectory(root, packageName!!)) return true
                val roots = root.findAccessibilityNodeInfosByViewId("$packageName:id/roots_list")
                val drawerOpen = try { roots.any { it.packageName?.toString() == packageName && it.isVisibleToUser && it.isEnabled } }
                finally { roots.forEach { it.recycle() } }
                if (drawerOpen) {
                    if (!downloadsRootTouched && selectDownloadsRoot(root, packageName, deadline)) downloadsRootTouched = true
                } else if (!rootsToggleTouched) {
                    val toggle = findVisibleDescription(root, packageName, setOf("Show roots", "Open navigation drawer"))
                    if (toggle != null) try {
                        rootsToggleTouched = tapStableDocumentNode(root, toggle, packageName, deadline)
                    } finally { toggle.recycle() }
                }
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        return false
    }

    private fun selectDownloadsRoot(root: AccessibilityNodeInfo, packageName: String, deadline: Long): Boolean {
        val lists = root.findAccessibilityNodeInfosByViewId("$packageName:id/roots_list")
        try {
            val list = lists.singleOrNull { it.packageName?.toString() == packageName &&
                it.viewIdResourceName == "$packageName:id/roots_list" &&
                it.className?.toString() == "android.widget.ListView" && it.isVisibleToUser && it.isEnabled } ?: return false
            val labels = list.findAccessibilityNodeInfosByText("Downloads")
            try {
                val label = labels.singleOrNull { it.packageName?.toString() == packageName &&
                    it.text?.toString() == "Downloads" && it.isVisibleToUser && it.isEnabled } ?: return false
                val beforeList = Rect().also(list::getBoundsInScreen)
                val beforeLabel = Rect().also(label::getBoundsInScreen)
                if (!awaitDocumentsIdle(deadline) || !list.refresh() || !label.refresh() ||
                    !list.isVisibleToUser || !list.isEnabled || list.packageName?.toString() != packageName ||
                    list.viewIdResourceName != "$packageName:id/roots_list" || list.className?.toString() != "android.widget.ListView" ||
                    !label.isVisibleToUser || !label.isEnabled || label.packageName?.toString() != packageName ||
                    label.text?.toString() != "Downloads" || beforeList != Rect().also(list::getBoundsInScreen) ||
                    beforeLabel != Rect().also(label::getBoundsInScreen)) return false
                val active = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
                try {
                    if (active.packageName?.toString() != packageName || active.windowId != label.windowId || list.windowId != label.windowId) return false
                    val labelBounds = Rect().also(label::getBoundsInScreen)
                    val listBounds = Rect().also(list::getBoundsInScreen)
                    val windowBounds = Rect().also(active::getBoundsInScreen)
                    if (labelBounds.isEmpty || !listBounds.contains(labelBounds) || !windowBounds.contains(labelBounds)) return false
                    return injectNormalTouch(active, labelBounds.centerX(), labelBounds.centerY())
                } finally { active.recycle() }
            } finally { labels.forEach { it.recycle() } }
        } finally { lists.forEach { it.recycle() } }
    }

    /** A root request is complete only when dir_list is visible and roots_list is gone. */
    private fun isDownloadsDirectory(root: AccessibilityNodeInfo, packageName: String): Boolean {
        if (packageName !in DOCUMENT_PACKAGES || root.packageName?.toString() != packageName) return false
        fun visible(id: String): Boolean {
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            return try { nodes.any { it.packageName?.toString() == packageName && it.isVisibleToUser } }
            finally { nodes.forEach { it.recycle() } }
        }
        if (visible("roots_list") || !visible("dir_list")) return false
        val titles = root.findAccessibilityNodeInfosByText("Downloads")
        return try { titles.any { it.packageName?.toString() == packageName && it.isVisibleToUser && it.text?.toString() == "Downloads" } }
        finally { titles.forEach { it.recycle() } }
    }

    /** Never scroll: select only the exact task-owned image tile on the first Downloads page. */
    private fun selectExactQrFileInSystemPicker(fileName: String, deadline: Long): Boolean {
        if (!fileName.startsWith("cc13-")) return false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                val packageName = root.packageName?.toString()
                if (packageName !in DOCUMENT_PACKAGES || !isDownloadsDirectory(root, packageName!!)) {
                    SystemClock.sleep(100)
                    continue
                }
                val grids = root.findAccessibilityNodeInfosByViewId("$packageName:id/dir_list")
                try {
                    val grid = grids.singleOrNull { it.packageName?.toString() == packageName &&
                        it.viewIdResourceName == "$packageName:id/dir_list" &&
                        it.className?.toString() in setOf("android.widget.GridView", "androidx.recyclerview.widget.RecyclerView") &&
                        it.isVisibleToUser && it.isEnabled }
                    if (grid == null) { SystemClock.sleep(100); continue }
                    val labels = ownedQrTiles(grid, packageName, fileName)
                    try {
                        // DocumentsUI may expose the same physical tile twice during layout. Distinct
                        // screen bounds still reject separate same-name files as ambiguous targets.
                        val label = labels.distinctBy { Triple(it.windowId, Rect().also(it::getBoundsInScreen), it.contentDescription?.toString()) }.singleOrNull()
                        if (label == null) { SystemClock.sleep(100); continue }
                        val beforeGrid = Rect().also(grid::getBoundsInScreen)
                        val beforeLabel = Rect().also(label::getBoundsInScreen)
                        if (!awaitDocumentsIdle(deadline) || !grid.refresh() || !label.refresh() ||
                            !grid.isVisibleToUser || !grid.isEnabled || grid.packageName?.toString() != packageName ||
                            grid.viewIdResourceName != "$packageName:id/dir_list" || label.packageName?.toString() != packageName ||
                            !isOwnedQrTile(label, packageName, fileName) ||
                            beforeGrid != Rect().also(grid::getBoundsInScreen) || beforeLabel != Rect().also(label::getBoundsInScreen)) return false
                        val active = automation.rootInActiveWindow ?: return false
                        try {
                            if (active.packageName?.toString() != packageName || active.windowId != label.windowId ||
                                grid.windowId != label.windowId || !isDownloadsDirectory(active, packageName)) return false
                            val labelBounds = Rect().also(label::getBoundsInScreen)
                            val gridBounds = Rect().also(grid::getBoundsInScreen)
                            val windowBounds = Rect().also(active::getBoundsInScreen)
                            if (labelBounds.isEmpty || !gridBounds.contains(labelBounds) || !windowBounds.contains(labelBounds)) return false
                            val x = labelBounds.centerX()
                            val y = labelBounds.centerY()
                            if (!labelBounds.contains(x, y) || hasInteractivePreviewAt(active, packageName, x, y)) return false
                            return injectNormalTouch(active, x, y)
                        } finally { active.recycle() }
                    } finally { labels.forEach { it.recycle() } }
                } finally { grids.forEach { it.recycle() } }
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        return false
    }

    private fun isOwnedQrTile(node: AccessibilityNodeInfo, packageName: String, fileName: String): Boolean =
        node.packageName?.toString() == packageName && node.isVisibleToUser && node.isEnabled &&
            node.className?.toString() == "android.widget.LinearLayout" &&
            node.contentDescription?.toString()?.substringBefore(", ") == fileName

    private fun ownedQrTiles(root: AccessibilityNodeInfo, packageName: String, fileName: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (isOwnedQrTile(node, packageName, fileName)) result += AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(root)
        return result
    }

    private fun findVisibleDescription(root: AccessibilityNodeInfo, packageName: String, descriptions: Set<String>): AccessibilityNodeInfo? {
        val description = root.contentDescription?.toString()
        if (root.packageName?.toString() == packageName && root.isVisibleToUser && root.isEnabled &&
            description != null && description in descriptions) return AccessibilityNodeInfo.obtain(root)
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            try { findVisibleDescription(child, packageName, descriptions)?.let { return it } }
            finally { child.recycle() }
        }
        return null
    }

    private fun tapStableDocumentNode(root: AccessibilityNodeInfo, node: AccessibilityNodeInfo, packageName: String, deadline: Long): Boolean {
        if (node.packageName?.toString() != packageName || !node.isVisibleToUser || !node.isEnabled) return false
        val before = Rect().also(node::getBoundsInScreen)
        if (!awaitDocumentsIdle(deadline) || !node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
            node.packageName?.toString() != packageName ||
            node.contentDescription?.toString()?.let { it in setOf("Show roots", "Open navigation drawer") } != true ||
            before != Rect().also(node::getBoundsInScreen)) return false
        val active = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        try {
            if (active.packageName?.toString() != packageName || active.windowId != node.windowId || root.windowId != node.windowId) return false
            val bounds = Rect().also(node::getBoundsInScreen)
            val windowBounds = Rect().also(active::getBoundsInScreen)
            if (bounds.isEmpty || !windowBounds.contains(bounds)) return false
            return injectNormalTouch(active, bounds.centerX(), bounds.centerY())
        } finally { active.recycle() }
    }

    private fun injectNormalTouch(active: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        if (!Rect().also(active::getBoundsInScreen).contains(x, y)) return false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        // Setting SOURCE_TOUCHSCREEN on the short obtain() overload leaves TOOL_TYPE_UNKNOWN.
        // Use the same explicit finger pointer shape as the working native Copy helper.
        val properties = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        fun event(action: Int): MotionEvent {
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x.toFloat(); this.y = y.toFloat()
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
            }
            return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val down = event(MotionEvent.ACTION_DOWN)
        val downAccepted = try {
            assertEquals("Normal DocumentsUI touch uses a finger tool", MotionEvent.TOOL_TYPE_FINGER, down.getToolType(0))
            automation.injectInputEvent(down, true)
        } finally { down.recycle() }
        SystemClock.sleep(50)
        val up = event(MotionEvent.ACTION_UP)
        val upAccepted = try {
            assertEquals("Normal DocumentsUI release keeps the same finger tool", MotionEvent.TOOL_TYPE_FINGER, up.getToolType(0))
            automation.injectInputEvent(up, true)
        } finally { up.recycle() }
        assertTrue("Exact stable DocumentsUI target receives normal touchscreen down/up", downAccepted && upAccepted)
        return true
    }

    private fun hasInteractivePreviewAt(root: AccessibilityNodeInfo, packageName: String, x: Int, y: Int): Boolean {
        val text = root.text?.toString().orEmpty()
        val description = root.contentDescription?.toString().orEmpty()
        val preview = (text + " " + description).contains("preview", ignoreCase = true)
        val bounds = Rect().also(root::getBoundsInScreen)
        if (root.packageName?.toString() == packageName && root.isVisibleToUser && root.isEnabled && preview &&
            bounds.contains(x, y) && (root.isClickable || root.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return true
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            try { if (hasInteractivePreviewAt(child, packageName, x, y)) return true }
            finally { child.recycle() }
        }
        return false
    }

    private fun awaitDocumentsIdle(deadline: Long): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        while (SystemClock.uptimeMillis() < deadline) {
            val remaining = deadline - SystemClock.uptimeMillis()
            try {
                automation.waitForIdle(minOf(150L, remaining).coerceAtLeast(1L), minOf(1_000L, remaining).coerceAtLeast(1L))
                return true
            } catch (_: java.util.concurrent.TimeoutException) {
                // Keep retrying within the original chooser deadline only.
            }
        }
        return false
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
