package app.croptop.core.data

import app.croptop.core.data.db.DevelopDao
import app.croptop.core.data.db.DevelopSettingsEntity
import app.croptop.core.data.db.DevelopSnapshotEntity
import app.croptop.core.data.db.VirtualCopyEntity
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.DevelopSnapshot
import app.croptop.core.model.VirtualCopy
import app.croptop.core.model.developKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Non-destructive develop settings stored per photo in the catalog database. */
interface DevelopRepository {
    fun settings(uri: String): Flow<DevelopSettings>

    suspend fun save(uri: String, settings: DevelopSettings, now: Long = System.currentTimeMillis())

    /** Virtual copies of the photo at [uri], oldest first. */
    fun copies(uri: String): Flow<List<VirtualCopy>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Creates a copy named [name] starting from [settings]; returns its id. */
    suspend fun createCopy(uri: String, settings: DevelopSettings, name: String, now: Long = System.currentTimeMillis()): Long =
        throw UnsupportedOperationException("Virtual copies are not supported by this repository")

    suspend fun deleteCopy(uri: String, copyId: Long) = Unit

    suspend fun renameCopy(copyId: Long, name: String) = Unit

    /** Snapshots of the photo at [uri] (its original recipe), oldest first. */
    fun snapshots(uri: String): Flow<List<DevelopSnapshot>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Stores [settings] under [name]; returns the snapshot id. */
    suspend fun createSnapshot(uri: String, name: String, settings: DevelopSettings, now: Long = System.currentTimeMillis()): Long =
        throw UnsupportedOperationException("Snapshots are not supported by this repository")

    suspend fun deleteSnapshot(uri: String, snapshotId: Long) = Unit
}

@Singleton
class RoomDevelopRepository @Inject constructor(private val dao: DevelopDao) : DevelopRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun settings(uri: String): Flow<DevelopSettings> = dao.observe(uri).map { e ->
        e?.let { runCatching { json.decodeFromString(DevelopSettings.serializer(), it.json) }.getOrNull() } ?: DevelopSettings()
    }

    override suspend fun save(uri: String, settings: DevelopSettings, now: Long) {
        // Untouched photos carry no row, so "reset" really means pristine.
        if (settings.isDefault) {
            dao.delete(
                uri,
            )
        } else {
            dao.upsert(DevelopSettingsEntity(uri, json.encodeToString(DevelopSettings.serializer(), settings), now))
        }
    }

    override fun copies(uri: String): Flow<List<VirtualCopy>> = dao.copies(uri).map { rows -> rows.map { VirtualCopy(it.id, it.name) } }

    override suspend fun createCopy(uri: String, settings: DevelopSettings, name: String, now: Long): Long {
        val id = dao.insertCopy(VirtualCopyEntity(imageUri = uri, name = name.trim().take(60).ifBlank { "Copy" }, createdAt = now))
        // A neutral recipe has no row, like an untouched photo.
        save(developKey(uri, id), settings, now)
        return id
    }

    override suspend fun deleteCopy(uri: String, copyId: Long) {
        dao.deleteCopy(uri, copyId)
        dao.delete(developKey(uri, copyId))
    }

    override fun snapshots(uri: String): Flow<List<DevelopSnapshot>> = dao.snapshots(uri).map { rows ->
        rows.mapNotNull { e ->
            runCatching { json.decodeFromString(DevelopSettings.serializer(), e.json) }.getOrNull()
                ?.let { DevelopSnapshot(e.id, e.name, e.createdAt, it) }
        }
    }

    override suspend fun createSnapshot(uri: String, name: String, settings: DevelopSettings, now: Long): Long = dao.insertSnapshot(
        DevelopSnapshotEntity(
            imageUri = uri,
            name = name.trim().take(60).ifBlank { "Snapshot" },
            json = json.encodeToString(DevelopSettings.serializer(), settings),
            createdAt = now,
        ),
    )

    override suspend fun deleteSnapshot(uri: String, snapshotId: Long) = dao.deleteSnapshot(uri, snapshotId)

    override suspend fun renameCopy(copyId: Long, name: String) {
        dao.renameCopy(copyId, name.trim().take(60).ifBlank { return })
    }
}
