package com.lateapex.collie.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewActivityTest {
    @Test
    fun disconnectedScreenShowsScopedMachineChoiceAndKeepsLaunchDisabled() {
        val controller = Robolectric.buildActivity(
            NewActivity::class.java,
            NewActivity.intent(ApplicationProvider.getApplicationContext(), Scope(host = "build-machine", session = "review")),
        ).create().start().resume()
        try {
            val activity = controller.get()
            assertTrue(visibleText(activity.window.decorView).contains(activity.getString(R.string.new_machine_select, "build-machine")))
            assertTrue(visibleText(activity.window.decorView).contains(activity.getString(R.string.new_unavailable)))
            val start = findTextView(activity.window.decorView, activity.getString(R.string.new_start))
            assertFalse(start?.isEnabled ?: true)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun newScreenIntentCarriesMachineAndSessionWithoutWideningScope() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = NewActivity.intent(context, Scope(host = "build-machine", session = "review"))
        assertEquals("build-machine", intent.getStringExtra("host"))
        assertEquals("review", intent.getStringExtra("session"))
    }

    private fun visibleText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { visibleText(view.getChildAt(it)) }
        else -> ""
    }

    private fun findTextView(view: View, text: String): TextView? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == text }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTextView(view.getChildAt(it), text) }
        else -> null
    }
}
