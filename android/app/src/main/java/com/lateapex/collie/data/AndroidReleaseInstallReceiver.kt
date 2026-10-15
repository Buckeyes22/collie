package com.lateapex.collie.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives only the explicit PendingIntent capability issued to Android for one session. */
class AndroidReleaseInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RESULT_ACTION || intent.data?.scheme != "collie-install") return
        val attemptId = intent.data?.schemeSpecificPart?.takeIf(OPERATION_ID::matches) ?: return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0 || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AndroidReleaseRepository(context.applicationContext).recordInstallerSessionResult(
                    attemptId, sessionId, status, confirmation,
                )
            } finally { pending.finish() }
        }
    }

    companion object {
        internal const val RESULT_ACTION = "com.lateapex.collie.ANDROID_INSTALL_RESULT"
        private val OPERATION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}
