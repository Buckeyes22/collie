package com.lateapex.collie.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.lateapex.collie.R

/** One sentence-case status vocabulary shared by the pane dot, the pane label, and (via the
 * same colour resource names) the dashboard ring and space chip. */
object PaneStatusVocabulary {
    fun label(resources: Resources, agent: String?, status: String?): String = when {
        agent.equals("shell", ignoreCase = true) -> resources.getString(R.string.pane_status_shell)
        status == "working" -> resources.getString(R.string.pane_status_working)
        status == "blocked" -> resources.getString(R.string.pane_status_needs_input)
        status == "done" -> resources.getString(R.string.pane_status_ready)
        else -> resources.getString(R.string.pane_status_idle)
    }

    @ColorInt
    fun colour(context: Context, status: String?): Int = ContextCompat.getColor(
        context,
        when (status) {
            "working" -> R.color.collie_working
            "blocked" -> R.color.collie_blocked
            "done" -> R.color.collie_done
            else -> R.color.collie_idle
        },
    )
}
