package app.croptop.app.agent

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import app.croptop.core.agent.AgentExports
import app.croptop.core.agent.AgentTools
import app.croptop.core.agent.OpenDocuments
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Result returned to the calling agent. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CropTopResult(
    /** True when the change was applied. */
    val success: Boolean,
    /** Human-readable outcome to relay to the user. */
    val message: String,
)

/**
 * Android AppFunctions for CropTop. Same behavior as the MCP tools: every edit is an
 * undoable history step in the document the user has open.
 */
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(serviceName = "CropTopAppFunctions", appFunctionXmlFileName = "croptop_app_functions")
abstract class BaseCropTopAppFunctions : AppFunctionService() {
    @Inject internal lateinit var docs: OpenDocuments

    @Inject internal lateinit var exports: AgentExports

    private val tools by lazy { AgentTools(docs, exports) }

    private suspend fun run(tool: String, vararg args: Pair<String, Any>): CropTopResult = withContext(Dispatchers.Main.immediate) {
        val json = JsonObject(
            args.associate { (k, v) ->
                k to when (v) {
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    is JsonObject -> v
                    is Map<*, *> -> JsonObject(
                        v.entries.associate { (mk, mv) ->
                            val number = mv.toString().toDoubleOrNull()
                            mk.toString() to (if (number != null) JsonPrimitive(number) else JsonPrimitive(mv.toString()))
                        },
                    )
                    else -> JsonPrimitive(v.toString())
                }
            },
        )
        val (text, isError) = tools.call(tool, json)
        CropTopResult(success = !isError, message = text)
    }

    /** Lists the layers of the photo currently open in CropTop, top first, with their ids. */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun listLayers(): CropTopResult = run("list_layers")

    /**
     * Adds a new empty layer on top of the open photo.
     * @param name Name for the new layer.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun addLayer(name: String): CropTopResult = run("add_layer", "name" to name)

    /**
     * Sets a layer's opacity.
     * @param layerId Id of the layer (from listLayers).
     * @param opacityPercent Opacity from 0 to 100.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun setLayerOpacity(layerId: Long, opacityPercent: Int): CropTopResult =
        run("set_layer", "layer_id" to layerId, "opacity" to opacityPercent)

    /**
     * Shows or hides a layer.
     * @param layerId Id of the layer (from listLayers).
     * @param visible True to show, false to hide.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun setLayerVisible(layerId: Long, visible: Boolean): CropTopResult =
        run("set_layer", "layer_id" to layerId, "visible" to visible)

    /**
     * Makes the open photo warmer or cooler with a Photo Filter adjustment layer.
     * @param warmer True for a warming filter, false for a cooling filter.
     * @param amountPercent How strong the effect is, from 1 to 100.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun adjustWarmth(warmer: Boolean, amountPercent: Int): CropTopResult = run(
        "apply_adjustment",
        "adjustment" to "photoFilter",
        "values" to mapOf("filter" to if (warmer) "warming85" else "cooling80", "density" to amountPercent.coerceIn(1, 100)),
    )

    /**
     * Applies a brightness, contrast or exposure style adjustment to the open photo as an adjustment layer.
     * @param adjustmentId One of: brightnessContrast, exposure, vibrance, hueSaturation, shadowsHighlights, photoFilter.
     * @param valuesJson A JSON object with the adjustment's parameter values, for example {"brightness": 20, "contrast": 10}.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun applyAdjustment(adjustmentId: String, valuesJson: String): CropTopResult {
        val values = runCatching { Json.parseToJsonElement(valuesJson) as? JsonObject }.getOrNull()
            ?: return CropTopResult(false, "valuesJson must be a JSON object such as {\"brightness\": 20}")
        return run("apply_adjustment", "adjustment" to adjustmentId, "values" to values)
    }

    /**
     * Selects part of the open photo (or clears the selection) for later edits.
     * @param mode One of: all, none, invert, rectangle, ellipse.
     * @param x Left edge in pixels (rectangle or ellipse).
     * @param y Top edge in pixels (rectangle or ellipse).
     * @param width Width in pixels (rectangle or ellipse).
     * @param height Height in pixels (rectangle or ellipse).
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun select(mode: String, x: Int?, y: Int?, width: Int?, height: Int?): CropTopResult = run(
        "select",
        *listOfNotNull("mode" to mode, x?.let { "x" to it }, y?.let { "y" to it }, width?.let { "width" to it }, height?.let { "height" to it }).toTypedArray(),
    )

    /**
     * Exports the open photo for Instagram: a JPEG scaled so its longer side is at most 1350 pixels. The user may be asked to allow it.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun exportForInstagram(): CropTopResult {
        val size = docs.active.value?.info?.value?.size ?: return CropTopResult(false, "No photo is open in CropTop.")
        val scale = (1350.0 * 100.0 / maxOf(size.width, size.height)).toInt().coerceIn(1, 100)
        return run("export", "format" to "jpeg", "quality" to 90, "scale_percent" to scale, "name" to "instagram")
    }

    /**
     * Exports the open photo to a file in CropTop's export folder. The user may be asked to allow it.
     * @param format One of: jpeg, png, webp, avif.
     * @param quality Quality from 1 to 100 for lossy formats.
     */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun exportPhoto(format: String, quality: Int): CropTopResult = run("export", "format" to format, "quality" to quality)

    /** Undoes the last change in the open photo. */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun undo(): CropTopResult = run("undo")

    /** Redoes the last undone change in the open photo. */
    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun redo(): CropTopResult = run("redo")
}
