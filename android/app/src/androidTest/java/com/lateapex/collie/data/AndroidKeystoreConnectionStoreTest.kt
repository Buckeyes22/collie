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

    @Test
    fun retainedContentUsesRealKeystoreAndColdScopedExpiryAndRetirementWithoutTouchingPairing() {
        val directory = java.io.File(context.noBackupFilesDir, "retention-instrumentation-${java.util.UUID.randomUUID()}")
        var clock = System.currentTimeMillis()
        fun cold() = OfflineContentStore(RetainedReadDatabase(context, cipher, { clock }, directory), { clock })
        val store = cold()
        val owner = ScopedCustodyOwner("https://retention.example/mount/", "https://retention.example/mount/", "instrumented-owner")
        val scope = RetainedReadScope(owner, "00000000-0000-0000-0000-000000000001", "peer-a", "session-a", "pane-1")
        try {
            org.junit.Assert.assertTrue(store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS))
            org.junit.Assert.assertTrue(store.chooseDrafts(true))
            org.junit.Assert.assertTrue(store.retainPane(scope,
                com.lateapex.collie.network.PaneReadResponse("pane-1", "instrumented retained masked mirror", false, 1),
                listOf(com.lateapex.collie.network.TranscriptEntry("turn-1", "2026-10-12", "assistant", listOf(com.lateapex.collie.network.TranscriptPart("text", "instrumented retained Chat"))))) { true })
            org.junit.Assert.assertTrue(store.saveDraft("instrumented-draft", "instrumented saved draft", owner, scope))
            assertEquals("instrumented retained masked mirror", cold().pane(scope)?.value?.pane?.text)
            assertNull(cold().pane(scope.copy(session = "other")))
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse(file.readBytes().decodeToString().contains("instrumented retained"))
                assertFalse(file.readBytes().decodeToString().contains("instrumented saved draft"))
            }
            clock += 48L * 60 * 60 * 1000
            assertNull(cold().draft("instrumented-draft", owner))
            assertNull(cold().pane(scope)?.value?.pane)
            org.junit.Assert.assertNotNull(cold().pane(scope)?.value?.entries)
            org.junit.Assert.assertTrue(store.retireOwner(owner))
            assertNull(cold().pane(scope))
            assertEquals(OfflineContentStore.Retention.SEVEN_DAYS, cold().retention)
        } finally { directory.deleteRecursively() }
    }
}
