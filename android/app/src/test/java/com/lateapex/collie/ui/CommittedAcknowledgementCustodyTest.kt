package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CommittedAcknowledgementCustodyTest {
    @Test fun actualCommittedSameObjectAbaCannotRestoreAcknowledgedInputWhileSameLiveRecommitRetainsIt() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val preferences = app.getSharedPreferences("ack-custody-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(ByteArray(12), plaintext.map { (it.toInt() xor 90).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 90).toByte() }.toByteArray()
        }
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, preferences)
        val a = Connection(CollieOrigin("https://ack.fixture/"), "A", "paired-a")
        val b = Connection(a.origin, "B", "paired-b")
        store.save(a)
        val repository = CollieRepository(app.container.api, store, app.container.originValidator)
        val vm = PaneViewModel(app, PaneAddress(paneId = "pane"), "codex", repository)
        fun settle() {
            val done = CountDownLatch(1)
            val observer = store.observeCommitted { done.countDown() }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (done.count != 0L && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    dispatcher.scheduler.runCurrent()
                    Thread.yield()
                }
                assertEquals(0L, done.count)
                dispatcher.scheduler.runCurrent()
            }
            finally { observer.close() }
        }
        // Exercise the owned local acknowledgement cache itself; the original complete submit
        // test still proves terminal acceptance/no replay. This seam performs no network read.
        fun acknowledgements(): CodexAcceptedReplies = vm.javaClass.getDeclaredMethod("acceptedReplies").run {
            isAccessible = true; invoke(vm) as CodexAcceptedReplies
        }
        try {
            val original = repository.readContext()!!
            settle()
            val input = acknowledgements()
            input.accept("A-local-input", null)
            store.save(a); settle()
            assertSame(input, acknowledgements())
            assertEquals(listOf("A-local-input"), acknowledgements().texts)
            store.save(b); repository.readContext()!!; settle()
            assertTrue(acknowledgements().texts.isEmpty())
            store.save(a)
            val fresh = repository.readContext()!!
            settle()
            assertNotEquals(original.connectionIncarnation, fresh.connectionIncarnation)
            assertTrue(repository.isActorRetired(original))
            assertTrue(acknowledgements().texts.isEmpty())
            assertNotSame(input, acknowledgements())
            acknowledgements().accept("fresh-A-input", null)
            store.save(a); settle()
            assertEquals(listOf("fresh-A-input"), acknowledgements().texts)
            assertTrue(repository.isCurrent(fresh))
        } finally { preferences.edit().clear().commit(); vm.stopPolling(); Dispatchers.resetMain() }
    }
}
