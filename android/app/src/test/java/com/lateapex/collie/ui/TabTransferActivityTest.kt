package com.lateapex.collie.ui

import android.content.Context
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.lateapex.collie.data.PaneTransferIntent
import com.lateapex.collie.data.PaneTransferLayout
import com.lateapex.collie.data.PaneTransferLayoutCell
import com.lateapex.collie.data.PaneTransferMember
import com.lateapex.collie.data.PaneTransferOwner
import com.lateapex.collie.data.PaneTransferRecipeStep
import com.lateapex.collie.data.PaneTransferStepResult
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TabTransferActivityTest {
    @Test fun ownerScopedWholeTabProgressScreenIsReachableAndFailsClosedWithoutAnActor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = Scope(host = "host-a", session = "session-a")
        val activity = Robolectric.buildActivity(
            TabTransferActivity::class.java,
            TabTransferActivity.intent(context, scope, "tab-a", "workspace-a"),
        ).create().start().resume().get()
        shadowOf(activity.mainLooper).idle()

        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val root = (content.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        val title = root.getChildAt(0) as TextView
        assertEquals(activity.getString(R.string.tab_transfer_title), title.text)
        val controls = root.getChildAt(2) as LinearLayout
        assertTrue((controls.getChildAt(0) as Button).minimumHeight >= activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target))
        assertEquals(activity.getString(R.string.tab_transfer_unavailable), (root.getChildAt(1) as TextView).text)
        assertEquals(0, (root.getChildAt(3) as LinearLayout).childCount)
        activity.finish()
        shadowOf(activity.mainLooper).idle()
    }

    @Test fun supportedCurrentOwnerGetsTheAccessibleTransferExplanation() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val oldRepository = app.container.repository
        val scope = Scope(host = "host-a", session = "session-a")
        val fixtureConnection = Connection(CollieOrigin("https://transfer-help.test/"), "fixture", "fixture-token")
        val connections = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(fixtureConnection)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val fixtureSnapshot = SnapshotResponse("connected", ControlAuthority("lead", "epoch-help"), DeviceAuthorization(false, null, true),
            agents = emptyList(), shellPanes = emptyList(),
            workspaces = listOf(WorkspaceSummary("workspace-a", 1, "Source", true, "tab-a", 1, 1, host = "host-a")),
            tabs = listOf(TabSummary("tab-a", "workspace-a", 1, "Source", true, 0, "host-a")), ts = 1)
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(
                mux = MuxConfigResponse("herdr", mapOf("paneLayout" to true, "paneMove" to true, "tabTransfer" to true)),
                control = ControlCapabilityResponse(mapOf("controlOperations" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(fixtureSnapshot, 200)
        }
        val repository = CollieRepository(api, connections, app.container.originValidator)
        val repositoryField = AppContainer::class.java.getDeclaredField("repository").apply { isAccessible = true }
        repositoryField.set(app.container, repository)
        try {
            repository.selectSubject(scope, null)
            runBlocking { repository.snapshot(scope) }
            val controller = Robolectric.buildActivity(TabTransferActivity::class.java,
                TabTransferActivity.intent(app, scope, "tab-a", "workspace-a")).create().start().resume()
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val root = (content.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
            val rows = root.getChildAt(3) as LinearLayout
            assertEquals(1, rows.childCount)
            val explanation = rows.getChildAt(0) as TextView
            assertEquals(activity.getString(R.string.tab_transfer_description), explanation.text)
            assertEquals(explanation.text, explanation.contentDescription)
            assertTrue((root.getChildAt(2) as LinearLayout).getChildAt(0).isEnabled)
            controller.pause().stop().destroy()
        } finally {
            repositoryField.set(app.container, oldRepository)
        }
    }

    @Test fun recoveredSecondStepOffersReceiptLookupAndNeverReplaysOnActivityRecreation() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val oldRepository = app.container.repository
        val oldStore = app.container.paneLayoutIntentStore
        val scope = Scope(host = "host-a", session = "session-a")
        val fixtureConnection = Connection(CollieOrigin("https://transfer.test/"), "fixture", "fixture-token")
        val connections = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(fixtureConnection)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val paneSource = testPane("pane-2", "space-a", "tab-a")
        val paneMoved = testPane("pane-new-1", "space-b", "tab-new")
        val fixtureSnapshot = SnapshotResponse("connected", ControlAuthority("lead", "epoch-1"), DeviceAuthorization(false, null, true),
            agents = listOf(paneSource, paneMoved), shellPanes = emptyList(),
            workspaces = listOf(WorkspaceSummary("space-a", 1, "Source", true, "tab-a", 1, 1, host = "host-a"),
                WorkspaceSummary("space-b", 2, "Destination", false, "tab-new", 1, 1, host = "host-a")),
            tabs = listOf(TabSummary("tab-a", "space-a", 1, "Source", true, 1, "host-a"),
                TabSummary("tab-new", "space-b", 1, "Moved", false, 1, "host-a")), ts = 1)
        var receiptReads = 0
        var writes = 0
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(
                mux = MuxConfigResponse("herdr", mapOf("paneLayout" to true, "paneMove" to true, "tabTransfer" to true)),
                control = ControlCapabilityResponse(mapOf("controlOperations" to true))), 200)
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(fixtureSnapshot, 200)
            override suspend fun controlOperation(connection: Connection, scope: Scope, requestId: String): ApiResult<ControlOperationResponse> {
                receiptReads++
                return ApiResult.Success(ControlOperationResponse(true, ControlOperation(1, requestId, "pending", "paneMove", "a".repeat(64), 1, 1)), 200)
            }
            override suspend fun applyPaneLayout(connection: Connection, address: PaneAddress, requestId: String, planToken: String,
                action: kotlinx.serialization.json.JsonObject): ApiResult<PaneLayoutOperationResponse> {
                writes++
                return ApiResult.Failure(ApiFailure.Protocol("unexpected replay"))
            }
        }
        val repository = CollieRepository(api, connections, app.container.originValidator)
        val repositoryField = AppContainer::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val storeField = AppContainer::class.java.getDeclaredField("paneLayoutIntentStore").apply { isAccessible = true }
        val preferences = app.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        var store = PaneLayoutIntentStore(app, TestCipher(), Json { ignoreUnknownKeys = true })
        repositoryField.set(app.container, repository)
        storeField.set(app.container, store)
        try {
            repository.selectSubject(scope, null)
            runBlocking { repository.snapshot(scope) }
            val captured: ScopedReadContext = repository.readContext(scope)!!
            val transferOwner = PaneTransferOwner(captured.key.origin, captured.key.pairedDeviceIdentity, captured.key.host, captured.key.session,
                captured.key.subjectIdentity, "space-a", "tab-a")
            val first = PaneTransferMember("pane-1", "a".repeat(64))
            val second = PaneTransferMember("pane-2", "b".repeat(64))
            val initial = PaneTransferIntent(transferOwner, captured.key.connectionGeneration, captured.key.leadEpoch, "transfer-1", "new_tab", "space-b", "Moved", null,
                listOf(PaneTransferRecipeStep("right", 0.5)), listOf(first, second), sourceLayout = PaneTransferLayout(0, 0, 80, 24, false, "pane-1",
                    listOf(PaneTransferLayoutCell("pane-1", true, 0, 0, 40, 24), PaneTransferLayoutCell("pane-2", false, 40, 0, 40, 24)), emptyList()))
            assertTrue(store.beginTransfer(initial))
            val firstChild = PaneLayoutIntent(PaneLayoutIntentOwner.from(captured, first.originalPaneId), captured.key.connectionGeneration,
                captured.key.leadEpoch, "child-1", "plan-1", transferChildAction(initial, firstStep = true).toString(), "tab-a", "space-a")
            assertTrue(store.reserveTransferChild(transferOwner, initial.transferId, firstChild))
            assertTrue(store.completeTransferChild(transferOwner, initial.transferId, firstChild.requestId,
                PaneTransferStepResult(true, "pane-new-1", "space-b", "tab-new", first.witness, "tab-new")))
            val progressed = store.transfer(transferOwner)!!
            val secondChild = PaneLayoutIntent(PaneLayoutIntentOwner.from(captured, second.originalPaneId), captured.key.connectionGeneration,
                captured.key.leadEpoch, "child-2", "plan-2", transferChildAction(progressed, firstStep = false).toString(), "tab-a", "space-a")
            assertTrue(store.reserveTransferChild(transferOwner, initial.transferId, secondChild))

            val controller = Robolectric.buildActivity(TabTransferActivity::class.java, TabTransferActivity.intent(app, scope, "tab-a", "space-a"))
                .create().start().resume()
            var activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.tab_transfer_unknown_notice) })
            val lookup = descendants(activity.window.decorView).filterIsInstance<Button>().firstOrNull { it.text == activity.getString(R.string.tab_transfer_check_outcome) }
            assertNotNull(lookup)
            lookup!!.performClick()
            shadowOf(activity.mainLooper).idle()
            assertEquals(1, receiptReads)
            assertEquals(0, writes)
            assertEquals("pending", store.transfer(transferOwner)?.activeChild?.state)

            store = PaneLayoutIntentStore(app, TestCipher(), Json { ignoreUnknownKeys = true })
            storeField.set(app.container, store) // A fresh journal instance models process recovery.
            controller.configurationChange()
            shadowOf(activity.mainLooper).idle()
            activity = controller.get()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.tab_transfer_unknown_notice) })
            assertEquals("child-2", store.transfer(transferOwner)?.activeChild?.requestId)
            assertEquals("unknown", store.transfer(transferOwner)?.activeChild?.state)
            assertEquals(0, writes)
            controller.pause().stop().destroy()
        } finally {
            repositoryField.set(app.container, oldRepository)
            storeField.set(app.container, oldStore)
            preferences.edit().clear().commit()
        }
    }

    private fun testPane(id: String, workspaceId: String, tabId: String) = PaneSummary(
        paneId = id, workspaceId = workspaceId, workspaceLabel = workspaceId, workspaceNumber = 1, tabId = tabId,
        agent = "Claude", status = AgentStatus.WORKING, cwd = "/repo", focused = false, kind = "agent", host = "host-a", session = "session-a")

    private fun transferChildAction(parent: PaneTransferIntent, firstStep: Boolean) = buildJsonObject {
        put("kind", JsonPrimitive("move"))
        if (firstStep) put("destination", buildJsonObject {
            put("type", JsonPrimitive("new_tab")); put("workspaceId", JsonPrimitive(parent.destinationWorkspaceId!!)); put("label", JsonPrimitive(parent.destinationLabel!!))
        }) else put("destination", buildJsonObject {
            put("type", JsonPrimitive("tab")); put("tabId", JsonPrimitive("tab-new")); put("split", JsonPrimitive("right"));
            put("targetPaneId", JsonPrimitive("pane-new-1")); put("ratio", JsonPrimitive(0.5))
        })
        put("transfer", buildJsonObject {
            put("schema", JsonPrimitive(1)); put("transferId", JsonPrimitive(parent.transferId));
            put("sourceWorkspaceId", JsonPrimitive(parent.owner.sourceWorkspaceId)); put("sourceTabId", JsonPrimitive(parent.owner.sourceTabId))
            val remaining = parent.sourceMembers.filter { row -> parent.mappings.none { it.originalPaneId == row.originalPaneId } }
            put("remainingSource", buildJsonArray { remaining.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.originalPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
            if (firstStep) put("destination", JsonNull) else put("destination", buildJsonObject {
                val target = parent.mappings.first()
                put("workspaceId", JsonPrimitive(target.workspaceId)); put("tabId", JsonPrimitive(target.tabId))
                put("members", buildJsonArray { parent.mappings.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.currentPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
            })
        })
    }

    private fun descendants(root: android.view.View): List<android.view.View> = buildList {
        add(root)
        if (root is android.view.ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private class TestCipher : SecretCipher {
        private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 3 }, "AES")
        override fun encrypt(plaintext: ByteArray): com.lateapex.collie.data.CipherEnvelope {
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return com.lateapex.collie.data.CipherEnvelope(aes.iv, aes.doFinal(plaintext))
        }
        override fun decrypt(envelope: com.lateapex.collie.data.CipherEnvelope): ByteArray {
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, envelope.iv))
            return aes.doFinal(envelope.ciphertext)
        }
    }
}
