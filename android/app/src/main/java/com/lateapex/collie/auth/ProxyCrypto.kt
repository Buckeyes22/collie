package com.lateapex.collie.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import kotlinx.serialization.json.*
import java.util.UUID

internal object ProxyJson {
    val json = Json { ignoreUnknownKeys = false }
    fun objectOf(text: String, maxBytes: Int = 65_536): JsonObject {
        require(text.toByteArray().size <= maxBytes)
        // Duplicate escaped property names must not choose a different signature/admission value.
        val stack = java.util.ArrayDeque<MutableSet<String>>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { require(++depth <= 32); stack.addLast(mutableSetOf()); i++ }
                '}' -> { require(stack.isNotEmpty() && --depth >= 0); stack.removeLast(); i++ }
                '[' -> { require(++depth <= 32); i++ }
                ']' -> { require(--depth >= 0); i++ }
                '"' -> {
                    val start = i++
                    while (i < text.length) { if (text[i] == '\\') { i += 2; continue }; if (text[i++] == '"') break }
                    val end = i
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i < text.length && text[i] == ':') {
                        val key = json.parseToJsonElement(text.substring(start, end)).jsonPrimitive.content
                        require(stack.isNotEmpty() && stack.last().add(key))
                    }
                }
                else -> i++
            }
        }
        return json.parseToJsonElement(text).jsonObject
    }
    fun string(value: JsonObject, name: String, max: Int = 2048): String =
        value[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.also {
            require(it.isNotEmpty() && it.length <= max && it.none { ch -> ch.code < 32 || ch.code == 127 })
        } ?: error("Invalid identity field")
    fun number(value: JsonObject, name: String): Long = value[name]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull?.also { require(it in 0..9_007_199_254_740_991L) } ?: error("Invalid identity clock")
}

interface ProxySigningKey {
    fun publicJwk(): JsonObject
    fun sign(input: ByteArray): ByteArray
    val thumbprint: String get() = ProxyJose.hash(buildJsonObject {
        put("crv", "P-256"); put("kty", "EC"); put("x", publicJwk().getValue("x")); put("y", publicJwk().getValue("y"))
    }.toString().encodeToByteArray())
}
class KeystoreProxySigningKey : ProxySigningKey {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun ensure() {
        if (store().containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    override fun publicJwk(): JsonObject {
        ensure(); val key = store().getCertificate(ALIAS).publicKey as ECPublicKey
        fun coordinate(value: BigInteger) = ProxyJose.encode(value.toByteArray().let { bytes ->
            require(bytes.size <= 33); ByteArray(32).also { bytes.copyInto(it, 32 - minOf(bytes.size, 32), maxOf(0, bytes.size - 32)) }
        })
        return buildJsonObject { put("kty", "EC"); put("crv", "P-256"); put("x", coordinate(key.w.affineX)); put("y", coordinate(key.w.affineY)) }
    }
    override fun sign(input: ByteArray): ByteArray {
        ensure(); return Signature.getInstance("SHA256withECDSA").run {
            initSign(store().getKey(ALIAS, null) as java.security.PrivateKey); update(input); ProxyJose.derToJose(sign())
        }
    }
    companion object { private const val ALIAS = "com.lateapex.collie.proxy.dpop.v1" }
}
object ProxyJose {
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun decode(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also { require(encode(it) == value) }
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    fun hash(bytes: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun proof(key: ProxySigningKey, method: String, uri: String, token: String? = null, nonce: String? = null, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val header = buildJsonObject { put("typ", "dpop+jwt"); put("alg", "ES256"); put("jwk", key.publicJwk()) }
        val parsed = java.net.URI(uri); require(parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.rawFragment == null)
        // Retain the actual raw path; a component constructor would double-escape percent bytes.
        val exactHtu = "https://${parsed.rawAuthority}${parsed.rawPath.ifEmpty { "/" }}"
        val body = buildJsonObject { put("jti", UUID.randomUUID().toString()); put("htm", method); put("htu", exactHtu); put("iat", nowSeconds)
            token?.let { put("ath", hash(it.encodeToByteArray())) }; nonce?.let { put("nonce", it) }
        }
        val input = "${encode(header.toString().encodeToByteArray())}.${encode(body.toString().encodeToByteArray())}"
        return "$input.${encode(key.sign(input.encodeToByteArray()))}"
    }
    fun derToJose(bytes: ByteArray): ByteArray {
        var offset = 0
        fun take(): Int = (bytes.getOrNull(offset++)?.toInt() ?: error("Invalid ES256 DER")) and 255
        require(take() == 0x30); val length = take(); require(length < 128 && length == bytes.size - 2)
        fun integer(): ByteArray {
            require(take() == 2); val n = take(); require(n in 1..33 && offset + n <= bytes.size)
            val v = bytes.copyOfRange(offset, offset + n); offset += n
            require(v[0].toInt() and 128 == 0 && (n == 1 || v[0] != 0.toByte() || v[1].toInt() and 128 != 0))
            val positive = if (v[0] == 0.toByte()) v.drop(1).toByteArray() else v
            require(positive.isNotEmpty() && positive.size <= 32 && BigInteger(1, positive) > BigInteger.ZERO && BigInteger(1, positive) < ORDER)
            return ByteArray(32).also { positive.copyInto(it, 32 - positive.size) }
        }
        val r = integer(); val s = integer(); require(offset == bytes.size); return r + s
    }
    fun joseToDer(bytes: ByteArray): ByteArray {
        require(bytes.size == 64)
        fun integer(offset: Int): ByteArray {
            val scalar = BigInteger(1, bytes.copyOfRange(offset, offset + 32)); require(scalar > BigInteger.ZERO && scalar < ORDER)
            val value = bytes.copyOfRange(offset, offset + 32).dropWhile { it == 0.toByte() }.toByteArray(); require(value.isNotEmpty())
            val positive = if (value[0].toInt() and 128 != 0) byteArrayOf(0) + value else value
            return byteArrayOf(2, positive.size.toByte()) + positive
        }
        val body = integer(0) + integer(32); return byteArrayOf(0x30, body.size.toByte()) + body
    }
    internal fun verifyJwt(token: String, keys: JsonArray, type: String? = null): JsonObject {
        require(token.length <= 16_384); val parts = token.split('.'); require(parts.size == 3)
        val header = ProxyJson.objectOf(utf8(decode(parts[0]))); val claims = ProxyJson.objectOf(utf8(decode(parts[1])))
        require(header["alg"]?.jsonPrimitive?.content == "ES256" && listOf("crit", "b64", "jku", "jwk", "x5u").none { header.containsKey(it) })
        type?.let { require(header["typ"]?.jsonPrimitive?.content == it) }
        val kid = ProxyJson.string(header, "kid", 128)
        val jwk = keys.map { it.jsonObject }.single { it["kid"]?.jsonPrimitive?.content == kid }
        verifySignature("${parts[0]}.${parts[1]}".encodeToByteArray(), decode(parts[2]), jwk)
        return claims
    }
    private val ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",16)
    internal fun verifySignature(input: ByteArray, signature: ByteArray, jwk: JsonObject) {
        require(jwk.keys.all { it in setOf("kty","crv","x","y","kid","alg","use") })
        require(jwk["kty"]?.jsonPrimitive?.content == "EC" && jwk["crv"]?.jsonPrimitive?.content == "P-256" && !jwk.containsKey("d"))
        val x = decode(ProxyJson.string(jwk, "x", 43)); val y = decode(ProxyJson.string(jwk, "y", 43)); require(x.size == 32 && y.size == 32)
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params))
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(input); verify(joseToDer(signature)) })
    }
}
