package app.croptop.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** A folder the user added to the Library (persisted SAF tree grant). */
@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey val treeUri: String,
    val name: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
)

@Entity(
    tableName = "catalog_images",
    indices = [
        Index(value = ["uri"], unique = true), Index("folderUri"), Index("takenAt"), Index("rating"),
        Index(value = ["sortKey", "name"], orders = [Index.Order.DESC, Index.Order.ASC]),
        // Other sort orders (DB v8), so paging never sorts the whole table.
        Index(value = ["name"]),
        Index(value = ["rating", "sortKey"], orders = [Index.Order.DESC, Index.Order.DESC]),
        Index(value = ["addedAt"]),
    ],
    foreignKeys = [ForeignKey(LibraryFolderEntity::class, ["treeUri"], ["folderUri"], onDelete = ForeignKey.CASCADE)],
)
data class CatalogImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val folderUri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: Long? = null,
    val camera: String? = null,
    val lens: String? = null,
    val iso: Int? = null,
    val exposure: String? = null,
    val aperture: Float? = null,
    val focalLength: Float? = null,
    /** 0 until EXIF has been read. */
    val metadataVersion: Int = 0,
    val rating: Int = 0,
    /** -1 reject, 0 none, 1 pick. */
    val flag: Int = 0,
    val label: Int = 0,
    val addedAt: Long,
    // IPTC core: prefilled from EXIF, edited in the Library, written on export.
    val title: String? = null,
    val caption: String? = null,
    val creator: String? = null,
    val copyright: String? = null,
    /** Stored so "RAW only" is an integer compare, not 17 LIKEs per row (DB v6). */
    @androidx.room.ColumnInfo(defaultValue = "0") val isRaw: Boolean = false,
    /** Grid order, `takenAt ?: lastModified`, stored so the (paged) sort walks an index (DB v7). */
    @androidx.room.ColumnInfo(defaultValue = "0") val sortKey: Long = takenAt ?: lastModified,
)

/** Columns the grid needs; EXIF/IPTC load only for the focused photo ([CatalogDao.image]). */
data class CatalogGridRow(
    val id: Long,
    val uri: String,
    val folderUri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val takenAt: Long?,
    val rating: Int,
    val flag: Int,
    val label: Int,
)

/** Backfills [CatalogImageEntity.sortKey] for catalogs created before v7. */
class CatalogV6ToV7 : androidx.room.migration.AutoMigrationSpec {
    override fun onPostMigrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE catalog_images SET sortKey = COALESCE(takenAt, lastModified)")
    }
}

/** Backfills [CatalogImageEntity.isRaw] for catalogs created before v6. */
class CatalogV5ToV6 : androidx.room.migration.AutoMigrationSpec {
    override fun onPostMigrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE catalog_images SET isRaw = 1 WHERE " +
                app.croptop.core.model.CatalogImage.RawExtensions.joinToString(" OR ") { "LOWER(name) LIKE '%.$it'" },
        )
    }
}

@Entity(
    tableName = "catalog_keywords",
    primaryKeys = ["imageId", "keyword"],
    indices = [Index("keyword")],
    foreignKeys = [ForeignKey(CatalogImageEntity::class, ["id"], ["imageId"], onDelete = ForeignKey.CASCADE)],
)
data class KeywordEntity(val imageId: Long, val keyword: String)

