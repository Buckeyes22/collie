package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.CreatedPane
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.domain.Scope
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FreshPaneLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val created = CreatedPane("w1:p2", "w1", "Project", "w1:t2", "/repo")

    @Test
    fun everyCreatedPaneLaunchMarksTheOptimisticFreshBootstrap() {
        val main = Robolectric.buildActivity(MainActivity::class.java).create().get()
        invokeOpenCreatedPane(main)
        assertTrue(shadowOf(main).nextStartedActivity.getBooleanExtra(PaneActivity.EXTRA_FRESH_PANE, false))

        val workspace = WorkspaceSummary("w1", 1, "Project", true, "w1:t1", 1, 1)
        val space = Robolectric.buildActivity(
            SpaceActivity::class.java,
            SpaceActivity.intent(context, workspace, null),
        ).create().get()
        invokeOpenCreatedPane(space)
        assertTrue(shadowOf(space).nextStartedActivity.getBooleanExtra(PaneActivity.EXTRA_FRESH_PANE, false))

        val pane = Robolectric.buildActivity(
            PaneActivity::class.java,
            android.content.Intent(context, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, "w1:p1"),
        ).create().get()
        invokeOpenCreatedPane(pane)
        assertTrue(shadowOf(pane).nextStartedActivity.getBooleanExtra(PaneActivity.EXTRA_FRESH_PANE, false))
    }

    private fun invokeOpenCreatedPane(activity: Any) {
        val parameterTypes = if (activity is MainActivity) {
            arrayOf(CreatedPane::class.java, Scope::class.java)
        } else {
            arrayOf(CreatedPane::class.java)
        }
        activity.javaClass.getDeclaredMethod("openCreatedPane", *parameterTypes).apply {
            isAccessible = true
            if (activity is MainActivity) invoke(activity, created, Scope()) else invoke(activity, created)
        }
    }
}
