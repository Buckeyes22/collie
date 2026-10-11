package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.FrozenLaunchIntent
import com.lateapex.collie.data.LaunchIntentState
import com.lateapex.collie.data.StoredLaunchIntent
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.network.WorktreeOpenResponse
import org.junit.Assert.assertEquals
import com.lateapex.collie.network.ApiResult
import android.view.ViewGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class DashboardShellActivityTest {
    @Test
    fun dashboardDefaultFactoryCreatesTheApplicationOnlyViewModelConstructor() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()

        assertNotNull(ViewModelProvider(activity)[MainViewModel::class.java])
    }

    @Test
    fun scopeRowsExposeCurrentSelectionToAccessibility() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val scopeRow = MainActivity::class.java.declaredMethods.single { it.name == "scopeRow" }.apply {
            isAccessible = true
        }
        fun row(active: Boolean) = scopeRow.invoke(
            activity,
            "Workshop",
            "Host",
            active,
            true,
            false,
            R.color.collie_muted,
            {},
        ) as View

        val current = row(true)
        val other = row(false)
        assertTrue(current.isSelected)
        assertEquals(activity.getString(R.string.dashboard_scope_current), ViewCompat.getStateDescription(current))
        assertFalse(other.isSelected)
        assertEquals(null, ViewCompat.getStateDescription(other))
    }

    @Test
    fun connectionBannerActionsKeepTheFortyEightDpTouchFloor() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)

        listOf(
            R.id.dashboard_connection_retry,
            R.id.dashboard_connection_reload,
            R.id.dashboard_idle_resume,
            R.id.host_switcher,
            R.id.session_switcher,
        ).forEach { id ->
            val control = activity.findViewById<View>(id)
            assertTrue("view $id should retain a 48dp height", control.layoutParams.height >= floor)
        }
        assertTrue(activity.findViewById<View>(R.id.dashboard_connection_reload).layoutParams.width >= floor)
        assertTrue(activity.findViewById<View>(R.id.dashboard_connection_banner).layoutParams.height >= floor)
    }

    @Test
    fun idlePauseCoversWithoutDestroyingDashboardAndCatchUpRemovesTheButton() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().start().resume().get()
        MainActivity::class.java.getDeclaredField("latestShellState").apply {
            isAccessible = true
            set(activity, MainUiState(configured = true))
        }
        MainActivity::class.java.getDeclaredField("visible").apply {
            isAccessible = true
            setBoolean(activity, true)
        }
        MainActivity::class.java.getDeclaredMethod("enterIdlePause").apply {
            isAccessible = true
            MainActivity::class.java.getDeclaredField("idleDeadline").apply {
                isAccessible = true
                setLong(activity, 0L)
            }
            invoke(activity)
        }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.dashboard_idle_cover).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.dashboard_idle_resume).visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            activity.findViewById<View>(R.id.dashboard_panel).importantForAccessibility)

        activity.findViewById<View>(R.id.dashboard_idle_resume).performClick()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.dashboard_idle_resume).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.dashboard_idle_progress).visibility)
    }

    @Test
    fun configuredReadOnlyConnectionShowsTheCanonicalPairingRemedyStrip() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()

        MainActivity::class.java.getDeclaredMethod("render", MainUiState::class.java).apply {
            isAccessible = true
            invoke(activity, MainUiState(configured = true, paired = false, writeAuthorized = false))
        }

        val banner = activity.findViewById<android.widget.TextView>(R.id.dashboard_read_only_banner)
        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals(activity.getString(R.string.read_only_not_paired), banner.text.toString())
        assertTrue(banner.isClickable)
    }

    @Test
    fun sectionToggleIsAChevronThatDoesNotRotate() {
        val toggle = boundSectionToggle(open = false)
        assertEquals(View.VISIBLE, toggle.visibility)
        assertEquals(0f, toggle.rotation, 0f)
        assertEquals(R.drawable.ic_history_chevron_right, shadowOf(toggle.drawable).createdFromResId)
    }

    @Test
    fun openSectionToggleIsADownChevron() {
        val toggle = boundSectionToggle(open = true)
        assertEquals(0f, toggle.rotation, 0f)
        assertEquals(R.drawable.ic_history_chevron_down, shadowOf(toggle.drawable).createdFromResId)
    }

    private fun boundSectionToggle(open: Boolean): ImageView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Collie))
        val adapter = DashboardAdapter(onPane = {}, onSpace = {})
        adapter.submitList(
            listOf(
                DashboardItem.PaneSection(
                    bucket = TriageBucket.RECENT,
                    panes = listOf(
                        PaneRow(pane = pane("recent", AgentStatus.IDLE, seen = 1_000), title = "recent", metadata = "", age = null),
                    ),
                    open = open,
                ),
            ),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView.findViewById(R.id.section_toggle)
    }

    private fun pane(id: String, status: AgentStatus, seen: Long?) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = "w1:t1",
        agent = "codex",
        status = status,
        cwd = "/repo/project",
        focused = false,
        lastSeenAt = seen,
    )

    @Test
    fun worktreeFlowIsOneSheet() {
        val activity = launchDashboardWithWorktreeRepos(listOf(repo("collie-app")))
        activity.startWorktreeFlowForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertNotNull(sheet.findViewById<EditText>(R.id.worktree_branch_input))
        assertEquals(
            "In /repos/collie-app\nBranches from the repository's current HEAD.",
            sheet.findViewById<TextView>(R.id.worktree_base_caption)!!.text.toString(),
        )
        assertEquals(1, ShadowDialog.getShownDialogs().size)
    }

    @Test
    fun anUnopenedWorktreeCanStillBeOpenedAndWaitsForConfirmation() {
        val app = ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>()
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(
                com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://worktree.fixture/"), "phone", null))
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val original = app.container.repository
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        field.set(app.container, com.lateapex.collie.data.CollieRepository(app.container.api, store, app.container.originValidator))
        try {
            // Restored after the one-sheet rework dropped it: the old options sheet listed every
            // linked worktree with no open space, and opening one asked first (NativeInteractionTest
            // openWorktreeWaitsForConfirmation, removed 2026-09-10).
            val activity = launchDashboardWithWorktreeRepos(listOf(repo("collie-app")))
            val opened = mutableListOf<String>()
            activity.worktreeLister = { _, _ ->
                ApiResult.Success(
                    com.lateapex.collie.network.WorktreeListResponse(
                        ok = true,
                        worktrees = listOf(
                            com.lateapex.collie.network.Worktree("/repos/collie-app", "main", "collie-app", linked = false, prunable = false),
                            com.lateapex.collie.network.Worktree("/fixture/existing", "existing-fixture", null, linked = true, prunable = false),
                            com.lateapex.collie.network.Worktree("/fixture/open", "already-open", "w9", linked = true, prunable = false),
                        ),
                    ),
                    200,
                )
            }
            activity.worktreeOpener = { _, path, _ -> opened += path; ApiResult.Failure(com.lateapex.collie.network.ApiFailure.Network("fixture")) }
            activity.startWorktreeFlowForTest()
            val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
            val existing = sheet.findViewById<ViewGroup>(R.id.worktree_existing)!!
            val labels = (0 until existing.childCount).map(existing::getChildAt).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("existing-fixture"))
            assertFalse(labels.contains("already-open"))
            assertFalse(labels.contains("main"))

            (0 until existing.childCount).map(existing::getChildAt).filterIsInstance<TextView>().single { it.text == "existing-fixture" }.performClick()
            val confirm = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
            assertEquals(activity.getString(R.string.worktree_open_title), confirm.findViewById<TextView>(R.id.collie_sheet_title)!!.text.toString())
            assertTrue(opened.isEmpty())
        } finally { field.set(app.container, original) }
    }

    @Test
    fun http200OutcomeUnknownOpensReachableRetryAndDuplicateRiskAbandonActions() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val app = activity.application as CollieApplication
        @Suppress("UNCHECKED_CAST")
        val connectionFlow = EncryptedConnectionStore::class.java.getDeclaredField("mutableConnection")
            .apply { isAccessible = true }
            .get(app.container.connectionStore) as kotlinx.coroutines.flow.MutableStateFlow<Connection?>
        connectionFlow.value = Connection(CollieOrigin("https://collie.example/"), "phone", "paired-token")
        val repository = app.container.repository
        val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(activity) as MainViewModel
        val scope = viewModel.currentScope
        val context = repository.readContext(scope)!!
        val record = StoredLaunchIntent(
            FrozenLaunchIntent(
                requestId = "33333333-3333-4333-8333-333333333333",
                origin = context.key.origin,
                mount = context.key.origin,
                connectionGeneration = context.key.connectionGeneration,
                pairedDeviceIdentity = context.key.pairedDeviceIdentity,
                leadEpoch = context.key.leadEpoch,
                host = context.key.host,
                session = context.key.session,
                subjectIdentity = context.key.subjectIdentity,
                operation = "launch",
                confirmedNoPrompts = false,
                canonicalBody = "{\"requestId\":\"33333333-3333-4333-8333-333333333333\",\"shell\":true}",
            ),
            LaunchIntentState.unknown,
        )
        MainViewModel::class.java.getDeclaredField("mutableState").apply {
            isAccessible = true
        }.get(viewModel).let { it as kotlinx.coroutines.flow.MutableStateFlow<MainUiState> }
            .value = MainUiState(unknownLaunchIntents = listOf(record))
        MainActivity::class.java.getDeclaredMethod(
            "handleLaunchIntentOutcome",
            ApiResult::class.java,
            String::class.java,
            Scope::class.java,
            com.lateapex.collie.data.ScopedReadContext::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, ApiResult.Success(CreateResponse(ok = false, error = "receipt write failed", code = "launch.outcome_unknown"), 200), record.intent.requestId, scope, context)
        }

        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val labels = collectText(sheet.window!!.decorView)
        assertTrue(labels.any { it.contains(record.intent.requestId) })
        assertTrue(labels.any { it.contains("If the bridge lost its receipt, this may create another pane") })
        assertTrue(labels.contains(activity.getString(R.string.launch_retry_same_request)))
        assertTrue(labels.contains(activity.getString(R.string.launch_abandon_request)))
        findTextView(sheet.window!!.decorView, activity.getString(R.string.launch_abandon_request))!!.performClick()
        val confirmation = ShadowDialog.getLatestDialog()
        assertTrue(collectText(confirmation.window!!.decorView).any { it.contains("Abandoning removes the saved request and permits a replacement") })
    }

    @Test
    fun workspaceWorktreeUnknownOutcomeUsesTheSameBoundedRecoverySheet() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val app = activity.application as CollieApplication
        @Suppress("UNCHECKED_CAST")
        val connectionFlow = EncryptedConnectionStore::class.java.getDeclaredField("mutableConnection")
            .apply { isAccessible = true }
            .get(app.container.connectionStore) as kotlinx.coroutines.flow.MutableStateFlow<Connection?>
        connectionFlow.value = Connection(CollieOrigin("https://collie.example/"), "phone", "paired-token")
        val repository = app.container.repository
        val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(activity) as MainViewModel
        val scope = viewModel.currentScope
        val context = repository.readContext(scope)!!
        val requestId = "77777777-7777-4777-8777-777777777777"
        val record = StoredLaunchIntent(
            FrozenLaunchIntent(
                requestId = requestId,
                origin = context.key.origin,
                mount = context.key.origin,
                connectionGeneration = context.key.connectionGeneration,
                pairedDeviceIdentity = context.key.pairedDeviceIdentity,
                leadEpoch = context.key.leadEpoch,
                host = context.key.host,
                session = context.key.session,
                subjectIdentity = context.key.subjectIdentity,
                operation = "worktree.create",
                targetIdentity = "workspace-opaque-7",
                confirmedNoPrompts = false,
                canonicalBody = "{\"branch\":\"feature/native\",\"requestId\":\"$requestId\"}",
            ),
            LaunchIntentState.unknown,
        )
        MainViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(viewModel).let { it as kotlinx.coroutines.flow.MutableStateFlow<MainUiState> }
            .value = MainUiState(unknownLaunchIntents = listOf(record))

        MainActivity::class.java.getDeclaredMethod(
            "handleWorktreeIntentOutcome",
            ApiResult::class.java,
            String::class.java,
            Scope::class.java,
            com.lateapex.collie.data.ScopedReadContext::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, ApiResult.Success(WorktreeOpenResponse(ok = false, code = "launch.outcome_unknown"), 200), requestId, scope, context)
        }

        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val labels = collectText(sheet.window!!.decorView)
        assertTrue(labels.contains(activity.getString(R.string.launch_retry_same_request)))
        assertTrue(labels.contains(activity.getString(R.string.launch_abandon_request)))
        assertTrue(labels.any { it.contains(requestId) })
    }

    private fun launchDashboardWithWorktreeRepos(repos: List<WorkspaceSummary>): MainActivity {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().start().resume().get()
        MainActivity::class.java.getDeclaredField("latestShellState").apply {
            isAccessible = true
        }.set(
            activity,
            MainUiState(
                configured = true,
                paired = true,
                writeAuthorized = true,
                snapshot = com.lateapex.collie.network.SnapshotResponse(
                    bridge = "connected",
                    agents = emptyList(),
                    shellPanes = emptyList(),
                    workspaces = repos,
                    tabs = emptyList(),
                    ts = 1,
                ),
            ),
        )
        return activity
    }

    private fun repo(label: String) = WorkspaceSummary(
        workspaceId = label,
        number = 1,
        label = label,
        focused = false,
        activeTabId = "",
        tabCount = 0,
        paneCount = 0,
        repoRoot = "/repos/$label",
        isWorktree = false,
    )

    private fun collectText(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { collectText(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun findTextView(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findTextView(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    @Test
    fun unreachableServerReadsAsWaitingNotAsAnUnauthorisedDevice() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val render = MainActivity::class.java.getDeclaredMethod("render", MainUiState::class.java).apply { isAccessible = true }
        val banner = activity.findViewById<android.widget.TextView>(R.id.dashboard_read_only_banner)

        render.invoke(activity, MainUiState(configured = true, paired = true, writeAuthorized = false, snapshotFailed = true, error = "Can't reach Collie"))
        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals(activity.getString(R.string.read_only_unreachable), banner.text.toString())

        render.invoke(activity, MainUiState(configured = true, paired = true, writeAuthorized = false, snapshot = com.lateapex.collie.network.SnapshotResponse(bridge = "collie", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1L)))
        assertEquals(activity.getString(R.string.read_only_device_unauthorised), banner.text.toString())
    }
}
