package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.OperatorFontRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class PreferredTypefaceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearChoice() {
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(NativePreferences.UI_TYPEFACE)
            .remove(NativePreferences.OPERATOR_TYPEFACE_FAMILY)
            .remove(NativePreferences.OPERATOR_TYPEFACE_BASENAME)
            .remove(NativePreferences.OPERATOR_TYPEFACE_OWNER)
            .commit()
        PreferredTypeface.clearLoadedOperator()
    }

    @Test
    fun defaultsToAldrichAndPersistsSystemChoice() {
        assertEquals(NativePreferences.UiTypeface.ALDRICH, PreferredTypeface.selected(context))
        assertEquals(ResourcesCompat.getFont(context, R.font.collie_ui), PreferredTypeface.current(context))

        PreferredTypeface.select(context, NativePreferences.UiTypeface.SYSTEM)

        assertEquals(NativePreferences.UiTypeface.SYSTEM, PreferredTypeface.selected(context))
        assertEquals(Typeface.create("sans-serif", Typeface.NORMAL), PreferredTypeface.current(context))
        assertEquals(NativePreferences.UiTypeface.SYSTEM, PreferredTypeface.selected(ApplicationProvider.getApplicationContext()))

        PreferredTypeface.select(context, NativePreferences.UiTypeface.GROTESK)
        assertEquals(NativePreferences.UiTypeface.GROTESK, PreferredTypeface.selected(context))
        assertEquals(ResourcesCompat.getFont(context, R.font.space_grotesk_regular), PreferredTypeface.current(context))
    }

    @Test
    fun changingChromeFaceKeepsSelectableOperatorContentAndMonoTextUntouched() {
        val root = LinearLayout(context)
        val chrome = TextView(context).apply { text = "Settings" }
        val operatorContent = TextView(context).apply {
            text = "prompt text"
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setTextIsSelectable(true)
        }
        val mono = TextView(context).apply {
            id = R.id.terminal_text
            typeface = Typeface.MONOSPACE
        }
        root.addView(chrome)
        root.addView(operatorContent)
        root.addView(mono)

        PreferredTypeface.select(context, NativePreferences.UiTypeface.SYSTEM)
        root.applyAppTypeface()

        assertEquals(Typeface.create("sans-serif", Typeface.NORMAL), chrome.typeface)
        assertEquals(Typeface.create("serif", Typeface.NORMAL), operatorContent.typeface)
        assertEquals(Typeface.MONOSPACE, mono.typeface)
        assertNotEquals(chrome.typeface, ResourcesCompat.getFont(context, R.font.collie_ui))
        assertTrue(operatorContent.isTextSelectable)
    }

    @Test
    fun displaySettingsExposeTheStoredTypefaceChoice() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val display = LinearLayout(activity)
        val preferences = NativePreferences(activity)
        preferences.uiTypeface = NativePreferences.UiTypeface.SYSTEM

        SettingsLocalPreferences(activity, preferences).bindDisplay(display)

        val selector = display.findViewById<android.widget.Button>(R.id.settings_ui_typeface_value)
        assertEquals(activity.getString(R.string.settings_ui_typeface_system), selector.text.toString())
        selector.performClick()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val entries = sheet.findViewById<FrameLayout>(R.id.collie_sheet_content)?.getChildAt(0) as ViewGroup
        assertEquals(3, entries.childCount)
        (entries.getChildAt(1) as MaterialButton).performClick()
        assertEquals(NativePreferences.UiTypeface.GROTESK, preferences.uiTypeface)
        assertEquals(activity.getString(R.string.settings_ui_typeface_grotesk), selector.text.toString())
    }

    @Test
    fun operatorChoiceIsOfferedOnlyFromTheCurrentOwnerRowsAndEmitsTheExactRow() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val display = LinearLayout(activity)
        val preferences = NativePreferences(activity)
        val row = OperatorFontRow("Studio Face", "studio-face.woff2", "350 750")
        var selected: OperatorFontRow? = null
        val settings = SettingsLocalPreferences(activity, preferences).apply {
            setOnOperatorTypefaceSelected { selected = it }
            updateOperatorFonts(listOf(row, OperatorFontRow("Bad", "../bad.woff2")), "a".repeat(64), loading = false)
        }
        settings.bindDisplay(display)

        val selector = display.findViewById<android.widget.Button>(R.id.settings_ui_typeface_value)
        selector.performClick()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val entries = sheet.findViewById<FrameLayout>(R.id.collie_sheet_content)?.getChildAt(0) as ViewGroup
        assertEquals(4, entries.childCount)
        (entries.getChildAt(3) as MaterialButton).performClick()
        assertEquals(row, selected)
        assertEquals(null, preferences.operatorTypefaceSelection)

        settings.updateOperatorFonts(listOf(row), "b".repeat(64), loading = false)
        assertEquals(activity.getString(R.string.settings_ui_typeface_aldrich), selector.text.toString())
    }

    @Test
    fun operatorSelectionPersistsOwnerFingerprintWithoutCredentialMaterial() {
        val preferences = NativePreferences(context)
        val fingerprint = "a".repeat(64)
        assertTrue(preferences.selectOperatorTypeface("Studio Face", "studio-face.woff2", fingerprint))
        assertEquals(NativePreferences.OperatorTypefaceSelection("Studio Face", "studio-face.woff2", fingerprint), preferences.operatorTypefaceSelection)
        assertEquals(false, preferences.selectOperatorTypeface("Studio Face", "../secret.woff2", fingerprint))
        preferences.clearOperatorTypeface()
        assertEquals(null, preferences.operatorTypefaceSelection)
    }
}
