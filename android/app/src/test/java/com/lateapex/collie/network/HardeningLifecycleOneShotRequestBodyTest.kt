package com.lateapex.collie.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningLifecycleOneShotRequestBodyTest {
    @Test
    fun delegatesMetadataAndSerializedBytesWithoutNetwork() {
        val contentType = "application/json".toMediaType()
        val delegate = "{\"text\":\"safe\"}".toRequestBody(contentType)
        val body = OneShotRequestBody(delegate)
        val sink = Buffer()

        assertTrue(body.isOneShot())
        assertEquals(delegate.contentType(), body.contentType())
        assertEquals(delegate.contentLength(), body.contentLength())
        body.writeTo(sink)
        assertEquals("{\"text\":\"safe\"}", sink.readUtf8())
    }
}
