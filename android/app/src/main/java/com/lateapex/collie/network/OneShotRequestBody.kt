package com.lateapex.collie.network

import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

/** Prevents OkHttp HTTP follow-ups from replaying a user-triggered POST body. */
internal class OneShotRequestBody(
    private val delegate: RequestBody,
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

/** File-backed multipart part; the source itself enforces a single stream open. */
internal class UploadStreamRequestBody(
    private val upload: UploadSource,
) : RequestBody() {
    override fun contentType() = upload.contentType.toMediaTypeOrNull()
    override fun contentLength(): Long = upload.contentLength
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
        upload.openOnce().use { input ->
            val buffer = ByteArray(16 * 1024)
            var sent = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                sink.write(buffer, 0, count)
                sent += count
                upload.reportProgress(sent)
            }
        }
    }
}

internal fun safeUploadFilename(raw: String): String {
    val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
    val clean = leaf.map { c ->
        val category = Character.getType(c)
        if (c.code < 0x20 || c.code == 0x7f || category == Character.FORMAT.toInt() ||
            category == Character.LINE_SEPARATOR.toInt() || category == Character.PARAGRAPH_SEPARATOR.toInt() || c == '"' || c == '\\' || c == '/'
        ) '_' else c
    }.joinToString("").trim().trim('.')
    return clean.take(120).ifBlank { "attachment.txt" }
}
