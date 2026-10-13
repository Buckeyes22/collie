package com.lateapex.collie.data

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.SnapshotResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Test

class HardeningLifecycleRepositoryTest {
    @Test
    fun paneBodyAndEtagAreScopedToTheConfiguredOrigin() = runBlocking {
        val store = FakeStore(Connection(CollieOrigin("https://one.example/"), "phone", "token"))
        val api = FakeApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val address = PaneAddress(Scope(host = "desk", session = "work"), "w1:p1")

        val first = repository.readPane(address) as ApiResult.Success
        repository.connectReadOnly("https://two.example/", "phone")
        val second = repository.readPane(address) as ApiResult.Success

        assertEquals("https://one.example/", first.value.pane.text)
        assertEquals("https://two.example/", second.value.pane.text)
        assertEquals(listOf(null, null), api.etags)
    }


    @Test
    fun aReadFromADisconnectedCredentialCannotPopulateTheNewConnectionCache() = runBlocking {
        val original = Connection(CollieOrigin("https://one.example/"), "phone", "old-token")
        val store = FakeStore(original)
        val api = FakeApi()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforePane = { entered.complete(Unit); release.await() }
        val repository = CollieRepository(api, store, OriginValidator())
        val address = PaneAddress(paneId = "p1")
        val pending = async { repository.readPane(address) }
        entered.await()
        repository.disconnect()
        store.save(Connection(original.origin, original.label, "new-token"))
        release.complete(Unit)
        assertTrue(pending.await() is ApiResult.Failure)
        api.beforePane = null
        repository.readPane(address)
        assertNull(api.etags.last())
    }

    @Test
    fun cachedBodiesAreNotSharedByDifferentCredentialsAtTheSameOrigin() = runBlocking {
        val original = Connection(CollieOrigin("https://one.example/"), "phone", "old-token")
        val store = FakeStore(original)
        val api = FakeApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val address = PaneAddress(paneId = "p1")
        repository.readPane(address)
        store.save(Connection(original.origin, original.label, "new-token"))
        repository.readPane(address)
        assertEquals(listOf(null, null), api.etags)
    }

    @Test
    fun aCancellationResistantReadCannotCrossHostSessionOrSubjectNavigation() = runBlocking {
        val store = FakeStore(Connection(CollieOrigin("https://one.example/"), "phone", "token"))
        val api = FakeApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val before = PaneAddress(Scope(host = "desk", session = "work"), "p1")
        val after = PaneAddress(Scope(host = "laptop", session = "personal"), "p2")
        repository.selectSubject(before.scope, before.paneId)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforePane = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { entered.complete(Unit); release.await() } }
        val pending = async { repository.readPane(before) }
        entered.await()
        repository.selectSubject(after.scope, after.paneId)
        release.complete(Unit)
        assertTrue(pending.await() is ApiResult.Failure)
        api.beforePane = null
        repository.readPane(after)
        assertNull(api.etags.last())
        repository.selectSubject(before.scope, before.paneId)
        repository.readPane(before)
        assertNull("old response must not repopulate its cache", api.etags.last())
    }

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val value = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = value
        override fun save(connection: Connection) { value.value = connection }
        override fun clear() { value.value = null }
    }

    private class FakeApi : CollieApi {
        val etags = mutableListOf<String?>()
        var beforePane: (suspend () -> Unit)? = null

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
            beforePane?.invoke()
            etags += etag
            if (etag != null) return ApiResult.NotModified(etag)
            return ApiResult.Success(
                PaneReadResponse(address.paneId, connection.origin.value, false, 1),
                status = 200,
                etag = "\"${connection.origin.value}\"",
            )
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = error("unused")
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(
            connection: Connection,
            address: PaneAddress,
            text: String,
            submit: Boolean,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(
            connection: Connection,
            address: PaneAddress,
            keys: List<String>,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
    }
}
