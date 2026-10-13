package com.lateapex.collie.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.lateapex.collie.network.UploadCapability
import com.lateapex.collie.network.UploadSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal sealed interface DocumentSelection {
    data class Ready(val source: UploadSource.Document) : DocumentSelection
    data class Rejected(val reason: String) : DocumentSelection
}

/** Bounded SAF import into app-private cache. The returned file is consumed once and deleted on close. */
internal object DocumentAttachment {
    const val NATIVE_MAX_BYTES = 10L * 1024 * 1024
    internal const val MAX_PROVIDER_OPERATION_MS = 15_000L
    private const val CHUNK = 16 * 1024

    /** Called once by Application.onCreate, before this process can own any upload spools. */
    fun cleanupPreviousProcessSpools(cacheDir: File) {
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("collie-upload-") }?.forEach { it.delete() }
    }

    fun import(
        resolver: ContentResolver,
        uri: Uri,
        cacheDir: File,
        capability: UploadCapability,
        fallbackName: String,
        unreadable: String,
        unsupported: String,
        tooLarge: String,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): DocumentSelection {
        return boundedProviderOperation(unreadable) { control ->
            val name = safeName(queryName(resolver, uri, control.signal) ?: fallbackName)
            // Provider MIME is deliberately advisory; the byte and extension checks below decide type.
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", control.signal) ?: return@boundedProviderOperation DocumentSelection.Rejected(unreadable)
            control.own(descriptor)
            val input = descriptor.createInputStream()
            control.own(input)
            importStream(name, null, input, cacheDir, capability, unreadable, unsupported, tooLarge, progress, control)
        }
    }

    /** Own Ready before the cancellable IO→actor handoff, until dispatch/consumption completes. */
    internal suspend fun withSelection(
        read: () -> DocumentSelection,
        consume: suspend (DocumentSelection) -> Unit,
    ) {
        val retained = java.util.concurrent.atomic.AtomicReference<UploadSource.Document?>()
        try {
            val selection = runInterruptible(Dispatchers.IO) {
                read().also { retained.set((it as? DocumentSelection.Ready)?.source) }
            }
            consume(selection)
        } finally { retained.getAndSet(null)?.dispose() }
    }

    // A hostile provider may ignore interruption or block in cancel/close. Keep its slot until
    // worker AND cancellation cleanup finish; replacements cannot create unbounded daemon threads.
    internal const val MAX_PROVIDER_WORKERS = 2
    internal fun availableProviderSlots(): Int = providerSlots.availablePermits()
    private val providerSlots = java.util.concurrent.Semaphore(MAX_PROVIDER_WORKERS)

    internal fun boundedProviderOperation(
        unreadable: String,
        timeoutMs: Long = MAX_PROVIDER_OPERATION_MS,
        operation: (ProviderReadControl) -> DocumentSelection,
    ): DocumentSelection {
        require(timeoutMs > 0)
        if (!providerSlots.tryAcquire()) return DocumentSelection.Rejected(unreadable)
        val parts = java.util.concurrent.atomic.AtomicInteger(2) // caller and provider worker
        fun releasePart() { if (parts.decrementAndGet() == 0) providerSlots.release() }
        val control = ProviderReadControl {
            parts.incrementAndGet()
            val release: () -> Unit = { releasePart() }
            release
        }
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread({
            try {
                control.publish(operation(control))
            } catch (_: Exception) {
                control.publish(DocumentSelection.Rejected(unreadable))
            } finally {
                try { control.close() } finally { done.countDown(); releasePart() }
            }
        }, "collie-document-provider").apply { isDaemon = true }
        try {
            try { worker.start() } catch (failure: Throwable) { releasePart(); throw failure }
            val completed = try { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) {
                    control.cancel(); worker.interrupt()
                    Thread.currentThread().interrupt()
                    return DocumentSelection.Rejected(unreadable)
                }
            if (!completed || Thread.currentThread().isInterrupted) {
                control.cancel(); worker.interrupt()
                return DocumentSelection.Rejected(unreadable)
            }
            return control.takeResult() ?: DocumentSelection.Rejected(unreadable)
        } finally { releasePart() }
    }

    internal class ProviderReadControl(private val retainCleanup: () -> (() -> Unit) = { {} }) {
        val signal = CancellationSignal()
        private val lock = Any()
        private val owned = mutableListOf<AutoCloseable>()
        private val spools = mutableSetOf<File>()
        private var cancelled = false
        private var result: DocumentSelection? = null

        fun own(resource: AutoCloseable) {
            val reject = synchronized(lock) { if (cancelled) true else { owned.add(resource); false } }
            // Late provider handles close on their already-budgeted provider worker, outside locks.
            if (reject) runCatching { resource.close() }
        }

        fun ownSpool(file: File): Boolean = synchronized(lock) {
            if (cancelled) false else { spools.add(file); true }
        }

        fun publish(value: DocumentSelection) {
            val reject = synchronized(lock) { if (cancelled) true else { result = value; false } }
            if (reject) (value as? DocumentSelection.Ready)?.source?.dispose()
        }

        fun takeResult(): DocumentSelection? = synchronized(lock) {
            if (cancelled) null else result.also { result = null; spools.clear() }
        }

        fun cancel() {
            val retired = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                Triple(result, spools.toList(), owned.toList()).also {
                    result = null; spools.clear(); owned.clear()
                }
            }
            // App-private file ownership is retired immediately, before any arbitrary provider IPC.
            (retired.first as? DocumentSelection.Ready)?.source?.dispose()
            retired.second.forEach { it.delete() }
            val release = retainCleanup()
            Thread({
                try {
                    runCatching { signal.cancel() }
                    retired.third.asReversed().forEach { runCatching { it.close() } }
                } finally { release() }
            }, "collie-document-cancel").apply { isDaemon = true; start() }
        }

        fun close() {
            val handles = synchronized(lock) { owned.toList().also { owned.clear() } }
            handles.asReversed().forEach { runCatching { it.close() } }
        }
    }

    internal fun importStream(
        rawName: String,
        @Suppress("UNUSED_PARAMETER") providerMime: String?,
        input: InputStream?,
        cacheDir: File,
        capability: UploadCapability,
        unreadable: String = "unreadable",
        unsupported: String = "unsupported",
        tooLarge: String = "too large",
        progress: (Long, Long) -> Unit = { _, _ -> },
        control: ProviderReadControl? = null,
    ): DocumentSelection {
        val name = safeName(rawName)
        val dot = name.lastIndexOf('.')
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { dot > 0 && dot < name.lastIndex && it.matches(Regex("[a-z0-9]+")) }
            ?: return DocumentSelection.Rejected(unsupported)
        val textTypes = capability.textTypes.map { it.removePrefix(".").lowercase() }.toSet()
        val imageTypes = capability.imageTypes.map { it.removePrefix(".").lowercase() }.toSet()
        val allowed = textTypes + imageTypes
        if (extension !in allowed || extension == "svg") return DocumentSelection.Rejected(unsupported)
        if (capability.maxBytes <= 0L) return DocumentSelection.Rejected(unsupported)
        val maximum = minOf(NATIVE_MAX_BYTES, capability.maxBytes)
        val file = File.createTempFile("collie-upload-", ".part", cacheDir)
        try {
            val stream = input ?: return reject(file, unreadable)
            val count = try { stream.use { copyBounded(it, file, maximum, Long.MAX_VALUE, control) } }
                catch (_: SecurityException) { return reject(file, unreadable) }
                catch (_: DocumentTooLarge) { return reject(file, tooLarge) }
                catch (_: IOException) { return reject(file, unreadable) }
            if (count <= 0L) return reject(file, unreadable)
            val detected = detectType(file, extension) ?: return reject(file, unsupported)
            if (detected.image && extension !in imageTypes || !detected.image && extension !in textTypes) {
                return reject(file, unsupported)
            }
            if (!detected.image && !looksLikeText(file)) return reject(file, unsupported)
            return DocumentSelection.Ready(UploadSource.Document(name, detected.mime, file, count, progress))
        } catch (_: IOException) {
            return reject(file, unreadable)
        } catch (_: RuntimeException) {
            return reject(file, unreadable)
        }
    }

    private data class Detected(val mime: String, val image: Boolean)

    private fun detectType(file: File, extension: String): Detected? {
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        val image = when (extension) {
            "png" -> header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            "jpg" -> header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "gif" -> String(header, 0, 4, Charsets.US_ASCII).startsWith("GIF8")
            "webp" -> String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            else -> false
        }
        if (extension in IMAGE_EXTENSIONS) {
            if (!image) return null
            return Detected("image/${if (extension == "jpg") "jpeg" else extension}", true)
        }
        return if (image || extension == "svg") null else Detected("text/plain", false)
    }

    private fun looksLikeText(file: File): Boolean = try {
        file.inputStream().use { input ->
            repeat(4096) {
                val byte = input.read()
                if (byte < 0) return true
                if (byte == 0x7f || (byte < 0x20 && byte !in TEXT_CONTROLS)) return false
            }
            true
        }
    } catch (_: IOException) { false }

    internal fun copyBounded(input: InputStream, file: File, maximum: Long, deadline: Long, control: ProviderReadControl? = null): Long {
        var total = 0L
        FileOutputStream(file).use { output ->
            // Open BEFORE registration: cancellation may unlink the path but cannot cause this
            // already-open output to recreate it when a blocked provider eventually returns.
            if (control != null && !control.ownSpool(file)) { file.delete(); throw IOException("Document read cancelled") }
            val buffer = ByteArray(CHUNK)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Document read cancelled")
                if (System.nanoTime() / 1_000_000L > deadline) throw IOException("Document provider timed out")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (total > maximum - read) throw DocumentTooLarge()
                output.write(buffer, 0, read)
                total += read
            }
            output.fd.sync()
        }
        return total
    }

    internal class DocumentTooLarge : IOException()

    private fun queryName(resolver: ContentResolver, uri: Uri, signal: CancellationSignal): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) { null }

    internal fun safeName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = leaf.map {
            val category = Character.getType(it)
            if (it.code < 0x20 || it.code == 0x7f || category == Character.FORMAT.toInt() ||
                category == Character.LINE_SEPARATOR.toInt() || category == Character.PARAGRAPH_SEPARATOR.toInt() || it in "\"\\/"
            ) '_' else it
        }.joinToString("")
            .trim().trim('.').take(120)
        return clean.ifBlank { "attachment" }
    }

    private fun reject(file: File, message: String): DocumentSelection.Rejected { file.delete(); return DocumentSelection.Rejected(message) }
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "gif", "webp")
    private val TEXT_CONTROLS = setOf(0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1b)
}
