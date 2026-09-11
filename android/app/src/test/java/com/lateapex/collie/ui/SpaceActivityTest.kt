package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SpaceActivityTest {
    @Test
    fun intentCarriesTheCompleteScopedWorkspaceIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary(
            workspaceId = "w1",
            number = 1,
            label = "Native client",
            focused = true,
            activeTabId = "t1",
            tabCount = 2,
            paneCount = 3,
            host = "desk",
        )

        val intent = SpaceActivity.intent(context, workspace, "work")

        assertEquals("w1", intent.getStringExtra(SpaceActivity.EXTRA_WORKSPACE_ID))
        assertEquals("desk", intent.getStringExtra(SpaceActivity.EXTRA_HOST))
        assertEquals("work", intent.getStringExtra(SpaceActivity.EXTRA_SESSION))
        assertEquals("Native client", intent.getStringExtra(SpaceActivity.EXTRA_TITLE))
    }

    @Test
    fun actionAvailabilityRequiresPairingAndHonoursExplicitCapabilityDenials() {
        val limited = MuxConfigResponse(
            capabilities = mapOf(
                "createTab" to true,
                "createSpace" to false,
                "renameTab" to false,
                "closeTab" to true,
            ),
        )

        assertFalse(SpaceActionModel.canUse(false, null, "createTab"))
        assertTrue(SpaceActionModel.canUse(true, limited, "createTab"))
        assertFalse(SpaceActionModel.canUse(true, limited, "createSpace"))
        assertEquals(listOf(SpaceTabAction.CLOSE), SpaceActionModel.tabActions(true, limited))
        assertEquals(emptyList<SpaceTabAction>(), SpaceActionModel.tabActions(false, null))
    }

    @Test
    fun tabCloseCopyNamesTheDestructivePaneCount() {
        val one = tab(panes = 1)
        val many = tab(panes = 3)

        assertEquals(
            com.lateapex.collie.R.string.tab_close_one_pane,
            SpaceActionModel.closeMessageResource(one),
        )
        assertEquals(
            com.lateapex.collie.R.string.tab_close_many_panes,
            SpaceActionModel.closeMessageResource(many),
        )
    }

    @Test
    fun spaceHeaderKeepsSettingsReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary("w1", 1, "Native", true, "t1", 1, 1)
        val activity = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().get()

        activity.findViewById<android.view.View>(com.lateapex.collie.R.id.settings_button).performClick()

        assertEquals(
            SettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun systemBackLeavesTheSpaceEvenWhenATabFilterIsSelected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary("w1", 1, "Native", true, "t1", 1, 1)
        val activity = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().start().resume().get()
        val selection = SpaceActivity::class.java.getDeclaredField("selectedTabId").apply {
            isAccessible = true
            set(activity, "t1")
        }

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals("t1", selection.get(activity))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun headerMarkReturnsToDashboardAndClearsStackedSpaceRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary("w1", 1, "Native", true, "t1", 1, 1)
        val activity = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().get()

        activity.findViewById<android.view.View>(com.lateapex.collie.R.id.back_button).performClick()

        val dashboard = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, dashboard.component?.className)
        assertTrue(dashboard.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(dashboard.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun missingSpaceReturnsToDashboardWithNotFoundRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary("missing", 1, "Missing", false, "", 0, 0)
        val activity = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().get()

        SpaceActivity::class.java.getDeclaredMethod("render", SpaceUiState::class.java).apply {
            isAccessible = true
            invoke(activity, SpaceUiState(loading = false, error = activity.getString(com.lateapex.collie.R.string.space_missing)))
        }

        assertEquals(
            MainActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
        assertTrue(activity.isFinishing)
    }

    @Test
    fun spaceHeaderHasNoSiblingStripAndScrollsTheActiveTabIntoView() {
        val activity = launchSpace(workspaces = 14, tabs = 8, currentTab = 7)
        assertNull(activity.findViewById<View?>(R.id.spaces_strip))
        val strip = activity.findViewById<HorizontalScrollView>(R.id.tabs_strip)
        val chips = activity.findViewById<ViewGroup>(R.id.tab_chips)
        val active = (0 until chips.childCount).map(chips::getChildAt).first(View::isSelected)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(strip.scrollX >= active.left - 200)
        assertTrue(strip.isHorizontalFadingEdgeEnabled)
    }

    @Test
    fun overviewCardIsGoneAndSubtitleCarriesCounts() {
        val activity = launchSpace(workspaces = 2, tabs = 3, currentTab = 0)
        assertEquals("3 tabs · 3 panes", activity.findViewById<TextView>(R.id.space_subtitle).text.toString())
    }

    private fun launchSpace(workspaces: Int, tabs: Int, currentTab: Int): SpaceActivity {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = WorkspaceSummary("w1", 1, "StormLens", true, "t$currentTab", tabs, tabs)
        val activity = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().start().resume().get()
        val agents = (0 until tabs).map { index -> pane("p$index", "t$index") }
        val content = SpaceContent(
            workspace = workspace,
            tabs = (0 until tabs).map { index ->
                SpaceTab(TabSummary("t$index", "w1", index + 1, "Tab ${index + 1}", index == currentTab, 1), listOf(agents[index]))
            },
            workspaces = (0 until workspaces).map { index ->
                WorkspaceSummary("s$index", index + 1, "Space $index", false, "", 0, 0)
            },
            agents = agents,
        )
        SpaceActivity::class.java.getDeclaredField("selectedTabId").apply {
            isAccessible = true
        }.set(activity, "t$currentTab")
        SpaceActivity::class.java.getDeclaredMethod("render", SpaceUiState::class.java).apply {
            isAccessible = true
        }.invoke(activity, SpaceUiState(loading = false, content = content))
        return activity
    }

    private fun pane(id: String, tabId: String) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "StormLens",
        workspaceNumber = 1,
        tabId = tabId,
        agent = "codex",
        status = AgentStatus.IDLE,
        cwd = "/repo/stormlens",
        focused = false,
        tabLabel = "Tab $tabId",
    )

    private fun tab(panes: Int) = TabSummary(
        tabId = "t1",
        workspaceId = "w1",
        number = 1,
        label = "Build",
        focused = true,
        paneCount = panes,
    )
}
