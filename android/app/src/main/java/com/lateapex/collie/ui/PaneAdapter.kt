package com.lateapex.collie.ui

import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ItemDashboardAllClearBinding
import com.lateapex.collie.databinding.ItemDashboardEmptyBinding
import com.lateapex.collie.databinding.ItemDashboardFooterBinding
import com.lateapex.collie.databinding.ItemDashboardLaunchersBinding
import com.lateapex.collie.databinding.ItemDashboardSectionBinding
import com.lateapex.collie.databinding.ItemDashboardSpacesBinding
import com.lateapex.collie.databinding.ItemPaneBinding
import com.lateapex.collie.databinding.ItemSpaceBinding
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.domain.Scope
import java.util.Locale

/** The dashboard's four fixed buckets. Their order is a product contract, not a sort preference. */
internal enum class TriageBucket(
    @StringRes val labelRes: Int,
    @ColorRes val colour: Int,
    val attention: Boolean,
) {
    NEEDS_YOU(R.string.triage_needs_you, R.color.collie_blocked, true),
    READY_UNSEEN(R.string.triage_ready_unseen, R.color.collie_done, true),
    WORKING(R.string.triage_working, R.color.collie_working, false),
    RECENT(R.string.triage_recent, R.color.collie_idle, false),
}

internal data class CompactAge(@StringRes val resource: Int, val value: Long? = null) {
    fun resolve(view: View): String = value?.let { view.resources.getString(resource, it) }
        ?: view.resources.getString(resource)
}

internal data class PaneRow(
    val pane: PaneSummary,
    val title: String,
    val metadata: String,
    val age: CompactAge?,
    val host: DashboardPaneHost? = null,
    val session: String? = null,
)

internal enum class DashboardPaneHostState { AVAILABLE, UNREACHABLE, INCOMPATIBLE }

internal data class DashboardPaneHost(
    val name: String,
    val slot: Int?,
    val state: DashboardPaneHostState,
)

internal data class SpaceRow(
    val workspace: WorkspaceSummary,
    val status: AgentStatus?,
    val age: CompactAge?,
    val depth: Int = 0,
)

internal data class PackFooterSummary(val machines: Int, val reachable: Int)

internal fun packFooterSummary(servers: List<ServerSummary>?): PackFooterSummary? = servers
    ?.takeIf { it.size > 1 }
    ?.let { roster -> PackFooterSummary(roster.size, roster.count(ServerSummary::reachable)) }

internal sealed interface DashboardItem {
    data object AllClear : DashboardItem
    data class EmptyPanes(@StringRes val messageRes: Int) : DashboardItem
    data class PaneSection(
        val bucket: TriageBucket,
        val panes: List<PaneRow>,
        val open: Boolean = true,
        val newest: Boolean = true,
    ) : DashboardItem
    data class Launchers(
        val rows: List<Launcher>,
        val enabled: Boolean = true,
        val open: Boolean = true,
        val launching: Set<String> = emptySet(),
        val home: String = "",
    ) : DashboardItem
    data class Spaces(
        val rows: List<SpaceRow>,
        val total: Int = rows.size,
        val blockedCount: Int = 0,
        val open: Boolean = true,
        val query: String = "",
        val canCreate: Boolean = true,
        val capabilityNote: String = "",
        val creating: Boolean = false,
        @StringRes val progressDescriptionRes: Int = R.string.space_creating,
    ) : DashboardItem
    data class Footer(
        val build: String,
        val pack: PackFooterSummary? = null,
    ) : DashboardItem
}

