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

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val value = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = value
        override fun save(connection: Connection) { value.value = connection }
        override fun clear() { value.value = null }
    }

    private class FakeApi : CollieApi {
        val etags = mutableListOf<String?>()

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
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
