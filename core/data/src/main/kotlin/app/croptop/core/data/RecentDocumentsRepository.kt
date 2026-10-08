package app.croptop.core.data

import app.croptop.core.data.db.RecentDocumentDao
import app.croptop.core.data.db.RecentDocumentEntity
import app.croptop.core.model.DocSize
import app.croptop.core.model.RecentDocument
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface RecentDocumentsRepository {
    fun observe(limit: Int = 24): Flow<List<RecentDocument>>

    suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long = System.currentTimeMillis())

    suspend fun remove(uri: String)

    suspend fun setPinned(uri: String, pinned: Boolean)
}

@Singleton
class RoomRecentDocumentsRepository @Inject constructor(
    private val dao: RecentDocumentDao,
    private val grants: UriGrants,
    private val names: DocumentNames,
) : RecentDocumentsRepository {

    override fun observe(limit: Int): Flow<List<RecentDocument>> = dao.observe(limit).map { rows ->
        rows.map { e ->
            RecentDocument(
                uri = e.uri,
                name = if (RawIdName.matches(e.name)) repairName(e.uri, e.name) else e.name,
                size = if (e.width > 0 && e.height > 0) DocSize(e.width, e.height) else null,
                thumbnailPath = e.thumbnailPath,
                lastOpenedAt = e.lastOpenedAt,
                pinned = e.pinned,
            )
        }
    }

    override suspend fun recordOpened(uri: String, name: String, size: DocSize?, now: Long) {
        dao.upsert(RecentDocumentEntity(uri, name, size?.width ?: 0, size?.height ?: 0, thumbnailPath = null, lastOpenedAt = now))
        // Android caps persisted grants per app; release the ones that fall off the list.
        dao.overflow(keep = MAX_RECENTS).forEach(grants::release)
        dao.trim(keep = MAX_RECENTS)
    }

    override suspend fun remove(uri: String) {
        dao.delete(uri)
        grants.release(uri)
    }

    override suspend fun setPinned(uri: String, pinned: Boolean) = dao.setPinned(uri, pinned)

    /** Entries saved before names came from the provider look like "image:27"; fix them once. */
    private suspend fun repairName(uri: String, stored: String): String {
        val real = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { names.displayName(uri) } ?: return stored
        dao.rename(uri, real)
        return real
    }

    companion object {
        const val MAX_RECENTS = 50
        private val RawIdName = Regex("^[A-Za-z_]+:\\d+$")
    }
}
