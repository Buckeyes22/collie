package com.lateapex.collie.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Platform accessibility service interaction; it does not synthesize or assert spoken output. */
@RunWith(AndroidJUnit4::class)
class TalkBackDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Test
    fun connectedTalkBackFocusesAndActivatesSetupSettingsGear() {
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val automationInfo = automation.serviceInfo
        val previousFlags = automationInfo.flags
        automationInfo.flags = previousFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = automationInfo
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val spokenServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        assertTrue(
            "TalkBack must be enabled and connected for this device test",
            spokenServices.any { it.resolveInfo.serviceInfo.packageName == TALKBACK_PACKAGE },
        )
        assertTrue("TalkBack touch exploration must be active", manager.isTouchExplorationEnabled)

        val connectionStore = app.container.connectionStore
        val previousConnection = connectionStore.connection.value
        try {
            // Exercise the app's offline setup surface without contacting a server.
            connectionStore.clear()
            ActivityScenario.launch(MainActivity::class.java).use {
                val settingsId = app.resources.getResourceName(R.id.setup_settings_button)
                val expectedLabel = app.getString(R.string.connection_settings)
                val gear = awaitNode(automation, settingsId)
                assertNotNull("setup Settings control should appear in the accessibility tree", gear)
                requireNotNull(gear).let { node ->
                    try {
                        assertEquals("settings gear must expose its complete label", expectedLabel, node.contentDescription)
                        assertTrue(
                            "TalkBack must place accessibility focus on the Settings gear",
                            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
                        )
                    } finally {
                        node.recycle()
                    }
                }

                val focused = awaitFocus(automation)
                assertNotNull("the focused accessibility node should be available", focused)
                requireNotNull(focused).let { node ->
                    try {
                        assertEquals(settingsId, node.viewIdResourceName)
                        assertEquals(expectedLabel, node.contentDescription)
                        assertTrue(node.isAccessibilityFocused)
                        assertTrue(
                            "TalkBack must activate Settings through the platform node action",
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        )
                    } finally {
                        node.recycle()
                    }
                }

                val settingsHeaderId = app.resources.getResourceName(R.id.settings_header_title)
                val settingsHeader = awaitNode(automation, settingsHeaderId)
                assertNotNull("platform activation should navigate to SettingsActivity", settingsHeader)
                assertTrue(
                    "Settings screen header should be visible to accessibility",
                    requireNotNull(settingsHeader).isVisibleToUser,
                )
                requireNotNull(settingsHeader).recycle()
            }
        } finally {
            connectionStore.clear()
            previousConnection?.let(connectionStore::save)
            automationInfo.flags = previousFlags
            automation.serviceInfo = automationInfo
        }
    }

    private fun awaitNode(automation: UiAutomation, viewId: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            root?.recycle()
            if (node != null) return node
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private fun awaitFocus(automation: UiAutomation): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            root?.recycle()
            if (focused != null) return focused
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private companion object {
        const val TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
