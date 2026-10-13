package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** B24 callback branch of the single exported B23 link dispatcher. Generic B23 import branches
 * are integrated serially by ROOT; this callback never falls through to a connection import. */
class OpenLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val raw = intent.dataString
        intent.data = null
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW && raw != null && raw.length <= 8192 &&
            runCatching { java.net.URI(raw).let { it.scheme == "com.lateapex.collie" && it.rawAuthority == null && it.rawPath == "/oauth2redirect" && it.rawFragment == null } }.getOrDefault(false)) {
            val actor = (application as com.lateapex.collie.CollieApplication).container.repository.readContext()
            val id = ProxyCallbackHandoff.put(raw, actor)
            // Remove private external data immediately; never forward a callback URI as an extra.
            intent.data = null
            startActivity(Intent(this, ProxySignInActivity::class.java).putExtra(ProxySignInActivity.EXTRA_CALLBACK_ID, id))
        }
        finish()
    }
}
