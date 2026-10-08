package app.croptop.core.model

import kotlinx.serialization.Serializable

/**
 * Pixels a crop kept outside the canvas ("Delete Cropped Pixels" off): the layer's full pixels live in an immutable
 * source ([sourceKey], [sourceWidth]×[sourceHeight], saved in `.croptop`) whose top-left sits at ([offsetX], [offsetY])
 * in document pixels. The layer's canvas pixels stay authoritative; Canvas Size, a later crop and Move reveal the rest.
 */
@Serializable
data class HiddenPixelsInfo(
    val sourceKey: String,
    val offsetX: Int,
    val offsetY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)
