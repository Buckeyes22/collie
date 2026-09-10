package com.lateapex.collie.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lateapex.collie.R
import com.lateapex.collie.databinding.ItemPaneBinding
import com.lateapex.collie.databinding.ItemSpaceOverviewBinding
import com.lateapex.collie.databinding.ItemSpaceTabBinding
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary

internal sealed interface SpaceListItem {
    data class Overview(val content: SpaceContent) : SpaceListItem
    data class Tab(val tab: TabSummary) : SpaceListItem
    data class Pane(val pane: PaneSummary) : SpaceListItem
    data class Empty(val tabId: String?, val messageRes: Int) : SpaceListItem
}

/** Pure projection from route state to the grouped list below the navigation strips. */
internal object SpacePresentationModel {
    fun rows(content: SpaceContent?, selectedTabId: String?): List<SpaceListItem> {
        if (content == null) return emptyList()
        val selected = selectedTabId?.takeIf { wanted -> content.tabs.any { it.tab.tabId == wanted } }
        val groups = selected?.let { wanted -> content.tabs.filter { it.tab.tabId == wanted } } ?: content.tabs
        return buildList {
            add(SpaceListItem.Overview(content))
            if (groups.isEmpty()) {
                add(SpaceListItem.Empty(selected, R.string.space_view_no_panes_space))
                return@buildList
            }
            groups.forEach { group ->
                if (selected == null) add(SpaceListItem.Tab(group.tab))
                if (group.panes.isEmpty()) {
                    add(SpaceListItem.Empty(group.tab.tabId, R.string.space_view_empty_tab))
                } else {
                    group.panes.forEach { add(SpaceListItem.Pane(it)) }
                }
            }
        }
    }

    fun retainedSelection(content: SpaceContent?, selectedTabId: String?): String? =
        selectedTabId?.takeIf { wanted -> content?.tabs?.any { it.tab.tabId == wanted } == true }

    fun worstStatus(panes: List<PaneSummary>): AgentStatus? = panes
        .minByOrNull { DashboardModel.bucketOf(it).ordinal }
        ?.status

    fun soleAgent(panes: List<PaneSummary>): String? = panes
        .map(PaneSummary::agent)
        .filter(String::isNotBlank)
        .distinct()
        .singleOrNull()
}

