@file:Suppress("ktlint:standard:max-line-length") // Tool descriptions are user-facing prose; keep them on one line.

package app.croptop.core.agent

import app.croptop.core.engine.api.CommandResult
import java.io.File
import app.croptop.core.model.ToggleParam
import app.croptop.core.model.ChoiceParam
import app.croptop.core.model.NumberParam
import app.croptop.core.model.SelectionOp
import app.croptop.core.model.SelectionShape
import app.croptop.core.model.ParamValue
import app.croptop.core.model.DocRectF
import app.croptop.core.model.AdjustmentInfo
import app.croptop.core.model.AdjustmentCatalog
import app.croptop.core.engine.api.ExportTarget
import app.croptop.core.engine.api.ExportOptions
import app.croptop.core.engine.api.ExportFormat
import app.croptop.core.engine.api.DocumentHandle
import app.croptop.core.engine.api.EditCommand
import app.croptop.core.engine.api.LayerProperties
import app.croptop.core.model.BlendMode
import app.croptop.core.model.DocSize
import app.croptop.core.model.LayerId
import app.croptop.core.model.LayerKind
import app.croptop.core.model.LayerNode
import app.croptop.core.model.NewDocumentSpec
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One MCP tool: JSON-schema'd input, text result. Errors are reported to the agent, not thrown. */
class AgentTool(val name: String, val description: String, val inputSchema: JsonObject, val run: suspend (JsonObject) -> String)

class ToolError(message: String) : Exception(message)

private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("additionalProperties", false)
}

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun enumProp(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
}

/**
 * Tools over the current engine-api. Every edit goes through EditCommand, so it lands
 * in the document's history and the user can undo it. Grows with the contract (schema export).
 */
class AgentTools(private val docs: OpenDocuments, private val exports: ExportGate? = null) {
    /** PNG bytes a tool wants returned as an MCP image next to its text; consumed by [call]. */
    private var pendingImage: ByteArray? = null

    private fun doc(args: JsonObject): DocumentHandle {
        val id = args["document_id"]?.jsonPrimitive?.long
        return (if (id != null) docs.byId(id) else docs.active.value)
            ?: throw ToolError("No document is open in CropTop. Ask the user to open one, or call new_document.")
    }

    private fun layer(h: DocumentHandle, args: JsonObject): LayerNode {
        val id = args["layer_id"]?.jsonPrimitive?.long ?: throw ToolError("layer_id is required")
        return h.layers.value.find(LayerId(id)) ?: throw ToolError("No layer with id $id. Call list_layers first.")
    }

    private fun check(r: CommandResult): String = when (r) {
        is CommandResult.Accepted -> "ok"
        is CommandResult.Rejected -> throw ToolError(r.message)
    }

    private fun describe(h: DocumentHandle): String {
        val i = h.info.value
        val state = if (i.dirty) ", unsaved changes" else ""
        return "Document ${i.id.value} “${i.name}”: ${i.size.width}×${i.size.height}px, ${i.depth.label}, ${i.profile.displayName}$state"
    }

    private fun layersText(h: DocumentHandle): String {
        val tree = h.layers.value
        return tree.flatten().joinToString("\n") { n ->
            val indent = "  ".repeat(tree.depthOf(n.id))
            "$indent- id=${n.id.value} “${n.name}” ${n.kind.name.lowercase()} opacity=${(n.opacity * 100).toInt()}% blend=${n.blendMode.name.lowercase()}" +
                (if (!n.visible) " hidden" else "") + (if (n.id in tree.active) " [selected]" else "")
        }.ifBlank { "(no layers)" }
    }

