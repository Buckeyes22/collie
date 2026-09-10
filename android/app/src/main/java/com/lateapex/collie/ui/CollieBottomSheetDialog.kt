package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lateapex.collie.R

/** Canonical raised-card bottom sheet shell shared by all native action surfaces. */
class CollieBottomSheetDialog(
    context: Context,
    title: CharSequence,
) : BottomSheetDialog(context) {
    private val panel: LinearLayout
    private val titleView: TextView
    private val content: FrameLayout

    init {
        val shell = LayoutInflater.from(context).inflate(
            R.layout.view_collie_bottom_sheet,
            FrameLayout(context),
            false,
        )
        panel = shell.findViewById(R.id.collie_sheet_panel)
        titleView = shell.findViewById(R.id.collie_sheet_title)
        content = shell.findViewById(R.id.collie_sheet_content)
        titleView.text = title
        ViewCompat.setAccessibilityHeading(titleView, true)
        shell.findViewById<ImageButton>(R.id.collie_sheet_close).setOnClickListener { dismiss() }
        super.setContentView(shell)

        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(panel) { _, insets ->
            val safeBottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            ).bottom
            content.setPadding(
                content.paddingLeft,
                content.paddingTop,
                content.paddingRight,
                baseBottom + safeBottom,
            )
            insets
        }
        setOnShowListener {
            window?.setDimAmount(0.5f)
            behavior.maxHeight = (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()
            findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            panel.applyAppTypeface()
            ViewCompat.requestApplyInsets(panel)
        }
    }

    fun setSheetContent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        content.removeAllViews()
        content.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private companion object {
        const val MAX_HEIGHT_FRACTION = 0.82f
    }
}
