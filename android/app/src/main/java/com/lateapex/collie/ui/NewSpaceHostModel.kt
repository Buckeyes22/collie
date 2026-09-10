package com.lateapex.collie.ui

import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorkspaceSummary

internal data class NewSpaceHostOption(
    val server: ServerSummary,
    val health: DashboardHostHealth,
    val slot: Int?,
) {
    val writable: Boolean get() = health.writable && !health.incompatible
}

/** Pure target-selection rules shared by the New Space sheet and focused tests. */
internal object NewSpaceHostModel {
    fun options(snapshot: SnapshotResponse): List<NewSpaceHostOption> {
        val servers = snapshot.servers.orEmpty()
        if (servers.size <= 1) return emptyList()
        return servers.map { server ->
            NewSpaceHostOption(
                server = server,
                health = DashboardHostHealthModel.from(server, snapshot),
                slot = HostPalette.slot(servers, server.id),
            )
        }
    }

    /** Ambient machine first, then the lead, then the first member that can actually take the write. */
    fun defaultHostId(options: List<NewSpaceHostOption>, ambientHost: String?): String? {
        if (options.isEmpty()) return null
        val lead = options.firstOrNull { it.server.isLead }?.server?.id
        val wanted = ambientHost ?: lead
        return options.firstOrNull { it.server.id == wanted && it.writable }?.server?.id
            ?: options.firstOrNull(NewSpaceHostOption::writable)?.server?.id
            ?: options.firstOrNull { it.server.id == wanted }?.server?.id
            ?: options.first().server.id
    }

    /** A write target is valid only while it remains in the roster and takes writes. */
    fun validatedScope(
        current: Scope,
        servers: List<ServerSummary>,
        options: List<NewSpaceHostOption>,
        selectedId: String?,
    ): Scope? {
        if (servers.size <= 1) return current.copy(viewAll = false)
        val selected = options.firstOrNull { it.server.id == selectedId } ?: return null
        if (!selected.writable) return null
        return Scope(
            host = selected.server.id.takeUnless { selected.server.isLead },
            session = current.session,
            viewAll = false,
        )
    }

    fun validatedScope(snapshot: SnapshotResponse, current: Scope, selectedId: String?): Scope? =
        validatedScope(
            current = current,
            servers = snapshot.servers.orEmpty(),
            options = options(snapshot),
            selectedId = selectedId,
        )

    /** Re-check a previously chosen target against the newest roster before a structural write. */
    fun revalidateScope(snapshot: SnapshotResponse, target: Scope): Scope? {
        val servers = snapshot.servers.orEmpty()
        if (servers.size <= 1) return target.copy(viewAll = false)
        val selectedId = target.host ?: servers.firstOrNull(ServerSummary::isLead)?.id
        return validatedScope(snapshot, target, selectedId)
    }

    /** Workspace ids are host-local, so worktree choices must follow the selected machine too. */
    fun repositoriesFor(
        workspaces: List<WorkspaceSummary>,
        servers: List<ServerSummary>,
        selectedId: String?,
    ): List<WorkspaceSummary> {
        if (servers.size <= 1) return workspaces
        val resolved = selectedId ?: servers.firstOrNull(ServerSummary::isLead)?.id
        return workspaces.filter { workspace ->
            workspace.host == resolved || (workspace.host == null && resolved == servers.firstOrNull(ServerSummary::isLead)?.id)
        }
    }
}
