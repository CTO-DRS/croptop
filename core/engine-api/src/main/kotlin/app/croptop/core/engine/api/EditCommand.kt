package app.croptop.core.engine.api

import app.croptop.core.model.BlendMode
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import kotlinx.serialization.Serializable

/** Every document mutation is represented as data so history, replay and macros share one path. */
@Serializable
sealed interface EditCommand {
    @Serializable
    data class AddLayer(
        val id: LayerId,
        val name: String,
        val kind: LayerKind,
        val parentId: LayerId? = null,
        val index: Int = 0,
    ) : EditCommand

    @Serializable
    data class DeleteLayer(val id: LayerId) : EditCommand

    @Serializable
    data class MoveLayer(val id: LayerId, val parentId: LayerId? = null, val index: Int) : EditCommand

    @Serializable
    data class SetLayerProps(val id: LayerId, val properties: LayerProperties) : EditCommand

    @Serializable
    data class SetBlendMode(val id: LayerId, val blendMode: BlendMode) : EditCommand

    @Serializable
    data object Undo : EditCommand

    @Serializable
    data object Redo : EditCommand
}

/** Accepted means queued; later completion is reflected in document state flows. */
@Serializable
sealed interface CommandResult {
    @Serializable
    data class Accepted(val sequence: Long) : CommandResult

    @Serializable
    data class Rejected(val code: RejectionCode, val message: String) : CommandResult
}

@Serializable
enum class RejectionCode { CLOSED, INVALID_ARGUMENT, NOT_FOUND, UNSUPPORTED, QUEUE_FULL, CANCELLED }
