package com.lateapex.collie.data

import com.lateapex.collie.domain.Scope

/** Contains lightweight authority identity only; never a bearer or content body. */
data class ScopedRequestKey(
    val origin: String,
    val connectionGeneration: Long,
    val pairedDeviceIdentity: String,
    val leadEpoch: Long,
    val host: String?,
    val session: String?,
    val subjectIdentity: String?,
) {
    val scope: Scope get() = Scope(host, session)
}
