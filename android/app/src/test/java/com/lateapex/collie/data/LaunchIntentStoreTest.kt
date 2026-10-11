package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchIntentStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = TestCipher()

    @Before
    fun reset() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun persistsEncryptedFrozenIdentityAndRejectsChangedBodyOrScopeForSameId() {
        val store = LaunchIntentStore(context, cipher, Json)
        val intent = intent()
        assertTrue(store.reserve(intent) is ReserveIntentResult.Reserved)

        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(RECORD, null).orEmpty()
        assertFalse(raw.contains(intent.canonicalBody))
        assertFalse(raw.contains(intent.origin))

        val restored = LaunchIntentStore(context, cipher, Json)
        assertEquals(intent, restored.get(intent.requestId)?.intent)
        assertTrue(restored.reserve(intent) is ReserveIntentResult.Existing)
        assertEquals(ReserveIntentResult.Mismatch, restored.reserve(intent(canonicalBody = "{\"run\":\"other\"}")))
        assertEquals(ReserveIntentResult.Mismatch, restored.reserve(intent(host = "other-host")))
        assertEquals(ReserveIntentResult.Mismatch, restored.reserve(intent.copy(targetIdentity = "workspace-other")))
    }

    @Test
    fun pendingIntentAfterProcessRestartBecomesUnknownAndDoesNotReplay() {
        val store = LaunchIntentStore(context, cipher, Json)
        val intent = intent()
        store.reserve(intent)

        val afterRestart = LaunchIntentStore(context, cipher, Json)
        assertEquals(LaunchIntentState.unknown, afterRestart.get(intent.requestId)?.state)
        assertNull(afterRestart.transition(intent.requestId, intent.bodySha256, LaunchIntentState.pending))
        assertNotNull(afterRestart.transition(intent.requestId, intent.bodySha256, LaunchIntentState.succeeded, "receipt-1"))
        assertEquals("receipt-1", afterRestart.get(intent.requestId)?.receiptReference)
    }

    @Test
    fun unknownIntentCannotBeResolvedByARefusedReplay() {
        val store = LaunchIntentStore(context, cipher, Json)
        val intent = intent()
        store.reserve(intent)
        assertNotNull(store.transition(intent.requestId, intent.bodySha256, LaunchIntentState.unknown))

        assertNull(store.transition(intent.requestId, intent.bodySha256, LaunchIntentState.refused))
        assertEquals(LaunchIntentState.unknown, store.get(intent.requestId)?.state)
        assertEquals(LaunchIntentState.unknown, LaunchIntentStore(context, cipher, Json).get(intent.requestId)?.state)
    }

    @Test
    fun explicitRetirementPurgesEncryptedIntentRecords() {
        val store = LaunchIntentStore(context, cipher, Json)
        val intent = intent()
        assertTrue(store.reserve(intent) is ReserveIntentResult.Reserved)

        store.clear()

        assertNull(store.get(intent.requestId))
        assertTrue(store.all().isEmpty())
        assertTrue(LaunchIntentStore(context, cipher, Json).all().isEmpty())
    }

    @Test
    fun explicitAbandonRemovesOnlyTheMatchingUnknownRequest() {
        val store = LaunchIntentStore(context, cipher, Json)
        val first = intent()
        val second = intent(requestId = "22222222-2222-4222-8222-222222222222")
        store.reserve(first)
        assertNotNull(store.transition(first.requestId, first.bodySha256, LaunchIntentState.succeeded))
        store.reserve(second)
        assertNotNull(store.transition(second.requestId, second.bodySha256, LaunchIntentState.unknown))

        assertFalse(store.abandonUnknown(first.requestId, first.bodySha256))
        assertFalse(store.abandonUnknown(second.requestId, "0".repeat(64)))
        assertTrue(store.abandonUnknown(second.requestId, second.bodySha256))

        assertEquals(LaunchIntentState.succeeded, store.get(first.requestId)?.state)
        assertNull(store.get(second.requestId))
        assertEquals(listOf(first.requestId), LaunchIntentStore(context, cipher, Json).all().map { it.intent.requestId })
    }

    @Test
    fun pendingOrUnknownIntentAtomicallyBlocksDifferentIdsOnlyForTheSameDurableAuthority() {
        val store = LaunchIntentStore(context, cipher, Json)
        val first = intent()
        val sameScope = intent(requestId = "22222222-2222-4222-8222-222222222222").copy(
            connectionGeneration = 99L,
            leadEpoch = 88L,
            operation = "worktree.createAt",
            confirmedNoPrompts = false,
        )
        val otherHost = sameScope.copy(requestId = "33333333-3333-4333-8333-333333333333", host = "another-host")

        assertTrue(store.reserve(first) is ReserveIntentResult.Reserved)
        assertTrue(store.reserve(sameScope) is ReserveIntentResult.Unresolved)
        assertTrue(store.reserve(otherHost) is ReserveIntentResult.Reserved)
        assertNotNull(store.transition(first.requestId, first.bodySha256, LaunchIntentState.unknown))

        val recovered = LaunchIntentStore(context, cipher, Json)
        assertTrue(recovered.reserve(sameScope) is ReserveIntentResult.Unresolved)
        assertTrue(recovered.reserve(first) is ReserveIntentResult.Existing)
    }

    @Test
    fun explicitPairingRetirementCanRemoveAnUnreadableEnvelope() {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(RECORD, "unreadable-owner-journal").commit()
        val store = LaunchIntentStore(context, cipher, Json)
        assertTrue(runCatching { store.all() }.isFailure)

        store.clear()

        assertFalse(prefs.contains(RECORD))
        assertTrue(LaunchIntentStore(context, cipher, Json).all().isEmpty())
    }

    @Test
    fun corruptedEnvelopeFailsClosedAndIsNotDeleted() {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(RECORD, "not-an-envelope").commit()

        val store = LaunchIntentStore(context, cipher, Json)

        assertTrue(runCatching { store.reserve(intent()) }.isFailure)
        assertEquals("not-an-envelope", prefs.getString(RECORD, null))
    }

    @Test
    fun oversizedEnvelopeAndCipherFieldsAreRejectedBeforeDecodeOrDecrypt() {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val countingCipher = CountingCipher()
        val oversizedRaw = "x".repeat(1_000_000)
        prefs.edit().putString(RECORD, oversizedRaw).commit()
        val store = LaunchIntentStore(context, countingCipher, Json)
        assertTrue(runCatching { store.all() }.isFailure)
        assertEquals(0, countingCipher.decryptCalls)
        assertEquals(oversizedRaw, prefs.getString(RECORD, null))

        prefs.edit().putString(RECORD, "{\"version\":1,\"iv\":\"${"A".repeat(100_000)}\",\"ciphertext\":\"${"A".repeat(24)}\"}").commit()
        val second = LaunchIntentStore(context, countingCipher, Json)
        assertTrue(runCatching { second.all() }.isFailure)
        assertEquals(0, countingCipher.decryptCalls)
    }

    @Test
    fun decryptedJournalSizeIsCheckedBeforeRecordParsing() {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val cipher = OversizedClearCipher()
        prefs.edit().putString(RECORD, "{\"version\":1,\"iv\":\"${Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)}\",\"ciphertext\":\"${"A".repeat(24)}\"}").commit()

        val store = LaunchIntentStore(context, cipher, Json)

        assertTrue(runCatching { store.all() }.isFailure)
        assertEquals(1, cipher.decryptCalls)
    }

    @Test
    fun mixedValidAndInvalidJournalDoesNotPersistOrExposeTheValidPrefix() {
        val valid = StoredLaunchIntent(intent(), LaunchIntentState.pending)
        val json = Json
        val serialized = json.encodeToString(StoredLaunchIntent.serializer(), valid)
        val clear = "{\"records\":[$serialized,{}]}".encodeToByteArray()
        val encrypted = cipher.encrypt(clear)
        val envelope = json.encodeToString(
            Envelope.serializer(),
            Envelope(1, Base64.encodeToString(encrypted.iv, Base64.NO_WRAP), Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)),
        )
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(RECORD, envelope).commit()

        val store = LaunchIntentStore(context, cipher, Json)

        assertEquals(envelope, prefs.getString(RECORD, null))
        assertTrue(runCatching { store.all() }.isFailure)
    }

    @Test
    fun failedDurableReservationCannotBeRetriedAsAnExistingIntent() {
        val store = LaunchIntentStore(context, FailingEncryptCipher(), Json)

        assertTrue(runCatching { store.reserve(intent()) }.isFailure)
        assertTrue(runCatching { store.reserve(intent()) }.isFailure)
    }

    @Test
    fun reserveAndRestartUseTheSameAuthorityFieldValidation() {
        val store = LaunchIntentStore(context, cipher, Json)
        assertTrue(runCatching { store.reserve(intent(origin = " ")) }.isFailure)
        assertTrue(runCatching { store.reserve(intent(host = " ")) }.isFailure)
        assertTrue(store.all().isEmpty())
        assertTrue(store.reserve(intent()) is ReserveIntentResult.Reserved)
        assertEquals(LaunchIntentState.unknown, LaunchIntentStore(context, cipher, Json).get(intent().requestId)?.state)
    }

    @Test
    fun journalSizeLimitRetainsEveryEarlierIntentAndRefusesAdditionalIntents() {
        val store = LaunchIntentStore(context, cipher, Json)
        val body = "{\"command\":\"${"a".repeat(7_800)}\"}"
        var refusedAt = -1
        for (number in 1..100) {
            val record = intent(
                requestId = "%08d-0000-4000-8000-%012d".format(number, number),
                canonicalBody = body,
                host = "build-host-$number",
            )
            if (runCatching { store.reserve(record) }.isFailure) {
                refusedAt = number
                break
            }
        }
        assertTrue("a bounded journal must eventually refuse", refusedAt > 1)
        val retained = store.all()
        assertEquals(refusedAt - 1, retained.size)
        retained.forEachIndexed { index, record ->
            val terminal = if (index % 2 == 0) LaunchIntentState.succeeded else LaunchIntentState.refused
            assertNotNull(store.transition(record.intent.requestId, record.intent.bodySha256, terminal, "r".repeat(256)))
        }
        val restarted = LaunchIntentStore(context, cipher, Json).all()
        assertEquals(retained.size, restarted.size)
        assertTrue(restarted.all { it.state == LaunchIntentState.succeeded || it.state == LaunchIntentState.refused })
    }

    private fun intent(
        requestId: String = "11111111-1111-4111-8111-111111111111",
        canonicalBody: String = "{\"command\":\"claude\",\"noPrompts\":true}",
        host: String? = "build-host",
        origin: String = "https://collie.example",
    ) = FrozenLaunchIntent(
        requestId = requestId,
        origin = origin,
        mount = "https://collie.example",
        connectionGeneration = 8L,
        pairedDeviceIdentity = "phone-identity",
        leadEpoch = 3L,
        host = host,
        session = "default",
        subjectIdentity = "workspace:repo",
        operation = "launch",
        confirmedNoPrompts = true,
        canonicalBody = canonicalBody,
    )

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(ByteArray(12) { 3 }, plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private class FailingEncryptCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope = error("test encryption failure")
        override fun decrypt(envelope: CipherEnvelope): ByteArray = error("not used")
    }

    private class CountingCipher : SecretCipher {
        var decryptCalls = 0
        override fun encrypt(plaintext: ByteArray): CipherEnvelope = TestCipher().encrypt(plaintext)
        override fun decrypt(envelope: CipherEnvelope): ByteArray {
            decryptCalls++
            return TestCipher().decrypt(envelope)
        }
    }

    private class OversizedClearCipher : SecretCipher {
        var decryptCalls = 0
        override fun encrypt(plaintext: ByteArray): CipherEnvelope = error("not used")
        override fun decrypt(envelope: CipherEnvelope): ByteArray {
            decryptCalls++
            return ByteArray(600 * 1024)
        }
    }

    @kotlinx.serialization.Serializable
    private data class Envelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "collie_launch_intents"
        private const val RECORD = "encrypted_records"
    }
}
