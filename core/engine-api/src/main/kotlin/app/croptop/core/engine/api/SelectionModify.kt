package app.croptop.core.engine.api

import app.croptop.core.model.SelectionModify

/**
 * Interactive selection modification.
 *
 * [update] previews Expand/Contract/Border/Smooth/Feather with [px] without adding history;
 * [commit] applies the modification as one undoable history step;
 * [cancel] restores the original selection from before the session began.
 */
interface SelectionModifySession {
    /** Previews the modification with [px] on [DocumentHandle.selection] without creating a history step. */
    fun update(px: Float): CommandResult

    /** Commits the modification as one undoable history step. */
    fun commit(): CommandResult

    /** Cancels the session and restores the selection to what it was when the session began. */
    fun cancel()
}

/** Default session for engines that don't support interactive selection modification: reports why. */
class UnsupportedSelectionModifySession(private val label: String = "Modify Selection") : SelectionModifySession {
    override fun update(px: Float): CommandResult = unsupported(label)

    override fun commit(): CommandResult = unsupported(label)

    override fun cancel() = Unit
}