/** Pure presentation model shared by the adapter and deterministic unit tests. */
internal object DashboardModel {
    fun items(
        snapshot: SnapshotResponse,
        scope: Scope = Scope(),
        stale: Boolean,
        build: String,
        recentOpen: Boolean = true,
        recentNewest: Boolean = true,
        spacesOpen: Boolean = true,
        spaceQuery: String = "",
        canCreateSpace: Boolean = true,
        createSpaceNote: String = "",
        creatingSpace: Boolean = false,
        @StringRes structuralProgressRes: Int = R.string.space_creating,
        launchers: List<Launcher> = emptyList(),
        writesEnabled: Boolean = true,
        launchOpen: Boolean = true,
        launching: Set<String> = emptySet(),
        launcherHome: String = "",
    ): List<DashboardItem> {
        // Dashboard triage is agents-only, exactly like the web AgentList. Shell panes remain
        // available in Spaces and pane navigation, but presenting them as agents makes an idle
        // terminal look like work that needs triage.
        val panes = snapshot.agents
        val byBucket = panes.groupBy(::bucketOf)
        return buildList {
            if (panes.isEmpty()) {
                add(
                    DashboardItem.EmptyPanes(
                        when {
                            stale -> R.string.dashboard_disconnected_empty
                            snapshot.bridge == "disconnected" -> R.string.dashboard_mux_disconnected
                            else -> R.string.dashboard_no_agents
                        },
                    ),
                )
            } else {
                if (byBucket[TriageBucket.NEEDS_YOU].isNullOrEmpty()) add(DashboardItem.AllClear)
                TriageBucket.entries.forEach { bucket ->
                    val rows = byBucket[bucket]
                        .orEmpty()
                        .sortedWith(bucketComparator(bucket))
                        .let { ordered ->
                            if (bucket == TriageBucket.RECENT && !recentNewest) ordered.reversed() else ordered
                        }
                        .map { pane -> paneRow(pane, bucket, snapshot) }
                    if (rows.isNotEmpty()) add(
                        DashboardItem.PaneSection(
                            bucket,
                            rows,
                            open = bucket != TriageBucket.RECENT || recentOpen,
                            newest = recentNewest,
                        ),
                    )
                }
            }
            if (launchers.isNotEmpty()) {
                add(DashboardItem.Launchers(launchers, writesEnabled, launchOpen, launching, launcherHome))
            }
            val orderedSpaces = spaceRows(snapshot, scope)
            val filtered = spaceQuery.trim().lowercase(Locale.ROOT).let { query ->
                if (query.isEmpty()) nestWorktrees(orderedSpaces) else orderedSpaces.filter { row ->
                    row.workspace.label.lowercase(Locale.ROOT).contains(query) ||
                        row.workspace.repoRoot?.lowercase(Locale.ROOT)?.contains(query) == true
                }.map { it.copy(depth = 0) }
            }
            add(
                DashboardItem.Spaces(
                    rows = filtered,
                    total = orderedSpaces.size,
                    blockedCount = orderedSpaces.count { it.status == AgentStatus.BLOCKED },
                    open = spacesOpen,
                    query = spaceQuery,
                    canCreate = canCreateSpace,
                    capabilityNote = createSpaceNote.takeIf { !canCreateSpace }.orEmpty(),
                    creating = creatingSpace,
                    progressDescriptionRes = structuralProgressRes,
                ),
            )
            add(
                DashboardItem.Footer(
                    build = build,
                    pack = packFooterSummary(snapshot.servers),
                ),
            )
        }
    }

    fun bucketOf(pane: PaneSummary): TriageBucket = when {
        pane.status == AgentStatus.BLOCKED -> TriageBucket.NEEDS_YOU
        pane.status == AgentStatus.DONE && (pane.lastActiveAt ?: 0) > (pane.lastSeenAt ?: 0) ->
            TriageBucket.READY_UNSEEN
        pane.status == AgentStatus.WORKING -> TriageBucket.WORKING
        else -> TriageBucket.RECENT
    }

    fun paneTitle(pane: PaneSummary): String {
        return dashboardPaneText(pane).primary
    }

    fun paneMetadata(pane: PaneSummary): String = dashboardPaneText(pane).secondary.orEmpty()

    fun compactAge(at: Long?, now: Long): CompactAge? {
        if (at == null) return null
        val seconds = (now - at).coerceAtLeast(0) / 1_000
        if (seconds < 60) return CompactAge(R.string.age_now)
        val minutes = seconds / 60
        if (minutes < 60) return CompactAge(R.string.age_minutes, minutes)
        val hours = minutes / 60
        if (hours < 24) return CompactAge(R.string.age_hours, hours)
        return CompactAge(R.string.age_days, hours / 24)
    }

