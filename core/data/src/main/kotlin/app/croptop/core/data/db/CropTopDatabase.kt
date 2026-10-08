package app.croptop.core.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A document the user opened or saved; [uri] is a persisted SAF grant or app-private file. */
@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** App-private cached thumbnail (engine-rendered once available); null until then. */
    val thumbnailPath: String?,
    val lastOpenedAt: Long,
    val pinned: Boolean = false,
)

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY pinned DESC, lastOpenedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentDocumentEntity>>

    @Upsert
    suspend fun upsert(entity: RecentDocumentEntity)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("UPDATE recent_documents SET pinned = :pinned WHERE uri = :uri")
    suspend fun setPinned(uri: String, pinned: Boolean)

    @Query("UPDATE recent_documents SET name = :name WHERE uri = :uri")
    suspend fun rename(uri: String, name: String)

    /** URIs [trim] would delete (so their persisted grants can be released first). */
    @Query(
        "SELECT uri FROM recent_documents WHERE pinned = 0 AND uri NOT IN " +
            "(SELECT uri FROM recent_documents WHERE pinned = 0 ORDER BY lastOpenedAt DESC LIMIT :keep)",
    )
    suspend fun overflow(keep: Int): List<String>

    /** Keeps the list bounded; pinned rows are never trimmed. */
    @Query(
        "DELETE FROM recent_documents WHERE pinned = 0 AND uri NOT IN " +
            "(SELECT uri FROM recent_documents WHERE pinned = 0 ORDER BY lastOpenedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

/** User-saved New Document presets (v2). */
@Entity(tableName = "document_presets")
data class DocumentPresetEntity(
    @PrimaryKey val name: String,
    val width: Int,
    val height: Int,
    val ppi: Float,
    val depth: String,
    val profile: String,
    val background: String,
    val createdAt: Long,
)

@Dao
interface DocumentPresetDao {
    @Query("SELECT * FROM document_presets ORDER BY createdAt")
    fun observe(): Flow<List<DocumentPresetEntity>>

    @Upsert
    suspend fun upsert(entity: DocumentPresetEntity)

    @Query("DELETE FROM document_presets WHERE name = :name")
    suspend fun delete(name: String)
}

@Database(
    entities = [
        RecentDocumentEntity::class,
        DocumentPresetEntity::class,
        LibraryFolderEntity::class,
        CatalogImageEntity::class,
        KeywordEntity::class,
        DevelopSettingsEntity::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        BrushPresetEntity::class,
        BrushFavoriteEntity::class,
        VirtualCopyEntity::class,
        DevelopSnapshotEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(
            from = 2,
            to = 3,
        ), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6, spec = CatalogV5ToV6::class),
        AutoMigration(from = 6, to = 7, spec = CatalogV6ToV7::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
    ],
)
abstract class CropTopDatabase : RoomDatabase() {
    abstract fun recentDocuments(): RecentDocumentDao

    abstract fun documentPresets(): DocumentPresetDao

    abstract fun catalog(): CatalogDao

    abstract fun develop(): DevelopDao

    abstract fun brushes(): BrushDao
}

/** Per-photo develop settings (JSON of core:model DevelopSettings), keyed by document URI. */
@Entity(tableName = "develop_settings")
data class DevelopSettingsEntity(@PrimaryKey val uri: String, val json: String, val updatedAt: Long)

/** A virtual copy of the photo at [imageUri] (DB v10); its recipe lives in `develop_settings` under `developKey(uri, id)`. */
@Entity(tableName = "develop_virtual_copies", indices = [Index("imageUri")])
data class VirtualCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val name: String,
    val createdAt: Long,
)

/** A named snapshot of the Develop recipe of [imageUri] (DB v11); [json] is core:model DevelopSettings. */
@Entity(tableName = "develop_snapshots", indices = [Index("imageUri")])
data class DevelopSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val name: String,
    val json: String,
    val createdAt: Long,
)

@Dao
interface DevelopDao {
    @Query("SELECT * FROM develop_snapshots WHERE imageUri = :uri ORDER BY id")
    fun snapshots(uri: String): Flow<List<DevelopSnapshotEntity>>

    @Insert
    suspend fun insertSnapshot(e: DevelopSnapshotEntity): Long

    @Query("DELETE FROM develop_snapshots WHERE id = :id AND imageUri = :uri")
    suspend fun deleteSnapshot(uri: String, id: Long)

    @Query("SELECT * FROM develop_virtual_copies WHERE imageUri = :uri ORDER BY id")
    fun copies(uri: String): Flow<List<VirtualCopyEntity>>

    @Insert
    suspend fun insertCopy(e: VirtualCopyEntity): Long

    @Query("DELETE FROM develop_virtual_copies WHERE id = :id AND imageUri = :uri")
    suspend fun deleteCopy(uri: String, id: Long)

    @Query("UPDATE develop_virtual_copies SET name = :name WHERE id = :id")
    suspend fun renameCopy(id: Long, name: String)

    @Query("SELECT * FROM develop_settings WHERE uri = :uri")
    fun observe(uri: String): Flow<DevelopSettingsEntity?>

    @Upsert
    suspend fun upsert(e: DevelopSettingsEntity)

    @Query("DELETE FROM develop_settings WHERE uri = :uri")
    suspend fun delete(uri: String)
}
