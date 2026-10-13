package com.lateapex.collie.ui

import android.app.Application
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FilesViewModelTest {
    private val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
    private lateinit var api: FakeFilesApi
    private lateinit var repository: CollieRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeFilesApi()
        repository = CollieRepository(api, TestConnectionStore(connection), OriginValidator())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun navigatesOnlyRelativePathsAndBackRestoresSelectedDirectory() {
        val vm = FilesViewModel(Application(), repository, FilesTarget.Pane("w1:p1"), Scope(session = "work"), "", null, false)
        assertEquals("", api.fileReads.last().second)
        vm.openDirectory("src")
        vm.openFile("src/Main.kt")
        assertEquals("src/Main.kt", api.fileReads.last().second)
        vm.backToDirectory()
        assertEquals("src", api.fileReads.last().second)
        assertEquals("src", vm.state.value.directory)
        val count = api.fileReads.size
        vm.openFile("../private.key")
        assertEquals(count, api.fileReads.size)
        assertEquals(FilesMessage.NOT_FOUND, vm.state.value.error)
    }

    @Test fun aSlowerOldFolderResponseCannotReplaceTheCurrentDirectory() = runBlocking {
        val old = CompletableDeferred<ApiResult<FilesAnswer>>()
        api.deferPath = "old"
        api.deferred = old
        val vm = FilesViewModel(Application(), repository, FilesTarget.Workspace("ws-1"), Scope(), "", null, false)
        vm.openDirectory("old")
        vm.openDirectory("current")
        assertEquals("current", vm.state.value.directory)
        assertTrue(vm.state.value.answer is FilesAnswer.Listing)
        old.complete(ApiResult.Success(FilesAnswer.Listing("/repo", "old", emptyList(), false), 200))
        assertEquals("current", vm.state.value.directory)
        assertFalse(vm.state.value.loading)
    }

    @Test fun aValidUnavailableAnswerIsVisibleAsNoReadableFolder() {
        api.unavailableReason = "no-folder"
        val vm = FilesViewModel(Application(), repository, FilesTarget.Workspace("ws-1"), Scope(), "", null, false)
        assertFalse(vm.state.value.loading)
        assertEquals(FilesMessage.NO_FOLDER, vm.state.value.error)
    }

    @Test fun oldPeerCapability404AndMissingPath404RemainDistinct() {
        api.failure = ApiFailure.Http(404, "unknown-path", null)
        val missing = FilesViewModel(Application(), repository, FilesTarget.Workspace("ws-1"), Scope(), "", null, false)
        assertEquals(FilesMessage.NOT_FOUND, missing.state.value.error)
        api.failure = ApiFailure.Http(404, null, "capability.unsupported")
        val unsupported = FilesViewModel(Application(), repository, FilesTarget.Workspace("ws-1"), Scope(), "", null, false)
        assertEquals(FilesMessage.UNSUPPORTED, unsupported.state.value.error)
    }

    @Test fun acceptedReferenceClassificationOwnsRefreshBackAndRestoreNavigation() {
        val vm = FilesViewModel(Application(), repository, FilesTarget.Pane("w1:p1"), Scope(), "", null, false)
        api.referenceFile = "docs/Main.kt"
        vm.openReference("docs/Main.kt")
        assertEquals("docs/Main.kt", vm.state.value.path)
        assertEquals("docs", vm.state.value.directory)
        vm.refresh()
        assertEquals("path:docs/Main.kt", api.fileRoutes.last())
        vm.backToDirectory()
        assertEquals("dir:docs", api.fileRoutes.last())
        assertEquals("docs", vm.state.value.directory)

        vm.openReference("docs/sub")
        assertEquals(null, vm.state.value.path)
        assertEquals("docs/sub", vm.state.value.directory)
        vm.refresh()
        assertEquals("dir:docs/sub", api.fileRoutes.last())
        vm.backToDirectory()
        assertEquals("dir:docs", api.fileRoutes.last())
    }

    @Test fun unexpectedSuccessfulStatusCannotPublishAnUnadmittedListing() {
        api.successStatus = 201
        val vm = FilesViewModel(Application(), repository, FilesTarget.Workspace("ws-1"), Scope(), "", null, false)
        assertFalse(vm.state.value.loading)
        assertEquals(null, vm.state.value.answer)
        assertEquals(null, vm.state.value.acceptedContext)
        assertEquals(FilesMessage.MALFORMED, vm.state.value.error)
    }

    private class TestConnectionStore(connection: Connection) : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(connection)
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
    }

    private class FakeFilesApi : CollieApi {
        val fileReads = mutableListOf<Pair<FilesTarget, String>>()
        val fileRoutes = mutableListOf<String>()
        var successStatus = 200
        var referenceFile: String? = null
        var deferPath: String? = null
        var deferred: CompletableDeferred<ApiResult<FilesAnswer>>? = null
        var unavailableReason: String? = null
        var failure: ApiFailure? = null
        override suspend fun health(connection: Connection) = ApiResult.Success(HealthResponse(true, "1", false, "solo"), 200)
        override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
            SnapshotResponse("1", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 0), 200,
        )
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean) = ApiResult.Success(PaneReadResponse(address.paneId, "", false, 0), 200)
        override suspend fun refresh(connection: Connection, scope: Scope) = ApiResult.Success(ActionResponse(true), 200)
        override suspend fun pair(origin: CollieOrigin, code: String, label: String) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun devices(connection: Connection) = ApiResult.Success(DevicesResponse(false, devices = emptyList()), 200)
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?) = ApiResult.Success(ActionResponse(true), 200)
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?) = ApiResult.Success(ActionResponse(true), 200)
        override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
            val name = path?.value ?: dir?.value.orEmpty()
            fileReads += target to name
            fileRoutes += (if (path == null) "dir:" else "path:") + name
            if (name == deferPath) return deferred!!.await()
            failure?.let { return ApiResult.Failure(it) }
            if (path == null && name == referenceFile) return ApiResult.Failure(ApiFailure.Http(404, "unknown-path", null))
            val answer = if (path == null && unavailableReason != null) FilesAnswer.Unavailable(unavailableReason!!)
            else if (path == null) FilesAnswer.Listing("/repo", name, emptyList(), false)
            else FilesAnswer.Source("/repo", name, 12, 1.25, false, false, "source")
            return ApiResult.Success(answer, successStatus)
        }
    }
}
