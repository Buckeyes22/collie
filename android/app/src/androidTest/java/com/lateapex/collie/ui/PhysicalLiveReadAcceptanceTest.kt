package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only smoke. Requires -e liveOrigin and its already-paired installed connection. */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveReadAcceptanceTest {
    @Test
    fun configuredOriginRendersAtLeastOneLivePane() {
        val origin = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("liveOrigin instrumentation argument was not supplied", !origin.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as CollieApplication
        val connection = application.container.connectionStore.connection.value
        assumeTrue("A paired connection for liveOrigin is required", connection?.isPaired == true)
        val expected = com.lateapex.collie.network.OriginValidator().requireValid(origin!!)
        assumeTrue("The installed pairing must match liveOrigin", connection?.origin == expected)

        var paneCount = 0
        var error = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        assertTrue("live read failed: ${error.ifEmpty { "no panes rendered" }}", paneCount > 0)
    }
}
