package com.lateapex.collie.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.Launcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class LauncherConfirmationTest {
    @Test
    fun noPromptsStartNamesTheCommandAndCancellationRefusesIt() = runBlocking {
        val activity = activity()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            confirmLauncher(activity, Launcher("codex --yolo", "Codex", "/repo", noPrompts = true), "workstation")
        }
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertTrue(children(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.contains("codex --yolo") && it.text.contains("workstation") && it.text.contains("/repo")
        })
        dialog.dismiss()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(result.await())
    }

    @Test
    fun noPromptsStartRequiresTheNamedButtonAndNormalLaunchersProceed() = runBlocking {
        val activity = activity()
        assertTrue(confirmLauncher(activity, Launcher("codex", "Codex"), "workstation"))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            confirmLauncher(activity, Launcher("codex --yolo", "Codex", noPrompts = true), "workstation")
        }
        val dialog = ShadowDialog.getLatestDialog()
        children(dialog.window!!.decorView).filterIsInstance<MaterialButton>().single {
            it.text == activity.getString(R.string.launch_no_prompts_start)
        }.performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(result.await())
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.Theme_Collie)
    }

    private fun children(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(children(view.getChildAt(index)))
    }
}