    private fun paneRow(pane: PaneSummary, bucket: TriageBucket, snapshot: SnapshotResponse): PaneRow {
        val at = when (bucket) {
            TriageBucket.NEEDS_YOU -> null
            TriageBucket.READY_UNSEEN, TriageBucket.WORKING -> pane.lastActiveAt
            TriageBucket.RECENT -> pane.lastSeenAt
        }
        return PaneRow(
            pane = pane,
            title = paneTitle(pane),
            metadata = paneMetadata(pane),
            age = compactAge(at, snapshot.ts),
            host = paneHost(pane, snapshot),
            session = paneSession(pane, snapshot),
        )
    }

    private fun paneHost(pane: PaneSummary, snapshot: SnapshotResponse): DashboardPaneHost? {
        val hostId = pane.host?.takeIf(String::isNotBlank) ?: return null
        val servers = snapshot.servers.orEmpty()
        if (servers.size <= 1) return null
        val server = servers.firstOrNull { it.id == hostId }
        val health = server?.let { DashboardHostHealthModel.from(it, snapshot) }
        return DashboardPaneHost(
            name = server?.name?.takeIf(String::isNotBlank) ?: server?.id ?: hostId,
            slot = HostPalette.slot(servers, hostId),
            state = when {
                health?.incompatible == true -> DashboardPaneHostState.INCOMPATIBLE
                health?.writable != true -> DashboardPaneHostState.UNREACHABLE
                else -> DashboardPaneHostState.AVAILABLE
            },
        )
    }

    private fun paneSession(pane: PaneSummary, snapshot: SnapshotResponse): String? {
        val name = pane.session?.takeIf(String::isNotBlank) ?: return null
        val lead = snapshot.servers.orEmpty().firstOrNull(ServerSummary::isLead)?.id
        val host = pane.host ?: lead
        val primary = snapshot.sessions.firstOrNull { session ->
            session.isPrimary && (session.host == null || session.host == host)
        }?.name
        return name.takeUnless { it == primary }
    }

    private fun bucketComparator(bucket: TriageBucket): Comparator<PaneSummary> =
        compareByDescending<PaneSummary> {
            when (bucket) {
                TriageBucket.RECENT -> it.lastSeenAt ?: 0
                else -> it.lastActiveAt ?: 0
            }
        }

    private fun spaceRows(snapshot: SnapshotResponse, scope: Scope): List<SpaceRow> {
        val leadId = snapshot.servers?.firstOrNull { it.isLead }?.id
        val selectedHost = scope.host ?: leadId
        val selectedSession = scope.session ?: snapshot.sessions.firstOrNull { session ->
            session.isPrimary && (session.host == null || session.host == selectedHost)
        }?.name
        val allPanes = (snapshot.agents + snapshot.shellPanes).filter { pane ->
            (pane.host == null || pane.host == selectedHost) &&
                (pane.session == null || selectedSession == null || pane.session == selectedSession)
        }
        return snapshot.workspaces
            .map { workspace ->
                val panes = allPanes.filter { pane ->
                    pane.workspaceId == workspace.workspaceId &&
                        (workspace.host == null || pane.host == workspace.host)
                }
                val status = panes.minOfOrNull { bucketOf(it).ordinal }?.let(TriageBucket.entries::get)
                    ?.let(::representativeStatus)
                val seen = panes.mapNotNull(PaneSummary::lastSeenAt).maxOrNull()
                SpaceRow(workspace, status, compactAge(seen, snapshot.ts))
            }
            .sortedByDescending { row ->
                allPanes
                    .filter { pane ->
                        pane.workspaceId == row.workspace.workspaceId &&
                            (row.workspace.host == null || pane.host == row.workspace.host)
                    }
                    .mapNotNull(PaneSummary::lastSeenAt)
                    .maxOrNull() ?: 0
            }
    }

