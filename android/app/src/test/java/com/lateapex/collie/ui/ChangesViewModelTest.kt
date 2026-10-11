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
import com.lateapex.collie.network.ChangeDiffAnswer
import com.lateapex.collie.network.ChangeDiffState
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
}
