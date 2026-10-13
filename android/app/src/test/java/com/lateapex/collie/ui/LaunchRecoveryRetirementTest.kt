package com.lateapex.collie.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LaunchRecoveryRetirementTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Test
    fun networkProtocolAnd503PendingFencesPreserveActualOnePostUnknownRecoveryWithoutRestoringWrites() {
        for (failure in listOf(ApiFailure.Network("fixture response lost"), ApiFailure.Protocol("fixture bad response"), ApiFailure.Http(503, "fixture temporary failure", null))) {
            exercise(failure, permanent = false)
        }
    }

    @Test
    fun current401And403PermanentlyRetireTheActorAndCannotReviveItsPrivateRecoveryRows() {
        for (failure in listOf(ApiFailure.Http(401, "fixture identity expired", null), ApiFailure.Http(403, "fixture denied", null))) {
            exercise(failure, permanent = true)
        }
    }

    private fun exercise(failure: ApiFailure, permanent: Boolean) {
        app.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val connection = Connection(CollieOrigin("https://identity.fixture/"), "phone", "current-fixture-token")
        val store = object : ConnectionStore {
            private val state = MutableStateFlow<Connection?>(connection)
            override val connection: StateFlow<Connection?> = state
            override fun save(connection: Connection) { state.value = connection }
            override fun clear() { state.value = null }
        }
        var posts = 0
        var body: String? = null
        val api = object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse("fixture",
                device = DeviceAuthorization(true, "phone", true), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(receiptContextBound = true), 200)
            override suspend fun launchers(connection: Connection, scope: Scope) = ApiResult.Success(LaunchersResponse(
                launchers = emptyList(), home = "/home/operator", adding = LaunchersAdding(true, true, true, "launchers.toml", 0, 20, emptyList())), 200)
            override suspend fun checkRun(connection: Connection, scope: Scope, run: String) = ApiResult.Success(LaunchCheckResponse(ok = true, noPrompts = false), 200)
            override suspend fun startRunFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
                posts++
                body = canonicalBody
                return ApiResult.Failure(failure)
            }
        }
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(ByteArray(12) { 7 }, plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        val repository = CollieRepository(api, store, app.container.originValidator, launchIntentStore = LaunchIntentStore(app, cipher, Json))
        val controller = Robolectric.buildActivity(LaunchRecipeActivity::class.java, LaunchRecipeActivity.intent(app, Scope(), LaunchRecipeActivity.MODE_RUN))
        LaunchRecipeActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(controller.get(), repository)
        controller.create().start().resume().visible()
        var admissionClosedDuringFailure = false
        var actorPermanentlyRetired = false
        val captured = repository.readContext()!!
        val registration = repository.onAuthorityInvalidated { reason ->
            if (reason == AuthorityInvalidation.ACCESS_FROZEN) {
                admissionClosedDuringFailure = !repository.isCurrent(captured) && repository.readContext() == null && !repository.writesAllowed()
                actorPermanentlyRetired = repository.isActorRetired(captured)
            }
        }
        try {
            val activity = controller.get()
            idleUntil { repository.writesAllowed() && buttons(activity.window.decorView).any { it.text == activity.getString(R.string.recipes_reload) && it.isEnabled } }
            views(activity.window.decorView).filterIsInstance<EditText>().first { it.hint == activity.getString(R.string.recipes_run_input) }.setText("printf reviewed-fixture")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            val run = buttons(activity.window.decorView).first { it.text == activity.getString(R.string.recipes_run) }
            assertTrue(run.isEnabled)
            run.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleUntil { posts == 1 && buttons(activity.window.decorView).first { it.text == activity.getString(R.string.recipes_reload) }.isEnabled }
            assertTrue(admissionClosedDuringFailure)
            assertEquals(permanent, actorPermanentlyRetired)
            assertEquals(1, posts)
            val requestId = Json.parseToJsonElement(body!!).jsonObject.getValue("requestId").jsonPrimitive.content
            assertEquals(36, requestId.length)
            assertFalse(repository.writesAllowed())
            if (permanent) {
                assertTrue(repository.isActorRetired(captured))
                assertFalse(repository.readSource.value is ReadSourceState.Live)
                assertFalse(buttons(activity.window.decorView).any { it.text == activity.getString(R.string.recipes_retry_run) })
            } else {
                idleUntil { buttons(activity.window.decorView).any { it.text == activity.getString(R.string.recipes_retry_run) } }
                val retry = buttons(activity.window.decorView).first { it.text == activity.getString(R.string.recipes_retry_run) }
                assertTrue(retry.isShown)
                assertFalse(retry.isEnabled)
                assertFalse(repository.isActorRetired(captured))
                assertTrue(repository.isCurrent(captured))
                assertEquals(requestId, repository.unresolvedLaunchIntents().single().intent.requestId)
            }
            assertEquals("settlement never performs an automatic replay", 1, posts)
        } finally {
            registration.close()
            controller.destroy()
        }
    }

    private fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun buttons(view: View) = views(view).filterIsInstance<MaterialButton>()
    private fun idleUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        assertTrue("actual Activity completes within original recovery bound", predicate())
    }
}
