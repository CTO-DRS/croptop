package app.croptop.feature.editor

import app.croptop.core.data.RecentDocumentsRepository
import app.croptop.core.model.DocSize
import app.croptop.core.model.RecentDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object NoRecents : RecentDocumentsRepository {
    override fun observe(limit: Int): Flow<List<RecentDocument>> = flowOf(emptyList())

    override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) = Unit

    override suspend fun remove(uri: String) = Unit

    override suspend fun setPinned(uri: String, pinned: Boolean) = Unit
}

object TempShareCache : app.croptop.feature.editor.files.ShareCache {
    private val dir = kotlin.io.path.createTempDirectory("share").toFile()

    override fun newFile(name: String) = java.io.File(dir, name)

    override fun uriFor(file: java.io.File) = "file://${file.absolutePath}"
}
