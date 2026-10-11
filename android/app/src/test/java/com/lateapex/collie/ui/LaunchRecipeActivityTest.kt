package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import android.content.DialogInterface
import android.widget.CheckBox

@RunWith(RobolectricTestRunner::class)
class LaunchRecipeActivityTest {
    @Test
    fun historySelectionOnlyFillsExactCommandAndNeverDispatchesAutomatically() {
        val fixture = fixture(runEnabled = true)
        try {
            val activity = fixture.activity
            val review = allViews(activity.window.decorView).filterIsInstance<MaterialButton>()
                .first { it.text == activity.getString(R.string.recipes_replay_fill) }

            review.performClick()
            idleMain()

            val editor = allViews(activity.window.decorView).filterIsInstance<EditText>()
                .first { it.hint == activity.getString(R.string.recipes_run_input) }
            assertEquals("printf '%s' \"${'$'}HOME/a b\"", editor.text.toString())
            assertEquals(0, fixture.api.runDispatches)
        } finally {
            fixture.controller.destroy()
        }
    }

    @Test
    fun missingPositiveRunPolicyKeepsRunDisabledEvenWhenHistoryHasAnAvailableRow() {
        val fixture = fixture(runEnabled = false)
        try {
            val activity = fixture.activity
            val run = allViews(activity.window.decorView).filterIsInstance<MaterialButton>()
                .first { it.text == activity.getString(R.string.recipes_run) }
            assertFalse(run.isEnabled)
            assertEquals(0, fixture.api.runDispatches)
        } finally {
            fixture.controller.destroy()
        }
    }

