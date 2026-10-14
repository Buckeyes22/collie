package com.lateapex.collie.data

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReleaseMetadataTest {
    private val keyPair = newP256KeyPair()
    private val pinnedKey = publicPem(keyPair)
    private val verifier = AndroidReleaseMetadataVerifier(pinnedKey)
    private val keyId = sha256(keyPair.public.encoded)

    @Test
    fun `signature covers exact original bytes and returns verified metadata`() {
        val bytes = metadata()
        val result = verifier.verify(bytes, signature(bytes), installedVersionCode = 3, deviceApi = 26)
        val verified = (result as ReleaseMetadataResult.Verified).release
        assertEquals("android-v1.5.3", verified.metadata.tag)
        assertEquals(4, verified.metadata.versionCode)
        assertArrayEquals(bytes, verified.rawMetadata)
    }

    @Test
    fun `changed metadata bytes refuse despite a previously valid sidecar`() {
        val bytes = metadata()
        val changed = bytes.decodeToString().replaceFirst("aaaaaaaa", "baaaaaaa").encodeToByteArray()
        assertEquals(
            ReleaseMetadataFailure.InvalidSignature,
            refused(verifier.verify(changed, signature(bytes), 3, 34)),
        )
    }

    @Test
    fun `padded and malformed signature encodings are refused`() {
        val bytes = metadata()
        val encoded = signature(bytes).decodeToString()
        val padded = "$encoded=".encodeToByteArray()
        assertEquals(ReleaseMetadataFailure.InvalidEncoding, refused(verifier.verify(bytes, padded, 3, 34)))
        val malformedDer = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0x30, 0x00))
        assertEquals(ReleaseMetadataFailure.InvalidEncoding, refused(verifier.verify(bytes, malformedDer.encodeToByteArray(), 3, 34)))
    }

    @Test
    fun `wrong package and unsupported platform are distinct refusals`() {
        val wrongPackage = metadata(applicationId = "com.lateapex.collie.debug")
        assertEquals(ReleaseMetadataFailure.WrongPackage, refused(verifier.verify(wrongPackage, signature(wrongPackage), 3, 34)))
        val newerSdk = metadata(minSdk = 35)
        assertEquals(ReleaseMetadataFailure.IncompatibleDevice, refused(verifier.verify(newerSdk, signature(newerSdk), 3, 34)))
    }

    @Test
    fun `equal and lower versions are rejected as replay or downgrade`() {
        val bytes = metadata()
        assertEquals(ReleaseMetadataFailure.DowngradeOrReplay, refused(verifier.verify(bytes, signature(bytes), 4, 34)))
        assertEquals(ReleaseMetadataFailure.DowngradeOrReplay, refused(verifier.verify(bytes, signature(bytes), 5, 34)))
    }

    @Test
    fun `unknown key identifier and tampered key fail closed`() {
        val bytes = metadata(keyId = "0".repeat(64))
        assertEquals(ReleaseMetadataFailure.UnknownSigningKey, refused(verifier.verify(bytes, signature(bytes), 3, 34)))
        val other = AndroidReleaseMetadataVerifier(publicPem(newP256KeyPair()))
        val correctBytes = metadata()
        assertEquals(ReleaseMetadataFailure.UnknownSigningKey, refused(other.verify(correctBytes, signature(correctBytes), 3, 34)))
    }

    @Test
    fun `transition objects must use the settled exact field schema`() {
        val bytes = metadata(chainJson = "[{\"schemaVersion\":1,\"unknown\":true}]")
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition, refused(verifier.verify(bytes, signature(bytes), 3, 34)))
    }

    @Test
    fun `raw transition schema rejects mistyped scalars before typed metadata decoding`() {
        val second = newP256KeyPair()
        val complete = link(keyPair, second, 4, "android-v1.5.3")
        val variants = listOf(
            complete.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            complete.replace("\"activationVersionCode\":4", "\"activationVersionCode\":\"4\""),
            complete.replace("\"fromKeyId\":\"$keyId\"", "\"fromKeyId\":0"),
            complete.replace(Regex("\"signature\":\"[^\"]+\""), "\"signature\":null"),
        )
        for (variant in variants) {
            assertTrue("Fixture changes one actual scalar", variant != complete)
            val bytes = metadata(chainJson = "[$variant]")
            assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition,
                refused(verifier.verify(bytes, signature(bytes), 3, 34)))
        }
        val objectChain = metadata(chainJson = complete)
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition,
            refused(verifier.verify(objectChain, signature(objectChain), 3, 34)))
        for (bytes in listOf("[]".encodeToByteArray(), "{".encodeToByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            assertEquals(ReleaseMetadataFailure.InvalidMetadata,
                refused(verifier.verify(bytes, signature(bytes), 3, 34)))
        }
    }

    @Test
    fun `two-key skipped-release chain verifies against last key`() {
        val second = newP256KeyPair()
        val link = link(keyPair, second, 4, "android-v1.5.3")
        val bytes = metadata(keyId = sha256(second.public.encoded), chainJson = "[$link]")
        assertTrue(verifier.verify(bytes, signature(bytes, second), 3, 34) is ReleaseMetadataResult.Verified)
    }

    @Test
    fun `three-key skipped-release chain verifies and reordered chain refuses`() {
        val second = newP256KeyPair()
        val third = newP256KeyPair()
        val firstLink = link(keyPair, second, 4, "android-v1.5.3")
        val secondLink = link(second, third, 5, "android-v1.5.4")
        val bytes = metadata(keyId = sha256(third.public.encoded), chainJson = "[$firstLink,$secondLink]", version = 6, versionName = "1.5.5")
        val verified = verifier.verify(bytes, signature(bytes, third), 3, 34)
        assertTrue(verified is ReleaseMetadataResult.Verified)
        val reordered = metadata(keyId = sha256(third.public.encoded), chainJson = "[$secondLink,$firstLink]", version = 6, versionName = "1.5.5")
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition, refused(verifier.verify(reordered, signature(reordered, third), 3, 34)))
    }

    @Test
    fun `transition signature ordering activation and outer signature are enforced`() {
        val second = newP256KeyPair()
        val link = link(keyPair, second, 4, "android-v1.5.3")
        val good = metadata(keyId = sha256(second.public.encoded), chainJson = "[$link]")
        assertEquals(ReleaseMetadataFailure.InvalidSignature, refused(verifier.verify(good, signature(good, keyPair), 3, 34)))
        val badActivationTag = good.decodeToString()
            .replace("\"activationTag\":\"android-v1.5.3\"", "\"activationTag\":\"android-v1.5.4\"")
            .encodeToByteArray()
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition, refused(verifier.verify(badActivationTag, signature(badActivationTag, second), 3, 34)))
        val altered = good.decodeToString().replace("\"apkSizeBytes\":4", "\"apkSizeBytes\":5").encodeToByteArray()
        assertEquals(ReleaseMetadataFailure.InvalidSignature, refused(verifier.verify(altered, signature(good, second), 3, 34)))
    }

    @Test
    fun `cycle and too many transition links are refused`() {
        val second = newP256KeyPair()
        val first = link(keyPair, second, 4, "android-v1.5.3")
        val cycle = link(second, keyPair, 5, "android-v1.5.4")
        val cycleBytes = metadata(keyId = keyId, chainJson = "[$first,$cycle]", version = 6, versionName = "1.5.5")
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition, refused(verifier.verify(cycleBytes, signature(cycleBytes, keyPair), 3, 34)))
        val many = (0..8).joinToString(",") { first }
        val tooMany = metadata(keyId = sha256(second.public.encoded), chainJson = "[$many]")
        assertEquals(ReleaseMetadataFailure.UnsupportedKeyTransition, refused(verifier.verify(tooMany, signature(tooMany, second), 3, 34)))
    }

    @Test
    fun `oversized metadata refuses before parsing`() {
        assertEquals(
            ReleaseMetadataFailure.InvalidMetadata,
            refused(verifier.verify(ByteArray(AndroidReleaseMetadataVerifier.MAX_METADATA_BYTES + 1), byteArrayOf(), 3, 34)),
        )
    }

    private fun refused(result: ReleaseMetadataResult): ReleaseMetadataFailure =
        (result as ReleaseMetadataResult.Refused).failure

    private fun metadata(
        applicationId: String = "com.lateapex.collie",
        minSdk: Int = 26,
        keyId: String = this.keyId,
        chainJson: String = "[]",
        version: Int = 4,
        versionName: String = "1.5.3",
    ): ByteArray = buildString {
        append("{\"tag\":\"android-v").append(versionName).append("\",\"applicationId\":\"").append(applicationId)
        append("\",\"versionName\":\"").append(versionName).append("\",\"versionCode\":").append(version).append(',')
        append("\"sourceCommit\":\"").append("a".repeat(40)).append("\",\"buildInputsVerified\":true,")
        append("\"apk\":\"collie-android-").append(versionName).append(".apk\",\"apkSizeBytes\":4,")
        append("\"sha256\":\"").append("b".repeat(64)).append("\",\"signingCertificateSha256\":\"")
        append("c".repeat(64)).append("\",\"minSdk\":").append(minSdk)
        append(",\"metadataKeyId\":\"").append(keyId).append('"')
        append(",\"metadataKeyChain\":").append(chainJson)
        append('}')
    }.encodeToByteArray()

    private fun signature(bytes: ByteArray, signer: KeyPair = keyPair): ByteArray =
        Base64.getUrlEncoder().withoutPadding().encodeToString(derSignature(bytes, signer)).toByteArray(Charsets.US_ASCII)

    private fun derSignature(bytes: ByteArray, signer: KeyPair = keyPair): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signer.private)
        update(bytes)
        sign()
    }

    private fun link(from: KeyPair, to: KeyPair, activation: Long, tag: String): String {
        val spki = Base64.getUrlEncoder().withoutPadding().encodeToString(to.public.encoded)
        val fields = listOf("1", sha256(from.public.encoded), sha256(to.public.encoded), spki, activation.toString(), tag)
        val payload = buildString {
            append("CollieAndroidMetadataKeyTransitionV1\n")
            fields.forEach { value -> append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value) }
        }.encodeToByteArray()
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(derSignature(payload, from))
        return "{\"schemaVersion\":1,\"fromKeyId\":\"${fields[1]}\",\"toKeyId\":\"${fields[2]}\",\"successorSpki\":\"$spki\",\"activationVersionCode\":$activation,\"activationTag\":\"$tag\",\"signature\":\"$sig\"}"
    }

    private fun newP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun publicPem(pair: KeyPair): ByteArray {
        val encoded = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(pair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n".encodeToByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
