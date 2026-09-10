package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarInsetsDeviceTest {
    @Test
    fun launcherContentIsPaddedInsidePhysicalSystemBars() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertSafeContentPadding(content.getChildAt(0))
            }
        }
    }

    @Test
    fun paneContentIsPaddedInsidePhysicalSystemBars() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, "inset-test-pane")
            putExtra(PaneActivity.EXTRA_TITLE, "Inset test")
            // This isolated fixture has no matching bridge snapshot. Mark it as the same optimistic
            // bootstrap used by a newly-created pane so closed-pane recovery cannot retire it while
            // this test measures the window insets.
            putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        }
        ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertSafeContentPadding(content.getChildAt(0))
            }
        }
    }

    private fun assertSafeContentPadding(root: View) {
        val windowInsets = ViewCompat.getRootWindowInsets(root)
        assertNotNull(windowInsets)
        val safeDrawing = requireNotNull(windowInsets).getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )

        assertEquals(safeDrawing.left, root.paddingLeft)
        assertEquals(safeDrawing.top, root.paddingTop)
        assertEquals(safeDrawing.right, root.paddingRight)
        assertEquals(safeDrawing.bottom, root.paddingBottom)
    }
}
