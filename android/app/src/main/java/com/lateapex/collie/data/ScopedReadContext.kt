package com.lateapex.collie.data

import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope

/** A result belongs to the same credential instance and navigation generation that requested it. */
class ScopedReadContext internal constructor(
    val key: ScopedRequestKey,
    internal val connection: Connection,
    internal val navigationGeneration: Long,
    /** Preserve dashboard breadth separately from the host/session authority key. */
    internal val requestScope: Scope = key.scope,
    /** Original immutable durable witness, never reacquired after a transport/readiness wait. */
    internal val connectionIncarnation: String? = null,
    internal val committedStore: Boolean = false,
    internal val committedSnapshot: CommittedConnectionSnapshot? = null,
    internal val custodyProcessIdentity: String = ReadCustodyProcess.identity,
) {
    override fun toString(): String = "ScopedReadContext(key=$key)"
}

/** Public retirement facts exclude credentials; the original Connection stays private for identity checks. */
enum class AuthorityInvalidation { ACCESS_FROZEN, SCOPE_CHANGED, CONNECTION_CHANGED, LEAD_CHANGED, SAVED_COPY, PAIRING_RETIRED, DISCONNECTED }

/** Durable local custody identity. It contains no bearer or copied server content. */
@kotlinx.serialization.Serializable
data class ScopedCustodyOwner(val origin: String, val mount: String, val pairedDeviceIdentity: String) {
    val storageIdentity: String get() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (value in listOf(origin, mount, pairedDeviceIdentity)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString(): String = "ScopedCustodyOwner(<redacted>)"
}

val ScopedReadContext.custodyOwner: ScopedCustodyOwner
    get() = ScopedCustodyOwner(key.origin, key.origin, key.pairedDeviceIdentity)

/** Encrypted local copy owner. Process generations fence live publication, never wire authority. */
@kotlinx.serialization.Serializable
data class ScopedCustodyVersion(val owner: ScopedCustodyOwner, val connectionIncarnation: String?,
    val connectionGeneration: Long, val navigationGeneration: Long, val processIdentity: String? = null) {
    override fun toString(): String = "ScopedCustodyVersion(<redacted>)"
}
private object ReadCustodyProcess { val identity: String = java.util.UUID.randomUUID().toString() }
internal val currentReadCustodyProcessIdentity: String get() = ReadCustodyProcess.identity
val ScopedReadContext.custodyVersion: ScopedCustodyVersion
    get() = ScopedCustodyVersion(custodyOwner, connectionIncarnation, key.connectionGeneration, navigationGeneration, custodyProcessIdentity)

/** Safe original refusal facts only: no response message, content, or credential custody. */
data class AuthorityRefusal internal constructor(
    val status: Int,
    val source: com.lateapex.collie.network.FailureSource,
    val factor: com.lateapex.collie.network.FailureFactor,
    val code: String?,
) {
    override fun toString(): String = "AuthorityRefusal(status=$status, source=$source, factor=$factor)"

    companion object {
        internal fun from(failure: com.lateapex.collie.network.ApiFailure?): AuthorityRefusal? {
            val http = failure as? com.lateapex.collie.network.ApiFailure.Http ?: return null
            val code = http.code?.takeIf { it.length in 1..128 && it.all { char ->
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "._-"
            } }
            return AuthorityRefusal(http.status, http.source, http.factor, code)
        }
    }
}

/** Captured under admission at transition entry, never sampled again when a callback runs. */
class AuthorityRetirement internal constructor(
    val reason: AuthorityInvalidation,
    val owner: ScopedCustodyOwner?,
    val retiresReadActor: Boolean,
    private val connection: Connection?,
    private val incarnation: String?,
    private val generation: Long,
    private val navigation: Long,
    private val processIdentity: String? = ReadCustodyProcess.identity,
    val refusal: AuthorityRefusal? = null,
) {
    /** Pure original-copy predicate; a pending fence does not require fresh actor allocation. */
    fun affects(context: ScopedReadContext?): Boolean = context != null &&
        context.custodyProcessIdentity == processIdentity &&
        context.connection === connection && context.connectionIncarnation == incarnation &&
        context.key.connectionGeneration == generation && context.navigationGeneration <= navigation

    fun affectsCustody(version: ScopedCustodyVersion?): Boolean = version != null && version.owner == owner &&
        version.connectionIncarnation == incarnation &&
        ((incarnation != null && reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)) ||
            (processIdentity != null && version.processIdentity == processIdentity &&
                version.connectionGeneration == generation && version.navigationGeneration <= navigation))

    internal fun affectsConnection(original: Connection, originalIncarnation: String?): Boolean =
        original === connection && originalIncarnation == incarnation

    fun owns(context: ScopedReadContext?): Boolean = context != null && context.custodyOwner == owner
    override fun toString(): String = "AuthorityRetirement(reason=$reason, retiresReadActor=$retiresReadActor)"
}
