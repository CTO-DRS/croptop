package app.croptop.app.files

import android.content.Context
import androidx.core.content.FileProvider
import app.croptop.feature.editor.files.ShareCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** `cacheDir/share/` exposed via the app FileProvider (see res/xml/file_paths.xml). */
@Singleton
class FileProviderShareCache @Inject constructor(@ApplicationContext private val context: Context) : ShareCache {
    private val dir get() = File(context.cacheDir, "share").apply { mkdirs() }

    override fun newFile(name: String): File {
        // Keep the cache small: files older than an hour are no longer being shared.
        val cutoff = System.currentTimeMillis() - 3_600_000
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    }

    override fun uriFor(file: File): String = FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
}
