package com.lateapex.collie.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import mockwebserver3.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList

/** Actual mounted HTTPS through API/repository and visible form/confirmation actions. */
@RunWith(RobolectricTestRunner::class)
class LayoutActivityHttpsTest {
    @Test fun moveInitialReplyCorrelatesEverySourceAliasBeforeCleanupAndNavigation() {
        for (variant in listOf("valid", "wrong-tab", "wrong-space")) {
            Fixture(variant).use { f ->
                val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
                val activity = controller.get()
                try {
                    awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_move_new_workspace) } }
                    click(activity, R.string.pane_layout_move_new_workspace)
                    val form = ShadowDialog.getLatestDialog() as AlertDialog
                    form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    awaitUi { f.plans.size == 1 && ShadowDialog.getLatestDialog() !== form &&
                        (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true }
                    (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    awaitUi { f.effects.size == 1 && (activity.isFinishing || descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_unknown) }) }
                    val id = f.effects.single()
                    val row = f.journal.unresolved(f.owner)
                    if (variant == "valid") {
                        assertTrue(activity.isFinishing)
                        assertEquals(null, row)
                        assertEquals("pane-new", shadowOf(activity).resultIntent.getStringExtra(LayoutActivity.EXTRA_MOVED_PANE_ID))
                    } else {
                        assertFalse(activity.isFinishing)
                        assertEquals(id, row?.requestId)
                        assertEquals("unknown", row?.state)
                        click(activity, R.string.pane_layout_refresh)
                        shadowOf(android.os.Looper.getMainLooper()).idle()
                        assertEquals(id, f.journal.unresolved(f.owner)?.requestId)
                    }
                    assertEquals(1, f.effects.size)
                    assertEquals(1, f.plans.size)
                    assertTrue(f.requests.all { it.headers["Authorization"] == "Bearer fixture-token" })
                    assertTrue(f.requests.filter { it.method == "POST" }.all { it.url!!.encodedPath.startsWith("/nested/api/pane/pane-1/layout/") })
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun readOnlyPaneEntryOpensRealInspectionWithoutPlanningOrWriting() {
        Fixture("valid", authorized = false).use { f ->
            val paneController = Robolectric.buildActivity(PaneActivity::class.java, Intent(f.app, PaneActivity::class.java).putExtra(PaneActivity.EXTRA_PANE_ID, "pane-1")).create()
            val activity = paneController.get()
            val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
            render.invoke(activity, PaneUiState(loading = false, canWrite = false, mux = f.mux,
                panes = listOf(f.pane)))
            activity.findViewById<View>(R.id.refresh_button).performClick()
            val sheet = ShadowDialog.getLatestDialog()
            val entry = descendants(sheet.findViewById(R.id.collie_sheet_content)).filterIsInstance<TextView>()
                .first { it.text == activity.getString(R.string.pane_layout_controls) }
            assertTrue(entry.isEnabled)
            entry.performClick()
            val target = shadowOf(activity).nextStartedActivity
            assertEquals(LayoutActivity::class.java.name, target.component?.className)
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, target).create().start().resume()
            try {
                awaitUi(diagnostic = {
                    "paths=${f.requests.map { it.url!!.encodedPath }}; " +
                        "source=${f.repository.readSource.value.javaClass.simpleName}; " +
                        "failure=${f.repository.accessFailure?.javaClass?.simpleName}; " +
                        "ui=${descendants(controller.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.take(12)}"
                }) { f.requests.any { it.url!!.encodedPath == "/nested/api/pane/pane-1/layout/edges" } }
                assertFalse(f.repository.writesAllowed(Scope(), "pane-1"))
                val controls = descendants(controller.get().window.decorView).filterIsInstance<TextView>()
                assertTrue(controls.any { it.text == controller.get().getString(R.string.pane_layout_move_new_workspace) && !it.isEnabled })
                assertTrue(f.requests.none { it.method == "POST" })
                assertTrue(f.plans.isEmpty())
                assertTrue(f.effects.isEmpty())
            } finally { controller.pause().stop().destroy(); paneController.destroy() }
        }
    }

