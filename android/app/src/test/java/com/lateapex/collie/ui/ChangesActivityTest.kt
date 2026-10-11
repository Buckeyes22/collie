package com.lateapex.collie.ui

import android.content.ComponentName
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
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ChangeCommitAnswer
import com.lateapex.collie.network.ChangeCommitDiffAnswer
import com.lateapex.collie.network.ChangeCommitInfo
import com.lateapex.collie.network.ChangeCommitHistoryAnswer
import com.lateapex.collie.network.ChangeCommitHistoryPage
import com.lateapex.collie.network.ChangeCommitHistoryState
import com.lateapex.collie.network.ChangeCommitState
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class ChangesActivityTest {
    @Test
    fun workspaceReaderListsOnlyBridgeRowsAndOpensTheSelectedDiffAcrossRecreation() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://changes.fixture/"), "fixture", "paired"))
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val file = ChangedFile("src/a.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
        var diffReads = 0
        val api = object : CollieApi by app.container.api {
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                ApiResult.Success(
                    ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                        "/opaque/server/root", listOf(ChangedRepo(".", "repo", listOf(file))), false, false, emptyList(),
                    )), 200,
                )

            override suspend fun changeDiff(
                connection: Connection,
                target: ChangesTarget,
                scope: Scope,
                lookup: ChangesLookup,
                repo: String,
                path: String,
            ) = ApiResult.Success(
                ChangeDiffAnswer(target, "Project", ChangeDiffState.Available(repo, path, null, ChangeStatus.MODIFIED,
                    false, false, false, "+new line\n")), 200,
            ).also { diffReads++ }

            override suspend fun changeCommit(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String?): ApiResult<ChangeCommitAnswer> =
                ApiResult.Success(ChangeCommitAnswer(target, "Project", ChangeCommitState.Unavailable("no-commit")), 200)
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(
            ChangesActivity::class.java,
            ChangesActivity.intentForWorkspace(app, "workspace", Scope(host = "peer", session = "work"), "Project"),
        ).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(R.id.changes_content)
            assertTrue(findText(content, "repo") != null)
            assertTrue(findText(content, "M  src/a.kt") != null)
            assertEquals("Changes", activity.findViewById<TextView>(R.id.changes_title).text.toString())

            findText(content, "M  src/a.kt")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, diffReads)
            val diffText = findText(activity.findViewById(R.id.changes_content), "   1 │ +new line")
            assertNotNull(diffText)
            assertNull("TalkBack must receive the selectable diff text, not a generic replacement description", diffText!!.contentDescription)
            assertTrue(diffText.isTextSelectable)
            assertTrue(diffText.text.contains("+new line"))

            controller.recreateWithoutVisibleFrameDrain()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(findText(controller.get().findViewById(R.id.changes_content), "   1 │ +new line") != null)
            assertEquals(1, diffReads)
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    @Test
    fun activityIsPrivateAndBothTargetsHaveExplicitEntryMethods() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(ComponentName(context, ChangesActivity::class.java), 0)
        assertFalse(info.exported)
        val pane = ChangesActivity.intentForPane(context, "pane", Scope(host = "peer", session = "work"), "Pane")
        val workspace = ChangesActivity.intentForWorkspace(context, "space", Scope(), "Space")
        assertEquals("pane", pane.getStringExtra("pane_id"))
        assertEquals("space", workspace.getStringExtra("workspace_id"))
        assertNotNull(pane.component)
        assertNotNull(workspace.component)
    }

    @Test
    fun dashboardHasAReachableAccessibleChangesPicker() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val button = activity.findViewById<View>(R.id.dashboard_changes_button)
        assertTrue(button.hasOnClickListeners())
        assertEquals(context.getString(R.string.changes_open_dashboard_accessibility), button.contentDescription)
        activity.finish()
    }

    @Test
    fun truncatedLastCommitShowsIncompleteNoticeAndItsFileUsesCommitDiffEndpoint() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = connectedStore()
        val file = ChangedFile("src/a.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
        val latestHash = "a".repeat(40)
        var commitDiffReads = 0
        val target = ChangesTarget.Workspace("workspace")
        val api = object : CollieApi by app.container.api {
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                    "/opaque/root", listOf(ChangedRepo(".", "repo", listOf(file))), false, false, emptyList(),
                )), 200)

            override suspend fun changeCommit(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String?) =
                ApiResult.Success(ChangeCommitAnswer(target, "Project", ChangeCommitState.Available(
                    repo, "repo", ChangeCommitInfo(latestHash, latestHash.take(7), "latest change", "author", 1),
                    listOf(file), truncated = true,
                )), 200)

            override suspend fun changeCommitDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String, commitHash: String?) =
                ApiResult.Success(ChangeCommitDiffAnswer(target, "Project", ChangeDiffState.Available(
                    repo, path, null, ChangeStatus.MODIFIED, false, false, false, "+committed line\n", commitHash,
                )), 200).also { commitDiffReads++ }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(
            ChangesActivity::class.java,
            ChangesActivity.intentForWorkspace(app, "workspace", Scope(), "Project"),
        ).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(R.id.changes_content)
            findText(content, "Last commit")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val commitContent = activity.findViewById<ViewGroup>(R.id.changes_content)
            assertNotNull(findText(commitContent, "This file list is incomplete"))
            assertNotNull(findText(commitContent, "latest change"))

            findText(commitContent, "M  src/a.kt")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, commitDiffReads)
            assertNotNull(findText(activity.findViewById(R.id.changes_content), "   1 │ +committed line"))
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    @Test
    fun commitHistoryPagesWithoutDuplicatesAndReturnsFromSelectedCommitDetail() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = connectedStore()
        val target = ChangesTarget.Workspace("workspace")
        val hashes = listOf("a".repeat(40), "b".repeat(40), "c".repeat(40))
        val commitFile = ChangedFile("src/a.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
        val seen = mutableListOf<String>()
        var detailReads = 0
        val api = object : CollieApi by app.container.api {
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                    "/opaque/root", emptyList(), false, false, listOf(com.lateapex.collie.network.CleanRepo(".", "repo")),
                )), 200)

            override suspend fun changeCommitHistory(
                connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup,
                repo: String, head: String?, offset: Int, limit: Int,
            ): ApiResult<ChangeCommitHistoryAnswer> {
                val selected = if (offset == 0) hashes.take(2) else hashes.drop(offset).take(2)
                seen += selected
                val commits = selected.mapIndexed { index, hash ->
                    val ordinal = hashes.indexOf(hash)
                    ChangeCommitInfo(hash, hash.take(7), "revision $ordinal", "author $ordinal", 1_700_000_000L - ordinal)
                }
                val page = ChangeCommitHistoryPage(repo, "repo", hashes.first(), offset, limit, commits,
                    if (offset + selected.size < hashes.size) offset + selected.size else null, truncated = false)
                return ApiResult.Success(ChangeCommitHistoryAnswer(target, "Project", ChangeCommitHistoryState.Available(page)), 200)
            }

            override suspend fun changeCommit(
                connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, commitHash: String?,
            ): ApiResult<ChangeCommitAnswer> {
                detailReads++
                return ApiResult.Success(ChangeCommitAnswer(target, "Project", ChangeCommitState.Available(
                    repo, "repo", ChangeCommitInfo(commitHash!!, commitHash.take(7), "revision ${hashes.indexOf(commitHash)}", "author", 1),
                    listOf(commitFile), false,
                )), 200)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(
            ChangesActivity::class.java, ChangesActivity.intentForWorkspace(app, "workspace", Scope(), "Project"),
        ).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            var content = controller.get().findViewById<ViewGroup>(R.id.changes_content)
            findText(content, "Commit history")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = controller.get().findViewById(R.id.changes_content)
            assertNotNull(findText(content, hashes[0].take(7) + "  revision 0"))
            assertNotNull(findText(content, hashes[1].take(7) + "  revision 1"))
            findText(content, "Older commits")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = controller.get().findViewById(R.id.changes_content)
            assertNotNull(findText(content, hashes[2].take(7) + "  revision 2"))
            findText(content, "Previous commits")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = controller.get().findViewById(R.id.changes_content)
            assertNotNull(findText(content, hashes[0].take(7) + "  revision 0"))
            findText(content, hashes[1].take(7) + "  revision 1")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = controller.get().findViewById(R.id.changes_content)
            assertEquals(1, detailReads)
            assertNotNull(findText(content, "revision 1"))
            assertNotNull(findText(content, "Selected commit"))
            assertNull(findText(content, "Last commit"))
            assertNotNull(findText(content, "M  src/a.kt"))
            findText(content, "Back to history")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = controller.get().findViewById(R.id.changes_content)
            assertNotNull(findText(content, hashes[0].take(7) + "  revision 0"))
            assertEquals(listOf(hashes[0], hashes[1], hashes[2]), seen)
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    @Test
    fun cappedHistoryShowsItsPinnedRangeWithoutClaimingThereAreNoOlderCommits() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val target = ChangesTarget.Workspace("workspace")
        val headHash = "a".repeat(40)
        var reads = 0
        val api = object : CollieApi by app.container.api {
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                ApiResult.Success(ChangesListAnswer(target, "Project", null, ChangesListState.Available(
                    "/opaque/root", emptyList(), false, false, listOf(com.lateapex.collie.network.CleanRepo(".", "repo")),
                )), 200)
            override suspend fun changeCommitHistory(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup,
                repo: String, head: String?, offset: Int, limit: Int): ApiResult<ChangeCommitHistoryAnswer> {
                reads++
                val end = minOf(offset + limit, 10_000)
                val commits = (offset until end).map { ordinal ->
                    val hash = if (ordinal == 0) headHash else ordinal.toString(16).padStart(40, '0')
                    ChangeCommitInfo(hash, hash.take(7), "revision $ordinal", "author", 1)
                }
                val page = ChangeCommitHistoryPage(repo, "repo", headHash, offset, limit, commits,
                    if (end < 10_000) end else null, truncated = end == 10_000)
                return ApiResult.Success(ChangeCommitHistoryAnswer(target, "Project", ChangeCommitHistoryState.Available(page)), 200)
            }
        }
        val repository = CollieRepository(api, connectedStore(), app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(ChangesActivity::class.java,
            ChangesActivity.intentForWorkspace(app, "workspace", Scope(), "Project")).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            findText(controller.get().findViewById(R.id.changes_content), "Commit history")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            repeat(333) {
                findText(controller.get().findViewById(R.id.changes_content), "Older commits")!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            val content = controller.get().findViewById<ViewGroup>(R.id.changes_content)
            assertEquals(334, reads)
            assertNotNull(findText(content, "Commits 9991–10000 · pinned at ${headHash.take(12)}"))
            assertNotNull(findText(content, app.getString(R.string.changes_history_truncated)))
            assertNull(findText(content, "No older commits"))
            assertNull(findText(content, "Older commits"))
            assertNotNull(findText(content, 9999.toString(16).padStart(40, '0').take(7) + "  revision 9999"))
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    @Test
    fun largestSupportedListingMaterializesOnlyOneAccessibleFilePageAndRetainsSelection() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = connectedStore()
        val repos = (0 until 20).map { repoIndex ->
            ChangedRepo("repo-$repoIndex", "repo-$repoIndex", (0 until 1_000).map { fileIndex ->
                ChangedFile("src/file-${fileIndex.toString().padStart(4, '0')}.kt", null, ChangeStatus.MODIFIED, 1, 0, false)
            })
        }
        val target = ChangesTarget.Workspace("workspace")
        val api = object : CollieApi by app.container.api {
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) =
                ApiResult.Success(ChangesListAnswer(target, "Project", null,
                    ChangesListState.Available("/opaque/root", repos, false, false, emptyList())), 200)

            override suspend fun changeDiff(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup, repo: String, path: String) =
                ApiResult.Success(ChangeDiffAnswer(target, "Project", ChangeDiffState.Available(
                    repo, path, null, ChangeStatus.MODIFIED, false, false, false, "+selected page line\n",
                )), 200)
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(
            ChangesActivity::class.java,
            ChangesActivity.intentForWorkspace(app, "workspace", Scope(), "Project"),
        ).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            var content = activity.findViewById<ViewGroup>(R.id.changes_content)
            assertEquals(100, fileRowCount(content))
            assertNotNull(findText(content, "Files 1–100 of 20000"))

            findText(content, "Next files")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = activity.findViewById(R.id.changes_content)
            assertEquals(100, fileRowCount(content))
            assertNotNull(findText(content, "Files 101–200 of 20000"))
            controller.recreateWithoutVisibleFrameDrain()
            shadowOf(Looper.getMainLooper()).idle()
            val recreatedActivity = controller.get()
            content = recreatedActivity.findViewById(R.id.changes_content)
            assertEquals(100, fileRowCount(content))
            assertNotNull(findText(content, "Files 101–200 of 20000"))
            val selected = findText(content, "M  src/file-0100.kt")
            assertNotNull(selected)
            selected!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = recreatedActivity.findViewById(R.id.changes_content)
            assertNotNull(findText(content, "   1 │ +selected page line"))

            findText(content, "Back to changes")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            content = recreatedActivity.findViewById(R.id.changes_content)
            assertEquals(100, fileRowCount(content))
            assertNotNull(findText(content, "Files 101–200 of 20000"))
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    private fun connectedStore() = object : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://changes.fixture/"), "fixture", "paired"))
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
    }

    private fun fileRowCount(root: View): Int {
        var count = if (root is TextView && root.text.toString().startsWith("M  src/file-")) 1 else 0
        if (root is ViewGroup) for (index in 0 until root.childCount) count += fileRowCount(root.getChildAt(index))
        return count
    }

    private fun findText(root: View, prefix: String): TextView? {
        if (root is TextView && root.text.toString().startsWith(prefix)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findText(root.getChildAt(index), prefix)?.let { return it }
        }
        return null
    }
}

/** Real Activity replacement and retained ViewModel, without generic recreate()'s forced visible frame drain. */
private fun org.robolectric.android.controller.ActivityController<ChangesActivity>.recreateWithoutVisibleFrameDrain() {
    val prior = get()
    val configuration = android.content.res.Configuration(prior.resources.configuration)
    configuration.orientation = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    } else {
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }
    configurationChange(configuration)
    org.junit.Assert.assertNotSame("configuration change replaces the actual Activity", prior, get())
}
