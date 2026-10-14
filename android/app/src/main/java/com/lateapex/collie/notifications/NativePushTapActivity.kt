package com.lateapex.collie.notifications

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.ui.MainActivity
import com.lateapex.collie.ui.PaneActivity
import com.lateapex.collie.ui.UpdatesActivity

/** No exported deep link and no mutation: stale or retired enrollment returns to the paired home. */
class NativePushTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CollieApplication).container
        val target = container.nativePush.tap(intent.getStringExtra("instance"), intent.getStringExtra("payload"))
        if (target is NativePushPayload.Target.Home) container.repository.selectScope(target.scope)
        val next = when (target) {
            is NativePushPayload.Target.Pane -> Intent(this, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, target.address.paneId)
                .putExtra(PaneActivity.EXTRA_HOST, target.address.scope.host)
                .putExtra(PaneActivity.EXTRA_SESSION, target.address.scope.session)
            NativePushPayload.Target.Updates -> Intent(this, UpdatesActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
        }
        startActivity(next); finish()
    }
}
