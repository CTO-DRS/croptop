package app.croptop.core.data

import app.croptop.core.model.ColorGradient
import app.croptop.core.model.GradientPreset
import kotlinx.coroutines.flow.Flow

/** Stores user-authored gradient presets independently of the document database. */
interface GradientRepository {
    val customPresets: Flow<List<GradientPreset>>

    suspend fun save(name: String, gradient: ColorGradient): GradientPreset

    suspend fun update(id: Long, name: String, gradient: ColorGradient): Boolean

    suspend fun delete(id: Long): Boolean
}
