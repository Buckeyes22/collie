package com.lateapex.collie.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.AppContainer
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.PaneLayoutIntent
import com.lateapex.collie.data.PaneLayoutIntentOwner
import com.lateapex.collie.data.PaneLayoutIntentStore
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import androidx.appcompat.app.AlertDialog
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LayoutActivityTest {
    @Test
    fun refreshKeepsRecoveredUnknownLockedToTheSameRequestAndNeverPlansAnotherEffect() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val originalRepository = app.container.repository
        val originalStore = app.container.paneLayoutIntentStore
        val initialConnection = Connection(CollieOrigin("https://layout.fixture/"), "phone", "secret-token")
        var receiptsReady = true
        val cipher = TestCipher()
        val connectionStore = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(initialConnection)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        var plans = 0
        var mutations = 0
        val layout = LayoutSnapshot("space", "tab", false, LayoutRect(0, 0, 80, 24), "pane-1",
            listOf(LayoutPane("pane-1", true, LayoutRect(0, 0, 80, 24))), emptyList())
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                BridgeConfigResponse(
                    mux = MuxConfigResponse(name = "herdr", capabilities = mapOf("paneLayout" to true,
                        "paneSplit" to true, "paneResize" to true, "paneZoom" to true, "paneSwap" to true,
                        "paneMove" to true, "paneDirectionalFocus" to true)),
                    control = ControlCapabilityResponse(mapOf("controlOperations" to receiptsReady)),
                ), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
                SnapshotResponse("connected", controlAuthority = ControlAuthority("lead", "epoch-1"),
                    device = DeviceAuthorization(false, null, true), agents = emptyList(), shellPanes = emptyList(),
                    workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun folders(connection: Connection, scope: Scope) = ApiResult.Success(FoldersResponse(home = "/home/phone", recent = emptyList(), favourites = emptyList()), 200)
            override suspend fun paneLayout(connection: Connection, address: PaneAddress) = ApiResult.Success(
                PaneLayoutReadResponse(true, PaneLayoutRead("layout", layout)), 200)
            override suspend fun paneLayoutRead(connection: Connection, address: PaneAddress, kind: String, direction: String?) = ApiResult.Success(
                PaneLayoutReadResponse(true, if (kind == "edges") PaneLayoutRead("edges", layout, address.paneId, left = false, right = false, up = false, down = false)
                    else PaneLayoutRead("neighbor", layout, address.paneId, direction, null)), 200)
            override suspend fun planPaneLayout(connection: Connection, address: PaneAddress, action: kotlinx.serialization.json.JsonObject): ApiResult<PaneLayoutPlanResponse> {
                plans++
                return ApiResult.Failure(ApiFailure.Protocol("unexpected plan"))
            }
            override suspend fun applyPaneLayout(connection: Connection, address: PaneAddress, requestId: String, planToken: String, action: kotlinx.serialization.json.JsonObject): ApiResult<PaneLayoutOperationResponse> {
                mutations++
                return ApiResult.Failure(ApiFailure.Protocol("unexpected mutation"))
            }
            override suspend fun controlOperation(connection: Connection, scope: Scope, requestId: String): ApiResult<ControlOperationResponse> = ApiResult.Success(
                ControlOperationResponse(true, ControlOperation(1, requestId, "succeeded", "paneSplit", "a".repeat(64), System.currentTimeMillis(), System.currentTimeMillis())), 200)
        }
        val repository = CollieRepository(api, connectionStore, app.container.originValidator)
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val storeField = AppContainer::class.java.getDeclaredField("paneLayoutIntentStore").apply { isAccessible = true }
        val preferences = app.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val intentStore = PaneLayoutIntentStore(app, cipher, Json { ignoreUnknownKeys = true })
        repositoryField.set(app.container, repository)
        storeField.set(app.container, intentStore)
        val scope = Scope()
        val subject = "pane-1"
        repository.selectSubject(scope, subject)
        runBlocking { repository.snapshot(scope) }
        val captured: ScopedReadContext = repository.readContext(scope, subject)!!
        val owner = PaneLayoutIntentOwner.from(captured, subject)
        val action = buildJsonObject { put("kind", "split"); put("direction", "right") }
        assertEquals(com.lateapex.collie.data.PaneLayoutReservation.Reserved,
            intentStore.reserve(PaneLayoutIntent(owner, captured.key.connectionGeneration, captured.key.leadEpoch,
                "same-request", "same-plan", Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), action))))

        val launchIntent = Intent(app, LayoutActivity::class.java).apply {
            putExtra(LayoutActivity.EXTRA_PANE_ID, subject)
        }
        val controller = Robolectric.buildActivity(LayoutActivity::class.java, launchIntent).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_recovered_unknown) })
            assertNotNull(intentStore.unresolved(owner))
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            val refresh = descendants(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text == activity.getString(R.string.pane_layout_refresh) }
            assertNotNull(refresh)
            (refresh as View).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, plans)
            assertEquals(0, mutations)
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_retry_same_id) })
            assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_zoom_on) })
            assertFalse(activity.isFinishing)

            // A server-confirmed terminal result cannot unlock a new action unless durable local
            // custody deletion is acknowledged. The original same-ID row stays recoverable.
            cipher.failEncrypt = true
            val reconcile = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_reconcile) }
            (reconcile as View).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_recover_storage) })
            cipher.failEncrypt = false
            (descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_recover_storage) } as View).performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle() // AppCompat posts the actual confirmation callback.
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_retry_same_id) })
            assertEquals(0, plans)
            assertEquals(0, mutations)

            // Retire the displayed actor while the explicit abandon confirmation is open. Its old
            // callback cannot clear the durable row; a current actor must refresh and reconfirm.
            val oldActor = repository.readContext(scope, subject)!!
            val abandon = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_abandon) }
            (abandon as View).performClick()
            val cancelDialog = (ShadowDialog.getLatestDialog() as? AlertDialog)
            assertNotNull(cancelDialog)
            cancelDialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            val abandonAgainBeforeRetirement = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_abandon) }
            (abandonAgainBeforeRetirement as View).performClick()
            val staleDialog = (ShadowDialog.getLatestDialog() as? AlertDialog)
            assertNotNull(staleDialog)
            controller.pause().stop()
            val returned = java.util.concurrent.atomic.AtomicBoolean(false)
            val posted = java.util.concurrent.atomic.AtomicBoolean(false)
            val originalPoster = activity.retirementPoster
            activity.retirementPoster = { task -> originalPoster(task).also { posted.set(true) } }
            val retired = Thread { repository.setLeadEpoch(oldActor.key.leadEpoch + 1); returned.set(true) }
            retired.start()
            val actorField = LayoutActivity::class.java.getDeclaredField("actor").apply { isAccessible = true }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            val copiedFields = listOf("availableTargets", "availableWorkspaces", "advertisedFolders").map { name ->
                LayoutActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            }
            while ((actorField.get(activity) != null || copiedFields.any { !(it.get(activity) as List<*>).isEmpty() }) &&
                System.nanoTime() < deadline) Thread.yield()
            assertEquals(null, actorField.get(activity))
            for (name in listOf("availableTargets", "availableWorkspaces", "advertisedFolders")) {
                val field = LayoutActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                assertTrue((field.get(activity) as List<*>).isEmpty())
            }
            while (!posted.get() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(posted.get())
            assertFalse(returned.get()) // Off-main return requires acknowledgement of the actual UI wipe.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            retired.join(5_000)
            assertTrue(returned.get())
            assertFalse(staleDialog!!.isShowing)
            controller.start().resume()
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)
            staleDialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertEquals("same-request", intentStore.unresolved(owner)?.requestId)

            // Simulate process recreation: the new store can recover the prior encrypted row even
            // after a failed write. A peer without owner-bound receipts offers no replay or lookup.
            val recoveredCipher = TestCipher()
            val recoveredStore = PaneLayoutIntentStore(app, recoveredCipher, Json { ignoreUnknownKeys = true })
            storeField.set(app.container, recoveredStore)
            receiptsReady = false
            val refreshRetired = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_refresh) }
            (refreshRetired as View).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val refreshedOwner = PaneLayoutIntentOwner.from(repository.readContext(scope, subject)!!, subject)
            assertEquals("same-request", recoveredStore.unresolved(refreshedOwner)?.requestId)
            assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_retry_same_id) })
            assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_reconcile) })

            // Abandon requires a second, explicit confirmation and a successful synchronous
            // encrypted-store acknowledgement. It removes only the local custody row.
            val abandonAgain = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_abandon) }
            (abandonAgain as View).performClick()
            val currentDialog = (ShadowDialog.getLatestDialog() as? AlertDialog)
            assertNotNull(currentDialog)
            assertTrue(currentDialog!!.findViewById<TextView>(android.R.id.message)?.text?.contains("may already have happened") == true)
            recoveredCipher.failEncrypt = true
            currentDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("same-request", recoveredStore.unresolved(refreshedOwner)?.requestId)
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.pane_layout_recover_storage) })
            recoveredCipher.failEncrypt = false
            (descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_recover_storage) } as View).performClick()
            val recoveryDialog = ShadowDialog.getLatestDialog() as AlertDialog
            recoveryDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("same-request", recoveredStore.unresolved(refreshedOwner)?.requestId)
            assertEquals(0, plans)
            assertEquals(0, mutations)
            val retryAbandon = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.pane_layout_abandon) }
            (retryAbandon as View).performClick()
            val acknowledgedDialog = (ShadowDialog.getLatestDialog() as? AlertDialog)
            assertNotNull(acknowledgedDialog)
            acknowledgedDialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(null, recoveredStore.unresolved(refreshedOwner))
            controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply { orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE })
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(controller.get() !== activity)
            assertEquals(null, PaneLayoutIntentStore(app, TestCipher(), Json { ignoreUnknownKeys = true }).unresolved(refreshedOwner))
            assertEquals(0, plans)
            assertEquals(0, mutations)
        } finally {
            controller.pause().stop().destroy()
            repositoryField.set(app.container, originalRepository)
            storeField.set(app.container, originalStore)
            preferences.edit().clear().commit()
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private class TestCipher : SecretCipher {
        var failEncrypt = false
        private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun encrypt(plaintext: ByteArray): com.lateapex.collie.data.CipherEnvelope {
            if (failEncrypt) throw IllegalStateException("injected keystore failure")
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return com.lateapex.collie.data.CipherEnvelope(aes.iv, aes.doFinal(plaintext))
        }
        override fun decrypt(envelope: com.lateapex.collie.data.CipherEnvelope): ByteArray {
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, envelope.iv))
            return aes.doFinal(envelope.ciphertext)
        }
    }
}
