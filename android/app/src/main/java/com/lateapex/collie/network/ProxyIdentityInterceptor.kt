package com.lateapex.collie.network

import com.lateapex.collie.auth.ProxySignInCoordinator
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.data.custodyVersion
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/** The tag is installed only by the shared connection request builder, never from a URL or phone header. */
internal class NativeIngressEvidence(val requestId: String, val origin: String, val generation: Long)
class ProxyIdentityInterceptor(private val coordinator: ProxySignInCoordinator, private val current: () -> Connection?,
    private val isCurrent: (com.lateapex.collie.data.ScopedReadContext) -> Boolean) : Interceptor {
    private class NonceCustody(val actor: com.lateapex.collie.data.ScopedReadContext, val value: String)
    private class CallCustody(val actor: com.lateapex.collie.data.ScopedReadContext) { val retired = java.util.concurrent.atomic.AtomicBoolean() }
    private val nonces = ConcurrentHashMap<String, NonceCustody>()
    private val nonceLock = Any()
    private fun rememberNonce(key: String, actor: com.lateapex.collie.data.ScopedReadContext, value: String, call: CallCustody?) {
        synchronized(nonceLock) {
            if (call?.retired?.get() == true) return
            if (!nonces.containsKey(key) && nonces.size >= 16) nonces.keys.firstOrNull()?.let { nonces.remove(it) }
            nonces[key] = NonceCustody(actor, value)
        }
    }
    private val calls = ConcurrentHashMap<Call, CallCustody>()
    override fun intercept(chain: Interceptor.Chain): Response {
        val owner = chain.request().tag(com.lateapex.collie.data.ScopedReadContext::class.java)?.let(::CallCustody)
        if (owner != null) calls[chain.call()] = owner
        try { return interceptOwned(chain, owner) }
        finally { if (owner != null) calls.remove(chain.call(), owner) }
    }
    private fun interceptOwned(chain: Interceptor.Chain, ownerCall: CallCustody?): Response {
        val original = chain.request(); val connection = original.tag(Connection::class.java) ?: return chain.proceed(original)
        val bootstrap = original.tag(NativeBootstrapAdmission::class.java)
        if (bootstrap != null) {
            if (!bootstrap.matches(original, connection) || chain.call().isCanceled()) throw IOException("Invalid native bootstrap")
            val requestId = UUID.randomUUID().toString()
            // An uncredentialed candidate reaches a configured gateway's authenticated typed
            // refusal. Neither the current profile nor another pairing incarnation is borrowed.
            return chain.proceed(original.newBuilder().removeHeader("Authorization").removeHeader("Collie-Proxy-Authorization")
                .removeHeader("DPoP").removeHeader("Cookie").header("X-Collie-Native-Request-Id", requestId)
                .tag(NativeIngressEvidence::class.java, NativeIngressEvidence(requestId, connection.origin.value, -1)).build())
        }
        val actor = original.tag(com.lateapex.collie.data.ScopedReadContext::class.java)
        if (current() !== connection || chain.call().isCanceled()) throw IOException("Identity actor retired")
        fun currentActor() = ownerCall?.retired?.get() != true && actor != null && actor.connection === connection && current() === connection && isCurrent(actor) && !chain.call().isCanceled()
        if (!coordinator.configured()) {
            if (actor != null && !currentActor()) throw IOException("Identity actor retired")
            return chain.proceed(original)
        }
        val owner = actor?.key?.connectionGeneration ?: throw IOException("Identity actor unavailable")
        if (!currentActor()) throw IOException("Identity actor retired")
        val (profile, credentials) = try { coordinator.credentials(connection, actor) ?: throw IOException("Identity profile unavailable") }
        catch (_: Exception) { return refusal(original, "identity.sign_in_required") }
        fun preparedCurrent() = actor != null && currentActor() && coordinator.credentialsCurrent(actor, profile, credentials.accessToken)
        if (!preparedCurrent() || original.url.scheme != "https" || original.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString() != profile.gatewayOrigin) throw IOException("Identity actor retired")
        val requestId = UUID.randomUUID().toString()
        val nonceKey = "${profile.identity}:${credentials.thumbprint}:${com.lateapex.collie.auth.ProxyJose.hash(credentials.accessToken.encodeToByteArray())}"
        var nonce = nonces[nonceKey]?.takeIf { it.actor.custodyVersion == actor.custodyVersion }?.value
        // Obtain a nonce with a read-only health probe BEFORE the protected request, particularly
        // before any one-shot write body. The probe is never a terminal retry.
        if (nonce == null) {
            val health = connection.origin.value + "api/health"
            val probe = Request.Builder().url(health).header("Collie-Proxy-Authorization", "DPoP ${credentials.accessToken}")
                .header("DPoP", coordinator.proof("GET", health, credentials.accessToken, null))
                .header("X-Collie-Native-Request-Id", requestId).get().build()
            chain.proceed(probe).use { response -> nonce = response.header("DPoP-Nonce")?.takeIf(::validNonce) }
            if (!preparedCurrent()) throw IOException("Identity actor retired")
            if (nonce == null) return refusal(original, "identity.nonce_unavailable")
            rememberNonce(nonceKey, actor, nonce!!, ownerCall)
        }
        val request = original.newBuilder().header("Collie-Proxy-Authorization", "DPoP ${credentials.accessToken}")
            .header("DPoP", coordinator.proof(original.method, original.url.toString(), credentials.accessToken, nonce))
            .header("X-Collie-Native-Request-Id", requestId)
            .tag(NativeIngressEvidence::class.java, NativeIngressEvidence(requestId, connection.origin.value, owner)).build()
        if (!preparedCurrent()) throw IOException("Identity actor retired")
        val response = chain.proceed(request)
        if (!currentActor()) { response.close(); throw IOException("Identity actor retired") }
        response.header("DPoP-Nonce")?.takeIf(::validNonce)?.let { rememberNonce(nonceKey, actor, it, ownerCall) }
        val verifiedError = verifiedGatewayError(response, requestId)
        if (!currentActor()) { response.close(); throw IOException("Identity actor retired") }
        if (verifiedError && response.header("X-Collie-Error-Factor") == "identity" && response.header("X-Collie-Error-Code") in setOf("identity.expired", "identity.invalid")) {
            synchronized(nonceLock) {
                nonces[nonceKey]?.takeIf { it.actor.custodyVersion == actor.custodyVersion }?.let { nonces.remove(nonceKey, it) }
            }
            coordinator.identityFailed(connection, credentials.accessToken, actor)
        }
        if (response.header("X-Collie-Native-Request-Id") == requestId) {
            val binding = response.header("X-Collie-Native-Binding-Id")
            val revision = response.header("X-Collie-Native-Binding-Revision")?.toLongOrNull()
            if (binding != null && revision != null) {
                if (response.isSuccessful && response.header("X-Collie-Response-Source") == "collie") coordinator.acceptBinding(connection, credentials.accessToken, binding, revision, actor)
                else if (verifiedError && response.header("X-Collie-Error-Factor") == "device" && response.header("X-Collie-Error-Code") == "device.binding_revoked") coordinator.bindingRetired(connection, credentials.accessToken, binding, revision, actor)
            }
        }
        // There is deliberately no automatic retry of the original request, including nonce errors.
        return response
    }
    private fun verifiedGatewayError(response: Response, requestId: String): Boolean {
        val body = runCatching { response.peekBody(NativeGatewayError.MAX_BODY_BYTES.toLong() + 1).string() }.getOrNull() ?: return false
        return NativeGatewayError.verified(response, body, requestId)
    }
    fun retire() { nonces.clear() }
    fun retire(event: com.lateapex.collie.data.AuthorityRetirement) {
        val retired = calls.entries.filter { event.affects(it.value.actor) }
        retired.forEach { it.value.retired.set(true) }
        // Pure owner comparison and bounded mutation only. Cancellation remains outside this lock.
        synchronized(nonceLock) {
            nonces.entries.filter { event.affects(it.value.actor) }.forEach { nonces.remove(it.key, it.value) }
        }
        retired.forEach { it.key.cancel() }
    }
    private fun validNonce(value: String) = value.length in 1..256 && value.none { it.code < 33 || it.code > 126 }
    private fun refusal(request: Request, code: String): Response {
        val id = UUID.randomUUID().toString(); val evidence = NativeIngressEvidence(id, request.url.toString(), request.tag(com.lateapex.collie.data.ScopedReadContext::class.java)?.key?.connectionGeneration ?: -1)
        return Response.Builder().request(request.newBuilder().tag(NativeIngressEvidence::class.java, evidence).build())
            .protocol(Protocol.HTTP_1_1).code(401).message("Native identity sign-in required")
            .header("X-Collie-Response-Source", "native_gateway").header("X-Collie-Error-Source", "native_gateway")
            .header("X-Collie-Error-Factor", "identity").header("X-Collie-Error-Code", code).header("X-Collie-Native-Request-Id", id)
            .body(buildJsonObject { put("source", "native_gateway"); put("factor", "identity"); put("code", code); put("requestId", id) }.toString().toResponseBody("application/json".toMediaType())).build()
    }
}
