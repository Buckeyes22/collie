package com.lateapex.collie.ui

import com.lateapex.collie.network.UploadSource
import com.lateapex.collie.network.UploadCapability
import com.lateapex.collie.network.safeUploadFilename
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentUploadTest {
    private val policy = UploadCapability(1024, listOf("png", "jpg", "gif", "webp"), listOf("txt", "md", "json"))

    @Test fun providerMimeIsAdvisoryButConfiguredExtensionAndBytesControlAdmission() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        try {
            val accepted = DocumentAttachment.importStream("notes.txt", "image/jpeg", "safe notes\n".byteInputStream(), dir, policy)
            assertTrue(accepted is DocumentSelection.Ready)
            (accepted as DocumentSelection.Ready).source.dispose()
            val refused = DocumentAttachment.importStream("payload.exe", "text/plain", "safe".byteInputStream(), dir, policy)
            assertTrue(refused is DocumentSelection.Rejected)
            assertTrue(DocumentAttachment.importStream("no extension", "text/plain", "safe".byteInputStream(), dir, policy) is DocumentSelection.Rejected)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun revokedProviderAndBinaryRenamedAsTextAreRefusedWithoutSpoolLeak() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        try {
            assertTrue(DocumentAttachment.importStream("notes.txt", null, null, dir, policy) is DocumentSelection.Rejected)
            assertTrue(DocumentAttachment.importStream("notes.txt", "text/plain", byteArrayOf(0, 1, 2).inputStream(), dir, policy) is DocumentSelection.Rejected)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun svgIsAlwaysRefusedEvenWhenConfiguredAsAnExtraTextType() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        try {
            val extra = policy.copy(textTypes = policy.textTypes + "svg")
            assertTrue(DocumentAttachment.importStream("vector.svg", "image/svg+xml", "<svg/>".byteInputStream(), dir, extra) is DocumentSelection.Rejected)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun operatorEnabledExtraExtensionUsesSameTextValidation() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        try {
            val extra = policy.copy(textTypes = policy.textTypes + "trace")
            val result = DocumentAttachment.importStream("session.trace", "application/octet-stream", "trace line\n".byteInputStream(), dir, extra)
            assertTrue(result is DocumentSelection.Ready)
            (result as DocumentSelection.Ready).source.dispose()
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownLengthAndExactLimitAreCountedFromStream() {
        val file = File.createTempFile("collie-upload-test-", ".part")
        try {
            val total = DocumentAttachment.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), file, 4, Long.MAX_VALUE)
            assertEquals(4L, total)
            assertEquals(4L, file.length())
        } finally { file.delete() }
    }

    @Test fun oversizedNonseekableStreamAbortsAtLimit() {
        val reads = AtomicInteger()
        val input = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                java.util.Arrays.fill(buffer, offset, offset + minOf(length, 4), 1.toByte())
                return minOf(length, 4)
            }
        }
        val file = File.createTempFile("collie-upload-test-", ".part")
        try {
            try {
                DocumentAttachment.copyBounded(input, file, 5, Long.MAX_VALUE)
                fail("oversized stream should stop")
            } catch (_: DocumentAttachment.DocumentTooLarge) {
                assertTrue(file.length() <= 5L)
                assertTrue("reader must stop near the cap", reads.get() <= 2)
            }
        } finally { file.delete() }
    }

    @Test fun serverLimitBelowNativeLimitIsEnforcedAndPartialSpoolIsRemoved() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        try {
            val limited = policy.copy(maxBytes = 3)
            val result = DocumentAttachment.importStream("notes.txt", null, "four".byteInputStream(), dir, limited)
            assertEquals(DocumentSelection.Rejected("too large"), result)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedProviderReadRefusesAndDeletesPartialSpool() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-policy-").toFile()
        val input = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 1
                Thread.currentThread().interrupt()
                return 1
            }
        }
        try {
            assertTrue(DocumentAttachment.importStream("notes.txt", null, input, dir, policy) is DocumentSelection.Rejected)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            Thread.interrupted()
            dir.deleteRecursively()
        }
    }

    @Test fun stalledNonseekableProviderIsClosedAtDeadlineAndItsPartialSpoolIsRemoved() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-timeout-").toFile()
        val enteredRead = CountDownLatch(1)
        val released = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val input = object : InputStream() {
            @Volatile private var closed = false
            override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                enteredRead.countDown()
                while (!closed) {
                    try { released.await() } catch (_: InterruptedException) { /* Close, not interruption, owns cancellation. */ }
                }
                return -1
            }
            override fun close() { closed = true; released.countDown() }
        }
        try {
            val before = System.nanoTime()
            val result = DocumentAttachment.boundedProviderOperation("unreadable", timeoutMs = 100) { control ->
                control.own(input)
                try {
                    DocumentAttachment.importStream("notes.txt", null, input, dir, policy, control = control)
                } finally { workerFinished.countDown() }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertEquals(DocumentSelection.Rejected("unreadable"), result)
            assertTrue("operation must return at its deadline", elapsedMs < 1_000)
            assertTrue("fixture must actually enter its blocked read", enteredRead.await(1, TimeUnit.SECONDS))
            assertTrue("owned close must release the blocked provider read", workerFinished.await(2, TimeUnit.SECONDS))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun callerCancellationClosesOwnedProviderStreamAndWaitsForNoAutomaticRetry() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-cancel-").toFile()
        val enteredRead = CountDownLatch(1)
        val released = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val result = AtomicReference<DocumentSelection?>()
        val input = object : InputStream() {
            @Volatile private var closed = false
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                enteredRead.countDown()
                while (!closed) try { released.await() } catch (_: InterruptedException) { }
                return -1
            }
            override fun close() { closed = true; released.countDown() }
        }
        val caller = Thread {
            result.set(DocumentAttachment.boundedProviderOperation("unreadable", timeoutMs = 10_000) { control ->
                control.own(input)
                try { DocumentAttachment.importStream("notes.txt", null, input, dir, policy, control = control) }
                finally { workerFinished.countDown() }
            })
        }
        try {
            caller.start()
            assertTrue(enteredRead.await(1, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(1_000)
            assertFalse("cancelled caller must return promptly", caller.isAlive)
            assertEquals(DocumentSelection.Rejected("unreadable"), result.get())
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { caller.interrupt(); dir.deleteRecursively() }
    }

    @Test fun stalledCancelAndCloseReturnAtDeadlineKeepWorkerBudgetAndUnlinkPartialSpools() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-stalled-close-").toFile()
        val cancelEntered = CountDownLatch(DocumentAttachment.MAX_PROVIDER_WORKERS)
        val closeEntered = CountDownLatch(DocumentAttachment.MAX_PROVIDER_WORKERS)
        val readEntered = CountDownLatch(DocumentAttachment.MAX_PROVIDER_WORKERS)
        val releaseCancel = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val callers = mutableListOf<Thread>()
        val results = java.util.concurrent.CopyOnWriteArrayList<DocumentSelection>()
        fun awaitIgnoringInterrupt(latch: CountDownLatch) {
            while (true) try { latch.await(); return } catch (_: InterruptedException) { }
        }
        try {
            repeat(DocumentAttachment.MAX_PROVIDER_WORKERS) {
                callers += Thread {
                    results.add(DocumentAttachment.boundedProviderOperation("unreadable", 200) { control ->
                        control.signal.setOnCancelListener { cancelEntered.countDown(); awaitIgnoringInterrupt(releaseCancel) }
                        var reads = 0
                        val input = object : InputStream() {
                            override fun read(): Int = -1
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (reads++ == 0) { buffer[offset] = 'a'.code.toByte(); return 1 }
                                readEntered.countDown(); awaitIgnoringInterrupt(releaseRead); return -1
                            }
                            override fun close() { closeEntered.countDown(); awaitIgnoringInterrupt(releaseClose) }
                        }
                        control.own(input)
                        DocumentAttachment.importStream("notes.txt", null, input, dir, policy, control = control)
                    })
                }.apply { start() }
            }
            assertTrue(readEntered.await(2, TimeUnit.SECONDS))
            callers.forEach { it.join(1_000); assertFalse("deadline cannot wait on provider cancel", it.isAlive) }
            assertEquals(DocumentAttachment.MAX_PROVIDER_WORKERS, results.size)
            assertTrue(results.all { it == DocumentSelection.Rejected("unreadable") })
            assertTrue(cancelEntered.await(1, TimeUnit.SECONDS))
            assertTrue("partial private spools retire before blocked provider cleanup", dir.listFiles().orEmpty().isEmpty())
            assertEquals(0, DocumentAttachment.availableProviderSlots())
            var extraCalls = 0
            repeat(20) {
                assertEquals(DocumentSelection.Rejected("unreadable"), DocumentAttachment.boundedProviderOperation("unreadable", 100) {
                    extraCalls++; DocumentSelection.Rejected("unexpected")
                })
            }
            assertEquals("saturated ownership never starts replacement provider workers", 0, extraCalls)
            releaseCancel.countDown()
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(0, DocumentAttachment.availableProviderSlots())
            releaseClose.countDown(); releaseRead.countDown()
            awaitBudgetRestored()
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            releaseCancel.countDown(); releaseClose.countDown(); releaseRead.countDown()
            callers.forEach { it.join(2_000) }; awaitBudgetRestored(); dir.deleteRecursively()
        }
    }

    @Test fun interruptedCallerDisposesReadyPublishedBeforeWorkerCloseCompletes() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-published-ready-").toFile()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val source = AtomicReference<UploadSource.Document>()
        val result = AtomicReference<DocumentSelection>()
        val caller = Thread {
            result.set(DocumentAttachment.boundedProviderOperation("unreadable", 10_000) { control ->
                control.own(AutoCloseable {
                    closeEntered.countDown()
                    while (true) try { releaseClose.await(); break } catch (_: InterruptedException) { }
                })
                val file = File.createTempFile("collie-upload-", ".part", dir).apply { writeText("private ready") }
                assertTrue(control.ownSpool(file))
                DocumentSelection.Ready(UploadSource.Document("notes.txt", "text/plain", file, file.length()).also(source::set))
            })
        }
        try {
            caller.start(); assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, dir.listFiles().orEmpty().size)
            caller.interrupt(); caller.join(1_000)
            assertFalse(caller.isAlive)
            assertEquals(DocumentSelection.Rejected("unreadable"), result.get())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
            try { source.get().openOnce(); fail("retired Ready cannot be consumed") } catch (_: IllegalStateException) { }
            releaseClose.countDown(); awaitBudgetRestored()
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { caller.interrupt(); releaseClose.countDown(); caller.join(2_000); awaitBudgetRestored(); dir.deleteRecursively() }
    }

    @Test fun lateReadyAfterDeadlineIsDestroyedWithoutAnyOwnershipTransfer() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-late-ready-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lateSource = AtomicReference<UploadSource.Document>()
        try {
            val result = DocumentAttachment.boundedProviderOperation("unreadable", 100) { _ ->
                entered.countDown()
                while (true) try { release.await(); break } catch (_: InterruptedException) { }
                val file = File.createTempFile("collie-upload-", ".part", dir).apply { writeText("late content") }
                DocumentSelection.Ready(UploadSource.Document("notes.txt", "text/plain", file, file.length()).also(lateSource::set))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(DocumentSelection.Rejected("unreadable"), result)
            release.countDown(); awaitBudgetRestored()
            assertNotNull(lateSource.get())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
            try { lateSource.get().openOnce(); fail("late Ready cannot be delivered") } catch (_: IllegalStateException) { }
        } finally { release.countDown(); awaitBudgetRestored(); dir.deleteRecursively() }
    }

    @Test fun cancelledDispatcherHandoffDisposesReadyBeforeAnyActorConsumption() = runTest {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-handoff-").toFile()
        val readReady = CountDownLatch(1)
        val mayReturn = CountDownLatch(1)
        var consumed = 0
        try {
            val job = launch {
                DocumentAttachment.withSelection(read = {
                    val file = File.createTempFile("collie-upload-", ".part", dir).apply { writeText("owned") }
                    val ready = DocumentSelection.Ready(UploadSource.Document("notes.txt", "text/plain", file, file.length()))
                    readReady.countDown()
                    // Keep IO from returning during the initial dispatcher drain. Even an
                    // interrupted provider may return Ready; production must retain/dispose it.
                    while (true) try { mayReturn.await(); break } catch (_: InterruptedException) { }
                    ready
                }, consume = { consumed++ })
            }
            runCurrent()
            assertTrue(readReady.await(2, TimeUnit.SECONDS))
            mayReturn.countDown()
            job.cancel() // No actor dispatcher drain occurs between releasing IO and cancellation.
            runCurrent(); job.join()
            assertEquals(0, consumed)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { mayReturn.countDown(); dir.deleteRecursively() }
    }

    private fun awaitBudgetRestored() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (DocumentAttachment.availableProviderSlots() != DocumentAttachment.MAX_PROVIDER_WORKERS && System.nanoTime() < deadline) Thread.yield()
        assertEquals("all worker/cleanup ownership released", DocumentAttachment.MAX_PROVIDER_WORKERS, DocumentAttachment.availableProviderSlots())
    }

    @Test fun safeNamesDropProviderPathsAndControlCharacters() {
        assertEquals("notes_ draft.txt", DocumentAttachment.safeName("../../notes\u0000 draft.txt"))
        assertEquals("report_.txt", DocumentAttachment.safeName("report\u202E.txt"))
        assertEquals("report_final.txt", safeUploadFilename("C:\\private\\report\nfinal.txt"))
        assertEquals("attachment", DocumentAttachment.safeName("../../.."))
    }

    @Test fun documentSourceIsOneShotAndDisposeRemovesPrivateSpool() {
        val file = File.createTempFile("collie-upload-test-", ".part").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val source = UploadSource.Document("brief.txt", "text/plain", file, 3)
        assertArrayEquals(byteArrayOf(7, 8, 9), source.openOnce().use { it.readBytes() })
        try {
            source.openOnce()
            fail("consumed upload must not reopen")
        } catch (_: IllegalStateException) { }
        source.dispose()
        assertFalse(file.exists())
    }

    @Test fun processStartCleanupImmediatelyRemovesOnlyPrivateUploadNamespace() {
        val dir = kotlin.io.path.createTempDirectory(prefix = "collie-upload-cache-").toFile()
        try {
            val orphan = File(dir, "collie-upload-abcd.part").apply { writeText("secret") }
            val unrelated = File(dir, "other-cache-entry").apply { writeText("keep") }
            DocumentAttachment.cleanupPreviousProcessSpools(dir)
            assertFalse(orphan.exists())
            assertTrue(unrelated.exists())
            val liveUpload = File(dir, "collie-upload-live.part").apply { writeText("current") }
            // Pane creation does not run process-start cleanup again; concurrent live owners survive.
            assertTrue(liveUpload.exists())
        } finally { dir.deleteRecursively() }
    }
}
