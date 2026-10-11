package com.lateapex.collie.auth

import com.lateapex.collie.data.*
import com.lateapex.collie.domain.Connection
import java.security.SecureRandom
import java.util.UUID
import java.net.URI
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*

/** Separate issuer credential client; pairing and gateway tokens never reach discovery/JWKS/token endpoints. */
class ProxySignInCoordinator(private val store: ProxyIdentityStore, private val key: ProxySigningKey,
    private val current: () -> Connection?, private val context: () -> ScopedReadContext?,
    private val credentialClient: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build(), private val now: () -> Long = System::currentTimeMillis,
    private val actorCurrent: (ScopedReadContext) -> Boolean = { actor ->
        val live = context(); live != null && current() === actor.connection && live.key == actor.key && live.navigationGeneration == actor.navigationGeneration
    }) {
    val bindingLifecycle = ProxyBindingLifecycle(store)
    @androidx.annotation.VisibleForTesting internal var beforePublicationForTesting: () -> Unit = {}
    private val credentialCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private val authorityEpoch = java.util.concurrent.atomic.AtomicLong()
    fun retire() {
        authorityEpoch.incrementAndGet(); credentialCalls.forEach { it.cancel() }
        store.retire()
    }
    fun suspendPresentation() { store.suspendPresentation() }
    private val processIdentity = UUID.randomUUID().toString()
    fun deviceKeyThumbprint(actor: ScopedReadContext): String { require(isCurrent(actor)); return key.thumbprint }
    fun configured(): Boolean = store.hasCustody()
    fun importProfile(raw: String, actor: ScopedReadContext) {
        val (lease, prior) = store.capture()
        val profile = ProxyProfile.parse(raw)
        require(current() === actor.connection && isCurrent(actor))
        require(actor.connection.origin.headerValue + "/" == profile.gatewayOrigin)
        val imported = ProxyRecord(origin = actor.connection.origin.value, connectionIdentity = actor.key.pairedDeviceIdentity, profile = raw, binding = prior?.let { it.binding ?: legacyWitness(it) })
        require(isCurrent(actor)); beforePublicationForTesting()
        // The original empty/owned revision, never the replacement record, authorizes publication.
        require(prior == null || prior.origin == actor.key.origin && prior.connectionIdentity == actor.key.pairedDeviceIdentity)
        check(store.commit(lease, imported)) { "Identity actor retired" }
    }
    fun begin(actor: ScopedReadContext, admitted: () -> Boolean = { true }): String {
        val (lease, captured) = store.capture()
        val r = captured?.takeIf { it.origin == actor.key.origin && it.connectionIdentity == actor.key.pairedDeviceIdentity } ?: error("Configure the operator profile first")
        require(admitted() && isCurrent(actor) && actor.key.pairedDeviceIdentity == r.connectionIdentity)
        val profile = ProxyProfile.parse(r.profile)
        validateDiscovery(profile) { admitted() && isCurrent(actor) && store.current(lease) }
        require(admitted() && current() === actor.connection && isCurrent(actor))
        fun random() = ProxyJose.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val t = ProxyTransaction(random(), random(), random(), now() + 5 * 60_000, r.connectionIdentity, actor.key.connectionGeneration, processIdentity)
        beforePublicationForTesting()
        check(admitted() && store.commit(lease, r.copyCustody(transaction = t)))
        return profile.authorizationEndpoint.toHttpUrl().newBuilder().addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", profile.clientId).addQueryParameter("redirect_uri", ProxyProfile.REDIRECT)
            .addQueryParameter("scope", "openid offline_access").addQueryParameter("audience", profile.audience)
            .addQueryParameter("state", t.state).addQueryParameter("nonce", t.nonce)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", ProxyJose.hash(t.verifier.encodeToByteArray()))
            .addQueryParameter("dpop_jkt", key.thumbprint).build().toString()
    }
    fun consumeCallback(raw: String, originalActor: ScopedReadContext? = context(), admitted: () -> Boolean = { true }): Boolean {
        val uri = URI(raw); require(raw.length <= 8192 && uri.scheme == "com.lateapex.collie" && uri.rawAuthority == null && uri.rawPath == "/oauth2redirect" && uri.rawFragment == null)
        val query = mutableMapOf<String, String>()
        for (part in (uri.rawQuery ?: "").split('&')) {
            val pieces = part.split('=', limit = 2); require(pieces.size == 2)
            val name = decodeQuery(pieces[0]); val value = decodeQuery(pieces[1])
            require(name in setOf("state", "code", "iss", "error") && query.put(name, value) == null && value.length in 1..2048 && value.none { it.code < 32 || it.code == 127 })
        }
        val actor = originalActor ?: return false
        if (!admitted() || !isCurrent(actor)) return false
        val (record, transaction) = store.consume(query["state"] ?: return false, actor.key.pairedDeviceIdentity, actor.key.connectionGeneration, processIdentity, now()) ?: return false
        val (lease, consumed) = store.capture(); if (consumed?.revision != record.revision) return false
        val profile = ProxyProfile.parse(record.profile); require(query["iss"] == profile.issuer)
        if (query.containsKey("error")) { require(!query.containsKey("code")); return false }
        val code = query["code"] ?: return false
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", profile.clientId).add("code", code).add("redirect_uri", ProxyProfile.REDIRECT).add("code_verifier", transaction.verifier).build()
        if (!admitted() || !isCurrent(actor) || !store.current(lease)) return false
        val tokens = exchange(profile, form, transaction.nonce, null) { admitted() && isCurrent(actor) && store.current(lease) }
        if (!admitted() || !isCurrent(actor)) return false
        beforePublicationForTesting()
        return admitted() && store.commit(lease, record.copyCustody(tokens = tokens, transaction = null, binding = record.binding ?: legacyWitness(record)))
    }
    fun credentials(connection: Connection, actor: ScopedReadContext? = context()): Pair<ProxyProfile, ProxyTokens>? {
        if (actor == null || actor.connection !== connection || !isCurrent(actor)) error("Identity actor retired")
        val (lease, captured) = store.capture()
        val record = captured?.takeIf { it.origin == actor.key.origin && it.connectionIdentity == actor.key.pairedDeviceIdentity } ?: return null
        val profile = ProxyProfile.parse(record.profile); val prior = record.tokens ?: error("Native identity sign-in required")
        if (prior.expiresAt > now() + 30_000) return profile to prior
        val refresh = prior.refreshToken ?: error("Native identity sign-in required")
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("client_id", profile.clientId).add("refresh_token", refresh).build()
        val next = exchange(profile, form, prior.nonce, prior.subject) { isCurrent(actor) && store.current(lease) }.let { if (it.refreshToken == null) ProxyTokens(it.accessToken, refresh, it.subject, it.expiresAt, it.thumbprint, it.nonce) else it }
        if (!isCurrent(actor) || !store.current(lease)) error("Identity actor retired")
        beforePublicationForTesting()
        check(store.commit(lease, record.copyCustody(tokens = next, binding = record.binding ?: legacyWitness(record)))); return profile to next
    }
    fun credentialsCurrent(actor: ScopedReadContext, profile: ProxyProfile, accessToken: String): Boolean {
        if (!isCurrent(actor)) return false
        val record = store.record() ?: return false
        return record.origin == actor.key.origin && record.connectionIdentity == actor.key.pairedDeviceIdentity &&
            ProxyProfile.parse(record.profile).identity == profile.identity && record.tokens?.accessToken == accessToken
    }
    fun proof(method: String, url: String, token: String, nonce: String?) = ProxyJose.proof(key, method, url, token, nonce, now() / 1000)
    fun acceptBinding(connection: Connection, accessToken: String, id: String, revision: Long, originalActor: ScopedReadContext? = context()) {
        val actor = originalActor ?: return
        if (actor.connection !== connection || !isCurrent(actor) || !id.matches(Regex("[A-Za-z0-9_-]{16,64}")) || revision < 1) return
        val (lease, record) = store.capture(); record ?: return
        val token = record.tokens ?: return
        if (record.origin != actor.key.origin || record.connectionIdentity != actor.key.pairedDeviceIdentity || token.accessToken != accessToken) return
        val witness = ProxyBindingWitness(ProxyProfile.parse(record.profile).issuer, token.subject, token.thumbprint, id, revision)
        val old = record.binding ?: legacyWitness(record)
        val retired = old?.takeIf { it != witness }?.let { ProxyBindingRetirement(it.bindingId, it.revision) }
        if (!isCurrent(actor)) return
        beforePublicationForTesting()
        if (!store.commit(lease, record.copyCustody(binding = witness), retired)) return
        retired?.let(bindingLifecycle::publish)
    }
    fun bindingRetired(connection: Connection, accessToken: String, id: String, revision: Long, originalActor: ScopedReadContext? = context()) {
        val actor = originalActor ?: return
        if (actor.connection !== connection || !isCurrent(actor)) return
        val (lease, record) = store.capture(); record ?: return
        val token = record.tokens ?: return; val witness = record.binding ?: legacyWitness(record) ?: return
        if (record.origin != actor.key.origin || record.connectionIdentity != actor.key.pairedDeviceIdentity || token.accessToken != accessToken ||
            witness.issuer != ProxyProfile.parse(record.profile).issuer || witness.subject != token.subject || witness.thumbprint != token.thumbprint || witness.bindingId != id || witness.revision != revision) return
        val event = ProxyBindingRetirement(id, revision)
        if (!isCurrent(actor)) return
        beforePublicationForTesting()
        if (store.commit(lease, record.copyCustody(tokens = null, transaction = null, binding = null), event)) bindingLifecycle.publish(event)
    }
    fun identityFailed(connection: Connection, accessToken: String, originalActor: ScopedReadContext? = context()) {
        val actor = originalActor ?: return
        if (actor.connection !== connection || !isCurrent(actor)) return
        val (lease, record) = store.capture(); record ?: return
        if (record.origin != actor.key.origin || record.connectionIdentity != actor.key.pairedDeviceIdentity || record.tokens?.accessToken != accessToken) return
        val witness = record.binding ?: legacyWitness(record)
        if (!isCurrent(actor)) return
        beforePublicationForTesting()
        store.commit(lease, record.copyCustody(tokens = null, transaction = null, binding = witness))
    }
    private fun legacyWitness(record: ProxyRecord): ProxyBindingWitness? {
        val token = record.tokens ?: return null
        val id = token.bindingId ?: return null; val revision = token.bindingRevision ?: return null
        return ProxyBindingWitness(ProxyProfile.parse(record.profile).issuer, token.subject, token.thumbprint, id, revision)
    }
    private fun isCurrent(actor: ScopedReadContext): Boolean = actorCurrent(actor)
    private fun validateDiscovery(profile: ProxyProfile, admitted: () -> Boolean) {
        val metadata = read(Request.Builder().url(profile.issuer.trimEnd('/') + "/.well-known/openid-configuration").build(), admitted)
        require(ProxyJson.string(metadata, "issuer") == profile.issuer && ProxyJson.string(metadata, "authorization_endpoint") == profile.authorizationEndpoint && ProxyJson.string(metadata, "token_endpoint") == profile.tokenEndpoint && ProxyJson.string(metadata, "jwks_uri") == profile.jwksEndpoint)
        require(metadata["code_challenge_methods_supported"]?.jsonArray?.any { it.jsonPrimitive.content == "S256" } == true && metadata["dpop_signing_alg_values_supported"]?.jsonArray?.any { it.jsonPrimitive.content == "ES256" } == true && metadata["authorization_response_iss_parameter_supported"]?.jsonPrimitive?.booleanOrNull == true)
        val actual = read(Request.Builder().url(profile.jwksEndpoint).build(), admitted)["keys"]?.jsonArray ?: error("Missing issuer keys")
        for (pin in profile.keys) require(actual.any { candidate -> val p = pin.jsonObject; val c = candidate.jsonObject; listOf("kid", "kty", "crv", "x", "y").all { p[it] == c[it] } && !c.containsKey("d") })
    }
    private fun exchange(profile: ProxyProfile, form: FormBody, nonce: String, expectedSubject: String?, admitted: () -> Boolean): ProxyTokens {
        var issuerNonce: String? = null
        var answer: JsonObject? = null
        repeat(2) { attempt ->
            if (answer != null) return@repeat
            check(admitted()) { "Identity actor retired" }
            val request = Request.Builder().url(profile.tokenEndpoint).header("DPoP", ProxyJose.proof(key, "POST", profile.tokenEndpoint, nonce = issuerNonce, nowSeconds = now() / 1000)).post(form).build()
            issuerCall(request, admitted) { response ->
                val parsed = bounded(response)
                if (!response.isSuccessful) {
                    if (attempt == 0 && response.code in setOf(400, 401) && parsed["error"]?.jsonPrimitive?.content == "use_dpop_nonce") { issuerNonce = response.header("DPoP-Nonce")?.takeIf { it.length in 1..256 && it.none { ch -> ch.code < 33 || ch.code > 126 } } ?: error("Invalid issuer nonce") }
                    else error("Issuer refused identity exchange")
                } else answer = parsed
            }
        }
        val value = answer ?: error("Issuer refused identity exchange"); require(ProxyJson.string(value, "token_type") == "DPoP")
        val access = ProxyJson.string(value, "access_token", 16_384); val claims = ProxyJose.verifyJwt(access, profile.keys, "at+jwt")
        val subject = ProxyJson.string(claims, "sub", 256); val expires = ProxyJson.number(claims, "exp"); val issued = ProxyJson.number(claims, "iat")
        require(ProxyJson.string(claims, "iss") == profile.issuer && ProxyJson.string(claims,"aud",256) == profile.audience && ProxyJson.string(claims,"client_id",256) == profile.clientId && expires > now() / 1000 && issued <= now() / 1000 + 30 && expires > issued && (claims["nbf"] == null || ProxyJson.number(claims, "nbf") <= now() / 1000 + 30) && ProxyJson.string(claims.getValue("cnf").jsonObject,"jkt",43) == key.thumbprint)
        expectedSubject?.let { require(it == subject) }
        val idToken = value["id_token"]?.let { ProxyJson.string(value,"id_token",16_384) }
        require(expectedSubject != null || idToken != null)
        idToken?.let {
            val id = ProxyJose.verifyJwt(it, profile.keys); require(ProxyJson.string(id, "iss") == profile.issuer && ProxyJson.string(id, "sub", 256) == subject && ProxyJson.number(id, "exp") > now() / 1000 && ProxyJson.number(id, "iat") <= now() / 1000 + 30 && ProxyJson.number(id,"exp") > ProxyJson.number(id,"iat"))
            val audiences = when (val a = id["aud"]) { is JsonArray -> a.map { field -> require(field.jsonPrimitive.isString); field.jsonPrimitive.content }; is JsonPrimitive -> { require(a.isString); listOf(a.content) }; else -> emptyList() }
            require(profile.clientId in audiences && audiences.size in 1..8 && (audiences.size == 1 && id["azp"] == null || id["azp"]?.jsonPrimitive?.content == profile.clientId))
            require(ProxyJson.string(id,"nonce",128) == nonce)
            id["at_hash"]?.let { field -> require(field.jsonPrimitive.isString && field.jsonPrimitive.content == ProxyJose.encode(java.security.MessageDigest.getInstance("SHA-256").digest(access.encodeToByteArray()).copyOfRange(0, 16))) }
        }
        val refresh = value["refresh_token"]?.let { ProxyJson.string(value,"refresh_token",16_384) }
        return ProxyTokens(access, refresh, subject, Math.multiplyExact(expires, 1000), key.thumbprint, nonce)
    }
    private fun read(request: Request, admitted: () -> Boolean): JsonObject {
        check(admitted()) { "Identity actor retired" }
        return issuerCall(request, admitted) { require(it.isSuccessful); bounded(it) }
    }
    private fun <T> issuerCall(request: Request, admitted: () -> Boolean, read: (Response) -> T): T {
        val epoch = authorityEpoch.get(); val call = credentialClient.newCall(request)
        credentialCalls.add(call)
        try {
            check(epoch == authorityEpoch.get() && admitted()) { "Identity actor retired" }
            val result = call.execute().use(read)
            check(epoch == authorityEpoch.get() && admitted()) { "Identity actor retired" }
            return result
        } finally { credentialCalls.remove(call) }
    }
    private fun decodeQuery(raw: String): String {
        val bytes = java.io.ByteArrayOutputStream(); var i=0
        while(i<raw.length) {
            val ch=raw[i++]
            when(ch) {
                '%' -> { require(i+1<raw.length); val high=raw[i++].digitToInt(16); val low=raw[i++].digitToInt(16); bytes.write(high*16+low) }
                '+' -> bytes.write(32)
                else -> { require(ch.code in 33..126); bytes.write(ch.code) }
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    private fun bounded(response: Response): JsonObject {
        require(response.code !in 300..399); val source = response.body?.source() ?: error("Missing issuer response")
        require(!source.request(65_537)); val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(source.readByteArray())).toString(); return ProxyJson.objectOf(text)
    }
}
