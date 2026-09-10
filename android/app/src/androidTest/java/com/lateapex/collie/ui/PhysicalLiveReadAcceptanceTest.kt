package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device smoke. It performs reads only and is skipped without -e liveOrigin. */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveReadAcceptanceTest {
    @Test
    fun configuredOriginRendersAtLeastOneLivePane() {
        val origin = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("liveOrigin instrumentation argument was not supplied", !origin.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as CollieApplication
        application.container.connectionStore.clear()

        var paneCount = 0
        var error = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(R.id.origin_input).setText(origin)
                activity.findViewById<TextInputEditText>(R.id.device_label_input)
                    .setText("Pixel physical read smoke")
                activity.findViewById<View>(R.id.connect_button).performClick()
            }

            val deadline = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadline && paneCount == 0 && error.isEmpty()) {
                Thread.sleep(250)
                scenario.onActivity { activity ->
                    val dashboard = activity.findViewById<View>(R.id.dashboard_panel)
                    if (dashboard.visibility == View.VISIBLE) {
                        paneCount = activity.findViewById<RecyclerView>(R.id.pane_list)
                            .adapter?.itemCount ?: 0
                    }
                    error = activity.findViewById<android.widget.TextView>(R.id.error_text)
                        .text?.toString().orEmpty()
                }
            }
        }
        application.container.connectionStore.clear()

        assertTrue("live read failed: ${error.ifEmpty { "no panes rendered" }}", paneCount > 0)
    }
}
