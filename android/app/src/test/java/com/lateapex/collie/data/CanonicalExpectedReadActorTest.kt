package com.lateapex.collie.data

import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Canonical admission retains the renderer's resolved actor; API custody sees that exact object. */
class CanonicalExpectedReadActorTest {
    private class Store(initial: Connection) : ConnectionStore {
        private val state = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = state
        override fun save(connection: Connection) { state.value = connection }
        override fun clear() { state.value = null }
    }
    private data class Read(val method: String, val scope: Scope, val actor: ScopedReadContext?)
    private class Api : CollieApi {
        val reads = mutableListOf<Read>()
        private fun record(method: String, scope: Scope) { reads += Read(method, scope, RequestActorCustody.actor()) }
        override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
            record("config", scope); return ApiResult.Success(BridgeConfigResponse(), 200)
        }
        override suspend fun folders(connection: Connection, scope: Scope): ApiResult<FoldersResponse> {
            record("folders", scope); return ApiResult.Success(FoldersResponse(emptyList(), emptyList(), "/fixture"), 200)
        }
        override suspend fun cacheRules(connection: Connection, etag: String?): ApiResult<CacheRulesResponse> {
            record("cache-rules", Scope(viewAll = true)); return ApiResult.Failure(ApiFailure.Protocol("unexpected guard bypass"))
        }
        override suspend fun history(connection: Connection, address: PaneAddress, limit: Int, before: String?, etag: String?): ApiResult<PaneHistoryResponse> {
            record("history", address.scope); return ApiResult.Success(PaneHistoryResponse(address.paneId, true), 200)
        }
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = error("unused")
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> = error("unused")
        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> = error("unused")
    }
    private val connection = Connection(CollieOrigin("https://read.fixture/mount/"), "phone", "paired-fixture")
    private val selected = Scope(host = "desk", session = "session-a")
    private val wide = Scope(viewAll = true)

    @Test fun currentNullAndPaneSubjectsKeepExactOriginalActorAndScopeAcrossConfigAndFolderDispatchers() = runBlocking {
        val api = Api(); val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(selected, "pane-a")
        for ((scope, subject) in listOf(wide to null, selected to "pane-a")) {
            val actor = requireNotNull(repository.readContext(scope, subject)); assertTrue(repository.isCurrent(actor))
            val start = api.reads.size
            assertTrue(withContext(Dispatchers.Default) { repository.config(scope, actor) } is ApiResult.Success)
            assertTrue(withContext(Dispatchers.Default) { repository.config(expectedContext = actor) } is ApiResult.Success)
            assertTrue(withContext(Dispatchers.Default) { repository.folders(scope, actor) } is ApiResult.Success)
            val admitted = api.reads.drop(start); assertEquals(listOf("config", "config", "folders"), admitted.map { it.method })
            for (read in admitted) { assertEquals(scope, read.scope); assertSame(actor, read.actor); assertEquals(subject, read.actor!!.key.subjectIdentity) }
        }
        assertEquals(6, api.reads.size)
    }

    @Test fun explicitDifferentSubjectAndScopeStayRefusedWhileExactPaneHistoryRemainsAdmitted() = runBlocking {
        val api = Api(); val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(selected, "pane-a")
        val noSubject = requireNotNull(repository.readContext(wide, null))
        assertTrue(repository.history(PaneAddress(wide, "pane-a"), expectedContext = noSubject) is ApiResult.Failure)
        assertTrue(repository.config(Scope(host = "other"), noSubject) is ApiResult.Failure)
        assertEquals(0, api.reads.size)
        val widePane = requireNotNull(repository.readContext(wide, "pane-a"))
        assertTrue(repository.cacheRules(wide, subjectIdentity = null, expectedContext = widePane) is ApiResult.Failure)
        assertEquals("an explicitly supplied null cannot borrow a pane actor", 0, api.reads.size)
        val pane = requireNotNull(repository.readContext(selected, "pane-a"))
        assertTrue(repository.history(PaneAddress(selected, "pane-b"), expectedContext = pane) is ApiResult.Failure)
        assertEquals(0, api.reads.size)
        assertTrue(withContext(Dispatchers.Default) { repository.history(PaneAddress(selected, "pane-a"), expectedContext = pane) } is ApiResult.Success)
        assertEquals(1, api.reads.size); assertSame(pane, api.reads.single().actor)
        assertEquals(selected, api.reads.single().scope)
    }

    @Test fun nullSubjectNeverSurvivesRetiredNavigationLeadConnectionOrSavedCopyAndFreshActorStillReads() = runBlocking {
        for (transition in listOf("subject", "scope", "lead", "connection", "saved-copy")) {
            val api = Api(); val store = Store(connection); val repository = CollieRepository(api, store, OriginValidator())
            repository.selectSubject(selected, "pane-a")
            val noSubject = requireNotNull(repository.readContext(wide, null)); val pane = requireNotNull(repository.readContext(selected, "pane-a"))
            when (transition) {
                "subject" -> repository.selectSubject(selected, "pane-b")
                "scope" -> repository.selectSubject(Scope(host = "other", session = "session-b"), "pane-a")
                "lead" -> repository.setLeadEpoch(9)
                "connection" -> store.save(Connection(connection.origin, connection.label, "new-paired-fixture"))
                else -> repository.showSavedCopy(1)
            }
            for ((scope, actor) in listOf(wide to noSubject, selected to pane)) {
                assertFalse("$transition retires the captured actor", repository.isCurrent(actor))
                assertTrue(withContext(Dispatchers.Default) { repository.config(scope, actor) } is ApiResult.Failure)
                assertTrue(withContext(Dispatchers.Default) { repository.config(expectedContext = actor) } is ApiResult.Failure)
                assertTrue(withContext(Dispatchers.Default) { repository.folders(scope, actor) } is ApiResult.Failure)
            }
            assertEquals("$transition dispatches zero retired reads", 0, api.reads.size)
            val fresh = requireNotNull(repository.readContext(wide, null)); assertTrue(repository.isCurrent(fresh))
            assertTrue(withContext(Dispatchers.Default) { repository.config(wide, fresh) } is ApiResult.Success)
            assertTrue(withContext(Dispatchers.Default) { repository.folders(wide, fresh) } is ApiResult.Success)
            assertEquals(2, api.reads.size); api.reads.forEach { assertSame(fresh, it.actor); assertEquals(wide, it.scope) }
        }
    }
}