/** A Library collection (DB v8). [filterJson] is a serialized `LibraryFilter` for smart collections. */
@Entity(tableName = "catalog_collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filterJson: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "catalog_collection_items",
    primaryKeys = ["collectionId", "imageId"],
    indices = [Index("imageId")],
    foreignKeys = [
        ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CatalogImageEntity::class, ["id"], ["imageId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CollectionItemEntity(val collectionId: Long, val imageId: Long, val addedAt: Long)

data class CollectionRow(val id: Long, val name: String, val filterJson: String?, val n: Int)

/** Minimal row for incremental rescans. */
data class ScanRow(val id: Long, val uri: String, val lastModified: Long)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM library_folders ORDER BY name")
    fun folders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT folderUri, COUNT(*) AS n FROM catalog_images GROUP BY folderUri")
    fun folderCounts(): Flow<List<FolderCount>>

    @Upsert
    suspend fun upsertFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE treeUri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

    @Query("SELECT id, uri, lastModified FROM catalog_images WHERE folderUri = :folderUri")
    suspend fun scanRows(folderUri: String): List<ScanRow>

    @Upsert
    suspend fun upsertImages(images: List<CatalogImageEntity>)

    @Query("DELETE FROM catalog_images WHERE id IN (:ids)")
    suspend fun deleteImages(ids: List<Long>)

    @Query("SELECT * FROM catalog_images WHERE metadataVersion < :version LIMIT :limit")
    suspend fun needingMetadata(version: Int, limit: Int): List<CatalogImageEntity>

    @Query(
        "UPDATE catalog_images SET width = :w, height = :h, takenAt = :takenAt, camera = :camera, lens = :lens, iso = :iso, " +
            "exposure = :exposure, aperture = :aperture, focalLength = :focal, metadataVersion = :version, " +
            "sortKey = COALESCE(:takenAt, lastModified) WHERE id = :id",
    )
    suspend fun setMetadata(
        id: Long,
        w: Int,
        h: Int,
        takenAt: Long?,
        camera: String?,
        lens: String?,
        iso: Int?,
        exposure: String?,
        aperture: Float?,
        focal: Float?,
        version: Int,
    )

    /** Prefill from the file without overwriting what the user typed. */
    @Query(
        "UPDATE catalog_images SET caption = COALESCE(caption, :caption), creator = COALESCE(creator, :creator), " +
            "copyright = COALESCE(copyright, :copyright) WHERE id = :id",
    )
    suspend fun fillIptc(id: Long, caption: String?, creator: String?, copyright: String?)

    @Query("UPDATE catalog_images SET title = :v WHERE id IN (:ids)")
    suspend fun setTitle(ids: List<Long>, v: String?)

    @Query("UPDATE catalog_images SET caption = :v WHERE id IN (:ids)")
    suspend fun setCaption(ids: List<Long>, v: String?)

    @Query("UPDATE catalog_images SET creator = :v WHERE id IN (:ids)")
    suspend fun setCreator(ids: List<Long>, v: String?)

    @Query("UPDATE catalog_images SET copyright = :v WHERE id IN (:ids)")
    suspend fun setCopyright(ids: List<Long>, v: String?)

    @Query("UPDATE catalog_images SET rating = :rating WHERE id IN (:ids)")
    suspend fun setRating(ids: List<Long>, rating: Int)

    @Query("UPDATE catalog_images SET flag = :flag WHERE id IN (:ids)")
    suspend fun setFlag(ids: List<Long>, flag: Int)

    @Query("UPDATE catalog_images SET label = :label WHERE id IN (:ids)")
    suspend fun setLabel(ids: List<Long>, label: Int)

    @RawQuery(observedEntities = [CatalogImageEntity::class, CollectionItemEntity::class, KeywordEntity::class])
    fun queryGrid(q: SupportSQLiteQuery): Flow<List<CatalogGridRow>>

    /** The grid pages through this; only the rows near the viewport are loaded. */
    @RawQuery(observedEntities = [CatalogImageEntity::class, CollectionItemEntity::class, KeywordEntity::class])
    fun pageGrid(q: SupportSQLiteQuery): PagingSource<Int, CatalogGridRow>

    /** Ordered ids of a filter: selection ranges, arrow keys and counts work without loading rows. */
    @RawQuery(observedEntities = [CatalogImageEntity::class, CollectionItemEntity::class, KeywordEntity::class])
    fun queryIds(q: SupportSQLiteQuery): Flow<List<Long>>

    @Query("SELECT $GRID_COLUMNS FROM catalog_images WHERE id IN (:ids)")
    fun gridRows(ids: List<Long>): Flow<List<CatalogGridRow>>

    @Query("SELECT id, uri FROM catalog_images WHERE id IN (:ids)")
    suspend fun uris(ids: List<Long>): List<IdUri>

    @Query("SELECT * FROM catalog_images WHERE id = :id")
    fun image(id: Long): Flow<CatalogImageEntity?>

    @Upsert
    suspend fun addKeywords(k: List<KeywordEntity>)

    @Query("SELECT keyword FROM catalog_keywords WHERE imageId = :id ORDER BY keyword")
    fun keywords(id: Long): Flow<List<String>>

    @Query(
        "SELECT c.id, c.name, c.filterJson, (SELECT COUNT(*) FROM catalog_collection_items i WHERE i.collectionId = c.id) AS n " +
            "FROM catalog_collections c ORDER BY c.name COLLATE NOCASE",
    )
    fun collections(): Flow<List<CollectionRow>>

    @androidx.room.Insert
    suspend fun insertCollection(c: CollectionEntity): Long

    @Query("UPDATE catalog_collections SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("UPDATE catalog_collections SET filterJson = :json WHERE id = :id")
    suspend fun setCollectionFilter(id: Long, json: String?)

    @Query("DELETE FROM catalog_collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun addToCollection(items: List<CollectionItemEntity>)

    @Query("DELETE FROM catalog_collection_items WHERE collectionId = :collectionId AND imageId IN (:ids)")
    suspend fun removeFromCollection(collectionId: Long, ids: List<Long>)

    @Transaction
    suspend fun replaceFolderContents(add: List<CatalogImageEntity>, removeIds: List<Long>) {
        if (removeIds.isNotEmpty()) removeIds.chunked(500).forEach { deleteImages(it) }
        if (add.isNotEmpty()) add.chunked(500).forEach { upsertImages(it) }
    }
}

data class FolderCount(val folderUri: String, val n: Int)

data class IdUri(val id: Long, val uri: String)

/** Columns of [CatalogGridRow]. */
const val GRID_COLUMNS = "id, uri, folderUri, name, mimeType, sizeBytes, width, height, takenAt, rating, flag, label"

/** Builds the filtered library query (filter bar), newest first. */
fun catalogQuery(
    minRating: Int,
    flag: Int?,
    label: Int?,
    query: String,
    folderUri: String?,
    rawOnly: Boolean,
    columns: String = GRID_COLUMNS,
    collectionId: Long? = null,
    sort: app.croptop.core.model.LibrarySort = app.croptop.core.model.LibrarySort.CAPTURE_NEWEST,
): SupportSQLiteQuery {
    val where = mutableListOf<String>()
    val args = mutableListOf<Any>()
    if (minRating > 0) {
        where += "rating >= ?"
        args += minRating
    }
    if (flag != null) {
        where += "flag = ?"
        args += flag
    }
    if (label != null) {
        where += "label = ?"
        args += label
    }
    if (folderUri != null) {
        where += "folderUri = ?"
        args += folderUri
    }
    if (query.isNotBlank()) {
        where += "(name LIKE ? OR camera LIKE ? OR lens LIKE ? OR title LIKE ? OR caption LIKE ? OR creator LIKE ? " +
            "OR id IN (SELECT imageId FROM catalog_keywords WHERE keyword LIKE ?))"
        val like = "%${query.trim()}%"
        repeat(7) { args += like }
    }
    if (rawOnly) where += "isRaw = 1"
    if (collectionId != null) {
        where += "id IN (SELECT imageId FROM catalog_collection_items WHERE collectionId = ?)"
        args += collectionId
    }
    // Each order has an index (see CatalogImageEntity); id breaks ties so paging is stable.
    val order = when (sort) {
        app.croptop.core.model.LibrarySort.CAPTURE_NEWEST -> "sortKey DESC, name"
        app.croptop.core.model.LibrarySort.CAPTURE_OLDEST -> "sortKey ASC, name"
        app.croptop.core.model.LibrarySort.NAME -> "name, id"
        app.croptop.core.model.LibrarySort.RATING -> "rating DESC, sortKey DESC"
        app.croptop.core.model.LibrarySort.ADDED -> "addedAt DESC, id DESC"
    }
    val sql =
        "SELECT $columns FROM catalog_images" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY $order"
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}
