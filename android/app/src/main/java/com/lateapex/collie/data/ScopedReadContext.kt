package com.lateapex.collie.data

import com.lateapex.collie.domain.Connection

/** A result belongs to the same credential instance and navigation generation that requested it. */
class ScopedReadContext internal constructor(
    val key: ScopedRequestKey,
    internal val connection: Connection,
    internal val navigationGeneration: Long,
) {
    override fun toString(): String = "ScopedReadContext(key=$key)"
}

/** No credentials/content are included in invalidation events. */
enum class AuthorityInvalidation { ACCESS_FROZEN, SCOPE_CHANGED, CONNECTION_CHANGED, LEAD_CHANGED, SAVED_COPY, PAIRING_RETIRED, DISCONNECTED }
