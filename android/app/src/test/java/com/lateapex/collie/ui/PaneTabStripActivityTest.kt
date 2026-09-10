package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class PaneTabStripActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun paneChromeEnumeratesTabsAndPanesMarksCurrentAndPersistsTheFold() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent()).create().get()
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            panes = listOf(
                pane("p1", "t1"),
                pane("p2", "t2"),
                pane("p3", "t2"),
            ),
            tabs = listOf(tab("t2", 2), tab("t1", 1)),
            mux = MuxConfigResponse(capabilities = mapOf("createTab" to true)),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }

        val tabs = activity.findViewById<LinearLayout>(R.id.tab_items)
        val panes = activity.findViewById<LinearLayout>(R.id.pane_items)
        assertEquals(2, tabs.childCount)
        assertEquals(2, panes.childCount)
        assertFalse(tabs.getChildAt(0).isSelected)
        assertTrue(tabs.getChildAt(1).isSelected)
        assertTrue(panes.getChildAt(0).isSelected)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.new_tab_button).visibility)

        activity.findViewById<View>(R.id.hide_tabs_button).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tab_strip).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.show_tabs_button).visibility)
        assertFalse(NativePreferences(context).paneStripsVisible)

        activity.findViewById<View>(R.id.show_tabs_button).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tab_strip).visibility)
        assertTrue(NativePreferences(context).paneStripsVisible)
    }

    @Test
    fun longPressAndActiveRetapOpenActionsWithoutBreakingInactiveNavigation() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent()).create().get()
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            panes = listOf(pane("p1", "t1"), pane("p2", "t2"), pane("p3", "t2")),
            tabs = listOf(tab("t1", 1), tab("t2", 2)),
            mux = MuxConfigResponse(
                capabilities = mapOf(
                    "renameTab" to true,
                    "closeTab" to true,
                    "renamePane" to true,
                    "closePane" to true,
                ),
            ),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }
        val tabs = activity.findViewById<LinearLayout>(R.id.tab_items)
        val panes = activity.findViewById<LinearLayout>(R.id.pane_items)

        tabs.getChildAt(1).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is BottomSheetDialog)
        ShadowDialog.getLatestDialog().dismiss()

        assertTrue(tabs.getChildAt(0).performLongClick())
        assertTrue(ShadowDialog.getLatestDialog() is BottomSheetDialog)
        ShadowDialog.getLatestDialog().dismiss()

        panes.getChildAt(0).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is BottomSheetDialog)
        ShadowDialog.getLatestDialog().dismiss()

        assertTrue(panes.getChildAt(1).performLongClick())
        assertTrue(ShadowDialog.getLatestDialog() is BottomSheetDialog)
    }

    @Test
    fun tabStripRenameAndCloseStayInOneSheetWithBlastRadiusConfirmation() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent()).create().get()
        renderActionsState(activity)
        val tabs = activity.findViewById<LinearLayout>(R.id.tab_items)

        tabs.getChildAt(1).performClick()
        val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        button(dialog, activity.getString(R.string.tab_action_rename)).performClick()

        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertEquals("t2", descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
            .filterIsInstance<EditText>().single().text.toString())

        button(dialog, activity.getString(R.string.pane_action_cancel)).performClick()
        val close = button(dialog, activity.getString(R.string.tab_action_close))
        close.performClick()

        assertTrue(dialog.isShowing)
        assertEquals("Tap again to close 2 panes", close.text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun paneStripRenameAndCloseStayInOneSheetWithPaneConfirmation() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent()).create().get()
        renderActionsState(activity)
        val panes = activity.findViewById<LinearLayout>(R.id.pane_items)

        panes.getChildAt(0).performClick()
        val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        button(dialog, activity.getString(R.string.pane_action_rename)).performClick()

        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
            .filterIsInstance<EditText>().any())

        button(dialog, activity.getString(R.string.pane_action_cancel)).performClick()
        val close = button(dialog, activity.getString(R.string.pane_action_close))
        close.performClick()

        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.pane_close_again), close.text.toString())
        assertFalse(activity.isFinishing)
    }

    private fun renderActionsState(activity: PaneActivity) {
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            panes = listOf(pane("p1", "t1"), pane("p2", "t2"), pane("p3", "t2")),
            tabs = listOf(tab("t1", 1), tab("t2", 2, panes = 2)),
            mux = MuxConfigResponse(
                capabilities = mapOf(
                    "renameTab" to true,
                    "closeTab" to true,
                    "renamePane" to true,
                    "closePane" to true,
                ),
            ),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }
    }

    private fun button(dialog: CollieBottomSheetDialog, label: String): MaterialButton =
        descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == label }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun paneIntent() = Intent(context, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, "p2")
        .putExtra(PaneActivity.EXTRA_HOST, "desk")
        .putExtra(PaneActivity.EXTRA_SESSION, "work")
        .putExtra(PaneActivity.EXTRA_AGENT, "codex")

    private fun tab(id: String, number: Int, panes: Int = 1) =
        TabSummary(id, "w1", number, id, false, panes, "desk")

    private fun pane(id: String, tab: String) = PaneSummary(
        paneId = id,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = tab,
        agent = "codex",
        status = AgentStatus.IDLE,
        cwd = "/home/op/project",
        focused = id == "p2",
        tabLabel = tab,
        host = "desk",
        session = "work",
    )
}
