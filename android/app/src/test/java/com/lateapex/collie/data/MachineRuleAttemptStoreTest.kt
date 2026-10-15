package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MachineRuleAttemptStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pendingAttemptIsEncryptedMetadataOnlyAndRecoversAsUnknownWithoutReplay() {
        val preferences = preferences("pending")
        val cipher = RecordingCipher()
        val firstProcess = store(preferences, cipher)
        val attempt = attempt()
        assertEquals(MachineRuleAttemptReservation.Reserved, firstProcess.reserve(attempt) { commitPrepared -> commitPrepared() })
        val storedMetadata = cipher.lastPlaintext.decodeToString()
        assertTrue(storedMetadata.contains(attempt.machineId))
        assertTrue(storedMetadata.contains(attempt.rulesRevision))
        assertFalse(storedMetadata.contains("threshold"))
        assertFalse(storedMetadata.contains("extensions"))
        assertFalse(storedMetadata.contains("90"))
        assertTrue(preferences.getString("encrypted_attempts", "")!!.contains("ciphertext"))

        val afterRestart = store(preferences, cipher)
        val recovered = afterRestart.unresolved(attempt.ownerDigest, attempt.connectionIncarnation, attempt.machineId)!!
        assertEquals(1, recovered.size)
        assertEquals(MachineRuleAttemptState.unknown, recovered.single().state)
        assertEquals(MachineRuleAttemptReservation.Unresolved, afterRestart.reserve(attempt().copy(attemptId = "2".repeat(32))) { commitPrepared -> commitPrepared() })
    }

    @Test
    fun capacityIsBoundedAndDifferentOwnersCannotSettleEachOthersAttempt() {
        val preferences = preferences("owners")
        val store = store(preferences, RecordingCipher())
        val ownerA = attempt()
        val ownerB = ownerA.copy(attemptId = "b".repeat(32), connectionIncarnation = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val otherOwner = ownerA.copy(attemptId = "c".repeat(32), ownerDigest = "e".repeat(64))
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(ownerA) { commitPrepared -> commitPrepared() })
        assertEquals("A reused attempt identity cannot overwrite another owner's marker",
            MachineRuleAttemptReservation.Unavailable,
            store.reserve(ownerA.copy(ownerDigest = "f".repeat(64))) { commitPrepared -> commitPrepared() })
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(ownerB) { commitPrepared -> commitPrepared() })
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(otherOwner) { commitPrepared -> commitPrepared() })
        assertTrue(store.finishLive(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId))
        assertTrue(store.markUnknown(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId))
        assertFalse(store.acknowledgeReviewed(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerB.attemptId) { commitPrepared -> commitPrepared() })
        assertTrue(store.acknowledgeReviewed(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId) { commitPrepared -> commitPrepared() })
        assertEquals(listOf(ownerB), store.unresolved(ownerB.ownerDigest, ownerB.connectionIncarnation, ownerB.machineId))
        assertEquals(listOf(otherOwner), store.unresolved(otherOwner.ownerDigest, otherOwner.connectionIncarnation, otherOwner.machineId))

        val bounded = store("capacity", RecordingCipher())
        for (index in 0 until 64) {
            val machine = "m" + index.toString(16).padStart(2, '0')
            assertEquals(MachineRuleAttemptReservation.Reserved, bounded.reserve(attempt().copy(
                attemptId = index.toString(16).padStart(32, '0'),
                machineId = machine,
            )) { commitPrepared -> commitPrepared() })
        }
        assertEquals(MachineRuleAttemptReservation.Unavailable, bounded.reserve(attempt().copy(
            attemptId = "f".repeat(32), machineId = "mff",
        )) { commitPrepared -> commitPrepared() })
        val count = (0 until 64).sumOf { index ->
            bounded.unresolved("d".repeat(64), UUID_INCARNATION, "m" + index.toString(16).padStart(2, '0'))!!.size
        }
        assertEquals(64, count)
    }

    @Test
    fun delayedRetiredOwnerCannotClearOrBlockCurrentOwnersIndependentAttempt() {
        val store = store("owner-rotation", RecordingCipher())
        val retiredA = attempt()
        val currentB = retiredA.copy(
            attemptId = "b".repeat(32),
            ownerDigest = "e".repeat(64),
            connectionIncarnation = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        )
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(retiredA) { commitPrepared -> commitPrepared() })
        assertTrue(store.finishLive(retiredA.ownerDigest, retiredA.connectionIncarnation, retiredA.attemptId))
        assertTrue(store.markUnknown(retiredA.ownerDigest, retiredA.connectionIncarnation, retiredA.attemptId))
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(currentB) { commitPrepared -> commitPrepared() })

        assertFalse(store.settleKnown(retiredA.ownerDigest, retiredA.connectionIncarnation, retiredA.attemptId) { false })
        assertEquals(listOf(retiredA.copy(state = MachineRuleAttemptState.unknown)),
            store.unresolved(retiredA.ownerDigest, retiredA.connectionIncarnation, retiredA.machineId))
        assertEquals(listOf(currentB), store.unresolved(currentB.ownerDigest, currentB.connectionIncarnation, currentB.machineId))
    }

    @Test
    fun failedCommitOrDecryptionMakesCustodyUnavailableAndNeverReserves() {
        val prefs = preferences("commit-failure")
        val failing = FailingCommitPreferences(prefs)
        val unavailable = store(failing, RecordingCipher())
        assertEquals(MachineRuleAttemptReservation.Unavailable, unavailable.reserve(attempt()) { commitPrepared -> commitPrepared() })
        assertNull(unavailable.unresolved("d".repeat(64), UUID_INCARNATION))

        val decryptPrefs = preferences("decrypt-failure")
        assertEquals(MachineRuleAttemptReservation.Reserved, store(decryptPrefs, RecordingCipher()).reserve(attempt()) { commitPrepared -> commitPrepared() })
        val unreadable = store(decryptPrefs, RecordingCipher(failDecrypt = true))
        assertEquals(MachineRuleAttemptReservation.Unavailable, unreadable.reserve(attempt()) { commitPrepared -> commitPrepared() })
        assertNull(unreadable.unresolved("d".repeat(64), UUID_INCARNATION))
    }

    @Test
    fun originalCurrentPredicateIsCheckedBeforeDurableReservationAndSettlement() {
        val store = store("current", RecordingCipher())
        val attempt = attempt()
        assertEquals(MachineRuleAttemptReservation.Retired, store.reserve(attempt) { false })
        assertTrue(store.unresolved(attempt.ownerDigest, attempt.connectionIncarnation, attempt.machineId)!!.isEmpty())
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(attempt) { commitPrepared -> commitPrepared() })
        assertFalse(store.settleKnown(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId) { false })
        assertEquals(1, store.unresolved(attempt.ownerDigest, attempt.connectionIncarnation, attempt.machineId)!!.size)
    }

    @Test
    fun heldDeleteIsRefusedAfterOriginalRetiresDuringEncryption() {
        val cipher = BlockingCipher()
        val store = store("held-retirement", cipher)
        val attempt = attempt()
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(attempt) { commitPrepared -> commitPrepared() })
        assertTrue(store.finishLive(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))
        assertTrue(store.markUnknown(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))
        val actorCurrent = AtomicBoolean(true)
        cipher.blockNextEmptyCandidate = true
        val executor = Executors.newSingleThreadExecutor()
        try {
            val deletion = executor.submit<Boolean> {
                store.acknowledgeReviewed(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId) { commit ->
                    if (actorCurrent.get()) commit() else false
                }
            }
            assertTrue(cipher.encryptEntered.await(5, TimeUnit.SECONDS))
            actorCurrent.set(false)
            cipher.releaseEncrypt.countDown()
            assertFalse(deletion.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(attempt.copy(state = MachineRuleAttemptState.unknown)),
                store.unresolved(attempt.ownerDigest, attempt.connectionIncarnation, attempt.machineId))
        } finally {
            cipher.releaseEncrypt.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun preparedADeletionCannotOverwriteConcurrentSuccessorRows() {
        val cipher = BlockingCipher()
        val store = store("held-successor", cipher)
        val ownerA = attempt()
        val ownerB = ownerA.copy(
            attemptId = "b".repeat(32), ownerDigest = "e".repeat(64),
            connectionIncarnation = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        )
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(ownerA) { commitPrepared -> commitPrepared() })
        assertTrue(store.finishLive(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId))
        assertTrue(store.markUnknown(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId))
        cipher.blockNextEmptyCandidate = true
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldDelete = executor.submit<Boolean> {
                store.acknowledgeReviewed(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.attemptId) { commit -> commit() }
            }
            assertTrue(cipher.encryptEntered.await(5, TimeUnit.SECONDS))
            assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(ownerB) { commitPrepared -> commitPrepared() })
            assertTrue(store.finishLive(ownerB.ownerDigest, ownerB.connectionIncarnation, ownerB.attemptId))
            assertTrue(store.markUnknown(ownerB.ownerDigest, ownerB.connectionIncarnation, ownerB.attemptId))
            cipher.releaseEncrypt.countDown()

            assertFalse("The stale zero-row candidate must fail its exact version recheck", oldDelete.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(ownerA.copy(state = MachineRuleAttemptState.unknown)),
                store.unresolved(ownerA.ownerDigest, ownerA.connectionIncarnation, ownerA.machineId))
            assertEquals(listOf(ownerB.copy(state = MachineRuleAttemptState.unknown)),
                store.unresolved(ownerB.ownerDigest, ownerB.connectionIncarnation, ownerB.machineId))
        } finally {
            cipher.releaseEncrypt.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun oversizedPreparedEnvelopeRefusesWithoutEvictingOrWriting() {
        val preferences = preferences("oversized-envelope")
        val store = store(preferences, OversizedCipher())
        val attempt = attempt()
        assertEquals(MachineRuleAttemptReservation.Unavailable, store.reserve(attempt) { commitPrepared -> commitPrepared() })
        assertNull(preferences.getString("encrypted_attempts", null))
        assertTrue(store.unresolved(attempt.ownerDigest, attempt.connectionIncarnation)!!.isEmpty())
    }

    @Test
    fun oversizedNewCandidatePreservesExistingUnknownCustodyAndItsCiphertext() {
        val preferences = preferences("oversized-existing")
        val original = attempt()
        val first = store(preferences, RecordingCipher())
        assertEquals(MachineRuleAttemptReservation.Reserved, first.reserve(original) { commit -> commit() })
        assertTrue(first.finishLive(original.ownerDigest, original.connectionIncarnation, original.attemptId))
        assertTrue(first.markUnknown(original.ownerDigest, original.connectionIncarnation, original.attemptId))
        val committedCiphertext = preferences.getString("encrypted_attempts", null)!!
        assertTrue(committedCiphertext.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        val current = store(preferences, OversizedCipher())
        val newOwner = original.copy(attemptId = "b".repeat(32), ownerDigest = "e".repeat(64))
        assertEquals(MachineRuleAttemptReservation.Unavailable, current.reserve(newOwner) { commit -> commit() })
        assertEquals(committedCiphertext, preferences.getString("encrypted_attempts", null))
        assertEquals(listOf(original.copy(state = MachineRuleAttemptState.unknown)),
            current.unresolved(original.ownerDigest, original.connectionIncarnation))
        assertTrue(current.unresolved(newOwner.ownerDigest, newOwner.connectionIncarnation)!!.isEmpty())
    }

    @Test
    fun sixteenStaleUnknownPreparationsProjectInactivePendingAsReviewableUnknownWithoutTouchingSuccessors() {
        val preferences = preferences("sixteen-stale-unknown")
        val cipher = ContentionCipher()
        val store = store(preferences, cipher)
        val original = attempt()
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(original) { commit -> commit() })
        assertFalse("An active POST cannot be acknowledged", store.acknowledgeReviewed(
            original.ownerDigest, original.connectionIncarnation, original.attemptId,
        ) { commit -> commit() })
        assertTrue("Cancellation/retirement ends this exact local dispatch first",
            store.finishLive(original.ownerDigest, original.connectionIncarnation, original.attemptId))

        cipher.onOriginalUnknownCandidate = { index ->
            val successor = original.copy(
                attemptId = (index + 2).toString(16).padStart(32, 'b'),
                ownerDigest = "e".repeat(64),
                connectionIncarnation = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                machineId = "b$index",
            )
            assertEquals(MachineRuleAttemptReservation.Reserved,
                store.reserve(successor) { commit -> commit() })
            assertTrue(store.finishLive(successor.ownerDigest, successor.connectionIncarnation, successor.attemptId))
            assertTrue(store.markUnknown(successor.ownerDigest, successor.connectionIncarnation, successor.attemptId))
        }

        assertFalse("Sixteen concurrent version advances may defeat durable UNKNOWN preparation",
            store.markUnknown(original.ownerDigest, original.connectionIncarnation, original.attemptId))
        assertEquals(16, cipher.interferenceCount)
        val effective = store.unresolved(original.ownerDigest, original.connectionIncarnation)!!.single()
        assertEquals(MachineRuleAttemptState.unknown, effective.state)
        assertTrue("The UI must disclose that the encrypted record is still PENDING", effective.durablePendingWarning)

        cipher.onOriginalUnknownCandidate = null
        assertTrue("A fresh authorized census plus explicit review can clear only the inactive exact tuple",
            store.acknowledgeReviewed(original.ownerDigest, original.connectionIncarnation, original.attemptId) { commit -> commit() })
        assertTrue(store.unresolved(original.ownerDigest, original.connectionIncarnation)!!.isEmpty())
        val successorRows = store.unresolved("e".repeat(64), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!!
        assertEquals(16, successorRows.size)
        assertTrue(successorRows.all { it.state == MachineRuleAttemptState.unknown })
        assertTrue("No successor marker was evicted or rewritten", successorRows.all { it.durablePendingWarning.not() })
        assertTrue("A retired UUID cannot alias another same-process attempt",
            store.reserve(original) { commit -> commit() } == MachineRuleAttemptReservation.Unavailable)
    }

    @Test
    fun oneStaleUnknownPreparationRetriesToDurableUnknownAndPreservesSuccessor() {
        val cipher = ContentionCipher()
        val store = store("one-stale-unknown", cipher)
        val original = attempt()
        val successor = original.copy(
            attemptId = "c".repeat(32), ownerDigest = "e".repeat(64),
            connectionIncarnation = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", machineId = "peer-c",
        )
        assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(original) { commit -> commit() })
        assertTrue(store.finishLive(original.ownerDigest, original.connectionIncarnation, original.attemptId))
        cipher.onOriginalUnknownCandidate = {
            assertEquals(MachineRuleAttemptReservation.Reserved, store.reserve(successor) { commit -> commit() })
            assertTrue(store.finishLive(successor.ownerDigest, successor.connectionIncarnation, successor.attemptId))
            assertTrue(store.markUnknown(successor.ownerDigest, successor.connectionIncarnation, successor.attemptId))
            cipher.onOriginalUnknownCandidate = null
        }

        assertTrue(store.markUnknown(original.ownerDigest, original.connectionIncarnation, original.attemptId))
        val originalRow = store.unresolved(original.ownerDigest, original.connectionIncarnation)!!.single { it.attemptId == original.attemptId }
        assertEquals(MachineRuleAttemptState.unknown, originalRow.state)
        assertFalse(originalRow.durablePendingWarning)
        assertEquals(listOf(successor.copy(state = MachineRuleAttemptState.unknown)),
            store.unresolved(successor.ownerDigest, successor.connectionIncarnation, successor.machineId))
    }

    private fun attempt() = MachineRuleAttempt(
        attemptId = "1".repeat(32),
        ownerDigest = "d".repeat(64),
        connectionIncarnation = UUID_INCARNATION,
        machineId = "local",
        machineIncarnation = "a".repeat(64),
        rulesRevision = "4",
    )

    private fun preferences(label: String): SharedPreferences =
        context.getSharedPreferences("machine-rule-attempt-test-$label", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        }

    private fun store(label: String, cipher: SecretCipher) = store(preferences(label), cipher)
    private fun store(preferences: SharedPreferences, cipher: SecretCipher) =
        EncryptedMachineRuleAttemptStore(context, cipher, kotlinx.serialization.json.Json, preferences)

    private class RecordingCipher(private val failDecrypt: Boolean = false) : SecretCipher {
        var lastPlaintext: ByteArray = byteArrayOf()
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            lastPlaintext = plaintext.copyOf()
            return CipherEnvelope(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), plaintext.copyOf())
        }
        override fun decrypt(envelope: CipherEnvelope): ByteArray {
            check(!failDecrypt)
            return envelope.ciphertext.copyOf()
        }
    }

    private class BlockingCipher : SecretCipher {
        @Volatile var blockNextEmptyCandidate = false
        val encryptEntered = CountDownLatch(1)
        val releaseEncrypt = CountDownLatch(1)
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            if (blockNextEmptyCandidate && plaintext.decodeToString().contains("\"rows\":[]")) {
                blockNextEmptyCandidate = false
                encryptEntered.countDown()
                check(releaseEncrypt.await(5, TimeUnit.SECONDS))
            }
            return CipherEnvelope(ByteArray(12) { it.toByte() }, plaintext.copyOf())
        }
        override fun decrypt(envelope: CipherEnvelope): ByteArray = envelope.ciphertext.copyOf()
    }

    private class OversizedCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(ByteArray(12), plaintext + ByteArray(70 * 1024))
        override fun decrypt(envelope: CipherEnvelope): ByteArray = envelope.ciphertext
    }

    private class ContentionCipher : SecretCipher {
        @Volatile var onOriginalUnknownCandidate: ((Int) -> Unit)? = null
        @Volatile var interferenceCount = 0
        private val inInterference = ThreadLocal.withInitial { false }
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            val text = plaintext.decodeToString()
            if (!inInterference.get() && text.contains("\"attemptId\":\"${"1".repeat(32)}\"") &&
                text.contains("\"state\":\"unknown\"")) {
                val index = interferenceCount + 1
                onOriginalUnknownCandidate?.let { interfere ->
                    interferenceCount = index
                    inInterference.set(true)
                    try { interfere(index) } finally { inInterference.set(false) }
                }
            }
            return CipherEnvelope(ByteArray(12) { it.toByte() }, plaintext.copyOf())
        }
        override fun decrypt(envelope: CipherEnvelope): ByteArray = envelope.ciphertext.copyOf()
    }

    private class FailingCommitPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = FailingEditor(delegate.edit())

        private class FailingEditor(private val delegate: SharedPreferences.Editor) : SharedPreferences.Editor by delegate {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                delegate.putString(key, value)
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                delegate.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                delegate.clear()
                return this
            }
            override fun commit(): Boolean = false
        }
    }

    private companion object {
        const val UUID_INCARNATION = "11111111-2222-3333-4444-555555555555"
    }
}
