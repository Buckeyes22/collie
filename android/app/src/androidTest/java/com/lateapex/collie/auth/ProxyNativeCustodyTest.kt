package com.lateapex.collie.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lateapex.collie.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Android Keystore signing and encryption only. No live issuer/push acceptance claim. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class ProxyNativeCustodyTest {
    @Test fun productionP256ProofAndSeparateEncryptedIdentitySurviveStoreRecreation() {
        val app:Context=ApplicationProvider.getApplicationContext()
        val namespace="proxy-instrumentation-"+java.util.UUID.randomUUID()
        val scoped=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences(namespace+name,mode)
        }
        val alias="com.lateapex.collie.proxy.instrumentation."+java.util.UUID.randomUUID()
        val cipher=AndroidKeystoreCipher(alias)
        val store=ProxyIdentityStore(scoped,cipher,Json)
        try {
            val key=KeystoreProxySigningKey()
            val input="production device signature fixture".encodeToByteArray()
            val raw=key.sign(input);assertEquals(64,raw.size);ProxyJose.verifySignature(input,raw,key.publicJwk())
            assertFalse(key.publicJwk().containsKey("d"));assertEquals(43,key.thumbprint.length)
            val profile=buildJsonObject {
                put("schema",1);put("issuer","https://issuer.example/");put("clientId","native");put("audience","gateway");put("redirectUri",ProxyProfile.REDIRECT)
                put("authorizationEndpoint","https://issuer.example/authorize");put("tokenEndpoint","https://issuer.example/token");put("jwksEndpoint","https://issuer.example/jwks");put("gatewayOrigin","https://collie.example/")
                put("algorithms",buildJsonArray {add("ES256")});put("jwks",buildJsonArray {add(JsonObject(key.publicJwk()+mapOf("kid" to JsonPrimitive("fixture"))))})
            }.toString()
            store.write(ProxyRecord(origin="https://collie.example/",connectionIdentity="1".repeat(64),profile=profile,tokens=ProxyTokens("private-access-fixture","private-refresh-fixture","operator",System.currentTimeMillis()+120_000,key.thumbprint,"nonce")))
            val encoded=scoped.getSharedPreferences("collie_proxy_identity_v1",Context.MODE_PRIVATE).getString("record",null)!!
            assertFalse(encoded.contains("private-access-fixture"));assertFalse(encoded.contains("private-refresh-fixture"));assertFalse(encoded.contains("issuer.example"))
            val recreated=ProxyIdentityStore(scoped,AndroidKeystoreCipher(alias),Json)
            assertEquals("private-access-fixture",recreated.record()!!.tokens!!.accessToken);assertEquals(key.thumbprint,KeystoreProxySigningKey().thumbprint)
            recreated.clearIdentity();assertNull(recreated.record()!!.tokens);assertEquals(profile,recreated.record()!!.profile)
        }finally{
            store.clear()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        }
    }
}
