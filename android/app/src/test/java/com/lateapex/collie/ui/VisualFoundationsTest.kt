package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualFoundationsTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val context = ContextThemeWrapper(application, R.style.Theme_Collie)

    @Before
    fun clearPreferences() {
        application.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun routeColumnFillsPhonesButCapsWideDisplaysAt640dp() {
        val column = CollieMaxWidthLinearLayout(context)
        val tall = View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.EXACTLY)

        column.measure(View.MeasureSpec.makeMeasureSpec(dp(390), View.MeasureSpec.EXACTLY), tall)
        assertEquals(dp(390), column.measuredWidth)

        column.measure(View.MeasureSpec.makeMeasureSpec(dp(900), View.MeasureSpec.EXACTLY), tall)
        assertEquals(dp(640), column.measuredWidth)
    }

    @Test
    fun paneAndHistoryColumnFillsPhonesButCapsWideDisplaysAt768dp() {
        val column = CollieWideMaxWidthLinearLayout(context)
        val history = HistoryMaxWidthFrameLayout(context)
        val tall = View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.EXACTLY)

        column.measure(View.MeasureSpec.makeMeasureSpec(dp(390), View.MeasureSpec.EXACTLY), tall)
        history.measure(View.MeasureSpec.makeMeasureSpec(dp(390), View.MeasureSpec.EXACTLY), tall)
        assertEquals(dp(390), column.measuredWidth)
        assertEquals(dp(390), history.measuredWidth)

        column.measure(View.MeasureSpec.makeMeasureSpec(dp(900), View.MeasureSpec.EXACTLY), tall)
        history.measure(View.MeasureSpec.makeMeasureSpec(dp(900), View.MeasureSpec.EXACTLY), tall)
        assertEquals(dp(768), column.measuredWidth)
        assertEquals(dp(768), history.measuredWidth)
    }

    @Test
    fun defaultAldrichNeverSynthesizesBoldAndReachesViewsAddedLater() {
        val root = LinearLayout(context)
        val initial = TextView(context).apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(initial)
        root.applyAppTypeface()
        assertEquals(Typeface.NORMAL, initial.typeface.style)

        val dynamic = TextView(context).apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(dynamic)
        root.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(Typeface.NORMAL, dynamic.typeface.style)
    }

    @Test
    fun hostPaletteHasTenStableWrappingSlots() {
        assertEquals(10, HostPalette.SIZE)
        assertEquals(R.color.collie_host_0, HostPalette.resource(0))
        assertEquals(R.color.collie_host_9, HostPalette.resource(9))
        assertEquals(R.color.collie_host_0, HostPalette.resource(10))
        assertEquals(R.color.collie_host_9, HostPalette.resource(-1))
        assertTrue(HostPalette.resource(4) != HostPalette.resource(5))
    }

    @Test
    fun canonicalSheetExposesAHeadingAndOwnsInsertedContent() {
        val dialog = CollieBottomSheetDialog(context, "Actions")
        val inserted = TextView(context).apply { text = "Choice" }
        dialog.setSheetContent(inserted)

        val title = dialog.findViewById<TextView>(R.id.collie_sheet_title)
        assertEquals("Actions", title?.text)
        assertTrue(title != null && ViewCompat.isAccessibilityHeading(title))
        assertEquals(R.id.collie_sheet_content, (inserted.parent as View).id)
        val close = requireNotNull(dialog.findViewById<ImageButton>(R.id.collie_sheet_close))
        assertEquals(dp(44), close.layoutParams.width)
        assertEquals(dp(44), close.layoutParams.height)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
