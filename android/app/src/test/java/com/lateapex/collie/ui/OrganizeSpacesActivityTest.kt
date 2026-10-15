package com.lateapex.collie.ui

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.ControlCapabilityResponse
import com.lateapex.collie.network.ControlOperation
import com.lateapex.collie.network.ControlOperationResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.WorkspaceSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class OrganizeSpacesActivityTest {
    @Test
    fun organizerIsReachableAsAnInertScopedScreenWithoutConnectionAuthority() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            OrganizeSpacesActivity::class.java,
            OrganizeSpacesActivity.intent(context, Scope()),
        ).create().get()

        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val scroll = content.getChildAt(0) as ScrollView
        val root = scroll.getChildAt(0) as LinearLayout
        val title = root.getChildAt(0) as TextView
        assertEquals(activity.getString(R.string.organization_title), title.text)
        assertEquals(activity.getString(R.string.organization_title), title.contentDescription)
        val controls = root.getChildAt(2) as LinearLayout
        assertTrue(controls.getChildAt(0).minimumHeight >= activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target))
    }

    @Test
    fun viewAllScopeRequiresExplicitSessionSelectionAndDoesNotLoadDefaultWorkspace() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            OrganizeSpacesActivity::class.java,
            OrganizeSpacesActivity.intent(context, Scope(viewAll = true)),
        ).create().get()

        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val root = (content.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
        val notice = root.getChildAt(1) as TextView
        assertEquals(activity.getString(R.string.organization_select_session), notice.text)
        assertEquals(0, (root.getChildAt(3) as LinearLayout).childCount)
    }

    @Test
    fun recreatedUnknownActionAllowsOnlyReadOnlyOutcomeChecksAndRetainsUnknown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as CollieApplication
        val original = app.container.repository
        try {
            for (state in listOf("succeeded", "refused", "unknown")) {
                var reads = 0
                var writes = 0
                val initialConnection = Connection(CollieOrigin("https://organization.fixture/"), "fixture", "token")
                val store = object : ConnectionStore {
                    override val connection: StateFlow<Connection?> = MutableStateFlow(initialConnection)
                    override fun save(connection: Connection) = Unit
                    override fun clear() = Unit
                }
                val api = object : CollieApi by app.container.api {
                    override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                        BridgeConfigResponse(
                            mux = MuxConfigResponse(capabilities = mapOf("workspaceMove" to true)),
                            control = ControlCapabilityResponse(mapOf("controlOperations" to true)),
                        ), 200,
                    )
                    override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
                        SnapshotResponse("connected", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = System.currentTimeMillis()), 200,
                    )
                    override suspend fun controlOperation(connection: Connection, scope: Scope, requestId: String): ApiResult<ControlOperationResponse> {
                        reads++
                        val now = System.currentTimeMillis()
                        return ApiResult.Success(ControlOperationResponse(true, ControlOperation(1, requestId, state, "workspaceMove", "a".repeat(64), now, now)), 200)
                    }
                    override suspend fun applyOrganization(connection: Connection, scope: Scope, requestId: String, planToken: String, action: com.lateapex.collie.network.OrganizationAction): ApiResult<com.lateapex.collie.network.OrganizationOperationResponse> {
                        writes++
                        error("outcome reconciliation must never replay a mutation")
                    }
                }
                val repository = CollieRepository(api, store, app.container.originValidator)
                app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
                val requestId = "b13c0000-0000-4000-8000-000000000001"
                val saved = Bundle().apply { putString("organization_uncertain_request", requestId) }
                val activity = Robolectric.buildActivity(
                    OrganizeSpacesActivity::class.java,
                    OrganizeSpacesActivity.intent(context, Scope()),
                ).create(saved).start().resume().get()
                shadowOf(activity.mainLooper).idle()
                val check = findButton(activity.findViewById(android.R.id.content), activity.getString(R.string.organization_check_outcome))
                assertTrue("read-only outcome control should be reachable after recreation", check.isEnabled)
                check.performClick()
                shadowOf(activity.mainLooper).idle()
                assertEquals(1, reads)
                assertEquals(0, writes)
                val text = collectText(activity.findViewById(android.R.id.content))
                when (state) {
                    "succeeded" -> assertTrue(text.contains(activity.getString(R.string.organization_succeeded)))
                    "refused" -> assertTrue(text.contains(activity.getString(R.string.organization_refused)))
                    else -> {
                        assertTrue(text.contains(activity.getString(R.string.organization_unknown_notice)))
                        assertTrue(findButton(activity.findViewById(android.R.id.content), activity.getString(R.string.organization_check_outcome)).isEnabled)
                    }
                }
                activity.finish()
                shadowOf(activity.mainLooper).idle()
            }
        } finally {
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, original)
        }
    }

    @Test
    fun transferEntryOpensWholeTabProgressForExactVisibleTabAndOwner() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as CollieApplication
        val original = app.container.repository
        val scope = Scope(host = "host-a", session = "session-a")
        val pane = PaneSummary("pane-a", "space-a", "Space A", 1, "tab-a", "shell", AgentStatus.UNKNOWN, "/tmp", false, host = "host-a", session = "session-a", paneLabel = "Terminal A")
        val state = SnapshotResponse(
            "connected", agents = emptyList(), shellPanes = listOf(pane),
            workspaces = listOf(WorkspaceSummary("space-a", 1, "Space A", true, "tab-a", 1, 1, host = "host-a")),
            tabs = listOf(TabSummary("tab-a", "space-a", 1, "Tab A", true, 1, host = "host-a")),
            ts = System.currentTimeMillis(),
        )
        try {
            val initialConnection = Connection(CollieOrigin("https://organization.fixture/"), "fixture", "token")
            val store = object : ConnectionStore {
                override val connection: StateFlow<Connection?> = MutableStateFlow(initialConnection)
                override fun save(connection: Connection) = Unit
                override fun clear() = Unit
            }
            val api = object : CollieApi by app.container.api {
                override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(
                    BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("paneMove" to true, "paneLayout" to true, "tabTransfer" to true)), control = ControlCapabilityResponse(mapOf("controlOperations" to true))), 200,
                )
                override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(state, 200)
            }
            val repository = CollieRepository(api, store, app.container.originValidator)
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
            val activity = Robolectric.buildActivity(OrganizeSpacesActivity::class.java, OrganizeSpacesActivity.intent(context, scope)).create().start().resume().get()
            shadowOf(activity.mainLooper).idle()
            findButton(activity.findViewById(android.R.id.content), activity.getString(R.string.organization_open_tabs)).performClick()
            shadowOf(activity.mainLooper).idle()
            findButton(activity.findViewById(android.R.id.content), activity.getString(R.string.tab_transfer_open)).performClick()
            shadowOf(activity.mainLooper).idle()
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(TabTransferActivity::class.java.name, started.component?.className)
            assertEquals("tab-a", started.getStringExtra("tab-transfer-tab"))
            assertEquals("space-a", started.getStringExtra("tab-transfer-workspace"))
            assertEquals("host-a", started.getStringExtra("tab-transfer-host"))
            assertEquals("session-a", started.getStringExtra("tab-transfer-session"))
            activity.finish()
            shadowOf(activity.mainLooper).idle()
        } finally {
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, original)
        }
    }

    private fun findButton(view: android.view.View, label: String): Button {
        if (view is Button && view.text.toString() == label) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            runCatching { findButton(view.getChildAt(index), label) }.getOrNull()?.let { return it }
        }
        error("Button not found: $label")
    }

    private fun collectText(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { collectText(view.getChildAt(it)) }
        else -> ""
    }
}
