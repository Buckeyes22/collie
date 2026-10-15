package com.lateapex.collie.auth

import java.security.Signature
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyCryptoTest {
    @Test fun officialRfc7515AppendixA3Es256RawSignatureVerifiesAndCannotVerifyChangedInput() {
        val input="eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ".encodeToByteArray()
        val signature=ProxyJose.decode("DtEhU3ljbEg8L38VWAfUAqOyKAM6-Xx-F4GawxaepmXFCgfTjDxw5djxLa8ISlSApmWQxfKTUJqPP3-Kg6NU1Q")
        val public=buildJsonObject {put("kty","EC");put("crv","P-256");put("x","f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU");put("y","x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")}
        ProxyJose.verifySignature(input,signature,public)
        assertTrue(runCatching {ProxyJose.verifySignature(input+byteArrayOf(0),signature,public)}.isFailure)
        assertEquals(64,signature.size)
    }
    @Test fun pkceS256MatchesOfficialRfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ProxyJose.hash("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".encodeToByteArray()))
    }
    @Test fun strictJoseFramingVerifiesActualP256SignatureAndRefusesDerSubstitution() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update("fixture message".encodeToByteArray()) }
        val der = signer.sign(); val jose = ProxyJose.derToJose(der)
        assertEquals(64, jose.size)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update("fixture message".encodeToByteArray()); verify(ProxyJose.joseToDer(jose)) })
        assertTrue(runCatching { ProxyJose.joseToDer(der) }.isFailure)
        assertTrue(runCatching { ProxyJose.derToJose(der + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { ProxyJose.derToJose(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1)) }.isFailure)
    }
    @Test fun proofUsesActualMountedEncodedPublicPathWithoutQueryOrFragmentAndExactTokenHash() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = pair.public as ECPublicKey
        fun coordinate(value: java.math.BigInteger): String = ProxyJose.encode(value.toByteArray().let { bytes -> ByteArray(32).also { bytes.copyInto(it, 32 - minOf(bytes.size, 32), maxOf(0, bytes.size - 32)) } })
        val key = object : ProxySigningKey {
            override fun publicJwk() = buildJsonObject { put("kty", "EC"); put("crv", "P-256"); put("x", coordinate(public.w.affineX)); put("y", coordinate(public.w.affineY)) }
            override fun sign(input: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(input); ProxyJose.derToJose(sign()) }
        }
        val proof = ProxyJose.proof(key, "POST", "https://collie.example/mount/api/pane/a%3Ab/keys?host=owner", "fixture-access", "fixture-nonce", 100)
        val pieces = proof.split('.'); val body = ProxyJson.objectOf(ProxyJose.decode(pieces[1]).toString(Charsets.UTF_8))
        assertEquals("https://collie.example/mount/api/pane/a%3Ab/keys", body["htu"]?.jsonPrimitive?.content)
        assertEquals("POST", body["htm"]?.jsonPrimitive?.content)
        assertEquals(ProxyJose.hash("fixture-access".encodeToByteArray()), body["ath"]?.jsonPrimitive?.content)
        assertEquals("fixture-nonce", body["nonce"]?.jsonPrimitive?.content)
        assertEquals(64, ProxyJose.decode(pieces[2]).size)
    }
    @Test fun identityJsonHasBoundedDepthAndNumericDatesNeverCoerceStringsOrFractions() {
        assertTrue(runCatching { ProxyJson.objectOf("{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}") }.isFailure)
        for (raw in listOf("\"120\"", "1.5", "-1", "9007199254740992", "null", "true")) {
            assertTrue(runCatching { ProxyJson.number(ProxyJson.objectOf("{\"exp\":"+raw+"}"), "exp") }.isFailure)
        }
        assertEquals(120L, ProxyJson.number(ProxyJson.objectOf("{\"exp\":120}"), "exp"))
    }
    @Test fun duplicateEscapedAliasesAndPrivateOrForeignIssuerKeyProfilesRefuse() {
        assertTrue(runCatching { ProxyJson.objectOf("{\"iss\":\"a\",\"\\u0069ss\":\"b\"}") }.isFailure)
        assertTrue(runCatching { ProxyJson.objectOf("{\"a\":{\"x\":1,\"x\":2}}") }.isFailure)
    }
}
