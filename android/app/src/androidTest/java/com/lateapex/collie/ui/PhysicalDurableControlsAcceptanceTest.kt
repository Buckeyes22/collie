package com.lateapex.collie.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.DurableIntentJournal
import com.lateapex.collie.data.DurableIntentOwner
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.DurableControlRequest
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneChatResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real-server acceptance for native durable Send, in-turn Steer and Stop. No direct control API calls. */
@RunWith(AndroidJUnit4::class)
class PhysicalDurableControlsAcceptanceTest {
    @Test
    fun nativeIdleSendSteersWorkingTurnThenStopsWithoutReplayAfterRecreation() {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveDurableOrigin")
        assumeTrue("liveDurableOrigin instrumentation argument was not supplied", originText != null)
        assertTrue("Supplied liveDurableOrigin must not be blank", !originText.isNullOrBlank())
        val expectedOrigin = OriginValidator().requireValid(requireNotNull(originText))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Durable control acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("Durable controls require the existing paired connection", connection?.isPaired == true)
        assertEquals("The existing pairing must match the exact supplied origin", expectedOrigin, connection?.origin)
        val paneId = args.getString("livePaneId")?.trim()
        assertTrue("Supply the existing completed disposable durable pane", !paneId.isNullOrEmpty())
        assertTrue("Pane identifier must be bounded", requireNotNull(paneId).matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val address = PaneAddress(Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        ), requireNotNull(paneId))
        val repository = app.container.repository
        val journal = DurableIntentJournal(app)
        val owner = DurableIntentOwner.from(requireNotNull(connection), address)
        val originalScope = repository.selectedScope.value
        val originalSubject = repository.readContext()?.key?.subjectIdentity
        val sendText = "Native controls follow-up ${UUID.randomUUID()}"
        val steerText = "Native controls steer ${UUID.randomUUID()}"
        val requestIds = mutableListOf<String>()
        var currentTarget: String? = null
        try {
            assertEquals("Control acceptance cannot begin with unresolved local durable intent", DurableIntentJournal.Lookup.Empty,
                journal.read(owner))
            ActivityScenario.launch<DurableChatActivity>(DurableChatActivity.intent(app, address, "Disposable native control acceptance")).use { scenario ->
                val idlePane = awaitPane(app, address, "actual conversation is idle and has a turn") {
                    it.agent == "durable" && it.asks.orEmpty().isEmpty() && it.status == AgentStatus.IDLE && !it.turnId.isNullOrBlank()
                }
                assertEquals("The selected real pane must be the requested disposable pane", address.paneId, idlePane.paneId)
                assertEquals("The follow-up must begin while the real agent is idle", AgentStatus.IDLE, idlePane.status)
                val idleTurnId = requireNotNull(idlePane.turnId) { "The actual idle pane must report its current turn identity" }
                currentTarget = idleTurnId
                await(scenario, "the native idle composer has current live write admission") { activity ->
                    val actor = repository.readContext(address.scope, address.paneId)
                    actor != null && repository.isCurrent(actor) && actor.connection === connection &&
                        repository.writesAllowed(address.scope, address.paneId) && composer(activity).isEnabled &&
                        composer(activity).isShown && visibleText(activity).any { it == "Idle" } &&
                        checkbox(activity, activity.getString(R.string.durable_chat_steer))?.isShown != true &&
                        buttons(activity, activity.getString(R.string.durable_chat_send)).singleOrNull() != null
                }

                val firstSend = sendThroughUi(scenario, app, requireNotNull(connection), address, idleTurnId, sendText, steer = false)
                assertEquals("Native idle Send must bind the exact turn it observed", idleTurnId, firstSend.turnId)
                assertEquals("Native idle Send must preserve its exact visible text", sendText, firstSend.text)
                assertEquals("Ordinary idle Send must not carry a steer flag", null, firstSend.steer)
                requestIds += firstSend.requestId
                awaitJournalEmpty(journal, owner, "first Send settles")
                val started = awaitPane(app, address, "actual server accepts the first user turn") {
                    it.agent == "durable" && it.status == AgentStatus.WORKING && it.turnId != idleTurnId
                }
                currentTarget = requireNotNull(started.turnId) { "The actual newly started turn must have its own identity" }
                awaitWorking(scenario, app, address, currentTarget!!, "new actual server turn exposes Steer and Stop")
                val sendUuid = assertExactUserTurn(app, address, sendText)

                // Recreate while the real slow scripted turn is working. The native screen only
                // reads state; the exact user UUID must not be sent or synthesized again.
                scenario.recreate()
                await(scenario, "working state and original user turn survive recreation") { activity ->
                    visibleText(activity).any { it == sendText }
                }
                assertEquals("The same server turn must still be working after recreation", currentTarget,
                    awaitPane(app, address, "working turn after recreation") { it.status == AgentStatus.WORKING }.turnId)
                assertEquals("Activity recreation must preserve the one original server user UUID", sendUuid,
                    assertExactUserTurn(app, address, sendText))

                val steering = sendThroughUi(scenario, app, requireNotNull(connection), address, requireNotNull(currentTarget), steerText, steer = true)
                assertNotEquals("Steer must create a new exact request identity", firstSend.requestId, steering.requestId)
                assertEquals("Steer must target the still-working turn", currentTarget, steering.turnId)
                assertEquals("Steer must preserve the exact visible text", steerText, steering.text)
                assertEquals("Native working Send must carry the steer flag", true, steering.steer)
                requestIds += steering.requestId
                awaitJournalEmpty(journal, owner, "Steer settles")
                // Pi Durable queues this Steer during the scripted text generation. Its final
                // boundary places the user entry and starts a new run, so Stop must bind the
                // newly observed turn rather than reuse the turn targeted by the Steer request.
                val (steerUuid, steeredTurnId) = awaitPlacedSteer(app, address, steerText, requireNotNull(currentTarget))
                currentTarget = steeredTurnId
                // A repository read does not prove this activity has polled the new turn yet.
                // Recreate normally after observing the boundary: the new activity has no pane
                // or write admission until its own fresh snapshot and Chat reads complete.
                scenario.recreate()
                await(scenario, "fresh native screen renders the placed Steer and working status") { activity ->
                    val texts = visibleText(activity)
                    texts.any { it == steerText } && texts.any { it == "Working" }
                }
                awaitWorking(scenario, app, address, steeredTurnId, "fresh native screen admits Stop on the observed new turn")
                val stoppedNoteIdsBeforeStop = stoppedNoteIds(app, address)
                await(scenario, "native Stop button is enabled") { activity ->
                    buttons(activity, activity.getString(R.string.durable_chat_stop)).singleOrNull()?.isEnabled == true
                }

                val stop = stopThroughUi(scenario)
                assertNotEquals("Stop must use a request ID distinct from the first Send", firstSend.requestId, stop.requestId)
                assertNotEquals("Stop must use a request ID distinct from Steer", steering.requestId, stop.requestId)
                assertEquals("Stop must target the newly observed turn created when Steer was placed", currentTarget, stop.turnId)
                assertEquals("Stop must not include message text", null, stop.text)
                assertEquals("Stop must not carry a steer flag", null, stop.steer)
                requestIds += stop.requestId
                awaitJournalEmpty(journal, owner, "Stop settles")
                val stopped = awaitPane(app, address, "the actual server records stopped status") {
                    it.agent == "durable" && it.status == AgentStatus.IDLE
                }
                assertEquals("Stop must leave the actual durable pane idle", AgentStatus.IDLE, stopped.status)
                await("Stop creates a new server stopped note for an aborted input") {
                    stoppedNoteIds(app, address).any { it !in stoppedNoteIdsBeforeStop }
                }

                scenario.recreate()
                await(scenario, "stopped conversation reloads without another user turn") { activity ->
                    val texts = visibleText(activity)
                    // The latest prompt context intentionally repeats the latest user message;
                    // exact one-user-entry evidence comes from the real server UUID checks below.
                    texts.any { it == sendText } && texts.any { it == steerText }
                }
                assertEquals("Every visible action must have its own unique request identity", 3, requestIds.distinct().size)
                assertEquals("Final readback must preserve exactly one original server user UUID", sendUuid,
                    assertExactUserTurn(app, address, sendText))
                assertEquals("Final readback must preserve exactly one original steer UUID", steerUuid,
                    assertExactUserTurn(app, address, steerText))
                assertEquals("Resolved native actions must leave no unresolved local intent", DurableIntentJournal.Lookup.Empty,
                    journal.read(owner))
                android.util.Log.i(TAG, "sendCount=1 steerCount=1 stopCount=1 uniqueRequestIds=${requestIds.distinct().size} steerTargetsObservedTurn=true stopTargetsObservedNewTurn=true replayAfterRecreation=false")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.selectSubject(originalScope, originalSubject) }
        }
        assertTrue("Durable controls must preserve the original paired connection", app.container.connectionStore.connection.value === connection)
    }

