package com.lateapex.collie.ui

import android.content.Intent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.network.PreflightReport
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdateRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsRoutesTest {
    private val application = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Before
    fun disconnect() {
        application.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, 0).edit().clear().commit()
        application.container.connectionStore.clear()
    }

    @Test
    fun settingsLinksToDedicatedUpdateAndPackSurfaces() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        settings.findViewById<android.view.View>(R.id.settings_updates_button).performClick()
        assertEquals(UpdatesActivity::class.java.name, shadowOf(settings).nextStartedActivity.component?.className)
        settings.findViewById<android.view.View>(R.id.settings_pack_button).performClick()
        assertEquals(PackActivity::class.java.name, shadowOf(settings).nextStartedActivity.component?.className)
    }

    @Test
    fun settingsHeaderReturnsClearTopToDashboard() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create().get()

        settings.findViewById<android.view.View>(R.id.settings_back_button).performClick()

        val intent = shadowOf(settings).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(settings.isFinishing)
    }

    @Test
    fun androidSystemBackRetainsNormalActivityStackBehavior() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()

        settings.onBackPressedDispatcher.onBackPressed()

        assertTrue(settings.isFinishing)
        assertNull(shadowOf(settings).nextStartedActivity)
    }

    @Test
    fun updateAndPackSurfacesAreCapturableAndExposeTheirPrimaryContent() {
        val updates = Robolectric.buildActivity(UpdatesActivity::class.java).create().get()
        val pack = Robolectric.buildActivity(PackActivity::class.java).create().get()

        assertTrue(updates.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        assertTrue(pack.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        assertNotNull(updates.findViewById<android.view.View>(R.id.updates_checks))
        assertNotNull(updates.findViewById<android.view.View>(R.id.updates_start_button))
        assertFalse(updates.findViewById<android.view.View>(R.id.updates_start_button).isEnabled)
        assertNotNull(pack.findViewById<android.view.View>(R.id.pack_members))
        val updatesRoot = updates.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        assertTrue(updatesRoot.getChildAt(0) is CollieMaxWidthLinearLayout)
    }

    @Test
    fun updateHeaderAlwaysReturnsToSettingsEvenWhenOpenedOutsideSettings() {
        val updates = Robolectric.buildActivity(UpdatesActivity::class.java).create().get()

        updates.findViewById<android.view.View>(R.id.updates_back_button).performClick()

        assertEquals(
            SettingsActivity::class.java.name,
            shadowOf(updates).nextStartedActivity.component?.className,
        )
        assertTrue(updates.isFinishing)
    }

    @Test
    fun packHeaderReturnsHomeLikeTheWebRoute() {
        val pack = Robolectric.buildActivity(PackActivity::class.java).create().get()

        pack.findViewById<android.view.View>(R.id.pack_back_button).performClick()

        assertEquals(MainActivity::class.java.name, shadowOf(pack).nextStartedActivity.component?.className)
        assertTrue(pack.isFinishing)
    }

    @Test
    fun updateStartRequiresAUsablePreflightAndNoActiveRun() {
        val unchecked = updateState(preflight = null)
        val green = updateState(preflight = PreflightReport(1, "green", emptyList()))
        val red = updateState(preflight = PreflightReport(1, "red", emptyList()))
        val active = green.copy(run = run("restarting"))
        val idle = green.copy(run = run("idle"))

        assertTrue(!UpdateActionGate.canStart(unchecked, busy = false, writeAuthorized = true))
        assertTrue(UpdateActionGate.canStart(green, busy = false, writeAuthorized = true))
        assertTrue(!UpdateActionGate.canStart(green, busy = false, writeAuthorized = false))
        assertTrue(!UpdateActionGate.canStart(red, busy = false, writeAuthorized = true))
        assertTrue(!UpdateActionGate.canStart(active, busy = false, writeAuthorized = true))
        assertTrue(!UpdateActionGate.canStart(green, busy = true, writeAuthorized = true))
        assertTrue(UpdateActionGate.hasActiveRun(active))
        assertEquals("restarting", UpdateActionGate.freshestRun(run("restarting"), run("staging").copy(updatedAt = 0)).state)
        assertEquals("staging", UpdateActionGate.freshestRun(run("restarting"), run("staging").copy(updatedAt = 2)).state)
        assertEquals(null, UpdateActionGate.visibleRun(idle))
        assertEquals("1.6.0", UpdateActionGate.target(green))
    }

    @Test
    fun bridgeStatusComesFromTheSnapshotRatherThanEndpointReachability() {
        assertTrue(SettingsConnectionModel.bridgeConnected("connected"))
        assertFalse(SettingsConnectionModel.bridgeConnected("offline"))
        assertFalse(SettingsConnectionModel.bridgeConnected(null))
    }

    private fun updateState(preflight: PreflightReport?) = UpdateCheckResponse(
        current = "1.5.0",
        latest = "1.6.0",
        latestUrl = null,
        releaseAvailable = true,
        majorAvailable = null,
        majorUrl = null,
        bridgeStale = false,
        checkedAt = 1,
        preflight = preflight,
    )

    private fun run(state: String) = UpdateRun(
        schema = 1,
        state = state,
        from = "1.5.0",
        to = "1.6.0",
        startedAt = 1,
        updatedAt = 1,
        pid = 1,
        attempt = 1,
    )
}
