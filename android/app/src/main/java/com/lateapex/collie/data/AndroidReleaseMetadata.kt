package com.lateapex.collie.data

import android.content.Context
import com.lateapex.collie.R
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64 as JavaBase64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class AndroidReleaseMetadata(
    val tag: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val sourceCommit: String,
    val buildInputsVerified: Boolean,
    val apk: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val minSdk: Int,
    val metadataKeyId: String,
    val metadataKeyChain: List<MetadataKeyTransition> = emptyList(),
)

@Serializable
internal data class MetadataKeyTransition(
    val schemaVersion: Int,
    val fromKeyId: String,
    val toKeyId: String,
    val successorSpki: String,
    val activationVersionCode: Long,
    val activationTag: String,
    val signature: String,
)

internal data class VerifiedAndroidRelease(
    val metadata: AndroidReleaseMetadata,
    val rawMetadata: ByteArray,
    val rawSignature: ByteArray,
)

internal sealed interface ReleaseMetadataFailure {
    data object InvalidEncoding : ReleaseMetadataFailure
    data object InvalidSignature : ReleaseMetadataFailure
    data object UnknownSigningKey : ReleaseMetadataFailure
    data object UnsupportedKeyTransition : ReleaseMetadataFailure
    data object InvalidMetadata : ReleaseMetadataFailure
    data object WrongPackage : ReleaseMetadataFailure
    data object IncompatibleDevice : ReleaseMetadataFailure
    data object DowngradeOrReplay : ReleaseMetadataFailure
}

internal sealed interface ReleaseMetadataResult {
    data class Verified(val release: VerifiedAndroidRelease) : ReleaseMetadataResult
    data class Refused(val failure: ReleaseMetadataFailure) : ReleaseMetadataResult
}

/**
 * Verifies the detached sidecar against the app's bundled P-256 metadata trust anchor.
 * The signature covers the exact received metadata bytes; this class never re-serializes JSON.
 */
