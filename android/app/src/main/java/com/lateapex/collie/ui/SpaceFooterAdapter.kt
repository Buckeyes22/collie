package com.lateapex.collie.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.R

/** Scroll-tail build stamp for the Space route. Updates live in Settings only. */
internal class SpaceFooterAdapter : RecyclerView.Adapter<SpaceFooterAdapter.Holder>() {
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
        val stamp = TextView(context).apply {
            id = R.id.space_build_stamp
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.collie_muted))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = context.getString(R.string.build_stamp, BuildConfig.VERSION_NAME)
        }
        root.addView(stamp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(root)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    internal class Holder(root: View) : RecyclerView.ViewHolder(root)
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
