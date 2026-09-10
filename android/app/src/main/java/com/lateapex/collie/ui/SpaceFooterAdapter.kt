package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.R
import com.lateapex.collie.network.UpdateInfo

internal enum class FooterUpdateNoticeKind { RESTART, RELEASE, MAJOR }

internal data class FooterUpdateNotice(
    val kind: FooterUpdateNoticeKind,
    val version: String? = null,
) {
    fun text(context: Context): String = when (kind) {
        FooterUpdateNoticeKind.RESTART -> context.getString(R.string.settings_update_restart_needed)
        FooterUpdateNoticeKind.RELEASE -> context.getString(R.string.settings_update_available_short, version)
        FooterUpdateNoticeKind.MAJOR -> context.getString(R.string.settings_update_major, version)
    }
}

/** Shared web-order footer notice used by both Dashboard and Space. */
internal object FooterUpdateNoticeModel {
    fun from(update: UpdateInfo?): FooterUpdateNotice? = when {
        update?.bridgeStale == true -> FooterUpdateNotice(FooterUpdateNoticeKind.RESTART)
        update?.releaseAvailable == true && !update.latest.isNullOrBlank() ->
            FooterUpdateNotice(FooterUpdateNoticeKind.RELEASE, update.latest)
        !update?.majorAvailable.isNullOrBlank() ->
            FooterUpdateNotice(FooterUpdateNoticeKind.MAJOR, update?.majorAvailable)
        else -> null
    }
}

/** Scroll-tail parity for the web Space route's UpdateBanner followed by BuildStamp. */
internal class SpaceFooterAdapter(
    private val openUpdates: () -> Unit,
) : RecyclerView.Adapter<SpaceFooterAdapter.Holder>() {
    private var update: UpdateInfo? = null

    fun submit(value: UpdateInfo?) {
        if (value == update) return
        update = value
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val root = LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val notice = TextView(context).apply {
            id = R.id.space_update_notice
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(ContextCompat.getColor(context, R.color.collie_working))
            textSize = 11f
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_dashboard_shell_working)
        }
        val stamp = TextView(context).apply {
            id = R.id.space_build_stamp
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.collie_muted))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = context.getString(R.string.build_stamp, BuildConfig.VERSION_NAME)
        }
        root.addView(notice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(stamp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(root, notice, openUpdates)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(update)

    internal class Holder(
        root: View,
        private val notice: TextView,
        private val openUpdates: () -> Unit,
    ) : RecyclerView.ViewHolder(root) {
        fun bind(update: UpdateInfo?) {
            val text = FooterUpdateNoticeModel.from(update)?.text(itemView.context).orEmpty()
            notice.text = text
            notice.contentDescription = text.takeIf(String::isNotEmpty)?.let { "$it. Open Updates." }
            notice.isVisible = text.isNotEmpty()
            notice.setOnClickListener(if (text.isNotEmpty()) View.OnClickListener { openUpdates() } else null)
        }
    }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
