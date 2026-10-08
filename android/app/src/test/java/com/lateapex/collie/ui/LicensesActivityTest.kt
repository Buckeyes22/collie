package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.EncryptedConnectionStore
import androidx.core.view.ViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicensesActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        (context.applicationContext as CollieApplication).container.connectionStore.clear()
    }

    @Test
    fun showsEveryPackagedNotice() {
        val activity = Robolectric.buildActivity(LicensesActivity::class.java).setup().get()
        val view = activity.findViewById<TextView>(R.id.licenses_text)
        val text = view.text.toString()
        assertTrue(text.contains("MIT License"))
        assertTrue(text.contains("Copyright 2024 Block, Inc."))
        assertTrue(text.contains("com.squareup.okhttp3:okhttp"))
        assertTrue(text.contains("SIL OPEN FONT LICENSE"))
        assertTrue(view.isTextSelectable)
    }

    @Test
    fun showsAVisibleHeadingThatTalkBackAnnouncesAsOne() {
        val activity = Robolectric.buildActivity(LicensesActivity::class.java).setup().get()
        val title = activity.findViewById<TextView>(R.id.licenses_title)
        assertEquals(View.VISIBLE, title.visibility)
        assertEquals("Open-source licenses", title.text.toString())
        assertTrue(ViewCompat.isAccessibilityHeading(title))
        assertTrue(title.isAccessibilityHeading)
    }

    @Test
    fun rendersTheNoticesInMonospace() {
        val activity = Robolectric.buildActivity(LicensesActivity::class.java).setup().get()
        val view = activity.findViewById<TextView>(R.id.licenses_text)
        assertEquals(Typeface.MONOSPACE, view.typeface)
    }

    @Test
    fun settingsOpensTheLicensesScreen() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()
        shadowOf(settings.mainLooper).idle()
        settings.findViewById<View>(R.id.settings_licenses_button).performClick()
        val next: Intent = shadowOf(settings).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, next.component?.className)
    }
}
