package com.lateapex.collie.ui

/** Private JNI conversion; no filesystem/network access or unbounded native output. */
internal object BoundedWoff2Decoder {
    private val loaded by lazy {
        try { System.loadLibrary("collie_woff2"); true }
        catch (failure: LinkageError) { throw IllegalArgumentException("Operator font decoder unavailable", failure) }
    }
    fun decode(bytes: ByteArray): ByteArray {
        OperatorWoff2Bounds.validate(bytes)
        check(loaded)
        val decoded = try { decodeNative(bytes) }
        catch (failure: LinkageError) { throw IllegalArgumentException("Operator font decoder unavailable", failure) }
        return decoded.also { require(it.size in 12..16 * 1024 * 1024) { "Invalid decoded operator font" } }
    }
    private external fun decodeNative(bytes: ByteArray): ByteArray
}