    val all: List<AgentTool> = listOf(
        AgentTool("list_documents", "List documents open in CropTop. The active one is what the user is looking at.", schema()) {
            val active = docs.active.value
            docs.open.value.joinToString("\n") { (if (it == active) "* " else "  ") + describe(it) }.ifBlank { "No documents are open." }
        },
        AgentTool(
            "new_document",
            "Create a new blank document (it opens for the user when they switch to it).",
            schema(
                "name" to prop("string", "Document name"),
                "width" to prop("integer", "Width in pixels (1–30000)"),
                "height" to prop("integer", "Height in pixels (1–30000)"),
                required = listOf("width", "height"),
            ),
        ) { a ->
            val w = a["width"]?.jsonPrimitive?.int ?: throw ToolError("width is required")
            val h = a["height"]?.jsonPrimitive?.int ?: throw ToolError("height is required")
            if (w !in 1..30000 || h !in 1..30000) throw ToolError("width/height must be 1–30000")
            val handle = docs.engine.newDocument(NewDocumentSpec(name = a["name"]?.jsonPrimitive?.content ?: "Untitled", size = DocSize(w, h)))
            docs.register(handle)
            describe(handle)
        },
        AgentTool(
            "get_document",
            "Size, bit depth, color profile and save state of a document.",
            schema("document_id" to prop("integer", "Optional; defaults to the active document")),
        ) { a ->
            describe(doc(a))
        },
        AgentTool(
            "list_layers",
            "Layer stack, top first, with ids, opacity, blend mode and visibility.",
            schema("document_id" to prop("integer", "Optional")),
        ) { a ->
            layersText(doc(a))
        },
        AgentTool(
            "add_layer",
            "Add a layer on top of the stack (or into a group).",
            schema(
                "document_id" to prop("integer", "Optional"),
                "name" to prop("string", "Layer name"),
                "kind" to enumProp("Layer kind", listOf("pixel", "group", "adjustment", "fill")),
                "parent_id" to prop("integer", "Optional group id"),
            ),
        ) { a ->
            val h = doc(a)
            val kind = when (a["kind"]?.jsonPrimitive?.content) {
                "group" -> LayerKind.GROUP
                "adjustment" -> LayerKind.ADJUSTMENT
                "fill" -> LayerKind.FILL
                else -> LayerKind.PIXEL
            }
            val id = LayerId(Random.nextLong(1L shl 40, Long.MAX_VALUE))
            check(
                h.execute(
                    EditCommand.AddLayer(
                        id,
                        a["name"]?.jsonPrimitive?.content ?: "Layer",
                        kind,
                        a["parent_id"]?.jsonPrimitive?.long?.let(::LayerId),
                        0,
                    ),
                ),
            )
            "Added layer id=${id.value}"
        },
        AgentTool(
            "delete_layer",
            "Delete a layer (undoable).",
            schema(
                "document_id" to prop("integer", "Optional"),
                "layer_id" to prop("integer", "Layer id"),
                required = listOf("layer_id"),
            ),
        ) { a ->
            val h = doc(a)
            check(h.execute(EditCommand.DeleteLayer(layer(h, a).id)))
        },
        AgentTool(
            "set_layer",
            "Change a layer's name, visibility, opacity (0–100), fill (0–100) or blend mode.",
            schema(
                "document_id" to prop("integer", "Optional"),
                "layer_id" to prop("integer", "Layer id"),
                "name" to prop("string", "New name"),
                "visible" to prop("boolean", "Show or hide"),
                "opacity" to prop("number", "0–100"),
                "fill" to prop("number", "0–100"),
                "blend_mode" to enumProp("Blend mode", BlendMode.entries.map { it.name.lowercase() }),
                required = listOf("layer_id"),
            ),
        ) { a ->
            val h = doc(a)
            val n = layer(h, a)
            fun pct(k: String) = a[k]?.jsonPrimitive?.float?.let { if (it !in 0f..100f) throw ToolError("$k must be 0–100") else it / 100f }
            val blend = a["blend_mode"]?.jsonPrimitive?.content?.let { v ->
                BlendMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: throw ToolError("Unknown blend mode $v")
            }
            val props = LayerProperties(
                name = a["name"]?.jsonPrimitive?.content,
                visible = a["visible"]?.jsonPrimitive?.boolean,
                opacity = pct("opacity"),
                fillOpacity = pct("fill"),
                blendMode = blend,
            )
            check(h.execute(EditCommand.SetLayerProps(n.id, props)))
        },
        AgentTool(
            "move_layer",
            "Move a layer to a new position (index 0 = top) within a parent group or the root.",
            schema(
                "document_id" to prop("integer", "Optional"),
                "layer_id" to prop("integer", "Layer id"),
                "index" to prop("integer", "New index among siblings, 0 = top"),
                "parent_id" to prop("integer", "Optional group id; omit for root"),
                required = listOf("layer_id", "index"),
            ),
        ) { a ->
            val h = doc(a)
            check(
                h.execute(EditCommand.MoveLayer(layer(h, a).id, a["parent_id"]?.jsonPrimitive?.long?.let(::LayerId), a["index"]!!.jsonPrimitive.int)),
            )
        },
        AgentTool(
            "select",
            "Make a selection: all, none, invert, or a rectangle/ellipse in document pixels. Selections limit later adjustments and fills.",
            schema(
                "document_id" to prop("integer", "Optional"),
                "mode" to enumProp("What to select", listOf("all", "none", "invert", "rectangle", "ellipse")),
                "x" to prop("number", "Left edge (rectangle/ellipse)"),
                "y" to prop("number", "Top edge (rectangle/ellipse)"),
                "width" to prop("number", "Width (rectangle/ellipse)"),
                "height" to prop("number", "Height (rectangle/ellipse)"),
                "operation" to enumProp("How to combine with the existing selection", listOf("replace", "add", "subtract", "intersect")),
                required = listOf("mode"),
            ),
        ) { a ->
            val h = doc(a)
            when (val mode = a["mode"]?.jsonPrimitive?.content) {
                "all" -> check(h.selectAll())
                "none" -> check(h.deselect())
                "invert" -> check(h.invertSelection())
                "rectangle", "ellipse" -> {
                    fun num(k: String) = a[k]?.jsonPrimitive?.float ?: throw ToolError("$k is required for $mode")
                    val rect = DocRectF(num("x"), num("y"), num("x") + num("width"), num("y") + num("height"))
                    val shape = if (mode == "rectangle") SelectionShape.Rect(rect) else SelectionShape.Ellipse(rect)
                    val op = when (a["operation"]?.jsonPrimitive?.content) {
                        "add" -> SelectionOp.ADD
                        "subtract" -> SelectionOp.SUBTRACT
                        "intersect" -> SelectionOp.INTERSECT
                        else -> SelectionOp.REPLACE
                    }
                    check(h.select(shape, op))
                }

                else -> throw ToolError("Unknown selection mode $mode")
            }
        },
        AgentTool("list_adjustments", "The adjustments CropTool can apply, with each parameter's range and default.", schema()) {
            AdjustmentCatalog.All.joinToString("\n") { spec ->
                val params = spec.params.mapNotNull { p ->
                    when (p) {
                        is NumberParam -> "${p.key} ${p.min}..${p.max} (default ${p.default})"
                        is ChoiceParam -> "${p.key} one of ${p.options.joinToString("|") { it.value }}"
                        is ToggleParam -> "${p.key} true|false"
                        else -> null
                    }
                }
                "- ${spec.id} “${spec.name}”${if (params.isEmpty()) "" else ": " + params.joinToString(", ")}"
            }
        },
        AgentTool(
            "apply_adjustment",
            "Apply an adjustment (see list_adjustments) as one undo step, either as a new adjustment layer (non-destructive, default) or destructively to the active pixel layer. Respects the current selection when destructive.",
            schema(
                "document_id" to prop("integer", "Optional"),
                "adjustment" to prop("string", "Adjustment id, e.g. brightnessContrast"),
                "values" to prop("object", "Parameter values keyed by parameter name (numbers, strings for choices, booleans for toggles); omitted ones use defaults"),
                "mode" to enumProp("layer (default) or destructive", listOf("layer", "destructive")),
                required = listOf("adjustment"),
            ),
        ) { a ->
            val h = doc(a)
            val id = a["adjustment"]?.jsonPrimitive?.content ?: throw ToolError("adjustment is required")
            val spec = AdjustmentCatalog[id] ?: throw ToolError("Unknown adjustment $id. Call list_adjustments.")
            val given = (a["values"] as? JsonObject).orEmpty().mapNotNull { (key, v) ->
                val param = spec.params.firstOrNull { it.key == key } ?: throw ToolError("$id has no parameter $key")
                val prim = v as? JsonPrimitive ?: throw ToolError("Parameter $key must be a number, string or boolean")
                key to when (param) {
                    is NumberParam -> ParamValue.Num(prim.float)
                    is ChoiceParam -> ParamValue.Choice(prim.content)
                    is ToggleParam -> ParamValue.Bool(prim.boolean)
                    else -> throw ToolError("Parameter $key cannot be set from an agent")
                }
            }.toMap()
            val adjustment = AdjustmentInfo(id, spec.normalize(given))
            if (a["mode"]?.jsonPrimitive?.content == "destructive") {
                check(h.applyAdjustment(adjustment))
            } else {
                val layerId = LayerId(Random.nextLong(1L shl 40, Long.MAX_VALUE))
                check(h.addAdjustmentLayer(layerId, adjustment))
                "Added adjustment layer id=${layerId.value}"
            }
        },
        AgentTool(
            "get_preview",
            "A PNG preview of the whole document (merged), at most max_px on the longer side. Returned as an image.",
            schema("document_id" to prop("integer", "Optional"), "max_px" to prop("integer", "Longer side in pixels, 64–2048 (default 768)")),
        ) { a ->
            val h = doc(a)
            val maxPx = (a["max_px"]?.jsonPrimitive?.int ?: 768).coerceIn(64, 2048)
            val size = h.info.value.size
            val scale = (maxPx.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
            val dir = (exports?.directory ?: throw ToolError("Previews are not available in this build")).also { it.mkdirs() }
            val file = File.createTempFile("preview-", ".png", dir)
            try {
                val result = h.export(ExportTarget.AppFile(file.absolutePath), ExportOptions(ExportFormat.PNG, scale = scale))
                pendingImage = file.readBytes()
                "Preview ${result.width}×${result.height}px of “${h.info.value.name}”"
            } finally {
                file.delete()
            }
        },
        AgentTool(
            "export",
            "Export the document to a file in CropTop's agent-exports folder. The user may be asked to allow it first (Settings ▸ AI agents). Returns the file path and size.",
            schema(
                "document_id" to prop("integer", "Optional"),
                "format" to enumProp("File format", listOf("jpeg", "png", "webp", "avif")),
                "quality" to prop("integer", "1–100 for lossy formats (default 90)"),
                "scale_percent" to prop("integer", "1–400 (default 100)"),
                "name" to prop("string", "File name without extension (default: the document name)"),
                required = listOf("format"),
            ),
        ) { a ->
            val gate = exports ?: throw ToolError("Export is not available in this build")
            val h = doc(a)
            val format = when (a["format"]?.jsonPrimitive?.content?.lowercase()) {
                "jpeg", "jpg" -> ExportFormat.JPEG
                "png" -> ExportFormat.PNG
                "webp" -> ExportFormat.WEBP
                "avif" -> ExportFormat.AVIF
                else -> throw ToolError("format must be jpeg, png, webp or avif")
            }
            val quality = (a["quality"]?.jsonPrimitive?.int ?: 90).coerceIn(1, 100)
            val scale = (a["scale_percent"]?.jsonPrimitive?.int ?: 100).coerceIn(1, 400) / 100f
            val base = (a["name"]?.jsonPrimitive?.content ?: h.info.value.name).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).ifBlank { "export" }
            val extension = when (format) {
                ExportFormat.JPEG -> "jpg"
                ExportFormat.PNG -> "png"
                ExportFormat.WEBP -> "webp"
                else -> "avif"
            }
            if (!gate.confirm("An agent wants to export “${h.info.value.name}” as $base.$extension")) throw ToolError("The user did not allow this export")
            gate.directory.mkdirs()
            val file = File(gate.directory, "$base.$extension")
            val result = h.export(ExportTarget.AppFile(file.absolutePath), ExportOptions(format, quality, scale))
            "Exported ${result.width}×${result.height}px to ${file.absolutePath} (${result.bytesWritten} bytes)" +
                (if (result.warnings.isEmpty()) "" else "\nNotes: " + result.warnings.joinToString("; "))
        },
        AgentTool("undo", "Undo the last step in the document.", schema("document_id" to prop("integer", "Optional"))) { a -> check(doc(a).undo()) },
        AgentTool("redo", "Redo the last undone step.", schema("document_id" to prop("integer", "Optional"))) { a -> check(doc(a).redo()) },
        AgentTool(
            "get_history",
            "The document's history steps; the current step is marked.",
            schema("document_id" to prop("integer", "Optional")),
        ) { a ->
            val hs = doc(a).history.value
            hs.entries.mapIndexed { i, e -> (if (i == hs.current) "* " else "  ") + "$i ${e.label}" }.joinToString("\n")
        },
    )

    fun toolsJson(): JsonArray = buildJsonArray {
        all.forEach { t ->
            add(
                buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                },
            )
        }
    }

    /** The image the last [call] produced (consumed once), for tools such as get_preview. */
    fun takeImage(): ByteArray? = pendingImage.also { pendingImage = null }

    suspend fun call(name: String, args: JsonObject): Pair<String, Boolean> {
        pendingImage = null
        val tool = all.firstOrNull { it.name == name } ?: return "Unknown tool $name" to true
        return try {
            tool.run(args) to false
        } catch (e: ToolError) {
            (e.message ?: "Error") to true
        } catch (e: IllegalArgumentException) {
            (e.message ?: "Invalid argument") to true
        } catch (e: NullPointerException) {
            "Missing a required argument" to true
        }
    }
}
