package com.lateapex.collie.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the current trace — every sealed file decrypted back to plaintext, plus whatever is in
 * the still-open active file — into one zip under [exportDir], ready to hand to the share sheet.
 * The caller is responsible for deleting the returned file once the share sheet has taken it.
 */
class DiagnosticsExport(
    private val writer: DiagnosticsWriter,
    private val exportDir: File,
) {
    // Holds the writer's lock (its methods are @Synchronized on the instance) so a seal or prune on
    // the recorder thread cannot delete a file between listing it and reading it.
    fun buildZip(): File = synchronized(writer) {
        exportDir.mkdirs()
        val zipFile = File(exportDir, "collie-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            writer.sealedFiles().forEachIndexed { index, sealed ->
                // A Keystore key can be invalidated (lock-screen change); say so rather than fail the export.
                val text = runCatching { writer.decrypt(sealed) }
                    .getOrElse { "{\"category\":\"export\",\"unreadable\":\"${sealed.name}\",\"error\":\"${it.javaClass.simpleName}\"}\n" }
                zip.putNextEntry(ZipEntry("trace-$index.jsonl"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            val active = writer.activeFile()
            if (active.exists() && active.length() > 0) {
                zip.putNextEntry(ZipEntry("trace-active.jsonl"))
                zip.write(active.readBytes())
                zip.closeEntry()
            }
        }
        zipFile
    }

    companion object {
        /** Must match `diagnostics_file_paths.xml`'s `cache-path`. */
        fun exportDir(cacheDir: File): File = File(cacheDir, "diagnostics-export")
    }
}
