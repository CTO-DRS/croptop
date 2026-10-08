package app.croptop.feature.editor

import androidx.navigation3.runtime.NavKey
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.NewDocumentSpec
import kotlinx.serialization.Serializable

/**
 * Opens [sourceUri] (content:// from SAF, VIEW or SEND intents), creates [newSpec], or reopens crash-recovery
 * data [recoveryId] ([recoveryName] titles the tab).
 */
@Serializable
data class EditorRoute(
    val sourceUri: String? = null,
    /** Develop recipe passed through when opening a RAW from Develop. */
    val rawDevelopSettings: DevelopSettings? = null,
    /** Opens a RAW as a smart object (Develop settings stay re-editable) instead of flattened pixels. */
    val rawAsSmartObject: Boolean = false,
    val newSpec: NewDocumentSpec? = null,
    val recoveryId: String? = null,
    val recoveryName: String? = null,
    /** Existing in-memory document opened as a new editor tab (smart-object contents). */
    val openDocumentId: Long? = null,
    val smartObjectParentDocumentId: Long? = null,
    val smartObjectParentLayerId: Long? = null,
) : NavKey

/** UI summary for one editor route in the app's open-document tab strip. */
data class EditorDocumentTab(
    val route: EditorRoute,
    val title: String,
    val dirty: Boolean,
)
