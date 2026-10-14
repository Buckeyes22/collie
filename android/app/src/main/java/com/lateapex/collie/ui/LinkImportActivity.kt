package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local import preview. Only an explicit Continue produces a guarded native navigation handoff. */
class LinkImportActivity : AppCompatActivity() {
    private val app get() = application as CollieApplication
    private var owner: LinkImportOwner? = null
    private var prepared: LinkImportHandoff.Prepared? = null
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var continueButton: MaterialButton
    private var retirement: AutoCloseable? = null
    private var decodeJob: Job? = null
    private var generation = 0L
    private var qrRequestActive = false
    private var clearingInput = false
    @androidx.annotation.VisibleForTesting internal var qrDecoderForTests: (suspend (android.net.Uri) -> String)? = null
    private val qrPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val original = owner
        val admitted = qrRequestActive && original?.current(app) == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        qrRequestActive = false
        if (uri != null && admitted && original != null) {
            val ticket = ++generation
            status.setText(R.string.link_import_loading_qr)
            decodeJob?.cancel()
            decodeJob = lifecycleScope.launch {
                val raw = try {
                    val testDecoder = qrDecoderForTests
                    if (testDecoder != null) testDecoder(uri) else LinkImportQr.read(contentResolver, uri)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (ticket == generation && original === owner && original.current(app) &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    if (raw == null) refuse() else review(raw)
                }
            }
        } else if (uri != null) expire()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never allow incoming raw data/extras to enter view state or a recreation bundle.
        intent.data = null
        intent.clipData = null
        val incoming = LinkImportHandoff.consumePreview(intent, app)
        intent.replaceExtras(Bundle())
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.prepareEdgeToEdgeContent()
        owner = incoming?.owner ?: LinkImportOwner.capture(app)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16); setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply { setText(R.string.link_import_title); textSize = 22f })
        input = EditText(this).apply {
            id = R.id.link_import_input; isSaveEnabled = false
            setHint(R.string.link_import_input_hint); contentDescription = getString(R.string.link_import_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(android.text.InputFilter.LengthFilter(LinkImportCoordinator.MAX_LINK_CHARS))
            minHeight = dp(48)
        }
        root.addView(input, row())
        status = TextView(this).apply { id = R.id.link_import_status; isSaveEnabled = false; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status, row())
        root.addView(button(R.string.link_import_paste_review, R.id.link_import_review) { review(input.text.toString()) }, row())
        root.addView(button(R.string.link_import_choose_qr, R.id.link_import_qr) {
            if (owner?.current(app) != true) { expire(); return@button }
            clearPreview()
            qrRequestActive = true
            qrPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        }, row())
        continueButton = button(R.string.link_import_continue, R.id.link_import_continue, ::continueImport).apply { isEnabled = false }
        input.doAfterTextChanged {
            if (!clearingInput) {
                ++generation; decodeJob?.cancel(); prepared = null
                continueButton.isEnabled = false; status.text = ""
            }
        }
        root.addView(continueButton, row())
        root.addView(button(R.string.link_import_cancel, android.R.id.button2) { clearPreview(); finish() }, row())
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        root.applyAppTypeface()
        retirement = app.container.repository.onReadContextRetired {
            runOnUiThread { owner = null; qrRequestActive = false; expire() }
        }
        if (savedInstanceState == null && incoming != null) present(incoming)
    }

    private fun review(raw: String) {
        val original = owner
        clearPreview()
        if (original?.current(app) != true) { expire(); return }
        val candidate = LinkImportCoordinator.parse(raw, original.connection)
        if (candidate == null) refuse() else present(LinkImportHandoff.Prepared(candidate, original))
    }

    private fun present(value: LinkImportHandoff.Prepared) {
        if (!value.owner.current(app)) { expire(); return }
        prepared = value
        status.text = getString(when (value.candidate) {
            is LinkImportCoordinator.Candidate.Pairing -> R.string.link_import_ready_pairing
            is LinkImportCoordinator.Candidate.Navigation -> R.string.link_import_ready_navigation
        }, value.candidate.origin.value)
        continueButton.isEnabled = true
    }

    private fun continueImport() {
        val value = prepared ?: return
        if (!value.owner.current(app)) { expire(); return }
        val id = LinkImportHandoff.putConfirmed(value)
        clearPreview()
        try { startActivity(Intent(this, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, id)); finish() }
        catch (_: RuntimeException) { LinkImportHandoff.remove(id); expire() }
    }

    private fun clearPreview() {
        prepared = null
        if (::input.isInitialized) {
            clearingInput = true
            try { input.text.clear() } finally { clearingInput = false }
        }
        if (::continueButton.isInitialized) continueButton.isEnabled = false
    }
    private fun refuse() { clearPreview(); status.setText(R.string.link_import_refused) }
    private fun expire() { ++generation; decodeJob?.cancel(); clearPreview(); if (::status.isInitialized) status.setText(R.string.link_import_expired) }

    override fun onStop() { ++generation; decodeJob?.cancel(); clearPreview(); super.onStop() }
    override fun onDestroy() { qrRequestActive = false; retirement?.close(); retirement = null; decodeJob?.cancel(); clearPreview(); super.onDestroy() }

    private fun button(text: Int, viewId: Int, action: () -> Unit) = MaterialButton(this).apply {
        id = viewId; setText(text); isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { fun intent(context: Context) = Intent(context, LinkImportActivity::class.java) }
}
