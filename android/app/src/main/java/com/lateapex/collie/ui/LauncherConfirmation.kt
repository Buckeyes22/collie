package com.lateapex.collie.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.Launcher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The server marks launchers that skip the agent's permission prompts. */
internal suspend fun confirmLauncher(context: Context, launcher: Launcher, machine: String): Boolean {
    if (!launcher.noPrompts) return true
    return suspendCancellableCoroutine { continuation ->
        val dialog = CollieBottomSheetDialog(context, context.getString(R.string.launch_no_prompts_title))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.launch_no_prompts_body, machine, launcher.command, launcher.cwd.orEmpty())
                setTextIsSelectable(true)
            })
            addView(MaterialButton(context).apply {
                text = context.getString(R.string.launch_no_prompts_start)
                isAllCaps = false
                setOnClickListener {
                    if (continuation.isActive) continuation.resume(true)
                    dialog.dismiss()
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setSheetContent(content)
        dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
        dialog.show()
        // Registration immediately runs for an already cancelled caller, after show.
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
}
