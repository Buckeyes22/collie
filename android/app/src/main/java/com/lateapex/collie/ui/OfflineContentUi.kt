package com.lateapex.collie.ui

import android.content.Context
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.SavedCopyProvenance
import com.lateapex.collie.data.ScopedCustodyVersion

internal fun Context.savedCopyCaption(copy: SavedCopyProvenance): String {
    val now = System.currentTimeMillis()
    return getString(R.string.offline_saved_copy,
        ((now - copy.capturedAt) / 60_000).coerceAtLeast(0), ((copy.expiresAt - now) / 60_000).coerceAtLeast(0),
        copy.scope.owner.origin, copy.scope.host ?: getString(R.string.offline_local_host),
        copy.scope.session ?: getString(R.string.offline_primary_session))
}

/** Original display owner captured before posting; no actor is minted for a saved copy. */
internal fun withOfflineRetirementOnMain(
    event: AuthorityRetirement, version: () -> ScopedCustodyVersion?, erase: () -> Unit,
) {
    // A temporary write/admission freeze is not retirement of opted read-only display custody.
    // Owner/scope/pairing retirement still erases copies synchronously before ACK returns.
    if (!event.retiresReadActor) return
    val original = version() ?: return
    if (!event.affectsCustody(original)) return
    withMainCustodyAck { if (version() == original && event.affectsCustody(original)) erase() }
}
