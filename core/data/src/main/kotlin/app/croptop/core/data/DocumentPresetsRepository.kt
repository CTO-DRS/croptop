package app.croptop.core.data

import app.croptop.core.data.db.DocumentPresetDao
import app.croptop.core.data.db.DocumentPresetEntity
import app.croptop.core.model.BackgroundContents
import app.croptop.core.model.ColorDepth
import app.croptop.core.model.ColorProfileRef
import app.croptop.core.model.DocSize
import app.croptop.core.model.NewDocumentSpec
import app.croptop.core.model.WorkingSpace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-saved New Document presets. The preset name is stored as the spec name. */
interface DocumentPresetsRepository {
    val presets: Flow<List<NewDocumentSpec>>

    suspend fun save(spec: NewDocumentSpec, now: Long = System.currentTimeMillis())

    suspend fun delete(name: String)
}

@Singleton
class RoomDocumentPresetsRepository @Inject constructor(private val dao: DocumentPresetDao) : DocumentPresetsRepository {
    override val presets: Flow<List<NewDocumentSpec>> = dao.observe().map { rows ->
        rows.map { e ->
            NewDocumentSpec(
                name = e.name,
                size = DocSize(e.width, e.height),
                resolutionPpi = e.ppi,
                depth = enumValues<ColorDepth>().firstOrNull { it.name == e.depth } ?: ColorDepth.U8,
                profile = ColorProfileRef.BuiltIn(enumValues<WorkingSpace>().firstOrNull { it.name == e.profile } ?: WorkingSpace.DISPLAY_P3),
                background = enumValues<BackgroundContents>().firstOrNull { it.name == e.background } ?: BackgroundContents.WHITE,
            )
        }
    }

    override suspend fun save(spec: NewDocumentSpec, now: Long) {
        val space = (spec.profile as? ColorProfileRef.BuiltIn)?.space ?: WorkingSpace.DISPLAY_P3
        dao.upsert(
            DocumentPresetEntity(
                spec.name,
                spec.size.width,
                spec.size.height,
                spec.resolutionPpi,
                spec.depth.name,
                space.name,
                spec.background.name,
                now,
            ),
        )
    }

    override suspend fun delete(name: String) = dao.delete(name)
}