internal class SpaceAdapter(
    private val onPane: (PaneSummary) -> Unit,
) : ListAdapter<SpaceListItem, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SpaceListItem.Overview -> TYPE_OVERVIEW
        is SpaceListItem.Tab -> TYPE_TAB
        is SpaceListItem.Pane -> TYPE_PANE
        is SpaceListItem.Empty -> TYPE_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OVERVIEW -> OverviewHolder(ItemSpaceOverviewBinding.inflate(inflater, parent, false))
            TYPE_TAB -> TabHolder(ItemSpaceTabBinding.inflate(inflater, parent, false))
            TYPE_PANE -> PaneHolder(ItemPaneBinding.inflate(inflater, parent, false).also { binding ->
                (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    marginStart = parent.dp(16)
                    marginEnd = parent.dp(16)
                    bottomMargin = parent.dp(8)
                }
            })
            TYPE_EMPTY -> EmptyHolder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setTextColor(ContextCompat.getColor(context, R.color.collie_muted))
                textSize = 12f
                typeface = ResourcesCompat.getFont(context, R.font.collie_ui)
            })
            else -> error("Unknown space row type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SpaceListItem.Overview -> (holder as OverviewHolder).bind(item.content)
            is SpaceListItem.Tab -> (holder as TabHolder).bind(item.tab)
            is SpaceListItem.Pane -> (holder as PaneHolder).bind(item.pane)
            is SpaceListItem.Empty -> (holder as EmptyHolder).bind(item)
        }
    }

    private class OverviewHolder(
        private val binding: ItemSpaceOverviewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(content: SpaceContent) = with(binding) {
            spaceOverviewTitle.text = content.workspace.label
            val tabCount = root.resources.getQuantityString(
                R.plurals.space_tab_count,
                content.workspace.tabCount,
                content.workspace.tabCount,
            )
            val paneCount = root.resources.getQuantityString(
                R.plurals.space_pane_count,
                content.workspace.paneCount,
                content.workspace.paneCount,
            )
            spaceOverviewMeta.text = "$tabCount · $paneCount"
        }
    }

    private class TabHolder(
        private val binding: ItemSpaceTabBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tab: TabSummary) = with(binding) {
            tabTitle.text = tab.label.ifBlank { root.resources.getString(R.string.tab_default, tab.number) }
        }
    }

    private inner class PaneHolder(
        private val binding: ItemPaneBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pane: PaneSummary) = with(binding) {
            val text = spacePaneText(pane)
            paneTitle.text = text.primary
            paneAgent.setImageResource(agentIcon(pane.agent))
            paneAgent.contentDescription = root.resources.getString(R.string.agent_icon_description, pane.agent)
            paneMeta.text = text.secondary.orEmpty()
            paneMeta.typeface = Typeface.MONOSPACE
            paneMeta.isVisible = text.secondary != null
            paneHint.text = pane.hint.orEmpty()
            paneHint.isVisible = pane.hint?.isNotBlank() == true
            paneAge.isVisible = false
            statusDot.background = statusDotDrawable(statusDot, pane.status, R.color.collie_surface)
            root.setBackgroundResource(
                if (pane.status == AgentStatus.BLOCKED) R.drawable.bg_collie_attention_blocked else R.drawable.bg_pane_card,
            )
            root.setOnClickListener { onPane(pane) }
            root.contentDescription = root.resources.getString(
                R.string.pane_agent_accessibility,
                text.primary,
                pane.agent,
            )
        }
    }

    private class EmptyHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(item: SpaceListItem.Empty) {
            text.setText(item.messageRes)
            val centered = item.messageRes == R.string.space_view_no_panes_space
            text.gravity = if (centered) android.view.Gravity.CENTER else android.view.Gravity.START
            text.setPadding(text.dp(16), text.dp(if (centered) 32 else 4), text.dp(16), text.dp(16))
        }
    }

    private object Diff : DiffUtil.ItemCallback<SpaceListItem>() {
        override fun areItemsTheSame(oldItem: SpaceListItem, newItem: SpaceListItem): Boolean = when {
            oldItem is SpaceListItem.Overview && newItem is SpaceListItem.Overview ->
                oldItem.content.workspace.workspaceId == newItem.content.workspace.workspaceId
            oldItem is SpaceListItem.Tab && newItem is SpaceListItem.Tab -> oldItem.tab.tabId == newItem.tab.tabId
            oldItem is SpaceListItem.Pane && newItem is SpaceListItem.Pane -> oldItem.pane.paneId == newItem.pane.paneId
            oldItem is SpaceListItem.Empty && newItem is SpaceListItem.Empty -> oldItem.tabId == newItem.tabId
            else -> false
        }

        override fun areContentsTheSame(oldItem: SpaceListItem, newItem: SpaceListItem): Boolean = oldItem == newItem
    }

    private companion object {
        const val TYPE_OVERVIEW = 0
        const val TYPE_TAB = 1
        const val TYPE_PANE = 2
        const val TYPE_EMPTY = 3
    }
}

internal fun statusDotDrawable(
    view: View,
    status: AgentStatus,
    @ColorRes surface: Int,
): GradientDrawable {
    val colour = when (status) {
        AgentStatus.BLOCKED -> R.color.collie_blocked
        AgentStatus.WORKING -> R.color.collie_working
        AgentStatus.DONE -> R.color.collie_done
        AgentStatus.IDLE -> R.color.collie_idle
        AgentStatus.UNKNOWN -> R.color.collie_unknown
    }
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val resolved = ContextCompat.getColor(view.context, colour)
        if (status == AgentStatus.IDLE || status == AgentStatus.UNKNOWN) {
            setColor(ContextCompat.getColor(view.context, surface))
            setStroke(view.resources.displayMetrics.density.times(1.5f).toInt().coerceAtLeast(1), resolved)
        } else {
            setColor(resolved)
        }
    }
}

private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