    /** Read-only preparation around the actual stale Abort left by run 270. Parent kills this PID. */
    @Test
    fun holdExistingAbortCustodyForExternalProcessDeath() {
        assumeTrue("Existing Abort recovery is explicitly opt-in", InstrumentationRegistry.getArguments().getString("durableRecoveryOwnedAbort") == "true")
        val live = existingAbortRecovery()
        ActivityScenario.launch<DurableChatActivity>(DurableChatActivity.intent(live.app, live.address, "Existing native Abort recovery")).use { scenario ->
            awaitRecoveredBlock(scenario, live)
            val userIds = recoveryUserIds(live)
            assertEquals("The exact original encrypted record remains pending before death", live.record,
                (DurableIntentJournal(live.app).read(live.owner) as? DurableIntentJournal.Lookup.Pending)?.record)
            val bodyHash = recoveryHash(live.record.requestJson.encodeToByteArray())
            val ownerHash = recoveryOwnerHash(live.owner)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("durableRecoveryRequestId", live.record.requestId)
                putString("durableRecoveryBodySha256", bodyHash)
                putString("durableRecoveryOwnerSha256", ownerHash)
                putString("durableRecoveryCipherSha256", recoveryCipherHash(live))
                putString("durableRecoveryUserIdsSha256", recoveryHash(userIds.joinToString("\n").encodeToByteArray()))
                putString("durableRecoveryUserCount", userIds.size.toString())
                putString("durableRecoveryOldPid", android.os.Process.myPid().toString())
                putString("stream", "Exact existing unresolved Abort verified read-only. Parent must kill this owned app PID externally; no control was dispatched.\n")
            })
            SystemClock.sleep(30_000)
            throw AssertionError("Parent did not externally kill this process; process-death acceptance has not occurred")
        }
    }

    /** A different real process reads the same custody and uses only the shipped resolve dialog. */
    @Test
    fun resolveExistingAbortAfterActualProcessDeathWithoutReplay() {
        assumeTrue("Existing Abort recovery is explicitly opt-in", InstrumentationRegistry.getArguments().getString("durableRecoveryOwnedAbort") == "true")
        val live = existingAbortRecovery()
        val args = InstrumentationRegistry.getArguments()
        fun required(name: String) = requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) { "Supply original phase-one $name" }
        assertNotEquals("Recovery must execute in a different actual app process", required("durableRecoveryOldPid").toInt(), android.os.Process.myPid())
        assertEquals("The same exact request body survives real process death", required("durableRecoveryBodySha256"),
            recoveryHash(live.record.requestJson.encodeToByteArray()))
        assertEquals("The same original credential/scope owner survives real process death", required("durableRecoveryOwnerSha256"), recoveryOwnerHash(live.owner))
        assertEquals("The original encrypted file survives unchanged", required("durableRecoveryCipherSha256"), recoveryCipherHash(live))
        ActivityScenario.launch<DurableChatActivity>(DurableChatActivity.intent(live.app, live.address, "Existing native Abort recovery")).use { scenario ->
            awaitRecoveredBlock(scenario, live)
            val beforeIds = recoveryUserIds(live)
            assertEquals("Read-only startup must preserve the original user UUID census", required("durableRecoveryUserIdsSha256"),
                recoveryHash(beforeIds.joinToString("\n").encodeToByteArray()))
            assertEquals("Read-only startup must preserve the original user count", required("durableRecoveryUserCount").toInt(), beforeIds.size)
            assertEquals("Live reads must leave the original exact custody unchanged", live.record,
                (DurableIntentJournal(live.app).read(live.owner) as? DurableIntentJournal.Lookup.Pending)?.record)
            scenario.onActivity { activity ->
                val resolve = buttons(activity, activity.getString(R.string.durable_chat_resolve_unknown)).single()
                assertTrue("Only the normal native Check Chat action may reconcile this custody", resolve.isEnabled)
                assertTrue("The normal native Check Chat listener must open its confirmation", resolve.performClick())
            }
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withText(live.app.getString(R.string.durable_chat_resolve_unknown_detail))
            ).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withText(live.app.getString(R.string.durable_chat_resolve_unknown))
            ).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            await("Native confirmation clears only the selected exact-owner journal") {
                DurableIntentJournal(live.app).read(live.owner) == DurableIntentJournal.Lookup.Empty
            }
            await(scenario, "native custody block clears without sending a new action") { activity ->
                buttons(activity, activity.getString(R.string.durable_chat_resolve_unknown)).isEmpty() &&
                    buttons(activity, activity.getString(R.string.durable_chat_retry)).isEmpty() &&
                    buttons(activity, activity.getString(R.string.durable_chat_send)).singleOrNull()?.isEnabled == true
            }
            assertEquals("Native reconciliation must not create, remove or duplicate any actual user UUID", beforeIds, recoveryUserIds(live))
            assertEquals("Native confirmation removes only the one verified original custody file", 0,
                java.io.File(live.app.noBackupFilesDir, "durable-intents-v1").listFiles()?.count { it.isFile } ?: 0)
            assertTrue("Reconciliation preserves the original paired connection", live.app.container.connectionStore.connection.value === live.connection)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("durableRecoveryRequestId", live.record.requestId)
                putString("durableRecoveryNewPid", android.os.Process.myPid().toString())
                putString("stream", "Actual process death preserved exact encrypted Abort custody; native Chat confirmation cleared it with unchanged user UUID census. Parent verifies no new POST/receipt changes. This does not prove an accepted Stop.\n")
            })
        }
    }

    private data class AbortRecovery(
        val app: CollieApplication,
        val connection: Connection,
        val address: PaneAddress,
        val owner: DurableIntentOwner,
        val record: com.lateapex.collie.data.DurableIntentRecord,
    )

    private fun existingAbortRecovery(): AbortRecovery {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Explicit existing-Abort recovery opt-in is required", "true", args.getString("durableRecoveryOwnedAbort"))
        assertEquals("Only the existing run-270 Abort may be reconciled", RECOVERY_ABORT_ID, args.getString("durableRecoveryRequestId"))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Recovery is restricted to the isolated completion app", app.packageName.endsWith(".completiontest"))
        val expectedOrigin = OriginValidator().requireValid("https://prod.lateapexllc.com/completion-durable/")
        assertEquals("Supply the exact owned durable origin", expectedOrigin,
            OriginValidator().requireValid(requireNotNull(args.getString("liveDurableOrigin"))))
        assertEquals("Only the original disposable pane may be reconciled", "conv-70", args.getString("livePaneId"))
        assertTrue("Recovery remains on the original local host/session", args.getString("liveHost").isNullOrBlank() && args.getString("liveSession").isNullOrBlank())
        val connection = requireNotNull(app.container.connectionStore.connection.value)
        assertTrue("The existing durable connection must remain paired", connection.isPaired)
        assertEquals("Recovery cannot change pairing origin", expectedOrigin, connection.origin)
        val address = PaneAddress(Scope(), "conv-70")
        val owner = DurableIntentOwner.from(connection, address)
        val record = (DurableIntentJournal(app).read(owner) as? DurableIntentJournal.Lookup.Pending)?.record
        assertNotNull("The genuine original encrypted pending record is required; do not seed it", record)
        requireNotNull(record)
        assertEquals("The saved owner must remain the original paired scope", owner, record.owner)
        assertEquals("Only the actual unresolved Abort action is accepted", "abort", record.action)
        assertEquals("Only the exact original Abort request is accepted", RECOVERY_ABORT_ID, record.requestId)
        val request = kotlinx.serialization.json.Json.decodeFromString(DurableControlRequest.serializer(), record.requestJson)
        assertTrue("The real saved request must be a valid Abort", request.valid(send = false))
        assertEquals("The original native Abort still names its actual stale turn", "9b8fd38b28d351c9398249f2c243514a", request.turnId)
        assertEquals("Abort custody must preserve its exact request UUID", RECOVERY_ABORT_ID, request.requestId)
        return AbortRecovery(app, connection, address, owner, record)
    }

    private fun awaitRecoveredBlock(scenario: ActivityScenario<DurableChatActivity>, live: AbortRecovery) {
        await(scenario, "native unknown custody blocks controls after fresh live reads") { activity ->
            // A nonempty visible draft proves Send is blocked by custody, rather than emptiness.
            if (composer(activity).text.toString() != RECOVERY_DRAFT) composer(activity).setText(RECOVERY_DRAFT)
            visibleText(activity).any { it == activity.getString(R.string.durable_chat_unknown) } &&
                buttons(activity, activity.getString(R.string.durable_chat_send)).singleOrNull()?.isEnabled == false &&
                buttons(activity, activity.getString(R.string.durable_chat_stop)).singleOrNull()?.isEnabled == false &&
                buttons(activity, activity.getString(R.string.durable_chat_retry)).isEmpty() &&
                buttons(activity, activity.getString(R.string.durable_chat_resolve_unknown)).singleOrNull()?.isEnabled == true
        }
        assertEquals("Fresh native recovery must retain the exact genuine custody", live.record,
            (DurableIntentJournal(live.app).read(live.owner) as? DurableIntentJournal.Lookup.Pending)?.record)
    }

    private fun recoveryUserIds(live: AbortRecovery): List<String> {
        val page = requireLiveChat(live.app, live.address)
        assertTrue("The actual current durable Chat must be available", page.available)
        val ids = page.upserts.filter { it.role == "user" }.map { it.uuid }.sorted()
        assertTrue("The real bounded user census must be nonempty", ids.isNotEmpty())
        assertEquals("Each actual user UUID appears once", ids.size, ids.distinct().size)
        return ids
    }

    private fun recoveryHash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun recoveryOwnerHash(owner: DurableIntentOwner): String = recoveryHash(
        kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }
            .encodeToString(DurableIntentOwner.serializer(), owner).encodeToByteArray(),
    )

    private fun recoveryCipherHash(live: AbortRecovery): String {
        val directory = java.io.File(live.app.noBackupFilesDir, "durable-intents-v1")
        val file = java.io.File(directory, recoveryOwnerHash(live.owner))
        assertEquals("Only the one genuine original custody file may precede this recovery", listOf(file.name),
            directory.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted())
        assertTrue("The actual exact-owner encrypted file must exist within its existing bound", file.isFile && file.length() in 29L..131072L)
        return recoveryHash(file.readBytes())
    }

    private fun sendThroughUi(
        scenario: ActivityScenario<DurableChatActivity>,
        app: CollieApplication,
        originalConnection: Connection,
        address: PaneAddress,
        turnId: String,
        text: String,
        steer: Boolean,
    ): DurableControlRequest {
        val repository = app.container.repository
        val actor = requireNotNull(repository.readContext(address.scope, address.paneId))
        assertTrue("Only the original paired profile may prepare a control", actor.connection === originalConnection)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertTrue("The original actor belongs to the committed paired incarnation", committed.connection === originalConnection && committed.incarnationId != null)
        fun assertOwner() {
            assertTrue("The original actor remains current while preparing this one Send", repository.isCurrent(actor))
            assertTrue("The original committed pair remains unchanged before Send", committed == app.container.connectionStore.captureCommitted())
            assertTrue("Only the original paired connection may prepare this Send", app.container.connectionStore.connection.value === actor.connection)
        }
        assertOwner()
        var capturedRecord: com.lateapex.collie.data.DurableIntentRecord? = null
        scenario.onActivity { activity ->
            assertOwner()
            val composer = composer(activity)
            val steerControl = checkbox(activity, activity.getString(R.string.durable_chat_steer))
            if (steer) {
                assertTrue("Steer must be available on the actual working turn", steerControl?.isShown == true && steerControl.isEnabled)
                steerControl?.isChecked = true
            } else {
                assertFalse("Idle Send must not expose a steer selection", steerControl?.isShown == true)
            }
            composer.setText(text)
        }
        // Draft/steer presentation is updated by the screen's normal UI refresh. Wait only for
        // reads and visible readiness; never repeat the Send or manufacture write admission.
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var ready = false
        while (SystemClock.uptimeMillis() < deadline) {
            assertOwner()
            val pane = currentPane(app, address, actor)
            assertOwner()
            assertEquals("Preparing a Send cannot silently change its exact server turn", turnId, pane?.turnId)
            assertEquals("Send must retain the expected idle/working state", if (steer) AgentStatus.WORKING else AgentStatus.IDLE, pane?.status)
            scenario.onActivity { activity ->
                assertOwner()
                val steerControl = checkbox(activity, activity.getString(R.string.durable_chat_steer))
                ready = repository.writesAllowed(address.scope, address.paneId) && composer(activity).text.toString() == text &&
                    composer(activity).isEnabled &&
                    (if (steer) steerControl?.let { it.isShown && it.isEnabled && it.isChecked } == true else steerControl?.isShown != true) &&
                    buttons(activity, activity.getString(R.string.durable_chat_send)).singleOrNull()?.isEnabled == true
            }
            if (ready) break
            SystemClock.sleep(100)
        }
        assertTrue("The real native Send must become enabled with this exact owner, turn and draft", ready)
        scenario.onActivity { activity ->
            assertOwner()
            assertEquals("The native draft remains exact at the sole dispatch", text, composer(activity).text.toString())
            val steerControl = checkbox(activity, activity.getString(R.string.durable_chat_steer))
            assertTrue("The native steering selection remains exact at dispatch",
                if (steer) steerControl?.let { it.isShown && it.isEnabled && it.isChecked } == true else steerControl?.isShown != true)
            val send = buttons(activity, activity.getString(R.string.durable_chat_send)).single()
            assertTrue("The actual native Send control must be enabled for this current turn", send.isEnabled)
            assertTrue("Send must dispatch through the actual button listener", send.performClick())
            capturedRecord = pendingFrom(activity, "send")
        }
        val record = requireNotNull(capturedRecord) { "Native Send must synchronously reserve its local request" }
        val request = kotlinx.serialization.json.Json.decodeFromString(DurableControlRequest.serializer(), record.requestJson)
        assertEquals("The request UUID must be the one synchronously reserved by native UI", record.requestId, request.requestId)
        return request
    }

    private fun stopThroughUi(scenario: ActivityScenario<DurableChatActivity>): DurableControlRequest {
        var capturedRecord: com.lateapex.collie.data.DurableIntentRecord? = null
        scenario.onActivity { activity ->
            val button = buttons(activity, activity.getString(R.string.durable_chat_stop)).single()
            assertTrue("Stop must be enabled for the current working turn", button.isEnabled)
            assertTrue("Stop must dispatch through the actual native button listener", button.performClick())
            capturedRecord = pendingFrom(activity, "abort")
        }
        val record = requireNotNull(capturedRecord) { "Native Stop must synchronously reserve its local request" }
        val request = kotlinx.serialization.json.Json.decodeFromString(DurableControlRequest.serializer(), record.requestJson)
        assertEquals("The request UUID must be the one synchronously reserved by native UI", record.requestId, request.requestId)
        return request
    }

    /** Read synchronously in the same UI callback as performClick, before the response can clear it. */
    private fun pendingFrom(activity: DurableChatActivity, action: String): com.lateapex.collie.data.DurableIntentRecord {
        val app = activity.application as CollieApplication
        val address = PaneAddress(Scope(
            host = activity.intent.getStringExtra("host")?.trim()?.takeIf(String::isNotEmpty),
            session = activity.intent.getStringExtra("session")?.trim()?.takeIf(String::isNotEmpty),
        ), requireNotNull(activity.intent.getStringExtra("pane_id")))
        val connection = requireNotNull(app.container.connectionStore.connection.value)
        val owner = DurableIntentOwner.from(connection, address)
        val result = DurableIntentJournal(app).read(owner)
        assertTrue("Native action must synchronously reserve its exact local intent", result is DurableIntentJournal.Lookup.Pending)
        val record = (result as DurableIntentJournal.Lookup.Pending).record
        assertEquals("The observed intent must match the action dispatched by the UI", action, record.action)
        return record
    }

    private fun awaitJournalEmpty(journal: DurableIntentJournal, owner: DurableIntentOwner, checkpoint: String) {
        await(checkpoint) { journal.read(owner) == DurableIntentJournal.Lookup.Empty }
    }

    private fun awaitPane(
        app: CollieApplication,
        address: PaneAddress,
        checkpoint: String,
        ready: (com.lateapex.collie.network.PaneSummary) -> Boolean,
    ): com.lateapex.collie.network.PaneSummary {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            currentPane(app, address)?.takeIf(ready)?.let { return it }
            SystemClock.sleep(750)
        }
        throw AssertionError("Physical durable controls timed out: $checkpoint")
    }

    private fun awaitWorking(
        scenario: ActivityScenario<DurableChatActivity>,
        app: CollieApplication,
        address: PaneAddress,
        turnId: String,
        checkpoint: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val pane = currentPane(app, address)
            var controlsReady = false
            scenario.onActivity { activity ->
                val actor = app.container.repository.readContext(address.scope, address.paneId)
                controlsReady = actor != null && app.container.repository.isCurrent(actor) &&
                    app.container.repository.writesAllowed(address.scope, address.paneId) &&
                    checkbox(activity, activity.getString(R.string.durable_chat_steer))
                    ?.let { it.isShown && it.isEnabled } == true &&
                    buttons(activity, activity.getString(R.string.durable_chat_stop)).singleOrNull()?.isEnabled == true
            }
            if (pane?.status == AgentStatus.WORKING && pane.turnId == turnId && controlsReady) return
            SystemClock.sleep(500)
        }
        throw AssertionError("Physical durable controls timed out: $checkpoint")
    }

    /** Reads only: a queued Steer becomes a user entry at the official final boundary. */
    private fun awaitPlacedSteer(
        app: CollieApplication,
        address: PaneAddress,
        text: String,
        originalTurnId: String,
    ): Pair<String, String> {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val page = requireLiveChat(app, address)
            val matching = page.upserts.filter {
                it.role == "user" && it.parts.filter { part -> part.kind == "text" }
                    .joinToString("\n") { part -> part.text.orEmpty() } == text
            }
            assertTrue("The exact Steer must never produce duplicate server user UUIDs", matching.size <= 1)
            val pane = currentPane(app, address)
            if (matching.size == 1 && pane?.status == AgentStatus.WORKING && !pane.turnId.isNullOrBlank() &&
                pane.turnId != originalTurnId) {
                return matching.single().uuid to requireNotNull(pane.turnId)
            }
            SystemClock.sleep(500)
        }
        throw AssertionError("Physical durable controls timed out: actual Steer user UUID and newly working server turn")
    }

    private fun assertExactUserTurn(app: CollieApplication, address: PaneAddress, text: String): String {
        val page = requireLiveChat(app, address)
        val matching = page.upserts.filter {
            it.role == "user" && it.parts.filter { part -> part.kind == "text" }.joinToString("\n") { part -> part.text.orEmpty() } == text
        }
        assertEquals("A real server Chat read must contain exactly one matching user turn UUID", 1, matching.size)
        return matching.single().uuid
    }

    private fun currentPane(app: CollieApplication, address: PaneAddress): com.lateapex.collie.network.PaneSummary? {
        val repository = app.container.repository
        val actor = repository.readContext(address.scope, address.paneId) ?: return null
        return currentPane(app, address, actor)
    }

    private fun currentPane(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext): com.lateapex.collie.network.PaneSummary? {
        val repository = app.container.repository
        val result = runBlocking { repository.snapshot(address.scope, actor) }
        if (result !is ApiResult.Success || !repository.isCurrent(actor)) return null
        return (result.value.agents + result.value.shellPanes).singleOrNull {
            it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
        }
    }

    private fun requireLiveChat(app: CollieApplication, address: PaneAddress): PaneChatResponse {
        val repository = app.container.repository
        val actor = requireNotNull(repository.readContext(address.scope, address.paneId)) { "Current durable pane must have a read actor" }
        val result = runBlocking { repository.chat(address, limit = 200, expectedContext = actor) }
        assertTrue("Read-only real-server Chat must succeed under the current actor", result is ApiResult.Success)
        assertTrue("The current actor must remain current after Chat", repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }

    private fun stoppedNoteIds(app: CollieApplication, address: PaneAddress): Set<String> {
        val repository = app.container.repository
        val actor = repository.readContext(address.scope, address.paneId) ?: return emptySet()
        val result = runBlocking { repository.chat(address, limit = 200, expectedContext = actor) }
        if (result !is ApiResult.Success || !repository.isCurrent(actor)) return emptySet()
        return result.value.upserts.filter { entry ->
            entry.role == "note" && entry.parts.any { it.kind == "text" && it.text == STOPPED_TEXT }
        }.map { it.uuid }.toSet()
    }

    private fun buttons(activity: DurableChatActivity, label: String): List<MaterialButton> = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<MaterialButton>().filter { it.isShown && it.text.toString() == label }.toList()

    private fun checkbox(activity: DurableChatActivity, label: String): CheckBox? = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<CheckBox>().singleOrNull { it.text.toString() == label }

    private fun composer(activity: DurableChatActivity): EditText = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<EditText>().single { it.isShown && it.hint.toString() == activity.getString(R.string.durable_chat_send_hint) }

    private fun visibleText(activity: DurableChatActivity): List<String> = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun <T : android.app.Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical durable controls timed out: $checkpoint")
    }

    private fun await(checkpoint: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical durable controls timed out: $checkpoint")
    }

    companion object {
        private const val WAIT_MS = 45_000L
        private const val TAG = "CollieDurableControls"
        private const val RECOVERY_ABORT_ID = "2c3ec323-9517-4ae6-b7b7-817ed8ef9648"
        private const val RECOVERY_DRAFT = "Recovery read-only draft; never dispatch"
        private const val STOPPED_TEXT = "stopped"
    }
}
