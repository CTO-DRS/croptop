package app.croptop.feature.library.batch

import androidx.core.net.toUri
import android.content.Context
import android.provider.DocumentsContract
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.ExportTarget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates files in a Storage Access Framework folder; the provider renames on a clash, so nothing is ever overwritten. */
class SafBatchOutput @Inject constructor(@ApplicationContext private val context: Context) : BatchOutput {
    override suspend fun create(folder: String, baseName: String, format: ExportFormat): ExportTarget.ContentUri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tree = folder.toUri()
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(context.contentResolver, parent, format.mimeType(), baseName)
            }.getOrNull()?.let { ExportTarget.ContentUri(it.toString()) }
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BatchExportModule {
    @Binds
    abstract fun output(impl: SafBatchOutput): BatchOutput
}
