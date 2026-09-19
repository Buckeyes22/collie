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
    fun buildZip(): File {
        exportDir.mkdirs()
        val zipFile = File(exportDir, "collie-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            writer.sealedFiles().forEachIndexed { index, sealed ->
                zip.putNextEntry(ZipEntry("trace-$index.jsonl"))
                zip.write(writer.decrypt(sealed).toByteArray())
                zip.closeEntry()
            }
            val active = writer.activeFile()
            if (active.exists() && active.length() > 0) {
                zip.putNextEntry(ZipEntry("trace-active.jsonl"))
                zip.write(active.readBytes())
                zip.closeEntry()
            }
        }
        return zipFile
    }
}
