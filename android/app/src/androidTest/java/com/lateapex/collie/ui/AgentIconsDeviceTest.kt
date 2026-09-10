package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentIconsDeviceTest {
    @Test
    fun opencodeBlockMarkPaintsItsThreeOfficialColoursOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = requireNotNull(
            AppCompatResources.getDrawable(context, R.drawable.ic_agent_opencode),
        )
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))

        assertEquals(Color.rgb(0x13, 0x10, 0x10), bitmap.getPixel(16, 48))
        assertEquals(Color.WHITE, bitmap.getPixel(31, 48))
        assertEquals(Color.rgb(0x5A, 0x58, 0x58), bitmap.getPixel(48, 48))
    }
}
