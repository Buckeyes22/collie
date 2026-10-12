package com.lateapex.collie.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AgentAutomationCustody
import com.lateapex.collie.data.AgentFixtureCipher
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class AgentAutomationActivityTest {
    private val app: CollieApplication = ApplicationProvider.getApplicationContext()
    private val address = PaneAddress(Scope(), "w1:p1")
    private fun info(label: String) = AgentAutomationInfo("w1:p1", "terminal-$label", "w1", "w1:t1", "runtime", "codex", "a".repeat(64), "b".repeat(64), "named", "idle", true, true, false, "0", "0")
    private fun drainUntil(label: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(label, predicate())
    }
    @Test fun actualNativePromptKeepsUnknownAcrossRecreationWithoutReplayAndStoppedPrivateCopiesAreWiped() {
        val oldRepository = app.container.repository; val oldCustody = app.container.agentAutomationCustody
        val store = EncryptedConnectionStore(app, AgentFixtureCipher(), Json, app.container.originValidator, app.getSharedPreferences("agent-ui-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val custody = AgentAutomationCustody(app, AgentFixtureCipher(), preferencesOverride = app.getSharedPreferences("agent-ui-actions-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val posts = AtomicInteger(); val latestPublished = AtomicReference<String?>(); val initialTail = store.observeCommitted { latestPublished.set(it.incarnationId) }
        val original = Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token"); store.save(original)
        drainUntil("initial publication before Activity registration") { latestPublished.get() == store.captureCommitted().incarnationId }; initialTail.close()
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentAutomation" to true, "agentWaits" to true)), control = ControlCapabilityResponse(mapOf("controlOperations" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "fixture", true), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun namedAgentControl(connection: Connection, address: PaneAddress) = ApiResult.Success(AgentAutomationResponse(true, 1, info(connection.label), "c".repeat(64)), 200)
            override suspend fun namedAgentRead(connection: Connection, address: PaneAddress, source: String, lines: Int) = ApiResult.Success(NamedAgentReadResponse(true, 1, address.paneId, "terminal-${connection.label}", "> ready", false), 200)
            override suspend fun namedAgentAction(connection: Connection, address: PaneAddress, id: String, witness: String, action: NamedAgentAction, expectedText: String?): ApiResult<AgentActionResponse> { assertEquals("> ready", expectedText); assertTrue(action is NamedAgentAction.Prompt); posts.incrementAndGet(); return ApiResult.Failure(ApiFailure.Network("owned lost response")) }
        }
        val repository = CollieRepository(api, store, app.container.originValidator); val retirement = repository.onReadContextRetired(custody::retire)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
        app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, custody)
        var controller = Robolectric.buildActivity(AgentControlActivity::class.java, AgentControlActivity.intent(app, address)).setup()
        val tail = store.observeCommitted { latestPublished.set(it.incarnationId) }
        try {
            drainUntil("actual native prompt enabled") { controller.get().findViewById<Button>(R.id.agent_control_prompt)?.isEnabled == true }
            val draft = controller.get().findViewById<EditText>(R.id.agent_control_prompt_text); draft.setText("private original draft"); assertFalse(draft.isSaveEnabled)
            controller.get().findViewById<Button>(R.id.agent_control_prompt).performClick()
            drainUntil("single delivered unknown shown") { controller.get().findViewById<Button>(R.id.agent_control_reconcile) != null }
            assertEquals(1, posts.get()); val saved = Bundle(); controller.saveInstanceState(saved).pause().stop(); assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text)); assertFalse(saved.toString().contains("private original draft")); controller.destroy()
            controller = Robolectric.buildActivity(AgentControlActivity::class.java, AgentControlActivity.intent(app, address)).create(saved).start().resume().visible()
            drainUntil("retained opaque unknown after actual recreation") { controller.get().findViewById<Button>(R.id.agent_control_reconcile) != null }
            assertEquals(1, posts.get()); assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text))
            assertEquals(1, posts.get())
        } finally {
            // Drain the actual committed publication before removing its Activity ACK listeners.
            drainUntil("committed publication finished") { latestPublished.get() == store.captureCommitted().incarnationId }
            controller.pause().stop().destroy(); retirement.close(); tail.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository)
            app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, oldCustody)
        }
    }
    @Test fun retainedOldNativeReadCannotPublishAfterIdleCommittedReplacementAndFreshRefreshRemainsPositive() {
        val oldRepository = app.container.repository; val oldCustody = app.container.agentAutomationCustody
        val store = EncryptedConnectionStore(app, AgentFixtureCipher(), Json, app.container.originValidator, app.getSharedPreferences("agent-ui-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE)); val custody = AgentAutomationCustody(app, AgentFixtureCipher(), preferencesOverride = app.getSharedPreferences("agent-ui-actions-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val latestPublished = AtomicReference<String?>(); val initialTail = store.observeCommitted { latestPublished.set(it.incarnationId) }; store.save(Connection(CollieOrigin("https://agent.fixture/"), "A", "same-fixture-token"))
        drainUntil("initial publication before any Activity ACK") { latestPublished.get() == store.captureCommitted().incarnationId }; initialTail.close()
        val old = CompletableDeferred<ApiResult<AgentAutomationResponse>>(); val entered = AtomicInteger(); val currentReads = AtomicInteger()
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentAutomation" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "fixture", false), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun namedAgentControl(connection: Connection, address: PaneAddress): ApiResult<AgentAutomationResponse> = if (connection.label == "A") { entered.incrementAndGet(); old.await() } else { currentReads.incrementAndGet(); ApiResult.Success(AgentAutomationResponse(true, 1, info("B"), "d".repeat(64)), 200) }
            override suspend fun namedAgentRead(connection: Connection, address: PaneAddress, source: String, lines: Int) = ApiResult.Success(NamedAgentReadResponse(true, 1, address.paneId, "terminal-B", "fresh current only", false), 200)
        }
        val repository = CollieRepository(api, store, app.container.originValidator); val retirement = repository.onReadContextRetired(custody::retire)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, custody)
        val controller = Robolectric.buildActivity(AgentControlActivity::class.java, AgentControlActivity.intent(app, address)).setup()
        // Last listener is registered before clear/save can snapshot the observer iterator.
        val tail = store.observeCommitted { latestPublished.set(it.incarnationId) }
        try {
            drainUntil("held original actual read") { entered.get() == 1 }
            store.clear(); store.save(Connection(CollieOrigin("https://agent.fixture/"), "B", "same-fixture-token"))
            drainUntil("committed observer finished without another repository read") { latestPublished.get() == store.captureCommitted().incarnationId }
            old.complete(ApiResult.Success(AgentAutomationResponse(true, 1, info("A"), "c".repeat(64)), 200)); shadowOf(Looper.getMainLooper()).idle()
            assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text)); assertEquals(0, currentReads.get())
            // Retirement clears private copies. A deliberate refresh is the only fresh admission.
            drainUntil("actual deliberate refresh visible") { controller.get().findViewById<Button>(R.id.agent_control_refresh) != null }
            controller.get().findViewById<Button>(R.id.agent_control_refresh).performClick()
            drainUntil("fresh current read positive") { currentReads.get() == 1 && controller.get().findViewById<Button>(R.id.agent_control_explain) != null }
            assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text)) // independent write factor remains false
        } finally {
            old.cancel(); drainUntil("committed tail completed") { latestPublished.get() == store.captureCommitted().incarnationId }; controller.pause().stop().destroy(); retirement.close(); tail.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, oldCustody)
        }
    }
    @Test fun actualWaitsRotationPollsOnlyTheOriginalOpaqueIdAndCancelDoesNotDeliverOrRestartWork() {
        val oldRepository = app.container.repository; val oldCustody = app.container.agentAutomationCustody
        val store = EncryptedConnectionStore(app, AgentFixtureCipher(), Json, app.container.originValidator, app.getSharedPreferences("agent-waits-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE)); val custody = AgentAutomationCustody(app, AgentFixtureCipher(), preferencesOverride = app.getSharedPreferences("agent-waits-actions-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val initialDone = AtomicReference<String?>(); val initialTail = store.observeCommitted { initialDone.set(it.incarnationId) }; store.save(Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token")); drainUntil("initial committed wait connection") { initialDone.get() == store.captureCommitted().incarnationId }; initialTail.close()
        val starts = AtomicInteger(); val polls = AtomicInteger(); val cancellations = AtomicInteger(); var originalId: String? = null
        val held = CompletableDeferred<ApiResult<AgentWaitResponse>>()
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentAutomation" to true, "agentWaits" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "fixture", false), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun namedAgentControl(connection: Connection, address: PaneAddress) = ApiResult.Success(AgentAutomationResponse(true, 1, info("A"), "c".repeat(64)), 200)
            override suspend fun namedAgentRead(connection: Connection, address: PaneAddress, source: String, lines: Int) = ApiResult.Success(NamedAgentReadResponse(true, 1, address.paneId, "terminal-A", "owned text", false), 200)
            override suspend fun startAgentWait(connection: Connection, address: PaneAddress, id: String, witness: String, condition: NamedAgentWait, timeoutMs: Int): ApiResult<AgentWaitResponse> {
                starts.incrementAndGet(); originalId = id; assertTrue(condition is NamedAgentWait.Lifecycle); assertEquals("c".repeat(64), witness)
                return ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, id, address.paneId, "pending", null, System.currentTimeMillis() + timeoutMs, null)), 202)
            }
            override suspend fun agentWait(connection: Connection, address: PaneAddress, id: String, cancel: Boolean): ApiResult<AgentWaitResponse> {
                assertEquals(originalId, id)
                if (cancel) { cancellations.incrementAndGet(); return ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, id, address.paneId, "cancelled", null, System.currentTimeMillis() + 1000, null)), 200) }
                polls.incrementAndGet(); return held.await()
            }
            override suspend fun namedAgentAction(connection: Connection, address: PaneAddress, id: String, witness: String, action: NamedAgentAction, expectedText: String?): ApiResult<AgentActionResponse> = throw AssertionError("observation never dispatches delivered agent work")
        }
        val repository = CollieRepository(api, store, app.container.originValidator); val retirement = repository.onReadContextRetired(custody::retire)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, custody)
        var controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).setup()
        try {
            drainUntil("actual native waits form") { controller.get().findViewById<Button>(R.id.agent_waits_start) != null }
            controller.get().findViewById<Button>(R.id.agent_waits_start).performClick(); drainUntil("original wait GET entered") { polls.get() == 1 }; assertEquals(1, starts.get())
            val saved = Bundle(); controller.saveInstanceState(saved).pause().stop().destroy(); assertFalse(saved.toString().contains("owned text")); assertNotNull(saved.getString("agent.wait.id"))
            controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).create(saved).start().resume().visible()
            drainUntil("same saved observer polled without restart") { polls.get() == 2 && controller.get().findViewById<Button>(R.id.agent_waits_cancel) != null }; assertEquals(1, starts.get())
            controller.get().findViewById<Button>(R.id.agent_waits_cancel).performClick(); drainUntil("actual observer cancellation") { cancellations.get() == 1 }; assertEquals(1, starts.get())
        } finally {
            held.cancel(); controller.pause().stop().destroy(); retirement.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, oldCustody)
        }
    }

    @Test fun actualMovedWaitSurvivesOriginalSlotLossAndRotationWithoutDestinationActionsOrFreshWait() {
        val oldRepository = app.container.repository; val oldCustody = app.container.agentAutomationCustody
        val store = EncryptedConnectionStore(app, AgentFixtureCipher(), Json, app.container.originValidator, app.getSharedPreferences("agent-move-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val custody = AgentAutomationCustody(app, AgentFixtureCipher(), preferencesOverride = app.getSharedPreferences("agent-move-custody-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val publication = AtomicReference<String?>(); val initialTail = store.observeCommitted { publication.set(it.incarnationId) }
        store.save(Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token")); drainUntil("original publication") { publication.get() == store.captureCommitted().incarnationId }; initialTail.close()
        val starts = AtomicInteger(); val polls = AtomicInteger(); val effects = AtomicInteger(); var moved = false; var originalId: String? = null
        val held = CompletableDeferred<ApiResult<AgentWaitResponse>>()
        val original = info("A"); val destination = original.copy(paneId = "w1:p2", tabId = "w1:t2")
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentAutomation" to true, "agentWaits" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "fixture", false), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun namedAgentControl(connection: Connection, address: PaneAddress): ApiResult<AgentAutomationResponse> {
                assertEquals("w1:p1", address.paneId)
                return if (moved) ApiResult.Failure(ApiFailure.Http(404, "owned old slot absent", null)) else ApiResult.Success(AgentAutomationResponse(true, 1, original, "c".repeat(64)), 200)
            }
            override suspend fun namedAgentRead(connection: Connection, address: PaneAddress, source: String, lines: Int) = ApiResult.Success(NamedAgentReadResponse(true, 1, address.paneId, original.terminalId, "owned original text", false), 200)
            override suspend fun startAgentWait(connection: Connection, address: PaneAddress, id: String, witness: String, condition: NamedAgentWait, timeoutMs: Int): ApiResult<AgentWaitResponse> {
                assertEquals(NamedAgentWait.Event("pane-moved", "w1:p1"), condition); assertEquals("c".repeat(64), witness); starts.incrementAndGet(); originalId = id
                return ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, id, address.paneId, "pending", null, System.currentTimeMillis() + timeoutMs, null)), 202)
            }
            override suspend fun agentWait(connection: Connection, address: PaneAddress, id: String, cancel: Boolean): ApiResult<AgentWaitResponse> {
                assertEquals(originalId, id); assertEquals("w1:p1", address.paneId); assertFalse(cancel); polls.incrementAndGet(); return held.await()
            }
            override suspend fun namedAgentAction(connection: Connection, address: PaneAddress, id: String, witness: String, action: NamedAgentAction, expectedText: String?): ApiResult<AgentActionResponse> { effects.incrementAndGet(); throw AssertionError("moved metadata cannot grant an action") }
        }
        val repository = CollieRepository(api, store, app.container.originValidator); val retirement = repository.onReadContextRetired(custody::retire)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, custody)
        var controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).setup()
        val tail = store.observeCommitted { publication.set(it.incarnationId) }
        try {
            drainUntil("actual original observer form") { controller.get().findViewById<Button>(R.id.agent_waits_start) != null }
            controller.get().findViewById<android.widget.Spinner>(R.id.agent_waits_mode).setSelection(2)
            controller.get().findViewById<android.widget.Spinner>(R.id.agent_waits_event).setSelection(AGENT_WAIT_EVENTS.indexOf("pane-moved"))
            val retainedStart = controller.get().findViewById<Button>(R.id.agent_waits_start); retainedStart.performClick()
            drainUntil("original opaque poll entered") { polls.get() == 1 }; assertEquals(1, starts.get())
            moved = true; val saved = Bundle(); controller.saveInstanceState(saved).pause().stop().destroy()
            assertEquals(originalId, saved.getString("agent.wait.id")); assertFalse(saved.toString().contains("owned original text")); assertFalse(saved.toString().contains("fixture-token"))
            controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).create(saved).start().resume().visible()
            drainUntil("same original opaque ID after old slot loss") { polls.get() == 2 }; assertEquals(1, starts.get())
            held.complete(ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, originalId!!, address.paneId, "matched", null, System.currentTimeMillis() + 1000,
                AgentWaitResult(kind = "event", event = "pane-moved", targetId = "w1:p2", status = null, move = AgentObservedMove("w1:p1", "w1", "w1:t1", destination, "c".repeat(64))))), 200))
            drainUntil("actual neutral moved result drawn") {
                val root = controller.get().window.decorView
                fun contains(view: android.view.View): Boolean = (view is android.widget.TextView && view.text.toString().contains("Observed event: pane-moved · w1:p2")) || (view is android.view.ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
                contains(root)
            }
            assertNull(controller.get().findViewById<Button>(R.id.agent_waits_start)); assertNull(controller.get().findViewById<Button>(R.id.agent_control_prompt)); assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text))
            retainedStart.performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, starts.get()); assertEquals(0, effects.get())
            assertNull(org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity)
        } finally {
            held.cancel(); drainUntil("actual publication tail finished") { publication.get() == store.captureCommitted().incarnationId }; controller.pause().stop().destroy(); retirement.close(); tail.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, oldCustody)
        }
    }
    @Test fun actualClosedAndDetectedWaitsKeepOriginalOpaqueIdAcrossRotationWithoutGrantingNewActions() {
        for (event in listOf("pane-closed", "pane-exited", "tab-closed", "workspace-closed", "agent-detected")) {
        val target = if (event.startsWith("workspace-")) "w1" else if (event.startsWith("tab-")) "w1:t1" else "w1:p1"
        val oldRepository = app.container.repository; val oldCustody = app.container.agentAutomationCustody
        val store = EncryptedConnectionStore(app, AgentFixtureCipher(), Json, app.container.originValidator, app.getSharedPreferences("agent-move-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val custody = AgentAutomationCustody(app, AgentFixtureCipher(), preferencesOverride = app.getSharedPreferences("agent-move-custody-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        val publication = AtomicReference<String?>(); val initialTail = store.observeCommitted { publication.set(it.incarnationId) }
        store.save(Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token")); drainUntil("original publication") { publication.get() == store.captureCommitted().incarnationId }; initialTail.close()
        val starts = AtomicInteger(); val polls = AtomicInteger(); val effects = AtomicInteger(); var moved = false; var originalId: String? = null
        val held = CompletableDeferred<ApiResult<AgentWaitResponse>>()
        val original = info("A"); val destination = original.copy(agent = "claude", sessionIdentity = "d".repeat(64))
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentAutomation" to true, "agentWaits" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "fixture", false), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun namedAgentControl(connection: Connection, address: PaneAddress): ApiResult<AgentAutomationResponse> {
                assertEquals("w1:p1", address.paneId)
                return if (moved) ApiResult.Failure(ApiFailure.Http(404, "owned old slot absent", null)) else ApiResult.Success(AgentAutomationResponse(true, 1, original, "c".repeat(64)), 200)
            }
            override suspend fun namedAgentRead(connection: Connection, address: PaneAddress, source: String, lines: Int) = ApiResult.Success(NamedAgentReadResponse(true, 1, address.paneId, original.terminalId, "owned original text", false), 200)
            override suspend fun startAgentWait(connection: Connection, address: PaneAddress, id: String, witness: String, condition: NamedAgentWait, timeoutMs: Int): ApiResult<AgentWaitResponse> {
                assertEquals(NamedAgentWait.Event(event, target), condition); assertEquals("c".repeat(64), witness); starts.incrementAndGet(); originalId = id
                return ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, id, address.paneId, "pending", null, System.currentTimeMillis() + timeoutMs, null)), 202)
            }
            override suspend fun agentWait(connection: Connection, address: PaneAddress, id: String, cancel: Boolean): ApiResult<AgentWaitResponse> {
                assertEquals(originalId, id); assertEquals("w1:p1", address.paneId); assertFalse(cancel); polls.incrementAndGet(); return held.await()
            }
            override suspend fun namedAgentAction(connection: Connection, address: PaneAddress, id: String, witness: String, action: NamedAgentAction, expectedText: String?): ApiResult<AgentActionResponse> { effects.incrementAndGet(); throw AssertionError("moved metadata cannot grant an action") }
        }
        val repository = CollieRepository(api, store, app.container.originValidator); val retirement = repository.onReadContextRetired(custody::retire)
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, custody)
        var controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).setup()
        val tail = store.observeCommitted { publication.set(it.incarnationId) }
        try {
            drainUntil("actual original observer form") { controller.get().findViewById<Button>(R.id.agent_waits_start) != null }
            controller.get().findViewById<android.widget.Spinner>(R.id.agent_waits_mode).setSelection(2)
            controller.get().findViewById<android.widget.Spinner>(R.id.agent_waits_event).setSelection(AGENT_WAIT_EVENTS.indexOf(event))
            val retainedStart = controller.get().findViewById<Button>(R.id.agent_waits_start); retainedStart.performClick()
            drainUntil("original opaque poll entered") { polls.get() == 1 }; assertEquals(1, starts.get())
            moved = true; val saved = Bundle(); controller.saveInstanceState(saved).pause().stop().destroy()
            assertEquals(originalId, saved.getString("agent.wait.id")); assertFalse(saved.toString().contains("owned original text")); assertFalse(saved.toString().contains("fixture-token"))
            controller = Robolectric.buildActivity(WaitsActivity::class.java, WaitsActivity.intent(app, address)).create(saved).start().resume().visible()
            drainUntil("same original opaque ID after old slot loss") { polls.get() == 2 }; assertEquals(1, starts.get())
            held.complete(ApiResult.Success(AgentWaitResponse(true, AgentWaitRecord(1, originalId!!, address.paneId, "matched", null, System.currentTimeMillis() + 1000,
                AgentWaitResult(kind = "event", event = event, targetId = target, status = null, transition = AgentObservedTransition(event, "w1:p1", "w1", "w1:t1", original.terminalId, if (event == "agent-detected") destination else null, "c".repeat(64))))), 200))
            drainUntil("actual neutral moved result drawn") {
                val root = controller.get().window.decorView
                fun contains(view: android.view.View): Boolean = (view is android.widget.TextView && view.text.toString().contains("Observed event: $event · $target")) || (view is android.view.ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
                contains(root)
            }
            assertNull(controller.get().findViewById<Button>(R.id.agent_waits_start)); assertNull(controller.get().findViewById<Button>(R.id.agent_control_prompt)); assertNull(controller.get().findViewById<EditText>(R.id.agent_control_prompt_text))
            retainedStart.performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, starts.get()); assertEquals(0, effects.get())
            assertNull(org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity)
        } finally {
            held.cancel(); drainUntil("actual publication tail finished") { publication.get() == store.captureCommitted().incarnationId }; controller.pause().stop().destroy(); retirement.close(); tail.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository); app.container.javaClass.getDeclaredField("agentAutomationCustody").apply { isAccessible = true }.set(app.container, oldCustody)
        }
        }
    }
}
