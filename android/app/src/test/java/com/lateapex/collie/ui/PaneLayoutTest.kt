package com.lateapex.collie.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ActivityPaneBinding
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaneLayoutTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val context = ContextThemeWrapper(application, R.style.Theme_Collie)
    private val binding = ActivityPaneBinding.inflate(LayoutInflater.from(context))

    @Test
    fun routeUsesTheWideCenteredColumn() {
        val column = binding.root.getChildAt(0)
        assertTrue(column is CollieWideMaxWidthLinearLayout)
        val gravity = (column.layoutParams as FrameLayout.LayoutParams).gravity
        assertEquals(Gravity.CENTER_HORIZONTAL, gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
    }

    @Test
    fun chromeKeepsTheSharedHeaderAndTouchFloors() {
        assertEquals(dp(60), binding.paneHeader.minimumHeight)
        assertEquals(dp(44), binding.backButton.layoutParams.height)
        assertEquals(dp(44), binding.refreshButton.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, binding.tabStrip.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, binding.tabScroll.layoutParams.height)
        assertEquals(dp(44), (binding.tabScroll.parent as View).layoutParams.height)
        assertEquals(dp(44), binding.paneScroll.layoutParams.height)
        assertEquals(dp(44), binding.showTabsButton.layoutParams.height)
        assertEquals(dp(44), binding.paneBufferAction.minimumHeight)
        assertEquals(dp(44), binding.newOutputButton.layoutParams.height)
        assertEquals(dp(44), binding.newOutputButton.minimumHeight)
        assertEquals(dp(14), binding.paneStatus.layoutParams.height)
        assertEquals(dp(44), binding.switcherHandle.layoutParams.height)
        assertEquals(dp(48), (binding.switcherHandle as ViewGroup).getChildAt(0).layoutParams.width)
        assertEquals(dp(6), (binding.switcherHandle as ViewGroup).getChildAt(0).layoutParams.height)
        assertEquals(dp(44), binding.modeRow.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, binding.replyInput.layoutParams.height)
        assertEquals(dp(44), binding.replyInput.minimumHeight)
        assertEquals(dp(44), binding.sendButton.layoutParams.height)
        assertEquals(dp(44), binding.attachImageButton.layoutParams.width)
        assertEquals(dp(44), binding.attachImageButton.layoutParams.height)
        assertEquals(dp(14), binding.attachImageButton.paddingStart)
        assertSame(binding.replyInput.parent, binding.attachImageButton.parent)
    }

    @Test
    fun localDisplayControlsRemainPresentBesideTheWriteControls() {
        assertTrue(binding.quickModeButton.isEnabled)
        assertTrue(binding.agentModeButton.isEnabled)
        assertTrue(binding.composerSettingsButton.isEnabled)
        assertSame(binding.composerDock, binding.keyRow.parent)
        assertSame(binding.composerDock, binding.quickActionsContainer.parent)
        assertSame(binding.composerDock, binding.displayPrefsContainer.parent)
    }

    @Test
    fun terminalWrapsInAVerticalScrollerAtTheWebScale() {
        assertSame(binding.terminalHorizontalScroll, binding.terminalText.parent)
        assertEquals(TerminalHorizontalScrollView::class.java, binding.terminalHorizontalScroll.javaClass)
        assertSame(binding.terminalHorizontalScroll.parent, binding.terminalBlockContent.parent)
        assertSame(binding.terminalContent, binding.terminalHorizontalScroll.parent)
        assertSame(binding.paneBufferAction.parent, binding.terminalContent.parent)
        assertSame(binding.terminalScroll, binding.terminalContent.parent.parent)
        assertEquals(ScrollView::class.java, binding.terminalScroll.javaClass)
        assertEquals(sp(10), binding.terminalText.textSize, 0.01f)
        assertEquals(1.25f, binding.terminalText.lineSpacingMultiplier, 0.01f)
    }

    @Test
    fun replyComposerGrowsFromOneToFiveLinesAndNewOutputStartsHidden() {
        assertEquals(1, binding.replyInput.minLines)
        assertEquals(5, binding.replyInput.maxLines)
        assertFalse(binding.replyInput.isSingleLine)
        assertEquals(View.GONE, binding.newOutputButton.visibility)
    }

    @Test
    fun mirrorUsesTheBundledMonoFace() {
        val intent = android.content.Intent(ApplicationProvider.getApplicationContext(), PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, "font:pane")
            .putExtra(PaneActivity.EXTRA_AGENT, "opencode")
        val activity = org.robolectric.Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val expected = androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.collie_mono)
        assertSame(expected, activity.findViewById<android.widget.TextView>(R.id.terminal_text).typeface)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun sp(value: Int): Float = value * context.resources.displayMetrics.scaledDensity
}
