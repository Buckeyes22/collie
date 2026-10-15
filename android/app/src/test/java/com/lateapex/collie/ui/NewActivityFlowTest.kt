package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
class NewActivityFlowTest {
    @Test
    fun selectingPeerAndSessionObtainsScopedAuthorityBeforeConfirmedRichLaunch() {
        val fixture = fixture(Scope())
        try {
            val activity = fixture.activity
            idleMain()
            button(activity, activity.getString(R.string.new_machine_select, activity.getString(R.string.new_this_machine))).performClick()
            chooseDialogItem(1)
            idleMain()
            button(activity, activity.getString(R.string.new_session_select, activity.getString(R.string.new_default_session))).performClick()
            chooseDialogItem(1)
            idleMain()
            val scope = Scope(host = "builder", session = "review")
            assertTrue(fixture.api.snapshotScopes.contains(scope))
            assertFalse(button(activity, activity.getString(R.string.new_start)).isEnabled)
            allViews(field<ViewGroup>(activity, "choicesView")).filterIsInstance<android.widget.RadioButton>().first { it.isEnabled }.performClick()
            assertTrue(button(activity, activity.getString(R.string.new_start)).isEnabled)
            button(activity, activity.getString(R.string.folder_star)).performClick()
            idleMain()
            assertEquals(scope, fixture.api.starScopes.single())
            button(activity, activity.getString(R.string.new_start)).performClick()
            val prompt = ShadowDialog.getLatestDialog()
            button(prompt.window!!.decorView, activity.getString(R.string.launch_no_prompts_start)).performClick()
            idleMain()
            assertEquals(scope, fixture.api.richLaunchScopes.single())
        } finally {
            fixture.controller.destroy()
        }
    }

    @Test
    fun scopeChangesAndRecreationRequireFreshChoiceForReusedKeys() {
        val hostScope = Scope(host = "builder")
        val sessionScope = Scope(host = "builder", session = "review")
        val api = TestApi(true, true).apply { harnessByScope = mapOf(hostScope to "muse", sessionScope to "pi") }
        val first = fixture(Scope(), apiOverride = api)
        val saved = android.os.Bundle()
        try {
            val activity = first.activity
            button(activity, activity.getString(R.string.new_machine_select, activity.getString(R.string.new_this_machine))).performClick()
            chooseDialogItem(1)
            idleMain()
            assertFalse(button(activity, activity.getString(R.string.new_start)).isEnabled)
            val hostRows = allViews(field<ViewGroup>(activity, "choicesView")).filterIsInstance<android.widget.RadioButton>()
            assertTrue(hostRows.none { it.isChecked })
            hostRows.first { it.text == "muse" }.performClick()
            assertTrue(button(activity, activity.getString(R.string.new_start)).isEnabled)
            button(activity, activity.getString(R.string.new_session_select, activity.getString(R.string.new_default_session))).performClick()
            chooseDialogItem(1)
            idleMain()
            assertFalse(button(activity, activity.getString(R.string.new_start)).isEnabled)
            assertTrue(allViews(field<ViewGroup>(activity, "choicesView")).filterIsInstance<android.widget.RadioButton>().none { it.isChecked })
            first.controller.saveInstanceState(saved)
            assertEquals(0, api.richLaunchCalls)
        } finally { first.controller.destroy() }
        val restored = fixture(Scope(), clearIntents = false, apiOverride = api, restoredState = saved)
        try {
            idleMain()
            assertEquals(sessionScope, field<Scope>(restored.activity, "scope"))
            val start = button(restored.activity, restored.activity.getString(R.string.new_start))
            assertFalse(start.isEnabled)
            val rows = allViews(field<ViewGroup>(restored.activity, "choicesView")).filterIsInstance<android.widget.RadioButton>()
            assertTrue(rows.none { it.isChecked })
            rows.single { it.text == "pi" }.performClick()
            assertTrue(start.isEnabled)
            start.performClick()
            idleMain()
            assertEquals(listOf(sessionScope), api.richLaunchScopes)
            assertEquals("pi", Json.parseToJsonElement(api.richBodies.single()).jsonObject.getValue("harness").jsonPrimitive.content)
        } finally { restored.controller.destroy() }
    }

