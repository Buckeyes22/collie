package com.lateapex.collie.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.CreatedPane
import com.lateapex.collie.network.StartTarget
import com.lateapex.collie.network.WorktreeOpenResponse
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AddedLauncherResponse
import com.lateapex.collie.network.AudioUpload
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.ChangesListAnswer
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.NotificationState
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneProcessesResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.UpdateStartResponse
import com.lateapex.collie.network.UpdateDismissalScope
import com.lateapex.collie.network.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
class CollieRepositoryTest {
    private val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
    private val store = FakeStore(connection)
    private val api = FakeApi()
    private val repository = CollieRepository(api, store, OriginValidator())

    @Test
    fun processInspectionRequiresPositiveScopedMuxCapability() = runBlocking {
        val address = PaneAddress(Scope(host = "desk", session = "work"), "w1:p1")
        api.configResult = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = emptyMap())), 200)
        val unsupported = repository.paneProcesses(address)
        assertTrue(unsupported is ApiResult.Failure)
        assertEquals(0, api.processCalls)

        api.configResult = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("paneProcesses" to true))), 200)
        assertTrue(repository.paneProcesses(address) is ApiResult.Success)
        assertEquals(1, api.processCalls)
        assertEquals(address, api.processAddress)
    }

    @Test
    fun writeAuthorityCannotCarryOverToAnotherPairingOnTheSameServer() = runBlocking {
        authorizeWrites()
        assertTrue(repository.writesAllowed())
        store.save(Connection(connection.origin, "other phone", "different-token"))
        assertFalse(repository.writesAllowed())
        assertTrue(repository.reply(PaneAddress(paneId = "w1:p1"), "must not send") is ApiResult.Failure)
        assertEquals(0, api.replyCalls)
        repository.snapshot()
        assertTrue(repository.writesAllowed())
    }

    @Test
    fun oversizedDeviceLabelIsRejectedBeforePairing() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.pair(connection.origin.value, "x".repeat(49), "fake-code") }
        }
    }

    @Test
    fun etagAndBodyAreReusedOnlyForTheExactScopedPane() = runBlocking {
        val desk = PaneAddress(Scope(host = "desk", session = "work"), "w1:p1")
        val laptop = PaneAddress(Scope(host = "laptop", session = "work"), "w1:p1")

        val first = repository.readPane(desk) as ApiResult.Success
        val unchanged = repository.readPane(desk) as ApiResult.Success
        repository.readPane(laptop)

        assertFalse(first.value.notModified)
        assertTrue(unchanged.value.notModified)
        assertEquals("screen-desk", unchanged.value.pane.text)
        assertEquals(listOf(null, "\"desk\"", null), api.paneEtags)
    }

    @Test
    fun paneEtagIsNotReusedAcrossDifferentRequestedLineWindows() = runBlocking {
        val pane = PaneAddress(paneId = "w1:p1")

        repository.readPane(pane, lines = 600)
        repository.readPane(pane, lines = 1_000)
        repository.readPane(pane, lines = 1_000)

        assertEquals(listOf(600, 1_000, 1_000), api.paneLines)
        assertEquals(listOf(null, null, "\"local\""), api.paneEtags)
    }

    @Test
    fun hostileBoundedReadMetadataIsRejectedBeforeItCanGrowTheWindow() = runBlocking {
        api.paneValue = PaneReadResponse(
            "w1:p1", "two\nrows", false, 1,
            requestedLines = 600,
            servedLines = 601,
            readableLines = 1_000_000,
            limitReason = "future-unbounded",
        )

        val result = repository.readPane(PaneAddress(paneId = "w1:p1"), lines = 600)

        assertTrue(result is ApiResult.Failure)
        assertEquals(
            ApiFailure.Protocol("Pane read returned inconsistent bounded-read metadata"),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun ambiguousMutationFailureIsReturnedAfterExactlyOneAttempt() = runBlocking {
        authorizeWrites()
        api.replyResult = ApiResult.Failure(
            ApiFailure.Http(502, "submit failed", "reply.not_submitted", textDelivered = true),
        )

        val result = repository.reply(PaneAddress(paneId = "w1:p1"), "hello")

        assertTrue(result is ApiResult.Failure)
        assertEquals(1, api.replyCalls)
        assertTrue(((result as ApiResult.Failure).error as ApiFailure.Http).textDelivered)
    }

    @Test
    fun arbitraryTerminalKeysAreRejectedBeforeTransport() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                authorizeWrites()
                repository.keys(PaneAddress(paneId = "w1:p1"), listOf("raw-escape"))
            }
        }
        assertEquals(0, api.keysCalls)
    }

    @Test
    fun tokenlessConnectionCannotReachEitherWriteTransport() = runBlocking {
        val readOnlyApi = FakeApi()
        val readOnly = CollieRepository(
            readOnlyApi,
            FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", null)),
            OriginValidator(),
        )

        val reply = readOnly.reply(PaneAddress(paneId = "w1:p1"), "hello")
        val keys = readOnly.keys(PaneAddress(paneId = "w1:p1"), listOf("Enter"))
        val speech = readOnly.transcribe(AudioUpload("audio/mp4", byteArrayOf(1)))

        assertTrue(reply is ApiResult.Failure)
        assertTrue(keys is ApiResult.Failure)
        assertTrue(speech is ApiResult.Failure)
        assertEquals(0, readOnlyApi.replyCalls)
        assertEquals(0, readOnlyApi.keysCalls)
        assertEquals(0, readOnlyApi.transcribeCalls)
    }

    @Test
    fun promptBindingEvidenceReachesBothWriteCalls() = runBlocking {
        authorizeWrites()
        val address = PaneAddress(paneId = "w1:p1")

        repository.reply(address, "hello", expectedPrompt = "prompt region")
        repository.keys(address, listOf("Enter"), expectedPrompt = "prompt region")

        assertEquals("prompt region", api.replyExpectedPrompt)
        assertEquals("prompt region", api.keysExpectedPrompt)
    }

    @Test
    fun pairedTranscriptionCrossesTheWriteBoundaryExactlyOnce() = runBlocking {
        authorizeWrites()
        val result = repository.transcribe(AudioUpload("audio/mp4", byteArrayOf(1, 2)))

        assertEquals("draft", (result as ApiResult.Success).value.text)
        assertEquals(1, api.transcribeCalls)
    }

    @Test
    fun readLevelPreferencePostWorksWithoutTokenButUpdateAndRevokeDoNot() = runBlocking {
        val fake = FakeApi()
        val readOnly = CollieRepository(
            fake,
            FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", null)),
            OriginValidator(),
        )

        val snooze = readOnly.setNotificationSnooze(null)
        val update = readOnly.startUpdate("1.6.0", major = false)
        val revoke = readOnly.revokeDevice("old phone")

        assertTrue(snooze is ApiResult.Success)
        assertTrue(update is ApiResult.Failure)
        assertTrue(revoke is ApiResult.Failure)
        assertEquals(1, fake.snoozeCalls)
        assertEquals(0, fake.updateCalls)
        assertEquals(0, fake.revokeCalls)
    }

    @Test
    fun updateDismissalUsesPairedDeviceAdmissionAndPreservesExactScope() = runBlocking {
        val result = repository.dismissUpdate("1.8.0", UpdateDismissalScope.CREW)
        assertEquals("1.8.0", (result as ApiResult.Success).value.dismissedCrewVersion)
        assertEquals("1.8.0", api.dismissedVersion)
        assertEquals(UpdateDismissalScope.CREW, api.dismissedScope)
        assertEquals(1, api.dismissCalls)

        val unpairedApi = FakeApi()
        val unpaired = CollieRepository(
            unpairedApi,
            FakeStore(Connection(connection.origin, "phone", null)),
            OriginValidator(),
        )
        assertTrue(unpaired.dismissUpdate("1.8.0", UpdateDismissalScope.OFFER) is ApiResult.Failure)
        assertEquals(0, unpairedApi.dismissCalls)
    }

    @Test
    fun disconnectClearsCredentialScopeAndCachedBodies() = runBlocking {
        repository.selectScope(Scope("desk", "work"))
        repository.readPane(PaneAddress(Scope("desk", "work"), "w1:p1"))

        repository.disconnect()

        assertNull(repository.connection.value)
        assertEquals(Scope(), repository.selectedScope.value)
        assertTrue(store.cleared)
        assertEquals(DeviceWriteAuthorization.UNVERIFIED, repository.deviceWriteAuthorization.value)
    }

    @Test
    fun selfRevocationDemotesToReadOnlyWithoutForgettingTheOrigin() {
        repository.demoteToReadOnly()

        assertEquals("https://collie.example/", repository.connection.value?.origin?.value)
        assertEquals("phone", repository.connection.value?.label)
        assertNull(repository.connection.value?.token)
        assertFalse(store.cleared)
        assertEquals(DeviceWriteAuthorization.UNVERIFIED, repository.deviceWriteAuthorization.value)
    }

    @Test
    fun deviceWriteGateFreezesAfterAnUnsuccessfulSnapshot() = runBlocking {
        assertFalse(repository.writesAllowed())
        assertTrue(repository.reply(PaneAddress(paneId = "w1:p1"), "blocked") is ApiResult.Failure)
        assertEquals(0, api.replyCalls)

        api.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.AUTHORIZED, repository.deviceWriteAuthorization.value)
        assertTrue(repository.writesAllowed())

        api.snapshotFailure = true
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.UNVERIFIED, repository.deviceWriteAuthorization.value)
        assertFalse(repository.writesAllowed())
    }

    @Test
    fun legacyAndNonEnforcedSnapshotsRemainCompatibleButEnforcedDenialFailsClosed() = runBlocking {
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.LEGACY_OR_NOT_ENFORCED, repository.deviceWriteAuthorization.value)
        assertTrue(repository.writesAllowed())

        api.snapshotDevice = DeviceAuthorization(enforced = false, device = null, authorized = false)
        repository.snapshot()
        assertTrue(repository.writesAllowed())

        api.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = false)
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.DENIED, repository.deviceWriteAuthorization.value)
        assertFalse(repository.writesAllowed())
        assertTrue(repository.startUpdate("1.6.0", major = false) is ApiResult.Failure)
        assertEquals(0, api.updateCalls)
    }

    @Test
    fun narrowSnapshotsRecoverOnlyTheRequestedSessionAndLocalLeadAlias() = runBlocking {
        val local = PaneSummary("p1", "w1", "work", 1, "t1", "codex", AgentStatus.IDLE, "/repo", false, host = "lead")
        val peer = local.copy(host = "peer", session = "other")
        api.snapshotValue = SnapshotResponse("1.19.0", agents = listOf(local, peer), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), servers = listOf(
            ServerSummary("lead", "Local", true, true, "ok", lastSeenAt = 1),
            ServerSummary("peer", "Peer", false, true, "ok", lastSeenAt = 1),
        ), ts = 1)
        val narrowed = (repository.paneSnapshot(Scope(session = "work")) as ApiResult.Success).value.agents
        assertEquals("work", narrowed[0].session)
        assertNull(narrowed[0].host)
        assertEquals("other", narrowed[1].session)
        assertEquals("peer", narrowed[1].host)
        val explicit = (repository.paneSnapshot(Scope(host = "lead", session = "work")) as ApiResult.Success).value.agents
        assertEquals("lead", explicit[0].host)
        val wide = (repository.snapshot(Scope(viewAll = true)) as ApiResult.Success).value.agents
        assertNull(wide[0].session)
    }

    @Test
    fun expiredReadRevokesLocalWriteAuthorityAndDropsCachedScreens() = runBlocking {
        authorizeWrites()
        val address = PaneAddress(paneId = "p1")
        repository.readPane(address)
        api.paneFailure = ApiFailure.Http(403, "device expired", "pairing.expired", source = com.lateapex.collie.network.FailureSource.COLLIE, factor = com.lateapex.collie.network.FailureFactor.PAIRING)
        assertTrue(repository.readPane(address) is ApiResult.Failure)
        assertFalse(repository.connection.value!!.isPaired)
        assertFalse(repository.writesAllowed())
        api.paneFailure = null
        repository.readPane(address)
        assertNull(api.paneEtags.last())
    }

    @Test
    fun unrelatedForbiddenReadDoesNotErasePairing() = runBlocking {
        authorizeWrites()
        api.paneFailure = ApiFailure.Http(403, "host not allowed", null)
        repository.readPane(PaneAddress(paneId = "p1"))
        assertTrue(repository.connection.value!!.isPaired)
        assertFalse(repository.writesAllowed())
    }

    @Test
    fun unknownServerOutcomeStaysUnknownUntilExplicitSameIdReceiptReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            firstLaunchResponse = ApiResult.Success(
                CreateResponse(ok = false, error = "receipt write failed", code = OUTCOME_UNKNOWN_CODE),
                200,
            )
            replayLaunchResponse = ApiResult.Success(
                CreateResponse(
                    ok = true,
                    pane = CreatedPane("w1:p1", "w1", "repo", "w1:t1", "/repo"),
                ),
                200,
            )
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, FakeStore(connection), OriginValidator(), launchIntentStore = intentStore)
        intentRepository.snapshot()
        val requestId = "22222222-2222-4222-8222-222222222222"

        val first = intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)

        assertTrue(first is ApiResult.Success && first.value.code == OUTCOME_UNKNOWN_CODE)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
        assertEquals(1, intentApi.launchCalls)
        assertEquals(1, intentApi.simulatedEffects)

        val replay = intentRepository.retryLaunchIntent(Scope(), requestId)

        assertTrue(replay is ApiResult.Success && replay.value.ok)
        assertEquals(LaunchIntentState.succeeded, intentStore.get(requestId)?.state)
        assertEquals(2, intentApi.launchCalls)
        assertEquals(1, intentApi.simulatedEffects)
        assertEquals(intentApi.launchBodies[0], intentApi.launchBodies[1])
    }

    @Test
    fun legacyLaunchLostResponseRemainsDurableUnknownAndCannotBeReplayedOrReplaced() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            legacyLaunchResponse = ApiResult.Failure(ApiFailure.Network("response lost after dispatch"))
        }
        val requestId = "91919191-9191-4191-8191-919191919191"
        val firstStore = LaunchIntentStore(context, TestCipher(), Json)
        val firstRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = firstStore)
        assertTrue(firstRepository.snapshot(Scope(host = "builder", session = "review")) is ApiResult.Success)

        val first = firstRepository.startLegacyLaunchInitial(
            Scope(host = "builder", session = "review"), "codex --resume", requestId, "/srv/repo", false,
        )

        assertTrue(first is ApiResult.Failure)
        assertEquals(1, intentApi.legacyLaunchCalls)
        assertEquals(listOf("codex --resume"), intentApi.legacyLaunchCommands)
        assertEquals(LaunchIntentState.unknown, firstStore.get(requestId)?.state)
        assertEquals(LaunchIntentReceiptMode.legacyInitialOnly, firstStore.get(requestId)?.intent?.receiptMode)

        val recreatedStore = LaunchIntentStore(context, TestCipher(), Json)
        val recreatedRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = recreatedStore)
        assertTrue(recreatedRepository.snapshot(Scope(host = "builder", session = "review")) is ApiResult.Success)
        assertEquals(1, recreatedRepository.unresolvedLaunchIntents(Scope(host = "builder", session = "review")).size)
        assertTrue(recreatedRepository.retryLaunchIntent(Scope(host = "builder", session = "review"), requestId) is ApiResult.Failure)
        assertTrue(recreatedRepository.startLegacyLaunchInitial(
            Scope(host = "builder", session = "review"), "codex --resume", "92929292-9292-4292-8292-929292929292", "/srv/repo", false,
        ) is ApiResult.Failure)
        assertEquals("The legacy endpoint is never retried with a new identity", 1, intentApi.legacyLaunchCalls)
    }

    @Test
    fun workspaceBranchIntentSurvivesRepositoryRecreationAndReplaysExactTargetBody() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            firstWorktreeResponse = ApiResult.Success(WorktreeOpenResponse(ok = false, code = OUTCOME_UNKNOWN_CODE), 200)
            replayWorktreeResponse = ApiResult.Success(WorktreeOpenResponse(ok = true, pane = CreatedPane("w1:p1", "w1", "repo", "w1:t1", "/repo")), 200)
        }
        val requestId = "66666666-6666-4666-8666-666666666666"
        val firstStore = LaunchIntentStore(context, TestCipher(), Json)
        val firstRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = firstStore)
        assertTrue(firstRepository.snapshot() is ApiResult.Success)

        val first = firstRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-opaque-7", "feature/native", requestId)

        assertTrue(first is ApiResult.Success && first.value.code == OUTCOME_UNKNOWN_CODE)
        val uncertain = firstStore.get(requestId)!!
        assertEquals(LaunchIntentState.unknown, uncertain.state)
        assertEquals("worktree.create", uncertain.intent.operation)
        assertEquals("workspace-opaque-7", uncertain.intent.targetIdentity)
        assertEquals(ReserveIntentResult.Mismatch, firstStore.reserve(uncertain.intent.copy(targetIdentity = "workspace-other")))
        assertEquals(1, intentApi.worktreeCalls)
        assertEquals(1, intentApi.worktreeEffects)

        val restartedStore = LaunchIntentStore(context, TestCipher(), Json)
        val restartedRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = restartedStore)
        assertTrue(restartedRepository.snapshot() is ApiResult.Success)
        val replay = restartedRepository.retryWorktreeIntent(Scope(), requestId)

        assertTrue(replay is ApiResult.Success && replay.value.ok)
        assertEquals(2, intentApi.worktreeCalls)
        assertEquals(1, intentApi.worktreeEffects)
        assertEquals(listOf("workspace-opaque-7", "workspace-opaque-7"), intentApi.worktreeTargets)
        assertEquals(intentApi.worktreeBodies[0], intentApi.worktreeBodies[1])
        assertEquals(requestId, Json.parseToJsonElement(intentApi.worktreeBodies[1]).jsonObject.getValue("requestId").jsonPrimitive.content)
        assertEquals(LaunchIntentState.succeeded, restartedStore.get(requestId)?.state)
    }

    @Test(timeout = 30_000)
    fun rejectedReplayCannotResolveOriginalUnknownAcrossAllCreationRoutes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = listOf("launch", "worktreeAt", "workspaceBranch", "launcherAdd")
        for ((index, action) in actions.withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val requestId = "abababab-abab-4bab-8bab-${"%012d".format(index + 1)}"
            val refusalCode = when (action) {
                "launch" -> "launch.request_mismatch"
                "worktreeAt" -> "launch.not_allowlisted"
                "launcherAdd" -> "launcher.invalid_recipe"
                else -> "worktree.invalid_branch"
            }
            val refusal = ApiResult.Failure(ApiFailure.Http(
                409,
                "the deliberate retry was refused",
                refusalCode,
                source = com.lateapex.collie.network.FailureSource.COLLIE,
                factor = com.lateapex.collie.network.FailureFactor.UNKNOWN,
            ))
            val launchRefusal = ApiResult.Failure(ApiFailure.Http(
                409,
                "the deliberate retry was refused",
                refusalCode,
                source = com.lateapex.collie.network.FailureSource.COLLIE,
                factor = com.lateapex.collie.network.FailureFactor.UNKNOWN,
            ))
            val intentApi = FakeApi().apply {
                snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
                firstLaunchResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                replayLaunchResponse = launchRefusal
                firstWorktreeResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                replayWorktreeResponse = refusal
                firstWorktreeAtResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                replayWorktreeAtResponse = refusal
                firstLauncherAddResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                replayLauncherAddResponse = ApiResult.Failure(ApiFailure.Http(
                    409, "the deliberate retry was refused", refusalCode,
                    source = com.lateapex.collie.network.FailureSource.COLLIE,
                    factor = com.lateapex.collie.network.FailureFactor.UNKNOWN,
                ))
            }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val repository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(repository.snapshot() is ApiResult.Success)

            val first = when (action) {
                "launch" -> repository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "worktreeAt" -> repository.createWorktreeAtIntent(
                    Scope(), "/repo", "feature/retry", StartTarget(shell = true), requestId, confirmedNoPrompts = false,
                )
                "workspaceBranch" -> repository.createWorkspaceWorktreeIntent(Scope(), "workspace-retry", "feature/retry", requestId)
                else -> repository.addLauncherIntent(
                    Scope(), requestId,
                    "{\"requestId\":\"$requestId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}",
                )
            }
            assertTrue("$action original request must have an ambiguous transport result", first is ApiResult.Failure)
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)

            val retry = when (action) {
                "launch" -> repository.retryLaunchIntent(Scope(), requestId)
                "launcherAdd" -> repository.retryAddedLauncherIntent(Scope(), requestId)
                else -> repository.retryWorktreeIntent(Scope(), requestId)
            }
            assertTrue("$action retry should report its refusal", retry is ApiResult.Failure)
            assertEquals(refusalCode, ((retry as ApiResult.Failure).error as ApiFailure.Http).code)
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)

            // The canonical response handler freezes writes after an HTTP refusal. Restore only
            // current read authority before asserting that the unresolved journal independently
            // blocks a replacement ID.
            assertTrue("$action current authority read should recover", repository.snapshot() is ApiResult.Success)
            assertTrue(repository.writesAllowed())

            val freshId = "bcbcbcbc-bcbc-4cbc-8cbc-${"%012d".format(index + 1)}"
            val fresh = when (action) {
                "launch" -> repository.startLaunchIntent(Scope(), StartTarget(shell = true), freshId, confirmedNoPrompts = false)
                "worktreeAt" -> repository.createWorktreeAtIntent(
                    Scope(), "/repo", "feature/fresh", StartTarget(shell = true), freshId, confirmedNoPrompts = false,
                )
                "workspaceBranch" -> repository.createWorkspaceWorktreeIntent(Scope(), "workspace-retry", "feature/fresh", freshId)
                else -> repository.addLauncherIntent(
                    Scope(), freshId,
                    "{\"requestId\":\"$freshId\",\"recipe\":{\"harness\":\"claude\",\"options\":[{\"name\":\"fresh\"}]}}",
                )
            }
            assertEquals("launch.unresolved_intent", ((fresh as ApiResult.Failure).error as ApiFailure.Http).code)
            assertNull(intentStore.get(freshId))
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
            when (action) {
                "launch" -> {
                    assertEquals(2, intentApi.launchCalls)
                    assertEquals(1, intentApi.simulatedEffects)
                }
                "worktreeAt" -> {
                    assertEquals(2, intentApi.worktreeAtCalls)
                    assertEquals(1, intentApi.worktreeAtEffects)
                }
                "launcherAdd" -> {
                    assertEquals(2, intentApi.launcherAddCalls)
                    assertEquals(1, intentApi.launcherAddEffects)
                }
                else -> {
                    assertEquals(2, intentApi.worktreeCalls)
                    assertEquals(1, intentApi.worktreeEffects)
                }
            }
        }
    }

    @Test
    fun malformedLauncherSuccessKeepsTheSameIntentUnknownUntilDeliberateReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val api = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            firstLauncherAddResponse = ApiResult.Failure(ApiFailure.Protocol("Malformed launcher add response"))
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val repository = CollieRepository(api, store, OriginValidator(), launchIntentStore = intentStore)
        assertTrue(repository.snapshot() is ApiResult.Success)

        val requestId = "9a9a9a9a-9a9a-4a9a-8a9a-9a9a9a9a9a9a"
        val body = """{"requestId":"$requestId","recipe":{"harness":"claude","options":[]}}"""
        val first = repository.addLauncherIntent(Scope(), requestId, body)
        assertTrue(first is ApiResult.Failure && (first.error as ApiFailure.Protocol).message.contains("Malformed launcher"))
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
        assertEquals(1, api.launcherAddCalls)
        assertEquals(1, api.launcherAddEffects)

        // The malformed 200 freezes writes. An explicit fresh admitted read restores current authority,
        // but it cannot clear the uncertain request or authorize a new ID.
        assertTrue(repository.snapshot() is ApiResult.Success)

        val replacementId = "abababab-abab-4bab-8bab-abababababab"
        val replacement = repository.addLauncherIntent(
            Scope(), replacementId,
            """{"requestId":"$replacementId","recipe":{"harness":"claude","options":[]}}""",
        )
        assertEquals("launch.unresolved_intent", ((replacement as ApiResult.Failure).error as ApiFailure.Http).code)
        assertNull(intentStore.get(replacementId))
        assertEquals(1, api.launcherAddCalls)

        val retry = repository.retryAddedLauncherIntent(Scope(), requestId)
        assertTrue(retry is ApiResult.Success && retry.value.ok)
        assertEquals(2, api.launcherAddCalls)
        assertEquals(1, api.launcherAddEffects)
        assertEquals(LaunchIntentState.succeeded, intentStore.get(requestId)?.state)
    }

    @Test(timeout = 30_000)
    fun writeFreezeDuringUnknownRetryPreservesOriginalUnknownAcrossAllCreationRoutes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = listOf("launch", "worktreeAt", "workspaceBranch", "launcherAdd")
        for ((index, action) in actions.withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val requestId = "cdcdcdcd-cdcd-4dcd-8dcd-${"%012d".format(index + 1)}"
            val intentApi = FakeApi().apply {
                snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
                firstLaunchResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                firstWorktreeResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                firstWorktreeAtResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
                firstLauncherAddResponse = ApiResult.Failure(ApiFailure.Network("original response was lost"))
            }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val repository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(repository.snapshot() is ApiResult.Success)
            when (action) {
                "launch" -> repository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "worktreeAt" -> repository.createWorktreeAtIntent(
                    Scope(), "/repo", "feature/retry", StartTarget(shell = true), requestId, confirmedNoPrompts = false,
                )
                "workspaceBranch" -> repository.createWorkspaceWorktreeIntent(Scope(), "workspace-freeze", "feature/retry", requestId)
                else -> repository.addLauncherIntent(
                    Scope(), requestId,
                    "{\"requestId\":\"$requestId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}",
                )
            }
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)

            intentApi.configEntered = CompletableDeferred()
            intentApi.configGate = CompletableDeferred()
            val retry = async {
                when (action) {
                    "launch" -> repository.retryLaunchIntent(Scope(), requestId)
                    "launcherAdd" -> repository.retryAddedLauncherIntent(Scope(), requestId)
                    else -> repository.retryWorktreeIntent(Scope(), requestId)
                }
            }
            intentApi.configEntered!!.await()
            intentApi.paneFailure = ApiFailure.Http(
                503,
                "an unrelated ordinary read failed",
                "gateway.unknown",
                source = com.lateapex.collie.network.FailureSource.GATEWAY,
                factor = com.lateapex.collie.network.FailureFactor.UNKNOWN,
            )
            assertTrue(repository.readPane(PaneAddress(paneId = "p1")) is ApiResult.Failure)
            intentApi.configGate!!.complete(Unit)
            assertTrue("$action retry must be refused before another POST", retry.await() is ApiResult.Failure)
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
            when (action) {
                "launch" -> assertEquals(1, intentApi.launchCalls)
                "worktreeAt" -> assertEquals(1, intentApi.worktreeAtCalls)
                "launcherAdd" -> assertEquals(1, intentApi.launcherAddCalls)
                else -> assertEquals(1, intentApi.worktreeCalls)
            }

            intentApi.paneFailure = null
            assertTrue(repository.snapshot() is ApiResult.Success)
            val freshId = "dededede-dede-4ede-8ede-${"%012d".format(index + 1)}"
            val fresh = when (action) {
                "launch" -> repository.startLaunchIntent(Scope(), StartTarget(shell = true), freshId, confirmedNoPrompts = false)
                "worktreeAt" -> repository.createWorktreeAtIntent(
                    Scope(), "/repo", "feature/fresh", StartTarget(shell = true), freshId, confirmedNoPrompts = false,
                )
                "workspaceBranch" -> repository.createWorkspaceWorktreeIntent(Scope(), "workspace-freeze", "feature/fresh", freshId)
                else -> repository.addLauncherIntent(
                    Scope(), freshId,
                    "{\"requestId\":\"$freshId\",\"recipe\":{\"harness\":\"claude\",\"options\":[{\"name\":\"fresh\"}]}}",
                )
            }
            assertEquals("launch.unresolved_intent", ((fresh as ApiResult.Failure).error as ApiFailure.Http).code)
            assertNull(intentStore.get(freshId))
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
            when (action) {
                "launch" -> assertEquals(1, intentApi.launchCalls)
                "worktreeAt" -> assertEquals(1, intentApi.worktreeAtCalls)
                "launcherAdd" -> assertEquals(1, intentApi.launcherAddCalls)
                else -> assertEquals(1, intentApi.worktreeCalls)
            }
        }
    }

    @Test(timeout = 15_000)
    fun pairingChangeDuringReceiptReadinessStopsBeforeReservationOrWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            configEntered = CompletableDeferred()
            configGate = CompletableDeferred()
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        assertTrue(intentRepository.snapshot() is ApiResult.Success)
        val requestId = "33333333-3333-4333-8333-333333333333"
        val write = async {
            intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
        }

        intentApi.configEntered!!.await()
        store.save(Connection(connection.origin, "replacement phone", "replacement-token"))
        intentApi.configGate!!.complete(Unit)
        val result = write.await()

        assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
        assertEquals(0, intentApi.launchCalls)
        assertNull(intentStore.get(requestId))
    }

    @Test(timeout = 15_000)
    fun ordinaryReadFailureDuringReceiptReadinessBlocksTheFrozenPostAtDispatch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            configEntered = CompletableDeferred()
            configGate = CompletableDeferred()
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        assertTrue(intentRepository.snapshot() is ApiResult.Success)
        val captured = intentRepository.readContext(Scope())!!
        val requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val write = async {
            intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
        }
        intentApi.configEntered!!.await()

        intentApi.paneFailure = ApiFailure.Http(
            503, "temporary pane read failure", "gateway.unknown",
            source = com.lateapex.collie.network.FailureSource.GATEWAY,
            factor = com.lateapex.collie.network.FailureFactor.UNKNOWN,
        )
        assertTrue(intentRepository.readPane(PaneAddress(paneId = "p1")) is ApiResult.Failure)
        assertTrue("the read actor remains current while write admission freezes", intentRepository.isCurrent(captured))
        assertFalse(intentRepository.writesAllowed())

        intentApi.configGate!!.complete(Unit)
        val result = write.await()

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, intentApi.launchCalls)
        assertEquals(LaunchIntentState.refused, intentStore.get(requestId)?.state)
    }

    @Test(timeout = 30_000)
    fun everyIntentCreationRechecksAtomicAdmissionAfterDelayedReadiness() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val blockedIds = listOf(
            "77777777-7777-4777-8777-777777777777",
            "88888888-8888-4888-8888-888888888888",
            "99999999-9999-4999-8999-999999999999",
        )
        for ((index, action) in listOf("launch", "worktreeAt", "workspaceBranch").withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val intentApi = FakeApi().apply {
                snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
                firstLaunchResponse = ApiResult.Success(
                    CreateResponse(ok = false, code = OUTCOME_UNKNOWN_CODE),
                    200,
                )
                launchEntered = CompletableDeferred()
                launchGate = CompletableDeferred()
            }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(intentRepository.snapshot() is ApiResult.Success)
            val firstId = "11111111-1111-4111-8111-${"%012d".format(index + 1)}"
            val first = async {
                intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), firstId, confirmedNoPrompts = false)
            }
            intentApi.launchEntered!!.await()

            // The second creation crosses its initial unresolved check, then waits for readiness.
            intentApi.configEntered = CompletableDeferred()
            intentApi.configGate = CompletableDeferred()
            val secondId = blockedIds[index]
            val second = async {
                when (action) {
                    "launch" -> intentRepository.startLaunchIntent(
                        Scope(), StartTarget(shell = true), secondId, confirmedNoPrompts = false,
                    )
                    "worktreeAt" -> intentRepository.createWorktreeAtIntent(
                        Scope(), "/repo", "feature/second", StartTarget(shell = true), secondId,
                        confirmedNoPrompts = false,
                    )
                    else -> intentRepository.createWorkspaceWorktreeIntent(
                        Scope(), "workspace-7", "feature/second", secondId,
                    )
                }
            }
            intentApi.configEntered!!.await()
            intentApi.launchGate!!.complete(Unit)
            assertTrue(first.await() is ApiResult.Success)
            assertEquals(LaunchIntentState.unknown, intentStore.get(firstId)?.state)
            intentApi.configGate!!.complete(Unit)
            val result = second.await()

            assertTrue("$action must refuse the overlapping request", result is ApiResult.Failure)
            assertEquals(
                "launch.unresolved_intent",
                (result as ApiResult.Failure).error.let { (it as ApiFailure.Http).code },
            )
            assertNull(intentStore.get(secondId))
            assertEquals(listOf(firstId), intentStore.all().map { it.intent.requestId })
            assertEquals(1, intentApi.launchCalls)
            assertEquals(1, intentApi.simulatedEffects)
            assertEquals(0, intentApi.worktreeCalls)
            assertEquals(0, intentApi.worktreeAtCalls)
        }
    }

    @Test(timeout = 30_000)
    fun cancellationAfterDispatchBecomesReachableUnknownAndOnlySameIdCanReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = listOf("launch", "worktreeAt", "workspaceBranch", "launcherAdd")
        for ((index, action) in actions.withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val intentApi = FakeApi().apply {
                snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
                when (action) {
                    "launch" -> { launchEntered = CompletableDeferred(); launchGate = CompletableDeferred() }
                    "worktreeAt" -> { worktreeAtEntered = CompletableDeferred(); worktreeAtGate = CompletableDeferred() }
                    "workspaceBranch" -> { worktreeEntered = CompletableDeferred(); worktreeGate = CompletableDeferred() }
                    else -> { launcherAddEntered = CompletableDeferred(); launcherAddGate = CompletableDeferred() }
                }
            }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(intentRepository.snapshot() is ApiResult.Success)
            val requestId = "cccccccc-cccc-4ccc-8ccc-${"%012d".format(index + 1)}"
            val actionJob = async {
                when (action) {
                    "launch" -> intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                    "worktreeAt" -> intentRepository.createWorktreeAtIntent(Scope(), "/repo", "feature/cancel", StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                    "workspaceBranch" -> intentRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-7", "feature/cancel", requestId)
                    else -> intentRepository.addLauncherIntent(Scope(), requestId, "{\"requestId\":\"$requestId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}")
                }
            }
            when (action) {
                "launch" -> intentApi.launchEntered!!.await()
                "worktreeAt" -> intentApi.worktreeAtEntered!!.await()
                "workspaceBranch" -> intentApi.worktreeEntered!!.await()
                else -> intentApi.launcherAddEntered!!.await()
            }
            actionJob.cancel()
            try { actionJob.await() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("dispatched effect is counted once", 1, intentApi.simulatedEffects + intentApi.worktreeAtEffects + intentApi.worktreeEffects + intentApi.launcherAddEffects)
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)

            // Recreate only the repository/store owner; this models Activity recreation without process death.
            val recreatedStore = LaunchIntentStore(context, TestCipher(), Json)
            val recreatedRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = recreatedStore)
            assertTrue(recreatedRepository.snapshot() is ApiResult.Success)
            assertEquals(requestId, recreatedRepository.unresolvedLaunchIntents().single().intent.requestId)
            val replacementId = "dddddddd-dddd-4ddd-8ddd-${"%012d".format(index + 1)}"
            val replacement = when (action) {
                "launch" -> recreatedRepository.startLaunchIntent(Scope(), StartTarget(shell = true), replacementId, confirmedNoPrompts = false)
                "worktreeAt" -> recreatedRepository.createWorktreeAtIntent(Scope(), "/repo", "feature/replacement", StartTarget(shell = true), replacementId, confirmedNoPrompts = false)
                "workspaceBranch" -> recreatedRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-7", "feature/replacement", replacementId)
                else -> recreatedRepository.addLauncherIntent(Scope(), replacementId, "{\"requestId\":\"$replacementId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}")
            }
            assertEquals("launch.unresolved_intent", ((replacement as ApiResult.Failure).error as ApiFailure.Http).code)
            assertNull(recreatedStore.get(replacementId))

            intentApi.launchGate = null
            intentApi.worktreeAtGate = null
            intentApi.worktreeGate = null
            intentApi.launcherAddGate = null
            val replay = when (action) {
                "launch" -> recreatedRepository.retryLaunchIntent(Scope(), requestId)
                "launcherAdd" -> recreatedRepository.retryAddedLauncherIntent(Scope(), requestId)
                else -> recreatedRepository.retryWorktreeIntent(Scope(), requestId)
            }
            assertTrue("explicit replay settles the saved request", replay is ApiResult.Success)
            assertEquals(1, intentApi.simulatedEffects + intentApi.worktreeAtEffects + intentApi.worktreeEffects + intentApi.launcherAddEffects)
        }
    }

    @Test
    fun legacyWorktreeCapabilityFalseAllowsOneInitialCreateButNeverOffersSameIdReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            configResult = ApiResult.Success(BridgeConfigResponse(receiptContextBound = false), 200)
            firstWorktreeResponse = ApiResult.Failure(ApiFailure.Network("reply lost"))
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        assertTrue(intentRepository.snapshot() is ApiResult.Success)
        val requestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

        intentRepository.createWorkspaceWorktreeLegacyInitial(Scope(), "workspace-7", "feature/legacy", requestId)

        assertEquals(1, intentApi.worktreeCalls)
        assertEquals(1, intentApi.worktreeEffects)
        assertEquals(LaunchIntentReceiptMode.legacyInitialOnly, intentStore.get(requestId)?.intent?.receiptMode)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
        val retry = intentRepository.retryWorktreeIntent(Scope(), requestId)
        assertTrue(retry is ApiResult.Failure)
        assertEquals(1, intentApi.worktreeCalls)
    }

    @Test
    fun frozenWritesUseCanonicalPairingRetirementAndGenericFailureHandling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = listOf("launch", "worktreeAt", "workspaceBranch", "launcherAdd")
        for ((index, action) in actions.withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val intentApi = FakeApi().apply { snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true) }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(intentRepository.snapshot() is ApiResult.Success)
            val requestId = "abababab-abab-4aba-8aba-${"%012d".format(index + 1)}"
            fun setResponse(failure: ApiFailure) {
                when (action) {
                    "launch" -> { intentApi.firstLaunchResponse = ApiResult.Failure(failure); intentApi.replayLaunchResponse = ApiResult.Failure(failure) }
                    "worktreeAt" -> { intentApi.firstWorktreeAtResponse = ApiResult.Failure(failure); intentApi.replayWorktreeAtResponse = ApiResult.Failure(failure) }
                    "workspaceBranch" -> { intentApi.firstWorktreeResponse = ApiResult.Failure(failure); intentApi.replayWorktreeResponse = ApiResult.Failure(failure) }
                    else -> { intentApi.firstLauncherAddResponse = ApiResult.Failure(failure); intentApi.replayLauncherAddResponse = ApiResult.Failure(failure) }
                }
            }
            suspend fun dispatch(): ApiResult<*> = when (action) {
                "launch" -> intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "worktreeAt" -> intentRepository.createWorktreeAtIntent(Scope(), "/repo", "feature/failure", StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "workspaceBranch" -> intentRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-7", "feature/failure", requestId)
                else -> intentRepository.addLauncherIntent(Scope(), requestId, "{\"requestId\":\"$requestId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}")
            }

            setResponse(ApiFailure.Http(
                401, "authoritative pairing retired", "pairing.expired",
                source = com.lateapex.collie.network.FailureSource.COLLIE,
                factor = com.lateapex.collie.network.FailureFactor.PAIRING,
            ))
            dispatch()
            assertNull("$action pairing must be retired synchronously", intentRepository.connection.value?.token)
            assertTrue("$action pairing retirement clears local intent custody", intentStore.all().isEmpty())

            // Re-pair a synthetic replacement only to exercise untrusted gateway denial separately.
            store.save(Connection(connection.origin, "phone", "token"))
            intentApi.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            intentRepository.snapshot()
            val gatewayId = "cdcdcdcd-cdcd-4dcd-8dcd-${"%012d".format(index + 1)}"
            setResponse(ApiFailure.Http(
                403, "proxy refused", "gateway.forbidden",
                source = com.lateapex.collie.network.FailureSource.GATEWAY,
                factor = com.lateapex.collie.network.FailureFactor.IDENTITY,
            ))
            when (action) {
                "launch" -> intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), gatewayId, confirmedNoPrompts = false)
                "worktreeAt" -> intentRepository.createWorktreeAtIntent(Scope(), "/repo", "feature/gateway", StartTarget(shell = true), gatewayId, confirmedNoPrompts = false)
                "workspaceBranch" -> intentRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-7", "feature/gateway", gatewayId)
                else -> intentRepository.addLauncherIntent(Scope(), gatewayId, "{\"requestId\":\"$gatewayId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}")
            }
            assertFalse("$action generic gateway denial freezes writes", intentRepository.writesAllowed())
            assertEquals(LaunchIntentState.unknown, intentStore.get(gatewayId)?.state)
            val callsAfterFreeze = intentApi.launchCalls + intentApi.worktreeAtCalls + intentApi.worktreeCalls + intentApi.launcherAddCalls
            intentRepository.startLaunch(Scope(), StartTarget(shell = true), "fefefefe-fefe-4fef-8fef-${"%012d".format(index + 1)}")
            assertEquals("$action frozen access prevents every subsequent write", callsAfterFreeze,
                intentApi.launchCalls + intentApi.worktreeAtCalls + intentApi.worktreeCalls + intentApi.launcherAddCalls)
        }
    }

    @Test
    fun frozenSuccessFromAChangedAuthenticatedLeadIsNotShownAsCurrentSuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = listOf("launch", "worktreeAt", "workspaceBranch", "launcherAdd")
        for ((index, action) in actions.withIndex()) {
            context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
            val intentApi = FakeApi().apply {
                snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
                snapshotValue = SnapshotResponse(
                    bridge = "1.5.0", device = snapshotDevice, agents = emptyList(), shellPanes = emptyList(),
                    workspaces = emptyList(), tabs = emptyList(), ts = 1,
                    controlAuthority = com.lateapex.collie.network.ControlAuthority("lead-1", "epoch-1"),
                )
                val nextAuthority = com.lateapex.collie.network.ControlAuthority("lead-2", "epoch-2")
                firstLaunchResponse = ApiResult.Success(CreateResponse(ok = true), 200, controlAuthority = nextAuthority)
                firstWorktreeAtResponse = ApiResult.Success(WorktreeOpenResponse(ok = true), 200, controlAuthority = nextAuthority)
                firstWorktreeResponse = ApiResult.Success(WorktreeOpenResponse(ok = true), 200, controlAuthority = nextAuthority)
                firstLauncherAddAuthority = nextAuthority
            }
            val intentStore = LaunchIntentStore(context, TestCipher(), Json)
            val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
            assertTrue(intentRepository.snapshot() is ApiResult.Success)
            val requestId = "efefefef-efef-4fef-8fef-${"%012d".format(index + 1)}"
            val result: ApiResult<*> = when (action) {
                "launch" -> intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "worktreeAt" -> intentRepository.createWorktreeAtIntent(Scope(), "/repo", "feature/lead", StartTarget(shell = true), requestId, confirmedNoPrompts = false)
                "workspaceBranch" -> intentRepository.createWorkspaceWorktreeIntent(Scope(), "workspace-7", "feature/lead", requestId)
                else -> intentRepository.addLauncherIntent(Scope(), requestId, "{\"requestId\":\"$requestId\",\"recipe\":{\"harness\":\"claude\",\"options\":[]}}")
            }
            assertTrue("$action changed authority returns stale failure", result is ApiResult.Failure)
            assertFalse("$action changed authority freezes writes", intentRepository.writesAllowed())
            assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
            assertEquals(1, intentApi.simulatedEffects + intentApi.worktreeAtEffects + intentApi.worktreeEffects + intentApi.launcherAddEffects)
        }
    }

    @Test(timeout = 15_000)
    fun pairingChangeDuringLaunchKeepsOriginalRecordUnknownAndReturnsStaleScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            launchEntered = CompletableDeferred()
            launchGate = CompletableDeferred()
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        assertTrue(intentRepository.snapshot() is ApiResult.Success)
        val requestId = "44444444-4444-4444-8444-444444444444"
        val write = async {
            intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
        }

        intentApi.launchEntered!!.await()
        store.save(Connection(connection.origin, "replacement phone", "replacement-token"))
        intentApi.launchGate!!.complete(Unit)
        val result = write.await()

        assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
        assertEquals(1, intentApi.launchCalls)
        assertEquals(listOf("token"), intentApi.launchTokens)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
    }

    @Test
    fun pairingRetirementPurgesIntentsButGatewayFailureAndOtherDeviceRevokeDoNot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            firstLaunchResponse = ApiResult.Failure(
                ApiFailure.Http(502, "ambiguous response", "gateway.unknown", source = com.lateapex.collie.network.FailureSource.GATEWAY,
                    factor = com.lateapex.collie.network.FailureFactor.IDENTITY),
            )
            revokeResult = ApiResult.Success(DevicesResponse(enforced = true, current = "phone", devices = emptyList()), 200)
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        intentRepository.snapshot()
        val requestId = "55555555-5555-4555-8555-555555555555"
        intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)

        intentRepository.revokeDevice("another phone")
        assertNotNull(intentStore.get(requestId))

        intentApi.snapshotApiFailure = ApiFailure.Http(
            401, "untrusted gateway sign-in expired", "pairing.expired",
            source = com.lateapex.collie.network.FailureSource.GATEWAY,
            factor = com.lateapex.collie.network.FailureFactor.IDENTITY,
        )
        intentRepository.snapshot()
        assertNotNull(intentStore.get(requestId))

        intentApi.snapshotApiFailure = ApiFailure.Http(
            401, "pairing expired", "pairing.expired",
            source = com.lateapex.collie.network.FailureSource.COLLIE,
            factor = com.lateapex.collie.network.FailureFactor.PAIRING,
        )
        intentRepository.snapshot()

        assertNull(intentStore.get(requestId))
        assertTrue(intentStore.all().isEmpty())
    }

    @Test
    fun recoveredIntentSurvivesProcessLocalGenerationAndLeadEpochResetForSameOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply { snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true) }
        val firstStore = LaunchIntentStore(context, TestCipher(), Json)
        val firstRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = firstStore)
        val scope = Scope(host = "build-host", session = "compile")
        val captured = firstRepository.readContext(scope)!!
        val requestId = "66666666-6666-4666-8666-666666666666"
        val body = "{\"requestId\":\"$requestId\",\"shell\":true}"
        val intent = FrozenLaunchIntent(
            requestId = requestId,
            origin = captured.key.origin,
            mount = captured.key.origin,
            connectionGeneration = captured.key.connectionGeneration + 8,
            pairedDeviceIdentity = captured.key.pairedDeviceIdentity,
            leadEpoch = captured.key.leadEpoch + 4,
            host = scope.host,
            session = scope.session,
            subjectIdentity = captured.key.subjectIdentity,
            operation = "launch",
            confirmedNoPrompts = false,
            canonicalBody = body,
        )
        firstStore.reserve(intent)

        val restartedStore = LaunchIntentStore(context, TestCipher(), Json)
        val restartedRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = restartedStore)
        val replay = restartedRepository.retryLaunchIntent(scope, requestId)

        assertTrue(replay is ApiResult.Success && replay.value.ok)
        assertEquals(1, intentApi.launchCalls)
        assertEquals(body, intentApi.launchBodies.single())
        assertEquals(LaunchIntentState.succeeded, restartedStore.get(requestId)?.state)
    }

    @Test
    fun recoveredIntentCannotCrossHostSessionOrPairingIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply { snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true) }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val initialRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        val scope = Scope(host = "build-host", session = "compile")
        val owner = initialRepository.readContext(scope)!!
        val requestId = "77777777-7777-4777-8777-777777777777"
        val body = "{\"requestId\":\"$requestId\",\"shell\":true}"
        val intent = FrozenLaunchIntent(
            requestId = requestId,
            origin = owner.key.origin,
            mount = owner.key.origin,
            connectionGeneration = owner.key.connectionGeneration,
            pairedDeviceIdentity = owner.key.pairedDeviceIdentity,
            leadEpoch = owner.key.leadEpoch,
            host = scope.host,
            session = scope.session,
            subjectIdentity = owner.key.subjectIdentity,
            operation = "launch",
            confirmedNoPrompts = false,
            canonicalBody = body,
        )
        intentStore.reserve(intent)
        intentStore.transition(requestId, intent.bodySha256, LaunchIntentState.unknown)

        val wrongHost = initialRepository.retryLaunchIntent(Scope(host = "other-host", session = "compile"), requestId)
        val wrongSession = initialRepository.retryLaunchIntent(Scope(host = "build-host", session = "other-session"), requestId)
        store.save(Connection(connection.origin, "replacement phone", "replacement-token"))
        val wrongOwner = initialRepository.retryLaunchIntent(scope, requestId)

        assertTrue(wrongHost is ApiResult.Failure)
        assertTrue(wrongSession is ApiResult.Failure)
        assertTrue(wrongOwner is ApiResult.Failure)
        assertEquals(0, intentApi.launchCalls)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
    }

    @Test
    fun successfulSelfRevokeSignalPurgesJournalAndDemotesPairing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
        val intentApi = FakeApi().apply {
            snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
            firstLaunchResponse = ApiResult.Failure(ApiFailure.Network("ambiguous launch response"))
            revokeResult = ApiResult.Success(
                DevicesResponse(enforced = true, current = "phone", devices = emptyList(), currentExpired = true),
                200,
            )
        }
        val intentStore = LaunchIntentStore(context, TestCipher(), Json)
        val intentRepository = CollieRepository(intentApi, store, OriginValidator(), launchIntentStore = intentStore)
        intentRepository.snapshot()
        val requestId = "88888888-8888-4888-8888-888888888888"
        intentRepository.startLaunchIntent(Scope(), StartTarget(shell = true), requestId, confirmedNoPrompts = false)
        assertEquals(LaunchIntentState.unknown, intentStore.get(requestId)?.state)
        assertFalse("an ambiguous transport failure freezes writes until a current read restores authority", intentRepository.writesAllowed())

        assertTrue("a positive current snapshot restores write authority without resolving the intent", intentRepository.snapshot() is ApiResult.Success)
        assertTrue(intentRepository.writesAllowed())

        val revoked = intentRepository.revokeDevice("phone")

        assertTrue(revoked is ApiResult.Success)
        assertNull(intentStore.get(requestId))
        assertNull(intentRepository.connection.value?.token)
    }

    @Test
    fun changesReadsUseOrdinaryScopedAdmissionRatherThanDeviceOwnedOrWriteGate(): Unit = runBlocking {
        val target = ChangesTarget.Workspace("w1")
        val scope = Scope(host = "peer", session = "work")

        val result = repository.changes(target, scope, ChangesLookup())

        assertTrue(result is ApiResult.Success)
        assertEquals(target, api.lastChangesTarget)
        assertEquals(scope, api.lastChangesScope)
        assertEquals(1, api.changesCalls)
        assertFalse(repository.writesAllowed(scope, target.id))
    }

    @Test
    fun changesUnauthorizedReadFreezesWritesWithoutRetiringThePairing(): Unit = runBlocking {
        val scope = Scope(host = "peer", session = "work")
        api.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
        repository.snapshot(scope)
        assertTrue(repository.writesAllowed(scope))
        api.changesFailure = ApiFailure.Http(403, "device denied", "device.denied")

        val result = repository.changes(ChangesTarget.Pane("p1"), scope, ChangesLookup())

        assertTrue(result is ApiResult.Failure)
        assertTrue(repository.connection.value!!.isPaired)
        assertFalse(repository.writesAllowed(scope, "p1"))
        assertEquals(1, api.changesCalls)
    }

    private suspend fun authorizeWrites() {
        api.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
        repository.snapshot()
    }

    private class FakeStore(initial: Connection?) : ConnectionStore {
        private val mutable = MutableStateFlow(initial)
        override val connection: StateFlow<Connection?> = mutable
        var cleared = false

        override fun save(connection: Connection) {
            mutable.value = connection
        }

        override fun clear() {
            cleared = true
            mutable.value = null
        }
    }

    private class FakeApi : CollieApi {
        val paneEtags = mutableListOf<String?>()
        val paneLines = mutableListOf<Int>()
        var replyCalls = 0
        var keysCalls = 0
        var replyExpectedPrompt: String? = null
        var keysExpectedPrompt: String? = null
        var snoozeCalls = 0
        var dismissCalls = 0
        var dismissedVersion: String? = null
        var dismissedScope: UpdateDismissalScope? = null
        var updateCalls = 0
        var revokeCalls = 0
        var transcribeCalls = 0
        var replyResult: ApiResult<ActionResponse> = ApiResult.Success(ActionResponse(true), 200)
        var snapshotDevice: DeviceAuthorization? = null
        var snapshotFailure = false
        var snapshotApiFailure: ApiFailure? = null
        var snapshotValue: SnapshotResponse? = null
        var paneFailure: ApiFailure? = null
        var paneValue: PaneReadResponse? = null
        var configResult: ApiResult<BridgeConfigResponse> = ApiResult.Success(BridgeConfigResponse(receiptContextBound = true), 200)
        var firstLaunchResponse: ApiResult<CreateResponse> = ApiResult.Success(CreateResponse(ok = true), 200)
        var replayLaunchResponse: ApiResult<CreateResponse> = ApiResult.Success(CreateResponse(ok = true), 200)
        var firstWorktreeResponse: ApiResult<WorktreeOpenResponse> = ApiResult.Success(WorktreeOpenResponse(ok = true), 200)
        var replayWorktreeResponse: ApiResult<WorktreeOpenResponse> = ApiResult.Success(WorktreeOpenResponse(ok = true), 200)
        var firstWorktreeAtResponse: ApiResult<WorktreeOpenResponse> = ApiResult.Success(WorktreeOpenResponse(ok = true), 200)
        var replayWorktreeAtResponse: ApiResult<WorktreeOpenResponse> = ApiResult.Success(WorktreeOpenResponse(ok = true), 200)
        var firstLauncherAddResponse: ApiResult<AddedLauncherResponse>? = null
        var replayLauncherAddResponse: ApiResult<AddedLauncherResponse>? = null
        var firstLauncherAddAuthority: com.lateapex.collie.network.ControlAuthority? = null
        var launchCalls = 0
        var simulatedEffects = 0
        val launchBodies = mutableListOf<String>()
        val launchTokens = mutableListOf<String?>()
        var legacyLaunchCalls = 0
        var legacyLaunchResponse: ApiResult<CreateResponse> = ApiResult.Failure(ApiFailure.Network("unused"))
        val legacyLaunchCommands = mutableListOf<String>()
        var worktreeCalls = 0
        var worktreeAtCalls = 0
        var worktreeAtEffects = 0
        var worktreeEffects = 0
        var launcherAddCalls = 0
        var launcherAddEffects = 0
        val worktreeBodies = mutableListOf<String>()
        val worktreeTargets = mutableListOf<String>()
        var configEntered: CompletableDeferred<Unit>? = null
        var configGate: CompletableDeferred<Unit>? = null
        var launchEntered: CompletableDeferred<Unit>? = null
        var launchGate: CompletableDeferred<Unit>? = null
        var worktreeAtEntered: CompletableDeferred<Unit>? = null
        var worktreeAtGate: CompletableDeferred<Unit>? = null
        var worktreeEntered: CompletableDeferred<Unit>? = null
        var worktreeGate: CompletableDeferred<Unit>? = null
        var launcherAddEntered: CompletableDeferred<Unit>? = null
        var launcherAddGate: CompletableDeferred<Unit>? = null
        var revokeResult: ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Network("unused"))
        private val launchedIds = mutableSetOf<String>()

        override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
            configEntered?.complete(Unit)
            configGate?.await()
            return configResult
        }
        override suspend fun startLaunchFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<CreateResponse> {
            launchCalls++
            launchTokens += connection.token
            launchBodies += canonicalBody
            val requestId = Json.parseToJsonElement(canonicalBody).jsonObject.getValue("requestId").jsonPrimitive.content
            if (launchedIds.add(requestId)) simulatedEffects++
            launchEntered?.complete(Unit)
            launchGate?.await()
            return if (launchCalls == 1) firstLaunchResponse else replayLaunchResponse
        }
        override suspend fun launch(connection: Connection, scope: Scope, command: String, besidePaneId: String?): ApiResult<CreateResponse> {
            legacyLaunchCalls++
            legacyLaunchCommands += command
            return legacyLaunchResponse
        }
        override suspend fun addLauncherFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<AddedLauncherResponse> {
            launcherAddCalls++
            val requestId = Json.parseToJsonElement(canonicalBody).jsonObject.getValue("requestId").jsonPrimitive.content
            if (launchedIds.add(requestId)) launcherAddEffects++
            launcherAddEntered?.complete(Unit)
            launcherAddGate?.await()
            val configured = if (launcherAddCalls == 1) firstLauncherAddResponse else replayLauncherAddResponse
            return configured ?: launcherAddSuccess(
                canonicalBody,
                replayed = launcherAddCalls > 1,
                authority = if (launcherAddCalls == 1) firstLauncherAddAuthority else null,
            )
        }

        private fun launcherAddSuccess(
            canonicalBody: String,
            replayed: Boolean,
            authority: com.lateapex.collie.network.ControlAuthority? = null,
        ): ApiResult<AddedLauncherResponse> {
            val request = Json.parseToJsonElement(canonicalBody).jsonObject
            val requestId = request.getValue("requestId").jsonPrimitive.content
            val recipe = request["recipe"]?.jsonObject
            val addedAs = if (recipe != null) "recipe" else "text"
            val harness = recipe?.get("harness")?.jsonPrimitive?.content
                ?: request["harness"]?.jsonPrimitive?.content
            val kind = when {
                recipe != null -> "agent"
                request["kind"]?.jsonPrimitive?.content == "agent" -> "agent"
                else -> "command"
            }
            val recipeBinaries = mapOf(
                "claude" to "claude", "codex" to "codex", "opencode" to "opencode",
                "pi" to "pi", "omp" to "omp", "grok" to "grok", "hermes" to "hermes",
                "muse" to "muse", "agy" to "agy",
            )
            val recipeLabels = mapOf(
                "claude" to "Claude Code", "codex" to "Codex", "grok" to "Grok",
                "hermes" to "Hermes", "muse" to "Muse", "agy" to "Antigravity",
            )
            val command = if (recipe != null) recipeBinaries[harness].orEmpty() else request["text"]?.jsonPrimitive?.content.orEmpty()
            val label = if (recipe != null) recipeLabels[harness] ?: harness.orEmpty() else command.substringBefore(' ')
            val row = Json.parseToJsonElement(
                """{"source":"added","id":"$requestId","kind":"$kind","command":"$command","label":"$label","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"$addedAs"${harness?.let { ",\"harness\":\"$it\"" }.orEmpty()}}""",
            ).jsonObject
            return ApiResult.Success(
                AddedLauncherResponse(ok = true, row = row, replayed = replayed.takeIf { it }),
                200,
                controlAuthority = authority,
            )
        }
        override suspend fun createWorktreeAtFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<WorktreeOpenResponse> {
            worktreeAtCalls++
            val requestId = kotlinx.serialization.json.Json.parseToJsonElement(canonicalBody)
                .jsonObject.getValue("requestId").jsonPrimitive.content
            if (launchedIds.add(requestId)) worktreeAtEffects++
            worktreeAtEntered?.complete(Unit)
            worktreeAtGate?.await()
            return if (worktreeAtCalls == 1) firstWorktreeAtResponse else replayWorktreeAtResponse
        }
        override suspend fun createWorktreeFrozen(connection: Connection, scope: Scope, workspaceId: String, canonicalBody: String): ApiResult<WorktreeOpenResponse> {
            worktreeCalls++
            worktreeTargets += workspaceId
            worktreeBodies += canonicalBody
            val requestId = Json.parseToJsonElement(canonicalBody).jsonObject.getValue("requestId").jsonPrimitive.content
            if (launchedIds.add(requestId)) worktreeEffects++
            worktreeEntered?.complete(Unit)
            worktreeGate?.await()
            return if (worktreeCalls == 1) firstWorktreeResponse else replayWorktreeResponse
        }
        var processCalls = 0
        var processAddress: PaneAddress? = null
        override suspend fun paneProcesses(connection: Connection, address: PaneAddress): ApiResult<PaneProcessesResponse> {
            processCalls++
            processAddress = address
            return ApiResult.Success(PaneProcessesResponse(true, address.paneId), 200)
        }

        var changesCalls = 0
        var lastChangesTarget: ChangesTarget? = null
        var lastChangesScope: Scope? = null
        var changesFailure: ApiFailure? = null

        override suspend fun changes(
            connection: Connection,
            target: ChangesTarget,
            scope: Scope,
            lookup: ChangesLookup,
        ): ApiResult<ChangesListAnswer> {
            changesCalls++
            lastChangesTarget = target
            lastChangesScope = scope
            changesFailure?.let { return ApiResult.Failure(it) }
            return ApiResult.Success(ChangesListAnswer(target, null, null, ChangesListState.Unavailable("no-git")), 200)
        }

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
            paneLines += lines
            paneEtags += etag
            paneFailure?.let { return ApiResult.Failure(it) }
            paneValue?.let { return ApiResult.Success(it, status = 200, etag = "\"bounded\"") }
            if (etag != null) return ApiResult.NotModified(etag)
            val host = address.scope.host ?: "local"
            return ApiResult.Success(
                PaneReadResponse(address.paneId, "screen-$host", false, 1),
                status = 200,
                etag = "\"$host\"",
            )
        }

        override suspend fun reply(
            connection: Connection,
            address: PaneAddress,
            text: String,
            submit: Boolean,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> {
            replyCalls += 1
            replyExpectedPrompt = expectedPrompt
            return replyResult
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotApiFailure?.let { return ApiResult.Failure(it) }
            if (snapshotFailure) return ApiResult.Failure(ApiFailure.Network("offline"))
            snapshotValue?.let { return ApiResult.Success(it, 200) }
            return ApiResult.Success(
                SnapshotResponse(
                    bridge = "1.5.0",
                    device = snapshotDevice,
                    agents = emptyList(),
                    shellPanes = emptyList(),
                    workspaces = emptyList(),
                    tabs = emptyList(),
                    ts = 1,
                ),
                200,
            )
        }
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun revokeDevice(connection: Connection, label: String): ApiResult<DevicesResponse> {
            revokeCalls += 1
            return revokeResult
        }
        override suspend fun setNotificationSnooze(
            connection: Connection,
            snoozedUntil: Long?,
        ): ApiResult<NotificationState> {
            snoozeCalls += 1
            return ApiResult.Success(NotificationState(snoozedUntil), 200)
        }
        override suspend fun dismissUpdate(
            connection: Connection,
            version: String,
            scope: UpdateDismissalScope,
        ): ApiResult<UpdateInfo> {
            dismissCalls += 1
            dismissedVersion = version
            dismissedScope = scope
            return ApiResult.Success(
                UpdateInfo(
                    current = "1.5.0", latest = "1.8.0", latestUrl = null, releaseAvailable = true,
                    majorAvailable = null, majorUrl = null, bridgeStale = false, checkedAt = 1,
                    dismissedVersion = version.takeIf { scope == UpdateDismissalScope.OFFER },
                    dismissedCrewVersion = version.takeIf { scope == UpdateDismissalScope.CREW },
                ),
                200,
            )
        }
        override suspend fun startUpdate(
            connection: Connection,
            target: String,
            major: Boolean,
            peersOnly: Boolean,
        ): ApiResult<UpdateStartResponse> {
            updateCalls += 1
            return error("unused")
        }
        override suspend fun keys(
            connection: Connection,
            address: PaneAddress,
            keys: List<String>,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> {
            keysCalls += 1
            keysExpectedPrompt = expectedPrompt
            return ApiResult.Success(ActionResponse(true), 200)
        }

        override suspend fun transcribe(
            connection: Connection,
            audio: AudioUpload,
        ): ApiResult<SttResponse> {
            transcribeCalls += 1
            return ApiResult.Success(SttResponse(ok = true, text = "draft"), 200)
        }
    }

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope = CipherEnvelope(
            ByteArray(12) { 3 }, plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray(),
        )
        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private companion object {
        const val OUTCOME_UNKNOWN_CODE = "launch.outcome_unknown"
    }
}
