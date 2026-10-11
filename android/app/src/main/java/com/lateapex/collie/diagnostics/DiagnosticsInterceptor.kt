package com.lateapex.collie.diagnostics

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
 * timestamp guessing. Ordinary text bodies are captured in full; binary bodies (image uploads, STT audio)
 * are captured as content-type and size only. Intent creation and pane-layout bodies are deliberately omitted because
 * they can contain operator-authored command lines. The `Authorization` header's VALUE is never
 * recorded, under any circumstance — only whether one was present. Pairing bodies are never
 * captured: the request contains a short-lived code and the response contains the device token.
 *
 * A GET records only what is new since the last answer from the same URL ([ResponseDelta]); a
 * poll with nothing new is not recorded. A send (anything but GET) is recorded in full unless its
 * route has a feature-specific redaction rule, repeats included. What the app reads is untouched.
 */
class DiagnosticsInterceptor internal constructor(
    private val diagnostics: DiagnosticsRecorder,
    private val delta: ResponseDelta,
) : Interceptor {
    constructor(diagnostics: DiagnosticsRecorder) : this(diagnostics, ResponseDelta())

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
        if (tagged.url.pathSegments.takeLast(2) == listOf("api", "pair")) {
            diagnostics.record("network", fields)
            return response
        }
        if (isPaneLayoutRequest(tagged)) {
            // Layout replies and plans have independent streaming bounds downstream. Keep
            // literal environment values, folders and geometry out of diagnostic body custody.
            tagged.body?.let { body ->
                fields["requestBodyBytes"] = body.contentLength().takeIf { it >= 0L }
                fields["requestBodyRedacted"] = true
            }
            fields["responseBodyBytes"] = response.body?.contentLength()?.takeIf { it >= 0L }
            fields["responseBodyRedacted"] = true
            fields["responseBodyStreaming"] = true
            diagnostics.record("network", fields)
            return response
        }
        if (isJournalBlobRequest(tagged)) {
            // Blob admission enforces a hard streamed-body bound downstream. Diagnostics must
            // never buffer or replace that response, even when its MIME is missing or textual.
            fields["responseBodyBytes"] = response.body?.contentLength()?.takeIf { it >= 0L }
            fields["responseBodyStreaming"] = true
            diagnostics.record("network", fields)
            return response
        }
        // The URL path is decoded into segments before matching, so mounted origins (and encoded
        // segment spellings) follow the same policy. Match complete route suffixes, never substrings.
        val intentPost = tagged.method == "POST" && isIntentCreationPath(tagged.url.pathSegments)
        val requestBody = tagged.body
        if (requestBody != null) {
            if (intentPost) {
                fields["requestBodyBytes"] = requestBody.contentLength()
                fields["requestBodyRedacted"] = true
            } else if (isTextual(requestBody.contentType())) {
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
            val kept = if (intentPost) {
                fields["responseBodyBytes"] = text.toByteArray().size
                fields["responseBodyRedacted"] = true
                null
            } else if (tagged.method == "GET") delta.of(tagged.url.toString(), response.code, text) else ResponseDelta.Recorded(body = text)
            if (kept != null) {
                kept.body?.let { fields["responseBody"] = it }
                kept.patch?.let { fields["responsePatch"] = it }
                kept.entriesUnchanged?.let { fields["entriesUnchanged"] = it }
                diagnostics.record("network", fields)
            }
            if (intentPost) diagnostics.record("network", fields)
            response.newBuilder()
                .body(text.toResponseBody(responseBody.contentType()))
                .build()
        } else if (responseBody != null) {
            val bytes = responseBody.bytes()
            fields["responseBodyBytes"] = bytes.size
            if (intentPost) fields["responseBodyRedacted"] = true
            diagnostics.record("network", fields)
            response.newBuilder()
                .body(bytes.toResponseBody(responseBody.contentType()))
                .build()
        } else {
            diagnostics.record("network", fields)
            response
        }
    }

    private fun isTextual(type: MediaType?): Boolean =
        type == null || type.type == "application" && type.subtype in TEXTUAL_SUBTYPES ||
            type.type == "text"

    private fun isJournalBlobRequest(request: okhttp3.Request): Boolean {
        if (request.method != "GET") return false
        val segments = request.url.encodedPath.split('/').filter(String::isNotEmpty)
        if (segments.size < 3 || segments.takeLast(3).take(2) != listOf("api", "blobs")) return false
        return JOURNAL_HASH.matches(segments.last())
    }
    private fun isPaneLayoutRequest(request: okhttp3.Request): Boolean {
        val segments = request.url.pathSegments
        if (request.method == "GET" && segments.size >= 4 &&
            segments.takeLast(4).let { it[0] == "api" && it[1] == "pane" && it[2].isNotEmpty() && it[3] == "layout" }) return true
        if (segments.size < 5) return false
        val suffix = segments.takeLast(5)
        if (suffix[0] != "api" || suffix[1] != "pane" || suffix[2].isEmpty() || suffix[3] != "layout") return false
        return request.method == "GET" && suffix[4] in setOf("edges", "neighbor") ||
            request.method == "POST" && suffix[4] in setOf("plan", "split", "resize", "zoom", "swap", "move", "focus-direction")
    }

    private fun isIntentCreationPath(segments: List<String>): Boolean =
        segments.takeLast(2) == listOf("api", "launch") ||
            segments.takeLast(2) == listOf("api", "worktree") ||
            (segments.size >= 4 && segments[segments.size - 4] == "api" &&
                segments[segments.size - 3] == "workspace" && segments.last() == "worktree") ||
            segments.takeLast(3) == listOf("api", "launchers", "added")

    companion object {
        internal const val TRACE_HEADER = "X-Collie-Trace-Id"
        private val JOURNAL_HASH = Regex("[0-9a-f]{64}")
        private val TEXTUAL_SUBTYPES = setOf("json", "xml")
    }
}
