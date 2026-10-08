package app.croptop.core.data.catalog

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.croptop.core.data.UriGrants
import app.croptop.core.data.db.CatalogDao
import app.croptop.core.data.db.CatalogImageEntity
import app.croptop.core.data.db.LibraryFolderEntity
import app.croptop.core.data.db.catalogQuery
import app.croptop.core.model.CatalogImage
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.Flag
import app.croptop.core.model.IptcEdit
import app.croptop.core.model.LibraryCollection
import app.croptop.core.model.LibraryFilter
import app.croptop.core.model.LibraryFolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A file found while scanning a folder. */
data class ScannedFile(val uri: String, val name: String, val mimeType: String, val sizeBytes: Long, val lastModified: Long)

/** Lists supported images under a SAF tree (recursively). */
fun interface FolderSource {
    suspend fun list(treeUri: String): List<ScannedFile>
}

data class ImageMetadata(
    val width: Int,
    val height: Int,
    val takenAt: Long?,
    val camera: String?,
    val lens: String?,
    val iso: Int?,
    val exposure: String?,
    val aperture: Float?,
    val focalLength: Float?,
    val caption: String? = null,
    val creator: String? = null,
    val copyright: String? = null,
)

fun interface MetadataReader {
    suspend fun read(uri: String): ImageMetadata?
}

/** Library catalog: folders, incremental scans, metadata backfill, ratings/flags/labels. */
interface CatalogRepository {
    val folders: Flow<List<LibraryFolder>>

    fun images(filter: LibraryFilter): Flow<List<CatalogImage>>

    /** The grid's rows, a page at a time; placeholders keep indexes aligned with [ids]. */
    fun pagedImages(filter: LibraryFilter): Flow<PagingData<CatalogImage>>

    /** Ordered ids matching [filter] (cheap: no row data). */
    fun ids(filter: LibraryFilter): Flow<List<Long>>

    /** Grid rows for a few ids (Compare/Survey), in the order given. */
    fun images(ids: List<Long>): Flow<List<CatalogImage>>

    /** Uris for [ids], in the order given. */
    suspend fun uris(ids: List<Long>): List<String>

    fun keywords(imageId: Long): Flow<List<String>>

    /** One photo with full EXIF/IPTC (the grid list carries only what the grid shows). */
    fun image(id: Long): Flow<CatalogImage?>

    /** Adds (or rescans) a folder. Returns the number of images now in it. */
    suspend fun addFolder(treeUri: String, name: String, now: Long = System.currentTimeMillis()): Int

    suspend fun removeFolder(treeUri: String)

    /** Reads EXIF for up to [batch] images still missing it; returns how many were processed. */
    suspend fun backfillMetadata(batch: Int = 64): Int

    suspend fun setRating(ids: List<Long>, rating: Int)

    suspend fun setFlag(ids: List<Long>, flag: Flag)

    suspend fun setLabel(ids: List<Long>, label: ColorLabel)

    suspend fun addKeywords(ids: List<Long>, keywords: List<String>)

    /** Batch IPTC edit; null fields are left alone, blank clears. */
    suspend fun setIptc(ids: List<Long>, edit: IptcEdit)

    val collections: Flow<List<LibraryCollection>>

    /** Creates a manual collection, or a smart one when [smart] is given. Returns its id. */
    suspend fun createCollection(name: String, smart: LibraryFilter? = null, now: Long = System.currentTimeMillis()): Long

    suspend fun renameCollection(id: Long, name: String)

    suspend fun deleteCollection(id: Long)

    suspend fun addToCollection(collectionId: Long, ids: List<Long>, now: Long = System.currentTimeMillis())

    suspend fun removeFromCollection(collectionId: Long, ids: List<Long>)
}

