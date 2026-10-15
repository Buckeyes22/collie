package com.lateapex.collie.ui

import android.app.AlertDialog
import android.os.Looper
import android.content.Context
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlertDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PackTakeoverActivityTest {
    private val dispatcher = StandardTestDispatcher()
    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<PackActivity>>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val original = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private-bearer")
    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        context.getSharedPreferences("collie_takeover_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun after() { Dispatchers.resetMain() }
    private class Store(initial: Connection) : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(initial)
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
    }
    private class Api : CollieApi by CollieApiClient(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) {
        var state = "armed"
        var unavailable = false
        var posts = 0
        var healthCalls = 0
        var healthGate: CompletableDeferred<Unit>? = null
        var result: ApiResult<TakeoverResponse> = ApiResult.Failure(ApiFailure.Timeout)
        override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Failure(ApiFailure.Network("lead unavailable"))
        override suspend fun crew(connection: Connection): ApiResult<CrewStatusResponse> = ApiResult.Failure(ApiFailure.Network("lead unavailable"))
        override suspend fun standbyHealth(connection: Connection): ApiResult<StandbyHealthResponse> {
            healthCalls++
            if (unavailable) return ApiResult.Failure(ApiFailure.Network("standby unavailable"))
            if (healthCalls > 1) healthGate?.await()
            return ApiResult.Success(StandbyHealthResponse(state, if (state == "armed") 40_000 else null, "1.21.0", "1.21.0+abcdef1"), if (state == "cold") 503 else 200)
        }
        override suspend fun takeover(connection: Connection): ApiResult<TakeoverResponse> { posts++; return result }
    }
    private fun screen(repository: CollieRepository): org.robolectric.android.controller.ActivityController<PackActivity> =
        Robolectric.buildActivity(PackActivity::class.java).also { it.get().repositoryForTests = repository; controllers += it }.create().start().resume()

    private fun screenTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() }
        finally {
            // A failed assertion must stop the real production poll before runTest drains tasks.
            // @After alone runs too late: runTest otherwise keeps advancing the endless poll.
            controllers.asReversed().forEach { controller ->
                if (!controller.get().isDestroyed) {
                    if (controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) controller.pause().stop()
                    controller.destroy()
                }
            }
            controllers.clear()
        }
    }

    @Test fun coldLeadingAndUnavailableStandbyNeverEnableOrDispatchTakeover() = screenTest {
        for (state in listOf("cold", "leading", "unavailable")) {
            val api = Api().also { it.state = state; it.unavailable = state == "unavailable" }
            val controller = screen(CollieRepository(api, Store(original), OriginValidator()))
            runCurrent()
            assertFalse(controller.get().findViewById<Button>(R.id.pack_takeover_button).isEnabled)
            controller.get().findViewById<Button>(R.id.pack_takeover_button).performClick(); runCurrent()
            assertEquals(0, api.posts)
            controller.pause().stop().destroy()
        }
    }
    @Test fun userConfirmationSendsOnceAndUnknownPersistsAcrossActivityRestart() = screenTest {
        val api = Api(); val repository = CollieRepository(api, Store(original), OriginValidator())
        val controller = screen(repository); runCurrent()
        val button = controller.get().findViewById<Button>(R.id.pack_takeover_button)
        assertTrue(button.isEnabled); assertEquals(0, api.posts)
        button.performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
        confirmation.performClick(); confirmation.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(1, api.posts)
        assertTrue(DeputyTakeoverGuard(context).pending(original)); assertFalse(button.isEnabled)
        controller.pause().stop().destroy()
        val restarted = screen(repository); runCurrent()
        assertFalse(restarted.get().findViewById<Button>(R.id.pack_takeover_button).isEnabled)
        assertEquals(1, api.posts)
        restarted.pause().stop().destroy()
    }
    @Test fun stoppedScreenConfirmationCannotSendOrBorrowRestartedScreen() = screenTest {
        val api = Api(); val controller = screen(CollieRepository(api, Store(original), OriginValidator())); runCurrent()
        controller.get().findViewById<Button>(R.id.pack_takeover_button).performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
        controller.pause().stop()
        confirmation.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(0, api.posts)
        controller.start().resume(); runCurrent()
        confirmation.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(0, api.posts); assertFalse(DeputyTakeoverGuard(context).pending(original))
        controller.pause().stop().destroy()
    }
    @Test fun changedPairingDuringFreshStandbyCheckCannotDispatchUnderSuccessor() = screenTest {
        val api = Api(); val store = Store(original); val controller = screen(CollieRepository(api, store, OriginValidator())); runCurrent()
        api.healthGate = CompletableDeferred()
        controller.get().findViewById<Button>(R.id.pack_takeover_button).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(2, api.healthCalls); assertEquals(0, api.posts)
        store.save(Connection(original.origin, "replacement", "replacement-bearer"))
        api.healthGate!!.complete(Unit); runCurrent()
        assertEquals(0, api.posts); assertFalse(DeputyTakeoverGuard(context).pending(original))
        controller.pause().stop().destroy()
    }
}
