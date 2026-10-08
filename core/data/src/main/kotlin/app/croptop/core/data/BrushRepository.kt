package app.croptop.core.data

import app.croptop.core.data.db.BrushDao
import app.croptop.core.data.db.BrushFavoriteEntity
import app.croptop.core.data.db.BrushPresetEntity
import app.croptop.core.model.BrushCategory
import app.croptop.core.model.BrushFileFormat
import app.croptop.core.model.BrushPreset
import app.croptop.core.model.BrushSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

class BrushFormatException(message: String) : Exception(message)

/** Brush presets: built-ins first, then the user's; favorites for both; JSON import/export. */
interface BrushRepository {
    val presets: Flow<List<BrushPreset>>

    /** Saves [settings] as a new preset in My Brushes; returns its id. */
    suspend fun create(name: String, settings: BrushSettings, now: Long = System.currentTimeMillis()): Long

    suspend fun rename(id: Long, name: String)

    /** Overwrites a user preset's settings (built-ins are read-only). */
    suspend fun update(id: Long, settings: BrushSettings)

    suspend fun delete(id: Long)

    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** A `.aebrushes` file (JSON) with the given presets. */
    fun export(presets: List<BrushPreset>): ByteArray

    /** Adds the presets in [bytes] to My Brushes; returns how many were imported. */
    suspend fun import(bytes: ByteArray, now: Long = System.currentTimeMillis()): Int
}

@Singleton
class RoomBrushRepository @Inject constructor(private val dao: BrushDao) : BrushRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override val presets: Flow<List<BrushPreset>> = combine(dao.presets(), dao.favorites()) { rows, favs ->
        val fav = favs.toSet()
        BrushPreset.BuiltIn.map { it.copy(favorite = it.id in fav) } + rows.mapNotNull { r ->
            val settings = runCatching { json.decodeFromString(BrushSettings.serializer(), r.json) }.getOrNull() ?: return@mapNotNull null
            BrushPreset(r.id, r.name, BrushCategory.entries.firstOrNull { it.name == r.category } ?: BrushCategory.CUSTOM, settings, r.id in fav)
        }
    }

    override suspend fun create(name: String, settings: BrushSettings, now: Long): Long =
        dao.insert(BrushPresetEntity(name = clean(name), category = BrushCategory.CUSTOM.name, json = encode(settings), createdAt = now))

    override suspend fun rename(id: Long, name: String) {
        if (id > 0 && name.isNotBlank()) dao.rename(id, clean(name))
    }

    override suspend fun update(id: Long, settings: BrushSettings) {
        if (id > 0) dao.setSettings(id, encode(settings))
    }

    override suspend fun delete(id: Long) {
        if (id > 0) dao.delete(id)
    }

    override suspend fun setFavorite(id: Long, favorite: Boolean) = if (favorite) dao.addFavorite(BrushFavoriteEntity(id)) else dao.removeFavorite(id)

    override fun export(presets: List<BrushPreset>): ByteArray = BrushFileFormat.encode(presets.map { it.name to it.settings })

    override suspend fun import(bytes: ByteArray, now: Long): Int {
        val entries = BrushFileFormat.decode(bytes) ?: throw BrushFormatException("This isn't an CropTop brushes file.")
        if (entries.isEmpty()) throw BrushFormatException("The file has no brushes.")
        val rows = entries.take(MAX_IMPORT).mapIndexed { i, (name, settings) ->
            BrushPresetEntity(name = clean(name), category = BrushCategory.CUSTOM.name, json = encode(settings), createdAt = now + i)
        }
        dao.insertAll(rows)
        return rows.size
    }

    private fun encode(s: BrushSettings) = json.encodeToString(BrushSettings.serializer(), s.normalized())

    private fun clean(name: String) = name.trim().take(60).ifBlank { "Untitled Brush" }

    private companion object {
        const val MAX_IMPORT = 500
    }
}
