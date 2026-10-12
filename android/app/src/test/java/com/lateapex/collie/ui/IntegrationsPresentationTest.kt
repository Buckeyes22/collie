package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.IntegrationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationsPresentationTest {
    @Test
    fun codexGuidanceExplainsFirstPromptAndHookTrustChoice() {
        val guidance = ApplicationProvider.getApplicationContext<Context>().getString(R.string.host_manifest_codex_guidance)
        assertTrue(guidance.contains("first prompt"))
        assertTrue(guidance.contains("/hooks"))
        assertTrue(guidance.contains("Continue without trusting"))
        assertTrue(guidance.contains("status says current"))
    }

    @Test
    fun activeButUntrustedIntegrationGetsDistinctRepairPresentation() {
        val repair = HostControlPresentation.integrationState("needs_repair")
        assertEquals(IntegrationAction.INSTALL, repair.action)
        assertEquals(R.string.host_integration_repair, repair.actionLabel)
        val label = ApplicationProvider.getApplicationContext<Context>().getString(repair.statusLabel)
        assertTrue(label.contains("untrusted"))
    }
}
