package com.lateapex.collie.domain

/** A validated Collie origin. The value is always HTTPS and always ends in '/'. */
@JvmInline
value class CollieOrigin(val value: String) {
    init {
        require(value.startsWith("https://") && value.endsWith('/'))
    }

    /** RFC 6454 serialization used by Collie's write gate (no trailing slash). */
    val headerValue: String get() = value.dropLast(1)
}

/**
 * One locally configured Collie. Deliberately not a data class: generated toString/copy methods
 * make it too easy to leak a bearer into logs or saved state.
 */
class Connection(
    val origin: CollieOrigin,
    val label: String,
    val token: String?,
) {
    val isPaired: Boolean get() = !token.isNullOrBlank()

    override fun toString(): String =
        "Connection(origin=${origin.value}, label=$label, token=${if (isPaired) "<redacted>" else "null"})"
}

data class Scope(
    val host: String? = null,
    val session: String? = null,
    /** Dashboard-only breadth; it widens snapshot reads but is never sent with writes. */
    val viewAll: Boolean = false,
) {
    init {
        require(host?.isNotBlank() != false)
        require(session?.isNotBlank() != false)
        require(!viewAll || session == null)
    }
}

data class PaneAddress(
    val scope: Scope = Scope(),
    val paneId: String,
) {
    init {
        require(paneId.isNotBlank())
    }
}
