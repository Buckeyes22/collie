package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptBodyViewTest {
    @Test
    fun bindAddsOneViewPerUuidAndReusesOnRebind() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Collie,
        )
        val view = TranscriptBodyView(context)
        val a = TranscriptEntry("a", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart("text", text = "hi")))
        val b = TranscriptEntry("b", "2026-09-10T00:00:01Z", "assistant", listOf(TranscriptPart("text", text = "yo")))
        view.bind("claude", listOf(a), hasMore = false)
        val first = view.turnViewForTest("a")
        view.bind("claude", listOf(a, b), hasMore = true)
        assertSame(first, view.turnViewForTest("a"))
        assertEquals(2, view.turnCountForTest())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.transcript_load_older).visibility)
    }
}
