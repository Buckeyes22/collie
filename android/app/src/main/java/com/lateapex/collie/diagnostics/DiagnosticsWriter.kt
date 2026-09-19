package com.lateapex.collie.diagnostics

import android.util.Base64
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DiagnosticsEnvelope(val version: Int, val iv: String, val ciphertext: String)

/**
 * Appends newline-delimited JSON to one plaintext active file bounded by [maxActiveBytes]; once
 * full it is gzipped, sealed with AES/GCM (the [SecretCipher] pattern `EncryptedConnectionStore`
 * uses for the pairing bearer) into a `.jsonl.enc` file, and a fresh active file starts. Sealed
 * files are kept newest-first up to [maxSealedBytes] on disk, so the trace is a rolling window.
 *
 * Measured on ed8, 2026-09-18: a busy pane's 2-second poll carries 30-75 KB of JSON, so a byte
 * budget of raw text would hold only minutes. Compressing before sealing stretches the same disk
 * budget several times over. The active file stays plaintext between seals; app-private storage
 * is not readable by another app without root, and the small active cap bounds that exposure.
 */
class DiagnosticsWriter(
    private val directory: File,
    private val cipher: SecretCipher,
    private val json: Json,
    private val maxActiveBytes: Long = 1_000_000L,
    private val maxSealedBytes: Long = 20_000_000L,
) : DiagnosticsAppendable {
    private var sealSequence = 0

    init {
        directory.mkdirs()
    }

    @Synchronized
    override fun appendLine(line: String) {
        activeFile().appendText(line + "\n")
        if (activeFile().length() >= maxActiveBytes) seal()
    }

    @Synchronized
    fun seal() {
        val active = activeFile()
        if (!active.exists() || active.length() == 0L) return
        val envelope = cipher.encrypt(gzip(active.readBytes()))
        // Zero-padded so name order is age order even when two seals land in one millisecond.
        val name = String.format(Locale.ROOT, "trace-%013d-%06d.jsonl.enc", System.currentTimeMillis(), sealSequence++)
        File(directory, name).writeText(
            json.encodeToString(
                DiagnosticsEnvelope.serializer(),
                DiagnosticsEnvelope(
                    ENVELOPE_VERSION,
                    Base64.encodeToString(envelope.iv, Base64.NO_WRAP),
                    Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                ),
            ),
        )
        active.delete()
        pruneSealedFiles()
    }

    /** Oldest first. */
    fun sealedFiles(): List<File> =
        (directory.listFiles { file -> file.name.endsWith(".jsonl.enc") } ?: emptyArray())
            .sortedBy { it.name }

    fun decrypt(file: File): String {
        val envelope = json.decodeFromString(DiagnosticsEnvelope.serializer(), file.readText())
        require(envelope.version == ENVELOPE_VERSION)
        val compressed = cipher.decrypt(
            CipherEnvelope(
                Base64.decode(envelope.iv, Base64.NO_WRAP),
                Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
            ),
        )
        return GZIPInputStream(compressed.inputStream()).use { it.readBytes() }.decodeToString()
    }

    fun activeFile(): File = File(directory, ACTIVE_FILE_NAME)

    private fun pruneSealedFiles() {
        var kept = 0L
        sealedFiles().asReversed().forEachIndexed { index, file ->
            kept += file.length()
            if (index > 0 && kept > maxSealedBytes) file.delete()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    companion object {
        internal const val ACTIVE_FILE_NAME = "trace-active.jsonl"
        private const val ENVELOPE_VERSION = 1
    }
}
