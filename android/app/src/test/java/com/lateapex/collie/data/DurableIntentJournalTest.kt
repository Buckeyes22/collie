package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class DurableIntentJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = object : SecretCipher {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            val engine = Cipher.getInstance("AES/GCM/NoPadding"); engine.init(Cipher.ENCRYPT_MODE, key)
            return CipherEnvelope(engine.iv, engine.doFinal(plaintext))
        }
        override fun decrypt(envelope: CipherEnvelope): ByteArray {
            val engine = Cipher.getInstance("AES/GCM/NoPadding"); engine.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            return engine.doFinal(envelope.ciphertext)
        }
    }
    private val first = "5a19f221-8e1e-45d5-a2b5-6aa7d213f83b"
    private val second = "9c8f9d54-b544-4807-ac61-8b2baf884e40"
    private val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private-bearer")
    private val address = PaneAddress(Scope("host1", "work"), "pane1")
    private val owner = DurableIntentOwner.from(connection, address)
    @Before fun reset() { File(context.noBackupFilesDir, "durable-intents-v1").deleteRecursively() }
    private fun body(id: String, text: String = "Private draft λ 🐕") = buildJsonObject { put("requestId", id); put("turnId", "turn1"); put("text", text) }.toString()
    @Test fun exactIntentSurvivesRestartAndDoesNotStoreCredentialsOrDraftInPlaintext() {
        val journal = DurableIntentJournal(context, cipher)
        assertTrue(journal.reserve(owner, "send", first, body(first)))
        val pending = DurableIntentJournal(context, cipher).read(owner) as DurableIntentJournal.Lookup.Pending
        assertEquals(body(first), pending.record.requestJson)
        val bytes = File(context.noBackupFilesDir, "durable-intents-v1").listFiles()!!.single().readText()
        assertFalse(bytes.contains("private-bearer")); assertFalse(bytes.contains("Private draft")); assertFalse(bytes.contains("collie.example"))
        assertFalse(pending.record.toString().contains("Private draft")); assertFalse(owner.toString().contains("collie.example"))
    }
    @Test fun unresolvedIntentCannotBeReplacedAndLateOriginalReceiptCannotEraseSuccessor() {
        val journal = DurableIntentJournal(context, cipher)
        assertTrue(journal.reserve(owner, "send", first, body(first)))
        assertTrue(journal.reserve(owner, "send", first, body(first)))
        assertFalse(journal.reserve(owner, "send", first, body(first, "changed")))
        assertFalse(journal.reserve(owner, "send", second, body(second)))
        assertFalse(journal.clear(owner, second)); assertTrue(journal.clear(owner, first))
        assertTrue(journal.reserve(owner, "send", second, body(second)))
        assertFalse(journal.clear(owner, first))
        assertEquals(second, (journal.read(owner) as DurableIntentJournal.Lookup.Pending).record.requestId)
    }
    @Test fun exactOwnerSeparatesCredentialHostSessionAndPaneAndCorruptionBlocksReservation() {
        val journal = DurableIntentJournal(context, cipher)
        assertTrue(journal.reserve(owner, "send", first, body(first)))
        for (different in listOf(owner.copy(host = "host2"), owner.copy(session = "other"), owner.copy(paneId = "pane2"),
            DurableIntentOwner.from(Connection(connection.origin, "phone", "different-token"), address))) assertEquals(DurableIntentJournal.Lookup.Empty, journal.read(different))
        val file = File(context.noBackupFilesDir, "durable-intents-v1").listFiles()!!.single()
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertEquals(DurableIntentJournal.Lookup.Unreadable, journal.read(owner))
        assertFalse(journal.reserve(owner, "send", second, body(second))); assertFalse(journal.clear(owner, first))
    }
    @Test fun explicitCorruptionRecoveryAndRetirementCannotEraseAnotherOwner() {
        val journal = DurableIntentJournal(context, cipher)
        val other = owner.copy(paneId = "pane2")
        assertTrue(journal.reserve(owner, "send", first, body(first)))
        assertTrue(journal.reserve(other, "send", second, body(second)))
        assertFalse(journal.discardUnreadable(owner))
        assertTrue(journal.retire(owner))
        assertEquals(second, (journal.read(other) as DurableIntentJournal.Lookup.Pending).record.requestId)
        File(context.noBackupFilesDir, "durable-intents-v1").listFiles()!!.single().writeBytes(byteArrayOf(1, 2))
        assertEquals(DurableIntentJournal.Lookup.Unreadable, journal.read(other))
        assertTrue(journal.discardUnreadable(other))
        assertTrue(journal.reserve(other, "send", second, body(second)))
    }
    @Test fun separateJournalInstancesCannotReserveTwoDifferentRequestsConcurrently() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val firstResult = java.util.concurrent.atomic.AtomicBoolean()
        val secondResult = java.util.concurrent.atomic.AtomicBoolean()
        val a = Thread { gate.await(); firstResult.set(DurableIntentJournal(context, cipher).reserve(owner, "send", first, body(first))) }
        val b = Thread { gate.await(); secondResult.set(DurableIntentJournal(context, cipher).reserve(owner, "send", second, body(second))) }
        a.start(); b.start(); gate.countDown(); a.join(5000); b.join(5000)
        assertFalse(a.isAlive); assertFalse(b.isAlive)
        assertTrue(firstResult.get() xor secondResult.get())
    }
    @Test fun malformedAndMismatchedRequestsNeverReserve() {
        val journal = DurableIntentJournal(context, cipher)
        assertFalse(journal.reserve(owner, "answer", first, body(first)))
        assertFalse(journal.reserve(owner, "abort", first, body(first)))
        assertFalse(journal.reserve(owner, "send", first, body(second)))
        assertFalse(journal.reserve(owner, "send", first, body(first, "🐕".repeat(8193))))
        assertEquals(DurableIntentJournal.Lookup.Empty, journal.read(owner))
    }
}