    @Test
    fun selectedScopedSnapshotEnablesOneCorrectRichLaunchAndNoPromptsNeedsConsent() {
        val scope = Scope(host = "builder", session = "review")
        val fixture = fixture(scope)
        try {
            val activity = fixture.activity
            idleMain()
            assertTrue(fixture.api.snapshotScopes.contains(scope))
            val start = button(activity, activity.getString(R.string.new_start))
            assertTrue(start.isEnabled)
            start.performClick()
            val prompt = ShadowDialog.getLatestDialog()
            assertTrue(prompt.isShowing)
            assertTrue(texts(prompt.window!!.decorView).any { it.contains("codex") })
            assertEquals("consent must precede the guarded POST", 0, fixture.api.richLaunchCalls)
            button(prompt.window!!.decorView, activity.getString(R.string.launch_no_prompts_start)).performClick()
            idleMain()

            assertEquals(1, fixture.api.richLaunchCalls)
            assertEquals(scope, fixture.api.richLaunchScopes.single())
            val request = Json.parseToJsonElement(fixture.api.richBodies.single()).jsonObject
            assertEquals("codex", request.getValue("harness").jsonPrimitive.content)
            assertTrue(request.getValue("requestId").jsonPrimitive.content.isNotBlank())
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(PaneActivity::class.java.name, opened.component?.className)
            assertEquals("builder", opened.getStringExtra(PaneActivity.EXTRA_HOST))
            assertEquals("review", opened.getStringExtra(PaneActivity.EXTRA_SESSION))
        } finally {
            fixture.controller.destroy()
        }
    }

    @Test
    fun pinnedLauncherKeepsItsReportedFolderAndHidesOverridePicker() {
        val fixture = fixture(Scope(host = "builder", session = "review"))
        try {
            val activity = fixture.activity
            idleMain()
            val pinned = field<ViewGroup>(activity, "choicesView")
            val row = allViews(pinned).filterIsInstance<android.widget.RadioButton>().single { it.text == "Pinned Codex" }
            row.performClick()
            val cwd = field<android.widget.EditText>(activity, "cwd")
            assertEquals("/srv/pinned", cwd.text.toString())
            assertFalse(cwd.isEnabled)
            assertFalse(field<View>(activity, "pickerSlot").isShown)
            button(activity, activity.getString(R.string.new_start)).performClick()
            idleMain()
            val body = Json.parseToJsonElement(fixture.api.richBodies.single()).jsonObject
            assertEquals("/srv/pinned", body.getValue("cwd").jsonPrimitive.content)
        } finally {
            fixture.controller.destroy()
        }
    }

    @Test
    fun unknownRichLaunchSurvivesActivityRecreationAndOnlyExplicitSameIdRetryDispatches() {
        val scope = Scope(host = "builder", session = "review")
        val api = TestApi(authorized = true, rich = true).apply {
            firstRichResponse = ApiResult.Failure(ApiFailure.Network("response lost"))
        }
        val first = fixture(scope, apiOverride = api)
        try {
            idleMain()
            button(first.activity, first.activity.getString(R.string.new_start)).performClick()
            val prompt = ShadowDialog.getLatestDialog()
            button(prompt.window!!.decorView, first.activity.getString(R.string.launch_no_prompts_start)).performClick()
            idleMain()
            assertEquals(1, first.api.richLaunchCalls)
        } finally {
            first.controller.destroy()
        }

        val recreated = fixture(scope, clearIntents = false, apiOverride = api)
        try {
            idleMain()
            assertEquals(1, recreated.api.richLaunchCalls)
            assertTrue(texts(recreated.activity.window.decorView).contains(recreated.activity.getString(R.string.new_retry_same_intent)))
            button(recreated.activity, recreated.activity.getString(R.string.new_retry_same_intent)).performClick()
            idleMain()
            assertEquals(2, recreated.api.richLaunchCalls)
            val ids = recreated.api.richBodies.map {
                Json.parseToJsonElement(it).jsonObject.getValue("requestId").jsonPrimitive.content
            }
            assertEquals(ids.first(), ids.last())
            assertEquals(recreated.api.richBodies.first(), recreated.api.richBodies.last())
            assertEquals(1, recreated.api.simulatedRichEffects)
        } finally {
            recreated.controller.destroy()
        }
    }

