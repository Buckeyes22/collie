package com.lateapex.collie.diagnostics

import android.util.Base64
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DiagnosticsEnvelope(val version: Int, val iv: String, val ciphertext: String)

/**
 * Appends newline-delimited JSON to one plaintext active file bounded by [maxActiveBytes]; once
 * full it is sealed into an AES/GCM-encrypted `.jsonl.enc` file (via the same [SecretCipher]
 * pattern `EncryptedConnectionStore` uses for the pairing bearer) and a fresh active file starts.
 * At most [maxSealedFiles] sealed files are kept, oldest dropped first — the trace is a rolling
 * window, not a growing log. The active file itself stays plaintext between seals; app-private
 * storage is not readable by another app without root, and the size cap bounds the exposure.
 */
class DiagnosticsWriter(
    private val directory: File,
    private val cipher: SecretCipher,
    private val json: Json,
    private val maxActiveBytes: Long = 5_000_000L,
    private val maxSealedFiles: Int = 4,
) {
    init {
        directory.mkdirs()
    }

    @Synchronized
    fun appendLine(line: String) {
        activeFile().appendText(line + "\n")
        if (activeFile().length() >= maxActiveBytes) seal()
    }

    @Synchronized
    fun seal() {
        val active = activeFile()
        if (!active.exists() || active.length() == 0L) return
        val plaintext = active.readBytes()
        val envelope = cipher.encrypt(plaintext)
        val sealed = File(directory, "trace-${System.currentTimeMillis()}.jsonl.enc")
        sealed.writeText(
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

    fun sealedFiles(): List<File> =
        (directory.listFiles { file -> file.name.endsWith(".jsonl.enc") } ?: emptyArray())
            .sortedBy { it.name }

    fun decrypt(file: File): String {
        val envelope = json.decodeFromString(DiagnosticsEnvelope.serializer(), file.readText())
        require(envelope.version == ENVELOPE_VERSION)
        val plaintext = cipher.decrypt(
            CipherEnvelope(
                Base64.decode(envelope.iv, Base64.NO_WRAP),
                Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
            ),
        )
        return plaintext.decodeToString()
    }

    fun activeFile(): File = File(directory, ACTIVE_FILE_NAME)

    private fun pruneSealedFiles() {
        val files = sealedFiles()
        if (files.size > maxSealedFiles) {
            files.take(files.size - maxSealedFiles).forEach { it.delete() }
        }
    }

    companion object {
        internal const val ACTIVE_FILE_NAME = "trace-active.jsonl"
        private const val ENVELOPE_VERSION = 1
    }
}
