package app.croptop.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A user brush preset (DB v9). [json] is a serialized `BrushSettings`. */
@Entity(tableName = "brush_presets")
data class BrushPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val json: String,
    val createdAt: Long,
)

/** Favorites for both built-in (negative ids) and user presets. */
@Entity(tableName = "brush_favorites")
data class BrushFavoriteEntity(@PrimaryKey val presetId: Long)

@Dao
interface BrushDao {
    @Query("SELECT * FROM brush_presets ORDER BY createdAt")
    fun presets(): Flow<List<BrushPresetEntity>>

    @Query("SELECT presetId FROM brush_favorites")
    fun favorites(): Flow<List<Long>>

    @Insert
    suspend fun insert(p: BrushPresetEntity): Long

    @Insert
    suspend fun insertAll(p: List<BrushPresetEntity>): List<Long>

    @Query("UPDATE brush_presets SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE brush_presets SET json = :json WHERE id = :id")
    suspend fun setSettings(id: Long, json: String)

    @Query("DELETE FROM brush_presets WHERE id = :id")
    suspend fun deletePreset(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(f: BrushFavoriteEntity)

    @Query("DELETE FROM brush_favorites WHERE presetId = :id")
    suspend fun removeFavorite(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deletePreset(id)
        removeFavorite(id)
    }
}
