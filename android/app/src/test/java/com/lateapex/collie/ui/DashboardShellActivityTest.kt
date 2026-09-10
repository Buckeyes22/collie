package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DashboardShellActivityTest {
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
    fun connectionBannerActionsKeepTheFortyFourDpTouchFloor() {
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        ).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)

        listOf(
            R.id.dashboard_connection_retry,
            R.id.dashboard_connection_reload,
        ).forEach { id ->
            val control = activity.findViewById<View>(id)
            assertTrue("view $id should retain a 44dp height", control.layoutParams.height >= floor)
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
