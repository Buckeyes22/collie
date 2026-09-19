package com.lateapex.collie.diagnostics

import java.security.MessageDigest
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The single place every Collie API request and response passes through. Tags each request with a
 * trace id the bridge's own access log echoes back (`bridge/server.ts`'s `accessLogRecord`), so a
 * client-side event can be matched to the bridge's `journalctl` output by id instead of by
 * timestamp guessing. Text bodies are captured in full; binary bodies (image uploads, STT audio)
 * are captured as content-type and size only. The `Authorization` header's VALUE is never
 * recorded, under any circumstance — only whether one was present.
 *
 * A GET whose status and body match the last answer from the same URL is not recorded at all:
 * measured on the S25 Ultra (2026-09-18), 70% of a 20-minute trace was the same transcript page,
 * re-read every 2 seconds. A send (anything but GET) is always recorded, repeats included.
 */
class DiagnosticsInterceptor(private val diagnostics: DiagnosticsRecorder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val traceId = UUID.randomUUID().toString()
        val original = chain.request()
        val tagged = original.newBuilder().header(TRACE_HEADER, traceId).build()
        // Switched off: still tag the request so the bridge log stays correlatable, but copy no bodies.
        if (!diagnostics.isEnabled) return chain.proceed(tagged)
        val start = System.currentTimeMillis()
        val response = chain.proceed(tagged)
        val fields = mutableMapOf<String, Any?>(
            "method" to tagged.method,
            "path" to tagged.url.encodedPath,
            "status" to response.code,
            "ms" to (System.currentTimeMillis() - start),
            "traceId" to traceId,
            "hadCredential" to (tagged.header("Authorization") != null),
        )
        val requestBody = tagged.body
        if (requestBody != null) {
            if (isTextual(requestBody.contentType())) {
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                fields["requestBody"] = buffer.readUtf8()
            } else {
                fields["requestBodyBytes"] = requestBody.contentLength()
            }
        }
        val responseBody = response.body
        return if (responseBody != null && isTextual(responseBody.contentType())) {
            val text = responseBody.string()
            fields["responseBody"] = text
            if (!repeatsLastAnswer(tagged.method, tagged.url.toString(), response.code, text)) {
                diagnostics.record("network", fields)
            }
            response.newBuilder()
                .body(text.toResponseBody(responseBody.contentType()))
                .build()
        } else if (responseBody != null) {
            val bytes = responseBody.bytes()
            fields["responseBodyBytes"] = bytes.size
            diagnostics.record("network", fields)
            response.newBuilder()
                .body(bytes.toResponseBody(responseBody.contentType()))
                .build()
        } else {
            diagnostics.record("network", fields)
            response
        }
    }

    private val lastAnswers = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_REMEMBERED_URLS
    }

    private fun repeatsLastAnswer(method: String, url: String, status: Int, body: String): Boolean {
        if (method != "GET") return false
        val digest = MessageDigest.getInstance("SHA-256").digest("$status\n$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return synchronized(lastAnswers) { lastAnswers.put(url, digest) == digest }
    }

    private fun isTextual(type: MediaType?): Boolean =
        type == null || type.type == "application" && type.subtype in TEXTUAL_SUBTYPES ||
            type.type == "text"

    companion object {
        internal const val TRACE_HEADER = "X-Collie-Trace-Id"
        private val TEXTUAL_SUBTYPES = setOf("json", "xml")
        private const val MAX_REMEMBERED_URLS = 64
    }
}