    private fun nestWorktrees(ordered: List<SpaceRow>): List<SpaceRow> {
        val parents = ordered.filter { it.workspace.repoRoot != null && it.workspace.isWorktree == false }
            .associateBy { it.workspace.repoRoot }
        val children = ordered.filter { it.workspace.repoRoot != null && it.workspace.isWorktree == true }
            .groupBy { child -> parents[child.workspace.repoRoot]?.workspace?.workspaceId }
        val placed = mutableSetOf<String>()
        return buildList {
            ordered.forEach { row ->
                if (row.workspace.workspaceId in placed) return@forEach
                val ownChildren = children[row.workspace.workspaceId]
                if (ownChildren != null) {
                    placed += row.workspace.workspaceId
                    add(row.copy(depth = 0))
                    ownChildren.forEach { child ->
                        if (placed.add(child.workspace.workspaceId)) add(child.copy(depth = 1))
                    }
                    return@forEach
                }
                if (row.workspace.isWorktree == true) {
                    val parent = parents[row.workspace.repoRoot]
                    if (parent != null && parent.workspace.workspaceId !in placed) {
                        placed += parent.workspace.workspaceId
                        add(parent.copy(depth = 0))
                        children[parent.workspace.workspaceId].orEmpty().forEach { child ->
                            if (placed.add(child.workspace.workspaceId)) add(child.copy(depth = 1))
                        }
                        return@forEach
                    }
                }
                placed += row.workspace.workspaceId
                add(row.copy(depth = 0))
            }
        }
    }

    private fun representativeStatus(bucket: TriageBucket): AgentStatus = when (bucket) {
        TriageBucket.NEEDS_YOU -> AgentStatus.BLOCKED
        TriageBucket.READY_UNSEEN -> AgentStatus.DONE
        TriageBucket.WORKING -> AgentStatus.WORKING
        TriageBucket.RECENT -> AgentStatus.IDLE
    }
}

