package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** JVM AES-GCM validates authenticated envelope custody; it is not Android Keystore evidence. */
internal class AgentFixtureCipher : SecretCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val aad = "owned-agent-custody-fixture-v1".toByteArray()
    override fun encrypt(plaintext: ByteArray): CipherEnvelope = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key); updateAAD(aad); CipherEnvelope(iv, doFinal(plaintext))
    }
    override fun decrypt(envelope: CipherEnvelope): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv)); updateAAD(aad); doFinal(envelope.ciphertext)
    }
}
@RunWith(RobolectricTestRunner::class)
class AgentAutomationCustodyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun actor(connection: Connection, incarnation: String, process: String = "fixture-process", navigation: Long = 1) = ScopedReadContext(
        ScopedRequestKey(connection.origin.value, 1, "paired-owner", 0, "peer", "session", "w1:p1"), connection, navigation,
        Scope("peer", "session"), incarnation, true, null, process)
    private fun record(actor: ScopedReadContext, id: String = UUID.randomUUID().toString()) = StoredAgentAction(id, actor.custodyVersion, "peer", "session", "w1:p1", "terminal-A", "runtime-A", "a".repeat(64), "b".repeat(64), "prompt", "pending")
    private fun retirement(actor: ScopedReadContext, reason: AuthorityInvalidation) = AuthorityRetirement(reason, actor.custodyOwner, true, actor.connection, actor.connectionIncarnation, actor.key.connectionGeneration, actor.navigationGeneration, actor.custodyProcessIdentity)
    @Test fun zeroRowRetirementRejectsDelayedAAfterRecreationWhileSameOwnerFreshIncarnationRemainsPositive() {
        val preferences = context.getSharedPreferences("agent-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val cipher = AgentFixtureCipher(); val a = actor(Connection(CollieOrigin("https://agent.fixture/"), "A", "private-test-bearer"), UUID.randomUUID().toString())
        val store = AgentAutomationCustody(context, cipher, preferencesOverride = preferences)
        store.retire(retirement(a, AuthorityInvalidation.DISCONNECTED))
        assertFalse(store.reserve(record(a)))
        val b = actor(Connection(a.connection.origin, "B", "same-owner-new-credential"), UUID.randomUUID().toString())
        val bRecord = record(b); assertTrue(store.reserve(bRecord)); assertEquals("pending", store.get(bRecord.id)?.state)
        val restored = AgentAutomationCustody(context, cipher, preferencesOverride = preferences)
        assertFalse(restored.reserve(record(a)))
        assertEquals("unknown", restored.get(bRecord.id)?.state)
        assertFalse(restored.finish(record(a, bRecord.id), "succeeded"))
        assertEquals("unknown", restored.get(bRecord.id)?.state)
        assertTrue(restored.finish(bRecord, "succeeded"))
        val raw = preferences.getString("sealed", "")!!
        assertFalse(raw.contains("private-test-bearer")); assertFalse(raw.contains("runtime-A")); assertFalse(raw.contains("https://agent.fixture/"))
    }
    @Test fun scopedNavigationRetirementDoesNotEraseFreshBOrResurrectOldRecordAndCurrentPendingRestoreNeverReplays() {
        val preferences = context.getSharedPreferences("agent-test-${UUID.randomUUID()}", Context.MODE_PRIVATE); val cipher = AgentFixtureCipher()
        val connection = Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token"); val incarnation = UUID.randomUUID().toString()
        val a = actor(connection, incarnation); val b = actor(connection, incarnation, navigation = 2)
        val store = AgentAutomationCustody(context, cipher, preferencesOverride = preferences); val old = record(a); assertTrue(store.reserve(old)); store.retire(retirement(a, AuthorityInvalidation.SCOPE_CHANGED))
        val fresh = record(b).copy(paneId = "w1:p2"); assertTrue(store.reserve(fresh)); assertEquals("unknown", store.get(old.id)?.state); assertFalse(store.reserve(record(a)))
        val restored = AgentAutomationCustody(context, cipher, preferencesOverride = preferences)
        assertEquals("unknown", restored.get(fresh.id)?.state); assertFalse(restored.reserve(record(a))); assertFalse(restored.reserve(record(b)))
        assertTrue(restored.finish(fresh, "abandoned")); assertTrue(restored.clearSettled(b, PaneAddress(Scope("peer", "session"), "w1:p2")))
        assertTrue(restored.reserve(record(b).copy(paneId = "w1:p2")))
    }
    @Test fun ordinaryNetworkFailureKeepsOriginalUnknownAndAuthenticationRetirementNeverManufacturesZeroEffect() {
        val preferences = context.getSharedPreferences("agent-test-${UUID.randomUUID()}", Context.MODE_PRIVATE); val cipher = AgentFixtureCipher()
        val a = actor(Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token"), UUID.randomUUID().toString()); val store = AgentAutomationCustody(context, cipher, preferencesOverride = preferences); val original = record(a)
        assertTrue(store.reserve(original))
        val ordinary = AuthorityRetirement(AuthorityInvalidation.ACCESS_FROZEN, a.custodyOwner, false, a.connection, a.connectionIncarnation, a.key.connectionGeneration, a.navigationGeneration, a.custodyProcessIdentity)
        store.retire(ordinary); assertEquals("pending", store.get(original.id)?.state); assertTrue(store.finish(original, "unknown"))
        store.retire(retirement(a, AuthorityInvalidation.ACCESS_FROZEN)); assertEquals("unknown", store.get(original.id)?.state)
        val fresh = actor(a.connection, a.connectionIncarnation!!, navigation = 2); assertFalse(store.reserve(record(fresh)))
        val restored = AgentAutomationCustody(context, cipher, preferencesOverride = preferences); assertEquals("unknown", restored.get(original.id)?.state); assertFalse(restored.reserve(record(fresh)))
    }
    @Test fun wrongKeyAndFailedPersistencePreserveRawCustodyAndAdmitNoAction() {
        val preferences = context.getSharedPreferences("agent-test-${UUID.randomUUID()}", Context.MODE_PRIVATE); val cipher = AgentFixtureCipher()
        val a = actor(Connection(CollieOrigin("https://agent.fixture/"), "A", "fixture-token"), UUID.randomUUID().toString()); val store = AgentAutomationCustody(context, cipher, preferencesOverride = preferences)
        assertTrue(store.reserve(record(a))); val raw = preferences.getString("sealed", null)
        val wrongKey = AgentAutomationCustody(context, AgentFixtureCipher(), preferencesOverride = preferences)
        assertFalse(wrongKey.track(a)); assertEquals(raw, preferences.getString("sealed", null))
        val failed = object : android.content.SharedPreferences by preferences { override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor by preferences.edit() {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = this
            override fun commit() = false
        } }
        val unreadable = AgentAutomationCustody(context, cipher, preferencesOverride = failed)
        assertFalse(unreadable.track(a)); assertEquals(raw, preferences.getString("sealed", null))
    }
}
