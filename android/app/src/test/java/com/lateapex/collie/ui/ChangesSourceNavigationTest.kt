package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ChangesSourceNavigationTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Test fun visibleCurrentActionOpensExactRootAndNestedPaneWorkspaceSourceWithOriginalActor() {
        for (target in listOf(ChangesTarget.Pane("pane"), ChangesTarget.Workspace("workspace"))) {
            for (repo in listOf(".", "nested/repo")) Fixture(target, repo).use { fixture ->
                fixture.openDiff()
                val button = fixture.sourceButton()
                assertTrue(button.isShown)
                assertTrue(button.height >= (48 * app.resources.displayMetrics.density).toInt())
                button.performClick()
                val intent = requireNotNull(shadowOf(fixture.activity).nextStartedActivity)
                val expected = if (repo == ".") "src/file.kt" else "$repo/src/file.kt"
                assertEquals(FilesActivity::class.java.name, intent.component!!.className)
                assertEquals(expected, intent.getStringExtra("files_path"))
                assertEquals("peer", intent.getStringExtra("host"))
                assertEquals("work", intent.getStringExtra("session"))
                assertFalse(intent.extras.toString().contains("original-private-token"))
                assertFalse(intent.extras.toString().contains("/server-only/root"))
                val files = fixture.openFiles(intent)
                awaitUi { text(files.get().window.decorView, "current native source") != null }
                assertEquals(listOf(expected), fixture.fileReads.map { it.path })
                assertSame(fixture.owner, fixture.fileReads.single().connection)
                assertEquals(Scope("peer", "work"), fixture.fileReads.single().scope)
                assertEquals(if (target is ChangesTarget.Pane) FilesTarget.Pane(target.id) else FilesTarget.Workspace(target.id), fixture.fileReads.single().target)
                assertEquals(1, fixture.diffReads)
            }
        }
    }

    @Test fun historicalAndDeletedDiffsRemainVisibleWithHonestNoticeAndNoCurrentSource() {
        Fixture(status = ChangeStatus.DELETED).use { fixture ->
            fixture.openDiff()
            assertNotNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_deleted_source_notice)))
            assertNotNull(text(fixture.activity.window.decorView, "   1 │ +reviewed diff"))
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
        }
        Fixture().use { fixture ->
            text(fixture.activity.window.decorView, app.getString(R.string.changes_last_commit))!!.performClick()
            idle()
            text(fixture.activity.window.decorView, "M  src/file.kt")!!.performClick()
            idle()
            assertNotNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_historical_source_notice)))
            assertNotNull(text(fixture.activity.window.decorView, "   1 │ +historical diff"))
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
            assertEquals(1, fixture.commitDiffReads)
            assertTrue(fixture.fileReads.isEmpty())
        }
    }

    @Test fun mismatchedUnlistedInvalidAndUnacceptedDiffsNeverCreateTheSourceAction() {
        for (configure in listOf<(Fixture) -> Unit>(
            { it.returnedRepo = "different" }, { it.returnedPath = "other.kt" },
            { it.diffStatus = 202 }, { it.diffFailure = ApiFailure.Http(404, "unknown-path", "unknown-path") },
        )) Fixture().use { fixture ->
            configure(fixture)
            fixture.openDiff()
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.fileReads.isEmpty())
        }
        for (repo in listOf("/absolute", "../outside", ".git")) Fixture(repo = repo).use { fixture ->
            fixture.openDiff()
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
            assertTrue(fixture.fileReads.isEmpty())
        }
        Fixture(path = "../outside.kt").use { fixture ->
            fixture.openDiff()
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
        }
    }

    @Test fun retainedSourceButtonsCannotBorrowReplacementScopeSubjectSavedCopyOrRefreshedDetail() {
        for (retire in listOf<(Fixture) -> Unit>(
            { it.store.save(Connection(it.owner.origin, it.owner.label, "replacement-private-token")) },
            { it.repository.selectScope(Scope("other-peer", "other-session")) },
            { it.repository.selectSubject(Scope("peer", "work"), "other-subject") },
            { it.repository.showSavedCopy(1) },
            { runBlocking { it.repository.config(Scope("peer", "work")) } },
            { it.activity.findViewById<View>(R.id.changes_refresh).performClick() },
        )) Fixture().use { fixture ->
            fixture.openDiff()
            val held = fixture.sourceButton()
            retire(fixture)
            idle()
            held.performClick()
            idle()
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.fileReads.isEmpty())
        }
    }

    @Test fun delayedIntentRetirementAndWrongInstanceTargetPathScopeMissingTokenAndExpiryMakeZeroFilesReads() {
        for (invalidate in listOf<(Fixture, Intent) -> Unit>(
            { f, _ -> f.store.save(Connection(f.owner.origin, f.owner.label, "replacement-private-token")) },
            { f, _ -> f.repository.showSavedCopy(1) },
            { _, i -> i.putExtra("files_target_id", "other") },
            { _, i -> i.putExtra("files_path", "other.kt") },
            { _, i -> i.putExtra("host", "other-peer") },
            { _, i -> i.removeExtra("files_handoff_token") },
            { _, _ -> org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(30)) },
        )) Fixture().use { fixture ->
            fixture.openDiff()
            fixture.sourceButton().performClick()
            val intent = requireNotNull(shadowOf(fixture.activity).nextStartedActivity)
            invalidate(fixture, intent)
            val files = fixture.openFiles(intent)
            idle()
            assertTrue(fixture.fileReads.isEmpty())
            assertTrue(files.get().findViewById<TextView>(R.id.files_status).text.isNotEmpty())
        }
        Fixture().use { fixture ->
            fixture.openDiff(); fixture.sourceButton().performClick()
            val intent = requireNotNull(shadowOf(fixture.activity).nextStartedActivity)
            val replacement = CollieRepository(fixture.api, fixture.store, app.container.originValidator)
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, replacement)
            fixture.openFiles(intent)
            idle()
            assertTrue(fixture.fileReads.isEmpty())
        }
    }

    @Test fun originalHandoffSurvivesActualRotationButCannotReplayAfterProcessStyleRestoration() {
        Fixture().use { fixture ->
            fixture.openDiff(); fixture.sourceButton().performClick()
            val intent = requireNotNull(shadowOf(fixture.activity).nextStartedActivity)
            val files = fixture.openFiles(intent)
            awaitUi { text(files.get().window.decorView, "current native source") != null }
            val previous = files.get()
            val config = android.content.res.Configuration(previous.resources.configuration).apply {
                orientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            files.configurationChange(config)
            idle()
            assertNotSame(previous, files.get())
            assertNotNull(text(files.get().window.decorView, "current native source"))
            assertEquals(1, fixture.fileReads.size)
            val saved = Bundle()
            files.saveInstanceState(saved)
            assertTrue(saved.getBoolean("files.state.handoff_required"))
            assertFalse(saved.toString().contains("original-private-token"))
            val strippedIntent = Intent(intent).apply {
                removeExtra("files_handoff_token")
                removeExtra("files_handoff_required")
            }
            val restored = fixture.openFiles(strippedIntent, saved)
            idle()
            assertEquals(1, fixture.fileReads.size)
            assertNull(text(restored.get().window.decorView, "current native source"))
        }
    }

    @Test fun filesDeviceFactorAndDeletedAfterDiffRefusalsReachTheActualFilesScreen() {
        for (failure in listOf(
            ApiFailure.Http(403, "Device factor required", "device.not_authorised", source = FailureSource.COLLIE, factor = FailureFactor.DEVICE),
            ApiFailure.Http(404, "unknown-path", "unknown-path"),
        )) Fixture().use { fixture ->
            fixture.openDiff(); fixture.fileFailure = failure
            fixture.sourceButton().performClick()
            val files = fixture.openFiles(requireNotNull(shadowOf(fixture.activity).nextStartedActivity))
            awaitUi { fixture.fileReads.size == 1 && files.get().findViewById<TextView>(R.id.files_status).text.isNotEmpty() }
            val expected = if (failure.status == 403) R.string.files_proxy_required else R.string.files_not_found
            assertEquals(app.getString(expected), files.get().findViewById<TextView>(R.id.files_status).text.toString())
            assertNull(text(files.get().window.decorView, "current native source"))
            assertEquals(1, fixture.fileReads.size)
        }
    }

    @Test fun delayedDiffCannotPublishSourceAfterCredentialReplacementAndFreshPositiveStillWorks() {
        Fixture().use { fixture ->
            val release = CompletableDeferred<Unit>()
            fixture.diffGate = release
            fixture.openDiff()
            assertEquals(1, fixture.diffReads)
            fixture.store.save(Connection(fixture.owner.origin, fixture.owner.label, "replacement-private-token"))
            release.complete(Unit)
            idle()
            assertNull(text(fixture.activity.window.decorView, app.getString(R.string.changes_open_current_source)))
            assertTrue(fixture.fileReads.isEmpty())
            fixture.diffGate = null
            fixture.activity.findViewById<View>(R.id.changes_refresh).performClick()
            idle()
            fixture.openDiff()
            fixture.sourceButton().performClick()
            fixture.openFiles(requireNotNull(shadowOf(fixture.activity).nextStartedActivity))
            idle()
            assertEquals(1, fixture.fileReads.size)
            assertSame(fixture.store.connection.value, fixture.fileReads.single().connection)
        }
    }

    @Test fun singleUseBoundedPendingHandoffsCannotBeReplayedAndRetirementReleasesEverySlot() {
        Fixture().use { fixture ->
            fixture.openDiff()
            val intents = (0 until 32).map {
                fixture.sourceButton().performClick()
                requireNotNull(shadowOf(fixture.activity).nextStartedActivity)
            }
            fixture.sourceButton().performClick()
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            fixture.openFiles(intents.first())
            idle()
            assertEquals(1, fixture.fileReads.size)
            fixture.openFiles(intents.first())
            idle()
            assertEquals(1, fixture.fileReads.size)
            fixture.repository.showSavedCopy(1)
            for (intent in intents.drop(1)) fixture.openFiles(intent)
            idle()
            assertEquals(1, fixture.fileReads.size)
            fixture.activity.findViewById<View>(R.id.changes_refresh).performClick()
            idle()
            fixture.openDiff()
            fixture.sourceButton().performClick()
            assertNotNull(shadowOf(fixture.activity).nextStartedActivity)
        }
    }

    @Test fun actualMountedHttpsChangesDiffAndFilesCarryExactTargetScopeAndOriginalPairing() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        val original = app.container.repository
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val store = Store(Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "mounted-private-token"))
        val http = CollieApiClient.defaultHttpClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).protocols(listOf(Protocol.HTTP_1_1)).build()
        val repository = CollieRepository(CollieApiClient(http, Json { ignoreUnknownKeys = true }), store, app.container.originValidator)
        field.set(app.container, repository)
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"paneId":"pane","available":true,"root":"/server-only/root","repos":[{"relPath":".","name":"repo","files":[{"path":"src/file.kt","status":"M","added":1,"removed":0,"binary":false}]}],"truncated":false,"depthLimited":false,"clean":[]}""").build())
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"paneId":"pane","available":true,"repo":".","path":"src/file.kt","status":"M","binary":false,"directory":false,"truncated":false,"diff":"+HTTPS diff"}""").build())
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"paneId":"pane","available":true,"root":"/server-only/root","path":"src/file.kt","size":12,"mtimeMs":1,"binary":false,"truncated":false,"text":"HTTPS source"}""").build())
        val changes = Robolectric.buildActivity(ChangesActivity::class.java, ChangesActivity.intentForPane(app, "pane", Scope("peer", "work"), "Pane")).create().start().resume().visible()
        var files: ActivityController<FilesActivity>? = null
        try {
            awaitUi { text(changes.get().window.decorView, "M  src/file.kt") != null }
            text(changes.get().window.decorView, "M  src/file.kt")!!.performClick()
            awaitUi { text(changes.get().window.decorView, app.getString(R.string.changes_open_current_source)) != null }
            text(changes.get().window.decorView, app.getString(R.string.changes_open_current_source))!!.performClick()
            val intent = requireNotNull(shadowOf(changes.get()).nextStartedActivity)
            val filesController = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
            files = filesController
            awaitUi { text(filesController.get().window.decorView, "HTTPS source") != null }
            val requests = (0 until 3).map { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            assertEquals(listOf("/mount/api/pane/pane/changes", "/mount/api/pane/pane/changes", "/mount/api/pane/pane/files"), requests.map { it.url!!.encodedPath })
            for (request in requests) {
                assertEquals("Bearer mounted-private-token", request.headers["Authorization"])
                assertEquals("peer", request.url!!.queryParameter("host"))
                assertEquals("work", request.url!!.queryParameter("session"))
            }
            assertEquals(".", requests[1].url!!.queryParameter("repo"))
            assertEquals("src/file.kt", requests[1].url!!.queryParameter("path"))
            assertEquals("src/file.kt", requests.last().url!!.queryParameter("path"))
            assertNull(requests.last().url!!.queryParameter("root"))
            assertEquals(3, server.requestCount)
        } finally {
            files?.pause()?.stop()?.destroy()
            changes.pause().stop().destroy()
            field.set(app.container, original)
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.close()
        }
    }

    private data class Read(val connection: Connection, val target: FilesTarget, val scope: Scope, val path: String?)
    private class Store(owner: Connection) : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(owner)
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
    }
    private inner class Fixture(
        val target: ChangesTarget = ChangesTarget.Workspace("workspace"),
        val repo: String = ".",
        val path: String = "src/file.kt",
        val status: ChangeStatus = ChangeStatus.MODIFIED,
    ) : AutoCloseable {
        val owner = Connection(CollieOrigin("https://changes.fixture/"), "phone", "original-private-token")
        val store = Store(owner)
        val fileReads = mutableListOf<Read>()
        var diffReads = 0
        var commitDiffReads = 0
        var returnedRepo = repo
        var returnedPath = path
        var diffStatus = 200
        var diffFailure: ApiFailure? = null
        var fileFailure: ApiFailure? = null
        var diffGate: CompletableDeferred<Unit>? = null
        private val listed = ChangedFile(path, null, status, 1, 0, false)
        val api = object : CollieApi by app.container.api {
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> =
                ApiResult.Failure(ApiFailure.Http(403, "fixture access frozen", "device.not_authorised", source = FailureSource.COLLIE, factor = FailureFactor.DEVICE))
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) = ApiResult.Success(
                ChangesListAnswer(target, "Project", null, ChangesListState.Available("/server-only/root", listOf(ChangedRepo(repo, "repo", listOf(listed))), false, false, emptyList())), 200)
            override suspend fun changeDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String): ApiResult<ChangeDiffAnswer> {
                diffReads++
                diffGate?.let { withContext(NonCancellable) { it.await() } }
                return diffFailure?.let { ApiResult.Failure(it) } ?: ApiResult.Success(ChangeDiffAnswer(target, "Project", ChangeDiffState.Available(returnedRepo, returnedPath, null, status, false, false, false, "+reviewed diff")), diffStatus)
            }
            override suspend fun changeCommit(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String?) = ApiResult.Success(
                ChangeCommitAnswer(target, "Project", ChangeCommitState.Available(repo, "repo", ChangeCommitInfo("a".repeat(40), "aaaaaaa", "history", "author", 1), listOf(listed), false)), 200)
            override suspend fun changeCommitDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String, commitHash: String?) = ApiResult.Success(
                ChangeCommitDiffAnswer(target, "Project", ChangeDiffState.Available(repo, path, null, status, false, false, false, "+historical diff", "a".repeat(40))), 200).also { commitDiffReads++ }
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                fileReads += Read(connection, target, scope, path?.value)
                return fileFailure?.let { ApiResult.Failure(it) } ?: ApiResult.Success(FilesAnswer.Source("/server-only/root", path!!.value, 21, 1.0, false, false, "current native source"), 200)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        private val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        private val original = app.container.repository
        private val files = mutableListOf<ActivityController<FilesActivity>>()
        private val controller: ActivityController<ChangesActivity>
        val activity get() = controller.get()
        init {
            field.set(app.container, repository)
            val intent = if (target is ChangesTarget.Pane) ChangesActivity.intentForPane(app, target.id, Scope("peer", "work"), "Project") else ChangesActivity.intentForWorkspace(app, target.id, Scope("peer", "work"), "Project")
            controller = Robolectric.buildActivity(ChangesActivity::class.java, intent).create().start().resume().visible()
            idle()
        }
        fun openDiff() { text(activity.window.decorView, status.wire + "  " + path)!!.performClick(); idle() }
        fun sourceButton() = requireNotNull(text(activity.window.decorView, app.getString(R.string.changes_open_current_source)))
        fun openFiles(intent: Intent, saved: Bundle? = null) = Robolectric.buildActivity(FilesActivity::class.java, intent).create(saved).start().resume().visible().also { files += it }
        override fun close() {
            files.reversed().forEach { it.pause().stop().destroy() }
            controller.pause().stop().destroy()
            repository.selectScope(Scope("fixture-disposed"))
            field.set(app.container, original)
        }
    }
    private fun text(view: View, prefix: String): TextView? {
        if (view is TextView && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) text(view.getChildAt(i), prefix)?.let { return it }
        return null
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitUi(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do { idle(); if (predicate()) return; Thread.sleep(10) } while (System.nanoTime() < deadline)
        assertTrue("actual native Activity completes within the existing5s bound", predicate())
    }
}
