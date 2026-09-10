package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SpaceActivityTest {
    @Test
    fun spaceFooterNamesTheNativeBuild() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val holder = SpaceFooterAdapter().onCreateViewHolder(FrameLayout(context), 0)

        val stamp = holder.itemView.findViewById<TextView>(R.id.space_build_stamp)
        assertTrue(stamp.text.toString().startsWith("Android app build "))
    }

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

    private fun tab(panes: Int) = TabSummary(
        tabId = "t1",
        workspaceId = "w1",
        number = 1,
        label = "Build",
        focused = true,
        paneCount = panes,
    )
}
