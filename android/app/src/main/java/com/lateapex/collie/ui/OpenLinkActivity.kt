package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** One external dispatcher. The authority-free OAuth callback is a terminal, separate branch. */
class OpenLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val action = intent.action
        val raw = intent.dataString
        val shared = if (action == Intent.ACTION_SEND && intent.type == "text/plain")
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.takeIf { it.length <= LinkImportCoordinator.MAX_LINK_CHARS }?.toString() }.getOrNull() else null
        intent.data = null
        intent.clipData = null
        intent.replaceExtras(Bundle())
        super.onCreate(savedInstanceState)
        if (action == Intent.ACTION_VIEW && raw != null && raw.length <= 8192 &&
            runCatching { java.net.URI(raw).let { it.scheme == "com.lateapex.collie" && it.rawAuthority == null && it.rawPath == "/oauth2redirect" && it.rawFragment == null } }.getOrDefault(false)) {
            val actor = (application as com.lateapex.collie.CollieApplication).container.repository.readContext()
            val id = ProxyCallbackHandoff.put(raw, actor)
            // Remove private external data immediately; never forward a callback URI as an extra.
            intent.data = null
            startActivity(Intent(this, ProxySignInActivity::class.java).putExtra(ProxySignInActivity.EXTRA_CALLBACK_ID, id))
        } else if (raw?.startsWith("com.lateapex.collie:", ignoreCase = true) != true) {
            val app = application as com.lateapex.collie.CollieApplication
            val owner = LinkImportOwner.capture(app)
            val text = when (action) { Intent.ACTION_VIEW -> raw; Intent.ACTION_SEND -> shared; else -> null }
            val candidate = if (owner != null && text != null) LinkImportCoordinator.parse(text, owner.connection) else null
            if (candidate != null && owner != null && owner.current(app)) {
                val id = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
                startActivity(LinkImportActivity.intent(this).putExtra(LinkImportHandoff.EXTRA_ID, id))
            }
        }
        finish()
    }
}
