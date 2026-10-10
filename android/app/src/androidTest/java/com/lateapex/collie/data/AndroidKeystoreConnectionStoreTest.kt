package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.OriginValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreConnectionStoreTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val context: Context = object : android.content.ContextWrapper(appContext) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            appContext.getSharedPreferences("instrumentation-isolated-$name", mode)
    }
    private val keyAlias = "com.lateapex.collie.instrumentation.${java.util.UUID.randomUUID()}"
    private val cipher = AndroidKeystoreCipher(keyAlias)
    private val json = Json { ignoreUnknownKeys = true }
    private val validator = OriginValidator()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun removeFixtureStorageAndKey() {
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun androidKeystoreRoundTripLeavesNoPlaintextAndCanBeWiped() {
        val store = EncryptedConnectionStore(context, cipher, json, validator)
        store.save(
            Connection(
                validator.requireValid("https://collie.example"),
                "instrumented phone",
                "instrumented-secret-token",
            ),
        )

        val raw = context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .getString(EncryptedConnectionStore.RECORD, null).orEmpty()
        assertFalse(raw.contains("instrumented-secret-token"))
        assertFalse(raw.contains("collie.example"))
        val restored = EncryptedConnectionStore(context, cipher, json, validator)
        assertEquals("instrumented-secret-token", restored.connection.value?.token)

        restored.clear()
        assertNull(restored.connection.value)
        assertFalse(
            context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
                .contains(EncryptedConnectionStore.RECORD),
        )
    }
}
