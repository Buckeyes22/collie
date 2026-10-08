package com.lateapex.collie.network

import okhttp3.RequestBody
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
