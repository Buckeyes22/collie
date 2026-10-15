package com.lateapex.collie.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentCapabilitiesResponse
import com.lateapex.collie.network.AgentCapabilityRow
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.MuxAgentCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AgentCatalogActivityTest {
    @Test
    fun rendersVersionAndExactCurrentOwnerObservationCounts() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(
                Connection(CollieOrigin("https://agent-catalog.fixture/"), "fixture", "paired"),
            )
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val api = object : CollieApi by app.container.api {
            override suspend fun agentCapabilities(connection: Connection, scope: Scope) = ApiResult.Success(
                AgentCapabilitiesResponse(
                    schema = 1,
                    mux = "fixture-owner",
                    muxCapabilities = MuxAgentCapabilities(agentDetection = true, agentSessionRef = true),
                    rows = listOf(
                        AgentCapabilityRow(
                            kind = "claude",
                            label = "Claude Code",
                            executableFound = true,
                            version = "2.1.296",
                            launch = "implemented",
                            detection = "implemented",
                            sessionReference = "implemented",
                            observedPanes = 3,
                            observedSessionReferences = 2,
                        ),
                    ),
                    note = "Current owner snapshot observations",
                ),
                200,
            )
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(
            AgentCatalogActivity::class.java,
            AgentCatalogActivity.intent(app, Scope(host = "peer", session = "project")),
        ).create().start().resume()
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            val windowContent = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = windowContent.getChildAt(0) as ScrollView
            val content = root.getChildAt(0) as ViewGroup
            val text = descendants(content).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            assertTrue(text.contains("2.1.296"))
            assertTrue(text.contains("Current owner snapshot: 3 matching panes · 2 with session references"))
            assertEquals(1, (0 until content.childCount).count { content.getChildAt(it) is ViewGroup })
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
}
