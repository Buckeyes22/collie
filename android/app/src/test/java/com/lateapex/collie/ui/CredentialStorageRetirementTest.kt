package com.lateapex.collie.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.CredentialRetirementFailure
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class CredentialStorageRetirementTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()
    private val address = PaneAddress(Scope(), "w1:p1")

    @Test
    fun actualCipherAndCommitFailuresRetireViewsAndFreezeRetainedCurrentCredentials() {
        for (fault in listOf("cipher", "commit")) {
            val f = Fixture()
            val original = app.container.repository
            install(f.repository)
            val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
                AgentIdentityActivity.intent(app, address)).create().start().resume().visible()
            val acknowledgements = AtomicInteger()
            val registration = f.repository.onAuthorityInvalidated { acknowledgements.incrementAndGet() }
            try {
                val activity = controller.get()
                shadowOf(activity.mainLooper).idle()
                assertTrue(text(activity.window.decorView).contains("private-current"))
                assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
                assertTrue(f.repository.writesAllowed())
                f.fault = fault
                f.refusal = true
                assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
                shadowOf(activity.mainLooper).idle()
                assertEquals(1, acknowledgements.get())
                assertFalse(text(activity.window.decorView).contains("private-current"))
                assertTrue(text(activity.window.decorView).contains(activity.getString(R.string.pairing_storage_retained)))
                assertEquals(CredentialRetirementFailure.PERSISTENCE_FAILED, f.repository.credentialRetirementFailure)
                assertTrue("failed removal retains the original encrypted custody", f.store.connection.value === f.old)
                assertFalse(f.repository.writesAllowed())
                assertFalse(f.repository.readSource.value is ReadSourceState.Live)
                assertNull(f.repository.readContext())
                assertTrue(runBlocking { f.repository.keys(address, listOf("Enter")) } is ApiResult.Failure)
                f.fault = null
                assertEquals(f.old.token, EncryptedConnectionStore(app, f.cipher, Json, app.container.originValidator).connection.value?.token)
                assertTrue("a new optimistic snapshot cannot resurrect a known retired credential", runBlocking { f.repository.snapshot() } is ApiResult.Failure)
                assertEquals(0, f.posts.get())
            } finally {
                registration.close()
                controller.destroy()
                install(original)
            }
        }
    }

    @Test
    fun failedCipherRetirementCannotRemoveReplacementInstalledDuringActualWipeCallbacks() {
        val f = Fixture()
        val original = app.container.repository
        install(f.repository)
        val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(app, address)).create().start().resume().visible()
        val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
        val callbacks = AtomicInteger()
        val registration = f.repository.onReadContextInvalidated { reason ->
            if (reason == AuthorityInvalidation.PAIRING_RETIRED) {
                assertTrue(f.store.connection.value === f.old)
                assertEquals(CredentialRetirementFailure.PERSISTENCE_FAILED, f.repository.credentialRetirementFailure)
                f.fault = null
                f.store.save(replacement)
                callbacks.incrementAndGet()
            }
        }
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(text(activity.window.decorView).contains("private-current"))
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            f.fault = "cipher"
            f.refusal = true
            assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
            assertEquals(1, callbacks.get())
            assertTrue(f.store.connection.value === replacement)
            assertFalse(text(activity.window.decorView).contains("private-current"))
            f.refusal = false
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            assertNull(f.repository.credentialRetirementFailure)
            assertNull(f.repository.accessFailure)
            assertTrue(f.repository.writesAllowed())
            val identity = runBlocking { f.repository.paneAgentIdentity(address) }
            assertTrue(identity is ApiResult.Success)
            assertEquals("replacement-current", (identity as ApiResult.Success).value.agent)
            assertEquals(replacement.token, EncryptedConnectionStore(app, f.cipher, Json, app.container.originValidator).connection.value?.token)
            assertEquals(0, f.posts.get())
        } finally {
            registration.close()
            controller.destroy()
            install(original)
        }
    }

    @Test
    fun originalCipherFailureAfterDurableReplacementDoesNotPublishTheOldFreezeOntoB() {
        val f = Fixture()
        val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
        assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
        assertTrue(f.repository.writesAllowed())
        f.onCipherFailure = {
            // The production serialization monitor is reentrant; this controlled cipher boundary
            // commits the external replacement before the original CAS failure reaches repository.
            f.fault = null
            f.store.save(replacement)
        }
        f.fault = "cipher"
        f.refusal = true
        assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
        assertTrue(f.store.connection.value === replacement)
        assertNull(f.repository.credentialRetirementFailure)
        assertNull(f.repository.accessFailure)
        f.refusal = false
        assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
        assertTrue(f.repository.writesAllowed())
        assertTrue(runBlocking { f.repository.paneAgentIdentity(address) } is ApiResult.Success)
        assertEquals(replacement.token, EncryptedConnectionStore(app, f.cipher, Json, app.container.originValidator).connection.value?.token)
        assertEquals(0, f.posts.get())
    }

    @Test
    fun currentOwnerUnsupportedCasSelfRevocationFreezesWithoutInventingPersistenceSuccess() {
        val f = Fixture()
        val state = MutableStateFlow<Connection?>(f.old)
        val unsupported = object : ConnectionStore {
            override val connection: StateFlow<Connection?> = state
            override fun save(connection: Connection) { state.value = connection }
            override fun clear() { state.value = null }
        }
        val repository = CollieRepository(f.api, unsupported, app.container.originValidator)
        val acknowledgements = AtomicInteger()
        val registration = repository.onReadContextInvalidated { acknowledgements.incrementAndGet() }
        try {
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            assertTrue(repository.writesAllowed())
            assertTrue(runBlocking { repository.revokeDevice("phone") } is ApiResult.Success)
            assertEquals("the successful current-owner revoke is dispatched once", 1, f.revocations.get())
            assertEquals(1, acknowledgements.get())
            assertTrue(state.value === f.old)
            assertEquals(CredentialRetirementFailure.UNSUPPORTED, repository.credentialRetirementFailure)
            assertFalse(repository.writesAllowed())
            assertNull(repository.readContext())
            assertTrue(runBlocking { repository.keys(address, listOf("Enter")) } is ApiResult.Failure)
            assertEquals(0, f.posts.get())
            val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
            unsupported.save(replacement)
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            assertNull(repository.credentialRetirementFailure)
            assertTrue(repository.writesAllowed())
        } finally { registration.close() }
    }

    private inner class Fixture {
        val old = Connection(CollieOrigin("https://identity.fixture/"), "phone", "current-fixture-token")
        @Volatile var fault: String? = null
        @Volatile var refusal = false
        var onCipherFailure: (() -> Unit)? = null
        val posts = AtomicInteger()
        val revocations = AtomicInteger()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): CipherEnvelope {
                if (fault == "cipher") {
                    onCipherFailure?.invoke()
                    throw IllegalStateException("fixture cipher unavailable")
                }
                return CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            }
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        val prefs = app.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        private val failingPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    // Preserve the wrapper through chaining so real commit failure is injected.
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean = if (fault == "commit") false else editor.commit()
                }
            }
        }
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, failingPrefs).apply { save(old) }
        val api = object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
                SnapshotResponse("fixture", device = DeviceAuthorization(true, "phone", true), agents = emptyList(),
                    shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> =
                if (refusal) ApiResult.Failure(ApiFailure.Http(403, "fixture pairing expired", "pairing.expired",
                    source = FailureSource.COLLIE, factor = FailureFactor.PAIRING))
                else ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200)
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress) = ApiResult.Success(
                AgentIdentityResponse(true, address.paneId, if (connection === old) "private-current" else "replacement-current",
                    AgentStatus.WORKING, null, AgentStateAuthority(false, null), null, null, null, null), 200)
            override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> {
                posts.incrementAndGet()
                return ApiResult.Success(ActionResponse(true), 200)
            }
            override suspend fun revokeDevice(connection: Connection, label: String): ApiResult<DevicesResponse> {
                revocations.incrementAndGet()
                return ApiResult.Success(DevicesResponse(enforced = true, current = "phone", devices = emptyList(), currentExpired = true), 200)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
    }

    private fun install(repository: CollieRepository) {
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
    }
    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }
}
