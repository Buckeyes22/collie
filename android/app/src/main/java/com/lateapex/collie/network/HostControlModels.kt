package com.lateapex.collie.network

import kotlinx.serialization.Serializable

enum class IntegrationAction { INSTALL, UNINSTALL }
enum class HostOperationKind { INTEGRATION, LETTA, MANIFEST_RELOAD, MANIFEST_REFRESH }

data class HostOperationIntent(val kind: HostOperationKind, val action: IntegrationAction? = null, val target: String? = null) {
    init {
        when (kind) {
            HostOperationKind.INTEGRATION -> require(action != null && target in INTEGRATION_TARGETS)
            HostOperationKind.LETTA -> require(action != null && target == null)
            HostOperationKind.MANIFEST_RELOAD, HostOperationKind.MANIFEST_REFRESH -> require(action == null && target == null)
        }
    }
    val targetIdentity: String get() = when (kind) {
        HostOperationKind.INTEGRATION -> "integration:$target"
        HostOperationKind.LETTA -> "integration:letta"
        HostOperationKind.MANIFEST_RELOAD, HostOperationKind.MANIFEST_REFRESH -> "agent-manifests"
    }
    val capability: String get() = when (kind) {
        HostOperationKind.INTEGRATION -> if (action == IntegrationAction.INSTALL) "integrationInstall" else "integrationUninstall"
        HostOperationKind.LETTA -> "experimentalLettaIntegration"
        HostOperationKind.MANIFEST_RELOAD -> "manifestReload"
        HostOperationKind.MANIFEST_REFRESH -> "manifestRefresh"
    }
    fun path(plan: Boolean): List<String> {
        val suffix = if (plan) listOf("plan") else emptyList()
        return when (kind) {
            HostOperationKind.INTEGRATION -> listOf("api", "host", "integrations", requireNotNull(target), action!!.name.lowercase()) + suffix
            HostOperationKind.LETTA -> listOf("api", "host", "integrations", "letta", action!!.name.lowercase()) + suffix
            HostOperationKind.MANIFEST_RELOAD -> listOf("api", "host", "agent-manifests", "reload") + suffix
            HostOperationKind.MANIFEST_REFRESH -> listOf("api", "host", "agent-manifests", "refresh") + suffix
        }
    }
}

val INTEGRATION_TARGETS = setOf(
    "pi", "omp", "claude", "codex", "copilot", "devin", "droid", "kimi", "opencode", "kilo",
    "hermes", "qodercli", "qwen", "cursor", "mastracode", "antigravity_cli", "grok",
)

@Serializable
data class HostIntegration(
    val target: String,
    val label: String,
    /** Display-safe executable basename only; host configuration paths never reach the UI. */
    val command: String,
    val available: Boolean,
    val state: String,
    val installedVersion: String? = null,
    val requiredVersion: String? = null,
    val versionStatus: String = "unavailable",
) {
    fun valid(): Boolean = target in INTEGRATION_TARGETS && label.length <= 256 && command.length <= 256 &&
        command.none(Char::isISOControl) && '/' !in command && '\\' !in command &&
        state in setOf("not_installed", "current", "outdated", "needs_repair") && versionStatus in setOf("available", "partial", "unavailable") &&
        listOfNotNull(installedVersion, requiredVersion).all { it.length <= 128 && it.none(Char::isISOControl) }
}

@Serializable
data class HostIntegrationsResponse(val ok: Boolean, val integrations: List<HostIntegration>, val status: String, val versionStatus: String = "unavailable") {
    fun valid(): Boolean = ok && status == "authoritative" && integrations.size <= INTEGRATION_TARGETS.size &&
        versionStatus in setOf("available", "partial", "unavailable") && integrations.all(HostIntegration::valid) && integrations.map(HostIntegration::target).toSet().size == integrations.size
}

@Serializable
data class AgentManifest(
    val agent: String,
    val source: String,
    val sourceKind: String,
    val activeVersion: String? = null,
    val cachedRemoteVersion: String? = null,
    val localOverrideShadowingRemote: Boolean,
    val remoteLastCheckedUnix: String? = null,
    val remoteUpdateResult: String? = null,
    val remoteUpdateError: String? = null,
    val warning: String? = null,
) {
    fun valid(): Boolean = agent.length in 1..256 && source.length <= 2048 && sourceKind.length <= 128 &&
        listOfNotNull(activeVersion, cachedRemoteVersion, remoteUpdateResult, remoteUpdateError, warning).all { it.length <= 2048 } &&
        listOfNotNull(source, activeVersion, cachedRemoteVersion, remoteLastCheckedUnix, remoteUpdateResult, remoteUpdateError, warning).all { it.none(Char::isISOControl) }
}

