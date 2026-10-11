package com.lateapex.collie.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyIdentityStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray) = CipherEnvelope(byteArrayOf(1), plaintext.map { (it.toInt() xor 90).toByte() }.toByteArray())
        override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 90).toByte() }.toByteArray()
    }
    @Before fun clear() { context.getSharedPreferences("collie_proxy_identity_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun profile() = buildJsonObject {
        put("schema",1); put("issuer","https://issuer.example/"); put("clientId","native"); put("audience","gateway"); put("redirectUri",ProxyProfile.REDIRECT)
        put("authorizationEndpoint","https://issuer.example/authorize"); put("tokenEndpoint","https://issuer.example/token"); put("jwksEndpoint","https://issuer.example/jwks"); put("gatewayOrigin","https://collie.example/")
        put("algorithms",buildJsonArray { add("ES256") }); put("jwks",buildJsonArray { add(buildJsonObject { put("kty","EC");put("crv","P-256");put("kid","issuer");put("x",ProxyJose.encode(ByteArray(32){1}));put("y",ProxyJose.encode(ByteArray(32){2})) }) })
    }.toString()
    @Test fun callbackConsumeIsDurableOneShotAndExactActorProcessExpiryBound() {
        val store=ProxyIdentityStore(context,cipher,Json); val transaction=ProxyTransaction("state","nonce","verifier",2000,"identity",3,"process")
        store.write(ProxyRecord(origin="https://collie.example/",connectionIdentity="identity",profile=profile(),transaction=transaction))
        assertNull(store.consume("foreign","identity",3,"process",1000))
        assertNull(store.consume("state","other",3,"process",1000));assertNull(store.consume("state","identity",4,"process",1000))
        assertNull(store.consume("state","identity",3,"new-process",1000));assertNull(store.consume("state","identity",3,"process",2000))
        assertNotNull(store.consume("state","identity",3,"process",1000))
        assertNull(ProxyIdentityStore(context,cipher,Json).consume("state","identity",3,"process",1000))
        val encoded=context.getSharedPreferences("collie_proxy_identity_v1",Context.MODE_PRIVATE).getString("record",null)!!
        assertFalse(encoded.contains("verifier"));assertFalse(encoded.contains("issuer.example"))
    }
    @Test fun identityExpiryClearPreservesProfileAndPairingCustodyAndDoesNotEmitBindingRetirement() {
        val store=ProxyIdentityStore(context,cipher,Json)
        val pairing=EncryptedConnectionStore(context,cipher,Json,com.lateapex.collie.network.OriginValidator())
        pairing.save(com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://collie.example/"),"phone","valid-pairing"))
        store.write(ProxyRecord(origin="https://collie.example/",connectionIdentity="identity",profile=profile(),tokens=ProxyTokens("access","refresh","operator",2000,"key","nonce","binding",1)))
        store.clearIdentity();assertNull(store.record()?.tokens);assertNotNull(store.record()?.profile);assertEquals("valid-pairing",pairing.connection.value?.token)
        val lifecycle=ProxyBindingLifecycle();val events=mutableListOf<ProxyBindingRetirement>();lifecycle.observe { events.add(it) }
        assertTrue(events.isEmpty())
    }
}