    @Test
    fun legacyLostResponseIsDurableAndHasNoRetryActionAcrossRecreation() {
        val scope = Scope(host = "builder", session = "review")
        val api = TestApi(authorized = true, rich = false).apply {
            legacyLaunchResponse = ApiResult.Failure(ApiFailure.Network("response lost"))
        }
        val first = fixture(scope, rich = false, apiOverride = api)
        try {
            idleMain()
            button(first.activity, first.activity.getString(R.string.new_start)).performClick()
            idleMain()
            assertEquals(1, first.api.legacyLaunchCalls)
        } finally {
            first.controller.destroy()
        }

        val recreated = fixture(scope, rich = false, clearIntents = false, apiOverride = api)
        try {
            idleMain()
            assertEquals(1, recreated.api.legacyLaunchCalls)
            assertTrue(texts(recreated.activity.window.decorView).contains(recreated.activity.getString(R.string.new_legacy_unknown_no_retry)))
            assertFalse(texts(recreated.activity.window.decorView).contains(recreated.activity.getString(R.string.new_retry_same_intent)))
            assertFalse(button(recreated.activity, recreated.activity.getString(R.string.new_start)).isEnabled)
        } finally {
            recreated.controller.destroy()
        }
    }

    @Test
    fun folderStarOnlyUpdatesOnServerSuccessAndIsDisabledWithoutWriteAuthority() {
        val success = fixture(Scope(host = "builder", session = "review"))
        try {
            idleMain()
            val star = button(success.activity, success.activity.getString(R.string.folder_star))
            assertTrue(star.isEnabled)
            star.performClick()
            idleMain()
            assertEquals(Scope(host = "builder", session = "review"), success.api.starScopes.single())
            assertTrue(texts(success.activity.window.decorView).contains(success.activity.getString(R.string.folder_unstar)))
        } finally {
            success.controller.destroy()
        }

        val failed = fixture(Scope(host = "builder", session = "review"))
        failed.api.starFailure = ApiFailure.Http(409, "favourites full", "folders.favourites_full")
        try {
            idleMain()
            button(failed.activity, failed.activity.getString(R.string.folder_star)).performClick()
            idleMain()
            assertFalse(texts(failed.activity.window.decorView).contains(failed.activity.getString(R.string.folder_unstar)))
            assertTrue(texts(failed.activity.window.decorView).any { it.contains("Could not update folder favourite") })
        } finally {
            failed.controller.destroy()
        }

        val denied = fixture(Scope(host = "builder", session = "review"), authorized = false)
        try {
            idleMain()
            assertFalse(button(denied.activity, denied.activity.getString(R.string.folder_star)).isEnabled)
            assertFalse(button(denied.activity, denied.activity.getString(R.string.new_start)).isEnabled)
        } finally {
            denied.controller.destroy()
        }
    }

    @Test
    fun accessInvalidationSettlesTheMatchingActivitySpinnerAndDisablesWrites() {
        val fixture = fixture(Scope(host = "builder", session = "review"))
        try {
            fixture.api.launcherGate = CompletableDeferred()
            invoke(fixture.activity, "loadCatalog")
            assertEquals(View.VISIBLE, field<View>(fixture.activity, "progress").visibility)
            fixture.api.launcherFailure = ApiFailure.Http(403, "device not authorised", "device.not_authorised")
            fixture.api.launcherGate!!.complete(Unit)
            idleMain()
            assertEquals(View.GONE, field<View>(fixture.activity, "progress").visibility)
            assertFalse(button(fixture.activity, fixture.activity.getString(R.string.new_start)).isEnabled)
            assertTrue(texts(fixture.activity.window.decorView).contains(fixture.activity.getString(R.string.new_authority_unavailable)))
        } finally {
            fixture.controller.destroy()
        }
    }

    private data class Fixture(
        val controller: org.robolectric.android.controller.ActivityController<NewActivity>,
        val api: TestApi,
        val repository: CollieRepository,
    ) { val activity: NewActivity get() = controller.get() }

