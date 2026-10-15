package com.lateapex.collie.ui

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** QR-only, local image decode. The document grant is neither persisted nor forwarded. */
internal object LinkImportQr {
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_SIDE = 2048

    suspend fun read(resolver: ContentResolver, uri: Uri): String {
        require(uri.scheme == "content") { "QR document is unavailable" }
        return runInterruptible(Dispatchers.IO) {
            readProvider { control ->
                val descriptor = resolver.openAssetFileDescriptor(uri, "r", control.signal)
                    ?: throw IOException("QR document is unavailable")
                control.own(descriptor)
                descriptor.createInputStream()
            }
        }
    }

    /** Share upload's bounded provider slots/cancellation custody; decoding retains that same slot. */
    internal fun readProvider(
        timeoutMs: Long = DocumentAttachment.MAX_PROVIDER_OPERATION_MS,
        open: (DocumentAttachment.ProviderReadControl) -> InputStream,
    ): String {
        require(timeoutMs in 1..DocumentAttachment.MAX_PROVIDER_OPERATION_MS)
        val decoded = java.util.concurrent.atomic.AtomicReference<String?>()
        val completed = DocumentSelection.Rejected("QR decode completed")
        val failure = "QR document is unavailable"
        try {
            val result = DocumentAttachment.boundedProviderOperation(failure, timeoutMs) { control ->
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                val input = open(control)
                control.own(input)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var zeroReads = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw IOException(failure)
                    control.signal.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        if (++zeroReads > 32) throw IOException(failure)
                        continue
                    }
                    zeroReads = 0
                    require(output.size() <= MAX_IMAGE_BYTES - count) { "QR image is too large" }
                    output.write(buffer, 0, count)
                }
                val value = decode(output.toByteArray())
                control.signal.throwIfCanceled()
                if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw IOException(failure)
                decoded.set(value)
                completed
            }
            // A late worker never publishes a QR string after timeout/cancellation. The bounded
            // provider layer holds its slot until that worker and arbitrary close/cancel finish.
            if (result !== completed) throw IOException(failure)
            return decoded.getAndSet(null) ?: throw IOException(failure)
        } finally { decoded.set(null) }
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "QR image is unavailable" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "QR image dimensions are unavailable" }
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("QR image is unavailable")
        try {
            require(bitmap.width in 1..MAX_SIDE && bitmap.height in 1..MAX_SIDE) { "QR image dimensions are unavailable" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
            require(result.text.length <= LinkImportCoordinator.MAX_LINK_CHARS) { "QR link is too long" }
            return result.text
        } finally { bitmap.recycle() }
    }
}