@Serializable
data class AgentManifestStatusResponse(val ok: Boolean, val status: AgentManifestSnapshot)

@Serializable
data class AgentManifestSnapshot(
    val manifests: List<AgentManifest>,
    val lastCheckUnix: String? = null,
    val lastResult: String? = null,
) {
    fun valid(): Boolean = manifests.size <= 256 && manifests.all(AgentManifest::valid) &&
        manifests.map(AgentManifest::agent).toSet().size == manifests.size &&
        listOfNotNull(lastCheckUnix, lastResult).all { it.length <= 2048 && it.none(Char::isISOControl) }
}

data class HostManifestRowDisplay(
    val agent: String,
    val activeVersion: String?,
    val cachedRemoteVersion: String?,
    val source: String,
    val sourceKind: String,
    val localOverrideShadowingRemote: Boolean,
    val remoteLastCheckedUnix: String?,
    val remoteUpdateResult: String?,
    val remoteUpdateError: String?,
    val warning: String?,
)

data class HostManifestDisplay(
    val lastCheckUnix: String?,
    val lastResult: String?,
    val manifests: List<HostManifestRowDisplay>,
)

object HostManifestPresentation {
    fun display(snapshot: AgentManifestSnapshot): HostManifestDisplay = HostManifestDisplay(
        lastCheckUnix = snapshot.lastCheckUnix,
        lastResult = snapshot.lastResult,
        manifests = snapshot.manifests.map { row ->
            HostManifestRowDisplay(
                agent = row.agent,
                activeVersion = row.activeVersion,
                cachedRemoteVersion = row.cachedRemoteVersion,
                source = row.source,
                sourceKind = row.sourceKind,
                localOverrideShadowingRemote = row.localOverrideShadowingRemote,
                remoteLastCheckedUnix = row.remoteLastCheckedUnix,
                remoteUpdateResult = row.remoteUpdateResult,
                remoteUpdateError = row.remoteUpdateError,
                warning = row.warning,
            )
        },
    )
}

@Serializable
data class HostOperationPlanResponse(val ok: Boolean, val plan: HostOperationPlan? = null, val code: String? = null, val error: String? = null) {
    fun valid(): Boolean = if (!ok) plan == null && !code.isNullOrBlank() else plan?.valid() == true && code == null
}

@Serializable
data class HostOperationPlan(val token: String, val expiresAt: Long, val effect: String, val targetIdentity: String) {
    fun valid(): Boolean = token.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
        expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) &&
        effect.length in 1..512 && effect.none(Char::isISOControl) && targetIdentity in VALID_HOST_TARGETS
}
private val VALID_HOST_TARGETS = INTEGRATION_TARGETS.map { "integration:$it" }.toSet() + setOf("integration:letta", "agent-manifests")

@Serializable
data class HostOperationRequest(val requestId: String, val planToken: String, val confirmed: Boolean = true)

@Serializable
data class HostOperationReceipt(
    val schema: Int,
    val requestId: String,
    val state: String,
    val capability: String,
    val targetIdentity: String,
    val at: Long,
    val updatedAt: Long,
    val code: String? = null,
) {
    fun validFor(id: String, expectedCapability: String, expectedTarget: String): Boolean = schema == 1 && requestId == id &&
        state in setOf("pending", "succeeded", "refused", "unknown") && capability == expectedCapability &&
        targetIdentity == expectedTarget && at >= 0 && updatedAt >= at && updatedAt <= System.currentTimeMillis() + 60_000 &&
        (code == null || code.length <= 128 && code.none(Char::isISOControl))
}
@Serializable
data class HostOperationResponse(val ok: Boolean, val operation: HostOperationReceipt) {
    fun validFor(id: String, expectedCapability: String, expectedTarget: String): Boolean = operation.validFor(id, expectedCapability, expectedTarget) &&
        (ok == (operation.state == "succeeded")) && (operation.state != "refused" || !operation.code.isNullOrBlank())
}
