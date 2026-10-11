package com.lateapex.collie.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ImageBlob
import java.net.URI
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

sealed interface ScopedImageResult {
    data class Ready(val lease: ScopedImageLease) : ScopedImageResult
    data class Refused(val message: String) : ScopedImageResult
    data object Unsupported : ScopedImageResult
}

/** A counted reference to a bitmap; close only after clearing every drawable that uses it. */
class ScopedImageLease internal constructor(
    val bitmap: Bitmap,
    private val onClose: () -> Unit,
    private val onRetain: () -> ScopedImageLease?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    fun retain(): ScopedImageLease? = if (closed.get()) null else onRetain()
    override fun close() { if (closed.compareAndSet(false, true)) onClose() }
}

/** Authenticated, bounded journal image reader. It never resolves agent supplied host names. */
class ScopedImageLoader(
    private val repository: CollieRepository,
    private val maxCacheBytes: Int = 8 * 1024 * 1024,
    private val maxResidentBytes: Int = 24 * 1024 * 1024,
) : AutoCloseable {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedSetOf<Job>()
    private val retirementListeners = CopyOnWriteArrayList<() -> Unit>()
    private val decodeSlots = Semaphore(2)
    private val entries = mutableMapOf<CacheKey, CachedBitmap>()
    private val cache = LinkedHashMap<CacheKey, CachedBitmap>(16, 0.75f, true)
    private var residentBytes = 0
    private var cachedBytes = 0
    private var reservedBytes = 0L
    private var pendingDeliveries = 0
    private var retirementGeneration = 0L
    private val invalidation = repository.onReadContextInvalidated { retire() }
    @Volatile private var closed = false

    init { require(maxCacheBytes > 0 && maxResidentBytes >= maxCacheBytes) }

    fun onRetired(listener: () -> Unit): AutoCloseable {
        retirementListeners += listener
        return AutoCloseable { retirementListeners -= listener }
    }

    fun isCurrent(context: ScopedReadContext?): Boolean = !closed && context != null && repository.isCurrent(context)

    internal fun residentBytesForTest(): Long = synchronized(lock) { residentBytes.toLong() + reservedBytes }
    internal fun committedBitmapBytesForTest(): Long = synchronized(lock) { residentBytes.toLong() }
    internal fun pendingDeliveriesForTest(): Int = synchronized(lock) { pendingDeliveries }

    fun load(
        context: ScopedReadContext?,
        reference: String?,
        viewportWidth: Int,
        viewportHeight: Int,
        callback: (ScopedImageResult) -> Unit,
        expectedMimeType: String? = null,
    ): AutoCloseable {
        val requestRetirementGeneration = synchronized(lock) { retirementGeneration.takeUnless { closed } }
        if (context == null || reference == null || reference.length > MAX_REFERENCE_CHARS || requestRetirementGeneration == null || !repository.isCurrent(context)) {
            callback(ScopedImageResult.Refused("Image reference is unavailable or outside this connection"))
            return AutoCloseable { }
        }
        val width = viewportWidth.coerceAtLeast(1)
        val height = viewportHeight.coerceAtLeast(1)
        val cancelled = AtomicBoolean(false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var pendingReadyLease: ScopedImageLease? = null
            fun ready(lease: ScopedImageLease) = ScopedImageResult.Ready(lease).also { pendingReadyLease = lease }
            val result = try {
                decodeSlots.withPermit {
                    if (!isGenerationCurrent(requestRetirementGeneration)) throw StaleImage()
                    val parsed = parse(reference, context) ?: throw RefusedImage()
                    if (!isGenerationCurrent(requestRetirementGeneration) || !repository.isCurrent(context)) throw StaleImage()
                    val declaredMime = expectedMimeType?.substringBefore(';')?.trim()?.lowercase()
                    if (declaredMime != null && declaredMime !in SUPPORTED_MIME) throw UnsupportedImage()
                    val key = CacheKey(context.key, context.navigationGeneration, parsed.key, width, height)
                    val existing = synchronized(lock) { if (closed) null else cache[key] ?: entries[key] }
                    if (existing != null) {
                        if (declaredMime != null && existing.mime != declaredMime) throw UnsupportedImage()
                        val retained = acquire(key, existing) ?: throw StaleImage()
                        ready(retained)
                    } else {
                        var responseMime: String? = null
                        val bytes = when (parsed) {
                            is ImageReference.Inline -> parsed.bytes
                            is ImageReference.Blob -> when (val response = repository.imageBlob(context, parsed.sha256)) {
                                is ApiResult.Success -> {
                                    responseMime = response.value.contentType
                                    response.value.bytes
                                }
                                is ApiResult.Failure -> if (response.error is com.lateapex.collie.network.ApiFailure.Http && response.error.status == 404) {
                                    throw MissingImage()
                                } else throw ImageUnavailable()
                                is ApiResult.NotModified -> throw ImageUnavailable()
                            }
                        }
                        val mime = sniff(bytes) ?: throw UnsupportedImage()
                        if ((declaredMime != null && declaredMime != mime) || (responseMime != null && responseMime != mime)) throw UnsupportedImage()
                        val bounds = dimensions(bytes)
                        val sample = sampleSize(bounds.first, bounds.second, width, height)
                        if (!isGenerationCurrent(requestRetirementGeneration) || !repository.isCurrent(context)) throw StaleImage()
                        val reservation = reserveBytes(estimateBytes(bounds.first, bounds.second, sample)) ?: throw MemoryBudgetExceeded()
                        var reservationHeld = true
                        fun releaseReservationOnce() {
                            if (reservationHeld) {
                                releaseReservation(reservation)
                                reservationHeld = false
                            }
                        }
                        val bitmap = try { decode(bytes, mime, sample) }
                        catch (error: Throwable) { releaseReservationOnce(); throw error }
                            ?: run { releaseReservationOnce(); throw UnsupportedImage() }
                        try {
                            if (!isGenerationCurrent(requestRetirementGeneration) || !repository.isCurrent(context)) {
                                bitmap.recycle()
                                releaseReservationOnce()
                                throw StaleImage()
                            }
                            val committed = try {
                                synchronized(lock) {
                                    if (closed || cancelled.get() || retirementGeneration != requestRetirementGeneration || !repository.isCurrent(context)) {
                                        null
                                    } else if (bitmap.allocationByteCount > reservation) {
                                        null
                                    } else {
                                        val existingAfterDecode = entries[key]
                                        if (existingAfterDecode != null) {
                                            if (existingAfterDecode.retired || existingAfterDecode.mime != mime) null else {
                                                if (existingAfterDecode.cached) {
                                                    cache.remove(key)
                                                    cachedBytes -= existingAfterDecode.bytes
                                                    existingAfterDecode.cached = false
                                                }
                                                existingAfterDecode.references++
                                                lease(key, existingAfterDecode) to false
                                            }
                                        } else {
                                            // Atomically exchange the conservative reservation for
                                            // actual resident accounting before exposing this bitmap.
                                            reservedBytes -= reservation
                                            reservationHeld = false
                                            val entry = CachedBitmap(bitmap, mime, bitmap.allocationByteCount, references = 1)
                                            entries[key] = entry
                                            residentBytes += entry.bytes
                                            lease(key, entry) to true
                                        }
                                    }
                                }
                            } catch (error: Throwable) {
                                bitmap.recycle()
                                releaseReservationOnce()
                                throw error
                            }
                            if (committed == null) {
                                bitmap.recycle()
                                releaseReservationOnce()
                                if (!isGenerationCurrent(requestRetirementGeneration) || !repository.isCurrent(context)) throw StaleImage()
                                throw MemoryBudgetExceeded()
                            }
                            if (!committed.second) {
                                // A second concurrent decode found an existing cache entry. Keep
                                // its reservation charged until its duplicate allocation is gone.
                                bitmap.recycle()
                                releaseReservationOnce()
                            }
                            ready(committed.first)
                        } finally {
                            releaseReservationOnce()
                        }
                    }
                }
            } catch (_: CancellationException) {
                pendingReadyLease?.close()
                return@launch
            }
            catch (_: RefusedImage) { ScopedImageResult.Refused("Image reference is unavailable or outside this connection") }
            catch (_: MissingImage) { ScopedImageResult.Refused("Image is no longer available") }
            catch (_: StaleImage) { ScopedImageResult.Refused("Image access changed; reload the transcript") }
            catch (_: UnsupportedImage) { ScopedImageResult.Unsupported }
            catch (_: MemoryBudgetExceeded) { ScopedImageResult.Refused("Too many journal images are currently open") }
            catch (_: Exception) { ScopedImageResult.Refused("Image could not be loaded") }
            finally { synchronized(lock) { jobs.remove(coroutineContext[Job]) } }
            synchronized(lock) { pendingDeliveries++ }
            val deliver = Runnable {
                try {
                    val allowed = !cancelled.get() && isGenerationCurrent(requestRetirementGeneration) && repository.isCurrent(context)
                    if (!allowed) (result as? ScopedImageResult.Ready)?.lease?.close()
                    else try { callback(result) }
                    catch (error: Throwable) {
                        (result as? ScopedImageResult.Ready)?.lease?.close()
                        throw error
                    }
                } finally {
                    synchronized(lock) { pendingDeliveries-- }
                }
            }
            if (!main.post(deliver)) {
                synchronized(lock) { pendingDeliveries-- }
                (result as? ScopedImageResult.Ready)?.lease?.close()
            }
        }
        synchronized(lock) { if (closed) job.cancel() else jobs += job }
        job.start()
        return AutoCloseable {
            cancelled.set(true)
            job.cancel()
        }
    }

    /** Called synchronously by authority retirement; no old actor can repopulate this cache. */
    fun retire() {
        synchronized(lock) {
            retirementGeneration++
            jobs.toList().forEach(Job::cancel)
            jobs.clear()
        }
        retirementListeners.forEach { listener -> runCatching(listener) }
        synchronized(lock) {
            cache.clear()
            cachedBytes = 0
            entries.toList().forEach { (key, entry) ->
                entry.cached = false
                entry.retired = true
                if (entry.references == 0) removeEntry(key, entry)
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        retire()
        invalidation.close()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun parse(raw: String, context: ScopedReadContext): ImageReference? {
        val data = DATA_URI.matchEntire(raw)
        if (data == null) {
            val declaredDataMime = DATA_IMAGE_MIME.find(raw)?.groupValues?.getOrNull(1)?.lowercase()
            if (declaredDataMime != null && declaredDataMime !in SUPPORTED_MIME) throw UnsupportedImage()
        }
        if (data != null) {
            val mime = data.groupValues[1].lowercase()
            val encoded = data.groupValues[2]
            if (encoded.length > MAX_BASE64_CHARS || encoded.length % 4 != 0) return null
            val decoded = try { android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP) } catch (_: IllegalArgumentException) { return null }
            if (decoded.isEmpty() || decoded.size > ImageBlob.MAX_BYTES || sniff(decoded) != mime) return null
            return ImageReference.Inline("data:$mime:${sha256(decoded)}", decoded)
        }
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val path = uri.rawPath ?: return null
        val match = BLOB_PATH.matchEntire(path) ?: return null
        val query = uri.rawQuery
        if (query != null) {
            val values = query.split('&').map { item ->
                val pair = item.split('=', limit = 2)
                if (pair.size != 2) return null
                if (pair[0] !in setOf("host", "session") || '+' in pair[1]) return null
                try { pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8") } catch (_: Exception) { return null }
            }
            if (values.map { it.first }.toSet().size != values.size || values.any { it.first !in setOf("host", "session") }) return null
            val expected = listOfNotNull(context?.key?.host?.let { "host" to it }, context?.key?.session?.let { "session" to it }).toMap()
            if (values.toMap() != expected) return null
        }
        return ImageReference.Blob(match.groupValues[1].lowercase())
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw UnsupportedImage()
        return bounds.outWidth to bounds.outHeight
    }

    private fun decode(bytes: ByteArray, mime: String, sample: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (info.size.width.toLong() * info.size.height > MAX_PIXELS) throw UnsupportedImage()
                if (info.mimeType.lowercase() != mime) throw UnsupportedImage()
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        if (bounds.outMimeType?.lowercase() != mime) throw UnsupportedImage()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        val ratio = maxOf(sourceWidth / targetWidth.coerceAtLeast(1), sourceHeight / targetHeight.coerceAtLeast(1)).coerceAtLeast(1)
        return Integer.highestOneBit(ratio)
    }

    private fun estimateBytes(sourceWidth: Int, sourceHeight: Int, sample: Int): Long {
        val width = (sourceWidth.toLong() + sample - 1) / sample
        val height = (sourceHeight.toLong() + sample - 1) / sample
        // ImageDecoder may keep the embedded wide-gamut color space as RGBA_F16 (8 bytes/pixel).
        // Reserve against that upper bound before allocation; actual allocationByteCount is
        // still checked and charged after decode.
        val rowBytes = ((width * MAX_BYTES_PER_PIXEL + ROW_ALIGNMENT_BYTES - 1) / ROW_ALIGNMENT_BYTES) * ROW_ALIGNMENT_BYTES
        return rowBytes * height
    }

    private fun reserveBytes(bytes: Long): Long? = synchronized(lock) {
        if (closed || bytes <= 0 || bytes > maxResidentBytes) return@synchronized null
        while (residentBytes.toLong() + reservedBytes + bytes > maxResidentBytes) {
            val victim = cache.entries.firstOrNull { it.value.references == 0 } ?: return@synchronized null
            cache.remove(victim.key)
            victim.value.cached = false
            cachedBytes -= victim.value.bytes
            removeEntry(victim.key, victim.value)
        }
        reservedBytes += bytes
        bytes
    }

    private fun releaseReservation(bytes: Long) = synchronized(lock) {
        reservedBytes = (reservedBytes - bytes).coerceAtLeast(0)
    }

    private fun isGenerationCurrent(expected: Long): Boolean = synchronized(lock) {
        !closed && retirementGeneration == expected
    }

    private fun trimCache() {
        while (cachedBytes > maxCacheBytes && cache.isNotEmpty()) {
            val eldest = cache.entries.first()
            cache.remove(eldest.key)
            eldest.value.cached = false
            cachedBytes -= eldest.value.bytes
            if (eldest.value.references == 0) removeEntry(eldest.key, eldest.value)
        }
    }

    private fun removeEntry(key: CacheKey, entry: CachedBitmap) {
        if (entries.remove(key, entry)) {
            check(entry.references == 0) { "cannot remove a bitmap while a view lease is active" }
            residentBytes -= entry.bytes
            if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
        }
        if (entry.cached) {
            cache.remove(key)
            cachedBytes -= entry.bytes
            entry.cached = false
        }
    }

    private fun lease(key: CacheKey, entry: CachedBitmap): ScopedImageLease = ScopedImageLease(
        bitmap = entry.bitmap,
        onClose = { release(key, entry) },
        onRetain = { retain(key, entry) },
    )

    private fun acquire(key: CacheKey, entry: CachedBitmap): ScopedImageLease? = synchronized(lock) {
        if (closed || entry.retired || entries[key] !== entry) return@synchronized null
        if (entry.cached) {
            cache.remove(key)
            cachedBytes -= entry.bytes
            entry.cached = false
        }
        entry.references++
        lease(key, entry)
    }

    private fun retain(key: CacheKey, entry: CachedBitmap): ScopedImageLease? = synchronized(lock) {
        if (closed || entry.retired || entries[key] !== entry) return@synchronized null
        entry.references++
        lease(key, entry)
    }

    private fun release(key: CacheKey, entry: CachedBitmap) = synchronized(lock) {
        if (entries[key] !== entry) return@synchronized
        if (entry.references > 0) entry.references--
        if (entry.references == 0 && !entry.cached) {
            if (!entry.retired && entry.bytes <= maxCacheBytes) {
                entry.cached = true
                cache[key] = entry
                cachedBytes += entry.bytes
                trimCache()
            } else removeEntry(key, entry)
        }
    }

    private fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private sealed interface ImageReference {
        val key: String
        data class Blob(val sha256: String) : ImageReference { override val key get() = "blob:$sha256" }
        data class Inline(override val key: String, val bytes: ByteArray) : ImageReference
    }
    private data class CacheKey(val actor: ScopedRequestKey, val navigationGeneration: Long, val content: String, val width: Int, val height: Int)
    private class CachedBitmap(
        val bitmap: Bitmap,
        val mime: String,
        val bytes: Int,
        var references: Int,
        var cached: Boolean = false,
        var retired: Boolean = false,
    )
    private class MissingImage : Exception()
    private class ImageUnavailable : Exception()
    private class StaleImage : Exception()
    private class UnsupportedImage : Exception()
    private class MemoryBudgetExceeded : Exception()
    private class RefusedImage : Exception()

    companion object {
        private const val MAX_PIXELS = 16_000_000L
        private const val MAX_BYTES_PER_PIXEL = 8L
        private const val ROW_ALIGNMENT_BYTES = 64L
        private const val MAX_REFERENCE_CHARS = 14 * 1024 * 1024
        private const val MAX_BASE64_CHARS = ((ImageBlob.MAX_BYTES + 2) / 3) * 4
        private val SUPPORTED_MIME = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val BLOB_PATH = Regex("/api/blobs/([0-9a-fA-F]{64})")
        private val DATA_URI = Regex("data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/]*={0,2})", RegexOption.IGNORE_CASE)
        private val DATA_IMAGE_MIME = Regex("^data:(image/[^;,]+);base64,", RegexOption.IGNORE_CASE)
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
