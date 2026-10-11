package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.widget.TextView
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.FoldersResponse
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorktreeListResponse
import com.lateapex.collie.network.WorktreeOpenResponse
import com.lateapex.collie.network.AgentIdentityResponse
import com.lateapex.collie.network.AgentIdentitySession
import com.lateapex.collie.network.AgentStateAuthority
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.MuxConfigResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AgentIdentityActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app: CollieApplication get() = context.applicationContext as CollieApplication

    @Test
    fun connectionRetirementListenersRunOutsideTheRepositoryMonitorAndReturnFreshContext() {
        val original = app.container.repository
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(
                Connection(CollieOrigin("https://identity.fixture/"), "fixture", "old-token"),
            )
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val repository = install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
            ApiResult.Failure(ApiFailure.Network("unused")),
        ) {}, store)
        val oldContext = repository.readContext(Scope(), "w1:p1")!!
        var monitorHeld = true
        var callbacks = 0
        val registration = repository.onReadContextInvalidated {
            callbacks++
            monitorHeld = Thread.holdsLock(repository)
        }
        try {
            store.save(Connection(CollieOrigin("https://identity.fixture/"), "fixture", "new-token"))
            val context = repository.readContext(Scope(), "w1:p1")
            assertEquals(1, callbacks)
            assertFalse(repository.isCurrent(oldContext))
            assertFalse(monitorHeld)
            assertNotNull(context)
            assertTrue(repository.isCurrent(context!!))
        } finally {
            registration.close()
            restoreRepository(original)
        }
    }

    @Test
    fun atomicCompletionRejectsTheExactOldLoadingAfterPublicRetirementAndAdmitsOnlyFreshLoading() {
        val original = app.container.repository
        val response = CompletableDeferred<ApiResult<AgentIdentityResponse>>()
        val repository = install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200,
            )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress) = response.await()
        })
        val vm = AgentIdentityViewModel(app, PaneAddress(Scope(), "w1:p1"), repository)
        val contextField = AgentIdentityViewModel::class.java.getDeclaredField("context").apply { isAccessible = true }
        val commit = AgentIdentityViewModel::class.java.getDeclaredMethod(
            "commitIfCurrent", com.lateapex.collie.data.ScopedReadContext::class.java,
            AgentIdentityUiState::class.java, AgentIdentityUiState::class.java,
        ).apply { isAccessible = true }
        try {
            vm.start()
            shadowOf(Looper.getMainLooper()).idle()
            val oldLoading = vm.state.value
            assertTrue(oldLoading.loading)
            val oldContext = contextField.get(vm)
            assertNotNull(oldContext)
            val identity = AgentIdentityResponse(true, "w1:p1", "private-old", AgentStatus.WORKING,
                null, AgentStateAuthority(false, null), null, null, null, null)
            val oldCompletion = AgentIdentityUiState(oldLoading.presentationGeneration, identity = identity)
            repository.selectSubject(Scope(), "replacement-pane")
            val retired = vm.state.value
            assertEquals(AgentIdentityProblem.RETIRED, retired.problem)
            assertEquals(null, retired.identity)
            assertEquals(false, commit.invoke(vm, oldContext, oldLoading, oldCompletion))
            assertTrue(vm.state.value === retired)
            assertTrue(vm.isCurrentPresentation(retired))
            vm.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            val freshLoading = vm.state.value
            assertTrue(freshLoading.loading)
            assertTrue(freshLoading.presentationGeneration > oldLoading.presentationGeneration)
            val freshContext = contextField.get(vm)
            val freshCompletion = AgentIdentityUiState(freshLoading.presentationGeneration,
                identity = identity.copy(agent = "fresh-agent"))
            assertEquals(true, commit.invoke(vm, freshContext, freshLoading, freshCompletion))
            assertTrue(vm.state.value === freshCompletion)
            assertTrue(vm.isCurrentPresentation(freshCompletion))
            // Even after a fresh success, an old admitted completion cannot overwrite it.
            assertEquals(false, commit.invoke(vm, oldContext, oldLoading, oldCompletion))
            assertEquals("fresh-agent", vm.state.value.identity?.agent)
        } finally {
            vm.screenStopped()
            restoreRepository(original)
        }
    }

    @Test
    fun displaysOwnerReportedFactsAndExplicitUnknownAuthorityWithoutFocusOrMutationControls() {
        val original = app.container.repository
        var reads = 0
        install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
            ApiResult.Success(AgentIdentityResponse(
                ok = true,
                paneId = "w1:p1",
                agent = "codex",
                status = AgentStatus.WORKING,
                session = AgentIdentitySession("codex", "codex", "id", "session-42"),
                stateAuthority = AgentStateAuthority(false, null),
                interactiveReady = true,
                launchPending = false,
                stateChangeSeq = "9",
                completionSeq = "7",
            ), 200),
        ) { reads++ })
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val visible = collectText(activity.window.decorView)
            assertTrue(visible.contains("State authority: not exposed"))
            assertTrue(visible.contains("Session reference: session-42"))
            assertTrue(visible.contains("State change sequence: 9"))
            assertTrue(visible.contains(activity.getString(com.lateapex.collie.R.string.agent_identity_mutations_unavailable)))
            assertEquals(1, reads)
            assertFalse(visible.contains("Resume"))
            assertFalse(visible.contains("Release"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun knownStatusWithUnknownOptionalFactsRemainsReachableInTheActualActivity() {
        val original = app.container.repository
        install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
            ApiResult.Success(AgentIdentityResponse(true, "w1:p1", "codex", AgentStatus.WORKING,
                null, AgentStateAuthority(false, null), null, null, null, null), 200),
        ) {})
        val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val visible = collectText(activity.window.decorView)
            assertTrue(visible.contains("codex"))
            assertTrue(visible.contains("working"))
            assertTrue(visible.contains(activity.getString(com.lateapex.collie.R.string.agent_identity_unknown)))
            assertTrue(visible.contains("State authority: not exposed"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun unsupportedRuntimeExplainsRefusalWithoutRequestingAgentDetails() {
        val original = app.container.repository
        var reads = 0
        install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to false))),
            ApiResult.Failure(ApiFailure.Protocol("must not be requested")),
        ) { reads++ })
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val visible = collectText(activity.window.decorView)
            assertTrue(visible.contains(activity.getString(com.lateapex.collie.R.string.agent_identity_unsupported)))
            assertEquals(0, reads)
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun retiredCapturedReadCannotRenderAfterAReplacementSubjectIsSelected() {
        val original = app.container.repository
        val response = CompletableDeferred<ApiResult<AgentIdentityResponse>>()
        var reads = 0
        install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200,
            )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress): ApiResult<AgentIdentityResponse> {
                reads++
                if (reads == 1) return response.await()
                return ApiResult.Success(AgentIdentityResponse(
                    true, address.paneId, "fresh-agent", AgentStatus.IDLE,
                    AgentIdentitySession("codex", "codex", "id", "fresh-session"),
                    AgentStateAuthority(false, null), true, false, "10", null,
                ), 200)
            }
        })
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertEquals(1, reads)
            app.container.repository.selectSubject(Scope(), "replacement-pane")
            response.complete(ApiResult.Success(AgentIdentityResponse(
                ok = true, paneId = "w1:p1", agent = "private-old-agent", status = AgentStatus.WORKING,
                session = AgentIdentitySession("codex", "codex", "id", "private-old-session"),
                stateAuthority = AgentStateAuthority(false, null), interactiveReady = true, launchPending = false,
                stateChangeSeq = "9", completionSeq = null,
            ), 200))
            shadowOf(activity.mainLooper).idle()
            assertFalse(collectText(activity.window.decorView).contains("private-old-session"))
            assertTrue(collectText(activity.window.decorView).contains(activity.getString(com.lateapex.collie.R.string.agent_identity_retired)))
            val refresh = findButton(activity.window.decorView)!!
            refresh.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals(2, reads)
            assertTrue(collectText(activity.window.decorView).contains("fresh-session"))
            assertFalse(collectText(activity.window.decorView).contains("private-old-session"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun visibleIdentityCanRetryTransientFailureAndReachASecondCurrentRead() {
        val original = app.container.repository
        var reads = 0
        install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200,
            )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress): ApiResult<AgentIdentityResponse> {
                reads++
                return if (reads == 1) ApiResult.Failure(ApiFailure.Network("offline")) else ApiResult.Success(
                    AgentIdentityResponse(true, address.paneId, "codex", AgentStatus.WORKING,
                        AgentIdentitySession("codex", "codex", "id", "retry-session"), AgentStateAuthority(false, null),
                        true, false, "4", "3"), 200,
                )
            }
        })
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertEquals(1, reads)
            assertTrue(collectText(activity.window.decorView).contains(activity.getString(com.lateapex.collie.R.string.agent_identity_unavailable)))
            val refresh = findButton(activity.window.decorView)!!
            assertTrue(refresh.isEnabled)
            refresh.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals(2, reads)
            assertTrue(collectText(activity.window.decorView).contains("retry-session"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun stoppedScreenClearsPrivateTextAndCannotRenderItsLateReply() {
        val original = app.container.repository
        val late = CompletableDeferred<ApiResult<AgentIdentityResponse>>()
        var reads = 0
        install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200,
            )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress): ApiResult<AgentIdentityResponse> {
                reads++
                return if (reads == 1) ApiResult.Success(
                    AgentIdentityResponse(true, address.paneId, "codex", AgentStatus.IDLE,
                        AgentIdentitySession("codex", "codex", "id", "visible-session"), AgentStateAuthority(false, null),
                        true, false, "6", null), 200,
                ) else late.await()
            }
        })
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(collectText(activity.window.decorView).contains("visible-session"))
            findButton(activity.window.decorView)!!.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals(2, reads)
            controller.pause().stop()
            assertFalse(collectText(activity.window.decorView).contains("visible-session"))
            late.complete(ApiResult.Success(
                AgentIdentityResponse(true, "w1:p1", "codex", AgentStatus.WORKING,
                    AgentIdentitySession("codex", "codex", "id", "late-private-session"), AgentStateAuthority(false, null),
                    true, false, "7", null), 200,
            ))
            shadowOf(activity.mainLooper).idle()
            assertFalse(collectText(activity.window.decorView).contains("late-private-session"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun authRefusalIsDistinctAndDoesNotDeleteThePairedCredential() {
        val original = app.container.repository
        var reads = 0
        install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200,
            )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress): ApiResult<AgentIdentityResponse> {
                reads++
                return if (reads == 1) ApiResult.Failure(ApiFailure.Http(403, "read refused", "auth.forbidden")) else ApiResult.Success(
                    AgentIdentityResponse(true, address.paneId, "codex", AgentStatus.IDLE,
                        AgentIdentitySession("codex", "codex", "id", "recovered-after-auth"), AgentStateAuthority(false, null),
                        true, false, "5", null), 200,
                )
            }
        })
        val configuredToken = app.container.repository.connection.value?.token
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val visible = collectText(activity.window.decorView)
            assertTrue(visible.contains(activity.getString(com.lateapex.collie.R.string.agent_identity_auth_refused)))
            val retry = findButton(activity.window.decorView)!!
            assertTrue(retry.isEnabled)
            retry.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals(2, reads)
            assertTrue(collectText(activity.window.decorView).contains("recovered-after-auth"))
            assertEquals(configuredToken, app.container.repository.connection.value?.token)
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun refreshDuringAnAcknowledgedButUnfinishedSelectionCannotAdmitOldIdentity() {
        val original = app.container.repository
        var reads = 0
        val repository = install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
            ApiResult.Success(AgentIdentityResponse(true, "w1:p1", "codex", AgentStatus.WORKING,
                AgentIdentitySession("codex", "codex", "id", "current-private-session"),
                AgentStateAuthority(false, null), true, false, "9", null), 200),
        ) { reads++ })
        val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")))
            .create().start().resume().visible()
        val afterAck = CountDownLatch(1)
        val release = CountDownLatch(1)
        var armed = true
        var reentrantActor: com.lateapex.collie.data.ScopedReadContext? = null
        var reentrantScope: Scope? = null
        // Registered last, so the actual Activity main-thread wipe has ACKed before this pauses.
        val registration = repository.onAuthorityInvalidated {
            if (armed) {
                repository.selectSubject(Scope(host = "reentrant-host"), "reentrant-pane")
                reentrantScope = repository.selectedScope.value
                reentrantActor = repository.readContext(Scope(), "w1:p1")
                afterAck.countDown()
                release.await()
            }
        }
        val replacement = Thread { repository.selectSubject(Scope(host = "replacement-host", session = "replacement-session"), "replacement-pane") }
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertEquals(1, reads)
            assertTrue(collectText(activity.window.decorView).contains("current-private-session"))
            replacement.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (afterAck.count != 0L && System.nanoTime() < deadline) {
                shadowOf(activity.mainLooper).idle()
                Thread.yield()
            }
            assertTrue("the last listener follows the real Activity wipe ACK", afterAck.await(0, TimeUnit.SECONDS))
            assertFalse(collectText(activity.window.decorView).contains("current-private-session"))
            assertEquals(null, repository.readContext(Scope(), "w1:p1"))
            val retry = findButton(activity.window.decorView)!!
            assertTrue(retry.isEnabled)
            retry.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals("pending transition admits no identity GET", 1, reads)
            assertFalse(collectText(activity.window.decorView).contains("current-private-session"))
            assertTrue("refused pending read settles to enabled retry", retry.isEnabled)
            assertEquals("replacement-host", repository.selectedScope.value.host)
            assertEquals("reentrant selection cannot overwrite the pending canonical target", repository.selectedScope.value, reentrantScope)
            assertEquals("a callback cannot mint an actor before retirement has settled", null, reentrantActor)
            val pendingRetry = findButton(activity.window.decorView)!!
            assertTrue("the settled temporary screen has an attached visible retry", pendingRetry.isShown)
            assertTrue(pendingRetry.isEnabled)
            armed = false
            release.countDown()
            replacement.join(2_000)
            assertFalse(replacement.isAlive)
            val freshRetry = findButton(activity.window.decorView)!!
            assertTrue("recovery uses the actual current visible button", freshRetry.isShown)
            freshRetry.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals("a deliberate post-transition refresh can select the screen target", 2, reads)
            assertTrue(collectText(activity.window.decorView).contains("current-private-session"))
            assertEquals(Scope(), repository.selectedScope.value)
        } finally {
            armed = false
            release.countDown()
            replacement.join(2_000)
            registration.close()
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun offMainAuthorityRetirementAcknowledgesOnlyAfterQueuedMainThreadWipe() {
        val original = app.container.repository
        install(agentApi(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
            ApiResult.Success(AgentIdentityResponse(
                true, "w1:p1", "codex", AgentStatus.WORKING,
                AgentIdentitySession("codex", "codex", "id", "off-main-private-session"),
                AgentStateAuthority(false, null), true, false, "9", null,
            ), 200),
        ) {})
        var armed = false
        val retirementObserved = CountDownLatch(1)
        val registration = app.container.repository.onReadContextInvalidated {
            if (armed) retirementObserved.countDown()
        }
        val controller = Robolectric.buildActivity(
            AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(context, PaneAddress(Scope(), "w1:p1")),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(activity.window.decorView.isAttachedToWindow)
            assertTrue(collectText(activity.window.decorView).contains("off-main-private-session"))
            shadowOf(activity.mainLooper).pause()
            armed = true
            val returned = CountDownLatch(1)
            val invalidator = Thread {
                app.container.repository.selectSubject(Scope(), "replacement")
                returned.countDown()
            }
            invalidator.start()
            assertTrue("authority listener entered", retirementObserved.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (invalidator.state != Thread.State.WAITING && invalidator.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("listener must wait for the queued wipe acknowledgement", Thread.State.WAITING, invalidator.state)
            assertEquals("selectSubject cannot return before the main looper runs the wipe", 1L, returned.count)
            assertTrue("the paused main looper has not run the wipe yet", collectText(activity.window.decorView).contains("off-main-private-session"))
            shadowOf(activity.mainLooper).idle()
            assertTrue("retirement listener returns only after the main-thread wipe", returned.await(2, TimeUnit.SECONDS))
            invalidator.join(2_000)
            assertFalse(collectText(activity.window.decorView).contains("off-main-private-session"))
        } finally {
            registration.close()
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun implicitSharedCallsCaptureTheCurrentTargetAfterACompletedHostSessionSelection() {
        val original = app.container.repository
        val requests = CopyOnWriteArrayList<Pair<String, Scope>>()
        fun <T> answer(method: String, scope: Scope, value: T): ApiResult<T> {
            requests.add(method to scope)
            return ApiResult.Success(value, 200)
        }
        val repository = install(object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) =
                answer("config", scope, BridgeConfigResponse(mux = MuxConfigResponse(name = scope.host.orEmpty())))
            override suspend fun snapshot(connection: Connection, scope: Scope) = answer("snapshot", scope,
                SnapshotResponse("connected", agents = emptyList(), shellPanes = emptyList(),
                    workspaces = emptyList(), tabs = emptyList(), ts = 1))
            override suspend fun refresh(connection: Connection, scope: Scope) = answer("refresh", scope, ActionResponse(true))
            override suspend fun launchers(connection: Connection, scope: Scope) =
                answer("launchers", scope, LaunchersResponse(emptyList(), "/${scope.host}"))
            override suspend fun folders(connection: Connection, scope: Scope) =
                answer("folders", scope, FoldersResponse(emptyList(), emptyList(), "/${scope.host}"))
            override suspend fun listWorktrees(connection: Connection, scope: Scope, workspaceId: String) =
                answer("listWorktrees", scope, WorktreeListResponse(true))
            override suspend fun createWorkspace(connection: Connection, scope: Scope, label: String?, cwd: String?) =
                answer("createWorkspace", scope, CreateResponse(true))
            override suspend fun starFolder(connection: Connection, scope: Scope, folder: String, starred: Boolean) =
                answer("starFolder", scope, FoldersResponse(emptyList(), emptyList(), "/${scope.host}"))
            override suspend fun launch(connection: Connection, scope: Scope, command: String, besidePaneId: String?) =
                answer("launch", scope, CreateResponse(true))
            override suspend fun createWorktree(connection: Connection, scope: Scope, workspaceId: String, branch: String) =
                answer("createWorktree", scope, WorktreeOpenResponse(true))
            override suspend fun openWorktree(connection: Connection, scope: Scope, workspaceId: String, path: String) =
                answer("openWorktree", scope, WorktreeOpenResponse(true))
        })
        val calls = linkedMapOf<String, suspend () -> ApiResult<*>>()
        calls["config"] = { repository.config() }
        calls["snapshot"] = { repository.snapshot() }
        calls["refresh"] = { repository.refresh() }
        calls["launchers"] = { repository.launchers() }
        calls["folders"] = { repository.folders() }
        calls["listWorktrees"] = { repository.listWorktrees("workspace") }
        calls["createWorkspace"] = { repository.createWorkspace(label = "new") }
        calls["starFolder"] = { repository.starFolder(folder = "/owned", starred = true) }
        calls["launch"] = { repository.launch("declared-command") }
        calls["createWorktree"] = { repository.createWorktree("workspace", "branch") }
        calls["openWorktree"] = { repository.openWorktree("workspace", "/owned/worktree") }
        val old = Scope(host = "old-host", session = "old-session")
        val current = Scope(host = "current-host", session = "current-session")
        try {
            // Same pane ID at two hosts must not mask a scope mismatch; null subject is also covered.
            for (subject in listOf(null, "same-pane")) for ((name, call) in calls) {
                repository.selectSubject(old, subject)
                requests.clear()
                val captureReached = CountDownLatch(1)
                val releaseCapture = CountDownLatch(1)
                val result = AtomicReference<ApiResult<*>?>()
                val failure = AtomicReference<Throwable?>()
                val worker = Thread {
                    try { result.set(runBlocking { call() }) } catch (error: Throwable) { failure.set(error) }
                }
                repository.beforeReadContextCaptureForTesting = {
                    if (Thread.currentThread() === worker) {
                        captureReached.countDown()
                        assertTrue("release paused actual shared caller", releaseCapture.await(2, TimeUnit.SECONDS))
                    }
                }
                try {
                    worker.start()
                    assertTrue("$name reaches capture before transport", captureReached.await(2, TimeUnit.SECONDS))
                    repository.selectSubject(current, subject)
                    assertEquals(current, repository.selectedScope.value)
                    // Establish the current snapshot's independent device write authority for write callers.
                    assertTrue(runBlocking { repository.snapshot(current) } is ApiResult.Success)
                    requests.clear()
                    releaseCapture.countDown()
                    worker.join(2_000)
                    assertFalse("$name completes", worker.isAlive)
                    assertEquals(null, failure.get())
                    assertTrue("$name returns the current response", result.get() is ApiResult.Success)
                    assertEquals("$name has no old-target dispatch", listOf(name to current), requests.toList())
                    val actor = repository.readContext()!!
                    assertEquals(current.host, actor.key.host)
                    assertEquals(current.session, actor.key.session)
                    assertEquals(subject, actor.key.subjectIdentity)
                    assertTrue(repository.isCurrent(actor))
                    if (name == "config") {
                        val config = (result.get() as ApiResult.Success<*>).value as BridgeConfigResponse
                        assertEquals("current-host", config.mux?.name)
                    }
                } finally {
                    releaseCapture.countDown()
                    worker.join(2_000)
                    repository.beforeReadContextCaptureForTesting = {}
                }
            }
        } finally {
            repository.beforeReadContextCaptureForTesting = {}
            restoreRepository(original)
        }
    }

    @Test
    fun anImplicitSharedReadCapturedBeforeReplacementCannotDispatchAndFreshExplicitWideReadsRemainValid() {
        val original = app.container.repository
        val scopes = CopyOnWriteArrayList<Scope>()
        val repository = install(object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
                scopes.add(scope)
                return ApiResult.Success(SnapshotResponse(scope.host.orEmpty(), agents = emptyList(),
                    shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            }
        })
        val old = Scope(host = "old-host", session = "old-session")
        val current = Scope(host = "current-host", session = "current-session")
        repository.selectSubject(old, "same-pane")
        val admissionReached = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val result = AtomicReference<ApiResult<SnapshotResponse>?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { result.set(runBlocking { repository.snapshot() }) } catch (error: Throwable) { failure.set(error) }
        }
        repository.beforeReadAdmissionForTesting = {
            if (Thread.currentThread() === worker) {
                admissionReached.countDown()
                assertTrue("release actual paused admission", releaseAdmission.await(2, TimeUnit.SECONDS))
            }
        }
        try {
            worker.start()
            assertTrue(admissionReached.await(2, TimeUnit.SECONDS))
            repository.selectSubject(current, "same-pane")
            releaseAdmission.countDown()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertEquals(null, failure.get())
            assertTrue("old admitted target is refused", result.get() is ApiResult.Failure)
            assertTrue("old target causes zero snapshot transport", scopes.isEmpty())
            repository.beforeReadAdmissionForTesting = {}
            val fresh = runBlocking { repository.snapshot() }
            assertTrue(fresh is ApiResult.Success)
            assertEquals("current-host", (fresh as ApiResult.Success).value.bridge)
            assertEquals(listOf(current), scopes.toList())
            // Explicit independently scoped reads remain supported, including dashboard breadth.
            val wide = Scope(host = "explicit-host", viewAll = true)
            assertTrue(runBlocking { repository.snapshot(wide) } is ApiResult.Success)
            assertEquals(wide, scopes.last())
            val wideActor = repository.readContext(wide)!!
            assertTrue(runBlocking { repository.snapshot(expectedContext = wideActor) } is ApiResult.Success)
            assertEquals("an explicit wide actor keeps its original request breadth", wide, scopes.last())
            assertEquals(current, repository.selectedScope.value)
            assertEquals(null, repository.readContext(current, null)!!.key.subjectIdentity)
            assertEquals("same-pane", repository.readContext(current)!!.key.subjectIdentity)
            repository.selectSubject(old, "same-pane")
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            assertEquals("deliberate old-address reselect uses a fresh actor", old, scopes.last())
        } finally {
            releaseAdmission.countDown()
            worker.join(2_000)
            repository.beforeReadAdmissionForTesting = {}
            restoreRepository(original)
        }
    }

    private fun install(api: CollieApi, suppliedStore: ConnectionStore? = null): CollieRepository {
        val connection = Connection(CollieOrigin("https://identity.fixture/"), "fixture", "token")
        val store = suppliedStore ?: object : ConnectionStore {
            override val connection: StateFlow<Connection?> = MutableStateFlow(connection)
            override fun save(connection: Connection) = Unit
            override fun clear() = Unit
        }
        return CollieRepository(api, store, app.container.originValidator).also { restoreRepository(it) }
    }

    private fun restoreRepository(repository: CollieRepository) {
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
    }

    private fun agentApi(
        config: BridgeConfigResponse,
        result: ApiResult<AgentIdentityResponse>,
        read: () -> Unit,
    ): CollieApi = object : CollieApi by app.container.api {
        override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(config, 200)
        override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress): ApiResult<AgentIdentityResponse> {
            read()
            return result
        }
    }

    private fun collectText(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { collectText(view.getChildAt(it)) }
        else -> ""
    }

    private fun findButton(view: android.view.View): Button? = when (view) {
        is Button -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findButton(view.getChildAt(it)) }
        else -> null
    }
}
