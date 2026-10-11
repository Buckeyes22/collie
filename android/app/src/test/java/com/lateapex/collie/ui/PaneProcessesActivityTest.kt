package com.lateapex.collie.ui

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneProcess
import com.lateapex.collie.network.PaneProcessesResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PaneProcessesActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app: CollieApplication get() = context.applicationContext as CollieApplication

    @Test
    fun successfulDetailsRenderAndVisibleSensitiveTextClearsWhenAuthorityRetires() {
        val original = app.container.repository
        val api = processApi(
            configResult = positiveConfig(),
            result = ApiResult.Success(processDetails("/private/fixture/path", "KnownCredentialForActivity"), 200),
        )
        val repository = install(api)
        val controller = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val before = visibleText(activity)
            assertTrue(before.contains("/private/fixture/path"))
            assertTrue(before.contains("KnownCredentialForActivity"))
            assertTrue(before.contains("Foreground process group: 12"))

            repository.selectSubject(Scope(), "replacement-pane")
            shadowOf(activity.mainLooper).idle()
            val after = visibleText(activity)
            assertFalse(after.contains("/private/fixture/path"))
            assertFalse(after.contains("KnownCredentialForActivity"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun nullForegroundProcessGroupDoesNotRenderItsLabel() {
        val original = app.container.repository
        install(processApi(
            configResult = positiveConfig(),
            result = ApiResult.Success(processDetails("/private/no-group", "NoGroupCredential", null), 200),
        ))
        val controller = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertFalse(visibleText(activity).contains("Foreground process group:"))
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun unsupportedCapabilityHasAnExplicitExplanation() {
        val original = app.container.repository
        var processReads = 0
        val api = processApi(configResult = ApiResult.Success(
            BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("paneProcesses" to false))), 200,
        ), delayed = {
            processReads++
            ApiResult.Success(processDetails("/must-not-read", "MustNotRead"), 200)
        })
        install(api)
        val controller = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val rendered = visibleText(activity)
            assertTrue(rendered.contains(activity.getString(com.lateapex.collie.R.string.pane_processes_unsupported)))
            assertFalse(rendered.contains(activity.getString(com.lateapex.collie.R.string.pane_processes_unavailable)))
            assertEquals(0, processReads)
        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun retiredScopeDiscardsDelayedProcessResponse() {
        val original = app.container.repository
        val pending = CompletableDeferred<ApiResult<PaneProcessesResponse>>()
        val dispatched = CompletableDeferred<Unit>()
        val api = processApi(configResult = positiveConfig(), delayed = {
            dispatched.complete(Unit)
            pending.await()
        })
        val repository = install(api)
        val controller = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(dispatched.isCompleted)
            repository.selectSubject(Scope(), "new-pane")
            shadowOf(activity.mainLooper).idle()
            assertFalse(visibleText(activity).contains("LateCredentialMustNotRender"))
            pending.complete(ApiResult.Success(processDetails("/retired/path", "LateCredentialMustNotRender"), 200))
            shadowOf(activity.mainLooper).idle()
            assertFalse(visibleText(activity).contains("LateCredentialMustNotRender"))

        } finally {
            controller.destroy()
            restoreRepository(original)
        }
    }

    @Test
    fun recreationStateDoesNotContainSensitiveProcessText() {
        val original = app.container.repository
        val pendingResponse = CompletableDeferred<ApiResult<PaneProcessesResponse>>()
        var requestCount = 0
        install(processApi(
            configResult = positiveConfig(),
            delayed = {
                if (requestCount++ == 0) {
                    ApiResult.Success(processDetails("/private/recreate/path", "RecreationCredential"), 200)
                } else {
                    pendingResponse.await()
                }
            },
        ))
        val controller = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create().start().resume()
        var originalDestroyed = false
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(visibleText(activity).contains("RecreationCredential"))
            val state = Bundle()
            controller.saveInstanceState(state)
            assertFalse(state.toString().contains("RecreationCredential"))
            assertFalse(state.toString().contains("/private/recreate/path"))
            controller.pause().stop().destroy()
            originalDestroyed = true
            val recreated = Robolectric.buildActivity(PaneProcessesActivity::class.java, activityIntent()).create(state).start().resume()
            try {
                val replacement = recreated.get()
                assertFalse(visibleText(replacement).contains("RecreationCredential"))
                shadowOf(replacement.mainLooper).idle()
                assertFalse(visibleText(replacement).contains("RecreationCredential"))
                pendingResponse.complete(ApiResult.Success(processDetails("/private/recreate/path", "RecreationCredential"), 200))
                shadowOf(replacement.mainLooper).idle()
                assertTrue(visibleText(replacement).contains("RecreationCredential"))
            } finally {
                recreated.destroy()
            }
        } finally {
            if (!originalDestroyed) controller.destroy()
            restoreRepository(original)
        }
    }

    private fun install(api: CollieApi): CollieRepository {
        val initialConnection = Connection(CollieOrigin("https://process.fixture/"), "fixture", "token")
        val store = object : ConnectionStore {
            override val connection: StateFlow<Connection?> = MutableStateFlow(initialConnection)
            override fun save(connection: Connection) = Unit
            override fun clear() = Unit
        }
        return CollieRepository(api, store, app.container.originValidator).also { restoreRepository(it) }
    }

    private fun restoreRepository(repository: CollieRepository) {
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
    }

    private fun processApi(
        configResult: ApiResult<BridgeConfigResponse>,
        result: ApiResult<PaneProcessesResponse>? = null,
        delayed: (suspend () -> ApiResult<PaneProcessesResponse>)? = null,
    ): CollieApi = object : CollieApi by app.container.api {
        override suspend fun config(connection: Connection, scope: Scope) = configResult
        override suspend fun paneProcesses(connection: Connection, address: PaneAddress): ApiResult<PaneProcessesResponse> =
            delayed?.invoke() ?: result ?: error("process result not configured")
    }

    private fun positiveConfig() = ApiResult.Success(
        BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("paneProcesses" to true))), 200,
    )

    private fun processDetails(path: String, secret: String, foregroundGroupId: Long? = 12L) = PaneProcessesResponse(
        ok = true,
        paneId = "w1:p1",
        foregroundProcessGroupId = foregroundGroupId,
        processes = listOf(PaneProcess(pid = 7, name = "node", cmdline = secret, cwd = path)),
    )

    private fun activityIntent() = PaneProcessesActivity.intent(context, PaneAddress(Scope(), "w1:p1"))

    private fun visibleText(activity: PaneProcessesActivity): String = collectText(activity.window.decorView)

    private fun collectText(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { collectText(view.getChildAt(it)) }
        else -> ""
    }
}
