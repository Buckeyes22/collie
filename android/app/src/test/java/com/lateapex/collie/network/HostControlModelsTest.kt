package com.lateapex.collie.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostControlModelsTest {
    @Test
    fun operationIntentsBuildOnlyDeclaredTargetsAndExactRoutes() {
        val install = HostOperationIntent(HostOperationKind.INTEGRATION, IntegrationAction.INSTALL, "antigravity_cli")
        assertEquals(listOf("api", "host", "integrations", "antigravity_cli", "install", "plan"), install.path(plan = true))
        assertEquals("integration:antigravity_cli", install.targetIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            HostOperationIntent(HostOperationKind.INTEGRATION, IntegrationAction.INSTALL, "../../tmp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostOperationIntent(HostOperationKind.LETTA, IntegrationAction.INSTALL, "letta")
        }
    }

    @Test
    fun integrationInventoryRejectsUnknownOrDuplicateMutationTargets() {
        val current = HostIntegration("codex", "Codex", "codex", true, "current", "2", "2", "available")
        assertTrue(HostIntegrationsResponse(true, listOf(current), "authoritative", "available").valid())
        assertTrue(HostIntegrationsResponse(true, listOf(current.copy(state = "needs_repair")), "authoritative", "available").valid())
        assertFalse(HostIntegrationsResponse(true, listOf(current.copy(target = "letta")), "authoritative").valid())
        assertFalse(HostIntegrationsResponse(true, listOf(current, current), "authoritative").valid())
        assertFalse(current.copy(command = "/home/private/.config/token").valid())
    }

    @Test
    fun manifestPresentationRetainsMissingStaleOverrideAndFailedRefreshEvidence() {
        val missing = HostManifestPresentation.display(AgentManifestSnapshot(emptyList()))
        assertNull(missing.lastCheckUnix)
        assertNull(missing.lastResult)
        assertTrue(missing.manifests.isEmpty())

        val staleFailedFixture = AgentManifestSnapshot(
            manifests = listOf(AgentManifest(
                agent = "codex",
                source = "https://example.invalid/manifests/codex.json",
                sourceKind = "remote",
                activeVersion = null,
                cachedRemoteVersion = "2.1",
                localOverrideShadowingRemote = true,
                remoteLastCheckedUnix = "1",
                remoteUpdateResult = "failed",
                remoteUpdateError = "remote source unavailable",
                warning = "Using a local override",
            )),
            lastCheckUnix = "1",
            lastResult = "failed",
        )
        val shown = HostManifestPresentation.display(staleFailedFixture)
        assertEquals("1", shown.lastCheckUnix)
        assertEquals("failed", shown.lastResult)
        assertEquals(1, shown.manifests.size)
        assertEquals("https://example.invalid/manifests/codex.json", shown.manifests.single().source)
        assertEquals("remote", shown.manifests.single().sourceKind)
        assertNull(shown.manifests.single().activeVersion)
        assertEquals("2.1", shown.manifests.single().cachedRemoteVersion)
        assertTrue(shown.manifests.single().localOverrideShadowingRemote)
        assertEquals("1", shown.manifests.single().remoteLastCheckedUnix)
        assertEquals("failed", shown.manifests.single().remoteUpdateResult)
        assertEquals("remote source unavailable", shown.manifests.single().remoteUpdateError)
        assertEquals("Using a local override", shown.manifests.single().warning)
    }

    @Test
    fun manifestAndPlanModelsBoundTextAndEffects() {
        val manifest = AgentManifest("codex", "bundled", "built-in", activeVersion = "3", localOverrideShadowingRemote = false)
        assertTrue(AgentManifestSnapshot(listOf(manifest)).valid())
        assertFalse(AgentManifestSnapshot(listOf(manifest.copy(warning = "bad\u0000value"))).valid())
        val plan = HostOperationPlan("plan_123", System.currentTimeMillis() + 10_000, "Remove Codex hooks", "integration:codex")
        assertTrue(plan.valid())
        assertFalse(plan.copy(targetIdentity = "integration:../../tmp").valid())
    }

    @Test
    fun operationReceiptKeepsUnknownAsAValidNonRetryableOutcome() {
        val receipt = HostOperationReceipt(1, "request-1", "unknown", "integrationInstall", "integration:codex", 10, 11, "operation.outcome_unknown")
        assertTrue(HostOperationResponse(false, receipt).validFor("request-1", "integrationInstall", "integration:codex"))
        assertFalse(HostOperationResponse(false, receipt).validFor("request-1", "integrationUninstall", "integration:codex"))
        assertFalse(HostOperationResponse(true, receipt).validFor("request-1", "integrationInstall", "integration:codex"))
    }
}
