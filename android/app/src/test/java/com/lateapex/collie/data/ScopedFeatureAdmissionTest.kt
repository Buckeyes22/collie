package com.lateapex.collie.data

import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScopedFeatureAdmissionTest {
    private val initial = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "secret")
    private class Store(initial: Connection) : ConnectionStore {
        private val state = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = state
        override fun save(connection: Connection) { state.value = connection }
        override fun clear() { state.value = null }
    }
    private class Api : CollieApi {
        var snapshotBlock: (suspend () -> Unit)? = null
        var authority = ControlAuthority("lead", "one")
        var protectedFailure: ApiFailure? = null
        var keys = 0
        var uploads = 0
        var documentUploads = 0
        var configReads = 0
        override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
            configReads++; return ApiResult.Success(BridgeConfigResponse(), 200)
        }
        var readAuthority: ControlAuthority? = null
        var beforeRefresh: (suspend () -> Unit)? = null
        var refreshCalls = 0
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotBlock?.invoke()
            return ApiResult.Success(SnapshotResponse("connected", controlAuthority = authority,
                agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(),
                device = DeviceAuthorization(false, null, true), ts = 1), 200)
        }
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> =
            protectedFailure?.let { ApiResult.Failure(it) } ?: ApiResult.Success(PaneReadResponse(address.paneId, "screen", false, 1), 200, controlAuthority = readAuthority)
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> {
            this.keys++; return ApiResult.Success(ActionResponse(true), 200)
        }
        override suspend fun upload(connection: Connection, address: PaneAddress, upload: ImageUpload): ApiResult<UploadResponse> {
            uploads++; return ApiResult.Success(UploadResponse(true, "/image.png"), 200)
        }
        override suspend fun upload(connection: Connection, address: PaneAddress, upload: UploadSource): ApiResult<UploadResponse> {
            documentUploads++; return ApiResult.Success(UploadResponse(true, "/document.txt"), 200)
        }
        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> {
            refreshCalls++; beforeRefresh?.invoke(); return ApiResult.Success(ActionResponse(true), 200)
        }
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
    }
    @Test fun queuedDocumentPolicyReadCannotBorrowReplacementActor() = runTest {
        val api = Api(); val store = Store(initial)
        val repository = CollieRepository(api, store, OriginValidator())
        val scope = Scope(host = "desk", session = "work")
        repository.selectSubject(scope, "pane")
        val picked = repository.readContext(scope, "pane")!!
        val request = async { repository.config(scope, picked) }
        store.save(Connection(initial.origin, initial.label, "replacement")) // Queue has not entered repository admission.
        runCurrent()
        assertTrue(request.await() is ApiResult.Failure)
        assertEquals(0, api.configReads)
        val replacement = repository.readContext(scope, "pane")!!
        assertTrue(repository.config(scope, replacement) is ApiResult.Success)
        assertEquals(1, api.configReads)
    }

    @Test fun writesRequireTheExactHostSessionSubjectAndLiveGeneration() = runTest {
        val api = Api(); val repository = CollieRepository(api, Store(initial), OriginValidator())
        val desk = Scope(host = "desk", session = "work")
        repository.selectSubject(desk, "pane")
        repository.snapshot(desk)
        assertTrue(repository.writesAllowed(desk, "pane"))
        for (address in listOf(PaneAddress(Scope(host = "other", session = "work"), "pane"),
            PaneAddress(Scope(host = "desk", session = "other"), "pane"), PaneAddress(desk, "other"))) {
            assertTrue(repository.keys(address, listOf("Enter")) is ApiResult.Failure)
        }
        repository.showSavedCopy(1)
        assertTrue(repository.keys(PaneAddress(desk, "pane"), listOf("Enter")) is ApiResult.Failure)
        assertEquals(0, api.keys)
    }
    @Test fun authenticatedLeaderIncarnationInvalidatesOldActorsAndRequiresFreshSnapshot() = runTest {
        val api = Api(); val repository = CollieRepository(api, Store(initial), OriginValidator())
        repository.snapshot(); val old = repository.readContext()!!
        api.authority = ControlAuthority("lead", "two")
        assertTrue(repository.snapshot() is ApiResult.Failure)
        assertFalse(repository.isCurrent(old)); assertFalse(repository.writesAllowed())
        repository.snapshot()
        assertTrue(repository.writesAllowed())
        assertTrue(repository.readContext()!!.key.leadEpoch > old.key.leadEpoch)
    }
    @Test fun protectedRefusalCancelsAnOlderSnapshotWithoutErasingUnknownPairing() = runTest {
        val api = Api(); val store = Store(initial); val repository = CollieRepository(api, store, OriginValidator())
        repository.snapshot()
        val entered = CompletableDeferred<Unit>()
        api.snapshotBlock = { entered.complete(Unit); awaitCancellation() }
        val oldRead = async { repository.snapshot() }
        entered.await()
        api.protectedFailure = ApiFailure.Http(403, "device expired", "pairing.expired")
        repository.readPane(PaneAddress(paneId = "pane"))
        assertTrue(oldRead.await() is ApiResult.Failure)
        assertEquals("secret", store.connection.value?.token)
        assertFalse(repository.writesAllowed())
        api.snapshotBlock = null; repository.snapshot(); assertTrue(repository.writesAllowed())
    }
    @Test fun aFreshRefreshNeverJoinsTheCancelledActorForTheSameHostSession() = runTest {
        val api = Api(); val repository = CollieRepository(api, Store(initial), OriginValidator())
        val entered = CompletableDeferred<Unit>()
        api.beforeRefresh = { entered.complete(Unit); awaitCancellation() }
        val old = async { repository.refresh() }
        entered.await()
        repository.selectScope(Scope(viewAll = true))
        api.beforeRefresh = null
        assertTrue(repository.refresh() is ApiResult.Success)
        assertTrue(old.await() is ApiResult.Failure)
        assertEquals(2, api.refreshCalls)
    }

    @Test fun peerReadHeadersInvalidateActorsWhenTheLeadChangesAtTheSameOrigin() = runTest {
        val api = Api(); val repository = CollieRepository(api, Store(initial), OriginValidator())
        val scope = Scope(host = "peer", session = "work")
        repository.selectSubject(scope, "pane")
        repository.snapshot(scope)
        val before = repository.readContext(scope, "pane")!!
        api.readAuthority = ControlAuthority("replacement", "two")
        assertTrue(repository.readPane(PaneAddress(scope, "pane")) is ApiResult.Failure)
        assertFalse(repository.isCurrent(before)); assertFalse(repository.writesAllowed(scope, "pane"))
    }

    @Test fun delayedMediaPreparationCannotUploadToAReplacementConnection() = runTest {
        val api = Api(); val store = Store(initial); val repository = CollieRepository(api, store, OriginValidator())
        val address = PaneAddress(paneId = "pane")
        repository.selectSubject(address.scope, address.paneId); repository.snapshot()
        val captured = repository.readContext(address.scope, address.paneId)!!
        val prepared = CompletableDeferred<Unit>()
        val result = async { prepared.await(); repository.upload(address, ImageUpload("image.png", "image/png", byteArrayOf(1)), captured) }
        store.save(Connection(CollieOrigin("https://other.example/mount/"), "phone", "newsecret"))
        repository.snapshot(); assertTrue(repository.writesAllowed(address.scope, address.paneId))
        prepared.complete(Unit)
        assertTrue(result.await() is ApiResult.Failure); assertEquals(0, api.uploads)
        assertFalse(captured.toString().contains("secret"))
    }

    @Test fun documentPreparedForAnOldConnectionIsDisposedWithoutCrossHostUpload() = runTest {
        val api = Api(); val store = Store(initial); val repository = CollieRepository(api, store, OriginValidator())
        val address = PaneAddress(paneId = "pane")
        repository.selectSubject(address.scope, address.paneId); repository.snapshot()
        val captured = repository.readContext(address.scope, address.paneId)!!
        val spool = java.io.File.createTempFile("collie-upload-test-", ".part").apply { writeText("private") }
        val source = UploadSource.Document("brief.txt", "text/plain", spool, spool.length())
        store.save(Connection(CollieOrigin("https://other.example/mount/"), "phone", "newsecret"))
        repository.snapshot()

        assertTrue(repository.upload(address, source, captured) is ApiResult.Failure)
        assertEquals(0, api.documentUploads)
        assertFalse(spool.exists())
        try {
            source.openOnce()
            fail("stale scoped source must remain unusable")
        } catch (_: IllegalStateException) { }
    }
}
