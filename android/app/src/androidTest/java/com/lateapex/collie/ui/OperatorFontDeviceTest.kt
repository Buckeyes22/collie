package com.lateapex.collie.ui

import android.graphics.Typeface
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorFontDeviceTest {
    @Test fun officialWoff2LoadsWithNativeFontBuilder() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("fonts/operator-space.woff2").use { it.readBytes() }
        val face = OperatorWoff2Codec.build(InstrumentationRegistry.getInstrumentation().targetContext, com.lateapex.collie.network.OperatorFontRow("Acceptance Space", "operator-space.woff2"), bytes)
        val paint = Paint().apply { typeface = face; textSize = 24f }
        assertTrue("Native WOFF2 must render admitted operator content", paint.measureText("Collie operator face") > 0f)
        assertTrue("Typeface.Builder must return a decoded font", face != null)
    }
}
