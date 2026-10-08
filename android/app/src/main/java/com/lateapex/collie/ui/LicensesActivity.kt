package com.lateapex.collie.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lateapex.collie.R

class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        setContentView(R.layout.activity_licenses)
        window.applySafeContentInsets(findViewById(R.id.licenses_root))
        title = getString(R.string.licenses_title)
        findViewById<View>(R.id.licenses_back_button).setOnClickListener { finish() }
        val licensesText = findViewById<TextView>(R.id.licenses_text)
        // The notices are fixed-width text; the theme's face must not replace it.
        licensesText.typeface = Typeface.MONOSPACE
        licensesText.text = NOTICE_RESOURCES.joinToString("\n\n") { id ->
            resources.openRawResource(id).bufferedReader().use { it.readText() }.trimEnd()
        }
    }

    internal companion object {
        val NOTICE_RESOURCES = listOf(
            R.raw.license_collie,
            R.raw.third_party_notices,
            R.raw.dependency_notices,
            R.raw.license_aldrich,
            R.raw.license_jetbrains_mono,
        )
    }
}
