package com.lateapex.collie.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.FoldersResponse

internal data class FolderChoice(val value: String?, val label: String, val favourite: Boolean)

internal object FolderPickerModel {
    fun choices(response: FoldersResponse): List<FolderChoice> = buildList {
        add(FolderChoice(null, response.home, false))
        response.favourites.forEach { add(FolderChoice(it, it, true)) }
        response.recent.forEach { add(FolderChoice(it, it, false)) }
    }
}

/** Host-owned choices only. This view never probes a phone path or turns a folder into a file read. */
internal class FolderPickerView(
    context: Context,
    private val onSelect: (String?) -> Unit,
    private val onStar: (String, Boolean) -> Unit,
) : LinearLayout(context) {
    private var folders: FoldersResponse? = null
    private var selected: String? = null
    private var canStar = false

    init { orientation = VERTICAL }

    fun render(response: FoldersResponse?, selectedFolder: String?, allowStar: Boolean = true) {
        folders = response
        selected = selectedFolder
        canStar = allowStar
        removeAllViews()
        if (response == null) {
            addView(label(context.getString(R.string.folder_list_unavailable)))
            return
        }
        addView(label(context.getString(R.string.folder_picker_home)))
        addView(folderRow(null, response.home, starred = false))
        addGroup(context.getString(R.string.folder_favourites), response.favourites, starred = true)
        addGroup(context.getString(R.string.folder_recent), response.recent, starred = false)
    }

    private fun addGroup(title: String, paths: List<String>, starred: Boolean) {
        addView(label(title))
        if (paths.isEmpty()) {
            addView(label(context.getString(R.string.folder_list_empty)))
        } else paths.forEach { path -> addView(folderRow(path, path, starred)) }
    }

    private fun folderRow(value: String?, visiblePath: String, starred: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val choose = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = visiblePath
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            contentDescription = context.getString(R.string.folder_select_accessibility, visiblePath)
            setTextColor(ContextCompat.getColor(context, R.color.collie_foreground))
            setOnClickListener { selected = value; onSelect(value) }
        }
        addView(choose, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = context.getString(if (starred) R.string.folder_unstar else R.string.folder_star)
                contentDescription = context.getString(if (starred) R.string.folder_unstar_accessibility else R.string.folder_star_accessibility, visiblePath)
                isAllCaps = false
                minHeight = dp(48)
                isEnabled = canStar
                setOnClickListener { onStar(value, !starred) }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            })
        }
        isSelected = value == selected
    }

    private fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.collie_muted))
        setPadding(dp(4), dp(6), dp(4), dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
