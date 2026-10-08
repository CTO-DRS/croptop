package app.croptop.core.engine.api

import app.croptop.core.model.LayerId
import app.croptop.core.model.Matrix3

/** What an interactive transform moves (Move drag, Free Transform, transform selection). */
sealed interface TransformTarget {
    data class Layers(val ids: Set<LayerId>) : TransformTarget {
        init {
            require(ids.isNotEmpty()) { "Transform needs at least one layer" }
        }
    }

    /** The selection outline only (Select ▸ Transform Selection). */
    data object Selection : TransformTarget
}

/**
 * Interactive transform. [update] replaces the preview matrix (document space, relative
 * to the state when the session began) and adds no history; [commit] applies it as one undoable
 * step; [cancel] restores the original. Calls after commit/cancel are ignored.
 */
interface TransformSession {
    fun update(matrix: Matrix3)

    fun commit(): CommandResult

    fun cancel()
}

/** Default session for engines that don't transform yet: every commit reports why. */
class UnsupportedTransformSession(private val what: String) : TransformSession {
    override fun update(matrix: Matrix3) = Unit

    override fun commit(): CommandResult = unsupported(what)

    override fun cancel() = Unit
}

/** Typed rejection for v3 calls an engine hasn't implemented yet. */
fun unsupported(what: String): CommandResult = CommandResult.Rejected(RejectionCode.UNSUPPORTED, "$what isn't available in this build yet.")
