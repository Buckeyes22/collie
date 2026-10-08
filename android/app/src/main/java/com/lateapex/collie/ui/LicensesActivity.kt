package com.lateapex.collie.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lateapex.collie.R

class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        setContentView(R.layout.activity_licenses)
        window.applySafeContentInsets(findViewById(R.id.licenses_scroll))
        title = getString(R.string.licenses_title)
        findViewById<TextView>(R.id.licenses_text).text = NOTICE_RESOURCES.joinToString("\n\n") { id ->
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
