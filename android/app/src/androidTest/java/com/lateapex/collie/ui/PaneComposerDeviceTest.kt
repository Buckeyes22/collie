package com.lateapex.collie.ui

import android.content.Intent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneComposerDeviceTest {
    @Test
    fun everyComposerDoorIsWiredAndDisplayOpensInFlow() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, "device-test")
            .putExtra(PaneActivity.EXTRA_AGENT, "opencode")

        ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                listOf(
                    R.id.keys_mode_button,
                    R.id.type_mode_button,
                    R.id.quick_mode_button,
                    R.id.agent_mode_button,
                    R.id.switcher_handle,
                ).forEach { id -> assertTrue(activity.findViewById<View>(id).hasOnClickListeners()) }

                val dock = activity.findViewById<View>(R.id.composer_dock)
                assertEquals(View.GONE, dock.visibility)
            }
        }
    }
}
