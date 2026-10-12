package com.lateapex.collie.ui

import com.lateapex.collie.R
import com.lateapex.collie.network.IntegrationAction

data class HostIntegrationStatePresentation(
    val statusLabel: Int,
    val action: IntegrationAction,
    val actionLabel: Int,
)

object HostControlPresentation {
    fun integrationState(state: String): HostIntegrationStatePresentation = when (state) {
        "current" -> HostIntegrationStatePresentation(R.string.host_integration_current, IntegrationAction.UNINSTALL, R.string.host_integration_uninstall)
        "outdated" -> HostIntegrationStatePresentation(R.string.host_integration_outdated, IntegrationAction.UNINSTALL, R.string.host_integration_uninstall)
        "needs_repair" -> HostIntegrationStatePresentation(R.string.host_integration_needs_repair, IntegrationAction.INSTALL, R.string.host_integration_repair)
        "not_installed" -> HostIntegrationStatePresentation(R.string.host_integration_not_installed, IntegrationAction.INSTALL, R.string.host_integration_install)
        else -> HostIntegrationStatePresentation(R.string.host_integration_not_installed, IntegrationAction.INSTALL, R.string.host_integration_install)
    }
}
