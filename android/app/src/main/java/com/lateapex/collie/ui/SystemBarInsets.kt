package com.lateapex.collie.ui

import android.content.res.Configuration
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import kotlin.math.max

/**
 * Makes edge-to-edge layout explicit, then keeps interactive content inside system bars, cutouts,
 * and the on-screen keyboard. The initial padding is captured once so repeated inset dispatches do
 * not accumulate padding as bars or keyboard visibility change.
 */
internal fun Window.prepareEdgeToEdgeContent() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
}

internal fun Window.applySafeContentInsets(root: View) {
    val lightTheme = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(this, root).apply {
        isAppearanceLightNavigationBars = lightTheme
        isAppearanceLightStatusBars = lightTheme
    }
    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safeDrawing = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(
            initialLeft + safeDrawing.left,
            initialTop + safeDrawing.top,
            initialRight + safeDrawing.right,
            initialBottom + max(safeDrawing.bottom, keyboard.bottom),
        )
        insets
    }
    root.doOnAttach { ViewCompat.requestApplyInsets(it) }
}