    private fun fixture(
        scope: Scope,
        authorized: Boolean = true,
        rich: Boolean = true,
        clearIntents: Boolean = true,
        apiOverride: TestApi? = null,
        restoredState: android.os.Bundle? = null,
    ): Fixture {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        if (clearIntents) app.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "test-token")
        val store = TestStore(connection)
        val api = apiOverride ?: TestApi(authorized, rich)
        val intents = LaunchIntentStore(app, TestCipher(), Json)
        val repository = CollieRepository(api, store, OriginValidator(), launchIntentStore = intents, intentJson = Json)
        val controller = Robolectric.buildActivity(NewActivity::class.java, NewActivity.intent(app, scope))
        val activity = controller.get()
        NewActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, repository)
        controller.create(restoredState).start().resume()
        idleMain()
        return Fixture(controller, api, repository)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun chooseDialogItem(index: Int) {
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NewActivity, name: String): T =
        NewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun invoke(activity: NewActivity, name: String) {
        NewActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true; invoke(activity) }
    }

    private fun button(activity: NewActivity, text: String): MaterialButton = button(activity.window.decorView, text)

    private fun button(root: View, text: String): MaterialButton = allViews(root)
        .filterIsInstance<MaterialButton>().first { it.text.toString() == text }

    private fun texts(view: View): List<String> = allViews(view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun allViews(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(allViews(view.getChildAt(index)))
    }

    private class TestStore(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
    }

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray) = CipherEnvelope(ByteArray(12) { 1 }, plaintext.map { (it.toInt() xor 0x35).toByte() }.toByteArray())
        override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x35).toByte() }.toByteArray()
    }

    private class TestApi(private val authorized: Boolean, private val rich: Boolean) : CollieApi {
        val snapshotScopes = mutableListOf<Scope>()
        val richLaunchScopes = mutableListOf<Scope>()
        val richBodies = mutableListOf<String>()
        val starScopes = mutableListOf<Scope>()
        var richLaunchCalls = 0
        var simulatedRichEffects = 0
        var legacyLaunchCalls = 0
        var firstRichResponse: ApiResult<CreateResponse> = success()
        var legacyLaunchResponse: ApiResult<CreateResponse> = ApiResult.Failure(ApiFailure.Network("unused"))
        var starFailure: ApiFailure? = null
        var launcherFailure: ApiFailure? = null
        var harnessByScope: Map<Scope, String> = emptyMap()
        var launcherGate: CompletableDeferred<Unit>? = null
        private val effected = mutableSetOf<String>()

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotScopes += scope
            return ApiResult.Success(
                SnapshotResponse(
                    bridge = "fixture",
                    device = DeviceAuthorization(enforced = true, device = "phone", authorized = authorized),
                    agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(),
                    sessions = listOf(SessionSummary("review", false, true, 0, 0, 0, "builder")),
                    servers = listOf(
                        ServerSummary("lead", "Lead", true, true, "crew", lastSeenAt = 1),
                        ServerSummary("builder", "Builder", false, true, "crew", lastSeenAt = 1),
                    ),
                    ts = 1,
                ), 200,
            )
        }
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> = error("unused")
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
        override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> = ApiResult.Success(BridgeConfigResponse(receiptContextBound = true), 200)
        override suspend fun launchers(connection: Connection, scope: Scope): ApiResult<LaunchersResponse> {
            launcherGate?.await()
            launcherFailure?.let { return ApiResult.Failure(it) }
            val currentHarness = harnessByScope[scope] ?: "codex"
            val items = listOf(
                LauncherItem("agent:codex", StartTarget(harness = currentHarness), "agents", currentHarness, "harness", currentHarness == "codex", false, true, harness = currentHarness),
                LauncherItem("pinned", StartTarget(command = "codex --resume"), "commands", "Pinned Codex", "operator", false, false, true, command = "codex --resume", cwd = "/srv/pinned"),
            )
            val legacy = if (rich) emptyList() else listOf(Launcher("codex --resume", "Codex"))
            return ApiResult.Success(LaunchersResponse(legacy, "/home/builder", items = if (rich) items else null), 200)
        }
        override suspend fun folders(connection: Connection, scope: Scope): ApiResult<FoldersResponse> =
            ApiResult.Success(FoldersResponse(listOf("/srv/recent"), emptyList(), "/home/builder"), 200)
        override suspend fun starFolder(connection: Connection, scope: Scope, folder: String, starred: Boolean): ApiResult<FoldersResponse> {
            starScopes += scope
            starFailure?.let { return ApiResult.Failure(it) }
            return ApiResult.Success(
                FoldersResponse(if (starred) emptyList() else listOf(folder), if (starred) listOf(folder) else emptyList(), "/home/builder"), 200,
            )
        }
        override suspend fun startLaunchFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
            richLaunchCalls++
            richLaunchScopes += scope
            richBodies += canonicalBody
            val requestId = Json.parseToJsonElement(canonicalBody).jsonObject.getValue("requestId").jsonPrimitive.content
            if (effected.add(requestId)) simulatedRichEffects++
            return if (richLaunchCalls == 1) firstRichResponse else success()
        }
        override suspend fun launch(connection: Connection, scope: Scope, command: String, besidePaneId: String?): ApiResult<CreateResponse> {
            legacyLaunchCalls++
            return legacyLaunchResponse
        }

        companion object {
            fun success() = ApiResult.Success(
                CreateResponse(true, CreatedPane("w1:p1", "w1", "Project", "t1", "/srv/repo")), 200,
            )
        }
    }
}