    @Test
    fun noPromptsRunRequiresSecondConfirmationAndSendsReviewedTextAndFolder() {
        val fixture = fixture(runEnabled = true, noPrompts = true)
        try {
            val activity = fixture.activity
            val command = allViews(activity.window.decorView).filterIsInstance<EditText>().first { it.hint == activity.getString(R.string.recipes_run_input) }
            val cwd = allViews(activity.window.decorView).filterIsInstance<EditText>().first { it.hint == activity.getString(R.string.recipes_run_cwd) }
            val exact = "printf '%s' \"${'$'}HOME/path with spaces\""
            command.setText(exact)
            cwd.setText("/work with spaces")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_run) }.performClick()
            val confirmation = latestDialog()
            assertTrue(confirmation.findViewById<TextView>(android.R.id.message)!!.text.contains(exact))
            assertTrue(confirmation.findViewById<TextView>(android.R.id.message)!!.text.contains("/work with spaces"))
            confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.runDispatches)

            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_run) }.performClick()
            val second = latestDialog()
            assertTrue(second.findViewById<TextView>(android.R.id.message)!!.text.contains(exact))
            assertTrue(second.findViewById<TextView>(android.R.id.message)!!.text.contains("/work with spaces"))
            second.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.runDispatches == 1 }
            assertEquals(1, fixture.api.runDispatches)
            val body = Json.parseToJsonElement(fixture.api.runBody!!).jsonObject
            assertEquals(exact, body.getValue("run").jsonPrimitive.content)
            assertEquals("/work with spaces", body.getValue("cwd").jsonPrimitive.content)
            assertEquals(36, body.getValue("requestId").jsonPrimitive.content.length)
            awaitMain { allViews(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.recipes_retry_run) } }
            val frozenRetry = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_retry_run) }
            assertTrue(frozenRetry.isShown)
            assertFalse(frozenRetry.isEnabled)
            awaitMain { allViews(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.recipes_reload) && it.isEnabled } }
            val reload = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_reload) }
            assertTrue(reload.isShown)
            reload.performClick()
            awaitMain { allViews(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.recipes_retry_run) && it.isShown && it.isEnabled } }
            assertEquals(1, fixture.api.runDispatches) // Reload is read-only: no implicit replay.
            val retry = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_retry_run) }
            assertTrue(retry.isShown)
            assertTrue(retry.isEnabled)
            retry.performClick()
            val retryDialog = latestDialog()
            assertTrue(retryDialog.findViewById<TextView>(android.R.id.message)!!.text.contains(exact))
            retryDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.runDispatches == 2 }
            assertEquals(2, fixture.api.runDispatches)
            assertEquals(fixture.api.runBody, fixture.api.firstRunBody)
        } finally { fixture.controller.destroy() }
    }

    @Test
    fun staleRunConfirmationDoesNotDispatchAndUnsentTextIsNotSavedInBundle() {
        val fixture = fixture(runEnabled = true, noPrompts = false)
        try {
            val activity = fixture.activity
            val command = allViews(activity.window.decorView).filterIsInstance<EditText>().first { it.hint == activity.getString(R.string.recipes_run_input) }
            val secret = "printf secret-token-should-not-be-saved"
            command.setText(secret)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_run) }.performClick()
            val dialog = latestDialog()
            assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.contains(secret))
            val bundle = Bundle()
            fixture.controller.saveInstanceState(bundle)
            assert(!bundle.toString().contains(secret))
            fixture.store.clear()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.runDispatches)
        } finally { fixture.controller.destroy() }
    }

    @Test
    fun typedNoPromptsAddCancelAndConfirmAreActivityGated() {
        val fixture = fixture(runEnabled = true)
        try {
            val activity = fixture.activity
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_add_text) }.performClick()
            (latestDialog()).getListView().performItemClick(null, 0, 0)
            val edit = latestDialog()
            val fields = allViews(edit.window!!.decorView).filterIsInstance<EditText>()
            fields.first { it.hint == activity.getString(R.string.recipes_command) }.setText("agent --safe")
            fields.first { it.hint == activity.getString(R.string.recipes_add_label) }.setText("Safe agent")
            allViews(edit.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
            edit.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            val noPrompts = latestDialog()
            assertTrue(noPrompts.findViewById<TextView>(android.R.id.message)!!.text.contains("agent --safe"))
            noPrompts.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.addDispatches)

            // Reopen and positively acknowledge the same declared no-prompts operation.
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_add_text) }.performClick()
            (latestDialog()).getListView().performItemClick(null, 0, 0)
            val secondEdit = latestDialog()
            val secondFields = allViews(secondEdit.window!!.decorView).filterIsInstance<EditText>()
            secondFields.first { it.hint == activity.getString(R.string.recipes_command) }.setText("agent --safe")
            secondFields.first { it.hint == activity.getString(R.string.recipes_add_label) }.setText("Safe agent")
            allViews(secondEdit.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
            secondEdit.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            (latestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.addDispatches == 1 }
            assertEquals(1, fixture.api.addDispatches)
            val added = Json.parseToJsonElement(fixture.api.addBody!!).jsonObject
            assertEquals("agent --safe", added.getValue("text").jsonPrimitive.content)
            assertEquals(true, added.getValue("noPrompts").jsonPrimitive.content.toBoolean())
        } finally { fixture.controller.destroy() }
    }

    @Test
    fun renameRemoveAndRecentHistoryMutationsAreDrivenByTheirDialogs() {
        val fixture = fixture(runEnabled = true)
        try {
            val activity = fixture.activity
            val rename = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_rename) }
            rename.performClick()
            val renameDialog = latestDialog()
            renameDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.renameDispatches)
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_rename) }.performClick()
            val positiveRename = latestDialog()
            allViews(positiveRename.window!!.decorView).filterIsInstance<EditText>().single().setText("Renamed safely")
            positiveRename.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.renameDispatches == 1 }
            assertEquals(1, fixture.api.renameDispatches)

            val removeLauncher = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_remove) }
            removeLauncher.performClick()
            val removeDialog = latestDialog()
            assertTrue(removeDialog.findViewById<TextView>(android.R.id.message)!!.text.contains("Phone-added"))
            removeDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.removeLauncherDispatches)
            allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_remove) }.performClick()
            (latestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.removeLauncherDispatches == 1 }
            assertEquals(1, fixture.api.removeLauncherDispatches)

            val removeRecent = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_recent_remove) }
            removeRecent.performClick()
            val recentDialog = latestDialog()
            assertTrue(recentDialog.findViewById<TextView>(android.R.id.message)!!.text.contains("printf"))
            recentDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.removeRecentDispatches == 1 }
            assertEquals(1, fixture.api.removeRecentDispatches)

            val clear = allViews(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == activity.getString(R.string.recipes_recent_clear) }
            clear.performClick()
            val clearDialog = latestDialog()
            clearDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            idleMain()
            assertEquals(0, fixture.api.clearRecentDispatches)
            clear.performClick()
            (latestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idleMain()
            awaitMain { fixture.api.clearRecentDispatches == 1 }
            assertEquals(1, fixture.api.clearRecentDispatches)
        } finally { fixture.controller.destroy() }
    }

    private data class Fixture(
        val controller: org.robolectric.android.controller.ActivityController<LaunchRecipeActivity>,
        val api: TestApi,
        val store: TestStore,
    ) { val activity: LaunchRecipeActivity get() = controller.get() }

    private fun fixture(runEnabled: Boolean, noPrompts: Boolean = false): Fixture {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        app.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "test-token")
        val api = TestApi(runEnabled, noPrompts)
        val store = TestStore(connection)
        val repository = CollieRepository(
            api, store, OriginValidator(), launchIntentStore = LaunchIntentStore(app, TestCipher(), Json), intentJson = Json,
        )
        val controller = Robolectric.buildActivity(LaunchRecipeActivity::class.java, LaunchRecipeActivity.intent(app, Scope(), LaunchRecipeActivity.MODE_RUN))
        LaunchRecipeActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(controller.get(), repository)
        controller.create().start().resume().visible()
        idleMain()
        return Fixture(controller, api, store)
    }

    private fun latestDialog(): androidx.appcompat.app.AlertDialog {
        // Android posts OnShowListener; advance it before driving the installed product buttons.
        idleMain()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as androidx.appcompat.app.AlertDialog
        assertTrue("The product dialog must actually be showing", dialog.isShowing)
        return dialog
    }

    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        do {
            idleMain()
            if (predicate()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("The actual Activity operation did not finish within the fixture bound", predicate())
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

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

    private class TestApi(private val runEnabled: Boolean, private val noPrompts: Boolean) : CollieApi {
        var runDispatches = 0
        var runBody: String? = null
        var firstRunBody: String? = null
        var addDispatches = 0
        var addBody: String? = null
        var renameDispatches = 0
        var removeLauncherDispatches = 0
        var removeRecentDispatches = 0
        var clearRecentDispatches = 0
        override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(receiptContextBound = true), 200)
        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = ApiResult.Success(
            SnapshotResponse(
                bridge = "fixture", device = DeviceAuthorization(enforced = true, device = "phone", authorized = true),
                agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1,
            ), 200,
        )
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> = error("unused")
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
        override suspend fun launchers(connection: Connection, scope: Scope): ApiResult<LaunchersResponse> = ApiResult.Success(
            LaunchersResponse(
                launchers = listOf(Launcher("agent --safe", "Phone-added", source = "added", id = "row-id", addedBy = "phone", addedAs = "command")), home = "/home/operator",
                adding = LaunchersAdding(true, true, runEnabled, "launchers.toml", 0, 20, emptyList()),
                recentRuns = listOf(RecentRun("printf '%s' \"${'$'}HOME/a b\"", "/work tree", 1_700_000_000_000, false, true)),
            ), 200,
        )
        override suspend fun checkRun(connection: Connection, scope: Scope, run: String): ApiResult<LaunchCheckResponse> {
            return ApiResult.Success(LaunchCheckResponse(ok = true, noPrompts = noPrompts), 200)
        }
        override suspend fun startRunFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
            runDispatches++
            runBody = canonicalBody
            if (firstRunBody == null) firstRunBody = canonicalBody
            return ApiResult.Failure(ApiFailure.Network("unexpected run dispatch"))
        }
        override suspend fun addLauncherFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<AddedLauncherResponse> {
            addDispatches++
            addBody = canonicalBody
            return ApiResult.Failure(ApiFailure.Network("fixture lost response"))
        }
        override suspend fun renameAddedLauncher(connection: Connection, scope: Scope, id: String, label: String): ApiResult<AddedLauncherResponse> {
            renameDispatches++
            return ApiResult.Success(AddedLauncherResponse(ok = true), 200)
        }
        override suspend fun removeAddedLauncher(connection: Connection, scope: Scope, id: String): ApiResult<AddedLauncherMutationResponse> {
            removeLauncherDispatches++
            return ApiResult.Success(AddedLauncherMutationResponse(ok = true, removed = 1), 200)
        }
        override suspend fun removeRecentRun(connection: Connection, scope: Scope, line: String): ApiResult<RecentRunsResponse> {
            removeRecentDispatches++
            return ApiResult.Success(RecentRunsResponse(ok = true, removed = 1), 200)
        }
        override suspend fun clearRecentRuns(connection: Connection, scope: Scope): ApiResult<RecentRunsResponse> {
            clearRecentDispatches++
            return ApiResult.Success(RecentRunsResponse(ok = true, removed = 1), 200)
        }
    }
}
