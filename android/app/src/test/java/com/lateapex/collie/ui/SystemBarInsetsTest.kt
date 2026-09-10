package com.lateapex.collie.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemBarInsetsTest {
    @Test
    fun appliesLargestSafeInsetsWithoutAccumulatingRepeatedDispatches() {
        val activity = Robolectric.buildActivity(InsetTestActivity::class.java).setup().get()
        val root = View(activity).apply {
            setPadding(1, 2, 3, 4)
        }
        activity.window.prepareEdgeToEdgeContent()
        activity.setContentView(root)
        activity.window.applySafeContentInsets(root)

        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(5, 10, 7, 12),
                )
                .setInsets(
                    WindowInsetsCompat.Type.displayCutout(),
                    Insets.of(8, 14, 9, 0),
                )
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 100))
                .build(),
        )

        assertPadding(root, left = 9, top = 16, right = 12, bottom = 104)

        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(1, 2, 3, 4))
                .build(),
        )

        assertPadding(root, left = 2, top = 4, right = 6, bottom = 8)
    }

    private fun assertPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(left, view.paddingLeft)
        assertEquals(top, view.paddingTop)
        assertEquals(right, view.paddingRight)
        assertEquals(bottom, view.paddingBottom)
    }

    class InsetTestActivity : android.app.Activity()
}
