package app.croptop.core.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * Writing over a file that lives behind a document provider (a SAF content URI), where there is no atomic rename. The new
 * contents are encoded completely into an app-private staging file first, so an encoder failure never touches the destination.
 * The destination's current contents are copied aside before it is truncated; if copying the new bytes in fails part way, the old
 * bytes are written back. If even that fails, the copy is kept and its path is in the error, so nothing is lost.
 */
internal object SafeReplace {
    class RestoreFailed(message: String, val backup: File, cause: Throwable) : IOException(message, cause)

    /**
     * [encode] writes the new contents and returns the byte count it reports. [readCurrent] opens the destination's present
     * contents (null when it is new or unreadable). [openDestination] opens it truncated for writing. [sync] flushes it to storage
     * when the provider allows (a file descriptor), and is called after the copy. Returns [encode]'s count.
     */
    fun replace(
        workDir: File,
        name: String,
        encode: (OutputStream) -> Long,
        readCurrent: () -> InputStream?,
        openDestination: () -> OutputStream,
        sync: (OutputStream) -> Unit = {},
    ): Long {
        if (!workDir.exists() && !workDir.mkdirs()) throw IOException("Could not create the save staging folder")
        val stem = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "document" }
        val staged = Files.createTempFile(workDir.toPath(), "$stem.", ".new").toFile()
        var backup: File? = null
        try {
            val count = FileOutputStream(staged).use { output ->
                val written = encode(output)
                output.flush()
                output.fd.sync()
                written
            }
            if (staged.length() == 0L) throw IOException("The encoder produced no data; the original file was left unchanged")
            backup = readCurrent()?.use { input ->
                Files.createTempFile(workDir.toPath(), "$stem.", ".old").toFile().also { copy ->
                    FileOutputStream(copy).use { input.copyTo(it); it.fd.sync() }
                }
            }
            try {
                openDestination().use { output ->
                    staged.inputStream().use { it.copyTo(output) }
                    output.flush()
                    sync(output)
                }
            } catch (failure: Throwable) {
                val old = backup ?: throw failure
                try {
                    openDestination().use { output ->
                        old.inputStream().use { it.copyTo(output) }
                        output.flush()
                        sync(output)
                    }
                } catch (restore: Throwable) {
                    failure.addSuppressed(restore)
                    backup = null // keep it: it is now the only good copy
                    throw RestoreFailed("Saving failed and the original could not be put back; a copy is kept at ${old.path}", old, failure)
                }
                throw failure
            }
            return count
        } finally {
            staged.delete()
            backup?.delete()
        }
    }
}
