@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

enum class IntegrationAction { INSTALL, UNINSTALL }
@Serializable
data class HostPluginSource(val kind: String, val owner: String? = null, val repo: String? = null, val subdir: String? = null, val requestedRef: String? = null, val resolvedCommit: String? = null) {
    fun valid(): Boolean = kind in setOf("local", "github") && listOfNotNull(owner, repo, subdir, requestedRef, resolvedCommit).all { it.length <= 512 && it.none(Char::isISOControl) }
}
@Serializable
data class HostPluginAction(val id: String, val qualifiedId: String, val pluginId: String, val actionId: String, val title: String, val description: String? = null, val contexts: List<String> = emptyList(), val platforms: List<String>? = null, val enabled: Boolean, val platformSupported: Boolean) {
    fun valid(): Boolean = id == qualifiedId && pluginId.length in 1..120 && actionId.length in 1..120 && title.length <= 256 &&
        listOfNotNull(description).all { it.length <= 2048 } && contexts.size <= 5 && contexts.all { it in setOf("global", "workspace", "tab", "pane", "selection") } &&
        (platforms == null || platforms.size <= 3 && platforms.all { it in setOf("linux", "macos", "windows") })
}
@Serializable
data class HostPluginPane(val id: String, val title: String, val description: String? = null, val platforms: List<String>? = null, val placement: String, val width: kotlinx.serialization.json.JsonElement? = null, val height: kotlinx.serialization.json.JsonElement? = null) {
    fun valid(): Boolean = id.length in 1..120 && title.length <= 256 && listOfNotNull(description).all { it.length <= 2048 } && placement in setOf("overlay", "popup", "split", "tab", "zoomed") && (platforms == null || platforms.size <= 3)
}
@Serializable
data class HostPlugin(val id: String, val name: String, val version: String, val minimumHerdrVersion: String, val description: String? = null, val enabled: Boolean, val platforms: List<String>? = null, val source: HostPluginSource, val actions: List<HostPluginAction>, val panes: List<HostPluginPane>, val warnings: List<String> = emptyList()) {
    fun valid(): Boolean = id.length in 1..120 && name.length <= 256 && version.length <= 128 && minimumHerdrVersion.length <= 128 &&
        listOfNotNull(description).all { it.length <= 2048 } && source.valid() && actions.size <= 256 && actions.all(HostPluginAction::valid) && panes.size <= 256 && panes.all(HostPluginPane::valid) && warnings.size <= 128 && warnings.all { it.length <= 2048 }
}
@Serializable
data class HostPluginsResponse(val ok: Boolean, val plugins: List<HostPlugin>) { fun valid(): Boolean = ok && plugins.size <= 256 && plugins.all(HostPlugin::valid) && plugins.map(HostPlugin::id).toSet().size == plugins.size }
@Serializable
data class HostPluginActionsResponse(val ok: Boolean, val actions: List<HostPluginAction>) { fun valid(): Boolean = ok && actions.size <= 1024 && actions.all(HostPluginAction::valid) && actions.map(HostPluginAction::qualifiedId).toSet().size == actions.size }
@Serializable
data class HostPluginLog(val id: String, val pluginId: String, val actionId: String? = null, val event: String? = null, val status: String, val startedAt: String, val finishedAt: String? = null, val exitCode: Int? = null, val stdout: String? = null, val stderr: String? = null, val error: String? = null) {
    fun valid(): Boolean = id.length <= 256 && pluginId.length in 1..120 && listOfNotNull(actionId, event, startedAt, finishedAt, stdout, stderr, error).all { it.length <= 16_384 && it.none(Char::isISOControl) } && status in setOf("running", "succeeded", "failed") && listOfNotNull(exitCode).all { it in -255..255 }
}
@Serializable
data class HostPluginLogsResponse(val ok: Boolean, val logs: List<HostPluginLog>) { fun valid(): Boolean = ok && logs.size <= 100 && logs.all(HostPluginLog::valid) }
@Serializable
data class HostPluginActionResult(val pluginId: String, val actionId: String, val logId: String? = null, val status: String) { fun valid(): Boolean = pluginId.length in 1..120 && actionId.length in 1..120 && listOfNotNull(logId).all { it.length <= 256 } && status in setOf("running", "succeeded", "failed") }
@Serializable
data class HostPluginActionRequest(val correlationId: String)
@Serializable
data class HostPluginActionResponse(val ok: Boolean, val action: HostPluginActionResult) { fun valid(): Boolean = ok && action.valid() }
@Serializable data class HostPluginPaneRequest(val entrypoint: String, val placement: String? = null)
@Serializable data class HostPluginPaneResult(val pluginId: String, val entrypoint: String, val paneId: String? = null, val placement: String)
@Serializable data class HostPluginPaneResponse(val ok: Boolean, val pane: HostPluginPaneResult) { fun valid() = ok && pane.pluginId.length in 1..120 && pane.entrypoint.length in 1..120 && listOfNotNull(pane.paneId).all { it.length in 1..128 } && pane.placement in setOf("overlay", "popup", "split", "tab", "zoomed") }
@Serializable data class HostPluginConfigFile(val id: String, val bytes: Int, val revision: String) { fun valid() = id.length in 1..512 && bytes in 0..65_536 && revision.matches(Regex("[a-f0-9]{64}")) }
@Serializable data class HostPluginConfigFilesResponse(val ok: Boolean, val files: List<HostPluginConfigFile>) { fun valid() = ok && files.size <= 256 && files.all(HostPluginConfigFile::valid) && files.map { it.id }.toSet().size == files.size }
@Serializable data class HostPluginConfigContent(val id: String, val bytes: Int, val revision: String, val content: String) { fun valid() = HostPluginConfigFile(id, bytes, revision).valid() && content.length <= 65_536 && content.toByteArray(Charsets.UTF_8).size <= 65_536 }
@Serializable data class HostPluginConfigResponse(val ok: Boolean, val file: HostPluginConfigContent) { fun valid() = ok && file.valid() }
@Serializable data class HostPluginConfigPlan(val token: String, val expiresAt: Long, val pluginId: String, val file: String? = null, val effect: String) {
    fun validFor(id: String, expectedFile: String? = null) = token.matches(Regex("[A-Fa-f0-9-]{36}")) && expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) && pluginId == id && file == expectedFile && effect.length in 1..2048 && effect.none(Char::isISOControl)
}
@Serializable data class HostPluginConfigPlanResponse(val ok: Boolean, val plan: HostPluginConfigPlan)
@Serializable data class HostPluginConfigOperation(val schema: Int, val requestId: String, val state: String, val capability: String, val targetIdentity: String, val at: Long, val updatedAt: Long, val code: String? = null, val result: kotlinx.serialization.json.JsonElement? = null) { fun valid() = schema == 1 && requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && state in setOf("pending", "succeeded", "refused", "unknown") && capability in setOf("plugins", "pluginOwnerOperations", "configuration") && validTarget() && at >= 0 && updatedAt >= at && (code == null || code.length <= 128)
    private fun validTarget(): Boolean {
        val id = "[A-Za-z0-9][A-Za-z0-9:._-]{0,119}"
        return when (capability) {
            "pluginOwnerOperations" -> targetIdentity.matches(Regex("plugin:$id:(?:install|link|uninstall)"))
            "plugins" -> targetIdentity.matches(Regex("plugin:$id:config-user-dirs"))
            "configuration" -> {
                val match = Regex("plugin:$id:config:([A-Za-z0-9._/-]{1,512})").matchEntire(targetIdentity) ?: return false
                match.groupValues[1].split('/').none { it in setOf("", ".", "..") }
            }
            else -> false
        }
    }
}
@Serializable data class HostPluginConfigOperationResponse(val ok: Boolean, val operation: HostPluginConfigOperation, val effect: String? = null) { fun valid() = operation.valid() && (ok == (operation.state == "succeeded")) && (operation.state != "refused" || !operation.code.isNullOrBlank()) && listOfNotNull(effect).all { it.length <= 2048 && it.none(Char::isISOControl) } }
@Serializable data class HostPluginConfigFileRequest(val requestId: String, val file: String, val content: String)
@Serializable data class HostPluginConfigApplyRequest(val requestId: String, val planToken: String, val file: String, val content: String, @EncodeDefault val confirmed: Boolean = true)
@Serializable data class HostPluginConfigPrepareRequest(val requestId: String, val planToken: String, @EncodeDefault val confirmed: Boolean = true)
@Serializable data class HostPluginLinkSelection(val id: String, val pluginId: String, val name: String, val version: String)
@Serializable data class HostPluginLinkSelectionsResponse(val ok: Boolean, val selections: List<HostPluginLinkSelection>) { fun valid() = ok && selections.size <= 256 && selections.all { it.id.matches(Regex("[a-f0-9]{64}")) && it.pluginId.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,119}")) && it.name.length <= 256 && it.version.length <= 128 } }
@Serializable data class HostPluginInstallSource(val owner: String, val repo: String, val subdir: String? = null, val ref: String? = null, val commit: String)
@Serializable data class HostPluginInstallPlanInput(val owner: String, val repo: String, val subdir: String? = null, val ref: String? = null)
@Serializable data class HostPluginInstallPlan(val token: String, val expiresAt: Long, val plugin: HostPlugin, val source: HostPluginInstallSource, val checksum: String, val effects: List<String>) {
    fun valid(owner: String, repo: String, subdir: String?, ref: String?): Boolean = token.matches(Regex("[A-Fa-f0-9-]{36}")) && expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) && plugin.valid() && source.owner == owner && source.repo == repo && source.subdir == subdir && source.ref == ref && source.commit.matches(Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")) && checksum.matches(Regex("[a-f0-9]{64}")) && effects.size <= 32 && effects.all { it.length in 1..2048 && it.none(Char::isISOControl) }
}
@Serializable data class HostPluginInstallPlanResponse(val ok: Boolean, val plan: HostPluginInstallPlan)
@Serializable data class HostPluginLinkPlan(val token: String, val expiresAt: Long, val pluginId: String, val name: String, val version: String, val effect: String) {
    fun valid() = token.matches(Regex("[A-Fa-f0-9-]{36}")) && expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) && pluginId.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,119}")) && name.length <= 256 && version.length <= 128 && effect.length in 1..2048 && effect.none(Char::isISOControl)
}
@Serializable data class HostPluginLinkPlanResponse(val ok: Boolean, val plan: HostPluginLinkPlan)
@Serializable data class HostPluginUninstallPlan(val token: String, val expiresAt: Long, val pluginId: String, val removeManagedCheckout: Boolean, val preservesConfigState: Boolean, val effect: String) {
    fun validFor(id: String) = token.matches(Regex("[A-Fa-f0-9-]{36}")) && expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) && pluginId == id && preservesConfigState && effect.length in 1..2048 && effect.none(Char::isISOControl)
}
@Serializable data class HostPluginUninstallPlanResponse(val ok: Boolean, val plan: HostPluginUninstallPlan)
@Serializable data class HostPluginInstallRequest(val requestId: String, val planToken: String, val checksum: String, @EncodeDefault val confirmed: Boolean = true)
@Serializable data class HostPluginLinkRequest(val requestId: String, val planToken: String, @EncodeDefault val confirmed: Boolean = true)
@Serializable data class HostPluginUninstallRequest(val requestId: String, val planToken: String, @EncodeDefault val confirmed: Boolean = true)
@Serializable data class HostPluginMutationResponse(val ok: Boolean, val operation: HostPluginConfigOperation, val effect: String? = null, val plugin: HostPlugin? = null) { fun validFor(requestId: String): Boolean = operation.valid() && operation.capability == "pluginOwnerOperations" && operation.requestId == requestId && (ok == (operation.state == "succeeded")) && (plugin == null || plugin.valid()) && listOfNotNull(effect).all { it.length <= 2048 && it.none(Char::isISOControl) } }
@Serializable data class HostPluginOwnerOperationResult(val requestId: String, val state: String, val plugin: HostPlugin? = null, val removed: Boolean, val code: String? = null)
@Serializable data class HostPluginOwnerOperationResponse(val ok: Boolean, val operation: HostPluginConfigOperation, val ownerOperation: HostPluginOwnerOperationResult) { fun validFor(requestId: String): Boolean = ok && operation.valid() && operation.requestId == requestId && operation.capability == "pluginOwnerOperations" && ownerOperation.requestId == requestId && ownerOperation.state in setOf("running", "succeeded", "refused", "unknown") && (ownerOperation.plugin == null || ownerOperation.plugin.valid()) && (ownerOperation.code == null || ownerOperation.code.length <= 128) && (ownerOperation.state != "succeeded" || ownerOperation.plugin != null || ownerOperation.removed) }
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