@Singleton
class RoomCatalogRepository @Inject constructor(
    private val dao: CatalogDao,
    private val source: FolderSource,
    private val metadata: MetadataReader,
    private val grants: UriGrants,
) : CatalogRepository {

    override val folders: Flow<List<LibraryFolder>> = combine(dao.folders(), dao.folderCounts()) { folders, counts ->
        val byFolder = counts.associate { it.folderUri to it.n }
        folders.map { LibraryFolder(it.treeUri, it.name, byFolder[it.treeUri] ?: 0) }
    }

    override fun images(filter: LibraryFilter): Flow<List<CatalogImage>> = dao.queryGrid(filter.toQuery()).map { rows -> rows.map(::gridToModel) }

    override fun pagedImages(filter: LibraryFilter): Flow<PagingData<CatalogImage>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE, enablePlaceholders = true, initialLoadSize = PAGE_SIZE * 2)) {
            dao.pageGrid(filter.toQuery())
        }.flow.map { page -> page.map(::gridToModel) }

    override fun ids(filter: LibraryFilter): Flow<List<Long>> = dao.queryIds(filter.toQuery(columns = "id"))

    override fun images(ids: List<Long>): Flow<List<CatalogImage>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        val wanted = ids.take(MAX_ID_ARGS)
        return dao.gridRows(wanted).map { rows ->
            val byId = rows.associateBy { it.id }
            wanted.mapNotNull { byId[it]?.let(::gridToModel) }
        }
    }

    override suspend fun uris(ids: List<Long>): List<String> {
        val byId = ids.chunked(MAX_ID_ARGS).flatMap { dao.uris(it) }.associate { it.id to it.uri }
        return ids.mapNotNull { byId[it] }
    }

    private fun LibraryFilter.toQuery(columns: String = app.croptop.core.data.db.GRID_COLUMNS) = catalogQuery(
        minRating,
        flag?.let(::flagToInt),
        label?.ordinal,
        query,
        folderUri,
        rawOnly,
        columns,
        collectionId,
        sort,
    )

    override val collections: Flow<List<LibraryCollection>> = dao.collections().map { rows ->
        rows.map { r ->
            val smart = r.filterJson?.let { runCatching { json.decodeFromString(LibraryFilter.serializer(), it) }.getOrNull() }
            LibraryCollection(r.id, r.name, smart, if (r.filterJson == null) r.n else null)
        }
    }

    override suspend fun createCollection(name: String, smart: LibraryFilter?, now: Long): Long = dao.insertCollection(
        app.croptop.core.data.db.CollectionEntity(
            name = name.trim().ifBlank { "Untitled Collection" },
            filterJson = smart?.let { json.encodeToString(LibraryFilter.serializer(), it.criteria()) },
            createdAt = now,
        ),
    )

    override suspend fun renameCollection(id: Long, name: String) {
        if (name.isNotBlank()) dao.renameCollection(id, name.trim())
    }

    override suspend fun deleteCollection(id: Long) = dao.deleteCollection(id)

    override suspend fun addToCollection(collectionId: Long, ids: List<Long>, now: Long) =
        ids.chunked(MAX_ID_ARGS).forEach { chunk ->
            dao.addToCollection(chunk.map { app.croptop.core.data.db.CollectionItemEntity(collectionId, it, now) })
        }

    override suspend fun removeFromCollection(collectionId: Long, ids: List<Long>) =
        ids.chunked(MAX_ID_ARGS).forEach { dao.removeFromCollection(collectionId, it) }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun image(id: Long): Flow<CatalogImage?> = dao.image(id).map { it?.let(::toModel) }

    override fun keywords(imageId: Long): Flow<List<String>> = dao.keywords(imageId)

    override suspend fun addFolder(treeUri: String, name: String, now: Long): Int {
        dao.upsertFolder(LibraryFolderEntity(treeUri, name, addedAt = now, lastScanAt = now))
        val found = source.list(treeUri)
        val existing = dao.scanRows(treeUri).associateBy { it.uri }
        val foundUris = found.mapTo(mutableSetOf()) { it.uri }
        val removed = existing.values.filter { it.uri !in foundUris }.map { it.id }
        // New or changed files; unchanged ones keep their rows (and ratings) untouched.
        val changed = found.filter { f -> existing[f.uri]?.lastModified != f.lastModified }.map { f ->
            CatalogImageEntity(
                id = existing[f.uri]?.id ?: 0,
                uri = f.uri,
                folderUri = treeUri,
                name = f.name,
                mimeType = f.mimeType,
                sizeBytes = f.sizeBytes,
                lastModified = f.lastModified,
                addedAt = now,
                isRaw = CatalogImage.isRawName(f.name),
            )
        }
        // Keep user edits for changed files: re-read the existing ones and only reset metadata.
        dao.replaceFolderContents(changed.filter { it.id == 0L }, removed)
        changed.filter { it.id != 0L }.forEach { e -> dao.setMetadata(e.id, 0, 0, null, null, null, null, null, null, null, 0) }
        return found.size
    }

    override suspend fun removeFolder(treeUri: String) {
        dao.deleteFolder(treeUri)
        grants.release(treeUri)
    }

    override suspend fun backfillMetadata(batch: Int): Int {
        val todo = dao.needingMetadata(METADATA_VERSION, batch)
        todo.forEach { e ->
            val m = runCatching { metadata.read(e.uri) }.getOrNull()
            dao.setMetadata(
                e.id, m?.width ?: 0, m?.height ?: 0, m?.takenAt, m?.camera, m?.lens, m?.iso, m?.exposure, m?.aperture, m?.focalLength,
                METADATA_VERSION,
            )
            if (m != null) dao.fillIptc(e.id, m.caption, m.creator, m.copyright)
        }
        return todo.size
    }

    override suspend fun setRating(ids: List<Long>, rating: Int) = dao.setRating(ids, rating.coerceIn(0, 5))

    override suspend fun setFlag(ids: List<Long>, flag: Flag) = dao.setFlag(ids, flagToInt(flag))

    override suspend fun setLabel(ids: List<Long>, label: ColorLabel) = dao.setLabel(ids, label.ordinal)

    override suspend fun addKeywords(ids: List<Long>, keywords: List<String>) =
        dao.addKeywords(ids.flatMap { id -> keywords.map { app.croptop.core.data.db.KeywordEntity(id, it.trim().lowercase()) } })

    override suspend fun setIptc(ids: List<Long>, edit: IptcEdit) {
        fun v(s: String) = s.trim().ifBlank { null }
        edit.title?.let { dao.setTitle(ids, v(it)) }
        edit.caption?.let { dao.setCaption(ids, v(it)) }
        edit.creator?.let { dao.setCreator(ids, v(it)) }
        edit.copyright?.let { dao.setCopyright(ids, v(it)) }
    }

    private fun flagToInt(f: Flag) = when (f) {
        Flag.PICK -> 1
        Flag.REJECT -> -1
        Flag.NONE -> 0
    }

    private fun flagOf(v: Int) = when (v) {
        1 -> Flag.PICK
        -1 -> Flag.REJECT
        else -> Flag.NONE
    }

    private fun gridToModel(r: app.croptop.core.data.db.CatalogGridRow) = CatalogImage(
        id = r.id,
        uri = r.uri,
        folderUri = r.folderUri,
        name = r.name,
        mimeType = r.mimeType,
        sizeBytes = r.sizeBytes,
        width = r.width,
        height = r.height,
        takenAt = r.takenAt,
        rating = r.rating,
        flag = flagOf(r.flag),
        label = ColorLabel.entries.getOrElse(r.label) { ColorLabel.NONE },
    )

    private fun toModel(e: CatalogImageEntity) = CatalogImage(
        id = e.id,
        uri = e.uri,
        folderUri = e.folderUri,
        name = e.name,
        mimeType = e.mimeType,
        sizeBytes = e.sizeBytes,
        width = e.width,
        height = e.height,
        takenAt = e.takenAt,
        camera = e.camera,
        lens = e.lens,
        iso = e.iso,
        exposure = e.exposure,
        aperture = e.aperture,
        focalLength = e.focalLength,
        rating = e.rating,
        flag = when (e.flag) {
            1 -> Flag.PICK
            -1 -> Flag.REJECT
            else -> Flag.NONE
        },
        label = ColorLabel.entries.getOrElse(e.label) { ColorLabel.NONE },
        title = e.title,
        caption = e.caption,
        creator = e.creator,
        copyright = e.copyright,
    )

    companion object {
        const val METADATA_VERSION = 2
        const val PAGE_SIZE = 120

        /** Below SQLite's default host-parameter limit. */
        const val MAX_ID_ARGS = 900
    }
}
