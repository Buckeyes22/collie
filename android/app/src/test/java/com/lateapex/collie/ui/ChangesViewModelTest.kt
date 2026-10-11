package com.lateapex.collie.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ChangeCommitAnswer
import com.lateapex.collie.network.ChangeCommitDiffAnswer
import com.lateapex.collie.network.ChangeDiffAnswer
import com.lateapex.collie.network.ChangeDiffState
import com.lateapex.collie.network.ChangeCommitHistoryAnswer
import com.lateapex.collie.network.ChangeCommitHistoryPage
import com.lateapex.collie.network.ChangeCommitHistoryState
import com.lateapex.collie.network.ChangeCommitInfo
import com.lateapex.collie.network.ChangeStatus
import com.lateapex.collie.network.ChangedFile
import com.lateapex.collie.network.ChangedRepo
import com.lateapex.collie.network.ChangesListAnswer
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChangesViewModelTest {
    @Test
    fun everyChangesActionRefusesTransportWhenItsCapturedActorRetiresBeforeAdmission() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                for (action in listOf("list", "diff", "history", "next", "commit", "commitDiff")) {
                    val app = ApplicationProvider.getApplicationContext<CollieApplication>()
                    val store = object : ConnectionStore {
                        override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://changes-admission.fixture/"), "fixture", "paired"))
                        override fun save(connection: Connection) { this.connection.value = connection }
                        override fun clear() { connection.value = null }
                    }
                    val target = ChangesTarget.Workspace("workspace")
                    val scope = Scope(host = "peer", session = "work")
                    val hash = "a".repeat(40)
                    val file = ChangedFile("src/a.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
                    val calls = mutableListOf<String>()
                    val api = object : CollieApi by app.container.api {
                        override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                            ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                                "/listed/root", listOf(ChangedRepo(".", "repo", listOf(file))), false, false, emptyList(),
                            )), 200).also { calls += "list" }
                        override suspend fun changeCommitHistory(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, head: String?, offset: Int, limit: Int) =
                            ApiResult.Success(ChangeCommitHistoryAnswer(target, "Project", ChangeCommitHistoryState.Available(
                                ChangeCommitHistoryPage(repo, "repo", hash, 0, limit, listOf(ChangeCommitInfo(hash, hash.take(7), "current", "author", 1)), 1, false),
                            )), 200).also { calls += "history" }
                        override suspend fun changeDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String): ApiResult<ChangeDiffAnswer> {
                            calls += "diff"; return ApiResult.Failure(ApiFailure.Protocol("Unexpected stale transport"))
                        }
                        override suspend fun changeCommit(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String?): ApiResult<ChangeCommitAnswer> {
                            calls += "commit"; return ApiResult.Failure(ApiFailure.Protocol("Unexpected stale transport"))
                        }
                        override suspend fun changeCommitDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String, commitHash: String?): ApiResult<ChangeCommitDiffAnswer> {
                            calls += "commitDiff"; return ApiResult.Failure(ApiFailure.Protocol("Unexpected stale transport"))
                        }
                    }
                    val repository = CollieRepository(api, store, app.container.originValidator)
                    val viewModel = ChangesViewModel(app as Application, repository, target, scope)
                    advanceUntilIdle()
                    if (action == "next") { viewModel.openHistory("."); advanceUntilIdle() }
                    val before = calls.toList()
                    when (action) {
                        "list" -> viewModel.refresh()
                        "diff" -> viewModel.openFile(".", file)
                        "history" -> viewModel.openHistory(".")
                        "next" -> viewModel.nextHistoryPage()
                        "commit" -> viewModel.openHistoryCommit(".", ChangeCommitInfo(hash, hash.take(7), "current", "author", 1))
                        "commitDiff" -> viewModel.openCommitFile(".", file)
                    }
                    // The screen has captured its actor, but its coroutine has not reached the repository.
                    // Same subject with a new lead epoch would let an omitted expectedContext borrow authority.
                    repository.setLeadEpoch(2)
                    advanceUntilIdle()
                    assertEquals("Retired captured actor must not dispatch $action", before, calls)
                    assertNull(viewModel.state.value.list)
                    assertNull(viewModel.state.value.history)
                    assertNull(viewModel.state.value.detail)
                    assertEquals(ChangesMessage.UNAVAILABLE, viewModel.state.value.error)
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun immutableReadRetirementSurvivesAnInterleavedOrdinaryRefusal() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val app = ApplicationProvider.getApplicationContext<CollieApplication>()
                val store = object : ConnectionStore {
                    override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://changes-refusal.fixture/"), "fixture", "paired"))
                    override fun save(connection: Connection) { this.connection.value = connection }
                    override fun clear() { connection.value = null }
                }
                val target = ChangesTarget.Workspace("workspace")
                val scope = Scope()
                var denied = false
                val api = object : CollieApi by app.container.api {
                    override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup): ApiResult<ChangesListAnswer> =
                        if (denied) ApiResult.Failure(ApiFailure.Http(401, "fixture gateway denial", null)) else
                            ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                                "/listed/root", emptyList(), false, false, emptyList(),
                            )), 200)
                    override suspend fun changeDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String): ApiResult<ChangeDiffAnswer> =
                        ApiResult.Failure(ApiFailure.Http(404, "fixture ordinary refusal", null))
                }
                val repository = CollieRepository(api, store, app.container.originValidator)
                val interleaving = repository.onReadContextInvalidated { reason ->
                    if (reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN) {
                        // A fresh ordinary refusal runs after the closed retirement ACK window.
                        // The earlier immutable retirement event must still leave the screen retired.
                        launch { repository.changeDiff(target, scope, ChangesLookup(), ".", "missing.txt") }
                    }
                }
                val viewModel = ChangesViewModel(app as Application, repository, target, scope)
                advanceUntilIdle()
                val context = repository.readContext(scope, target.id)!!
                repository.changeDiff(target, scope, ChangesLookup(), ".", "missing.txt", expectedContext = context)
                assertTrue(repository.isCurrent(context))
                assertTrue(viewModel.state.value.list?.state is ChangesListState.Available)
                denied = true
                viewModel.refresh()
                advanceUntilIdle()
                assertEquals(404, (repository.accessFailure as ApiFailure.Http).status)
                assertNull(viewModel.state.value.list)
                assertEquals(ChangesMessage.UNAUTHORIZED, viewModel.state.value.error)
                interleaving.close()
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun delayedDiffIsDiscardedWhenTheScopedSubjectChanges() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val app = ApplicationProvider.getApplicationContext<CollieApplication>()
                val initialConnection = Connection(CollieOrigin("https://changes-viewmodel.fixture/"), "fixture", "paired")
                val store = object : ConnectionStore {
                    override val connection = MutableStateFlow<Connection?>(initialConnection)
                    override fun save(connection: Connection) { this.connection.value = connection }
                    override fun clear() { connection.value = null }
                }
                val file = ChangedFile("src/a.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
                val pendingDiff = CompletableDeferred<ApiResult<ChangeDiffAnswer>>()
                var diffCalls = 0
                val target = ChangesTarget.Pane("pane")
                val api = object : CollieApi by app.container.api {
                    override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                        ApiResult.Success(ChangesListAnswer(target, null, null, ChangesListState.Available(
                            "/listed/root", listOf(ChangedRepo(".", "repo", listOf(file))), false, false, emptyList(),
                        )), 200)

                    override suspend fun changeDiff(
                        connection: Connection,
                        target: ChangesTarget,
                        scope: Scope,
                        lookup: ChangesLookup,
                        repo: String,
                        path: String,
                    ): ApiResult<ChangeDiffAnswer> {
                        diffCalls++
                        return pendingDiff.await()
                    }
                }
                val repository = CollieRepository(api, store, app.container.originValidator)
                val viewModel = ChangesViewModel(app as Application, repository, target, Scope(host = "peer", session = "work"))
                advanceUntilIdle()
                assertTrue(viewModel.state.value.list?.state is ChangesListState.Available)

                viewModel.openFile(".", file)
                runCurrent()
                assertEquals(1, diffCalls)
                repository.selectSubject(Scope(host = "other", session = "work"), "replacement-pane")
                pendingDiff.complete(ApiResult.Success(ChangeDiffAnswer(target, null,
                    ChangeDiffState.Available(".", "src/a.kt", null, ChangeStatus.MODIFIED, false, false, false, "+stale\n")), 200))
                advanceUntilIdle()

                assertNull(viewModel.state.value.detail)
                assertNull(viewModel.state.value.list)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delayedHistoryPageIsDiscardedWhenTheScopedSubjectChanges() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val app = ApplicationProvider.getApplicationContext<CollieApplication>()
                val initialConnection = Connection(CollieOrigin("https://changes-history.fixture/"), "fixture", "paired")
                val store = object : ConnectionStore {
                    override val connection = MutableStateFlow<Connection?>(initialConnection)
                    override fun save(connection: Connection) { this.connection.value = connection }
                    override fun clear() { connection.value = null }
                }
                val target = ChangesTarget.Workspace("workspace")
                val pending = CompletableDeferred<ApiResult<ChangeCommitHistoryAnswer>>()
                var historyCalls = 0
                val api = object : CollieApi by app.container.api {
                    override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                        ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                            "/listed/root", emptyList(), false, false, emptyList(),
                        )), 200)

                    override suspend fun changeCommitHistory(
                        connection: Connection,
                        target: ChangesTarget,
                        scope: Scope,
                        lookup: ChangesLookup,
                        repo: String,
                        head: String?,
                        offset: Int,
                        limit: Int,
                    ): ApiResult<ChangeCommitHistoryAnswer> {
                        historyCalls++
                        return pending.await()
                    }
                }
                val repository = CollieRepository(api, store, app.container.originValidator)
                val viewModel = ChangesViewModel(app as Application, repository, target, Scope(host = "peer", session = "work"))
                advanceUntilIdle()
                viewModel.openHistory(".")
                runCurrent()
                assertEquals(1, historyCalls)

                repository.selectSubject(Scope(host = "other-peer", session = "work"), "replacement-workspace")
                val hash = "0123456789abcdef0123456789abcdef01234567"
                val page = ChangeCommitHistoryPage(
                    repo = ".", name = "repo", headHash = hash, offset = 0, limit = 30,
                    commits = listOf(ChangeCommitInfo(hash, hash.take(7), "stale commit", "author", 1)),
                    nextOffset = null, truncated = false,
                )
                pending.complete(ApiResult.Success(ChangeCommitHistoryAnswer(
                    target, "Project", ChangeCommitHistoryState.Available(page),
                ), 200))
                advanceUntilIdle()

                assertNull(viewModel.state.value.history)
                assertNull(viewModel.state.value.list)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun duplicateCommitAcrossHistoryPagesIsRejectedWithoutAppendingIt() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val app = ApplicationProvider.getApplicationContext<CollieApplication>()
                val initialConnection = Connection(CollieOrigin("https://changes-history-duplicate.fixture/"), "fixture", "paired")
                val store = object : ConnectionStore {
                    override val connection = MutableStateFlow<Connection?>(initialConnection)
                    override fun save(connection: Connection) { this.connection.value = connection }
                    override fun clear() { connection.value = null }
                }
                val target = ChangesTarget.Workspace("workspace")
                val hash = "0123456789abcdef0123456789abcdef01234567"
                val oldHash = "1123456789abcdef0123456789abcdef01234567"
                var historyCalls = 0
                val api = object : CollieApi by app.container.api {
                    override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                        ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                            "/listed/root", emptyList(), false, false, emptyList(),
                        )), 200)

                    override suspend fun changeCommitHistory(
                        connection: Connection,
                        target: ChangesTarget,
                        scope: Scope,
                        lookup: ChangesLookup,
                        repo: String,
                        head: String?,
                        offset: Int,
                        limit: Int,
                    ): ApiResult<ChangeCommitHistoryAnswer> {
                        historyCalls++
                        val commit = ChangeCommitInfo(hash, hash.take(7), "current", "author", 2)
                        val page = if (offset == 0) {
                            ChangeCommitHistoryPage(repo, "repo", hash, 0, limit, listOf(commit), 1, false)
                        } else {
                            // A server that repeats a row across page boundaries is malformed.
                            ChangeCommitHistoryPage(repo, "repo", hash, 1, limit, listOf(commit, ChangeCommitInfo(oldHash, oldHash.take(7), "older", "author", 1)), null, false)
                        }
                        return ApiResult.Success(ChangeCommitHistoryAnswer(
                            target, "Project", ChangeCommitHistoryState.Available(page),
                        ), 200)
                    }
                }
                val repository = CollieRepository(api, store, app.container.originValidator)
                val viewModel = ChangesViewModel(app as Application, repository, target, Scope(host = "peer", session = "work"))
                advanceUntilIdle()
                viewModel.openHistory(".")
                advanceUntilIdle()
                assertEquals(1, viewModel.state.value.history?.page?.commits?.size)
                viewModel.nextHistoryPage()
                advanceUntilIdle()

                assertEquals(2, historyCalls)
                assertEquals(listOf(hash), viewModel.state.value.history?.page?.commits?.map { it.hash })
                assertEquals(ChangesMessage.MALFORMED, viewModel.state.value.error)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
