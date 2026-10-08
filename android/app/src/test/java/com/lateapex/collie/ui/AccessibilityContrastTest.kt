package com.lateapex.collie.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.view.ContextThemeWrapper
import com.lateapex.collie.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
class AccessibilityContrastTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun semanticTextAndButtonPairsMeetContrastInLightAndDarkThemes() {
        for (night in listOf(false, true)) {
            val context = themedContext(night)
            val background = color(context, R.color.collie_background)
            val card = color(context, R.color.collie_card)
            val primary = color(context, R.color.collie_primary)
            val destructive = color(context, R.color.collie_destructive)

            assertContrast(context, "main text on background", R.color.collie_foreground, background, 4.5)
            assertContrast(context, "main text on card", R.color.collie_foreground, card, 4.5)
            assertContrast(context, "muted text on card", R.color.collie_muted, card, 4.5)
            assertContrast(context, "button text on primary", R.color.collie_on_primary, primary, 4.5)
            assertContrast(context, "button text on destructive", R.color.collie_on_destructive, destructive, 4.5)
            for (status in listOf(
                R.color.collie_blocked,
                R.color.collie_working,
                R.color.collie_done,
                R.color.collie_idle,
                R.color.collie_unknown,
                R.color.collie_info,
            )) {
                assertContrast(context, "semantic status on card", status, card, 4.5)
            }
            assertContrast(context, "status ring on card", R.color.collie_ring, card, 3.0)
        }
    }

    private fun themedContext(night: Boolean): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        return ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_Collie)
    }

    private fun color(context: Context, id: Int): Int = context.getColor(id)

    private fun assertContrast(context: Context, label: String, foreground: Int, background: Int, minimum: Double) {
        val ratio = contrastRatio(color(context, foreground), background)
        val mode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertTrue(
            "$label in ${if (mode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"} theme: $ratio < $minimum",
            ratio >= minimum,
        )
    }

    private fun contrastRatio(foreground: Int, background: Int): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }
}
