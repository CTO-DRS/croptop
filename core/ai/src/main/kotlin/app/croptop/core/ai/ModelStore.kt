// On-device model files: download with progress, verify, delete, report storage used.
package app.croptop.core.ai

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** An open download. [resumed] is true when the server honoured the requested start offset. */
class DownloadStream(val input: InputStream, val resumed: Boolean) : Closeable {
    override fun close() = input.close()
}

/** Opens [url] starting at byte [resumeFrom] (0 = from the start). The app supplies an HTTP one; tests supply files. */
fun interface Downloader {
    fun open(url: String, resumeFrom: Long): DownloadStream
}

sealed interface ModelState {
    data object NotInstalled : ModelState

    /** Some files are present and complete, some are partial or missing (an interrupted download). */
    data class Partial(val bytesOnDisk: Long, val totalBytes: Long) : ModelState
    data object Installed : ModelState
}

sealed interface InstallResult {
    data object Installed : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val reason: String) : InstallResult
}

/**
 * Models live in `root/<id>/<version>/<file>`. A file counts as installed when it exists with the registered size, and is only
 * renamed into place after its sha256 matched, so an interrupted or tampered download never reads as installed.
 * Blocking: run it off the main thread. Not thread-safe per model: serialise installs of one model.
 */
class ModelStore(private val root: File, private val downloader: Downloader) {
    fun directory(spec: ModelSpec): File = File(File(root, spec.id), spec.version.toString())

    fun file(spec: ModelSpec, name: String): File? =
        spec.files.firstOrNull { it.name == name }?.let { File(directory(spec), it.name) }?.takeIf { it.isFile }

    fun state(spec: ModelSpec): ModelState {
        val dir = directory(spec)
        var complete = 0
        var onDisk = 0L
        for (file in spec.files) {
            val final = File(dir, file.name)
            val part = File(dir, file.name + PART)
            when {
                final.isFile && final.length() == file.sizeBytes -> {
                    complete++
                    onDisk += file.sizeBytes
                }

                part.isFile -> onDisk += part.length().coerceAtMost(file.sizeBytes)
            }
        }
        return when {
            complete == spec.files.size -> ModelState.Installed
            onDisk == 0L -> ModelState.NotInstalled
            else -> ModelState.Partial(onDisk, spec.totalBytes)
        }
    }

    /** Bytes this store holds across every model and version, partial downloads included. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(spec: ModelSpec) {
        directory(spec).deleteRecursively()
        File(root, spec.id).takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }

    /** Keeps only the version listed for each model id in [keepVersions]; every other model and version is removed. */
    fun prune(keepVersions: Map<String, Int>) {
        for (modelDir in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            val keep = keepVersions[modelDir.name]
            for (versionDir in modelDir.listFiles { f -> f.isDirectory }.orEmpty()) {
                if (keep == null || versionDir.name != keep.toString()) versionDir.deleteRecursively()
            }
            if (modelDir.list().isNullOrEmpty()) modelDir.delete()
        }
    }

    /**
     * Downloads and verifies every missing file. [onProgress] gets (bytes done, total bytes) across the whole model; [cancelled] is
     * polled between chunks. Partial files are kept for a later resume when the downloader supports it.
     */
    fun install(
        spec: ModelSpec,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): InstallResult {
        val problems = spec.problems()
        if (problems.isNotEmpty()) return InstallResult.Failed(problems.first())
        val dir = directory(spec)
        if (!dir.isDirectory && !dir.mkdirs()) return InstallResult.Failed("could not create ${dir.path}")
        var done = spec.files.filter { File(dir, it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }.sumOf { it.sizeBytes }
        onProgress(done, spec.totalBytes)
        for (file in spec.files) {
            val final = File(dir, file.name)
            if (final.isFile && final.length() == file.sizeBytes) continue
            val part = File(dir, file.name + PART)
            try {
                val received = download(file, part, cancelled) { chunk -> onProgress(done + chunk, spec.totalBytes) }
                    ?: return InstallResult.Cancelled
                if (received != file.sizeBytes) {
                    part.delete()
                    return InstallResult.Failed("${file.name}: expected ${file.sizeBytes} bytes, received $received")
                }
                val actual = sha256(part)
                if (actual != file.sha256) {
                    part.delete()
                    return InstallResult.Failed("${file.name}: checksum mismatch")
                }
                if (final.exists()) final.delete()
                if (!part.renameTo(final)) return InstallResult.Failed("${file.name}: could not move into place")
            } catch (e: IOException) {
                return InstallResult.Failed("${file.name}: ${e.message ?: "I/O error"}")
            }
            done += file.sizeBytes
            onProgress(done, spec.totalBytes)
        }
        return InstallResult.Installed
    }

    /** Returns the file's byte count, or null when cancelled. */
    private fun download(file: ModelFile, part: File, cancelled: () -> Boolean, onChunk: (Long) -> Unit): Long? {
        var offset = if (part.isFile) part.length().coerceAtMost(file.sizeBytes) else 0L
        downloader.open(file.url, offset).use { stream ->
            val append = offset > 0L && stream.resumed
            if (!append) offset = 0L
            java.io.FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER)
                var total = offset
                while (true) {
                    if (cancelled()) return null
                    val n = stream.input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total > file.sizeBytes) return total // more than promised: caller rejects on the size check
                    onChunk(total)
                }
                return total
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART = ".part"
        const val BUFFER = 64 * 1024
    }
}
