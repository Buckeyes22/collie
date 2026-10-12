package com.lateapex.collie.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import mockwebserver3.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Generated disposable keys and a real pinned TLS socket. This is controlled issuer evidence,
 * never evidence that an operator's live identity provider implements DPoP. */
internal class ProxyHttpsFixture(private val issuerWithoutSlash: Boolean = false) : java.io.Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val issuerPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val issuerJwk: JsonObject get() {
        val pub=issuerPair.public as ECPublicKey
        fun coordinate(value:java.math.BigInteger)=ProxyJose.encode(value.toByteArray().let {bytes-> ByteArray(32).also {bytes.copyInto(it,32-minOf(bytes.size,32),maxOf(0,bytes.size-32))}})
        return buildJsonObject {put("kty","EC");put("crv","P-256");put("x",coordinate(pub.w.affineX));put("y",coordinate(pub.w.affineY));put("kid","issuer")}
    }
    val key = object : ProxySigningKey {
        override fun publicJwk(): JsonObject {
            val pub = pair.public as ECPublicKey
            fun coordinate(value: java.math.BigInteger) = ProxyJose.encode(value.toByteArray().let { bytes -> ByteArray(32).also { bytes.copyInto(it, 32 - minOf(bytes.size, 32), maxOf(0, bytes.size - 32)) } })
            return buildJsonObject { put("kty","EC"); put("crv","P-256"); put("x",coordinate(pub.w.affineX)); put("y",coordinate(pub.w.affineY)) }
        }
        override fun sign(input: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(input); ProxyJose.derToJose(sign()) }
    }
    val client: OkHttpClient
    val store: ProxyIdentityStore
    var connection: Connection
    var generation = 1L
    var navigation = 1L
    val actor get() = ScopedReadContext(ScopedRequestKey(connection.origin.value,generation,"1".repeat(64),0,null,null,null),connection,navigation)
    val coordinator: ProxySignInCoordinator
    val rawProfile: String
    val issuer: String get() = server.url("/").toString().let { if (issuerWithoutSlash) it.removeSuffix("/") else it }
    var clockOffsetMs=0L
    val now get() = (System.currentTimeMillis()+clockOffsetMs) / 1000
    init {
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory());server.start()
        client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        context.getSharedPreferences("collie_proxy_identity_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val cipher=object: SecretCipher {
            override fun encrypt(plaintext:ByteArray)=CipherEnvelope(byteArrayOf(1),plaintext.map { (it.toInt() xor 90).toByte() }.toByteArray())
            override fun decrypt(envelope:CipherEnvelope)=envelope.ciphertext.map { (it.toInt() xor 90).toByte() }.toByteArray()
        }
        store=ProxyIdentityStore(context,cipher,Json)
        connection=Connection(CollieOrigin(server.url("/mount/").toString()),"phone","pairing-secret")
        rawProfile=buildJsonObject {
            put("schema",1);put("issuer",issuer);put("clientId","native");put("audience","gateway");put("redirectUri",ProxyProfile.REDIRECT)
            put("authorizationEndpoint",server.url("/authorize").toString());put("tokenEndpoint",server.url("/token").toString());put("jwksEndpoint",server.url("/jwks").toString());put("gatewayOrigin",server.url("/").toString())
            put("algorithms",buildJsonArray { add("ES256") });put("jwks",buildJsonArray { add(issuerJwk) })
        }.toString()
        coordinator=ProxySignInCoordinator(store,key,{connection},{actor},client,now={System.currentTimeMillis()+clockOffsetMs})
        coordinator.importProfile(rawProfile,actor)
    }
    fun discovery() = buildJsonObject {
        put("issuer",issuer);put("authorization_endpoint",server.url("/authorize").toString());put("token_endpoint",server.url("/token").toString());put("jwks_uri",server.url("/jwks").toString())
        put("code_challenge_methods_supported",buildJsonArray { add("S256") });put("dpop_signing_alg_values_supported",buildJsonArray { add("ES256") });put("authorization_response_iss_parameter_supported",true)
    }.toString()
    fun jwks()=buildJsonObject { put("keys",buildJsonArray { add(issuerJwk) }) }.toString()
    fun jwt(claims: JsonObject, typ: String? = null): String {
        val head=buildJsonObject {put("alg","ES256");put("kid","issuer");typ?.let {put("typ",it)}}
        val input=ProxyJose.encode(head.toString().encodeToByteArray())+"."+ProxyJose.encode(claims.toString().encodeToByteArray())
        return input+"."+ProxyJose.encode(Signature.getInstance("SHA256withECDSA").run {initSign(issuerPair.private);update(input.encodeToByteArray());ProxyJose.derToJose(sign())})
    }
    fun tokens(nonce: String, expires: Long = now+120, type: String = "DPoP", subject: String = "operator", signedIssuer: String = issuer) = buildJsonObject {
        put("token_type",type);put("refresh_token","refresh-fixture")
        put("access_token",jwt(buildJsonObject {put("iss",signedIssuer);put("aud","gateway");put("client_id","native");put("sub",subject);put("iat",now);put("exp",expires);put("cnf",buildJsonObject {put("jkt",key.thumbprint)})},"at+jwt"))
        put("id_token",jwt(buildJsonObject {put("iss",signedIssuer);put("aud","native");put("sub",subject);put("iat",now);put("exp",expires);put("nonce",nonce)}))
    }.toString()
    fun access(): String = ProxyJson.string(ProxyJson.objectOf(tokens("nonce")),"access_token",16_384)
    fun readyTokens() {
        val r=store.record()!!
        store.write(ProxyRecord(origin=r.origin,connectionIdentity=r.connectionIdentity,profile=r.profile,tokens=ProxyTokens(access(),"refresh","operator",System.currentTimeMillis()+120_000,key.thumbprint,"nonce")))
    }
    override fun close() { client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.close();store.clear() }
}