internal class AndroidReleaseMetadataVerifier(
    private val pinnedPublicKey: ByteArray,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val expectedPackage: String = "com.lateapex.collie",
) {
    internal fun pinnedKeyId(): String = try {
        sha256(decodePinnedPublicKey(pinnedPublicKey).encoded)
    } catch (_: Exception) { "" }

    constructor(context: Context) : this(
        context.resources.openRawResource(R.raw.android_release_metadata_public_key).use { it.readBytes() },
    )

    fun verify(
        rawMetadata: ByteArray,
        rawSignature: ByteArray,
        installedVersionCode: Long,
        deviceApi: Int,
    ): ReleaseMetadataResult {
        if (rawMetadata.isEmpty() || rawMetadata.size > MAX_METADATA_BYTES) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidMetadata)
        }
        val publicKey = try {
            decodePinnedPublicKey(pinnedPublicKey)
        } catch (_: Exception) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.UnknownSigningKey)
        }
        val pinnedKeyId = sha256(publicKey.encoded)
        val decodedSignature = decodeStrictSignature(rawSignature)
            ?: return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidEncoding)
        if (!isCanonicalP256Signature(decodedSignature)) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidEncoding)
        }

        val text = try { rawMetadata.decodeToString(throwOnInvalidSequence = true) }
            catch (_: Exception) { return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidMetadata) }
        val root = try { json.parseToJsonElement(text) as? JsonObject }
            catch (_: Exception) { null }
            ?: return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidMetadata)
        // Check the closed chain shape before DTO decoding: a missing/extra/mistyped link
        // is an unsupported transition, rather than an unrelated metadata decode error.
        if (!hasExactTransitionFields(root)) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
        }
        val parsed = try { json.decodeFromString<AndroidReleaseMetadata>(text) }
            catch (_: Exception) { return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidMetadata) }
        if (parsed.applicationId != expectedPackage) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.WrongPackage)
        }
        if (!parsed.isStructurallyValid()) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidMetadata)
        }
        if (parsed.minSdk > deviceApi) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.IncompatibleDevice)
        }
        val resolvedKey = when (val result = resolveSigningKey(parsed, publicKey, pinnedKeyId)) {
            is KeyResolution.Refused -> return ReleaseMetadataResult.Refused(result.failure)
            is KeyResolution.Resolved -> result.key
        }
        val validSignature = verifySignature(resolvedKey, rawMetadata, decodedSignature)
        if (!validSignature) return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.InvalidSignature)
        if (parsed.versionCode <= installedVersionCode) {
            return ReleaseMetadataResult.Refused(ReleaseMetadataFailure.DowngradeOrReplay)
        }
        return ReleaseMetadataResult.Verified(VerifiedAndroidRelease(parsed, rawMetadata.copyOf(), decodedSignature))
    }

    private fun hasExactTransitionFields(root: JsonObject): Boolean {
        val links = root["metadataKeyChain"] ?: return true
        val array = links as? JsonArray ?: return false
        if (array.size > MAX_TRANSITIONS) return false
        return array.all { element ->
            val obj = element as? JsonObject ?: return@all false
            if (obj.keys != TRANSITION_FIELDS) return@all false
            val schema = obj["schemaVersion"] as? JsonPrimitive ?: return@all false
            val activation = obj["activationVersionCode"] as? JsonPrimitive ?: return@all false
            !schema.isString && schema.content == "1" && !activation.isString &&
                activation.content.toLongOrNull()?.let { it > 0 } == true &&
                (TRANSITION_FIELDS - setOf("schemaVersion", "activationVersionCode")).all { field ->
                    (obj[field] as? JsonPrimitive)?.isString == true
                }
        }
    }

    private sealed interface KeyResolution {
        data class Resolved(val key: java.security.interfaces.ECPublicKey) : KeyResolution
        data class Refused(val failure: ReleaseMetadataFailure) : KeyResolution
    }

    /** The complete bounded chain is repeated in the exact-byte signed manifest. */
    private fun resolveSigningKey(
        metadata: AndroidReleaseMetadata,
        pinnedKey: java.security.interfaces.ECPublicKey,
        pinnedKeyId: String,
    ): KeyResolution {
        val chain = metadata.metadataKeyChain
        if (chain.size > MAX_TRANSITIONS) return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
        var currentKey = pinnedKey
        var currentId = pinnedKeyId
        var previousActivation = 0L
        val seen = mutableSetOf(currentId)
        for (link in chain) {
            if (link.schemaVersion != 1 || link.fromKeyId != currentId ||
                !link.fromKeyId.matches(HEX_SHA256) || !link.toKeyId.matches(HEX_SHA256) ||
                !link.activationTag.matches(STABLE_TAG) || link.activationVersionCode !in 1..MAX_SAFE_INTEGER ||
                link.activationVersionCode <= previousActivation ||
                link.activationVersionCode > metadata.versionCode || link.toKeyId in seen
            ) return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            val successor = decodeSpki(link.successorSpki)
                ?: return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            val computedId = sha256(successor.encoded)
            if (computedId != link.toKeyId) return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            val transitionSignature = decodeStrictSignature(link.signature.toByteArray(Charsets.US_ASCII))
                ?: return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            if (!isCanonicalP256Signature(transitionSignature) ||
                !verifySignature(currentKey, transitionBytes(link), transitionSignature)
            ) return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            currentKey = successor
            currentId = computedId
            previousActivation = link.activationVersionCode
            seen += currentId
        }
        if (currentId != metadata.metadataKeyId) return KeyResolution.Refused(ReleaseMetadataFailure.UnknownSigningKey)
        if (chain.isNotEmpty()) {
            val last = chain.last()
            if (metadata.versionCode == last.activationVersionCode && metadata.tag != last.activationTag) {
                return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            }
            if (metadata.versionCode > last.activationVersionCode && !metadata.tag.matches(STABLE_TAG)) {
                return KeyResolution.Refused(ReleaseMetadataFailure.UnsupportedKeyTransition)
            }
        }
        return KeyResolution.Resolved(currentKey)
    }

    private fun transitionBytes(link: MetadataKeyTransition): ByteArray {
        val values = listOf(
            link.schemaVersion.toString(), link.fromKeyId, link.toKeyId, link.successorSpki,
            link.activationVersionCode.toString(), link.activationTag,
        )
        val payload = buildString {
            append("CollieAndroidMetadataKeyTransitionV1\n")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                append(bytes.size).append(':').append(value)
            }
        }
        return payload.toByteArray(Charsets.UTF_8)
    }

    private fun verifySignature(key: java.security.interfaces.ECPublicKey, bytes: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        } catch (_: Exception) { false }

    private fun decodeSpki(encoded: String): java.security.interfaces.ECPublicKey? {
        if (!BASE64URL_NOPAD.matches(encoded)) return null
        return try {
            val der = JavaBase64.getUrlDecoder().decode(encoded)
            if (JavaBase64.getUrlEncoder().withoutPadding().encodeToString(der) != encoded) return null
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as java.security.interfaces.ECPublicKey
            if (!isP256(key.params) || !key.encoded.contentEquals(der)) null else key
        } catch (_: Exception) { null }
    }

    private fun AndroidReleaseMetadata.isStructurallyValid(): Boolean =
        tag == "android-v$versionName" &&
            STABLE_VERSION.matches(versionName) &&
            versionCode > 0 &&
            versionCode <= MAX_SAFE_INTEGER &&
            sourceCommit.matches(HEX_COMMIT) &&
            buildInputsVerified &&
            apk == "collie-android-$versionName.apk" &&
            apkSizeBytes in 1..MAX_APK_BYTES &&
            sha256.matches(HEX_SHA256) &&
            signingCertificateSha256.matches(HEX_SHA256) &&
            minSdk in MIN_SUPPORTED_API..MAX_SUPPORTED_API

    private fun decodePinnedPublicKey(pem: ByteArray): java.security.interfaces.ECPublicKey {
        val text = pem.decodeToString(throwOnInvalidSequence = true)
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size >= 3 && lines.first() == "-----BEGIN PUBLIC KEY-----" && lines.last() == "-----END PUBLIC KEY-----")
        val body = lines.subList(1, lines.lastIndex).joinToString("")
        require(body.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        val der = JavaBase64.getDecoder().decode(body)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            as java.security.interfaces.ECPublicKey
        require(isP256(key.params))
        require(key.encoded.contentEquals(der))
        return key
    }

    private fun decodeStrictSignature(sidecar: ByteArray): ByteArray? {
        if (sidecar.isEmpty() || sidecar.size > MAX_SIGNATURE_TEXT_BYTES) return null
        val encoded = sidecar.decodeToString(throwOnInvalidSequence = false)
        if (!BASE64URL_NOPAD.matches(encoded)) return null
        val decoded = try {
            JavaBase64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (JavaBase64.getUrlEncoder().withoutPadding().encodeToString(decoded) != encoded) return null
        return decoded
    }

    private fun isCanonicalP256Signature(der: ByteArray): Boolean {
        if (der.size !in 8..72 || (der[0].toInt() and 0xff) != 0x30) return false
        if ((der[1].toInt() and 0xff) != der.size - 2) return false
        var offset = 2
        fun integer(): BigInteger? {
            if (offset + 2 > der.size || (der[offset++].toInt() and 0xff) != 0x02) return null
            val length = der[offset++].toInt() and 0xff
            if (length !in 1..33 || offset + length > der.size) return null
            val bytes = der.copyOfRange(offset, offset + length)
            offset += length
            if ((bytes[0].toInt() and 0x80) != 0) return null // DER INTEGER must be positive.
            if (bytes.size > 1 && bytes[0].toInt() == 0 && (bytes[1].toInt() and 0x80) == 0) return null
            val value = BigInteger(bytes)
            if (value.signum() <= 0 || value >= P256_ORDER) return null
            return value
        }
        val r = integer() ?: return false
        val s = integer() ?: return false
        return offset == der.size && r.signum() > 0 && s.signum() > 0
    }

    private fun isP256(params: ECParameterSpec): Boolean {
        if (params.order != P256_ORDER || params.cofactor != 1) return false
        val field = params.curve.field as? ECFieldFp ?: return false
        if (field.p.bitLength() != 256) return false
        val expected = p256Parameters()
        return params.curve == expected.curve && params.generator == expected.generator
    }

    private fun p256Parameters(): ECParameterSpec {
        val parameters = java.security.AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_METADATA_BYTES = 128 * 1024
        const val MAX_APK_BYTES = 100L * 1024L * 1024L
        const val MIN_SUPPORTED_API = 26
        const val MAX_SUPPORTED_API = 100
        const val MAX_SIGNATURE_TEXT_BYTES = 128
        const val MAX_TRANSITIONS = 8
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val BASE64URL_NOPAD = Regex("[A-Za-z0-9_-]+")
        private val STABLE_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val STABLE_TAG = Regex("android-v[0-9]+\\.[0-9]+\\.[0-9]+")
        private val HEX_COMMIT = Regex("[0-9a-f]{40}")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private val TRANSITION_FIELDS = setOf(
            "schemaVersion", "fromKeyId", "toKeyId", "successorSpki", "activationVersionCode", "activationTag", "signature",
        )
        private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    }
}