/** One list whose section rows own either attention cards or a single framed group of flat rows. */
internal class DashboardAdapter(
    private val onPane: (PaneSummary) -> Unit,
    private val onSpace: (WorkspaceSummary) -> Unit,
    private val onRecentOpen: (Boolean) -> Unit = {},
    private val onRecentSort: () -> Unit = {},
    private val onSpacesOpen: (Boolean) -> Unit = {},
    private val onSpaceQuery: (String) -> Unit = {},
    private val onNewSpace: () -> Unit = {},
    private val onLauncher: (Launcher) -> Unit = {},
    private val onLaunchOpen: (Boolean) -> Unit = {},
    private val onPack: () -> Unit = {},
) : ListAdapter<DashboardItem, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        DashboardItem.AllClear -> TYPE_ALL_CLEAR
        is DashboardItem.EmptyPanes -> TYPE_EMPTY
        is DashboardItem.PaneSection -> TYPE_PANES
        is DashboardItem.Launchers -> TYPE_LAUNCHERS
        is DashboardItem.Spaces -> TYPE_SPACES
        is DashboardItem.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ALL_CLEAR -> AllClearHolder(ItemDashboardAllClearBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyHolder(ItemDashboardEmptyBinding.inflate(inflater, parent, false))
            TYPE_PANES -> PaneSectionHolder(ItemDashboardSectionBinding.inflate(inflater, parent, false))
            TYPE_LAUNCHERS -> LaunchersHolder(ItemDashboardLaunchersBinding.inflate(inflater, parent, false))
            TYPE_SPACES -> SpacesHolder(ItemDashboardSpacesBinding.inflate(inflater, parent, false))
            TYPE_FOOTER -> FooterHolder(ItemDashboardFooterBinding.inflate(inflater, parent, false))
            else -> error("Unknown dashboard row type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            DashboardItem.AllClear -> Unit
            is DashboardItem.EmptyPanes -> (holder as EmptyHolder).bind(item)
            is DashboardItem.PaneSection -> (holder as PaneSectionHolder).bind(item)
            is DashboardItem.Launchers -> (holder as LaunchersHolder).bind(item)
            is DashboardItem.Spaces -> (holder as SpacesHolder).bind(item)
            is DashboardItem.Footer -> (holder as FooterHolder).bind(item)
        }
    }

    private class AllClearHolder(binding: ItemDashboardAllClearBinding) : RecyclerView.ViewHolder(binding.root)

    private class EmptyHolder(
        private val binding: ItemDashboardEmptyBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardItem.EmptyPanes) {
            binding.emptyText.setText(item.messageRes)
        }
    }

    private inner class PaneSectionHolder(
        private val binding: ItemDashboardSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardItem.PaneSection) = with(binding) {
            sectionTitle.text = root.resources.getString(item.bucket.labelRes).uppercase(Locale.ENGLISH)
            sectionCount.text = root.resources.getString(R.string.count_parenthesized, item.panes.size)
            sectionTitle.setTextColor(
                ContextCompat.getColor(
                    root.context,
                    if (item.bucket == TriageBucket.NEEDS_YOU) item.bucket.colour else R.color.collie_muted,
                ),
            )
            sectionDot.background = dot(root, item.bucket.colour)
            sectionToggle.visibility = if (item.bucket == TriageBucket.RECENT) View.VISIBLE else View.GONE
            sectionToggle.setImageResource(
                if (item.open) R.drawable.ic_history_chevron_down else R.drawable.ic_history_chevron_right,
            )
            sectionToggle.rotation = 0f
            sectionToggle.contentDescription = root.resources.getString(
                if (item.open) R.string.collapse_recent else R.string.expand_recent,
            )
            sectionToggle.setOnClickListener { onRecentOpen(!item.open) }
            sectionSort.visibility = if (item.bucket == TriageBucket.RECENT && item.open) View.VISIBLE else View.GONE
            sectionSort.setText(if (item.newest) R.string.recent_newest else R.string.recent_oldest)
            sectionSort.contentDescription = root.resources.getString(
                if (item.newest) R.string.recent_switch_oldest else R.string.recent_switch_newest,
            )
            sectionSort.setOnClickListener { onRecentSort() }
            sectionRows.removeAllViews()
            sectionRows.background = if (item.bucket.attention) null else
                ContextCompat.getDrawable(root.context, R.drawable.bg_dashboard_group)
            item.panes.takeIf { item.open }.orEmpty().forEachIndexed { index, row ->
                if (index > 0 && !item.bucket.attention) addDivider(sectionRows)
                val rowBinding = ItemPaneBinding.inflate(LayoutInflater.from(root.context), sectionRows, false)
                bindPane(
                    binding = rowBinding,
                    row = row,
                    attention = item.bucket.attention,
                    hasFollowing = index < item.panes.lastIndex,
                )
                sectionRows.addView(rowBinding.root)
            }
        }

        private fun bindPane(
            binding: ItemPaneBinding,
            row: PaneRow,
            attention: Boolean,
            hasFollowing: Boolean,
        ) = with(binding) {
            paneTitle.text = row.title
            paneAgent.setImageResource(agentIcon(row.pane.agent))
            paneAgent.contentDescription = root.resources.getString(R.string.agent_icon_description, row.pane.agent)
            paneMeta.text = row.metadata
            paneHost.isVisible = row.host != null
            row.host?.let { host ->
                paneHost.text = when (host.state) {
                    DashboardPaneHostState.AVAILABLE -> host.name
                    DashboardPaneHostState.UNREACHABLE -> root.resources.getString(
                        R.string.dashboard_pane_host_unreachable,
                        host.name,
                    )
                    DashboardPaneHostState.INCOMPATIBLE -> root.resources.getString(
                        R.string.dashboard_pane_host_incompatible,
                        host.name,
                    )
                }
                paneHost.contentDescription = when (host.state) {
                    DashboardPaneHostState.AVAILABLE -> root.resources.getString(
                        R.string.dashboard_pane_host_accessibility,
                        host.name,
                    )
                    DashboardPaneHostState.UNREACHABLE -> root.resources.getString(
                        R.string.dashboard_pane_host_state_accessibility,
                        host.name,
                        root.resources.getString(R.string.health_unreachable),
                    )
                    DashboardPaneHostState.INCOMPATIBLE -> root.resources.getString(
                        R.string.dashboard_pane_host_state_accessibility,
                        host.name,
                        root.resources.getString(R.string.health_incompatible),
                    )
                }
                val alert = host.state != DashboardPaneHostState.AVAILABLE
                val glyphColor = ContextCompat.getColor(
                    root.context,
                    if (alert) R.color.collie_blocked
                    else host.slot?.let(HostPalette::resource) ?: R.color.collie_muted,
                )
                TextViewCompat.setCompoundDrawableTintList(paneHost, ColorStateList.valueOf(glyphColor))
                paneHost.setTextColor(ContextCompat.getColor(
                    root.context,
                    if (alert) R.color.collie_blocked else R.color.collie_muted,
                ))
                paneHost.background = addressTagBackground(root, alert)
            }
            paneSession.text = row.session.orEmpty()
            paneSession.contentDescription = row.session?.let {
                root.resources.getString(R.string.dashboard_pane_session_accessibility, it)
            }
            paneSession.isVisible = row.session != null
            // The web dashboard keeps ordinary rows to title + metadata. A third line is reserved
            // for an actual bridge hint; repeating cwd on every row destroys the same glance density.
            paneHint.text = row.pane.hint.orEmpty()
            paneHint.isVisible = paneHint.text.isNotBlank()
            paneAge.text = row.age?.resolve(root).orEmpty()
            paneAge.isVisible = row.age != null
            statusDot.background = statusDot(root, row.pane.status)
            root.background = when {
                !attention -> null
                row.pane.status == AgentStatus.BLOCKED ->
                    ContextCompat.getDrawable(root.context, R.drawable.bg_pane_attention)
                else -> ContextCompat.getDrawable(root.context, R.drawable.bg_pane_card)
            }
            (root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                if (attention && hasFollowing) {
                    root.resources.getDimensionPixelSize(R.dimen.dashboard_card_gap)
                } else {
                    0
                }
            root.setOnClickListener { onPane(row.pane) }
            root.contentDescription = root.resources.getString(
                R.string.pane_accessibility,
                row.title,
                statusText(root, row.pane),
                listOfNotNull(
                    row.metadata.takeIf(String::isNotBlank),
                    paneHost.contentDescription?.toString()?.takeIf(String::isNotBlank),
                    paneSession.contentDescription?.toString()?.takeIf(String::isNotBlank),
                ).joinToString(" · "),
            )
        }

        private fun addressTagBackground(view: View, alert: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.resources.getDimension(R.dimen.collie_corner_radius)
            setColor(ContextCompat.getColor(view.context, R.color.collie_muted_surface))
            if (alert) {
                val density = view.resources.displayMetrics.density
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    ContextCompat.getColor(view.context, R.color.collie_blocked),
                    density * 3f,
                    density * 2f,
                )
            }
        }
    }

    private inner class LaunchersHolder(
        private val binding: ItemDashboardLaunchersBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardItem.Launchers) = with(binding) {
            launcherCount.text = root.resources.getString(R.string.count_parenthesized, item.rows.size)
            launchToggle.setImageResource(
                if (item.open) R.drawable.ic_history_chevron_down else R.drawable.ic_history_chevron_right,
            )
            launchToggle.rotation = 0f
            launchToggle.contentDescription = root.resources.getString(
                if (item.open) R.string.collapse_launch else R.string.expand_launch,
            )
            launchToggle.setOnClickListener { onLaunchOpen(!item.open) }
            launcherRows.removeAllViews()
            launcherRows.isVisible = item.open
            item.rows.takeIf { item.open }.orEmpty().chunked(2).forEach { pair ->
                val row = LinearLayout(root.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                }
                pair.forEachIndexed { index, launcher ->
                val pending = launcher.command in item.launching
                val button = com.google.android.material.button.MaterialButton(
                    root.context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    val title = if (pending) {
                        root.resources.getString(R.string.launcher_pending, launcher.label)
                    } else {
                        launcher.label
                    }
                    text = launcherLabel(title, launcher, item.home)
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight = resources.getDimensionPixelSize(R.dimen.collie_touch_target)
                    isAllCaps = false
                    contentDescription = root.resources.getString(R.string.launcher_accessibility, launcher.label)
                    isEnabled = item.enabled && !pending
                    setOnClickListener { onLauncher(launcher) }
                }
                    row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) marginEnd = root.resources.getDimensionPixelSize(R.dimen.dashboard_card_gap) / 2
                        if (index == 1) marginStart = root.resources.getDimensionPixelSize(R.dimen.dashboard_card_gap) / 2
                        bottomMargin = root.resources.getDimensionPixelSize(R.dimen.dashboard_card_gap)
                    })
                }
                if (pair.size == 1) row.addView(View(root.context), LinearLayout.LayoutParams(0, 1, 1f))
                launcherRows.addView(row)
            }
        }

        private fun launcherLabel(title: String, launcher: Launcher, home: String): CharSequence {
            val label = SpannableStringBuilder(title)
            launcher.cwd?.let { cwd ->
                val suffix = " ${DisplayText.abbreviateHome(cwd, home)}"
                val start = label.length
                label.append(suffix)
                label.setSpan(TypefaceSpan("monospace"), start, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(RelativeSizeSpan(0.85f), start, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val commandStart = label.length + 1
            label.append('\n').append(launcher.command)
            label.setSpan(TypefaceSpan("monospace"), commandStart, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.setSpan(RelativeSizeSpan(0.85f), commandStart, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return label
        }
    }

    private inner class SpacesHolder(
        private val binding: ItemDashboardSpacesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var filterWatcher: TextWatcher? = null

        fun bind(item: DashboardItem.Spaces) = with(binding) {
            spacesCount.text = root.resources.getString(R.string.count_parenthesized, item.rows.size)
            spacesBlockedCount.text = item.blockedCount.takeIf { it > 0 }?.toString().orEmpty()
            spacesBlockedCount.contentDescription = item.blockedCount.takeIf { it > 0 }?.let { count ->
                root.resources.getQuantityString(R.plurals.space_needs_you, count, count)
            }
            spacesBlockedCount.isVisible = item.blockedCount > 0
            spacesToggle.setImageResource(
                if (item.open) R.drawable.ic_history_chevron_down else R.drawable.ic_history_chevron_right,
            )
            spacesToggle.rotation = 0f
            spacesToggle.contentDescription = root.resources.getString(
                if (item.open) R.string.collapse_spaces else R.string.expand_spaces,
            )
            spacesToggle.setOnClickListener { onSpacesOpen(!item.open) }
            newSpace.visibility = if (item.canCreate && !item.creating) View.VISIBLE else View.GONE
            newSpace.setOnClickListener { onNewSpace() }
            newSpaceProgress.visibility = if (item.canCreate && item.creating) View.VISIBLE else View.GONE
            newSpaceProgress.contentDescription = root.resources.getString(item.progressDescriptionRes)
            spaceFilter.visibility = if (item.open && item.total > 1) View.VISIBLE else View.GONE
            filterWatcher?.let(spaceFilter::removeTextChangedListener)
            if (spaceFilter.text.toString() != item.query) {
                spaceFilter.setText(item.query)
                spaceFilter.setSelection(item.query.length)
            }
            filterWatcher = object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    val query = text?.toString().orEmpty()
                    if (query != item.query) onSpaceQuery(query)
                }
            }
            spaceFilter.addTextChangedListener(filterWatcher)
            spacesCapabilityNote.text = item.capabilityNote
            spacesCapabilityNote.isVisible = item.open && item.capabilityNote.isNotBlank()
            spacesRows.removeAllViews()
            spacesRows.isVisible = item.open
            spacesEmpty.isVisible = item.open && item.rows.isEmpty()
            item.rows.takeIf { item.open }.orEmpty().forEachIndexed { index, row ->
                if (index > 0) addDivider(spacesRows)
                val rowBinding = ItemSpaceBinding.inflate(LayoutInflater.from(root.context), spacesRows, false)
                rowBinding.spaceTitle.text = row.workspace.label
                rowBinding.spaceAge.text = row.age?.resolve(root).orEmpty()
                rowBinding.spaceAge.isVisible = row.age != null
                rowBinding.spaceCount.text = root.resources.getString(R.string.pane_count, row.workspace.paneCount)
                rowBinding.root.setPadding(
                    root.resources.getDimensionPixelSize(R.dimen.dashboard_row_padding) +
                        if (row.depth == 1) root.resources.getDimensionPixelSize(R.dimen.dashboard_worktree_indent) else 0,
                    rowBinding.root.paddingTop,
                    root.resources.getDimensionPixelSize(R.dimen.dashboard_row_padding),
                    rowBinding.root.paddingBottom,
                )
                rowBinding.statusDot.background = row.status?.let { statusDot(root, it) }
                    ?: dot(root, R.color.collie_muted, hollow = true)
                rowBinding.root.setOnClickListener { onSpace(row.workspace) }
                rowBinding.root.contentDescription = root.resources.getString(
                    R.string.space_accessibility,
                    row.workspace.label,
                    row.workspace.paneCount,
                )
                spacesRows.addView(rowBinding.root)
            }
        }
    }

    private inner class FooterHolder(
        private val binding: ItemDashboardFooterBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardItem.Footer) {
            binding.dashboardPackLink.isVisible = item.pack != null
            binding.dashboardPackText.text = item.pack?.let { pack ->
                val machines = binding.root.resources.getQuantityString(
                    R.plurals.pack_machine_count,
                    pack.machines,
                    pack.machines,
                )
                binding.root.resources.getString(R.string.pack_footer_label, machines, pack.reachable)
            }.orEmpty()
            binding.dashboardPackLink.setOnClickListener(
                if (item.pack != null) View.OnClickListener { onPack() } else null,
            )
            binding.buildText.text = item.build
        }
    }

    private object Diff : DiffUtil.ItemCallback<DashboardItem>() {
        override fun areItemsTheSame(oldItem: DashboardItem, newItem: DashboardItem): Boolean =
            when {
                oldItem is DashboardItem.PaneSection && newItem is DashboardItem.PaneSection ->
                    oldItem.bucket == newItem.bucket
                else -> oldItem::class == newItem::class
            }

        override fun areContentsTheSame(oldItem: DashboardItem, newItem: DashboardItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_ALL_CLEAR = 0
        private const val TYPE_EMPTY = 1
        private const val TYPE_PANES = 2
        private const val TYPE_LAUNCHERS = 3
        private const val TYPE_SPACES = 4
        private const val TYPE_FOOTER = 5

        private fun addDivider(parent: LinearLayout) {
            parent.addView(View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(context, R.color.collie_rule))
            })
        }

        private fun statusDot(view: View, status: AgentStatus): GradientDrawable = when (status) {
            AgentStatus.BLOCKED -> dot(view, R.color.collie_blocked)
            AgentStatus.WORKING -> dot(view, R.color.collie_working)
            AgentStatus.DONE -> dot(view, R.color.collie_done)
            AgentStatus.IDLE, AgentStatus.UNKNOWN -> dot(view, R.color.collie_idle, hollow = true)
        }

        private fun dot(view: View, @ColorRes colour: Int, hollow: Boolean = false): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val resolved = ContextCompat.getColor(view.context, colour)
                if (hollow) {
                    setColor(ContextCompat.getColor(view.context, R.color.collie_background))
                    setStroke(view.resources.displayMetrics.density.times(1.5f).toInt().coerceAtLeast(1), resolved)
                } else {
                    setColor(resolved)
                }
            }

        private fun statusText(view: View, pane: PaneSummary): String = view.resources.getString(
            when (pane.status) {
                AgentStatus.BLOCKED -> R.string.status_needs_you
                AgentStatus.WORKING -> R.string.status_working
                AgentStatus.DONE -> R.string.status_done
                AgentStatus.IDLE -> R.string.status_idle
                AgentStatus.UNKNOWN -> if (pane.kind == "shell") R.string.status_shell else R.string.status_unknown
            },
        )

    }
}