    @Test fun lateRetiredPlanDoesNotClearTheCurrentConfirmationOrBusyAttempt() {
        Fixture("valid", holdFirstPlan = true).use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            try {
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_move_new_workspace) } }
                click(activity, R.string.pane_layout_move_new_workspace)
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                awaitUi { f.oldPlanEntered.count == 0L }
                val old = f.repository.readContext(Scope(), "pane-1")!!
                f.repository.setLeadEpoch(old.key.leadEpoch + 1)
                click(activity, R.string.pane_layout_refresh)
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_move_new_workspace) } }
                click(activity, R.string.pane_layout_move_new_workspace)
                val form = ShadowDialog.getLatestDialog() as AlertDialog
                form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                awaitUi { f.plans.size == 2 && ShadowDialog.getLatestDialog() !== form &&
                    (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true }
                val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
                f.releaseOldPlan.countDown()
                awaitUi { f.oldPlanReturned.count == 0L }
                assertTrue(confirmation.isShowing)
                assertTrue(LayoutActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(activity))
                assertTrue(f.effects.isEmpty())
                assertEquals(null, f.journal.unresolved(f.owner))
            } finally { f.releaseOldPlan.countDown(); controller.pause().stop().destroy() }
        }
    }

    @Test fun stoppedEnvFormIsWipedBeforeOffMainAuthorityRetirementReturns() {
        Fixture("valid").use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            try {
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_split_right) } }
                click(activity, R.string.pane_layout_split_right)
                val form = ShadowDialog.getLatestDialog() as AlertDialog
                descendants(form.window!!.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_add_env) }.performClick()
                val fields = descendants(form.window!!.decorView).filterIsInstance<android.widget.EditText>()
                assertEquals(3, fields.size)
                fields[1].setText("PRIVATE_VALUE"); fields[2].setText("copied-private-value")
                controller.pause().stop()
                val old = f.repository.readContext(Scope(), "pane-1")!!
                val returned = java.util.concurrent.atomic.AtomicBoolean(false)
                val interruptRestored = java.util.concurrent.atomic.AtomicBoolean(false)
                val posted = java.util.concurrent.atomic.AtomicBoolean(false)
                val originalPoster = activity.retirementPoster
                activity.retirementPoster = { task -> originalPoster(task).also { posted.set(true) } }
                val worker = Thread {
                    f.repository.setLeadEpoch(old.key.leadEpoch + 1)
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                    returned.set(true)
                }
                worker.start()
                val actorField = LayoutActivity::class.java.getDeclaredField("actor").apply { isAccessible = true }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (actorField.get(activity) != null && System.nanoTime() < deadline) Thread.yield()
                assertEquals(null, actorField.get(activity))
                while (!posted.get() && System.nanoTime() < deadline) Thread.yield()
                assertTrue(posted.get())
                worker.interrupt()
                assertFalse(returned.get())
                shadowOf(android.os.Looper.getMainLooper()).idle()
                worker.join(5_000)
                assertTrue(returned.get()); assertTrue(interruptRestored.get()); assertFalse(form.isShowing)
                assertTrue(fields.all { it.text.isEmpty() })
                assertTrue(descendants(form.window!!.decorView).filterIsInstance<android.widget.Spinner>().all { it.adapter == null })
                form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
                assertEquals(null, f.journal.unresolved(f.owner))
                controller.start().resume()
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun retirementBetweenActorCheckAndDialogPublicationWipesTheUnshownFormBeforeAcknowledgement() {
        Fixture("valid").use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            var worker: Thread? = null
            try {
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_split_right) } }
                val old = f.repository.readContext(Scope(), "pane-1")!!
                val returned = java.util.concurrent.atomic.AtomicBoolean(false)
                val posted = java.util.concurrent.atomic.AtomicReference<Runnable>()
                val originalPoster = activity.retirementPoster
                activity.retirementPoster = { task -> originalPoster(task).also { posted.set(task) } }
                var retiredDialog: AlertDialog? = null
                var oldFields = emptyList<android.widget.EditText>()
                var oldSpinners = emptyList<android.widget.Spinner>()
                var oldAdd: View? = null
                activity.beforeDialogPublication = { dialog, copiedView ->
                    activity.beforeDialogPublication = { _, _ -> }
                    retiredDialog = dialog
                    assertNotNull(copiedView)
                    val form = copiedView!!
                    oldAdd = descendants(form).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_add_env) }
                    oldAdd!!.performClick()
                    oldFields = descendants(form).filterIsInstance<android.widget.EditText>()
                    oldSpinners = descendants(form).filterIsInstance<android.widget.Spinner>()
                    assertEquals(3, oldFields.size)
                    oldFields[1].setText("PRIVATE_KEY"); oldFields[2].setText("private-before-publication")
                    assertTrue(oldSpinners.all { it.adapter != null })
                    worker = Thread { f.repository.setLeadEpoch(old.key.leadEpoch + 1); returned.set(true) }.also { it.start() }
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    while (posted.get() == null && System.nanoTime() < deadline) Thread.yield()
                    assertNotNull(posted.get())
                    assertFalse(returned.get()) // Main is still held in the real presentation path.
                }
                click(activity, R.string.pane_layout_split_right)
                assertNotNull(retiredDialog)
                assertFalse(retiredDialog!!.isShowing)
                assertTrue(oldFields.all { it.text.isEmpty() })
                assertTrue(oldSpinners.all { it.adapter == null })
                assertFalse(oldAdd!!.performClick())
                assertFalse(returned.get())
                shadowOf(android.os.Looper.getMainLooper()).idle()
                worker!!.join(5_000)
                assertTrue(returned.get())
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
                assertTrue(f.requests.none { it.method == "POST" })

                click(activity, R.string.pane_layout_refresh)
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_split_right) } }
                val replacement = f.repository.readContext(Scope(), "pane-1")!!
                assertFalse(f.repository.isCurrent(old))
                assertTrue(f.repository.isCurrent(replacement))
                click(activity, R.string.pane_layout_split_right)
                val currentDialog = ShadowDialog.getLatestDialog() as AlertDialog
                val currentRatio = descendants(currentDialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
                currentRatio.setText("0.7")
                posted.get().run() // A late old completion must not wipe the replacement modal.
                assertTrue(currentDialog.isShowing)
                assertEquals("0.7", currentRatio.text.toString())
                assertTrue(f.repository.isCurrent(replacement))
                assertTrue(oldFields.all { it.text.isEmpty() })
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
            } finally {
                activity.beforeDialogPublication = { _, _ -> }
                shadowOf(android.os.Looper.getMainLooper()).idle()
                worker?.join(5_000)
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun unshownExplicitSwapChoicesAreWipedWhenPublicationLosesAuthority() {
        Fixture("valid", withSwapTarget = true).use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            var worker: Thread? = null
            try {
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_swap_explicit) } }
                val old = f.repository.readContext(Scope(), "pane-1")!!
                val posted = java.util.concurrent.atomic.AtomicBoolean(false)
                val returned = java.util.concurrent.atomic.AtomicBoolean(false)
                val originalPoster = activity.retirementPoster
                activity.retirementPoster = { task -> originalPoster(task).also { posted.set(true) } }
                var choice: TextView? = null
                var retired: AlertDialog? = null
                activity.beforeDialogPublication = { dialog, copiedView ->
                    activity.beforeDialogPublication = { _, _ -> }
                    retired = dialog
                    assertNotNull(copiedView)
                    choice = descendants(copiedView!!).filterIsInstance<TextView>().single { it.text.contains("private-choice-label") }
                    assertTrue(choice!!.text.contains("pane-2"))
                    worker = Thread { f.repository.setLeadEpoch(old.key.leadEpoch + 1); returned.set(true) }.also { it.start() }
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    while (!posted.get() && System.nanoTime() < deadline) Thread.yield()
                    assertTrue(posted.get())
                    assertFalse(returned.get())
                }
                click(activity, R.string.pane_layout_swap_explicit)
                assertNotNull(retired)
                assertFalse(retired!!.isShowing)
                assertTrue(choice!!.text.isEmpty())
                assertFalse(choice!!.performClick())
                shadowOf(android.os.Looper.getMainLooper()).idle()
                worker!!.join(5_000)
                assertTrue(returned.get())
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
                assertTrue(f.requests.none { it.method == "POST" })
            } finally {
                activity.beforeDialogPublication = { _, _ -> }
                shadowOf(android.os.Looper.getMainLooper()).idle()
                worker?.join(5_000)
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun rejectedPostAndDisposalNeverPretendTheRetirementWipeWasAcknowledged() {
        for (mode in listOf("rejected", "dispose")) Fixture("valid").use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            var destroyed = false
            try {
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_split_right) } }
                click(activity, R.string.pane_layout_split_right)
                val form = ShadowDialog.getLatestDialog() as AlertDialog
                val ratio = descendants(form.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
                ratio.setText("0.6")
                controller.pause().stop()
                val queued = java.util.concurrent.atomic.AtomicReference<Runnable>()
                activity.retirementPoster = { task -> queued.set(task); mode != "rejected" }
                val returned = java.util.concurrent.atomic.AtomicBoolean(false)
                val old = f.repository.readContext(Scope(), "pane-1")!!
                val worker = Thread { f.repository.setLeadEpoch(old.key.leadEpoch + 1); returned.set(true) }
                worker.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (queued.get() == null && System.nanoTime() < deadline) Thread.yield()
                assertNotNull(queued.get())
                assertEquals(null, LayoutActivity::class.java.getDeclaredField("actor").apply { isAccessible = true }.get(activity))
                assertTrue(form.isShowing); assertEquals("0.6", ratio.text.toString())
                if (mode == "rejected") {
                    worker.join(5_000)
                    assertTrue(returned.get()) // Rejected posting is an explicit failure, not a false wipe ACK.
                    assertTrue(form.isShowing)
                    form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
                    controller.start().resume() // Real lifecycle retries pending wipe on main before a fresh actor.
                    assertTrue(ratio.text.isEmpty()); assertFalse(form.isShowing)
                    assertEquals(null, LayoutActivity::class.java.getDeclaredField("actor").apply { isAccessible = true }.get(activity))
                } else {
                    assertFalse(returned.get())
                    controller.destroy(); destroyed = true
                    worker.join(5_000)
                    assertTrue(returned.get()); assertTrue(ratio.text.isEmpty()); assertFalse(form.isShowing)
                }
                queued.get().run() // A late queued task cannot re-populate disposed/replaced custody.
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
                assertEquals(null, f.journal.unresolved(f.owner))
            } finally { if (!destroyed) controller.pause().stop().destroy() }
        }
    }

    @Test fun lateRetiredLayoutFailureCannotOverwriteTheReplacementActorView() {
        Fixture("valid", holdFirstLayout = true).use { f ->
            val controller = Robolectric.buildActivity(LayoutActivity::class.java, f.layoutIntent()).create().start().resume()
            val activity = controller.get()
            try {
                awaitUi { f.oldLayoutEntered.count == 0L }
                val old = f.repository.readContext(Scope(), "pane-1")!!
                f.repository.setLeadEpoch(old.key.leadEpoch + 1)
                click(activity, R.string.pane_layout_refresh)
                awaitUi { descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_move_new_workspace) } }
                f.releaseOldLayout.countDown()
                awaitUi { f.oldLayoutReturned.count == 0L }
                assertTrue(descendants(activity.window.decorView).any { it is TextView && it.text == activity.getString(R.string.pane_layout_move_new_workspace) })
                assertFalse(descendants(activity.window.decorView).any { it is TextView && it.text == "retired layout failure" })
                assertTrue(f.plans.isEmpty()); assertTrue(f.effects.isEmpty())
            } finally { f.releaseOldLayout.countDown(); controller.pause().stop().destroy() }
        }
    }

    private class Fixture(private val variant: String, private val authorized: Boolean = true,
        private val holdFirstLayout: Boolean = false, private val holdFirstPlan: Boolean = false,
        private val withSwapTarget: Boolean = false) : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        private val oldRepository = app.container.repository
        private val oldJournal = app.container.paneLayoutIntentStore
        private val repoField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        private val storeField = app.container.javaClass.getDeclaredField("paneLayoutIntentStore").apply { isAccessible = true }
        val oldLayoutEntered = java.util.concurrent.CountDownLatch(1)
        val releaseOldLayout = java.util.concurrent.CountDownLatch(1)
        val oldLayoutReturned = java.util.concurrent.CountDownLatch(1)
        private val layoutHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        val oldPlanEntered = java.util.concurrent.CountDownLatch(1)
        val releaseOldPlan = java.util.concurrent.CountDownLatch(1)
        val oldPlanReturned = java.util.concurrent.CountDownLatch(1)
        private val planHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val plans = CopyOnWriteArrayList<String>()
        val effects = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        private val json = Json { ignoreUnknownKeys = true }
        val mux = MuxConfigResponse("herdr", capabilities = listOf("paneLayout", "paneMove", "paneSplit", "paneSwap").associateWith { true })
        val pane = PaneSummary("pane-1", "space", "space", 1, "tab", "shell", AgentStatus.IDLE, "/fixture", false)
        private val geometry = LayoutSnapshot("space", "tab", false, LayoutRect(0, 0, 80, 24), "pane-1",
            if (withSwapTarget) listOf(LayoutPane("pane-1", true, LayoutRect(0, 0, 40, 24)), LayoutPane("pane-2", false, LayoutRect(40, 0, 40, 24)))
            else listOf(LayoutPane("pane-1", true, LayoutRect(0, 0, 80, 24))), emptyList())
        val journal: PaneLayoutIntentStore
        val repository: CollieRepository
        val owner: PaneLayoutIntentOwner
        init {
            app.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.url!!.encodedPath
                    if (holdFirstLayout && path.endsWith("/layout") && layoutHeld.compareAndSet(false, true)) {
                        oldLayoutEntered.countDown()
                        check(releaseOldLayout.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        oldLayoutReturned.countDown()
                        return MockResponse.Builder().code(503).body("retired layout failure").build()
                    }
                    val response: String = when {
                        path.endsWith("/config") -> json.encodeToString(BridgeConfigResponse(mux = mux, control = ControlCapabilityResponse(mapOf("controlOperations" to true))))
                        path.endsWith("/snapshot") -> json.encodeToString(SnapshotResponse("connected", agents = emptyList(),
                            shellPanes = if (withSwapTarget) listOf(pane, pane.copy(paneId = "pane-2", workspaceLabel = "private-choice-label")) else listOf(pane),
                            workspaces = emptyList(), tabs = emptyList(), device = DeviceAuthorization(true, "fixture", authorized), ts = 1))
                        path.endsWith("/folders") -> json.encodeToString(FoldersResponse(home = "/fixture", recent = emptyList(), favourites = emptyList()))
                        // PaneActivity's header reads launcher home during create. A missing healthy
                        // prerequisite would correctly freeze the actor before layout inspection.
                        path.endsWith("/launchers") -> json.encodeToString(LaunchersResponse(emptyList(), "/fixture"))
                        path.endsWith("/plan") -> {
                            plans.add(request.target!!)
                            if (holdFirstPlan && planHeld.compareAndSet(false, true)) {
                                oldPlanEntered.countDown()
                                check(releaseOldPlan.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                oldPlanReturned.countDown()
                            }
                            json.encodeToString(PaneLayoutPlanResponse(true, "plan", System.currentTimeMillis() + 30_000, "move",
                                buildJsonObject { put("verb", "move"); put("sourcePaneId", "pane-1"); put("destination", "new_workspace"); put("focusChanges", false); put("mayCloseSourceTab", true) }, PaneLayoutRead("layout", geometry)))
                        }
                        path.endsWith("/move") -> {
                            val id = json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject.getValue("requestId").jsonPrimitive.content
                            effects.add(id)
                            val move = PaneMoveResult("pane-move", "pane-new", "pane-1", if (variant == "wrong-tab") "wrong" else "tab", if (variant == "wrong-space") "wrong" else "space")
                            json.encodeToString(PaneLayoutOperationResponse(true, operation = ControlOperation(1, id, "succeeded", "paneMove", "a".repeat(64), 1, 2, result = move), move = move))
                        }
                        path.endsWith("/edges") -> json.encodeToString(PaneLayoutReadResponse(true, PaneLayoutRead("edges", geometry, "pane-1", left = false, right = false, up = false, down = false)))
                        path.endsWith("/neighbor") -> json.encodeToString(PaneLayoutReadResponse(true, PaneLayoutRead("neighbor", geometry, "pane-1", request.url!!.queryParameter("direction"), null)))
                        path.endsWith("/layout") -> json.encodeToString(PaneLayoutReadResponse(true, PaneLayoutRead("layout", geometry)))
                        path.contains("/control/operations/") -> json.encodeToString(ControlOperationResponse(true, ControlOperation(1, path.substringAfterLast('/'), "unknown", "paneMove", "a".repeat(64), 1, 2)))
                        else -> return MockResponse.Builder().code(404).build()
                    }
                    return MockResponse.Builder().setHeader("Content-Type", "application/json").body(response).build()
                }
            }
            server.start()
            val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            val initialConnection = Connection(CollieOrigin(server.url("/nested/").toString()), "fixture", "fixture-token")
            val connectionStore = object : ConnectionStore {
                override val connection = MutableStateFlow<Connection?>(initialConnection)
                override fun save(connection: Connection) { this.connection.value = connection }
                override fun clear() { this.connection.value = null }
            }
            repository = CollieRepository(CollieApiClient(OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build(), json), connectionStore, OriginValidator())
            repository.selectSubject(Scope(), "pane-1")
            runBlocking { assertTrue(repository.snapshot() is ApiResult.Success) }
            owner = PaneLayoutIntentOwner.from(repository.readContext(Scope(), "pane-1")!!, "pane-1")
            val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 17 }, "AES")
            val cipher = object : SecretCipher {
                override fun encrypt(plaintext: ByteArray): CipherEnvelope {
                    val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                    return CipherEnvelope(aes.iv, aes.doFinal(plaintext))
                }
                override fun decrypt(envelope: CipherEnvelope): ByteArray {
                    val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, envelope.iv))
                    return aes.doFinal(envelope.ciphertext)
                }
            }
            journal = PaneLayoutIntentStore(app, cipher, json)
            repoField.set(app.container, repository); storeField.set(app.container, journal)
        }
        fun layoutIntent() = Intent(app, LayoutActivity::class.java).putExtra(LayoutActivity.EXTRA_PANE_ID, "pane-1")
        override fun close() {
            repoField.set(app.container, oldRepository); storeField.set(app.container, oldJournal)
            releaseOldLayout.countDown(); releaseOldPlan.countDown()
            server.close()
            app.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun descendants(view: View): List<View> = buildList { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i))) }
    private fun click(activity: LayoutActivity, stringId: Int) = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(stringId) }.performClick()
    private fun awaitUi(diagnostic: () -> String = { "" }, check: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!check() && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("bounded HTTPS/Activity completion: ${if (check()) "" else diagnostic()}", check())
    }
}
