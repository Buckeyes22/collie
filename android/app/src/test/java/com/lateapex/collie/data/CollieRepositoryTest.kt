package com.lateapex.collie.data

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AudioUpload
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.NotificationState
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.UpdateStartResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CollieRepositoryTest {
    private val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
    private val store = FakeStore(connection)
    private val api = FakeApi()
    private val repository = CollieRepository(api, store, OriginValidator())

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
    fun deviceWriteGateIsUnknownUntilSnapshotAndRetainsTheLastSuccessfulDecision() = runBlocking {
        assertFalse(repository.writesAllowed())
        assertTrue(repository.reply(PaneAddress(paneId = "w1:p1"), "blocked") is ApiResult.Failure)
        assertEquals(0, api.replyCalls)

        api.snapshotDevice = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.AUTHORIZED, repository.deviceWriteAuthorization.value)
        assertTrue(repository.writesAllowed())

        api.snapshotFailure = true
        repository.snapshot()
        assertEquals(DeviceWriteAuthorization.AUTHORIZED, repository.deviceWriteAuthorization.value)
        assertTrue(repository.writesAllowed())
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
        api.paneFailure = ApiFailure.Http(403, "device expired", null)
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
        assertTrue(repository.writesAllowed())
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
        var updateCalls = 0
        var revokeCalls = 0
        var transcribeCalls = 0
        var replyResult: ApiResult<ActionResponse> = ApiResult.Success(ActionResponse(true), 200)
        var snapshotDevice: DeviceAuthorization? = null
        var snapshotFailure = false
        var snapshotValue: SnapshotResponse? = null
        var paneFailure: ApiFailure? = null

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
            return error("unused")
        }
        override suspend fun setNotificationSnooze(
            connection: Connection,
            snoozedUntil: Long?,
        ): ApiResult<NotificationState> {
            snoozeCalls += 1
            return ApiResult.Success(NotificationState(snoozedUntil), 200)
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
}
